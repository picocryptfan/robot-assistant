// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.link.LinkRest
import app.heyrobot.core.link.requests.CardOffer
import android.content.Context
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.Read
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.LinkStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.link.LinkAsk
import app.heyrobot.core.link.LinkAskKind
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkCardText
import app.heyrobot.core.link.LinkCommand
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLockedCode
import app.heyrobot.core.link.LinkNames
import app.heyrobot.core.link.LinkOutCode
import app.heyrobot.core.link.LinkPeople
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkReady
import app.heyrobot.core.link.LinkRouter
import app.heyrobot.core.link.PairingView
import app.heyrobot.core.link.PlanRules
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.LinkClient
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.PeopleFiles
import java.time.ZoneId
import java.time.ZonedDateTime

// VOICE PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §4.3, §4.4, §4.5, D15; job L7): asking another Robot. Your words
// first (core LinkRouter, after Robot Files' router and before hands-free): a request becomes one ask_robot call of your own
// words through the turn runner (PanelController), so it has the same card, the same approval level (Ask first), the same lock
// rule (internal: never on the lock screen) and the same quarantine as the model's call; "stop answering Sam on your own" is
// done at once (tightening never waits for a tap). ask_robot's card is drawn here before it shows: whose Robot (only someone
// you're paired with and said who they are in People), exactly what goes (a window, a plan's title and times, which plan is
// cancelled), read by code (core LinkAsk), never by the model. What comes back is content (PanelLink shows it, framed).
//
// Nothing here opens a socket or reads a request's JSON: requests go to :link through LinkClient (SEND for free/busy, PLAN_SEND
// and PLAN_CANCEL for plans), and LinkService refuses all of them while the phone is locked. Codes and counts only in the logs.

object PanelLinkAsk {
    /** Words that may start a Robot to Robot phrase: anything else never costs a call to :link. */
    private val HINT = Regex("""(?i)\b(robot|free|available|busy|libre|dispo|disponible|propose|suggest|sugg[eè]re|plan|planifie|invite|cancel|annule|answer|answering|r[ée]pondre|r[ée]ponds)\b""") // l10n: not text (words Robot hears)
    private const val CACHE_MS = 60_000L

    internal class Known(val at: Long, val views: List<PairingView>, val people: List<app.heyrobot.core.Person>)
    @Volatile internal var known: Known? = null

    /** The pairings and People, read at most once a minute (a question never waits on :link twice). */
    private fun known(ctx: Context): Known? {
        val now = System.currentTimeMillis()
        known?.takeIf { now - it.at < CACHE_MS }?.let { return it }
        val c = LinkClient(ctx.applicationContext)
        val views = try { c.pairings() } finally { c.close() } ?: return null
        val people = try { PeopleFiles.store(ctx).list() } catch (_: Throwable) { emptyList() }
        return Known(now, views, people).also { known = it }
    }

    /** A pairing changed (a level, a person): read again next time. */
    fun forget() { known = null }

    private val worker by lazy { java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "robot-link-ask").apply { isDaemon = true } } } // l10n: not text (a thread's name)

    /** Who you're paired with, read again in the background (the panel showed): LinkRouter then uses it without waiting. */
    fun refresh(ctx: Context) {
        if (!LinkSettingsStore.current(ctx).on) { known = null; return }
        if (known?.let { System.currentTimeMillis() - it.at < CACHE_MS } == true) return
        worker.execute { try { known = null; known(ctx) } catch (t: Throwable) { RLog.w("link_refresh", t) } }
    }

    /** ask_robot may be described to the model: Robot to Robot on and someone paired (as last read; never waits on :link). */
    fun described(ctx: Context): Boolean = LinkSettingsStore.current(ctx).on && known?.let { LinkPeople.all(it.views, it.people).isNotEmpty() } == true

    /**
     * Your words as a Robot to Robot command, or null: on the panel's thread, so it reads only what [refresh] last read (no call
     * to :link). No hint word: null at once. Off, nobody paired or not read yet: only a Robot you named counts, and it isn't paired (rc4).
     * Null while locked or under a phone lock: whoever holds the phone learns nothing about who's paired and changes no level.
     */
    fun route(ctx: Context, q: String): LinkCommand? {
        if (!HINT.containsMatchIn(q) || Assistant.locked(ctx)) return null
        val k = if (LinkSettingsStore.current(ctx).on) known else null
        if (k != null && System.currentTimeMillis() - k.at >= CACHE_MS) refresh(ctx)
        if (k == null || LinkPeople.all(k.views, k.people).isEmpty()) return LinkRouter.named(q, ZonedDateTime.now())
        return LinkRouter.route(q, ZonedDateTime.now()) { LinkPeople.isPaired(it, k.views, k.people) }
    }

    /** What Robot says to [c], reading the pairings if it may (not on the panel's thread). */
    fun notPaired(ctx: Context, c: LinkCommand.NotPaired): Phrase {
        val on = LinkSettingsStore.current(ctx).on
        val k = if (on) try { known(ctx) } catch (_: Throwable) { null } else null
        return LinkRouter.notPairedWords(c.who, on, k?.let { LinkPeople.isPaired(c.who, it.views, it.people) })
    }

    /** [c] as ask_robot's arguments (an Ask), for the turn runner. */
    fun args(c: LinkCommand.Ask): Map<String, Any?> = linkedMapOf("person" to c.who, "request" to c.kind.wire, "details" to c.details) // l10n: not text (a tool's argument names)

    /**
     * "Stop answering Sam on your own": each capability Robot answers on its own for them back to Ask me first, at once (a
     * tightening, so a spoken word is enough; [route] only brings it here unlocked). Read fresh: a level set in the last minute
     * counts. What Robot says.
     */
    fun stopAlone(ctx: Context, c: LinkCommand.StopAlone): Phrase {
        forget()
        val k = try { known(ctx) } catch (_: Throwable) { null } ?: return LinkStrings.linkOff()
        val found = LinkPeople.find(c.who, k.views, k.people)
        if (found.isEmpty()) return LinkStrings.notPaired("name" to c.who)
        if (found.size > 1) return LinkStrings.whichOne("name" to c.who)
        val p = found.single()
        val name = LinkNames.of(p.pairing, k.people)
        val alone = p.pairing.levels.filterValues { it == LinkLevel.AUTO }.keys
        if (alone.isEmpty()) return LinkStrings.noneAlone("name" to name)
        val client = LinkClient(ctx.applicationContext)
        val ok = try { alone.all { cap: LinkCapability -> client.setLevel(p.pairing.id, cap, LinkLevel.ASK) } } finally { client.close() }
        forget()
        VoiceLog.add(VoiceEvent.LINK_LEVEL, if (ok) 1 else 0, alone.size)
        return if (ok) LinkStrings.stoppedAlone("name" to name) else LinkStrings.couldntSend("name" to name)
    }

    // ask_robot's card and run (ToolExecutor) ---------------------------------------------------------------------------------

    /** Before the card: who, and exactly what goes. Refused (with why) when it can't go now. */
    fun prepare(ctx: Context, a: Action.AskRobot, lang: Lang, eventMinutes: Int): Prepared {
        if (!LinkSettingsStore.current(ctx).on) return refused(LinkStrings.linkOff())
        if (Assistant.locked(ctx)) {
            VoiceLog.add(VoiceEvent.LINK_LOCKED, LinkLockedCode.ASK.id, 0)
            return Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LinkStrings.unlockToAsk("name" to a.person))
        }
        if (PhoneLockNow.on(ctx)) return Prepared.Refused(ActionStatus.NEEDS_UNLOCK, LinkStrings.phoneLocked())
        val kind = LinkAskKind.of(a.request) ?: return refused(LinkStrings.notYet())
        val k = known(ctx) ?: return refused(LinkStrings.linkOff())
        val found = LinkPeople.find(a.person, k.views, k.people)
        if (found.isEmpty()) return refused(LinkStrings.notPaired("name" to a.person))
        if (found.size > 1) return refused(LinkStrings.whichOne("name" to a.person))
        val p = found.single()
        val name = LinkNames.of(p.pairing, k.people)
        val now = ZonedDateTime.now()
        val zone = ZoneId.systemDefault()
        val ready = when (kind) {
            LinkAskKind.FREE_BUSY -> when (val r = LinkAsk.window(a.details, now, lang)) {
                is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
                is Read.Ok -> LinkReady(p.pairing.id, name, kind, LinkCardText.toFreeBusy(r.value, name, zone), fb = r.value)
            }
            LinkAskKind.PLAN -> when (val r = LinkAsk.plan(a.details, now, lang, eventMinutes.toLong())) {
                is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
                is Read.Ok -> LinkReady(p.pairing.id, name, kind, LinkCardText.toPlan(r.value, name, zone), plan = r.value)
            }
            LinkAskKind.CANCEL_PLAN -> {
                val c = LinkClient(ctx.applicationContext)
                val plans = try { c.plans() } finally { c.close() } ?: return refused(LinkStrings.couldntSend("name" to name))
                val ahead = PlanRules.ahead(plans, p.pairing.id, now.toEpochSecond())
                // The plan your words name; the next one only when they name none (L16).
                val plan = PlanRules.named(ahead, a.details, a.person)
                    ?: return refused(if (ahead.isEmpty()) LinkStrings.noPlan("name" to name) else LinkStrings.noSuchPlan("name" to name))
                LinkReady(p.pairing.id, name, kind, LinkCardText.toCancel(plan, name, zone), cancel = plan)
            }
            // L10: what goes is built by code from your words; the model only picked the kind.
            LinkAskKind.ETA -> when (val r = LinkAsk.eta(a.details)) {
                is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
                is Read.Ok -> LinkReady(p.pairing.id, name, kind, LinkCardText.toEta(r.value, name), body = r.value)
            }
            LinkAskKind.REMINDER -> when (val r = LinkAsk.reminder(a.details, now, lang)) {
                is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
                is Read.Ok -> LinkReady(p.pairing.id, name, kind, LinkCardText.toReminder(r.value, name, zone), body = r.value)
            }
            LinkAskKind.QUESTION -> when (val r = LinkAsk.question(a.details)) {
                is Read.Unreadable -> return Prepared.Refused(ActionStatus.INVALID, r.why)
                is Read.Ok -> LinkReady(p.pairing.id, name, kind, LinkCardText.toQuestion(r.value, name), body = r.value)
            }
            LinkAskKind.CONTACT -> {
                // The one person of your People your words name; their card without Robot links (LinkRest.card).
                val card = k.people.filter { app.heyrobot.core.People.matches(it, a.details) }.singleOrNull()?.let(LinkRest::card)
                    ?: return refused(LinkStrings.whichContact())
                LinkReady(p.pairing.id, name, kind, LinkCardText.toContact(card, name), body = CardOffer.of(card))
            }
        }
        return Prepared.Ready(a.copy(ready = ready))
    }

    private fun refused(why: Phrase) = Prepared.Refused(ActionStatus.FAILED, why)

    /** The card's Send (or the level's "just do it"): to :link, which queues it for their Robot. */
    fun run(ctx: Context, a: Action.AskRobot, lang: Lang): Executed {
        val r = a.ready ?: return Executed(ActionStatus.FAILED, LinkStrings.couldntSend("name" to a.person))
        if (Assistant.locked(ctx) || !LinkPolicy.canSend(locked = false, phoneLock = PhoneLockNow.on(ctx))) {
            VoiceLog.add(VoiceEvent.LINK_LOCKED, LinkLockedCode.SEND.id, 0)
            return Executed(ActionStatus.NEEDS_UNLOCK, LinkStrings.unlockToAsk("name" to r.name))
        }
        val c = LinkClient(ctx.applicationContext)
        try {
            val code: LinkOutCode = when (r.kind) {
                LinkAskKind.FREE_BUSY, LinkAskKind.ETA, LinkAskKind.REMINDER, LinkAskKind.QUESTION, LinkAskKind.CONTACT -> {
                    val req = LinkRequest.of(r.fb ?: r.body!!, System.currentTimeMillis() / 1000, lang.id)
                    val bytes = LinkRequests.encode(req) ?: return Executed(ActionStatus.FAILED, LinkStrings.couldntSend("name" to r.name))
                    when (c.send(r.pairing, req, bytes)) {
                        0 -> when (r.kind) {
                            LinkAskKind.ETA -> LinkOutCode.ETA; LinkAskKind.REMINDER -> LinkOutCode.REMINDER
                            LinkAskKind.QUESTION -> LinkOutCode.QUESTION; LinkAskKind.CONTACT -> LinkOutCode.CONTACT
                            else -> LinkOutCode.FREE_BUSY
                        }
                        null -> LinkOutCode.REFUSED
                        NOT_READ -> LinkOutCode.NOT_READ
                        else -> LinkOutCode.FAILED
                    }
                }
                LinkAskKind.PLAN -> if (c.propose(r.pairing, r.plan!!) != null) LinkOutCode.PLAN else LinkOutCode.FAILED
                LinkAskKind.CANCEL_PLAN -> if (c.cancelPlan(r.pairing, r.cancel!!.id)) LinkOutCode.CANCEL else LinkOutCode.FAILED
            }
            VoiceLog.add(VoiceEvent.LINK_OUT, code.id, 0)
            return when (code) {
                LinkOutCode.FREE_BUSY, LinkOutCode.PLAN, LinkOutCode.ETA, LinkOutCode.REMINDER, LinkOutCode.QUESTION, LinkOutCode.CONTACT -> Executed(ActionStatus.DONE, LinkStrings.sentTo("name" to r.name))
                LinkOutCode.CANCEL -> Executed(ActionStatus.DONE, LinkStrings.cancelledPlan("name" to r.name))
                LinkOutCode.NOT_READ -> Executed(ActionStatus.FAILED, LinkStrings.theyCantRead("name" to r.name))
                else -> Executed(ActionStatus.FAILED, LinkStrings.couldntSend("name" to r.name))
            }
        } finally {
            c.close()
        }
    }

    /** The node's QueueProblem.NOT_READ: their Robot didn't list this type (an older Robot). */
    private const val NOT_READ = 3
}
