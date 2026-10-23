// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

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

    /** Settings.Global.BOOT_COUNT: tells this boot's elapsed-time ends from an older boot's. */
    private fun boot(ctx: Context): Int? = try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) } catch (_: Throwable) { null }

    fun clocks(ctx: Context) = Clocks(ZonedDateTime.now(), SystemClock.elapsedRealtime(), boot(ctx))

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
        val file = LockStore.usable(ctx)
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
                val r = LockSchedule.refresh(file, st, c)
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
            try { LockStore.readStateOrNull(ctx)?.let { LockSchedule.refresh(file, it, c).state } } catch (_: Throwable) { null }
                ?: snapshot?.state ?: LockState()
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
        LockNotices.update(ctx, snap, access, couldNotStart = if (access) emptyList() else started0)
        for (a in metApps) LockNotices.appDone(ctx, file, a)
        LockAccessibilityService.instance?.onEngine(snap, startedNow = startedNow || started0.isNotEmpty())
        timeCheck(ctx, snap, screen)
        synchronized(listeners) { listeners.toList() }.forEach { it() }
        return snap
    }

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
     * Phase B (§9.3): :voice says Robot's car mode started or ended. Only car mode your remembered car started counts
     * ([byCar]); car mode you asked for changes nothing. The lock's time keeps running, and the record notes car mode
     * during a lock.
     */
    fun car(ctx: Context, on: Boolean, byCar: Boolean) {
        val now = System.currentTimeMillis()
        LockStore.editState(ctx) { st ->
            when {
                on && byCar && !st.carOn(now) -> st.copy(car = now).let { if (it.active.isNotEmpty()) it.recording(RecordLine(now, RecordEvent.CAR)) else it }
                !on && st.car != 0L -> st.copy(car = 0)
                else -> st
            }
        }
        refresh(ctx)
    }

    /** End early under a wait (the cover's button). */
    fun startWait(ctx: Context) {
        val s = current(ctx)
        val settings = s.file?.settings ?: return
        LockStore.editState(ctx) { EndEarlyRules.startWait(settings, it, System.currentTimeMillis()) }
        refresh(ctx)
    }

    /** End the lock now, once the wait is over. False when it isn't allowed now. */
    fun endAfterWait(ctx: Context): Boolean {
        val s = current(ctx)
        val settings = s.file?.settings ?: return false
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
        val now = System.currentTimeMillis()
        return LockStore.editState(ctx) { st ->
            if (right) st.copy(code = LockCodeRules.RIGHT)
            else {
                val t = LockCodeRules.wrong(st.code, now)
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
     * case :lock writes locks.json), and the record notes it.
     */
    internal fun keyLost(ctx: Context) {
        LockStore.editLocks(ctx) { f -> if (f.settings.forWho == LockFor.SOMEONE_ELSE) f.copy(locks = f.locks.map { it.copy(on = false) }) else f }
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

    private fun today(ctx: Context, c: Clocks, screen: Screen): Long? {
        val midnight = c.now.toLocalDate().atStartOfDay(c.now.zone).toInstant().toEpochMilli()
        val events = UsageReader.events(ctx, midnight, c.nowMs) ?: return null
        return ScreenTime.count(events, midnight, c.nowMs, null, ScreenNow(screen.on, screen.unlocked))
    }

    private val timer: Handler by lazy { Handler(HandlerThread("robot-lock-time").apply { start() }.looper) }
    private var timerCtx: Context? = null
    private val timeUp = Runnable { timerCtx?.let { refresh(it) } }

    /**
     * §8.1: a handler timer, never polling. While a One app first lock's app is in front, for the time it still needs; while
     * a Daily limit is armed and the screen is on and unlocked, for the screen time left today. Usage events are read again
     * when it fires, at every change of the app in front, and at screen on, off and unlock.
     */
    private fun timeCheck(ctx: Context, s: Snapshot, screen: Screen) {
        timer.removeCallbacks(timeUp)
        timerCtx = ctx.applicationContext
        if (!screen.on || !screen.unlocked) return
        val waits = mutableListOf<Long>()
        for ((a, r) in LockSchedule.appLocks(s.file, s.state)) if (front == r.app) waits += LockSchedule.appLeftMs(a, r)
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

    private class Fixed(val at: Long, val keyboard: String?, val emergency: String?, val dialer: String?, val clocks: Set<String>, val maps: String?)

    @Volatile private var fixed: Fixed? = null

    /** What LockGate decides on besides the window. The packages are read once a minute at most. */
    fun facts(ctx: Context, s: Snapshot): GateFacts {
        val now = System.currentTimeMillis()
        val f = fixed?.takeIf { now - it.at < 60_000 } ?: readFixed(ctx).also { fixed = it }
        val mode = try { ctx.getSystemService(AudioManager::class.java)?.mode } catch (_: Throwable) { null }
        val car = s.state.carOn(now)
        return GateFacts(
            active = s.active, ownPackage = ctx.packageName, keyboard = f.keyboard, emergencyDialer = f.emergency, dialer = f.dialer,
            inCall = mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_CALL_SCREENING,
            dialerOpen = now < dialerOpenUntil, clocks = f.clocks, allowed = LockSchedule.allowedPackages(s.file, s.state, f.maps),
            editSession = s.state.editOpen(now),
            // Phase B (§9.3): car mode your remembered car started. During your own lock nothing is covered; during someone
            // else's, the maps app stays open (Robot's car screen is one of Robot's own allowed windows).
            carOwnLock = car && s.file?.settings?.forWho == LockFor.ME,
            carApps = if (car && s.file?.settings?.forWho == LockFor.SOMEONE_ELSE) setOfNotNull(f.maps) else emptySet(),
        )
    }

    private fun readFixed(ctx: Context): Fixed {
        val pm = ctx.packageManager
        fun system(p: String?): Boolean = try {
            p != null && (pm.getApplicationInfo(p, 0).flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Throwable) { false }
        val keyboard = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.let { ComponentName.unflattenFromString(it)?.packageName } } catch (_: Throwable) { null }
        val emergency = try { pm.resolveActivity(Intent(LockSignals.EMERGENCY_DIAL), PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo?.packageName } catch (_: Throwable) { null }
        val dialer = try { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (_: Throwable) { null }
        val clocks = try { pm.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), PackageManager.MATCH_ALL).map { it.activityInfo.packageName } } catch (_: Throwable) { emptyList() }
        return Fixed(System.currentTimeMillis(), keyboard, emergency?.takeIf(::system), dialer, clocks.filter(::system).toSet(), LockMaps.app(ctx))
    }

    const val EDIT_MS = 5 * 60_000L
    private const val ALIVE_MS = 10 * 60_000L
    private const val RETRY_MS = 60_000L
}
