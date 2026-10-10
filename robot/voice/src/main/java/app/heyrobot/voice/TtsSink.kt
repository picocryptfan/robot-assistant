package app.heyrobot.voice

/**
 * Receives each chunk of samples sherpa-onnx's OfflineTts produces (one call per sentence with
 * maxNumSentences = 1). Return true to keep generating, false to stop. Never hand this, or any Kotlin lambda,
 * to sherpa directly: wrap it in [TtsCallback] (see there for why).
 */
fun interface TtsSink {
    fun onSamples(samples: FloatArray): Boolean
}
