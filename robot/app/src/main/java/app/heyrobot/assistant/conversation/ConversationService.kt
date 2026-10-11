package app.heyrobot.assistant.conversation

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.service.quicksettings.TileService
import app.heyrobot.R
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConvEntry
import app.heyrobot.core.ConvMode
import app.heyrobot.core.ConvPause
import app.heyrobot.core.ConvStop
import app.heyrobot.core.RLog
import app.heyrobot.core.TranscriptTitles
import app.heyrobot.core.enumOr
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.Transcripts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// VOICE PROCESS. The conversation session's foreground service (docs/conversation-mode.md 1.1, 8): microphone type, not
// exported, in the :voice process where the listener and the speech engines live. Its ongoing notification is the
// recording indicator (Pause, Stop, Open; on the lock screen only "Robot is transcribing" and the time); dismissed, it
// comes straight back. Started only from something you did (the transcript screen, the panel, the tile, a shortcut, a
// notification's Resume), never by Robot on its own: START_NOT_STICKY, so Android doesn't restart it either.

class ConversationService : Service() {
    private var receiver: BroadcastReceiver? = null
    private var foreground = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConversationSession.init(this)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> ConversationSession.screen(true)
                    Intent.ACTION_SCREEN_OFF -> ConversationSession.screen(false)
                    // The Robot app deleted transcripts or your voiceprint: VoiceHub hears it too while Robot is the assistant; this covers the rest.
                    Cmd.ACTION -> when (i.getStringExtra("op")) {
                        Cmd.TRANSCRIPTS_DELETED -> ConversationSession.transcriptsDeleted(c, i.getStringExtra(Cmd.EXTRA_ID))
                        Cmd.VOICEPRINT_DELETED -> ConversationSession.voiceprintDeleted()
                    }
                }
            }
        }
        try {
            registerReceiver(r, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }, null, null, Context.RECEIVER_NOT_EXPORTED)
            registerReceiver(r, IntentFilter(Cmd.ACTION), Cmd.PERMISSION, null, Context.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (t: Throwable) { RLog.w("conv_receiver", t) }
    }

    override fun onDestroy() {
        receiver?.let { try { unregisterReceiver(it) } catch (_: Throwable) {} }
        receiver = null
        if (instance === this) instance = null
        // Killed or stopped while a session ran (not through Stop): close it properly.
        if (ConversationSession.running) ConversationSession.stop(ConvStop.ERROR)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RESUME -> {
                // Foreground first (within Android's few seconds), then the work on the session's own thread.
                if (!goForeground()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                val mode = enumOr(intent.getStringExtra(EXTRA_MODE), ConvMode.TRANSCRIBE)
                val entry = enumOr(intent.getStringExtra(Transcripts.EXTRA_ENTRY), ConvEntry.APP)
                val id = if (intent.action == ACTION_RESUME) intent.getStringExtra(Transcripts.EXTRA_ID) else null
                ConversationSession.start(this, mode, if (id != null) ConvEntry.RESUME else entry, id) { done() }
            }
            ACTION_PAUSE -> ConversationSession.userPause()
            ACTION_CONTINUE -> ConversationSession.userResume()
            ACTION_STOP -> ConversationSession.stop(enumOr(intent.getStringExtra(EXTRA_WHY), ConvStop.NOTIFICATION))
            // The notification was swiped away: it is the recording indicator, so it comes back.
            ACTION_REPOST -> if (ConversationSession.running) ConversationNotices.changed(this)
            else -> if (!ConversationSession.running) stopSelf()
        }
        if (!ConversationSession.running && intent?.action !in setOf(ACTION_START, ACTION_RESUME)) {
            if (!foreground) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        startForeground(ConversationNotices.ONGOING_ID, ConversationNotices.ongoing(this, ConversationSession.state.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        foreground = true
        true
    } catch (t: Throwable) {
        // Android refused a microphone service from here (the app wasn't in front): the transcript screen offers Start.
        RLog.w("conv_foreground", t)
        ConversationNotices.couldNotStart(this)
        false
    }

    /** The session ended: out of the foreground, notification gone, service stopped. */
    fun done() {
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        foreground = false
        stopSelf()
    }

    fun refresh(n: Notification) {
        if (!foreground) return
        try { getSystemService(NotificationManager::class.java).notify(ConversationNotices.ONGOING_ID, n) } catch (t: Throwable) { RLog.w("conv_notify", t) }
    }

    companion object {
        @Volatile var instance: ConversationService? = null

        const val ACTION_START = "app.heyrobot.conv.START"
        const val ACTION_RESUME = "app.heyrobot.conv.RESUME"
        const val ACTION_PAUSE = "app.heyrobot.conv.PAUSE"
        const val ACTION_CONTINUE = "app.heyrobot.conv.CONTINUE"
        const val ACTION_STOP = "app.heyrobot.conv.STOP"
        const val ACTION_REPOST = "app.heyrobot.conv.REPOST"
        const val EXTRA_MODE = "mode"
        const val EXTRA_WHY = "why"

        /**
         * Starts a session from something on screen (the transcript screen, the panel): Android allows a microphone
         * service then. False if it refused.
         */
        fun start(ctx: Context, entry: ConvEntry, resumeId: String? = null, mode: ConvMode = ConvMode.TRANSCRIBE): Boolean = try {
            val i = Intent(ctx, ConversationService::class.java).setAction(if (resumeId != null) ACTION_RESUME else ACTION_START)
                .putExtra(EXTRA_MODE, mode.name).putExtra(Transcripts.EXTRA_ENTRY, entry.name)
            resumeId?.let { i.putExtra(Transcripts.EXTRA_ID, it) }
            ctx.startForegroundService(i)
            true
        } catch (t: Throwable) {
            RLog.w("conv_start_service", t)
            false
        }

        fun action(ctx: Context, action: String, why: ConvStop? = null) {
            try {
                ctx.startService(Intent(ctx, ConversationService::class.java).setAction(action).apply { why?.let { putExtra(EXTRA_WHY, it.name) } })
            } catch (t: Throwable) { RLog.w("conv_action", t) }
        }
    }
}

/** The session's notifications: the ongoing recording indicator, and "stopped unexpectedly · Resume" after a crash. */
object ConversationNotices {
    const val ONGOING_ID = 501
    private const val RESUME_ID = 502
    private const val CHANNEL = "conversation"
    private const val CHANNEL_RESUME = "conversation_resume"
    private val HM = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    private fun channels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Transcribing a conversation", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Shown the whole time Robot transcribes a conversation, with Pause and Stop."
            setSound(null, null)
            enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(CHANNEL_RESUME, "Transcription stopped", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a transcription stopped unexpectedly: Resume."
        })
    }

    private fun pi(ctx: Context, req: Int, action: String, why: ConvStop? = null): PendingIntent = PendingIntent.getService(ctx, req,
        Intent(ctx, ConversationService::class.java).setAction(action).apply { why?.let { putExtra(ConversationService.EXTRA_WHY, it.name) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun openPi(ctx: Context): PendingIntent = PendingIntent.getActivity(ctx, 5_010,
        Intent(Transcripts.ACTION_OPEN).setClassName(ctx.packageName, Transcripts.ACTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** The recording indicator: no words of the conversation, ever; the lock screen gets only the title and the time. */
    fun ongoing(ctx: Context, ui: ConversationSession.Ui): Notification {
        channels(ctx)
        val paused = ui.status == ConversationSession.Status.PAUSED
        val title = if (paused) "Transcribing paused" else "Robot is transcribing"
        val why = when (ui.pausedFor) {
            ConvPause.MIC_TAKEN -> "Another app is using the microphone."
            ConvPause.CALL -> "Paused for a call."
            ConvPause.THERMAL -> "The phone is too hot."
            ConvPause.PANEL -> "Paused while you talk to Robot."
            else -> null
        }
        val lines = if (ui.total == 1) "1 line" else "${ui.total} lines"
        val text = listOfNotNull(why ?: ui.notice, "$lines · ${ConsentRecord.LINE}").joinToString(" ")
        val start = if (ui.createdAt > 0) ui.createdAt else System.currentTimeMillis()
        val public = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setWhen(start).setShowWhen(true).setUsesChronometer(true)
            .build()
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_robot)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setWhen(start).setShowWhen(true).setUsesChronometer(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openPi(ctx))
            .setDeleteIntent(pi(ctx, 5_005, ConversationService.ACTION_REPOST))
        if (ui.status == ConversationSession.Status.RUNNING || ui.status == ConversationSession.Status.STARTING) {
            b.addAction(Notification.Action.Builder(null, "Pause", pi(ctx, 5_001, ConversationService.ACTION_PAUSE)).build())
        } else if (paused) {
            b.addAction(Notification.Action.Builder(null, "Resume", pi(ctx, 5_002, ConversationService.ACTION_CONTINUE)).build())
        }
        b.addAction(Notification.Action.Builder(null, "Stop", pi(ctx, 5_003, ConversationService.ACTION_STOP, ConvStop.NOTIFICATION)).build())
        b.addAction(Notification.Action.Builder(null, "Open", openPi(ctx)).build())
        return b.build()
    }

    /** The session changed: the notification and the tile follow. */
    fun changed(ctx: Context) {
        val ui = ConversationSession.state.value
        if (ui.active) ConversationService.instance?.refresh(ongoing(ctx, ui))
        try { TileService.requestListeningState(ctx, ComponentName(ctx, ConversationTileService::class.java)) } catch (_: Throwable) {}
    }

    /**
     * After a crash (docs/conversation-mode.md 1.6): "Transcription stopped unexpectedly. Saved up to 14:32. Resume". Resume
     * opens the transcript screen, which starts again: a tap, never Robot by itself. No words of the conversation.
     */
    fun offerResume(ctx: Context, id: String, savedUpTo: Long) {
        try {
            channels(ctx)
            val at = HM.format(Instant.ofEpochMilli(savedUpTo).atZone(ZoneId.systemDefault()))
            val resume = PendingIntent.getActivity(ctx, 5_020,
                Intent(Transcripts.ACTION_RESUME).setClassName(ctx.packageName, Transcripts.ACTIVITY).putExtra(Transcripts.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val text = "Transcription stopped unexpectedly. Saved up to $at."
            ctx.getSystemService(NotificationManager::class.java).notify(RESUME_ID, Notification.Builder(ctx, CHANNEL_RESUME)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle("Transcription stopped")
                .setContentText(text)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(Notification.Builder(ctx, CHANNEL_RESUME).setSmallIcon(R.drawable.ic_robot).setContentTitle("Transcription stopped").build())
                .setContentIntent(resume)
                .addAction(Notification.Action.Builder(null, "Resume", resume).build())
                .setExtras(android.os.Bundle().apply { putString(EXTRA_RESUME_ID, id) })
                .setAutoCancel(true)
                .build())
            resumeOffered = id
        } catch (t: Throwable) {
            RLog.w("conv_resume_notice", t)
        }
    }

    private const val EXTRA_RESUME_ID = "app.heyrobot.conv.resume_id"
    @Volatile private var resumeOffered: String? = null

    /** Takes the Resume offer down when that transcript was resumed or deleted ([id] null: whatever it was for). */
    fun cancelResume(ctx: Context, id: String?) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val shown = nm.activeNotifications.firstOrNull { it.id == RESUME_ID }?.notification?.extras?.getString(EXTRA_RESUME_ID) ?: resumeOffered
            if (id == null || shown == null || shown == id) {
                nm.cancel(RESUME_ID)
                resumeOffered = null
            }
        } catch (_: Throwable) {}
    }

    /** Android refused to start the microphone service (Robot wasn't in front). */
    fun couldNotStart(ctx: Context) {
        try {
            channels(ctx)
            ctx.getSystemService(NotificationManager::class.java).notify(RESUME_ID + 1, Notification.Builder(ctx, CHANNEL_RESUME)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle("Transcribing didn't start")
                .setContentText("Android didn't let Robot use the microphone from here. Tap to open the transcript screen and start there.")
                .setContentIntent(openPi(ctx))
                .setAutoCancel(true)
                .build())
        } catch (_: Throwable) {}
    }

    /** The title the transcript screen and the library show. */
    fun title(meta: app.heyrobot.core.TranscriptMeta): String = TranscriptTitles.shown(meta)
}
