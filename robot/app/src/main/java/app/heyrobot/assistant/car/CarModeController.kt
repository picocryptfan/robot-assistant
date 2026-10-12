package app.heyrobot.assistant.car

import app.heyrobot.core.output.SpokenLang
import app.heyrobot.core.Playback
import app.heyrobot.core.headset.EntryKind
import app.heyrobot.core.output.SessionKind
import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import app.heyrobot.sys.ProtectedBroadcasts
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.service.notification.Condition
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.RoutineSpeakingService
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.car.CarEffect
import app.heyrobot.core.car.CarInput
import app.heyrobot.core.car.CarModeMachine
import app.heyrobot.core.car.CarNavigate
import app.heyrobot.core.car.CarPhase
import app.heyrobot.core.car.CarSettings
import app.heyrobot.core.car.CarState
import app.heyrobot.core.car.NavDestination
import app.heyrobot.core.car.NavPlan
import app.heyrobot.core.output.DevicePresence
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.Presence
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.voice.SessionRoute

// VOICE PROCESS (0.8). Car mode (design §4.6, §4.8 CarModeController): core's CarModeMachine decides, this carries it out.
// Your car connecting (the device list, while car mode is on in Settings and a car is added) starts it in under a
// second; it ends 30 s after the car goes. On: the speaking service in the foreground with the car's notification
// (routines' RoutineSpeakingService, mediaPlayback: car mode speaks with the screen off for the whole drive), "Car mode
// on." through the car, the car screen if the screen is on, the trusted session after one unlock in the car (in memory
// only: a :voice restart ends it), Do Not Disturb's "Driving (Robot)" rule only with that switch and its access. Off:
// "From your drive: n things" if something was kept for when you park. Main thread.

object CarModeController {
    private val app: Context get() = VoiceHub.app
    private val main get() = VoiceHub.main

    var state = CarState(); private set
    private var presence = Presence()
    private var running = false
    private var unlock: BroadcastReceiver? = null
    /** Screens showing car mode (CarModeActivity), told when it changes. */
    private val watchers = mutableListOf<() -> Unit>()

    val on: Boolean get() = state.on

    /** Car functions work on the lock screen now (the trusted session). */
    val trusted: Boolean get() = state.trustedAt(System.currentTimeMillis())

    /** Car mode listens for your car: on in Settings with a car added, or running. */
    val wantsDevices: Boolean get() = running && HandsFreeHub.car.on && (CarSettings.hasCar(HandsFreeHub.memory) || state.phase != CarPhase.OFF)

    private val settings: CarSettings get() = HandsFreeFiles.store(app).carSettings()

    val END_TEXT = Line("Ending car mode.", "Fin du mode auto.")

    /** HandsFreeHub.reload: Settings changed (car mode turned off ends it), and the devices are read again. */
    fun reload() {
        running = true
        if (!HandsFreeHub.car.on && state.phase != CarPhase.OFF) input(CarInput.End(now()))
        devicesChanged()
        reconcile()
    }

    fun stop() {
        if (state.phase != CarPhase.OFF) input(CarInput.End(now()))
        running = false
        presence = Presence()
        reconcile()
    }

    /** "Car mode", "I'm driving", the notification's Start, the car screen. */
    fun start() = input(CarInput.Start(now()))

    /** "End car mode", the notification's End, the car screen's End. */
    fun end() = input(CarInput.End(now()))

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }

    private fun now() = System.currentTimeMillis()

    // ------------------------------------------------------------------ your car

    /** The device list changed: remembered cars connecting or gone (car mode counts its own grace). */
    fun devicesChanged() {
        if (!running || !HandsFreeHub.car.on) return
        val seen = HandsFreeHub.devices().filter { it.sink }
        val (p, events) = DevicePresence.onDevices(presence, HandsFreeHub.memory, seen, now()) { 0L }
        presence = p
        for (e in events) {
            if (e.device.role != DeviceRole.CAR) continue
            VoiceLog.add(VoiceEvent.DEVICE_PRESENCE, if (e.connected) 1 else 0, e.device.number, 2)
            input(if (e.connected) CarInput.CarSeen(e.device, e.at) else CarInput.CarGone(e.device, e.at))
        }
    }

    /** The car this car mode is for, as Android lists it now (its call entry first), or null. */
    fun carSeen(): SeenDevice? {
        if (!on) return null
        val car = state.car?.let { HandsFreeHub.memory.byNumber(it) } ?: return null
        return HandsFreeHub.devices().filter { it.sink && HandsFreeHub.memory.find(it, DeviceRole.CAR)?.number == car.number }
            .sortedBy { if (it.type == RouteType.BT_SCO) 0 else 1 }.firstOrNull()
    }

    // ------------------------------------------------------------------ the machine

    fun input(i: CarInput) {
        val (next, fx) = CarModeMachine.on(state, i, settings)
        state = next
        for (e in fx) try { apply(e) } catch (t: Throwable) { RLog.w("car_effect", t, "effect" to RLog.Code(e.javaClass.simpleName.lowercase())) }
        reconcile()
        HandsFreeHub.reconcile()
        watchers.toList().forEach { it() }
    }

    private fun apply(e: CarEffect) {
        when (e) {
            CarEffect.StartService -> if (!RoutineSpeakingService.startCar(app)) input(CarInput.StartRefused(now()))
            CarEffect.StopService -> RoutineSpeakingService.carEnded()
            is CarEffect.Notify -> CarNotices.startNotice(app)
            is CarEffect.Say -> CarVoice.say(e.line.get(HandsFreeHub.lang))
            CarEffect.ShowCarScreen -> if (screenOn()) openScreen()
            is CarEffect.CarModeFlag -> if (!e.on) HandsFreeHub.session?.takeIf { it.kind == SessionKind.CAR }?.cancel()
            is CarEffect.SetDnd -> CarDnd.set(app, e.on)
            is CarEffect.AndroidCarMode -> try {
                val ui = app.getSystemService(UiModeManager::class.java)
                if (e.on) ui.enableCarMode(0) else ui.disableCarMode(0)
            } catch (t: Throwable) { RLog.w("car_ui_mode", t) }
            is CarEffect.Trusted -> {
                VoiceLog.add(VoiceEvent.CAR_TRUSTED, if (e.on) 1 else 0, e.why.id)
                RoutineSpeakingService.refreshCar()
            }
            CarEffect.ParkNotice -> CarNotices.parked(app)
            is CarEffect.Logged -> VoiceLog.add(VoiceEvent.CAR_MODE, if (e.on) 1 else 0, e.why.id)
            is CarEffect.WakeAt -> main.postDelayed({ input(CarInput.Tick(now())) }, (e.at - now()).coerceAtLeast(0) + 50)
        }
    }

    /** RoutineSpeakingService is in the foreground with car mode's notification. */
    fun serviceStarted() {
        if (state.phase == CarPhase.CONNECTED) input(CarInput.Started(now()))
    }

    /** Android refused the service from the background: "Car connected · Start car mode" waits for your tap. */
    fun serviceRefused() {
        if (state.phase == CarPhase.CONNECTED) input(CarInput.StartRefused(now()))
    }

    /** Something was kept for when you park: the notification's line counts it. */
    fun parkChanged() = RoutineSpeakingService.refreshCar()

    private fun screenOn(): Boolean = try { app.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { false }

    fun openScreen() {
        try {
            app.startActivity(Intent().setClassName(app.packageName, CarModeActivity::class.java.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) {
            RLog.w("car_screen", t)
        }
    }

    // ------------------------------------------------------------------ the unlock (trusted session)

    private fun reconcile() {
        val want = running && state.on
        if (want && unlock == null) {
            unlock = object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) = input(CarInput.Unlocked(now()))
            }.also { ProtectedBroadcasts.register(app, it, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT), main) }
        } else if (!want && unlock != null) {
            try { app.unregisterReceiver(unlock) } catch (_: Throwable) {}
            unlock = null
        }
    }

    // ------------------------------------------------------------------ the car screen's buttons

    /** Talk (or Call: "Who should I call?" first): the panel opens for the car's session, heard through the car. */
    fun talk(call: Boolean) {
        RobotVoiceService.instance?.show(RobotVoiceService.SRC_HEADSET, Bundle().apply {
            putString(HandsFreeHub.ARG_ENTRY, EntryKind.BVRA.name)
            putBoolean(PanelHandsFree.ARG_CALL, call)
        })
    }

    /** Navigate home: Organic Maps' route preview to your saved home, from the screen (no question needed). */
    fun navigateHome(ctx: Context): String {
        val lang = HandsFreeHub.lang
        val here = null as Pair<Double, Double>?
        val plan = CarNavigate.plan(NavDestination.Home, settings, here, HandsFreeHub.phone().locked, trusted, lang)
        VoiceLog.add(VoiceEvent.CAR_NAV, plan.kind)
        if (plan is NavPlan.Route) {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(plan.uri))) } catch (t: Throwable) { RLog.w("car_navigate", t) }
        }
        CarVoice.say(plan.say)
        return plan.say
    }
}

/**
 * What car mode says by itself ("Car mode on."): through the car as a call when "Speak through the car as a call" is on
 * and the car's call route is there (cars mute their radio for it), released as soon as it is said; else as usual.
 */
object CarVoice {
    private val owner = Any()
    private var held = false

    fun say(text: String) {
        val app = VoiceHub.app
        // A session already routes Robot's voice.
        if (HandsFreeHub.session == null && !held && HandsFreeHub.car.settings.speakAsCall) {
            val sco = CarModeController.carSeen()?.takeIf { it.type == RouteType.BT_SCO }?.let { AudioDevices.info(app, it, outputs = true) }
            if (sco != null && SessionRoute.take(app, owner, sco)) {
                held = true
                VoiceHub.tts.setPlayback(Playback.CALL)
                VoiceHub.main.postDelayed(release, 400)
            }
        }
        VoiceHub.tts.speak(text)
    }

    private val release: Runnable = object : Runnable {
        override fun run() {
            if (!held) return
            if (VoiceHub.tts.isSpeaking) return run { VoiceHub.main.postDelayed(this, 300) }
            held = false
            SessionRoute.release(VoiceHub.app, owner)
            VoiceHub.tts.setPlayback(VoiceHub.settings.playback)
        }
    }
}

/**
 * "Do Not Disturb while driving" (off, design §4.5 flow 5): Robot's own "Driving (Robot)" rule of TYPE_DRIVING, made
 * once and switched with car mode; your own override wins, as Android does. Only with Do Not Disturb access, which you
 * grant from Settings › Car mode's row; without it nothing happens.
 */
object CarDnd {
    private const val PREF = "car_dnd_rule"

    fun set(ctx: Context, on: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ok = try {
            // The driving rule's type and builder are Android 15's (Robot targets Android 17 phones).
            if (!nm.isNotificationPolicyAccessGranted || android.os.Build.VERSION.SDK_INT < 35) false else {
                val id = ruleId(ctx, nm)
                nm.setAutomaticZenRuleState(id, Condition(condition(ctx), "Driving", if (on) Condition.STATE_TRUE else Condition.STATE_FALSE))
                true
            }
        } catch (t: Throwable) {
            RLog.w("car_dnd", t)
            false
        }
        VoiceLog.add(VoiceEvent.CAR_DND, if (on) 1 else 0, if (ok) 1 else 0)
    }

    /** The rule's condition, named after Robot's application ID (ai.robot_app since 1.0). */
    private fun condition(ctx: Context): Uri = Uri.parse("condition://${ctx.packageName}/driving")

    @android.annotation.SuppressLint("NewApi") // Called only on Android 15 and later (set).
    private fun ruleId(ctx: Context, nm: NotificationManager): String {
        val prefs = ctx.getSharedPreferences("car", Context.MODE_PRIVATE)
        prefs.getString(PREF, null)?.takeIf { nm.getAutomaticZenRule(it) != null }?.let { return it }
        val name = if (HandsFreeHub.lang == SpokenLang.FR) "Conduite (Robot)" else "Driving (Robot)"
        val rule = AutomaticZenRule.Builder(name, condition(ctx))
            .setType(AutomaticZenRule.TYPE_DRIVING)
            .setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            .setConfigurationActivity(ComponentName(ctx.packageName, "app.heyrobot.ui.MainActivity"))
            .build()
        val id = nm.addAutomaticZenRule(rule)
        prefs.edit().putString(PREF, id).apply()
        return id
    }
}
