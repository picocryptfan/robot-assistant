package app.heyrobot.sys

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import app.heyrobot.core.NextFire
import app.heyrobot.core.RLog
import app.heyrobot.core.RoutineSchedule
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import java.time.ZonedDateTime

// Main and voice processes (0.8). Routines' files are core RoutineStore's, in files/robot next to settings.json, changed
// under a lock both processes take: the main app's Settings › Routines and the :voice process (a routine saved or deleted
// by voice, Ready cards, brief reminders, the run ledger). The one exact alarm for the next time routine is set from here
// by whichever process changed something; the receiver it rings is in :voice, named by its class (the main process never
// refers to the assistant's classes).

object RoutineFiles {
    fun store(ctx: Context): RoutineStore = RoutineStore(Paths.dir(ctx))

    /** [MAIN] Settings › Routines changed something: :voice reloads its receivers, and the next alarm is set again. */
    fun changed(ctx: Context) {
        Cmd.send(ctx, Cmd.ROUTINES)
        RoutineAlarms.arm(ctx)
    }
}

/** Names and extras shared by the two processes (string keys, as MainActivity's showAssist arguments are). */
object RoutineIntents {
    /** :voice's manifest receiver: the exact alarm, boot, time and time zone changes, an update, a Ready notification's tap. */
    const val RECEIVER = "app.heyrobot.assistant.routines.RoutineAlarmReceiver"
    const val ACTION_FIRE = "app.heyrobot.routines.FIRE"
    const val ACTION_READY = "app.heyrobot.routines.READY"
    /** 0.8: car mode's "From your drive: n things" tapped (the same receiver, in :voice). */
    const val ACTION_PARKED = "app.heyrobot.car.PARKED"
    /** The local date and time the alarm is for ("2026-10-12T07:00"), and when it was set to ring (ms). */
    const val EXTRA_SLOT = "slot"
    const val EXTRA_AT = "at"
    /** The panel's show arguments: run this routine now (Run now), or show these Ready cards ([ALL_READY]: every one). */
    const val ARG_ROUTINE = "robot_routine"
    const val ARG_READY = "robot_ready"
    const val ALL_READY = "*"
}

/**
 * The one exact alarm (design §2.1): AlarmManager.setExactAndAllowWhileIdle for the soonest time over every enabled time
 * routine, the brief included; never setAlarmClock, which would become Android's "next alarm" (the status bar, the lock
 * screen) and confuse "when I turn off my alarm". USE_EXACT_ALARM, declared since 0.2.3 for the restart check, is granted
 * at install and can't be revoked, so it is exact on this phone; if Android ever says otherwise, an inexact
 * allow-while-idle alarm is set instead and Settings › Routines says so.
 */
object RoutineAlarms {
    private const val REQ = 8_401

    /** Exact alarms are allowed (USE_EXACT_ALARM). */
    fun exact(ctx: Context): Boolean = try { ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms() } catch (_: Throwable) { false }

    private fun intent(ctx: Context, next: NextFire?): Intent = Intent(RoutineIntents.ACTION_FIRE).setClassName(ctx.packageName, RoutineIntents.RECEIVER).apply {
        next?.let {
            putExtra(RoutineIntents.EXTRA_SLOT, it.due.first().slot.toString())
            putExtra(RoutineIntents.EXTRA_AT, it.at.toInstant().toEpochMilli())
        }
    }

    /**
     * Sets (or cancels) the alarm for the soonest routine after [now]. Called after every change, by the receiver after
     * each ring, at boot, and when the time or the time zone changes. [log]: the :voice process writes the voice log.
     */
    fun arm(ctx: Context, now: ZonedDateTime = ZonedDateTime.now(), log: Boolean = false): NextFire? {
        val next = try { RoutineSchedule.soonest(RoutineFiles.store(ctx).all(), now) } catch (t: Throwable) { RLog.w("routine_next", t); null }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(ctx, REQ, intent(ctx, next), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (next == null) {
                am.cancel(pi)
                if (log) VoiceLog.add(VoiceEvent.ROUTINE_ALARM_SET, -1)
                return null
            }
            val at = next.at.toInstant().toEpochMilli()
            val exact = exact(ctx)
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            val minutes = ((at - now.toInstant().toEpochMilli()) / 60_000L).coerceIn(0, 99_999).toInt()
            RLog.i("routine_alarm", "minutes" to minutes, "exact" to exact, "due" to next.due.size)
            if (log) VoiceLog.add(VoiceEvent.ROUTINE_ALARM_SET, minutes, if (exact) 1 else 0, next.due.size)
        } catch (t: Throwable) {
            RLog.w("routine_alarm", t)
        }
        return next
    }
}

/** What Settings › Routines' status rows show: the permissions and apps routines rely on, read now. */
data class RoutineFacts(
    val exactAlarms: Boolean,
    val notifications: Boolean,
    /** GrapheneOS Clock (com.android.deskclock) is installed: its ALARM_ALERT and ALARM_DONE broadcasts can come. */
    val clock: Boolean,
    val calendar: Boolean,
    val location: Boolean,
) {
    companion object {
        const val CLOCK = "com.android.deskclock"

        fun read(ctx: Context): RoutineFacts = RoutineFacts(
            exactAlarms = RoutineAlarms.exact(ctx),
            notifications = Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS),
            // Seen through the SET_ALARM <queries> entry the alarm tool already has: no new package visibility.
            clock = try {
                ctx.packageManager.queryIntentActivities(Intent(AlarmClock.ACTION_SET_ALARM), 0).any { it.activityInfo.packageName == CLOCK }
            } catch (_: Throwable) { false },
            calendar = Calendars.readGranted(ctx),
            location = CoarseLocation.granted(ctx),
        )
    }
}
