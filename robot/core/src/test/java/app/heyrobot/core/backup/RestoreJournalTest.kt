// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.TranscriptFiles
import app.heyrobot.core.TranscriptStore
import app.heyrobot.core.writeAtomic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Design §7.10: all or nothing per category. A crash after each step: a COMMIT category is finished, a STAGED one is
 * undone, keys saved in a cut run are deleted again; repeating any step changes nothing; each process recovers only its
 * own categories.
 */
class RestoreJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun live(dir: File): Map<String, String> = dir.listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readText() }

    /** Notes in Replace: write a and d from the file, delete b and c of this phone's. Stops after [steps] steps. */
    private fun notesRun(journal: RestoreJournal, liveDir: File, steps: Int, moveFirstOnly: Boolean = false) {
        val c = BackupCategory.NOTES
        if (steps < 1) return
        journal.begin(c, RestoreProcess.VOICE)
        if (steps < 2) return
        val st = journal.staging(c)
        st.mkdirs()
        File(st, "a.json").writeText("new a")
        File(st, "d.json").writeText("new d")
        StagedFiles.stageDelete(st, listOf("b.json", "c.json"))
        if (steps < 3) return
        journal.commit(c, RestoreProcess.VOICE)
        if (steps < 4) return
        if (moveFirstOnly) {
            // Cut in the middle of moving in: a.json moved, the deletions done, d.json still staged.
            File(liveDir, "b.json").delete()
            File(liveDir, "c.json").delete()
            File(st, "a.json").renameTo(File(liveDir, "a.json"))
            return
        }
        StagedFiles.moveIn(st, liveDir)
        if (steps < 5) return
        journal.done(c)
    }

    private val before = mapOf("a.json" to "old a", "b.json" to "old b", "c.json" to "old c")
    private val after = mapOf("a.json" to "new a", "d.json" to "new d")

    @Test fun aCrashAfterEachStepEndsWholeOrUntouched() {
        for (steps in 0..5) for (partial in listOf(false, true)) {
            if (partial && steps != 4) continue
            val root = tmp.newFolder()
            val liveDir = File(root, "notes").apply { mkdirs() }
            before.forEach { (n, t) -> File(liveDir, n).writeText(t) }
            val journal = RestoreJournal(File(root, "robot/restore"))
            notesRun(journal, liveDir, steps, partial)
            // The process dies here; at its next start:
            val finish = { c: BackupCategory, staged: File -> assertEquals(BackupCategory.NOTES, c); StagedFiles.moveIn(staged, liveDir) }
            val recovered = journal.recover(RestoreProcess.VOICE, finish) { error("no keys") }
            val expected = if (steps >= 3) after else before
            assertEquals("steps $steps partial $partial", expected, live(liveDir))
            assertEquals("steps $steps", when (steps) {
                1, 2 -> listOf(Recovered(BackupCategory.NOTES, JournalState.ROLLED_BACK))
                3, 4 -> listOf(Recovered(BackupCategory.NOTES, JournalState.DONE))
                else -> emptyList()
            }, recovered)
            // Nothing staged is left, and the journal is gone once every category is settled.
            assertFalse(journal.staging(BackupCategory.NOTES).exists())
            if (steps != 5) assertTrue(journal.read().isEmpty())
            // Repeating recovery, or the move itself, changes nothing.
            assertEquals(emptyList<Recovered>(), journal.recover(RestoreProcess.VOICE, finish) { error("no keys") })
            assertEquals(expected, live(liveDir))
            StagedFiles.moveIn(journal.staging(BackupCategory.NOTES), liveDir)
            assertEquals(expected, live(liveDir))
        }
    }

    @Test fun keysSavedInACutRunAreDeletedAgain() {
        val journal = RestoreJournal(tmp.newFolder("restore"))
        val saved = HashSet<String>()
        journal.begin(BackupCategory.KEYS, RestoreProcess.VOICE)
        journal.keySaving("helper/claude")
        saved += "helper/claude"
        journal.keySaving("search/brave")
        // Cut before brave's key was saved: both names are in the journal, never the keys.
        assertFalse(File(journal.dir, RestoreJournal.FILE).readText().contains("test-key"))
        val deleted = ArrayList<String>()
        val r = journal.recover(RestoreProcess.VOICE, { _, _ -> error("nothing to finish") }) { names -> deleted += names; saved -= names.toSet() }
        assertEquals(listOf("helper/claude", "search/brave"), deleted)
        assertTrue(saved.isEmpty())
        assertEquals(listOf(Recovered(BackupCategory.KEYS, JournalState.ROLLED_BACK)), r)
        // A run that finished keeps its keys.
        journal.begin(BackupCategory.KEYS, RestoreProcess.VOICE)
        journal.keySaving("helper/claude")
        journal.done(BackupCategory.KEYS)
        assertEquals(emptyList<Recovered>(), journal.recover(RestoreProcess.VOICE, { _, _ -> }) { error("kept") })
        // A name that isn't a key's never comes back from the journal.
        assertTrue(RestoreJournal.decode("{\"v\":1,\"categories\":{\"keys\":{\"state\":\"STAGED\",\"process\":\"VOICE\",\"keys\":[\"../x\",\"helper/claude\"]}}}")
            .getValue(BackupCategory.KEYS).keys == listOf("helper/claude"))
    }

    @Test fun eachProcessRecoversItsOwn() {
        val journal = RestoreJournal(tmp.newFolder("restore"))
        // :voice staged the settings files for the main app, then died; and was staging History.
        journal.begin(BackupCategory.SETTINGS, RestoreProcess.VOICE)
        File(journal.staging(BackupCategory.SETTINGS).apply { mkdirs() }, "settings.json").writeText("{}")
        journal.commit(BackupCategory.SETTINGS, RestoreProcess.MAIN)
        journal.begin(BackupCategory.HISTORY, RestoreProcess.VOICE)
        // :voice starts first: it undoes History, and leaves the main app's settings alone.
        assertEquals(listOf(Recovered(BackupCategory.HISTORY, JournalState.ROLLED_BACK)), journal.recover(RestoreProcess.VOICE, { _, _ -> error("not mine") }) {})
        assertTrue(File(journal.staging(BackupCategory.SETTINGS), "settings.json").isFile)
        // The main app applies them through its stores.
        val applied = ArrayList<String>()
        val r = journal.recover(RestoreProcess.MAIN, { c, staged -> applied += staged.list().orEmpty().map { "${c.id}/$it" } }) {}
        assertEquals(listOf("settings/settings.json"), applied)
        assertEquals(listOf(Recovered(BackupCategory.SETTINGS, JournalState.DONE)), r)
        assertTrue(journal.read().isEmpty())
    }

    @Test fun peopleAndMemoryAreOneAtomicWrite() {
        val root = tmp.newFolder()
        val people = File(root, "people/people.json").apply { parentFile.mkdirs(); writeText("old") }
        val journal = RestoreJournal(File(root, "restore"))
        journal.begin(BackupCategory.PEOPLE, RestoreProcess.VOICE)
        // Cut before the write: the old file; the journal line is undone (nothing to undo).
        assertEquals(listOf(Recovered(BackupCategory.PEOPLE, JournalState.ROLLED_BACK)), journal.recover(RestoreProcess.VOICE, { _, _ -> }) {})
        assertEquals("old", people.readText())
        journal.begin(BackupCategory.PEOPLE, RestoreProcess.VOICE)
        writeAtomic(people, "new")
        journal.done(BackupCategory.PEOPLE)
        assertEquals("new", people.readText())
        assertEquals(emptyList<Recovered>(), journal.recover(RestoreProcess.VOICE, { _, _ -> }) {})
    }

    @Test fun movingInNeverLeavesTheFolderOrReplacesAFolder() {
        val root = tmp.newFolder()
        val liveDir = File(root, "live").apply { mkdirs() }
        File(root, "outside.json").writeText("keep")
        File(liveDir, "kept").mkdirs()
        File(liveDir, "kept/meta.json").writeText("mine")
        val st = File(root, "staged").apply { mkdirs() }
        StagedFiles.stageDelete(st, listOf("../outside.json", "..", "/etc"))
        File(st, "kept").mkdirs()
        File(st, "kept/meta.json").writeText("theirs")
        StagedFiles.moveIn(st, liveDir)
        assertEquals("keep", File(root, "outside.json").readText())
        assertEquals("mine", File(liveDir, "kept/meta.json").readText())
    }

    @Test fun aTranscriptMovesInWhole() {
        val root = tmp.newFolder()
        val store = TranscriptStore(File(root, "transcripts"))
        val id = "20261011-1402-7f3a"
        val staged = File(root, "restore/transcripts/$id").apply { mkdirs() }
        File(staged, TranscriptFiles.META).writeText(BackupKit.transcriptMeta().toJson())
        File(staged, TranscriptFiles.LOG).writeText(BackupKit.transcriptLines())
        assertFalse(store.restore(staged, "../$id"))
        assertTrue(store.restore(staged, id))
        assertEquals(BackupKit.transcriptMeta(), store.meta(id))
        assertFalse(staged.exists())
        // Again: it is here now, so nothing changes (Add leaves yours alone).
        val again = File(root, "restore/transcripts/again").apply { mkdirs() }
        File(again, TranscriptFiles.META).writeText("{}")
        assertFalse(store.restore(again, id))
        assertEquals(BackupKit.transcriptMeta(), store.meta(id))
        // A folder without its meta.json isn't a transcript.
        val empty = File(root, "restore/transcripts/x").apply { mkdirs() }
        assertFalse(store.restore(empty, "20261011-1403-0000"))
    }

    /**
     * 1.4, Robot Files: a finisher's "not now" (its key needs the phone unlocked) keeps that line at COMMIT for the next
     * recovery while the other lines go on; a staged Files line is undone through [undo] first (its sealed copies' keys).
     */
    @Test fun notNowWaitsAndTheRestGoesOn() {
        val journal = RestoreJournal(tmp.newFolder())
        journal.begin(BackupCategory.FILES, RestoreProcess.VOICE)
        journal.commit(BackupCategory.FILES, RestoreProcess.VOICE)
        journal.begin(BackupCategory.NOTES, RestoreProcess.VOICE)
        journal.commit(BackupCategory.NOTES, RestoreProcess.VOICE)
        val finished = ArrayList<BackupCategory>()
        var locked = true
        val finish = { c: BackupCategory, _: File -> if (c == BackupCategory.FILES && locked) throw NotNow(); finished += c; Unit }
        assertEquals(listOf(Recovered(BackupCategory.NOTES, JournalState.DONE)), journal.recover(RestoreProcess.VOICE, finish, {}))
        assertEquals(JournalState.COMMIT, journal.state(BackupCategory.FILES))
        // Unlocked: finished, and the journal goes.
        locked = false
        assertEquals(listOf(Recovered(BackupCategory.FILES, JournalState.DONE)), journal.recover(RestoreProcess.VOICE, finish, {}))
        assertTrue(journal.read().isEmpty())
        // Anything else a finisher throws still stops the recovery, as before.
        journal.begin(BackupCategory.FILES, RestoreProcess.VOICE)
        journal.commit(BackupCategory.FILES, RestoreProcess.VOICE)
        assertTrue(runCatching { journal.recover(RestoreProcess.VOICE, { _, _ -> error("boom") }, {}) }.isFailure)
        assertEquals(JournalState.COMMIT, journal.state(BackupCategory.FILES))
        journal.recover(RestoreProcess.VOICE, { _, _ -> }, {})
        // Staged and cut: undone, its folder given to [undo] before it goes.
        journal.begin(BackupCategory.FILES, RestoreProcess.VOICE)
        File(journal.staging(BackupCategory.FILES).apply { mkdirs() }, "x.rf").writeText("sealed")
        val undone = ArrayList<String>()
        val r = journal.recover(RestoreProcess.VOICE, { _, _ -> error("not committed") }, {}, undo = { c, st -> undone += c.id + ":" + st.list()!!.joinToString() })
        assertEquals(listOf(Recovered(BackupCategory.FILES, JournalState.ROLLED_BACK)), r)
        assertEquals(listOf("files:x.rf"), undone)
        assertFalse(journal.staging(BackupCategory.FILES).exists())
    }

    /**
     * FX1 (files-1.4-review.md finding 10): a new restore's Files line never overwrites one still at COMMIT (it must be
     * finished first), and leftover Files staging goes through [undo] (its sealed copies' keys destroyed) before it is cleared.
     */
    @Test fun aFilesBeginNeverOverwritesACommitAndUndoesLeftovers() {
        val journal = RestoreJournal(tmp.newFolder())
        journal.begin(BackupCategory.FILES, RestoreProcess.VOICE)
        File(journal.staging(BackupCategory.FILES).apply { mkdirs() }, "a.rf").writeText("sealed")
        journal.commit(BackupCategory.FILES, RestoreProcess.VOICE)
        val undone = ArrayList<String>()
        assertTrue(runCatching { journal.begin(BackupCategory.FILES, RestoreProcess.VOICE, undo = { undone += it.name }) }.isFailure)
        assertEquals(JournalState.COMMIT, journal.state(BackupCategory.FILES))
        assertTrue(File(journal.staging(BackupCategory.FILES), "a.rf").isFile)
        assertTrue(undone.isEmpty())
        // Finished: a new one begins, and a leftover folder (a cut run's, say) goes through undo first.
        journal.recover(RestoreProcess.VOICE, { _, _ -> }, {})
        File(journal.staging(BackupCategory.FILES).apply { mkdirs() }, "b.rf").writeText("sealed")
        journal.begin(BackupCategory.FILES, RestoreProcess.VOICE, undo = { undone += it.list()!!.joinToString() })
        assertEquals(listOf("b.rf"), undone)
        assertFalse(journal.staging(BackupCategory.FILES).exists())
        assertEquals(JournalState.STAGED, journal.state(BackupCategory.FILES))
        // Other categories at COMMIT are begun again as before (Settings waits for the main app and a newer restore's wins).
        journal.begin(BackupCategory.SETTINGS, RestoreProcess.VOICE)
        journal.commit(BackupCategory.SETTINGS, RestoreProcess.MAIN)
        journal.begin(BackupCategory.SETTINGS, RestoreProcess.VOICE)
        assertEquals(JournalState.STAGED, journal.state(BackupCategory.SETTINGS))
    }
}
