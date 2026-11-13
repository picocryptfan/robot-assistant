// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.routines.RoutineVoice
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsStop
import app.heyrobot.core.headset.EarbudButtons
import app.heyrobot.core.headset.EarbudButtons.End

// Earbud buttons while Robot speaks (core EarbudButtons decides; docs/plans/earbud-buttons-plan.md). Robot's only media
// session: created when her voice starts and released when it goes quiet, when anything stops it, on an error and at the
// voice service's shutdown, so your music app has its buttons back. A killed process loses it with the process
// (Android's binder death). No metadata and no notification: nothing of the answer shows in media controls or on the
// lock screen. A tap stops her through VoiceHub.stopVoice; a double tap skips a brief's part (RoutineVoice's "next"), and
// on a plain answer, with nothing to skip to, stops it. Main thread only.
object EarbudSession {
    private var state = EarbudButtons.State()
    private var session: MediaSession? = null
    // Between two parts of a brief her voice is quiet for a moment: the run still counts as speaking.
    private val check = Runnable { step(EarbudButtons.tick(state, now(), VoiceHub.tts.isSpeaking || RoutineVoice.speakingRun)) }

    private fun now() = SystemClock.elapsedRealtime()

    private fun onMain(f: () -> Unit) = if (Looper.myLooper() == Looper.getMainLooper()) f() else { VoiceHub.main.post(f); Unit }

    /** VoiceHub.onSpeakingChanged. */
    fun speaking(on: Boolean) = onMain { step(EarbudButtons.speaking(state, on, now(), HandsFreeHub.headset.settings.earbudButtons)) }

    /** VoiceHub.stopVoice ([End.STOPPED]) and VoiceHub.onShutdown ([End.KILLED]): the buttons go back at once. */
    fun ended(why: End) = onMain { step(EarbudButtons.ended(state, why)) }

    private fun step(s: EarbudButtons.Step) {
        state = s.state
        try {
            if (s.take) take()
            s.action?.let(::act)
        } catch (t: Throwable) {
            RLog.w("earbud", t)
            state = EarbudButtons.ended(state, End.ERROR).state
            giveBack()
            return
        }
        if (s.giveBack) giveBack()
        VoiceHub.main.removeCallbacks(check)
        if (state.held) s.checkAt?.let { VoiceHub.main.postDelayed(check, (it - now()).coerceAtLeast(0)) }
    }

    private fun act(a: EarbudButtons.Action) {
        RLog.i("earbud", "action" to RLog.Code(a.name.lowercase()))
        if (a == EarbudButtons.Action.SKIP && RoutineVoice.speakingRun) RoutineVoice.control(app.heyrobot.core.BriefControl.NEXT)
        else VoiceHub.stopVoice(TtsStop.EARBUD)
    }

    private fun take() {
        giveBack()
        session = MediaSession(VoiceHub.app, "robot-speaking").apply {
            setCallback(callback, VoiceHub.main)
            setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f).build())
            isActive = true
        }
    }

    private fun giveBack() {
        val s = session ?: return
        session = null
        VoiceHub.main.removeCallbacks(check)
        try { s.isActive = false } catch (t: Throwable) { RLog.w("earbud", t) }
        try { s.release() } catch (t: Throwable) { RLog.w("earbud", t) }
    }

    private fun key(code: Int, down: Boolean = true, long: Boolean = false) = step(EarbudButtons.key(state, code, down, long, now()))

    private val callback = object : MediaSession.Callback() {
        override fun onMediaButtonEvent(intent: Intent): Boolean {
            val e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java) ?: return false
            key(e.keyCode, e.action == KeyEvent.ACTION_DOWN, e.repeatCount > 0 || e.flags and KeyEvent.FLAG_LONG_PRESS != 0)
            return true
        }

        // A controller (Bluetooth, the system's media controls) can call these directly instead of sending a key.
        override fun onPlay() = key(KeyEvent.KEYCODE_MEDIA_PLAY)
        override fun onPause() = key(KeyEvent.KEYCODE_MEDIA_PAUSE)
        override fun onStop() = key(KeyEvent.KEYCODE_MEDIA_STOP)
        override fun onSkipToNext() = key(KeyEvent.KEYCODE_MEDIA_NEXT)
    }
}
