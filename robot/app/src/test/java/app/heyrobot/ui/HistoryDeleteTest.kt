// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

/** seal-history-fix-b 6: a delete cancelled half way (Back, a rotation) still tells the panel, so it never writes the conversation back. */
class HistoryDeleteTest {
    @Test fun aDeleteCancelledHalfWayStillTellsThePanel() = runBlocking {
        val started = CountDownLatch(1)
        val go = CountDownLatch(1)
        val told = AtomicBoolean(false)
        val job = launch(Dispatchers.Default) { deleteAndTell({ started.countDown(); go.await() }) { told.set(true) } }
        started.await()
        job.cancel()
        go.countDown()
        job.join()
        assertTrue(told.get())
    }
}
