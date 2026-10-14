package app.heyrobot.core.output

import java.util.Locale

// Pure. What Android reports about where sound goes, as plain data, so the rules about where personal speech may play (OutputGuard), which headset or car is which (DeviceMemory)
// and how a headset session routes its microphone (headset RoutePlanner) are decided and tested here, on the JVM. The
// :voice process turns AudioDeviceInfo, BluetoothClass, KeyguardManager, PowerManager, AudioManager.getMode and
// NotificationManager.getCurrentInterruptionFilter into these snapshots; nothing here calls Android.
//
// Platform facts this model rests on (VERIFIED in AOSP android-17.0.0_r1 unless marked, design §0):
// - Without BLUETOOTH_CONNECT, a Bluetooth device's address comes as "XX:XX:XX:XX:EE:FF": only the last two bytes are
//   real. The product name is not hidden (UNVERIFIED that it is the Bluetooth name on this phone: check H0.4).
// - One physical headset shows as several devices: A2DP (music, no microphone) and SCO (call audio with its microphone),
//   or an LE Audio headset (TYPE_BLE_HEADSET, microphone only for VOICE_COMMUNICATION).
// - Android Auto is absent: GrapheneOS offers it only through sandboxed Google Play, and only Play apps with Android Auto
//   support show in it. A car is an ordinary Bluetooth device (HFP and A2DP); BluetoothClass says "car audio" only with
//   BLUETOOTH_CONNECT.

/** The kind of device a sound plays on (or is captured from), folded from AudioDeviceInfo.getType to what the rules need. */
enum class RouteType(
    /** The AudioDeviceInfo.TYPE_* values it stands for (stable public API numbers; OutputRouteTest checks them). */
    val androidTypes: Set<Int>,
    val family: DeviceFamily,
    /** It has a microphone Robot can listen with (A2DP and plain headphones have none). */
    val hasMic: Boolean,
    /**
     * Private by its type alone: true (worn: wired or USB headsets, the earpiece, hearing aids), false (heard by the
     * room: the phone's speaker, a Bluetooth or LE speaker, line out, HDMI, a dock, an LE broadcast anyone can join), or
     * null (a Bluetooth headset or an unknown USB device: you are asked once, DeviceMemory keeps the answer).
     */
    val privateByType: Boolean?,
) {
    SPEAKER(setOf(2, 24), DeviceFamily.BUILTIN, false, false),
    EARPIECE(setOf(1), DeviceFamily.BUILTIN, false, true),
    BUILTIN_MIC(setOf(15), DeviceFamily.BUILTIN, true, null),
    WIRED_HEADSET(setOf(3), DeviceFamily.WIRED, true, true),
    WIRED_HEADPHONES(setOf(4), DeviceFamily.WIRED, false, true),
    USB_HEADSET(setOf(22), DeviceFamily.USB, true, true),
    /** A USB device or accessory that isn't a headset: a DAC, a dock with speakers. */
    USB_OTHER(setOf(11, 12), DeviceFamily.USB, false, null),
    BT_A2DP(setOf(8), DeviceFamily.BLUETOOTH, false, null),
    BT_SCO(setOf(7), DeviceFamily.BLUETOOTH, true, null),
    BLE_HEADSET(setOf(26), DeviceFamily.BLUETOOTH, true, null),
    BLE_SPEAKER(setOf(27), DeviceFamily.BLUETOOTH, false, false),
    BLE_BROADCAST(setOf(30), DeviceFamily.BLUETOOTH, false, false),
    /** ASHA hearing aids: worn, so private (the design names wired and earpiece only; hearing aids are as personal). */
    HEARING_AID(setOf(23), DeviceFamily.BLUETOOTH, false, true),
    /** Line out, HDMI (ARC, eARC), docks, aux: heard by the room. */
    LINE(setOf(5, 6, 9, 10, 13, 19, 29, 31), DeviceFamily.OTHER, false, false),
    OTHER(emptySet(), DeviceFamily.OTHER, false, false);

    val bluetooth: Boolean get() = family == DeviceFamily.BLUETOOTH

    /** It can be the communication device a headset session sets (setCommunicationDevice: SCO, LE headset, wired, USB). */
    val communication: Boolean get() = this == BT_SCO || this == BLE_HEADSET || this == WIRED_HEADSET || this == USB_HEADSET

    companion object {
        /** The kind for an AudioDeviceInfo.getType value; an unknown number (a newer Android) is [OTHER]: never private. */
        fun of(androidType: Int): RouteType = entries.firstOrNull { androidType in it.androidTypes } ?: OTHER
    }
}

/** Which identity a device has: Bluetooth (address tail and name), wired, USB, the phone itself. */
enum class DeviceFamily(val id: String) {
    BLUETOOTH("bluetooth"), WIRED("wired"), USB("usb"), BUILTIN("builtin"), OTHER("other");

    companion object {
        fun of(id: String?): DeviceFamily? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A Bluetooth device's class (BluetoothClass.getDeviceClass), known only with BLUETOOTH_CONNECT: [UNKNOWN] without it.
 * It never makes a device private by itself (you confirm headphones once); it can make one never private (a speaker or a
 * car) and suggests "Is this your car?".
 */
enum class BtClass(val deviceClass: Int?) {
    UNKNOWN(null),
    WEARABLE_HEADSET(0x0404),
    /** Hands-free kits: most are cars, some are headsets. Asked, never assumed. */
    HANDSFREE(0x0408),
    LOUDSPEAKER(0x0414),
    HEADPHONES(0x0418),
    PORTABLE_AUDIO(0x041C),
    CAR_AUDIO(0x0420),
    HIFI_AUDIO(0x0428),
    DISPLAY_AND_LOUDSPEAKER(0x043C),
    /** Another audio or video class (a microphone, a set-top box, a camera). */
    OTHER_AUDIO(null),
    /** Not an audio device at all. */
    OTHER(null);

    /** Heard by the room or a car: never private, never asked. */
    val neverPrivate: Boolean get() = this == LOUDSPEAKER || this == CAR_AUDIO || this == HIFI_AUDIO || this == DISPLAY_AND_LOUDSPEAKER

    /** Likely a car's head unit: car setup offers it first. */
    val carLike: Boolean get() = this == CAR_AUDIO || this == HANDSFREE

    companion object {
        /** BluetoothClass.Device.Major.AUDIO_VIDEO. */
        private const val AUDIO_VIDEO = 0x0400

        /** The class for BluetoothClass.getDeviceClass(); null (no permission, not Bluetooth) is [UNKNOWN]. */
        fun of(deviceClass: Int?): BtClass {
            if (deviceClass == null) return UNKNOWN
            entries.firstOrNull { it.deviceClass == deviceClass }?.let { return it }
            return if (deviceClass and 0x1F00 == AUDIO_VIDEO) OTHER_AUDIO else OTHER
        }
    }
}

/**
 * One device as Android listed it (AudioDeviceInfo, plus BluetoothClass when Robot may read it). [name]: getProductName.
 * [address]: getAddress, "" when it has none; "XX:XX:XX:XX:EE:FF" without BLUETOOTH_CONNECT. [sink]: an output (false: an
 * input). [id]: AudioDeviceInfo.getId, valid only while the device stays connected. [toString] leaves the name and the
 * address out, so a device can't reach a log line by accident.
 */
data class SeenDevice(
    val type: RouteType,
    val name: String = "",
    val address: String = "",
    val btClass: BtClass = BtClass.UNKNOWN,
    val sink: Boolean = true,
    val id: Int = 0,
) {
    val family: DeviceFamily get() = type.family

    /** The two real bytes at the end of a Bluetooth address ("EE:FF"), or null without a MAC-shaped address. */
    val tail: String? get() = Addresses.tail(address)

    /** The whole address when Android gave it (BLUETOOTH_CONNECT), else null. */
    val fullAddress: String? get() = Addresses.full(address)

    /**
     * The same physical device as [other]: the same family and, for Bluetooth, the same whole address when both have one,
     * else the same tail and the same name (A2DP and SCO of one headset). Wired and USB: the same family and name. The
     * phone's own speaker, earpiece and microphones are one device.
     */
    fun samePhysical(other: SeenDevice): Boolean {
        if (family != other.family) return false
        return when (family) {
            DeviceFamily.BUILTIN -> true
            DeviceFamily.BLUETOOTH -> {
                val a = fullAddress
                val b = other.fullAddress
                if (a != null && b != null) a == b else tail != null && tail == other.tail && Addresses.sameName(name, other.name)
            }
            else -> Addresses.sameName(name, other.name)
        }
    }

    override fun toString(): String = "SeenDevice(type=$type, class=$btClass, sink=$sink, tail=${tail != null}, full=${fullAddress != null})"
}

/** Bluetooth addresses as Android gives them, with or without BLUETOOTH_CONNECT. */
object Addresses {
    private val MAC = Regex("^[0-9A-Fa-fXx]{2}(:[0-9A-Fa-fXx]{2}){5}$")
    private val HEX = Regex("^[0-9A-Fa-f]{2}$")

    /** "EE:FF" (upper case) from "XX:XX:XX:XX:EE:FF" or a whole address; null when it isn't one. */
    fun tail(address: String): String? {
        val a = address.trim()
        if (!MAC.matches(a)) return null
        val parts = a.uppercase(Locale.ROOT).split(':')
        if (!HEX.matches(parts[4]) || !HEX.matches(parts[5])) return null
        return parts[4] + ":" + parts[5]
    }

    /** The whole address (upper case) when none of it is hidden, else null. */
    fun full(address: String): String? {
        val a = address.trim()
        if (!MAC.matches(a) || a.contains('X') || a.contains('x')) return null
        return a.uppercase(Locale.ROOT)
    }

    /** Names compared as you would read them: case and spaces aside. */
    fun sameName(a: String, b: String): Boolean = norm(a) == norm(b)

    fun norm(s: String): String = s.trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)
}

/**
 * Do Not Disturb as NotificationManager.getCurrentInterruptionFilter reports it: 1 all (off), 2 priority, 3 none,
 * 4 alarms; 0 (unknown) reads as off.
 */
enum class Dnd(val filter: Int) {
    OFF(1), PRIORITY(2), NONE(3), ALARMS(4);

    val on: Boolean get() = this != OFF

    companion object {
        fun of(filter: Int): Dnd = entries.firstOrNull { it.filter == filter } ?: OFF
    }
}

/**
 * The phone around a sentence: [locked] (KeyguardManager.isKeyguardLocked; a secure lock), [screenOn], [callActive]
 * (a phone or another app's call: AudioManager's mode is IN_CALL or IN_COMMUNICATION and Robot doesn't own it),
 * [mediaPlaying] (another app's music or podcast: AudioManager.isMusicActive), [dnd], and [robotDrivingRule]: the Do Not
 * Disturb that is on is Robot's own "Driving (Robot)" rule.
 */
data class PhoneState(
    val locked: Boolean,
    val screenOn: Boolean = true,
    val callActive: Boolean = false,
    val mediaPlaying: Boolean = false,
    val dnd: Dnd = Dnd.OFF,
    val robotDrivingRule: Boolean = false,
)

/**
 * Everything at one moment: the devices connected now ([devices], outputs and inputs), where Robot's sound would play
 * ([routed]: AudioTrack.getRoutedDevice, or the default output before a track exists), the communication device if one
 * is set ([communication]), whether Robot holds BLUETOOTH_CONNECT ([exactBluetooth]: whole addresses and classes), and the
 * phone's state.
 */
data class AudioSnapshot(
    val devices: List<SeenDevice>,
    val routed: SeenDevice?,
    val phone: PhoneState,
    val communication: SeenDevice? = null,
    val exactBluetooth: Boolean = false,
) {
    val outputs: List<SeenDevice> get() = devices.filter { it.sink }
    val inputs: List<SeenDevice> get() = devices.filter { !it.sink }
}
