// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** M4 (review-wikipedia-p4): a deadline on the copy's calls that stop can interrupt; a file that crashes :wiki is said once, then left. */
class WikiGuardTest {
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1_000_000

    @Test fun aCallPastItsDeadlineClosesTheLink() = runBlocking {
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val t0 = System.nanoTime()
        val r = WikiGuard.within(200, { closed.incrementAndGet(); released.countDown() }) { released.await(5, TimeUnit.SECONDS); "late" }
        assertNull(r)
        assertTrue(ms(t0) < 3_000)
        assertEquals(1, closed.get())
    }

    @Test fun stopClosesTheLink() = runBlocking {
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val job = launch(Dispatchers.Default) { WikiGuard.within(10_000, { closed.incrementAndGet(); released.countDown() }) { released.await(5, TimeUnit.SECONDS); "x" } }
        delay(200)
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(ms(t0) < 3_000)
        assertEquals(1, closed.get())
    }

    /** review-wikipedia-p4-b: a binder call no interrupt breaks; stop still closes the link, which ends the call. */
    @Test fun stopClosesTheLinkWhileACallHangs() = runBlocking {
        val released = CountDownLatch(1)
        val closed = AtomicInteger()
        val job = launch(Dispatchers.Default) {
            WikiGuard.within(10_000, { closed.incrementAndGet(); released.countDown() }) {
                val end = System.nanoTime() + 6_000_000_000L
                while (released.count > 0 && System.nanoTime() < end) try { released.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) {}
                "x"
            }
        }
        delay(200)
        val t0 = System.nanoTime()
        job.cancelAndJoin()
        assertTrue(ms(t0) < 3_000)
        assertEquals(1, closed.get())
    }

    @Test fun anErrorLeavesTheLinkToItsOwner() = runBlocking {
        val closed = AtomicInteger()
        assertTrue(runCatching { WikiGuard.within(5_000, { closed.incrementAndGet() }) { error("x") } }.isFailure)
        assertEquals(0, closed.get())
    }

    @Test fun inTimeTheLinkIsLeftOpen() = runBlocking {
        val closed = AtomicInteger()
        assertEquals("ok", WikiGuard.within(5_000, { closed.incrementAndGet() }) { "ok" })
        assertEquals(0, closed.get())
    }

    @Test fun aBrokenFileIsSaidOnceThenLeft() {
        val b = WikiBroken()
        assertFalse(b.skips("f"))
        assertTrue(b.note("f"))
        assertTrue(b.skips("f"))
        assertFalse(b.note("f"))
        assertFalse(b.skips("g"))
    }
}
