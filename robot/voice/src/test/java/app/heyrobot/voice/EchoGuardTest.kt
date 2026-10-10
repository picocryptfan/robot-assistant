package app.heyrobot.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoGuardTest {
    @Test fun wordsTheKeywordModelConfusesWithStop() {
        // On the build machine "STOP" fired on Robot's own "stop", "bus stop", "stopwatch" and "step by step".
        for (t in listOf("You can stop by the store.", "The bus stop is near.", "That stopwatch works.", "Step by step.", "STOPPED", "a steep hill")) {
            assertTrue(t, EchoGuard.soundsLikeStop(t))
        }
        for (t in listOf("The top story today.", "Turn left at the shop.", "Set a timer for ten minutes.", "It's the best plan.")) {
            assertFalse(t, EchoGuard.soundsLikeStop(t))
        }
    }

    @Test fun aStopIsIgnoredWhileARiskyPieceIsPlayingAndJustAfter() {
        val rate = 24_000
        val g = EchoGuard(rate)
        g.onPiece(0, rate * 2L, "Sure, here is the weather.")
        g.onPiece(rate * 2L, rate * 5L, "Please don't stop the music.")
        assertFalse(g.mayBeEcho(rate * 1L))
        // From 0.3 s before the risky piece to 1.5 s after it.
        assertTrue(g.mayBeEcho(rate * 2L - rate / 4))
        assertTrue(g.mayBeEcho(rate * 4L))
        assertTrue(g.mayBeEcho(rate * 6L + rate / 3))
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
}
