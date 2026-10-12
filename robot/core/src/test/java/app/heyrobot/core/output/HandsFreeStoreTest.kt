package app.heyrobot.core.output

import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.ParkCode
import app.heyrobot.core.car.ParkItem
import app.heyrobot.core.car.ParkList
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.headset.ListenWith
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Headset and car mode's files (0.8): their own JSON next to settings.json, defaults when absent, written under one lock. */
class HandsFreeStoreTest {
    private fun dir(): File = Files.createTempDirectory("handsfree").toFile()

    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")

    @Test fun nothingSavedReadsAsTheDesignsDefaults() {
        val s = HandsFreeStore(dir())
        // Car mode off, the headset button on, no device, no place, nothing parked.
        assertFalse(s.car().on)
        assertEquals(CarSettings(), s.car().settings)
        assertTrue(s.headset().settings.buttonOpensRobot)
        assertEquals(HeadsetSettings(), s.headset().settings)
        assertEquals(HandsFreeSafety(), s.headset().safety)
        assertFalse(s.headset().noticeSeen)
        assertEquals(DeviceMemory(), s.devices())
        assertEquals(Places(), s.places())
        assertTrue(s.parked().isEmpty())
    }

    @Test fun eachFileRoundTripsAndStaysApart() {
        val d = dir()
        val s = HandsFreeStore(d)
        s.editHeadset { it.copy(settings = it.settings.copy(listenWith = ListenWith.PHONE_MIC, followUpSeconds = 9), noticeSeen = true) }
        s.editCar { it.copy(on = true, settings = it.settings.copy(calls = CallSetting.CALL_AFTER_YES, home = SavedPlace("ignored", 1.0, 2.0))) }
        s.editPlaces { it.copy(home = SavedPlace.of("12 Main St", 45.5, -73.6)) }
        s.editDevices { it.remember(buds, DeviceRole.HEADSET, 10)!!.first.remember(car, DeviceRole.CAR, 20)!!.first }
        val again = HandsFreeStore(d)
        assertEquals(ListenWith.PHONE_MIC, again.headset().settings.listenWith)
        assertEquals(9, again.headset().settings.followUpSeconds)
        assertTrue(again.headset().noticeSeen)
        assertTrue(again.car().on)
        assertEquals(CallSetting.CALL_AFTER_YES, again.car().settings.calls)
        // The places live in places.json only: car.json never keeps an address.
        assertNull(again.car().settings.home)
        assertFalse(File(d, HandsFreeStore.CAR).readText().contains("Main"))
        assertEquals("12 Main St", again.places().home!!.label)
        assertEquals("12 Main St", again.carSettings().home!!.label)
        assertEquals(listOf(DeviceRole.HEADSET, DeviceRole.CAR), again.devices().devices.map { it.role })
        // settings.json is never touched.
        assertFalse(File(d, "settings.json").exists())
        assertEquals(HandsFreeStore.FILES.toSet() - HandsFreeStore.PARK, d.list()!!.filter { it.endsWith(".json") }.toSet())
    }

    @Test fun aDamagedFileReadsAsDefaultsAndADamagedDeviceListIsKeptAside() {
        val d = dir()
        File(d, HandsFreeStore.HEADSET).writeText("{nope")
        File(d, HandsFreeStore.CAR).writeText("[]")
        File(d, HandsFreeStore.DEVICES).writeText("{\"v\":1}")
        val s = HandsFreeStore(d)
        assertEquals(HeadsetFile(), s.headset())
        assertEquals(CarFile(), s.car())
        assertEquals(DeviceMemory(), s.devices())
        s.editDevices { it.remember(buds, DeviceRole.HEADSET, 1)!!.first }
        assertTrue(File(d, "devices.json.bad").isFile)
        assertEquals(1, s.devices().devices.size)
    }

    @Test fun aNewerRobotsDeviceListIsLeftAlone() {
        val d = dir()
        File(d, HandsFreeStore.DEVICES).writeText(JSONObject().put("v", 99).put("devices", org.json.JSONArray()).toString())
        val s = HandsFreeStore(d)
        assertNull(s.editDevices { it.remember(buds, DeviceRole.HEADSET, 1)!!.first })
        assertTrue(File(d, HandsFreeStore.DEVICES).readText().contains("99"))
    }

    @Test fun theParkListKeepsWhatToAskAgainOnceEach() {
        val s = HandsFreeStore(dir())
        val text = Parked(ParkItem(ParkCode.NEW_TEXT, "Text Bob: I'm late", 5), "Text Bob I'm late")
        s.editParked { ParkFile.add(it, text) }
        s.editParked { ParkFile.add(it, text.copy(item = text.item.copy(at = 6))) }
        s.editParked { ParkFile.add(it, Parked(ParkItem(ParkCode.MUST_SEE, "Open Signal", 7))) }
        val list = s.parked()
        assertEquals(2, list.size)
        assertEquals("Text Bob I'm late", list[0].ask)
        assertNull(list[1].ask)
        // Never more than the list's limit, the oldest dropped; toString never shows the words.
        var many = emptyList<Parked>()
        for (i in 0 until ParkList.MAX + 5) many = ParkFile.add(many, Parked(ParkItem(ParkCode.NEEDS_SCREEN, "thing $i", i.toLong())))
        assertEquals(ParkList.MAX, many.size)
        assertEquals("thing 5", many.first().item.summary)
        assertFalse(text.toString().contains("Bob"))
        assertFalse(Places(SavedPlace("12 Main St", 1.0, 2.0)).toString().contains("Main"))
    }

    @Test fun theNoticeShowsOnlyAfterAnUpdateUntilClosed() {
        assertTrue(HandsFreeNotice.shows(hadSettings = true, seen = false))
        assertFalse(HandsFreeNotice.shows(hadSettings = true, seen = true))
        assertFalse(HandsFreeNotice.shows(hadSettings = false, seen = false))
        assertTrue(HandsFreeNotice.TEXT.contains("Nothing runs until you turn it on"))
    }
}
