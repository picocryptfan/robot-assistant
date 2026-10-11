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

    @Test fun stopEndsTheWaitAndAbortsTheWork() = runBlocking {
        val released = CountDownLatch(1)
        val job = async(Dispatchers.Default) { OnlineRun.run(60_000, stuck(released)) }
        assertTrue(running.await(2, TimeUnit.SECONDS))
        withTimeout(2_000) { job.cancelAndJoin() }
        assertTrue(job.isCancelled)
        assertTrue(released.await(2, TimeUnit.SECONDS))
    }
}
