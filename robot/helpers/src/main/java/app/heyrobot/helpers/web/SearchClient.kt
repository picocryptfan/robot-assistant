package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.web.BraveWire
import app.heyrobot.core.web.DuckDuckGoWire
import app.heyrobot.core.web.KagiWire
import app.heyrobot.core.web.MojeekWire
import app.heyrobot.core.web.OpenLibraryWire
import app.heyrobot.core.web.SearchAnswer
import app.heyrobot.core.web.SearchRequest
import app.heyrobot.core.web.SearchResult
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.SearchWire
import app.heyrobot.core.web.SearxngWire
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.web.WebUserAgent
import app.heyrobot.core.web.WikiExtracts
import app.heyrobot.core.web.WikipediaWire

// VOICE PROCESS ONLY, :helpers module (0.9 web search, design A6.1 SearchClient). One search with the service Settings ›
// Web search chose: your own SearXNG (HOME: LanRule on the LAN, Tailscale HTTPS anywhere), a public SearXNG, Brave,
// Kagi or Mojeek (CLOUD, https only, your key), Wikipedia (search, then the articles' intros), DuckDuckGo's Instant Answers
// (1.0, keyless) and Open Library. Core's
// wires build each request and read each answer; [transport] sends it. Only the query words, the language, the
// safe-search level, a result count and the key (for the paid services) leave the phone. Nothing here logs.

class SearchClient(
    private val settings: WebSettings,
    /** The service's API key (Brave, Kagi, Mojeek), decrypted just now in the voice process; null for the others. */
    private val key: String?,
    /** Robot's version, for the honest API User-Agent. */
    private val version: String,
    private val transport: WebSender,
    private val abort: Abort,
) {
    private val ua get() = WebUserAgent.api(version)

    private fun send(req: SearchRequest, route: WebTransport.Route, parse: (Reply) -> SearchAnswer): SearchAnswer = try {
        parse(transport.send(req, route, abort))
    } catch (e: WebFailure) {
        SearchAnswer.Failed(e.why)
    }

    /**
     * The search for [query] in [lang]: [readThrough] (Read pages through the service, when the service can) asks Brave for
     * its LLM context or Kagi for page text with the results ([pages] of them), so the sites never see the phone.
     */
    fun search(query: String, lang: String?, readThrough: Boolean = false, pages: Int = 0): SearchAnswer {
        val service = settings.service ?: return SearchAnswer.Failed(WebFail.OFF)
        if (service.needsKey && key.isNullOrBlank()) return SearchAnswer.Failed(WebFail.KEY_MISSING)
        return when (service) {
            SearchService.SEARXNG_HOME -> {
                val base = settings.searxng?.let { SearxngWire.base(it, public = false) } ?: return SearchAnswer.Failed(WebFail.NOT_HOME)
                send(SearxngWire.request(base, query, lang, settings.safeSearch, ua), WebTransport.Route.HOME) { SearxngWire.parse(it.status, it.body, it.retryAfter) }
            }
            SearchService.SEARXNG_PUBLIC -> {
                val base = settings.publicSearxng?.let { SearxngWire.base(it, public = true) } ?: return SearchAnswer.Failed(WebFail.SERVICE)
                send(SearxngWire.request(base, query, lang, settings.safeSearch, ua), WebTransport.Route.CLOUD) { SearxngWire.parse(it.status, it.body, it.retryAfter) }
            }
            SearchService.BRAVE -> if (readThrough && pages > 0) {
                send(BraveWire.contextRequest(key!!, query, ua), WebTransport.Route.CLOUD) { BraveWire.parseContext(it.status, it.body, it.retryAfter) }
            } else {
                // Extra snippets come in the search's price: asked for only when pages are read through the service.
                send(BraveWire.request(key!!, query, lang, settings.safeSearch, ua, extraSnippets = readThrough), WebTransport.Route.CLOUD) {
                    BraveWire.parse(it.status, it.body, it.retryAfter)
                }
            }
            SearchService.KAGI -> send(KagiWire.request(key!!, query, settings.safeSearch, ua, extract = if (readThrough) pages else 0), WebTransport.Route.CLOUD) {
                KagiWire.parse(it.status, it.body, it.retryAfter)
            }
            SearchService.MOJEEK -> send(MojeekWire.request(key!!, query, lang, settings.safeSearch, ua), WebTransport.Route.CLOUD) {
                MojeekWire.parse(it.status, it.body, it.retryAfter)
            }
            SearchService.WIKIPEDIA_ONLY -> wikipedia(query, lang)?.let { SearchAnswer.Results(it) } ?: SearchAnswer.Failed(WebFail.OFF)
            // 1.0: DuckDuckGo's free Instant Answer API (keyless): short answers with their sources, never a list of results.
            SearchService.DDG_INSTANT -> send(DuckDuckGoWire.request(query, ua), WebTransport.Route.CLOUD) { DuckDuckGoWire.parse(it.status, it.body, query, it.retryAfter) }
        }
    }

    /**
     * Wikipedia in [lang] (en or fr): the search, then the intros of its first two articles that aren't disambiguation
     * pages, as results whose text is the intro. Null when Wikipedia may not be asked (no contact for its User-Agent
     * rule yet, or a wiki Robot doesn't ask); empty when nothing was found or it didn't answer.
     */
    fun wikipedia(query: String, lang: String?, contact: String? = WebUserAgent.CONTACT): List<SearchResult>? {
        val l = SearchWire.lang(lang)?.takeIf { it in WikipediaWire.LANGS } ?: "en"
        val req = WikipediaWire.searchRequest(l, query, version, contact) ?: return null
        val found = send(req, WebTransport.Route.CLOUD) { WikipediaWire.parseSearch(l, it.status, it.body, it.retryAfter) }
        val titles = (found as? SearchAnswer.Results)?.results?.map { it.title }?.take(2).orEmpty()
        if (titles.isEmpty()) return emptyList()
        val ex = WikipediaWire.extractsRequest(l, titles, version, contact) ?: return emptyList()
        val articles = try {
            transport.send(ex, WebTransport.Route.CLOUD, abort).let { WikipediaWire.parseExtracts(l, it.status, it.body, it.retryAfter) }
        } catch (_: WebFailure) {
            return emptyList()
        }
        return (articles as? WikiExtracts.Found)?.articles?.filter { !it.disambiguation }?.map { it.asResult() }.orEmpty()
    }

    /** Book lookups: Open Library's works for [query] (keyless). */
    fun books(query: String): SearchAnswer =
        send(OpenLibraryWire.request(query, ua), WebTransport.Route.CLOUD) { OpenLibraryWire.parse(it.status, it.body, it.retryAfter) }

    /** Settings' Test: the fixed query "test", once, and what came back in words ("JSON on · 9 results · engines: …"). */
    fun test(): Pair<Boolean, String> {
        val service = settings.service ?: return false to WebFail.OFF.text(null)
        return when (val a = search(TEST_QUERY, null)) {
            is SearchAnswer.Failed -> false to a.why.text(service)
            is SearchAnswer.Results -> if (service == SearchService.SEARXNG_HOME || service == SearchService.SEARXNG_PUBLIC) {
                true to SearxngWire.testLine(a, service)
            } else {
                true to "${service.title} answered: ${a.results.size} result${if (a.results.size == 1) "" else "s"}"
            }
        }
    }

    companion object {
        const val TEST_QUERY = "test"
    }
}
