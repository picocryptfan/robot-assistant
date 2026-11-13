// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.core.car.CarBottomSaid
import app.heyrobot.core.car.CarHalvesPhrase
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.PanelController
import app.heyrobot.core.car.CarGate
import app.heyrobot.core.car.CarTurnEnd
import app.heyrobot.core.car.CarBeside
import app.heyrobot.core.car.CarTurn
import app.heyrobot.core.car.CarWhy
import app.heyrobot.lock.LockSignals
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Lang
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
import app.heyrobot.core.car.CarOpen
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.output.spoken
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.MapsApps
import app.heyrobot.core.output.DevicePresence
import app.heyrobot.core.output.DeviceRole
import app.heyrobot.core.output.Line
import app.heyrobot.core.output.Presence
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.AudioDevices
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.voice.SessionRoute

/** How long after a show a session must exist, and how long Android may take to open one. EST. */
private const val QUIET_CHECK_MS = 2_000L
private const val SHOW_CHECK_MS = 1_500L
/** The panel waits for the reply's last words: this often, at most this many times. EST. */
private const val CLOSE_STEP_MS = 300L
private const val CLOSE_TRIES = 200

// VOICE PROCESS (0.8). Car mode: core's CarModeMachine decides, this carries it out.
// Your car connecting (the device list, while car mode is on in Settings and a car is added) starts it in under a
// second; it ends 30 s after the car goes. On: the speaking service in the foreground with the car's notification
// (routines' RoutineSpeakingService, mediaPlayback: car mode speaks with the screen off for the whole drive), "Car mode
// on." through the car, the car screen if the screen is on, the trusted session after one unlock in the car (in memory
// only: a :voice restart ends it), Do Not Disturb's "Driving (Robot)" rule only with that switch and its access. Nothing
// waits for when you park (1.0): what needs the screen opens as it would outside the car. Main thread.

object CarModeController {
    private val app: Context get() = VoiceHub.app
    private val main get() = VoiceHub.main

    var state = CarState(); private set
    private var presence = Presence()
    private var running = false
    private var unlock: BroadcastReceiver? = null
    private var leftoversCleared = false
    /** Screens showing car mode (CarModeActivity), told when it changes. */
    private val watchers = mutableListOf<() -> Unit>()

    val on: Boolean get() = state.on

    /** Robot opened a route since car mode came on: the car screen shows "Back to directions" (handoff-car-media-maps). */
    @Volatile var routeThisDrive = false; private set

    /** A route opened in your maps app (the car screen, "navigate home", a tool's route): remembered for this drive only. */
    fun routeOpened() {
        if (on) lastBeside = CarBeside.Last.Map
        if (!on || routeThisDrive) return
        routeThisDrive = true
        main.post { watchers.toList().forEach { it() } }
    }

    /** Car functions work on the lock screen now (the trusted session). */
    val trusted: Boolean get() = CarGate.trustedNow(state.trustedAt(System.currentTimeMillis()), PhoneLockNow.someoneElse(app))

    /** Car mode listens for your car: on in Settings with a car added, or running. */
    val wantsDevices: Boolean get() = running && HandsFreeHub.car.on && (CarSettings.hasCar(HandsFreeHub.memory) || state.phase != CarPhase.OFF)

    private val settings: CarSettings get() = HandsFreeFiles.store(app).carSettings()

    val END_TEXT = Line(HandsFreeStrings.endingCar())

    /** HandsFreeHub.reload: Settings changed (car mode turned off ends it), and the devices are read again. */
    fun reload() {
        running = true
        if (!leftoversCleared) {
            leftoversCleared = true
            for (e in CarModeMachine.leftovers(state)) try { apply(e) } catch (t: Throwable) { RLog.w("car_effect", t) }
        }
        if (!HandsFreeHub.car.on && state.phase != CarPhase.OFF) input(CarInput.End(now()))
        devicesChanged()
        reconcile()
        if (on) VoiceHub.listenTuning()
    }

    fun stop() {
        if (state.phase != CarPhase.OFF) input(CarInput.End(now()))
        running = false
        presence = Presence()
        reconcile()
    }

    /** "Car mode", "I'm driving", the notification's Start, the car screen. */
    fun start() = input(CarInput.Start(now()))

    /** The app's Car mode button and its shortcut (1.2.4): car mode for no car, until you end it. */
    fun startByHand() = input(CarInput.Start(now(), byHand = true))

    /** "End car mode", the notification's End, the car screen's End. */
    fun end() = input(CarInput.End(now()))

    fun watch(w: () -> Unit) { watchers += w }
    fun unwatch(w: () -> Unit) { watchers -= w }

    private fun now() = System.currentTimeMillis()

    private const val UI_MODE_PREF = "android_car_mode_on"

    // ------------------------------------------------------------------ your car

    /** The device list changed: remembered cars connecting or gone (car mode counts its own grace). */
    fun devicesChanged() {
        if (!running || !HandsFreeHub.car.on) return
        tickIfDue()
        CarModeMachine.forgotten(state, HandsFreeHub.memory, now()).forEach { input(it) }
        val seen = HandsFreeHub.devices().filter { it.sink }
        val (p, events) = DevicePresence.onDevices(presence, HandsFreeHub.memory, seen, now()) { 0L }
        presence = p
        for (e in events) {
            if (e.device.role != DeviceRole.CAR) continue
            VoiceLog.add(VoiceEvent.DEVICE_PRESENCE, if (e.connected) 1 else 0, e.device.number, 2)
            input(if (e.connected) CarInput.CarSeen(e.device, e.at) else CarInput.CarGone(e.device, e.at))
        }
    }

    /** A timer that slept through its moment runs now: the screen came on, or a device changed. */
    private fun tickIfDue() {
        if (CarModeMachine.due(state, now())) input(CarInput.Tick(now()))
    }

    /** The car this car mode is for, as Android lists it now (its call entry first), or null. */
    fun carSeen(): SeenDevice? {
        if (!on) return null
        val car = state.car?.let { HandsFreeHub.memory.byNumber(it) } ?: return null
        return HandsFreeHub.devices().filter { it.sink && HandsFreeHub.memory.find(it, DeviceRole.CAR)?.number == car.number }
            .sortedBy { if (it.type == RouteType.BT_SCO) 0 else 1 }.firstOrNull()
    }

    /** The app last put beside the car screen this drive: the screen's way back to your map follows it. */
    @Volatile var lastBeside: CarBeside.Last? = null; private set
    /** Apps car mode saw playing this drive: only these and your music app may take the tile with a paused song. */
    val playedThisDrive: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Apps Robot put beside the car screen this drive, oldest first: the Maps button's swap picks the one last used. */
    val besideThisDrive: MutableList<String> = java.util.concurrent.CopyOnWriteArrayList()

    fun besideOpened(last: CarBeside.Last) {
        if (!on) return
        lastBeside = last
        if (last is CarBeside.Last.App) { besideThisDrive.remove(last.pkg); besideThisDrive.add(last.pkg) }
        main.post { watchers.toList().forEach { it() } }
    }

    /** The car screen is in front or split: Talk, the car's button and "hey robot" open no panel (handoff item 3). */
    @Volatile var screenUp = false
    /** This show has no panel, so the car screen shows its lines. */
    @Volatile var quietTurn = false; private set
    /** Android didn't take a show without a window this drive: the panel opens, and closes when the reply has been spoken. */
    @Volatile private var windowNeeded = false

    /**
     * Asked as the session prepares a show from [source] (null for anything not Robot's own): whether it has no panel.
     * [trusted]: Robot's own show or the system's (ShowGate.userShow). fix-car-stay: with the car screen up, any of them, the
     * screen capture's aside (its crop needs the panel) and the lock screen's button (fix-car-stay-b: its panel
     * waits for a tap on the mic, as in 1.3.0); with no car or headset the phone's mic hears it (CarTurn.listensOnPhone).
     */
    fun prepareShow(source: String?, trusted: Boolean): Boolean {
        quietTurn = CarTurn.quiet(on, screenUp, trusted && source != RobotVoiceService.SRC_CAPTURE && source != RobotVoiceService.SRC_KEYGUARD, windowNeeded)
        notifyWatchers()
        return quietTurn
    }

    /** The session opened with no panel but no question came of it (nothing to wait for): it closes, and the mic with it. */
    fun quietShown() {
        listenHeld = 0
        if (quietTurn) main.postDelayed({ quietCheck() }, QUIET_CHECK_MS)
    }

    /** fix-car-stay-b: idle polls in a row held only for a listen Robot said would come (CarTurnEnd.listenHeld). */
    private var listenHeld = 0

    /** fix-car-stay: a turn with no panel the phone's mic hears stays while it listens, thinks, asks or speaks (CarTurnEnd.quietDone). */
    private fun quietCheck() {
        if (!quietTurn) return
        val s = PanelController.state.value
        // fix-car-stay-b: idle isn't done while a card waits for a yes or Robot is about to listen for an answer; fix-car-stay-c:
        // nor while a reply waits out its 3 seconds or is on its way (closing would leave it unsent).
        val idle = s.phase == Phase.IDLE
        val speaking = VoiceHub.tts.isSpeaking
        listenHeld = CarTurnEnd.listenHeld(listenHeld, PanelController.listenComing, idle, speaking)
        val waits = CarTurnLines.of(s)?.ui?.ask != null || CarTurnEnd.listenHolds(listenHeld) || CarTurnLines.sending(s)
        if (CarTurnEnd.quietDone(quietTurn, PanelHandsFree.active, idle, speaking, waits)) PanelController.hideAfterAnnouncement()
        else main.postDelayed({ quietCheck() }, QUIET_CHECK_MS)
    }

    /** fix-car-stay: the car screen's own opener while it's started (CarModeActivity): an app Robot opens goes beside it. */
    @Volatile var besideOpener: ((Intent) -> Boolean)? = null

    /** Opens [i] beside the car screen while it's up (CarSplitOpen); null when it isn't, and the caller opens it as before. */
    fun openBeside(i: Intent): Boolean? = if (on && screenUp) besideOpener?.invoke(i) else null

    @Volatile private var refusedSplit = false

    /** Android put the app over the car screen, not beside it (core CarSplit.covered): the car screen comes back and says so. */
    fun splitRefused() {
        refusedSplit = true
        main.post { openScreen() }
    }

    /** Whether the car screen has that to say (once). */
    fun takeRefusedSplit(): Boolean = refusedSplit.also { refusedSplit = false }

    fun sessionHidden() {
        privateTurn = false
        if (!quietTurn) return
        quietTurn = false
        notifyWatchers()
    }

    /** A private answer is on show in this turn: the car screen draws it, so it takes the session's FLAG_SECURE too. */
    @Volatile var privateTurn = false; private set

    fun secure(on: Boolean) {
        privateTurn = on
        notifyWatchers()
    }

    /** Android said it couldn't show a session: from now on this drive it has a window. [again] shows it. */
    fun showFailed(source: String?, again: () -> Unit) {
        val fromCar = source == RobotVoiceService.SRC_HEADSET || source == RobotVoiceService.SRC_WAKE
        if (!on || !screenUp || !fromCar || windowNeeded) return
        windowNeeded = true
        quietTurn = false
        again()
    }

    /** The question is over: with the car screen up the panel goes once the reply has been spoken, window or none. */
    fun turnEnded() {
        if (CarTurnEnd.closes(quietTurn, on, screenUp)) main.post { closeWhenSaid(CLOSE_TRIES) }
    }

    /** Whether a question heard through a session ([session]) or not leaves a text box that stays (CarTurnEnd.boxStays). */
    fun boxStays(session: Boolean): Boolean = CarTurnEnd.boxStays(quietTurn, on, screenUp, session)

    private fun closeWhenSaid(tries: Int, held: Boolean = false) {
        // fix-car-stay-d: closing in a reply's 3 seconds would leave it unsent and nothing said (onHide, stopWindow).
        // fix-car-stay-g: what held it goes on (held), so a question said in the reply's 3 seconds is answered first.
        val sending = CarTurnLines.sending(PanelController.state.value)
        if (CarTurnEnd.closeWaits(VoiceHub.tts.isSpeaking, sending, tries)) main.postDelayed({ closeWhenSaid(tries - 1, held || sending) }, CLOSE_STEP_MS)
        else if (!PanelHandsFree.active) PanelController.hideAfterAnnouncement(held = held)
    }

    private fun notifyWatchers() { main.post { watchers.toList().forEach { it() } } }

    // ------------------------------------------------------------------ the machine

    fun input(i: CarInput) {
        val (next, fx) = CarModeMachine.on(state, i, settings)
        state = next
        if (!next.on) { routeThisDrive = false; lastBeside = null; playedThisDrive.clear(); besideThisDrive.clear(); windowNeeded = false }
        for (e in fx) try { apply(e) } catch (t: Throwable) { RLog.w("car_effect", t, "effect" to RLog.Code(e.javaClass.simpleName.lowercase())) }
        reconcile()
        HandsFreeHub.reconcile()
        watchers.toList().forEach { it() }
    }

    private fun apply(e: CarEffect) {
        when (e) {
            CarEffect.StartService -> if (!RoutineSpeakingService.startCar(app)) input(CarInput.StartRefused(now()))
            CarEffect.StopService -> {
                RoutineSpeakingService.carEnded()
                CarNotices.ended(app)
            }
            is CarEffect.Notify -> CarNotices.startNotice(app)
            is CarEffect.Say -> CarVoice.say(e.line.get(HandsFreeHub.lang))
            CarEffect.ShowCarScreen -> if (screenOn()) openScreen()
            is CarEffect.CarModeFlag -> {
                if (!e.on) HandsFreeHub.session?.takeIf { it.kind == SessionKind.CAR }?.cancel()
                VoiceHub.listenTuning()
            }
            is CarEffect.SetDnd -> CarDnd.set(app, e.on)
            is CarEffect.AndroidCarMode -> try {
                val ui = app.getSystemService(UiModeManager::class.java)
                // Off only if Robot turned it on: another app's Android car mode isn't Robot's to end.
                val prefs = app.getSharedPreferences("car", Context.MODE_PRIVATE)
                if (e.on) { ui.enableCarMode(0); prefs.edit().putBoolean(UI_MODE_PREF, true).apply() }
                else if (prefs.getBoolean(UI_MODE_PREF, false)) { ui.disableCarMode(0); prefs.edit().remove(UI_MODE_PREF).apply() }
            } catch (t: Throwable) { RLog.w("car_ui_mode", t) }
            is CarEffect.Trusted -> {
                VoiceLog.add(VoiceEvent.CAR_TRUSTED, if (e.on) 1 else 0, e.why.id)
                RoutineSpeakingService.refreshCar()
            }
            is CarEffect.Logged -> {
                VoiceLog.add(VoiceEvent.CAR_MODE, if (e.on) 1 else 0, e.why.id)
                // 1.1, Phone lock (§9.3): only car mode your remembered car started counts, never one you asked for.
                LockSignals.car(app, e.on, e.on && state.startedBy == CarWhy.CAR_CONNECTED && state.car != null)
            }
            CarEffect.ClearLockCar -> LockSignals.car(app, false, false)
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
                override fun onReceive(c: Context, i: Intent) {
                    tickIfDue()
                    if (i.action == Intent.ACTION_USER_PRESENT) input(CarInput.Unlocked(now()))
                }
            }.also { ProtectedBroadcasts.register(app, it, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON), main) }
        } else if (!want && unlock != null) {
            try { app.unregisterReceiver(unlock) } catch (_: Throwable) {}
            unlock = null
        }
    }

    // ------------------------------------------------------------------ the car screen's buttons

    /** Talk: the car's session, heard through the car, with no panel while the car screen is up. */
    fun talk() {
        showTalk()
        // Android may refuse a session with no window and not say so (below API 34's callback): then it gets one.
        main.postDelayed({
            if (on && screenUp && !windowNeeded && !PanelController.shown) { windowNeeded = true; showTalk() }
        }, SHOW_CHECK_MS)
    }

    private fun showTalk() {
        RobotVoiceService.instance?.show(RobotVoiceService.SRC_HEADSET, Bundle().apply { putString(HandsFreeHub.ARG_ENTRY, EntryKind.BVRA.name) })
    }

    /** Navigate home: Organic Maps' route preview to your saved home, from the screen (no question needed). */
    /**
     * The car screen's Navigate home, from [here] (the position the screen found first, or null) in your maps app, beside
     * the car screen when Android allows it. Phone lock first, as the spoken one: only when every active lock has Maps on.
     * Off the main thread (it reads files and package signers).
     */
    /** The car screen's answer to "swap the bottom" and its kin, while it's up (set from onStart to onStop). */
    @Volatile var bottomScreen: ((CarHalvesPhrase) -> CarBottomSaid)? = null

    fun bottom(p: CarHalvesPhrase): CarBottomSaid =
        bottomScreen?.invoke(p) ?: CarBottomSaid.NOT_SHOWN

    fun navigateHome(ctx: Context, here: Pair<Double, Double>?): String {
        val lang = HandsFreeHub.lang
        val s = settings
        val locked = Assistant.keyguard(ctx)
        val lockOn = PhoneLockNow.on(ctx)
        if (CarOpen.maps(locked, trusted, lockOn, lockOn && PhoneLockNow.mapsInCar(ctx)) == CarOpen.Gate.PHONE_LOCK) {
            val no = spoken(LockStrings.appClosed(), lang)
            CarVoice.say(no)
            return no
        }
        val plan = CarNavigate.plan(NavDestination.Home, s, here, locked, trusted, lang)
        VoiceLog.add(VoiceEvent.CAR_NAV, plan.kind)
        val home = s.home
        if (plan is NavPlan.Route && home != null) {
            val said = when (val o = MapsApps.open(ctx, MapsApps.route(ctx, here, CarNavigate.HERE.get(lang), home.lat to home.lon, home.label), launch = { CarSplitOpen.start(ctx, it) })) {
                MapsApps.Opened.Done -> { routeOpened(); plan.say }
                is MapsApps.Opened.Repick -> spoken(MapsApps.replacedText(), lang)
                MapsApps.Opened.None -> spoken(HandsFreeStrings.noOrganicMaps(), lang)
            }
            CarVoice.say(said)
            return said
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
            // A headset session that began meanwhile set its own playback.
            if (HandsFreeHub.session == null) VoiceHub.tts.setPlayback(VoiceHub.settings.playback)
        }
    }
}

/**
 * "Do Not Disturb while driving" (off): Robot's own "Driving (Robot)" rule of TYPE_DRIVING, made
 * once and switched with car mode; your own override wins, as Android does. Only with Do Not Disturb access, which you
 * grant from Settings › Car mode's row; without it nothing happens.
 */
object CarDnd {
    private const val PREF = "car_dnd_rule"

    fun set(ctx: Context, on: Boolean) {
        // Off with no rule made yet: nothing to turn off, and no rule to make for it.
        if (!on && ctx.getSharedPreferences("car", Context.MODE_PRIVATE).getString(PREF, null) == null) return
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
        // Android's Do Not Disturb settings list it: the menus' language.
        val name = HandsFreeStrings.drivingRobot().render(HandsFreeHub.screen)
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
