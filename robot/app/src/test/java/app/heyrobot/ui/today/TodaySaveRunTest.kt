// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import app.heyrobot.core.HomeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** fix-126-review 5: a failed Today save shows what the file holds; leaving the page doesn't cancel a queued save. */
class TodaySaveRunTest {
    private val file = HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY)

    @Test fun aFailedSaveShowsWhatTheFileHolds() = runBlocking {
        val got = CompletableDeferred<HomeSettings?>()
        TodaySave.save(this, { null }, { file }) { got.complete(it) }
        assertEquals(file, withTimeout(5_000) { got.await() })
    }

    @Test fun leavingThePageDoesntCancelTheSave() {
        val page = CoroutineScope(Job())
        val go = CountDownLatch(1)
        val written = CountDownLatch(1)
        TodaySave.save(page, { go.await(); written.countDown(); file }, { file }) {}
        page.cancel()
        go.countDown()
        assertTrue(written.await(5, TimeUnit.SECONDS))
    }

    /** review-126-final 1: saves made back to back are written in the order they were made. */
    @Test fun savesKeepTheirOrder() {
        val page = CoroutineScope(Job())
        val order = java.util.Collections.synchronizedList(ArrayList<Int>())
        val all = CountDownLatch(200)
        repeat(200) { i -> TodaySave.save(page, { order.add(i); all.countDown(); file }, { file }) {} }
        assertTrue(all.await(10, TimeUnit.SECONDS))
        assertEquals((0 until 200).toList(), order.toList())
        page.cancel()
    }
}
