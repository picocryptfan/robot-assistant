package app.heyrobot.core.web

import app.heyrobot.core.UriText
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// Pure (org.json). What goes to each search service and what comes back (design A2.1, A6.1 SearchWire): one request
// builder and one parser per service, tested against answers written from each service's own documentation examples
// (core/src/test/resources/web). The voice process's clients only send the request and hand the status, the body and
// the Retry-After header back here. Every result becomes one neutral SearchResult; titles and snippets lose their
// markup, character references and invisible characters before anything shows or reads them, and only http(s) URLs
// are kept. A request's toString never shows its URL (a Mojeek key rides in it), the query or a key.

/** Who returned a result: the card credits it ([credit]), the answer's sources name it. */
enum class SearchProvider(val title: String, val credit: String?) {
    SEARXNG("SearXNG", null),
    /** Brave's attribution line is optional; the card shows it while "Powered by Brave" is on. */
    BRAVE("Brave", "Powered by Brave"),
    KAGI("Kagi", null),
    MOJEEK("Mojeek", null),
    /** Text under CC BY-SA 4.0: the card credits the article with a link and the licence (WikipediaWire.credit). */
    WIKIPEDIA("Wikipedia", "Wikipedia text: CC BY-SA 4.0"),
    /** 0.9, Book lookups (off by default): "From Open Library" with its link. */
    OPEN_LIBRARY("Open Library", "From Open Library"),
}

/**
 * One search result, whoever found it: [title] and [snippet] cleaned and cut, [url] an http(s) URL, [provider] the
 * service that returned it (the attribution), [date] as the service gave it, [engines] the engines a SearXNG asked,
 * [extra] page text the service supplied itself (Brave's extra snippets or LLM context, Kagi's extract), paragraphs
 * separated by blank lines.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val provider: SearchProvider,
    val date: String? = null,
    val engines: List<String> = emptyList(),
    val extra: List<String> = emptyList(),
) {
    /** "nhl.com", "en.wikipedia.org": what the card and history show. */
    val domain: String get() = WebUrl.domain(url)
}

/**
 * One HTTP request a search client sends as it is: [headers] may hold a key and [url] may too (Mojeek), so neither is
 * ever logged; [toString] names the method and host only.
 */
class SearchRequest(val method: String, val url: String, val headers: List<Pair<String, String>>, val body: String? = null) {
    val host: String get() = WebUrl.parse(url)?.host ?: "?"

    fun header(name: String): String? = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String = "SearchRequest($method $host)"
}

/** What a search answered: results (possibly none: "No results"), or why it failed. */
sealed class SearchAnswer {
    data class Results(
        val results: List<SearchResult>,
        val suggestions: List<String> = emptyList(),
        val corrections: List<String> = emptyList(),
        /** Short answers the service computed itself (SearXNG answers): web content like the snippets. */
        val answers: List<String> = emptyList(),
        /** SearXNG engines that didn't answer (Test shows them). */
        val unresponsive: List<String> = emptyList(),
    ) : SearchAnswer()

    /** [retryAfterS]: the service's Retry-After, in seconds, when it gave one. */
    data class Failed(val why: WebFail, val retryAfterS: Int? = null) : SearchAnswer()
}

/** The parts every service's parser shares. */
object SearchWire {
    const val TITLE_CHARS = 200
    const val SNIPPET_CHARS = 500
    /** Page text a service supplies, per result. */
    const val EXTRA_CHARS = PageText.MAX_TEXT
    const val MAX_EXTRA = 5

    private val TAG = Regex("<[^<>]{0,500}>")
    private val SPACE = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]+")

    /** A service's markup-bearing text ("Best <strong>Greek</strong>", "Apple&#39;s") as plain text, cut to [max] characters. */
    fun text(raw: String?, max: Int): String {
        if (raw == null) return ""
        val plain = Invisible.strip(Entities.decode(TAG.replace(raw, ""))).replace(SPACE, " ").trim()
        return cut(plain, max)
    }

    /** At most [max] characters, on a word when it can, with "…" when cut; never half of an emoji. */
    fun cut(s: String, max: Int): String {
        if (s.length <= max) return s
        var t = s.take(max - 1)
        if (t.isNotEmpty() && Character.isHighSurrogate(t.last())) t = t.dropLast(1)
        val space = t.lastIndexOf(' ')
        if (space > max / 2) t = t.substring(0, space)
        return t.trimEnd() + "…"
    }

    /** One cleaned result, or null when its URL isn't a web URL or it has neither title nor snippet. */
    fun result(url: String?, title: String?, snippet: String?, provider: SearchProvider, date: String? = null,
        engines: List<String> = emptyList(), extra: List<String> = emptyList()): SearchResult? {
        val u = url?.let(WebUrl::parse) ?: return null
        val t = text(title, TITLE_CHARS)
        val s = text(snippet, SNIPPET_CHARS)
        if (t.isEmpty() && s.isEmpty()) return null
        return SearchResult(t.ifEmpty { WebUrl.domain(u.toString()) }, u.toString(), s, provider, date?.trim()?.takeIf { it.isNotEmpty() }?.take(40),
            engines.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(12), extra.map(PageText::cleanParagraphs).filter { it.isNotEmpty() }.take(MAX_EXTRA))
    }

    /** At most [WebLimits.MAX_RESULTS], each URL once. */
    fun distinct(results: List<SearchResult>): List<SearchResult> {
        val seen = HashSet<String>()
        return results.filter { seen.add(it.url) }.take(WebLimits.MAX_RESULTS)
    }

    /** Retry-After in seconds (0 to an hour); an HTTP date or anything else: null. */
    fun retryAfter(header: String?): Int? = header?.trim()?.takeIf { it.isNotEmpty() && it.length <= 6 && it.all { c -> c in '0'..'9' } }
        ?.toInt()?.coerceAtMost(3600)

    /** [body] as a JSON object, or null. */
    fun json(body: String?): JSONObject? = try { JSONObject(body ?: return null) } catch (_: Exception) { null }

    /** The query as it goes in a URL: trimmed, RFC 3986 unreserved characters only, a space as %20. */
    fun encode(query: String): String = UriText.strict(query.trim())

    /** The usual meaning of an HTTP status when a service says nothing more: null for 200. */
    fun httpFail(status: Int): WebFail? = when (status) {
        200 -> null
        401, 403 -> WebFail.KEY_REFUSED
        402 -> WebFail.OUT_OF_CREDIT
        429 -> WebFail.BUSY
        else -> WebFail.SERVICE
    }

    /** The strings of a JSON array (anything else in it left out). */
    fun strings(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.opt(it) as? String }

    /** The objects of a JSON array. */
    fun objects(a: JSONArray?): List<JSONObject> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optJSONObject(it) }

    /** A JSON string member, or null (missing, null or not a string). */
    fun str(o: JSONObject?, key: String): String? = (o?.opt(key) as? String)?.takeIf { it.isNotEmpty() }

    /** The language a service is asked for: a lower-case ISO 639-1 code ("en", "fr"), or null to leave it out. */
    fun lang(lang: String?): String? = lang?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.matches(Regex("[a-z]{2}")) }
}

/**
 * Your own SearXNG (recommended) or a public one: `GET {base}/search?q=…&format=json&safesearch=…&language=…`. JSON must
 * be listed under `search: formats:` in settings.yml, or SearXNG answers 403 ([WebFail.JSON_OFF] says how to fix it).
 */
object SearxngWire {
    /**
     * The address typed at set-up, ready to search: [url] is scheme, host, port and any path prefix, without a trailing
     * slash. [cleartext]: http://, which the voice process allows only to private addresses (0.6.0's LanRule, on the
     * resolved addresses).
     */
    data class Base(val url: String, val cleartext: Boolean)

    /**
     * [typed] ("192.168.1.20:8888", "https://search.tail1234.ts.net", "https://example.org/searx/") as a base, or null.
     * Without a scheme, http:// for a local name or private address and https:// otherwise. [public] (a public
     * SearXNG): https only, and not a local name or private address. Your own: http:// is refused for a public literal
     * address (it would never be on your home network); a name is decided when it resolves (LanRule).
     */
    fun base(typed: String, public: Boolean): Base? {
        val t = typed.trim()
        if (t.isEmpty() || t.contains('?') || t.contains('#')) return null
        val withScheme = if (t.contains("://")) t else {
            val hostPart = t.substringBefore('/')
            val probe = WebUrl.parse("http://$hostPart/") ?: return null
            if (!public && !PublicAddress.hostPublic(probe)) "http://$t" else "https://$t"
        }
        val u = WebUrl.parse(withScheme) ?: return null
        val prefix = u.path.substringBefore('?').trimEnd('/')
        val url = "${u.scheme}://" + (if (u.kind == HostKind.IPV6) "[${u.host}]" else u.host) + (if (u.port >= 0) ":${u.port}" else "") + prefix
        return when {
            public && (!u.https || !PublicAddress.hostPublic(u)) -> null
            !public && !u.https && u.kind != HostKind.NAME && PublicAddress.hostPublic(u) -> null
            else -> Base(url, !u.https)
        }
    }

    /** The search: the query words, the safe-search level (0, 1, 2) and, when known, the question's language. */
    fun request(base: Base, query: String, lang: String?, safe: SafeSearch, userAgent: String): SearchRequest {
        val level = when (safe) { SafeSearch.OFF -> 0; SafeSearch.MODERATE -> 1; SafeSearch.STRICT -> 2 }
        val url = "${base.url}/search?q=${SearchWire.encode(query)}&format=json&safesearch=$level&pageno=1" +
            (SearchWire.lang(lang)?.let { "&language=$it" } ?: "")
        return SearchRequest("GET", url, listOf("User-Agent" to userAgent, "Accept" to "application/json"))
    }

    /** SearXNG's answer (searx/webutils.py get_json_response): query, results, answers, corrections, infoboxes, suggestions, unresponsive_engines. */
    fun parse(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        when (status) {
            200 -> {}
            403 -> return SearchAnswer.Failed(WebFail.JSON_OFF)
            429 -> return SearchAnswer.Failed(WebFail.BUSY, SearchWire.retryAfter(retryAfter))
            in 500..599 -> return SearchAnswer.Failed(WebFail.NOT_ANSWERING)
            else -> return SearchAnswer.Failed(WebFail.SERVICE)
        }
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val list = o.optJSONArray("results") ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val results = SearchWire.objects(list).mapNotNull { r ->
            val engines = SearchWire.strings(r.optJSONArray("engines")).ifEmpty { listOfNotNull(SearchWire.str(r, "engine")) }
            SearchWire.result(SearchWire.str(r, "url"), SearchWire.str(r, "title"), SearchWire.str(r, "content"), SearchProvider.SEARXNG,
                SearchWire.str(r, "publishedDate") ?: SearchWire.str(r, "pubdate"), engines)
        }
        val answers = (0 until (o.optJSONArray("answers")?.length() ?: 0)).mapNotNull { i ->
            when (val a = o.optJSONArray("answers")?.opt(i)) {
                is String -> a
                is JSONObject -> SearchWire.str(a, "answer") ?: SearchWire.str(a, "content")
                else -> null
            }
        }.map { SearchWire.text(it, SearchWire.SNIPPET_CHARS) }.filter { it.isNotEmpty() }
        val unresponsive = (0 until (o.optJSONArray("unresponsive_engines")?.length() ?: 0)).mapNotNull { i ->
            when (val e = o.optJSONArray("unresponsive_engines")?.opt(i)) {
                is JSONArray -> e.opt(0) as? String
                is String -> e
                else -> null
            }
        }
        return SearchAnswer.Results(SearchWire.distinct(results), clean(o.optJSONArray("suggestions")), clean(o.optJSONArray("corrections")), answers, unresponsive)
    }

    private fun clean(a: JSONArray?): List<String> = SearchWire.strings(a).map { SearchWire.text(it, SearchWire.TITLE_CHARS) }.filter { it.isNotEmpty() }.take(5)

    /** Test's line: "JSON on · 9 results · engines: brave, duckduckgo, wikipedia". */
    fun testLine(answer: SearchAnswer, service: SearchService = SearchService.SEARXNG_HOME): String = when (answer) {
        is SearchAnswer.Failed -> answer.why.text(service)
        is SearchAnswer.Results -> {
            val engines = answer.results.flatMap { it.engines }.distinct().sorted()
            "JSON on · ${answer.results.size} result${if (answer.results.size == 1) "" else "s"}" +
                (if (engines.isNotEmpty()) " · engines: " + engines.joinToString(", ") else "")
        }
    }
}

/**
 * Brave Search API: `GET https://api.search.brave.com/res/v1/web/search?q=…&count=…&safesearch=…&search_lang=…
 * &extra_snippets=true` with `X-Subscription-Token`; and, for "Read pages through the service", `/res/v1/llm/context`.
 */
object BraveWire {
    const val SEARCH = "https://api.search.brave.com/res/v1/web/search"
    const val CONTEXT = "https://api.search.brave.com/res/v1/llm/context"

    private fun headers(key: String, userAgent: String) = listOf("User-Agent" to userAgent, "Accept" to "application/json", "X-Subscription-Token" to key)

    /** Web search: [count] results (at most [ServiceTerms.Brave.MAX_COUNT]), with up to 5 extra snippets each. */
    fun request(key: String, query: String, lang: String?, safe: SafeSearch, userAgent: String, count: Int = WebLimits.MAX_RESULTS,
        extraSnippets: Boolean = true): SearchRequest {
        val url = "$SEARCH?q=${SearchWire.encode(query)}&count=${count.coerceIn(1, ServiceTerms.Brave.MAX_COUNT)}&safesearch=${safe.name.lowercase(Locale.ROOT)}" +
            (SearchWire.lang(lang)?.let { "&search_lang=$it" } ?: "") + (if (extraSnippets) "&extra_snippets=true" else "")
        return SearchRequest("GET", url, headers(key, userAgent))
    }

    /** LLM context: page text for the query, so the sites never see the phone. */
    fun contextRequest(key: String, query: String, userAgent: String): SearchRequest =
        SearchRequest("GET", "$CONTEXT?q=${SearchWire.encode(query)}", headers(key, userAgent))

    /** The web search's answer: `web.results[]` (title, url, description, age or page_age, extra_snippets). */
    fun parse(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        if (status != 200) return SearchAnswer.Failed(fail(status, body), SearchWire.retryAfter(retryAfter))
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        if (o.optString("type") == "ErrorResponse") return SearchAnswer.Failed(fail(422, body))
        val results = SearchWire.objects(o.optJSONObject("web")?.optJSONArray("results")).mapNotNull { r ->
            SearchWire.result(SearchWire.str(r, "url"), SearchWire.str(r, "title"), SearchWire.str(r, "description"), SearchProvider.BRAVE,
                SearchWire.str(r, "page_age") ?: SearchWire.str(r, "age"),
                extra = SearchWire.strings(r.optJSONArray("extra_snippets")).map { SearchWire.text(it, 1_000) })
        }
        val altered = SearchWire.str(o.optJSONObject("query"), "altered")?.let { listOf(SearchWire.text(it, SearchWire.TITLE_CHARS)) } ?: emptyList()
        return SearchAnswer.Results(SearchWire.distinct(results), corrections = altered)
    }

    /** LLM context's answer: `grounding.generic[]` (url, title, snippets) and `sources{url: {title, hostname, age}}`. */
    fun parseContext(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        if (status != 200) return SearchAnswer.Failed(fail(status, body), SearchWire.retryAfter(retryAfter))
        val o = SearchWire.json(body) ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val sources = o.optJSONObject("sources")
        val results = SearchWire.objects(o.optJSONObject("grounding")?.optJSONArray("generic")).mapNotNull { g ->
            val url = SearchWire.str(g, "url") ?: return@mapNotNull null
            val snippets = SearchWire.strings(g.optJSONArray("snippets")).map(PageText::markdownToText).filter { it.isNotBlank() }
            val date = SearchWire.strings(sources?.optJSONObject(url)?.optJSONArray("age")).firstOrNull { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}.*")) }
            SearchWire.result(url, SearchWire.str(g, "title"), snippets.firstOrNull(), SearchProvider.BRAVE, date, extra = snippets)
        }
        return SearchAnswer.Results(SearchWire.distinct(results))
    }

    /**
     * Brave's failure: 401/403 (or 422 with a token error) the key is refused; 402, or a quota code, out of credit; 429
     * rate limited. Its error object (`{"type": "ErrorResponse", "error": {"code", "detail", "status"}}`) is read when
     * there is one (UNVERIFIED shape: the documentation pages read show no error example); the status decides otherwise.
     */
    fun fail(status: Int, body: String?): WebFail {
        val code = SearchWire.str(SearchWire.json(body)?.optJSONObject("error"), "code")?.uppercase(Locale.ROOT) ?: ""
        return when {
            code.contains("QUOTA") || code.contains("CREDIT") || code.contains("PAYMENT") || status == 402 -> WebFail.OUT_OF_CREDIT
            code.contains("TOKEN") || code.contains("SUBSCRIPTION") || status == 401 || status == 403 -> WebFail.KEY_REFUSED
            status == 429 || code.contains("RATE") -> WebFail.BUSY
            else -> WebFail.SERVICE
        }
    }
}

/**
 * Kagi Search API v1, as its OpenAPI reference states it (kagi.com/api/docs, servers `https://kagi.com/api/v1`): `POST
 * /search` with a JSON body (`query`, `workflow`, `limit`, `safe_search`, optionally `extract.count`) and `Authorization:
 * Bearer <key>`. Its help page shows `GET /api/v1/search?q=…` with `Authorization: Bot <key>` instead (the form the
 * design read): which one the server prefers is UNVERIFIED; this follows the reference. The answer lists web results
 * under `data.search[]` (url, title, snippet, time, props); with `extract` the first results' snippets are page text.
 * The older v0 answer (`data` an array, web results `t = 0`) is read too.
 */
object KagiWire {
    const val SEARCH = "https://kagi.com/api/v1/search"

    /** [extract]: results (0 to 10) whose page text Kagi fetches and returns (billed at its Extract rate). */
    fun request(key: String, query: String, safe: SafeSearch, userAgent: String, limit: Int = WebLimits.MAX_RESULTS, extract: Int = 0): SearchRequest {
        val body = JSONObject().put("query", query.trim()).put("workflow", "search").put("limit", limit.coerceIn(1, WebLimits.MAX_RESULTS))
            .put("safe_search", safe != SafeSearch.OFF)
        if (extract > 0) body.put("extract", JSONObject().put("count", extract.coerceIn(1, ServiceTerms.Kagi.MAX_EXTRACT)))
        return SearchRequest("POST", SEARCH, listOf("User-Agent" to userAgent, "Accept" to "application/json",
            "Content-Type" to "application/json", "Authorization" to "Bearer $key"), body.toString())
    }

    fun parse(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        val o = SearchWire.json(body)
        val errors = SearchWire.objects(o?.optJSONArray("error"))
        if (status != 200 || errors.isNotEmpty()) return SearchAnswer.Failed(fail(status, errors), SearchWire.retryAfter(retryAfter))
        if (o == null) return SearchAnswer.Failed(WebFail.SERVICE)
        val results = when (val data = o.opt("data")) {
            is JSONObject -> SearchWire.objects(data.optJSONArray("search")).mapNotNull(::item)
            is JSONArray -> SearchWire.objects(data).filter { it.optInt("t", -1) == 0 }.mapNotNull(::item)
            else -> return SearchAnswer.Failed(WebFail.SERVICE)
        }
        val related = (o.opt("data") as? JSONObject)?.let { d -> SearchWire.objects(d.optJSONArray("related_search")).mapNotNull { SearchWire.str(it, "title") } }
            ?: SearchWire.objects(o.optJSONArray("data")).filter { it.optInt("t", -1) == 1 }.flatMap { SearchWire.strings(it.optJSONArray("list")) }
        return SearchAnswer.Results(SearchWire.distinct(results), suggestions = related.map { SearchWire.text(it, SearchWire.TITLE_CHARS) }.filter { it.isNotEmpty() }.take(5))
    }

    /** A result; a snippet longer than a snippet is page text (an extract), kept as [SearchResult.extra]. */
    private fun item(r: JSONObject): SearchResult? {
        val snippet = SearchWire.str(r, "snippet")
        val page = snippet?.takeIf { it.length > SearchWire.SNIPPET_CHARS * 2 }?.let(PageText::markdownToText)
        return SearchWire.result(SearchWire.str(r, "url"), SearchWire.str(r, "title"), page ?: snippet, SearchProvider.KAGI,
            SearchWire.str(r, "time") ?: SearchWire.str(r, "published"), extra = listOfNotNull(page))
    }

    /** 401 and 403 (an IP the key doesn't allow) refuse the key; 402 or a usage-limit error is out of credit; 429 otherwise busy. */
    fun fail(status: Int, errors: List<JSONObject>): WebFail {
        val said = errors.joinToString(" ") { (SearchWire.str(it, "code") ?: "") + " " + (SearchWire.str(it, "message") ?: SearchWire.str(it, "msg") ?: "") }
            .lowercase(Locale.ROOT)
        return when {
            status == 402 || listOf("balance", "credit", "insufficient", "exhausted", "usage limit", "payment").any { said.contains(it) } -> WebFail.OUT_OF_CREDIT
            status == 401 || status == 403 || said.contains("unauthorized") || said.contains("api key") || said.contains("token") -> WebFail.KEY_REFUSED
            status == 429 -> WebFail.BUSY
            else -> WebFail.SERVICE
        }
    }
}

/**
 * Mojeek: `GET https://www.mojeek.com/search?api_key=…&q=…&fmt=json&t=…&safe=…&lb=…&lbb=100`. The key rides in the URL,
 * so the URL is never logged. Mojeek reports trouble in `response.status` ("ERROR: Daily Limit Reached") with a 200.
 */
object MojeekWire {
    const val SEARCH = "https://www.mojeek.com/search"
    /** Snippet length asked for (Mojeek's default is 160, at most 511). */
    const val SNIPPET = 300

    fun request(key: String, query: String, lang: String?, safe: SafeSearch, userAgent: String, count: Int = WebLimits.MAX_RESULTS): SearchRequest {
        val url = "$SEARCH?api_key=${UriText.strict(key.trim())}&q=${SearchWire.encode(query)}&fmt=json&t=${count.coerceIn(1, ServiceTerms.Mojeek.MAX_RESULTS)}" +
            "&safe=${if (safe == SafeSearch.OFF) 0 else 1}&dlen=$SNIPPET" + (SearchWire.lang(lang)?.let { "&lb=${it.uppercase(Locale.ROOT)}&lbb=100" } ?: "")
        return SearchRequest("GET", url, listOf("User-Agent" to userAgent, "Accept" to "application/json"))
    }

    fun parse(status: Int, body: String?, retryAfter: String? = null): SearchAnswer {
        if (status != 200) return SearchAnswer.Failed(SearchWire.httpFail(status) ?: WebFail.SERVICE, SearchWire.retryAfter(retryAfter))
        val r = SearchWire.json(body)?.optJSONObject("response") ?: return SearchAnswer.Failed(WebFail.SERVICE)
        val st = r.optString("status")
        if (!st.equals("OK", ignoreCase = true)) return SearchAnswer.Failed(fail(st))
        val results = SearchWire.objects(r.optJSONArray("results")).mapNotNull { x ->
            SearchWire.result(SearchWire.str(x, "url"), SearchWire.str(x, "title"), SearchWire.str(x, "desc"), SearchProvider.MOJEEK, SearchWire.str(x, "date"))
        }
        return SearchAnswer.Results(SearchWire.distinct(results))
    }

    /** Mojeek's "ERROR: …" status: a daily limit is out of credit for today, a key error refuses the key. */
    fun fail(status: String): WebFail {
        val s = status.lowercase(Locale.ROOT)
        return when {
            s.contains("daily limit") || s.contains("credit") || s.contains("quota") -> WebFail.OUT_OF_CREDIT
            s.contains("key") || s.contains("auth") || s.contains("forbidden") -> WebFail.KEY_REFUSED
            s.contains("rate") || s.contains("too many") -> WebFail.BUSY
            else -> WebFail.SERVICE
        }
    }
}

/**
 * Each service's limits and terms that shape what Robot does, as read on 2026-10-11 (design A2.1, A2.2; "Sources").
 * Robot keeps results in memory for one answer and saves source domains only (A10.6).
 */
object ServiceTerms {
    /** SearXNG (AGPL-3.0 server software; Robot is only a client). */
    object Searxng {
        /**
         * "Requesting an unset format will return a 403 Forbidden error. Be aware that many public instances have these
         * formats disabled." https://docs.searxng.org/dev/search_api.html
         */
        const val JSON_OFF_STATUS = 403
        /**
         * A public instance's limiter rate-limits and blocks clients that look like bots:
         * https://docs.searxng.org/admin/searx.limiter.html. Its admin "does not know whether their requests are logged"
         * from the user's side: https://docs.searxng.org/own-instance.html
         */
        const val PUBLIC_WARNING = "Run by someone you don't know: they can see and keep your searches."
    }

    /** Brave Search API. */
    object Brave {
        /** "$5 per 1,000 requests", "$5 in free credits every month", a card required: https://brave.com/search/api/ */
        const val USD_PER_1000 = 5.0
        const val FREE_USD_A_MONTH = 5.0
        /** Search plan capacity, "50 queries per second": https://brave.com/search/api/ */
        const val QUERIES_PER_SECOND = 50
        /**
         * `count` at most 20 (default 20), `offset` at most 9, up to 5 `extra_snippets`:
         * https://api-dashboard.search.brave.com/app/documentation/web-search/query
         */
        const val MAX_COUNT = 20
        const val MAX_EXTRA_SNIPPETS = 5
        /**
         * Search queries kept "for a maximum of 90 days" for billing and troubleshooting; zero retention only on enterprise
         * plans: https://api-dashboard.search.brave.com/privacy-policy
         */
        const val RETENTION_DAYS = 90
        /**
         * No storing or caching "other than transient storage", no derivative works of Search Results, no training
         * (terms updated 1 Sep 2026): https://api-dashboard.search.brave.com/terms-of-service. Robot keeps domains only
         * (open question 5).
         */
        const val MAY_STORE_RESULTS = false
        /** The "Powered by Brave" attribution is optional (same terms). */
        const val ATTRIBUTION_REQUIRED = false
    }

    /** Kagi Search API. */
    object Kagi {
        /** Search "$12" per 1,000; Extract "$4" per 1,000 pages; pay per use: https://kagi.com/api/pricing */
        const val USD_PER_1000 = 12.0
        const val EXTRACT_USD_PER_1000 = 4.0
        /** `extract.count` 1 to 10; `timeout` 0.5 to 4 s; `page` 1 to 10: https://kagi.com/api/docs (OpenAPI). */
        const val MAX_EXTRACT = 10
        /**
         * Kagi does "not store API queries against your account or use them to build a profile"; load-balancer logs 7
         * days, sampled errors 90 days: https://kagi.com/privacy. The API Terms (https://kagi.com/privacy/api) were not
         * opened: UNVERIFIED.
         */
        const val LOG_DAYS = 7
    }

    /** Mojeek Search API. */
    object Mojeek {
        /** Startup plan "£2" CPM, 5 queries a second, 100,000 a day, up to 10 results a request: https://www.mojeek.com/services/search/web-search-api/ */
        const val GBP_PER_1000 = 2.0
        const val QUERIES_PER_SECOND = 5
        const val QUERIES_PER_DAY = 100_000
        const val MAX_RESULTS = 10
        /** AI use "allowed with all plans"; on Startup "you may store the results for 1 hour to enable caching" (same page). */
        const val CACHE_MS = 60 * 60_000L
    }

    /** Wikipedia (Wikimedia APIs). */
    object Wikimedia {
        /**
         * Requests a minute: 200 with a compliant User-Agent, 10 without; at most 3 at once; respect Retry-After on 429:
         * https://www.mediawiki.org/wiki/Wikimedia_APIs/Rate_limits
         */
        const val PER_MINUTE = 200
        const val PER_MINUTE_UNIDENTIFIED = 10
        const val MAX_CONCURRENT = 3
        /**
         * A descriptive User-Agent "that includes contact information such as an email or full URL"; "Do not copy a
         * browser's user agent for your bot": https://foundation.wikimedia.org/wiki/Policy:Wikimedia_Foundation_User-Agent_Policy
         */
        const val NEEDS_CONTACT = true
        /** Text CC BY-SA 4.0; credit by a link to the page plus the licence: https://en.wikipedia.org/wiki/Wikipedia:Reusing_Wikipedia_content */
        const val LICENCE = "CC BY-SA 4.0"
    }
}
