package app.heyrobot.core

import java.text.Normalizer

// Pure. What Robot hands Organic Maps to search for (0.3.1). Organic Maps' search has no idea of "near me": it
// searches the words, and already ranks results around you (or around the map's view when it has no position yet).
// So "find a pharmacy near me" searches "pharmacy". It can't plan a route to a name either: om://route needs
// coordinates, so "directions to X" opens a search for X and you tap Route.

object MapsQuery {
    /** "near me" and its kin at the end of the query, English and French ("pharmacie près de moi"). */
    private val NEAR_TAIL = Regex(
        "[\\s,]+(?:" +
            "near(?:\\s+(?:to\\s+)?(?:me|here|us|my(?:\\s+current)?\\s+location|where\\s+i\\s+am))|nearby|close\\s*by|close\\s+to\\s+(?:me|here)|" +
            "(?:the\\s+)?(?:nearest|closest)\\s+(?:to\\s+)?(?:me|here)|around\\s+(?:me|here|us)|in\\s+my\\s+area|in\\s+the\\s+area|" +
            "(?:pr[eè]s|proche)\\s+(?:de\\s+moi|d['’]ici)|[àa]\\s+proximit[ée]|autour\\s+de\\s+moi|dans\\s+le\\s+coin|pas\\s+loin|" +
            "(?:le|la|les)\\s+plus\\s+proches?" +
            ")\\s*[.!?]*$",
        RegexOption.IGNORE_CASE,
    )
    /** "the nearest gas station", "closest pharmacy". */
    private val NEAREST_LEAD = Regex("^(?:(?:the|a|an)\\s+)?(?:nearest|closest)\\s+", RegexOption.IGNORE_CASE)
    /** An article left in front once the "near me" words are gone ("a pharmacy nearby" → "pharmacy"). */
    private val ARTICLE = Regex("^(?:(?:the|a|an|some|any|le|la|les|un|une|des)\\s+|l['’])", RegexOption.IGNORE_CASE)

    /**
     * [raw] without the words that only mean "around me": "pharmacy near me", "a pharmacy nearby", "coffee, near me",
     * "the nearest gas station", "pharmacie près de moi" and "la pharmacie la plus proche" all search the place alone.
     * A name that only starts with such a word stays ("Nearby Café"), and so does a query that is nothing else.
     */
    fun clean(raw: String): String {
        val original = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        var q = original
        var near = false
        while (true) {
            val next = NEAR_TAIL.replace(q, "").let { NEAREST_LEAD.replace(it, "") }.trim()
            if (next == q) break
            near = true
            q = next
        }
        if (near) q = ARTICLE.replace(q, "").trim().trimEnd(',').trim()
        return q.ifEmpty { original }
    }
}
