package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {
    private fun split(vararg chunks: String): List<String> {
        val s = SentenceSplitter()
        val out = chunks.flatMap { s.feed(it) }.toMutableList()
        s.flush()?.let { out += it }
        return out
    }

    @Test fun sentencesComeOutAsSoonAsTheyAreComplete() {
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Hello there"))
        assertEquals(listOf("Hello there!"), s.feed("! How"))
        assertEquals(listOf("How are you?"), s.feed(" are you? I'm"))
        // The end of the stream so far may still be "3." of "3.5": wait for the next chunk or the flush.
        assertEquals(emptyList<String>(), s.feed(" fine."))
        assertEquals("I'm fine.", s.flush())
        assertNull(s.flush())
    }

    @Test fun decimalsAndAbbreviationsDoNotSplit() {
        assertEquals(listOf("It costs 3.50 euros, e.g. at the bakery.", "Done."), split("It costs 3.50 euros, e.g. at the bakery. Done."))
        assertEquals(listOf("Ask Dr. Smith today.", "Then rest."), split("Ask Dr. Smith today. Then rest."))
    }

    @Test fun listsAndLinesSplit() {
        assertEquals(listOf("Steps:", "1. Boil water.", "2. Add tea."), split("Steps:\n1. Boil water.\n2. Add tea."))
    }

    @Test fun longRunsAreCutSoSpeechCanStart() {
        val s = SentenceSplitter(maxChars = 50)
        val out = s.feed("one two three four five six seven eight nine ten eleven twelve thirteen")
        assertEquals(1, out.size)
        assertTrue(out[0].length <= 51)
    }

    @Test fun markdownIsRemovedForSpeech() {
        assertEquals("Bold and code", SpeechText.clean("**Bold** and `code`"))
        assertEquals("Title", SpeechText.clean("## Title"))
        assertEquals("first item", SpeechText.clean("- first item"))
        assertEquals("second", SpeechText.clean("2. second"))
        assertEquals("see the docs", SpeechText.clean("see [the docs](https://x.y/z)"))
        assertEquals("go to a link now", SpeechText.clean("go to https://example.com/a?b now"))
        assertEquals("", SpeechText.clean("***"))
    }
}
