// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.update

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.backup.BackupReminder
import app.heyrobot.core.i18n.BackupStrings
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.BackupFiles

// MAIN PROCESS. "Remind me to back up every month" (1.1, docs/plans/backup-1.1-design.md §9.5): job 4804 runs daily
// while the switch is on (UpdateJobs.ensureBackupReminder) and, when core BackupReminder says it's due (the last backup,
// or the switch turned on, 30 days old, and no reminder in the last 7), posts a low-importance notice that opens
// Settings › Back up and restore. Nothing personal is in it. Robot can't back up by itself: it never keeps the passphrase.

object BackupReminders {
    const val CHANNEL = "backup_reminders"
    private const val ID = 8_701
    private const val REQ_OPEN = 8_702

    /** The job's run (background thread). */
    fun run(ctx: Context, now: Long = System.currentTimeMillis()) {
        val p = BackupFiles.prefs(ctx)
        val last = BackupFiles.state(ctx).lastAt
        if (!BackupReminder.due(now, last, p.remindSince, p.lastShown, p.remind)) return
        if (post(ctx, BackupReminder.daysSince(now, last))) BackupFiles.savePrefs(ctx, p.copy(lastShown = now))
    }

    /** The switch: saved in backup.json, and the job scheduled or cancelled with it. */
    fun set(ctx: Context, on: Boolean, now: Long = System.currentTimeMillis()) {
        val p = BackupFiles.prefs(ctx)
        BackupFiles.savePrefs(ctx, p.copy(remind = on, remindSince = if (on && !p.remind) now else p.remindSince))
        UpdateJobs.ensureBackupReminder(ctx, on)
    }

    private fun post(ctx: Context, days: Int?): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return false
        val langs = AppLanguage.langs(ctx)
        if (!Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) || !nm.areNotificationsEnabled()) return false
        return try {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, BackupStrings.remindChannel().render(langs), NotificationManager.IMPORTANCE_LOW).apply {
                description = BackupStrings.remindChannelDetail().render(langs)
            })
            if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            val text = (if (days == null) BackupStrings.remindNever() else BackupStrings.remindDays(days.toLong())).render(langs)
            val open = PendingIntent.getActivity(ctx, REQ_OPEN,
                Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.BACKUP)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            nm.notify(ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(BackupStrings.remindTitle().render(langs))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_REMINDER)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            true
        } catch (t: Throwable) {
            RLog.w("backup_remind", t)
            false
        }
    }
}
