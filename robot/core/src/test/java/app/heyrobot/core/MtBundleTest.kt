package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream

/** The fast translation engine's files (docs/conversation-mode.md 4.6, translation-plan.md 1.4) and how they install. */
class MtBundleTest {
    @get:Rule val tmp = TemporaryFolder()
    private val b = MtBundle.FR_EN

    private fun sha(x: ByteArray) = MessageDigest.getInstance("SHA-256").digest(x).joinToString("") { "%02x".format(it) }
    private fun gzip(x: ByteArray) = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(x) } }.toByteArray()
    private fun file(name: String) = b.files.single { it.name == name }

    @Test fun theManifestIsTheTranslationPlansPinnedFiles() {
        assertEquals(5, b.files.size)
        assertEquals(b.files.size, b.files.map { it.name }.toSet().size)
        // 52 MB to download (one vocabulary for both directions), 73 MB on the phone.
        assertEquals(51_577_481L, b.downloadBytes)
        assertEquals(73_135_034L, b.bytes)
        assertEquals("Fast translation engine (52 MB)", MtModelLogic.label(b))
        for (f in b.files) {
            val url = b.url(f)
            assertTrue(url, url.startsWith("https://storage.googleapis.com/moz-fx-translations-data--303e-prod-translations-data/models/"))
            // The training run's id is in the path, so the file at a URL can't change.
            val run = if (f.path.startsWith("fr-en/")) "fr-en/retrain_hr_EFgIftH_RrCyzl5gjemVNg/exported/" else "en-fr/retrain_hr_NLIxDbE1TBGyOTI-zwZagw/exported/"
            assertEquals(run + f.name + ".gz", f.path)
            assertTrue(Regex("[0-9a-f]{64}").matches(f.sha256) && Regex("[0-9a-f]{64}").matches(f.gzSha256))
            assertTrue(f.gzBytes in 1 until f.bytes)
        }
        // The models' unpacked hashes are models.json's uncompressedHash.
        assertEquals("15f997bc0d13808b0b0fbd0786e684a3c8a52adcd8071844b76123fdacbf2b90", file("model.fren.intgemm.alphas.bin").sha256)
        assertEquals("6322e296d4fecfe395a8d5723da4ec37ecbe6d7613bb1dfcf4b28e2a47498b68", file("model.enfr.intgemm.alphas.bin").sha256)
        assertEquals(31_561_787L, file("model.enfr.intgemm.alphas.bin").bytes)
        assertEquals("783abf3abe075afdf8d85d233994bef2c3a064e935ab1bed946820aff6ac002a", file("vocab.fren.spm").sha256)
        assertEquals(b, MtBundle.byId("mt-fr-en"))
        assertEquals(File(tmp.root, "models/mt-fr-en"), MtBundle.dir(tmp.root, b))
    }

    @Test fun eachDirectionLoadsItsModelAndShortlistAndTheSharedVocabulary() {
        val fr = b.direction("fr", "en")!!
        val en = b.direction("en", "fr")!!
        assertEquals(MtDirection("fr", "en", "model.fren.intgemm.alphas.bin", "vocab.fren.spm", "lex.50.50.fren.s2t.bin"), fr)
        assertEquals(MtDirection("en", "fr", "model.enfr.intgemm.alphas.bin", "vocab.fren.spm", "lex.50.50.enfr.s2t.bin"), en)
        assertEquals(listOf("model.fren.intgemm.alphas.bin", "vocab.fren.spm", "lex.50.50.fren.s2t.bin"), b.filesOf(fr).map { it.name })
        assertEquals(b.files.map { it.name }.toSet(), b.directions.flatMap { d -> b.filesOf(d).map { it.name } }.toSet())
        assertEquals(b to fr, MtBundle.forDirection("fr-en"))
        assertEquals(b to en, MtBundle.forDirection("en-fr"))
        assertNull(MtBundle.forDirection("de-en"))
        assertNull(b.direction("fr", "fr"))
        // Every pair a conversation can use translates both ways.
        for (p in LangPair.entries) {
            val m = MtBundle.of(p)!!
            assertNotNull(m.direction(p.first, p.second))
            assertNotNull(m.direction(p.second, p.first))
        }
    }

    @Test fun aGzipIsCheckedThenUnpackedAndCheckedAgain() {
        val raw = "Je vais être en retard de dix minutes. ".repeat(500).toByteArray()
        val gz = gzip(raw)
        val f = MtFile("x.bin", "fr-en/run/exported/x.bin.gz", gz.size.toLong(), sha(gz), raw.size.toLong(), sha(raw))
        val src = tmp.newFile("x.bin.gz").apply { writeBytes(gz) }
        val dest = File(tmp.root, "models/mt-fr-en/x.bin")
        var progress = 0L
        assertEquals(InstallResult.OK, installGzVerified(src, dest, f) { progress = it })
        assertArrayEquals(raw, dest.readBytes())
        assertEquals(raw.size.toLong(), progress)
        dest.delete()
        // A .gz that isn't the pinned one is never unpacked.
        assertEquals(InstallResult.WRONG_HASH, installGzVerified(src, dest, f.copy(gzSha256 = "0".repeat(64))))
        assertEquals(InstallResult.WRONG_SIZE, installGzVerified(src, dest, f.copy(gzBytes = f.gzBytes + 1)))
        assertEquals(InstallResult.WRONG_SIZE, installGzVerified(File(tmp.root, "missing.gz"), dest, f))
        assertFalse(dest.exists())
        // The .gz checks out but what it unpacks to doesn't (uncompressedHash): nothing is kept, no part file left.
        assertEquals(InstallResult.WRONG_HASH, installGzVerified(src, dest, f.copy(sha256 = "0".repeat(64))))
        assertEquals(InstallResult.WRONG_SIZE, installGzVerified(src, dest, f.copy(bytes = f.bytes - 1)))
        assertEquals(InstallResult.WRONG_SIZE, installGzVerified(src, dest, f.copy(bytes = f.bytes + 1)))
        assertFalse(dest.exists())
        assertEquals(emptyList<String>(), dest.parentFile!!.list()!!.toList())
        // Bytes that match their manifest entry but aren't gzip at all.
        val notGz = tmp.newFile("y.gz").apply { writeBytes(raw) }
        assertEquals(InstallResult.IO_ERROR, installGzVerified(notGz, dest, f.copy(gzBytes = raw.size.toLong(), gzSha256 = sha(raw))))
        assertFalse(dest.exists())
    }

    @Test fun theMarkVouchesForEachDirectionOnItsOwn() {
        val onDisk = b.files.associate { it.name to (it.bytes to 1_000L) }
        fun mark(names: List<String>) = b.files.filter { it.name in names }.map { InstalledFile(it.name, it.bytes, 1_000L, it.sha256) }
        val fr = b.direction("fr", "en")!!
        val en = b.direction("en", "fr")!!
        val frOnly = mark(listOf("model.fren.intgemm.alphas.bin", "lex.50.50.fren.s2t.bin", "vocab.fren.spm"))
        assertTrue(MtMark.ready(b, fr, frOnly) { onDisk[it] })
        assertFalse(MtMark.ready(b, en, frOnly) { onDisk[it] })
        val all = mark(b.files.map { it.name })
        assertTrue(MtMark.ready(b, en, all) { onDisk[it] })
        // A file changed on disk since it was checked, or a mark with another hash, vouches for nothing.
        assertFalse(MtMark.ready(b, fr, all) { n -> if (n == "vocab.fren.spm") 814_404L to 2_000L else onDisk[n] })
        assertFalse(MtMark.ready(b, fr, all.map { if (it.name == "lex.50.50.fren.s2t.bin") it.copy(sha256 = "0".repeat(64)) else it }) { onDisk[it] })
        // A real directory: nothing there, nothing ready.
        assertFalse(MtMark.complete(b, File(tmp.root, "none")))
        assertFalse(MtMark.ready(b, fr, File(tmp.root, "none")))
    }

    @Test fun downloadStatesSpaceAndProgress() {
        fun facts(vouched: Int = 0, downloading: Boolean = false, downloaded: Boolean = false, installing: Boolean = false, bad: Boolean = false) =
            VoiceFacts(vouched, downloading, downloaded, installing, bad)
        assertEquals(ModelStatus.VERIFIED, MtModelLogic.status(b, facts(vouched = 5)))
        assertEquals(ModelStatus.DOWNLOADING, MtModelLogic.status(b, facts(vouched = 2, downloading = true)))
        assertEquals(ModelStatus.UNVERIFIED, MtModelLogic.status(b, facts(downloaded = true)))
        assertEquals(ModelStatus.VERIFYING, MtModelLogic.status(b, facts(installing = true)))
        assertEquals(ModelStatus.BAD, MtModelLogic.status(b, facts(bad = true)))
        assertEquals(ModelStatus.MISSING, MtModelLogic.status(b, facts()))
        // Every .gz waits while each is unpacked beside it, plus the headroom kept free.
        assertEquals(51_577_481L + 73_135_034L + VoiceModelLogic.HEADROOM, MtModelLogic.needBytes(b.files))
        assertEquals(73_135_034L + VoiceModelLogic.HEADROOM, MtModelLogic.needBytes(b.files, b.files.map { it.name }.toSet()))
        val why = MtModelLogic.refuseDownload(b, ModelStatus.MISSING, b.files, 100_000_000L)!!
        assertEquals("Not enough free space: “Fast translation engine” needs 425 MB while it downloads and installs, and 100 MB is free.", why)
        assertNull(MtModelLogic.refuseDownload(b, ModelStatus.MISSING, b.files, 1_000_000_000L))
        assertNull(MtModelLogic.refuseDownload(b, ModelStatus.BAD, b.files, -1))
        assertEquals("Already on this phone.", MtModelLogic.refuseDownload(b, ModelStatus.VERIFIED, emptyList(), 1_000_000_000L))
        assertEquals("The download is already running.", MtModelLogic.refuseDownload(b, ModelStatus.DOWNLOADING, b.files, 0))
        // Progress is counted in .gz bytes.
        val model = file("model.fren.intgemm.alphas.bin")
        val (line, pct) = MtModelLogic.downloadText(b, 0, listOf(FileDownload(Download.STATUS_RUNNING, model.gzBytes, 0)))
        assertEquals("23 MB of 52 MB (44%)", line)
        assertEquals(44, pct)
        assertEquals("Downloaded. Checking the files next." to 100, MtModelLogic.downloadText(b, 0, listOf(FileDownload(Download.STATUS_SUCCESSFUL, 1, 0))))
        // What a running download will still take: the rest of its .gz, then every unpacked file.
        assertEquals(model.gzBytes - 1_000 + b.bytes, MtModelLogic.pendingBytes(b.files, mapOf(model.name to FileDownload(Download.STATUS_RUNNING, 1_000, 0))))
        assertEquals(0L, MtModelLogic.pendingBytes(b.files, mapOf(model.name to FileDownload(Download.STATUS_SUCCESSFUL, model.gzBytes, 0))))
    }
}
