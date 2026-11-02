// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.RequestType

// Pure. 1.5, Robot to Robot (robot-to-robot-plan.md §2.3, §3.4, §3.7, §4.6, lists-plan.md §9; job L1): the numbers that
// keep a paired Robot from flooding yours, and the outbox's patience. Every figure is the plan's EST, to be measured; the
// clock is always passed in (Unix seconds), so every window here is exact in tests.

object LinkLimits {
    /** L16: acks queued to one person, past which one more refused or repeated request gets no answer: it can't fill your outbox. */
    const val REFUSALS_QUEUED = 32
    /** F74: acks of any kind queued to one person: past it the newest replaces the oldest (they ask again), so acks to dead routes can't fill your outbox. */
    const val ACKS_QUEUED = 32
    /** F73: items one person may have waiting in the inbox, so one Robot can't fill it and have others answered busy. */
    const val INBOX_PER_PERSON = 100
    /** §2.3. */
    const val PAIRINGS = 100
    const val NEW_PAIRINGS_PER_DAY = 10
    /** §4.6: per person. */
    const val REQUESTS_PER_HOUR = 60
    const val REQUESTS_PER_DAY = 300
    /** §4.6: at most 10 cards waiting per person; more become one "12 more from Sam". */
    const val CARDS_PER_PERSON = 10
    /** §3.7. */
    const val OUTBOX = 500
    const val OUTBOX_PER_PERSON = 200
    /** Live connections bound to a pairing or dialled by this Robot (L5-fix: those waiting for a hello have their own cap). */
    const val LIVE_CONNECTIONS = 8
    /** F69: of those, coming in for one pairing: a new one closes the oldest, so one Robot can't hold them all. */
    const val LIVE_PER_PAIRING = 2
    /**
     * L5-fix: connections accepted that haven't said hello yet (anyone on a shared network can open one): at most 4, apart
     * from [LIVE_CONNECTIONS], so silent ones never stop a paired Robot's connection, nor one this Robot dials.
     */
    const val UNBOUND_CONNECTIONS = 4
    /** A connection hello within 30 seconds, or the connection closes. */
    const val CONNECT_HELLO_SECONDS = 30L
    /** §3.7: a file accepted on its own, and what one person may send in a day. */
    const val FILE_AUTO_BYTES = 50L * 1024 * 1024
    const val FILE_BYTES_PER_DAY = 2L * 1024 * 1024 * 1024
    /** §4.1: a shared folder. */
    const val FOLDER_FILES = 500
    const val FOLDER_BYTES = 1L * 1024 * 1024 * 1024
    /** §4.1's limits that stay: events accepted on their own, reminders, from one person in a day. */
    const val EVENTS_AUTO_PER_DAY = 3
    /** Free/busy on its own looks back at most this far (seconds): an ask for further back is your card. */
    const val FB_ALONE_LOOK_BACK = 3_600L
    const val REMINDERS_PER_DAY = 3
    /** lists-plan.md §9: changes to shared lists from one person in an hour, counted one by one inside `ls.op`. */
    const val LIST_OPS_PER_HOUR = 60
    /** §4.7, reserved with payments (after the full test): payment hand-offs per person in a day. */
    const val PAYMENT_HANDOFFS_PER_DAY = 3

    const val HOUR = 3_600L
    const val DAY = 86_400L

    /** Another pairing may start: under [PAIRINGS] in all and [NEW_PAIRINGS_PER_DAY] in the last day. */
    fun canPair(pairings: Int, pairedLastDay: Int): Boolean = pairings < PAIRINGS && pairedLastDay < NEW_PAIRINGS_PER_DAY

    /** The outbox takes one more request for this person. */
    fun canQueue(total: Int, forPerson: Int): Boolean = total < OUTBOX && forPerson < OUTBOX_PER_PERSON

    /** Another bound or dialled connection, with [open] of them open. */
    fun canConnect(open: Int): Boolean = open < LIVE_CONNECTIONS

    /** L5-fix: another connection accepted, with [unbound] still waiting for their hello. */
    fun canAccept(unbound: Int): Boolean = unbound < UNBOUND_CONNECTIONS

    /** Of [waiting] cards from one person, how many show as cards and how many as one "N more from Sam". */
    fun cards(waiting: Int): Pair<Int, Int> = minOf(waiting, CARDS_PER_PERSON).let { it to (waiting - it).coerceAtLeast(0) }
}

/**
 * A sum over a sliding [window] (seconds) that may not pass [max]: requests (weight 1), list changes, bytes. Only what was
 * let in counts: a refused request adds nothing, so a flood can't keep a pause going by itself.
 */
class SlidingSum(val window: Long, val max: Long) {
    private val times = ArrayDeque<Long>()
    private val weights = ArrayDeque<Long>()
    private var sum = 0L

    init {
        require(window > 0 && max > 0)
    }

    private fun drop(now: Long) {
        while (times.isNotEmpty() && times.first() <= now - window) { sum -= weights.removeFirst(); times.removeFirst() }
    }

    fun total(now: Long): Long { drop(now); return sum }

    /** What's still in the window, the oldest first, as (time, weight). */
    fun entries(now: Long): List<Pair<Long, Long>> { drop(now); return times.zip(weights) }

    /** [weight] more fits at [now]. */
    fun room(now: Long, weight: Long = 1): Boolean { drop(now); return weight in 0..max - sum }

    fun add(now: Long, weight: Long = 1) {
        require(weight > 0)
        drop(now)
        require(times.isEmpty() || now >= times.last())
        times.addLast(now); weights.addLast(weight); sum += weight
    }

    /** When [weight] will fit again, if nothing else is added: [now] if it fits now; never ([Long.MAX_VALUE]) if it never can. */
    fun freeAt(now: Long, weight: Long = 1): Long {
        drop(now)
        if (weight > max) return Long.MAX_VALUE
        var s = sum
        if (s + weight <= max) return now
        for (i in times.indices) {
            s -= weights[i]
            if (s + weight <= max) return times[i] + window
        }
        return now
    }
}

/** What the rate limits make of a request from one person. */
sealed class RateVerdict {
    object Ok : RateVerdict() {
        override fun toString(): String = "Ok" // l10n: not text (toString for logs and tests)
    }

    /**
     * Answered `busy` until [until] (Unix seconds: "paused until 14:00"). [notify]: the first refusal of this pause, the
     * one that raises "Sam's Robot sent too many requests: paused until 14:00" and offers Block; later ones are quiet.
     */
    data class Busy(val until: Long, val notify: Boolean) : RateVerdict()
}

/**
 * §4.6's rate limits for one person: 60 requests an hour and 300 a day, and lists' 60 changes an hour. Acks and `unpair`
 * never count and are never refused: an ack is never answered (no loops), and an unpair always ends the pairing.
 */
class PeerRate {
    private val hour = SlidingSum(LinkLimits.HOUR, LinkLimits.REQUESTS_PER_HOUR.toLong())
    private val day = SlidingSum(LinkLimits.DAY, LinkLimits.REQUESTS_PER_DAY.toLong())
    private val listOps = SlidingSum(LinkLimits.HOUR, LinkLimits.LIST_OPS_PER_HOUR.toLong())
    private var pausedUntil = 0L

    /** A request of [type] (with [ops] list changes, for `ls.op`) at [now]: let in and counted, or busy. */
    fun admit(type: RequestType, now: Long, ops: Int = 0): RateVerdict {
        if (!counts(type)) return RateVerdict.Ok
        val w = ops.toLong()
        if (hour.room(now) && day.room(now) && (w == 0L || listOps.room(now, w))) {
            hour.add(now); day.add(now)
            if (w > 0) listOps.add(now, w)
            return RateVerdict.Ok
        }
        val until = maxOf(hour.freeAt(now), day.freeAt(now), if (w > 0) listOps.freeAt(now, w) else now)
        val notify = now >= pausedUntil
        pausedUntil = maxOf(pausedUntil, until)
        return RateVerdict.Busy(until, notify)
    }

    /** The `ls.op` count, for its record next to the replay window: a restart of :link doesn't give a peer a fresh hour. */
    fun savedListOps(now: Long): ByteArray {
        val e = listOps.entries(now)
        val b = java.nio.ByteBuffer.allocate(e.size * 16)
        for ((t, w) in e) { b.putLong(t); b.putLong(w) }
        return b.array()
    }

    /** [saved] back, what's still within the hour; a time past [now] (the clock moved) or a wrong record counts as nothing. */
    fun restoreListOps(saved: ByteArray, now: Long) {
        if (saved.size % 16 != 0) return
        val b = java.nio.ByteBuffer.wrap(saved)
        val e = List(saved.size / 16) { b.long to b.long }
        for ((t, w) in e.sortedBy { it.first }) if (t in (now - LinkLimits.HOUR + 1)..now && w > 0 && listOps.room(t, w)) listOps.add(t, w)
    }

    companion object {
        fun counts(type: RequestType): Boolean = type != RequestType.ACK && type != RequestType.UNPAIR
    }
}

/**
 * §3.4: the outbox tries again after 1 minute, then waits twice as long each time up to 6 hours, until the request's
 * `at + ttl`; then it gives up and says "Couldn't reach Sam's Robot for 7 days. Try again?".
 */
object Backoff {
    const val FIRST_SECONDS = 60L
    const val MAX_SECONDS = 6 * 3_600L

    /** The wait after try number [attempt] (0 for the first try) failed. */
    fun delay(attempt: Int): Long {
        require(attempt >= 0)
        if (attempt >= 20) return MAX_SECONDS
        return minOf(FIRST_SECONDS shl attempt, MAX_SECONDS)
    }

    /** When to try again after try [attempt] failed at [now]; null when that would be past [expiresAt]: give up. */
    fun next(attempt: Int, now: Long, expiresAt: Long): Long? = (now + delay(attempt)).takeIf { it <= expiresAt }
}
