package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelLogicTest {
    private val full = ModelSpec.BYTES
    private fun facts(bytes: Long = 0, verified: Boolean = false, bad: Boolean = false, dl: Boolean = false, imp: Boolean = false, ver: Boolean = false) =
        ModelFacts(bytes, verified, bad, dl, imp, ver)

    @Test fun statusFollowsTheFacts() {
        assertEquals(ModelStatus.MISSING, ModelLogic.status(facts()))
        assertEquals(ModelStatus.MISSING, ModelLogic.status(facts(bytes = full - 1)))
        assertEquals(ModelStatus.UNVERIFIED, ModelLogic.status(facts(bytes = full)))
        assertEquals(ModelStatus.VERIFIED, ModelLogic.status(facts(bytes = full, verified = true)))
        assertEquals(ModelStatus.BAD, ModelLogic.status(facts(bytes = full, bad = true)))
        assertEquals(ModelStatus.VERIFYING, ModelLogic.status(facts(bytes = full, ver = true)))
        // Work in progress wins over a partial or complete file.
        assertEquals(ModelStatus.DOWNLOADING, ModelLogic.status(facts(bytes = 123, dl = true)))
        assertEquals(ModelStatus.IMPORTING, ModelLogic.status(facts(bytes = full, verified = true, imp = true)))
    }

    @Test fun downloadNeedsTheFileAndAGigabyteToSpare() {
        val need = ModelSpec.BYTES + ModelSpec.HEADROOM
        assertNull(ModelLogic.refuseDownload(ModelStatus.MISSING, need, 0))
        assertNull(ModelLogic.refuseDownload(ModelStatus.BAD, need - full, full))
        val why = ModelLogic.refuseDownload(ModelStatus.MISSING, need - 1, 0)!!
        assertTrue(why, why.startsWith("Not enough free space"))
        assertTrue(why.contains("4.11 GB"))
        // Unknown free space does not block.
        assertNull(ModelLogic.refuseDownload(ModelStatus.MISSING, -1, 0))
    }

    /** 0.1.2 review, minor 4: voice models still downloading take space Gemma's check must not count as free. */
    @Test fun voiceDownloadsUnderWayCountAgainstGemmasSpace() {
        val need = ModelSpec.BYTES + ModelSpec.HEADROOM
        assertNull(ModelLogic.refuseDownload(ModelStatus.MISSING, need, 0, pendingBytes = 0))
        val why = ModelLogic.refuseDownload(ModelStatus.MISSING, need, 0, pendingBytes = 1_000_000_000L)!!
        assertTrue(why, why.endsWith("needs 4.11 GB (the file plus 1 GB to spare) and only 3.11 GB will be left once the voice models still downloading finish."))
        assertNull(ModelLogic.refuseDownload(ModelStatus.MISSING, need + 1_000_000_000L, 0, pendingBytes = 1_000_000_000L))
        assertTrue(ModelLogic.refuseImport(ModelStatus.MISSING, ModelSpec.BYTES, need, 0, pendingBytes = 1) != null)
    }

    @Test fun downloadIsRefusedWhileBusyOrPresent() {
        for (s in listOf(ModelStatus.DOWNLOADING, ModelStatus.IMPORTING, ModelStatus.VERIFYING, ModelStatus.VERIFIED, ModelStatus.UNVERIFIED)) {
            assertTrue(s.name, ModelLogic.refuseDownload(s, Long.MAX_VALUE / 2, 0) != null)
        }
    }

    @Test fun importChecksTheSizeTheProviderReports() {
        val free = ModelSpec.BYTES + ModelSpec.HEADROOM
        assertNull(ModelLogic.refuseImport(ModelStatus.MISSING, ModelSpec.BYTES, free, 0))
        assertNull(ModelLogic.refuseImport(ModelStatus.MISSING, -1, free, 0))
        assertTrue(ModelLogic.refuseImport(ModelStatus.MISSING, 1234, free, 0)!!.contains("1,234 bytes"))
        assertTrue(ModelLogic.refuseImport(ModelStatus.VERIFIED, ModelSpec.BYTES, free, 0) != null)
        assertTrue(ModelLogic.refuseImport(ModelStatus.UNVERIFIED, ModelSpec.BYTES, 10, 0) != null)
        // The files an import replaces count as free.
        assertNull(ModelLogic.refuseImport(ModelStatus.UNVERIFIED, ModelSpec.BYTES, ModelSpec.HEADROOM, ModelSpec.BYTES))
    }

    @Test fun downloadTextIsPlainLanguage() {
        assertEquals("Waiting to start", ModelLogic.downloadText(Download.STATUS_PENDING, 0, 0))
        // Truncated, never rounded up: 100% only when every byte is there.
        assertEquals("1.56 GB of 3.11 GB (49%)", ModelLogic.downloadText(Download.STATUS_RUNNING, 0, full / 2))
        assertEquals(99, ModelLogic.percent(full - 1))
        assertEquals("Paused at 0.50 GB: waiting for Wi-Fi (too large for mobile data)", ModelLogic.downloadText(Download.STATUS_PAUSED, 3, 500_000_000))
        assertTrue(ModelLogic.downloadText(Download.STATUS_FAILED, 404, 0).contains("error 404"))
        assertTrue(ModelLogic.downloadText(Download.STATUS_FAILED, 1006, 0).contains("ran out of space"))
    }

    @Test fun percentIsClamped() {
        assertEquals(0, ModelLogic.percent(-5))
        assertEquals(100, ModelLogic.percent(full * 2))
        assertEquals(25, ModelLogic.percent(full / 4 + 1_000_000))
    }

    @Test fun specMatchesThePhaseZeroFile() {
        assertEquals("gemma-4-E2B-it_Google_Tensor_G5.litertlm", ModelSpec.FILE)
        assertEquals(3_113_545_589L, ModelSpec.BYTES)
        assertEquals("af1082986639ecde7db95d91be6fe54f8b6b458104734c5bafc204e69d6852dc", ModelSpec.SHA256)
        assertTrue(ModelSpec.URL.endsWith("/" + ModelSpec.FILE))
        assertTrue(ModelSpec.URL.contains("b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1"))
    }
}
