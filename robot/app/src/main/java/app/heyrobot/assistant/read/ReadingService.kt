// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.read

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.IBinder
import android.view.KeyEvent
import app.heyrobot.R
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.RLog
import app.heyrobot.core.headset.MediaKey
import app.heyrobot.core.read.ReadState
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.ProtectedBroadcasts
import java.util.concurrent.Executor

// VOICE PROCESS. Read it to me's foreground service (mediaPlayback, as RoutineSpeakingService: Android lets background
// audio play only from a visible window or such a service). It lives only while a reading is on (ReadingVoice), and with
// it the reading's MediaSession, so your headset's button is the reading's while it plays or is paused and goes back to
// your music app the moment it ends. It hears "becoming noisy", the screen going off, the phone's mode (a call) and an
// output going away, and passes each to ReadingVoice. Its notification shows Robot's own words only, never what is read.

class ReadingService : Service() {
    private var media: MediaSession? = null
    private var foreground = false

    private val watch = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                AudioManager.ACTION_AUDIO_BECOMING_NOISY -> ReadingVoice.noisy()
                Intent.ACTION_SCREEN_OFF -> ReadingVoice.lockedNow()
            }
        }
    }
    private val modes = AudioManager.OnModeChangedListener { VoiceHub.main.post { ReadingVoice.modeChanged() } }
    private val devices = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) { ReadingVoice.routeChanged() }
        // A speaker or a car connecting mid-piece can take the voice: the piece playing is checked again now.
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) { ReadingVoice.routeChanged() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val am = getSystemService(AudioManager::class.java)
        // Each watcher on its own: a reading that can't hear the headset going, a call or a new output doesn't go on.
        val ok = listOf(
            { ProtectedBroadcasts.register(this, watch, ProtectedBroadcasts.filter(AudioManager.ACTION_AUDIO_BECOMING_NOISY, Intent.ACTION_SCREEN_OFF)) },
            { am.addOnModeChangedListener(Executor { it.run() }, modes) },
            { am.registerAudioDeviceCallback(devices, VoiceHub.main) },
        ).map { w -> try { w(); true } catch (t: Throwable) { RLog.w("read_watch", t); false } }
        if (false in ok) VoiceHub.main.post { ReadingVoice.stopNow() }
    }

    override fun onDestroy() {
        try { unregisterReceiver(watch) } catch (_: Throwable) {}
        val am = getSystemService(AudioManager::class.java)
        try { am.removeOnModeChangedListener(modes) } catch (_: Throwable) {}
        try { am.unregisterAudioDeviceCallback(devices) } catch (_: Throwable) {}
        releaseMedia()
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!goForeground()) {
                    VoiceHub.main.post { ReadingVoice.stopNow() }
                    stopSelf()
                    return START_NOT_STICKY
                }
                // The reading may have ended before the service came up: then nothing holds the button or the notice.
                if (ReadingVoice.state == ReadState.NONE) { done(); return START_NOT_STICKY }
                holdMedia()
                VoiceHub.main.post { ReadingVoice.ready() }
            }
            ACTION_TOGGLE -> VoiceHub.main.post { if (ReadingVoice.state == ReadState.NONE) done() else ReadingVoice.key(MediaKey.PLAY_PAUSE) }
            ACTION_STOP -> VoiceHub.main.post { if (ReadingVoice.state == ReadState.NONE) done() else ReadingVoice.command(app.heyrobot.core.read.ReadCommand.Stop) }
            else -> if (ReadingVoice.state == ReadState.NONE) stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean = try {
        startForeground(NOTICE_ID, notice(this, ReadState.PLAYING), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        foreground = true
        true
    } catch (t: Throwable) {
        RLog.w("read_foreground", t)
        false
    }

    /** The reading's own MediaSession: the headset's button presses come here only while it is active. */
    private fun holdMedia() {
        if (media != null) return
        media = try {
            MediaSession(this, "RobotReading").apply { // l10n: not text (a session tag)
                setCallback(object : MediaSession.Callback() {
                    override fun onMediaButtonEvent(i: Intent): Boolean {
                        val e = i.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
                        if (e.action != KeyEvent.ACTION_DOWN || e.repeatCount > 0) return true
                        val k = MediaKey.of(e.keyCode) ?: return false
                        ReadingVoice.key(k)
                        return true
                    }
                }, VoiceHub.main)
                isActive = true
            }
        } catch (t: Throwable) { RLog.w("read_media", t); null }
        update(ReadState.PLAYING)
    }

    private fun releaseMedia() {
        val m = media ?: return
        media = null
        try { m.isActive = false; m.release() } catch (t: Throwable) { RLog.w("read_media", t) }
    }

    /** The reading plays or is paused: the session's state and the notification follow. */
    fun update(state: ReadState) {
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP
        val st = if (state == ReadState.PLAYING) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        try { media?.setPlaybackState(PlaybackState.Builder().setActions(actions).setState(st, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build()) } catch (t: Throwable) { RLog.w("read_media", t) }
        if (foreground && !someoneElse()) try { getSystemService(NotificationManager::class.java).notify(NOTICE_ID, notice(this, state)) } catch (t: Throwable) { RLog.w("read_notice", t) }
    }

    /** L1: a Phone lock for someone else, or one that can't be told, posts nothing (review-lock 10). */
    private fun someoneElse(): Boolean = try { PhoneLockNow.someoneElse(this) } catch (t: Throwable) { RLog.w("read_notice_lock", t); true }

    /** The reading is over: the MediaSession goes first, then the notification and the service. */
    fun done() {
        releaseMedia()
        if (foreground) try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        foreground = false
        stopSelf()
    }

    companion object {
        const val ACTION_START = "app.heyrobot.read.START" // l10n: not text (an intent action)
        const val ACTION_TOGGLE = "app.heyrobot.read.TOGGLE" // l10n: not text (an intent action)
        const val ACTION_STOP = "app.heyrobot.read.STOP" // l10n: not text (an intent action)
        private const val CHANNEL = "reading" // l10n: not text (a channel id)
        private const val NOTICE_ID = 8_560

        @Volatile var instance: ReadingService? = null
            private set

        /** ReadingVoice: a reading starts; false when Android refused the service. */
        fun start(ctx: Context): Boolean = try {
            channel(ctx)
            ctx.startForegroundService(Intent(ctx, ReadingService::class.java).setAction(ACTION_START))
            true
        } catch (t: Throwable) {
            RLog.w("read_start_service", t)
            false
        }

        private fun channel(ctx: Context) {
            ctx.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, ctx.getString(R.string.read_channel), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        }

        private fun action(ctx: Context, req: Int, action: String, label: Int): Notification.Action =
            Notification.Action.Builder(null, ctx.getString(label), PendingIntent.getService(ctx, req,
                Intent(ctx, ReadingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

        private fun notice(ctx: Context, state: ReadState): Notification {
            val title = ctx.getString(if (state == ReadState.PAUSED) R.string.read_paused else R.string.read_reading)
            return Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_robot)
                .setContentTitle(title)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
                .addAction(action(ctx, 8_561, ACTION_TOGGLE, if (state == ReadState.PAUSED) R.string.read_resume else R.string.read_pause))
                .addAction(action(ctx, 8_562, ACTION_STOP, R.string.read_stop))
                .build()
        }
    }
}
