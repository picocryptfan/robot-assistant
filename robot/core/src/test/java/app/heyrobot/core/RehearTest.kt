package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The dropped-words check (docs/conversation-mode.md 2.2): too few words for the speech heard means decode again in halves. */
class RehearTest {
    @Test fun theWordsPerSecondThreshold() {
        // 3 s of speech or more, under 0.9 words a second.
        assertTrue(Rehear.needed(3_000, 2))
        assertFalse(Rehear.needed(3_000, 3))
        assertFalse(Rehear.needed(2_999, 0))
        // The research clip: 38 words said, 21 came back from about 13 s of speech.
        assertFalse(Rehear.needed(13_000, 21))
        assertTrue(Rehear.needed(13_000, 11))
        assertTrue(Rehear.needed(10_000, 8))
        assertFalse(Rehear.needed(10_000, 9))
    }

    @Test fun theSplitChoice() {
        val s = Segment(0, 80_000, 3_200, 76_800, 4_600, SegmentEnd.SILENCE, 40_960)
        assertEquals(40_960L, Rehear.splitAt(s))
        assertNull(Rehear.splitAt(s.copy(split = null)))
        // A split at or past an edge is no split.
        assertNull(Rehear.splitAt(s.copy(split = 0)))
        assertNull(Rehear.splitAt(s.copy(split = 80_000)))
    }

    @Test fun theBetterResultIsKept() {
        assertTrue(Rehear.keepHalves(21, 38))
        assertFalse(Rehear.keepHalves(21, 21))
        assertFalse(Rehear.keepHalves(21, 12))
        assertEquals(3, Rehear.words("Hello, there. 42 !"))
        assertEquals(0, Rehear.words("  ... "))
        assertEquals(4, Rehear.words("J'ai pogné un char"))
    }
}
