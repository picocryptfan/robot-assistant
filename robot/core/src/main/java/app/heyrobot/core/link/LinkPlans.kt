// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.EventFields
import app.heyrobot.core.link.requests.RequestCaps

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.4, §4.1, D18; job L7): a plan shared with another Robot, as :link keeps
// it (LinkPurpose.PLAN, sealed under robot_link_v1, keyed by [LinkPlan.key]). One record per plan and pairing: the one you proposed ([LinkPlan.mine])
// or the one their Robot proposed. It holds what the plan is, its counter (the highest (rev, request id) wins, LinkOrder),
// what became of it, the time taken, and the calendar event Robot added for it, so that a cancel or an Undo removes exactly
// that event and nothing else of your calendar. Another Robot names a plan only by its id, and only its own pairing's plans
// answer to it: a cancel from Sam's Robot never reaches a plan you have with Mom.
//
//   plan     "RPL" 1 | id 16 | pairing 16 | mine u8 | state u8 | rev u64 | req 16 | sent 16 (zeros: none) | at u64 | done u64
//            | time u64 | cal u64 | aloneAt u64 | minutes u16 | times u8, each u64 | title | place | note | grp 16 (zeros)
//   text     u16 length + UTF-8 (0: none)
//   all      "RPA" 1 | count u16, each: u32 length + plan
//
// Big-endian, nothing after the last field, every length capped, read strictly.

enum class PlanState(val code: Int) {
    /** Proposed, no answer yet (or a card still waiting on your side). */
    PROPOSED(1),
    /** Accepted at [LinkPlan.time]: in your calendar when Robot could add it ([LinkPlan.cal]). */
    ACCEPTED(2),
    DECLINED(3),
    /** Cancelled by either side (an Undo is a cancel): its event is off your calendar. */
    CANCELLED(4);

    companion object { fun of(code: Int): PlanState? = entries.firstOrNull { it.code == code } }
}

class LinkPlan(
    /** The plan's id (the requests' "ev"). */
    val id: String,
    val pairing: String,
    /** You proposed it (else their Robot did). */
    val mine: Boolean,
    var state: PlanState,
    /** Its counter, and the request that set it (LinkOrder's tie-break). */
    var rev: Long,
    var req: String,
    var event: EventFields,
    /** When it was made and last changed (Unix seconds). */
    val at: Long,
    var done: Long,
    /** Your proposal's request id (its line under Sent), when you proposed it. */
    var sent: String? = null,
    /** The time accepted (Unix seconds; 0: none yet). */
    var time: Long = 0,
    /** The calendar event Robot added for it (0: none). Removed only by a cancel or an Undo of this plan. */
    var cal: Long = 0,
    /** When Robot accepted it on its own (0: never): counts toward "at most 3 a day from one person" (D18). */
    var aloneAt: Long = 0,
) {
    init {
        require(LinkIds.isId(id) && LinkIds.isId(pairing) && LinkIds.isId(req) && rev in 1..RequestCaps.MAX_REV)
        require(sent == null || LinkIds.isId(sent!!))
        require(at >= 0 && done >= 0 && time >= 0 && cal >= 0 && aloneAt >= 0)
        require(event.minutes in RequestCaps.EVENT_MIN_MINUTES..RequestCaps.EVENT_MAX_MINUTES && event.times.size in 1..RequestCaps.TIMES)
    }

    /** When it ends (the accepted time's, else the last time offered's). */
    val ends: Long get() = (if (time > 0) time else event.times.last()) + event.minutes * 60

    override fun toString(): String = "LinkPlan(mine=$mine, state=$state)" // l10n: not text (toString for logs and tests; nothing of the plan)

    companion object {
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), 'L'.code.toByte(), 1)
        private val ALL = byteArrayOf('R'.code.toByte(), 'P'.code.toByte(), 'A'.code.toByte(), 1)
        /** A plan's record at most (LinkPurpose.PLAN's cap). */
        const val MAX_BYTES = 4 * 1024
        /** Plans in one list across the IPC at most. */
        const val MAX_LIST = 500
        private val ZERO = ByteArray(LinkIds.BYTES)

        fun encode(p: LinkPlan): ByteArray {
            val w = BinWriter(512).bytes(MAGIC).bytes(id(p.id)).bytes(id(p.pairing)).u8(if (p.mine) 1 else 0).u8(p.state.code).u64(p.rev).bytes(id(p.req))
                .bytes(p.sent?.let(::id) ?: ZERO).u64(p.at).u64(p.done).u64(p.time).u64(p.cal).u64(p.aloneAt).u16(p.event.minutes.toInt())
                .u8(p.event.times.size)
            p.event.times.forEach { w.u64(it) }
            text(w, p.event.title); text(w, p.event.place); text(w, p.event.note)
            w.bytes(p.event.grp?.let(::id) ?: ZERO)
            return w.toByteArray().also { require(it.size <= MAX_BYTES) }
        }

        fun decode(b: ByteArray): LinkPlan? = try {
            if (b.size > MAX_BYTES) null else BinReader(b).let { r -> read(r).also { r.end() } }
        } catch (_: RuntimeException) {
            null
        }

        private fun read(r: BinReader): LinkPlan {
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val id = DeskEntry.id(r); val pairing = DeskEntry.id(r)
            val mine = DeskEntry.flag(r)
            val state = PlanState.of(r.u8()) ?: throw LinkBad
            val rev = r.u64(); val req = DeskEntry.id(r)
            val sent = optId(r)
            val at = r.u64(); val done = r.u64(); val time = r.u64(); val cal = r.u64(); val aloneAt = r.u64()
            val minutes = r.u16().toLong()
            val n = r.u8()
            if (n !in 1..RequestCaps.TIMES) throw LinkBad
            val times = List(n) { r.u64() }
            if (times.zipWithNext().any { (a, b) -> a >= b } || times.any { it !in RequestCaps.MIN_TIME..RequestCaps.MAX_TIME }) throw LinkBad
            val title = textOf(r, RequestCaps.TITLE, false) ?: throw LinkBad
            val place = textOf(r, RequestCaps.PLACE, false)
            val note = textOf(r, RequestCaps.NOTE, true)
            val grp = optId(r)
            if (rev !in 1..RequestCaps.MAX_REV || minutes !in RequestCaps.EVENT_MIN_MINUTES..RequestCaps.EVENT_MAX_MINUTES) throw LinkBad
            if (time != 0L && time !in times) throw LinkBad
            return LinkPlan(id, pairing, mine, state, rev, req, EventFields(title, times, minutes, place, note, grp), at, done, sent, time, cal, aloneAt)
        }

        /** [plans] as one list for :voice (LinkService's PLANS), at most [MAX_LIST]. */
        fun encodeAll(plans: List<LinkPlan>): ByteArray {
            val kept = plans.take(MAX_LIST).map(::encode)
            val w = BinWriter(8 + kept.sumOf { it.size + 4 }).bytes(ALL).u16(kept.size)
            kept.forEach { w.u32(it.size.toLong()).bytes(it) }
            return w.toByteArray()
        }

        fun decodeAll(b: ByteArray): List<LinkPlan>? = try {
            val r = BinReader(b)
            if (!ALL.all { r.u8() == (it.toInt() and 0xFF) }) null else {
                val n = r.u16()
                if (n > MAX_LIST) null else {
                    val out = ArrayList<LinkPlan>(n)
                    repeat(n) {
                        val len = r.u32()
                        if (len > MAX_BYTES) throw LinkBad
                        out += decode(r.bytes(len.toInt())) ?: throw LinkBad
                    }
                    r.end()
                    out
                }
            }
        } catch (_: RuntimeException) {
            null
        }

        /**
         * The record's key for plan [id] with [pairing]: the plan's id is chosen by whoever proposed it, so two Robots may name
         * the same id; keyed by both, one pairing's request never reaches another pairing's plan.
         */
        fun key(pairing: String, id: String): String {
            require(LinkIds.isId(pairing) && LinkIds.isId(id))
            val h = LinkBytes.sha256("robot-link-plan-v1".toByteArray(Charsets.US_ASCII), id(pairing), id(id)).copyOf(LinkIds.BYTES) // l10n: not text (a key's label)
            if (LinkBytes.allZero(h)) h[0] = 1
            return LinkBytes.hex(h)
        }

        private fun id(s: String): ByteArray = LinkBytes.unhex(s, LinkIds.BYTES)!!

        private fun optId(r: BinReader): String? {
            val b = r.bytes(LinkIds.BYTES)
            return if (LinkBytes.allZero(b)) null else LinkBytes.hex(b)
        }

        private fun text(w: BinWriter, s: String?) {
            val b = s?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
            w.u16(b.size).bytes(b)
        }

        private fun textOf(r: BinReader, max: Int, lines: Boolean): String? {
            val n = r.u16()
            if (n == 0) return null
            if (n > max * 4) throw LinkBad
            val s = LinkBytes.utf8(r.bytes(n)) ?: throw LinkBad
            if (!LinkText.ok(s, max, lines)) throw LinkBad
            return s
        }
    }
}

/** The plans' rules, by code (LinkPlansTest). */
object PlanRules {
    /** A plan is kept this long after it ends or was cancelled or declined. */
    const val KEEP_DAYS = 30L
    /** At most this many plans kept per pairing (the oldest go first). */
    const val PER_PAIRING = 200

    private const val DAY = LinkLimits.DAY

    /** Plans of [pairing]'s Robot that Robot accepted on its own in the day before [now] (D18's "at most 3 a day"). */
    fun aloneLastDay(plans: Collection<LinkPlan>, pairing: String, now: Long): Int =
        plans.count { !it.mine && it.pairing == pairing && it.aloneAt > 0 && now - it.aloneAt < DAY }

    /** Whether [p] goes at [now]: 30 days after it ended, or after it was declined or cancelled. */
    fun drop(p: LinkPlan, now: Long): Boolean = when (p.state) {
        PlanState.DECLINED, PlanState.CANCELLED -> now - p.done >= KEEP_DAYS * DAY
        PlanState.PROPOSED, PlanState.ACCEPTED -> now - maxOf(p.ends, p.done) >= KEEP_DAYS * DAY
    }

    /**
     * [pairing]'s plan [id] in [plans] (keyed by [LinkPlan.key]), only when it is that pairing's: another Robot never reaches
     * a plan you have with someone else.
     */
    fun of(plans: Map<String, LinkPlan>, pairing: String, id: String): LinkPlan? =
        if (!LinkIds.isId(pairing) || !LinkIds.isId(id)) null else plans[LinkPlan.key(pairing, id)]?.takeIf { it.pairing == pairing && it.id == id }

    /** The plans with [pairing] still ahead at [now] (proposed or accepted, not yet ended), soonest first. */
    fun ahead(plans: Collection<LinkPlan>, pairing: String, now: Long): List<LinkPlan> =
        plans.filter { it.pairing == pairing && (it.state == PlanState.PROPOSED || it.state == PlanState.ACCEPTED) && it.ends > now }
            .sortedBy { if (it.time > 0) it.time else it.event.times.first() }

    /**
     * L16: the plan your [details] name among [ahead]: every word of yours but the small ones and [person]'s name begins a word
     * of its title. Words that name no plan ("the plan", or none) mean the next one. Null when they name one that isn't there:
     * it is never another plan instead.
     */
    fun named(ahead: List<LinkPlan>, details: String, person: String): LinkPlan? {
        val words = planWords(details) - planWords(person).toSet() - NOT_A_NAME
        if (words.isEmpty()) return ahead.firstOrNull()
        return ahead.firstOrNull { p -> planWords(p.event.title).let { title -> words.all { w -> title.any { it.startsWith(w) } } } }
    }

    private fun planWords(s: String): List<String> = LinkAsk.fold(s).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    /** Words of "cancel the plan with Sam" that name no plan, as folded. */
    private val NOT_A_NAME = setOf( // l10n: not text (words matched, never shown)
        "a", "an", "at", "for", "my", "of", "on", "one", "our", "plan", "plans", "s", "that", "the", "this", "to", "with", "next", "first", "upcoming",
        "au", "aux", "avec", "ce", "cette", "d", "de", "des", "du", "l", "la", "le", "les", "mes", "mon", "nos", "notre", "premier", "premiere", "prochain", "prochaine", "rendez", "rdv", "un", "une", "vous",
    )

    /** Whether a change at ([rev], [requestId]) wins over [p]'s current one (LinkOrder): an older change arriving late is ignored. */
    fun wins(p: LinkPlan, rev: Long, requestId: String): Boolean = LinkOrder.wins(rev, requestId, p.rev, p.req)
}
