// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.routines.RoutineSpeakingService
import app.heyrobot.core.RLog
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.CarModeMachine
import app.heyrobot.sys.HandsFreeFiles

// VOICE PROCESS (0.8). Car mode's notifications (design §4.4, §4.6): the speaking service's while car mode is on ("Car
// mode", or "Car mode · unlocked features" in the trusted session, with End; the car screen on a tap), and "Car connected
// · Start car mode" when Android didn't let car mode start by itself (a tap starts it). On the lock screen each shows only
// its public version.

object CarNotices {
    private const val START_ID = 8_440
    /** 1.0's test builds' "From your drive" notification: only ever cancelled now. */
    private const val LEGACY_PARKED_ID = 8_441
    private const val CHANNEL = "car_mode"

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Car mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while car mode is on, and when your car connected."
                setSound(null, null)
                enableVibration(false)
            })
    }

    private fun lang() = HandsFreeHub.lang

    /** The speaking service's notification while car mode is on. */
    fun notification(ctx: Context): Notification {
        channel(ctx)
        val title = CarScreen.title(CarModeController.trusted, lang())
        val text = if (lang() == app.heyrobot.core.output.SpokenLang.FR) "Dis « hey robot », ou appuie sur le bouton vocal de ton auto."
            else "Say “hey robot”, or press your car's voice button."
        val screen = PendingIntent.getActivity(ctx, 8_442, Intent().setClassName(ctx.packageName, CarModeActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val end = PendingIntent.getService(ctx, 8_443, Intent(ctx, RoutineSpeakingService::class.java).setAction(RoutineSpeakingService.ACTION_CAR_END),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(CarScreen.title(false, lang())).build())
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(screen)
            .addAction(Notification.Action.Builder(null, if (lang() == app.heyrobot.core.output.SpokenLang.FR) "Terminer" else "End", end).build())
            .build()
    }

    /** "Car connected · Start car mode": Android didn't let it start by itself; your tap starts the service. */
    fun startNotice(ctx: Context) {
        channel(ctx)
        val start = PendingIntent.getForegroundService(ctx, 8_444, Intent(ctx, RoutineSpeakingService::class.java).setAction(RoutineSpeakingService.ACTION_CAR),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val line = CarModeMachine.START_NOTICE.get(lang())
        notify(ctx, START_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(line.substringBefore(" · "))
            .setContentText(line.substringAfter(" · "))
            .setContentIntent(start)
            .addAction(Notification.Action.Builder(null, line.substringAfter(" · "), start).build())
            .setAutoCancel(true)
            .build())
    }

    fun cancelStart(ctx: Context) = cancel(ctx, START_ID)

    /**
     * Car mode stopped: "Car connected · Start car mode" goes, and so do what 1.0's test builds kept for when you park
     * (park.json) and their "From your drive" notification, if either is still there.
     */
    fun ended(ctx: Context) {
        cancelStart(ctx)
        cancel(ctx, LEGACY_PARKED_ID)
        HandsFreeFiles.store(ctx).dropLegacy()
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (t: Throwable) { RLog.w("car_notify", t) }
    }

    private fun cancel(ctx: Context, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Throwable) {}
    }
}
