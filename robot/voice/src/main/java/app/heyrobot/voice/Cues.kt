package app.heyrobot.voice

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import app.heyrobot.core.RLog

// VOICE PROCESS. Short sounds for a question that went nowhere (0.2.3): the panel also says so in words, but after a
// wake word the phone may be across the room, so a missed question is heard as well as shown.

object Cues {
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Two short low tones: "that didn't work". */
    fun missed() = tone(ToneGenerator.TONE_PROP_NACK, 300)

    /**
     * 0.8: the short chime before a headset or car session listens, in the headset: over its call route ([call]: the
     * voice-call stream follows the communication device) or its media output.
     */
    fun listening(call: Boolean) = tone(ToneGenerator.TONE_PROP_BEEP, 150, if (call) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC)

    private fun tone(type: Int, ms: Int, stream: Int = AudioManager.STREAM_MUSIC) {
        try {
            val t = ToneGenerator(stream, 70)
            t.startTone(type, ms)
            main.postDelayed({ try { t.release() } catch (_: Throwable) {} }, ms + 300L)
        } catch (e: Throwable) {
            RLog.w("cue", e)
        }
    }
}
