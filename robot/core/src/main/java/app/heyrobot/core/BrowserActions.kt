package app.heyrobot.core

import app.heyrobot.core.web.BrowserEngine

// Pure. 1.0: browser_search, a search in your browser (core web Engines.kt, BrowserSearch.kt). It opens the engine's own
// search address in your browser and ends Robot's turn there: Robot sends nothing and never sees the results, so it isn't
// [Action.online] (no INTERNET use by Robot, no Stop, no deadline). It is Robot's own tool ([ToolName.internal]): no level
// in Settings › Actions and never on the lock screen (unlock first); Settings › Web search picks the default engine.

/**
 * browser_search: [query] in [engine] (null until prepared: the one you said, or your default). [own]: the words are
 * exactly your own phrase's this turn ("search DuckDuckGo for …"), so no card was needed for them (BrowserGate).
 */
data class BrowserSearchAction(val query: String, val engine: BrowserEngine? = null, val own: Boolean = false) : Action() {
    override val tool get() = ToolName.BROWSER_SEARCH
    override fun describe() = "Search ${engine?.title ?: "the web"} for “$query” in your browser"
    override val confirmLabel get() = "Search"
    override val online get() = false
    override fun worksLocked(lockedTools: Set<ToolName>) = false
}

object BrowserCalls {
    /** Two short parameters (every token here is paid on every rebuild that describes it). */
    val SPEC = ToolSpec(ToolName.BROWSER_SEARCH, "Search in the user's browser.", listOf(
        ToolParam("query", "string", "Short search words", true),
        ToolParam("engine", "string", "e.g. google; omit for theirs", false),
    ))

    /** The query as written (BrowserGate decides whether it may open), and the engine when it names one Robot knows. */
    fun parse(a: Map<String, Any?>, text: (Map<String, Any?>, Int, Array<out String>) -> String?): ParsedCall {
        val query = text(a, MAX_QUERY, arrayOf("query", "q", "search", "text", "words")) ?: return ParsedCall.Invalid(ToolName.BROWSER_SEARCH.id, "query is required")
        val named = text(a, 40, arrayOf("engine", "site", "with", "provider"))
        val engine = named?.let { BrowserEngine.of(it) ?: return ParsedCall.Invalid(ToolName.BROWSER_SEARCH.id, "engine must be one of ${ENGINES}") }
        return ParsedCall.Ok(BrowserSearchAction(query, engine))
    }

    private const val MAX_QUERY = 300
    private val ENGINES = BrowserEngine.entries.joinToString(", ") { it.id }
}
