// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.lock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.EndEarly
import app.heyrobot.core.lock.LockSchedule
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.AppPages

// :LOCK PROCESS ONLY. Phone lock's notices, on one channel, "Phone lock": the warning before a timed lock
// starts (with Skip this time when End early is With the code), the quiet ongoing notice while a lock is on (its tap
// opens the cover), and "couldn't start" when a lock came due with the service's access off (its tap opens Settings ›
// Phone lock). They name a lock and a time, never what you were doing.

object LockNotices {
    private const val CHANNEL = "lock"
    /** The ongoing notice's own channel: quiet (the warnings' channel is high). */
    private const val CHANNEL_ON = "lock_on"
    private const val ID_WARN = 8_601
    private const val ID_ON = 8_602
    private const val ID_FAILED = 8_603
    private const val ID_UNSAVED = 8_605

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun channel(ctx: Context) {
        nm(ctx)?.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.lock_channel), NotificationManager.IMPORTANCE_HIGH).apply {
            description = ctx.getString(R.string.lock_channel_detail)
        })
        nm(ctx)?.createNotificationChannel(NotificationChannel(CHANNEL_ON, ctx.getString(R.string.lock_channel_on), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.lock_channel_on_detail)
        })
    }

    private fun base(ctx: Context, channel: String = CHANNEL) = Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_robot).setShowWhen(false)

    /**
     * [access]: the service is on. Without it nothing is covered, so there is no "opens at" notice: a lock that came due
     * stays in state.json (turning the access back on within its time enforces it) and "couldn't start" says why.
     */
    fun update(ctx: Context, s: LockEngine.Snapshot, access: Boolean, couldNotStart: List<ActiveLock>) {
        try {
            channel(ctx)
            val l = AppLanguage.langs(ctx)
            val nm = nm(ctx) ?: return
            // While a lock is on.
            val opens = s.opensAt?.takeIf { access }
            // Phase B: One app first ends on time in its app, so it shows its progress rather than a time.
            val progress = LockSchedule.appLocks(s.file, s.state).firstOrNull()?.takeIf { access }
            if (opens != null || progress != null) {
                val tap = PendingIntent.getActivity(ctx, ID_ON, LockSignals.cover(ctx).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                val title = if (opens != null) LockStrings.noticeOn("time" to opens.toLocalTime()).render(l) else progress!!.let { (a, r) ->
                    LockStrings.noticeProgress("done" to a.usedMs / 60_000, "total" to r.minutes, "label" to label(ctx, r.app)).render(l)
                }
                nm.notify(ID_ON, base(ctx, CHANNEL_ON).setContentTitle(title)
                    .setContentText(ctx.getString(R.string.lock_notice_on_text)).setOngoing(true).setOnlyAlertOnce(true)
                    .setContentIntent(tap).build())
            } else nm.cancel(ID_ON)
            // Before one starts.
            val w = LockSchedule.warning(s.file, s.state, s.clocks.now)
            if (w != null) {
                val b = base(ctx).setContentTitle(LockStrings.warnTitle("time" to w.start.toLocalTime(), "name" to w.lock.name).render(l))
                    .setContentText((w.lock.rule as? app.heyrobot.core.lock.LockRule.App)?.let { r -> LockStrings.warnBodyApp("n" to r.minutes, "label" to label(ctx, r.app)) }
                        ?.render(l) ?: LockStrings.warnBody("end" to w.end.toLocalTime()).render(l)).setOnlyAlertOnce(true).setTimeoutAfter(
                        java.time.Duration.between(s.clocks.now, w.start).toMillis().coerceAtLeast(1_000))
                // Skip this time follows End early: with the code, the code pad; Off, no button (a wait can't come before the start).
                if (s.file?.settings?.effectiveEndEarly == EndEarly.CODE) {
                    val skip = PendingIntent.getActivity(ctx, ID_WARN, LockSignals.code(ctx, LockSignals.PURPOSE_SKIP, w.lock.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                    b.addAction(Notification.Action.Builder(null, ctx.getString(R.string.lock_warn_skip), skip).build())
                }
                nm.notify(ID_WARN, b.build())
            } else nm.cancel(ID_WARN)
            // A lock came due with the access off.
            couldNotStart.firstOrNull()?.let { a ->
                val name = s.file?.lock(a.lock)?.name
                val open = PendingIntent.getActivity(ctx, ID_FAILED, Intent().setClassName(ctx.packageName, MAIN).putExtra(AppPages.EXTRA, AppPages.PHONE_LOCK)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
                nm.notify(ID_FAILED, base(ctx).setContentTitle(if (name != null) LockStrings.noticeFailedName("name" to name).render(l) else ctx.getString(R.string.lock_notice_failed))
                    .setContentIntent(open).setAutoCancel(true).build())
            }
        } catch (t: Throwable) {
            RLog.w("lock_notice", t)
        }
    }

    /**
     * state.json couldn't be written ([on]; storage full): "Phone lock couldn't save its state", until a write works. Locks
     * still follow the state :lock worked out, but the other processes, and :lock after a restart, don't see it.
     */
    fun unsaved(ctx: Context, on: Boolean) {
        try {
            val nm = nm(ctx) ?: return
            if (!on) {
                nm.cancel(ID_UNSAVED)
                return
            }
            channel(ctx)
            val l = AppLanguage.langs(ctx)
            nm.notify(ID_UNSAVED, base(ctx).setContentTitle(LockStrings.noticeUnsaved().render(l)).setContentText(LockStrings.noticeUnsavedText().render(l))
                .setOnlyAlertOnce(true).build())
        } catch (t: Throwable) {
            RLog.w("lock_notice_unsaved", t)
        }
    }

    /** An app's name as the launcher shows it (its package when it can't be read). */
    fun label(ctx: Context, pkg: String): String = try {
        ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Throwable) { pkg }

    /** Phase B: a One app first lock ended by its time in the app: "20 minutes in Libby: your phone is open." */
    fun appDone(ctx: Context, file: app.heyrobot.core.lock.LockFile?, a: ActiveLock) {
        try {
            val r = file?.lock(a.lock)?.rule as? app.heyrobot.core.lock.LockRule.App ?: return
            channel(ctx)
            nm(ctx)?.notify(ID_OPEN, base(ctx).setContentTitle(LockStrings.noticeOpen(r.minutes.toLong(), "label" to label(ctx, r.app)).render(AppLanguage.langs(ctx)))
                .setAutoCancel(true).setTimeoutAfter(30 * 60_000L).build())
        } catch (t: Throwable) {
            RLog.w("lock_notice_open", t)
        }
    }

    private const val ID_OPEN = 8_604
    private const val MAIN = "app.heyrobot.ui.MainActivity"
}
