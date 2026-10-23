// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import java.time.ZonedDateTime

// :LOCK PROCESS ONLY. Phone lock's one exact alarm: AlarmManager.setExactAndAllowWhileIdle at the soonest of
// every lock's next warning, wake-up, start and end, a wait's end and the edit session's end (core LockSchedule.nextAlarm);
// never setAlarmClock, which would become Android's "next alarm" and confuse "when I turn off my alarm". USE_EXACT_ALARM
// is already declared (routines). Set again after every refresh, at boot, after a time or zone change and an update.

object LockAlarms {
    private const val REQ = 8_501

    fun arm(ctx: Context, at: ZonedDateTime?) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, REQ, Intent(LockSignals.ACTION_FIRE).setClassName(ctx.packageName, LockSignals.RECEIVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            if (at == null) am.cancel(pi)
            else if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toInstant().toEpochMilli(), pi)
        } catch (t: Throwable) {
            RLog.w("lock_alarm", t)
        }
    }
}

/**
 * The exact alarm, the system's BOOT_COMPLETED, TIME_SET, TIMEZONE_CHANGED and MY_PACKAGE_REPLACED, and Robot's own
 * explicit intents (LockSignals): not exported, so no other app can send it anything. Each one refreshes the engine.
 */
class LockAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val done = goAsync()
        Thread({
            try {
                when (intent.action) {
                    LockSignals.ACTION_EDIT_END -> LockEngine.endEdit(app)
                    // Phase B: LockRouter's card ("Lock the phone until 7"), after you tapped Lock. The engine starts it in
                    // its refresh's one edit: recorded, the cover shown at once, and kept even when state.json can't be written.
                    LockSignals.ACTION_START_VOICE -> LockEngine.refresh(app, voiceEnd = intent.getLongExtra(LockSignals.EXTRA_END, 0).takeIf { it > 0 })
                    // Phase B: Robot's car mode started or ended (:voice's CarModeController).
                    LockSignals.ACTION_CAR -> LockEngine.car(app, intent.getBooleanExtra(LockSignals.EXTRA_ON, false), intent.getBooleanExtra(LockSignals.EXTRA_BY_CAR, false))
                    else -> LockEngine.refresh(app)
                }
            } catch (t: Throwable) {
                RLog.w("lock_receiver", t)
            } finally {
                done.finish()
            }
        }, "robot-lock").start()
    }
}
