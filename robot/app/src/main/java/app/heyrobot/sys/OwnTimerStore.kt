// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import app.heyrobot.core.OwnTimer
import app.heyrobot.core.OwnTimers
import app.heyrobot.core.RLog
import org.json.JSONObject
import java.io.File

// named-timers (docs/plans/named-timers-plan.md): Robot's own timers, kept in files/own_timers.json (names, lengths, ends;
// gone 10 minutes after a ring) and rung by the routines' receiver in :voice (RoutineIntents.RECEIVER) on an exact
// allow-while-idle alarm, as routines are (RoutineAlarms). The switch is its own file, own_timers_settings.json, written by
// Settings › Routines in the main process and read fresh each time by :voice. Nothing is kept in memory: a killed process
// or a reboot loses nothing, and the alarm is set again at boot, on an update and when the time changes.
object OwnTimerIntents {
    const val ACTION_RING = "app.heyrobot.timers.RING"
    const val ACTION_STOP = "app.heyrobot.timers.STOP"
    const val EXTRA_ID = "timer"
    const val CHANNEL = "robot_own_timers"
    /** The ring's notification id is this plus the timer's id (1 to 999). */
    const val NOTE_BASE = 74_000
}

object OwnTimerStore {
    private const val FILE = "own_timers.json"
    private const val SETTINGS = "own_timers_settings.json"
    private val lock = Any()

    private fun file(ctx: Context) = AtomicFile(File(ctx.filesDir, FILE))
    private fun settings(ctx: Context) = AtomicFile(File(ctx.filesDir, SETTINGS))

    /** The switch in Settings › Routines; off by default (timers go to the clock app). */
    fun on(ctx: Context): Boolean = try {
        val f = settings(ctx)
        f.baseFile.exists() && JSONObject(String(f.readFully(), Charsets.UTF_8)).optBoolean("on", false)
    } catch (_: Exception) {
        false
    }

    fun setOn(ctx: Context, on: Boolean) = synchronized(lock) { write(settings(ctx), JSONObject().put("on", on).toString()) }

    /** Tests only: runs once the file is read, before it is used (where a cancel from elsewhere could land). */
    internal var afterRead: (() -> Unit)? = null

    /** Runs [block] under the store's lock, so a change and the notifications that follow it can't be split by another change. */
    fun <T> locked(block: () -> T): T = synchronized(lock, block)

    private fun boot(ctx: Context): Int = try {
        Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (_: Exception) {
        -1
    }

    /** The kept timers, their ends in [now]'s wall-clock frame from elapsed time [el] (OwnTimers.rebase). */
    fun load(ctx: Context, now: Long = System.currentTimeMillis(), el: Long = SystemClock.elapsedRealtime()): List<OwnTimer> = synchronized(lock) {
        try {
            val f = file(ctx)
            if (!f.baseFile.exists()) emptyList() else OwnTimers.decode(String(f.readFully(), Charsets.UTF_8)).also { afterRead?.invoke() }
                .let { OwnTimers.kept(OwnTimers.rebase(it, now, el, boot(ctx)), now) }
        } catch (t: Throwable) {
            RLog.w("own_timer_load", t)
            emptyList()
        }
    }

    /** Changes the list under the lock and writes it whole; returns what was written. */
    fun update(ctx: Context, now: Long = System.currentTimeMillis(), el: Long = SystemClock.elapsedRealtime(),
        change: (List<OwnTimer>) -> List<OwnTimer>): List<OwnTimer> = synchronized(lock) {
        val next = OwnTimers.kept(OwnTimers.rebase(change(load(ctx, now, el)), now, el, boot(ctx)), now)
        write(file(ctx), OwnTimers.encode(next))
        next
    }

    private fun write(f: AtomicFile, text: String) {
        val out = f.startWrite()
        try {
            out.write(text.toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (t: Throwable) {
            f.failWrite(out)
            RLog.w("own_timer_write", t)
        }
    }

    /** Robot can ring: its notifications are allowed and the timers' channel isn't silenced. Creates the channel. */
    fun canRing(ctx: Context): Boolean = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        OwnTimerChannel.make(ctx)
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED && nm.areNotificationsEnabled() &&
            (nm.getNotificationChannel(OwnTimerIntents.CHANNEL)?.importance ?: NotificationManager.IMPORTANCE_NONE) >= NotificationManager.IMPORTANCE_DEFAULT
    } catch (t: Throwable) {
        RLog.w("own_timer_can_ring", t)
        false
    }
}

/** The one alarm for the soonest own timer, as RoutineAlarms sets the routines' (its own request code). */
object OwnTimerAlarms {
    private const val REQ = 8_406

    fun intent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(ctx, REQ,
        Intent(OwnTimerIntents.ACTION_RING).setClassName(ctx.packageName, RoutineIntents.RECEIVER), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets (or cancels) the alarm for the soonest timer not rung yet; one whose end has passed (a restart) goes off at once. */
    fun arm(ctx: Context, now: Long = System.currentTimeMillis()): Long? {
        val next = OwnTimers.nextAt(OwnTimerStore.load(ctx, now))
        val am = ctx.getSystemService(AlarmManager::class.java)
        try {
            if (next == null) {
                am.cancel(intent(ctx))
                return null
            }
            val at = maxOf(next, now)
            if (RoutineAlarms.exact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent(ctx))
            RLog.i("own_timer_alarm", "seconds" to ((at - now) / 1000).coerceIn(0, 99_999).toInt())
        } catch (t: Throwable) {
            RLog.w("own_timer_alarm", t)
        }
        return next
    }
}
