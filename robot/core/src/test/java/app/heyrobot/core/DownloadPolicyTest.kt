// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.update.GitHubHosts
import app.heyrobot.core.web.OpenLibraryWire
import app.heyrobot.core.web.WebUserAgent
import app.heyrobot.core.web.WikipediaWire
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

    /** 1.0 rc1: one version string in every User-Agent, "-" kept (a token character), as Settings › About shows it. */
    @Test fun everyUserAgentNamesTheSameVersion() {
        val v = "1.0.0-rc1"
        val product = "Robot/1.0.0-rc1"
        assertEquals(product, AppId.product(v))
        assertEquals(product, DownloadPolicy.userAgent(v))
        assertEquals(product, GitHubHosts.userAgent(v))
        for (ua in listOf(WebUserAgent.api(v), WebUserAgent.api(v, null), Nearby.userAgent(v), OpenLibraryWire.request("dune", WebUserAgent.api(v)).header("User-Agent"),
            WikipediaWire.searchRequest("en", "dune", v)!!.header("User-Agent"))) {
            assertTrue(ua, ua!!.startsWith("$product (${AppId.PACKAGE}; "))
        }
        // Only token characters reach the header: no space, bracket, quote, CR or LF, and no letter outside ASCII.
        assertEquals("Robot/1.0.0-betaX", AppId.product("1.0.0-beta (X)\r\n\"é"))
        assertEquals("Robot/0", AppId.product("(;)"))
        assertEquals(32, AppId.product("1".repeat(40)).removePrefix("Robot/").length)
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
