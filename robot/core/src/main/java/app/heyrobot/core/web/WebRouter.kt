// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.HelperRouter
import java.util.Locale

// Pure. Which questions are web search's (design A4.3, A6.1 WebRouter), in English and French: the explicit forms Robot
// routes in code before Gemma ("search the web for X", "look up X online", "cherche sur le web X", "recherche X"), whose
// query is exactly your words X; and the words that make the WEB tool group described to Gemma (search, look up, online,
// news, who won, cherche, actualité…). Searches of your own things stay with them: "search my notes for…" is a note
// search, "look up Bob" a contact lookup, "ask Jarvis to search…" the helper's (HelperRouter runs first). Also the question's
// language for the services and the one-shot (fr or en, by its words).

object WebRouter {
    /** Words and phrases (folded, as [WebWords.fold] writes them) that call the WEB group (A4.3). */
    private val GROUP_WORDS = listOf(
        "search", "searches", "searching", "look up", "looking up", "lookup", "google", "googling", "online", "on the web", "on the internet",
        "the web", "internet", "latest", "news", "headline", "headlines", "today s", "todays", "current", "currently", "who won", "price of",
        "prices of", "score of", "results of", "cherche", "chercher", "recherche", "rechercher", "regarde sur internet", "en ligne", "sur le web",
        "sur internet", "dernieres nouvelles", "nouvelles", "actualite", "actualites", "actu", "qui a gagne", "prix de", "prix du", "prix des",
        // 1.0: an engine or your browser named (a search in your browser, BrowserRouter).
        "duckduckgo", "duck duck go", "startpage", "start page", "brave search", "mojeek", "qwant", "ecosia", "bing", "browser", "navigateur",
    )

    /** The words that stand for something said before ("google it"): the model writes the query, never "it". */
    private val PRONOUN = setOf("it", "that", "this", "them", "ca", "cela", "ceci", "le", "la", "les")

    /** "search …", "look up …" when what follows is one of your own things: notes, contacts, calendar, messages, photos. */
    private val OWN = Regex(
        "^(?:(?:in|through|on) )?(?:my|our|the) (?:notes?|contacts?|calendar|agenda|events?|messages?|texts?|emails?|mails?|photos?|pictures?|files?|phone|people|history|conversations?)\\b|" +
            "^(?:dans |parmi )?(?:mes|nos|les|mon|ma) (?:notes?|contacts?|calendriers?|agendas?|evenements?|messages?|textos?|courriels?|photos?|fichiers?|telephone|conversations?)\\b",
    )

    /** Politeness and wake words around a command. */
    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay|dis|salut) robot[,.!]?\\s+)?(?:(?:please|can you|could you|would you|will you|peux[- ]tu|pourrais[- ]tu|stp|s['’ ]il te pla[iî]t)[,]?\\s+)*",
        RegexOption.IGNORE_CASE)
    private val TAIL = Regex("[\\s,.!?;:…]*(?:\\s+(?:please|for me|s['’ ]il te pla[iî]t|s['’ ]il vous pla[iî]t|stp|svp|merci))?[\\s,.!?;:…]*$", RegexOption.IGNORE_CASE)

    /**
     * The explicit forms (A4.3), on the question as written: the query is what follows, exactly your words. English
     * "search (the web|online|the internet) for X", "search for X (online|on the web|on the internet)", "look up X
     * (online|on the web|on the internet)", "look X up online", "google X", "web search X"; French "cherche (sur le
     * web|sur internet|en ligne) X", "cherche X (sur le web|sur internet|en ligne)", "recherche X", "fais une recherche
     * (sur|pour) X". Null when it isn't one, X is one of your own things ("search my notes for…"), or X stands for something
     * said before ("google it"). 1.0: « … sur Google » names an engine, so it opens in your browser (BrowserRouter).
     */
    fun explicit(question: String): String? {
        val q = question.trim().replace(Regex("\\s+"), " ")
        if (q.length < 4 || q.length > 400) return null
        val body = LEAD.replace(q, "").trim()
        val patterns = listOf(
            Regex("^(?:do a |run a )?(?:web search|search (?:the web|online|the internet|on the web|on the internet|the net))(?: for| about)?:? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:search|look) (?:for|up) (.+?) (?:online|on the web|on the internet|on the net)$", RegexOption.IGNORE_CASE),
            Regex("^look (.+?) up (?:online|on the web|on the internet)$", RegexOption.IGNORE_CASE),
            Regex("^(?:look up|lookup) (.+?) (?:online|on the web|on the internet)$", RegexOption.IGNORE_CASE),
            Regex("^google(?: for)? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:cherche|recherche|trouve)(?:-moi| moi)? (?:sur le web|sur internet|sur le net|en ligne)(?: pour)? (.+)$", RegexOption.IGNORE_CASE),
            Regex("^(?:cherche|recherche|trouve)(?:-moi| moi)? (.+?) (?:sur le web|sur internet|sur le net|en ligne)$", RegexOption.IGNORE_CASE),
            Regex("^fais (?:une )?recherche (?:sur le web |sur internet |en ligne )?(?:sur|pour|a propos de|à propos de) (.+)$", RegexOption.IGNORE_CASE),
            Regex("^recherche (.+)$", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(body) ?: continue
            val x = TAIL.replace(m.groupValues[1], "").trim().trim('"', '“', '”', '«', '»', '\'').trim()
            if (x.isEmpty() || WebWords.fold(x) in PRONOUN) return null
            if (OWN.containsMatchIn(WebWords.fold(x))) return null
            return x
        }
        return null
    }

    /** The question's words call for the WEB group (described only when web search is on). */
    fun wants(question: String): Boolean {
        val padded = " " + WebWords.words(question.replace("'", " ").replace("’", " ")).joinToString(" ") + " "
        return GROUP_WORDS.any { padded.contains(" $it ") }
    }

    private val FR = setOf(
        "le", "la", "les", "des", "du", "un", "une", "est", "sont", "qui", "que", "quel", "quelle", "quels", "quelles", "comment", "pourquoi",
        "combien", "ou", "quand", "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "mon", "ma", "mes", "ton", "ta", "dans", "sur",
        "avec", "pour", "pas", "cherche", "recherche", "trouve", "dernieres", "nouvelles", "actualite", "aujourd", "hui", "cette", "ce", "au",
        "aux", "et", "de", "d", "l", "qu", "c", "a", "gagne", "prix", "meteo", "mairesse", "maire",
    )
    private val EN = setOf(
        "the", "a", "an", "is", "are", "was", "were", "who", "what", "which", "how", "why", "when", "where", "i", "you", "he", "she", "we",
        "they", "my", "your", "in", "on", "with", "for", "of", "to", "and", "search", "look", "up", "find", "latest", "news", "today", "this",
        "won", "price", "does", "did", "do", "can", "will",
    )

    /** "fr" when the question reads as French, else "en" (the services' and the one-shot's language). */
    fun lang(question: String): String {
        val words = WebWords.words(question.lowercase(Locale.ROOT))
        val fr = words.count { it in FR }
        val en = words.count { it in EN }
        val accents = question.count { it in "éèêàâçùûôîœë" }
        return if (fr + (if (accents > 0) 1 else 0) > en) "fr" else "en"
    }
}
