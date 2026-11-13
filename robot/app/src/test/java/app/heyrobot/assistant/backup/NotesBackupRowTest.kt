// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import app.heyrobot.core.Note
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.sys.NotesRead
import app.heyrobot.sys.NotesVault
import app.heyrobot.sys.SoftHistoryKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** fix-seal-lows-c 1 and 3: Back up now's Notes row never says Empty while notes are held, and the backup then names Notes. */
class NotesBackupRowTest {
    @get:Rule val tmp = TemporaryFolder()
    private var lockedNow = false
    private val keys = SoftHistoryKeys()
    private val dir by lazy { tmp.newFolder("notes") }
    private fun vault(lock: File = File(tmp.root, "notes.lock")) = NotesVault(dir, keys, lock, { lockedNow })
    private fun note(id: String) = Note(id, "", "Row F, by the lift", 1_000L, 2_000L)

    private fun stops(v: NotesVault, tooNew: Boolean) {
        try { BackupSources.notesWhole(v); fail("whole") } catch (e: BackupSources.NotesUnreadable) { assertEquals(tooNew, e.tooNew) }
    }

    @Test fun onlyLockedNotesStayTickableAndStopTheBackup() {
        val v = vault()
        assertTrue(v.save(note("n1")))
        lockedNow = true
        val s = BackupSources.notesSummary(v.read())
        assertFalse(s.empty)
        assertEquals(-1, s.count)
        assertEquals(0, s.extra)
        assertTrue(BackupSources.choosable(BackupCategory.NOTES, s))
        stops(v, tooNew = false)
    }

    @Test fun aReadThatFailsStopsTheBackupAsNotes() {
        val v = vault(File(tmp.newFile("in-the-way"), "notes.lock"))
        assertTrue(BackupSources.choosable(BackupCategory.NOTES, BackupSources.notesSummary(v.read())))
        stops(v, tooNew = false)
    }

    @Test fun aNewerRobotsNotesSayWhy() {
        val s = BackupSources.notesSummary(NotesRead(emptyList(), tooNew = 2))
        assertEquals(-1, s.count)
        assertEquals(BackupSources.NOTES_NEWER, s.extra)
        assertTrue(BackupSources.choosable(BackupCategory.NOTES, s))
    }

    @Test fun openNotesCountAsBeforeAndNoneIsEmpty() {
        assertEquals(1, BackupSources.notesSummary(NotesRead(listOf(note("n1")))).count)
        val none = BackupSources.notesSummary(NotesRead(emptyList()))
        assertTrue(none.empty)
        assertFalse(BackupSources.choosable(BackupCategory.NOTES, none))
        assertEquals(emptyList<Note>(), BackupSources.notesWhole(vault()))
    }
}
