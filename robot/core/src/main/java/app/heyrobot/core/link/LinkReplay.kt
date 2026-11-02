// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import java.util.TreeMap

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §3.4; job L1): the replay window and the ordering counters, one of
// each per pairing, on top of libsignal's own refusal of a message key used twice. R0 found that libsignal raises the same
// exception for a true replay and for an evicted skipped key (§3.2), so Robot keeps its own list of the request ids it has
// seen; this file is that list.
//
// The replay window ([ReplayWindow]):
//   - Refused: `at` more than 10 minutes ahead of this phone's clock (FUTURE), or `at + ttl` already past (EXPIRED); both
//     are answered `expired`. A request seen before is a REPEAT: never handled twice (the carrier may send its ack again).
//   - Each id is kept until its own `at + ttl` plus the 10 minutes of skew, so a copy can't come back while it would still
//     be accepted (at most 30 days and 20 minutes, D9).
//   - At most 5,000 ids a pairing (EST). When the window is full of live ids, the one with the oldest `at` goes, and the
//     window's floor rises to that `at`: from then on anything not newer than the floor is refused as EXPIRED, so an
//     evicted id can never be replayed. A pairing that sends that much (past 60 an hour and 300 a day, LinkLimits) loses
//     only its oldest stragglers.
//
// The ordering counters ([ThingOrder], [LinkOrder]): requests about something that changes over time (an event, a split,
// a folder's item) carry its id and a counter, `rev`. The change with the highest (rev, request id) wins, compared the
// same way on both phones so they agree even when both changed the same thing at the same counter; an older change that
// arrives late is ignored. A counter may jump ahead by at most [LinkOrder.MAX_JUMP] at once, so one message can't push a
// thing to the cap and freeze it. A thing's counter is kept 30 days and 20 minutes after its last change (any older change
// would be past its ttl by then).
//
// Both are kept by `:link` in its sealed store (L2) between runs: [ReplayWindow.encode] and [ThingOrder.encode] write
// them, and their decoders read them as strictly as anything from outside.

/** What the replay window made of a request. */
enum class Admit {
    ACCEPTED,
    /** Seen before (its id): not handled again. */
    REPEAT,
    /** `at` more than 10 minutes in the future: answered `expired`. */
    FUTURE,
    /** Past `at + ttl`, or not newer than the window's floor: answered `expired`. */
    EXPIRED,
}

class ReplayWindow(val max: Int = MAX_IDS) {
    private class Key(val at: Long, val id: String) : Comparable<Key> {
        override fun compareTo(other: Key): Int = compareValues(at, other.at).let { if (it != 0) it else id.compareTo(other.id) }
    }

    /** By (at, id): the first is the one to evict. Values: when the id may be forgotten. */
    private val entries = TreeMap<Key, Long>()
    private val ids = HashMap<String, Key>()

    /** Nothing at or before this `at` is accepted any more (0: no floor yet). */
    var floor: Long = 0
        private set

    val size: Int get() = ids.size

    init {
        require(max in 1..MAX_IDS)
    }

    /** Request [id], sent at [at] with [ttl], arrives at [now] (Unix seconds): accepted and remembered, or refused. */
    fun admit(id: String, at: Long, ttl: Long, now: Long): Admit {
        require(LinkIds.isId(id) && at >= 0 && ttl >= 0)
        if (at > now + SKEW_SECONDS) return Admit.FUTURE
        if (at + ttl < now) return Admit.EXPIRED
        purge(now)
        if (at <= floor) return Admit.EXPIRED
        if (id in ids) return Admit.REPEAT
        if (ids.size >= max) {
            val oldest = entries.firstKey()
            // Accepting this one would raise the floor to its own `at` or past it: refuse it instead, and forget nothing.
            if (at <= oldest.at) return Admit.EXPIRED
            entries.remove(oldest)
            ids.remove(oldest.id)
            floor = maxOf(floor, oldest.at)
            if (at <= floor) return Admit.EXPIRED
        }
        val k = Key(at, id)
        entries[k] = at + ttl + SKEW_SECONDS
        ids[id] = k
        return Admit.ACCEPTED
    }

    /** Ids whose request can't come back any more are forgotten. */
    fun purge(now: Long) {
        val it = entries.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.value < now) { ids.remove(e.key.id); it.remove() }
        }
    }

    fun seen(id: String): Boolean = id in ids

    /**
     * L5-fix: [id] forgotten, as if it never came: a request answered `busy` wasn't handled, so the same request sent again
     * after the pause must be let in, not refused as a repeat.
     */
    fun forget(id: String) {
        val k = ids.remove(id) ?: return
        entries.remove(k)
    }

    /** For `:link`'s sealed store: "RLR" 1 | floor u64 | count u16 | each (by at, then id): id 16, at u64, keep-until u64. */
    fun encode(): ByteArray {
        val w = BinWriter(4 + 8 + 2 + entries.size * 32).bytes(MAGIC).u64(floor).u16(entries.size)
        for ((k, keep) in entries) w.bytes(LinkBytes.unhex(k.id, LinkIds.BYTES)!!).u64(k.at).u64(keep)
        return w.toByteArray()
    }

    override fun toString(): String = "ReplayWindow(size=$size)" // l10n: not text (toString for logs and tests)

    companion object {
        /** §3.4: at most 5,000 ids a pairing (EST). */
        const val MAX_IDS = 5_000
        /** §3.4: a request may say it was sent up to 10 minutes in the future. */
        const val SKEW_SECONDS = 600L
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'R'.code.toByte(), 1)

        /** A window [encode] wrote, or null for anything else (then `:link` starts the pairing's window again, with its floor at now). */
        fun decode(bytes: ByteArray, max: Int = MAX_IDS): ReplayWindow? = try {
            val r = BinReader(bytes)
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val w = ReplayWindow(max)
            w.floor = r.u64()
            val n = r.u16()
            if (w.floor < 0 || n > max || r.remaining != n * 32) throw LinkBad
            var last: Key? = null
            repeat(n) {
                val id = r.bytes(LinkIds.BYTES).let { if (LinkBytes.allZero(it)) throw LinkBad else LinkBytes.hex(it) }
                val at = r.u64()
                val keep = r.u64()
                val k = Key(at, id)
                // An id evicted at the floor's `at` may leave others of that same second: at == floor is fine, below isn't.
                if (at < w.floor || keep < at || at < 0 || (last != null && k <= last!!)) throw LinkBad
                w.entries[k] = keep
                w.ids[id] = k
                last = k
            }
            r.end()
            w
        } catch (_: LinkBad) {
            null
        }
    }
}

/** How a change to a thing compares with what this phone has of it. */
enum class Order {
    /** Newer: apply it. */
    NEWER,
    /** Older (or lost a tie): ignore it. */
    STALE,
    /** The very change already applied (same counter, same request). */
    SAME,
    /** Its counter jumps too far ahead at once: refused. */
    JUMP,
    /** A thing this pairing hasn't mentioned before, and the table is full: refused (answered `busy`). */
    FULL,
}

object LinkOrder {
    /** How far one change may move a thing's counter at once. */
    const val MAX_JUMP = 100_000L

    /**
     * Whether change ([rev], [requestId]) wins over ([currentRev], [currentRequestId]): the higher counter, and on a tie the
     * higher request id (lower-case hex compares as its bytes do). Both phones compute the same answer.
     */
    fun wins(rev: Long, requestId: String, currentRev: Long, currentRequestId: String): Boolean =
        rev > currentRev || (rev == currentRev && requestId > currentRequestId)
}

/** The counters of the things one pairing changes, by thing id. */
class ThingOrder(val max: Int = MAX_THINGS) {
    private class Mark(val rev: Long, val requestId: String, val seen: Long)

    private val marks = TreeMap<String, Mark>()

    val size: Int get() = marks.size

    init {
        require(max in 1..MAX_THINGS)
    }

    /** A change to [thing] at counter [rev], carried by request [requestId], at [now]: what to do with it, recorded when NEWER. */
    fun offer(thing: String, rev: Long, requestId: String, now: Long): Order {
        require(LinkIds.isId(thing) && LinkIds.isId(requestId) && rev in 1..app.heyrobot.core.link.requests.RequestCaps.MAX_REV)
        purge(now)
        val m = marks[thing]
        if (m == null) {
            if (marks.size >= max) return Order.FULL
            marks[thing] = Mark(rev, requestId, now)
            return Order.NEWER
        }
        if (rev == m.rev && requestId == m.requestId) return Order.SAME
        if (!LinkOrder.wins(rev, requestId, m.rev, m.requestId)) return Order.STALE
        if (rev - m.rev > LinkOrder.MAX_JUMP) return Order.JUMP
        marks[thing] = Mark(rev, requestId, now)
        return Order.NEWER
    }

    /** The counter this phone's own next change to [thing] uses. */
    fun next(thing: String): Long = (marks[thing]?.rev ?: 0) + 1

    fun rev(thing: String): Long? = marks[thing]?.rev

    /** Things unchanged for longer than any request lives are forgotten. */
    fun purge(now: Long) {
        val it = marks.entries.iterator()
        while (it.hasNext()) if (it.next().value.seen < now - KEEP_SECONDS) it.remove()
    }

    /** "RLO" 1 | count u16 | each (by thing id): thing 16, rev u32, request id 16, seen u64. */
    fun encode(): ByteArray {
        val w = BinWriter(6 + marks.size * 44).bytes(MAGIC).u16(marks.size)
        for ((t, m) in marks) w.bytes(LinkBytes.unhex(t, LinkIds.BYTES)!!).u32(m.rev).bytes(LinkBytes.unhex(m.requestId, LinkIds.BYTES)!!).u64(m.seen)
        return w.toByteArray()
    }

    override fun toString(): String = "ThingOrder(size=$size)" // l10n: not text (toString for logs and tests)

    companion object {
        const val MAX_THINGS = 2_000
        /** The longest a request lives (30 days) and twice the skew. */
        const val KEEP_SECONDS = 30 * 24 * 3600L + 2 * ReplayWindow.SKEW_SECONDS
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'L'.code.toByte(), 'O'.code.toByte(), 1)

        fun decode(bytes: ByteArray, max: Int = MAX_THINGS): ThingOrder? = try {
            val r = BinReader(bytes)
            if (!MAGIC.all { r.u8() == (it.toInt() and 0xFF) }) throw LinkBad
            val o = ThingOrder(max)
            val n = r.u16()
            if (n > max || r.remaining != n * 44) throw LinkBad
            var last: String? = null
            repeat(n) {
                val t = r.bytes(LinkIds.BYTES).let { if (LinkBytes.allZero(it)) throw LinkBad else LinkBytes.hex(it) }
                val rev = r.u32()
                val req = r.bytes(LinkIds.BYTES).let { if (LinkBytes.allZero(it)) throw LinkBad else LinkBytes.hex(it) }
                val seen = r.u64()
                if (rev !in 1..app.heyrobot.core.link.requests.RequestCaps.MAX_REV || seen < 0 || (last != null && t <= last!!)) throw LinkBad
                o.marks[t] = Mark(rev, req, seen)
                last = t
            }
            r.end()
            o
        } catch (_: LinkBad) {
            null
        }
    }
}
