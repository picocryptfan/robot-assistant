// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Test

/** review-develop-14 M1: car mode keeps App pause out of the way whatever started it; only the car's own counts for the lock. */
class CarModePauseTest {
    @Test fun carModeByHandCountsForThePauseOnly() {
        val st = LockState().withCar(on = true, byCar = false, now = 1_000)
        assertTrue(st.carModeOn(2_000))
        assertFalse(st.carOn(2_000))
        assertFalse(st.withCar(on = false, byCar = false, now = 3_000).carModeOn(3_000))
    }

    @Test fun carModeByTheCarCountsForBoth() {
        val st = LockState().withCar(on = true, byCar = true, now = 1_000)
        assertTrue(st.carModeOn(2_000) && st.carOn(2_000))
        assertFalse(st.carModeOn(1_000 + LockState.CAR_MAX_MS))
        val off = st.withCar(on = false, byCar = false, now = 3_000)
        assertFalse(off.carModeOn(3_000) || off.carOn(3_000))
    }

    @Test fun aSecondStartKeepsTheFirstTime() {
        val st = LockState().withCar(on = true, byCar = false, now = 1_000).withCar(on = true, byCar = true, now = 5_000)
        assertEquals(1_000L, st.carMode)
        assertEquals(5_000L, st.car)
    }

    @Test fun theStateKeepsItAcrossARestart() {
        val st = LockState().withCar(on = true, byCar = false, now = 1_000)
        assertEquals(1_000L, LockCodec.decodeState(LockCodec.encodeState(st))!!.carMode)
        // Off, nothing is written: a state without it reads as before.
        assertFalse(LockCodec.encodeState(LockState()).contains("carMode"))
        assertEquals(0L, LockCodec.decodeState(LockCodec.encodeState(LockState()))!!.carMode)
    }
    @Test fun aRebootEndsCarModeFromBeforeIt() {
        // review-develop-14-b L1: Robot killed mid-drive, or a reboot, kept App pause away for up to 12 hours.
        val st = LockState().withCar(on = true, byCar = true, now = 1_000)
        val after = st.afterReboot(bootAt = 2_000)
        assertFalse(after.carModeOn(3_000))
        assertFalse(after.carOn(3_000))
        assertEquals(0L, after.car)
        assertEquals(0L, after.carMode)
    }

    @Test fun aRebootKeepsCarModeStartedSinceIt() {
        // The car connected and car mode started before BOOT_COMPLETED reached :lock: it stays.
        val st = LockState().withCar(on = true, byCar = true, now = 5_000)
        val after = st.afterReboot(bootAt = 2_000)
        assertTrue(after.carModeOn(6_000))
        assertTrue(after.carOn(6_000))
        assertSame(st, st.afterReboot(bootAt = 2_000))
    }
}
