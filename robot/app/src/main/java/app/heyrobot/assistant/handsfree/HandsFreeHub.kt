// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.handsfree

import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.headset.HeadsetSession
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.headset.EntryDecision
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.headset.EntryCaller
import app.heyrobot.core.headset.EntryReferrer
import app.heyrobot.core.headset.HeadsetEntry
import app.heyrobot.core.output.CarFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.HeadsetFile
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.output.SpokenLang
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.voice.SessionRoute
import java.util.Locale

// VOICE PROCESS (0.8). What headset mode and car mode share in :voice (design §1.1, §3.6, §4.8): their files read once
// and again after Settings changes them (Cmd.HANDSFREE), the headset button's start (HeadsetEntryActivity), the one
// session at a time (a headset's, or the car's), and the system callbacks they need, registered only while needed: the
// audio devices (a session's headset going, your car coming and going while car mode is on), ACTION_AUDIO_BECOMING_NOISY
// and the audio mode (another call) while a session runs. Nothing polls. Main thread.

object HandsFreeHub {
    private val app: Context get() = VoiceHub.app
    private val main get() = VoiceHub.main

    @Volatile var headset = HeadsetFile(); private set
    @Volatile var car = CarFile(); private set
    @Volatile var memory = DeviceMemory(); private set
    /** The session running now (a headset's or the car's), or null. */
    var session: HeadsetSession? = null; private set
    @Volatile private var running = false

    /** The language hands-free lines are said in: the phone's (English, or Québec French). */
    val lang: SpokenLang get() = SpokenLang.of(Locale.getDefault().toLanguageTag())

    /** VoiceHub.onReady: Robot is the assistant; car mode listens for your car if it is on. */
    fun start() {
        running = true
        reload()
    }

    /** VoiceHub.onShutdown: any session ends, car mode stops listening. */
    fun stop() {
        running = false
        session?.cancel()
        CarModeController.stop()
        reconcile()
    }

    /** Cmd.HANDSFREE, or a change of its own: the files again, and car mode with them. */
    fun reload() {
        val s = HandsFreeFiles.store(app)
        headset = s.headset()
        car = s.car()
        memory = s.devices()
        if (running) CarModeController.reload()
        reconcile()
    }

    /** [change] devices.json under the lock (a headset's first use, your private answer); the memory here follows. */
    fun editDevices(change: (DeviceMemory) -> DeviceMemory) {
        HandsFreeFiles.store(app).editDevices(change)?.let { memory = it }
    }

    // ------------------------------------------------------------------ the phone

    /** Another app's call or a phone call is on (the mode is not Robot's own call audio or session route). */
    fun callActive(): Boolean {
        val mode = try { app.getSystemService(AudioManager::class.java).mode } catch (_: Throwable) { AudioManager.MODE_NORMAL }
        return when (mode) {
            AudioManager.MODE_NORMAL -> false
            AudioManager.MODE_IN_COMMUNICATION -> !SessionRoute.held
            else -> true
        }
    }

    fun phone(): PhoneState {
        val am = app.getSystemService(AudioManager::class.java)
        return PhoneState(
            locked = Assistant.locked(app),
            screenOn = try { app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { true },
            callActive = callActive(),
            mediaPlaying = try { am.isMusicActive } catch (_: Throwable) { false },
        )
    }

    fun devices(): List<SeenDevice> = AudioDevices.all(app)

    // ------------------------------------------------------------------ the button

    /** BVRA's acknowledgement is due within this long of the button (HeadsetService's wait). */
    private var bvraAt = 0L

    /**
     * HeadsetEntryActivity: a headset's button (design §3.6). Robot isn't the running assistant: a toast says so.
     * During a phone call nothing happens: the call's audio is never touched. Otherwise the panel opens for a headset
     * session (or the car's, with car mode on), and the headset is told Robot listens when that is allowed.
     */
    fun onButton(a: Activity, intent: Intent) {
        val ctx = a.applicationContext
        // 1.0.1: who started it, as Android recorded it (core EntryCaller). A start that names its own referrer could name
        // anyone, so it is never read; Activity.getReferrer gives Android's own only without those extras.
        val claimed = try { intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME) } catch (_: Throwable) { true }
        val from = if (claimed) null else try { a.referrer?.takeIf { it.scheme == "android-app" }?.host } catch (_: Throwable) { null }
        val fromOwnUid = try { a.launchedFromUid == android.os.Process.myUid() } catch (_: Throwable) { false }
        val trusted = EntryCaller.trusted(from, claimed, ctx.packageName, fromOwnUid)
        val referrer = if (claimed) EntryReferrer.OTHER else EntryReferrer.of(from)
        val locked = Assistant.locked(ctx)
        val secure = intent.getBooleanExtra(HeadsetEntry.EXTRA_SECURE, false)
        val vis = RobotVoiceService.instance
        val file = HandsFreeFiles.store(ctx).headset()
        val mayAck = file.settings.answerVoiceButton && AudioDevices.exactBluetooth(ctx)
        val d = HeadsetEntry.decide(intent.action, secure, locked, vis != null, file.settings.buttonOpensRobot, CarModeController.on, mayAck, trusted)
        RLog.i("headset_entry", "decision" to RLog.Code(d.javaClass.simpleName.lowercase()), "referrer" to referrer)
        when (d) {
            EntryDecision.Ignore, EntryDecision.NotTrusted -> return
            EntryDecision.NotAssistant -> {
                android.widget.Toast.makeText(ctx, HeadsetEntry.NOT_ASSISTANT, android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            is EntryDecision.Show -> {
                VoiceLog.add(VoiceEvent.HEADSET_ENTRY, d.kind, if (d.locked) 1 else 0, if (d.car) 1 else 0)
                // During a call, the button does nothing to it (and Robot doesn't open over it).
                if (callActive()) return
                bvraAt = SystemClock.elapsedRealtime()
                if (d.ackBvra) ackVoiceRecognition(ctx, buttonDevice(intent))
                vis?.show(RobotVoiceService.SRC_HEADSET, Bundle().apply {
                    putString(ARG_ENTRY, d.kind.name)
                    putBoolean(ARG_LOCKED, d.locked)
                })
            }
        }
    }

    /** The headset whose voice button sent [intent] (BluetoothDevice.EXTRA_DEVICE), when the intent says. */
    private fun buttonDevice(intent: Intent): BluetoothDevice? = try {
        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
    } catch (_: Throwable) { null }

    /**
     * "Answer the headset's voice button properly" (with Nearby devices): BluetoothHeadset.startVoiceRecognition within
     * HeadsetService's 5 s, so it gets OK instead of an error tone, for the headset that sent the button ([from], when its
     * intent named it and it is still connected), else the first connected one (H0 notes which with two headsets).
     */
    @Suppress("MissingPermission") // Only with BLUETOOTH_CONNECT granted (mayAck).
    private fun ackVoiceRecognition(ctx: Context, from: BluetoothDevice?) {
        try {
            val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter ?: return
            adapter.getProfileProxy(ctx, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val h = proxy as BluetoothHeadset
                    val ok = try {
                        val connected = h.connectedDevices
                        val target = from?.takeIf { f -> connected.any { it.address == f.address } } ?: connected.firstOrNull()
                        target?.let { h.startVoiceRecognition(it) } == true
                    } catch (_: Throwable) { false }
                    VoiceLog.add(VoiceEvent.BVRA_ACK, if (ok) 1 else 0, (SystemClock.elapsedRealtime() - bvraAt).toInt())
                    try { adapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy) } catch (_: Throwable) {}
                }
                override fun onServiceDisconnected(profile: Int) {}
            }, BluetoothProfile.HEADSET)
        } catch (t: Throwable) {
            RLog.w("bvra_ack", t)
        }
    }

    // ------------------------------------------------------------------ sessions

    /** PanelHandsFree: the panel opened for [entry] (or "hey robot" in car mode): one session at a time. */
    fun begin(entry: EntryKind, locked: Boolean, wake: Boolean): HeadsetSession {
        session?.cancel()
        val s = HeadsetSession(entry, locked, wake, CarModeController.carSeen())
        session = s
        reconcile()
        s.begin()
        return s
    }

    /** The session ended (by itself, or cancelled). */
    fun ended(s: HeadsetSession) {
        if (session === s) session = null
        reconcile()
    }

    // ------------------------------------------------------------------ callbacks, only while needed

    private var deviceCb: AudioDeviceCallback? = null
    private var noisy: BroadcastReceiver? = null
    private var modeListener: AudioManager.OnModeChangedListener? = null

    /** Registers what a session or car mode needs, and nothing else. */
    fun reconcile() {
        val am = try { app.getSystemService(AudioManager::class.java) } catch (_: Throwable) { return }
        val wantDevices = running && (session != null || CarModeController.wantsDevices)
        if (wantDevices && deviceCb == null) {
            deviceCb = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = devicesChanged(emptyList())
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) =
                    devicesChanged(removed.orEmpty().map { AudioDevices.seen(app, it) })
            }.also { try { am.registerAudioDeviceCallback(it, main) } catch (t: Throwable) { RLog.w("handsfree_devices", t) } }
        } else if (!wantDevices && deviceCb != null) {
            try { am.unregisterAudioDeviceCallback(deviceCb) } catch (_: Throwable) {}
            deviceCb = null
        }
        val wantSession = running && session != null
        if (wantSession && noisy == null) {
            noisy = object : BroadcastReceiver() {
                // The headset is about to go: sound would move to the phone's speaker. Stop at once (OutputGuard rule 3).
                override fun onReceive(c: Context, i: Intent) { session?.deviceGone() }
            }.also { try { app.registerReceiver(it, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), null, main, Context.RECEIVER_NOT_EXPORTED) } catch (t: Throwable) { RLog.w("handsfree_noisy", t) } }
            modeListener = AudioManager.OnModeChangedListener { if (callActive()) session?.callStarted() }
                .also { try { am.addOnModeChangedListener(app.mainExecutor, it) } catch (t: Throwable) { RLog.w("handsfree_mode", t) } }
        } else if (!wantSession && noisy != null) {
            try { app.unregisterReceiver(noisy) } catch (_: Throwable) {}
            noisy = null
            modeListener?.let { try { am.removeOnModeChangedListener(it) } catch (_: Throwable) {} }
            modeListener = null
        }
    }

    /** Android's device list changed ([removed]: what went, when it did): the session's headset, and your car. */
    private fun devicesChanged(removed: List<SeenDevice>) {
        session?.let { s -> if (removed.any { s.isSessionDevice(it) }) s.deviceGone() }
        CarModeController.devicesChanged()
    }

    const val ARG_ENTRY = "robot_headset_entry"
    const val ARG_LOCKED = "robot_headset_locked"
}
