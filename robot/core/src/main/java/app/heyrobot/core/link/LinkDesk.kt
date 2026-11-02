// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.LinkRequest
import app.heyrobot.core.link.requests.LinkRequests
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.core.link.requests.RequestType

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §4.2, §4.4, §4.6, §6.4, D14; job L6): the desk, what became of the
// requests other Robots sent you and of the ones you sent. :link keeps it (the link module's LinkDesk); :voice and main see
// it through LinkService as a [DeskView], and :voice's notifications through [DeskNotice]s.
//
//   - A card (Ask me first, or Answer on its own outside its limits) waits sealed in the waiting inbox (robot_inbox_v1,
//     core LinkInbox) until you answer it or its lifetime ends: it is read only while the phone is unlocked.
//   - What happened is a [DeskEntry] in :link's sealed state (LinkPurpose.DESK, robot_link_v1): handled by Robot (with Undo
//     for 7 days where it can be undone), answered or declined by you, expired, undone; and what you sent, with its last
//     answer (Sent, Delivered, Waiting for Sam, Answered, Declined, Couldn't reach). An incoming entry keeps the request's
//     plaintext for 7 days (the Handled list shows it), then codes only; every entry goes after 30 days. "Robot proposes the
//     switch" is decided from these entries ([DeskRules.proposes]), and a "Not now" is an entry too.
//
// What crosses to :voice and main is the request as :linkparse answered about it (LinkParseWire's reply frame) beside its
// plaintext, and the reader keeps it only when LinkParseWire.check rebuilds that plaintext exactly: nothing outside
// :linkparse ever reads a request's JSON (linkParsesOnlyInIsolation), and nothing on the way can change a request.
//
//   view     "RLD" 1 | cards u16, each: card | entries u16, each: entry | proposals u8, each: pairing 16, capability u8
//            | more u8, each: pairing 16, count u32
//   card     id 16 | pairing 16 | received u64 | yes u8 (a DeskYes code, 0: none) | frame u32 + bytes | plain u32 + bytes
//   entry    id 16 | pairing 16 | way u8 | type u16 | state u8 | at u64 | done u64 | undo u8 | seen u8 | frame u32 + bytes
//            | plain u32 + bytes (both empty when the plaintext isn't kept)
//   notices  "RLN" 1 | waiting u32 | count u8, each: kind u8 | pairing 16 | value u64
//
// Big-endian, nothing after the last field, every count and length capped, read strictly: anything else is no view.

/** Which way a request went. */
enum class DeskWay(val code: Int) {
    IN(1), OUT(2),
    /** "Robot proposes the switch": your "Not now" ([DeskEntry.type] is the capability's LinkLevels code). */
    PROPOSAL(3),
    /**
     * L7: their Robot's answer to a request of yours, or a change to a plan you have (a free/busy reply, a plan accepted,
     * declined or cancelled): kept like an incoming request's (its plaintext 7 days), shown under Sent and said by :voice.
     */
    REPLY(4);

    companion object { fun of(code: Int): DeskWay? = entries.firstOrNull { it.code == code } }
}

/** What became of a request. */
enum class DeskState(val code: Int, val way: DeskWay) {
    /** Robot answered or did it on its own (Answer on its own). */
    HANDLED(1, DeskWay.IN),
    /** You answered its card (its yes). */
    ANSWERED(2, DeskWay.IN),
    /** You said yes to a contact card, and People gained nothing: you have that person, or nothing was left of the card. */
    ALREADY_HAVE(6, DeskWay.IN),
    /** You declined its card. */
    DECLINED(3, DeskWay.IN),
    /** Its card waited past its lifetime. */
    EXPIRED(4, DeskWay.IN),
    /** Handled by Robot, then your Undo. */
    UNDONE(5, DeskWay.IN),
    /** Queued for their Robot; no answer yet. */
    SENT(10, DeskWay.OUT),
    /** Their Robot received it. */
    DELIVERED(11, DeskWay.OUT),
    /** It's a card on their side: "Waiting for Sam". */
    WAITING_FOR_THEM(12, DeskWay.OUT),
    /** Their Robot answered it. */
    ANSWERED_BY_THEM(13, DeskWay.OUT),
    DECLINED_BY_THEM(14, DeskWay.OUT),
    /** Not delivered in its lifetime, or their Robot can't read it: "Couldn't reach". */
    COULDNT_REACH(15, DeskWay.OUT),
    /** "Not now" on Robot's proposal. */
    NOT_NOW(20, DeskWay.PROPOSAL),
    /** L7: their Robot answered (or changed a plan of yours); [DeskEntry.seen] once :voice said it. */
    REPLIED(30, DeskWay.REPLY);

    companion object { fun of(code: Int): DeskState? = entries.firstOrNull { it.code == code } }
}

/** What a card's yes button does (its words are the UI's). None: the card has Decline only, until a job adds its answer. */
enum class DeskYes(val code: Int) {
    /** "Got it" (an ETA: just show it). */
    GOT_IT(1),
    /** "Accept" (a later job's answer, worded by that job's card). */
    ACCEPT(2),
    /** L7: "Send my busy times" (free/busy: busy blocks only). */
    SEND_BUSY(3),
    /** L7: "Add to my calendar" (a plan they propose). */
    ADD_EVENT(4),
    /** L7: "Move it" (a plan of theirs you have, at a new time). */
    MOVE_EVENT(5),
    /** L10: "Add to my brief" (a reminder for you: into your brief reminders, never your calendar). */
    ADD_REMINDER(6),
    /** L10: "Add to People" (a contact card: a new person, unconfirmed; never someone you have). */
    ADD_CONTACT(7),
    /** L8: "Send a file" (a file they ask for: you pick it; only then does anything go). */
    SEND_FILE(8);

    companion object { fun of(code: Int): DeskYes? = entries.firstOrNull { it.code == code } }
}

/** Your answer to a card. */
enum class DeskChoice(val code: Int) {
    YES(1), DECLINE(2),
    /** L7: yes, at the plan's second, third or first time (a plan offers 1 to 3; [YES] is the first you're free at). */
    SECOND(3), THIRD(4), FIRST(5);

    /** Which of a plan's times a yes picks: 0 for [YES] (the handler's pick), 1 to 3 for the first to the third; -1 for Decline. */
    val option: Int get() = when (this) { YES -> 0; FIRST -> 1; SECOND -> 2; THIRD -> 3; DECLINE -> -1 }

    companion object { fun of(code: Int): DeskChoice? = entries.firstOrNull { it.code == code } }
}

/**
 * One line of the desk. [type]: the request type's code (for [DeskWay.PROPOSAL], the capability's LinkLevels code). [at]:
 * when it arrived or was sent (Unix seconds); [done]: when its state was last set. [undo]: a handler's token to undo it
 * (Robot did it on its own and it can be undone), at most [MAX_UNDO] characters. [seen]: announced ("Robot handled 3
 * things"). [plain]: the request's plaintext while it is kept (an incoming entry's, 7 days).
 */
class DeskEntry(
    val id: String,
    val pairing: String,
    val way: DeskWay,
    val type: Int,
    var state: DeskState,
    val at: Long,
    var done: Long,
    var undo: String? = null,
    var seen: Boolean = false,
    plain: ByteArray? = null,
) {
    var plain: ByteArray? = plain?.copyOf()
        set(v) { field = v?.copyOf() }

    init {
        require(LinkIds.isId(id) && LinkIds.isId(pairing) && at >= 0 && done >= 0 && state.way == way)
        require(undo == null || (undo!!.length in 1..MAX_UNDO && undo!!.all { it in '!'..'~' }))
        require(plain == null || plain.size <= LinkRequests.LIVE)
    }

    override fun toString(): String = "DeskEntry(way=$way, state=$state)" // l10n: not text (toString for logs and tests; nothing of the request)

    companion object {
        const val MAX_UNDO = 64
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'D'.code.toByte(), 'E'.code.toByte(), 1)

        /** As :link's state keeps it (LinkPurpose.DESK): the entry, with its plaintext when kept. */
        fun encode(e: DeskEntry): ByteArray = BinWriter(256 + (e.plain?.size ?: 0)).bytes(MAGIC).also { w ->
            writeHead(e, w)
            val p = e.plain ?: ByteArray(0)
            w.u32(p.size.toLong()).bytes(p)
        }.toByteArray()

        fun decode(b: ByteArray): DeskEntry? = try {
            val r = BinReader(b)
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) null else read(r).also { r.end() }
        } catch (_: RuntimeException) {
            null
        }

        /** Everything but the plaintext. */
        internal fun writeHead(e: DeskEntry, w: BinWriter) {
            w.bytes(LinkBytes.unhex(e.id, LinkIds.BYTES)!!).bytes(LinkBytes.unhex(e.pairing, LinkIds.BYTES)!!)
                .u8(e.way.code).u16(e.type).u8(e.state.code).u64(e.at).u64(e.done)
            val u = e.undo?.toByteArray(Charsets.US_ASCII) ?: ByteArray(0)
            w.u8(u.size).bytes(u).u8(if (e.seen) 1 else 0)
        }

        internal fun read(r: BinReader): DeskEntry {
            val id = id(r); val pairing = id(r)
            val way = DeskWay.of(r.u8()) ?: throw LinkBad
            val type = r.u16()
            val state = DeskState.of(r.u8())?.takeIf { it.way == way } ?: throw LinkBad
            val at = r.u64(); val done = r.u64()
            if (at < 0 || done < 0) throw LinkBad
            val un = r.u8()
            if (un > MAX_UNDO) throw LinkBad
            val undo = if (un == 0) null else String(r.bytes(un), Charsets.US_ASCII).also { s -> if (!s.all { it in '!'..'~' }) throw LinkBad }
            val seen = flag(r)
            val n = r.u32()
            if (n > LinkRequests.LIVE) throw LinkBad
            val plain = if (n == 0L) null else r.bytes(n.toInt())
            return DeskEntry(id, pairing, way, type, state, at, done, undo, seen, plain)
        }

        internal fun id(r: BinReader): String = LinkBytes.hex(r.bytes(LinkIds.BYTES)).also { if (!LinkIds.isId(it)) throw LinkBad }
        internal fun idBytes(s: String): ByteArray = requireNotNull(LinkBytes.unhex(s, LinkIds.BYTES))
        internal fun flag(r: BinReader): Boolean = when (r.u8()) { 0 -> false; 1 -> true; else -> throw LinkBad }
    }
}

/** A request as it crosses to :voice and main: :linkparse's answer about it ([frame]) and its [plain]text. */
class DeskRequest(frame: ByteArray, plain: ByteArray) {
    private val f = frame.copyOf()
    private val p = plain.copyOf()
    val frame: ByteArray get() = f.copyOf()
    val plain: ByteArray get() = p.copyOf()

    /** The request, when the frame rebuilds the plaintext exactly (LinkParseWire.check); null otherwise. */
    fun read(): LinkRequest? = (LinkParseWire.check(f, p, LinkRequests.LIVE) as? RequestRead.Ok)?.request

    override fun toString(): String = "DeskRequest" // l10n: not text (toString for logs and tests)

    companion object {
        /** [request] as :link sends it on: its frame (as :linkparse would answer) and its plaintext (LinkRequests.wire). */
        fun of(request: LinkRequest): DeskRequest = DeskRequest(LinkParseWire.encodeReply(RequestRead.Ok(request)), LinkRequests.wire(request))
    }
}

/** A card waiting for you: [id] is its inbox item's; [yes] what its yes does (null: Decline only). */
class DeskCard(val id: String, val pairing: String, val received: Long, val yes: DeskYes?, val request: LinkRequest) {
    val type: RequestType get() = request.type
    override fun toString(): String = "DeskCard(type=$type)" // l10n: not text (toString for logs and tests)
}

/** An entry with its request when it is kept. */
class DeskLine(val entry: DeskEntry, val request: LinkRequest?) {
    override fun toString(): String = "DeskLine(${entry.way}, ${entry.state})" // l10n: not text (toString for logs and tests)
}

/** fb13: the answer a notification says: the newest not yet said; with none and [owed] (a lock took it off the shade, or stopped it going up), the newest said. */
object DeskReplies {
    fun due(lines: List<DeskLine>, owed: Boolean): DeskLine? =
        lines.filter { !it.entry.seen }.maxByOrNull { it.entry.done } ?: if (owed) lines.maxByOrNull { it.entry.done } else null
}

/** Robot proposes letting [pairing]'s Robot be answered on its own for [capability]. */
data class DeskProposal(val pairing: String, val capability: LinkCapability) {
    override fun toString(): String = "DeskProposal($capability)" // l10n: not text (toString for logs and tests)
}

/** What :voice and main see of the desk. [more]: cards past [DeskRules.CARDS_SHOWN] per person, by pairing ("12 more from Sam"). */
class DeskView(val cards: List<DeskCard>, val lines: List<DeskLine>, val proposals: List<DeskProposal>, val more: Map<String, Int>) {
    override fun toString(): String = "DeskView(cards=${cards.size}, lines=${lines.size})" // l10n: not text (toString for logs and tests)

    companion object {
        val EMPTY = DeskView(emptyList(), emptyList(), emptyList(), emptyMap())
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'D'.code.toByte(), 1)
        /** A view at most (the Binder's transaction is 1 MB): what doesn't fit is left out, cards first kept. */
        const val MAX_BYTES = 512 * 1024
        const val MAX_CARDS = 200
        const val MAX_LINES = 500

        /**
         * [cards] and [lines] (their requests as [DeskRequest]s, null when not kept) as one view of at most [MAX_BYTES]:
         * whole items while they fit, cards before lines.
         */
        fun encode(cards: List<Pair<DeskCardOut, DeskRequest>>, lines: List<Pair<DeskEntry, DeskRequest?>>, proposals: List<DeskProposal>, more: Map<String, Int>): ByteArray {
            val head = 4 + 2 + 2 + 1 + proposals.size * 17 + 1 + more.size * 20
            var used = head
            val keptCards = ArrayList<ByteArray>()
            for ((c, r) in cards.take(MAX_CARDS)) {
                val w = BinWriter(256)
                w.bytes(LinkBytes.unhex(c.id, LinkIds.BYTES)!!).bytes(LinkBytes.unhex(c.pairing, LinkIds.BYTES)!!).u64(c.received).u8(c.yes?.code ?: 0)
                blob(w, r.frame); blob(w, r.plain)
                val b = w.toByteArray()
                if (used + b.size > MAX_BYTES) break
                used += b.size; keptCards += b
            }
            val keptLines = ArrayList<ByteArray>()
            for ((e, r) in lines.take(MAX_LINES)) {
                val w = BinWriter(256)
                DeskEntry.writeHead(e, w)
                blob(w, r?.frame ?: ByteArray(0)); blob(w, r?.plain ?: ByteArray(0))
                val b = w.toByteArray()
                if (used + b.size > MAX_BYTES) break
                used += b.size; keptLines += b
            }
            val w = BinWriter(used).bytes(MAGIC).u16(keptCards.size)
            keptCards.forEach { w.bytes(it) }
            w.u16(keptLines.size)
            keptLines.forEach { w.bytes(it) }
            w.u8(proposals.size.coerceAtMost(255))
            for (p in proposals.take(255)) w.bytes(LinkBytes.unhex(p.pairing, LinkIds.BYTES)!!).u8(LinkLevels.code(p.capability))
            w.u8(more.size.coerceAtMost(255))
            for ((k, n) in more.entries.take(255)) w.bytes(LinkBytes.unhex(k, LinkIds.BYTES)!!).u32(n.toLong().coerceIn(0, 0xFFFF_FFFFL))
            return w.toByteArray()
        }

        /** The view in [b], every request checked again (LinkParseWire.check); null when anything breaks the rules. */
        fun decode(b: ByteArray): DeskView? = try {
            if (b.size > MAX_BYTES + 64) null else decodeOrThrow(b)
        } catch (_: RuntimeException) {
            null
        }

        private fun decodeOrThrow(b: ByteArray): DeskView? {
            val r = BinReader(b)
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) return null
            val nc = r.u16()
            if (nc > MAX_CARDS) return null
            val cards = ArrayList<DeskCard>()
            repeat(nc) {
                val id = DeskEntry.id(r); val pairing = DeskEntry.id(r)
                val received = r.u64()
                val yesCode = r.u8()
                val yes = if (yesCode == 0) null else DeskYes.of(yesCode) ?: return null
                val req = DeskRequest(blobOf(r, LinkParseIpc.MAX_REPLY), blobOf(r, LinkRequests.LIVE)).read() ?: return null
                if (received < 0) return null
                cards += DeskCard(id, pairing, received, yes, req)
            }
            val nl = r.u16()
            if (nl > MAX_LINES) return null
            val lines = ArrayList<DeskLine>()
            repeat(nl) {
                val id = DeskEntry.id(r); val pairing = DeskEntry.id(r)
                val way = DeskWay.of(r.u8()) ?: return null
                val type = r.u16()
                val state = DeskState.of(r.u8())?.takeIf { it.way == way } ?: return null
                val at = r.u64(); val done = r.u64()
                val un = r.u8()
                if (un > DeskEntry.MAX_UNDO) return null
                val undo = if (un == 0) null else String(r.bytes(un), Charsets.US_ASCII)
                val seen = DeskEntry.flag(r)
                val frame = blobOf(r, LinkParseIpc.MAX_REPLY); val plain = blobOf(r, LinkRequests.LIVE)
                if ((frame.isEmpty()) != (plain.isEmpty())) return null
                val req = if (frame.isEmpty()) null else DeskRequest(frame, plain).read() ?: return null
                if (req != null && (way == DeskWay.PROPOSAL || req.type.code != type)) return null
                lines += DeskLine(DeskEntry(id, pairing, way, type, state, at, done, undo, seen, null), req)
            }
            val np = r.u8()
            val proposals = ArrayList<DeskProposal>()
            repeat(np) { proposals += DeskProposal(DeskEntry.id(r), LinkLevels.capability(r.u8()) ?: return null) }
            val nm = r.u8()
            val more = LinkedHashMap<String, Int>()
            repeat(nm) {
                val k = DeskEntry.id(r)
                val n = r.u32()
                if (n > Int.MAX_VALUE || more.put(k, n.toInt()) != null) return null
            }
            r.end()
            return DeskView(cards, lines, proposals, more)
        }

        private fun blob(w: BinWriter, b: ByteArray) { w.u32(b.size.toLong()).bytes(b) }

        private fun blobOf(r: BinReader, max: Int): ByteArray {
            val n = r.u32()
            if (n > max) throw LinkBad
            return r.bytes(n.toInt())
        }
    }
}

/** A card as :link writes it into a view (its request goes beside it). */
class DeskCardOut(val id: String, val pairing: String, val received: Long, val yes: DeskYes?)

/** What :voice is told after a ping from :link: never a request's contents, only what to say and for whom. */
enum class DeskNoticeKind(val code: Int) {
    /** New cards wait ([DeskNotice.value]: how many from that pairing). */
    CARDS(1),
    /** Robot handled things on its own ([DeskNotice.value]: how many, not yet announced). */
    HANDLED(2),
    PAIRED(3),
    UNPAIRED(4),
    MOVED(5),
    LOWER_ASKED(6),
    LOWERED(7),
    RAISED(8),
    /** Too many requests: paused until [DeskNotice.value] (Unix seconds). */
    BUSY(9),
    /** A request you sent couldn't be delivered in its lifetime. */
    GAVE_UP(10),
    /** L7: their Robot answered a request of yours, or changed a plan you have ([DeskNotice.value]: how many). */
    REPLIED(11),
    /** L8: a file from another Robot is whole and sealed: Files takes it at the next unlock. */
    FILES(12),
    /** L8: a file between you and that Robot failed (a wrong hash or size): nothing of it was kept. */
    FILE_FAILED(13),
    /** L11: their Robot left a shared folder, or never joined it. Your copies stay. */
    FOLDER_ENDED(14);

    companion object { fun of(code: Int): DeskNoticeKind? = entries.firstOrNull { it.code == code } }
}

data class DeskNotice(val kind: DeskNoticeKind, val pairing: String, val value: Long = 0) {
    init { require(LinkIds.isId(pairing) && value >= 0) }
    override fun toString(): String = "DeskNotice($kind)" // l10n: not text (toString for logs and tests)
}

/** DESK_NOTICES' answer: the notices since the last call, and how many items wait sealed in the inbox (read while locked too). */
class DeskNotices(val waiting: Int, val notices: List<DeskNotice>) {
    override fun toString(): String = "DeskNotices(waiting=$waiting, notices=${notices.size})" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX = 100
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'N'.code.toByte(), 1)

        fun encode(n: DeskNotices): ByteArray {
            val w = BinWriter(16 + n.notices.size * 25).bytes(MAGIC).u32(n.waiting.toLong().coerceAtLeast(0)).u8(n.notices.size.coerceAtMost(MAX))
            for (x in n.notices.take(MAX)) w.u8(x.kind.code).bytes(LinkBytes.unhex(x.pairing, LinkIds.BYTES)!!).u64(x.value)
            return w.toByteArray()
        }

        fun decode(b: ByteArray): DeskNotices? = try {
            val r = BinReader(b)
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) null else {
                val waiting = r.u32()
                val n = r.u8()
                if (waiting > Int.MAX_VALUE || n > MAX) null else {
                    val list = ArrayList<DeskNotice>()
                    var ok = true
                    repeat(n) {
                        val k = DeskNoticeKind.of(r.u8())
                        val p = DeskEntry.id(r)
                        val v = r.u64()
                        if (k == null || v < 0) ok = false else list += DeskNotice(k, p, v)
                    }
                    r.end()
                    if (ok) DeskNotices(waiting.toInt(), list) else null
                }
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** The desk's rules, by code (LinkDeskTest). */
object DeskRules {
    /** "Handled by Robot" lists 7 days, with Undo for as long (§6.4); an incoming entry's plaintext is kept as long. */
    const val HANDLED_DAYS = 7L
    /** Every entry goes after 30 days (the proposal's window). */
    const val KEEP_DAYS = 30L
    /** Cards shown per person; more become "12 more from Sam" (§4.6). */
    const val CARDS_SHOWN = LinkLimits.CARDS_PER_PERSON
    /** "Robot proposes the switch" after you said yes to this many cards of one capability from one person... */
    const val PROPOSE_AFTER = 3
    /** ...within this many days, with no Decline of that capability from them since the first of them. */
    const val PROPOSE_DAYS = 30L
    /** "Not now" holds the proposal back this long. */
    const val NOT_NOW_DAYS = 30L
    /** Entries kept at most (the oldest go first). */
    const val MAX_ENTRIES = 2_000

    private const val DAY = LinkLimits.DAY

    /** Whether [e] can still be undone at [now] (Unix seconds): Robot did it on its own, it can be undone, within 7 days. */
    fun undoable(e: DeskEntry, now: Long): Boolean = e.way == DeskWay.IN && e.state == DeskState.HANDLED && e.undo != null && now - e.done < HANDLED_DAYS * DAY

    /** Whether [e] shows in Requests' lists at [now]: Handled by Robot for 7 days, and what you sent for 30. */
    fun listed(e: DeskEntry, now: Long): Boolean = when (e.way) {
        DeskWay.IN, DeskWay.REPLY -> now - e.done < HANDLED_DAYS * DAY
        DeskWay.OUT -> now - e.at < KEEP_DAYS * DAY
        DeskWay.PROPOSAL -> false
    }

    /** What [e] keeps at [now]: false to delete it (30 days); its plaintext goes after 7 ([trim]). */
    fun keep(e: DeskEntry, now: Long): Boolean = now - maxOf(e.at, e.done) < KEEP_DAYS * DAY

    /** Whether [e]'s plaintext goes now (an incoming entry or a reply after 7 days; an outgoing one never keeps it). */
    fun trim(e: DeskEntry, now: Long): Boolean = e.plain != null && ((e.way != DeskWay.IN && e.way != DeskWay.REPLY) || now - e.done >= HANDLED_DAYS * DAY)

    /** A card whose request's lifetime ended at [now]. */
    fun expired(request: LinkRequest, now: Long): Boolean = now >= request.at + request.ttl || passed(request, now)

    /** A plan or a change whose every time has gone: nothing left to say yes to. */
    private fun passed(request: LinkRequest, now: Long): Boolean = when (val b = request.body) {
        is app.heyrobot.core.link.requests.EvPropose -> b.event.times.none { it > now }
        is app.heyrobot.core.link.requests.EvChange -> b.event.times.none { it > now }
        else -> false
    }

    /**
     * §4.2, "Robot proposes the switch": whether Robot offers to let [pairing]'s Robot be answered on its own for [cap] at
     * [now]. Only at Ask me first, only for a capability that may be answered on its own (never money or contact cards),
     * after [PROPOSE_AFTER] cards of it from them you said yes to in [PROPOSE_DAYS] days with no Decline of it from them
     * since the first of those, and not within [NOT_NOW_DAYS] days of a "Not now". Loosening is always your tap.
     */
    fun proposes(entries: Collection<DeskEntry>, pairing: String, cap: LinkCapability, level: LinkLevel, now: Long): Boolean {
        // Folders and lists: a change in one you joined applies at Ask me first already (Flow.SYNC), so there is nothing to propose.
        if (level != LinkLevel.ASK || !cap.autoAllowed || cap == LinkCapability.FOLDERS || cap == LinkCapability.LISTS) return false
        val code = LinkLevels.code(cap)
        if (entries.any { it.way == DeskWay.PROPOSAL && it.pairing == pairing && it.type == code && now - it.done < NOT_NOW_DAYS * DAY }) return false
        val mine = entries.filter { it.way == DeskWay.IN && it.pairing == pairing && RequestType.ofCode(it.type)?.capability == cap && now - it.done < PROPOSE_DAYS * DAY }
        val yeses = mine.filter { it.state == DeskState.ANSWERED }.sortedBy { it.done }.takeLast(PROPOSE_AFTER)
        if (yeses.size < PROPOSE_AFTER) return false
        val from = yeses.first().done
        return mine.none { it.state == DeskState.DECLINED && it.done >= from }
    }

    /** The capabilities Robot proposes for [pairing] now, given its [levels]. */
    fun proposals(entries: Collection<DeskEntry>, pairing: String, levels: Map<LinkCapability, LinkLevel>, now: Long): List<LinkCapability> =
        LinkCapability.entries.filter { proposes(entries, pairing, it, levels[it] ?: LinkLevel.OFF, now) }

    /** Cards per person: the first [CARDS_SHOWN] of each pairing's, oldest first, and how many more each has. */
    fun <T> shown(cards: List<T>, pairing: (T) -> String): Pair<List<T>, Map<String, Int>> {
        val out = ArrayList<T>()
        val more = LinkedHashMap<String, Int>()
        for ((p, list) in cards.groupBy(pairing)) {
            val (show, rest) = LinkLimits.cards(list.size)
            out += list.take(show)
            if (rest > 0) more[p] = rest
        }
        return out to more
    }

    /** The outgoing entry's state after their Robot's ack [ack]: null leaves it as it is. */
    fun afterAck(now: DeskState, ack: app.heyrobot.core.link.requests.AckState): DeskState? = when (ack) {
        app.heyrobot.core.link.requests.AckState.RECEIVED -> if (now == DeskState.SENT) DeskState.DELIVERED else null
        app.heyrobot.core.link.requests.AckState.WAITING -> DeskState.WAITING_FOR_THEM
        app.heyrobot.core.link.requests.AckState.HANDLED -> DeskState.ANSWERED_BY_THEM
        app.heyrobot.core.link.requests.AckState.DECLINED -> DeskState.DECLINED_BY_THEM
        app.heyrobot.core.link.requests.AckState.UNSUPPORTED -> DeskState.COULDNT_REACH
        // Refused for now: sent again later by the outbox, so nothing changes.
        app.heyrobot.core.link.requests.AckState.BUSY, app.heyrobot.core.link.requests.AckState.EXPIRED -> null
    }
}
