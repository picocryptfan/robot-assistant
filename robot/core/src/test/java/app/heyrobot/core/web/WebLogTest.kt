// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Design A10.5: web search's voice log lines (160–163) and logcat lines hold codes and numbers only. */
class WebLogTest {
    @Test fun theVoiceLogSaysWhatHappenedInNumbers() {
        assertEquals(listOf(160, 161, 162, 163), listOf(VoiceEvent.WEB_SEARCH, VoiceEvent.WEB_PAGES, VoiceEvent.WEB_ANSWER, VoiceEvent.WEB_FAILED).map { it.id })
        for (e in WebLogEvent.entries) assertEquals(e.reservedId, VoiceEvent.valueOf(e.name).id)
        fun say(e: VoiceEvent, a: Int, b: Int = 0, c: Int = 0) = VoiceLogText.describe(VoiceLogEntry(0, e, a, b, c))
        assertEquals("Web search with Your own SearXNG: 8 results in 950 ms", say(VoiceEvent.WEB_SEARCH, 1, 8, 950))
        assertEquals("Pages read for the answer: 2 · 1 skipped (not a public address) · 3100 ms",
            say(VoiceEvent.WEB_PAGES, 2, WebLogNumbers.skipped(listOf(PageSkip.NOT_PUBLIC)), 3100))
        assertEquals("Pages read for the answer: 3 · 3100 ms", say(VoiceEvent.WEB_PAGES, 3, 0, 3100))
        assertEquals("Answer from the web: about 2870 tokens in, 61 out · first words after 5400 ms", say(VoiceEvent.WEB_ANSWER, 287, 61, 5400))
        assertEquals("Web search failed: not on the home network (Your own SearXNG)", say(VoiceEvent.WEB_FAILED, WebFail.NOT_HOME.id, 1))
    }

    @Test fun logcatNeverGetsTheQueryOrAnAddress() {
        val lines = ArrayList<String>()
        RLog.sink = { lines += it }
        try {
            // What the voice process logs for a search (assistant WebTool), with a planted query and domain at hand.
            val query = "planted query zxqv"
            val url = "https://planted-domain.example/zxqv"
            RLog.i("web_search", "svc" to SearchService.BRAVE.code, "n" to 8, "ms" to 950L)
            RLog.i("web_answer", "in" to 2870, "out" to 61, "first_ms" to 5400L)
            // A string by mistake is never written.
            RLog.i("web_failed", "why" to query, "url" to url)
        } finally {
            RLog.sink = null
        }
        val all = lines.joinToString("\n")
        for (secret in listOf("planted", "zxqv", "example")) assertFalse(all, all.contains(secret))
        assertEquals("web_search svc=3 n=8 ms=950", lines[0])
    }
}
