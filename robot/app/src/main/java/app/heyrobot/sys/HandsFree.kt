package app.heyrobot.sys

import android.Manifest
import android.app.NotificationManager
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import app.heyrobot.core.RLog
import app.heyrobot.core.headset.ButtonHandler
import app.heyrobot.core.headset.HeadsetEntry
import app.heyrobot.core.headset.HeadsetSetup
import app.heyrobot.core.output.Addresses
import app.heyrobot.core.output.BtClass
import app.heyrobot.core.output.HandsFreeStore
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice

// Main and voice processes (0.8). Headset and car mode's files are core HandsFreeStore's, in files/robot next to
// settings.json (which stays schema 13), changed under a lock both processes take: the main app's Settings › Headset and
// Settings › Car mode, and the :voice process (a headset's first use, your answer to "Is it headphones only you can
// hear?"). The :voice classes are named here as strings: the main process never refers to the assistant's classes.

object HandsFreeFiles {
    fun store(ctx: Context): HandsFreeStore = HandsFreeStore(Paths.dir(ctx))

    /** [MAIN] Settings › Headset or Car mode changed something: :voice reads it again, and the button follows its switch. */
    fun changed(ctx: Context) {
        Cmd.send(ctx, Cmd.HANDSFREE)
        HeadsetButton.apply(ctx, store(ctx).headset().settings.buttonOpensRobot)
    }
}

/**
 * The headset button's trampoline (design §3.4 "Headset button opens Robot": off disables it, so other apps get the
 * three intents), and who answers them now (Assistant setup's row; Robot sees the other handlers through its <queries>
 * for these three actions only).
 */
object HeadsetButton {
    const val ENTRY = "app.heyrobot.assistant.headset.HeadsetEntryActivity"

    fun apply(ctx: Context, on: Boolean) {
        try {
            val c = ComponentName(ctx.packageName, ENTRY)
            val want = if (on) PackageManager.COMPONENT_ENABLED_STATE_DEFAULT else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (ctx.packageManager.getComponentEnabledSetting(c) != want) ctx.packageManager.setComponentEnabledSetting(c, want, PackageManager.DONT_KILL_APP)
        } catch (t: Throwable) {
            RLog.w("headset_button", t)
        }
    }

    /** Which app each of the three intents goes to now (PackageManager.resolveActivity, MATCH_DEFAULT_ONLY). */
    fun handlers(ctx: Context): Map<String, ButtonHandler> = HeadsetEntry.ACTIONS.associateWith { action ->
        try {
            val r = ctx.packageManager.resolveActivity(Intent(action).addCategory(Intent.CATEGORY_DEFAULT), PackageManager.MATCH_DEFAULT_ONLY)
            HeadsetSetup.handler(r?.activityInfo?.packageName, r?.activityInfo?.name, ctx.packageName)
        } catch (_: Throwable) {
            ButtonHandler.NONE
        }
    }
}

/** What Android lists about sound devices, as core's plain snapshots (output AudioRoute). */
object AudioDevices {
    /** BLUETOOTH_CONNECT ("Nearby devices"), asked only from Settings › Headset's or Car mode's row: whole addresses and classes. */
    fun exactBluetooth(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)

    /** [d] as core sees it; its Bluetooth class only with the permission. */
    fun seen(ctx: Context, d: AudioDeviceInfo, exact: Boolean = exactBluetooth(ctx)): SeenDevice {
        val type = RouteType.of(d.type)
        val address = try { d.address.orEmpty() } catch (_: Throwable) { "" }
        val cls = if (exact && type.bluetooth) btClass(ctx, address) else BtClass.UNKNOWN
        return SeenDevice(type, d.productName?.toString().orEmpty(), address, cls, sink = d.isSink, id = d.id)
    }

    @Suppress("MissingPermission") // Only called with BLUETOOTH_CONNECT granted ([exactBluetooth]).
    private fun btClass(ctx: Context, address: String): BtClass = try {
        val full = Addresses.full(address) ?: return BtClass.UNKNOWN
        BtClass.of(ctx.getSystemService(BluetoothManager::class.java)?.adapter?.getRemoteDevice(full)?.bluetoothClass?.deviceClass)
    } catch (_: Throwable) {
        BtClass.UNKNOWN
    }

    /** Every output and input Android lists now. */
    fun all(ctx: Context): List<SeenDevice> = try {
        val am = ctx.getSystemService(AudioManager::class.java)
        val exact = exactBluetooth(ctx)
        am.getDevices(AudioManager.GET_DEVICES_ALL).map { seen(ctx, it, exact) }
    } catch (_: Throwable) {
        emptyList()
    }

    /** The AudioDeviceInfo behind [s] (its id), while it is still connected. */
    fun info(ctx: Context, s: SeenDevice, outputs: Boolean = s.sink): AudioDeviceInfo? = try {
        ctx.getSystemService(AudioManager::class.java).getDevices(if (outputs) AudioManager.GET_DEVICES_OUTPUTS else AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id == s.id }
    } catch (_: Throwable) {
        null
    }
}

/** The optional permissions and accesses headset and car mode ask for, each only from its own settings row. */
data class HandsFreeFacts(
    /** "Nearby devices": tells the headset Robot listens, and knows devices exactly. */
    val bluetooth: Boolean,
    /** The Phone permission: "Call directly after a spoken yes". */
    val callPhone: Boolean,
    /** Do Not Disturb access: the "Driving (Robot)" rule. */
    val dndAccess: Boolean,
    /** Organic Maps is installed: navigation opens its route preview. */
    val organicMaps: Boolean,
    /** Who answers the headset button now. */
    val buttonHandlers: Map<String, ButtonHandler>,
) {
    companion object {
        const val ORGANIC_MAPS = "app.organicmaps"

        fun read(ctx: Context): HandsFreeFacts = HandsFreeFacts(
            bluetooth = AudioDevices.exactBluetooth(ctx),
            callPhone = Assistant.granted(ctx, Manifest.permission.CALL_PHONE),
            dndAccess = try { ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted } catch (_: Throwable) { false },
            organicMaps = try { ctx.packageManager.getPackageInfo(ORGANIC_MAPS, 0); true } catch (_: Throwable) { false },
            buttonHandlers = HeadsetButton.handlers(ctx),
        )
    }
}
