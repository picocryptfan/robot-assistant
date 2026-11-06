// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2.4: car mode opened by hand (the home screen's Car mode button, its shortcut): no car leaving ends it. */
class CarByHandTest {
    private val memory = DeviceMemory().remember(SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34"), DeviceRole.CAR, 0)!!.first
    private val car = memory.byNumber(1)!!
    private val s = CarSettings()

    private fun run(vararg inputs: CarInput, start: CarState = CarState()): Pair<CarState, List<CarEffect>> {
        var st = start
        val all = mutableListOf<CarEffect>()
        for (i in inputs) { val (n, fx) = CarModeMachine.on(st, i, s); st = n; all += fx }
        return st to all
    }

    @Test fun byHandStartsForNoCarAndSaysSo() {
        val (st, fx) = run(CarInput.Start(0, byHand = true), CarInput.Started(10))
        assertEquals(CarPhase.ACTIVE, st.phase)
        assertNull(st.car)
        assertTrue(fx.contains(CarEffect.Logged(true, CarWhy.BUTTON)))
        assertTrue(fx.contains(CarEffect.ShowCarScreen))
    }

    @Test fun aCarThatComesAndGoesDoesntEndIt() {
        val (st, fx) = run(CarInput.Start(0, byHand = true), CarInput.Started(10), CarInput.CarSeen(car, 20), CarInput.CarGone(car, 30),
            CarInput.Tick(30 + s.endGraceMs + 1))
        assertEquals(CarPhase.ACTIVE, st.phase)
        assertNull(st.car)
        assertTrue(fx.none { it == CarEffect.StopService })
    }

    @Test fun aCarConnectedBeforeTheTapIsntTakenUp() {
        val (st, _) = run(CarInput.CarSeen(car, 0), CarInput.Start(5, byHand = true), CarInput.Started(10), CarInput.CarGone(car, 30),
            CarInput.Tick(30 + s.endGraceMs + 1), start = CarState().copy(declined = 1))
        assertEquals(CarPhase.ACTIVE, st.phase)
        assertNull(st.car)
    }

    @Test fun theNotificationsStartKeepsItByHand() {
        val (st, _) = run(CarInput.Start(0, byHand = true), CarInput.Start(5), CarInput.Started(10), CarInput.CarSeen(car, 20))
        assertEquals(CarWhy.BUTTON, st.startedBy)
        assertNull(st.car)
    }

    @Test fun youEndIt() {
        val (st, fx) = run(CarInput.Start(0, byHand = true), CarInput.Started(10), CarInput.End(20))
        assertEquals(CarPhase.OFF, st.phase)
        assertTrue(fx.contains(CarEffect.Logged(false, CarWhy.ENDED_BY_YOU)))
    }

    @Test fun byVoiceStillTakesUpTheCarThatConnects() {
        val (st, _) = run(CarInput.Start(0), CarInput.Started(10), CarInput.CarSeen(car, 20))
        assertEquals(1, st.car)
    }
}
