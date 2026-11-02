// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.link.requests.RequestType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** robot-to-robot-plan.md §3.4, §3.7, §4.6 and §9.1's LinkLimitsTest: per-person rates, waiting cards, outbox caps, backoff. */
class LinkLimitsTest {
    @Test fun theListChangeCountOutlivesARestart() {
        val t = 1_800_000_000L
        val a = PeerRate()
        assertEquals(RateVerdict.Ok, a.admit(RequestType.LS_OP, t, 40))
        assertEquals(RateVerdict.Ok, a.admit(RequestType.LS_OP, t + 1, 20))
        val saved = a.savedListOps(t + 2)
        val b = PeerRate().also { it.restoreListOps(saved, t + 2) }
        assertTrue(b.admit(RequestType.LS_OP, t + 2, 1) is RateVerdict.Busy)
        val later = PeerRate().also { it.restoreListOps(saved, t + LinkLimits.HOUR + 2) }
        assertEquals(RateVerdict.Ok, later.admit(RequestType.LS_OP, t + LinkLimits.HOUR + 2, 40))
        val clockBack = PeerRate().also { it.restoreListOps(saved, t - 5) }
        assertEquals(RateVerdict.Ok, clockBack.admit(RequestType.LS_OP, t - 5, 40))
        PeerRate().restoreListOps(ByteArray(5), t)
    }

    private val now = 1_790_000_000L

    @Test fun sixtyAnHourThenBusyWithOneNotification() {
        // RR11: Sam's Robot sends 70 requests in an hour.
        val rate = PeerRate()
        for (i in 0 until 60) assertEquals(RateVerdict.Ok, rate.admit(RequestType.FB_ASK, now + i * 10))
        val first = rate.admit(RequestType.FB_ASK, now + 600)
        assertEquals(RateVerdict.Busy(now + 3_600, notify = true), first)
        for (i in 1 until 10) {
            val v = rate.admit(RequestType.EV_PROPOSE, now + 600 + i) as RateVerdict.Busy
            assertFalse(v.notify)
            assertEquals(now + 3_600, v.until)
        }
        // Refused requests don't count: one hour after the first, room for one more.
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.FB_ASK, now + 3_600))
        assertTrue(rate.admit(RequestType.FB_ASK, now + 3_601) is RateVerdict.Busy)
        // A new pause after the last one ended notifies again.
        val later = PeerRate()
        repeat(60) { later.admit(RequestType.ETA_TELL, now) }
        assertTrue((later.admit(RequestType.ETA_TELL, now) as RateVerdict.Busy).notify)
        repeat(60) { later.admit(RequestType.ETA_TELL, now + 3_600) }
        assertTrue((later.admit(RequestType.ETA_TELL, now + 3_600) as RateVerdict.Busy).notify)
    }

    @Test fun threeHundredADay() {
        val rate = PeerRate()
        // 60 an hour for five hours: 300.
        for (h in 0 until 5) for (i in 0 until 60) assertEquals(RateVerdict.Ok, rate.admit(RequestType.FB_ASK, now + h * 3_600 + i))
        // The sixth hour has room by the hour, not by the day: paused until the first of the 300 is a day old.
        assertEquals(RateVerdict.Busy(now + 86_400, notify = true), rate.admit(RequestType.FB_ASK, now + 5 * 3_600))
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.FB_ASK, now + 86_400))
    }

    @Test fun acksAndUnpairNeverCountAndAreNeverRefused() {
        val rate = PeerRate()
        repeat(60) { rate.admit(RequestType.CAPS, now) }
        assertTrue(rate.admit(RequestType.CAPS, now) is RateVerdict.Busy)
        repeat(1_000) { assertEquals(RateVerdict.Ok, rate.admit(RequestType.ACK, now)) }
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.UNPAIR, now))
        assertFalse(PeerRate.counts(RequestType.ACK))
        assertFalse(PeerRate.counts(RequestType.UNPAIR))
        assertTrue(RequestType.entries.filter { !PeerRate.counts(it) }.all { !it.answeredByAck })
    }

    @Test fun listChangesCountOneByOne() {
        val rate = PeerRate()
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.LS_OP, now, ops = 40))
        // 40 + 30 is past 60 an hour, though it is only the second request.
        val busy = rate.admit(RequestType.LS_OP, now + 60, ops = 30) as RateVerdict.Busy
        assertEquals(now + 3_600, busy.until)
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.LS_OP, now + 120, ops = 20))
        // Other requests still go.
        assertEquals(RateVerdict.Ok, rate.admit(RequestType.FB_ASK, now + 180))
    }

    @Test fun slidingSumsAreExact() {
        val bytes = SlidingSum(LinkLimits.DAY, LinkLimits.FILE_BYTES_PER_DAY)
        val gb = 1L shl 30
        assertTrue(bytes.room(now, 2 * gb))
        bytes.add(now, gb)
        bytes.add(now + 100, gb / 2)
        assertTrue(bytes.room(now + 200, gb / 2))
        assertFalse(bytes.room(now + 200, gb / 2 + 1))
        assertEquals(now + 86_400, bytes.freeAt(now + 200, gb))
        assertEquals(now + 200, bytes.freeAt(now + 200, gb / 2))
        assertEquals(Long.MAX_VALUE, bytes.freeAt(now, 3 * gb))
        assertEquals(gb / 2, bytes.total(now + 86_400))
        assertEquals(0, bytes.total(now + 86_500))
        // The limits that stay count the same way: three events on their own a day.
        val events = SlidingSum(LinkLimits.DAY, LinkLimits.EVENTS_AUTO_PER_DAY.toLong())
        repeat(3) { assertTrue(events.room(now + it)); events.add(now + it) }
        assertFalse(events.room(now + 10))
        assertTrue(events.room(now + 86_400))
    }

    @Test fun waitingCardsOutboxPairingsAndConnections() {
        assertEquals(3 to 0, LinkLimits.cards(3))
        assertEquals(10 to 0, LinkLimits.cards(10))
        assertEquals(10 to 2, LinkLimits.cards(12))
        assertEquals(0 to 0, LinkLimits.cards(0))
        assertTrue(LinkLimits.canQueue(499, 199))
        assertFalse(LinkLimits.canQueue(500, 0))
        assertFalse(LinkLimits.canQueue(10, 200))
        assertTrue(LinkLimits.canPair(99, 9))
        assertFalse(LinkLimits.canPair(100, 0))
        assertFalse(LinkLimits.canPair(5, 10))
        assertTrue(LinkLimits.canConnect(7))
        assertFalse(LinkLimits.canConnect(8))
        // L5-fix: connections still waiting for their hello have their own, smaller cap.
        assertTrue(LinkLimits.canAccept(3))
        assertFalse(LinkLimits.canAccept(4))
        assertEquals(50L * 1024 * 1024, LinkLimits.FILE_AUTO_BYTES)
    }

    @Test fun backoffDoublesToSixHoursUntilTheTtl() {
        assertEquals(listOf(60L, 120L, 240L, 480L, 960L, 1_920L, 3_840L, 7_680L, 15_360L, 21_600L, 21_600L), (0..10).map(Backoff::delay))
        assertEquals(21_600L, Backoff.delay(1_000))
        // A request that lives 7 days: tried until then, then given up.
        val expires = now + 7 * 86_400
        var t = now
        var attempt = 0
        while (true) {
            val n = Backoff.next(attempt, t, expires) ?: break
            assertTrue(n <= expires)
            t = n
            attempt++
        }
        assertTrue(t > expires - 21_600)
        assertEquals(35, attempt)
        assertNull(Backoff.next(0, expires, expires))
        assertEquals(expires, Backoff.next(0, expires - 60, expires))
    }
}
