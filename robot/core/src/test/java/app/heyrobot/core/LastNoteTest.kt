// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test Bench everyday-1.2.2-a, bug 5: "read me my last note" made find_notes look for a note about "last note" and found
 * none. "Last", "latest", "newest", « dernière » say which note, not what it is about.
 */
class LastNoteTest {
    private val older = Note("a", "", "the plumber comes Thursday", 1_000, 1_000)
    private val newer = Note("b", "", "call the bank", 2_000, 2_000)
    private val notes = listOf(older, newer)

    @Test fun theLastNoteIsTheNewest() {
        for (q in listOf("last note", "my last note", "latest note", "the most recent note", "newest", "ma dernière note", "la note la plus récente"))
            assertEquals(q, listOf(newer), NoteSearch.find(q, notes))
    }

    @Test fun lastWithAWordStillSearches() {
        assertEquals(listOf(older), NoteSearch.find("last note about the plumber", notes))
        assertEquals(listOf(newer, older), NoteSearch.find("my notes", notes))
    }
}
