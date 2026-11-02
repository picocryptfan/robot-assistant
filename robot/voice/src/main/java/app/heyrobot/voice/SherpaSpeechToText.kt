// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.heyrobot.core.EngineState
import app.heyrobot.core.Heard
import app.heyrobot.core.OpenWords
import app.heyrobot.core.RLog
import app.heyrobot.core.SttBench
import app.heyrobot.core.SttEnd
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.WordAccuracy
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.VoiceStrings
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
//  - a Silero VAD of its own (0.25 s minimum silence, 20 s maximum speech) and the pause you chose (Endpointer) end
//    the question, or the button: held, only its release ends it;
//  - while the person speaks, the whole buffer is decoded again about every 0.7 s for live partial text;
//  - one decode at a time, on the "robot-stt" thread; the model loads on first use (a question starting, never just
//    the panel opening) and is freed after 5 minutes unused (IdlePolicy), or on the rare memory-pressure signal;
//  - the everyday set (E5): another app's mic button listens the same way (startForApp) on the same capture, with no audio
//    focus and no audio from before it asked; Robot's own listening ends it, and Robot's stop and cancel never touch it.

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
    @Volatile private var pauseMs = Endpointer.DEFAULT_PAUSE_MS
    @Volatile private var tuning = SpeechTuning()
    private var vadThreshold = 0f
    /** Conversation sessions and the Transcription check holding the model loaded (the idle unload waits for them). */
    private val holds = AtomicInteger()

    /** [app]: another app's request (startForApp), null for Robot's own question. */
    private inner class Session(val id: Int, val listener: SpeechToText.Listener, val source: AudioFrames, val own: Listener?, val window: Long,
        val app: AppListen? = null, shortAnswer: Boolean = false) {
        val buffer = SampleBuffer(MAX_SAMPLES)
        /** Words so far as they come: always for Robot's own (if "Show words as you speak"), for an app only if it asked. */
        val wordsSoFar: Boolean = app?.partials ?: true
        // Another app is told the end as it comes (onEndOfSpeech), and that can't be taken back.
        val endpointer = Endpointer(Endpointer.Config(silenceSamples = window,
            openSamples = if (shortAnswer) window else maxOf(window, Endpointer.windowFor(OpenWords.PAUSE_MS)),
            listensOn = app == null))
        /** The end came on silence: the words are being written down while the microphone still listens. */
        @Volatile var writing = false
        /** Bumped when speech comes back: a write-down under way is dropped. */
        @Volatile var turn = 0
        /** The pause whose words were checked for an open word, with live words off. */
        var checkedPause = -1L
        /** The loudest frame, for the car's microphone log (audio thread). */
        var peakDb = -120f
        val scheduler = PartialScheduler()
        @Volatile var preRoll: FloatArray? = null
        @Volatile var ended = false
        @Volatile var cancelled = false
        @Volatile var partialRunning = false
        /** Set on the audio thread, cancelled from finish/cancel. */
        @Volatile var partialJob: Future<*>? = null
        var partials = 0
        var lastPartial = ""
        /** Audio behind [lastPartial], in samples. */
        var lastPartialSamples = 0
        @Volatile var endedAt = 0L
        val sink: (FloatArray) -> Unit = { x -> onAudio(this, x) }
    }

    override val available: Boolean get() = slot.state.usable

    /** 1.1: the panel's notices are in the menus' language. */
    private fun shown(p: Phrase): String = ScreenLangs.shown(ctx, p)

    private val voicePage: UiLabel get() = UiStrings.inSettings(UiStrings.voice)

    override val hint: String? get() = when (slot.state) {
        EngineState.MISSING -> shown(VoiceStrings.sttHintDownload())
        EngineState.BROKEN -> shown(VoiceStrings.sttHintBroken("page" to voicePage))
        EngineState.FAILED -> shown(VoiceStrings.sttHintFailed("page" to voicePage))
        else -> null
    }

    override fun configure(livePartials: Boolean, pauseMs: Int, tuning: SpeechTuning) {
        this.livePartials = livePartials
        this.pauseMs = pauseMs
        this.tuning = tuning
    }

    override fun holdOpen() {
        session?.let { if (it.app == null && !it.ended) it.endpointer.held = true }
    }

    override val hearing: Boolean get() = session?.let { it.app == null && !it.cancelled && it.endpointer.heardSpeech } ?: false

    override fun prewarm() {
        slot.refresh()
        if (available) worker.execute { load(); endVad() }
    }

    private fun load(): Recognizer? = slot.get { Recognizer.load(it) }

    /** The end-of-question VAD (asset model, 0.6 MB): made once and kept for the process. */
    private fun endVad(): Vad? = synchronized(vadLock) {
        // A new threshold (car mode) takes effect between questions.
        val wanted = tuning.speechThreshold
        if (vad != null && vadThreshold != wanted && session == null) {
            vad?.release()
            vad = null
        }
        vad ?: try {
            vadThreshold = wanted
            Vad(ctx.assets, VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = "vad/silero_vad.onnx", threshold = wanted,
                    // The flag drops after 0.25 s of silence; Endpointer counts on to the pause you chose.
                    minSilenceDuration = Endpointer.VAD_MIN_SILENCE_S, minSpeechDuration = Endpointer.VAD_MIN_SPEECH_S, windowSize = Listener.FRAME,
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

    private fun unavailableMessage(st: EngineState): String = shown(when (st) {
        EngineState.MISSING -> PlaceholderSpeechToText.messageText
        EngineState.BROKEN -> VoiceStrings.sttBroken("tryAgain" to UiLabel(UiStrings.tryAgain), "page" to voicePage)
        EngineState.FAILED -> VoiceStrings.sttFailed("tryAgain" to UiLabel(UiStrings.tryAgain), "page" to voicePage)
        else -> VoiceStrings.sttNotReady()
    })

    override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) {
        // Robot's own listening wins: another app's request ends here, and its listener is told (onTakenOver).
        takeOver()
        val (src, own) = open(listener) ?: return
        if (src.silenced == true) {
            own?.stop()
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.MIC_BUSY)
            return listener.onError(SpeechToText.Reason.MIC, shown(VoiceStrings.sttMicBusy()))
        }
        // 1.1: other apps' sound pauses from here until the panel ends the turn (TurnFocus), before the first frame is read.
        TurnFocus.listen(ctx)
        val window = if (shortAnswer) Endpointer.cardWindow(pauseMs) else Endpointer.windowFor(pauseMs)
        val s = Session(ids.incrementAndGet(), listener, src, own, window, shortAnswer = shortAnswer)
        session = s
        // If the microphone is closed under the question ("listen only while charging" and the charger is pulled,
        // another app takes it), the question ends with what was said; the watchdog catches a capture that stalls.
        val pre = src.attach(s.sink, PreRoll.SINCE_WAKE_MS, PreRoll.OTHERWISE_MS) { cut(s, "mic_stopped") }
        begin(s, pre)
    }

    /**
     * The everyday set (E5): another app's request, on the capture Robot's questions use (the wake word's, or one of its
     * own opened just for this when the wake word is off: never a second one beside it). Null while Robot listens or writes
     * down a question of its own: it is never ended or kept waiting for an app.
     */
    override fun startForApp(listener: SpeechToText.Listener, app: AppListen): Int? {
        if (session != null) return null
        val id = ids.incrementAndGet()
        val (src, own) = open(listener) ?: return id
        if (src.silenced == true) {
            own?.stop()
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.MIC_BUSY)
            listener.onError(SpeechToText.Reason.MIC, shown(VoiceStrings.sttMicBusy()))
            return id
        }
        // No TurnFocus here (D13): the app that asked handles its own audio focus, so nothing else's sound pauses for it.
        val s = Session(id, listener, src, own, Endpointer.windowFor(app.pauseMs), app)
        session = s
        // Only what is said from now on: no audio from before the app asked, not even after a wake word.
        val pre = src.attach(s.sink, sinceWakeMs = 0, otherwiseMs = 0) { cut(s, "mic_stopped") }
        begin(s, pre)
        return id
    }

    /**
     * What a question needs before it listens: the model, the end-of-question VAD, and the capture (the wake word's, or one
     * opened just for this when the wake word is off). Null if one is missing: [listener] has been told why.
     */
    private fun open(listener: SpeechToText.Listener): Pair<AudioFrames, Listener?>? {
        slot.refresh()
        val st = slot.state
        if (!st.usable) {
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.UNAVAILABLE)
            PlaceholderSpeechToText(unavailableMessage(st)).start(listener)
            return null
        }
        val v = endVad() ?: run {
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.ERROR)
            listener.onError(SpeechToText.Reason.ERROR, shown(VoiceStrings.sttCouldntStart()))
            return null
        }
        synchronized(vadLock) { v.reset() }
        var own: Listener? = null
        val src: AudioFrames = frames()?.takeIf { it.capturing } ?: run {
            // The wake word is off (or its listener stopped): open the microphone just for this question.
            val l = Listener(ctx, ListenerConfig(wakeWord = false), null, onWake = { _, _ -> })
            if (!l.start()) {
                l.stop()
                VoiceLog.add(VoiceEvent.STT_END, SttEnd.MIC_BUSY)
                listener.onError(SpeechToText.Reason.MIC, shown(VoiceStrings.sttNoMic()))
                return null
            }
            own = l
            l
        }
        return src to own
    }

    /** [s] listens from here, with [pre] (the audio just before it) first. */
    private fun begin(s: Session, pre: FloatArray) {
        s.preRoll = pre
        main.postDelayed({ cut(s, "watchdog") }, s.endpointer.watchdogMs)
        s.listener.onListening()
        // Loads while the person speaks (nothing to do once loaded).
        worker.execute { load() }
        RLog.i("stt_start", "ownMic" to (s.own != null), "pauseMs" to s.window * 1000 / Listener.SAMPLE_RATE,
            "preRollMs" to pre.size * 1000L / Listener.SAMPLE_RATE, "loaded" to (slot.value != null), "forApp" to (s.app != null))
        VoiceLog.add(VoiceEvent.STT_START, pre.size * 1000 / Listener.SAMPLE_RATE, if (slot.value != null) 1 else 0, if (s.own != null) 1 else 0)
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
            val db = Listener.db(ss / n).toFloat()
            if (db > s.peakDb) s.peakDb = db
            s.listener.onLevel(db)
            when (s.endpointer.onFrame(speech, n)) {
                Endpointer.Event.SPEECH -> {
                    VoiceLog.add(VoiceEvent.VAD_START, ms(s.endpointer.speechStart))
                    s.listener.onSpeechStart()
                    if (livePartials && s.wordsSoFar) maybePartial(s)
                }
                Endpointer.Event.END -> {
                    VoiceLog.add(VoiceEvent.VAD_END, ms(s.endpointer.samples - s.endpointer.speechStart))
                    if (s.endpointer.listeningOn) writeDown(s) else finish(s, "silence")
                }
                Endpointer.Event.RESUMED -> resume(s)
                Endpointer.Event.MAX_LENGTH -> {
                    // A voice detector that never ends (a television, a fan) stops at the 30 s cap.
                    VoiceLog.add(VoiceEvent.VAD_END, ms(s.endpointer.samples - maxOf(0L, s.endpointer.speechStart)))
                    if (!s.cancelled) s.listener.onCapReached()
                    finish(s, "max")
                }
                Endpointer.Event.NO_SPEECH -> noSpeech(s)
                else -> when {
                    s.writing -> {}
                    livePartials && s.wordsSoFar -> maybePartial(s)
                    else -> checkOpenWord(s)
                }
            }
        }
    }

    /** No words shown: one quiet decode in each pause, so the open-word rule still knows how the sentence stops. */
    private fun checkOpenWord(s: Session) {
        if (!s.endpointer.waitsForOpenWords) return
        val at = s.endpointer.pauseStart
        if (at < 0 || at == s.checkedPause) return
        s.checkedPause = at
        s.partialJob = worker.submit { partial(s) }
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
        if (s.ended || s.writing) return
        s.partialRunning = true
        try {
            val r = load() ?: return
            if (s.ended || s.writing) return
            val audio = s.buffer.snapshot()
            val text = r.decode(audio)
            s.partials++
            s.endpointer.openEnded = OpenWords.endsOpen(text)
            if (!s.ended && !s.writing && livePartials && s.wordsSoFar && text.isNotEmpty() && text != s.lastPartial) {
                s.lastPartial = text
                s.lastPartialSamples = audio.size
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
        val writing = synchronized(s) {
            if (s.ended) return
            s.ended = true
            s.writing
        }
        // The button or a closed capture during a write-down: nothing more is heard, and the words already on their way stand.
        if (writing) return detach(s)
        s.endedAt = SystemClock.elapsedRealtime()
        detach(s)
        // A partial still waiting is dropped; one already running finishes (native decode can't be stopped)
        // and its text is ignored, then the final decode runs.
        s.partialJob?.cancel(false)
        if (!s.cancelled) s.listener.onEndOfSpeech()
        worker.execute { final(s, reason) }
    }

    /**
     * End on silence: as [finish], but the capture stays on while the words are written down. If speech comes back before
     * they're out ([resume]) nothing is delivered and the question goes on, with all its audio. A finished sentence
     * answers as fast as ever: the decode starts at the same moment, and only speech coming back holds its result.
     */
    private fun writeDown(s: Session) {
        val turn = synchronized(s) {
            if (s.ended) return
            s.writing = true
            s.turn
        }
        s.endedAt = SystemClock.elapsedRealtime()
        s.partialJob?.cancel(false)
        if (!s.cancelled) s.listener.onEndOfSpeech()
        worker.execute { final(s, "silence", turn) }
    }

    /** Speech came back during the write-down (audio thread). */
    private fun resume(s: Session) {
        synchronized(s) {
            if (s.ended || !s.writing) return
            s.writing = false
            s.turn++
        }
        s.endpointer.openEnded = false
        RLog.i("stt_resumed", "afterMs" to SystemClock.elapsedRealtime() - s.endedAt)
        VoiceLog.add(VoiceEvent.VAD_START, ms(s.endpointer.samples))
        if (!s.cancelled) s.listener.onSpeechBack()
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
        if (why == "mic_stopped") s.listener.onError(SpeechToText.Reason.MIC, shown(VoiceStrings.sttMicStopped()))
        else s.listener.onError(SpeechToText.Reason.NO_SPEECH, shown(VoiceStrings.sttNothingHeard()))
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
    private fun final(s: Session, reason: String, turn: Int = s.turn) {
        try {
            if (s.cancelled || s.turn != turn) return
            val r = load()
            if (r == null) {
                VoiceLog.add(VoiceEvent.STT_END, SttEnd.UNAVAILABLE)
                if (!s.cancelled) s.listener.onError(SpeechToText.Reason.ERROR, unavailableMessage(slot.state))
                return
            }
            val audio = s.buffer.snapshot()
            if (tuning.logMic) VoiceLog.add(VoiceEvent.CAR_MIC, 1, (s.source as? Listener)?.routedInput?.type ?: -1, s.peakDb.toInt())
            val t0 = SystemClock.elapsedRealtime()
            val decoded = if (audio.size < MIN_SAMPLES) "" else r.decode(audio)
            val now = SystemClock.elapsedRealtime()
            val audioMs = audio.size * 1000 / Listener.SAMPLE_RATE
            val shownMs = s.lastPartialSamples * 1000 / Listener.SAMPLE_RATE
            // Numbers only: how long, how fast, how it ended, and the lengths behind the last pass and the words shown
            // (1.1: they're how a last pass shorter than the live text gets found); never the words.
            RLog.i("stt_final", "end" to RLog.Code(reason), "audioMs" to audioMs, "decodeMs" to now - t0, "waitMs" to now - s.endedAt,
                "partials" to s.partials, "heard" to s.endpointer.heardSpeech, "chars" to decoded.length,
                "shownChars" to s.lastPartial.length, "shownAudioMs" to shownMs, "empty" to decoded.isEmpty())
            val text = FinalWords.pick(decoded, s.lastPartial, s.endpointer.heardSpeech, audioMs, shownMs, (now - t0).toInt())
            // Speech came back while this ran: the question goes on, and these words are written down again with the rest.
            val closing = synchronized(s) {
                if (s.turn != turn) return
                val open = s.writing && !s.ended
                s.ended = true
                s.writing = false
                open
            }
            if (closing) detach(s)
            if (!s.cancelled) s.listener.onFinal(text)
        } catch (t: Throwable) {
            RLog.w("stt_decode", t)
            VoiceLog.add(VoiceEvent.STT_END, SttEnd.ERROR)
            if (!s.cancelled) s.listener.onError(SpeechToText.Reason.ERROR, shown(VoiceStrings.sttDecodeFailed()))
        } finally {
            if (s.turn == turn) {
                // An error or a missing model during a write-down: nobody is waiting for more audio.
                val open = synchronized(s) { (s.writing && !s.ended).also { if (it) { s.ended = true; s.writing = false } } }
                if (open) detach(s)
                if (session === s) session = null
            }
        }
    }

    /** Robot's own question only: another app's request stops through [stopForApp]. */
    override fun stop() {
        session?.takeIf { it.app == null }?.let { finish(it, "release") }
    }

    /** Robot's own question only: another app's request ends through [cancelForApp], or when Robot's own listening starts. */
    override fun cancel() {
        session?.takeIf { it.app == null }?.let(::cancelSession)
    }

    override fun stopForApp(handle: Int) {
        session?.takeIf { it.app != null && it.id == handle }?.let { finish(it, "release") }
    }

    override fun cancelForApp(handle: Int) {
        session?.takeIf { it.app != null && it.id == handle }?.let(::cancelSession)
    }

    /** [s] ends without a result: no callback (its owner asked, or [takeOver] told it). */
    private fun cancelSession(s: Session) {
        if (session === s) session = null
        s.cancelled = true
        synchronized(s) {
            if (s.ended) return
            s.ended = true
        }
        VoiceLog.add(VoiceEvent.STT_END, SttEnd.CANCELLED)
        detach(s)
        s.partialJob?.cancel(false)
    }

    /** Whatever listens now ends; another app's request (even one being written down) is told it was taken over. */
    private fun takeOver() {
        val s = session ?: return
        if (s.app != null && !s.cancelled) try { s.listener.onTakenOver() } catch (t: Throwable) { RLog.w("stt_taken", t) }
        cancelSession(s)
    }

    override fun release() {
        takeOver()
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
        const val MAX_SPEECH_S = 20f
        /** A question is capped at 30 s (Endpointer); the buffer a little above. */
        private const val MAX_SAMPLES = 31 * Listener.SAMPLE_RATE
        /** Less than this is a tap, not speech. */
        private const val MIN_SAMPLES = Listener.SAMPLE_RATE * 3 / 10
    }
}
