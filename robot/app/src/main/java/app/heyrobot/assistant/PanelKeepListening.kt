// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.TtsStop
import app.heyrobot.core.headset.PanelFollowUp

/**
 * The panel keeps listening after an answer (idea 7, docs/plans/keep-listening-plan.md; core [PanelFollowUp]): once the
 * answer to a spoken question has been said (or shown, with replies not spoken), a beep and [PanelFollowUp.WINDOW_MS] of
 * listening for the next one. Silence or "that's all" ends it with no sound or notice; anything else is the next question,
 * whose answer opens the window again, at most [PanelFollowUp.MAX_REOPENS] times in a row. A stopped answer (any path through
 * VoiceHub.stopVoice) or a call opens nothing. PanelController holds one and calls it from its turn's end, its voice and its mic.
 */
internal class PanelKeepListening(private val host: Host) {
    interface Host {
        /** The panel shows and nothing else is under way (Phase.IDLE, or SPEAKING while Robot talks). */
        val idle: Boolean
        /** Robot's voice is talking. */
        val speaking: Boolean
        /** Someone is talking into the mic now (SpeechToText.hearing). */
        val hearing: Boolean
        /** The mic is open and nothing has been said yet (Phase.LISTENING, not TRANSCRIBING). */
        val listening: Boolean
        /** A call is on or ringing, or the phone can't say: no window (fail closed). */
        val inCall: Boolean
        fun now(): Long
        fun post(delayMs: Long, run: () -> Unit)
        fun beep()
        fun listen()
        fun stopQuietly()
    }

    /** The answer has ended; the window opens once Robot stops talking. */
    private var pending = false

    /** The mic is open for the window (its listen is this one's, not a card's). */
    var open = false
        private set

    private var gen = 0
    private var since = 0L

    /** When someone started talking in the open window (-1: not yet): cut [PanelFollowUp.SPEECH_MS] after. */
    private var speechAt = -1L

    /** Windows opened in a row since a question you started yourself; [fromWindow]: this answer's question came from one. */
    private var inRow = 0
    private var fromWindow = false

    /** The answer to a question ended: [speaking] the answer is still being said. */
    fun answered(after: PanelFollowUp.After, speaking: Boolean) {
        if (!fromWindow) inRow = 0
        fromWindow = false
        // Past the limit the answer ends with no sound: no beep, no mic.
        pending = PanelFollowUp.opens(after) && inRow <= PanelFollowUp.MAX_REOPENS
        if (pending && !speaking) tryOpen()
    }

    /** Robot stopped talking: a sentence may follow, so it looks again after a short pause (as a card's listen does). */
    fun speakingStopped() { if (pending) tryOpen() }

    private fun tryOpen() {
        val g = gen
        host.post(SETTLE_MS) {
            if (g != gen || !pending) return@post
            // Still talking: the next stop looks again. Something else under way (a card's listen, a new turn): none.
            if (host.speaking) return@post
            pending = false
            if (!host.idle || host.inCall) return@post
            open = true
            inRow++
            since = host.now()
            speechAt = -1L
            host.beep()
            host.listen()
            host.post(PanelFollowUp.RECHECK_MS) { check(g) }
        }
    }

    private fun check(g: Int) {
        if (g != gen || !open) return
        val t = host.now() - since
        if (!host.listening) {
            // Words being written down: what they say decides (heard). No listen at all by the window's end (the mic busy):
            // the window is over, so the next listen of your own isn't taken for it.
            if (host.idle) { if (t >= PanelFollowUp.WINDOW_MS) close() else host.post(PanelFollowUp.RECHECK_MS) { check(g) } }
            return
        }
        if (host.hearing && speechAt < 0) speechAt = host.now()
        if (PanelFollowUp.ends(t, host.hearing, if (speechAt < 0) 0L else host.now() - speechAt)) { close(); host.stopQuietly() }
        else host.post(PanelFollowUp.RECHECK_MS) { check(g) }
    }

    /** The window's listen came back with [text]: true when that ends it (nothing said, or "that's all"), nothing to send. */
    fun heard(text: String): Boolean {
        if (!open) return false
        close()
        val asks = PanelFollowUp.heard(text) == PanelFollowUp.Heard.QUESTION
        fromWindow = asks
        return !asks
    }

    /** The window's listen ended without words ([noSpeech]): true when it ends quietly, with no chime or notice. */
    fun missed(noSpeech: Boolean): Boolean {
        if (!open) return false
        close()
        return noSpeech
    }

    /**
     * Robot's voice was stopped (VoiceHub.stopVoice: a tap, "stop", the volume key, a button, the panel closed): the answer
     * opens no window. One already open ends too when [byYou] stopped it, mic and all.
     */
    fun voiceStopped(byYou: Boolean) {
        pending = false
        if (open && byYou) { close(); host.stopQuietly() }
    }

    /** A new turn, a typed question, the panel hidden: no window now. */
    fun cancel() { pending = false; close() }

    private fun close() { open = false; gen++ }

    companion object {
        /** As PanelController's other listens after Robot's voice: 350 ms, so a sentence between two doesn't open it. */
        const val SETTLE_MS = 350L

        /** Stops that are Robot's own (a new question, the wake word, an answer's end): every other one is yours. */
        private val NOT_YOURS = setOf(TtsStop.DONE, TtsStop.NEW_QUESTION, TtsStop.WAKE, TtsStop.TEST, TtsStop.OTHER, TtsStop.COPY)

        fun byYou(why: TtsStop): Boolean = why !in NOT_YOURS
    }
}
