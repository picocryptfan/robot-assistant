// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import app.heyrobot.core.car.CarSetup
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Assistant setup's "Headset button" row, Settings › Headset's headphones, and Settings › Car mode's Add your car (0.8). */
class HeadsetSetupTest {
    private val own = "ai.robot_app"

    @Test fun whoAnswersTheButton() {
        assertEquals(ButtonHandler.ROBOT, HeadsetSetup.handler(own, "app.heyrobot.assistant.headset.HeadsetEntryActivity", own))
        assertEquals(ButtonHandler.ASK, HeadsetSetup.handler("android", "com.android.internal.app.ResolverActivity", own))
        assertEquals(ButtonHandler.ASK, HeadsetSetup.handler("com.android.intentresolver", "com.android.intentresolver.ChooserActivity", own))
        assertEquals(ButtonHandler.OTHER, HeadsetSetup.handler("org.example.voice", "org.example.voice.Main", own))
        assertEquals(ButtonHandler.NONE, HeadsetSetup.handler(null, null, own))
    }

    @Test fun theRowSaysRobotAskOrAnotherApp() {
        val all = HeadsetEntry.ACTIONS.associateWith { ButtonHandler.ROBOT }
        assertEquals(ButtonRow("Headset button", "Headset button → Robot ✓", true), HeadsetSetup.row(true, all))
        val ask = all + (HeadsetEntry.WEB_SEARCH to ButtonHandler.ASK)
        assertTrue(HeadsetSetup.row(true, ask).text.contains("ask each time"))
        assertEquals(false, HeadsetSetup.row(true, ask).ok)
        assertTrue(HeadsetSetup.row(true, all + (HeadsetEntry.VOICE_COMMAND to ButtonHandler.OTHER)).text.contains("another app"))
        assertEquals(false, HeadsetSetup.row(true, emptyMap()).ok)
        // Turned off: nothing to fix, other apps get the button.
        assertNull(HeadsetSetup.row(false, all).ok)
        assertTrue(HeadsetSetup.row(false, all).text.startsWith("Off"))
    }

    @Test fun headphonesListAndTheirPrivacy() {
        val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
        val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
        var m = DeviceMemory().remember(buds, DeviceRole.HEADSET, 10)!!.first.remember(car, DeviceRole.CAR, 20)!!.first
        assertEquals(listOf("Pixel Buds Pro"), HeadsetSetup.headphones(m).map { it.name })
        assertTrue(HeadsetSetup.deviceLine(m.byNumber(1)!!).startsWith("Not asked"))
        m = m.answerPrivate(1, true)
        assertTrue(HeadsetSetup.deviceLine(m.byNumber(1)!!).startsWith("Private"))
        m = m.answerPrivate(1, false)
        assertTrue(HeadsetSetup.deviceLine(m.byNumber(1)!!).startsWith("Not private"))
    }

    @Test fun addYourCarOffersConnectedBluetoothOncePerDeviceCarsFirst() {
        val carA2dp = SeenDevice(RouteType.BT_A2DP, "MyCar", "XX:XX:XX:XX:12:34", BtClass.CAR_AUDIO)
        val carSco = carA2dp.copy(type = RouteType.BT_SCO)
        val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
        val wired = SeenDevice(RouteType.WIRED_HEADSET, "USB-C")
        val speaker = SeenDevice(RouteType.SPEAKER)
        val list = CarSetup.candidates(listOf(buds, carA2dp, carSco, wired, speaker), DeviceMemory())
        assertEquals(listOf("MyCar", "Pixel Buds Pro"), list.map { it.seen.name })
        assertEquals(RouteType.BT_SCO, list[0].seen.type)
        assertTrue(list[0].suggested)
        assertFalse(list[1].suggested)
        // Once added it isn't offered again; a remembered headset still may become your car.
        val m = DeviceMemory().remember(carSco, DeviceRole.CAR, 1)!!.first.remember(buds, DeviceRole.HEADSET, 2)!!.first
        assertEquals(listOf("Pixel Buds Pro"), CarSetup.candidates(listOf(buds, carA2dp, carSco), m).map { it.seen.name })
        assertFalse(list.toString().contains("MyCar"))
    }

    @Test fun placesTakeCoordinatesOnly() {
        assertEquals(45.5017 to -73.5673, CarSetup.coordinates("45.5017, -73.5673"))
        assertEquals(45.5017 to -73.5673, CarSetup.coordinates("geo:45.5017,-73.5673"))
        assertEquals(-33.9 to 151.2, CarSetup.coordinates(" -33.9 151.2 "))
        assertNull(CarSetup.coordinates("12 Main St"))
        assertNull(CarSetup.coordinates("95, 10"))
        assertNull(CarSetup.coordinates("45, 190"))
    }

    @Test fun whatsNextSaysOneEventOnly() {
        val zone = java.time.ZoneId.of("America/Toronto")
        val now = java.time.ZonedDateTime.of(2026, 10, 12, 9, 0, 0, 0, zone)
        fun at(h: Int) = now.withHour(h).toInstant().toEpochMilli()
        val events = listOf(app.heyrobot.core.CalendarEvent(at(8), at(8) + 1_800_000, false, "Gym", null),
            app.heyrobot.core.CalendarEvent(at(15), at(16), false, "Dentist", null), app.heyrobot.core.CalendarEvent(at(0), at(23), true, "Holiday", null))
        assertEquals("Next: Dentist at 3:00 PM.", app.heyrobot.core.car.WhatsNext.line(events, now, app.heyrobot.core.output.SpokenLang.EN))
        assertEquals("Prochain : Dentist à 15 h 00.", app.heyrobot.core.car.WhatsNext.line(events, now, app.heyrobot.core.output.SpokenLang.FR))
        assertEquals("Nothing else on your calendar today.", app.heyrobot.core.car.WhatsNext.line(events.take(1), now, app.heyrobot.core.output.SpokenLang.EN))
        assertEquals("Now: Dentist.", app.heyrobot.core.car.WhatsNext.line(events, now.withHour(15).withMinute(30), app.heyrobot.core.output.SpokenLang.EN))
    }

    @Test fun carModesSummaryAndWhetherItStartsByItself() {
        val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
        val none = DeviceMemory()
        val one = none.remember(car, DeviceRole.CAR, 1)!!.first
        assertEquals("Off", CarSetup.summary(false, one))
        assertEquals("On · no car added", CarSetup.summary(true, none))
        assertEquals("On · 1 car", CarSetup.summary(true, one))
        assertFalse(CarSetup.startsByItself(false, CarSettings(), one))
        assertFalse(CarSetup.startsByItself(true, CarSettings(), none))
        assertTrue(CarSetup.startsByItself(true, CarSettings(), one))
        assertFalse(CarSetup.startsByItself(true, CarSettings(startOnConnect = false), one))
    }
}
