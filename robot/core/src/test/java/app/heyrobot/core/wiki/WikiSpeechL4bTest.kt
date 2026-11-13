// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** review-wikipedia-l4: what a car or a headset hears of the copy's answer matches what the bubble keeps. */
class WikiSpeechL4bTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val riel = "Louis Riel was a Canadian politician and a political leader of the Métis people. He was born in 1844 in St. Boniface. " +
        "The colony grew by 1.5 times."
    private val found = WikiAnswer.answer("Who was Louis Riel?", listOf(FakeCopy("en", pages = mapOf("Louis Riel" to riel))), now)!!
    private val line = WikiTurn.reply(found, "Louis Riel was born in 1844.").spoken.substringAfter("1844.").trim()

    /** M1: after a said sentence and an unbacked one, the lead isn't said: the said sentence and the month line are kept. */
    @Test fun afterAnUnbackedSentenceOnlyTheMonthLineIsLeft() {
        val s = WikiSpeech(found)
        val text = "Louis Riel was born in 1844. He was born in Ottawa."
        assertEquals(listOf("Louis Riel was born in 1844."), s.feed("$text "))
        val end = s.end(text, WikiTurn.reply(found, text).spoken)
        assertEquals("Louis Riel was born in 1844. $line", end.shown)
        assertEquals(line, end.rest)
    }

    /** L2: a piece with no letters isn't said, so the reply isn't said again. */
    @Test fun aLetterlessPieceDoesNotRepeatTheReply() {
        val s = WikiSpeech(found)
        val text = "Louis Riel was born in 1844 [1]. [2]. He led the Métis people… … He was a leader."
        s.feed("$text ")
        assertEquals(line, s.end(text, WikiTurn.reply(found, text).spoken).rest)
    }

    /** L4: stopped after speech (ANSWER_MS, a :brain cancel), what was said stays and the month line is said. */
    @Test fun aStoppedAnswerStillSaysTheMonthLine() {
        val s = WikiSpeech(found)
        val heard = "Louis Riel was born in 1844. He led"
        assertEquals(listOf("Louis Riel was born in 1844."), s.feed(heard))
        val end = s.end(heard, null)
        assertEquals("Louis Riel was born in 1844. $line", end.shown)
        assertEquals(line, end.rest)
        assertEquals("Nothing said, nothing to say", "", WikiSpeech(found).end(heard, null).rest)
    }

    /** L3: a stop inside "1.5" or after "St." keeps nothing cut there, and "St. " doesn't end a said piece. */
    @Test fun nothingCutMidNumberOrAbbreviation() {
        assertEquals("Louis Riel was born in 1844.", WikiTurn.kept(found, "Louis Riel was born in 1844. The colony grew by 1."))
        assertEquals("Louis Riel was born in 1844.", WikiTurn.kept(found, "Louis Riel was born in 1844. He was born in St."))
        assertEquals(listOf("Louis Riel was born in 1844."), WikiSpeech(found).feed("Louis Riel was born in 1844. He was born in St. Boniface"))
    }
}
