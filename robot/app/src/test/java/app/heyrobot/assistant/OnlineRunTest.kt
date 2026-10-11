package app.heyrobot.assistant

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The weather's blocking work ends on Stop and at its deadline, even when it ignores interrupts (DNS, sockets). */
class OnlineRunTest {
    private val running = CountDownLatch(1)

    /** Blocks until its abort fires (as a disconnect would end a read), ignoring interrupts. */
    private fun stuck(released: CountDownLatch) = { abort: app.heyrobot.core.Abort ->
        running.countDown()
        val gate = CountDownLatch(1)
        abort.onCancel { gate.countDown(); released.countDown() }
        while (gate.count > 0) try { gate.await() } catch (_: InterruptedException) {}
        "late"
    }

    @Test fun theResultComesThrough() = runBlocking {
        assertEquals("ok", OnlineRun.run(5_000) { "ok" })
    }

    @Test fun theDeadlineEndsTheWaitAndAbortsTheWork() = runBlocking {
        val released = CountDownLatch(1)
        val started = System.nanoTime()
        assertNull(OnlineRun.run(300, stuck(released)))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }

    /**
     * The nearest place found just as its deadline passed: the lookup and the panel's fallback search both want to open
     * Organic Maps, and only the first to [app.heyrobot.core.Abort.claim] does, however their timing falls (0.4.0 review).
     */
    @Test fun onlyOneSideOpensAnAppAroundTheDeadline() = runBlocking {
        for (lateMs in listOf(0L, 150L, 250L, 400L)) {
            val opened = java.util.concurrent.atomic.AtomicInteger()
            val done = CountDownLatch(1)
            val abort = app.heyrobot.core.Abort(200)
            val r = OnlineRun.run(abort, 200) { a ->
                try {
                    Thread.sleep(lateMs)
                } catch (_: InterruptedException) {}
                // As ToolExecutor.nearest: nothing after Stop or the deadline, and only with the claim.
                if (!a.cancelled && a.claim()) opened.incrementAndGet()
                done.countDown()
                "opened"
            }
            // As PanelActions.run: the deadline passed, the search instead unless the lookup claimed the launch.
            if (r == null && abort.claim()) opened.incrementAndGet()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals("late $lateMs ms", 1, opened.get())
        }
    }

    @Test fun stopEndsTheWaitAndAbortsTheWork() = runBlocking {
        val released = CountDownLatch(1)
        val job = async(Dispatchers.Default) { OnlineRun.run(60_000, stuck(released)) }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }
}
