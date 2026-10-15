// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLogicTest {
    private val fast = BrainModel.FAST
    private val full = fast.bytes
    private fun facts(bytes: Long = 0, verified: Boolean = false, bad: Boolean = false, dl: Boolean = false, imp: Boolean = false, ver: Boolean = false) =
        ModelFacts(bytes, verified, bad, dl, imp, ver)

    @Test fun statusFollowsTheFacts() {
        assertEquals(ModelStatus.MISSING, ModelLogic.status(fast, facts()))
        assertEquals(ModelStatus.MISSING, ModelLogic.status(fast, facts(bytes = full - 1)))
        assertEquals(ModelStatus.UNVERIFIED, ModelLogic.status(fast, facts(bytes = full)))
        assertEquals(ModelStatus.VERIFIED, ModelLogic.status(fast, facts(bytes = full, verified = true)))
        assertEquals(ModelStatus.BAD, ModelLogic.status(fast, facts(bytes = full, bad = true)))
        assertEquals(ModelStatus.VERIFYING, ModelLogic.status(fast, facts(bytes = full, ver = true)))
        // Work in progress wins over a partial or complete file.
        assertEquals(ModelStatus.DOWNLOADING, ModelLogic.status(fast, facts(bytes = 123, dl = true)))
        assertEquals(ModelStatus.IMPORTING, ModelLogic.status(fast, facts(bytes = full, verified = true, imp = true)))
        // Each model by its own size: Fast's file isn't Smarter's.
        assertEquals(ModelStatus.MISSING, ModelLogic.status(BrainModel.SMARTER, facts(bytes = full, verified = true)))
        assertEquals(ModelStatus.VERIFIED, ModelLogic.status(BrainModel.SMARTER, facts(bytes = BrainModel.SMARTER.bytes, verified = true)))
        // Your own file has no size in advance: any complete file.
        assertEquals(ModelStatus.UNVERIFIED, ModelLogic.status(BrainModel.OWN, facts(bytes = 5_000_000)))
        assertEquals(ModelStatus.MISSING, ModelLogic.status(BrainModel.OWN, facts(bytes = 0)))
        assertEquals(ModelStatus.BAD, ModelLogic.status(BrainModel.OWN, facts(bytes = 5_000_000, bad = true)))
    }

    @Test fun downloadNeedsTheFileAndAGigabyteToSpare() {
        val need = full + BrainModel.HEADROOM
        assertEquals(need, ModelFiles.needBytes(fast))
        assertNull(ModelLogic.refuseDownload(fast, ModelStatus.MISSING, need, 0))
        assertNull(ModelLogic.refuseDownload(fast, ModelStatus.BAD, need - full, full))
        val why = ModelLogic.refuseDownload(fast, ModelStatus.MISSING, need - 1, 0)!!
        assertTrue(why, why.startsWith("Not enough free space"))
        assertTrue(why.contains("4.11 GB"))
        // Unknown free space does not block.
        assertNull(ModelLogic.refuseDownload(fast, ModelStatus.MISSING, -1, 0))
    }

    /** 0.4.1: a CPU model's first load writes about 2.5 GB of cache, so its download needs that space too. */
    @Test fun theCpuModelsCountTheirCacheInTheSpaceCheck() {
        for (m in listOf(BrainModel.SMARTER, BrainModel.UNCENSORED)) {
            val need = m.bytes + m.cacheBytes + BrainModel.HEADROOM
            assertEquals(need, ModelFiles.needBytes(m))
            assertTrue(m.cacheBytes in 2_500_000_000L..3_000_000_000L)
            assertNull(ModelLogic.refuseDownload(m, ModelStatus.MISSING, need, 0))
            // The file alone would fit, the file and its cache don't.
            val why = ModelLogic.refuseDownload(m, ModelStatus.MISSING, m.bytes + BrainModel.HEADROOM + 1, 0)!!
            assertTrue(why, why.contains("Gemma 4 E4B") && why.contains("needs 7.26 GB") && why.contains("about 2.60 GB for the cache"))
        }
        // Your own file: the cache estimated from its size (the measured E4B ratio).
        assertEquals(2_744_647_680L, ModelFiles.cacheEstimate(3_659_530_240L))
        val own = 2_000_000_000L
        assertEquals(own + 1_500_000_000L + BrainModel.HEADROOM, ModelFiles.needBytes(BrainModel.OWN, own))
        assertTrue(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.MISSING, own, own + BrainModel.HEADROOM, 0)!!.contains("for the cache"))
        assertNull(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.MISSING, own, ModelFiles.needBytes(BrainModel.OWN, own), 0))
    }

    /** 0.1.2 review, minor 4: voice models still downloading take space Gemma's check must not count as free. */
    @Test fun voiceDownloadsUnderWayCountAgainstGemmasSpace() {
        val need = full + BrainModel.HEADROOM
        assertNull(ModelLogic.refuseDownload(fast, ModelStatus.MISSING, need, 0, pendingBytes = 0))
        val why = ModelLogic.refuseDownload(fast, ModelStatus.MISSING, need, 0, pendingBytes = 1_000_000_000L)!!
        assertTrue(why, why.endsWith("needs 4.11 GB (the file plus 1 GB to spare) and only 3.11 GB will be left once the downloads under way finish."))
        assertNull(ModelLogic.refuseDownload(fast, ModelStatus.MISSING, need + 1_000_000_000L, 0, pendingBytes = 1_000_000_000L))
        assertTrue(ModelLogic.refuseImport(fast, ModelStatus.MISSING, full, need, 0, pendingBytes = 1) != null)
    }

    @Test fun downloadIsRefusedWhileBusyOrPresent() {
        for (s in listOf(ModelStatus.DOWNLOADING, ModelStatus.IMPORTING, ModelStatus.VERIFYING, ModelStatus.VERIFIED, ModelStatus.UNVERIFIED)) {
            assertTrue(s.name, ModelLogic.refuseDownload(fast, s, Long.MAX_VALUE / 2, 0) != null)
        }
        // One model download or copy at a time, so the space check holds.
        val busy = ModelLogic.refuseDownload(BrainModel.SMARTER, ModelStatus.MISSING, Long.MAX_VALUE / 2, 0, busyWith = fast)!!
        assertTrue(busy, busy.contains("“Fast” is downloading"))
        assertNull(ModelLogic.refuseDownload(BrainModel.SMARTER, ModelStatus.MISSING, Long.MAX_VALUE / 2, 0, busyWith = BrainModel.SMARTER))
        assertTrue(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.MISSING, 5_000_000, Long.MAX_VALUE / 2, 0, busyWith = BrainModel.UNCENSORED) != null)
        // Your own file is never downloaded.
        assertTrue(ModelLogic.refuseDownload(BrainModel.OWN, ModelStatus.MISSING, Long.MAX_VALUE / 2, 0)!!.contains("import"))
    }

    @Test fun importChecksTheSizeTheProviderReports() {
        val free = full + BrainModel.HEADROOM
        assertNull(ModelLogic.refuseImport(fast, ModelStatus.MISSING, full, free, 0))
        assertNull(ModelLogic.refuseImport(fast, ModelStatus.MISSING, -1, free, 0))
        assertTrue(ModelLogic.refuseImport(fast, ModelStatus.MISSING, 1234, free, 0)!!.contains("1,234 bytes"))
        assertTrue(ModelLogic.refuseImport(fast, ModelStatus.VERIFIED, full, free, 0) != null)
        assertTrue(ModelLogic.refuseImport(fast, ModelStatus.UNVERIFIED, full, 10, 0) != null)
        // The files an import replaces count as free.
        assertNull(ModelLogic.refuseImport(fast, ModelStatus.UNVERIFIED, full, BrainModel.HEADROOM, full))
        // A built-in model takes only its own file.
        assertTrue(ModelLogic.refuseImport(BrainModel.SMARTER, ModelStatus.MISSING, full, Long.MAX_VALUE / 2, 0)!!.contains(BrainModel.SMARTER.file))
        // Your own file: any size from 1 MB (unknown too: the copy watches the space), and a checked one can be replaced.
        assertTrue(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.MISSING, 1234, Long.MAX_VALUE / 2, 0)!!.contains("too small"))
        assertNull(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.MISSING, -1, 10, 0))
        assertNull(ModelLogic.refuseImport(BrainModel.OWN, ModelStatus.VERIFIED, 5_000_000, Long.MAX_VALUE / 2, 0))
    }

    @Test fun downloadTextIsPlainLanguage() {
        assertEquals("Waiting to start", ModelLogic.downloadText(fast, Download.STATUS_PENDING, 0, 0))
        // Truncated, never rounded up: 100% only when every byte is there.
        assertEquals("1.56 GB of 3.11 GB (49%)", ModelLogic.downloadText(fast, Download.STATUS_RUNNING, 0, full / 2))
        assertEquals("1.83 GB of 3.66 GB (50%)", ModelLogic.downloadText(BrainModel.SMARTER, Download.STATUS_RUNNING, 0, BrainModel.SMARTER.bytes / 2))
        assertEquals(99, ModelLogic.percent(full - 1, full))
        assertEquals("Paused at 0.50 GB: waiting for Wi-Fi (too large for mobile data)", ModelLogic.downloadText(fast, Download.STATUS_PAUSED, 3, 500_000_000))
        assertTrue(ModelLogic.downloadText(fast, Download.STATUS_FAILED, 404, 0).contains("error 404"))
        assertTrue(ModelLogic.downloadText(fast, Download.STATUS_FAILED, 1006, 0).contains("ran out of space"))
    }

    @Test fun percentIsClamped() {
        assertEquals(0, ModelLogic.percent(-5, full))
        assertEquals(100, ModelLogic.percent(full * 2, full))
        assertEquals(25, ModelLogic.percent(full / 4 + 1_000_000, full))
        assertEquals(0, ModelLogic.percent(5, -1))
    }

    @Test fun headlinesNameWhatHappened() {
        assertEquals("Not downloaded", ModelLogic.headline(ModelStatus.MISSING))
        assertEquals("No file yet", ModelLogic.headline(ModelStatus.MISSING, BrainModel.OWN))
        assertEquals("The file is damaged", ModelLogic.headline(ModelStatus.BAD, BrainModel.SMARTER))
        assertEquals("Robot can't use this file", ModelLogic.headline(ModelStatus.BAD, BrainModel.OWN))
        assertFalse(ModelLogic.headline(ModelStatus.VERIFIED).isEmpty())
    }

    /** 0.4.0's E2B file, mark and download keep their names, so an installed model stays checked across the update. */
    @Test fun fastKeepsTheFilesOfEarlierVersions() {
        assertEquals("model_verified.txt", ModelFiles.verifiedMark(fast))
        assertEquals("download_id", ModelFiles.prefKey(fast, "download_id"))
        assertEquals("bad", ModelFiles.prefKey(fast, "bad"))
        assertNull(ModelFiles.cacheDir(fast))
        // The others have their own.
        val marks = BrainModel.entries.map(ModelFiles::verifiedMark)
        assertEquals(marks.size, marks.toSet().size)
        assertEquals("model_verified_e4b_cpu.txt", ModelFiles.verifiedMark(BrainModel.SMARTER))
        assertEquals("download_id_e4b_cpu_uncensored", ModelFiles.prefKey(BrainModel.UNCENSORED, "download_id"))
        assertEquals("litert_cache/e4b_cpu", ModelFiles.cacheDir(BrainModel.SMARTER))
        assertEquals("litert_cache/own", ModelFiles.cacheDir(BrainModel.OWN))
        assertNull(ModelFiles.cacheDir(BrainModel.OWN, ModelBackend.TPU))
        val files = BrainModel.entries.map { it.file }
        assertEquals(files.size, files.toSet().size)
        // No file's name starts another's (deleting one model's leftovers never touches another's file).
        for (a in files) for (b in files) if (a != b) assertFalse("$a / $b", b.startsWith(a))
    }

    @Test fun theLoadedFileHoldsIdsAndAPidOnly() {
        val l = BrainLoaded(BrainModel.SMARTER, ModelBackend.CPU, 4242)
        assertEquals("e4b_cpu cpu 4242", l.encode())
        assertEquals(l, BrainLoaded.decode(l.encode() + "\n"))
        for (bad in listOf(null, "", "e4b_cpu cpu", "nope cpu 1", "e4b_cpu vulkan 1", "e4b_cpu cpu -3", "e4b_cpu cpu x")) assertNull(bad, BrainLoaded.decode(bad))
    }
}
