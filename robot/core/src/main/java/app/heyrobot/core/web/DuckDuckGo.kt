// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import org.json.JSONObject

/**
 * 1.0, DuckDuckGo's Instant Answer API: `GET https://api.duckduckgo.com/?q=…&format=json&no_html=1&skip_disambig=1
 * &no_redirect=1&t=heyrobot`, free and keyless. Its page says it "is not a full search results API": it gives the answer
 * box above DuckDuckGo's results (a topic's abstract, often Wikipedia's, a definition, a computed answer, the official
 * site, related topics), or nothing for most other questions. It asks to be credited where its answers show, with each
 * answer's source (SearchProvider.DUCKDUCKGO's line on the card, each source numbered with its link), and to name the app
 * in `t` (the same "heyrobot" for every Robot, never you). It answers 202 as often as 200, with the same body. A bang
 * ("!w …") is never followed (`no_redirect`), and the answer's image is never loaded.
 */
object DuckDuckGoWire {
    const val SEARCH = "https://api.duckduckgo.com/"
    /** The app's name for the API's `t` parameter. */
    const val APP = "heyrobot"
    /** Related topics kept after the abstract, the definition and the official links. */
    const val MAX_RELATED = 3

    fun request(query: String, userAgent: String): SearchRequest = SearchRequest("GET",
        "$SEARCH?q=${SearchWire.encode(query)}&format=json&no_html=1&skip_disambig=1&no_redirect=1&t=$APP",
        listOf("User-Agent" to userAgent, "Accept" to "application/json"))

    /** DuckDuckGo's own results page for [query]: where a computed answer is credited (the API's attribution link). */
    fun resultsPage(query: String): String = "https://duckduckgo.com/?q=${SearchWire.encode(query)}"

    /**
     * The answer as results: the abstract (its text also as page text, so the answer can use all of it), the definition, a
     * computed answer (credited to DuckDuckGo's results page for [query]), the official links, then a few related topics.
     * Nothing at all is "No results" (most questions that aren't about a thing).
     */
    fun parse(status: Int, body: String?, query: String, retryAfter: String? = null): SearchAnswer {
        when (status) {
            200, 202 -> {}
            429 -> return SearchAnswer.Failed(WebFail.BUSY, SearchWire.retryAfter(retryAfter))
            in 500..599 -> return SearchAnswer.Failed(WebFail.NOT_ANSWERING)
            else -> return SearchAnswer.Failed(WebFail.SERVICE)
        }
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val heading = SearchWire.str(o, "Heading")
        val results = ArrayList<SearchResult>()
        val abstract = SearchWire.str(o, "AbstractText") ?: SearchWire.str(o, "Abstract")
        if (abstract != null) {
            SearchWire.result(SearchWire.str(o, "AbstractURL"), heading ?: SearchWire.str(o, "AbstractSource"), abstract, SearchProvider.DUCKDUCKGO,
                extra = listOf(SearchWire.text(abstract, SearchWire.EXTRA_CHARS)))?.let { results += it }
        }
        SearchWire.str(o, "Definition")?.let { d ->
            SearchWire.result(SearchWire.str(o, "DefinitionURL"), heading ?: SearchWire.str(o, "DefinitionSource"), d, SearchProvider.DUCKDUCKGO)?.let { results += it }
        }
        // A computed answer ("2 + 2 = 4", a conversion) has no source of its own: DuckDuckGo's results page credits it.
        (o.opt("Answer") as? String)?.takeIf { it.isNotBlank() }?.let { a ->
            SearchWire.result(resultsPage(query), SearchProvider.DUCKDUCKGO.title, a, SearchProvider.DUCKDUCKGO)?.let { results += it }
        }
        for (r in SearchWire.objects(o.optJSONArray("Results"))) topic(r, heading)?.let { results += it }
        results += related(o).take(MAX_RELATED)
        val answer = (o.opt("Answer") as? String)?.let { SearchWire.text(it, SearchWire.SNIPPET_CHARS) }?.takeIf { it.isNotEmpty() }
        return SearchAnswer.Results(SearchWire.distinct(results), answers = listOfNotNull(answer))
    }

    /** RelatedTopics: topics, and groups of them ({"Name", "Topics": […]}), in order. */
    private fun related(o: JSONObject): List<SearchResult> = SearchWire.objects(o.optJSONArray("RelatedTopics")).flatMap { t ->
        val inner = t.optJSONArray("Topics")
        if (inner != null) SearchWire.objects(inner).mapNotNull { topic(it, null) } else listOfNotNull(topic(t, null))
    }

    /** One link ({"FirstURL", "Text"}): "Valley Forge National Historical Park Category" titles itself; [heading] names an official site's. */
    private fun topic(t: JSONObject, heading: String?): SearchResult? {
        val text = SearchWire.str(t, "Text") ?: return null
        val title = if (heading != null && text.length <= 40) "$heading: $text" else text.substringBefore(" - ")
        return SearchWire.result(SearchWire.str(t, "FirstURL"), title, text, SearchProvider.DUCKDUCKGO)
    }
}
