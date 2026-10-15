// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0 audit (native-03): a :llama reply can't stream more than its budget into :brain; past it the request ends. */
class StreamBudgetTest {
    @Test fun aFloodOfBigPiecesStopsAtTheTotal() {
        val b = StreamBudget(maxPiece = 64 * 1024, maxTotal = 256L * 1024)
        // 700 pieces of 64 KiB (about 45 MB) used to be accepted: now the fifth ends it.
        var taken = 0
        for (i in 0 until 700) if (b.take(64 * 1024)) taken++
        assertTrue(taken == 4)
        assertTrue(b.over)
        // Once over, even a small piece is refused.
        assertFalse(b.take(3))
    }

    @Test fun anOrdinaryReplyFitsAndAnOversizedPieceEndsIt() {
        val b = StreamBudget(64 * 1024, 256L * 1024)
        repeat(5_000) { assertTrue(b.take(5)) }
        assertFalse(b.over)
        val c = StreamBudget(64 * 1024, 256L * 1024)
        assertFalse(c.take(64 * 1024 + 1))
        assertTrue(c.over)
        assertFalse(StreamBudget(10, 100).take(-1))
    }
}
