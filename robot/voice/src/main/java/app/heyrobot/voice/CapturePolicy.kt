package app.heyrobot.voice

import app.heyrobot.core.BargeIn
import app.heyrobot.core.InterruptSensitivity

// Pure. Which microphone mode the always-on capture should use (0.2.3, "hear me while Robot talks"). The speech
// recognition mode is best for the wake word and questions but hears Robot's own voice at full strength while she
// speaks, so "stop" and the wake word drown. The call mode (VOICE_COMMUNICATION) runs the phone's echo canceller,
// which takes what the speaker plays out of what the microphone hears. Switching means a new AudioRecord (a gap of
// a few tens of ms), so it happens only between questions, never in the middle of one.

object CapturePolicy {
    /** After Robot's voice stops, the call mode stays this long (her last words may still echo in the room). */
    const val TAIL_MS = 800L
    /** Longest a switch back to speech recognition waits for the wake gate to stop hearing speech (a noisy room). */
    const val MAX_HOLD_MS = 8_000L

    enum class Capture { RECOGNITION, COMMUNICATION }

    /**
     * The mode wanted now. [msSinceSpoke]: since Robot's voice last stopped (Long.MAX_VALUE: not lately). [panelOpen]:
     * the panel is on screen; [testing]: the barge-in test runs (it counts as an open panel).
     */
    fun desired(mode: BargeIn, speaking: Boolean, msSinceSpoke: Long, panelOpen: Boolean, testing: Boolean): Capture = when (mode) {
        BargeIn.OFF -> Capture.RECOGNITION
        BargeIn.ALWAYS -> if (speaking || panelOpen || testing || msSinceSpoke < TAIL_MS) Capture.COMMUNICATION else Capture.RECOGNITION
        BargeIn.ECHO_CANCEL -> if (speaking || msSinceSpoke < TAIL_MS) Capture.COMMUNICATION else Capture.RECOGNITION
    }

    /**
     * What the capture switches to now: never while a question is being captured (its audio would have a gap), and
     * not back to speech recognition while the wake gate hears speech (a "hey robot" said just after Robot stops would
     * be cut in two), for at most [MAX_HOLD_MS] of waiting ([heldMs]).
     */
    fun next(current: Capture, desired: Capture, questionOpen: Boolean, gateSpeech: Boolean = false, heldMs: Long = 0L): Capture = when {
        questionOpen -> current
        desired == Capture.RECOGNITION && current == Capture.COMMUNICATION && gateSpeech && heldMs < MAX_HOLD_MS -> current
        else -> desired
    }
}

/**
 * What the keyword spotter listens for, shared by VoiceHub and its listener and read on every frame. The wake lines are
 * built on another thread and a listener can be replaced at any time; a value copied into a listener could be lost
 * between the two (the wake word would stay deaf), a shared one can't.
 */
class KeywordInputs {
    /** The wake phrases' keyword lines (core KeywordBuffer.wake); empty: none. */
    @Volatile var wakeLines: String = ""
    /**
     * The same phrases while Robot talks, with Settings › Voice › "Interrupt sensitivity" applied (KwsThresholds.talkWake);
     * null: [wakeLines] (Medium, or not built yet).
     */
    @Volatile var talkWakeLines: String? = null
    /** "Interrupt sensitivity": how readily "stop" is heard while Robot talks (KwsThresholds.stop). */
    @Volatile var interrupt: InterruptSensitivity = InterruptSensitivity.MEDIUM
    /** Robot is speaking: "stop" is listened for too. */
    @Volatile var speaking: Boolean = false

    /** The wake lines for now: [talkWakeLines] while Robot speaks, else [wakeLines]. */
    fun wake(speaking: Boolean): String = if (speaking) talkWakeLines ?: wakeLines else wakeLines
}
