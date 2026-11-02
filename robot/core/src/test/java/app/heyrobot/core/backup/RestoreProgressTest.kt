// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** R1, R3 (review-restore): what a restore tells you when it stops part way, and when the main app refuses its part. */
class RestoreProgressTest {
    @get:Rule val tmp = TemporaryFolder()
    private fun progress() = RestoreProgress(tmp.root)
    private val cutMarker = RestoreResult(5, true, linkedMapOf(
        BackupCategory.PEOPLE to CategoryOutcome.Done,
        BackupCategory.NOTES to CategoryOutcome.Cut,
        BackupCategory.PHONE_LOCK to CategoryOutcome.Cut,
        BackupCategory.FILES to CategoryOutcome.Cut,
    ), cut = true, pid = 77)
    private val nobody: (Int) -> Boolean = { false }

    @Test fun aRestoreStartingTakesTheLastResultAway() {
        val p = progress()
        p.end(RestoreResult(1, true, mapOf(BackupCategory.PEOPLE to CategoryOutcome.Done)))
        assertNotNull(p.read(nobody))
        p.begin(cutMarker)
        assertFalse(File(tmp.root, RestoreResult.FILE).exists())
        assertEquals(setOf(BackupCategory.PEOPLE), p.read(nobody)!!.done)
        assertTrue(p.read(nobody)!!.cut)
    }

    @Test fun nothingIsSaidWhileTheRestoreIsBeingWritten() {
        val p = progress()
        p.begin(cutMarker)
        assertNull(p.read { it == 77 })
        assertNotNull(p.read { it == 78 })
    }

    @Test fun recoveryDecidesWhatWasInFlight() {
        val p = progress()
        p.begin(cutMarker)
        val r = p.settle(mapOf(BackupCategory.NOTES to false, BackupCategory.FILES to true))!!
        assertEquals(CategoryOutcome.Cut, r.outcomes[BackupCategory.NOTES])
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.FILES])
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.PEOPLE])
        assertEquals(0, r.pid)
        assertTrue(r.cut)
        // It's the result now, until you close it; no marker is left for the next start.
        assertEquals(r, p.read { true })
        assertNull(p.settle(emptyMap()))
    }

    @Test fun filesStillWaitingSaysSo() {
        val p = progress()
        p.begin(cutMarker)
        assertEquals(CategoryOutcome.AfterUnlock, p.settle(emptyMap(), CategoryOutcome.AfterUnlock)!!.outcomes[BackupCategory.FILES])
    }

    @Test fun aRunThatEndedIsNotCalledCut() {
        val p = progress()
        p.begin(cutMarker)
        val end = RestoreResult(6, true, mapOf(BackupCategory.PEOPLE to CategoryOutcome.Done))
        p.end(end)
        assertFalse(File(tmp.root, RestoreProgress.STARTED).exists())
        assertNull(p.settle(emptyMap()))
        assertEquals(end, p.read(nobody))
        // Only the clean-up was cut: the result stands.
        File(tmp.root, RestoreProgress.STARTED).writeText(cutMarker.toJson())
        assertNull(p.settle(emptyMap()))
        assertFalse(File(tmp.root, RestoreProgress.STARTED).exists())
        assertEquals(end, p.read(nobody))
    }

    @Test fun whatTheMainAppRefusedIsNotRestored() {
        val p = progress()
        p.end(RestoreResult(6, true, linkedMapOf(BackupCategory.PHONE_LOCK to CategoryOutcome.Done, BackupCategory.EVERYDAY to CategoryOutcome.Done,
            BackupCategory.SETTINGS to CategoryOutcome.Skipped(PlanSkip.NOT_YOURS))))
        p.applied(listOf(Recovered(BackupCategory.PHONE_LOCK, JournalState.ROLLED_BACK)))
        p.applied(listOf(Recovered(BackupCategory.EVERYDAY, JournalState.DONE), Recovered(BackupCategory.SETTINGS, JournalState.DONE)))
        val r = p.read(nobody)!!
        assertEquals(CategoryOutcome.Refused, r.outcomes[BackupCategory.PHONE_LOCK])
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.EVERYDAY])
        // A voiceprint's note in Settings' line doesn't make an unticked Settings restored.
        assertEquals(CategoryOutcome.Skipped(PlanSkip.NOT_YOURS), r.outcomes[BackupCategory.SETTINGS])
    }

    @Test fun whatTheMainAppFinishedAfterACutIsRestored() {
        val p = progress()
        p.begin(cutMarker)
        p.applied(listOf(Recovered(BackupCategory.PHONE_LOCK, JournalState.DONE)))
        assertEquals(CategoryOutcome.Done, p.read(nobody)!!.outcomes[BackupCategory.PHONE_LOCK])
        val settled = p.settle(mapOf(BackupCategory.NOTES to false))!!
        assertEquals(CategoryOutcome.Cut, settled.outcomes[BackupCategory.PHONE_LOCK])
        assertEquals(CategoryOutcome.Done, p.read(nobody)!!.outcomes[BackupCategory.PHONE_LOCK])
    }

    @Test fun aStorageErrorIsNotRestoredUntilTheRetryFinishesIt() {
        val p = progress()
        p.end(RestoreResult(1, true, mapOf(BackupCategory.SETTINGS to CategoryOutcome.Done)))
        p.applied(listOf(Recovered(BackupCategory.SETTINGS, JournalState.COMMIT)))
        assertEquals(CategoryOutcome.Refused, p.read(nobody)!!.outcomes[BackupCategory.SETTINGS])
        p.applied(listOf(Recovered(BackupCategory.SETTINGS, JournalState.DONE)))
        assertEquals(CategoryOutcome.Done, p.read(nobody)!!.outcomes[BackupCategory.SETTINGS])
    }

    @Test fun aNewRestoreForgetsTheMainAppsOldWord() {
        val p = progress()
        p.applied(listOf(Recovered(BackupCategory.PHONE_LOCK, JournalState.ROLLED_BACK)))
        p.begin(cutMarker)
        p.end(RestoreResult(7, true, mapOf(BackupCategory.PHONE_LOCK to CategoryOutcome.Done)))
        assertEquals(CategoryOutcome.Done, p.read(nobody)!!.outcomes[BackupCategory.PHONE_LOCK])
    }

    @Test fun closingItLeavesNothing() {
        val p = progress()
        p.begin(cutMarker)
        p.applied(listOf(Recovered(BackupCategory.PHONE_LOCK, JournalState.DONE)))
        p.dismiss()
        assertNull(p.read(nobody))
        assertEquals(emptyList<String>(), tmp.root.list().orEmpty().toList())
    }

    /** A real journal, cut with one category at COMMIT and one STAGED: the words follow what recovery did with each. */
    @Test fun aCutRunThroughTheJournal() {
        val j = RestoreJournal(tmp.root)
        val p = progress()
        p.begin(cutMarker)
        j.begin(BackupCategory.PEOPLE, RestoreProcess.VOICE); j.done(BackupCategory.PEOPLE)
        j.begin(BackupCategory.NOTES, RestoreProcess.VOICE); j.commit(BackupCategory.NOTES, RestoreProcess.VOICE)
        j.begin(BackupCategory.FILES, RestoreProcess.VOICE)
        val lines = j.recover(RestoreProcess.VOICE, { _, _ -> }, {}).associate { it.category to (it.state == JournalState.DONE) }
        val r = p.settle(lines)!!
        assertEquals(CategoryOutcome.Done, r.outcomes[BackupCategory.NOTES])
        assertEquals(CategoryOutcome.Cut, r.outcomes[BackupCategory.FILES])
        assertEquals(CategoryOutcome.Cut, r.outcomes[BackupCategory.PHONE_LOCK])
    }
}
