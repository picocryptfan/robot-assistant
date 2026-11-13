// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import app.heyrobot.core.Note
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.sys.NotesRead
import org.junit.Assert.assertEquals
import org.junit.Test

/** fix-seal-lows-c 4: "delete the note about…" while every note is held says they open once unlocked, not "no notes". */
class NoteDeleteNoneLineTest {
    @Test fun heldNotesAreNotNoNotes() {
        assertEquals(ToolStrings.notesLocked(), noteDeleteNoneLine(NotesRead(emptyList(), notNow = 2), "gate"))
        assertEquals(ToolStrings.notesLocked(), noteDeleteNoneLine(NotesRead(emptyList(), notNow = 1, tooNew = 1), "last"))
    }

    /** fix-seal-lows-d 1: only a newer Robot's notes: unlocking won't help, so it says to update Robot or use Delete these. */
    @Test fun onlyNewerRobotsNotesSayUpdate() {
        assertEquals(ToolStrings.notesTooNew(), noteDeleteNoneLine(NotesRead(emptyList(), tooNew = 1), "last"))
        assertEquals(ToolStrings.notesTooNew(), noteDeleteNoneLine(NotesRead(emptyList(), tooNew = 2), "gate"))
    }

    /** fix-seal-lows-e 2: in French the notes can't be read here, with no pronoun that could mean the newer Robot, and the page is Notes, as in English and the glossary. */
    @Test fun theFrenchLineSaysTheNotesCantBeReadHere() {
        assertEquals("Tes notes ont été enregistrées par un Robot plus récent et ne peuvent pas être lues ici. Mets Robot à jour, ou supprime-les avec Supprimer celles-ci dans Notes.",
            ToolStrings.notesTooNew().render(Langs.of(Lang.FR)))
    }

    /** fix-seal-lows-d 1: some notes open, none match, some locked: the match may be locked, as findNotes says. */
    @Test fun noMatchWithLockedNotesSaysUnlock() {
        val one = NotesRead(listOf(Note("n1", "", "Row F", 1L, 2L)), notNow = 1)
        assertEquals(ToolStrings.notesLocked(), noteDeleteNoneLine(one, "gate"))
    }

    @Test fun noNotesAndNoMatchAreAsBefore() {
        assertEquals(ToolStrings.noteDeleteNoNotes(), noteDeleteNoneLine(NotesRead(emptyList()), "gate"))
        val one = NotesRead(listOf(Note("n1", "", "Row F", 1L, 2L)), tooNew = 1)
        assertEquals(ToolStrings.noteDeleteNone("what" to "gate"), noteDeleteNoneLine(one, "gate"))
    }
}
