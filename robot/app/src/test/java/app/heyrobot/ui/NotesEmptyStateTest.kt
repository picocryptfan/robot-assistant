// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.core.Note
import app.heyrobot.sys.NotesRead
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-seal-lows-c 5: Notes shows no "no notes" while notes are held; the card above says why. */
class NotesEmptyStateTest {
    @Test fun heldNotesHideTheEmptyState() {
        assertFalse(notesEmptyShown(emptyList(), NotesRead(emptyList(), notNow = 1)))
        assertFalse(notesEmptyShown(emptyList(), NotesRead(emptyList(), tooNew = 1)))
        assertTrue(notesEmptyShown(emptyList(), NotesRead(emptyList(), aside = 1)))
        assertTrue(notesEmptyShown(listOf(Note("n1", "", "Row F", 1L, 2L)), NotesRead(emptyList(), notNow = 1)))
    }
}
