// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToInt

// Pure. Word error rate for the Transcription check (docs/conversation-mode.md 2.5), the gate for Québec French: read
// sentences (or say anything and correct what Robot wrote), and Robot counts the words it got wrong. Both texts are
// normalised first: lower case, apostrophes as ', "œ" as "oe", hyphens and punctuation as spaces. Accents count ("été"
// for "ete" is an error), but those errors are also counted apart, as the accent sub-score: a wrong accent is a much
// smaller problem than a wrong word.

/**
 * One comparison, or a sum of them. [words] in the reference; [substitutions], [deletions] (words missed) and
 * [insertions] (words added); [accents]: substitutions that differ only by accents.
 */
data class WerResult(val words: Int, val substitutions: Int = 0, val deletions: Int = 0, val insertions: Int = 0, val accents: Int = 0) {
    val errors: Int get() = substitutions + deletions + insertions

    /** Errors per reference word (can pass 1.0 when much was added); an empty reference is 0 with no words heard, else 1. */
    val rate: Double get() = if (words == 0) (if (errors == 0) 0.0 else 1.0) else errors.toDouble() / words

    val percent: Int get() = (rate * 100).roundToInt()

    /** The rate if wrong accents didn't count. */
    val rateWithoutAccents: Double get() = if (words == 0) rate else (errors - accents).toDouble() / words

    operator fun plus(o: WerResult): WerResult =
        WerResult(words + o.words, substitutions + o.substitutions, deletions + o.deletions, insertions + o.insertions, accents + o.accents)
}

object Wer {
    /** 5.0(a): Québec French passes at 15 % or less, English at 8 % or less. */
    const val PASS_FR = 0.15
    const val PASS_EN = 0.08

    private val APOSTROPHES = Regex("[’‘ʼ`´]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}']+")

    /** The words compared: see the file comment. */
    fun words(s: String): List<String> {
        val t = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
            .replace(APOSTROPHES, "'").replace("œ", "oe").replace("æ", "ae")
        return t.split(NOT_WORD).map { it.trim('\'') }.filter { it.isNotEmpty() }
    }

    fun stripAccents(w: String): String = Normalizer.normalize(w, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    fun score(reference: String, hypothesis: String): WerResult {
        val r = words(reference)
        val h = words(hypothesis)
        // Edit distance with a backtrace, to tell substitutions, deletions and insertions apart.
        val d = Array(r.size + 1) { IntArray(h.size + 1) }
        for (i in 0..r.size) d[i][0] = i
        for (j in 0..h.size) d[0][j] = j
        for (i in 1..r.size) for (j in 1..h.size) {
            d[i][j] = minOf(d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        var i = r.size
        var j = h.size
        var s = 0
        var del = 0
        var ins = 0
        var acc = 0
        while (i > 0 || j > 0) {
            if (i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1) {
                if (r[i - 1] != h[j - 1]) {
                    s++
                    if (stripAccents(r[i - 1]) == stripAccents(h[j - 1])) acc++
                }
                i--; j--
            } else if (i > 0 && d[i][j] == d[i - 1][j] + 1) {
                del++; i--
            } else {
                ins++; j--
            }
        }
        return WerResult(r.size, s, del, ins, acc)
    }

    /** Several lines together: their errors over their words (not the mean of their rates). */
    fun total(results: List<WerResult>): WerResult = results.fold(WerResult(0)) { a, b -> a + b }

    fun passes(r: WerResult, lang: String): Boolean = r.rate <= if (lang == "fr") PASS_FR else PASS_EN
}
