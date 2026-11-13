// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.later

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.EverydayStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.online.Later
import app.heyrobot.core.online.LaterItem
import app.heyrobot.sys.Assistant
import java.time.Instant
import java.time.ZoneId

// Main and voice processes. Signal later's notices: a low-importance channel of its own (no sound, no vibration: silent
// on any phone setting), hidden on the lock screen (a public line only, "Robot has an answer for you"). The answer, or
// "Back online: your question from 2:14 PM needs your OK". A tap opens Robot; the chat shows it on the next unlocked show.
object LaterNotices {
    private const val CHANNEL = "later" // l10n: not text (a channel id)
    private const val BASE_ID = 48_060

    fun clock(at: Long): Arg = Arg.Clock(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalTime())

    fun answer(ctx: Context, item: LaterItem, text: String) {
        val l = Langs.of(item.lang)
        post(ctx, item, EverydayStrings.laterAnswerTitle("t" to clock(item.askedAt)).render(l), text)
    }

    /** Goes by itself when its question expires ([Later.WAIT_MS] after it was asked), so its words never outlast the queue's. */
    fun ask(ctx: Context, item: LaterItem, now: Long = System.currentTimeMillis()) {
        val left = Later.WAIT_MS - (now - item.askedAt)
        if (left <= 0) {
            cancel(ctx, listOf(item))
            return
        }
        val l = Langs.of(item.lang)
        post(ctx, item, EverydayStrings.laterAskTitle("t" to clock(item.askedAt)).render(l), EverydayStrings.laterAskText("q" to Arg.Plain(item.said)).render(l), left)
    }

    fun cancel(ctx: Context, items: Collection<LaterItem>) = try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        items.forEach { nm.cancel(id(it)) }
    } catch (t: Throwable) {
        RLog.w("later_notice_cancel", t)
    }

    private fun id(item: LaterItem): Int = BASE_ID + (item.id.hashCode() and 0xFFF)

    private fun post(ctx: Context, item: LaterItem, title: String, text: String, timeout: Long = 0) { try {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val l = Langs.of(item.lang)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, EverydayStrings.laterChannel().render(l), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            })
        }
        if (Assistant.granted(ctx, Manifest.permission.POST_NOTIFICATIONS) && nm.areNotificationsEnabled()) {
            val open = PendingIntent.getActivity(ctx, id(item), Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity") // l10n: not text (a class name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val public = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(EverydayStrings.laterPublic().render(l)).build()
            nm.notify(id(item), Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setTimeoutAfter(timeout)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .setPublicVersion(public)
                .build())
        }
    } catch (t: Throwable) {
        RLog.w("later_notice", t)
    } }
}
