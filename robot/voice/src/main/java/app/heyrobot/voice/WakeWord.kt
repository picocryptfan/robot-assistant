package app.heyrobot.voice

import android.content.res.AssetManager
import app.heyrobot.core.KeywordBuffer
import app.heyrobot.core.KwsSpeller
import app.heyrobot.core.KwsRefusal
import app.heyrobot.core.KwsThresholds
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig

// VOICE PROCESS ONLY, :voice module only. Uses sherpa-onnx (its classes load libsherpa-onnx-jni.so and
// libonnxruntime.so on first use); ProcessIsolationTest keeps sherpa-onnx inside this module.

/**
 * The wake phrases with sherpa-onnx open-vocabulary keyword spotting (zipformer gigaspeech 3.3M), gated by Silero
 * VAD so the keyword model only sees audio around speech, as phase 0 proved it ("hey robot" at threshold 0.25, boost
 * 1.5: 10 of 10 close, 5 of 5 across the room, no false triggers in 2 h). A 1 s pre-roll is fed when speech starts,
 * because the VAD needs ~0.25 s of speech before it says so and "hey" is short.
 *
 * Since 0.2.3 the keywords come at runtime, as lines for KeywordSpotter.createStream: one per enabled wake phrase
 * ("<tokens> :<boost> #<threshold> @W<id>", built by core KeywordBuffer from Settings), plus "STOP" (kws/stop.txt)
 * while Robot speaks. sherpa-onnx adds a stream's keywords to the spotter's own list, so kws/keywords.txt is one empty
 * line: nothing is spotted that the settings don't ask for. A changed set starts a fresh stream (no app restart).
 * [accept] returns the label that fired: "W<id>" or [STOP].
 */
class WakeWord(assets: AssetManager, vadGate: Boolean = true) {
    private val kws: KeywordSpotter
    private val vad: Vad?
    private var stream: OnlineStream? = null
    /** The keyword set [stream] listens for. */
    private var streamKeywords = ""
    /** Every token the model knows: a line with any other token never reaches sherpa-onnx (its stream would fail). */
    private val known: Set<String> = try {
        KwsSpeller.parseTokens(assets.open("kws/tokens.txt").use { it.readBytes().toString(Charsets.UTF_8) })
    } catch (_: Exception) { emptySet() }
    /** "▁ST O P", or null if the asset is missing or the model refused it (then stop-by-voice is off). */
    private var stopTokens: List<String>? = try {
        KeywordBuffer.tokensOf(assets.open("kws/stop.txt").use { it.readBytes().toString(Charsets.UTF_8) }).takeIf { it.isNotEmpty() }
    } catch (_: Exception) { null }
    private val preRoll = FloatRing(PRE_ROLL_SAMPLES)
    private var seen = 0L
    private var lastSpeech = Long.MIN_VALUE / 2
    // The keyword set wanted, recomputed only when its inputs change (accept runs every 32 ms).
    private var lastWake: String? = null
    private var lastStop: KwsThresholds.Preset? = null
    private var wanted = ""
    /** A set sherpa-onnx refused: not tried again until it changes. */
    private var refused: String? = null
    /** The lines last left out for unknown tokens, so the voice log says so once, not at every change of the set. */
    private var droppedLogged: List<String> = emptyList()
    // The speech gate's stretches of speech, for the voice log.
    private var gateSpeaking = false
    private var gateFrom = 0L

    /** Called with the length (ms) of each stretch of speech the gate heard, when it ends. */
    var onGateSpeech: ((Int) -> Unit)? = null

    /** The speech gate hears speech right now (audio thread). */
    val hearing: Boolean get() = gateSpeaking

    /** A keyword stream is live and listens for "stop" (audio thread; the voice log's SPEAK_RUN). */
    val listeningForStop: Boolean get() = stream != null && streamKeywords.contains("@${KeywordBuffer.STOP_LABEL}")

    init {
        fun model(part: String) = "kws/$part-epoch-12-avg-2-chunk-16-left-64.onnx"
        kws = KeywordSpotter(assets, KeywordSpotterConfig(
            featConfig = getFeatureConfig(sampleRate = Listener.SAMPLE_RATE, featureDim = 80),
            modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(encoder = model("encoder"), decoder = model("decoder"), joiner = model("joiner")),
                tokens = "kws/tokens.txt",
                modelType = "zipformer2",
                numThreads = 1,
            ),
            // One empty line (see above). Every line Robot gives carries its own boost and threshold; these are
            // only sherpa's fallback.
            keywordsFile = "kws/keywords.txt",
            keywordsScore = KwsThresholds.MEDIUM.boost,
            keywordsThreshold = KwsThresholds.MEDIUM.threshold,
        ))
        vad = if (!vadGate) null else Vad(assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = 0.5f,
                minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = 512),
            sampleRate = Listener.SAMPLE_RATE,
            numThreads = 1,
        ))
    }

    /** The lines to listen for: [wake] (already built from the settings) and "STOP" with [stop]'s threshold, if set. */
    private fun wanted(wake: String, stop: KwsThresholds.Preset?): String {
        if (wake === lastWake && stop == lastStop) return wanted
        lastWake = wake
        lastStop = stop
        val st = stopTokens
        val stopLine = if (stop != null && st != null) KeywordBuffer.line(st, stop, KeywordBuffer.STOP_LABEL) else ""
        val lines = KeywordBuffer.join(wake, stopLine).lines().filter { it.isNotBlank() }
        // Checked again here: whatever the settings say, sherpa-onnx only ever gets tokens it knows.
        val ok = lines.filter { KeywordBuffer.validLine(it, known) }
        val dropped = lines - ok.toSet()
        if (dropped.isNotEmpty() && dropped != droppedLogged) {
            RLog.i("kws_lines_dropped", "count" to dropped.size)
            VoiceLog.add(VoiceEvent.KWS_REFUSED, KwsRefusal.LINES, dropped.count { it.contains("@W") }, if (stopLine in dropped) 1 else 0)
            droppedLogged = dropped
        }
        wanted = ok.joinToString("\n")
        return wanted
    }

    private fun newStream(keywords: String): OnlineStream? {
        if (keywords.isEmpty() || keywords == refused) return null
        return try {
            // sherpa-onnx answers a set it can't encode with a null stream (the vendored OnlineStream refuses it too).
            kws.createStream(keywords).also {
                check(it.ptr != 0L) { "null stream" }
                streamKeywords = keywords
            }
        } catch (t: Throwable) {
            // sherpa-onnx returns no stream for a set it can't encode; remember it rather than retrying every frame.
            RLog.w("kws_stream", t)
            refused = keywords
            val withStop = keywords.contains("@${KeywordBuffer.STOP_LABEL}")
            val stopOnly = withStop && !keywords.contains("@W")
            if (stopOnly) stopTokens = null
            VoiceLog.add(VoiceEvent.KWS_REFUSED, if (stopOnly) KwsRefusal.STOP else KwsRefusal.SET,
                keywords.lines().count { it.contains("@W") }, if (withStop) 1 else 0)
            null
        }
    }

    private fun dropStream() {
        stream?.release()
        stream = null
        streamKeywords = ""
    }

    /**
     * Feeds one chunk (16 kHz mono floats) and returns the label that fired, if any. [wake]: the wake lines (may be
     * empty); [stop]: listen for "STOP" too, with this threshold (Robot is speaking), or null.
     */
    fun accept(samples: FloatArray, wake: String, stop: KwsThresholds.Preset? = null): String? {
        seen += samples.size
        val want = wanted(wake, stop)
        // The keyword set changed (Robot started or stopped speaking, the settings changed): a fresh stream.
        if (stream != null && streamKeywords != want) dropStream()
        val v = vad
        if (v == null) {
            if (stream == null) stream = newStream(want)
            return feed(samples)
        }
        v.acceptWaveform(samples)
        val speech = v.isSpeechDetected()
        // Only the speech flag is used; drop the segments the VAD queues so they do not pile up.
        while (!v.empty()) v.pop()
        gate(speech)
        preRoll.push(samples)
        if (speech) lastSpeech = seen
        val s = stream
        return when {
            speech && s == null && want.isNotEmpty() -> {
                stream = newStream(want)
                feed(preRoll.drain())
            }
            s != null -> {
                val hit = feed(samples)
                if (!speech && seen - lastSpeech > HANGOVER_SAMPLES) dropStream()
                hit
            }
            else -> null
        }
    }

    private fun gate(speech: Boolean) {
        if (speech && !gateSpeaking) gateFrom = seen
        if (!speech && gateSpeaking) {
            val ms = ((seen - gateFrom) * 1000 / Listener.SAMPLE_RATE).toInt()
            try { onGateSpeech?.invoke(ms) } catch (_: Throwable) {}
        }
        gateSpeaking = speech
    }

    private fun feed(x: FloatArray): String? {
        val s = stream ?: return null
        s.acceptWaveform(x, Listener.SAMPLE_RATE)
        var hit: String? = null
        while (kws.isReady(s)) {
            kws.decode(s)
            val k = kws.getResult(s).keyword
            if (k.isNotBlank()) {
                hit = k
                // sherpa-onnx: reset the stream right after a detection.
                kws.reset(s)
            }
        }
        return hit
    }

    /**
     * The capture changed (a new AudioRecord): the keyword stream and the gate start again, but the pre-roll is kept,
     * so a word said across the few-ms gap is fed again whole when the gate hears speech.
     */
    fun reset() {
        dropStream()
        vad?.reset()
        gateSpeaking = false
        lastSpeech = Long.MIN_VALUE / 2
    }

    fun release() {
        dropStream()
        vad?.release()
        kws.release()
    }

    companion object {
        const val PRE_ROLL_SAMPLES = Listener.SAMPLE_RATE
        const val HANGOVER_SAMPLES = Listener.SAMPLE_RATE
        /** The label of "STOP" (kws/stop.txt, after the @). */
        const val STOP = KeywordBuffer.STOP_LABEL
    }
}

/** Fixed-size ring of the most recent samples. */
class FloatRing(capacity: Int) {
    private val buf = FloatArray(capacity)
    private var start = 0
    private var size = 0

    fun push(x: FloatArray) {
        for (v in x) {
            buf[(start + size) % buf.size] = v
            if (size < buf.size) size++ else start = (start + 1) % buf.size
        }
    }

    /** The newest [n] samples (fewer if the ring holds fewer), oldest first; the ring keeps them. */
    fun last(n: Int): FloatArray {
        val k = n.coerceIn(0, size)
        return FloatArray(k) { buf[(start + size - k + it) % buf.size] }
    }

    /** Oldest first; empties the ring. */
    fun drain(): FloatArray {
        val out = FloatArray(size) { buf[(start + it) % buf.size] }
        start = 0
        size = 0
        return out
    }
}

/**
 * The gate's stretches of speech, thinned for the voice log (pure): at most one line per [minGapMs], the stretches
 * in between counted into the next line, so a television can't fill the 300-entry log.
 */
class GateSpeechLog(private val minGapMs: Long = 10_000L) {
    private var lastAt = Long.MIN_VALUE / 2
    private var merged = 0
    private var longest = 0

    /** A stretch of [ms] ended at [now]: (longest ms, stretches) to log now, or null to hold it. */
    fun onSpeech(ms: Int, now: Long): Pair<Int, Int>? {
        merged++
        longest = maxOf(longest, ms)
        if (now - lastAt < minGapMs) return null
        lastAt = now
        return (longest to merged).also { merged = 0; longest = 0 }
    }
}
