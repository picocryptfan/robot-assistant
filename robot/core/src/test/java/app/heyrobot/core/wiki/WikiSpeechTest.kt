// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** L4 (review-wikipedia-p4): in a car or a headset the copy's answer is said by sentence, only what the passages hold. */
class WikiSpeechTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val riel = "Louis Riel was a Canadian politician and a political leader of the Métis people. He was born in 1844 in the Red River Colony."
    private val found = WikiAnswer.answer("Who was Louis Riel?", listOf(FakeCopy("en", pages = mapOf("Louis Riel" to riel))), now)!!

    @Test fun eachWholeBackedSentenceIsSaidOnceAsItStreams() {
        val s = WikiSpeech(found)
        assertEquals(emptyList<String>(), s.feed("Louis Riel was a Métis leader [1]."))
        assertEquals(listOf("Louis Riel was a Métis leader."), s.feed("Louis Riel was a Métis leader [1]. He was"))
        assertEquals(emptyList<String>(), s.feed("Louis Riel was a Métis leader [1]. He was born in 1844 [2]."))
        val text = "Louis Riel was a Métis leader [1]. He was born in 1844 [2]. He led the Métis people."
        assertEquals(listOf("He was born in 1844."), s.feed(text))
        val spoken = WikiTurn.reply(found, text).spoken
        assertEquals(spoken.substringAfter("1844.").trim(), s.end(text, spoken).rest)
        assertTrue(s.end(text, spoken).rest.startsWith("He led the Métis people."))
    }

    @Test fun nothingUnbackedIsSaidAndTheLeadComesWhole() {
        val s = WikiSpeech(found)
        val text = "Louis Riel was born in 1844. He was born in Ottawa. He led the Métis people. "
        assertEquals(listOf("Louis Riel was born in 1844."), s.feed(text))
        assertEquals(emptyList<String>(), s.feed("$text He was a leader. "))
        val spoken = WikiTurn.reply(found, text).spoken
        assertFalse(spoken.contains("Ottawa"))
        // M1 (review-wikipedia-l4): the lead isn't said after what was: the bubble keeps what was said and the month line.
        assertEquals(WikiTurn.reply(found, "Louis Riel was born in 1844.").spoken, s.end(text, spoken).shown)
        val quiet = WikiSpeech(found)
        assertEquals(emptyList<String>(), quiet.feed("Louis Riel was born in 1850. He led the Métis people. "))
        assertFalse(quiet.spoke)
        assertEquals("Said whole when nothing was said", spoken, quiet.end(text, spoken).rest)
    }

    @Test fun aStreamThatStartsAgainSaysNothingMore() {
        val s = WikiSpeech(found)
        assertEquals(listOf("Louis Riel was a Métis leader."), s.feed("Louis Riel was a Métis leader. He"))
        assertEquals(emptyList<String>(), s.feed("Louis"))
        assertEquals(emptyList<String>(), s.feed("Louis Riel was a Métis leader. He was born in 1844. "))
    }

    /** A stopped turn keeps what was said: the backed sentences before an unbacked one. */
    @Test fun aStopKeepsWhatWasSaid() {
        val partial = "Louis Riel was born in 1844. He was born in Ottawa. He led"
        assertEquals(WikiSpeech(found).feed(partial).joinToString(" "), WikiTurn.kept(found, partial))
        assertEquals("Louis Riel was born in 1844.", WikiTurn.kept(found, partial))
    }

    @Test fun frenchToo() {
        val fr = FakeCopy("fr", "2026-10-01", mapOf("Louis Riel" to "Louis Riel est un homme politique canadien, chef des Métis. Il est né en 1844."))
        val f = WikiAnswer.answer("Qui était Louis Riel ?", listOf(fr), now)!!
        val s = WikiSpeech(f)
        assertEquals(listOf("Louis Riel était le chef des Métis !"), s.feed("Louis Riel était le chef des Métis ! Il est né en 18"))
        assertEquals(emptyList<String>(), s.feed("Louis Riel était le chef des Métis ! Il est né en 1850. Il était canadien. "))
        val text = "Louis Riel était le chef des Métis ! Il est né en 1844."
        val spoken = WikiTurn.reply(f, text).spoken
        val t = WikiSpeech(f)
        assertEquals(listOf("Louis Riel était le chef des Métis !", "Il est né en 1844."), t.feed("$text "))
        assertTrue(spoken, t.end(text, spoken).rest.contains("octobre 2026"))
    }
}
