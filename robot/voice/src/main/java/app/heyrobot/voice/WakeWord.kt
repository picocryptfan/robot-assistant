package app.heyrobot.voice

import android.content.res.AssetManager
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
 * "HEY ROBOT" with sherpa-onnx open-vocabulary keyword spotting (zipformer gigaspeech 3.3M), gated by
 * Silero VAD so the keyword model only sees audio around speech, exactly as phase 0 proved it (threshold
 * 0.25, boost 1.5: 10 of 10 close, 5 of 5 across the room, no false triggers in 2 h). A 1 s pre-roll is
 * fed when speech starts, because the VAD needs ~0.25 s of speech before it says so and "hey" is short.
 */
class WakeWord(assets: AssetManager, cfg: ListenerConfig) {
    private val kws: KeywordSpotter
    private val vad: Vad?
    private var stream: OnlineStream? = null
    private val preRoll = FloatRing(PRE_ROLL_SAMPLES)
    private var seen = 0L
    private var lastSpeech = Long.MIN_VALUE / 2

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
            keywordsFile = "kws/keywords.txt",
            keywordsScore = cfg.boost,
            keywordsThreshold = cfg.threshold,
        ))
        vad = if (!cfg.vadGate) null else Vad(assets, VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = 0.5f,
                minSilenceDuration = 0.25f, minSpeechDuration = 0.25f, windowSize = 512),
            sampleRate = Listener.SAMPLE_RATE,
            numThreads = 1,
        ))
        if (vad == null) stream = kws.createStream()
    }

    /** Feeds one chunk (16 kHz mono floats); returns the keyword if it fired. */
    fun accept(samples: FloatArray): String? {
        seen += samples.size
        val v = vad ?: return feed(samples)
        v.acceptWaveform(samples)
        val speech = v.isSpeechDetected()
        // Only the speech flag is used; drop the segments the VAD queues so they do not pile up.
        while (!v.empty()) v.pop()
        preRoll.push(samples)
        if (speech) lastSpeech = seen
        val s = stream
        return when {
            speech && s == null -> {
                stream = kws.createStream()
                feed(preRoll.drain())
            }
            s != null -> {
                val hit = feed(samples)
                if (!speech && seen - lastSpeech > HANGOVER_SAMPLES) {
                    s.release()
                    stream = null
                }
                hit
            }
            else -> null
        }
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

    fun release() {
        stream?.release()
        stream = null
        vad?.release()
        kws.release()
    }

    companion object {
        const val PRE_ROLL_SAMPLES = Listener.SAMPLE_RATE
        const val HANGOVER_SAMPLES = Listener.SAMPLE_RATE
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

    /** Oldest first; empties the ring. */
    fun drain(): FloatArray {
        val out = FloatArray(size) { buf[(start + it) % buf.size] }
        start = 0
        size = 0
        return out
    }
}
