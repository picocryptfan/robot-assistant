// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.heyrobot.core.car.CarOpen
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.CallKeys
import app.heyrobot.core.lock.Clocks
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.lock.LockStateFile
import app.heyrobot.core.lock.LockView
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.Paths
import java.io.File
import java.time.ZonedDateTime

// All three app processes. Phone lock's files live in files/robot/lock/, written atomically under
// lock.lock, which every process takes:
//   locks.json  the locks and Settings › Phone lock's rows: written by the main process (Settings), and by :lock only
//               when the code's key is lost (locks for someone else go off);
//   state.json  what :lock knows about locks that are on: only :lock writes it ([editState]); the others read it;
//   code.json   the lock code's check: only :lock's LockCodeVault names it.
// No file is ever in a backup (core BackupCategory names none of them). Nothing here is logged but error codes.

object LockStore {
    const val LOCKS = "locks.json"
    const val STATE = "state.json"

    fun dir(ctx: Context): File = File(Paths.dir(ctx), "lock").apply { mkdirs() }
    private fun lockFile(ctx: Context) = File(dir(ctx), "lock.lock")
    private fun locksFile(ctx: Context) = File(dir(ctx), LOCKS)
    private fun stateFile(ctx: Context) = File(dir(ctx), STATE)
    @Volatile private var states: LockStateFile? = null
    private fun states(ctx: Context): LockStateFile {
        val file = stateFile(ctx)
        return states?.takeIf { it.file == file } ?: synchronized(this) {
            states?.takeIf { it.file == file } ?: LockStateFile(file) { LockMaps.app(ctx.applicationContext) }.also { states = it }
        }
    }

    fun <T> locked(ctx: Context, block: () -> T): T = withFileLock(lockFile(ctx), block)

    /** locks.json as it is now: no file is Phone lock as it comes (off, no locks). */
    fun read(ctx: Context): LockFileRead {
        val f = locksFile(ctx)
        if (!f.exists()) return LockFileRead.Ok(LockFile())
        return LockCodec.decode(readOrNull(f))
    }

    /**
     * Phone lock is on, or locks.json can't be read here (damaged, too new, an I/O error): a lock may be on whatever this
     * process sees, so a covered window tells :lock it is in front (§5.2). False only for a file read as off (LS13 low 1).
     */
    fun mayLock(ctx: Context): Boolean = try { read(ctx).let { it !is LockFileRead.Ok || it.file.settings.on } } catch (_: Throwable) { true }

    /** The locks that may come on now (none while Phone lock is off or its file can't be read). */
    fun usable(ctx: Context): LockFile? = read(ctx).ok?.takeIf { it.settings.on }

    /**
     * [MAIN] (and :lock when the code's key is lost) Reads, changes and writes locks.json under the lock, then tells :lock.
     * Null when the file can't be read or is newer (never written over: "Start over" keeps it aside), or the write failed.
     */
    fun editLocks(ctx: Context, change: (LockFile) -> LockFile): LockFile? = try {
        locked(ctx) {
            val now = read(ctx).ok ?: return@locked null
            val next = change(now)
            if (next != now) writeAtomic(locksFile(ctx), LockCodec.encode(next))
            next
        }?.also { LockSignals.locksChanged(ctx) }
    } catch (t: Throwable) {
        RLog.w("lock_edit", t)
        null
    }

    /** [MAIN] After a damaged file: kept aside as locks.json.bad, and Phone lock starts over (off, no locks). */
    fun startOver(ctx: Context) {
        try {
            locked(ctx) {
                val f = locksFile(ctx)
                if (f.exists()) f.renameTo(File(dir(ctx), "$LOCKS.bad"))
                writeAtomic(f, LockCodec.encode(LockFile()))
            }
        } catch (t: Throwable) {
            RLog.w("lock_start_over", t)
        }
        LockSignals.locksChanged(ctx)
    }

    /** [LOCK] The state worked out here isn't in state.json yet. */
    val unsavedState: Boolean get() = states?.unsavedState == true

    /**
     * state.json, read by any process; in :lock, the newest state it knows when the file can't be read or take a write. One
     * nobody can read has no locks in it, but says a code is set (the code pad puts that right), so nothing gets easier.
     */
    fun readState(ctx: Context): LockState = states(ctx).state()

    /** [readState], but null when state.json is there and can't be read. */
    fun readStateOrNull(ctx: Context): LockState? = states(ctx).stateOrNull()

    /** Settings.Global.BOOT_COUNT: tells this boot's elapsed-time ends from an older boot's. A process lives within one boot. */
    @Volatile private var bootCount: Int? = null
    private fun boot(ctx: Context): Int? = bootCount ?: try { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT).also { bootCount = it } } catch (_: Throwable) { null }

    /** The wall clock in the phone's zone, the elapsed-time clock, the boot count. */
    fun clocks(ctx: Context) = Clocks(ZonedDateTime.now(), SystemClock.elapsedRealtime(), boot(ctx))

    /** When state.json last changed (0: never), for the other processes' caches. */
    fun stateStamp(ctx: Context): Long = stateFile(ctx).lastModified()
    fun locksStamp(ctx: Context): Long = locksFile(ctx).lastModified()

    /**
     * [LOCK] The one writer of state.json: reads, changes and writes it under the lock (core LockStateFile). When only the
     * write fails (storage full), the new state is still returned and kept, so locks still start, end and come back in
     * :lock; the next edit (every refresh is one) writes it again. Throws when state.json can't be read and :lock knows no
     * state: it never writes one built on nothing.
     */
    fun editState(ctx: Context, change: (LockState) -> LockState): LockState = locked(ctx) { states(ctx).edit(change) }
}

/** Robot's accessibility service, as Android's settings name it ("ai.robot_app/app.heyrobot.lock.LockAccessibilityService"). */
object LockAccess {
    const val SERVICE = "app.heyrobot.lock.LockAccessibilityService"

    fun component(ctx: Context) = ComponentName(ctx.packageName, SERVICE)

    /** The service is turned on in Android's accessibility settings. */
    fun enabled(ctx: Context): Boolean = try {
        val on = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = component(ctx)
        on.split(':').any { ComponentName.unflattenFromString(it) == me } &&
            ctx.getSystemService(AccessibilityManager::class.java)?.isEnabled != false
    } catch (_: Throwable) {
        false
    }

    /** Android's accessibility settings (Android has no public way to open one app's own entry). */
    fun settings(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Phase B: the app that opens places on this phone, which a lock with Maps on leaves open: your maps app (Settings ›
 * Actions › Maps, sys MapsApps), Automatic's Organic Maps or the default app for geo: links; none when your pick was
 * replaced by an app signed by someone else (fail closed). Asked once, as a lock starts (LockState.mapsApp): the default
 * may be someone else's choice by the time it is needed.
 */
object LockMaps {
    fun app(ctx: Context): String? = try { app.heyrobot.sys.MapsApps.app(ctx) } catch (_: Throwable) { null }
}

/**
 * Phone lock as the other processes see it: a lock is on now, and whether it is for someone else. Read from state.json
 * and locks.json again only when either changed (their modification times), so the panel can ask on every turn.
 */
object PhoneLockNow {
    /**
     * What the two files said (core LockView), and when they last changed. Kept only when both could be read: a read that
     * failed is answered "on" for that call (LockView.Unknown) and asked again on the next (F22).
     */
    private class Seen(val stateStamp: Long, val locksStamp: Long, val view: LockView)

    @Volatile private var seen: Seen? = null

    /** The state.json whose damage :lock was last asked to look at (it heals it at its next refresh). */
    @Volatile private var healAsked = -1L

    private fun current(ctx: Context): LockView {
        val ss = LockStore.stateStamp(ctx)
        val ls = LockStore.locksStamp(ctx)
        seen?.let { if (it.stateStamp == ss && it.locksStamp == ls) return it.view }
        val v = try {
            LockView.of(LockStore.readStateOrNull(ctx), LockStore.read(ctx), ss)
        } catch (t: Throwable) {
            RLog.w("lock_now", t)
            LockView.unknown(ss)
        }
        if (v.exact) seen = Seen(ss, ls, v)
        else {
            seen = null
            if (v.state == null && healAsked != ss) {
                healAsked = ss
                try { LockSignals.locksChanged(ctx) } catch (t: Throwable) { RLog.w("lock_now_heal", t) }
            }
        }
        return v
    }

    /**
     * A phone lock is on now: one is in state.json and its end hasn't passed (should :lock not be running to take it off,
     * a lock still ends on time here, and none outlasts the 24-hour cap). When state.json can't be read: on (LockView).
     */
    fun on(ctx: Context): Boolean = current(ctx).on(clocks(ctx))

    /** The clocks as :lock reads them, for anything that asks how long a lock has left. */
    fun clocks(ctx: Context): Clocks = LockStore.clocks(ctx)

    /** A phone lock for someone else is on now (the panel then keeps to LockScreen.DEFAULT, §6). */
    fun someoneElse(ctx: Context): Boolean = on(ctx) && current(ctx).someoneElse

    /** Phase B, the extras (§6): the apps the active locks leave open (their chosen apps, music, other apps, maps). */
    fun apps(ctx: Context): Set<String> = if (on(ctx)) current(ctx).apps else emptySet()

    /** The People ids every active lock lets you call (empty: no calls but emergency ones). */
    fun people(ctx: Context): Set<String> = if (on(ctx)) current(ctx).people else emptySet()

    /**
     * Whether a number may be called during a lock (Calls to People you choose): one of those People's numbers, from People,
     * or while People is sealed from Phone lock's call list (seal-people-fix 5). Nobody outside a lock.
     */
    fun calls(ctx: Context): (String) -> Boolean {
        val ids = people(ctx)
        if (ids.isEmpty()) return { false }
        val load = try { app.heyrobot.sys.PeopleFiles.store(ctx).load() } catch (t: Throwable) { RLog.w("lock_people", t); app.heyrobot.core.PeopleLoad.NotNow }
        return when (load) {
            is app.heyrobot.core.PeopleLoad.Ok -> load.file.people.filter { it.id in ids }.flatMap { it.phones }.flatMap { CallKeys.chosen(it.number) }.toSet().let { keys -> ({ n: String -> CallKeys.asked(n).any { it in keys } }) }
            // seal-people-fix 5: People sealed (the phone locked): the call list, numbers only, checked against these ids.
            app.heyrobot.core.PeopleLoad.NotNow -> ({ n: String -> app.heyrobot.sys.LockCallList.allows(ctx, ids, n) })
            else -> ({ _: String -> false })
        }
    }

    /** A phone number as Phone lock compares them (seal-small 2: in full when both have a country code). */
    fun callKeys(n: String): List<String> = CallKeys.asked(n)

    /** Every active lock has Maps on. */
    fun maps(ctx: Context): Boolean = on(ctx) && current(ctx).maps

    /** Your maps app stays open: every lock has Maps on, or your car's car mode is on, when the cover leaves it open anyway (LockEngine.facts). */
    /** Car mode your car started during your own lock, as :lock's gate reads it: it covers nothing then. */
    fun carOwn(ctx: Context): Boolean = on(ctx) && current(ctx).carOwn(System.currentTimeMillis())

    fun mapsInCar(ctx: Context): Boolean = on(ctx) && current(ctx).let { CarOpen.lockMaps(it.maps, it.state?.carOn(System.currentTimeMillis()) == true) }

    /** locks.json and state.json as last read (for "how long is left" and the brief), or null while Phone lock is off. */
    fun files(ctx: Context): Pair<LockFile, LockState>? = current(ctx).let { s -> s.file?.let { f -> s.state?.let { f to it } } }
}
