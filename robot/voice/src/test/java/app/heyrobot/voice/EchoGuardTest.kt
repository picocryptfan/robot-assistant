// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    @Test fun wordsTheKeywordModelConfusesWithStop() {
        // On the build machine "STOP" fired on Robot's own "stop", "bus stop", "stopwatch" and "step by step". "Step" has
        // another vowel, so words with the same s-t-vowel-p sound count too until they are measured (tricky-words run).
        for (t in listOf("You can stop by the store.", "The bus stop is near.", "That stopwatch works.", "Step by step.", "STOPPED", "Keep stopping.",
            "a steep hill", "Buy a stamp.", "Stomp on it.", "An old stump.", "Strip the bed.", "Don't stoop.", "That was stupid.",
            // 0.3.1: "stop" and "step" inside a word count too.
            "It runs nonstop.", "Leave it on the doorstep.", "Two steps back.", "That's unstoppable.", "Mind the footsteps.")) {
            assertTrue(t, EchoGuard.soundsLikeStop(t))
        }
        for (t in listOf("The top story today.", "Turn left at the shop.", "Set a timer for ten minutes.", "It's the best plan.",
            "Ask Stephen.", "Christopher called.", "Start now.", "Mostly fine.")) {
            assertFalse(t, EchoGuard.soundsLikeStop(t))
        }
    }

    @Test fun theLogCanSayRobotSaidSuchAWordLately() {
        // STOP_HEARD notes a word like "stop" up to 3 s back, beyond the guard's window: did she stop herself?
        val g = EchoGuard(1_000)
        g.open(0, stop = true, wake = false)
        g.close(2_000)
        assertFalse(g.mayBeEcho(4_000))
        assertTrue(g.stopLikeWithin(4_000, EchoGuard.NOTE_MS))
        assertFalse(g.stopLikeWithin(5_100, EchoGuard.NOTE_MS))
    }

    @Test fun aStopIsIgnoredWhileARiskyPieceIsPlayingAndJustAfter() {
        val rate = 24_000
        val g = EchoGuard(rate)
        assertEquals(1_200, EchoGuard.AFTER_MS)
        g.onPiece(0, rate * 2L, "Sure, here is the weather.")
        g.onPiece(rate * 2L, rate * 5L, "Please don't stop the music.")
        assertFalse(g.mayBeEcho(rate * 1L))
        // From 0.3 s before the risky piece to 1.2 s after it.
        assertTrue(g.mayBeEcho(rate * 2L - rate / 4))
        assertTrue(g.mayBeEcho(rate * 4L))
        assertTrue(g.mayBeEcho(rate * 6L + rate / 10))
        assertFalse(g.mayBeEcho(rate * 6L + rate / 3))
        assertFalse(g.mayBeEcho(rate * 7L))
        g.reset()
        assertFalse(g.mayBeEcho(rate * 4L))
    }

    @Test fun aPieceStillBeingSynthesisedIsGuardedFromItsStart() {
        // "Don't stop. Here's more.": the first sentence plays while the second is still being made.
        val rate = 24_000
        val g = EchoGuard(rate)
        g.open(rate * 3L, "Don't stop. Here's more.")
        assertTrue(g.mayBeEcho(rate * 4L))
        assertTrue(g.mayBeEcho(rate * 60L))
        g.close(rate * 6L)
        assertFalse(g.mayBeEcho(rate * 60L))
        assertTrue(g.mayBeEcho(rate * 7L))
        // A harmless piece opened after it leaves it closed.
        g.open(rate * 6L, "All done.")
        g.close(rate * 8L)
        assertFalse(g.mayBeEcho(rate * 9L))
    }

    /**
     * The phone's voice: positions are ms of elapsedRealtime, from the engine's start and done callbacks, sentence by
     * sentence. One "step" in a reply used to turn "stop" off until the reply ended; now only around its sentence.
     */
    @Test fun thePhonesVoiceIsGuardedSentenceBySentence() {
        val g = EchoGuard(1_000)
        g.wakeWords = EchoGuard.wakeRegex(listOf("robot"))
        // Three sentences, the second says "step" and the third "Robot".
        g.open(10_000, stop = false, wake = false)
        g.close(13_000)
        g.open(13_100, stop = true, wake = false)
        g.close(16_000)
        g.open(16_100, stop = false, wake = true)
        assertFalse(g.mayBeEcho(12_000))
        assertTrue(g.mayBeEcho(14_000))
        assertTrue(g.mayBeEcho(17_000))
        assertFalse(g.mayBeEcho(17_300))
        // The third is still playing: a wake is guarded, "stop" is heard.
        assertTrue(g.mayBeWakeEcho(25_000))
        assertFalse(g.mayBeEcho(25_000))
        g.close(26_000)
        assertTrue(g.mayBeWakeEcho(27_000))
        assertFalse(g.mayBeWakeEcho(27_300))
    }

    @Test fun aPieceWhoseEndNeverCameEndsWhereTheNextStarts() {
        // A lost done callback must not leave "stop" off for the rest of the reply.
        val g = EchoGuard(1_000)
        g.open(0, stop = true, wake = false)
        g.open(5_000, stop = false, wake = false)
        assertTrue(g.mayBeEcho(6_000))
        assertFalse(g.mayBeEcho(6_300))
        g.close(9_000)
        assertFalse(g.mayBeEcho(9_500))
    }

    /**
     * 0.4.6: a long piece (Kokoro and Kitten know where it ends) sets "stop" aside only around its word like "stop", not
     * all through it: in a long story most sentences that say "steep" or "stepped" are long ones.
     */
    @Test fun aLongPieceIsGuardedOnlyAroundItsWordLikeStop() {
        val rate = 24_000
        val g = EchoGuard(rate)
        val text = "Every morning, Ignis would step out of his cave, stretch his enormous wings and fly over the valley until sunset."
        // "step" is about a quarter of the way in.
        val at = text.indexOf("step") / text.length.toFloat()
        assertTrue(at in 0.2f..0.3f)
        assertEquals(2, EchoGuard.stopSpans(text).size)
        // Ten seconds, from 2 s to 12 s of the output.
        g.open(rate * 2L, text)
        // Still being made: all of it, as before.
        assertTrue(g.mayBeEcho(rate * 11L))
        g.close(rate * 12L)
        // Around the word: ten seconds × a quarter = about 4.4 s in, give or take 2 s (a fifth of the piece), and 1.2 s after.
        assertTrue(g.mayBeEcho(rate * 4L))
        assertTrue(g.mayBeEcho(rate * 5L))
        assertTrue(g.mayBeEcho(rate * 7L))
        // Further on in the same sentence, "stop" is heard.
        assertFalse(g.mayBeEcho(rate * 9L))
        assertFalse(g.mayBeEcho(rate * 11L))
        assertFalse(g.mayBeEcho(rate * 13L))
        // Never wider than the whole piece was: before it starts nothing is set aside.
        assertFalse(g.mayBeEcho(rate * 1L))
        // The voice log's "did she say such a word lately" still counts the whole piece.
        assertTrue(g.stopLikeWithin(rate * 11L, EchoGuard.NOTE_MS))
    }

    @Test fun aShortPieceOrThePhonesVoiceIsGuardedAllThrough() {
        val rate = 24_000
        val g = EchoGuard(rate)
        // Three seconds: shorter than NARROW_FROM_MS, so all of it.
        g.onPiece(0, rate * 3L, "Step one, then rest for a while.")
        assertTrue(g.mayBeEcho(rate * 3L))
        g.reset()
        // The phone's voice: where the word is isn't known, however long the sentence.
        val p = EchoGuard(1_000)
        p.open(0, stop = true, wake = false)
        p.close(10_000)
        assertTrue(p.mayBeEcho(9_000))
        // Two words like "stop" far apart in one long piece: around each, not between.
        val text = "Stop and look around the old house, then walk slowly down the long and winding road to the doorstep."
        g.onPiece(0, rate * 12L, text)
        assertTrue(g.mayBeEcho(rate * 1L))
        assertFalse(g.mayBeEcho(rate * 6L))
        assertTrue(g.mayBeEcho(rate * 11L))
        assertEquals(0, EchoGuard.stopSpans("Nothing like it here.").size)
    }
}
