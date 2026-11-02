// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.DeskCardOut
import app.heyrobot.core.link.DeskChoice
import app.heyrobot.core.link.DeskEntry
import app.heyrobot.core.link.DeskNotice
import app.heyrobot.core.link.DeskNoticeKind
import app.heyrobot.core.link.DeskNotices
import app.heyrobot.core.link.DeskProposal
import app.heyrobot.core.link.DeskRequest
import app.heyrobot.core.link.DeskRules
import app.heyrobot.core.link.DeskState
import app.heyrobot.core.link.DeskView
import app.heyrobot.core.link.DeskWay
import app.heyrobot.core.link.DeskYes
import app.heyrobot.core.link.Flow
import app.heyrobot.core.link.Handling
import app.heyrobot.core.link.LinkCapability
import app.heyrobot.core.link.LinkContext
import app.heyrobot.core.link.LinkIds
import app.heyrobot.core.link.LinkLevel
import app.heyrobot.core.link.LinkLevels
import app.heyrobot.core.link.LinkPolicy
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.LinkStateException
import app.heyrobot.core.link.LinkStateProblem
import app.heyrobot.core.link.Waiting
import app.heyrobot.core.link.requests.AckState
import app.heyrobot.core.link.requests.FileOffer
import app.heyrobot.core.link.requests.LinkBody
import app.heyrobot.core.link.requests.OfferWhy
import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1 to §4.4, §4.6, §6.4, D14, Q3; job L6): the desk, the node's
// sink. Every request another Robot sends that passed the node's checks comes here, on the link thread, and is decided by
// your level for that person and capability (core LinkPolicy.decide), the phone's state (locked, a phone lock) and Q3's
// "Answer while my phone is locked":
//
//   - Off: answered `declined`; nothing kept.
//   - Answer on its own: its handler answers it (free/busy, an event in your hours, a reminder, an ETA: one per type, from the
//     job that builds it) and it is listed in "Robot handled 3 things" with Undo where the handler can undo it; a handler
//     that can't (outside the limits that stay), or a type no job has built yet, makes it a card instead.
//   - Ask me first (and everything waiting for an unlock): a card. It waits sealed in the waiting inbox (robot_inbox_v1,
//     which only an unlocked phone opens) and is answered `waiting`; :voice is pinged (no content crosses) and shows it in
//     the panel or as a notification; the requests screen lists it. Your Yes runs its handler and sends `handled`; Decline
//     sends `declined`. A card past its request's lifetime goes, listed as expired.
//   - Answers to your own requests and updates to things you have: their type's handler, else just received.
//
// After an unlock (:voice asks for the view), the waiting inbox is opened: each item read by :linkparse again (the node's
// decoder; nothing here reads JSON), decided again as unlocked (so what waited only for the unlock is answered on its own
// now), and the rest kept in memory as cards until answered. Nothing here shows anything or logs a request: codes only.

/** What the desk may send (the node's, on the link thread). */
interface DeskOut {
    /** An ack for request [re] from [pairing]; the queued ack's id, or null. */
    fun ack(pairing: String, re: String, state: AckState): String?
    /** A body of the desk's own (a handler's answer) for [pairing]; the queued request's id, or null. */
    fun send(pairing: String, body: LinkBody, ttl: Long = LinkRequests.DEFAULT_TTL): String?
    /** Unix seconds. */
    fun now(): Long
    /** Whether an answer to [pairing] can go now, its ack and one body: asked before anything of a card's is done. */
    fun room(pairing: String): Boolean = true
}

/** The phone, as the desk needs it. */
interface DeskEnv {
    /** The phone is locked now (unknown counts as locked). */
    fun locked(): Boolean
    /** A phone lock is on (PhoneLockNow): no card opens. */
    fun phoneLock(): Boolean
    /** link_settings.json (Q3's "Answer while my phone is locked"). */
    fun settings(): LinkSettings
    /** Tells :voice something changed on the desk (an explicit broadcast with no content). */
    fun ping()
}

/** Where cards wait: the waiting inbox (core LinkInbox, robot_inbox_v1). */
interface DeskBox {
    /** Sealed now (works while locked); its id. Throws LinkStateException (FULL, TOO_BIG, a key's problem). */
    fun put(pairing: String, at: Long, request: ByteArray): String
    fun ids(): List<String>
    fun count(): Int
    /** Opened (the phone unlocked); null when it's gone. Throws LinkStateException (LOCKED, DAMAGED, ...). */
    fun open(id: String): Waiting?
    fun delete(id: String)
}

/** What a handler did: the ack to send, its token to undo it when it can be undone, and [nothing]: your yes changed nothing (F144). */
class DeskDone(val ack: AckState = AckState.HANDLED, val undo: String? = null, val nothing: Boolean = false) {
    override fun toString(): String = "DeskDone($ack)" // l10n: not text (toString for logs and tests)
}

/**
 * One capability's answers, from the job that builds them (L7: free/busy and events; L8: files; L9: splits; L10: reminders,
 * contact cards, questions; L11: folders and lists). Called on the link thread, so quick or handed off; never shows anything.
 */
interface DeskHandler {
    val types: Set<RequestType>

    /** Answers [r] from [pairing] on its own now ([locked]): null when it can't (outside the limits that stay): a card. */
    fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): DeskDone? = null

    /** What the card's yes does, for [r]: null for none (the card offers Decline only). */
    fun yes(r: LinkRequest): DeskYes? = null

    /** Your yes on [r]'s card (your tap, unlocked): null when it couldn't be done (the card stays). */
    fun answer(pairing: String, r: LinkRequest, out: DeskOut): DeskDone? = null

    /**
     * L7: your yes on [r]'s card picking [option] (DeskChoice.option: 0 the handler's pick, 1 to 3 a plan's first to third
     * time). By default the plain [answer].
     */
    fun answerAt(pairing: String, r: LinkRequest, option: Int, out: DeskOut): DeskDone? = answer(pairing, r, out)

    /** L10: your typed words (or a note's) as the answer to [r] (a question); null when it takes none or couldn't send. */
    fun answerWith(pairing: String, r: LinkRequest, text: String, out: DeskOut): DeskDone? = null

    /** L8: a file offer [offerId], already on its way to [pairing], as the answer to [r] (a question); null when it takes none. */
    fun answerWithFile(pairing: String, r: LinkRequest, offerId: String, out: DeskOut): DeskDone? = null

    /** L7: you declined [r]'s card (its `declined` ack goes anyway): what else the type sends (a plan's `ev.decline`). */
    fun declined(pairing: String, r: LinkRequest, out: DeskOut) {}

    /** L7: [r]'s card has nothing left to answer (a plan cancelled while it waited): it goes, listed as expired. */
    fun stale(pairing: String, r: LinkRequest): Boolean = false

    /** Undo of something it did on its own ([token], its own): true when undone. */
    fun undo(pairing: String, token: String, out: DeskOut): Boolean = false

    /** An answer to your own request, or an update to something you have (core Flow.ANSWER, UPDATE, SYNC): its ack. */
    fun code(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut): AckState = AckState.RECEIVED

    /**
     * L7: called right after [code] on the same request (the link thread): whether it answered something of yours, so it is
     * kept as a reply line (DeskWay.REPLY) and :voice is told. An answer to nothing you asked is never kept.
     */
    fun kept(pairing: String, r: LinkRequest): Boolean = false

    /** L7: their Robot's ack [state] for request [requestId] of [type] you sent (or the handler sent) to [pairing]. */
    fun acked(pairing: String, requestId: String, type: RequestType, state: AckState) {}

    /** L7: what it keeps of its own goes when old (the desk's prune). */
    fun prune(now: Long) {}

    /**
     * 1.5: a request that would make a card, before it does: an ack when it took it some other way (a part of something
     * bigger; [card] makes the usual card when it's time), null for the usual card now.
     */
    fun hold(pairing: String, r: LinkRequest, out: DeskOut, card: () -> AckState): AckState? = null

    /** 1.5: an ack ([state]; null: the outbox gave up) that may be for something it sent that isn't on the desk's lists. */
    fun unlisted(pairing: String, requestId: String, state: AckState?) {}
}

/** ETA (§4.1): "Yes: just show it". Nothing to undo, nothing to send but the ack. */
object EtaHandler : DeskHandler {
    override val types = setOf(RequestType.ETA_TELL)
    override fun alone(pairing: String, r: LinkRequest, locked: Boolean, out: DeskOut) = DeskDone()
    override fun yes(r: LinkRequest) = DeskYes.GOT_IT
    override fun answer(pairing: String, r: LinkRequest, out: DeskOut) = DeskDone()
}

class LinkDesk(
    private val st: LinkStateStore,
    private val box: DeskBox,
    private val env: DeskEnv,
    private val decoder: RequestDecoder,
    handlers: List<DeskHandler> = listOf(EtaHandler),
) : LinkSink {
    private val byType = HashMap<RequestType, DeskHandler>().apply { for (h in handlers) for (t in h.types) require(put(t, h) == null) }
    @Volatile private var out: DeskOut? = null

    /** A card read from the inbox: its pairing, when it arrived, its request. */
    private class Held(val pairing: String, val received: Long, val request: LinkRequest)
    private val cards = LinkedHashMap<String, Held>()
    /** F73: items in the inbox per pairing that no card here stands for: what came since it was last opened, and at the start all the last run counted. */
    private val unopened = HashMap(st.inboxShare)
    /** Requests of entries read this run (an entry keeps only the plaintext). */
    private val read = HashMap<String, LinkRequest>()
    private val notices = ArrayList<DeskNotice>()

    /** The node's sending side (LinkNode.deskOut), once the node exists. */
    fun attach(o: DeskOut) { out = o }

    private fun now(): Long = out?.now() ?: (System.currentTimeMillis() / 1000)

    private fun handler(t: RequestType): DeskHandler? = byType[t]

    /** 1.5: the shared lists' handler, for LinkService. */
    val lists: ListsShareHandler? get() = handler(RequestType.LS_OP) as? ListsShareHandler

    /** L11: shared folders and group plans, for LinkService. */
    val folders: SharedFolders? get() = handler(RequestType.FOLD_OP) as? SharedFolders
    val groups: GroupPlans? get() = handler(RequestType.GRP_STATUS) as? GroupPlans

    /** Out to their Robots, for LinkService's calls to those two on the link thread. */
    val deskOut: DeskOut? get() = out

    // The sink ------------------------------------------------------------------------------------------------------------

    override fun delivered(pairing: String, request: LinkRequest): AckState {
        val o = out ?: return AckState.BUSY
        val p = st.pairings[pairing] ?: return AckState.RECEIVED
        val t = request.type
        val locked = env.locked()
        // L11: a file for a shared folder never asks: it's under the folder's rules, or it's nothing. Never a card, never Files' level.
        (request.body as? FileOffer)?.takeIf { it.why == OfferWhy.FOLDER }?.let { offer ->
            if ((p.levels[LinkCapability.FOLDERS] ?: LinkLevel.OFF) == LinkLevel.OFF) return AckState.DECLINED
            if (env.phoneLock()) return AckState.BUSY
            return folders?.offer(pairing, offer, o) ?: AckState.DECLINED
        }
        when (LinkPolicy.flow(t)) {
            Flow.CONTROL -> return AckState.RECEIVED
            Flow.ANSWER, Flow.UPDATE -> return answerOrUpdate(pairing, request, locked, o)
            Flow.SYNC -> {
                val cap = t.capability ?: return AckState.RECEIVED
                if ((p.levels[cap] ?: LinkLevel.OFF) == LinkLevel.OFF) return AckState.DECLINED
                return handler(t)?.code(pairing, request, locked, o) ?: AckState.RECEIVED
            }
            Flow.ASK, Flow.ALWAYS_CARD -> {}
        }
        val level = t.capability?.let { p.levels[it] } ?: LinkLevel.OFF
        return when (decide(t, level, locked)) {
            Handling.DECLINE -> AckState.DECLINED
            Handling.ANSWER_ALONE -> alone(pairing, request, locked, o) ?: wait(pairing, request)
            Handling.CODE -> AckState.RECEIVED
            // L8: a file accepted on its own while locked comes in sealed for Files' next unlock; past its limits, a card.
            Handling.RECEIVE_AND_SEAL -> alone(pairing, request, locked, o) ?: wait(pairing, request)
            Handling.CARD, Handling.WAIT -> handler(t)?.hold(pairing, request, o) { wait(pairing, request) } ?: wait(pairing, request)
        }
    }

    /**
     * L7: an answer to your request or a change to something you have: its handler's code; when the handler says it answered
     * something of yours, it is kept as a reply line (its plaintext 7 days) and :voice is told (REPLIED, no content).
     */
    private fun answerOrUpdate(pairing: String, r: LinkRequest, locked: Boolean, o: DeskOut): AckState {
        val h = handler(r.type) ?: return AckState.RECEIVED
        val ack = try { h.code(pairing, r, locked, o) } catch (_: RuntimeException) { return AckState.RECEIVED }
        val keep = try { h.kept(pairing, r) } catch (_: RuntimeException) { false }
        if (keep && st.desk[r.id] == null) {
            val now = now()
            record(DeskEntry(r.id, pairing, DeskWay.REPLY, r.type.code, DeskState.REPLIED, now, now, null, false, LinkRequests.wire(r)), r)
            tell(DeskNotice(DeskNoticeKind.REPLIED, pairing, 1))
        }
        return ack
    }

    private fun decide(t: RequestType, level: LinkLevel, locked: Boolean): Handling {
        val s = env.settings()
        return LinkPolicy.decide(t, level, LinkContext(locked = locked, answerWhileLocked = s.whileLocked, phoneLock = env.phoneLock()))
    }

    /** Answered on its own by its handler: listed as handled, :voice told. Null when it couldn't (a card instead). */
    private fun alone(pairing: String, r: LinkRequest, locked: Boolean, o: DeskOut): AckState? {
        val d = try { handler(r.type)?.alone(pairing, r, locked, o) } catch (_: RuntimeException) { null } ?: return null
        val now = now()
        val undo = d.undo?.takeIf { it.length in 1..DeskEntry.MAX_UNDO && it.all { c -> c in '!'..'~' } }
        record(DeskEntry(r.id, pairing, DeskWay.IN, r.type.code, DeskState.HANDLED, now, now, undo, false, LinkRequests.wire(r)), r)
        tell(DeskNotice(DeskNoticeKind.HANDLED, pairing, 1))
        return d.ack
    }

    /** A card: sealed in the waiting inbox (read only unlocked), :voice told. Busy when the inbox can't take it. */
    private fun wait(pairing: String, r: LinkRequest): AckState {
        // F73: the inbox says who sent what only once it's open: the cards opened, and what came since, kept in the state so a restart while locked doesn't start over.
        val waiting = cards.values.count { it.pairing == pairing } + (unopened[pairing] ?: 0)
        if (waiting >= app.heyrobot.core.link.LinkLimits.INBOX_PER_PERSON) return AckState.BUSY
        val before = unopened[pairing] ?: 0
        unopened[pairing] = before + 1
        saveShare() // first: a crash after it counts one item too many until the next unlock, never one too few
        try {
            box.put(pairing, now(), LinkRequests.wire(r))
        } catch (_: LinkStateException) {
            if (before == 0) unopened.remove(pairing) else unopened[pairing] = before
            saveShare()
            return AckState.BUSY
        }
        tell(DeskNotice(DeskNoticeKind.CARDS, pairing, 1))
        return AckState.WAITING
    }

    override fun queued(pairing: String, type: RequestType, id: String, at: Long) {
        if (!LinkIds.isId(id)) return
        val now = now()
        record(DeskEntry(id, pairing, DeskWay.OUT, type.code, DeskState.SENT, at.coerceAtLeast(0), now), null)
    }

    override fun acked(pairing: String, requestId: String, state: AckState) {
        unlisted(pairing, requestId, state)
        val e = st.desk[requestId]?.takeIf { it.way == DeskWay.OUT && it.pairing == pairing } ?: return
        RequestType.ofCode(e.type)?.let { t -> try { handler(t)?.acked(pairing, requestId, t, state) } catch (_: RuntimeException) {} }
        val next = DeskRules.afterAck(e.state, state) ?: return
        e.state = next; e.done = now()
        save(e)
    }

    private fun unlisted(pairing: String, requestId: String, state: AckState?) {
        for (t in listOf(RequestType.LS_OP, RequestType.FOLD_OP, RequestType.GRP_STATUS)) try { handler(t)?.unlisted(pairing, requestId, state) } catch (_: RuntimeException) {}
    }

    override fun gaveUp(pairing: String, requestId: String) {
        unlisted(pairing, requestId, null)
        st.desk[requestId]?.takeIf { it.way == DeskWay.OUT && it.pairing == pairing }?.let { e ->
            e.state = DeskState.COULDNT_REACH; e.done = now(); save(e)
            tell(DeskNotice(DeskNoticeKind.GAVE_UP, pairing))
        }
    }

    override fun paired(pairing: String) = tell(DeskNotice(DeskNoticeKind.PAIRED, pairing))
    override fun unpaired(pairing: String) {
        cards.entries.removeAll { it.value.pairing == pairing }
        tell(DeskNotice(DeskNoticeKind.UNPAIRED, pairing))
    }
    override fun busy(pairing: String, until: Long) = tell(DeskNotice(DeskNoticeKind.BUSY, pairing, until.coerceAtLeast(0)))
    override fun raised(pairing: String) = tell(DeskNotice(DeskNoticeKind.RAISED, pairing))
    override fun lowerAsked(pairing: String) = tell(DeskNotice(DeskNoticeKind.LOWER_ASKED, pairing))
    override fun lowered(pairing: String) = tell(DeskNotice(DeskNoticeKind.LOWERED, pairing))
    override fun moved(pairing: String) = tell(DeskNotice(DeskNoticeKind.MOVED, pairing))

    /** L8: a transfer's own news for :voice. */
    fun notice(n: DeskNotice) = tell(n)

    private fun tell(n: DeskNotice) {
        synchronized(notices) {
            if (notices.size >= DeskNotices.MAX) notices.removeAt(0)
            notices += n
        }
        try { env.ping() } catch (_: RuntimeException) {}
    }

    // What LinkService asks (on the link thread) ---------------------------------------------------------------------------

    /**
     * The notices since the last call, merged per person (cards and handled counted), and how many items wait sealed in the
     * inbox. Read while locked too: no request's contents, only kinds, pairings and counts. Handled entries are marked seen.
     */
    fun notices(): DeskNotices {
        val taken = synchronized(notices) { notices.toList().also { notices.clear() } }
        val merged = LinkedHashMap<Pair<DeskNoticeKind, String>, Long>()
        for (n in taken) {
            val k = n.kind to n.pairing
            merged[k] = when (n.kind) {
                DeskNoticeKind.CARDS, DeskNoticeKind.HANDLED -> (merged[k] ?: 0) + n.value
                else -> n.value
            }
        }
        for (e in st.desk.values.filter { it.way == DeskWay.IN && it.state == DeskState.HANDLED && !it.seen }) { e.seen = true; save(e) }
        val waiting = try { box.count() } catch (_: RuntimeException) { 0 }
        return DeskNotices(waiting, merged.map { (k, v) -> DeskNotice(k.first, k.second, v) })
    }

    /**
     * Everything Requests shows, with the phone unlocked (LinkService refuses this while locked): the inbox opened first,
     * old entries pruned. Null when the phone is locked.
     */
    fun view(): DeskView? = viewBytes()?.let { DeskView.decode(it) }

    /** [view] as the IPC carries it: cards and lines with their requests as LinkParseWire frames; null while locked. */
    fun viewBytes(): ByteArray? {
        if (env.locked()) return null
        drain()
        prune()
        val now = now()
        val (shown, more) = DeskRules.shown(cards.entries.toList()) { it.value.pairing }
        val cardsOut = shown.map { (id, h) -> DeskCardOut(id, h.pairing, h.received, handler(h.request.type)?.yes(h.request)) to DeskRequest.of(h.request) }
        val lines = st.desk.values.filter { DeskRules.listed(it, now) }.sortedByDescending { maxOf(it.at, it.done) }
            .map { e -> e to requestOf(e)?.let { DeskRequest.of(it) } }
        val proposals = st.pairings.values.filter { it.state == PairingState.ACTIVE }
            .flatMap { p -> DeskRules.proposals(st.desk.values, p.id, p.levels, now).map { DeskProposal(p.id, it) } }
        return DeskView.encode(cardsOut, lines, proposals, more)
    }

    /**
     * Your answer to card [id] (your tap, unlocked): Yes runs its handler and sends `handled`; Decline sends `declined`. True
     * when it was answered (the card is gone); false when there is no such card, the phone is locked, or its yes couldn't
     * be done (it stays).
     */
    fun answer(id: String, choice: DeskChoice): Boolean {
        val o = out ?: return false
        if (env.locked() || env.phoneLock()) return false
        if (id !in cards) drain()
        val h = cards[id] ?: return false
        if (!o.room(h.pairing)) return false
        val r = h.request
        val now = now()
        val state = when (choice) {
            DeskChoice.YES, DeskChoice.FIRST, DeskChoice.SECOND, DeskChoice.THIRD -> {
                val d = try { handler(r.type)?.answerAt(h.pairing, r, choice.option, o) } catch (_: RuntimeException) { null } ?: return false
                if (o.ack(h.pairing, r.id, d.ack) == null) return false
                if (d.nothing) DeskState.ALREADY_HAVE else DeskState.ANSWERED
            }
            DeskChoice.DECLINE -> {
                if (o.ack(h.pairing, r.id, AckState.DECLINED) == null) return false
                try { handler(r.type)?.declined(h.pairing, r, o) } catch (_: RuntimeException) {}
                DeskState.DECLINED
            }
        }
        cards.remove(id)
        try { box.delete(id) } catch (_: RuntimeException) {}
        saveShare()
        record(DeskEntry(r.id, h.pairing, DeskWay.IN, r.type.code, state, h.received, now, null, true, LinkRequests.wire(r)), r)
        return true
    }

    /**
     * L10: your words [text] as the answer to card [id] (a question; your typing, unlocked): sent by its handler, never the
     * model's. True when it went (the card is gone); false otherwise (it stays).
     */
    fun answerWith(id: String, text: String): Boolean = answered(id) { h, r, o -> handler(r.type)?.answerWith(h.pairing, r, text, o) }

    /** L8: the question on card [id] answered with a file you offered; as [answerWith]. */
    fun answerWithFile(id: String, offerId: String): Boolean = answered(id) { h, r, o -> handler(r.type)?.answerWithFile(h.pairing, r, offerId, o) }

    private fun answered(id: String, how: (Held, LinkRequest, DeskOut) -> DeskDone?): Boolean {
        val o = out ?: return false
        if (env.locked() || env.phoneLock()) return false
        if (id !in cards) drain()
        val h = cards[id] ?: return false
        if (!o.room(h.pairing)) return false
        val r = h.request
        val d = try { how(h, r, o) } catch (_: RuntimeException) { null } ?: return false
        if (o.ack(h.pairing, r.id, d.ack) == null) return false
        cards.remove(id)
        try { box.delete(id) } catch (_: RuntimeException) {}
        saveShare()
        record(DeskEntry(r.id, h.pairing, DeskWay.IN, r.type.code, DeskState.ANSWERED, h.received, now(), null, true, LinkRequests.wire(r)), r)
        return true
    }

    /** Undo of entry [id], something Robot did on its own within 7 days (your tap, unlocked): true when undone. */
    fun undo(id: String): Boolean {
        val o = out ?: return false
        if (env.locked() || env.phoneLock()) return false
        val e = st.desk[id] ?: return false
        val now = now()
        if (!DeskRules.undoable(e, now)) return false
        val t = RequestType.ofCode(e.type) ?: return false
        val ok = try { handler(t)?.undo(e.pairing, e.undo!!, o) == true } catch (_: RuntimeException) { false }
        if (!ok) return false
        e.state = DeskState.UNDONE; e.undo = null; e.done = now
        save(e)
        return true
    }

    /** "Not now" on Robot's proposal for [pairing] and [cap]: not offered again for 30 days. (Turn on is LinkService's SET_LEVEL.) */
    fun notNow(pairing: String, cap: LinkCapability): Boolean {
        if (st.pairings[pairing] == null) return false
        val now = now()
        record(DeskEntry(LinkIds.newId(), pairing, DeskWay.PROPOSAL, LinkLevels.code(cap), DeskState.NOT_NOW, now, now), null)
        return true
    }

    // L7: what you send and keep (LinkService's PLAN_SEND, PLAN_CANCEL, PLANS and DESK_SEEN) ------------------------------------

    private val events: EventsHandler? get() = byType[RequestType.EV_PROPOSE] as? EventsHandler

    /**
     * Your plan proposed to [pairing]'s Robot (unlocked, no phone lock): kept (LinkPlan, so its answer finds it) and listed
     * under Sent. The plan's id, or null when it couldn't go.
     */
    fun propose(pairing: String, event: app.heyrobot.core.link.requests.EventFields): String? {
        val o = out ?: return null
        if (env.locked() || env.phoneLock()) return null
        val (plan, req) = events?.propose(pairing, event, o) ?: return null
        queued(pairing, RequestType.EV_PROPOSE, req, now())
        return plan
    }

    /** Your cancel of plan [id] with [pairing]'s Robot (unlocked, no phone lock): off your calendar, their Robot told. */
    fun cancelPlan(pairing: String, id: String): Boolean {
        val o = out ?: return false
        if (env.locked() || env.phoneLock()) return false
        val req = events?.cancel(pairing, id, o) ?: return false
        queued(pairing, RequestType.EV_CANCEL, req, now())
        return true
    }

    /** L11: what you do from your screens, with the sending side: only unlocked, and never with the phone lock on. */
    fun <T> mine(act: (DeskOut) -> T?): T? {
        val o = out ?: return null
        return if (env.locked() || env.phoneLock()) null else act(o)
    }

    /** L11: group plan [id] proposed to everyone, at [time], each through [propose] as any plan (unlocked, no phone lock). */
    fun proposeGroup(id: String, time: Long): Boolean {
        val o = out ?: return false
        if (env.locked() || env.phoneLock()) return false
        return groups?.propose(id, time, ::propose, o) == true
    }

    /** L11: group plan [id] cancelled for everyone (unlocked, no phone lock). */
    fun cancelGroup(id: String): Boolean {
        if (out == null || env.locked() || env.phoneLock()) return false
        return groups?.cancel(id, ::cancelPlan) == true
    }

    /** Every plan kept (unlocked: LinkService refuses it while locked). */
    fun plans(): List<app.heyrobot.core.link.LinkPlan> = if (env.locked()) emptyList() else events?.plans().orEmpty()

    /** Reply line [id] (L10: or a request handled on its own) was said by :voice: not announced again. */
    fun seen(id: String): Boolean {
        val e = st.desk[id]?.takeIf { it.way == DeskWay.REPLY || (it.way == DeskWay.IN && it.state == DeskState.HANDLED) } ?: return false
        if (!e.seen) { e.seen = true; save(e) }
        return true
    }

    /** How many cards wait (the inbox's items, counted while locked too). */
    fun waiting(): Int = try { box.count() } catch (_: RuntimeException) { 0 }

    // The inbox and the entries --------------------------------------------------------------------------------------------

    /**
     * Opens what waits in the inbox (the phone unlocked): each read by :linkparse again, decided again as unlocked, and kept
     * as a card unless it is answered on its own now, declined (its level went to Off), expired or from a pairing that ended.
     * A pairing paused by a move keeps its items in the inbox, off the page, until it's back. The sender dropped each item when
     * it was told it waits here, so a read that couldn't happen stops the pass and keeps them all.
     */
    fun drain() {
        if (env.locked()) return
        val o = out ?: return
        val now = now()
        for ((id, h) in cards.entries.toList()) {
            val state = st.pairings[h.pairing]?.state
            if (state == null || state == PairingState.ENDED) { cards.remove(id); delete(id) }
            else if (state != PairingState.ACTIVE) cards.remove(id)
            else if (DeskRules.expired(h.request, now) || stale(h.pairing, h.request)) { cards.remove(id); delete(id); expired(h.pairing, h.request, h.received) }
        }
        val ids = try { box.ids() } catch (_: RuntimeException) { return }
        for (id in ids) {
            if (id in cards) continue
            val w: Waiting = try {
                box.open(id) ?: continue
            } catch (x: LinkStateException) {
                if (x.problem == LinkStateProblem.LOCKED) return
                // A key's problem, or an item that doesn't read: it can't be answered, so it goes.
                if (x.problem == LinkStateProblem.DAMAGED) delete(id)
                continue
            }
            val p = st.pairings[w.pairing]
            if (p == null || p.state == PairingState.ENDED) { delete(id); continue }
            if (p.state != PairingState.ACTIVE) continue
            val read = decoder.stored(w.request, LinkRequests.LIVE) ?: return
            val r = (read as? RequestRead.Ok)?.request
            if (r == null) { delete(id); continue }
            // L16: one card per request: a crash before the replay window was saved lets the same request in twice, and the
            // inbox is sealed while locked, so this is the first moment the two can be told apart.
            if (cards.values.any { it.pairing == w.pairing && it.request.id == r.id }) { delete(id); continue }
            if (DeskRules.expired(r, now) || stale(w.pairing, r)) { delete(id); expired(w.pairing, r, w.receivedAt); continue }
            val level = r.type.capability?.let { p.levels[it] } ?: LinkLevel.OFF
            when (decide(r.type, level, locked = false)) {
                Handling.DECLINE -> { o.ack(w.pairing, r.id, AckState.DECLINED); delete(id) }
                Handling.ANSWER_ALONE -> {
                    val ack = alone(w.pairing, r, false, o)
                    if (ack != null) { o.ack(w.pairing, r.id, ack); delete(id) } else cards[id] = Held(w.pairing, w.receivedAt, r)
                }
                else -> cards[id] = Held(w.pairing, w.receivedAt, r)
            }
        }
        unopened.clear()
        saveShare()
    }

    /**
     * F73: the count per person into the state, when it changed. A write that fails loses nothing but what a restart would
     * remember, and refuses no one: the inbox's total cap still holds.
     */
    private fun saveShare() {
        val share = HashMap(unopened)
        for (h in cards.values) share[h.pairing] = (share[h.pairing] ?: 0) + 1
        share.keys.retainAll(st.pairings.keys)
        if (share == st.inboxShare) return
        try { st.putInboxShare(share) } catch (_: LinkStateException) {}
    }

    private fun stale(pairing: String, r: LinkRequest): Boolean = try { handler(r.type)?.stale(pairing, r) == true } catch (_: RuntimeException) { false }

    private fun expired(pairing: String, r: LinkRequest, received: Long) =
        record(DeskEntry(r.id, pairing, DeskWay.IN, r.type.code, DeskState.EXPIRED, received.coerceAtLeast(0), now(), null, true), null)

    private fun delete(id: String) { try { box.delete(id) } catch (_: RuntimeException) {} }

    /** An entry written (the oldest go past [DeskRules.MAX_ENTRIES]). */
    private fun record(e: DeskEntry, r: LinkRequest?) {
        if (r != null) read[e.id] = r
        if (st.desk.size >= DeskRules.MAX_ENTRIES && !st.desk.containsKey(e.id)) {
            st.desk.values.minByOrNull { maxOf(it.at, it.done) }?.let { old -> try { st.deleteDesk(old.id) } catch (_: LinkStateException) {}; read.remove(old.id) }
        }
        save(e)
    }

    private fun save(e: DeskEntry) { try { st.put(e) } catch (_: LinkStateException) {} }

    /** Entries past 30 days go; an incoming one's plaintext after 7. L7: and the handlers' own records (plans) when old. */
    fun prune() {
        val now = now()
        for (h in byType.values.toSet()) try { h.prune(now) } catch (_: RuntimeException) {}
        for (e in st.desk.values.toList()) {
            if (!DeskRules.keep(e, now)) { try { st.deleteDesk(e.id) } catch (_: LinkStateException) {}; read.remove(e.id) }
            else if (DeskRules.trim(e, now)) { e.plain = null; read.remove(e.id); save(e) }
        }
    }

    /** [e]'s request while its plaintext is kept: read this run, or read again by :linkparse. */
    private fun requestOf(e: DeskEntry): LinkRequest? {
        val plain = e.plain ?: return null
        read[e.id]?.let { return it }
        val r = (try { decoder.decode(plain, LinkRequests.LIVE) } catch (_: Exception) { null } as? RequestRead.Ok)?.request ?: return null
        if (r.id != e.id || r.type.code != e.type) return null
        read[e.id] = r
        return r
    }
}
