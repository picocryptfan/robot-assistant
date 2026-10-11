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
 * [accept] returns the labels that fired: "W<id>" or [STOP].
 *
 * 0.3.1, for "stop" and the wake phrases over Robot's voice: while she speaks, a second spotter listens instead of
 * the first, one that fires after 2 blank frames of the model's output (80 ms) instead of 3, and the speech gate
 * doesn't decide what it hears (a short "stop" may be too short to open it). It is loaded the first time she speaks
 * ([loadTalkSpotter], about 14 MB more) and kept while this lives; until then the first spotter listens while she
 * speaks too. When she is quiet, the first spotter and the gate work exactly as phase 0 measured them. A stream is
 * only ever decoded by the spotter that made it.
 */
class WakeWord(private val assets: AssetManager, vadGate: Boolean = true) {
    /** For when Robot is quiet, behind the speech gate: [IDLE_TRAILING_BLANKS], as phase 0 measured it. */
    private val kws: KeywordSpotter = spotter(IDLE_TRAILING_BLANKS)
    /**
     * For while Robot speaks ([TALK_TRAILING_BLANKS]), once [loadTalkSpotter] made it on its thread (published and
     * released under [talkLock], read on the audio thread).
     */
    @Volatile private var talkKws: KeywordSpotter? = null
    private val talkLock = Any()
    private var released = false
    private val vad: Vad?
    private var stream: OnlineStream? = null
    /** The spotter that made [stream]. */
    private var streamKws: KeywordSpotter? = null
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
    /**
     * Sets sherpa-onnx refused: each is tried and logged once, not again at every start and stop of Robot's voice;
     * forgotten when the wake phrases change.
     */
    private val refused = HashSet<String>()
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

    /** The spotter for while Robot speaks is loaded. */
    val talkSpotterLoaded: Boolean get() = talkKws != null

    /** The last labels [accept] returned came from the spotter for while Robot speaks (audio thread; KWS_HIT). */
    var lastHitByTalkSpotter = false; private set

    init {
        vad = if (!vadGate) null else Vad(assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = 0.5f,
                minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = 512),
            sampleRate = Listener.SAMPLE_RATE,
            numThreads = 1,
        ))
    }

    private fun spotter(trailingBlanks: Int): KeywordSpotter {
        fun model(part: String) = "kws/$part-epoch-12-avg-2-chunk-16-left-64.onnx"
        return KeywordSpotter(assets, KeywordSpotterConfig(
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
            // A keyword fires once the model has output more than this many blank frames (40 ms each) after its last
            // token: see IDLE_TRAILING_BLANKS and TALK_TRAILING_BLANKS.
            numTrailingBlanks = trailingBlanks,
        ))
    }

    /**
     * Makes the spotter for while Robot speaks (a loader thread: the model is loaded a second time). False if it was
     * already there or this was released meanwhile; throws if sherpa-onnx can't make it.
     */
    fun loadTalkSpotter(): Boolean {
        val k = spotter(TALK_TRAILING_BLANKS)
        synchronized(talkLock) {
            if (!released && talkKws == null) {
                talkKws = k
                return true
            }
        }
        k.release()
        return false
    }

    /** The lines to listen for: [wake] (already built from the settings) and "STOP" with [stop]'s threshold, if set. */
    private fun wanted(wake: String, stop: KwsThresholds.Preset?): String {
        if (wake === lastWake && stop == lastStop) return wanted
        // New wake phrases: a set refused before may be fine now that they changed.
        if (wake != lastWake) refused.clear()
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

    /** A stream of [spotter] for [keywords], made [stream]; null if there is nothing to listen for or it is refused. */
    private fun newStream(keywords: String, spotter: KeywordSpotter): OnlineStream? {
        if (keywords.isEmpty() || keywords in refused) return null
        return try {
            // sherpa-onnx answers a set it can't encode with a null stream (the vendored OnlineStream refuses it too).
            spotter.createStream(keywords).also {
                check(it.ptr != 0L) { "null stream" }
                stream = it
                streamKws = spotter
                streamKeywords = keywords
            }
        } catch (t: Throwable) {
            // Remembered rather than retried every frame (or at every start and stop of Robot's voice).
            RLog.w("kws_stream", t)
            refused += keywords
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
        streamKws = null
        streamKeywords = ""
    }

    /**
     * Feeds one chunk (16 kHz mono floats) and returns the labels that fired, oldest first (usually none). [wake]: the
     * wake lines (may be empty); [stop]: listen for "STOP" too, with this threshold (Robot is speaking), or null.
     */
    fun accept(samples: FloatArray, wake: String, stop: KwsThresholds.Preset? = null): List<String> {
        seen += samples.size
        val want = wanted(wake, stop)
        val spotter = if (stop != null) talkKws ?: kws else kws
        // The keyword set or the spotter changed (Robot started or stopped speaking, the settings changed, the spotter
        // for her talking was loaded): a fresh stream.
        if (stream != null && (streamKeywords != want || streamKws !== spotter)) dropStream()
        val v = vad
        if (v == null) {
            if (stream == null) newStream(want, spotter)
            return feed(samples)
        }
        v.acceptWaveform(samples)
        val speech = v.isSpeechDetected()
        // Only the speech flag is used; drop the segments the VAD queues so they do not pile up.
        while (!v.empty()) v.pop()
        gate(speech)
        preRoll.push(samples)
        if (speech) lastSpeech = seen
        // While Robot speaks ("stop" listened for), one stream hears every frame: the gate needs 0.25 s of speech
        // before it opens and a short "stop" over her voice may not get there. The gate still runs, for the log and
        // the capture switch.
        if (stop != null) {
            if (stream == null) {
                if (newStream(want, spotter) == null) return NONE
                return feed(preRoll.drain())
            }
            return feed(samples)
        }
        val s = stream
        return when {
            speech && s == null && want.isNotEmpty() -> {
                if (newStream(want, spotter) == null) NONE else feed(preRoll.drain())
            }
            s != null -> {
                val hits = feed(samples)
                if (!speech && seen - lastSpeech > HANGOVER_SAMPLES) dropStream()
                hits
            }
            else -> NONE
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

    /** Every label that fires in [x] (a pre-roll may hold more than one), oldest first. */
    private fun feed(x: FloatArray): List<String> {
        val s = stream ?: return NONE
        val k = streamKws ?: return NONE
        s.acceptWaveform(x, Listener.SAMPLE_RATE)
        var hits: MutableList<String>? = null
        while (k.isReady(s)) {
            k.decode(s)
            val label = k.getResult(s).keyword
            if (label.isNotBlank()) {
                (hits ?: mutableListOf<String>().also { hits = it }).add(label)
                // sherpa-onnx: reset the stream right after a detection.
                k.reset(s)
            }
        }
        val out = hits ?: return NONE
        // The audio that led to a hit is never fed again: a stream started later begins from what comes after it.
        preRoll.clear()
        lastHitByTalkSpotter = k === talkKws
        return out
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

    /**
     * Wake detection goes on after a pause (the panel listened for a question): the stream, the gate and the pre-roll
     * start empty. The pre-roll stood still while paused and still holds the wake phrase that opened the panel; fed
     * into the first stream after the pause (with "Sorry, I didn't catch that." or any quick reply) it would wake
     * Robot a second time.
     */
    fun resume() {
        reset()
        preRoll.clear()
    }

    /** Forgets the last second of audio without touching the stream (Robot started or stopped talking). */
    fun clearPreRoll() = preRoll.clear()

    fun release() {
        dropStream()
        vad?.release()
        kws.release()
        synchronized(talkLock) {
            released = true
            talkKws?.release()
            talkKws = null
        }
    }

    companion object {
        const val PRE_ROLL_SAMPLES = Listener.SAMPLE_RATE
        const val HANGOVER_SAMPLES = Listener.SAMPLE_RATE
        /**
         * When Robot is quiet: sherpa-onnx's Kotlin default, which phase 0 measured (3 blank frames, 120 ms, after the
         * phrase).
         */
        const val IDLE_TRAILING_BLANKS = 2
        /**
         * While Robot speaks: sherpa-onnx's C++ default (2 blank frames, 80 ms). Robot's voice, even echo-cancelled,
         * probably seldom leaves 120 ms with no token at all after a "stop" said over her.
         */
        const val TALK_TRAILING_BLANKS = 1
        /** The label of "STOP" (kws/stop.txt, after the @). */
        const val STOP = KeywordBuffer.STOP_LABEL
        private val NONE = emptyList<String>()
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

    /** Empties the ring. */
    fun clear() {
        start = 0
        size = 0
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
