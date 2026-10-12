package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0: downloads name Robot, not the phone, and a big one asks about mobile data first (Robot can't see the network). */
class DownloadPolicyTest {
    @Test fun userAgentNamesRobotAndItsVersionOnly() {
        assertEquals("Robot/1.0.0", DownloadPolicy.userAgent("1.0.0"))
        assertEquals("Robot/0.9.0-rc1", DownloadPolicy.userAgent("0.9.0-rc1"))
        assertEquals("Robot/1.0evil", DownloadPolicy.userAgent("1.0 (evil)\r\nX: y".take(12)))
    }

    @Test fun onlyBigDownloadsAskFirst() {
        // Fast and the bigger models, speech recognition and the natural voice ask; the small ones don't.
        for (b in listOf(BrainModel.FAST.bytes, BrainModel.GENERAL.bytes, VoiceBundle.STT.bytes, VoiceBundle.KOKORO.bytes)) assertTrue("$b", DownloadPolicy.asksFirst(b))
        for (b in listOf(VoiceBundle.KITTEN.bytes, VoiceBundle.SPEAKERS.bytes, MtBundle.FR_EN.bytes, 199_999_999L)) assertFalse("$b", DownloadPolicy.asksFirst(b))
        assertTrue(DownloadPolicy.asksFirst(200_000_000L))
    }

    @Test fun theQuestionSaysWhyAndBothWays() {
        assertEquals("Download 3.11 GB?", DownloadPolicy.title(BrainModel.FAST.bytes))
        val text = DownloadPolicy.text(VoiceBundle.STT.bytes)
        assertTrue(text, text.contains("can't tell whether you're on Wi-Fi or mobile data") && text.contains("670 MB") && text.contains(DownloadPolicy.WAIT))
        assertEquals("Download anyway", DownloadPolicy.ANYWAY)
    }
}
