// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.headset.EarbudButtons.Action
import app.heyrobot.core.headset.EarbudButtons.End
import app.heyrobot.core.headset.EarbudButtons.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EarbudButtonsTest {
    private val held = EarbudButtons.speaking(State(), true, 1_000, enabled = true).state
    private fun tap(s: State, at: Long, code: Int = MediaKey.PLAY_PAUSE.keyCode) = EarbudButtons.key(s, code, true, false, at)

    @Test fun takenOnlyWhileSpeakingAndOnlyWhenOn() {
        val on = EarbudButtons.speaking(State(), true, 1_000, enabled = true)
        assertTrue(on.take && on.state.held)
        val off = EarbudButtons.speaking(State(), true, 1_000, enabled = false)
        assertFalse(off.take || off.state.held)
        // Turned off while she holds them: given back.
        assertTrue(EarbudButtons.speaking(held, true, 1_200, enabled = false).giveBack)
        // Quiet and not held: nothing to do, no check.
        assertNull(EarbudButtons.speaking(State(), false, 1_000, true).checkAt)
        // Taken once: a second start keeps the same session.
        assertFalse(EarbudButtons.speaking(held, true, 1_100, true).take)
    }

    @Test fun finishedGivesThemBackOnceQuiet() {
        val q = EarbudButtons.speaking(held, false, 2_000, true)
        assertFalse(q.giveBack)
        assertEquals(2_000 + EarbudButtons.QUIET_MS, q.checkAt)
        // The gap between two parts of a brief: she speaks again before the check, so they stay.
        val again = EarbudButtons.speaking(q.state, true, 2_350, true)
        assertTrue(again.state.held && !again.take && again.state.quietSince == null)
        val done = EarbudButtons.tick(q.state, 2_000 + EarbudButtons.QUIET_MS, speakingNow = false)
        assertTrue(done.giveBack && !done.state.held)
    }

    @Test fun stoppedErrorAndKilledGiveThemBackAtOnce() {
        for (why in End.entries) {
            val r = EarbudButtons.ended(held, why)
            assertTrue(why.name, r.giveBack && r.state == State())
            assertFalse(why.name, EarbudButtons.ended(State(), why).giveBack)
        }
    }

    @Test fun aMissedSignalCantKeepThem() {
        // The voice went quiet without telling: the check sees it and gives them back one quiet window later.
        val t1 = EarbudButtons.tick(held, 1_600, speakingNow = false)
        assertFalse(t1.giveBack)
        assertTrue(EarbudButtons.tick(t1.state, 1_600 + EarbudButtons.QUIET_MS, speakingNow = false).giveBack)
        // Still speaking: kept, and checked again.
        val t2 = EarbudButtons.tick(held, 1_600, speakingNow = true)
        assertTrue(t2.state.held && t2.checkAt != null && !t2.giveBack)
    }

    @Test fun oneTapStopsAfterTheDoubleTapWindow() {
        val t = tap(held, 5_000)
        assertNull(t.action)
        assertEquals(5_000 + EarbudButtons.DOUBLE_TAP_MS, t.checkAt)
        val stop = EarbudButtons.tick(t.state, 5_000 + EarbudButtons.DOUBLE_TAP_MS, speakingNow = true)
        assertEquals(Action.STOP, stop.action)
        assertTrue(stop.giveBack && !stop.state.held)
        // The hook of a wired headset counts the same.
        assertEquals(Action.STOP, EarbudButtons.tick(tap(held, 5_000, MediaKey.HEADSETHOOK.keyCode).state, 5_400, true).action)
    }

    @Test fun twoTapsSkipAndKeepTheButtons() {
        val s = tap(tap(held, 5_000).state, 5_300)
        assertEquals(Action.SKIP, s.action)
        assertTrue(s.state.held && s.state.tapAt == null && !s.giveBack)
        // Too far apart: two single taps; the first one stopped her already.
        assertNull(tap(tap(held, 5_000).state, 5_000 + EarbudButtons.DOUBLE_TAP_MS + 1).action)
    }

    @Test fun earbudsWithTheirOwnKeys() {
        // The earbud's own double tap, sent as "next".
        assertEquals(Action.SKIP, tap(held, 5_000, MediaKey.NEXT.keyCode).action)
        // Pause and stop keys stop at once.
        for (k in listOf(MediaKey.PAUSE.keyCode, 86)) {
            val r = tap(held, 5_000, k)
            assertEquals(Action.STOP, r.action)
            assertTrue(r.giveBack)
        }
        // Previous does nothing (it isn't asked for).
        assertNull(tap(held, 5_000, MediaKey.PREVIOUS.keyCode).action)
    }

    @Test fun longPressesKeyUpsAndKeysWhenNotHeldDoNothing() {
        assertEquals(held, EarbudButtons.key(held, MediaKey.PLAY_PAUSE.keyCode, true, true, 5_000).state)
        assertEquals(held, EarbudButtons.key(held, MediaKey.PLAY_PAUSE.keyCode, false, false, 5_000).state)
        val r = tap(State(), 5_000, MediaKey.NEXT.keyCode)
        assertNull(r.action)
        assertFalse(r.take)
    }
}
