// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import app.heyrobot.core.RLog
import app.heyrobot.core.lock.LockCodec
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockFileRead
import app.heyrobot.core.lock.LockFor
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.core.lock.LockState
import app.heyrobot.core.readOrNull
import app.heyrobot.core.withFileLock
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.Paths
import java.io.File

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

    fun <T> locked(ctx: Context, block: () -> T): T = withFileLock(lockFile(ctx), block)

    /** locks.json as it is now: no file is Phone lock as it comes (off, no locks). */
    fun read(ctx: Context): LockFileRead {
        val f = locksFile(ctx)
        if (!f.exists()) return LockFileRead.Ok(LockFile())
        return LockCodec.decode(readOrNull(f))
    }

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

    /**
     * [LOCK] A state :lock worked out but couldn't write to state.json (storage full). It is newer than the file, so :lock
     * reads and edits from it, and every edit writes it again until a write works. Only :lock writes state.json, so it
     * stays null in the other processes.
     */
    @Volatile private var unsaved: LockState? = null

    /** [LOCK] The state worked out here isn't in state.json yet. */
    val unsavedState: Boolean get() = unsaved != null

    /** state.json, read by any process (an unreadable one is an empty state); in :lock, a state it couldn't write yet. */
    fun readState(ctx: Context): LockState = readStateOrNull(ctx) ?: LockState()

    /** [readState], but null when state.json is there and can't be read. */
    fun readStateOrNull(ctx: Context): LockState? =
        unsaved ?: stateFile(ctx).let { f -> if (!f.exists()) LockState() else LockCodec.decodeState(readOrNull(f)) }

    /** When state.json last changed (0: never), for the other processes' caches. */
    fun stateStamp(ctx: Context): Long = stateFile(ctx).lastModified()
    fun locksStamp(ctx: Context): Long = locksFile(ctx).lastModified()

    /**
     * [LOCK] The one writer of state.json: reads, changes and writes it under the lock. When only the write fails (storage
     * full), the new state is still returned and kept as [unsaved], so locks still start, end and come back in :lock; the
     * next edit (every refresh is one) writes it again.
     */
    fun editState(ctx: Context, change: (LockState) -> LockState): LockState = locked(ctx) {
        val now = readState(ctx)
        val next = change(now)
        if (next != now || unsaved != null) {
            try {
                writeAtomic(stateFile(ctx), LockCodec.encodeState(next))
                unsaved = null
            } catch (e: java.io.IOException) {
                RLog.w("lock_state_write", e)
                unsaved = next
            }
        }
        next
    }
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

/** Phase B: the app that opens places (geo:) on this phone, which a lock with Maps on leaves open. Seen through the geo: <queries> entry. */
object LockMaps {
    fun app(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0")), android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it != "android" && app.heyrobot.core.lock.LockLimits.validPackage(it) }
    } catch (_: Throwable) { null }
}

/**
 * Phone lock as the other processes see it: a lock is on now, and whether it is for someone else. Read from state.json
 * and locks.json again only when either changed (their modification times), so the panel can ask on every turn.
 */
object PhoneLockNow {
    /**
     * What the two files said: each active lock's start and wall-clock end, and who the locks are for; phase B: what the
     * active locks leave open (their apps, the People you may call, Maps).
     */
    private class Seen(val stateStamp: Long, val locksStamp: Long, val active: List<Pair<Long, Long>>, val someoneElse: Boolean,
        val apps: Set<String> = emptySet(), val people: Set<String> = emptySet(), val maps: Boolean = false, val file: LockFile? = null,
        val state: LockState = LockState())

    @Volatile private var seen: Seen? = null

    private fun current(ctx: Context): Seen {
        val ss = LockStore.stateStamp(ctx)
        val ls = LockStore.locksStamp(ctx)
        seen?.let { if (it.stateStamp == ss && it.locksStamp == ls) return it }
        val s = try {
            val st = LockStore.readState(ctx)
            val f = LockStore.usable(ctx)
            // A lock in state.json counts only while Phone lock is on: turning it off ends every lock, before :lock writes.
            val on = if (f == null) LockState() else st
            Seen(ss, ls, on.active.map { it.start to it.end }, f?.settings?.forWho == LockFor.SOMEONE_ELSE,
                LockSchedule.allowedPackages(f, on, LockMaps.app(ctx)), LockSchedule.allowedPeople(f, on), LockSchedule.mapsAllowed(f, on), f, on)
        } catch (t: Throwable) {
            RLog.w("lock_now", t)
            Seen(ss, ls, emptyList(), someoneElse = false)
        }
        seen = s
        return s
    }

    /**
     * A phone lock is on now: one is in state.json and its end hasn't passed (should :lock not be running to take it off,
     * a lock still ends on time here, and none outlasts the 24-hour cap).
     */
    fun on(ctx: Context, now: Long = System.currentTimeMillis()): Boolean =
        current(ctx).active.any { (start, end) -> now < end && now < start + LockSchedule.CAP.toMillis() }

    /** A phone lock for someone else is on now (the panel then keeps to LockScreen.DEFAULT, §6). */
    fun someoneElse(ctx: Context): Boolean = on(ctx) && current(ctx).someoneElse

    /** Phase B, the extras (§6): the apps the active locks leave open (their chosen apps, music, other apps, maps). */
    fun apps(ctx: Context): Set<String> = if (on(ctx)) current(ctx).apps else emptySet()

    /** The People ids every active lock lets you call (empty: no calls but emergency ones). */
    fun people(ctx: Context): Set<String> = if (on(ctx)) current(ctx).people else emptySet()

    /**
     * The numbers you may call during a lock (Calls to People you choose), as [callKey] compares them: those People's
     * numbers in People. Empty outside a lock.
     */
    fun callKeys(ctx: Context): Set<String> = people(ctx).let { ids ->
        if (ids.isEmpty()) emptySet() else try {
            app.heyrobot.sys.PeopleFiles.store(ctx).list().filter { it.id in ids }.flatMap { it.phones }.map { callKey(it.number) }.toSet()
        } catch (t: Throwable) { RLog.w("lock_people", t); emptySet() }
    }

    /** A phone number as Phone lock compares them: its last 10 digits (so +1 418… and 418… match). */
    fun callKey(n: String): String = n.filter { it.isDigit() }.takeLast(10)

    /** Every active lock has Maps on. */
    fun maps(ctx: Context): Boolean = on(ctx) && current(ctx).maps

    /** locks.json and state.json as last read (for "how long is left" and the brief), or null while Phone lock is off. */
    fun files(ctx: Context): Pair<LockFile, LockState>? = current(ctx).let { s -> s.file?.let { it to s.state } }
}
