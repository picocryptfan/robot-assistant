// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headsup

import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Dnd
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class HeadsUpTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 7, 14, 15, 0, 0, zone)
    private val nowMs = now.toInstant().toEpochMilli()
    private fun ev(minutes: Long, title: String = "Dentist", id: Long = 1, allDay: Boolean = false) =
        CalendarEvent(nowMs + minutes * 60_000, nowMs + (minutes + 30) * 60_000, allDay, title, null, id)

    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "AA:BB:CC:DD:EE:FF", BtClass.WEARABLE_HEADSET)
    private val confirmed = DeviceMemory().remember(buds, DeviceRole.HEADSET, 0L, private = true)!!.first
    private val on = HeadsUpSettings(on = true)
    private fun facts(routed: SeenDevice?, car: Boolean = false, dnd: Dnd = Dnd.OFF, call: Boolean = false, locked: Boolean = true) =
        HeadsUpFacts(routed, car, dnd, call, locked)

    @Test fun eachOutputKind() {
        val empty = DeviceMemory()
        // Worn by type: private.
        for (t in listOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.HEARING_AID))
            assertTrue(t.name, HeadsUp.private(SeenDevice(t), empty, false))
        // Heard by others, or not headphones: never.
        for (t in listOf(RouteType.SPEAKER, RouteType.EARPIECE, RouteType.BUILTIN_MIC, RouteType.BLE_SPEAKER, RouteType.BLE_BROADCAST,
            RouteType.LINE, RouteType.USB_OTHER, RouteType.OTHER))
            assertFalse(t.name, HeadsUp.private(SeenDevice(t), confirmed, false))
        // Bluetooth headsets: only once confirmed.
        for (t in listOf(RouteType.BT_A2DP, RouteType.BT_SCO, RouteType.BLE_HEADSET)) {
            val d = buds.copy(type = t)
            assertFalse(t.name, HeadsUp.private(d, empty, false))
            assertTrue(t.name, HeadsUp.private(d, confirmed, false))
        }
        // Unknown route, a car by its class, a remembered car, car mode with its car, a loudspeaker class: never.
        assertFalse(HeadsUp.private(null, confirmed, false))
        assertFalse(HeadsUp.private(buds.copy(btClass = BtClass.CAR_AUDIO), confirmed, false))
        assertFalse(HeadsUp.private(buds.copy(btClass = BtClass.HANDSFREE), confirmed, false))
        assertFalse(HeadsUp.private(buds.copy(btClass = BtClass.LOUDSPEAKER), confirmed, false))
        assertFalse(HeadsUp.private(buds, confirmed, true))
        val car = SeenDevice(RouteType.BT_A2DP, "Car", "11:22:33:44:55:66")
        val asCar = DeviceMemory().remember(car, DeviceRole.CAR, 0L)!!.first
        assertFalse(HeadsUp.private(car, asCar, false))
        // A confirmed headset that said no.
        val said = DeviceMemory().remember(buds, DeviceRole.HEADSET, 0L, private = false)!!.first
        assertFalse(HeadsUp.private(buds, said, false))
    }

    @Test fun holdOrder() {
        assertEquals(HeadsUpHold.OFF, HeadsUp.hold(HeadsUpSettings(), facts(buds), confirmed, now))
        assertEquals(HeadsUpHold.CALL, HeadsUp.hold(on, facts(buds, call = true), confirmed, now))
        for (d in listOf(Dnd.PRIORITY, Dnd.ALARMS, Dnd.NONE)) assertEquals(HeadsUpHold.DND, HeadsUp.hold(on, facts(buds, dnd = d), confirmed, now))
        assertEquals(HeadsUpHold.QUIET, HeadsUp.hold(on, facts(buds), confirmed, now.withHour(23)))
        assertEquals(HeadsUpHold.NOT_PRIVATE, HeadsUp.hold(on, facts(SeenDevice(RouteType.SPEAKER)), confirmed, now))
        assertNull(HeadsUp.hold(on, facts(buds), confirmed, now))
    }

    @Test fun quietHours() {
        val q = HeadsUpQuiet.FROM_22
        assertTrue(q.contains(LocalTime.of(22, 0)))
        assertTrue(q.contains(LocalTime.of(3, 0)))
        assertFalse(q.contains(LocalTime.of(7, 0)))
        assertFalse(q.contains(LocalTime.of(14, 0)))
        assertFalse(HeadsUpQuiet.OFF.contains(LocalTime.of(3, 0)))
        assertTrue(HeadsUpQuiet.FROM_23.contains(LocalTime.of(7, 59)))
    }

    @Test fun timingAndOnce() {
        val events = listOf(ev(15), ev(16, id = 2), ev(90, id = 3), ev(5, "Lunch", id = 4, allDay = true), ev(0, id = 5))
        val due = HeadsUp.due(events, nowMs, 15, HeadsUpSaid())
        assertEquals(listOf(1L), due.map { it.id })
        val said = HeadsUpSaid().plus(due)
        assertTrue(HeadsUp.due(events, nowMs, 15, said).isEmpty())
        // The next moment: event 2's, a minute away.
        assertEquals(nowMs + 60_000, HeadsUp.nextAt(events, nowMs, 15, said))
        // Late (the alarm came after the moment): still said, with the real minutes.
        assertEquals(listOf(1L, 2L), HeadsUp.due(events, nowMs + 120_000, 15, HeadsUpSaid()).map { it.id })
        assertEquals(13, HeadsUp.minutesLeft(ev(15), nowMs + 120_000, 15))
        // Nothing soon: a recheck within the hour.
        assertEquals(nowMs + HeadsUp.RECHECK_MS, HeadsUp.nextAt(listOf(ev(300)), nowMs, 15, HeadsUpSaid()))
        // Pruned after two days.
        assertTrue(said.prune(nowMs + HeadsUp.KEEP_MS + 16 * 60_000).keys.isEmpty())
        assertEquals(said, HeadsUpSaid.decode(said.encode()))
    }

    @Test fun settingsFile() {
        assertFalse(HeadsUpSettings.decode(null).on)
        assertFalse(HeadsUpSettings.decode("{not json").on)
        assertFalse(HeadsUpSettings.decode("""{"v":2,"on":true}""").on)
        val s = HeadsUpSettings(on = true, minutes = 30, quiet = HeadsUpQuiet.OFF)
        assertEquals(s, HeadsUpSettings.decode(s.encode()))
        assertEquals(15, HeadsUpSettings.decode("""{"v":1,"on":true,"minutes":7}""").minutes)
    }

    @Test fun englishAndFrench() {
        val e = ev(15)
        assertEquals("Dentist at 2:30, in 15 minutes.", HeadsUp.line(e, nowMs, 15, now, Lang.EN, true).render(Langs.of(Lang.EN)))
        val fr = HeadsUp.line(e.copy(title = "Dentiste"), nowMs, 15, now, Lang.FR, true).render(Langs.of(Lang.FR))
        assertTrue(fr, fr.startsWith("Dentiste à 14") && fr.endsWith("30, dans 15 minutes."))
        assertEquals("Something on your calendar in 1 minute.", HeadsUp.line(ev(1), nowMs, 15, now, Lang.EN, false).render(Langs.of(Lang.EN)))
        assertEquals("Un événement à ton agenda dans 15 minutes.", HeadsUp.line(e, nowMs, 15, now, Lang.FR, false).render(Langs.of(Lang.FR)))
        assertTrue(HeadsUp.namedWhileLocked(buds, confirmed))
        assertFalse(HeadsUp.namedWhileLocked(SeenDevice(RouteType.WIRED_HEADSET), confirmed))
        assertFalse(HeadsUp.namedWhileLocked(buds, DeviceMemory()))
    }
}
