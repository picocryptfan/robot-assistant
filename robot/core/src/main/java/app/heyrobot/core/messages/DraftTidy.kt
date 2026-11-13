// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.HelperRouter
import java.util.Locale

/**
 * A dictated text, tidied (idea 5, row 3; docs/plans/tidy-reword-plan.md): fillers dropped ("uh", "um", "euh") and,
 * where you corrected a number, a time, a day or a name ("ten, no, fifteen", « cinq, non, six »), only the last one
 * kept. [marks]: what a correction changed, for the card to show before anything goes.
 */
data class Tidied(val text: String, val marks: List<DraftMark>)

/** A number or name on the card to check: [now], and [was] what you said first (null: new, from a reword). */
data class DraftMark(val now: String, val was: String?)

/** Pure text work, English and French at once (each one's fillers and correction words differ). */
object DraftTidy {
    private val FILLER = Regex("^(?:u+h+|u+m+|uhm+|e+r+m*|hm+|h+m+|e+u+h+|h+e+u+)$")
    /** Correction words, in plain words (no case or accents): one, two or three words. */
    private val MARKERS = listOf("i mean", "i meant", "je veux dire", "no", "sorry", "actually", "or rather", "non", "pardon", "plutot", "ou plutot")
        .map { it.split(' ') }.sortedByDescending { it.size }
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve", "thirteen",
        "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty", "thirty", "forty", "fifty", "sixty",
        "seventy", "eighty", "ninety", "hundred", "noon", "midnight", "half", "quarter",
        "un", "une", "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix", "onze", "douze", "treize", "quatorze",
        "quinze", "seize", "vingt", "trente", "quarante", "cinquante", "soixante", "cent", "midi", "minuit", "demie", "quart",
    )
    /** What may follow a number in the same fact: "5 pm", "ten minutes", « six heures ». */
    private val UNITS = setOf(
        "am", "pm", "a.m", "p.m", "oclock", "h", "minute", "minutes", "min", "hour", "hours", "heure", "heures",
        "dollars", "bucks", "km", "kilometres", "kilometers",
    )
    private val DAYS = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "today", "tomorrow", "tonight",
        "lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche", "aujourdhui", "demain",
        "january", "february", "march", "april", "june", "july", "august", "september", "october", "november", "december",
        "janvier", "fevrier", "mars", "avril", "mai", "juin", "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
    )
    /** Capitalized but not a name. */
    private val NOT_NAMES = setOf("i", "im", "ill", "ive", "id", "ok", "okay", "hi", "hey", "hello", "thanks", "sorry", "je", "jai", "salut", "bonjour", "merci")
    private val DIGITS = Regex("^\\$?\\d+(?:[:h.,]\\d+)*(?:am|pm|h)?\\$?$")

    enum class Kind { NUMBER, NAME }

    private class Tok(var w: String, var soft: Boolean = false)

    /** [names]: people's names Robot knows (the contacts), so a lowercase "sam, no, sarah" counts too. */
    fun tidy(text: String, names: Collection<String> = emptyList()): Tidied {
        val known = names.flatMap { it.split(' ') }.map(::key).filter { it.isNotEmpty() }.toSet()
        val toks = dropFillers(tokens(text))
        val marks = ArrayList<DraftMark>()
        var i = 1
        while (i < toks.size) {
            val m = marker(toks, i)
            if (m == 0) { i++; continue }
            val x = span(toks, i - 1, -1, known)
            val y = span(toks, i + m, 1, known)
            if (x == null || y == null || x.second != y.second) { i++; continue }
            val start = i - x.first
            val was = words(toks, start, i)
            val now = words(toks, i + m, i + m + y.first)
            if (start > 0 && toks[start - 1].soft) toks[start - 1].w = toks[start - 1].w.trimEnd(',')
            repeat(i + m - start) { toks.removeAt(start) }
            val prior = marks.indexOfFirst { key(it.now) == key(was) }
            if (prior >= 0) marks[prior] = DraftMark(now, marks[prior].was) else marks += DraftMark(now, was)
            i = start + 1
        }
        return Tidied(join(text, toks), marks)
    }

    /**
     * Fillers out and nothing else (dictation into another app): a correction word stays where you said it, since
     * "Friday, actually Saturday works" may mean both, and the app may act on the words before you see them.
     */
    fun fillersOnly(text: String): String = join(text, dropFillers(tokens(text)))

    private fun tokens(text: String): List<Tok> = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { Tok(it) }

    private fun join(text: String, toks: List<Tok>): String {
        val out = toks.joinToString(" ") { it.w }
        val upper = text.trimStart().firstOrNull()?.isUpperCase() == true
        return if (upper && out.isNotEmpty()) out.replaceFirstChar { it.titlecase(Locale.ROOT) } else out
    }

    /** The facts in [text] a rewrite must keep: numbers (as their values) and days and names, in plain words. */
    fun facts(text: String, names: Collection<String> = emptyList()): Map<Kind, Set<String>> {
        val known = names.flatMap { it.split(' ') }.map(::key).toSet()
        val nums = HashSet<String>()
        val others = HashSet<String>()
        var sentenceStart = true
        for (raw in text.split(Regex("\\s+")).filter { it.isNotEmpty() }) {
            val k = key(raw)
            when {
                k.any(Char::isDigit) -> Regex("\\d+").findAll(k).map { it.value.trimStart('0') }.filter { it.isNotEmpty() }.forEach { nums += it }
                k in NUMBER_WORDS -> nums += VALUES[k] ?: k
                k in DAYS -> others += k
                k in known && k !in NOT_NAMES -> others += k
                !sentenceStart && raw.trimStart('"', '“', '«', '(').firstOrNull()?.isUpperCase() == true && k !in NOT_NAMES && k.isNotEmpty() -> others += k
            }
            sentenceStart = raw.trimEnd('"', '”', '»', ')').let { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') } || (sentenceStart && k.isEmpty())
        }
        return mapOf(Kind.NUMBER to nums, Kind.NAME to others)
    }

    private val VALUES = mapOf(
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5", "six" to "6", "seven" to "7", "eight" to "8",
        "nine" to "9", "ten" to "10", "eleven" to "11", "twelve" to "12", "thirteen" to "13", "fourteen" to "14", "fifteen" to "15",
        "sixteen" to "16", "seventeen" to "17", "eighteen" to "18", "nineteen" to "19", "twenty" to "20", "thirty" to "30",
        "forty" to "40", "fifty" to "50", "sixty" to "60", "seventy" to "70", "eighty" to "80", "ninety" to "90",
        "un" to "1", "une" to "1", "deux" to "2", "trois" to "3", "quatre" to "4", "cinq" to "5", "sept" to "7", "huit" to "8",
        "neuf" to "9", "dix" to "10", "onze" to "11", "douze" to "12", "treize" to "13", "quatorze" to "14", "quinze" to "15",
        "seize" to "16", "vingt" to "20", "trente" to "30", "quarante" to "40", "cinquante" to "50", "soixante" to "60",
        "noon" to "12", "midi" to "12", "zero" to "0",
    )

    /** A word in plain form: no case, accents, apostrophes or punctuation around it. */
    fun key(w: String): String = HelperRouter.plain(w).lowercase(Locale.ROOT).replace("'", "").replace("’", "")
        .trim('"', '“', '”', '«', '»', '(', ')', ',', '.', ';', ':', '!', '?', ' ', ' ', ' ')

    private fun dropFillers(toks: List<Tok>): MutableList<Tok> {
        val out = ArrayList<Tok>()
        for (t in toks) {
            val start = out.isEmpty() || out.last().w.trimEnd('"', '”', '»', ')').let { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') }
            if (!filler(t.w, start)) { out += t; continue }
            val end = t.w.trimEnd().lastOrNull()?.takeIf { it == '.' || it == '!' || it == '?' }
            val prev = out.lastOrNull() ?: continue
            if (end != null) prev.w = prev.w.trimEnd(',') + end
            else if (prev.w.endsWith(',')) prev.soft = true
        }
        return out
    }

    /**
     * A filler standing alone: in lower case, or with only its first letter capitalised at the start of a sentence
     * ("Um, I'm late"). Never all capitals ("the ER", "UM" as initials) nor capitalised inside a sentence.
     */
    private fun filler(raw: String, start: Boolean): Boolean {
        if (!FILLER.matches(key(raw))) return false
        val letters = raw.filter(Char::isLetter)
        val rest = letters.drop(1)
        return letters == letters.lowercase(Locale.ROOT) || (start && letters.first().isUpperCase() && rest == rest.lowercase(Locale.ROOT))
    }

    /** How many tokens the correction word at [i] takes (0: none there). */
    private fun marker(toks: List<Tok>, i: Int): Int {
        for (m in MARKERS) {
            if (i + m.size >= toks.size) continue
            if (m.indices.all { key(toks[i + it].w) == m[it] }) return m.size
        }
        return 0
    }

    /** The fact that ends (dir -1) or starts (dir 1) at [at]: its length and kind, or null. */
    private fun span(toks: List<Tok>, at: Int, dir: Int, known: Set<String>): Pair<Int, Kind>? {
        if (at !in toks.indices) return null
        val k0 = key(toks[at].w)
        // A capital on the message's first word says nothing ("Sorry, no, I can't").
        if (k0 in DAYS || (k0 in known && k0 !in NOT_NAMES) || (at > 0 && name(toks[at].w, k0))) return 1 to Kind.NAME
        var n = 0
        var number = false
        var j = at
        while (j in toks.indices && n < 4) {
            val k = key(toks[j].w)
            val num = k in NUMBER_WORDS || DIGITS.matches(k) || k.split('-').all { it in NUMBER_WORDS && it.isNotEmpty() }
            if (!num && k !in UNITS) break
            // A fact ends at its own comma: "ten, no" is "ten"; reading back, a comma before ends it too.
            if (dir > 0 && n > 0 && toks[j - 1].w.endsWith(',')) break
            if (dir < 0 && n > 0 && toks[j].w.endsWith(',')) break
            number = number || num
            n++
            j += dir
        }
        // A number reads from its value: going back, the span must start on one (not "minutes" alone).
        return if (number && n > 0) n to Kind.NUMBER else null
    }

    private fun name(raw: String, k: String): Boolean =
        k.isNotEmpty() && k !in NOT_NAMES && k.all { it.isLetter() || it == '-' } && raw.trimStart('"', '“', '«', '(').first().isUpperCase()

    private fun words(toks: List<Tok>, from: Int, to: Int): String =
        toks.subList(from, to).joinToString(" ") { it.w }.trimEnd(',', '.', '!', '?', ';', ':')
}
