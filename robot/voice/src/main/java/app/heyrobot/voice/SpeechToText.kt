package app.heyrobot.voice

// VOICE PROCESS. Speech to text for push-to-talk in the panel and the question after a wake word
// (spec item 3): SherpaSpeechToText (Parakeet v3) once its model is downloaded, [PlaceholderSpeechToText]
// until then (VoiceEngines picks). Audio stays in memory: never written to disk.

interface SpeechToText {
    /** False while no recognizer is installed (the panel then offers the keyboard). */
    val available: Boolean

    /** Why voice input is not available, in a few words for the mic button's tooltip; null when it is. */
    val hint: String? get() = null

    /** Show partial text while the person speaks (decodes the speech so far about every 0.7 s). */
    fun configure(livePartials: Boolean) {}

    /** Start loading the model in the background (the panel opened), so the first question is not kept waiting. */
    fun prewarm() {}

    /** Memory is short: free the model unless it is listening or decoding. True if something was freed. */
    fun trim(): Boolean = false

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
        /** Capture ended (silence or release); the final text follows once it is decoded. */
        fun onEndOfSpeech() {}
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

/** Stand-in while the speech model is missing or broken: says why and lets the panel fall back to the text field. */
class PlaceholderSpeechToText(private val message: String = MESSAGE) : SpeechToText {
    override val available: Boolean = false

    override fun start(listener: SpeechToText.Listener) {
        listener.onError(SpeechToText.Reason.UNAVAILABLE, message)
    }

    override fun stop() {}
    override fun cancel() {}
    override fun release() {}

    companion object {
        const val MESSAGE = "Voice input needs the voice models: download them in the Robot app (Model › Voice models). Type your question for now."
    }
}
