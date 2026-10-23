// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import app.heyrobot.core.SkipWhy
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import app.heyrobot.assistant.RobotVoiceService
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.core.AlarmCheck
import app.heyrobot.core.AlarmEnd
import app.heyrobot.core.AlarmSignal
import app.heyrobot.core.AlarmWatch
import app.heyrobot.core.BriefItem
import app.heyrobot.core.LogThrottle
import app.heyrobot.core.MissedRun
import app.heyrobot.core.NextAlarm
import app.heyrobot.core.RLog
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineCatchUp
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineTriggers
import app.heyrobot.core.SeenAlarm
import app.heyrobot.core.TriggerEvent
import app.heyrobot.core.TriggerKind
import app.heyrobot.core.TriggerNeeds
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.output.DeviceFile
import app.heyrobot.core.output.DeviceMemory
import app.heyrobot.core.output.DevicePresence
import app.heyrobot.core.output.DeviceTriggers
import app.heyrobot.core.output.Presence
import app.heyrobot.core.output.RouteType
import app.heyrobot.core.output.SeenDevice
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Paths
import app.heyrobot.sys.RoutineAlarms
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.sys.RoutineFiles
import app.heyrobot.sys.RoutineIntents
import app.heyrobot.sys.SettingsStore
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZonedDateTime

// VOICE PROCESS (0.8). The trigger hub: in the :voice process, which runs all the time
// while Robot is the assistant, it listens only for what some enabled routine needs (TriggerNeeds, reconciled after
// every change): GrapheneOS Clock's ALARM_ALERT and ALARM_DONE (a runtime receiver, exported because Clock sends them;
// AlarmWatch tells a dismissal from a snooze and a missed alarm), the unlock (USER_PRESENT), charging starting or stopping,
// and the audio devices Android lists (remembered headsets and cars, DevicePresence). Time routines ring the one exact
// alarm (sys RoutineAlarms) at RoutineAlarmReceiver. No polling, no periodic job. Each trigger occurrence runs a routine
// once (the run ledger in state.json); a run nobody asked for goes through RoutineRunner on this hub's thread.

object RoutineHub {
    private lateinit var app: Context
    private lateinit var worker: Handler
    @Volatile private var running = false
    private var routines: List<Routine> = emptyList()
    private var rs = RoutineSettings()
    /** Registered now, by what they are for. */
    private val registered = HashMap<TriggerKind, Any>()
    /** What waits for the unlock: the rest of a brief, steps held while locked, a missed alarm's whole routine. */
    private val waits = mutableListOf<RoutineRunner.Waiting>()
    private var presence = Presence()

    @Synchronized
    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        worker = Handler(HandlerThread("robot-routines").apply { start() }.looper)
        // The receiver may start this process without the assistant (a time routine while Robot isn't it).
        VoiceLog.init(Paths.voiceLog(app))
    }

    /** Runs [block] on the hub's thread (the receiver's goAsync work). */
    fun post(ctx: Context, block: () -> Unit) {
        init(ctx)
        worker.post { try { block() } catch (t: Throwable) { RLog.w("routine_hub", t) } }
    }

    /** VoiceHub.onReady: Robot is the assistant; listen for what routines need, and catch up on what a reboot missed. */
    fun start(ctx: Context) {
        init(ctx)
        running = true
        worker.post { reload(); catchUp() }
    }

    /** VoiceHub.onShutdown: nothing listens any more. */
    fun stop() {
        running = false
        if (!::app.isInitialized) return
        worker.post { unregisterAll() }
    }

    /** Cmd.ROUTINES (Settings changed them), a routine saved or deleted by voice: read again, listen again, set the alarm again. */
    fun reload() {
        if (!::worker.isInitialized) return
        if (android.os.Looper.myLooper() != worker.looper) return run { worker.post { reload() } }
        val store = RoutineFiles.store(app)
        routines = store.all()
        rs = store.settings()
        // The ledger and the last runs forget routines that are gone.
        store.editState { it.keep(routines.map { r -> r.id }.toSet()) }
        reconcile()
        RoutineAlarms.arm(app, log = true)
    }

    /** The routines as the hub knows them now (the panel's "run my evening routine" reads them fresh from the file). */
    val all: List<Routine> get() = routines

    // ------------------------------------------------------------------ receivers, only what is needed

    private fun reconcile() {
        val need = if (running) TriggerNeeds.of(routines, waits.isNotEmpty()) else emptySet()
        for (k in registered.keys.toList()) if (k !in need) unregister(k)
        for (k in need) if (k !in registered) register(k)
    }

    private fun receiver(f: (Intent) -> Unit) = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { worker.post { try { f(i) } catch (t: Throwable) { RLog.w("routine_receiver", t) } } }
    }

    private fun register(k: TriggerKind) {
        try {
            val r: Any? = when (k) {
                // Clock's broadcasts come from another app, with no permission (GrapheneOS DeskClock): the only
                // receiver Robot registers exported. AlarmWatch needs ALERT then DONE, and a routine runs at most once a day
                // by default, so a fake DONE can only start your own routines under their gates.
                TriggerKind.ALARM -> receiver(::onClock).also {
                    // Clock's next alarm, read now and whenever it changes (a system broadcast), vouches for its ALERT (AlarmCheck).
                    seeNextAlarm()
                    nextAlarmChanged = receiver { seeNextAlarm() }.also { r ->
                        app.registerReceiver(r, IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED), null, worker, Context.RECEIVER_NOT_EXPORTED)
                    }
                    app.registerReceiver(it, IntentFilter().apply { addAction(ALARM_ALERT); addAction(ALARM_DONE) }, null, worker, Context.RECEIVER_EXPORTED)
                }
                // SystemUI sends the unlock, not the system: a non-exported receiver never gets it (ProtectedBroadcasts).
                TriggerKind.UNLOCK -> receiver { onUnlock() }.also {
                    ProtectedBroadcasts.register(app, it, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT), worker)
                }
                TriggerKind.CHARGING -> receiver { i -> fire(TriggerEvent.Power(i.action == Intent.ACTION_POWER_CONNECTED)) }.also {
                    app.registerReceiver(it, IntentFilter().apply { addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) },
                        null, worker, Context.RECEIVER_NOT_EXPORTED)
                }
                TriggerKind.DEVICE -> object : AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = devicesChanged()
                    override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = devicesChanged()
                }.also { app.getSystemService(AudioManager::class.java).registerAudioDeviceCallback(it, worker) }
                TriggerKind.TIME, TriggerKind.ASKED -> null
            }
            if (r != null) registered[k] = r
            RLog.i("routine_listen", "kind" to k, "on" to true)
        } catch (t: Throwable) {
            RLog.w("routine_register", t, "kind" to k)
        }
    }

    private fun unregister(k: TriggerKind) {
        val r = registered.remove(k) ?: return
        if (k == TriggerKind.ALARM) {
            nextAlarmChanged?.let { n -> try { app.unregisterReceiver(n) } catch (_: Throwable) {} }
            nextAlarmChanged = null
        }
        try {
            when (r) {
                is BroadcastReceiver -> app.unregisterReceiver(r)
                is AudioDeviceCallback -> app.getSystemService(AudioManager::class.java).unregisterAudioDeviceCallback(r)
            }
        } catch (_: Throwable) {}
        RLog.i("routine_listen", "kind" to k, "on" to false)
    }

    private fun unregisterAll() {
        for (k in registered.keys.toList()) unregister(k)
        worker.removeCallbacks(tick)
    }

    // ------------------------------------------------------------------ Clock's alarm

    private const val ALARM_ALERT = "com.android.deskclock.ALARM_ALERT"
    private const val ALARM_DONE = "com.android.deskclock.ALARM_DONE"

    /** Clock's next alarms as read before each ALERT (AlarmCheck): in memory, filled while an alarm routine waits. */
    private var seenAlarms: List<SeenAlarm> = emptyList()
    private var nextAlarmChanged: BroadcastReceiver? = null

    private fun seeNextAlarm() {
        seenAlarms = AlarmCheck.remember(seenAlarms, nextAlarm(), System.currentTimeMillis())
    }

    private fun onClock(i: Intent) {
        val now = System.currentTimeMillis()
        when (i.action) {
            ALARM_ALERT -> {
                val signal = AlarmSignal.Alert(now)
                // Any app can send it: only Clock's own alarm, seen due now before it came, counts; anything else is ignored
                // (never a routine, never the weather, never the once-a-day run).
                if (!AlarmCheck.admits(seenAlarms, signal)) {
                    // 1.0 (ipc-4): once a minute with a count, so a flood of fake ones can't push the real events out of the log.
                    unverified.admit(now)?.let { n ->
                        VoiceLog.add(VoiceEvent.ROUTINE_SKIPPED, SkipWhy.NOT_CLOCKS_ALARM, TriggerKind.ALARM, n)
                        RLog.i("alarm_unverified", "count" to n)
                    }
                    return
                }
                step(signal)
            }
            // Clock publishes the snoozed instance as the next alarm first: read it 2 s later (AlarmWatch.READ_DELAY_MS).
            // A DONE counts only after a counted ALERT (AlarmWatch): with nothing ringing it is dropped here, unlogged and with
            // nothing scheduled (1.0, ipc-4), and one read waits at a time.
            ALARM_DONE -> {
                if (donePending || !AlarmWatch.awaitsDone(RoutineFiles.store(app).state().alarm, now, rs.clockSilenceMinutes)) return
                donePending = true
                worker.postDelayed({
                    donePending = false
                    val next = nextAlarm()
                    seenAlarms = AlarmCheck.remember(seenAlarms, next, System.currentTimeMillis())
                    step(AlarmSignal.Done(now, next))
                }, AlarmWatch.READ_DELAY_MS)
            }
        }
    }

    /** Unverified ALERTs, logged once a minute with their count. */
    private val unverified = LogThrottle()
    /** A DONE's 2 s read is waiting (worker thread only). */
    private var donePending = false

    private fun nextAlarm(): NextAlarm? = try {
        app.getSystemService(AlarmManager::class.java).nextAlarmClock?.let { NextAlarm(it.triggerTime, it.showIntent?.creatorPackage == RoutineFacts.CLOCK) }
    } catch (_: Throwable) { null }

    private fun step(signal: AlarmSignal) {
        val store = RoutineFiles.store(app)
        val state = store.state()
        val s = AlarmWatch.on(state.alarm, signal, rs.clockSilenceMinutes)
        store.editState { it.copy(alarm = s.state) }
        // "Get ready while the alarm rings": the voice loads and the weather is fetched now (kept 15 minutes).
        if (s.prepare && TriggerNeeds.prepareOnAlert(routines, rs)) {
            VoiceHub.main.post { try { VoiceHub.tts.prewarm() } catch (_: Throwable) {} }
            BriefSource.prepare(app, SettingsStore.load(app))
        }
        val end = s.end ?: return
        val rang = s.alarmAt?.let { ((signal.at - it) / 60_000L).toInt() } ?: 0
        VoiceLog.add(VoiceEvent.ALARM_END, end, rang)
        RLog.i("alarm_end", "end" to end, "minutes" to rang)
        if ((end == AlarmEnd.DISMISSED || end == AlarmEnd.MISSED) && s.alarmAt != null) fire(TriggerEvent.Alarm(end, s.alarmAt!!))
    }

    // ------------------------------------------------------------------ audio devices

    private fun devicesChanged() {
        val memory = memory() ?: return
        val seen = try {
            app.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS).map {
                SeenDevice(RouteType.of(it.type), it.productName?.toString().orEmpty(), it.address.orEmpty(), sink = true, id = it.id)
            }
        } catch (_: Throwable) { return }
        val (p, events) = DevicePresence.onDevices(presence, memory, seen, System.currentTimeMillis())
        presence = p
        events.mapNotNull(DeviceTriggers::event).forEach(::fire)
        scheduleTick(memory)
    }

    /**
     * The headsets and cars Robot remembers (devices.json, written by headset mode once it exists). Until then there are
     * none, and a device routine waits: only a remembered device ever starts one (DevicePresence).
     */
    private fun memory(): DeviceMemory? = (DeviceMemory.decode(readOrNull(File(Paths.dir(app), "devices.json"))) as? DeviceFile.Ok)?.memory

    private val tick: Runnable = Runnable {
        val memory = memory() ?: return@Runnable
        val (p, events) = DevicePresence.tick(presence, memory, System.currentTimeMillis())
        presence = p
        events.mapNotNull(DeviceTriggers::event).forEach(::fire)
        scheduleTick(memory)
    }

    private fun scheduleTick(memory: DeviceMemory) {
        worker.removeCallbacks(tick)
        DevicePresence.nextTick(presence, memory)?.let { worker.postDelayed(tick, (it - System.currentTimeMillis()).coerceAtLeast(0) + 50) }
    }

    // ------------------------------------------------------------------ firing

    /** [event] at [now]: the routines it starts run (once per occurrence), the others are logged with why not. */
    private fun fire(event: TriggerEvent, now: ZonedDateTime = ZonedDateTime.now()) {
        val store = RoutineFiles.store(app)
        routines = store.all()
        rs = store.settings()
        val fired = RoutineTriggers.fired(routines, event, now, store.state().ledger)
        val kind = kindOf(event)
        if (fired.fire.isNotEmpty() || fired.skipped.isNotEmpty()) VoiceLog.add(VoiceEvent.ROUTINE_TRIGGER, kind, fired.fire.size, fired.skipped.size)
        for (s in fired.skipped) if (s.why != SkipWhy.OFF) VoiceLog.add(VoiceEvent.ROUTINE_SKIPPED, s.why, kind)
        for (f in fired.fire) {
            // The occurrence is recorded first: the same trigger twice (a flapping link, a second DONE) never runs it again.
            store.editState { it.copy(ledger = it.ledger.record(f.routine.id, f.key, now)) }
            when {
                f.waitUnlock -> waitForUnlock(RoutineRunner.Waiting(f.routine, kind, f.routine.steps, emptyList(), now, whole = true))
                RobotVoiceService.instance == null -> RoutineNotices.notAssistant(app, f.routine)
                else -> RoutineRunner.run(app, f.routine, kind, null)
            }
        }
    }

    private fun kindOf(e: TriggerEvent): TriggerKind = when (e) {
        is TriggerEvent.Time -> TriggerKind.TIME
        is TriggerEvent.Alarm -> TriggerKind.ALARM
        TriggerEvent.Unlocked -> TriggerKind.UNLOCK
        is TriggerEvent.Device -> TriggerKind.DEVICE
        is TriggerEvent.Power -> TriggerKind.CHARGING
    }

    /** RoutineAlarmReceiver: the exact alarm set for [slot] at [at] rang. Late after Doze: run, a Ready card, or nothing. */
    fun onTimeAlarm(slot: LocalDateTime?, at: Long) {
        val now = ZonedDateTime.now()
        if (slot != null) {
            val what = RoutineCatchUp.fired(at, now)
            val late = ((now.toInstant().toEpochMilli() - at) / 60_000L).toInt()
            if (what != MissedRun.RUN) VoiceLog.add(VoiceEvent.ROUTINE_MISSED, what, late)
            when (what) {
                MissedRun.RUN -> fire(TriggerEvent.Time(slot), now)
                MissedRun.OFFER -> offer(slot, now)
                MissedRun.DROP -> {}
            }
        }
        RoutineFiles.store(app).editState { it.copy(checked = now.toInstant().toEpochMilli()) }
        RoutineAlarms.arm(app, log = true)
    }

    /** "Your “Morning” routine didn't run at 07:00. Tap to run it now." for each routine due at [slot]. */
    private fun offer(slot: LocalDateTime, now: ZonedDateTime) {
        val store = RoutineFiles.store(app)
        val due = RoutineTriggers.fired(store.all(), TriggerEvent.Time(slot), now, store.state().ledger).fire
        for (f in due) {
            store.editState { it.copy(ledger = it.ledger.record(f.routine.id, f.key, now)) }
            store.editReady { ReadyCards.add(it, ReadyCard(f.routine.id, f.routine.name, f.routine.steps.first(), System.currentTimeMillis(), now.toLocalDate(),
                missed = slot.toLocalTime()), now.toLocalDate()) }
        }
        if (due.isNotEmpty()) RoutineNotices.ready(app, ReadyCards.live(store.ready(), now.toLocalDate()))
    }

    /**
     * After a reboot (the first unlock), a time or time zone change, or the assistant starting: the time routines whose
     * time passed unseen since the last check. Within 10 minutes they run; the same day up to 3 hours late, a Ready card;
     * older, nothing (never a run for each missed day).
     */
    fun catchUp() {
        val store = RoutineFiles.store(app)
        val now = ZonedDateTime.now()
        val state = store.state()
        if (state.checked > 0 && running) {
            val since = Instant.ofEpochMilli(state.checked).atZone(now.zone)
            for (late in RoutineCatchUp.late(store.all(), since, now, state.ledger)) {
                VoiceLog.add(VoiceEvent.ROUTINE_MISSED, late.what, ((now.toInstant().toEpochMilli() - late.due.toInstant().toEpochMilli()) / 60_000L).toInt())
                when (late.what) {
                    MissedRun.RUN -> fire(TriggerEvent.Time(late.slot), now)
                    MissedRun.OFFER -> offer(late.slot, now)
                    MissedRun.DROP -> {}
                }
            }
        }
        store.editState { it.copy(checked = now.toInstant().toEpochMilli()) }
    }

    /** Boot, the time or the time zone changed, Robot was updated: the alarm again, and what was missed meanwhile. */
    fun onClockChanged() {
        reload()
        if (running) catchUp()
    }

    // ------------------------------------------------------------------ the unlock

    /** Keeps [w] for the unlock, together with whatever its routine already left waiting. */
    fun waitForUnlock(w: RoutineRunner.Waiting) {
        if (!::worker.isInitialized) return
        if (android.os.Looper.myLooper() != worker.looper) return run { worker.post { waitForUnlock(w) } }
        val i = waits.indexOfFirst { it.routine.id == w.routine.id && !it.whole && !w.whole }
        if (i >= 0) {
            val old = waits[i]
            waits[i] = RoutineRunner.Waiting(old.routine, old.kind, old.steps + w.steps, old.parts + w.parts, old.heldAt)
        } else {
            waits += w
        }
        reconcile()
    }

    private fun onUnlock() {
        val now = ZonedDateTime.now()
        val due = waits.filter { RoutineGate.resumes(it.heldAt, now, rs.finishOnUnlock, it.whole) }
        waits.clear()
        reconcile()
        for (w in due) {
            if (RobotVoiceService.instance == null) continue
            if (w.whole) RoutineRunner.run(app, w.routine, w.kind, null)
            else RoutineRunner.run(app, w.routine, w.kind, null, steps = w.steps, before = w.parts, resumed = true)
        }
        fire(TriggerEvent.Unlocked, now)
    }

    /** RoutineVoice: a run's speech ended. What OutputGuard kept waits for the unlock; the brief's reminders are read. */
    fun spoken(ctx: Context, r: Routine, kind: TriggerKind, s: RoutineVoice.Session) {
        init(ctx)
        val held = s.held.toList()
        val reminders = BriefItem.REMINDERS in s.saidItems
        worker.post {
            if (held.isNotEmpty() && !s.stopped) waitForUnlock(RoutineRunner.Waiting(r, kind, emptyList(), held, ZonedDateTime.now()))
            if (reminders) BriefSource.remindersSaid(ctx)
        }
    }

    // ------------------------------------------------------------------ taps

    /** A Ready notification's tap: the panel opens with the cards ([key]: one, or every one). */
    fun openReady(key: String) {
        val vis = RobotVoiceService.instance ?: return
        VoiceHub.main.post {
            vis.show(RobotVoiceService.SRC_APP, Bundle().apply { putString(RoutineIntents.ARG_READY, key) })
        }
    }
}
