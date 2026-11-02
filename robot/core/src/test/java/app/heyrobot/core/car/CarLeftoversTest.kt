// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a drive leaves behind, and who car trust is for (the 1.1.2 audit's car findings). */
class CarLeftoversTest {
    private val memory = DeviceMemory().remember(SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34"), DeviceRole.CAR, 0)!!.first
    private val car = memory.byNumber(1)!!
    private val s = CarSettings()

    private fun active(settings: CarSettings = s): CarState {
        val a = CarModeMachine.on(CarState(), CarInput.CarSeen(car, 0), settings).first
        return CarModeMachine.on(a, CarInput.Started(1_000), settings).first
    }

    @Test fun endEmitsBothEvenWhenSettingsWereTurnedOffDuringTheDrive() {
        val on = s.copy(dndWhileDriving = true, androidCarMode = true)
        val fx = CarModeMachine.on(active(on), CarInput.End(2_000), s.copy(dndWhileDriving = false, androidCarMode = false)).second
        assertTrue(CarEffect.SetDnd(false) in fx)
        assertTrue(CarEffect.AndroidCarMode(false) in fx)
    }

    @Test fun aStartWithNothingRunningClearsWhatALostDriveLeft() {
        assertEquals(listOf(CarEffect.SetDnd(false), CarEffect.AndroidCarMode(false), CarEffect.ClearLockCar), CarModeMachine.leftovers(CarState()))
        assertEquals(emptyList<CarEffect>(), CarModeMachine.leftovers(active()))
    }

    @Test fun aForgottenCarGivesCarGoneOnceAndCarModeEnds() {
        val gone = CarModeMachine.forgotten(active(), DeviceMemory(), 5_000)
        assertEquals(listOf(1), gone.map { it.car.number })
        val (ending, fx) = CarModeMachine.on(active(), gone.single(), s)
        assertEquals(CarPhase.ENDING, ending.phase)
        assertFalse(ending.trustedAt(5_000))
        assertTrue(fx.single() is CarEffect.WakeAt)
        assertEquals(emptyList<CarInput.CarGone>(), CarModeMachine.forgotten(ending, DeviceMemory(), 6_000))
        assertEquals(CarPhase.OFF, CarModeMachine.on(ending, CarInput.Tick(ending.endsAt!!), s).first.phase)
    }

    @Test fun aRememberedCarIsNotForgotten() {
        assertEquals(emptyList<CarInput.CarGone>(), CarModeMachine.forgotten(active(), memory, 5_000))
    }

    @Test fun aTimerSleptThroughIsDue() {
        val ending = CarModeMachine.on(active(), CarInput.CarGone(car, 2_000), s).first
        assertFalse(CarModeMachine.due(ending, ending.endsAt!! - 1))
        assertTrue(CarModeMachine.due(ending, ending.endsAt!!))
        assertFalse(CarModeMachine.due(active(), 2_000))
        assertFalse(CarModeMachine.due(CarState(), Long.MAX_VALUE))
        val noCar = CarState(phase = CarPhase.ACTIVE, since = 0)
        assertFalse(CarModeMachine.due(noCar, CarModeMachine.MANUAL_MAX_MS - 1))
        assertTrue(CarModeMachine.due(noCar, CarModeMachine.MANUAL_MAX_MS))
        assertTrue(CarModeMachine.due(active().copy(trusted = TrustedSession(1, 0, 3_000)), 3_000))
    }

    @Test fun aLockForSomeoneElseTurnsCarTrustOff() {
        assertTrue(CarGate.trustedNow(session = true, someoneElseLock = false))
        assertFalse(CarGate.trustedNow(session = true, someoneElseLock = true))
        assertFalse(CarGate.trustedNow(session = false, someoneElseLock = false))
    }

    @Test fun aPhoneLockIsNotTheLockScreen() {
        assertEquals(CarOpen.Gate.OPEN, CarOpen.maps(keyguard = false, trusted = false, phoneLock = true, lockMaps = true))
        assertEquals(CarOpen.Gate.UNLOCK, CarOpen.maps(keyguard = true, trusted = false, phoneLock = true, lockMaps = true))
    }

    @Test fun yourCarKeepsMapsOpenAsTheCoverDoes() {
        assertTrue(CarOpen.lockMaps(everyLockHasMaps = false, carOn = true))
        assertTrue(CarOpen.lockMaps(everyLockHasMaps = true, carOn = false))
        assertFalse(CarOpen.lockMaps(everyLockHasMaps = false, carOn = false))
    }

    @Test fun aQuietTurnClosesWhateverTheScreenDid() {
        assertTrue(CarTurnEnd.closes(quietTurn = true, on = false, screenUp = false))
        assertTrue(CarTurnEnd.closes(quietTurn = true, on = true, screenUp = false))
        assertTrue(CarTurnEnd.closes(quietTurn = false, on = true, screenUp = true))
        assertFalse(CarTurnEnd.closes(quietTurn = false, on = true, screenUp = false))
    }

    @Test fun aPrivateLineStaysSecureUntilItHasGone() {
        assertTrue(CarRecents.secure(privateTurn = true, showsTurn = true, was = false))
        assertTrue(CarRecents.secure(privateTurn = false, showsTurn = true, was = true))
        assertFalse(CarRecents.secure(privateTurn = false, showsTurn = false, was = true))
        assertFalse(CarRecents.secure(privateTurn = false, showsTurn = true, was = false))
    }

    @Test fun onlyTheOngoingNoticeMeansCarModeIsOn() {
        assertTrue(CarNotice.running(CarNotice.CHANNEL, ongoing = true))
        assertFalse(CarNotice.running(CarNotice.CHANNEL, ongoing = false))
        assertFalse(CarNotice.running("other", ongoing = true))
    }

    /** review-fixes-check item 2: words cut at the cap are promised a box only where one stays. */
    @Test fun wordsCutAtTheCapWaitOnlyWhereTheBoxStays() {
        // A turn with no window never has one.
        assertFalse(CarTurnEnd.boxStays(quietTurn = true, on = true, screenUp = true, session = true))
        // The panel opened because Android refused a windowless show: the session's end closes it.
        assertFalse(CarTurnEnd.boxStays(quietTurn = false, on = true, screenUp = true, session = true))
        // With no session nothing closes it, and with no car screen up nothing does either.
        assertTrue(CarTurnEnd.boxStays(quietTurn = false, on = true, screenUp = true, session = false))
        assertTrue(CarTurnEnd.boxStays(quietTurn = false, on = true, screenUp = false, session = true))
        assertTrue(CarTurnEnd.boxStays(quietTurn = false, on = false, screenUp = false, session = true))
        // Wherever the session's end closes the panel, there's no box to promise.
        for (q in listOf(true, false)) for (on in listOf(true, false)) for (up in listOf(true, false))
            if (CarTurnEnd.closes(q, on, up)) assertFalse(CarTurnEnd.boxStays(q, on, up, session = true))
    }
}
