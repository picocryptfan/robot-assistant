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
import app.heyrobot.core.update.UpdateText
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant

// MAIN PROCESS. The "Updates" notification channel: an update available or ready to install (tap: Settings › Updates), the
// confirmation Android asks for when Robot isn't on screen, an install that failed, and once after an update, "Robot updated
// to …". Each returns whether it was posted (notifications can be off), so what wasn't said can be said in the app.

object UpdateNotices {
    const val CHANNEL = "updates"
    private const val ID = 78
    private const val REQ_OPEN = 7_802
    private const val REQ_CONFIRM = 7_803

    private fun manager(ctx: Context): NotificationManager? = ctx.getSystemService(NotificationManager::class.java)

    private fun channel(nm: NotificationManager) {
        nm.createNotificationChannel(NotificationChannel(CHANNEL, UpdateText.CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = UpdateText.CHANNEL_DESCRIPTION
        })
    }

    /** Settings › Updates in the app. */
    private fun openUpdates(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, REQ_OPEN,
        Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun post(ctx: Context, title: String, text: String, open: PendingIntent): Boolean {
        val nm = manager(ctx) ?: return false
        if (!Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) || !nm.areNotificationsEnabled()) return false
        return try {
            channel(nm)
            if (nm.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) return false
            nm.notify(ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_RECOMMENDATION)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            true
        } catch (t: Throwable) {
            RLog.w("update_notify", t)
            false
        }
    }

    fun available(ctx: Context, version: String) = post(ctx, UpdateText.availableTitle(version), UpdateText.AVAILABLE_TEXT, openUpdates(ctx))

    fun ready(ctx: Context, version: String) = post(ctx, UpdateText.readyTitle(version), UpdateText.READY_TEXT, openUpdates(ctx))

    fun updated(ctx: Context, version: String) = post(ctx, UpdateText.updatedTitle(version), UpdateText.UPDATED_TEXT, openUpdates(ctx))

    fun failed(ctx: Context, text: String) = post(ctx, "Robot's update wasn't installed", text, openUpdates(ctx))

    /** Android's confirmation window, when Robot couldn't open it itself (not on screen). */
    fun confirm(ctx: Context, confirm: Intent) = post(ctx, UpdateText.CONFIRM_TITLE, UpdateText.CONFIRM_TEXT,
        PendingIntent.getActivity(ctx, REQ_CONFIRM, confirm, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))

    fun cancel(ctx: Context) {
        try { manager(ctx)?.cancel(ID) } catch (_: Throwable) {}
    }
}
