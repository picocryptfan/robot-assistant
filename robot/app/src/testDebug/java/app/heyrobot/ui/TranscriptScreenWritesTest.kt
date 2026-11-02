// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.os.Looper
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.BrainModel
import app.heyrobot.core.ConvMode
import app.heyrobot.core.LangPair
import app.heyrobot.core.SummaryRecord
import app.heyrobot.core.TranscriptWriter
import app.heyrobot.sys.Transcripts
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A transcript's summaries are items of its lazy list, so the list reads them while it measures. Written from the IO
 * thread, that write can land while the list is working out its items, and the list then keeps the old ones for good
 * (flakes-2.md). So the page reads on the IO thread and writes its state back on the main thread.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TranscriptScreenWritesTest {
    @get:Rule val rule = createComposeRule()

    @Test fun thePageWritesWhatItReadOnTheMainThread() {
        val store = Transcripts.store(ApplicationProvider.getApplicationContext())
        val start = 1_791_000_000_000L
        val w = TranscriptWriter.start(store, "20261011-1400-0003", start, ConvMode.TRANSCRIBE, LangPair.FR_EN)!!
        store.addSummary(w.id, SummaryRecord("robot", BrainModel.FAST.id, SummaryRecord.VIA_PHONE, start, "Overview of the day", parts = 2))
        val off = CopyOnWriteArrayList<String>()
        val watch = Snapshot.registerGlobalWriteObserver { if (Looper.myLooper() != Looper.getMainLooper()) off += Thread.currentThread().name }
        try {
            rule.show { TranscriptScreen(w.id, null, SnackbarHostState(), onBack = {}) }
            rule.waitUntil(5_000) { rule.onAllNodes(hasText("Overview of the day", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            assertEquals(emptyList<String>(), off.toList())
        } finally {
            watch.dispose()
        }
    }
}
