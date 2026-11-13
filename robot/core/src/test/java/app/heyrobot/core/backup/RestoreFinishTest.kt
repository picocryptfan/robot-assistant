// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** F46: Routines and Skills end as Failed when their store wrote nothing, and a refused finish can't repeat for ever. */
class RestoreFinishTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun stagedRoutines(): File = tmp.newFolder().also { File(it, "routines.json").writeText(RoutineCodec.encode(BackupKit.routines())) }
    private fun newer(): String = "{\"v\":999999}"

    @Test fun routinesThatTheStoreRefusesThrow() {
        val dir = tmp.newFolder()
        File(dir, "routines.json").writeText(newer())
        val st = stagedRoutines()
        assertThrows { RestoreFinish.routines(RoutineStore(dir), st) }
        assertEquals(newer(), File(dir, "routines.json").readText())
    }

    @Test fun routinesTheStoreTakesAreWritten() {
        val dir = tmp.newFolder()
        RestoreFinish.routines(RoutineStore(dir), stagedRoutines())
        assertEquals(BackupKit.routines().map { it.id }, RoutineStore(dir).routines().map { it.id })
    }

    @Test fun aStagedFileThatDoesntReadThrows() {
        val st = tmp.newFolder().also { File(it, "routines.json").writeText("not json") }
        assertThrows { RestoreFinish.routines(RoutineStore(tmp.newFolder()), st) }
    }

    @Test fun skillsThatTheStoreRefusesThrow() {
        val dir = tmp.newFolder()
        File(dir, SkillStore.SKILLS).writeText(newer())
        val st = tmp.newFolder().also { File(it, SkillStore.SKILLS).writeText(app.heyrobot.core.skills.SkillCodec.encode(emptyList())) }
        assertThrows { RestoreFinish.skills(SkillStore(dir), st) }
    }

    @Test fun nothingStagedIsNotAFailure() {
        val st = tmp.newFolder()
        RestoreFinish.routines(RoutineStore(tmp.newFolder()), st)
        RestoreFinish.skills(SkillStore(tmp.newFolder()), st)
    }

    @Test fun aRecoveryGivesUpOnceAndStillFinishesTheOthers() {
        val journal = RestoreJournal(File(tmp.newFolder(), "restore"))
        for (c in listOf(BackupCategory.ROUTINES, BackupCategory.NOTES)) {
            journal.begin(c, RestoreProcess.VOICE)
            journal.staging(c).mkdirs()
            journal.commit(c, RestoreProcess.VOICE)
        }
        val tried = ArrayList<BackupCategory>()
        val recovered = journal.recover(RestoreProcess.VOICE, { c, _ ->
            tried += c
            if (c == BackupCategory.ROUTINES) throw CantFinish("routines")
        }, { error("no keys") })
        assertEquals(setOf(BackupCategory.ROUTINES, BackupCategory.NOTES), tried.toSet())
        assertEquals(setOf(Recovered(BackupCategory.ROUTINES, JournalState.ROLLED_BACK), Recovered(BackupCategory.NOTES, JournalState.DONE)), recovered.toSet())
        // Settled: the journal has gone and the next start tries nothing.
        assertTrue(journal.read().isEmpty())
        assertEquals(emptyList<Recovered>(), journal.recover(RestoreProcess.VOICE, { _, _ -> error("not again") }, { error("no keys") }))
    }

    @Test fun aRunGivesUpWithoutARepeat() {
        val journal = RestoreJournal(File(tmp.newFolder(), "restore"))
        val c = BackupCategory.SKILLS
        journal.begin(c, RestoreProcess.VOICE)
        journal.staging(c).mkdirs()
        journal.commit(c, RestoreProcess.VOICE)
        journal.giveUp(c)
        assertEquals(JournalState.ROLLED_BACK, journal.state(c))
        assertFalse(journal.staging(c).exists())
        journal.recover(RestoreProcess.VOICE, { _, _ -> error("not again") }, { error("no keys") })
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: CantFinish) {
            return
        }
        error("should have thrown CantFinish")
    }

    /** moments-backup-c (L5): a reminder added on this phone after the step, before a deferred or replayed finish, stays. */
    @Test fun momentsMergeAgainInsideTheEdit() {
        val car = app.heyrobot.core.Moment.entries.first()
        val here = app.heyrobot.core.MomentReminder("h1", "call the bank", car, 1L)
        val theirs = app.heyrobot.core.MomentReminder("b1", "buy milk", car, 3L)
        val staging = tmp.newFolder().also {
            File(it, RoutineStore.MOMENTS).writeText(app.heyrobot.core.MomentReminders.encode(listOf(theirs)))
            File(it, RestoreFinish.MOMENTS_MODE).writeText(RestoreMode.ADD.name)
        }
        val rs = RoutineStore(tmp.newFolder())
        rs.editMoments { listOf(here) }
        RestoreFinish.moments(rs, staging)
        assertEquals(listOf(here, theirs), rs.moments())
        RestoreFinish.moments(rs, staging) // replayed: nothing twice
        assertEquals(listOf(here, theirs), rs.moments())
    }
}
