// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.telecom.TelecomManager
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.Clocks
import app.heyrobot.core.lock.EndEarlyRules
import app.heyrobot.core.lock.GateFacts
import app.heyrobot.core.lock.LockCodeRules
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.lock.RecordEvent
import app.heyrobot.core.lock.RecordLine
import app.heyrobot.core.lock.ScreenNow
import app.heyrobot.core.lock.ScreenTime
import java.time.Instant
import java.time.ZonedDateTime

// :LOCK PROCESS ONLY. The lock engine: the single writer of state.json. It works out which locks are on
// from the stored facts (core LockSchedule) whenever something may have changed: the exact alarm, a change in Settings,
// boot, a time or zone change, the screen going on or off, the service connecting. Between those, window events use the
// last [Snapshot] until its [Snapshot.validUntil] passes. Nothing polls. It keeps the one exact alarm (LockAlarms) and the
// notices (LockNotices), and tells the service whether to ask for window events at all.

object LockEngine {
    /** What the engine last worked out. [file]: the usable locks (null: Phone lock off or unreadable). */
    data class Snapshot(val file: LockFile?, val state: LockState, val clocks: Clocks, val validUntil: Long, val opensAt: ZonedDateTime?) {
        val active: Boolean get() = state.active.isNotEmpty()
        /** End early's rules: the file's, or with a lock held while locks.json can't be read, the safe ones (LockSchedule.heldSettings). */
        val settings: app.heyrobot.core.lock.LockSettings? get() = file?.settings ?: if (active) LockSchedule.heldSettings(state) else null
    }

    @Volatile var snapshot: Snapshot? = null
        private set

    /** The cover's Emergency call fell back to the dialer: it stays open until then (§6). */
    @Volatile var dialerOpenUntil = 0L

    /** This process has refreshed once (the first refresh notes a restart's gap). */
    @Volatile private var started = false

    /** "Phone lock couldn't save its state" is up (state.json couldn't be written). */
    @Volatile private var unsavedShown = false

    private val listeners = mutableSetOf<() -> Unit>()

    fun watch(l: () -> Unit) = synchronized(listeners) { listeners += l }
    fun unwatch(l: () -> Unit) = synchronized(listeners) { listeners -= l }

    fun clocks(ctx: Context) = LockStore.clocks(ctx)

    /** The snapshot, refreshed first when its time has passed. */
    fun current(ctx: Context): Snapshot {
        val s = snapshot
        return if (s == null || System.currentTimeMillis() >= s.validUntil) refresh(ctx) else s
    }

    /**
     * Works the state out again and writes it: locks that came on or went off are recorded, the alarm is set again, the
     * notices follow. [alive]: also note that :lock is alive now (screen off, the 10-minute mark). [voiceEnd]: phase B, a
     * lock by voice until then (wall-clock ms), after you tapped Lock on LockRouter's card.
     */
    @Synchronized
    fun refresh(ctx: Context, alive: Boolean = false, unlocked: Boolean = false, startedNow: Boolean = false, voiceEnd: Long? = null): Snapshot {
        val c = clocks(ctx)
        val read = LockStore.read(ctx)
        val file = read.ok?.takeIf { it.settings.on }
        // F22: locks.json can't be read, which says nothing about the locks on: they stay until their own ends, and none starts.
        val hold = read.ok == null
        val access = LockAccess.enabled(ctx)
        var started0: List<ActiveLock> = emptyList()
        var metApps: List<ActiveLock> = emptyList()
        var longer: ActiveLock? = null
        val first = !started
        started = true
        val screen = screen(ctx)
        val state = try {
            LockStore.editState(ctx) { st0 ->
                var st = st0
                if (first && st.active.isNotEmpty()) st = st.recording(*gap(st, c))
                val r = LockSchedule.refresh(file, st, c, hold)
                started0 = r.started
                st = r.state
                // Phase B: a lock by voice starts like any other lock, or makes the one on longer; one that would end it
                // sooner changes nothing (LockSchedule.startVoice).
                if (voiceEnd != null) {
                    val before = LockSchedule.voiceOn(st, c)
                    LockSchedule.startVoice(file, st, c, Instant.ofEpochMilli(voiceEnd).atZone(c.now.zone))?.takeIf { it != st }?.let { v ->
                        val a = v.active.first { it.voice }
                        if (before == null) started0 = started0 + a else longer = a
                        st = v
                    }
                }
                // Phase B: a missed alarm's locks start at the first unlock; One app first's time in its app; Daily limit.
                val u = if (unlocked) LockSchedule.onUnlock(file, st, c) else null
                if (u != null) { st = u.state; started0 = started0 + u.started }
                val apps = countApps(ctx, file, st, c, screen)
                st = apps.state
                metApps = apps.ended
                val lim = startLimits(ctx, file, st, c, screen)
                st = lim.state
                started0 = started0 + lim.started
                val lines = started0.map { RecordLine(c.nowMs, if (access) RecordEvent.STARTED else RecordEvent.COULD_NOT_START, it.lock) } +
                    r.ended.map { RecordLine(c.nowMs, RecordEvent.ENDED, it.lock) } +
                    apps.ended.map { a -> RecordLine(c.nowMs, RecordEvent.ENDED_APP, a.lock, ((file?.lock(a.lock)?.rule as? app.heyrobot.core.lock.LockRule.App)?.minutes)) } +
                    listOfNotNull(longer?.let { a -> RecordLine(c.nowMs, RecordEvent.LONGER, ActiveLock.VOICE, ((a.end - c.nowMs + 59_999) / 60_000).toInt()) })
                st = st.recording(*lines.toTypedArray())
                if (st.edit in 1..c.nowMs) st = st.copy(edit = 0)
                if (st.active.isNotEmpty() && (alive || c.nowMs - st.last.alive >= ALIVE_MS)) st = st.copy(last = st.last.copy(alive = c.nowMs))
                st
            }
        } catch (t: Throwable) {
            RLog.w("lock_refresh", t)
            // Never an older or an empty state while there is one to read: the newest this process knows (one it couldn't
            // write yet, or state.json) worked out again; the last snapshot only when state.json can't be read.
            try { LockStore.readStateOrNull(ctx)?.let { LockSchedule.refresh(file, it, c, hold).state } } catch (_: Throwable) { null }
                ?: snapshot?.state ?: LockSchedule.refresh(file, LockState(), c, hold).state
        }
        // state.json couldn't be written (storage full): the state above is kept in :lock, every refresh writes it again
        // (at least once a minute while windows change), and a notice says so until a write works.
        val unsaved = LockStore.unsavedState
        if (first || unsaved != unsavedShown) {
            LockNotices.unsaved(ctx, unsaved)
            unsavedShown = unsaved
        }
        val next = LockSchedule.nextAlarm(file, state, c)
        LockAlarms.arm(ctx, next)
        val until = next?.toInstant()?.toEpochMilli() ?: Long.MAX_VALUE
        val snap = Snapshot(file, state, c, if (unsaved) minOf(until, c.nowMs + RETRY_MS) else until, LockSchedule.timedOpensAt(file, state, c))
        snapshot = snap
        // F139: the list widget says "locked" during a lock: drawn again when one starts or ends (by the main process).
        // fb13: and :voice looks at Robot to Robot's notices as a lock ends, with the screen still unlocked: an answer taken off the shade goes back up.
        if (!snap.active && widgetsActive == true) app.heyrobot.sys.Cmd.send(ctx, app.heyrobot.sys.Cmd.LINK_DESK)
        if (snap.active != widgetsActive) { widgetsActive = snap.active; redrawWidgets(ctx) }
        LockNotices.update(ctx, snap, access, couldNotStart = if (access) emptyList() else started0)
        for (a in metApps) LockNotices.appDone(ctx, file, a)
        LockAccessibilityService.instance?.onEngine(snap, startedNow = startedNow || started0.isNotEmpty())
        // FA8g: nothing records behind the cover. A conversation session in :voice stops (it checks state.json itself).
        if (started0.isNotEmpty() || startedNow) app.heyrobot.sys.Cmd.send(ctx, app.heyrobot.sys.Cmd.PHONE_LOCK)
        // F116: a Robot to Robot card or answer already in the shade goes as the lock starts, from here so it goes even with :voice down.
        if (started0.isNotEmpty()) app.heyrobot.sys.LinkShade.hide(ctx)
        if (started0.isNotEmpty() && file?.settings?.forWho == LockFor.SOMEONE_ELSE) app.heyrobot.sys.LockShade.hide(ctx)
        timeCheck(ctx, snap, screen)
        synchronized(listeners) { listeners.toList() }.forEach { it() }
        return snap
    }

    @Volatile private var widgetsActive: Boolean? = null

    private fun redrawWidgets(ctx: Context) = try {
        val widget = ComponentName(ctx.packageName, LockSignals.LIST_WIDGET)
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(widget)
        // today-fix-c 8: the providers take only the system's broadcasts (BIND_APPWIDGET), so Robot's own redraws go to the
        // receivers that aren't exported, in the main process, which draw through AppWidgetManager.
        if (ids.isNotEmpty()) ctx.sendBroadcast(Intent(LockSignals.LIST_REDRAW_ACTION).setComponent(ComponentName(ctx.packageName, LockSignals.LIST_REDRAW)))
        // Today H9: always, since its lock screen summary must go at a phone lock's start even with no widget placed.
        ctx.sendBroadcast(Intent().setComponent(ComponentName(ctx.packageName, LockSignals.TODAY_REFRESH)))
        Unit
    } catch (t: Throwable) { RLog.w("lock_widgets", t) }

    /**
     * The first refresh in this process during a lock: how long Robot wasn't running (§3). After a restart, from the
     * shutdown (or the last sign of life) to the boot; without a restart, "Phone lock restarted".
     */
    private fun gap(st: LockState, c: Clocks): Array<RecordLine> {
        val bootAt = c.nowMs - c.elapsed
        val last = maxOf(st.last.shutdown, st.last.alive)
        return when {
            last <= 0 -> emptyArray()
            last < bootAt -> {
                val minutes = ((bootAt - last) / 60_000L).toInt()
                if (minutes >= 2) arrayOf(RecordLine(c.nowMs, RecordEvent.GAP, null, minutes)) else emptyArray()
            }
            else -> arrayOf(RecordLine(c.nowMs, RecordEvent.RESTARTED))
        }
    }

    /** ACTION_SHUTDOWN: the phone is turning off (a restart's gap starts here). */
    fun shutdown(ctx: Context) {
        try { LockStore.editState(ctx) { it.copy(last = it.last.copy(shutdown = System.currentTimeMillis())) } } catch (_: Throwable) {}
    }

    /**
     * Phase B (§9.3): :voice says Robot's car mode started or ended. For the lock only car mode your remembered car started
     * counts ([byCar]); car mode you asked for changes nothing there, but keeps App pause away (M1). The lock's time keeps
     * running, and the record notes car mode during a lock.
     */
    fun car(ctx: Context, on: Boolean, byCar: Boolean) {
        val now = System.currentTimeMillis()
        LockStore.editState(ctx) { it.withCar(on, byCar, now) }
        refresh(ctx)
    }

    /** review-develop-14-b L1: BOOT_COMPLETED; car mode from before the boot no longer keeps App pause away. */
    fun rebooted(ctx: Context) {
        LockStore.editState(ctx) { it.afterReboot(System.currentTimeMillis() - SystemClock.elapsedRealtime()) }
        refresh(ctx)
    }

    /** End early under a wait (the cover's button). */
    fun startWait(ctx: Context) {
        val s = current(ctx)
        val settings = s.settings ?: return
        LockStore.editState(ctx) { EndEarlyRules.startWait(settings, it, System.currentTimeMillis()) }
        refresh(ctx)
    }

    /** End the lock now, once the wait is over. False when it isn't allowed now. */
    fun endAfterWait(ctx: Context): Boolean {
        val s = current(ctx)
        val settings = s.settings ?: return false
        val c = clocks(ctx)
        var ended = false
        LockStore.editState(ctx) { st ->
            if (!EndEarlyRules.mayEndNow(settings, st.active, c.nowMs)) st
            else {
                ended = true
                LockSchedule.endAll(st, c).recording(RecordLine(c.nowMs, RecordEvent.ENDED_WAIT))
            }
        }
        refresh(ctx)
        return ended
    }

    /** [LockCodeActivity] The right code: every active lock ends. */
    internal fun endWithCode(ctx: Context) {
        val c = clocks(ctx)
        LockStore.editState(ctx) { st -> if (st.active.isEmpty()) st else LockSchedule.endAll(st, c).recording(RecordLine(c.nowMs, RecordEvent.ENDED_CODE)) }
        refresh(ctx)
    }

    /** [LockCodeActivity] The right code (or no code needed): [lockId]'s next time is skipped. */
    internal fun skip(ctx: Context, lockId: String) {
        val f = LockStore.usable(ctx) ?: return
        val l = f.lock(lockId) ?: return
        val now = ZonedDateTime.now()
        LockStore.editState(ctx) { LockSchedule.skipNext(l, it, now).recording(RecordLine(now.toInstant().toEpochMilli(), RecordEvent.SKIPPED, lockId)) }
        refresh(ctx)
    }

    /** [LockCodeActivity] The right code: an edit session of 5 minutes. */
    internal fun openEdit(ctx: Context) {
        LockStore.editState(ctx) { it.copy(edit = System.currentTimeMillis() + EDIT_MS) }
        refresh(ctx)
    }

    /** You left Settings › Phone lock. */
    fun endEdit(ctx: Context) {
        LockStore.editState(ctx) { it.copy(edit = 0) }
        refresh(ctx)
    }

    /** [LockCodeActivity] A wrong or right try, kept in state.json so a restart doesn't reset it. */
    internal fun tried(ctx: Context, right: Boolean): LockState {
        val c = clocks(ctx)
        val now = c.nowMs
        return LockStore.editState(ctx) { st ->
            if (right) st.copy(code = LockCodeRules.RIGHT)
            else {
                val t = LockCodeRules.wrong(st.code, now, c.elapsed, c.boot)
                // Every 5th wrong try in a row goes in the record.
                if (t.fails % LockCodeRules.FREE_TRIES == 0) st.copy(code = t).recording(RecordLine(now, RecordEvent.WRONG_CODES, null, t.fails))
                else st.copy(code = t)
            }
        }
    }

    /** [LockCodeActivity] A code was set or removed: the other processes learn it from codeSet. */
    internal fun codeSet(ctx: Context, set: Boolean) {
        LockStore.editState(ctx) { it.copy(codeSet = set, code = LockCodeRules.RIGHT) }
        refresh(ctx)
    }

    /**
     * [LockCodeActivity] The code's key is gone (§7.1): treated as no code, every lock for someone else turned off (the one
     * case :lock writes locks.json), and the record notes it. A locks.json it can't read or write throws, before anything
     * says the code is gone: the pad says so, and the code, the key and the locks stay for the next try.
     */
    internal fun keyLost(ctx: Context) {
        LockStore.editLocks(ctx) { f -> if (f.settings.forWho == LockFor.SOMEONE_ELSE) f.copy(locks = f.locks.map { it.copy(on = false) }) else f }
            ?: throw java.io.IOException()
        LockStore.editState(ctx) { it.copy(codeSet = false).recording(RecordLine(System.currentTimeMillis(), RecordEvent.KEY_LOST)) }
        refresh(ctx)
    }

    /** The service was turned on or off in Android's settings (the record notes it). */
    fun access(ctx: Context, on: Boolean) {
        try {
            LockStore.editState(ctx) { st ->
                val lastAccess = st.record.lastOrNull { it.e == RecordEvent.ACCESS_OFF || it.e == RecordEvent.ACCESS_ON }
                when {
                    !on -> st.recording(RecordLine(System.currentTimeMillis(), RecordEvent.ACCESS_OFF))
                    lastAccess?.e == RecordEvent.ACCESS_OFF -> st.recording(RecordLine(System.currentTimeMillis(), RecordEvent.ACCESS_ON))
                    else -> st
                }
            }
        } catch (t: Throwable) {
            RLog.w("lock_access", t)
        }
    }

    // ------------------------------------------------------------------------------------------------ phase B: screen time

    /** The app in front as the service last saw it (an allowed app, or Robot's cover): One app first counts its time. */
    @Volatile var front: String? = null
        private set

    private class Screen(val on: Boolean, val unlocked: Boolean)

    private fun screen(ctx: Context): Screen = Screen(
        try { ctx.getSystemService(PowerManager::class.java).isInteractive } catch (_: Throwable) { false },
        try { !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked } catch (_: Throwable) { false },
    )

    /**
     * Each One app first lock's time in its app since it started, from usage events. Its app counts as in front now only
     * while every active lock lets it through, so the count pauses while another lock covers it (§2, overlaps). Without
     * usage access nothing is counted, and the lock ends at its latest end.
     */
    private fun countApps(ctx: Context, file: LockFile?, st: LockState, c: Clocks, screen: Screen): app.heyrobot.core.lock.Refreshed {
        val apps = LockSchedule.appLocks(file, st)
        if (apps.isEmpty()) return app.heyrobot.core.lock.Refreshed(st, emptyList(), emptyList())
        val events = UsageReader.events(ctx, apps.minOf { it.first.start }, c.nowMs) ?: return app.heyrobot.core.lock.Refreshed(st, emptyList(), emptyList())
        val allowed = LockSchedule.allowedPackages(file, st)
        val used = apps.associate { (a, r) ->
            val inFront = front == r.app && r.app in allowed
            a.lock to ScreenTime.count(events, a.start, c.nowMs, r.app, ScreenNow(screen.on, screen.unlocked, inFront))
        }
        return LockSchedule.countApps(file, st, used, c)
    }

    /** Today's screen time (since 00:00, screen on and unlocked) when a Daily limit is armed; its locks come on once it is reached. */
    private fun startLimits(ctx: Context, file: LockFile?, st: LockState, c: Clocks, screen: Screen): app.heyrobot.core.lock.Refreshed {
        if (LockSchedule.limitsArmed(file, st, c.now).isEmpty()) return app.heyrobot.core.lock.Refreshed(st, emptyList(), emptyList())
        val today = today(ctx, c, screen) ?: return app.heyrobot.core.lock.Refreshed(st, emptyList(), emptyList())
        return LockSchedule.startLimits(file, st, c, today)
    }

    /** App pause: today's pickups for phone_status (LockSignals.pickups), read from Android's usage history here and never kept. */
    fun pickups(ctx: Context, now: Long = System.currentTimeMillis()): app.heyrobot.core.pause.Pickups? {
        val day = app.heyrobot.core.pause.PauseCount.dayStart(now, java.time.ZoneId.systemDefault())
        return UsageReader.events(ctx, day - app.heyrobot.core.pause.PauseCount.LOOK_BACK_MS, now)?.let { app.heyrobot.core.pause.PauseCount.pickups(it, day, now) }
    }

    private fun today(ctx: Context, c: Clocks, screen: Screen): Long? {
        val midnight = c.now.toLocalDate().atStartOfDay(c.now.zone).toInstant().toEpochMilli()
        val events = UsageReader.events(ctx, midnight, c.nowMs) ?: return null
        return ScreenTime.count(events, midnight, c.nowMs, null, ScreenNow(screen.on, screen.unlocked))
    }

    private val timer: Handler by lazy { Handler(HandlerThread("robot-lock-time").apply { start() }.looper) }
    private var timerCtx: Context? = null
    private val timeUp = Runnable { timerCtx?.let { refresh(it) } }

    /**
     * §8.1: a handler timer, never polling. While a One app first lock's app is in front, for its count's next whole minute
     * or the time it still needs, whichever is sooner (LockSchedule.appTickMs); while
     * a Daily limit is armed and the screen is on and unlocked, for the screen time left today. Usage events are read again
     * when it fires, at every change of the app in front, and at screen on, off and unlock.
     */
    private fun timeCheck(ctx: Context, s: Snapshot, screen: Screen) {
        timer.removeCallbacks(timeUp)
        timerCtx = ctx.applicationContext
        if (!screen.on || !screen.unlocked) return
        val waits = mutableListOf<Long>()
        LockSchedule.appTickMs(s.file, s.state, front, screen.on, screen.unlocked)?.let(waits::add)
        if (LockSchedule.limitsArmed(s.file, s.state, s.clocks.now).isNotEmpty()) {
            today(ctx, s.clocks, screen)?.let { t -> LockSchedule.limitLeftMs(s.file, s.state, s.clocks.now, t)?.let(waits::add) }
        }
        val w = waits.minOrNull() ?: return
        timer.postDelayed(timeUp, w.coerceAtLeast(5_000) + 1_000)
    }

    /** [LockAccessibilityService] The app in front changed: One app first's count is read again (off the main thread). */
    fun foreground(ctx: Context, pkg: String?) {
        if (pkg == front) return
        front = pkg
        val s = snapshot ?: return
        if (LockSchedule.appLocks(s.file, s.state).isEmpty()) return
        timerCtx = ctx.applicationContext
        timer.removeCallbacks(timeUp)
        timer.postDelayed(timeUp, 300)
    }

    // ------------------------------------------------------------------------------------------------ the gate's facts

    private class Fixed(val at: Long, val keyboard: String?, val emergency: String?, val dialer: String?, val clocks: Set<String>,
        val keyboardActivities: Set<String>, val dialerActivities: Set<String>)

    @Volatile private var fixed: Fixed? = null

    /** review-lock 9: the emergency dialer ([dialer]: the phone's, as the fallback) just opened; state.json outlives a restart of :lock. */
    fun emergencyOpened(ctx: Context, dialer: Boolean) {
        val until = System.currentTimeMillis() + LockState.EMERGENCY_OPEN_MS
        LockStore.editState(ctx) { if (dialer) it.copy(emergencyUntil = until, dialerUntil = until) else it.copy(emergencyUntil = until) }
    }

    /** What LockGate decides on besides the window. The packages are read once a minute at most. */
    fun facts(ctx: Context, s: Snapshot): GateFacts {
        val now = System.currentTimeMillis()
        val f = fixed?.takeIf { now - it.at < 60_000 } ?: readFixed(ctx).also { fixed = it }
        val mode = try { ctx.getSystemService(AudioManager::class.java)?.mode } catch (_: Throwable) { null }
        val car = s.state.carOn(now)
        return GateFacts(
            active = s.active, ownPackage = ctx.packageName, keyboard = f.keyboard, emergencyDialer = f.emergency, dialer = f.dialer,
            inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING,
            dialerOpen = now < dialerOpenUntil || LockState.emergencyHeld(s.state.dialerUntil, now), clocks = f.clocks, allowed = LockSchedule.allowedPackages(s.file, s.state, s.state.mapsApp),
            editSession = s.state.editOpen(now), keyboardActivities = f.keyboardActivities, dialerActivities = f.dialerActivities,
            // Phase B (§9.3): car mode your remembered car started. During your own lock nothing is covered; during someone
            // else's, the maps app stays open (Robot's car screen is one of Robot's own allowed windows).
            carOwnLock = car && s.file?.settings?.forWho == LockFor.ME,
            carApps = if (car && s.file?.settings?.forWho == LockFor.SOMEONE_ELSE) setOfNotNull(s.state.mapsApp) else emptySet(),
        )
    }

    /** F13: the window usage access says is in front now (package, class), never the cover itself; null without usage access. */
    fun usageFront(ctx: Context): Pair<String, String?>? {
        val now = System.currentTimeMillis()
        return ScreenTime.front(UsageReader.events(ctx, now - 10 * 60_000L, now) ?: return null)?.takeIf { it.second != LockSignals.COVER }
    }

    private fun readFixed(ctx: Context): Fixed {
        val pm = ctx.packageManager
        fun system(p: String?): Boolean = try {
            p != null && (pm.getApplicationInfo(p, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Throwable) { false }
        val keyboard = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.let { ComponentName.unflattenFromString(it)?.packageName } } catch (_: Throwable) { null }
        // The app the cover's Emergency call opens, so the gate never covers the one it picked (review-lock 8).
        val emergency = try { LockSignals.emergencyDialer(ctx)?.component?.packageName } catch (_: Throwable) { null }
        val dialer = try { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (_: Throwable) { null }
        val clocks = try { pm.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_ALL).map { it.activityInfo.packageName } } catch (_: Throwable) { emptyList() }
        fun activities(p: String?): Set<String> = try {
            if (p == null) emptySet() else pm.getPackageInfo(p, PackageManager.GET_ACTIVITIES).activities.orEmpty().map { it.name }.toSet()
        } catch (_: Throwable) { emptySet() }
        return Fixed(System.currentTimeMillis(), keyboard, emergency?.takeIf(::system), dialer, clocks.filter(::system).toSet(), activities(keyboard), activities(dialer))
    }

    const val EDIT_MS = 5 * 60_000L
    private const val ALIVE_MS = 10 * 60_000L
    private const val RETRY_MS = 60_000L
}
