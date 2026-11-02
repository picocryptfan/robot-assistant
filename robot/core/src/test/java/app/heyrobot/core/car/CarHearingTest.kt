// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.ListenPause
import app.heyrobot.core.WakeSensitivity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CarHearingTest {
    @Test fun inTheCarThePauseIsAtLeastLong() {
        val car = CarSettings()
        assertEquals(ListenPause.LONG, car.waitPause)
        assertEquals(1_600, CarHearing.pauseMs(ListenPause.SHORT, true, car))
        assertEquals(1_600, CarHearing.pauseMs(ListenPause.NORMAL, true, car))
        assertEquals(2_500, CarHearing.pauseMs(ListenPause.VERY_LONG, true, car))
        assertEquals(2_500, CarHearing.pauseMs(ListenPause.SHORT, true, car.copy(waitPause = ListenPause.VERY_LONG)))
    }

    @Test fun outsideTheCarYourPauseIsUntouched() {
        val car = CarSettings(waitPause = ListenPause.VERY_LONG)
        for (p in ListenPause.entries) assertEquals(p.ms, CarHearing.pauseMs(p, false, car))
    }

    @Test fun moreLowersTheDetectorsAndRaisesTheWakePhrase() {
        val car = CarSettings()
        assertEquals(CarHearMe.MORE, car.hearMe)
        assertTrue(CarHearing.more(true, car))
        assertFalse(CarHearing.more(false, car))
        assertFalse(CarHearing.more(true, car.copy(hearMe = CarHearMe.NORMAL)))
        assertTrue(CarHearing.speechThreshold(true) < CarHearing.speechThreshold(false))
        assertEquals(0.5f, CarHearing.speechThreshold(false))
        assertEquals(WakeSensitivity.HIGH, CarHearing.sensitivity(WakeSensitivity.MEDIUM, true))
        assertEquals(WakeSensitivity.HIGH, CarHearing.sensitivity(WakeSensitivity.HIGH, true))
        assertEquals(WakeSensitivity.LOW, CarHearing.sensitivity(WakeSensitivity.LOW, true))
        assertEquals(WakeSensitivity.MEDIUM, CarHearing.sensitivity(WakeSensitivity.MEDIUM, false))
    }

    @Test fun bothRowsRoundTripAndAnAbsentOneIsItsDefault() {
        val s = CarSettings(hearMe = CarHearMe.NORMAL, waitPause = ListenPause.VERY_LONG)
        assertEquals(s, CarSettings.fromJson(JSONObject(s.toJson().toString())))
        assertEquals(CarSettings(), CarSettings.fromJson(JSONObject("{}")))
    }

    @Test fun aCarPauseBelowLongInAFileReadsAsLong() {
        assertEquals(ListenPause.LONG, CarSettings.fromJson(JSONObject("""{"waitPause":"short"}""")).waitPause)
        assertEquals(ListenPause.LONG, CarSettings.fromJson(JSONObject("""{"waitPause":"forever"}""")).waitPause)
    }
}
