// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

import java.text.Normalizer
import java.util.Locale

/**
 * The panel's own follow-up window (idea 7, docs/plans/keep-listening-plan.md): a headset session already listens
 * [HeadsetSettings.followUpSeconds] after an answer (HeadsetSession); with [HeadsetSettings.panelFollowUp] on, the panel on
 * the phone does the same for [WINDOW_MS], with a beep. "That's all" or silence ends it, quietly. Off by default: with no
 * voice matching it could answer the TV.
 */
object PanelFollowUp {
    const val WINDOW_MS = 5_000L

    /** How often the window's end looks again while someone is still talking (as HeadsetSession's FOLLOW_UP timer does). */
    const val RECHECK_MS = 250L

    /** Speech that starts in the window is cut this long after it starts (stopped quietly, nothing sent): a TV doesn't run on. */
    const val SPEECH_MS = 8_000L

    /** After the first window it opens again at most this many times in a row without a touch; then the answer ends with no sound. */
    const val MAX_REOPENS = 3

    /** What is true when the answer to a question ends. */
    data class After(
        /** [HeadsetSettings.panelFollowUp]. */
        val on: Boolean,
        /** The question was spoken (a typed one gets no window). */
        val spoken: Boolean,
        /** The answer was stopped, cut at its length or went wrong: you're not waiting for more. */
        val stopped: Boolean,
        val shown: Boolean,
        /** A headset or car session is on: it has its own window. */
        val handsFree: Boolean,
        /** A card or a question already listens for your words ("What should the text say?"): it does, once, as before. */
        val waitsForWords: Boolean,
    )

    fun opens(a: After): Boolean = a.on && a.spoken && !a.stopped && a.shown && !a.handsFree && !a.waitsForWords

    /** At [elapsedMs] into the window: it ends once it has run its time and nobody is talking, or [speechMs] after speech began. */
    fun ends(elapsedMs: Long, hearing: Boolean, speechMs: Long = 0): Boolean = (elapsedMs >= WINDOW_MS && !hearing) || speechMs >= SPEECH_MS

    enum class Heard { SILENCE, DONE, QUESTION }

    /** What was said in the window: nothing, "that's all" (it ends, nothing is asked), or the next question. */
    fun heard(text: String): Heard {
        val words = norm(text)
        if (words.isEmpty()) return Heard.SILENCE
        var rest = words
        while (true) {
            val lead = LEADS.firstOrNull { rest == it || rest.startsWith("$it ") } ?: break
            rest = rest.removePrefix(lead).trim()
        }
        // "Thanks." or "OK, great." alone is done too.
        return if (rest.isEmpty() || rest in DONE) Heard.DONE else Heard.QUESTION
    }

    // l10n: not text (words matched in what was heard, not shown)
    private val THANKS = listOf("thanks", "thank you", "thank you very much", "thanks a lot", "merci", "merci beaucoup", "merci bien")

    // Words that only lead in: "OK, thanks, that's all" is "that's all". Longest first, so "thank you very much" wins.
    // l10n: not text (words matched in what was heard, not shown)
    private val LEADS = (THANKS + listOf("ok", "okay", "great", "perfect", "good", "cool", "alright", "all right", "no", "non", "parfait",
        "super", "d accord", "bon")).sortedByDescending { it.length } // l10n: not text (words matched in what was heard)

    // l10n: not text (words matched in what was heard, not shown)
    private val DONE = setOf(
        "that s all", "that is all", "that s it", "that s all for now", "that s all thanks", "nothing", "nothing else", "that ll be all", // l10n: not text (words matched in what was heard)
        "i m done", "i m good", "all done", "we re done", "never mind", "no thanks", "no thank you", "stop listening", "goodbye", "bye", // l10n: not text (words matched in what was heard)
        "c est tout", "c est tout pour l instant", "c est beau", "c est correct", "rien", "rien d autre", "ce sera tout", "non merci", // l10n: not text (words matched in what was heard)
        "j ai fini", "ça va", "ca va", "laisse faire", "arrête d écouter", "arrete d ecouter", "bye bye", "salut", // l10n: not text (words matched in what was heard)
    )

    private fun norm(s: String): String {
        val t = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFC)
            .replace('’', '\'').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return t.replace(Regex("\\s+"), " ")
    }
}
