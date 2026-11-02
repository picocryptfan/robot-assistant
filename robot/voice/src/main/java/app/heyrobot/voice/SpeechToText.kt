// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.VoiceStrings

// VOICE PROCESS. Speech to text for push-to-talk in the panel and the question after a wake word
// (spec item 3): SherpaSpeechToText (Parakeet v3) once its model is downloaded, [PlaceholderSpeechToText]
// until then (VoiceEngines picks). Audio stays in memory: never written to disk.

interface SpeechToText {
    /** False while no recognizer is installed (the panel then offers the keyboard). */
    val available: Boolean

    /** Why voice input is not available, in a few words for the mic button's tooltip; null when it is. */
    val hint: String? get() = null

    /**
     * Show partial text while the person speaks (decodes the speech so far about every 0.7 s), and the pause a question
     * may hold before it ends ([pauseMs], Settings › Voice › "Wait before answering"; from the next question on).
     */
    fun configure(livePartials: Boolean, pauseMs: Int = Endpointer.DEFAULT_PAUSE_MS, tuning: SpeechTuning = SpeechTuning()) {}

    /** Start loading the model in the background (the panel opened), so the first question is not kept waiting. */
    fun prewarm() {}

    /** Memory is short: free the model unless it is listening or decoding. True if something was freed. */
    fun trim(): Boolean = false

    /**
     * Starts listening; results arrive on [listener] (on any thread: callers hop to their own). It ends
     * by itself on silence (VAD), or when [stop] is called (button released), with [Listener.onFinal]. [shortAnswer]
     * (a card's "yes" or "no"): no extra wait for an open last word, and the pause setting still holds.
     */
    fun start(listener: Listener, shortAnswer: Boolean = false)

    /** The mic button is held past a tap: silence doesn't end this question; [stop] (the release) does. */
    fun holdOpen() {}

    /** Speech was heard in the current question and its words are not in yet (a timer shouldn't end it now). */
    val hearing: Boolean get() = false

    /** Stop capturing and finish: the final text (possibly empty) arrives in [Listener.onFinal]. */
    fun stop()

    /** Stop without a result. */
    fun cancel()

    fun release()

    /**
     * The everyday set's listening for other apps (everyday-set-plan.md §6.2, E5): listens for another app's request as
     * [start] does for Robot's own questions, on the same capture, but takes no audio focus (the app that asked handles its
     * own), keeps no audio from before the request, and ends on [app]'s pause. Robot's own listening wins: null (nothing
     * started, no callback) while Robot listens or writes down a question, and Robot's own [start] ends the request, whose
     * listener then hears [Listener.onTakenOver]. [stop], [cancel] and [holdOpen] never touch it: only [stopForApp] and
     * [cancelForApp] with the handle this returns. Otherwise every outcome reaches [listener], as for [start].
     */
    fun startForApp(listener: Listener, app: AppListen): Int? = null

    /** Another app's request [handle] stops listening and its words are written down ([Listener.onFinal]). */
    fun stopForApp(handle: Int) {}

    /** Another app's request [handle] ends without a result. */
    fun cancelForApp(handle: Int) {}

    interface Listener {
        /** Capture started (show "Listening"). */
        fun onListening() {}
        /** Input level in dBFS, for a level meter. */
        fun onLevel(db: Float) {}
        /** Speech began (the voice detector heard it). */
        fun onSpeechStart() {}
        /** The words so far, if the model streams them (replaces the previous partial). */
        fun onPartial(text: String) {}
        /** Capture ended (silence or release); the final text follows once it is decoded. */
        fun onEndOfSpeech() {}
        /** You spoke again after [onEndOfSpeech], before the words were out: back to listening, no final text yet. */
        fun onSpeechBack() {}
        /** The 30 s cap cut the speech off: the [onFinal] that follows is only part of it. */
        fun onCapReached() {}
        fun onFinal(text: String)
        fun onError(reason: Reason, message: String)
        /** Another app's request only ([startForApp]): Robot's own listening took over; nothing more comes. */
        fun onTakenOver() {}
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

/**
 * Another app's request ([SpeechToText.startForApp]): the pause that ends it ([pauseMs], core ListenForApps.pauseMs), and
 * whether to decode the words so far as they come ([partials]: the app asked for them and "Show words as you speak" is on).
 */
data class AppListen(val pauseMs: Int, val partials: Boolean)

/** The question's speech detector ([speechThreshold], core CarHearing; from the next question on) and whether to log the microphone (car mode). */
data class SpeechTuning(val speechThreshold: Float = 0.5f, val logMic: Boolean = false)

/** Stand-in while the speech model is missing or broken: says why and lets the panel fall back to the text field. */
class PlaceholderSpeechToText(private val message: String = MESSAGE) : SpeechToText {
    override val available: Boolean = false

    override fun start(listener: SpeechToText.Listener, shortAnswer: Boolean) {
        listener.onError(SpeechToText.Reason.UNAVAILABLE, message)
    }

    override fun stop() {}
    override fun cancel() {}
    override fun release() {}

    companion object {
        /** 1.1: without a language yet (the recognizer says it in the menus' language, as the panel's notices). */
        val messageText: Phrase get() = VoiceStrings.sttNeedsModels("page" to UiStrings.inSettings(UiStrings.model, UiStrings.voiceModels))
        val MESSAGE: String get() = messageText.en()
    }
}
