// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** M2 (review-wikipedia-p4): a one-shot that timed out is stopped, never the answer; "nothing on X" only when the copy had nothing. */
class WikiTimeoutTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val copy = FakeCopy("en", pages = mapOf("Louis Riel" to "Louis Riel was a Métis leader born in 1844 in the Red River Settlement.\n\nHe led two resistance movements."))

    @Test fun aTimedOutOneShotIsStopped() {
        assertNull(WikiTurn.said(OneShotResult.TimedOut("Louis Riel was born")))
        assertNull(WikiTurn.said(OneShotResult.TimedOut("")))
        assertEquals("Louis Riel [1].", WikiTurn.said(OneShotResult.Done("Louis Riel [1].", DoneReason.END)))
        val r = runBlocking { WikiTurn.run("Who was Louis Riel?", 1, { listOf(copy) }, now, emptyList(), spoken = false) { WikiTurn.said(OneShotResult.TimedOut("Louis Riel was")) } }
        assertEquals(WikiTurn.Result.Stopped, r)
    }

    @Test fun noWordsFromTheModelSayThePassageNotNothing() {
        val said = WikiAnswer.reply(WikiAnswer.answer("Who was Louis Riel?", listOf(copy), now, spoken = false)!!, "")
        assertFalse(said, "has nothing on" in said)
        assertTrue(said, "born in 1844" in said)
    }
}
