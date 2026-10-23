// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.BrowserSearchAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.i18n.Phrase

// Pure. 1.0: a search in your browser (Engines.kt). Your own words in a phrase that names an engine or your browser
// ("search DuckDuckGo for …", "look up … on Google", « cherche sur Startpage … », « cherche … dans mon navigateur ») open
// it at once, before the model, exactly as said (BrowserRouter, as WebRouter does for Robot's own search). "Look it up on
// Google", "look that dentist up on DuckDuckGo", « cherche-le sur Google », « cherche ce resto sur Google » (1.0: a pronoun
// or a demonstrative first) leave the words to the model, which calls browser_search: its query then passes
// QueryCheck, and a card shows it in full before anything opens (BrowserGate). Robot sends nothing itself: the browser
// opens the engine's own address with the words, and Robot never sees the results.

/** What a phrase about a search in your browser asks for. */
sealed class BrowserAsk {
    /** [query] exactly as you said it, in [engine] (null: your default). */
    data class Open(val query: String, val engine: BrowserEngine?) : BrowserAsk()

    /** "Look it up on Google": the words are the conversation's, so the model writes them ([engine] null: your default). */
    data class Resolve(val engine: BrowserEngine?) : BrowserAsk()
}

/** An explicit search the panel runs without the model: Robot's own ([Search]), or in your browser ([Browser]). */
sealed class WebRoute {
    abstract val query: String

    /** "Search the web for …": Robot's web search with your words; [google]: said as "google …" (Google opens instead while Robot's own is off). */
    data class Search(override val query: String, val google: Boolean = false) : WebRoute()

    /** A search in your browser with your words, in [engine] (null: your default). */
    data class Browser(override val query: String, val engine: BrowserEngine?) : WebRoute()
}

object BrowserRouter {
    /** Every engine's spoken names, longest first ("brave search" before "brave"), as one alternation. */
    private val ENGINE: String = BrowserEngine.entries.flatMap { it.names }.sortedByDescending { it.length }
        .joinToString("|") { it.replace(" ", "[ -]?") }
    private const val BROWSER_EN = "(?:my|the) (?:web )?browser"
    private const val BROWSER_FR = "(?:mon|le) navigateur(?: web)?"
    private const val SUR = "(?:sur|su|avec|dans)"
    private const val VERB_FR = "(?:cherche|recherche|trouve|regarde|checke|check)"

    /** The words that stand for something said before ("look it up on Google"): the model has to write the query. */
    private val PRONOUN = setOf("it", "that", "this", "them", "those", "one", "le", "la", "les", "ca", "cela", "ceci", "moi ca", "moi le", "moi la")

    /**
     * 1.0: words that begin with a pronoun, a possessive or a demonstrative ("that dentist", "this place", "their hours",
     * « ce resto », « cette adresse », « ces horaires », « celui-là ») also stand for something said before, so they aren't
     * your literal words either: the model writes the query (card first, QueryCheck). An article doesn't ("the Stanley
     * Cup", « la mairesse de Montréal », « les horaires »), and « le », « la », « les » alone are [PRONOUN]'s.
     */
    private val REFERS = setOf("it", "its", "that", "this", "these", "those", "they", "them", "their", "he", "him", "his", "she", "her",
        "ca", "cela", "ceci", "ce", "cet", "cette", "ces", "celui", "celle", "ceux", "celles", "lui", "eux", "son", "sa", "ses", "leur", "leurs")

    /** [x] stands for something said before: a pronoun alone, or words that start with one ([REFERS]). */
    private fun refers(x: String): Boolean {
        val f = WebWords.fold(x)
        if (f.replace('-', ' ') in PRONOUN) return true
        // The first word, as said: "that's" is "that", « celui-là » is « celui » (an apostrophe or -là, -ci ends it).
        val first = f.trim().split(Regex("[\\s'’]+")).firstOrNull()?.replace(Regex("-(?:la|ci)$"), "") ?: return false
        return first in REFERS
    }

    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay|dis|salut) robot[,.!]?\\s+)?(?:(?:please|can you|could you|would you|will you|peux[- ]tu|pourrais[- ]tu|stp|s['’ ]il te pla[iî]t)[,]?\\s+)*",
        RegexOption.IGNORE_CASE)
    private val TAIL = Regex("[\\s,.!?;:…]*(?:\\s+(?:please|for me|s['’ ]il te pla[iî]t|s['’ ]il vous pla[iî]t|stp|svp|merci))?[\\s,.!?;:…]*$", RegexOption.IGNORE_CASE)
    private val IN_BROWSER = Regex("\\s+(?:in $BROWSER_EN|dans $BROWSER_FR)$", RegexOption.IGNORE_CASE)
    /** "search the web for X on Google": the words are X. */
    private val THE_WEB = Regex("^(?:the web|online|the internet|sur le web|sur internet|en ligne) (?:for |pour )?", RegexOption.IGNORE_CASE)

    private fun rx(p: String) = Regex(p, RegexOption.IGNORE_CASE)

    /** Group 1 the engine, group 2 the words. */
    private val ENGINE_FIRST = listOf(
        rx("^(?:do a |run a )?search (?:on |with |using |in )?($ENGINE) for (.+)$"),
        rx("^(?:open|use|go to) ($ENGINE) (?:and )?(?:search|look up|look) (?:for |up )?(.+)$"),
        rx("^(duckduckgo|duck duck go|bing) (?:for )?(.+)$"),
        rx("^$VERB_FR(?:-moi| moi)? $SUR ($ENGINE) (?:pour |à propos de |a propos de )?(.+)$"),
        rx("^fais (?:une )?recherche (?:$SUR )?($ENGINE) (?:sur|pour|à propos de|a propos de) (.+)$"),
    )

    /** Group 1 the words, group 2 the engine. */
    private val ENGINE_LAST = listOf(
        rx("^look (.+?) up (?:on|with|in) ($ENGINE)$"),
        rx("^(?:search|look) (?:for |up )?(.+?) (?:on|with|using|in) ($ENGINE)$"),
        rx("^(?:find|check) (.+?) on ($ENGINE)$"),
        rx("^$VERB_FR(?:-moi| moi)?[ -](.+?) $SUR ($ENGINE)$"),
        rx("^fais (?:une )?recherche (?:sur|pour|à propos de|a propos de) (.+?) $SUR ($ENGINE)$"),
    )

    /** Your browser without an engine: group 1 the words. */
    private val BROWSER_ONLY = listOf(
        rx("^(?:search|look) (?:in|with|using) $BROWSER_EN (?:for )?(.+)$"),
        rx("^look (.+?) up in $BROWSER_EN$"),
        rx("^(?:search|look) (?:for |up )?(.+?) in $BROWSER_EN$"),
        rx("^$VERB_FR(?:-moi| moi)? dans $BROWSER_FR (.+)$"),
        rx("^$VERB_FR(?:-moi| moi)?[ -](.+?) dans $BROWSER_FR$"),
    )

    /** Said as "google …" (WebRouter's form): Google opens in your browser while Robot's own web search is off. */
    private val GOOGLE_VERB = rx("^google(?: for)? ")

    /** Your own things ("search my notes on…") are never a web search (WebRouter's rule). */
    private val OWN = Regex("^(?:(?:in|through|on) )?(?:my|our) (?:notes?|contacts?|calendar|agenda|events?|messages?|texts?|emails?|photos?|files?)\\b|" +
        "^(?:dans |parmi )?(?:mes|nos) (?:notes?|contacts?|calendriers?|agendas?|messages?|textos?|courriels?|photos?|fichiers?)\\b")

    private fun body(question: String): String? {
        val q = question.trim().replace(Regex("\\s+"), " ")
        if (q.length < 4 || q.length > 400) return null
        return TAIL.replace(LEAD.replace(q, "").trim(), "").trim()
    }

    private fun words(x: String): String? {
        val w = THE_WEB.replace(IN_BROWSER.replace(TAIL.replace(x, ""), ""), "").trim().trim('"', '“', '”', '«', '»', '\'').trim()
        return w.takeIf { it.isNotEmpty() && !OWN.containsMatchIn(WebWords.fold(it)) }
    }

    private fun ask(raw: String, engine: BrowserEngine?): BrowserAsk? {
        val x = words(raw) ?: return null
        return if (refers(x)) BrowserAsk.Resolve(engine) else BrowserAsk.Open(x, engine)
    }

    /**
     * A phrase that names an engine or your browser, as written: [BrowserAsk.Open] with exactly your words, or
     * [BrowserAsk.Resolve] when they stand for something said before ("it", "that dentist", « ce resto »). Null for
     * anything else (Robot's own "search the web for …" is WebRouter's).
     */
    fun explicit(question: String?): BrowserAsk? {
        val b = body(question ?: return null) ?: return null
        for (p in ENGINE_FIRST) p.find(b)?.let { m -> return ask(m.groupValues[2], BrowserEngine.of(m.groupValues[1])) }
        for (p in ENGINE_LAST) p.find(b)?.let { m -> return ask(m.groupValues[1], BrowserEngine.of(m.groupValues[2])) }
        for (p in BROWSER_ONLY) p.find(b)?.let { m -> return ask(m.groupValues[1], null) }
        return null
    }

    /** The search the panel runs without the model for [question]: in your browser, or Robot's own; null when it isn't one. */
    fun route(question: String): WebRoute? = when (val b = explicit(question)) {
        is BrowserAsk.Open -> WebRoute.Browser(b.query, b.engine)
        is BrowserAsk.Resolve -> null
        null -> WebRouter.explicit(question)?.let { WebRoute.Search(it, google = body(question)?.let(GOOGLE_VERB::containsMatchIn) == true) }
    }

    /** "On Google", "sur Startpage", "in my browser", "google it": an engine or your browser named as where to search. */
    private val NAMED = rx("\\b(?:on|with|using|in|via|sur|su|avec|dans) ($ENGINE)\\b|^($ENGINE) (?:it|that|this|ça|ca)\\b|\\b$BROWSER_EN\\b|\\b$BROWSER_FR\\b")

    /**
     * The question names an engine or your browser as where to search ("look it up on Google", not "is Google down?"):
     * browser_search is described to the model, with web search's group.
     */
    fun named(question: String): Boolean = explicit(question) != null || body(question)?.let(NAMED::containsMatchIn) == true

    /** The engine [question] names, if any (a model that left `engine` out keeps the one you said). */
    fun engineIn(question: String?): BrowserEngine? = when (val b = explicit(question)) {
        is BrowserAsk.Open -> b.engine
        is BrowserAsk.Resolve -> b.engine
        null -> null
    }

    /** The words your own phrase this turn ([said]) asked to search for, in your browser or with Robot's own search. */
    fun ownWords(said: String?): String? = said?.let(::route)?.query
}

/**
 * browser_search between the call and your browser: the engine (the one named, the one you said, or your default), and
 * whether a card shows first. Your own words this turn open at once; the model's words pass QueryCheck (refused outright,
 * or a card that needs a tap for words from something Robot read on your phone) and always show the card with the query
 * in full, since the engine gets them with your browser's cookies. The gate adds the lock (unlock first: it never works
 * on the lock screen) and a message in the conversation (a tap).
 */
object BrowserGate {
    fun engine(a: BrowserSearchAction, said: String?, default: BrowserEngine): BrowserEngine = a.engine ?: BrowserRouter.engineIn(said) ?: default

    /**
     * [a]'s words are exactly what your own phrase this turn asked for ([said]), to the engine it chose: the one it names,
     * Google for "google …", else your [default]. Nobody else wrote them or picked where they go (a model that names another
     * engine shows the card).
     */
    fun own(a: BrowserSearchAction, said: String?, default: BrowserEngine): Boolean {
        val mine = BrowserRouter.ownWords(said) ?: return false
        return norm(mine) == norm(a.query) && (a.engine == null || a.engine == (saidEngine(said) ?: default))
    }

    /** The engine your own phrase [said] chose, if any: the one it names, or Google for "google …" (WebRouter's form). */
    private fun saidEngine(said: String?): BrowserEngine? = when (val r = said?.let(BrowserRouter::route)) {
        is WebRoute.Browser -> r.engine
        is WebRoute.Search -> if (r.google) BrowserEngine.GOOGLE else null
        null -> null
    }

    private fun norm(s: String) = s.trim().replace(Regex("\\s+"), " ")

    /**
     * [a] made ready: [BrowserSearchAction.engine] and [BrowserSearchAction.own] filled in; [note] says what the card's
     * line says (the app's words). Refused when QueryCheck refuses the model's words.
     */
    fun prepare(a: BrowserSearchAction, said: String?, ctx: QueryContext, default: BrowserEngine, note: (BrowserEngine) -> String? = { null }): Prepared {
        val engine = engine(a, said, default)
        if (own(a, said, default)) return Prepared.Ready(a.copy(query = norm(a.query), engine = engine, own = true))
        return when (val v = QueryCheck.check(a.query, ctx)) {
            is QueryVerdict.Refuse -> Prepared.Refused(ActionStatus.FAILED, v.why.textPhrase)
            is QueryVerdict.Card -> {
                // 1.1: QueryCheck's line in the reply language, the app's line ([note]) as the app wrote it.
                val text = Phrase.Join(listOfNotNull(v.why.noteText, note(engine)?.let(Phrase::plain)))
                Prepared.Ready(a.copy(query = v.query, engine = engine), why = setOf(if (v.why.tapOnly) AskWhy.SEARCH_PERSONAL else AskWhy.SEARCH),
                    note = text.en(), noteText = text)
            }
            is QueryVerdict.Send -> Prepared.Ready(a.copy(query = v.query, engine = engine), why = setOf(AskWhy.SEARCH), note = note(engine))
        }
    }
}
