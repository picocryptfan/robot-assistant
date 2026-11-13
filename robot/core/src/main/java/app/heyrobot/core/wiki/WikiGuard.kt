// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

// Pure (coroutines). M4 (review-wikipedia-p4): the copy's calls to the :wiki process block on a binder with no deadline of
// its own. [WikiGuard.within] gives the open and the search one: past it, or when you stop the answer, the link is closed
// (the process ends, so the blocked call returns) and the model answers alone. [WikiBroken]: the files that crashed :wiki.
object WikiGuard {
    /** The copy's open and search together, before the model's one-shot. */
    const val DEADLINE_MS = 8_000L

    /** [block]'s result, or null past [ms]; past [ms] or on cancellation (stop), [close] is called once, while [block] still runs. */
    suspend fun <T> within(ms: Long, close: () -> Unit, block: () -> T): T? = coroutineScope {
        val ended = AtomicBoolean(false)
        // review-wikipedia-p4-b: the watchdog closes in its finally, so a stop (which cancels it at once) closes the link while
        // runInterruptible still waits on a binder call no interrupt breaks; closing ends that call.
        val dog = launch { try { delay(ms) } finally { if (ended.compareAndSet(false, true)) close() } }
        try {
            val r = runInterruptible(Dispatchers.IO) { block() }
            if (ended.compareAndSet(false, true)) r else null
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            ended.set(true)
            throw t
        } finally {
            dog.cancel()
        }
    }
}

/** M4: the copies that crashed the :wiki process (by file, size and date): said once, then left out until downloaded again. */
class WikiBroken {
    private val keys = ConcurrentHashMap.newKeySet<String>()

    fun skips(key: String): Boolean = key in keys

    /** True the first time [key] is noted: the reply says so. */
    fun note(key: String): Boolean = keys.add(key)
}
