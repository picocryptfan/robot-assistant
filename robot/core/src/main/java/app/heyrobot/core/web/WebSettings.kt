package app.heyrobot.core.web

import app.heyrobot.core.AppId
import app.heyrobot.core.LogCode
import org.json.JSONObject

// Pure. Settings › Web search as data (design A5), the first search's tap-only card (A4.2, the 0.4.0 Nearby pattern),
// the User-Agents (A2.2, A7), the time limits, and the voice log's numbers (A10.5). 0.9 keeps these settings in a file
// of their own, files/robot/web.json (the main app writes it, the voice process reads it; settings.json and its schema
// don't change), read with WebSettings.fromJson: no file, or an unreadable one, is Off. VoiceEvent has the ids below.

/** Which service searches for you: the one set-up picked. [code] is what the voice log stores. */
enum class SearchService(val code: Int, val title: String, val label: String, val provider: SearchProvider, val keyId: String?, val setupLine: String) {
    SEARXNG_HOME(1, "Your own SearXNG", "your SearXNG", SearchProvider.SEARXNG, null,
        "Your server searches for you. Nothing goes to a company from Robot."),
    KAGI(2, "Kagi", "Kagi", SearchProvider.KAGI, "search_kagi",
        "Kagi doesn't store API searches against your account. \$12 per 1,000."),
    BRAVE(3, "Brave Search API", "Brave", SearchProvider.BRAVE, "search_brave",
        "Brave keeps your searches for up to 90 days, tied to your API account. \$5 per 1,000 searches; Brave gives \$5 a month (about 1,000 searches). Needs a card on file."),
    MOJEEK(4, "Mojeek", "Mojeek", SearchProvider.MOJEEK, "search_mojeek",
        "Mojeek's own index. About £2 per 1,000; sign-up through Mojeek's sales contact."),
    SEARXNG_PUBLIC(5, "A public SearXNG", "a public SearXNG", SearchProvider.SEARXNG, null,
        "Run by someone you don't know: they can see and keep your searches. Many public servers refuse apps."),
    WIKIPEDIA_ONLY(6, "Wikipedia only", "Wikipedia", SearchProvider.WIKIPEDIA, null,
        "No account. Answers facts from Wikipedia; no general web search."),
    /** 1.0: DuckDuckGo's official, free, keyless Instant Answer API: short answers only, never a list of results (DuckDuckGoWire). */
    DDG_INSTANT(7, "DuckDuckGo Instant Answers", "DuckDuckGo", SearchProvider.DUCKDUCKGO, null,
        "No account. DuckDuckGo's short answers, often from Wikipedia; not a list of results."),
    ;

    /** Needs an API key (kept by 0.6.0's HelperKeys under [keyId], read only in the voice process). */
    val needsKey: Boolean get() = keyId != null

    /** Needs an address typed at set-up. */
    val needsAddress: Boolean get() = this == SEARXNG_HOME || this == SEARXNG_PUBLIC

    /** The card's cost line for one search ([ServiceTerms]): Brave 0.5¢, Kagi 1.2¢, Mojeek 0.2p, the rest free. */
    val cost: String get() = when (this) {
        BRAVE -> "≈ 0.5¢"
        KAGI -> "≈ 1.2¢"
        MOJEEK -> "≈ 0.2p"
        else -> "≈ free"
    }

    /** Can be chosen now: Wikipedia only with a published contact for Wikimedia's User-Agent rule ([WebUserAgent.CONTACT]). */
    fun available(contact: String? = WebUserAgent.CONTACT): Boolean = this != WIKIPEDIA_ONLY || WikipediaWire.allowed(contact)
}

/** Set-up's list (design A4.1, open question 6). */
object WebSetup {
    /**
     * The services in the order set-up lists them: your own SearXNG first when you have a home server (0.6.0's Home
     * helper, or one you typed); otherwise Kagi first (pricier than Brave, but it stores no API queries against your
     * account), then Brave, Mojeek, your own SearXNG for later, then the free ones: DuckDuckGo Instant Answers (1.0), a public
     * SearXNG (never suggested), Wikipedia only.
     */
    fun order(hasHomeServer: Boolean): List<SearchService> =
        if (hasHomeServer) listOf(SearchService.SEARXNG_HOME, SearchService.KAGI, SearchService.BRAVE, SearchService.MOJEEK, SearchService.DDG_INSTANT,
            SearchService.SEARXNG_PUBLIC, SearchService.WIKIPEDIA_ONLY)
        else listOf(SearchService.KAGI, SearchService.BRAVE, SearchService.MOJEEK, SearchService.SEARXNG_HOME, SearchService.DDG_INSTANT, SearchService.SEARXNG_PUBLIC,
            SearchService.WIKIPEDIA_ONLY)

    /** 1.0: DuckDuckGo's Instant Answers are short answers with their source: no result pages are read. */
    fun readsPages(service: SearchService): Boolean = service != SearchService.DDG_INSTANT

    /** The one set-up marks as suggested. */
    fun suggested(hasHomeServer: Boolean): SearchService = order(hasHomeServer).first()

    /** The line under the list (1.0: they open in your browser instead, Settings › Web search's other list). */
    const val NOT_OFFERED = "Google, Bing, Startpage and DuckDuckGo have no full search API that Robot can use: they open in your browser instead."

    /** The consent screen, shown once at set-up and then in Settings. */
    fun consent(service: SearchService): String =
        if (!readsPages(service)) "When you ask, Robot sends your search words to ${service.label}. Nothing else leaves the phone."
        else "When you ask, Robot sends your search words to ${service.label}. To answer, it may open up to 3 of the result pages from " +
            "this phone, like a browser without cookies: those sites see your IP address. Nothing else leaves the phone."
}

/** "Ask before searching" (A5). The level in Settings › Actions and the Safety cards still apply on top. */
enum class WebAsk(val title: String) {
    /** The default: a tap-only card ([WebConsent]) until "Allow always" or "No" with "Don't ask again". */
    FIRST_TIME("Ask the first time"),
    /** A card for every search. */
    ALWAYS("Always ask"),
    /** No consent card (what "Allow always" sets). */
    NEVER("Never ask"),
}

/** "Read result pages". */
enum class ReadPages(val title: String) { WHEN_NEEDED("When needed"), ALWAYS("Always"), NEVER("Never (snippets only)") }

/** "Read pages through": this phone, or the search service when it can (Brave's LLM context, Kagi's extract: costs more, the sites don't see you). */
enum class ReadThrough(val title: String) { PHONE("This phone"), SERVICE("The search service when it can") }

/** "Safe search", passed to the service. */
enum class SafeSearch(val title: String) { OFF("Off"), MODERATE("Moderate"), STRICT("Strict") }

/** "Keep sources in history". */
enum class KeepSources(val title: String) { DOMAINS("Domains only"), LINKS("Full links"), NONE("None") }

/** "Current information": who answers news-like questions first. */
enum class CurrentInfo(val title: String) { ROBOT_FIRST("Robot searches first"), HELPER_FIRST("Ask a helper first") }

/**
 * Settings › Web search, every behaviour a switch with its default (design A5). Off until set up: [on] is false and
 * [service] null, so Gemma's prompt keeps today's "no internet" line. Keys are not here (HelperKeys, voice process).
 * [askAt]: when Settings last chose [ask] (wall clock ms), so the first-use card's lasting answer ([WebConsentAnswer])
 * counts only while it is newer.
 */
data class WebSettings(
    val on: Boolean = false,
    val service: SearchService? = null,
    /** Your own SearXNG's address as typed at set-up (SearxngWire.base checks it). */
    val searxng: String? = null,
    /** A public SearXNG's address: one of set-up's short list (1.0, PublicSearxng) or one you typed. */
    val publicSearxng: String? = null,
    val ask: WebAsk = WebAsk.FIRST_TIME,
    val askAt: Long = 0,
    /**
     * Encyclopedia lookups (Wikipedia): On, the design's default, now that Robot publishes a contact for Wikimedia's
     * User-Agent policy ([WebUserAgent.CONTACT]); Off without one. A search that asks Wikipedia says so on its card and its
     * "Sent to" line (WebAlso).
     */
    val encyclopedia: Boolean = WikipediaWire.allowed(WebUserAgent.CONTACT),
    /** Book lookups (Open Library): off. */
    val books: Boolean = false,
    val readPages: ReadPages = ReadPages.WHEN_NEEDED,
    val readThrough: ReadThrough = ReadThrough.PHONE,
    /** Pages per answer, 1 to 5. */
    val pages: Int = DEFAULT_PAGES,
    val safeSearch: SafeSearch = SafeSearch.MODERATE,
    /** "Add my town to local searches": the Home for weather town's name, never coordinates. */
    val addTown: Boolean = false,
    val currentInfo: CurrentInfo = CurrentInfo.ROBOT_FIRST,
    /** Offered as a client tool only to helpers that can't search themselves. */
    val helpersMayUse: Boolean = true,
    val keepSources: KeepSources = KeepSources.DOMAINS,
    /** A cap a day, said once when reached (protects a key's credit). */
    val dailySearches: Int = DEFAULT_DAILY,
    /** One more row in Safety & privacy › Works on the lock screen. */
    val lockScreen: Boolean = false,
    /** The optional "Powered by Brave" line on Brave's answers. */
    val poweredByBrave: Boolean = true,
    /**
     * 1.0: the engine a search in your browser uses when you name none ("search in my browser for …", and "search the web for
     * …" while Robot's own web search is off). Startpage by default ([BrowserEngine.DEFAULT] says why). Needs no set-up.
     */
    val browserEngine: BrowserEngine = BrowserEngine.DEFAULT,
) {
    /** Searches can happen: on, a service chosen, its address typed when it needs one. */
    val ready: Boolean get() = on && service != null && when (service) {
        SearchService.SEARXNG_HOME -> !searxng.isNullOrBlank()
        SearchService.SEARXNG_PUBLIC -> !publicSearxng.isNullOrBlank()
        SearchService.WIKIPEDIA_ONLY -> WikipediaWire.allowed(WebUserAgent.CONTACT)
        else -> true
    }

    /** Wikipedia lookups happen: switched on and allowed by its User-Agent rule ([contact]). */
    fun wikipedia(contact: String? = WebUserAgent.CONTACT): Boolean = WikipediaWire.allowed(contact) && (encyclopedia || service == SearchService.WIKIPEDIA_ONLY)

    fun toJson(): JSONObject = JSONObject().apply {
        put("on", on)
        service?.let { put("service", it.name) }
        searxng?.let { put("searxng", it) }
        publicSearxng?.let { put("publicSearxng", it) }
        put("ask", ask.name)
        put("askAt", askAt)
        put("encyclopedia", encyclopedia)
        put("books", books)
        put("readPages", readPages.name)
        put("readThrough", readThrough.name)
        put("pages", pages)
        put("safeSearch", safeSearch.name)
        put("addTown", addTown)
        put("currentInfo", currentInfo.name)
        put("helpersMayUse", helpersMayUse)
        put("keepSources", keepSources.name)
        put("dailySearches", dailySearches)
        put("lockScreen", lockScreen)
        put("poweredByBrave", poweredByBrave)
        put("browserEngine", browserEngine.id)
    }

    companion object {
        const val DEFAULT_PAGES = 3
        const val MAX_PAGES = 5
        const val DEFAULT_DAILY = 100
        const val MAX_DAILY = 10_000

        /** The `web` object of settings.json; missing (a file from before web search) or unreadable: the defaults, Off. */
        fun fromJson(o: JSONObject?): WebSettings {
            if (o == null) return WebSettings()
            val d = WebSettings()
            fun str(k: String) = o.optString(k, "").trim().takeIf { it.isNotEmpty() }
            return WebSettings(
                on = o.optBoolean("on", d.on),
                service = enumOr<SearchService>(o, "service", null),
                searxng = str("searxng"),
                publicSearxng = str("publicSearxng"),
                ask = enumOr(o, "ask", d.ask)!!,
                askAt = o.optLong("askAt", 0),
                encyclopedia = o.optBoolean("encyclopedia", d.encyclopedia),
                books = o.optBoolean("books", d.books),
                readPages = enumOr(o, "readPages", d.readPages)!!,
                readThrough = enumOr(o, "readThrough", d.readThrough)!!,
                pages = o.optInt("pages", d.pages).coerceIn(1, MAX_PAGES),
                safeSearch = enumOr(o, "safeSearch", d.safeSearch)!!,
                addTown = o.optBoolean("addTown", d.addTown),
                currentInfo = enumOr(o, "currentInfo", d.currentInfo)!!,
                helpersMayUse = o.optBoolean("helpersMayUse", d.helpersMayUse),
                keepSources = enumOr(o, "keepSources", d.keepSources)!!,
                dailySearches = o.optInt("dailySearches", d.dailySearches).coerceIn(1, MAX_DAILY),
                lockScreen = o.optBoolean("lockScreen", d.lockScreen),
                poweredByBrave = o.optBoolean("poweredByBrave", d.poweredByBrave),
                browserEngine = BrowserEngine.entries.firstOrNull { it.id == o.optString("browserEngine") } ?: d.browserEngine,
            ).let { if (it.service == null) it.copy(on = false) else it }
        }

        private inline fun <reified E : Enum<E>> enumOr(o: JSONObject, key: String, default: E?): E? =
            enumValues<E>().firstOrNull { it.name == o.optString(key) } ?: default
    }
}

/** The first search's card answer a search carries (like NearestConsent): what it keeps when it runs. */
enum class WebConsentChoice {
    /** "Allow always": Ask before searching becomes Never ask, and it searches now. */
    ALWAYS,
    /** "Just this time": searches now, the card again next time. */
    ONCE,
    /** "No": no search now (Gemma answers without it), the card again next time. */
    NO,
    /** "No" with "Don't ask again" ticked: web search turns Off, no search now. */
    NEVER,
}

/**
 * The card's lasting answer, [at] wall clock ms: Ask before searching Never ask ("Allow always"), or web search Off
 * ([off]: "No" with "Don't ask again"). The voice process keeps it in `web_answer.json` (settings.json has one writer,
 * the main app); it counts while it is newer than what Settings last chose ([WebSettings.askAt]).
 */
data class WebConsentAnswer(val ask: WebAsk, val off: Boolean, val at: Long) {
    fun toJson(): String = JSONObject().put("ask", ask.name).put("off", off).put("at", at).toString()

    companion object {
        const val FILE = "web_answer.json"

        fun of(choice: WebConsentChoice, at: Long): WebConsentAnswer? = when (choice) {
            WebConsentChoice.ALWAYS -> WebConsentAnswer(WebAsk.NEVER, off = false, at = at)
            WebConsentChoice.NEVER -> WebConsentAnswer(WebAsk.FIRST_TIME, off = true, at = at)
            else -> null
        }

        fun fromJson(text: String?): WebConsentAnswer? = try {
            val o = JSONObject(text ?: return null)
            val ask = WebAsk.entries.firstOrNull { it.name == o.optString("ask") } ?: return null
            WebConsentAnswer(ask, o.optBoolean("off", false), o.optLong("at", 0))
        } catch (_: Exception) {
            null
        }
    }
}

/** The card a search shows before anything leaves the phone, or none. */
data class WebConsentCard(
    /** "Search the web for “…”". */
    val title: String,
    /** Exactly what would leave the phone, and to whom. */
    val note: String,
    /** The buttons, in order. */
    val buttons: List<String>,
    /** The tick box ("Don't ask again"), when the answer can be kept. */
    val remember: String?,
    /** Answered by a tap only (AskWhy.ONLINE's CardAnswer.TAP_ONLINE words): a spoken yes can't. */
    val tapOnly: Boolean,
)

/** "Ask before searching" made concrete: whether this search shows a card first, and which. */
object WebConsent {
    const val ALLOW_ALWAYS = "Allow always"
    const val JUST_THIS_TIME = "Just this time"
    const val NO = "No"
    const val DONT_ASK = "Don't ask again"
    const val SEARCH = "Search"
    const val CANCEL = "Cancel"

    /** Ask before searching now: Settings' choice, or the card's lasting answer when that is newer. */
    fun ask(settings: WebSettings, answer: WebConsentAnswer?): WebAsk =
        if (answer != null && answer.at > settings.askAt) answer.ask else settings.ask

    /** Web search is on now: Settings say so and no newer "Don't ask again" on a No turned it off. */
    fun on(settings: WebSettings, answer: WebConsentAnswer?): Boolean =
        settings.ready && !(answer != null && answer.at > settings.askAt && answer.off)

    /**
     * The first-use card's line (A4.2): the query, the service and anyone else asked ([also]: WebAlso, 1.0), and that pages
     * may be opened from this phone ([pages]).
     */
    fun note(service: SearchService, pages: Int, also: List<String> = emptyList()): String =
        "Robot would send these words to ${WebAlso.to(service, also)}." +
            if (pages > 0) " It may also open up to $pages result page${if (pages == 1) "" else "s"} from this phone." else ""

    /**
     * The card for one search of [query] with [service], or null when it may go without one. Ask the first time: a card
     * that always shows, answered by a tap, with [ALLOW_ALWAYS], [JUST_THIS_TIME], [NO] and [DONT_ASK]. Always ask: a
     * plain card ([SEARCH], [CANCEL]). Never ask: none (the gate's own rules still apply).
     */
    fun card(ask: WebAsk, service: SearchService, query: String, pages: Int, also: List<String> = emptyList()): WebConsentCard? = when (ask) {
        WebAsk.NEVER -> null
        WebAsk.FIRST_TIME -> WebConsentCard("Search the web for “$query”", note(service, pages, also), listOf(ALLOW_ALWAYS, JUST_THIS_TIME, NO), DONT_ASK, tapOnly = true)
        WebAsk.ALWAYS -> WebConsentCard("Search the web for “$query”", note(service, pages, also), listOf(SEARCH, CANCEL), null, tapOnly = false)
    }
}

/** The User-Agents (A2.2, A7; open questions 1 and 2). */
object WebUserAgent {
    /**
     * The contact (an email or a URL) Robot publishes in its API User-Agent, the same for every Robot user (open
     * question 1). 1.0: Robot's published address, so Wikipedia (whose User-Agent policy requires one) is offered: the
     * "Wikipedia only" service and Encyclopedia lookups. It names Robot's project, never you or your phone.
     */
    val CONTACT: String? = "hey@robot-app.ai"

    /**
     * The honest User-Agent for the search APIs and Wikipedia: "Robot/0.7.0 (ai.robot_app; Android voice assistant)",
     * with "; <contact>" when there is one; Robot's app ID ([AppId], app.heyrobot before 1.0). Nothing about the phone or
     * the person.
     */
    fun api(version: String, contact: String? = CONTACT): String =
        "Robot/${version.filter { it.isLetterOrDigit() || it == '.' }.take(20).ifEmpty { "0" }} (${AppId.PACKAGE}; Android voice assistant" +
            (contact?.trim()?.takeIf { it.isNotEmpty() }?.let { "; $it" } ?: "") + ")"

    /** A generic mobile User-Agent for page fetches (the design's proposal for open question 2). */
    const val PAGE = "Mozilla/5.0 (Linux; Android 17) Robot"

    /**
     * Every header a page fetch sends: the generic User-Agent, HTML or text only, the question's language only. No
     * cookies, no Referer, no cache headers, no compression the fetcher didn't ask for itself.
     */
    fun pageHeaders(lang: String?): List<Pair<String, String>> = listOfNotNull(
        "User-Agent" to PAGE,
        "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.8",
        lang?.takeIf { it.matches(Regex("[a-z]{2,3}")) }?.let { "Accept-Language" to it },
    )
}

/** Time and size limits (A6.2, A7). */
object WebLimits {
    /** The search request. */
    const val SEARCH_MS = 8_000L
    /** The whole search: "The search took too long". */
    const val TOTAL_MS = 15_000L
    const val CONNECT_MS = 4_000L
    /** One page. */
    const val PAGE_MS = 6_000L
    /** All pages together (one Abort deadline). */
    const val PAGES_MS = 10_000L
    /** Results kept from a search. */
    const val MAX_RESULTS = 10
    /** Decompressed bytes read from a page at most. */
    const val PAGE_BYTES = 1 shl 20
}

/**
 * The voice log's web search events (A10.5): VoiceEvent's WEB_SEARCH to WEB_FAILED, 160–163 of web search's 160–169, and
 * 1.0's BROWSER_SEARCH, 164.
 * Codes and numbers only: never the query, a URL, a domain, a title or page text.
 */
enum class WebLogEvent(val reservedId: Int) {
    /** a = service code ([SearchService.code]), b = results, c = ms. */
    WEB_SEARCH(160),
    /** a = pages read, b = pages skipped, c = ms; the first skip's [PageSkip.code] in the spare slot. */
    WEB_PAGES(161),
    /** a = input tokens ÷ 10, b = output tokens, c = first-token ms. */
    WEB_ANSWER(162),
    /** a = [WebFail.id]. */
    WEB_FAILED(163),
    /** 1.0: a = [BrowserEngine.code], b = 1 your words or 2 the model's, c = 1 when Robot's own web search was off. Never the words. */
    BROWSER_SEARCH(164),
}

/** One web search log line: numbers only, by construction. */
data class WebLogLine(val event: WebLogEvent, val a: Int, val b: Int = 0, val c: Int = 0, val spare: Int = 0) {
    /** The logcat form ("web_search svc=1 n=8 ms=950"). */
    fun logcat(): String = when (event) {
        WebLogEvent.WEB_SEARCH -> "web_search svc=$a n=$b ms=$c"
        WebLogEvent.WEB_PAGES -> "web_pages ok=$a skipped=$b ms=$c why=$spare"
        WebLogEvent.WEB_ANSWER -> "web_answer in=${a * 10} out=$b first_ms=$c"
        WebLogEvent.WEB_FAILED -> "web_failed why=$a"
        WebLogEvent.BROWSER_SEARCH -> "browser_search engine=$a by=$b fallback=$c"
    }

    companion object {
        fun search(service: SearchService, results: Int, ms: Long) = WebLogLine(WebLogEvent.WEB_SEARCH, service.code, results, ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        fun pages(read: Int, skipped: List<PageSkip>, ms: Long) =
            WebLogLine(WebLogEvent.WEB_PAGES, read, skipped.size, ms.coerceIn(0, Int.MAX_VALUE.toLong()).toInt(), skipped.firstOrNull()?.code ?: 0)
        fun answer(inputTokens: Int, outputTokens: Int, firstTokenMs: Long) =
            WebLogLine(WebLogEvent.WEB_ANSWER, inputTokens / 10, outputTokens, firstTokenMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
        fun failed(why: WebFail) = WebLogLine(WebLogEvent.WEB_FAILED, why.id)
        fun browser(engine: BrowserEngine, own: Boolean, fallback: Boolean) = WebLogLine(WebLogEvent.BROWSER_SEARCH, engine.code, if (own) 1 else 2, if (fallback) 1 else 0)
    }
}

/** Why a search failed, in fixed words the card and Robot's voice use ([text]) and a code the log keeps. */
enum class WebFail(override val id: Int, override val title: String) : LogCode {
    NO_INTERNET(1, "no internet"),
    NO_NETWORK_PERMISSION(2, "no network permission"),
    NOT_HOME(3, "not on the home network"),
    NOT_ANSWERING(4, "not answering"),
    JSON_OFF(5, "json off"),
    KEY_MISSING(6, "key missing"),
    KEY_REFUSED(7, "key refused"),
    OUT_OF_CREDIT(8, "out of credit"),
    BUSY(9, "rate limited"),
    SERVICE(10, "service error"),
    TIMEOUT(11, "took too long"),
    NO_RESULTS(12, "no results"),
    STOPPED(13, "stopped"),
    DAILY_CAP(14, "daily cap"),
    QUERY_REFUSED(15, "query refused"),
    OFF(16, "off"),
    ;

    /** What Robot says and the card shows, naming [service] where it matters. */
    fun text(service: SearchService?): String {
        val name = service?.label ?: "the search service"
        val cap = name.replaceFirstChar { it.uppercase() }
        return when (this) {
            NO_INTERNET -> "No internet: Robot couldn't search the web"
            NO_NETWORK_PERMISSION -> "Robot isn't allowed to use the network"
            NOT_HOME -> "Robot can't reach $name off your home network"
            NOT_ANSWERING -> "$cap isn't answering"
            JSON_OFF -> "$cap answered 403: add json under search: formats: in settings.yml"
            KEY_MISSING -> "The key for $name is missing"
            KEY_REFUSED -> "$cap refused the key"
            OUT_OF_CREDIT -> "The key for $name is out of credit"
            BUSY -> "$cap is busy just now"
            SERVICE -> "$cap didn't answer properly"
            TIMEOUT -> "The search took too long"
            NO_RESULTS -> if (service == SearchService.DDG_INSTANT) "DuckDuckGo has no short answer for that" else "No results"
            STOPPED -> "Stopped"
            DAILY_CAP -> "That's today's limit of web searches"
            QUERY_REFUSED -> "That search looked like it contained personal details, so Robot didn't send it."
            OFF -> "Web search is off"
        }
    }
}

/**
 * 1.0 audit (network-1): who gets a search's words besides the chosen service, decided once for the consent card, the run
 * and the card's "Sent to" line: Wikipedia (Encyclopedia lookups, for a who/what/where question) and Open Library (Book
 * lookups, for a question about books).
 */
object WebAlso {
    const val WIKIPEDIA = "Wikipedia"
    const val OPEN_LIBRARY = "Open Library"

    /** "Who is…", "what is…", "where is…", "qui est…": a question Wikipedia's intro answers. */
    fun encyclopedic(question: String): Boolean =
        Regex("^\\s*(?:who|what|where|qui|qu'est-ce|quel|quelle|ou|où)\\b", RegexOption.IGNORE_CASE).containsMatchIn(question)

    fun wikipedia(s: WebSettings, service: SearchService, question: String): Boolean =
        service != SearchService.WIKIPEDIA_ONLY && s.wikipedia() && encyclopedic(question)

    fun books(s: WebSettings, question: String): Boolean = s.books && OpenLibraryWire.wanted(question)

    /** The others asked for [question] ([question]: your words, or the query when there are none). */
    fun names(s: WebSettings, service: SearchService, question: String): List<String> = listOfNotNull(
        WIKIPEDIA.takeIf { wikipedia(s, service, question) },
        OPEN_LIBRARY.takeIf { books(s, question) },
    )

    /** "your SearXNG", "your SearXNG and Open Library", "Brave Search, Wikipedia and Open Library". */
    fun to(service: SearchService, also: List<String>): String {
        val all = listOf(service.label) + also
        return if (all.size == 1) all[0] else all.dropLast(1).joinToString(", ") + " and " + all.last()
    }
}
