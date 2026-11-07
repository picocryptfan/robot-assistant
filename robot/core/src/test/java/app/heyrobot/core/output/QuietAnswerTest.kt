// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.RoutineOutput
import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.TriggerKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietAnswerTest {
    private fun q(asked: AskedBy = AskedBy.TYPED, ringer: Ringer = Ringer.NORMAL, dnd: Dnd = Dnd.OFF,
        routed: RouteType? = RouteType.SPEAKER, trust: Trust = Trust.ASK, car: Boolean = false, tapped: Boolean = false,
        always: Boolean = false) =
        QuietAnswer.quiet(asked, ringer, dnd, routed, trust, car, tapped, always)

    @Test fun ringerModes() {
        assertEquals(Ringer.SILENT, Ringer.of(0))
        assertEquals(Ringer.VIBRATE, Ringer.of(1))
        assertEquals(Ringer.NORMAL, Ringer.of(2))
        assertEquals(Ringer.NORMAL, Ringer.of(-1))
    }

    @Test fun typedOnSilentIsQuietNotOnVibrate() {
        assertTrue(q(ringer = Ringer.SILENT))
        // review-quiet-when-silent, 1: a phone kept on vibrate all day keeps its voice.
        assertFalse(q(ringer = Ringer.VIBRATE))
        assertTrue(q(ringer = Ringer.VIBRATE, dnd = Dnd.PRIORITY))
        assertFalse(q(ringer = Ringer.NORMAL))
        assertTrue(q(ringer = Ringer.SILENT, routed = null))
    }

    @Test fun doNotDisturbCountsAsSilent() {
        for (d in listOf(Dnd.PRIORITY, Dnd.ALARMS, Dnd.NONE)) assertTrue(d.name, q(dnd = d))
    }

    @Test fun spokenKeepsItsVoice() {
        for (r in Ringer.entries) for (d in Dnd.entries) assertFalse("$r $d", q(asked = AskedBy.SPOKEN, ringer = r, dnd = d))
    }

    @Test fun headphonesOrTheCarKeepTheVoice() {
        for (t in QuietAnswer.EARS) assertFalse(t.name, q(ringer = Ringer.SILENT, routed = t))
        assertFalse(q(ringer = Ringer.SILENT, car = true))
        // A speaker the room hears is no headphones: still quiet.
        for (t in listOf(RouteType.SPEAKER, RouteType.BLE_SPEAKER, RouteType.LINE, RouteType.OTHER)) assertTrue(t.name, q(ringer = Ringer.SILENT, routed = t))
    }

    @Test fun yourTapWins() {
        assertFalse(q(ringer = Ringer.SILENT, dnd = Dnd.NONE, tapped = true))
    }

    /** review-quiet-when-silent, 1: Speak replies on "Always" is your choice, and it wins over silent and Do Not Disturb. */
    @Test fun alwaysKeepsTheVoice() {
        for (d in Dnd.entries) assertFalse(d.name, q(ringer = Ringer.SILENT, dnd = d, always = true))
    }

    /** review-quiet-when-silent, 3: a Bluetooth speaker in the room isn't headphones; an unknown Bluetooth device still is. */
    @Test fun aBluetoothSpeakerIsTheRoom() {
        for (t in listOf(RouteType.BT_A2DP, RouteType.BT_SCO, RouteType.BLE_HEADSET)) {
            assertTrue(t.name, q(ringer = Ringer.SILENT, routed = t, trust = Trust.NOT_PRIVATE))
            assertFalse(t.name, q(ringer = Ringer.SILENT, routed = t, trust = Trust.ASK))
            assertFalse(t.name, q(ringer = Ringer.SILENT, routed = t, trust = Trust.PRIVATE))
        }
    }

    private fun r(ringer: Ringer = Ringer.SILENT, dnd: Dnd = Dnd.OFF, routed: SeenDevice? = null, trust: Trust = Trust.NOT_PRIVATE,
        car: SeenDevice? = null, output: RoutineOutput = RoutineOutput.HEADSET_OR_SPEAKER, kind: TriggerKind = TriggerKind.TIME,
        key: String? = null) =
        QuietAnswer.routine(kind, output, ringer, dnd, RoutineRoute(routed, trust, car), key)

    @Test fun aRoutineOnTheSpeakerGoesQuietOnSilentOrDoNotDisturb() {
        assertTrue(r())
        assertTrue(r(routed = SeenDevice(RouteType.SPEAKER)))
        assertTrue(r(ringer = Ringer.NORMAL, dnd = Dnd.PRIORITY))
        assertTrue(r(ringer = Ringer.VIBRATE, dnd = Dnd.NONE))
        assertFalse(r(ringer = Ringer.VIBRATE))
        assertFalse(r(ringer = Ringer.NORMAL))
        // Android wouldn't say where it goes (RoutineVoice reads it as OTHER): the room.
        assertTrue(r(routed = SeenDevice(RouteType.OTHER)))
    }

    @Test fun aRoutineInHeadphonesOrTheCarKeepsItsVoice() {
        assertFalse(r(routed = SeenDevice(RouteType.WIRED_HEADPHONES), trust = Trust.PRIVATE))
        assertFalse(r(routed = SeenDevice(RouteType.BT_A2DP), trust = Trust.ASK))
        assertFalse(r(car = SeenDevice(RouteType.BT_A2DP)))
        // A Bluetooth speaker is the room.
        assertTrue(r(routed = SeenDevice(RouteType.BT_A2DP), trust = Trust.NOT_PRIVATE))
        assertTrue(r(output = RoutineOutput.HEADSET_ONLY, routed = SeenDevice(RouteType.BT_A2DP), trust = Trust.NOT_PRIVATE))
        // "Don't speak" is quiet already: nothing more to do.
        assertFalse(r(output = RoutineOutput.SILENT))
    }

    @Test fun aBriefStartedByTurningOffYourAlarmStillSpeaks() {
        assertFalse(r(kind = TriggerKind.ALARM, key = "a:1"))
        assertFalse(r(kind = TriggerKind.ALARM, key = "a:1", ringer = Ringer.NORMAL, dnd = Dnd.PRIORITY))
        // A scheduled brief, and every other start nobody made just now, stays quiet.
        for (k in TriggerKind.entries - TriggerKind.ALARM) {
            assertTrue(k.name, r(kind = k))
            assertTrue(k.name, r(kind = k, ringer = Ringer.NORMAL, dnd = Dnd.PRIORITY))
        }
    }

    /** fix-126-review 2: a missed alarm's run at your first unlock (no key), or a brief's held rest, stays quiet. */
    @Test fun aMissedAlarmRunAtTheUnlockStaysQuiet() {
        assertTrue(r(kind = TriggerKind.ALARM))
        assertTrue(r(kind = TriggerKind.ALARM, ringer = Ringer.NORMAL, dnd = Dnd.PRIORITY))
        assertTrue(r(kind = TriggerKind.ALARM, key = "t:2026-11-07T07:00"))
        assertFalse(QuietAnswer.atAlarm(TriggerKind.TIME, "a:1"))
        assertTrue(QuietAnswer.atAlarm(TriggerKind.ALARM, "a:1"))
    }
}
