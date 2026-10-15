// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln

// Pure. Word search over what Robot remembers (design §A6): BM25 with accents and case folded, French elisions and
// English possessives cut, English and French stop words dropped, and plurals reduced ("birthdays" = "birthday",
// "journaux" = "journal"). It finds questions that share a word with an item, names and numbers best; it misses
// paraphrase and the other language ("la fête de ma sœur" against "sister's birthday"), which the embedding model
// covers when you download it. Microseconds for 2,000 items, no download, the fallback whenever the model is missing.

object MemoryText {
    /** Lower case, no accents, ligatures spelled out, typographic apostrophes made plain: "Hélène’s Œuvre" → "helene's oeuvre". */
    fun fold(s: String): String {
        val t = s.replace('’', '\'').replace('ʼ', '\'').replace('‘', '\'').replace("œ", "oe").replace("Œ", "OE")
            .replace("æ", "ae").replace("Æ", "AE").replace("ß", "ss")
        return Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
    }

    /** French elided words before an apostrophe ("l'anniversaire", "qu'il", "jusqu'à"). */
    private val ELISIONS = setOf("l", "d", "j", "m", "n", "s", "t", "c", "qu", "jusqu", "lorsqu", "puisqu", "quoiqu", "presqu")

    /** Words that say nothing about what an item is about, in English and French (folded). */
    val STOP: Set<String> = setOf(
        // English
        "a", "an", "the", "and", "or", "but", "of", "to", "in", "on", "at", "for", "from", "by", "with", "about", "as", "into",
        "is", "are", "was", "were", "be", "been", "being", "am", "do", "does", "did", "have", "has", "had", "having",
        "i", "me", "my", "mine", "myself", "you", "your", "yours", "he", "him", "his", "she", "her", "hers", "it", "its",
        "we", "us", "our", "they", "them", "their", "this", "that", "these", "those", "what", "which", "who", "whom",
        "whose", "when", "where", "why", "how", "will", "would", "can", "could", "should", "shall", "might", "must",
        "not", "no", "yes", "so", "if", "then", "than", "there", "here", "just", "also", "too", "very", "really", "please",
        "s", "t", "ll", "re", "ve", "d", "m", "o", "ok", "okay", "hey",
        "robot", "remember", "remembered", "forget", "know", "tell", "told", "said", "say", "any", "anything", "something",
        // French
        // Not "car" or "son": English nouns first ("my son's car").
        "le", "la", "les", "un", "une", "des", "de", "du", "au", "aux", "et", "ou", "mais", "donc", "ni", "que",
        "qui", "quoi", "dont", "est", "sont", "etait", "etre", "ai", "as", "avons", "avez", "ont", "suis", "es", "sommes",
        "etes", "je", "tu", "te", "il", "elle", "on", "nous", "vous", "ils", "elles", "se", "mon", "ma", "mes", "ton",
        "ta", "tes", "sa", "ses", "notre", "nos", "votre", "vos", "leur", "leurs", "ce", "cet", "cette", "ces",
        "ca", "cela", "ceci", "y", "en", "ne", "pas", "plus", "tres", "pour", "par", "sur", "dans", "avec", "sans",
        "sous", "chez", "quand", "comment", "pourquoi", "quel", "quelle", "quels", "quelles", "oui", "non", "si",
        "souviens", "rappelle", "rappelles", "oublie", "sais", "dit", "dis", "moi", "toi", "lui", "eux",
    )

    /**
     * Light stemming for English and French, the same on both sides of a search: plurals first ("eaux" → "eau", "aux" →
     * "al", "eux"/"oux" lose the "x", a final "s" goes except after "s" or "u", and after "i" only in a longer word:
     * "boss", "bus", "amis"), then English "-ing" and "-ed" in longer words ("parked", "parking" → "park"; not "shed"
     * or "need"), then a final "e" in a word of four letters or more ("dimanche" and "dimanches" → "dimanch", "watches"
     * → "watch", "amie" → "ami", "liked" and "like" → "lik"), and a final "y" after a consonant becomes "i" ("allergy",
     * "allergies" and "allergie" → "allergi"). Words of three letters or fewer, and anything with a digit, stay.
     */
    fun stem(word: String): String {
        if (word.length <= 3 || word.any { it.isDigit() }) return word
        var w = when {
            word.endsWith("eaux") -> word.dropLast(1)
            word.endsWith("aux") && word.length > 4 -> word.dropLast(3) + "al"
            word.endsWith("eux") || word.endsWith("oux") -> word.dropLast(1)
            word.endsWith("s") && !word.endsWith("ss") && !word.endsWith("us") && (!word.endsWith("is") || word.length > 4) -> word.dropLast(1)
            else -> word
        }
        if (w.length > 5 && w.endsWith("ing")) w = w.dropLast(3)
        else if (w.length > 4 && w.endsWith("ed") && !w.endsWith("eed")) w = w.dropLast(2)
        if (w.length > 3 && w.endsWith("e")) w = w.dropLast(1)
        if (w.length > 3 && w.endsWith("y") && w[w.length - 2] !in "aeiouy") w = w.dropLast(1) + "i"
        return w
    }

    /** The folded words of [s], apostrophes kept inside a word for [tokens] to cut. */
    fun words(s: String): List<String> = Regex("[\\p{L}\\p{N}]+(?:'[\\p{L}\\p{N}]+)*").findAll(fold(s)).map { it.value }.toList()

    /** Search terms: folded, elisions and possessives cut, stop words dropped, stemmed. Numbers stay as written. */
    fun tokens(s: String): List<String> {
        val out = ArrayList<String>()
        for (w in words(s)) {
            // "don't", "can't", "isn't": a negation, never what a note is about (and "Don" stays a name).
            if (w.endsWith("n't")) continue
            for (part in pieces(w)) {
                if (part.isEmpty() || part in STOP) continue
                out += stem(part)
            }
        }
        return out
    }

    /** "l'anniversaire" → [anniversaire]; "sister's" → [sister]; "aujourd'hui" → [aujourdhui]; "don't" → [don, t]. */
    private fun pieces(w: String): List<String> {
        if ('\'' !in w) return listOf(w)
        if (w == "aujourd'hui") return listOf("aujourdhui")
        val parts = w.split('\'')
        val out = ArrayList<String>(parts.size)
        for ((i, p) in parts.withIndex()) {
            when {
                i < parts.size - 1 && p in ELISIONS -> {}
                i == parts.size - 1 && i > 0 && p == "s" -> {}
                else -> out += p
            }
        }
        return out
    }
}

/**
 * A BM25 index (k1 = 1.2, b = 0.75, Lucene's idf, which never goes negative) over (id, text) pairs. Ties keep the
 * order the documents were given in, so pass the newest first.
 */
class Bm25Index private constructor(
    private val ids: List<String>,
    private val docs: List<Map<String, Int>>,
    private val lengths: IntArray,
    private val df: Map<String, Int>,
    private val k1: Double,
    private val b: Double,
) {
    /** One document found: its score and the query terms it holds (for the exact-token boost). */
    data class Hit(val id: String, val score: Double, val matched: Set<String>)

    val size: Int get() = ids.size
    private val avgLength: Double = if (lengths.isEmpty()) 0.0 else lengths.average()

    private fun idf(term: String): Double {
        val n = df[term] ?: return 0.0
        return ln(1.0 + (ids.size - n + 0.5) / (n + 0.5))
    }

    /** The documents sharing a term with [query], best first, at most [limit], each scoring at least [minScore]. */
    fun search(query: String, limit: Int = 10, minScore: Double = 0.0): List<Hit> {
        val terms = MemoryText.tokens(query).distinct()
        if (terms.isEmpty() || ids.isEmpty()) return emptyList()
        val hits = ArrayList<Pair<Int, Hit>>()
        for ((d, tf) in docs.withIndex()) {
            var score = 0.0
            val matched = HashSet<String>()
            for (t in terms) {
                val f = tf[t] ?: continue
                matched += t
                val norm = if (avgLength > 0) lengths[d] / avgLength else 1.0
                score += idf(t) * (f * (k1 + 1)) / (f + k1 * (1 - b + b * norm))
            }
            if (matched.isNotEmpty() && score >= minScore) hits += d to Hit(ids[d], score, matched)
        }
        return hits.sortedWith(compareByDescending<Pair<Int, Hit>> { it.second.score }.thenBy { it.first }).take(limit).map { it.second }
    }

    companion object {
        fun build(documents: List<Pair<String, String>>, k1: Double = 1.2, b: Double = 0.75): Bm25Index {
            val ids = documents.map { it.first }
            val docs = documents.map { (_, text) -> MemoryText.tokens(text).groupingBy { it }.eachCount() }
            val lengths = IntArray(docs.size) { docs[it].values.sum() }
            val df = HashMap<String, Int>()
            for (d in docs) for (t in d.keys) df[t] = (df[t] ?: 0) + 1
            return Bm25Index(ids, docs, lengths, df, k1, b)
        }
    }
}
