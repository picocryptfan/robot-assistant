// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.util.Locale

/**
 * A sentence left open: the latest live words end on a word that needs more after it ("and", « parce que »), so the pause
 * after it counts as Very long (Settings › Voice › "Wait before answering", car mode or not). It only ever waits longer.
 * Both languages at once: Robot doesn't know a question's language before it is written down, and the words of one
 * that happen to end a sentence in the other only cost a longer wait.
 */
object OpenWords {
    // l10n: not text (matched against what was heard, never shown)
    private val WORDS = setOf(
        "and", "but", "or", "so", "because", "the", "a", "to", "um", "uh",
        "et", "mais", "ou", "donc", "le", "la", "de", "euh",
    )
    private val EDGES = Regex("^[^\\p{L}]+|[^\\p{L}]+$")

    /** The pause an open sentence gets (ListenPause.VERY_LONG). */
    val PAUSE_MS: Int get() = ListenPause.VERY_LONG.ms

    /** Whether [text] (live words, with the recognizer's punctuation and capitals) stops on an open word. */
    fun endsOpen(text: String): Boolean {
        val words = text.lowercase(Locale.ROOT).trim().split(Regex("\\s+")).map { it.replace(EDGES, "") }.filter { it.isNotEmpty() }
        val last = words.lastOrNull() ?: return false
        return last in WORDS || (last == "que" && words.getOrNull(words.size - 2) == "parce")
    }
}
