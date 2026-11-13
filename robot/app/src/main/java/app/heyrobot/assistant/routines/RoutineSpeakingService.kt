// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import app.heyrobot.core.MomentReminder
import app.heyrobot.core.MomentReminders
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
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
import app.heyrobot.core.i18n.Langs
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.LockShade
import app.heyrobot.sys.ProtectedBroadcasts
import app.heyrobot.sys.RoutineIntents

// VOICE PROCESS (0.8). The routine's voice when nobody opened Robot: a mediaPlayback foreground service, not
// exported, in :voice where the voice is, alive only while a run speaks. Android 17 lets audio play in the background only
// from a visible activity or a foreground service that isn't short; it needs FOREGROUND_SERVICE_MEDIA_PLAYBACK (a normal
// permission, no prompt). Its notification is how you see and stop it: "Robot is reading your morning brief" with Next
// and Stop, and while the phone is locked only "Robot is speaking" (with the same buttons). If Android refuses to start it from the background, the run
// waits on a "Morning brief ready · Play" notification instead, and your tap starts it. Never sticky, never at boot.

class RoutineSpeakingService : Service() {
    private var foreground = false

    /** The screen went off, came on, or you unlocked: the notification's title follows ([RoutineNotices.speaking]). */
    private val lockWatch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = refreshForLock()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        try {
            ProtectedBroadcasts.register(this, lockWatch, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON, Intent.ACTION_SCREEN_OFF))
        } catch (t: Throwable) {
            RLog.w("routine_lock_watch", t)
        }
    }

    override fun onDestroy() {
        try { unregisterReceiver(lockWatch) } catch (_: Throwable) {}
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
            // 1.2.4: the app's Car mode button and shortcut. Car mode by hand first, so the notification is car mode's; on
            // already, its screen again.
            ACTION_CAR_OPEN -> {
                VoiceHub.init(this)
                if (CarModeController.state.phase == CarPhase.OFF) CarModeController.startByHand()
                if (!goForeground()) {
                    CarModeController.serviceRefused()
                    if (!carHolds()) stopSelf()
                    return START_NOT_STICKY
                }
                VoiceHub.main.post { CarNotices.cancelStart(this); CarModeController.serviceStarted(); if (CarModeController.on) CarModeController.openScreen() }
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

    /** While a routine speaks, its notification again, named or not as the lock now says. */
    internal fun refreshForLock(r: Routine? = RoutineVoice.now?.routine) {
        if (foreground && r != null) RoutineNotices.speakingAgain(this, r)
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
        const val ACTION_CAR_END = app.heyrobot.core.car.CarNotice.ACTION_END
        const val ACTION_CAR_OPEN = app.heyrobot.core.car.CarNotice.ACTION_OPEN

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
 * Robot isn't the assistant. 1.0 audit (secrets-lockscreen-notification-content): VISIBILITY_PRIVATE hides nothing under
 * Android's default "Show sensitive content when locked", so the ones that hold a routine's steps, the brief's words or
 * a routine's name are VISIBILITY_SECRET: never on the secure lock screen (their redacted public version is kept for
 * wherever Android uses it). Only the speaking one shows there, because SECRET would hide its Next, Repeat and Stop; so
 * while the phone is locked its own title is the public one, and it names the routine again after the unlock.
 */
object RoutineNotices {
    const val SPEAKING_ID = 8_410
    private const val READY_ID = LockShade.READY
    private const val SUMMARY_ID = LockShade.SUMMARY
    private const val PLAY_ID = LockShade.PLAY
    private const val CONTINUE_ID = LockShade.CONTINUE
    private const val NOT_ASSISTANT_ID = 8_415
    private const val CHANNEL_SPEAKING = "routine_speaking"
    private const val CHANNEL = "routines"

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_SPEAKING, ctx.getString(R.string.routine_channel_speaking), NotificationManager.IMPORTANCE_LOW).apply {
            description = ctx.getString(R.string.routine_channel_speaking_detail)
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL, ctx.getString(R.string.routine_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = ctx.getString(R.string.routine_channel_detail)
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

    private fun notify(ctx: Context, id: Int, n: Notification, tag: String? = null) {
        // review-lock 10: a routine's words aren't left in the shade during a lock for someone else.
        if (id != SPEAKING_ID && id != NOT_ASSISTANT_ID && PhoneLockNow.someoneElse(ctx)) return
        try { nm(ctx).notify(tag, id, n) } catch (t: Throwable) { RLog.w("routine_notify", t) }
    }

    private fun publicVersion(ctx: Context, channel: String, title: String) =
        Notification.Builder(ctx, channel).setSmallIcon(R.drawable.ic_robot).setContentTitle(title).build()

    /** 1.1: the routine's words in the reply-language setting, else the menus' (core ReplyLang.unheard); buttons in the menus'. */
    private fun langs(ctx: Context): Langs = BriefSource.langs(ctx)

    private fun publicSpeaking(l: Langs): String = app.heyrobot.core.i18n.RoutineStrings.speaking().render(l)

    /** The phone is in your hands and unlocked; any doubt is locked. */
    private fun unlocked(ctx: Context): Boolean = try {
        !ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked && ctx.getSystemService(PowerManager::class.java).isInteractive
    } catch (t: Throwable) {
        RLog.w("routine_keyguard", t)
        false
    }

    /** The service's notification while a run speaks: the routine's name only while unlocked, the buttons always. */
    fun speaking(ctx: Context, r: Routine?): Notification {
        channels(ctx)
        val l = langs(ctx)
        val title = r?.takeIf { unlocked(ctx) }?.let { RoutineTexts.speakingText(it).render(l) } ?: publicSpeaking(l)
        return Notification.Builder(ctx, CHANNEL_SPEAKING)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(ctx.getString(R.string.routine_speaking_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(ctx, CHANNEL_SPEAKING, publicSpeaking(l)))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.routine_next), service(ctx, 8_401, RoutineSpeakingService.ACTION_NEXT)).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.routine_repeat), service(ctx, 8_402, RoutineSpeakingService.ACTION_REPEAT)).build())
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.routine_stop), service(ctx, 8_403, RoutineSpeakingService.ACTION_STOP)).build())
            .build()
    }

    /** "Robot has 2 things ready for you": the cards' words only unlocked; a tap opens the panel with them. */
    fun speakingAgain(ctx: Context, r: Routine) = notify(ctx, SPEAKING_ID, speaking(ctx, r))

    fun ready(ctx: Context, cards: List<ReadyCard>) {
        if (cards.isEmpty()) return cancel(ctx, READY_ID)
        channels(ctx)
        val l = langs(ctx)
        val title = ReadyCards.publicText(cards.size).render(l)
        val lines = cards.map { it.phrase().render(l) }
        val text = lines.joinToString("\n")
        notify(ctx, READY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(lines.last())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
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
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(ctx, CHANNEL, ctx.getString(R.string.routine_ran_public)))
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
        val l = langs(ctx)
        notify(ctx, PLAY_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.readyToPlayText(r).render(l))
            .setContentText(RoutineTexts.playText.render(l))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(ctx, CHANNEL, ctx.getString(R.string.routine_something_to_say)))
            .setContentIntent(play)
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.routine_play), play).build())
            .setAutoCancel(true)
            .build())
    }

    fun cancelPlayLater(ctx: Context) = cancel(ctx, PLAY_ID)

    /** Unlocked within 15 minutes with the rest of the brief waiting, and Android didn't let it speak: a tap does. */
    fun continueBrief(ctx: Context) {
        channels(ctx)
        val play = PendingIntent.getForegroundService(ctx, 8_405, Intent(ctx, RoutineSpeakingService::class.java).setAction(RoutineSpeakingService.ACTION_PLAY),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val l = langs(ctx)
        notify(ctx, CONTINUE_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(app.heyrobot.core.i18n.RoutineStrings.continueBrief().render(l))
            .setContentText(app.heyrobot.core.i18n.RoutineStrings.continueText().render(l))
            .setContentIntent(play)
            .setAutoCancel(true)
            .build())
    }

    /** Robot isn't the assistant, so nothing can speak: "Your morning brief is ready · Open Robot". */
    fun notAssistant(ctx: Context, r: Routine) {
        channels(ctx)
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.NO_ASSISTANT)
        val l = langs(ctx)
        val text = app.heyrobot.core.i18n.RoutineStrings.notAssistant().render(l)
        notify(ctx, NOT_ASSISTANT_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.notAssistantText(r).render(l))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(ctx, CHANNEL, ctx.getString(R.string.routine_something_for_you)))
            .setContentIntent(openRoutines(ctx))
            .addAction(Notification.Action.Builder(null, ctx.getString(R.string.routine_open_robot), openRoutines(ctx)).build())
            .setAutoCancel(true)
            .build())
    }

    /** "Don't speak (notification only)": what the run had to say, in the notification (its words only unlocked). */
    fun silent(ctx: Context, r: Routine, text: String) {
        if (text.isBlank()) return
        val l = langs(ctx)
        channels(ctx)
        VoiceLog.add(VoiceEvent.ROUTINE_SPEECH, SpeechWay.SILENT)
        // fix-126-review 1: under an id of its own. A quiet brief's first notice stays when the rest, held for the unlock,
        // comes in a second one, and two routines in one quiet stretch both stay; the oldest goes only past 16.
        val shown = try {
            nm(ctx).activeNotifications.filter { it.id in LockShade.SILENT_BASE until LockShade.SILENT_BASE + LockShade.SILENT_SLOTS }
                .associate { it.id to it.postTime }
        } catch (_: Throwable) { emptyMap() }
        notify(ctx, LockShade.silentId(shown), Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(RoutineTexts.silentTitleText(r).render(l))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(ctx, CHANNEL, ctx.getString(R.string.routine_ran_public)))
            .setAutoCancel(true)
            .build())
    }

    /**
     * moment-reminders: a reminder for a moment went off. The channel has no sound of its own, so a phone on silent or Do
     * Not Disturb stays quiet; nothing is spoken. Your words are secret: PRIVATE showed them on the lock screen under
     * Android's default "Show sensitive content when locked" (review-develop-14-b M1). So it isn't unseen until the next
     * unlock (review-develop-14 L2), a locked phone also gets [lockedMoment]'s generic line.
     */
    fun moment(ctx: Context, r: MomentReminder) {
        val l = langs(ctx)
        channels(ctx)
        notify(ctx, LockShade.MOMENT_ID, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(app.heyrobot.core.i18n.RoutineStrings.momentTitle().render(l))
            .setContentText(r.text)
            .setStyle(Notification.BigTextStyle().bigText(r.text))
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setPublicVersion(publicVersion(ctx, CHANNEL, app.heyrobot.core.i18n.RoutineStrings.momentPublic().render(l)))
            .setAutoCancel(true)
            .build(), MomentReminders.noticeTag(r))
        if (!unlocked(ctx)) lockedMoment(ctx, l)
    }

    /** The lock screen's "Reminder": no words, no person; cancelled at the unlock, when the secret one shows in the shade. */
    private fun lockedMoment(ctx: Context, l: Langs) {
        notify(ctx, LockShade.MOMENT_LOCKED, Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(app.heyrobot.core.i18n.RoutineStrings.momentPublic().render(l))
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .build())
        try {
            val app = ctx.applicationContext
            ProtectedBroadcasts.register(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.action != Intent.ACTION_USER_PRESENT) return
                    cancel(app, LockShade.MOMENT_LOCKED)
                    try { app.unregisterReceiver(this) } catch (_: Throwable) {}
                }
            }, ProtectedBroadcasts.filter(Intent.ACTION_USER_PRESENT))
        } catch (t: Throwable) {
            RLog.w("moment_unlock_hook", t)
        }
    }

    private fun cancel(ctx: Context, id: Int) {
        try { nm(ctx).cancel(id) } catch (_: Throwable) {}
    }
}
