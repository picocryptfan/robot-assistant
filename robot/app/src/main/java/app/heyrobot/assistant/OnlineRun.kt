package app.heyrobot.assistant

import app.heyrobot.core.Abort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// VOICE PROCESS. How an online tool (the weather, 0.4.0) runs: its blocking work on a thread of its own, so Stop (the
// turn's cancellation) and an overall deadline end the wait at once, even while DNS or a socket ignores interrupts. The
// work is told to give up ([Abort]: its connection is disconnected, a location wait woken) and a late result is dropped.

object OnlineRun {
    /** [block]'s result, or null when [deadlineMs] passed first. Cancellation aborts the work and is rethrown. */
    suspend fun <T : Any> run(deadlineMs: Long, block: (Abort) -> T): T? {
        val abort = Abort(deadlineMs)
        val result = CompletableDeferred<T>()
        val worker = Thread({
            try { result.complete(block(abort)) } catch (t: Throwable) { result.completeExceptionally(t) }
        }, "robot-online")
        worker.isDaemon = true
        worker.start()
        try {
            return withTimeoutOrNull(deadlineMs) { result.await() } ?: run {
                abort.cancel()
                worker.interrupt()
                null
            }
        } catch (e: CancellationException) {
            abort.cancel()
            worker.interrupt()
            throw e
        }
    }
}
