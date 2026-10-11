package app.heyrobot.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.EngineState
import app.heyrobot.core.Heard
import app.heyrobot.core.RLog
import app.heyrobot.core.SttBench
import app.heyrobot.core.SttEnd
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.WordAccuracy
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicInteger

// VOICE PROCESS ONLY, :voice module only (sherpa-onnx). Speech to text for push-to-talk and the question
// after "hey robot" (spec item 3), with NVIDIA Parakeet TDT 0.6B v3 int8 (docs/voice-plan.md 4.5-4.7):
//  - audio comes from the always-on Listener (or a capture of its own when the wake word is off), with a
//    short pre-roll; it stays in memory and is dropped after the decode: never written to disk;
//  - a Silero VAD of its own (0.5 s minimum silence, 20 s maximum speech) ends the question, or the button;
//  - while the person speaks, the whole buffer is decoded again about every 0.7 s for live partial text;
//  - one decode at a time, on the "robot-stt" thread; the model loads on first use (a question starting, never just
//    the panel opening) and is freed after 5 minutes unused (IdlePolicy), or on the rare memory-pressure signal.

/** Parakeet v3 int8 in sherpa-onnx's OfflineRecognizer (nemo_transducer). Not thread-safe: one decode at a time. */
internal class Recognizer private constructor(private val r: OfflineRecognizer) {
    fun decode(samples: FloatArray): String {
        val s = r.createStream()
        try {
            s.acceptWaveform(samples, Listener.SAMPLE_RATE)
            r.decode(s)
            // Parakeet v3 picks the language itself, but sherpa-onnx 1.13.8 does not report it for this model
            // (result.lang is empty; checked with the same build on the build machine): text only.
            return r.getResult(s).text.trim()
        } finally {
            s.release()
        }
    }

    /** Conversation mode (0.5.0): the text with its tokens and their times, for word times and the re-hear check. */
    fun heard(samples: FloatArray): Heard {
        val s = r.createStream()
        try {
            s.acceptWaveform(samples, Listener.SAMPLE_RATE)
            r.decode(s)
            val res = r.getResult(s)
            return Heard(res.text.trim(), res.tokens.toList(), res.timestamps.toList())
        } finally {
            s.release()
        }
    }

    fun release() = r.release()

    companion object {
        const val THREADS = 4

        fun load(dir: File): Recognizer {
            // The verified mark was checked by the slot; a file that changed since then is not loaded.
            for (f in VoiceBundle.STT.files) require(File(dir, f.name).length() == f.bytes) { "size" }
            val p = dir.absolutePath
            return Recognizer(OfflineRecognizer(null, OfflineRecognizerConfig(
                // Feature size and normalisation come from the model's metadata for NeMo transducers.
                featConfig = FeatureConfig(sampleRate = Listener.SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(encoder = "$p/encoder.int8.onnx", decoder = "$p/decoder.int8.onnx", joiner = "$p/joiner.int8.onnx"),
                    tokens = "$p/tokens.txt",
                    modelType = "nemo_transducer",
                    numThreads = THREADS,
                    // Never on: sherpa's debug output can include what was recognised.
                    debug = false,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )))
        }
    }
}

/** Growable sample buffer (capped). */
internal class SampleBuffer(private val max: Int) {
    private var data = FloatArray(Listener.SAMPLE_RATE * 4)
    private var size = 0

    @Synchronized
    fun append(x: FloatArray) {
        val n = minOf(x.size, max - size)
        if (n <= 0) return
        if (size + n > data.size) data = data.copyOf(minOf(max, maxOf(size + n, data.size * 2)))
        System.arraycopy(x, 0, data, size, n)
        size += n
    }

    @Synchronized
    fun snapshot(): FloatArray = data.copyOf(size)
}

class SherpaSpeechToText internal constructor(
    private val ctx: Context,
    private val slot: ModelSlot<Recognizer>,
    private val frames: () -> AudioFrames?,
) : SpeechToText {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-stt") }
    private val main = Handler(Looper.getMainLooper())
    private val ids = AtomicInteger()
    private val vadLock = Any()
    private var vad: Vad? = null
    @Volatile private var session: Session? = null
    @Volatile private var livePartials = true
    /** Conversation sessions and the Transcription check holding the model loaded (the idle unload waits for them). */
    private val holds = AtomicInteger()

    private inner class Session(val id: Int, val listener: SpeechToText.Listener, val source: AudioFrames, val own: Listener?) {
        val buffer = SampleBuffer(MAX_SAMPLES)
        val endpointer = Endpointer()
        val scheduler = PartialScheduler()
        @Volatile var preRoll: FloatArray? = null
        @Volatile var ended = false
        @Volatile var cancelled = false
        @Volatile var partialRunning = false
        /** Set on the audio thread, cancelled from finish/cancel. */
        @Volatile var partialJob: Future<*>? = null
        var partials = 0
        var lastPartial = ""
        @Volatile var endedAt = 0L
        val sink: (FloatArray) -> Unit = { x -> onAudio(this, x) }
    }

    override val available: Boolean get() = slot.state.usable

    override val hint: String? get() = when (slot.state) {
        EngineState.MISSING -> "Download voice models"
        EngineState.BROKEN -> "Voice input is off after two crashes (Settings › Voice)"
        EngineState.FAILED -> "Voice input couldn't start (Settings › Voice)"
        else -> null
    }

    override fun configure(livePartials: Boolean) {
        this.livePartials = livePartials
    }

    override fun prewarm() {
        slot.refresh()
        if (available) worker.execute { load(); endVad() }
    }

    private fun load(): Recognizer? = slot.get { Recognizer.load(it) }

    /** The end-of-question VAD (asset model, 0.6 MB): made once and kept for the process. */
    private fun endVad(): Vad? = synchronized(vadLock) {
        vad ?: try {
            Vad(ctx.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx", threshold = 0.5f,
                    // The flag drops after 0.5 s of silence: that is the end of the question.
                    minSilenceDuration = MIN_SILENCE_S, minSpeechDuration = 0.25f, windowSize = Listener.FRAME,
                    // The default 5 s would cut a longer question into pieces.
                    maxSpeechDuration = MAX_SPEECH_S,
                ),
                sampleRate = Listener.SAMPLE_RATE, numThreads = 1, debug = false,
            )).also { vad = it }
        } catch (t: Throwable) {
            RLog.w("stt_vad", t)
            null
        }
    }

    private fun unavailableMessage(st: EngineState): String = when (st) {
        EngineState.MISSING -> PlaceholderSpeechToText.MESSAGE
        EngineState.BROKEN -> "Voice input is turned off: it crashed twice while loading. Tap “Try again” in the Robot app (Settings › Voice), or type your question."
        EngineState.FAILED -> "Voice input couldn't start. Tap “Try again” in the Robot app (Settings › Voice), or type your question."
        else -> "Voice input isn't ready. Type your question for now."
    }

    override fun start(listener: SpeechToText.Listener) {
        cancel()
        slot.refresh()
        val st = slot.state
        if (!st.usable) {
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.UNAVAILABLE)
            return PlaceholderSpeechToText(unavailableMessage(st)).start(listener)
        }
        val v = endVad() ?: run {
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.ERROR)
            return listener.onError(SpeechToText.Reason.ERROR, "Voice input couldn't start. Type your question for now.")
        }
        synchronized(vadLock) { v.reset() }
        var own: Listener? = null
        val src: AudioFrames = frames()?.takeIf { it.capturing } ?: run {
            // The wake word is off (or its listener stopped): open the microphone just for this question.
            val l = Listener(ctx, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
            if (!l.start()) {
                l.stop()
                VoiceLog.add(VoiceEvent.STT_END, SttEnd.MIC_BUSY)
                return listener.onError(SpeechToText.Reason.MIC, "Robot can't use the microphone. Allow it in the Robot app, or close the app that is recording.")
            }
            own = l
            l
        }
        if (src.silenced == true) {
            own?.stop()
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.MIC_BUSY)
            return listener.onError(SpeechToText.Reason.MIC, "Another app is using the microphone.")
        }
        val s = Session(ids.incrementAndGet(), listener, src, own)
        session = s
        // If the microphone is closed under the question ("listen only while charging" and the charger is pulled,
        // another app takes it), the question ends with what was said; the watchdog catches a capture that stalls.
        val pre = src.attach(s.sink, PreRoll.SINCE_WAKE_MS, PreRoll.OTHERWISE_MS) { cut(s, "mic_stopped") }
        s.preRoll = pre
        main.postDelayed({ cut(s, "watchdog") }, s.endpointer.watchdogMs)
        listener.onListening()
        // Loads while the person speaks (nothing to do once loaded).
        worker.execute { load() }
        RLog.i("stt_start", "ownMic" to (own != null), "preRollMs" to pre.size * 1000L / Listener.SAMPLE_RATE, "loaded" to (slot.value != null))
        VoiceLog.add(VoiceEvent.STT_START, pre.size * 1000 / Listener.SAMPLE_RATE, if (slot.value != null) 1 else 0, if (own != null) 1 else 0)
    }

    /** From the audio thread. */
    private fun onAudio(s: Session, x: FloatArray) {
        if (s.ended) return
        s.preRoll?.let { p ->
            s.preRoll = null
            if (p.isNotEmpty()) process(s, p)
        }
        process(s, x)
    }

    private fun process(s: Session, x: FloatArray) {
        var off = 0
        while (off < x.size && !s.ended) {
            val n = minOf(Listener.FRAME, x.size - off)
            val frame = if (off == 0 && n == x.size) x else x.copyOfRange(off, off + n)
            off += n
            s.buffer.append(frame)
            val speech = synchronized(vadLock) {
                val v = vad ?: return
                v.acceptWaveform(frame)
                val sp = v.isSpeechDetected()
                // Only the flag is used; drop the segments the VAD queues.
                while (!v.empty()) v.pop()
                sp
            }
            var ss = 0.0
            for (f in frame) ss += f * f
            s.listener.onLevel(Listener.db(ss / n).toFloat())
            when (s.endpointer.onFrame(speech, n)) {
                Endpointer.Event.SPEECH -> {
                    VoiceLog.add(VoiceEvent.VAD_START, ms(s.endpointer.speechStart))
                    if (livePartials) maybePartial(s)
                }
                Endpointer.Event.END -> {
                    VoiceLog.add(VoiceEvent.VAD_END, ms(s.endpointer.samples - s.endpointer.speechStart))
                    finish(s, "silence")
                }
                Endpointer.Event.MAX_LENGTH -> {
                    // A voice detector that never ends (a television, a fan) stops at the 30 s cap.
                    VoiceLog.add(VoiceEvent.VAD_END, ms(s.endpointer.samples - maxOf(0L, s.endpointer.speechStart)))
                    finish(s, "max")
                }
                Endpointer.Event.NO_SPEECH -> noSpeech(s)
                else -> if (livePartials) maybePartial(s)
            }
        }
    }

    private fun maybePartial(s: Session) {
        val job = s.partialJob
        val queued = job != null && !job.isDone && !s.partialRunning
        when (s.scheduler.decide(s.endpointer.samples, s.endpointer.speechStart, queued, s.partialRunning)) {
            PartialScheduler.Decision.START -> s.partialJob = worker.submit { partial(s) }
            PartialScheduler.Decision.REPLACE -> {
                job?.cancel(false)
                s.partialJob = worker.submit { partial(s) }
            }
            PartialScheduler.Decision.NONE -> {}
        }
    }

    /** Worker thread. */
    private fun partial(s: Session) {
        if (s.ended) return
        s.partialRunning = true
        try {
            val r = load() ?: return
            if (s.ended) return
            val text = r.decode(s.buffer.snapshot())
            s.partials++
            if (!s.ended && text.isNotEmpty() && text != s.lastPartial) {
                s.lastPartial = text
                s.listener.onPartial(text)
            }
        } catch (t: Throwable) {
            RLog.w("stt_partial", t)
        } finally {
            s.partialRunning = false
        }
    }

    /** End of capture (silence, the cap, the button): stop listening and decode everything once. */
    private fun finish(s: Session, reason: String) {
        synchronized(s) {
            if (s.ended) return
            s.ended = true
        }
        s.endedAt = SystemClock.elapsedRealtime()
        detach(s)
        // A partial still waiting is dropped; one already running finishes (native decode can't be stopped)
        // and its text is ignored, then the final decode runs.
        s.partialJob?.cancel(false)
        if (!s.cancelled) s.listener.onEndOfSpeech()
        worker.execute { final(s, reason) }
    }

    private fun noSpeech(s: Session, why: String = "no_speech") {
        synchronized(s) {
            if (s.ended) return
            s.ended = true
        }
        detach(s)
        if (session === s) session = null
        RLog.i("stt_final", "end" to RLog.Code(why))
        VoiceLog.add(VoiceEvent.STT_END, endCode(why))
        if (s.cancelled) return
        if (why == "mic_stopped") s.listener.onError(SpeechToText.Reason.MIC, "The microphone stopped before you spoke. Tap the mic and try again.")
        else s.listener.onError(SpeechToText.Reason.NO_SPEECH, "I didn't hear anything. Tap the mic and try again.")
    }

    /** No more audio will come for [s] (its capture stopped, or the watchdog fired): end it with what was heard. */
    private fun cut(s: Session, why: String) {
        if (s.ended) return
        when (s.endpointer.cut()) {
            Endpointer.Event.END -> {
                // Speech was heard: what there is gets decoded, but the log says the capture was cut.
                VoiceLog.add(VoiceEvent.STT_END, endCode(why))
                finish(s, why)
            }
            Endpointer.Event.NO_SPEECH -> noSpeech(s, why)
            else -> {}
        }
    }

    private fun detach(s: Session) {
        s.source.removeSink(s.sink)
        // Stopping a capture joins its audio thread, and this may be that thread: stop it from the main thread.
        s.own?.let { l -> main.post { l.stop() } }
    }

    /** Worker thread. */
    private fun final(s: Session, reason: String) {
        try {
            if (s.cancelled) return
            val r = load()
            if (r == null) {
                VoiceLog.add(VoiceEvent.STT_END, SttEnd.UNAVAILABLE)
                if (!s.cancelled) s.listener.onError(SpeechToText.Reason.ERROR, unavailableMessage(slot.state))
                return
            }
            val audio = s.buffer.snapshot()
            val t0 = SystemClock.elapsedRealtime()
            val text = if (audio.size < MIN_SAMPLES) "" else r.decode(audio)
            val now = SystemClock.elapsedRealtime()
            // Numbers only: how long, how fast, how it ended; never the words.
            RLog.i("stt_final", "end" to RLog.Code(reason), "audioMs" to audio.size * 1000L / Listener.SAMPLE_RATE,
                "decodeMs" to now - t0, "waitMs" to now - s.endedAt, "partials" to s.partials, "heard" to s.endpointer.heardSpeech,
                "empty" to text.isEmpty())
            val audioMs = audio.size * 1000 / Listener.SAMPLE_RATE
            if (text.isEmpty()) VoiceLog.add(VoiceEvent.STT_EMPTY, audioMs, if (s.endpointer.heardSpeech) 1 else 0)
            else VoiceLog.add(VoiceEvent.STT_DONE, text.length, audioMs, (now - t0).toInt())
            if (!s.cancelled) s.listener.onFinal(text)
        } catch (t: Throwable) {
            RLog.w("stt_decode", t)
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.ERROR)
            if (!s.cancelled) s.listener.onError(SpeechToText.Reason.ERROR, "Voice input failed. Type your question for now.")
        } finally {
            if (session === s) session = null
        }
    }

    override fun stop() {
        session?.let { finish(it, "release") }
    }

    override fun cancel() {
        val s = session ?: return
        session = null
        s.cancelled = true
        synchronized(s) {
            if (s.ended) return
            s.ended = true
        }
        VoiceLog.add(VoiceEvent.STT_END, SttEnd.CANCELLED)
        detach(s)
        s.partialJob?.cancel(false)
    }

    override fun release() {
        cancel()
        unload()
    }

    /** Frees the model (it loads again on next use). */
    internal fun unload() {
        worker.execute { slot.release() }
    }

    override fun trim(): Boolean {
        if (session != null || holds.get() > 0 || slot.value == null) return false
        unload()
        return true
    }

    /** VoiceEngines' idle check: free the model if a question has not used it for 5 minutes (never mid-question or mid-session). */
    internal fun unloadIfIdle() {
        worker.execute { slot.releaseIfIdle(inUse = session != null || holds.get() > 0) }
    }

    /** A conversation (or the Transcription check) keeps the model loaded while it runs ([on]), and lets it go after. */
    internal fun hold(on: Boolean) {
        if (on) holds.incrementAndGet() else holds.updateAndGet { maxOf(0, it - 1) }
    }

    /**
     * Conversation mode: decodes one stretch of audio on this engine's worker, in turn with questions (one decode at a
     * time, one model in memory), and waits for it. Null when the model can't be loaded. In memory only.
     */
    internal fun decodeHeard(samples: FloatArray): Pair<Heard, Long>? = worker.submit(Callable {
        val r = load() ?: return@Callable null
        val t0 = SystemClock.elapsedRealtime()
        val h = if (samples.size < MIN_SAMPLES) Heard("") else r.heard(samples)
        h to (SystemClock.elapsedRealtime() - t0)
    }).get()

    /** "Test voice speed" (benchmark thread): load afresh, decode the clip 3 times and a partial-sized piece once. */
    internal fun bench(clip: FloatArray, expected: String): SttBench? = worker.submit(Callable {
        if (session != null) return@Callable null
        slot.release()
        val t0 = SystemClock.elapsedRealtime()
        val r = load() ?: return@Callable null
        val loadMs = SystemClock.elapsedRealtime() - t0
        var text = ""
        val times = (1..3).map {
            val t = SystemClock.elapsedRealtime()
            text = r.decode(clip)
            SystemClock.elapsedRealtime() - t
        }.sorted()
        val tp = SystemClock.elapsedRealtime()
        r.decode(clip.copyOf(clip.size * 6 / 10))
        val partialMs = SystemClock.elapsedRealtime() - tp
        SttBench(loadMs, clip.size * 1000L / Listener.SAMPLE_RATE, times[1], partialMs, WordAccuracy.percent(expected, text))
    }).get()

    internal val usable: Boolean get() { slot.refresh(); return available }

    private fun ms(samples: Long): Int = (samples * 1000 / Listener.SAMPLE_RATE).toInt()

    private fun endCode(why: String): SttEnd = when (why) {
        "watchdog" -> SttEnd.TIMEOUT
        "mic_stopped" -> SttEnd.MIC_STOPPED
        else -> SttEnd.NO_SPEECH
    }

    companion object {
        const val MIN_SILENCE_S = 0.5f
        const val MAX_SPEECH_S = 20f
        /** A question is capped at 30 s (Endpointer); the buffer a little above. */
        private const val MAX_SAMPLES = 31 * Listener.SAMPLE_RATE
        /** Less than this is a tap, not speech. */
        private const val MIN_SAMPLES = Listener.SAMPLE_RATE * 3 / 10
    }
}
