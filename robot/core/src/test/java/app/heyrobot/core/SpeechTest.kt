package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun theFirstPieceIsShortSoSpeechStartsFast() {
        // At the first clause break after 5 words (docs/voice-plan.md 4.9), then whole sentences.
        val s = SentenceSplitter()
        assertEquals(emptyList<String>(), s.feed("Sure, here's what I"))
        assertEquals(listOf("Sure, here's what I found today,"), s.feed(" found today, and it"))
        assertEquals(emptyList<String>(), s.feed(" looks good, overall"))
        assertEquals(listOf("and it looks good, overall."), s.feed(". Next"))
        // A new reply gets a short first piece again.
        s.reset()
        assertEquals(listOf("One two three four five six seven eight nine ten eleven twelve"),
            s.feed("One two three four five six seven eight nine ten eleven twelve thirteen"))
        assertEquals("thirteen", s.flush())
    }

    @Test fun aShortSentenceIsItsOwnFirstPiece() {
        val s = SentenceSplitter()
        assertEquals(listOf("Sure!"), s.feed("Sure! The weather today is sunny, warm and calm, with"))
        // Already spoken: no early cut any more, wait for the sentence.
        assertEquals(emptyList<String>(), s.feed(" a light breeze all afternoon long and into the evening"))
        // Fewer than five words before the comma: no cut.
        val t = SentenceSplitter()
        assertEquals(emptyList<String>(), t.feed("Well, I think "))
        assertEquals(emptyList<String>(), t.feed("3,5 "))
    }

    @Test fun emojiAreNotRead() {
        assertEquals("Great job! It's 20°C.", SpeechText.clean("Great job! 🎉👍🏽 It's 20°C. ✨"))
        assertEquals("Family", SpeechText.clean("Family 👨‍👩‍👧"))
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

    // ReplyVoice: what of a streaming reply is spoken (0.1.2 review, majors 2 and 3).

    private val longFirst = "Sure, here is what I found for you today. "

    @Test fun aFinishedReplySpeaksItsLastSentence() {
        val v = ReplyVoice()
        v.start("r1")
        assertEquals(listOf("Sure, here is what I found for you today."), v.feed("r1", longFirst, speak = true))
        assertEquals(emptyList<String>(), v.feed("r1", "It is sunny", speak = true))
        assertEquals("It is sunny", v.finish("r1", cancelled = false, speak = true))
        // Finished: nothing more for it.
        assertNull(v.finish("r1", cancelled = false, speak = true))
        assertEquals(emptyList<String>(), v.feed("r1", "late. ", speak = true))
    }

    @Test fun aCancelledReplyDropsItsCutOffSentence() {
        val v = ReplyVoice()
        v.start("r1")
        v.feed("r1", longFirst, speak = true)
        v.feed("r1", "The forecast for tomor", speak = true)
        assertNull(v.finish("r1", cancelled = true, speak = true))
        // The next reply starts clean, with a short first piece again.
        v.start("r2")
        assertEquals(listOf("Okay."), v.feed("r2", "Okay. ", speak = true))
    }

    @Test fun stopMutesTheRestOfTheStreamingReply() {
        val v = ReplyVoice()
        v.start("r1")
        assertEquals(1, v.feed("r1", longFirst, speak = true).size)
        v.mute()
        assertFalse(v.live("r1"))
        assertEquals(emptyList<String>(), v.feed("r1", "More words here. And more. ", speak = true))
        assertNull(v.say("r1", "Timer set.", speak = true))
        assertNull(v.finish("r1", cancelled = false, speak = true))
        // The next question speaks again.
        v.start("r2")
        assertTrue(v.live("r2"))
        assertEquals("Done.", v.say("r2", "Done.", speak = true))
        assertEquals(listOf("Okay."), v.feed("r2", "Okay. ", speak = true))
    }

    @Test fun onlyTheCurrentReplyAndOnlyWithSpeechOn() {
        val v = ReplyVoice()
        v.start("r2")
        // A late chunk or the end of an older reply says nothing, and does not disturb the current one.
        assertEquals(emptyList<String>(), v.feed("r1", "Old text. ", speak = true))
        assertNull(v.finish("r1", cancelled = false, speak = true))
        assertTrue(v.live("r2"))
        assertEquals(emptyList<String>(), v.feed("r2", "Quiet, please. ", speak = false))
        assertNull(v.say("r2", "Quiet.", speak = false))
        assertNull(v.finish("r2", cancelled = false, speak = false))
        // Muting with no reply under way only clears what was buffered.
        v.mute()
        v.start("r3")
        assertTrue(v.live("r3"))
    }
}
