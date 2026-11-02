// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.EventAlone
import app.heyrobot.core.link.FreeBusy
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.PlanRules
import app.heyrobot.core.link.PlanState
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EvAccept
import app.heyrobot.core.link.requests.EvCancel
import app.heyrobot.core.link.requests.EvChange
import app.heyrobot.core.link.requests.EvDecline
import app.heyrobot.core.link.requests.EvPropose
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestType
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.DeskWay
import java.time.ZoneId

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, §4.2, §4.4, D18, Q3; job L7): the desk's handlers for free/busy
// and plans, on the link thread, over your calendar as [DeskCalendar] gives it (app/link's LinkCalendar on the phone). Each is
// what L6's desk calls when your level says Answer on its own, when you answer a card, and when another Robot answers or
// changes something of yours. What goes back is built by code from your calendar and your choices, never by a model (D11).
//
//   Free/busy  `fb.ask`: busy blocks only, on 30-minute steps from the window's start, never a title, a place or who, never
//              outside the window (core FreeBusy.blocks), from the calendars you chose. The calendar reader loads nothing
//              but each event's times, all-day flag, availability and calendar (ProcessIsolationTest pins its projection).
//              `fb.reply`: kept for you only when it answers an `fb.ask` you sent that Robot.
//   Plans      `ev.propose`: on its own only inside your working hours, when you're free, at most 3 a day from one person
//              (core EventAlone, D18), added to your calendar and listed with Undo, which takes it off and tells them
//              (`ev.cancel`). A card's yes adds it at the time you picked (or the first you're free at); Decline sends
//              `ev.decline`. `ev.change` moves a plan you have (a card, or on its own within the same limits).
//              `ev.accept` of your proposal adds it to your calendar; `ev.decline` and `ev.cancel` end it, and a cancel
//              takes off exactly the event Robot added for that plan. Every change carries the plan's counter: an older
//              one arriving late is ignored (LinkOrder).

/** Your calendar as the desk's handlers use it. Every call quick; null or false when it can't (no permission, no calendar). */
interface DeskCalendar {
    /** Your time zone (working hours and all-day events are read in it). */
    fun zone(): ZoneId

    /**
     * When you're busy between [from] and [to] (Unix seconds) in the calendars free/busy may read: each event's start and
     * end only (an all-day event as its whole day in [zone]); null when the calendar can't be read.
     */
    fun busy(from: Long, to: Long): List<Span>?

    /** A plan added to the calendar Robot adds events to: its calendar id, or null. */
    fun add(title: String, place: String?, start: Long, minutes: Long): Long?

    /** The event Robot added ([id]) moved to [start]. */
    fun move(id: Long, start: Long, minutes: Long): Boolean

    /** The event Robot added ([id]) taken off your calendar. */
    fun remove(id: Long): Boolean
}

/** Free/busy (§4.1): busy blocks only. */
class FreeBusyHandler(private val st: LinkStateStore, private val cal: DeskCalendar) : DeskHandler {
    override val types = setOf(RequestType.FB_ASK, RequestType.FB_REPLY)

    /** L11: every busy reply, for a group plan's asks (GroupPlans holds them to the Robot and the request they answer). */
    var onReply: (String, FbReply) -> Unit = { _, _ -> }

    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? {
        val ask = r.body as? FbAsk ?: return null
        return if (ask.from < out.now() - LinkLimits.FB_ALONE_LOOK_BACK) null else reply(pairing, r, out)
    }

    override fun yes(r: LinkRequest) = if (r.body is FbAsk) DeskYes.SEND_BUSY else null

    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? = reply(pairing, r, out)

    /** The busy blocks of the window asked, sent back; null when the calendar can't be read or nothing could be queued. */
    private fun reply(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? {
        val ask = r.body as? FbAsk ?: return null
        val busy = cal.busy(ask.from, ask.to) ?: return null
        val blocks = FreeBusy.blocks(ask.from, ask.to, busy)
        out.send(pairing, FbReply(r.id, ask.from, ask.to, blocks)) ?: return null
        return DeskDone()
    }

    private var keep = false

    /** An answer counts only when it answers an `fb.ask` you sent that Robot (its line under Sent). */
    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState {
        val b = r.body as? FbReply
        b?.let { onReply(pairing, it) }
        keep = b != null && st.desk[b.re]?.let { it.way == DeskWay.OUT && it.pairing == pairing && it.type == RequestType.FB_ASK.code } == true &&
            st.desk.values.none { it.way == DeskWay.REPLY && it.pairing == pairing && it.type == RequestType.FB_REPLY.code && it.id == r.id }
        return AckState.RECEIVED
    }

    override fun kept(pairing: String, r: LinkRequest): Boolean = keep.also { keep = false }
}

/** Plans and events (§4.1, D18). */
class EventsHandler(
    private val st: LinkStateStore,
    private val cal: DeskCalendar,
    private val settings: () -> LinkSettings,
) : DeskHandler {
    override val types = setOf(RequestType.EV_PROPOSE, RequestType.EV_ACCEPT, RequestType.EV_DECLINE, RequestType.EV_CHANGE, RequestType.EV_CANCEL)

    /** L11: an answer changed plan [LinkPlan] (GroupPlans: who's in, for one that's part of a group). */
    var onChange: (LinkPlan, DeskOut) -> Unit = { _, _ -> }

    private fun plan(pairing: String, id: String): LinkPlan? = PlanRules.of(st.plans, pairing, id)

    private fun save(p: LinkPlan): Boolean = try { st.put(p); true } catch (_: app.heyrobot.core.link.LinkStateException) { false }

    // Their proposal, and their changes --------------------------------------------------------------------------------------

    /** Answer on its own: a new plan only, within your hours, when free, 3 a day from them. A change is always your card. */
    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? {
        val now = out.now()
        val b = r.body as? EvPropose ?: return null
        if (plan(pairing, b.ev) != null) return null
        val t = pick(pairing, b.event, now) ?: return null
        return accept(pairing, r.id, b.ev, b.rev, b.event, t, now, out, alone = true)?.let { DeskDone(AckState.HANDLED, undo = b.ev) }
    }

    override fun yes(r: LinkRequest) = when (r.body) {
        is EvPropose -> DeskYes.ADD_EVENT
        is EvChange -> DeskYes.MOVE_EVENT
        else -> null
    }

    /** Your yes: at the time you picked ([option] 1 to 3), else the first one still to come you're free at, else the first still to come. Never a time that has passed. */
    override fun answerAt(pairing: String, r: LinkRequest, option: Int, out: DeskOut): DeskDone? {
        val now = out.now()
        val (ev, rev, event) = when (val b = r.body) {
            is EvPropose -> Triple(b.ev, b.rev, b.event)
            is EvChange -> Triple(b.ev, b.rev, b.event)
            else -> return null
        }
        val p = plan(pairing, ev)
        if (p != null && (p.mine || p.state == PlanState.CANCELLED || !PlanRules.wins(p, rev, r.id))) return null
        val t = when {
            option in 1..event.times.size -> event.times[option - 1].takeIf { it > now } ?: return null
            option == 0 -> {
                val future = event.times.filter { it > now }
                if (future.isEmpty()) return null
                firstFree(future, event.minutes, p) ?: future.first()
            }
            else -> return null
        }
        val done = if (p != null && p.state == PlanState.ACCEPTED) move(p, r.id, rev, event, t, now, out)
        else accept(pairing, r.id, ev, rev, event, t, now, out, alone = false, over = p)
        return done?.let { DeskDone(AckState.HANDLED) }
    }

    override fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? = answerAt(pairing, r, 0, out)

    /** You declined their proposal or change: their Robot is told (`ev.decline`), and a plan you had stays as it was. */
    override fun declined(pairing: String, r: LinkRequest, out: DeskOut) {
        val now = out.now()
        when (val b = r.body) {
            is EvPropose -> {
                if (plan(pairing, b.ev) != null) return
                out.send(pairing, EvDecline(b.ev, b.rev))
                save(LinkPlan(b.ev, pairing, false, PlanState.DECLINED, b.rev, r.id, b.event, now, now))
            }
            is EvChange -> out.send(pairing, EvDecline(b.ev, b.rev))
            else -> {}
        }
    }

    /** A waiting card for a plan cancelled (or answered another way) since: nothing left to answer. */
    override fun stale(pairing: String, r: LinkRequest): Boolean {
        val (ev, rev) = when (val b = r.body) {
            is EvPropose -> b.ev to b.rev
            is EvChange -> b.ev to b.rev
            else -> return false
        }
        val p = plan(pairing, ev) ?: return false
        return p.mine || p.state == PlanState.CANCELLED || p.state == PlanState.DECLINED || !PlanRules.wins(p, rev, r.id)
    }

    /** Undo of a plan Robot accepted on its own: off your calendar, cancelled, and their Robot told (`ev.cancel`). */
    override fun undo(pairing: String, token: String, out: DeskOut): Boolean {
        val p = plan(pairing, token)?.takeIf { !it.mine && it.state == PlanState.ACCEPTED && it.aloneAt > 0 } ?: return false
        return cancel(p, out) != null
    }

    // Answers to your proposals, and changes to a plan you have --------------------------------------------------------------

    private var keep = false

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState {
        keep = false
        val now = out.now()
        when (val b = r.body) {
            is EvAccept -> {
                // Your proposal, at one of its times and its current version: into your calendar.
                val p = plan(pairing, b.ev)?.takeIf { it.mine && it.rev == b.rev && b.time in it.event.times &&
                    (it.state == PlanState.PROPOSED || it.state == PlanState.ACCEPTED) } ?: return AckState.RECEIVED
                if (p.state == PlanState.ACCEPTED && p.time == b.time) return AckState.RECEIVED
                if (p.event.grp == null) { // A group plan is in your calendar once, however many say yes: GroupPlans adds it.
                    if (p.cal > 0) { if (!cal.move(p.cal, b.time, p.event.minutes)) p.cal = 0 }
                    if (p.cal == 0L) p.cal = cal.add(p.event.title, p.event.place, b.time, p.event.minutes) ?: 0
                }
                p.state = PlanState.ACCEPTED; p.time = b.time; p.done = now
                save(p)
                onChange(p, out)
                keep = true
                return AckState.HANDLED
            }
            is EvDecline -> {
                val p = plan(pairing, b.ev)?.takeIf { it.mine && it.rev == b.rev && it.state != PlanState.CANCELLED } ?: return AckState.RECEIVED
                if (p.state == PlanState.DECLINED) return AckState.RECEIVED
                if (p.cal > 0) { cal.remove(p.cal); p.cal = 0 }
                p.state = PlanState.DECLINED; p.done = now
                save(p)
                onChange(p, out)
                keep = true
                return AckState.HANDLED
            }
            is EvCancel -> {
                val p = plan(pairing, b.ev)
                if (p == null) {
                    // A plan whose card still waits (or never came): a cancelled record, so that card has nothing left to answer.
                    if (st.plans.values.count { it.pairing == pairing } < PlanRules.PER_PAIRING) {
                        save(LinkPlan(b.ev, pairing, false, PlanState.CANCELLED, b.rev, r.id, PLACEHOLDER, now, now))
                    }
                    return AckState.HANDLED
                }
                if (p.state == PlanState.CANCELLED) return AckState.RECEIVED
                if (p.cal > 0) { cal.remove(p.cal); p.cal = 0 }
                val shown = p.state == PlanState.ACCEPTED || p.mine
                p.state = PlanState.CANCELLED; p.done = now
                if (PlanRules.wins(p, b.rev, r.id)) { p.rev = b.rev; p.req = r.id }
                save(p)
                onChange(p, out)
                keep = shown
                return AckState.HANDLED
            }
            else -> return AckState.RECEIVED
        }
    }

    override fun kept(pairing: String, r: LinkRequest): Boolean = keep.also { keep = false }

    /** Their Robot declined your proposal by its level (Off) rather than a card: the plan ends. */
    override fun acked(pairing: String, requestId: String, type: RequestType, state: AckState) {
        if (type != RequestType.EV_PROPOSE || state != AckState.DECLINED) return
        st.plans.values.firstOrNull { it.mine && it.pairing == pairing && it.sent == requestId && it.state == PlanState.PROPOSED }?.let { p ->
            p.state = PlanState.DECLINED; p.done = System.currentTimeMillis() / 1000
            save(p)
        }
    }

    override fun prune(now: Long) {
        for (p in st.plans.values.toList()) if (PlanRules.drop(p, now)) try { st.deletePlan(p) } catch (_: app.heyrobot.core.link.LinkStateException) {}
    }

    // Yours -----------------------------------------------------------------------------------------------------------------

    /** Your plan proposed to [pairing]'s Robot: the plan's id and the request's, or null when it couldn't be queued. */
    fun propose(pairing: String, event: EventFields, out: DeskOut): Pair<String, String>? {
        if (st.plans.values.count { it.pairing == pairing } >= PlanRules.PER_PAIRING) return null
        val now = out.now()
        val ev = LinkIds.newId()
        val req = out.send(pairing, EvPropose(ev, 1, event)) ?: return null
        val p = LinkPlan(ev, pairing, true, PlanState.PROPOSED, 1, req, event, now, now, sent = req)
        if (!save(p)) return null
        return ev to req
    }

    /** Your cancel of plan [id] with [pairing]'s Robot: off your calendar, and their Robot told. The cancel's request id, or null. */
    fun cancel(pairing: String, id: String, out: DeskOut): String? {
        val p = plan(pairing, id)?.takeIf { it.state == PlanState.PROPOSED || it.state == PlanState.ACCEPTED } ?: return null
        return cancel(p, out)
    }

    private fun cancel(p: LinkPlan, out: DeskOut): String? {
        val rev = (p.rev + 1).coerceAtMost(RequestCaps.MAX_REV)
        val req = out.send(p.pairing, EvCancel(p.id, rev)) ?: return null
        if (p.cal > 0) { cal.remove(p.cal); p.cal = 0 }
        p.state = PlanState.CANCELLED; p.rev = rev; p.req = req; p.done = out.now()
        save(p)
        onChange(p, out)
        return req
    }

    /** Every plan kept, soonest first (LinkService's PLANS, unlocked). */
    fun plans(): List<LinkPlan> = st.plans.values.sortedBy { if (it.time > 0) it.time else it.event.times.first() }

    // Helpers -----------------------------------------------------------------------------------------------------------------

    /** D18's pick on its own: in your hours, free, under 3 a day from them. */
    private fun pick(pairing: String, e: EventFields, now: Long): Long? {
        val future = e.times.filter { it > now }
        if (future.isEmpty()) return null
        val busy = busyAround(future, e.minutes, null) ?: return null
        return EventAlone.pick(future, e.minutes, cal.zone(), settings().hours, busy, PlanRules.aloneLastDay(st.plans.values, pairing, now))
    }

    /** Your yes with no time picked: the first of the plan's times you're free at (your tap: no working hours, no daily count). */
    private fun firstFree(times: List<Long>, minutes: Long, moving: LinkPlan?): Long? {
        val busy = busyAround(times, minutes, moving) ?: return null
        return times.firstOrNull { t -> busy.none { it.start < t + minutes * 60 && it.end > t } }
    }

    /** Each time's own span, never the stretch between them: times can be years apart. */
    private fun busyAround(times: List<Long>, minutes: Long, moving: LinkPlan?): List<Span>? {
        val busy = times.flatMap { t -> cal.busy(t, t + minutes * 60) ?: return null }.distinct()
        // The plan's own event (being moved) doesn't make its new time busy.
        val own = moving?.takeIf { it.cal > 0 && it.time > 0 }?.let { Span(it.time, it.time + it.event.minutes * 60) }
        return if (own == null) busy else busy.filter { it != own }
    }

    /** Accepts their plan at [t]: into your calendar, `ev.accept` sent, the plan kept. Null when any step failed (nothing left behind). */
    private fun accept(pairing: String, req: String, ev: String, rev: Long, e: EventFields, t: Long, now: Long, out: DeskOut, alone: Boolean, over: LinkPlan? = null): LinkPlan? {
        if (over == null && st.plans.values.count { it.pairing == pairing } >= PlanRules.PER_PAIRING) return null
        val id = cal.add(e.title, e.place, t, e.minutes) ?: return null
        if (out.send(pairing, EvAccept(ev, rev, t)) == null) { cal.remove(id); return null }
        val p = over ?: LinkPlan(ev, pairing, false, PlanState.ACCEPTED, rev, req, e, now, now)
        p.state = PlanState.ACCEPTED; p.rev = rev; p.req = req; p.event = e; p.time = t; p.cal = id; p.done = now
        if (alone) p.aloneAt = now
        save(p)
        return p
    }

    /** Moves a plan you accepted to [t] (their change): your calendar's event moved, `ev.accept` of the new version sent. */
    private fun move(p: LinkPlan, req: String, rev: Long, e: EventFields, t: Long, now: Long, out: DeskOut): LinkPlan? {
        if (out.send(p.pairing, EvAccept(p.id, rev, t)) == null) return null
        // L16: a new title or place can't be moved onto the old event: the calendar shows what the plan shows.
        if (p.cal > 0 && (e.title != p.event.title || e.place != p.event.place)) { cal.remove(p.cal); p.cal = 0 }
        if (p.cal > 0) { if (!cal.move(p.cal, t, e.minutes)) { cal.remove(p.cal); p.cal = 0 } }
        if (p.cal == 0L) p.cal = cal.add(e.title, e.place, t, e.minutes) ?: 0
        p.rev = rev; p.req = req; p.event = e; p.time = t; p.done = now
        save(p)
        return p
    }

    private companion object {
        /** What a cancelled record keeps of a plan it never saw (it is never shown, and never added to a calendar). */
        val PLACEHOLDER = EventFields("-", listOf(RequestCaps.MIN_TIME), RequestCaps.EVENT_MIN_MINUTES)
    }
}
