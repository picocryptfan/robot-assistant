// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

/**
 * fix-wiki-answers-forms: English word forms for [WikiAnswer.answers], so a lead that says "died" answers "When did Louis
 * Riel die?" (and sank, formed, founded, built ...). A question's word matches a sentence's when they're the same, or one
 * of the sentence word's bases is the question word or one of its bases. Regular endings (-s, -es, -ed, -d, -ing, -ies,
 * -ied) give a word's bases; an irregular past form has only its own verb's: "found" in a sentence is find's, so "the
 * body was found" never answers "When was Halifax founded?". Words folded as WebWords folds them. French isn't read here.
 */
internal object WordForms {
    /** Irregular past forms a "When did/was ..." question meets, to their verb. */
    private val IRREGULAR = mapOf( // l10n: not text (words matched)
        "died" to "die", "sank" to "sink", "sunk" to "sink", "found" to "find", "built" to "build", "wrote" to "write",
        "written" to "write", "began" to "begin", "begun" to "begin", "won" to "win", "fell" to "fall", "fallen" to "fall",
    )
    private val VOWEL = Regex("[aeiouy]")

    /**
     * [w] and the words its ending can come from ("formed" to form, "created" to create, "stopped" to stop). H1
     * (review-fix-wiki-answers-forms): with [past], only past endings count, as a lead gives a past event in the past tense
     * ("Cream forms on milk" states none). H2: an -ing ending counts only with [ing] ("the sinking of", not "a replica building").
     */
    fun bases(w: String, past: Boolean = false, ing: Boolean = !past): Set<String> {
        IRREGULAR[w]?.let { return setOf(it) }
        val out = mutableSetOf(w)
        if (w.length < 4 || w.any { it.isDigit() }) return out
        fun cut(n: Int, e: Boolean = false) {
            val r = w.dropLast(n)
            if (r.length < 2 || !VOWEL.containsMatchIn(r)) return
            out += r
            if (e) out += r + "e"
            if (r.length >= 3 && r.last() == r[r.length - 2] && r.last() !in "aeiouslz") out += r.dropLast(1)
        }
        when {
            w.endsWith("ied") -> out += (if (w.length > 4) w.dropLast(3) + "y" else w.dropLast(1)) // L1: "lied" to lie
            w.endsWith("ies") -> if (!past) out += (if (w.length > 4) w.dropLast(3) + "y" else w.dropLast(1))
            w.endsWith("ying") && w.length <= 5 -> if (ing) out += w.dropLast(4) + "ie"
            w.endsWith("ing") -> if (ing) cut(3, e = true)
            w.endsWith("eed") -> Unit
            w.endsWith("ed") -> cut(2, e = true)
            w.endsWith("es") -> if (!past) { cut(2); cut(1) }
            w.endsWith("ss") || w.endsWith("us") || w.endsWith("is") -> Unit
            w.endsWith("s") -> if (!past) cut(1)
        }
        return out
    }

    /**
     * The sentence's word [x] is a past form of the question's word [n], or with [ing] its -ing form ("the X-ing of"). L1: a
     * question's irregular "found" is only find's, so a sentence's "founded" never answers it.
     */
    fun same(x: String, n: String, ing: Boolean = false): Boolean {
        if (x == n) return true
        val q = bases(n)
        return (bases(x, past = true, ing = ing) - x).any { it in q }
    }
}
