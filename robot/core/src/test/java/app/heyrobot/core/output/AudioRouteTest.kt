// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import android.bluetooth.BluetoothClass
import android.media.AudioDeviceInfo
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route model's numbers are Android's own: the constants below are compile-time values from android.jar (inlined,
 * nothing of Android runs), so a wrong number in core fails here rather than on the phone.
 */
class AudioRouteTest {
    @Test fun routeTypesAreAndroidsNumbers() {
        val expect = mapOf(
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER to RouteType.SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE to RouteType.SPEAKER,
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE to RouteType.EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_MIC to RouteType.BUILTIN_MIC,
            AudioDeviceInfo.TYPE_WIRED_HEADSET to RouteType.WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES to RouteType.WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET to RouteType.USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE to RouteType.USB_OTHER,
            AudioDeviceInfo.TYPE_USB_ACCESSORY to RouteType.USB_OTHER, AudioDeviceInfo.TYPE_BLUETOOTH_A2DP to RouteType.BT_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO to RouteType.BT_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET to RouteType.BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER to RouteType.BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST to RouteType.BLE_BROADCAST,
            AudioDeviceInfo.TYPE_HEARING_AID to RouteType.HEARING_AID, AudioDeviceInfo.TYPE_LINE_ANALOG to RouteType.LINE,
            AudioDeviceInfo.TYPE_LINE_DIGITAL to RouteType.LINE, AudioDeviceInfo.TYPE_HDMI to RouteType.LINE, AudioDeviceInfo.TYPE_HDMI_ARC to RouteType.LINE,
            AudioDeviceInfo.TYPE_HDMI_EARC to RouteType.LINE, AudioDeviceInfo.TYPE_DOCK to RouteType.LINE, AudioDeviceInfo.TYPE_DOCK_ANALOG to RouteType.LINE,
            AudioDeviceInfo.TYPE_AUX_LINE to RouteType.LINE, AudioDeviceInfo.TYPE_TELEPHONY to RouteType.OTHER, AudioDeviceInfo.TYPE_REMOTE_SUBMIX to RouteType.OTHER,
            AudioDeviceInfo.TYPE_BUS to RouteType.OTHER, AudioDeviceInfo.TYPE_UNKNOWN to RouteType.OTHER, 999 to RouteType.OTHER,
        )
        for ((n, t) in expect) assertEquals("type $n", t, RouteType.of(n))
        // Every number a kind claims is listed above.
        assertEquals(RouteType.entries.flatMap { it.androidTypes }.toSet(), RouteType.entries.flatMap { it.androidTypes }.filter { it in expect.keys }.toSet())
    }

    @Test fun privacyByType() {
        for (t in listOf(RouteType.EARPIECE, RouteType.WIRED_HEADSET, RouteType.WIRED_HEADPHONES, RouteType.USB_HEADSET, RouteType.HEARING_AID)) assertEquals(t.name, true, t.privateByType)
        for (t in listOf(RouteType.SPEAKER, RouteType.BLE_SPEAKER, RouteType.BLE_BROADCAST, RouteType.LINE, RouteType.OTHER)) assertEquals(t.name, false, t.privateByType)
        for (t in listOf(RouteType.BT_A2DP, RouteType.BT_SCO, RouteType.BLE_HEADSET, RouteType.USB_OTHER)) assertNull(t.name, t.privateByType)
        // A2DP and plain headphones have no microphone; SCO, LE headsets, wired and USB headsets do.
        assertFalse(RouteType.BT_A2DP.hasMic)
        assertFalse(RouteType.WIRED_HEADPHONES.hasMic)
        assertTrue(RouteType.BT_SCO.hasMic && RouteType.BLE_HEADSET.hasMic && RouteType.WIRED_HEADSET.hasMic && RouteType.USB_HEADSET.hasMic)
        assertEquals(setOf(RouteType.BT_SCO, RouteType.BLE_HEADSET, RouteType.WIRED_HEADSET, RouteType.USB_HEADSET), RouteType.entries.filter { it.communication }.toSet())
    }

    @Test fun bluetoothClassesAreAndroidsNumbers() {
        assertEquals(BtClass.CAR_AUDIO, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO))
        assertEquals(BtClass.HEADPHONES, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES))
        assertEquals(BtClass.WEARABLE_HEADSET, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET))
        assertEquals(BtClass.HANDSFREE, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE))
        assertEquals(BtClass.LOUDSPEAKER, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER))
        assertEquals(BtClass.PORTABLE_AUDIO, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO))
        assertEquals(BtClass.HIFI_AUDIO, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO))
        assertEquals(BtClass.DISPLAY_AND_LOUDSPEAKER, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER))
        assertEquals(BtClass.OTHER_AUDIO, BtClass.of(BluetoothClass.Device.AUDIO_VIDEO_MICROPHONE))
        assertEquals(BtClass.OTHER, BtClass.of(BluetoothClass.Device.PHONE_SMART))
        assertEquals(BtClass.UNKNOWN, BtClass.of(null))
        assertTrue(BtClass.CAR_AUDIO.neverPrivate && BtClass.LOUDSPEAKER.neverPrivate && BtClass.HIFI_AUDIO.neverPrivate)
        assertFalse(BtClass.HEADPHONES.neverPrivate || BtClass.HANDSFREE.neverPrivate || BtClass.UNKNOWN.neverPrivate)
        assertTrue(BtClass.CAR_AUDIO.carLike && BtClass.HANDSFREE.carLike && !BtClass.HEADPHONES.carLike)
    }

    @Test fun doNotDisturbFilters() {
        assertEquals(Dnd.OFF, Dnd.of(NotificationManager.INTERRUPTION_FILTER_ALL))
        assertEquals(Dnd.PRIORITY, Dnd.of(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertEquals(Dnd.NONE, Dnd.of(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertEquals(Dnd.ALARMS, Dnd.of(NotificationManager.INTERRUPTION_FILTER_ALARMS))
        assertEquals(Dnd.OFF, Dnd.of(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
        assertFalse(Dnd.OFF.on)
        assertTrue(Dnd.PRIORITY.on)
    }

    @Test fun addressesWithAndWithoutThePermission() {
        assertEquals("EE:FF", Addresses.tail("XX:XX:XX:XX:EE:FF"))
        assertEquals("EE:FF", Addresses.tail("aa:bb:cc:dd:ee:ff"))
        assertNull(Addresses.full("XX:XX:XX:XX:EE:FF"))
        assertEquals("AA:BB:CC:DD:EE:FF", Addresses.full("aa:bb:cc:dd:ee:ff"))
        for (bad in listOf("", "card=0;device=0", "AA:BB:CC:DD:EE", "AA-BB-CC-DD-EE-FF", "GG:BB:CC:DD:EE:FF")) {
            assertNull(bad, Addresses.tail(bad))
            assertNull(bad, Addresses.full(bad))
        }
        assertNull(Addresses.tail("XX:XX:XX:XX:XX:XX"))
        val a2dp = SeenDevice(RouteType.BT_A2DP, "Pixel Buds Pro", "XX:XX:XX:XX:EE:FF")
        val sco = SeenDevice(RouteType.BT_SCO, " pixel  buds pro", "XX:XX:XX:XX:EE:FF", sink = true)
        assertTrue(a2dp.samePhysical(sco))
        assertFalse(a2dp.samePhysical(sco.copy(address = "XX:XX:XX:XX:EE:00")))
        assertFalse(a2dp.samePhysical(sco.copy(name = "Car")))
        // Whole addresses decide when both have one, whatever the names.
        assertTrue(SeenDevice(RouteType.BT_A2DP, "Old", "AA:BB:CC:DD:EE:FF").samePhysical(SeenDevice(RouteType.BT_SCO, "New", "AA:BB:CC:DD:EE:FF")))
        assertFalse(SeenDevice(RouteType.BT_A2DP, "Same", "AA:BB:CC:DD:EE:FF").samePhysical(SeenDevice(RouteType.BT_SCO, "Same", "11:BB:CC:DD:EE:FF")))
        assertTrue(SeenDevice(RouteType.SPEAKER).samePhysical(SeenDevice(RouteType.EARPIECE)))
        assertFalse(SeenDevice(RouteType.SPEAKER).samePhysical(a2dp))
        // Never a name or address in its text.
        assertFalse(a2dp.toString().contains("Pixel") || a2dp.toString().contains("EE:FF"))
    }

    @Test fun languages() {
        assertEquals(SpokenLang.FR, SpokenLang.of("fr-CA"))
        assertEquals(SpokenLang.FR, SpokenLang.of(" FR "))
        assertEquals(SpokenLang.EN, SpokenLang.of("en-US"))
        assertEquals(SpokenLang.EN, SpokenLang.of(null))
    }
}
