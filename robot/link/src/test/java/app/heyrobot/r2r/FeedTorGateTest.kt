// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedTorGateTest {
    @Test fun torStillStartingPastTheBoundSaysStartingAndGoesOn() {
        val done = CountDownLatch(1)
        val starts = AtomicInteger()
        val g = FeedTorGate({ starts.incrementAndGet(); done.await(5, TimeUnit.SECONDS) }, waitMs = 50)
        val t0 = System.nanoTime()
        assertEquals(FeedTorGate.State.STARTING, g.state())
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0) < 2_000)
        // Asked again while it starts: the same start, not a second one.
        assertEquals(FeedTorGate.State.STARTING, g.state())
        assertEquals(1, starts.get())
        done.countDown()
        assertEquals(FeedTorGate.State.UP, g.state())
    }

    @Test fun torThatCantStartIsDown() {
        assertEquals(FeedTorGate.State.DOWN, FeedTorGate({ false }, waitMs = 1_000).state())
        assertEquals(FeedTorGate.State.DOWN, FeedTorGate({ throw TorException(TorProblem.NOT_READY) }, waitMs = 1_000).state())
        assertEquals(FeedTorGate.State.UP, FeedTorGate({ true }, waitMs = 1_000).state())
    }
}
