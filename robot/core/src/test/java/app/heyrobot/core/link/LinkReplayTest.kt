// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** robot-to-robot-plan.md §3.4 and §9.1's LinkReplayTest: seen ids, `ttl`, future `at`, per-thing counters, late changes ignored. */
class LinkReplayTest {
    private val now = 1_790_000_000L
    private val week = 7 * 86_400L
    private fun id(n: Int) = "%032x".format(n + 1)

    @Test fun aSeenIdIsRefused() {
        val w = ReplayWindow()
        assertEquals(Admit.ACCEPTED, w.admit(id(1), now - 60, week, now))
        assertEquals(Admit.REPEAT, w.admit(id(1), now - 60, week, now + 5))
        // The same id with another `at` or ttl is still the same request.
        assertEquals(Admit.REPEAT, w.admit(id(1), now - 30, 2 * week, now + 5))
        assertEquals(Admit.ACCEPTED, w.admit(id(2), now - 60, week, now + 5))
        assertTrue(w.seen(id(1)))
        assertFalse(w.seen(id(3)))
        // L5-fix: one answered `busy` is forgotten, so the same request sent again after the pause is let in, and kept then.
        w.forget(id(2))
        assertFalse(w.seen(id(2)))
        assertEquals(2 - 1, w.size)
        assertEquals(Admit.ACCEPTED, w.admit(id(2), now - 60, week, now + 3_600))
        assertEquals(Admit.REPEAT, w.admit(id(2), now - 60, week, now + 3_601))
        w.forget(id(9))
        assertEquals(2, w.size)
        assertEquals(ReplayWindow.decode(w.encode())!!.size, 2)
    }

    @Test fun ttlAndAFutureAt() {
        val w = ReplayWindow()
        // Past its ttl: expired. At its last second: still in time.
        assertEquals(Admit.EXPIRED, w.admit(id(1), now - week - 1, week, now))
        assertEquals(Admit.ACCEPTED, w.admit(id(2), now - week, week, now))
        // Up to 10 minutes ahead of this clock is a clock difference; more is refused.
        assertEquals(Admit.ACCEPTED, w.admit(id(3), now + 600, week, now))
        assertEquals(Admit.FUTURE, w.admit(id(4), now + 601, week, now))
        // An id is kept until its request can't be accepted any more, then forgotten: a late copy is expired anyway.
        assertEquals(Admit.REPEAT, w.admit(id(3), now + 600, week, now + week + 600))
        assertEquals(Admit.EXPIRED, w.admit(id(3), now + 600, week, now + week + 601))
        w.purge(now + week + 1_201)
        assertFalse(w.seen(id(3)))
        assertEquals(Admit.EXPIRED, w.admit(id(3), now + 600, week, now + week + 1_201))
    }

    @Test fun aFullWindowNeverLetsAnEvictedIdBack() {
        val w = ReplayWindow()
        // 5,000 requests a second apart, each living 30 days, all still live.
        for (i in 0 until 5_000) assertEquals(Admit.ACCEPTED, w.admit(id(i), now + i, 30 * 86_400L, now + i))
        assertEquals(5_000, w.size)
        // The 5,001st: the oldest goes, and the floor rises to its `at`.
        assertEquals(Admit.ACCEPTED, w.admit(id(5_000), now + 5_000, 30 * 86_400L, now + 5_000))
        assertEquals(5_000, w.size)
        assertEquals(now, w.floor)
        assertFalse(w.seen(id(0)))
        // The first one replayed, while its ttl would still allow it: refused.
        assertEquals(Admit.EXPIRED, w.admit(id(0), now, 30 * 86_400L, now + 5_001))
        // A new request older than everything in a full window: refused, and nothing is evicted for it.
        assertEquals(Admit.EXPIRED, w.admit(id(9_999), now + 1, 30 * 86_400L, now + 5_002))
        assertTrue(w.seen(id(1)))
        assertEquals(now, w.floor)
        // Newer ones keep coming in.
        assertEquals(Admit.ACCEPTED, w.admit(id(5_001), now + 5_001, 30 * 86_400L, now + 5_003))
        assertEquals(now + 1, w.floor)
        assertEquals(Admit.EXPIRED, w.admit(id(1), now + 1, 30 * 86_400L, now + 5_004))
    }

    @Test fun theWindowIsKeptAndReadStrictly() {
        val w = ReplayWindow(max = 10)
        for (i in 0 until 12) w.admit(id(i), now + i, week, now + i)
        val bytes = w.encode()
        val back = ReplayWindow.decode(bytes, max = 10)!!
        assertArrayEquals(bytes, back.encode())
        assertEquals(w.floor, back.floor)
        assertEquals(Admit.REPEAT, back.admit(id(11), now + 11, week, now + 20))
        assertEquals(Admit.EXPIRED, back.admit(id(0), now, week, now + 20))
        assertNull(ReplayWindow.decode(bytes + 0, max = 10))
        assertNull(ReplayWindow.decode(bytes.copyOf(bytes.size - 1), max = 10))
        assertNull(ReplayWindow.decode(bytes, max = 5))
        assertNull(ReplayWindow.decode(bytes.copyOf().also { it[0] = 'X'.code.toByte() }, max = 10))
        // Entries out of order: refused.
        val swapped = bytes.copyOf()
        val first = 14
        for (i in 0 until 32) { val t = swapped[first + i]; swapped[first + i] = swapped[first + 32 + i]; swapped[first + 32 + i] = t }
        assertNull(ReplayWindow.decode(swapped, max = 10))
        assertNotNull(ReplayWindow.decode(ReplayWindow().encode()))
    }

    @Test fun theHighestCounterWinsAndLateChangesAreIgnored() {
        val ev = id(100)
        val o = ThingOrder()
        assertEquals(1L, o.next(ev))
        assertEquals(Order.NEWER, o.offer(ev, 1, id(1), now))
        assertEquals(Order.NEWER, o.offer(ev, 3, id(3), now + 1))
        // The change at 2 arrives late: ignored.
        assertEquals(Order.STALE, o.offer(ev, 2, id(2), now + 2))
        assertEquals(3L, o.rev(ev))
        // The same change again: nothing new.
        assertEquals(Order.SAME, o.offer(ev, 3, id(3), now + 3))
        assertEquals(4L, o.next(ev))
        // One message can't push the counter to the cap and freeze the event.
        assertEquals(Order.JUMP, o.offer(ev, 3 + LinkOrder.MAX_JUMP + 1, id(4), now + 4))
        assertEquals(Order.NEWER, o.offer(ev, 3 + LinkOrder.MAX_JUMP, id(4), now + 4))
    }

    @Test fun twoPhonesChangingTheSameThingAtOnceAgree() {
        val thing = id(200)
        val (ra, rb) = id(7) to id(9)
        for (order in listOf(true, false)) {
            val a = ThingOrder().apply { offer(thing, 5, id(1), now) }
            val b = ThingOrder().apply { offer(thing, 5, id(1), now) }
            // Each changes it at 6 on its own phone, then receives the other's change.
            a.offer(thing, 6, ra, now + 1)
            b.offer(thing, 6, rb, now + 1)
            val (first, second) = if (order) a to b else b to a
            val fromSecond = if (second === b) rb else ra
            val fromFirst = if (first === a) ra else rb
            first.offer(thing, 6, fromSecond, now + 2)
            second.offer(thing, 6, fromFirst, now + 2)
            // Both end on the same change: the higher request id.
            assertEquals(Order.SAME, a.offer(thing, 6, rb, now + 3))
            assertEquals(Order.SAME, b.offer(thing, 6, rb, now + 3))
        }
        assertTrue(LinkOrder.wins(6, id(9), 6, id(7)))
        assertFalse(LinkOrder.wins(6, id(7), 6, id(9)))
        assertTrue(LinkOrder.wins(7, id(1), 6, id(9)))
        assertFalse(LinkOrder.wins(5, id(9), 6, id(1)))
    }

    @Test fun countersAreCappedAndForgottenAfterTheLongestLife() {
        val o = ThingOrder(max = 3)
        for (i in 0 until 3) assertEquals(Order.NEWER, o.offer(id(i), 1, id(50 + i), now))
        assertEquals(Order.FULL, o.offer(id(3), 1, id(60), now))
        // Changes to things it has still go.
        assertEquals(Order.NEWER, o.offer(id(0), 2, id(61), now + 10))
        // After 30 days and 20 minutes without a change, a thing is forgotten (any change to it would be expired).
        assertEquals(Order.NEWER, o.offer(id(3), 1, id(62), now + ThingOrder.KEEP_SECONDS + 1))
        assertEquals(2, o.size)
        val bytes = o.encode()
        val back = ThingOrder.decode(bytes, max = 3)!!
        assertArrayEquals(bytes, back.encode())
        assertNull(ThingOrder.decode(bytes + 1))
        assertNull(ThingOrder.decode(bytes.copyOf(bytes.size - 2)))
        assertNull(ThingOrder.decode(bytes, max = 1))
    }

    @Test fun damagedStoresNeverThrow() {
        val r = Random(1504)
        val w = ReplayWindow(max = 50).apply { for (i in 0 until 60) admit(id(i), now + i, week, now + i) }
        val o = ThingOrder().apply { for (i in 0 until 20) offer(id(i), 1L + i, id(100 + i), now) }
        for ((good, decode) in listOf<Pair<ByteArray, (ByteArray) -> Any?>>(
            w.encode() to { b -> ReplayWindow.decode(b, 50)?.encode() },
            o.encode() to { b -> ThingOrder.decode(b)?.encode() },
        )) {
            repeat(5_000) {
                val f = good.copyOf(if (r.nextInt(4) == 0) r.nextInt(good.size + 8) else good.size)
                repeat(1 + r.nextInt(3)) { if (f.isNotEmpty()) f[r.nextInt(f.size)] = r.nextInt(256).toByte() }
                val back = decode(f)
                if (back != null) assertArrayEquals(f, back as ByteArray)
            }
            repeat(2_000) { decode(ByteArray(r.nextInt(200)).also(r::nextBytes)) }
        }
    }
}
