// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceBundle
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

// VOICE PROCESS ONLY, :voice module only (sherpa-onnx). Who is speaking (docs/conversation-mode.md 3.1, phase 5.3):
// NVIDIA TitaNet-S in sherpa-onnx's SpeakerEmbeddingExtractor turns a stretch of speech into 192 numbers, and the same
// voice gives nearby numbers (core SpeakerClusterer compares them). The model is the downloaded "Speaker labels" bundle,
// loaded on first use behind the crash guard like the other voice models, and freed after 5 minutes unused unless a
// conversation (or the "me" enrolment) holds it. One embedding at a time, on its own thread ("robot-spk"), so it never
// waits behind a decode. The audio stays in memory; an embedding goes only to the session's clusterer (memory, dropped
// when the session ends) or to the "me" enrolment (an encrypted file); neither is ever logged.

/** TitaNet-S in sherpa-onnx's extractor. Not thread-safe: one embedding at a time. */
internal class Embedder private constructor(private val x: SpeakerEmbeddingExtractor) {
    val dim: Int = x.dim()

    /** The embedding of [samples] (16 kHz mono), or null when the model got too little audio to say. */
    fun embed(samples: FloatArray): FloatArray? {
        val s = x.createStream()
        try {
            s.acceptWaveform(samples, Listener.SAMPLE_RATE)
            s.inputFinished()
            // compute() on a stream with no frames ends the process in sherpa-onnx 1.13.8: ask first.
            if (!x.isReady(s)) return null
            return x.compute(s).takeIf { e -> e.size == dim && e.all { it.isFinite() } }
        } finally {
            s.release()
        }
    }

    fun release() = x.release()

    companion object {
        const val THREADS = 2

        fun load(dir: File): Embedder {
            // The verified mark was checked by the slot; a file that changed since then is not loaded.
            val f = VoiceBundle.SPEAKERS.files.single()
            val model = File(dir, f.name)
            require(model.length() == f.bytes) { "size" }
            // Never debug = true: sherpa would log its configuration and more.
            return Embedder(SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(model = model.absolutePath, numThreads = THREADS,
                debug = false, provider = "cpu")))
        }
    }
}

/** A voice embedding for conversation mode's speaker labels and the "me" enrolment (VoiceEngines.speakerEmbedder). */
interface SpeakerEmbedding {
    /** The speaker model is downloaded, verified and not turned off by crashes. */
    val available: Boolean
    /** Keep the model loaded while a session (or the enrolment) runs. */
    fun hold(on: Boolean)
    /** The embedding of [samples] (16 kHz mono; waits its turn), or null when there is too little audio or no usable model. */
    fun embed(samples: FloatArray): FloatArray?
}

class SpeakerEmbedder internal constructor(private val slot: ModelSlot<Embedder>) : SpeakerEmbedding {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-spk") }
    private val holds = AtomicInteger()

    override val available: Boolean get() {
        slot.refresh()
        return slot.state.usable
    }

    override fun hold(on: Boolean) {
        if (on) holds.incrementAndGet() else holds.updateAndGet { maxOf(0, it - 1) }
    }

    override fun embed(samples: FloatArray): FloatArray? {
        if (samples.size < MIN_SAMPLES) return null
        return try {
            worker.submit(Callable { slot.get { Embedder.load(it) }?.embed(samples) }).get()
        } catch (t: Throwable) {
            RLog.w("spk_embed", t)
            null
        }
    }

    /** VoiceEngines' idle check: free the model after 5 minutes unused, never while a session holds it. */
    internal fun unloadIfIdle() {
        worker.execute { slot.releaseIfIdle(inUse = holds.get() > 0) }
    }

    /** Frees the model (it loads again on next use). */
    internal fun unload() {
        worker.execute { slot.release() }
    }

    /** Memory pressure: free it unless something holds it. */
    internal fun trim(): Boolean {
        if (holds.get() > 0 || slot.value == null) return false
        unload()
        return true
    }

    companion object {
        /** Under half a second there is too little voice to say whose it is (the session asks for 1 s of speech). */
        const val MIN_SAMPLES = Listener.SAMPLE_RATE / 2
    }
}
