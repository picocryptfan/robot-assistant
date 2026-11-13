// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadOutputTest {
    private val piece = ReadPiece("Your note says the trip starts Friday.", 0, 38)
    private val buds = SeenDevice(RouteType.WIRED_HEADPHONES, "Buds", "")
    private val unlocked = PhoneState(locked = false)
    private val s = GuardSettings()

    @Test fun aReadingOnThePhoneItselfPlaysWhenUnlocked() {
        assertTrue(ReadOutput.mayPlay(piece, RoutineRoute(), unlocked, startedOnPhone = true, s))
    }

    @Test fun aReadingInHeadphonesNeverFallsBackToTheSpeaker() {
        assertTrue(ReadOutput.mayPlay(piece, RoutineRoute(buds, Trust.PRIVATE), unlocked, startedOnPhone = false, s))
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(), unlocked, startedOnPhone = false, s))
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(SeenDevice(RouteType.SPEAKER)), unlocked, startedOnPhone = false, s))
    }

    @Test fun everyPieceIsPersonal() {
        // Locked: held for the unlock. A headset not known as private: asked about, so not played.
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(), PhoneState(locked = true), startedOnPhone = true, s))
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(buds, Trust.ASK), unlocked, startedOnPhone = false, s))
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(buds, Trust.NOT_PRIVATE), unlocked, startedOnPhone = false, s))
        assertFalse(ReadOutput.mayPlay(piece, RoutineRoute(buds, Trust.PRIVATE), PhoneState(locked = false, callActive = true).copy(locked = true), startedOnPhone = false, s))
    }

    @Test fun aDuckingLossPausesLikeAShortOne() {
        assertEquals(ReadFocus.LOST, ReadOutput.focus(-1))
        assertEquals(ReadFocus.LOST_SHORT, ReadOutput.focus(-2))
        assertEquals(ReadFocus.LOST_SHORT, ReadOutput.focus(-3))
        assertEquals(ReadFocus.GAINED, ReadOutput.focus(1))
        assertEquals(ReadFocus.OTHER, ReadOutput.focus(2))
    }
}
