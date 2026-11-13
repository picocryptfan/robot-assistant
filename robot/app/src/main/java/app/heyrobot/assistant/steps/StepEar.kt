// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import android.content.Context
import android.os.SystemClock
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.RLog
import app.heyrobot.voice.AudioFrames
import app.heyrobot.voice.ConversationEngine
import app.heyrobot.voice.Listener
import app.heyrobot.voice.ListenerConfig
import app.heyrobot.voice.SegmentDecoder
import app.heyrobot.voice.VoiceEngines
import java.util.concurrent.Executors

// VOICE PROCESS. Step by step's ear (docs/plans/step-by-step-plan.md): a tap on the shared microphone, as conversation
// mode's (ConversationSession.attachAudio), or a capture of its own when the wake word isn't listening. Silero cuts short
// utterances, Parakeet (English and French) decodes them, and [heard] gets the words, which StepVoice matches against
// the step words and drops: never kept, never logged. Robot's own voice, and 300 ms after it, count as silence.
class StepEar(private val ctx: Context, private val heard: (String) -> Unit) {
    private var source: AudioFrames? = null
    private var own: Listener? = null
    private var decoder: SegmentDecoder? = null
    private var flag: ConversationEngine.SpeechFlag? = null
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "robot-steps-ear") } // l10n: not text (a thread name)
    private var buf = FloatArray(MAX_SAMPLES)
    private var n = 0
    private var quietFrames = 0
    private var hush = 0L
    @Volatile var paused = false; private set
    @Volatile private var resumedAt = 0L
    /** Android silences the capture: a call, or another app recording. */
    val silenced: Boolean get() = source?.silenced == true

    private val sink: (FloatArray) -> Unit = { f -> frame(f) }

    /** Starts listening; false when the microphone or the models can't be had (the steps still work by tapping). */
    fun start(): Boolean {
        val d = VoiceEngines.segmentDecoder()?.takeIf { it.available } ?: return false
        val v = ConversationEngine.makeVad(ctx) ?: return false
        decoder = d
        flag = ConversationEngine.SpeechFlag(v)
        d.hold(true)
        if (paused) return true // the panel's turn has the microphone: keep() attaches once it closes
        if (!attach()) { stop(); return false }
        return true
    }

    /** The shared listener if it runs, else a capture of its own. */
    private fun attach(): Boolean {
        val hub = VoiceHub.listener?.takeIf { it.running }
        if (hub != null) { hub.addSink(sink); source = hub; return true }
        val l = Listener(ctx, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
        if (!l.start()) { l.stop(); return false }
        l.addSink(sink)
        own = l
        source = l
        return true
    }

    /** Main thread, now and then: moves to the shared listener when it comes, or off it when it stops (as conversation
     *  mode's checkAudio). False: no microphone now. */
    fun keep(): Boolean {
        val hub = VoiceHub.listener?.takeIf { it.running }
        val src = source
        if (src != null && (src === hub || (hub == null && src.capturing))) return true
        if (paused) return true // the panel's turn has the microphone: no capture of our own until it closes
        if (decoder == null) return false
        src?.removeSink(sink)
        own?.stop()
        own = null
        source = null
        n = 0
        return attach()
    }

    /** A capture is open for the ear (the shared listener, or its own): the mic mark shows only then, not while
     *  keep() waits out the panel with none. */
    val hearing: Boolean get() { val s = source ?: return false; return s === VoiceHub.listener?.takeIf { it.running } || s.capturing }

    /** The panel is open (true): what is said goes to the panel, not here. */
    fun pause(on: Boolean) { if (paused && !on) resumedAt = SystemClock.uptimeMillis(); paused = on }

    /** Silenced, and not just the panel's capture letting go: [SETTLE_MS] after the panel closed. */
    fun silencedNow(now: Long): Boolean = !paused && now - resumedAt >= SETTLE_MS && silenced

    fun stop() {
        source?.removeSink(sink)
        source = null
        own?.stop()
        own = null
        decoder?.hold(false)
        decoder = null
        flag?.release()
        flag = null
        worker.shutdownNow()
        n = 0
        buf.fill(0f)
    }

    private fun frame(f: FloatArray) {
        val now = SystemClock.uptimeMillis()
        if (paused || VoiceHub.tts.isSpeaking) { hush = now + 300; n = 0; return }
        if (now < hush) return
        val speech = try { flag?.frame(f) ?: return } catch (t: Throwable) { RLog.w("steps_vad", t); return }
        if (!speech && n == 0) return
        if (n + f.size > MAX_SAMPLES) { n = 0; quietFrames = 0; return } // too long for a step word
        System.arraycopy(f, 0, buf, n, f.size)
        n += f.size
        quietFrames = if (speech) 0 else quietFrames + 1
        if (quietFrames < END_FRAMES) return
        val samples = buf.copyOf(n)
        n = 0
        quietFrames = 0
        if (samples.size < MIN_SAMPLES) return
        val d = decoder ?: return
        try {
            worker.execute {
                val text = try { d.decode(samples)?.first?.text } catch (t: Throwable) { RLog.w("steps_decode", t); null }
                samples.fill(0f)
                if (!text.isNullOrBlank()) heard(text)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {}
    }

    private companion object {
        /** 3 s at most: a step word, not a sentence. */
        const val MAX_SAMPLES = 3 * Listener.SAMPLE_RATE
        const val MIN_SAMPLES = Listener.SAMPLE_RATE / 5
        /** About 0.4 s of quiet ends an utterance. */
        const val END_FRAMES = 12
        /** Two watches: the panel's capture has let go of the microphone by then. */
        const val SETTLE_MS = 4_000L
    }
}
