// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.TranscriptCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F20: TranscriptStore keeps a lines.jsonl whose line a crash cut inside a multi-byte character (the next record starts on a
 * new line), and its reader skips that line. The restore must not refuse every transcript for it; bytes that were never
 * text still refuse them.
 */
class BackupTornLogTest {
    private fun verify(log: ByteArray): VerifiedBackup = BackupKit.verify(BackupKit.file {
        entry(BackupCategory.TRANSCRIPTS, "${BackupKit.TRANSCRIPT}/meta.json", BackupKit.text(BackupKit.transcriptMeta().toJson()))
        entry(BackupCategory.TRANSCRIPTS, "${BackupKit.TRANSCRIPT}/lines.jsonl", log)
    })

    private val line = BackupKit.text(BackupKit.transcriptLines())
    /** A record cut inside the two bytes of "é". */
    private val cut = BackupKit.text("{\"e\":\"line\",\"text\":\"caf").let { it + 0xC3.toByte() }
    private val newline = byteArrayOf('\n'.code.toByte())

    @Test fun aLineTornInsideACharacterIsAccepted() {
        for (log in listOf(line + cut, cut + newline + line, line + cut + newline + line)) {
            val v = verify(log)
            assertEquals(emptyList<CategorySkip>(), v.skipped)
            assertTrue(BackupKit.TRANSCRIPT in v.transcripts)
        }
    }

    @Test fun aLineTornInAThreeByteCharacterIsAccepted() {
        val euro = "€".toByteArray(Charsets.UTF_8)
        assertEquals(emptyList<CategorySkip>(), verify(line + euro.copyOf(2) + newline + line).skipped)
        assertEquals(emptyList<CategorySkip>(), verify(line + euro.copyOf(1)).skipped)
    }

    @Test fun bytesThatAreNotTextAnywhereInALineAreStillRefused() {
        val bad = byteArrayOf(0xFF.toByte(), 'a'.code.toByte())
        for (log in listOf(line + bad, bad + newline + line, BackupKit.text("caf") + 0xC3.toByte() + BackupKit.text("x") + newline)) {
            assertEquals(listOf(SkipWhy.REFUSED), verify(log).skipped.map { it.why })
        }
    }

    @Test fun aWholeLongLogAcrossChunksIsAccepted() {
        val long = TranscriptCodec.encode(app.heyrobot.core.TranscriptEvent.Line(0, 0.0, 1.0, "S1", "fr", "é€😀".repeat(40_000))) + "\n"
        assertEquals(emptyList<CategorySkip>(), verify(BackupKit.text(long) + cut).skipped)
    }
}
