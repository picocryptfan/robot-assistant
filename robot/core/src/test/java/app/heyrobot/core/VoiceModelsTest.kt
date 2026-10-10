package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class VoiceModelsTest {
    @Test fun manifestMatchesTheVoicePlan() {
        // docs/voice-plan.md 2.4: per-file sizes; totals 670.5 MB, 353.8 MB, 60.0 MB.
        assertEquals(670_478_772L, VoiceBundle.STT.bytes)
        assertEquals(353_762_203L, VoiceBundle.KOKORO.bytes)
        assertEquals(60_045_866L, VoiceBundle.KITTEN.bytes)
        assertEquals(listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"), VoiceBundle.STT.files.map { it.name })
        // fp32 Kokoro and Kitten (the int8 ones are 3x slower on the CPU path).
        assertEquals("model.onnx", VoiceBundle.KOKORO.files[0].name)
        assertEquals("model.fp32.onnx", VoiceBundle.KITTEN.files[0].name)
        assertEquals("acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247", VoiceBundle.STT.files[0].sha256)
        assertEquals("b40f62b166ac8164b0627ef48a0b358eda0985e272fb03ef5252e7206305da11", VoiceBundle.KOKORO.files[0].sha256)
        assertEquals("2174dbf67b58b7b50d7b65294f89c2c53c172834533519b853c579879a04cc22", VoiceBundle.KITTEN.files[0].sha256)
    }

    @Test fun everyFileHasAPinnedUrlAndAWellFormedHash() {
        val ids = VoiceBundle.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        for (b in VoiceBundle.entries) {
            assertTrue(b.commit, Regex("^[0-9a-f]{40}$").matches(b.commit))
            assertEquals(b.files.map { it.name }.distinct(), b.files.map { it.name })
            for (f in b.files) {
                assertTrue(f.sha256, Regex("^[0-9a-f]{64}$").matches(f.sha256))
                assertTrue(f.bytes > 0)
                val u = b.url(f)
                assertEquals("https://huggingface.co/${b.repo}/resolve/${b.commit}/${f.name}", u)
                assertFalse("never a branch", u.contains("/resolve/main/"))
            }
        }
        assertEquals(File("/data/files/models/stt-parakeet-v3"), VoiceBundle.dir(File("/data/files"), VoiceBundle.STT))
        assertEquals(VoiceBundle.KOKORO, VoiceBundle.byId("tts-kokoro-v1.0"))
        assertNull(VoiceBundle.byId("../x"))
    }

    private val b = VoiceBundle.KITTEN
    private fun allMarked(modified: Long = 7) = b.files.map { InstalledFile(it.name, it.bytes, modified, it.sha256) }
    private fun disk(modified: Long = 7, skip: String? = null): (String) -> Pair<Long, Long>? = { n ->
        if (n == skip) null else b.files.firstOrNull { it.name == n }?.let { it.bytes to modified }
    }

    @Test fun verifiedMarkRoundTripsAndVouchesOnlyForUnchangedFiles() {
        val m = allMarked()
        assertEquals(m, VoiceMark.decode(VoiceMark.encode(m)))
        assertTrue(VoiceMark.complete(b, m, disk()))
        // A file changed (modification time), missing, or marked with another hash or size: not vouched for.
        assertFalse(VoiceMark.complete(b, m, disk(modified = 8)))
        assertEquals(b.files.size - 1, VoiceMark.vouched(b, m, disk(skip = "voices.bin")).size)
        val wrongHash = m.map { if (it.name == "tokens.txt") it.copy(sha256 = "0".repeat(64)) else it }
        assertEquals(setOf("model.fp32.onnx", "voices.bin"), VoiceMark.vouched(b, wrongHash, disk()))
        val wrongSize = m.map { if (it.name == "voices.bin") it.copy(bytes = 1) else it }
        assertFalse(VoiceMark.complete(b, wrongSize, disk()))
        // Garbage lines are skipped; a later line for the same file wins.
        val text = "junk\n" + VoiceMark.encode(m) + "voices.bin 1 7 ${"a".repeat(64)}\n"
        assertEquals(setOf("model.fp32.onnx", "tokens.txt"), VoiceMark.vouched(b, VoiceMark.decode(text), disk()))
        assertTrue(VoiceMark.decode(null).isEmpty())
    }

    @Test fun completeReadsARealDirectory() {
        val dir = Files.createTempDirectory("vm").toFile()
        try {
            assertFalse(VoiceMark.complete(b, dir))
            val f = File(dir, "tokens.txt").apply { writeText("x") }
            val one = VoiceBundle.KOKORO
            // A bundle whose files have the wrong size on disk is never complete, whatever the mark says.
            File(dir, VoiceMark.FILE).writeText(VoiceMark.encode(listOf(InstalledFile("tokens.txt", 1, f.lastModified(), "z"))))
            assertFalse(VoiceMark.complete(one, dir))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun facts(vouched: Int = 0, dl: Boolean = false, done: Boolean = false, inst: Boolean = false, bad: Boolean = false) =
        VoiceFacts(vouched, dl, done, inst, bad)

    @Test fun statusFollowsTheFacts() {
        assertEquals(ModelStatus.MISSING, VoiceModelLogic.status(b, facts()))
        assertEquals(ModelStatus.MISSING, VoiceModelLogic.status(b, facts(vouched = 2)))
        assertEquals(ModelStatus.DOWNLOADING, VoiceModelLogic.status(b, facts(vouched = 1, dl = true)))
        assertEquals(ModelStatus.UNVERIFIED, VoiceModelLogic.status(b, facts(done = true)))
        assertEquals(ModelStatus.VERIFYING, VoiceModelLogic.status(b, facts(done = true, inst = true)))
        assertEquals(ModelStatus.VERIFIED, VoiceModelLogic.status(b, facts(vouched = 3)))
        assertEquals(ModelStatus.BAD, VoiceModelLogic.status(b, facts(bad = true)))
        // A new download wins over the old bad mark.
        assertEquals(ModelStatus.DOWNLOADING, VoiceModelLogic.status(b, facts(dl = true, bad = true)))
    }

    @Test fun freeSpaceCoversTheDownloadAndItsInstalledCopy() {
        val rem = VoiceBundle.STT.files
        val need = VoiceModelLogic.needBytes(rem)
        assertEquals(670_478_772L + 652_184_281L + VoiceModelLogic.HEADROOM, need)
        assertNull(VoiceModelLogic.spaceProblem(VoiceBundle.STT, rem, need))
        assertNull(VoiceModelLogic.spaceProblem(VoiceBundle.STT, rem, -1))
        val why = VoiceModelLogic.spaceProblem(VoiceBundle.STT, rem, need - 1)!!
        assertTrue(why, why.startsWith("Not enough free space: “Speech recognition” needs 1.62 GB"))
        // Only what is left counts.
        assertEquals(93_939L * 2 + VoiceModelLogic.HEADROOM, VoiceModelLogic.needBytes(rem.takeLast(1)))
        assertEquals(VoiceModelLogic.HEADROOM, VoiceModelLogic.needBytes(emptyList()))
    }

    @Test fun downloadIsRefusedWhileBusyOrPresent() {
        for (s in listOf(ModelStatus.DOWNLOADING, ModelStatus.VERIFYING, ModelStatus.UNVERIFIED, ModelStatus.VERIFIED)) {
            assertTrue(s.name, VoiceModelLogic.refuseDownload(b, s, b.files, Long.MAX_VALUE / 2) != null)
        }
        assertNull(VoiceModelLogic.refuseDownload(b, ModelStatus.MISSING, b.files, Long.MAX_VALUE / 2))
        assertNull(VoiceModelLogic.refuseDownload(b, ModelStatus.BAD, b.files, Long.MAX_VALUE / 2))
        assertTrue(VoiceModelLogic.refuseDownload(b, ModelStatus.MISSING, b.files, 1)!!.startsWith("Not enough free space"))
    }

    @Test fun downloadTextIsPlainLanguage() {
        val s = VoiceBundle.STT
        fun row(st: Int, soFar: Long = 0, reason: Int = 0) = FileDownload(st, soFar, reason)
        assertEquals("Waiting to start" to -1, VoiceModelLogic.downloadText(s, 0, listOf(row(Download.STATUS_PENDING))))
        assertEquals("335 MB of 670 MB (50%)" to 50,
            VoiceModelLogic.downloadText(s, 0, listOf(row(Download.STATUS_RUNNING, 335_239_386), row(Download.STATUS_PENDING))))
        // Installed files count as done.
        assertEquals(97, VoiceModelLogic.downloadText(s, 652_184_281, listOf(row(Download.STATUS_RUNNING, 1))).second)
        assertEquals("Paused at 100 MB: waiting for Wi-Fi (too large for mobile data)",
            VoiceModelLogic.downloadText(s, 0, listOf(row(Download.STATUS_PAUSED, 100_000_000, 3))).first)
        val failed = VoiceModelLogic.downloadText(s, 0, listOf(row(Download.STATUS_RUNNING, 5), row(Download.STATUS_FAILED, 0, 404)))
        assertTrue(failed.first, failed.first.contains("error 404") && failed.first.contains("Tap Download"))
        assertEquals(100, VoiceModelLogic.downloadText(s, 0, listOf(row(Download.STATUS_SUCCESSFUL, 1))).second)
        assertEquals("Checking and installing: 42%", VoiceModelLogic.installText(42))
        assertEquals("670 MB", VoiceModelLogic.size(670_478_772))
        assertEquals("1.02 GB", VoiceModelLogic.size(1_024_240_975))
        assertEquals("60 MB", VoiceModelLogic.size(VoiceBundle.KITTEN.bytes))
    }
}
