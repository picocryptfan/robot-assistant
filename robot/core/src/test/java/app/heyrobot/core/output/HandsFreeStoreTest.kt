// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.car.CallSetting
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.SavedPlace
import app.heyrobot.core.headset.HeadsetSettings
import app.heyrobot.core.headset.ListenWith
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

/** Headset and car mode's files (0.8): their own JSON next to settings.json, defaults when absent, written under one lock. */
class HandsFreeStoreTest {
    @get:Rule val tmpRoot = TemporaryFolder()
    private fun dir(): File = Files.createTempDirectory(tmpRoot.root.toPath(), "handsfree").toFile()

    private val buds = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
    private val car = SeenDevice(RouteType.BT_SCO, "MyCar", "XX:XX:XX:XX:12:34")

    @Test fun nothingSavedReadsAsTheDesignsDefaults() {
        val s = HandsFreeStore(dir())
        // Car mode off, the headset button on, no device, no place.
        assertFalse(s.car().on)
        assertEquals(CarSettings(), s.car().settings)
        assertTrue(s.headset().settings.buttonOpensRobot)
        assertEquals(HeadsetSettings(), s.headset().settings)
        assertEquals(HandsFreeSafety(), s.headset().safety)
        assertFalse(s.headset().noticeSeen)
        assertEquals(DeviceMemory(), s.devices())
        assertEquals(Places(), s.places())
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
        assertEquals(HandsFreeStore.FILES.toSet(), d.list()!!.filter { it.endsWith(".json") }.toSet())
        // 1.0: no park.json any more.
        assertFalse(HandsFreeStore.LEGACY_PARK in HandsFreeStore.FILES)
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

    @Test fun placesNeverShowTheirWords() {
        assertFalse(Places(SavedPlace("12 Main St", 1.0, 2.0)).toString().contains("Main"))
    }

    /** 1.0's test builds' park.json held what you said: it is deleted, never read, and nothing writes it again. */
    @Test fun anOldParkFileIsDeleted() {
        val d = dir()
        File(d, HandsFreeStore.LEGACY_PARK).writeText("""{"v":1,"items":[{"code":1,"summary":"Text Bob: I'm late","at":5,"ask":"Text Bob I'm late"}]}""")
        val s = HandsFreeStore(d)
        s.editCar { it.copy(on = true) }
        assertTrue(File(d, HandsFreeStore.LEGACY_PARK).isFile)
        s.dropLegacy()
        assertFalse(File(d, HandsFreeStore.LEGACY_PARK).exists())
        s.dropLegacy()
        assertTrue(s.car().on)
    }

    @Test fun theNoticeShowsOnlyAfterAnUpdateUntilClosed() {
        assertTrue(HandsFreeNotice.shows(hadSettings = true, seen = false))
        assertFalse(HandsFreeNotice.shows(hadSettings = true, seen = true))
        assertFalse(HandsFreeNotice.shows(hadSettings = false, seen = false))
        assertTrue(HandsFreeNotice.TEXT.contains("Nothing runs until you turn it on"))
    }
}
