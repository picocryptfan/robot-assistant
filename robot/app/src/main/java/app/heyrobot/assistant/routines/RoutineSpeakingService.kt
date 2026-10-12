package app.heyrobot.assistant.routines

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import app.heyrobot.R
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.car.CarNotices
import app.heyrobot.core.car.CarPhase
import app.heyrobot.core.BriefControl
import app.heyrobot.core.RLog
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineTexts
import app.heyrobot.core.SpeechWay
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.RoutineIntents

// VOICE PROCESS (0.8). The routine's voice when nobody opened Robot (design §1.2): a mediaPlayback foreground service, not
// exported, in :voice where the voice is, alive only while a run speaks. Android 17 lets audio play in the background only
// from a visible activity or a foreground service that isn't short; it needs FOREGROUND_SERVICE_MEDIA_PLAYBACK (a normal
// permission, no prompt). Its notification is how you see and stop it: "Robot is reading your morning brief" with Next
// and Stop, and on the lock screen only "Robot is speaking". If Android refuses to start it from the background, the run
// waits on a "Morning brief ready · Play" notification instead, and your tap starts it. Never sticky, never at boot.

class RoutineSpeakingService : Service() {
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_PLAY -> {
                // Foreground first, within Android's few seconds; the voice plays once it is.
                if (!goForeground()) {
                    VoiceHub.main.post { RoutineVoice.now?.let(RoutineVoice::refused) }
                    stopSelf()
                    return START_NOT_STICKY
                }
                if (intent.action == ACTION_PLAY) VoiceHub.main.post { RoutineVoice.playWaiting() } else VoiceHub.main.post { RoutineVoice.serviceReady() }
            }
            ACTION_NEXT -> VoiceHub.main.post { RoutineVoice.control(BriefControl.NEXT) }
            ACTION_REPEAT -> VoiceHub.main.post { RoutineVoice.control(BriefControl.REPEAT) }
            ACTION_STOP -> VoiceHub.main.post { RoutineVoice.control(BriefControl.STOP); if (!RoutineVoice.active) done() }
            // 0.8: car mode keeps this service in the foreground for the drive (its notification, with End).
            ACTION_CAR -> {
                if (!goForeground()) {
                    VoiceHub.main.post { CarModeController.serviceRefused() }
                    stopSelf()
                    return START_NOT_STICKY
                }
                VoiceHub.main.post { CarNotices.cancelStart(this); if (CarModeController.state.phase == CarPhase.OFF) CarModeController.start(); CarModeController.serviceStarted() }
            }
            ACTION_CAR_END -> VoiceHub.main.post { CarModeController.end() }
            else -> if (!RoutineVoice.active && !CarModeController.on) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        val r = RoutineVoice.now?.routine
        // A routine speaking shows its own notification; car mode's otherwise (0.8).
        val n = if (r == null && carHolds()) CarNotices.notification(this) else RoutineNotices.speaking(this, r)
        startForeground(RoutineNotices.SPEAKING_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        foreground = true
        true
    } catch (t: Throwable) {
        // ForegroundServiceStartNotAllowedException (started from the background without an exemption), or the type refused.
        RLog.w("routine_foreground", t)
        false
    }

    /** The run is over: out of the foreground, notification gone, service stopped (car mode on: its notification again). */
    fun done() {
        if (carHolds() && goForeground()) return
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        foreground = false
        stopSelf()
    }

    companion object {
        @Volatile var instance: RoutineSpeakingService? = null

        const val ACTION_START = "app.heyrobot.routines.SPEAK"
        const val ACTION_PLAY = "app.heyrobot.routines.PLAY"
        const val ACTION_NEXT = "app.heyrobot.routines.NEXT"
        const val ACTION_REPEAT = "app.heyrobot.routines.REPEAT"
        const val ACTION_STOP = "app.heyrobot.routines.STOP"
        /** 0.8, car mode: in the foreground for the drive, and its notification's End. */
        const val ACTION_CAR = "app.heyrobot.car.START"
        const val ACTION_CAR_END = "app.heyrobot.car.END"

        /** Car mode is starting or on: the service stays for it. */
        private fun carHolds(): Boolean = CarModeController.state.phase != CarPhase.OFF

        /** Car mode starts the service; false when Android refused (ForegroundServiceStartNotAllowedException). */
        fun startCar(ctx: Context): Boolean = try {
            ctx.startForegroundService(Intent(ctx, RoutineSpeakingService::class.java).setAction(ACTION_CAR))
            true
        } catch (t: Throwable) {
            RLog.w("car_start_service", t)
            false
        }

        /** Car mode ended: the service goes once no routine speaks. */
        fun carEnded() {
            if (!RoutineVoice.active) instance?.done()
        }

        /** Car mode's notification again (the trusted session, what was kept). */
        fun refreshCar() {
            val s = instance ?: return
            if (carHolds() && !RoutineVoice.active) s.goForeground()
        }

        /** Starts the service for [r]'s speech; false when Android refused (ForegroundServiceStartNotAllowedException). */
        fun start(ctx: Context, r: Routine): Boolean = try {
            ctx.startForegroundService(Intent(ctx, RoutineSpeakingService::class.java).setAction(ACTION_START))
            RLog.i("routine_speech", "service" to true, "brief" to (r.steps.size == 1))
            true
        } catch (t: Throwable) {
            RLog.w("routine_start_service", t)
            false
        }
    }
}

/**
 * Routines' notifications: the speaking one (the service's), things ready for you, a run's summary when something waited
 * or failed, "ready · Play" when Android refused to speak, "Continue your brief" after the unlock, and "Open Robot" when
 * Robot isn't the assistant. On the lock screen each shows only its public version: never a routine's steps or words.
 */
object RoutineNotices {
    const val SPEAKING_ID = 8_410
    private const val READY_ID = 8_411
    private const val SUMMARY_ID = 8_412
    private const val PLAY_ID = 8_413
    private const val CONTINUE_ID = 8_414
    private const val NOT_ASSISTANT_ID = 8_415
    private const val SILENT_ID = 8_416
    private const val CHANNEL_SPEAKING = "routine_speaking"
    private const val CHANNEL = "routines"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SPEAKING, "Routine speaking", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while a routine or the morning brief speaks, with Next and Stop."
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Routines", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Things a routine left for you to tap, and routines that couldn't speak."
            setSound(null, null)
        })
    }

    private fun service(ctx: Context, req: Int, action: String): PendingIntent = PendingIntent.getService(ctx, req,
        Intent(ctx, RoutineSpeakingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** A tap that opens the panel with the Ready cards (through the :voice receiver, which shows the session). */
    private fun ready(ctx: Context, key: String): PendingIntent = PendingIntent.getBroadcast(ctx, 8_420 + (key.hashCode() and 0xff),
        Intent(RoutineIntents.ACTION_READY).setClassName(ctx.packageName, RoutineIntents.RECEIVER).putExtra(RoutineIntents.ARG_READY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openRoutines(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 8_430,
        Intent().setClassName(ctx.packageName, "app.heyrobot.ui.MainActivity").putExtra(AppPages.EXTRA, AppPages.ROUTINES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun notify(ctx: Context, id: Int, n: Notification) {
        try { nm(ctx).notify(id, n) } catch (t: Throwable) { RLog.w("routine_notify", t) }
    }

    private fun publicVersion(ctx: Context, channel: String, title: String) =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_robot).setContentTitle(title).build()

    /** The service's notification while a run speaks. */
    fun speaking(ctx: Context, r: Routine?): Notification {
        channels(ctx)
        val title = r?.let(RoutineTexts::speaking) ?: RoutineTexts.SPEAKING_PUBLIC
        return Notification.Builder(ctx, CHANNEL_SPEAKING)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText("Say “hey robot, next”, or tap a button.")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL_SPEAKING, RoutineTexts.SPEAKING_PUBLIC))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, "Next", service(ctx, 8_401, RoutineSpeakingService.ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(null, "Repeat", service(ctx, 8_402, RoutineSpeakingService.ACTION_REPEAT)).build())
            .addAction(Notification.Action.Builder(null, "Stop", service(ctx, 8_403, RoutineSpeakingService.ACTION_STOP)).build())
            .build()
    }

    /** "Robot has 2 things ready for you": the cards' words only unlocked; a tap opens the panel with them. */
    fun ready(ctx: Context, cards: List<ReadyCard>) {
        if (cards.isEmpty()) return cancel(ctx, READY_ID)
        channels(ctx)
        val title = ReadyCards.publicLine(cards.size)
        val text = cards.joinToString("\n") { it.text() }
        notify(ctx, READY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(cards.last().text())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL, title))
            .setContentIntent(ready(ctx, RoutineIntents.ALL_READY))
            .setAutoCancel(true)
            .build())
    }

    /** "Ran “Leaving work” · 2 done, 1 ready": silent, only when something waited or didn't work. */
    fun summary(ctx: Context, text: String?) {
        text ?: return
        channels(ctx)
        notify(ctx, SUMMARY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(text)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL, "Robot ran a routine"))
            .setContentIntent(openRoutines(ctx))
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build())
    }

    /** Android didn't let the run speak by itself: "Morning brief ready · Play". */
    fun playLater(ctx: Context, r: Routine) {
        channels(ctx)
        val play = PendingIntent.getForegroundService(ctx, 8_404, Intent(ctx, RoutineSpeakingService::class.java).setAction(RoutineSpeakingService.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify(ctx, PLAY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.readyToPlay(r))
            .setContentText(RoutineTexts.PLAY_TEXT)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL, "Robot has something to say"))
            .setContentIntent(play)
            .addAction(Notification.Action.Builder(null, "Play", play).build())
            .setAutoCancel(true)
            .build())
    }

    fun cancelPlayLater(ctx: Context) = cancel(ctx, PLAY_ID)

    /** Unlocked within 15 minutes with the rest of the brief waiting, and Android didn't let it speak: a tap does. */
    fun continueBrief(ctx: Context) {
        channels(ctx)
        val play = PendingIntent.getForegroundService(ctx, 8_405, Intent(ctx, RoutineSpeakingService::class.java).setAction(RoutineSpeakingService.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        notify(ctx, CONTINUE_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.CONTINUE)
            .setContentText(RoutineTexts.CONTINUE_TEXT)
            .setContentIntent(play)
            .setAutoCancel(true)
            .build())
    }

    /** Robot isn't the assistant, so nothing can speak: "Your morning brief is ready · Open Robot". */
    fun notAssistant(ctx: Context, r: Routine) {
        channels(ctx)
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.NO_ASSISTANT)
        notify(ctx, NOT_ASSISTANT_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.notAssistant(r))
            .setContentText(RoutineTexts.NOT_ASSISTANT_TEXT)
            .setStyle(Notification.BigTextStyle().bigText(RoutineTexts.NOT_ASSISTANT_TEXT))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL, "Robot has something for you"))
            .setContentIntent(openRoutines(ctx))
            .addAction(Notification.Action.Builder(null, "Open Robot", openRoutines(ctx)).build())
            .setAutoCancel(true)
            .build())
    }

    /** "Don't speak (notification only)": what the run had to say, in the notification (its words only unlocked). */
    fun silent(ctx: Context, r: Routine, text: String) {
        if (text.isBlank()) return
        channels(ctx)
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.SILENT)
        notify(ctx, SILENT_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.silentTitle(r))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL, "Robot ran a routine"))
            .setAutoCancel(true)
            .build())
    }

    private fun cancel(ctx: Context, id: Int) {
        try { nm(ctx).cancel(id) } catch (_: Throwable) {}
    }
}
