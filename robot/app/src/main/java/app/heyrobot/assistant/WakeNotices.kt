// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import app.heyrobot.R
import app.heyrobot.core.RLog

// week-fixes: Battery Saver pauses the all-day wake word. Saying a phrase and getting nothing looks like a broken Robot, so
// one quiet notification says why, for as long as the pause lasts; it holds no words of yours. Settings › Voice says it too.
object WakeNotices {
    private const val ID = 8_450
    private const val CHANNEL = "wake_word_paused"
    @Volatile private var shown = false

    /** [paused]: the wake word would listen but for Battery Saver. Only a change does anything. */
    fun batterySaver(ctx: Context, paused: Boolean) {
        if (paused == shown) return
        shown = paused
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (!paused) return nm.cancel(ID)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.voice_saver_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            })
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let {
                PendingIntent.getActivity(ctx, ID, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            nm.notify(ID, Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(ctx.getString(R.string.voice_saver_paused))
                .setContentText(ctx.getString(R.string.voice_saver_paused_detail))
                .setStyle(Notification.BigTextStyle().bigText(ctx.getString(R.string.voice_saver_paused_detail)))
                .setOngoing(false)
                .setContentIntent(open)
                .build())
        } catch (t: Throwable) { RLog.w("wake_notice", t) }
    }
}
