// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmClocksTest {
    private val google = "com.google.android.deskclock"
    private val other = "org.example.clock"

    @Test fun theChosenClockThenThePhonesDefaultThenGrapheneOsClock() {
        val all = listOf(other, AlarmClocks.GRAPHENE, google)
        assertEquals(other, AlarmClocks.effective(other, all, google))
        assertEquals(google, AlarmClocks.effective(null, all, google))
        // Several and no default: Android answers with its chooser ("android"), which isn't a Clock.
        assertEquals(AlarmClocks.GRAPHENE, AlarmClocks.effective(null, all, "android"))
        assertEquals(other, AlarmClocks.effective(null, listOf(other, google), "android"))
        // Chosen, then uninstalled (or restored from another phone): the phone's default, never nothing.
        assertEquals(google, AlarmClocks.effective("gone.app", all, google))
        assertNull(AlarmClocks.effective(other, emptyList(), null))
    }

    @Test fun onlyGrapheneOsClockTellsAndItsPathIsTheDefault() {
        assertTrue(AlarmClocks.tells(AlarmClocks.GRAPHENE))
        assertFalse(AlarmClocks.tells(google))
        assertFalse(AlarmClocks.tells(null))
        assertEquals("com.android.deskclock", AlarmClocks.watched(null))
        assertEquals(google, AlarmClocks.watched(google))
    }

    @Test fun theSoundsEdgesOnly() {
        val e = AlarmSoundEdge(false)
        assertEquals(listOf(null, true, null, false, null), listOf(false, true, true, false, false).map(e::see))
        assertNull(AlarmSoundEdge(true).see(true))
        assertEquals(false, AlarmSoundEdge(true).see(false))
    }

    // The sound's start and stop go through the checks a broadcast does: another Clock's alarm ends as dismissed, and a sound
    // with none of the watched Clock's alarms due (a timer, another app) counts for nothing.
    @Test fun aSoundEndsAVouchedAlarmOnly() {
        val at = 1_000_000L
        val seen = AlarmCheck.remember(emptyList(), NextAlarm(at, fromClock = true), at - 60_000)
        val start = AlarmSignal.Alert(at + 500)
        assertTrue(AlarmCheck.admits(seen, start))
        val rang = AlarmWatch.on(AlarmWatchState(), start)
        val end = AlarmWatch.on(rang.state, AlarmSignal.Done(at + 60_000, null))
        assertEquals(AlarmEnd.DISMISSED, end.end)
        assertFalse(AlarmCheck.admits(emptyList(), start))
        assertFalse(AlarmCheck.admits(AlarmCheck.remember(emptyList(), NextAlarm(at, fromClock = false), at - 60_000), start))
        assertFalse(AlarmCheck.admits(seen, AlarmSignal.Alert(at + 6 * 3_600_000)))
    }

    @Test fun theSettingRoundTripsAndStartsEmpty() {
        val s = RoutineSettings(clockApp = google)
        assertEquals(google, RoutineSettingsCodec.decode(RoutineSettingsCodec.encode(s)).clockApp)
        assertNull(RoutineSettingsCodec.decode(RoutineSettingsCodec.encode(RoutineSettings())).clockApp)
        assertNull(RoutineSettings().clockApp)
        // Not a package name (a hand-edited or restored file): the phone's default.
        for (bad in listOf("nodots", "a b.c", "../x.y", "x".repeat(300) + ".y"))
            assertNull(RoutineSettingsCodec.decode(RoutineSettingsCodec.encode(RoutineSettings(clockApp = bad))).clockApp)
    }
}
