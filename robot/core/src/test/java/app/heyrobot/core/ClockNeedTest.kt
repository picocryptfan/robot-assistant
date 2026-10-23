// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.RoutineStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** 1.2 (every-android-phone, Step 6): a start after an alarm on a phone without GrapheneOS Clock is said, never silent. */
class ClockNeedTest {
    private val alarm = RoutineTrigger.AlarmDismissed()
    private val others = listOf(RoutineTrigger.At(LocalTime.of(7, 0), RoutineWords.WEEKDAYS), RoutineTrigger.Unlock(), RoutineTrigger.Charging(),
        RoutineTrigger.Asked)

    private fun routine(t: RoutineTrigger, on: Boolean = true) = Routine("r", "R", t, listOf(RoutineStep.Brief), enabled = on, created = 1)

    @Test fun onlyAnAlarmStartWithoutClock() {
        assertTrue(ClockNeed.unmet(alarm, clock = false))
        assertFalse(ClockNeed.unmet(alarm, clock = true))
        for (t in others) for (clock in listOf(true, false)) assertFalse("$t $clock", ClockNeed.unmet(t, clock))
    }

    @Test fun inUseOnlyWhenARoutineOrTheBriefThatIsOnWaitsForIt() {
        val briefOff = RoutineSettings(briefEnabled = false)
        // The brief starts after the alarm by default: on, it waits for Clock.
        assertTrue(ClockNeed.unmet(emptyList(), RoutineSettings(briefEnabled = true), clock = false))
        assertFalse(ClockNeed.unmet(emptyList(), RoutineSettings(briefEnabled = true), clock = true))
        assertFalse(ClockNeed.unmet(emptyList(), RoutineSettings(briefEnabled = true, briefTrigger = RoutineTrigger.Unlock()), clock = false))
        assertFalse(ClockNeed.unmet(emptyList(), briefOff, clock = false))
        assertTrue(ClockNeed.unmet(listOf(routine(alarm)), briefOff, clock = false))
        assertFalse(ClockNeed.unmet(listOf(routine(alarm, on = false)), briefOff, clock = false))
        assertFalse(ClockNeed.unmet(others.map { routine(it) }, briefOff, clock = false))
        assertFalse(ClockNeed.unmet(listOf(routine(alarm)), RoutineSettings(briefEnabled = true), clock = true))
    }

    @Test fun theVoiceCardsNoteNamesTheClockInBothLanguages() {
        assertEquals("This phone doesn't have the GrapheneOS Clock app, so turning off an alarm won't start this routine. Support for other clock " +
            "apps is planned.", RoutineStrings.clockMissing().en())
        // FrTypo's apostrophes and no-break space.
        assertEquals("Ce téléphone n\u2019a pas l\u2019appli Horloge de GrapheneOS\u00a0: arrêter une alarme ne lancera donc pas cette routine. La " +
            "prise en charge d\u2019autres applis d\u2019horloge est prévue.", RoutineStrings.clockMissing().render(Langs.of(Lang.FR)))
    }
}
