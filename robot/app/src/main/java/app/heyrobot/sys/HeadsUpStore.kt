// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.DayRange
import app.heyrobot.core.headsup.HeadsUp
import java.time.Instant
import java.time.ZoneId
import app.heyrobot.core.RLog
import app.heyrobot.core.headsup.HeadsUpSaid
import app.heyrobot.core.headsup.HeadsUpSettings
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File

// Idea 5's heads-up: its settings (headsup.json, its own file; settings.json stays at schema 13) and the events already
// handled (headsup-said.json: event ids and start times, never titles), in Robot's own storage. Nothing leaves the phone.
object HeadsUpStore {
    private const val FILE = HeadsUpSettings.FILE
    private const val SAID = "headsup-said.json"

    /** The backup's HEADSUP entry (headsup-said.json stays out). */
    fun file(ctx: Context): File = File(Paths.dir(ctx), FILE)

    fun settings(ctx: Context): HeadsUpSettings = HeadsUpSettings.decode(readOrNull(File(Paths.dir(ctx), FILE)))

    @Synchronized
    fun save(ctx: Context, s: HeadsUpSettings): Boolean = put(ctx, FILE, s.encode())

    fun said(ctx: Context): HeadsUpSaid = HeadsUpSaid.decode(readOrNull(File(Paths.dir(ctx), SAID)))

    @Synchronized
    fun saveSaid(ctx: Context, s: HeadsUpSaid): Boolean = put(ctx, SAID, s.encode())

    private fun put(ctx: Context, name: String, text: String): Boolean = try {
        writeAtomic(File(Paths.dir(ctx).apply { mkdirs() }, name), text)
        true
    } catch (t: Throwable) {
        RLog.w("headsup_write", t)
        false
    }
}

// One alarm at the next event's moment, or within the hour to catch new events, sent to RoutineAlarmReceiver (:voice,
// assistant/routines HeadsUpHub). Armed from Settings › Headset, after each check, at boot and when the clock changes.
object HeadsUpAlarms {
    const val ACTION = "app.heyrobot.headsup.CHECK"
    private const val REQ = 8_461

    private fun pi(ctx: Context, flag: Int): PendingIntent? = PendingIntent.getBroadcast(ctx, REQ,
        Intent(ACTION).setClassName(ctx.packageName, RoutineIntents.RECEIVER), PendingIntent.FLAG_IMMUTABLE or flag)

    /** The timed events from now until a little past the next recheck. */
    fun events(ctx: Context, nowMs: Long, s: HeadsUpSettings): List<CalendarEvent> = try {
        val z = ZoneId.systemDefault()
        Calendars.events(ctx, DayRange(Instant.ofEpochMilli(nowMs).atZone(z), Instant.ofEpochMilli(nowMs + s.minutes * 60_000L + HeadsUp.RECHECK_MS + 60_000L).atZone(z)))
    } catch (t: Throwable) {
        RLog.w("headsup_calendar", t)
        emptyList()
    }

    /** Android's answer gave Robot the calendar (Settings › Headset asked, or anything else that asks for it). */
    fun calendarGranted(result: Map<String, Boolean>): Boolean = result[Manifest.permission.READ_CALENDAR] == true

    /** [arm] off the main thread: when calendar access arrives, and at each app start in case it came from Android's settings. */
    fun armSoon(ctx: Context) {
        val app = ctx.applicationContext
        Thread { arm(app) }.start()
    }

    /** Sets the next check, or cancels it when the heads-up is off or the calendar can't be read. */
    fun arm(ctx: Context, nowMs: Long = System.currentTimeMillis()) {
        try {
            val am = ctx.getSystemService(AlarmManager::class.java) ?: return
            val s = HeadsUpStore.settings(ctx)
            if (!s.on || !Calendars.readGranted(ctx)) {
                pi(ctx, PendingIntent.FLAG_NO_CREATE)?.let { am.cancel(it); it.cancel() }
                return
            }
            val at = HeadsUp.nextAt(events(ctx, nowMs, s), nowMs, s.minutes, HeadsUpStore.said(ctx))
            val p = pi(ctx, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
            // An event's moment is exact; the hourly look for new events needn't be.
            if (at < nowMs + HeadsUp.RECHECK_MS && RoutineAlarms.exact(ctx)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, p)
        } catch (t: Throwable) {
            RLog.w("headsup_arm", t)
        }
    }
}
