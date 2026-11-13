// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS

// The job's stop (review-signal-later-b 3): each start has its own flag, so a stop reaches the start it was for, and a
// new start never un-stops an old thread still running.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LaterJobServiceTest {
    private val saved = LaterJobService.runner

    @After fun restore() {
        LaterJobService.runner = saved
    }

    @Test fun aStopReachesOnlyItsOwnStart() {
        val stops = LinkedBlockingQueue<() -> Boolean>()
        val go = CountDownLatch(1)
        LaterJobService.runner = { _, _, stopped -> stops.put(stopped); go.await(5, SECONDS); true }
        val finished = LinkedBlockingQueue<Boolean>()
        val s = Robolectric.setupService(LaterJobService::class.java)
        assertTrue(s.start(null) { finished.put(it) })
        val first = stops.poll(5, SECONDS)!!
        s.stopNow()
        assertTrue(s.start(null) { finished.put(it) })
        val second = stops.poll(5, SECONDS)!!
        assertTrue(first())
        assertFalse(second())
        go.countDown()
        // The stopped one doesn't ask to run again; the running one does.
        assertEquals(setOf(false, true), setOf(finished.poll(5, SECONDS), finished.poll(5, SECONDS)))
    }
}
