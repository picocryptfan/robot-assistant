// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkMode
import app.heyrobot.core.link.GroupAnswer
import app.heyrobot.core.link.GroupLimits
import app.heyrobot.core.link.GroupMember
import app.heyrobot.core.link.GroupRoster
import app.heyrobot.core.link.GroupSeen
import app.heyrobot.core.link.GroupSeenPlan
import app.heyrobot.core.link.GroupStanding
import app.heyrobot.core.link.GroupState
import app.heyrobot.core.link.GroupTimes
import app.heyrobot.core.link.GroupsView
import app.heyrobot.core.link.LinkGroup
import app.heyrobot.core.link.LinkLimits
import app.heyrobot.core.link.LinkOrder
import app.heyrobot.core.link.LinkPlan
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.PlanState
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.FbAsk
import app.heyrobot.core.link.requests.FbReply
import app.heyrobot.core.link.requests.GrpPerson
import app.heyrobot.core.link.requests.GrpStatus
import app.heyrobot.core.link.requests.GrpWho
import app.heyrobot.core.link.requests.Span
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.RequestCaps
import app.heyrobot.core.link.requests.RequestType

/**
 * 1.5, group plans (§4.1; L11), on the link thread. The organizer's Robot is the hub: every person is paired with it and with no one
 * else in the group, and has their own plain plan with it, so each stands only by what their own Robot said on their own plan.
 *
 *  - A busy reply counts once, from that Robot, answering the request asked of that Robot, about that window.
 *  - Who's in comes from each person's own plan with you, and a pairing that isn't active is out. Nothing another Robot says moves it.
 *  - The other side: what the organizer says of who's in is kept only from the Robot you have that group's plan with, and only while
 *    you have it. It's their word, shown as that, with no way to the model, your calendar, People or your pairings.
 */
class GroupPlans(
    private val st: LinkStateStore,
    private val cal: DeskCalendar,
    events: EventsHandler,
    freeBusy: FreeBusyHandler,
) : DeskHandler {
    override val types = setOf(RequestType.GRP_STATUS)

    init {
        events.onChange = ::changed
        freeBusy.onReply = ::reply
    }

    private fun save(g: LinkGroup): Boolean = try { st.put(g); true } catch (_: LinkStateException) { false }

    private fun active(pairing: String) = st.pairings[pairing]?.state == PairingState.ACTIVE

    private fun planOf(m: GroupMember): LinkPlan? = m.plan?.let { id -> st.plans.values.firstOrNull { it.id == id && it.pairing == m.pairing && it.mine } }

    // Organizing -------------------------------------------------------------------------------------------------------------

    /** Your new group: every person's Robot is asked when it's busy. Its id, or null when it isn't one the row allows. */
    fun start(g: LinkGroup, out: DeskOut): String? {
        if (!g.valid() || st.groups.size >= GroupLimits.MAX_GROUPS || g.id in st.groups || !g.members.all { active(it.pairing) }) return null
        for (m in g.members) {
            m.ask = out.send(m.pairing, FbAsk(g.from, g.to))
            m.answer = if (m.ask == null) GroupAnswer.WONT_SAY else GroupAnswer.ASKED
        }
        return g.id.takeIf { save(g) }
    }

    private fun reply(pairing: String, b: FbReply) {
        val g = st.groups.values.firstOrNull { x -> x.state == GroupState.ASKING && x.members.any { it.pairing == pairing && it.ask == b.re } } ?: return
        val m = g.members.first { it.pairing == pairing }
        if (m.answer != GroupAnswer.ASKED || b.from != g.from || b.to != g.to) return
        m.busy = GroupTimes.mask(g.from, g.to, b.busy)
        m.answer = GroupAnswer.KNOWN
        save(g)
    }

    /** Their Robot said no to being asked, or could never be reached: the common times go without them. */
    override fun unlisted(pairing: String, requestId: String, state: AckState?) {
        if (state != null && state != AckState.DECLINED && state != AckState.EXPIRED) return
        val g = st.groups.values.firstOrNull { x -> x.state == GroupState.ASKING && x.members.any { it.pairing == pairing && it.ask == requestId } } ?: return
        val m = g.members.first { it.pairing == pairing }
        if (m.answer == GroupAnswer.ASKED) { m.answer = GroupAnswer.WONT_SAY; save(g) }
    }

    /** Common free times so far: those who answered, and your own calendar. */
    fun times(g: LinkGroup, now: Long): List<Long> = times(g, now, cal.busy(g.from, g.to))

    private fun times(g: LinkGroup, now: Long, busy: List<Span>?): List<Long> {
        val own = busy?.let { GroupTimes.mask(g.from, g.to, it) }
        return GroupTimes.common(g.from, g.to, g.minutes, g.members.mapNotNull { it.busy } + listOfNotNull(own), cal.zone(), now)
    }

    /** You picked [time]: everyone's own `ev.propose`, one time, the group's id. False when none of it could go. */
    fun propose(id: String, time: Long, via: (String, EventFields) -> String?, out: DeskOut): Boolean {
        val g = st.groups[id]?.takeIf { it.state == GroupState.ASKING } ?: return false
        if (time % 60 != 0L || time < g.from || time + g.minutes * 60 > g.to || time <= out.now()) return false
        val event = EventFields(g.title, listOf(time), g.minutes, g.place, g.note, g.id)
        for (m in g.members) m.plan = if (active(m.pairing)) via(m.pairing, event) else null
        if (g.members.all { it.plan == null }) return false
        g.state = GroupState.PROPOSED; g.time = time; g.done = out.now()
        g.members.forEach { it.busy = null } // Used: nobody's busy times stay longer than they're needed.
        tell(g, out)
        return save(g)
    }

    /** You cancel for everyone: each person's plan, as any plan, and your one calendar event. */
    fun cancel(id: String, via: (String, String) -> Boolean): Boolean {
        val g = st.groups[id]?.takeIf { it.state != GroupState.CANCELLED } ?: return false
        g.state = GroupState.CANCELLED; g.done = System.currentTimeMillis() / 1000
        for (m in g.members) m.plan?.let { via(m.pairing, it) }
        if (g.cal > 0) { cal.remove(g.cal); g.cal = 0 }
        g.members.forEach { it.busy = null }
        return save(g)
    }

    /** An answer on one of your plans. Yours only, and the plan must be that person's in that group. */
    private fun changed(p: LinkPlan, out: DeskOut) {
        val id = p.event.grp?.takeIf { p.mine } ?: return
        val g = st.groups[id]?.takeIf { it.state == GroupState.PROPOSED } ?: return
        if (g.members.none { it.pairing == p.pairing && it.plan == p.id }) return
        val anyone = g.members.any { m -> planOf(m)?.state == PlanState.ACCEPTED && active(m.pairing) }
        if (anyone && g.cal == 0L) g.cal = cal.add(g.title, g.place, g.time, g.minutes) ?: 0
        if (!anyone && g.cal > 0) { cal.remove(g.cal); g.cal = 0 }
        tell(g, out)
        save(g)
    }

    internal fun tellForTest(id: String, out: DeskOut) { st.groups[id]?.let { tell(it, out); save(it) } }

    /** Who's in by name, and how many aren't or haven't answered, to everyone still in it, each without themselves. */
    private fun tell(g: LinkGroup, out: DeskOut) {
        g.rev = (g.rev + 1).coerceAtMost(RequestCaps.MAX_REV)
        val who = g.members.map { m -> m to GroupRoster.who(false, planOf(m), active(m.pairing)) }
        // L16: the name each asked to be shown, never yours for them in People. Someone on an Anonymous pairing isn't named at all.
        fun shown(m: GroupMember) = st.pairings[m.pairing]?.takeIf { it.mode != LinkMode.ANONYMOUS }?.name.orEmpty()
        for ((m, w) in who) {
            if (w == GrpWho.OUT) continue
            val rest = who.filter { it.first !== m }
            val named = rest.filter { it.second == GrpWho.IN && shown(it.first).isNotEmpty() }.map { GrpPerson(shown(it.first), GrpWho.IN) }
            out.send(m.pairing, GrpStatus(g.id, g.rev, named, rest.count { it.second == GrpWho.OUT }, rest.count { it.second == GrpWho.WAITING }))
        }
    }

    // The organizer's word, on the other side --------------------------------------------------------------------------------

    private fun theirPlan(pairing: String, grp: String) = st.plans.values.firstOrNull {
        !it.mine && it.pairing == pairing && it.event.grp == grp && (it.state == PlanState.PROPOSED || it.state == PlanState.ACCEPTED)
    }

    override fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState {
        val b = r.body as? GrpStatus ?: return AckState.RECEIVED
        if (theirPlan(pairing, b.grp) == null) return AckState.DECLINED
        val cur = st.groupSeen[GroupSeen.key(pairing, b.grp)]
        if (cur != null && (b.rev <= cur.rev || b.rev > cur.rev + LinkOrder.MAX_JUMP)) return AckState.RECEIVED
        return try { st.put(GroupSeen(b.grp, pairing, b.rev, r.id, out.now(), b.who, b.out, b.waiting)); AckState.RECEIVED } catch (_: LinkStateException) { AckState.BUSY }
    }

    // Keeping ----------------------------------------------------------------------------------------------------------------

    override fun prune(now: Long) {
        val keep = GroupLimits.KEEP_DAYS * LinkLimits.DAY
        for (g in st.groups.values.toList()) {
            val over = when (g.state) {
                GroupState.ASKING -> g.to + keep
                GroupState.PROPOSED -> g.time + g.minutes * 60 + keep
                GroupState.CANCELLED -> g.done + keep
            }
            if (now > over) try { st.deleteGroup(g.id) } catch (_: LinkStateException) {}
        }
        for (s in st.groupSeen.values.toList()) if (theirPlan(s.pairing, s.grp) == null) try { st.deleteSeen(s) } catch (_: LinkStateException) {}
    }

    /** Everything for the screens, without anyone's busy times (LinkService's GROUPS, unlocked). */
    fun view(now: Long): GroupsView = GroupsView(
        st.groups.values.sortedByDescending { it.at }.map { g ->
            val asking = g.state == GroupState.ASKING
            val own = if (asking) cal.busy(g.from, g.to) else null
            g.light() to GroupStanding(g.members.map { GroupRoster.who(asking, planOf(it), active(it.pairing)) }, if (asking) times(g, now, own) else emptyList(), !asking || own != null)
        },
        st.groupSeen.values.mapNotNull { s -> theirPlan(s.pairing, s.grp)?.let { GroupSeenPlan(s, it.event.title, if (it.time > 0) it.time else it.event.times.first()) } },
    )
}
