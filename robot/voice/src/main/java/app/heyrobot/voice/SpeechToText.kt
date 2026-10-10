package app.heyrobot.voice

// VOICE PROCESS. Speech to text for push-to-talk in the panel and the question after a wake word
// (spec item 3). Phase 1 ships [PlaceholderSpeechToText]; the voice spike's sherpa-onnx model replaces
// it behind this interface (create it in VoiceEngines). Audio stays in memory: never written to disk.

interface SpeechToText {
    /** False while no recognizer is installed (the panel then offers the keyboard). */
    val available: Boolean

    /**
     * Starts listening; results arrive on [listener] (on any thread: callers hop to their own). It ends
     * by itself on silence (VAD), or when [stop] is called (button released), with [Listener.onFinal].
     */
    fun start(listener: Listener)

    /** Stop capturing and finish: the final text (possibly empty) arrives in [Listener.onFinal]. */
    fun stop()

    /** Stop without a result. */
    fun cancel()

    fun release()

    interface Listener {
        /** Capture started (show "Listening"). */
        fun onListening() {}
        /** Input level in dBFS, for a level meter. */
        fun onLevel(db: Float) {}
        /** The words so far, if the model streams them (replaces the previous partial). */
        fun onPartial(text: String) {}
        fun onFinal(text: String)
        fun onError(reason: Reason, message: String)
    }

    enum class Reason {
        /** No recognizer in this build ([message] says what to do instead). */
        UNAVAILABLE,
        /** Nothing was said before the silence timeout. */
        NO_SPEECH,
        /** No microphone permission, or another app holds the microphone. */
        MIC,
        ERROR,
    }
}

/** Phase 1 stand-in: says voice input is coming and lets the panel fall back to the text field. */
class PlaceholderSpeechToText : SpeechToText {
    override val available: Boolean = false

    override fun start(listener: SpeechToText.Listener) {
        listener.onError(SpeechToText.Reason.UNAVAILABLE, MESSAGE)
    }

    override fun stop() {}
    override fun cancel() {}
    override fun release() {}

    companion object {
        const val MESSAGE = "Voice input arrives in the next build. Type your question for now."
    }
}
