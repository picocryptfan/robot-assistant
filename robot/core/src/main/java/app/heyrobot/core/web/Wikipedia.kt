// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.UriText
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.WebStrings
import java.util.Locale

// Pure (org.json). Encyclopedia lookups (design A2.2): search through MediaWiki's core REST API
// (`/w/rest.php/v1/search/page`), then the articles' intros through the Action API's TextExtracts (plain text,
// redirects followed, disambiguation pages marked). Not the RESTBase summary endpoint, which is being deprecated.
// Wikimedia's User-Agent policy wants a contact in the User-Agent, and Robot has none to publish yet
// (WebUserAgent.CONTACT, open question 1): until it does, every request builder here returns null and lookups stay off.

/** An article's intro: [title] as the wiki names it, [url] its page, [text] plain paragraphs. */
data class WikiArticle(
    val title: String,
    val url: String,
    val text: String,
    /** A disambiguation page ("Mercury may refer to…"): a list of other articles, not an answer. */
    val disambiguation: Boolean = false,
    /** The title asked for when the wiki followed a redirect to [title]. */
    val redirectedFrom: String? = null,
) {
    /** As a result the answer can cite. */
    fun asResult(): SearchResult = SearchResult(title, url, SearchWire.cut(text.replace(Regex("\\s+"), " "), SearchWire.SNIPPET_CHARS), SearchProvider.WIKIPEDIA,
        extra = listOf(text))
}

/** What the extracts request answered. */
sealed class WikiExtracts {
    data class Found(val articles: List<WikiArticle>) : WikiExtracts()
    data class Failed(val why: WebFail, val retryAfterS: Int? = null) : WikiExtracts()
}

object WikipediaWire {
    /** The wikis Robot asks, by the question's language. */
    val LANGS = setOf("en", "fr")
    const val LICENCE = ServiceTerms.Wikimedia.LICENCE
    const val LICENCE_URL = "https://creativecommons.org/licenses/by-sa/4.0/"
    /** Search results asked for (design A2.2). */
    const val SEARCH_LIMIT = 3
    /** Intro text kept per article. */
    const val TEXT_CHARS = 6_000

    private val EMAIL = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    /** Wikipedia may be asked: a contact (an email or an https URL) for the User-Agent exists. */
    fun allowed(contact: String?): Boolean {
        val c = contact?.trim() ?: return false
        return EMAIL.matches(c) || WebUrl.parse(c)?.https == true
    }

    private fun wiki(lang: String?): String? = SearchWire.lang(lang)?.takeIf { it in LANGS }

    private fun headers(version: String, contact: String?) = listOf("User-Agent" to WebUserAgent.api(version, contact), "Accept" to "application/json")

    /** An article's address from its title or key ("Stanley Cup" → https://en.wikipedia.org/wiki/Stanley_Cup). */
    fun articleUrl(lang: String, title: String): String =
        "https://$lang.wikipedia.org/wiki/" + UriText.encode(title.trim().replace(' ', '_'), ":@$&+,;=/")

    /**
     * Search: `GET https://{lang}.wikipedia.org/w/rest.php/v1/search/page?q=…&limit=3`, or null when [lang] has no wiki
     * here or there is no [contact] for the User-Agent.
     */
    fun searchRequest(lang: String?, query: String, version: String, contact: String? = WebUserAgent.CONTACT, limit: Int = SEARCH_LIMIT): SearchRequest? {
        val l = wiki(lang) ?: return null
        if (!allowed(contact)) return null
        return SearchRequest("GET", "https://$l.wikipedia.org/w/rest.php/v1/search/page?q=${SearchWire.encode(query)}&limit=${limit.coerceIn(1, 10)}",
            headers(version, contact))
    }

    /**
     * The search's `pages[]` (id, key, title, excerpt with `<span class="searchmatch">`, description). A disambiguation
     * page (its Wikidata description says so) goes after the articles.
     */
    fun parseSearch(lang: String, status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        if (status != 200) return SearchAnswer.Failed(if (status == 429) WebFail.BUSY else WebFail.SERVICE, SearchWire.retryAfter(retryAfter))
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val pages = o.optJSONArray("pages") ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val l = wiki(lang) ?: "en"
        val results = SearchWire.objects(pages).mapNotNull { p ->
            val key = SearchWire.str(p, "key") ?: SearchWire.str(p, "title") ?: return@mapNotNull null
            val description = SearchWire.str(p, "description")
            val excerpt = SearchWire.str(p, "excerpt")
            val snippet = listOfNotNull(description?.let { SearchWire.text(it, 200) }?.takeIf { it.isNotEmpty() }, excerpt).joinToString(". ")
            SearchWire.result(articleUrl(l, key), SearchWire.str(p, "title") ?: key, snippet, SearchProvider.WIKIPEDIA)
                ?.let { it to disambiguation(description) }
        }
        return SearchAnswer.Results(results.sortedBy { it.second }.map { it.first })
    }

    private fun disambiguation(description: String?): Boolean {
        val d = description?.lowercase(Locale.ROOT) ?: return false
        return d.contains("disambiguation") || d.contains("homonymie") || d.contains("topics referred to by the same term")
    }

    /**
     * The intros of [titles] (at most 3): `GET https://{lang}.wikipedia.org/w/api.php?action=query&prop=extracts|pageprops
     * &ppprop=disambiguation&exintro=1&explaintext=1&redirects=1&format=json&formatversion=2&titles=…`, or null as for
     * [searchRequest].
     */
    fun extractsRequest(lang: String?, titles: List<String>, version: String, contact: String? = WebUserAgent.CONTACT): SearchRequest? {
        val l = wiki(lang) ?: return null
        if (!allowed(contact)) return null
        val t = titles.map { it.trim() }.filter { it.isNotEmpty() && !it.contains('|') }.distinct().take(ServiceTerms.Wikimedia.MAX_CONCURRENT)
        if (t.isEmpty()) return null
        val url = "https://$l.wikipedia.org/w/api.php?action=query&prop=extracts%7Cpageprops&ppprop=disambiguation&exintro=1&explaintext=1" +
            "&redirects=1&format=json&formatversion=2&titles=" + UriText.strict(t.joinToString("|"))
        return SearchRequest("GET", url, headers(version, contact))
    }

    /**
     * TextExtracts' answer (formatversion 2): `query.pages[]` (title, extract, missing, pageprops.disambiguation) and
     * `query.redirects[]` (from, to). A missing page is left out; none found is an empty list.
     */
    fun parseExtracts(lang: String, status: Int, body: String?, retryAfter: String? = null): WikiExtracts {
        if (status != 200) return WikiExtracts.Failed(if (status == 429) WebFail.BUSY else WebFail.SERVICE, SearchWire.retryAfter(retryAfter))
        val o = SearchWire.json(body) ?: return WikiExtracts.Failed(WebFail.SERVICE)
        if (o.has("error")) return WikiExtracts.Failed(WebFail.SERVICE)
        val q = o.optJSONObject("query") ?: return WikiExtracts.Found(emptyList())
        val l = wiki(lang) ?: "en"
        val from = SearchWire.objects(q.optJSONArray("redirects")).mapNotNull { r -> SearchWire.str(r, "to")?.let { it to (SearchWire.str(r, "from") ?: "") } }.toMap()
        val articles = SearchWire.objects(q.optJSONArray("pages")).mapNotNull { p ->
            if (p.optBoolean("missing", false) || p.optBoolean("invalid", false)) return@mapNotNull null
            val title = SearchWire.str(p, "title") ?: return@mapNotNull null
            val text = PageText.cleanParagraphs(SearchWire.str(p, "extract") ?: "").let { SearchWire.cut(it, TEXT_CHARS) }
            if (text.isEmpty()) return@mapNotNull null
            WikiArticle(SearchWire.text(title, SearchWire.TITLE_CHARS), articleUrl(l, title), text,
                disambiguation = p.optJSONObject("pageprops")?.has("disambiguation") == true, redirectedFrom = from[title]?.takeIf { it.isNotEmpty() })
        }
        return WikiExtracts.Found(articles)
    }

    /** The card's credit, the reuse page's own form: "From Wikipedia's article “X”, CC BY-SA 4.0" (links: the article and [LICENCE_URL]). */
    fun creditText(title: String): Phrase = WebStrings.creditWikipedia("title" to title, "licence" to LICENCE)

    /** [creditText] in English. */
    fun credit(title: String): String = creditText(title).en()
}
