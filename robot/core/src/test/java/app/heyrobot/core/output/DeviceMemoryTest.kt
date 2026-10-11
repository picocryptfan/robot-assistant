package app.heyrobot.core.output

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remembered headsets and cars (design §3.9 DeviceMatch: name plus tail, collisions, renames). */
class DeviceMemoryTest {
    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val budsSco = buds.copy(type = RouteType.BT_SCO)
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")

    private fun mem(vararg d: Pair<SeenDevice, DeviceRole>): DeviceMemory =
        d.fold(DeviceMemory()) { m, (s, r) -> m.remember(s, r, now = 1_000)!!.first }

    @Test fun matchesByNameAndTailAcrossProfiles() {
        val m = mem(buds to DeviceRole.HEADSET)
        val one = m.match(budsSco) as DeviceMatch.One
        assertEquals(MatchHow.NAME_AND_TAIL, one.how)
        assertEquals(1, one.device.number)
        assertEquals("dev-1", one.device.id)
        assertEquals(DeviceMatch.None, m.match(buds.copy(address = "XX:XX:XX:XX:EE:00")))
        assertEquals(DeviceMatch.None, m.match(SeenDevice(RouteType.WIRED_HEADSET, "Pixel Buds Pro")))
        assertEquals(DeviceMatch.None, m.match(SeenDevice(RouteType.SPEAKER)))
    }

    @Test fun aRenameMatchesByTailOnlyAndAsksAgain() {
        var m = mem(buds to DeviceRole.HEADSET).answerPrivate(1, true)
        assertEquals(Trust.PRIVATE, m.trust(buds))
        val renamed = buds.copy(name = "My earbuds")
        val one = m.match(renamed) as DeviceMatch.One
        assertEquals(MatchHow.RENAMED, one.how)
        // Its role carries over, its "private" doesn't: another device could end in the same two bytes.
        assertEquals(Trust.ASK, m.trust(renamed))
        // Seeing it doesn't learn the new name from a tail alone; remembering it (your answer) does, and asks for privacy again.
        assertEquals("Pixel Buds Pro", m.saw(renamed, 2_000).byNumber(1)!!.name)
        m = m.remember(renamed, DeviceRole.HEADSET, 3_000)!!.first
        assertEquals("My earbuds", m.byNumber(1)!!.name)
        assertNull(m.byNumber(1)!!.private)
        m = m.answerPrivate(1, true)
        assertEquals(Trust.PRIVATE, m.trust(renamed))
        assertEquals(1, m.devices.size)
    }

    @Test fun theWholeAddressWinsWhenRobotHasIt() {
        val exact = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "AA:BB:CC:DD:EE:FF")
        var m = mem(exact to DeviceRole.HEADSET).answerPrivate(1, true)
        // Renamed, with the permission: the same device, private kept, and the name learned when seen.
        val renamed = exact.copy(name = "My earbuds")
        assertEquals(MatchHow.ADDRESS, (m.match(renamed) as DeviceMatch.One).how)
        assertEquals(Trust.PRIVATE, m.trust(renamed))
        assertEquals("My earbuds", m.saw(renamed, 5_000).byNumber(1)!!.name)
        // The same name and tail but another whole address: another device.
        assertEquals(DeviceMatch.None, m.match(exact.copy(address = "11:22:33:44:EE:FF")))
        assertEquals(Trust.ASK, m.trust(exact.copy(address = "11:22:33:44:EE:FF")))
        // The permission taken away later: back to name and tail.
        assertEquals(MatchHow.NAME_AND_TAIL, (m.match(buds) as DeviceMatch.One).how)
        // A device remembered without the permission learns its whole address once Robot has it.
        m = mem(buds to DeviceRole.HEADSET).saw(exact, 9_000)
        assertEquals("AA:BB:CC:DD:EE:FF", m.byNumber(1)!!.address)
        assertEquals(9_000, m.byNumber(1)!!.seen)
    }

    @Test fun twoDevicesThatLookTheSameAreACollision() {
        val twin = buds.copy(type = RouteType.BT_A2DP)
        var m = mem(buds to DeviceRole.HEADSET)
        // Remembering the same name and tail again updates the one there.
        assertEquals(1, m.remember(twin, DeviceRole.HEADSET, 2_000)!!.first.devices.size)
        // Two entries can only come from older files or whole addresses learned apart; then Robot can't tell them apart.
        m = DeviceMemory(listOf(RememberedDevice(1, DeviceRole.HEADSET, DeviceFamily.BLUETOOTH, "Pixel Buds Pro", "EE:FF", private = true),
            RememberedDevice(2, DeviceRole.HEADSET, DeviceFamily.BLUETOOTH, "Pixel Buds Pro", "EE:FF", private = null)), 3)
        assertTrue(m.match(buds) is DeviceMatch.Several)
        assertEquals(Trust.ASK, m.trust(buds))
        assertNull(m.find(buds))
        assertEquals(Trust.PRIVATE, m.answerPrivate(2, true).trust(buds))
        assertEquals(Trust.NOT_PRIVATE, m.answerPrivate(2, false).trust(buds))
    }

    @Test fun trustByTypeClassRoleAndAnswer() {
        val m = mem(buds to DeviceRole.HEADSET, car to DeviceRole.CAR)
        assertEquals(Trust.ASK, m.trust(buds))
        assertEquals(Trust.PRIVATE, m.answerPrivate(1, true).trust(budsSco))
        assertEquals(Trust.NOT_PRIVATE, m.answerPrivate(1, false).trust(buds))
        // A car is never private, even if asked.
        assertEquals(Trust.NOT_PRIVATE, m.trust(car))
        assertEquals(Trust.NOT_PRIVATE, m.answerPrivate(2, true).trust(car))
        assertEquals(false, m.answerPrivate(2, true).byNumber(2)!!.private)
        // By type: the speaker, line out, an LE speaker or broadcast never; wired, USB headsets, earpiece, hearing aids always.
        for (t in listOf(RouteType.SPEAKER, RouteType.LINE, RouteType.BLE_SPEAKER, RouteType.BLE_BROADCAST, RouteType.OTHER)) assertEquals(t.name, Trust.NOT_PRIVATE, m.trust(SeenDevice(t, "x", "XX:XX:XX:XX:00:01")))
        for (t in listOf(RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.EARPIECE, RouteType.HEARING_AID)) assertEquals(t.name, Trust.PRIVATE, m.trust(SeenDevice(t, "x", "XX:XX:XX:XX:00:01")))
        // An unknown USB device is asked; a never-seen Bluetooth headset too.
        assertEquals(Trust.ASK, m.trust(SeenDevice(RouteType.USB_OTHER, "DAC")))
        assertEquals(Trust.ASK, m.trust(SeenDevice(RouteType.BLE_HEADSET, "Galaxy Buds", "XX:XX:XX:XX:AB:CD")))
        // By Bluetooth class (with the permission): a loudspeaker or a car is never private, never asked.
        for (c in listOf(BtClass.LOUDSPEAKER, BtClass.CAR_AUDIO, BtClass.HIFI_AUDIO)) assertEquals(c.name, Trust.NOT_PRIVATE, m.trust(SeenDevice(RouteType.BT_A2DP, "Box", "XX:XX:XX:XX:AB:CD", c)))
        // Headphones by class are still asked once.
        assertEquals(Trust.ASK, m.trust(SeenDevice(RouteType.BT_A2DP, "Box", "XX:XX:XX:XX:AB:CD", BtClass.HEADPHONES)))
        // A wired headset you said No to stays not private.
        val wired = SeenDevice(RouteType.USB_HEADSET, "USB-C headset")
        val mw = DeviceMemory().remember(wired, DeviceRole.HEADSET, 1, private = false)!!.first
        assertEquals(Trust.NOT_PRIVATE, mw.trust(wired))
    }

    @Test fun rememberingAndForgetting() {
        var m = DeviceMemory()
        assertNull(m.remember(SeenDevice(RouteType.SPEAKER), DeviceRole.HEADSET, 1))
        assertNull(m.remember(SeenDevice(RouteType.BT_A2DP, "No address", ""), DeviceRole.HEADSET, 1))
        assertNull(m.remember(SeenDevice(RouteType.LINE, "TV"), DeviceRole.HEADSET, 1))
        val (m1, d1) = m.remember(buds, DeviceRole.HEADSET, 10)!!
        assertEquals(1, d1.number)
        assertEquals("EE:FF", d1.tail)
        assertNull(d1.address)
        assertTrue(d1.enabled)
        val (m2, d2) = m1.remember(car, DeviceRole.CAR, 20, private = true)!!
        assertEquals(2, d2.number)
        assertEquals(false, d2.private)
        assertEquals(listOf(d2), m2.cars)
        assertEquals(listOf(d1), m2.headsets)
        // A headset taught as a car becomes a car (never private).
        val m3 = m2.answerPrivate(1, true).remember(buds, DeviceRole.CAR, 30)!!.first
        assertEquals(DeviceRole.CAR, m3.byNumber(1)!!.role)
        assertEquals(false, m3.byNumber(1)!!.private)
        // Forgotten numbers are never given again.
        m = m2.forget(1)
        val (_, d3) = m.remember(buds, DeviceRole.HEADSET, 40)!!
        assertEquals(3, d3.number)
        assertFalse(m2.setEnabled(1, false).byNumber(1)!!.enabled)
        // At most MAX.
        var full = DeviceMemory()
        for (i in 0 until DeviceMemory.MAX) full = full.remember(SeenDevice(RouteType.BT_A2DP, "H$i", "XX:XX:XX:XX:00:%02X".format(i)), DeviceRole.HEADSET, 1)!!.first
        assertNull(full.remember(SeenDevice(RouteType.BT_A2DP, "One more", "XX:XX:XX:XX:FF:FF"), DeviceRole.HEADSET, 1))
        // Names are kept as one short line; never in toString.
        val odd = DeviceMemory().remember(SeenDevice(RouteType.BT_A2DP, "Buds\n‮evil  " + "x".repeat(100), "XX:XX:XX:XX:00:01"), DeviceRole.HEADSET, 1)!!.second
        assertEquals(DeviceMemory.MAX_NAME, odd.name.length)
        assertFalse(odd.name.contains('\n') || odd.name.contains('‮'))
        assertFalse(d1.toString().contains("Pixel") || d1.toString().contains("EE:FF"))
    }

    @Test fun devicesFileRoundTripAndDamage() {
        val exact = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "AA:BB:CC:DD:EE:FF")
        val m = mem(exact to DeviceRole.HEADSET, car to DeviceRole.CAR, SeenDevice(RouteType.USB_HEADSET, "USB-C") to DeviceRole.HEADSET)
            .answerPrivate(1, true).setEnabled(3, false).forget(3)
        val back = DeviceMemory.decode(m.encode()) as DeviceFile.Ok
        assertEquals(m, back.memory)
        assertEquals(0, back.dropped)
        assertEquals(4, back.memory.nextNumber)
        assertEquals(DeviceFile.Damaged, DeviceMemory.decode(null))
        assertEquals(DeviceFile.Damaged, DeviceMemory.decode("{"))
        assertEquals(DeviceFile.Damaged, DeviceMemory.decode("""{"devices":[]}"""))
        assertEquals(DeviceFile.TooNew(2), DeviceMemory.decode("""{"v":2,"devices":[]}"""))
        // A bad entry is dropped, never repaired; the next number stays past every number seen.
        val o = JSONObject(m.encode())
        val a = o.getJSONArray("devices")
        a.put(JSONObject().put("n", 9).put("role", "toaster").put("family", "bluetooth").put("tail", "00:01"))
        a.put(JSONObject().put("n", 10).put("role", "headset").put("family", "bluetooth"))
        a.put(JSONObject().put("n", 11).put("role", "headset").put("family", "bluetooth").put("tail", "00:01").put("address", "AA:BB:CC:DD:EE:FF"))
        a.put(JSONObject().put("n", 1).put("role", "headset").put("family", "wired").put("name", "dup"))
        a.put(JSONObject().put("n", 12).put("role", "car").put("family", "bluetooth").put("tail", "00:02").put("private", true))
        o.put("next", 2)
        val r = DeviceMemory.decode(o.toString()) as DeviceFile.Ok
        assertEquals(4, r.dropped)
        assertEquals(false, r.memory.byNumber(12)!!.private)
        assertEquals(13, r.memory.nextNumber)
    }
}

/** Remembered devices coming and going, with the grace that rides out a flapping link (design §2.1, §4.8). */
class DevicePresenceTest {
    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val budsSco = buds.copy(type = RouteType.BT_SCO)
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")
    private val carA2dp = car.copy(type = RouteType.BT_A2DP)
    private val stranger = SeenDevice(RouteType.BT_A2DP, "Someone's speaker", "XX:XX:XX:XX:99:99")
    private val memory = DeviceMemory().remember(buds, DeviceRole.HEADSET, 0)!!.first.remember(car, DeviceRole.CAR, 0)!!.first

    @Test fun connectOnceForEveryProfile() {
        val (p1, e1) = DevicePresence.onDevices(Presence(), memory, listOf(buds, budsSco, stranger, SeenDevice(RouteType.SPEAKER)), 1_000)
        assertEquals(listOf(PresenceEvent.Connected(memory.byNumber(1)!!, 1_000)), e1)
        // SCO going away while A2DP stays is no change.
        val (p2, e2) = DevicePresence.onDevices(p1, memory, listOf(buds), 2_000)
        assertEquals(emptyList<PresenceEvent>(), e2)
        assertEquals(p1, p2)
    }

    @Test fun aFlapWithinTheGraceIsNoEvent() {
        val (p1, _) = DevicePresence.onDevices(Presence(), memory, listOf(car, carA2dp), 0)
        val (p2, e2) = DevicePresence.onDevices(p1, memory, emptyList(), 10_000)
        assertEquals(emptyList<PresenceEvent>(), e2)
        assertTrue(p2.counts(2))
        assertEquals(10_000 + DevicePresence.CAR_GRACE_MS, DevicePresence.nextTick(p2, memory))
        // Back after 20 s: nothing happened.
        val (p3, e3) = DevicePresence.onDevices(p2, memory, listOf(carA2dp), 30_000)
        assertEquals(emptyList<PresenceEvent>(), e3)
        assertNull(DevicePresence.nextTick(p3, memory))
        // Gone for good: disconnected once the grace ends.
        val (p4, _) = DevicePresence.onDevices(p3, memory, emptyList(), 40_000)
        assertEquals(emptyList<PresenceEvent>(), DevicePresence.tick(p4, memory, 69_999).second)
        val (p5, e5) = DevicePresence.tick(p4, memory, 70_000)
        assertEquals(listOf(PresenceEvent.Disconnected(memory.byNumber(2)!!, 70_000)), e5)
        assertFalse(p5.counts(2))
        // A headset's grace is shorter.
        val (h1, _) = DevicePresence.onDevices(Presence(), memory, listOf(buds), 0)
        val (h2, _) = DevicePresence.onDevices(h1, memory, emptyList(), 1_000)
        assertEquals(1, DevicePresence.tick(h2, memory, 1_000 + DevicePresence.HEADSET_GRACE_MS).second.size)
        // A later device-list callback also ends what is past its grace.
        val (_, e6) = DevicePresence.onDevices(h2, memory, listOf(car), 60_000)
        assertEquals(listOf(PresenceEvent.Disconnected::class, PresenceEvent.Connected::class).toSet(), e6.map { it::class }.toSet())
    }

    @Test fun forgottenWhileLeavingIsJustGone() {
        val (p1, _) = DevicePresence.onDevices(Presence(), memory, listOf(car), 0)
        val (p2, _) = DevicePresence.onDevices(p1, memory, emptyList(), 1_000)
        val forgot = memory.forget(2)
        val (p3, e3) = DevicePresence.onDevices(p2, forgot, emptyList(), 2_000)
        assertEquals(emptyList<PresenceEvent>(), e3)
        assertFalse(p3.counts(2))
    }

    @Test fun routinesMatchTheirDeviceOrAnyOfTheKind() {
        val connected = PresenceEvent.Connected(memory.byNumber(2)!!, 0)
        val gone = PresenceEvent.Disconnected(memory.byNumber(1)!!, 0)
        assertTrue(DeviceTriggers.matches(connected, "car", null, connect = true))
        assertTrue(DeviceTriggers.matches(connected, "car", "dev-2", connect = true))
        assertFalse(DeviceTriggers.matches(connected, "car", "dev-1", connect = true))
        assertFalse(DeviceTriggers.matches(connected, "car", null, connect = false))
        assertFalse(DeviceTriggers.matches(connected, "headset", null, connect = true))
        assertTrue(DeviceTriggers.matches(gone, "headset", null, connect = false))
        assertTrue(DeviceTriggers.matches(gone, "headset", "dev-1", connect = false))
        assertEquals(listOf(memory.byNumber(2)), DeviceTriggers.choices(memory, "car"))
    }
}
