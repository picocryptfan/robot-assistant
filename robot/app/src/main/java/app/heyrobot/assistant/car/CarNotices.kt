package app.heyrobot.assistant.car

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import app.heyrobot.R
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.handsfree.PanelHandsFree
import app.heyrobot.assistant.routines.RoutineSpeakingService
import app.heyrobot.core.RLog
import app.heyrobot.core.car.CarScreen
import app.heyrobot.core.car.CarModeMachine
import app.heyrobot.core.car.ParkList
import app.heyrobot.sys.HandsFreeFiles
import app.heyrobot.sys.RoutineIntents

// VOICE PROCESS (0.8). Car mode's notifications (design §4.4, §4.6, §4.8): the speaking service's while car mode is on
// ("Car mode", or "Car mode · unlocked features" in the trusted session, with End; the car screen on a tap), "Car
// connected · Start car mode" when Android didn't let car mode start by itself (a tap starts it), and "From your drive: 2
// things" when car mode ends with something kept for when you park (a tap opens the panel with them to ask again). On the
// lock screen each shows only its public version: never what was kept.

object CarNotices {
    private const val START_ID = 8_440
    private const val PARKED_ID = 8_441
    private const val CHANNEL = "car_mode"

    private fun channel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Car mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while car mode is on, when your car connected, and what you kept for when you park."
                setSound(null, null)
                enableVibration(false)
            })
    }

    private fun lang() = HandsFreeHub.lang

    /** The speaking service's notification while car mode is on. */
    fun notification(ctx: Context): Notification {
        channel(ctx)
        val title = CarScreen.title(CarModeController.trusted, lang())
        val kept = HandsFreeFiles.store(ctx).parked().size
        val text = ParkList.status(kept, lang()) ?: "Say “hey robot”, or press your car's voice button."
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
     * Car mode ended: "From your drive: 2 things", the kept words only unlocked; a tap opens the panel with them. 1.0:
     * VISIBILITY_SECRET, never on the secure lock screen (PRIVATE shows the words there under Android's default setting).
     */
    fun parked(ctx: Context) {
        cancelStart(ctx)
        val list = HandsFreeFiles.store(ctx).parked()
        val title = ParkList.notice(list.size, lang()) ?: return cancel(ctx, PARKED_ID)
        channel(ctx)
        val open = PendingIntent.getBroadcast(ctx, 8_445, Intent(RoutineIntents.ACTION_PARKED).setClassName(ctx.packageName, RoutineIntents.RECEIVER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = list.joinToString("\n") { it.item.summary }
        notify(ctx, PARKED_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(list.last().item.summary)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_robot).setContentTitle(title).build())
            .setContentIntent(open)
            .setAutoCancel(true)
            .build())
    }

    /** The "From your drive" tap: the panel opens with what you kept, to ask again. */
    fun openParked() {
        val vis = app.heyrobot.assistant.RobotVoiceService.instance ?: return
        app.heyrobot.assistant.VoiceHub.main.post {
            vis.show(app.heyrobot.assistant.RobotVoiceService.SRC_APP, android.os.Bundle().apply { putString(PanelHandsFree.ARG_PARKED, "1") })
        }
    }

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (t: Throwable) { RLog.w("car_notify", t) }
    }

    private fun cancel(ctx: Context, id: Int) {
        try { ctx.getSystemService(NotificationManager::class.java).cancel(id) } catch (_: Throwable) {}
    }
}
