// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.heyrobot.core.RLog
import app.heyrobot.sys.RoutineIntents
import java.time.LocalDateTime

// VOICE PROCESS (0.8). The manifest receiver routines need (not exported, in :voice): the one exact alarm for the next
// time routine (sys RoutineAlarms sets it, by this class's name), a Ready notification's tap, and the broadcasts that
// make the alarm wrong: BOOT_COMPLETED (after the first unlock: routines.json is in credential-encrypted storage, so not
// LOCKED_BOOT_COMPLETED), TIME_SET, TIMEZONE_CHANGED and MY_PACKAGE_REPLACED, all exempt from the implicit-broadcast
// limits. Only the system and Robot's own PendingIntents reach it. The work goes to RoutineHub's thread.

class RoutineAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val action = intent.action ?: return
        val done = goAsync()
        RLog.i("routine_receiver", "action" to RLog.Code(action.substringAfterLast('.').lowercase()))
        RoutineHub.post(app) {
            try {
                when (action) {
                    RoutineIntents.ACTION_FIRE -> RoutineHub.onTimeAlarm(
                        intent.getStringExtra(RoutineIntents.EXTRA_SLOT)?.let { try { LocalDateTime.parse(it) } catch (_: Exception) { null } },
                        intent.getLongExtra(RoutineIntents.EXTRA_AT, 0L))
                    RoutineIntents.ACTION_READY -> RoutineHub.openReady(intent.getStringExtra(RoutineIntents.ARG_READY) ?: RoutineIntents.ALL_READY)
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED ->
                        RoutineHub.onClockChanged()
                }
            } finally {
                done.finish()
            }
        }
    }
}
