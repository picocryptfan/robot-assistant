// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import app.heyrobot.core.feedback.ReportTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-bugcard-drop (Pixel 8 bc7): a bug card left open forgets the turn once its 10 minutes pass, sent or not, and a card
// that comes back after the phone slept (its timer's clock stopped) finds it stale at once.
class CardTurnHeldTest {
    private val at = 1_000_000L
    private val turn = ReportTurn("what is two plus two", "2 + 2 = 4", at = at)
    private val json = turn.toJson()
    private val gone = at + ReportTurn.FRESH_MS + 1

    @Test fun anOpenCardShowsTheTurnWhileFresh() {
        val h = CardTurnHeld()
        assertEquals(turn.said, h.show(json, at + 60_000)?.said)
        assertFalse(h.stale(json, at + ReportTurn.FRESH_MS))
    }

    @Test fun afterASendTheCardKeepsShowingTheTurnOnlyWhileFresh() {
        val h = CardTurnHeld()
        h.show(json, at + 60_000)
        assertEquals(turn.said, h.show(null, at + 120_000)?.said)
        assertNull(h.show(null, gone))
    }

    @Test fun aCardLeftOpenPastTheTenMinutesForgetsTheTurn() {
        val h = CardTurnHeld()
        h.show(json, at + 60_000)
        // 11 minutes on, the screen back on: the timer may not have run, the card's check finds it stale.
        val back = at + 11 * 60_000L
        assertTrue(h.stale(json, back))
        assertNull(h.show(json, back))
        assertNull(h.show(null, back))
    }

    @Test fun theHeldTurnAloneGoingStaleIsSeenAfterASend() {
        val h = CardTurnHeld()
        h.show(json, at + 60_000)
        h.show(null, at + 120_000)
        assertTrue(h.stale(null, gone))
        h.forget()
        assertNull(h.show(null, at + 120_000))
        assertFalse(h.stale(null, gone))
    }
}
