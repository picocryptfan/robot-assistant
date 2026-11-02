// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** F20: Back up's check names a category a restore would leave out, and a category over the reader's caps never gets written. */
class BackupReadBackTest {
    private fun twoEntries(meta: String) = BackupKit.verify(BackupKit.file {
        entry(BackupCategory.TRANSCRIPTS, "${BackupKit.TRANSCRIPT}/meta.json", BackupKit.text(meta))
        entry(BackupCategory.TRANSCRIPTS, "${BackupKit.TRANSCRIPT}/lines.jsonl", BackupKit.text(BackupKit.transcriptLines()))
    })

    @Test fun aWholeBackupPasses() {
        val v = twoEntries(BackupKit.transcriptMeta().toJson())
        assertEquals(BackupReadBack.Result.Ok, BackupReadBack.check(v, 2))
    }

    @Test fun aDifferentCountIsAMismatch() {
        val v = twoEntries(BackupKit.transcriptMeta().toJson())
        assertEquals(BackupReadBack.Result.Mismatch, BackupReadBack.check(v, 3))
    }

    @Test fun aCategoryTheRestoreRefusesIsNamed() {
        val v = twoEntries("{\"not\":\"a transcript\"}")
        assertEquals(BackupReadBack.Result.Refused(BackupCategory.TRANSCRIPTS), BackupReadBack.check(v, 2))
    }

    @Test fun theEverydaySetIsHeldToTheSameCheck() {
        // E0's category: a file the reader's codec refuses fails the check by name, as any other.
        val v = BackupKit.verify(BackupKit.file { entry(BackupCategory.EVERYDAY, BackupCategory.EVERYDAY_FILE, BackupKit.text("{\"v\":999999}")) })
        assertEquals(BackupReadBack.Result.Refused(BackupCategory.EVERYDAY), BackupReadBack.check(v, 1))
    }

    @Test fun moreTranscriptsThanTheReaderTakesAreCaughtBeforeWriting() {
        fun names(n: Int) = (0 until n).flatMap { i ->
            val id = "20261011-1402-" + i.toString(16).padStart(4, '0')
            listOf(BackupCategory.TRANSCRIPTS to "$id/meta.json", BackupCategory.TRANSCRIPTS to "$id/lines.jsonl")
        }
        assertNull(BackupReadBack.overCap(names(BackupCaps.MAX_TRANSCRIPTS)))
        assertEquals(BackupCategory.TRANSCRIPTS, BackupReadBack.overCap(names(BackupCaps.MAX_TRANSCRIPTS + 1)))
    }

    @Test fun anyCategoryOverItsEntriesIsCaught() {
        val many = (0..BackupCategory.NOTES.maxEntries).map { BackupCategory.NOTES to "n$it.json" }
        assertEquals(BackupCategory.NOTES, BackupReadBack.overCap(many))
        assertNull(BackupReadBack.overCap(many.drop(1)))
    }
}
