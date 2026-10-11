package app.heyrobot.core.web

// Pure. The check every query passes before it may leave the phone (design A10.3, "Queries as an exfiltration
// channel"): an injected page, screen text or another AI's answer could make the model search for your address or a
// number from your calendar, so the query carries only what the person could have meant to send.
//
// - Refused outright, whoever wrote it (the Nominatim rule, extended): an "@" or an email spelled out ("jo at example dot
//   com"), a run of 6 or more digits (phone, card and account numbers, however grouped, or spelled out in words), a
//   link with data in it, a code-like token, hidden characters, more than 16 words or 200 characters; and a street
//   address made of words from the conversation's content rather than yours. "That search looked like it contained
//   personal details, so Robot didn't send it."
// - A card with the query in full: a word that is in content Robot didn't write (screen text, a web page, a read tool's
//   findings, a helper's answer) or in your personal content, and not in your own words of this or an earlier turn.
//   Personal words need a tap. When the conversation carries such content but its text is no longer at hand, any word
//   that isn't yours shows the card too.
// - Otherwise it goes: words the model added that came from nowhere in the conversation ("latest", "2026", "review")
//   carry nothing of yours.

/**
 * Where the conversation's words came from, for one check. Each list holds whole texts as the conversation has them.
 */
data class QueryContext(
    /** Your own words, this turn's and every earlier turn's, spoken or typed; never screen text or what a tool found. */
    val said: List<String>,
    /**
     * Content Robot didn't write: screen text, web results and pages, a helper's or Claude's answers, and Robot's own
     * answers made from them (a web answer counts: it came from pages).
     */
    val untrusted: List<String> = emptyList(),
    /** Personal content: what read tools found (calendar, notes, contacts, phone status, where you are), a photo's text. */
    val personal: List<String> = emptyList(),
    /** The conversation carries content (TurnRules.untrusted), even when [untrusted] no longer holds its text. */
    val content: Boolean = untrusted.isNotEmpty(),
    /** The conversation is personal (ClaudeContext.personal), even when [personal] no longer holds its text. */
    val isPersonal: Boolean = personal.isNotEmpty(),
)

/** What happens to a query. */
sealed class QueryVerdict {
    /** It may go (after the consent card and the gate): [query] cleaned. */
    data class Send(val query: String) : QueryVerdict()

    /** A card shows [query] in full first; [words] (as written in it) are the ones that aren't yours. */
    data class Card(val query: String, val words: List<String>, val why: QueryCard) : QueryVerdict()

    /** It never goes. */
    data class Refuse(val why: QueryRefusal) : QueryVerdict()
}

/** Why a query shows its card. [tapOnly]: a spoken yes can't send it. */
enum class QueryCard(val note: String, val tapOnly: Boolean) {
    PERSONAL("This search uses words from something Robot read on your phone, not from what you said.", true),
    CONTENT("This search uses words from a web page, your screen or another AI's answer, not from what you said.", false),
    UNKNOWN("This search uses words you didn't say, in a conversation with content Robot didn't write.", false),
}

/** Why a query never goes; [code] for the log. */
enum class QueryRefusal(val code: Int) {
    EMPTY(1), TOO_LONG(2), TOO_MANY_WORDS(3), EMAIL(4), DIGITS(5), LINK(6), CODE(7), HIDDEN(8), ADDRESS(9);

    /** What Robot says. */
    val text: String get() = when (this) {
        EMPTY -> "There was nothing to search for."
        TOO_LONG, TOO_MANY_WORDS -> "That search was too long to send."
        else -> WebFail.QUERY_REFUSED.text(null)
    }
}

object QueryCheck {
    const val MAX_CHARS = 200
    const val MAX_WORDS = 16
    /** A run of this many digits never goes. */
    const val MAX_DIGITS = 6

    private val SPACE = Regex("\\s+")
    private val EMAIL_WORDS = Regex(
        "\\b[\\p{L}\\p{N}._%+-]+\\s*(?:\\(at\\)|\\[at]|\\{at}|\\s(?:at|arobase|chez)\\s)\\s*[\\p{L}\\p{N}-]+\\s*(?:\\(dot\\)|\\[dot]|\\{dot}|\\s(?:dot|point)\\s|\\.)\\s*" +
            "(?:com|org|net|ca|fr|io|me|info|edu|gov|uk|de|be|ch|qc|eu|co|us|app|dev)\\b",
    )
    private val LINK_DATA = Regex("(?:[\\p{L}\\p{N}-]+\\.)+[a-z]{2,}(?::\\d+)?/\\S*[?=&#]|[\\p{L}_][\\p{L}\\p{N}_]*=\\S")
    private val STREET = Regex(
        "\\b\\d{1,6}[a-z]?(?:\\s*,)?\\s+(?:[\\p{L}'.-]+\\s+){0,3}?(?:street|st|avenue|ave|av|road|rd|boulevard|blvd|boul|bd|drive|dr|lane|ln|court|ct|" +
            "crescent|cres|terrace|highway|hwy|way|place|pl|parkway|pkwy|rue|chemin|ch|rang|route|allee|impasse|quai|cours|montee|cote)\\b",
    )
    private val YEAR_RANGE = Regex("\\b(?:19|20)\\d\\d\\s*[-–]\\s*(?:19|20)\\d\\d\\b")
    private val NUMBER_WORDS = setOf(
        "zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "un", "une", "deux", "trois", "quatre", "cinq", "sept", "huit", "neuf", "dix",
    )
    /**
     * Words that say what kind of search it is and nothing about anyone: the model adds them, and a page or a calendar
     * having them too means nothing.
     */
    private val GENERIC = setOf(
        "news", "latest", "today", "tonight", "tomorrow", "yesterday", "current", "currently", "now", "recent", "new", "update", "updates",
        "review", "reviews", "price", "prices", "cost", "weather", "forecast", "score", "scores", "result", "results", "winner", "won", "win",
        "schedule", "time", "times", "date", "best", "top", "vs", "versus", "near", "official", "site", "website", "wiki", "wikipedia",
        "definition", "meaning", "recipe", "guide", "tutorial", "how", "what", "when", "where", "who", "why", "which", "list", "year", "years",
        "week", "month", "open", "hours", "release", "info", "information", "facts", "history", "explained", "steps", "instructions",
        "nouvelles", "actualite", "actualites", "dernieres", "dernier", "derniere", "aujourdhui", "demain", "hier", "prix", "meteo",
        "resultat", "resultats", "gagnant", "horaire", "horaires", "avis", "meilleur", "meilleurs", "meilleure", "recette", "definition",
        "signification", "officiel", "site", "annee", "semaine", "mois", "ouvert", "heures", "sortie", "histoire", "aujourd", "hui",
    )

    fun check(query: String, ctx: QueryContext): QueryVerdict {
        if (Invisible.hasTags(query) || Invisible.hasControl(query)) return QueryVerdict.Refuse(QueryRefusal.HIDDEN)
        val q = unquote(Invisible.strip(query).replace(SPACE, " ").trim())
        if (q.isEmpty()) return QueryVerdict.Refuse(QueryRefusal.EMPTY)
        if (q.length > MAX_CHARS) return QueryVerdict.Refuse(QueryRefusal.TOO_LONG)
        if (q.split(' ').size > MAX_WORDS) return QueryVerdict.Refuse(QueryRefusal.TOO_MANY_WORDS)
        val folded = WebWords.fold(q)
        if (q.contains('@') || q.contains('＠') || q.contains('﹫') || EMAIL_WORDS.containsMatchIn(folded)) return QueryVerdict.Refuse(QueryRefusal.EMAIL)
        if (folded.contains("://") || LINK_DATA.containsMatchIn(folded)) return QueryVerdict.Refuse(QueryRefusal.LINK)
        if (longestDigitRun(folded) >= MAX_DIGITS || spelledDigits(folded) >= MAX_DIGITS) return QueryVerdict.Refuse(QueryRefusal.DIGITS)
        if (q.split(' ').any(::codeLike)) return QueryVerdict.Refuse(QueryRefusal.CODE)

        val mine = stems(ctx.said)
        val personal = stems(ctx.personal)
        val untrusted = stems(ctx.untrusted)
        // A street address whose number or name came from the conversation's content, not from you, never goes.
        STREET.findAll(folded).forEach { m ->
            val fromContent = WebWords.words(m.value).map(WebWords::stem).any { it !in mine && (it in personal || it in untrusted) }
            if (fromContent) return QueryVerdict.Refuse(QueryRefusal.ADDRESS)
        }

        val notMine = WebWords.spans(q).filter { (_, w) -> significant(w) && WebWords.stem(w) !in mine }
        if (notMine.isEmpty()) return QueryVerdict.Send(q)
        val fromPersonal = notMine.filter { WebWords.stem(it.second) in personal }
        val fromContent = notMine.filter { WebWords.stem(it.second) in untrusted }
        fun words(l: List<Pair<String, String>>) = l.map { it.first }.distinct()
        return when {
            fromPersonal.isNotEmpty() -> QueryVerdict.Card(q, words(fromPersonal + fromContent), QueryCard.PERSONAL)
            fromContent.isNotEmpty() -> QueryVerdict.Card(q, words(fromContent), QueryCard.CONTENT)
            (ctx.isPersonal && ctx.personal.isEmpty()) || (ctx.content && ctx.untrusted.isEmpty()) -> QueryVerdict.Card(q, words(notMine), QueryCard.UNKNOWN)
            else -> QueryVerdict.Send(q)
        }
    }

    private fun stems(texts: List<String>): Set<String> = texts.flatMapTo(HashSet()) { t -> WebWords.words(t).map(WebWords::stem) }

    /** A word that could carry something of yours: not a stop word, not a kind-of-search word, not a lone letter pair, not a 1–2 digit number or a year. */
    private fun significant(w: String): Boolean = when {
        w in WebWords.STOP || w in GENERIC || WebWords.stem(w) in GENERIC -> false
        w.all { it.isDigit() } -> w.length >= 3 && !(w.length == 4 && (w.startsWith("19") || w.startsWith("20")))
        w.length <= 2 -> false
        else -> true
    }

    private fun unquote(s: String): String =
        if (s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '“' && s.last() == '”') || (s.first() == '«' && s.last() == '»'))) s.substring(1, s.length - 1).trim() else s

    /**
     * The longest run of digits in [s], counting digit groups joined by one or two of "-./+()" ("514-555-0199",
     * "(514) 555", "192.168.1.1") and groups of two or more digits joined by spaces ("4111 1111 1111 1111", "514 555
     * 0199"), except where a group is a year ("top 10 2026") or a year range ("1939-1945"). Pairs of digits joined by
     * spaces count as a run from four pairs on ("51 45 55 01 99"), so "iphone 15 16 17" doesn't.
     */
    fun longestDigitRun(s: String): Int {
        val text = YEAR_RANGE.replace(s, " ")
        val groups = Regex("\\d+").findAll(text).toList()
        var best = 0
        var run = 0
        var count = 0
        var pairs = false
        for ((k, g) in groups.withIndex()) {
            val linked = k > 0 && link(text, groups[k - 1], g)
            val spaced = linked && text.substring(groups[k - 1].range.last + 1, g.range.first).isBlank()
            if (linked) {
                run += g.value.length
                count++
                pairs = pairs && spaced && g.value.length == 2
            } else {
                run = g.value.length
                count = 1
                pairs = g.value.length == 2
            }
            best = maxOf(best, if (pairs && count < 4) g.value.length else run)
        }
        return best
    }

    private fun year(g: String) = g.length == 4 && (g.startsWith("19") || g.startsWith("20"))

    private fun link(text: String, a: MatchResult, b: MatchResult): Boolean {
        val between = text.substring(a.range.last + 1, b.range.first)
        if (between.isEmpty() || between.length > 3) return false
        if (between.all { it in "-./+()" } || (between.any { it in "-./+()" } && between.all { it in "-./+() " })) return true
        return between.isBlank() && a.value.length >= 2 && b.value.length >= 2 && !year(a.value) && !year(b.value)
    }

    /**
     * Digits said one at a time ("five one four five five five", "5 1 4 5 5 5", as speech recognition may write a
     * number), digit groups in between counted by their digits. A run counts only with at least two such single digits,
     * so "iphone 15 16 17" or "top 10 2026" don't.
     */
    fun spelledDigits(s: String): Int {
        var best = 0
        var run = 0
        var singles = 0
        for (w in WebWords.words(s)) {
            when {
                w in NUMBER_WORDS || (w.length == 1 && w[0].isDigit()) -> { run += 1; singles++ }
                w.all { it.isDigit() } -> run += w.length
                else -> { run = 0; singles = 0 }
            }
            if (singles >= 2) best = maxOf(best, run)
        }
        return best
    }

    /** A token that looks like a key, an ID or encoded data rather than a word. */
    private fun codeLike(token: String): Boolean {
        val t = token.trim('"', '\'', ',', '.', '(', ')', '?', '!', ':', ';')
        if (t.length < 16) return false
        val digits = t.count { it.isDigit() }
        val letters = t.count { it.isLetter() }
        // Mixed case past the first letter ("aGVsbG8"), not a capitalized word.
        val mixedCase = t.drop(1).any { it.isUpperCase() } && t.any { it.isLowerCase() }
        // A long word is a word (German compounds, "pneumonoultramicroscopic…"); a long token with digits or mixed case isn't.
        if (t.length > 64 || (t.length > 40 && (digits > 0 || mixedCase || letters < t.length))) return true
        return (digits >= 4 && letters >= 4) || (mixedCase && digits >= 2) || t.any { it in "+/=_" } && digits > 0
    }
}
