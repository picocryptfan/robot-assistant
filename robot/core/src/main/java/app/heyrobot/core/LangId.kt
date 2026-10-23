// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.ReplyLang
import app.heyrobot.core.i18n.ReplyLangChoice
import java.io.InputStream
import java.text.Normalizer
import java.util.Locale

// Pure. Which language a transcript line is in (docs/conversation-mode.md 2.3). Parakeet v3 doesn't say (its result's
// `lang` is empty), so Robot reads it from the words: a naive Bayes over words and character 1-4-grams, its table
// trained by tools/make_langid.py on Tatoeba's CC0 sentences only (assets langid/fr-en.tsv, about 170 kB), plus a few
// cue rules (French accents and elisions, English contractions). French and English today; another language is one
// more column in the table, trained the same way (docs/languages.md, the playbook for adding one).
//
// Short lines are the hard part. A line with no word of two letters or more ("5 h 30", "OK!" spelled "K"), or a lone
// name ("Montréal", "Marie-Ève Gagnon": every word capitalised and none the table knows) is not sure, and so is a line
// whose best language leads by less than the table's margin (stricter for one word). Not sure is an answer: the line
// then takes its language from what is known around it ([LangLabel]) and shows "FR?" until you tap it.

/**
 * What [LangId.detect] found. [lang]: the language (an ISO 639-1 code from the table), or null when not sure.
 * [margin]: how far the best language is ahead, in the table's units (natural log, words counted twice); 0 when
 * nothing matched. [words]: words of two letters or more. [best]: the best-scoring language even when not sure (null
 * when no feature matched), for the last step of [LangLabel]. [why]: why not sure.
 */
data class LangGuess(val lang: String?, val margin: Double, val words: Int, val best: String?, val why: LangUnsure? = null) {
    val sure: Boolean get() = lang != null
    /** One or two words: [LangLabel] weighs what is known around the line against it. */
    val short: Boolean get() = words <= 2
}

enum class LangUnsure {
    /** No word of two letters or more: digits, "5 h 30", punctuation. */
    NO_WORDS,
    /** Every word capitalised and none the table knows: most likely a name. */
    NAME,
    /** The best language doesn't lead by the margin. */
    CLOSE,
}

/**
 * A language table and the rules around it. [provisional]: the built-in stand-in ([FALLBACK]), used only when the
 * asset can't be read: function words and the cue rules, much weaker on short lines.
 */
class LangId private constructor(
    val langs: List<String>,
    private val table: Map<String, IntArray>,
    private val scale: Int,
    private val wordWeight: Int,
    private val gramWeight: Int,
    /** The margin under which a line is not sure, and the one for a line of one word, in the table's units. */
    val margin: Double,
    val marginOneWord: Double,
    val provisional: Boolean = false,
) {
    /** The table's size (features), for the log and the tests. */
    val size: Int get() = table.size

    /** A word the table knows (lower case). */
    fun knows(word: String): Boolean = table.containsKey("w:$word")

    /** Each language's score times the table's scale, in [langs] order, and how many features matched. */
    fun scores(text: String): Pair<IntArray, Int> {
        val sc = IntArray(langs.size)
        var n = 0
        for (f in features(text)) {
            val row = table[f] ?: continue
            n++
            val w = if (f.startsWith("w:")) wordWeight else gramWeight
            for (i in sc.indices) sc[i] += w * row[i]
        }
        val t = norm(text)
        for (c in CUES) {
            val i = langs.indexOf(c.lang)
            if (i >= 0 && c.rx.containsMatchIn(t)) sc[i] += Math.round(c.bonus * scale).toInt()
        }
        return sc to n
    }

    fun detect(text: String): LangGuess {
        val (sc, n) = scores(text)
        val order = langs.indices.sortedWith(compareByDescending<Int> { sc[it] }.thenBy { it })
        val m = if (n > 0 && order.size > 1) sc[order[0]] - sc[order[1]] else 0
        val margin = m.toDouble() / scale
        val best = if (n > 0) langs[order[0]] else null
        val words = words(text).count { it.length >= 2 }
        return when {
            words == 0 || n == 0 -> LangGuess(null, margin, words, best, LangUnsure.NO_WORDS)
            nameLike(text) -> LangGuess(null, margin, words, best, LangUnsure.NAME)
            m < Math.round((if (words == 1) marginOneWord else this.margin) * scale) -> LangGuess(null, margin, words, best, LangUnsure.CLOSE)
            else -> LangGuess(best, margin, words, best)
        }
    }

    /**
     * At most three words, every one capitalised and none of them a word the table knows: "Montréal", "Gatineau",
     * "Marie-Ève Gagnon". One known word ("Merci", "Bonjour Marie", "That's Vega") leaves the line to the scores.
     */
    fun nameLike(text: String): Boolean {
        val raw = WORD.findAll(Normalizer.normalize(text, Normalizer.Form.NFC).replace('’', '\'').replace('ʼ', '\'')).map { it.value }.toList()
        if (raw.isEmpty() || raw.size > 3 || !raw.all { it[0].isUpperCase() }) return false
        return raw.none { knows(it.lowercase(Locale.ROOT)) }
    }

    /** A cue rule: [lang] gets [bonus] (natural log units) once when [rx] matches the lower-cased line. */
    class Cue(val lang: String, val rx: Regex, val bonus: Double)

    companion object {
        /** The asset the :voice process loads (core's assets/langid/fr-en.tsv). */
        const val ASSET = "langid/fr-en.tsv"
        const val FORMAT = "robot-langid 1"

        private val WORD = Regex("""\p{L}+(?:'\p{L}+)*""")

        /** As tools/make_langid.py's CUES: keep the two the same. */
        val CUES: List<Cue> = listOf(
            Cue("fr", Regex("[éèêëàâçùûüôœî]"), 2.0),
            Cue("fr", Regex("""(?<![\p{L}\p{N}])(?:[ldjmnstc]|qu)'[a-zéèêàâîôûh]"""), 3.0),
            Cue("en", Regex("""[a-z]'(?:s|t|m|re|ll|ve|d)(?![\p{L}\p{N}])"""), 3.0),
        )

        /** NFC, lower case, typographic apostrophes as "'". */
        fun norm(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT).replace('’', '\'').replace('ʼ', '\'')

        /** The words of a line, normalised ([norm]): letters with inner apostrophes ("aujourd'hui", "that's"). */
        fun words(s: String): List<String> = WORD.findAll(norm(s)).map { it.value }.toList()

        /** "w:word" for each word, then its character 1-4-grams with one space of padding on each side. */
        fun features(s: String): List<String> {
            val out = ArrayList<String>()
            for (w in words(s)) {
                out += "w:$w"
                val p = " $w "
                for (n in 1..4) for (i in 0..p.length - n) {
                    val g = p.substring(i, i + n)
                    if (g != " ") out += g
                }
            }
            return out
        }

        /**
         * Reads a table written by tools/make_langid.py. Throws IllegalArgumentException when it isn't one (no
         * languages, a row with the wrong number of values): the caller falls back to [FALLBACK].
         */
        fun parse(text: String): LangId {
            var langs: List<String> = emptyList()
            var scale = 10
            var weights = 2 to 1
            var margins = 1.0 to 2.0
            var format = false
            val table = HashMap<String, IntArray>(16_384)
            for (line in text.lineSequence()) {
                if (line.isEmpty()) continue
                if (line.startsWith("#")) {
                    if (line.removePrefix("#").trim().startsWith(FORMAT)) format = true
                    continue
                }
                val p = line.split('\t')
                if (line.startsWith("@")) {
                    when (p[0]) {
                        "@langs" -> langs = p.drop(1)
                        "@scale" -> scale = p.getOrNull(1)?.toIntOrNull() ?: throw IllegalArgumentException("scale")
                        "@weights" -> weights = (p.getOrNull(1)?.toIntOrNull() ?: 2) to (p.getOrNull(2)?.toIntOrNull() ?: 1)
                        "@margin" -> margins = (p.getOrNull(1)?.toDoubleOrNull() ?: 1.0) to (p.getOrNull(2)?.toDoubleOrNull() ?: 2.0)
                    }
                    continue
                }
                require(langs.size >= 2) { "languages first" }
                require(p.size == langs.size + 1) { "row" }
                table[p[0]] = IntArray(langs.size) { p[it + 1].toIntOrNull() ?: throw IllegalArgumentException("value") }
            }
            require(format && langs.size >= 2 && table.isNotEmpty() && scale > 0) { "not a language table" }
            return LangId(langs, table, scale, weights.first, weights.second, margins.first, margins.second)
        }

        fun load(input: InputStream): LangId = input.use { parse(it.readBytes().toString(Charsets.UTF_8)) }

        /** The table from [input], or [FALLBACK] when it can't be read (logged as a code only). */
        fun loadOrFallback(input: () -> InputStream): LangId = try {
            load(input())
        } catch (t: Throwable) {
            RLog.w("langid_fallback", t)
            FALLBACK
        }

        /**
         * The stand-in when the asset can't be read: common French and English words, the cue rules, nothing else.
         * Sure only when a line has a few of them; most short lines come out not sure and take the prior ([LangLabel]).
         */
        val FALLBACK: LangId by lazy {
            val fr = setOf("le", "la", "les", "des", "du", "de", "un", "une", "est", "et", "que", "qui", "pas", "pour", "avec", "dans", "sur",
                "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "ce", "cette", "ces", "mais", "ou", "très", "aussi", "sont", "être", // l10n: not text (words to recognise French by)
                "plus", "au", "aux", "en", "mon", "ma", "mes", "ton", "ta", "tes", "votre", "notre", "peut", "fait", "comme", "bien", "oui",
                "non", "merci", "bonjour", "salut", "ça", "c'est", "j'ai", "quoi", "pourquoi", "comment", "où", "quand", "rien", "tout")
            val en = setOf("the", "is", "and", "of", "to", "in", "that", "it", "you", "for", "with", "are", "this", "be", "was", "at", "by", "or",
                "not", "but", "from", "they", "we", "have", "has", "your", "my", "what", "which", "can", "will", "would", "about", "there",
                "yes", "no", "thanks", "hello", "hi", "it's", "i'm", "don't", "why", "how", "where", "when", "nothing", "everything", "i")
            val table = HashMap<String, IntArray>()
            // One word of either list counts about as much as a word of the trained table: log(1/20) against log(1/400).
            for (w in fr) table["w:$w"] = intArrayOf(-30, -60)
            for (w in en) table["w:$w"] = table["w:$w"]?.let { intArrayOf(-30, -30) } ?: intArrayOf(-60, -30)
            LangId(listOf("fr", "en"), table, 10, 2, 1, 1.0, 2.0, provisional = true)
        }
    }
}

/**
 * The language a line is labelled with (docs/conversation-mode.md 2.3). [fromPrior]: it came from what is known around
 * the line, not from its own words, so it shows "FR?" until you tap it.
 */
data class LineLang(val lang: String, val fromPrior: Boolean)

/** Pure. A line's language from its [LangGuess] and what is known around it. */
object LangLabel {
    /** A short line keeps its own language against the prior only with at least this margin. */
    const val STRONG_MARGIN = 8.0

    /**
     * In order: [forced] (the push-to-talk side: always wins, never "?"); a sure line of three words or more; then for a
     * short or not-sure line the prior: this [speaker]'s running majority language, else the [previous] line's. A short
     * sure line keeps its own language when the prior agrees, when there is no prior, or when it leads by
     * [STRONG_MARGIN]; otherwise the prior wins, with "?". A line that is not sure takes the prior, else the table's
     * best guess, else your language ([yours]), always with "?".
     */
    fun choose(guess: LangGuess, forced: String? = null, speaker: String? = null, previous: String? = null, yours: String): LineLang {
        if (forced != null) return LineLang(forced, false)
        val prior = speaker ?: previous
        val lang = guess.lang
        if (lang != null) {
            if (!guess.short || prior == null || prior == lang || guess.margin >= STRONG_MARGIN) return LineLang(lang, false)
            return LineLang(prior, true)
        }
        return LineLang(prior ?: guess.best ?: yours, true)
    }

    /** The language most of a speaker's lines are in (labels from a prior don't count); null without a clear majority. */
    fun majority(langs: List<LineLang>): String? {
        val counts = langs.filter { !it.fromPrior }.groupingBy { it.lang }.eachCount()
        val top = counts.maxByOrNull { it.value } ?: return null
        return if (counts.count { it.value == top.value } == 1) top.key else null
    }

    /** "fr" for a French phone, else "en": your language when nothing else is known (0.5.0 labels fr and en only). */
    fun yours(locale: Locale = Locale.getDefault()): String = if (locale.language == "fr") "fr" else "en"

    /**
     * 1.1: your language as Robot's reply language decides it with nothing heard (core ReplyLang.unheard): Settings ›
     * Language › "Robot answers in" when it is fixed, else the menus' language [ui]. Conversation mode's prior for a short
     * line, the interpreter's bottom half, your voiceprint's sentences and the summaries' "Your language" follow it.
     */
    fun yours(ui: Lang, setting: ReplyLangChoice): String = ReplyLang.unheard(ui, setting).id

    /** "FR", "EN", "FR?": the tag on a line. */
    fun tag(l: LineLang): String = l.lang.uppercase(Locale.ROOT) + if (l.fromPrior) "?" else ""
}
