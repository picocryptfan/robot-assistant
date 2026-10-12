package app.heyrobot.assistant

import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import app.heyrobot.core.RLog
import app.heyrobot.core.ShowSource
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.BootCheck

// VOICE PROCESS. The system binds RobotVoiceService while Robot is the digital assistant (from boot,
// after the first unlock) and calls onReady; the listener and the panel hang off that.

class RobotVoiceService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        instance = this
        RLog.i("vis", "state" to RLog.Code("ready"))
        // For the restart check (3 min after BOOT_COMPLETED): ready in this boot; takes down a stale notice.
        BootCheck.onVisReady(this)
        VoiceHub.onReady(this)
    }

    override fun onShutdown() {
        RLog.i("vis", "state" to RLog.Code("shutdown"))
        VoiceHub.onShutdown()
        instance = null
        super.onShutdown()
    }

    /** Memory pressure, if Android says so (rare since API 34, see VoiceHub): the speech models go unless in use. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        VoiceHub.onTrimMemory(level)
    }

    /** The lock-screen voice-assist affordance (supportsLaunchVoiceAssistFromKeyguard). */
    override fun onLaunchVoiceAssistFromKeyguard() {
        show(SRC_KEYGUARD)
    }

    /** API 34+: the system did not show the session although show() returned without an error. */
    override fun onShowSessionFailed(args: Bundle) {
        val src = args.getString(ARG_SOURCE)
        RLog.i("show_failed", "src" to src?.let { RLog.Code(it) })
        VoiceLog.add(VoiceEvent.SHOW_FAILED, when (src) {
            SRC_WAKE -> ShowSource.WAKE
            SRC_KEYGUARD -> ShowSource.KEYGUARD
            SRC_APP -> ShowSource.APP
            SRC_CAPTURE -> ShowSource.CAPTURE
            SRC_HEADSET -> ShowSource.HEADSET
            else -> ShowSource.SWIPE
        })
    }

    /**
     * Asks the system to open the panel. Screen access comes with the corner swipe only: Android 17 strips
     * SHOW_WITH_ASSIST / SHOW_WITH_SCREENSHOT from sessions the assistant opens itself from the background.
     */
    fun show(source: String, extras: Bundle? = null): Boolean = try {
        showSession(Bundle().apply {
            putString(ARG_SOURCE, source)
            extras?.let { putAll(it) }
        }, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        true
    } catch (t: Throwable) {
        RLog.w("show", t)
        false
    }

    companion object {
        @Volatile var instance: RobotVoiceService? = null

        /** Bundle keys in the session's show args. */
        const val ARG_SOURCE = "robot_source"
        const val ARG_CONVERSATION = "robot_conversation"

        const val SRC_WAKE = "wake"
        const val SRC_KEYGUARD = "keyguard"
        /** From Robot's own app ("Talk to Robot", "Continue"): its screen is not offered as screen context. */
        const val SRC_APP = "app"
        /** Back from the camera or the photo picker. */
        const val SRC_CAPTURE = "capture"
        /** 0.8: a headset's or car's button (HeadsetEntryActivity), or the car screen's Talk. */
        const val SRC_HEADSET = "headset"
    }
}

/**
 * Named by the voice-interaction metadata (android:recognitionService) and discoverable (see the
 * manifest), so the system keeps Robot as the assistant across restarts. Phase 1 has no recognizer for
 * other apps: it refuses every request and logs only that one came in.
 */
class RobotRecognitionService : RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback) {
        RLog.i("recognizer", "op" to RLog.Code("refused"))
        try { listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) } catch (_: Exception) {}
    }

    override fun onStopListening(listener: Callback) {}
    override fun onCancel(listener: Callback) {}
}
