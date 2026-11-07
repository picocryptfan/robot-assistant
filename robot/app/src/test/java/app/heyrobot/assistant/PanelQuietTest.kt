// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.output.Dnd
import app.heyrobot.core.output.Ringer
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.Trust
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The panel's quiet answers on a silenced phone (review-quiet-when-silent, findings 1 and 2). */
class PanelQuietTest {
    private val state = MutableStateFlow(PanelState(convId = "t", speak = true))
    private var notices = 0
    private val quiet = PanelQuiet(state) { notices++ }
    private val silent = PanelQuiet.Look(Ringer.SILENT, Dnd.OFF)
    private val normal = PanelQuiet.Look(Ringer.NORMAL, Dnd.OFF)

    private fun typed(look: PanelQuiet.Look, always: Boolean = false) { quiet.turn(fromVoice = false, always = always) { look }; quiet.tell() }

    @Test fun silentQuietsWithOneNotice() {
        typed(silent)
        assertFalse(state.value.speak)
        typed(silent)
        assertFalse(state.value.speak)
        assertEquals(1, notices)
    }

    /** Finding 1: vibrate isn't silent, and "Always" is your choice. */
    @Test fun vibrateAndAlwaysKeepTheVoice() {
        typed(PanelQuiet.Look(Ringer.VIBRATE, Dnd.OFF))
        assertTrue(state.value.speak)
        typed(PanelQuiet.Look(Ringer.SILENT, Dnd.NONE), always = true)
        assertTrue(state.value.speak)
        assertEquals(0, notices)
    }

    /** Finding 2: the next typed question checks again, so the voice comes back with the ringer or headphones. */
    @Test fun theVoiceComesBackWithTheRingerOrHeadphones() {
        typed(silent)
        assertFalse(state.value.speak)
        typed(normal)
        assertTrue(state.value.speak)
        typed(silent)
        assertFalse(state.value.speak)
        typed(PanelQuiet.Look(Ringer.SILENT, Dnd.OFF, RouteType.BT_A2DP, Trust.ASK))
        assertTrue(state.value.speak)
        assertEquals(1, notices)
    }

    /** Finding 8: a quieted turn that showed nothing keeps its notice for the next quiet answer, never a spoken one. */
    @Test fun aNoticeNeverComesWithASpokenAnswer() {
        quiet.turn(fromVoice = false, always = false) { silent }
        typed(normal)
        assertEquals(0, notices)
        typed(silent)
        assertEquals(1, notices)
    }

    /** Your own "off" stays off: a turn that isn't quieted doesn't turn the voice on. */
    @Test fun yourOffStaysOff() {
        quiet.tapped(false)
        state.value = state.value.copy(speak = false)
        typed(normal)
        assertFalse(state.value.speak)
    }

    /** Finding 6: silenced mid-answer after a typed question: the voice stops, the notice once; never after a spoken one, a tap or "Always". */
    @Test fun aRingerChangeMidAnswerQuietsTypedOnly() {
        typed(normal)
        assertTrue(state.value.speak)
        assertTrue(quiet.changed(always = false) { silent })
        assertFalse(state.value.speak)
        assertEquals(1, notices)
        assertFalse(quiet.changed(always = false) { silent })
        quiet.turn(fromVoice = true, always = false) { normal }
        assertTrue(state.value.speak)
        assertFalse(quiet.changed(always = false) { silent })
        assertTrue(state.value.speak)
        typed(normal)
        assertFalse(quiet.changed(always = true) { silent })
        quiet.tapped(true)
        assertFalse(quiet.changed(always = false) { silent })
        assertTrue(state.value.speak)
        assertEquals(1, notices)
    }
}
