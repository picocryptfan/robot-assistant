package app.heyrobot.sys

import app.heyrobot.core.Note
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** merge-seal-130: Today's note over sealed notes never puts another note in place of the pinned one while some can't be opened. */
class TodayNotePickTest {
    private val old = Note("a", "Old", "x", 1, 1)
    private val new = Note("b", "New", "y", 2, 2)

    @Test fun pinnedNoteWhenItOpens() {
        assertEquals(old to true, TodaySource.notePick(NotesRead(listOf(old, new), notNow = 3), "a"))
    }

    @Test fun newestWhenEveryNoteOpened() {
        assertEquals(new to false, TodaySource.notePick(NotesRead(listOf(old, new)), "gone"))
        assertEquals(new to false, TodaySource.notePick(NotesRead(listOf(old, new), aside = 2), null))
    }

    @Test fun noStandInWhileSomeCantBeOpened() {
        assertNull(TodaySource.notePick(NotesRead(listOf(old, new), notNow = 1), "c"))
        assertNull(TodaySource.notePick(NotesRead(listOf(old, new), tooNew = 1), null))
        assertNull(TodaySource.notePick(NotesRead(emptyList(), notNow = 2), "a"))
    }

    @Test fun heldSaysWhyOnlyWhenNothingStandsIn() {
        assertEquals(app.heyrobot.core.NoteHeld.NOT_NOW, TodaySource.noteHeld(NotesRead(listOf(old, new), notNow = 1), "c"))
        assertEquals(app.heyrobot.core.NoteHeld.TOO_NEW, TodaySource.noteHeld(NotesRead(emptyList(), tooNew = 2), null))
        assertEquals(app.heyrobot.core.NoteHeld.NOT_NOW, TodaySource.noteHeld(NotesRead(emptyList(), notNow = 1, tooNew = 1), "a"))
        assertNull(TodaySource.noteHeld(NotesRead(listOf(old, new), tooNew = 1), "a"))
        assertNull(TodaySource.noteHeld(NotesRead(emptyList()), null))
        assertNull(TodaySource.noteHeld(NotesRead(emptyList(), aside = 3), null))
    }

    @Test fun aQuickAddWritesNothingWhileHeld() {
        assertNull(TodaySource.quickAdd(NotesRead(listOf(old, new), notNow = 1), "c", "Row F", 9))
        assertNull(TodaySource.quickAdd(NotesRead(emptyList(), notNow = 1), null, "Row F", 9))
        assertNull(TodaySource.quickAdd(NotesRead(emptyList(), tooNew = 1), null, "Row F", 9))
        assertNull(TodaySource.quickAdd(NotesRead(listOf(old)), "a", "  ", 9))
    }

    @Test fun aQuickAddGoesToThePickedNoteOrStartsOne() {
        val line = TodaySource.quickAdd(NotesRead(listOf(old, new), notNow = 1), "a", "Row F", 9) as TodaySource.QuickAdd.Line
        assertEquals("a", line.note.id)
        assertEquals(TodaySource.QuickAdd.New("Row F"), TodaySource.quickAdd(NotesRead(emptyList()), null, " Row F ", 9))
        assertEquals(TodaySource.QuickAdd.New("Row F"), TodaySource.quickAdd(NotesRead(emptyList(), aside = 2), "gone", "Row F", 9))
    }
}
