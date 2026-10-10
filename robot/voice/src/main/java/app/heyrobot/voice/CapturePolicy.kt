package app.heyrobot.voice

import app.heyrobot.core.BargeIn

// Pure. Which microphone mode the always-on capture should use (0.2.3, "hear me while Robot talks"). The speech
// recognition mode is best for the wake word and questions but hears Robot's own voice at full strength while she
// speaks, so "stop" and the wake word drown. The call mode (VOICE_COMMUNICATION) runs the phone's echo canceller,
// which takes what the speaker plays out of what the microphone hears. Switching means a new AudioRecord (a gap of
// a few tens of ms), so it happens only between questions, never in the middle of one.

object CapturePolicy {
    /** After Robot's voice stops, the call mode stays this long (her last words may still echo in the room). */
    const val TAIL_MS = 800L

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

    /** What the capture switches to now: never while a question is being captured (its audio would have a gap). */
    fun next(current: Capture, desired: Capture, questionOpen: Boolean): Capture = if (questionOpen) current else desired
}
