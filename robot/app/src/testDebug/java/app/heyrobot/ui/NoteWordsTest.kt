// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.sys.NotesFiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** F70: a very large note must not crash the app with saved state too large, and must come back whole. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NoteWordsTest {
    init { app.heyrobot.sys.NotesFiles.testKeys(app.heyrobot.sys.SoftHistoryKeys()) }
    @get:Rule val rule = createComposeRule()
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val scope = SaverScope { true }

    private fun roundTrip(w: NoteWords): NoteWords? = with(NoteWordsSaver) { scope.save(w) }?.let { NoteWordsSaver.restore(it) }

    @Test fun aNormalNoteIsKeptWithoutASecondCopyOfItsWords() {
        val clean = NoteWords("Groceries", "Milk", noteKey("Groceries", "Milk"), true)
        val r = roundTrip(clean)!!
        assertEquals("Groceries", r.title)
        assertEquals("Milk", r.text)
        assertEquals(noteKey("Groceries", "Milk"), r.saved)
        assertTrue(r.loaded)
        // Typed since the last write: still to be written after the rotation.
        assertNull(roundTrip(NoteWords("Groceries", "Milk and eggs", noteKey("Groceries", "Milk"), true))!!.saved)
        val saved = with(NoteWordsSaver) { scope.save(NoteWords("t", "x".repeat(NOTE_MAX_SAVED_CHARS - 1), noteKey("t", "x".repeat(NOTE_MAX_SAVED_CHARS - 1)), true)) } as List<*>
        assertEquals(NOTE_MAX_SAVED_CHARS, saved.filterIsInstance<String>().sumOf { it.length })
    }

    @Test fun aHugeNoteLeavesNothingInSavedState() {
        val huge = "x".repeat(2_000_000)
        assertNull(with(NoteWordsSaver) { scope.save(NoteWords("t", huge, noteKey("t", huge), true)) })
    }

    /** The rotation of a note too big to save: read again by its id, and never written back blank. */
    @Test fun aNoteTooBigToSaveIsReadAgainAndStaysWhole() {
        val store = NotesFiles.store(ctx)
        val big = "word ".repeat(NOTE_MAX_SAVED_CHARS / 4)
        val n = store.add(big, "Big one", 1L)!!
        val tester = StateRestorationTester(rule)
        tester.setContent { NoteScreen(n.id, 0, app.heyrobot.ui.testing.ActionLog().actions, SnackbarHostState(), {}, {}) }
        fun shown() = rule.onAllNodesWithText("Big one").fetchSemanticsNodes().isNotEmpty()
        rule.waitUntil(5_000) { shown() }
        tester.emulateSavedInstanceStateRestore()
        rule.waitUntil(5_000) { shown() }
        rule.mainClock.advanceTimeBy(1_000)
        rule.waitForIdle()
        assertEquals(big.trim(), store.get(n.id)?.text)
        assertEquals("Big one", store.get(n.id)?.title)
        assertFalse(store.get(n.id)?.text.isNullOrBlank())
    }
}
