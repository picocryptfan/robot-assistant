// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot

import app.heyrobot.ui.link.PairClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F118: a copied pairing code leaves the clipboard when it's spent, and nothing else does. */
class PairClipTest {
    @Test fun nothingCopiedNothingToClear() = assertFalse(PairClip.spent(null, null, closed = true))

    @Test fun aLiveCodeOnScreenStays() = assertFalse(PairClip.spent("A", "A", closed = false))

    @Test fun usedOrCancelledGoes() = assertTrue(PairClip.spent("A", null, closed = false))

    @Test fun expiredGoes() = assertTrue(PairClip.spent("A", "A", closed = true))

    @Test fun onlyTheCodeItselfIsCleared() {
        assertTrue(PairClip.holds("A", "A"))
        assertFalse(PairClip.holds("something you copied", "A"))
        assertFalse(PairClip.holds(null, "A"))
    }

    @Test fun theScreenClearsOnLeavingAndOnASpentCode() {
        val s = File("src/main/java/app/heyrobot/ui/link/PairScreen.kt").readText()
        assertEquals(true, s.contains("onDispose { copied?.let { clearCopied(app, it) } }"))
        assertEquals(true, s.contains("copySensitive(ctx, c.text); copied = c.text"))
        assertEquals(true, s.contains("codeClosed = state == CodeState.CLOSED"))
    }
}
