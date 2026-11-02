// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import app.heyrobot.core.RLog
import app.heyrobot.core.ShowSource
import app.heyrobot.core.ShowTrust
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
        app.heyrobot.assistant.car.CarModeController.showFailed(src) { show(src ?: SRC_HEADSET, Bundle(args)) }
        VoiceLog.add(VoiceEvent.SHOW_FAILED, when (src) {
            SRC_WAKE -> ShowSource.WAKE
            SRC_KEYGUARD -> ShowSource.KEYGUARD
            SRC_APP -> ShowSource.APP
            SRC_CAPTURE -> ShowSource.CAPTURE
            SRC_HEADSET -> ShowSource.HEADSET
            SRC_SHARE -> ShowSource.SHARE
            else -> ShowSource.SWIPE
        })
    }

    /**
     * Asks the system to open the panel. Screen access comes with the corner swipe only: Android 17 strips
     * SHOW_WITH_ASSIST / SHOW_WITH_SCREENSHOT from sessions the assistant opens itself from the background.
     */
    fun show(source: String, extras: Bundle? = null): Boolean = try {
        showSession(Bundle().apply {
            extras?.let { putAll(it) }
            putString(ARG_SOURCE, source)
            // 1.0 (ipc-1): the panel keeps a show's own arguments only with this process's token (core ShowTrust).
            putString(ShowTrust.ARG_VIS, TOKEN)
        }, VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT)
        true
    } catch (t: Throwable) {
        RLog.w("show", t)
        false
    }

    companion object {
        @Volatile var instance: RobotVoiceService? = null

        /**
         * 1.0 (ipc-1): random for each start of the :voice process and only ever in its memory (and in the shows this service
         * asks Android for): a show that carries it came from [show], never from another app's showAssist.
         */
        val TOKEN: String = java.util.UUID.randomUUID().toString()

        /** Bundle keys in the session's show args. */
        const val ARG_SOURCE = "robot_source"
        const val ARG_CONVERSATION = "robot_conversation"
        /**
         * The everyday set's Ask Robot (everyday-set-plan.md §7.2, E7): the one-time id of what another app shared, held in
         * :voice's memory. Through PanelLaunchActivity only, capped there; "robot_" so ShowTrust keeps it on the service's show.
         */
        const val ARG_SHARED = "robot_shared"

        const val SRC_WAKE = "wake"
        const val SRC_KEYGUARD = "keyguard"
        /** From Robot's own app ("Talk to Robot", "Continue"): its screen is not offered as screen context. */
        const val SRC_APP = "app"
        /** Back from the camera or the photo picker. */
        const val SRC_CAPTURE = "capture"
        /** 0.8: a headset's or car's button (HeadsetEntryActivity), or the car screen's Talk. */
        const val SRC_HEADSET = "headset"
        /** The everyday set's Ask Robot (E7): opened with what another app shared ([ARG_SHARED]); the screen is never offered. */
        const val SRC_SHARE = "share"
    }
}
