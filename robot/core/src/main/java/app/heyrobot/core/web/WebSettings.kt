// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.AppId
import app.heyrobot.core.LogCode
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.WebStrings
import org.json.JSONObject

// Pure. Settings › Web search as data (design A5), the first search's tap-only card (A4.2, the 0.4.0 Nearby pattern),
// the User-Agents (A2.2, A7), the time limits, and the voice log's numbers (A10.5). 0.9 keeps these settings in a file
// of their own, files/robot/web.json (the main app writes it, the voice process reads it; settings.json and its schema
// don't change), read with WebSettings.fromJson: no file, or an unreadable one, is Off. VoiceEvent has the ids below.

/**
 * Which service searches for you: the one set-up picked. [code] is what the voice log stores. 1.1: its words are core's catalog
 * (WebStrings): [titleText] in a list, [labelText] inside a sentence ("to your SearXNG"), [capText] at a sentence's start;
 * [title] and [label] are their English, for the voice log and the callers still on English.
 */
enum class SearchService(val code: Int, val titleText: Phrase, val labelText: Phrase, val capText: Phrase, val provider: SearchProvider, val keyId: String?) {
    SEARXNG_HOME(1, WebStrings.homeTitle(), WebStrings.homeLabel(), WebStrings.homeCap(), SearchProvider.SEARXNG, null),
    KAGI(2, Phrase.plain("Kagi"), Phrase.plain("Kagi"), Phrase.plain("Kagi"), SearchProvider.KAGI, "search_kagi"),
    BRAVE(3, WebStrings.braveTitle(), Phrase.plain("Brave"), Phrase.plain("Brave"), SearchProvider.BRAVE, "search_brave"),
    MOJEEK(4, Phrase.plain("Mojeek"), Phrase.plain("Mojeek"), Phrase.plain("Mojeek"), SearchProvider.MOJEEK, "search_mojeek"),
    SEARXNG_PUBLIC(5, WebStrings.publicTitle(), WebStrings.publicLabel(), WebStrings.publicTitle(), SearchProvider.SEARXNG, null),
    WIKIPEDIA_ONLY(6, WebStrings.wikipediaOnly(), WebStrings.wikipedia(), WebStrings.wikipedia(), SearchProvider.WIKIPEDIA, null),
    /** 1.0: DuckDuckGo's official, free, keyless Instant Answer API: short answers only, never a list of results (DuckDuckGoWire). */
    DDG_INSTANT(7, WebStrings.ddgTitle(), Phrase.plain("DuckDuckGo"), Phrase.plain("DuckDuckGo"), SearchProvider.DUCKDUCKGO, null),
    ;

    /** [titleText] in English: the voice log's name ("Your own SearXNG"). */
    val title: String get() = titleText.en()

    /** [labelText] in English ("your SearXNG"). */
    val label: String get() = labelText.en()

    /** Needs an API key (kept by 0.6.0's HelperKeys under [keyId], read only in the voice process). */
    val needsKey: Boolean get() = keyId != null

    /** Needs an address typed at set-up. */
    val needsAddress: Boolean get() = this == SEARXNG_HOME || this == SEARXNG_PUBLIC

    /** The card's cost line for one search ([ServiceTerms]): Brave 0.5¢, Kagi 1.2¢, Mojeek 0.2p, the rest free. */
    val costText: Phrase get() = when (this) {
        BRAVE -> WebStrings.costCents("n" to Arg.Num(0.5))
        KAGI -> WebStrings.costCents("n" to Arg.Num(1.2))
        MOJEEK -> WebStrings.costPence("n" to Arg.Num(0.2))
        else -> WebStrings.costFree()
    }

    /** [costText] in English ("≈ 0.5¢"). */
    val cost: String get() = costText.en()

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
    val notOffered: Phrase get() = WebStrings.notOffered()

    /** [notOffered] in English. */
    val NOT_OFFERED: String get() = notOffered.en()

    /**
     * The consent screen, shown once at set-up and then in Settings, as PRIVACY.md says it: who gets your words once [service]
     * is on with [s] (the service; Wikipedia for a who, what or where question while Encyclopedia lookups are on, unless it
     * is the service; Open Library for a question about books while Book lookups are: WebAlso's rules), and how many result
     * pages this phone may open for one answer (WebGate.pagesFromPhone: Settings' number, none for DuckDuckGo's short
     * answers or pages read through the service).
     */
    fun consentText(service: SearchService, s: WebSettings): Phrase {
        val w = s.copy(service = service)
        val pages = WebGate.pagesFromPhone(w)
        return Phrase.Join(listOfNotNull(
            WebStrings.consentSends("service" to service.labelText),
            WebStrings.consentWikipedia().takeIf { service != SearchService.WIKIPEDIA_ONLY && w.wikipedia() },
            WebStrings.consentBooks().takeIf { w.books },
            if (pages > 0) WebStrings.consentPages(pages) else null,
            WebStrings.consentNothingElse(),
        ))
    }

    /** [consentText] in English. */
    fun consent(service: SearchService, s: WebSettings): String = consentText(service, s).en()
}

/** "Ask before searching" (A5). The level in Settings › Actions and the Safety cards still apply on top. */
enum class WebAsk(val label: Msg) {
    /** The default: a tap-only card ([WebConsent]) until "Allow always" or "No" with "Don't ask again". */
    FIRST_TIME(WebStrings.askFirstTime),
    /** A card for every search. */
    ALWAYS(WebStrings.askAlways),
    /** No consent card (what "Allow always" sets). */
    NEVER(WebStrings.askNever),
    ;

    /** [label] in English. */
    val title: String get() = label().en()
}

/** "Read result pages". */
enum class ReadPages(val label: Msg) {
    WHEN_NEEDED(WebStrings.readWhenNeeded), ALWAYS(WebStrings.readAlways), NEVER(WebStrings.readNever);

    /** [label] in English. */
    val title: String get() = label().en()
}

/** "Read pages through": this phone, or the search service when it can (Brave's LLM context, Kagi's extract: costs more, the sites don't see you). */
enum class ReadThrough(val label: Msg) {
    PHONE(WebStrings.throughPhone), SERVICE(WebStrings.throughService);

    /** [label] in English. */
    val title: String get() = label().en()
}

/** "Safe search", passed to the service. */
enum class SafeSearch(val label: Msg) {
    OFF(WebStrings.safeOff), MODERATE(WebStrings.safeModerate), STRICT(WebStrings.safeStrict);

    /** [label] in English. */
    val title: String get() = label().en()
}

/** "Keep sources in history". */
enum class KeepSources(val label: Msg) {
    DOMAINS(WebStrings.keepDomains), LINKS(WebStrings.keepLinks), NONE(WebStrings.keepNone);

    /** [label] in English. */
    val title: String get() = label().en()
}

/** "Current information": who answers news-like questions first. */
enum class CurrentInfo(val label: Msg) {
    ROBOT_FIRST(WebStrings.robotFirst), HELPER_FIRST(WebStrings.helperFirst);

    /** [label] in English. */
    val title: String get() = label().en()
}

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

/**
 * The card a search shows before anything leaves the phone, or none. 1.1: its words are phrases ([titleText], [noteText],
 * [buttonTexts], [rememberText]); [title], [note], [buttons] and [remember] are their English (the gate's Prepared holds
 * English words until the turn's cards take phrases).
 */
data class WebConsentCard(
    /** "Search the web for “…”". */
    val titleText: Phrase,
    /** Exactly what would leave the phone, and to whom. */
    val noteText: Phrase,
    /** The buttons, in order. */
    val buttonTexts: List<Phrase>,
    /** The tick box ("Don't ask again"), when the answer can be kept. */
    val rememberText: Phrase?,
    /** Answered by a tap only (AskWhy.ONLINE's CardAnswer.TAP_ONLINE words): a spoken yes can't. */
    val tapOnly: Boolean,
) {
    val title: String get() = titleText.en()
    val note: String get() = noteText.en()
    val buttons: List<String> get() = buttonTexts.map { it.en() }
    val remember: String? get() = rememberText?.en()
}

/** "Ask before searching" made concrete: whether this search shows a card first, and which. */
object WebConsent {
    // The buttons' words, and their English (a Choice's label is English until the turn's cards take phrases).
    val allowAlways: Phrase get() = UiStrings.allowAlways()
    val justThisTime: Phrase get() = WebStrings.justThisTime()
    val no: Phrase get() = WebStrings.no()
    val dontAsk: Phrase get() = WebStrings.dontAsk()
    val search: Phrase get() = WebStrings.search()
    val cancel: Phrase get() = UiStrings.cancel()
    val ALLOW_ALWAYS: String get() = allowAlways.en()
    val JUST_THIS_TIME: String get() = justThisTime.en()
    val NO: String get() = no.en()
    val DONT_ASK: String get() = dontAsk.en()
    val SEARCH: String get() = search.en()
    val CANCEL: String get() = cancel.en()

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
    fun noteText(service: SearchService, pages: Int, also: List<String> = emptyList()): Phrase =
        if (pages > 0) WebStrings.wouldSendPages(pages, "to" to WebAlso.toText(service, also)) else WebStrings.wouldSend("to" to WebAlso.toText(service, also))

    /** [noteText] in English. */
    fun note(service: SearchService, pages: Int, also: List<String> = emptyList()): String = noteText(service, pages, also).en()

    /**
     * The card for one search of [query] with [service], or null when it may go without one. Ask the first time: a card
     * that always shows, answered by a tap, with [ALLOW_ALWAYS], [JUST_THIS_TIME], [NO] and [DONT_ASK]. Always ask: a
     * plain card ([SEARCH], [CANCEL]). Never ask: none (the gate's own rules still apply).
     */
    fun card(ask: WebAsk, service: SearchService, query: String, pages: Int, also: List<String> = emptyList()): WebConsentCard? = when (ask) {
        WebAsk.NEVER -> null
        WebAsk.FIRST_TIME -> WebConsentCard(WebStrings.cardTitle("query" to query), noteText(service, pages, also), listOf(allowAlways, justThisTime, no), dontAsk,
            tapOnly = true)
        WebAsk.ALWAYS -> WebConsentCard(WebStrings.cardTitle("query" to query), noteText(service, pages, also), listOf(search, cancel), null, tapOnly = false)
    }
}

/** The User-Agents (A2.2, A7; open questions 1 and 2). */
object WebUserAgent {
    /**
     * The contact (an email or a URL) Robot publishes in its API User-Agent, the same for every Robot user (open
     * question 1). 1.0: Robot's published address, so Wikipedia (whose User-Agent policy requires one) is offered: the
     * "Wikipedia only" service and Encyclopedia lookups. It names Robot's project, never you or your phone.
     */
    val CONTACT: String? = "hey@robot-app.ai" // l10n: not text (an address in the User-Agent)

    /**
     * The honest User-Agent for the search APIs and Wikipedia: "Robot/0.7.0 (ai.robot_app; Android voice assistant)",
     * with "; <contact>" when there is one; Robot's app ID ([AppId], app.heyrobot before 1.0). The version as the downloads
     * send it ([AppId.product]: "Robot/1.0.0-rc1"). Nothing about the phone or the person.
     */
    fun api(version: String, contact: String? = CONTACT): String =
        "${AppId.product(version)} (${AppId.PACKAGE}; Android voice assistant" + // l10n: not text (the User-Agent)
            (contact?.trim()?.takeIf { it.isNotEmpty() }?.let { "; $it" } ?: "") + ")"

    /** A generic mobile User-Agent for page fetches (the design's proposal for open question 2). */
    const val PAGE = "Mozilla/5.0 (Linux; Android 17) Robot" // l10n: not text (the User-Agent)

    /**
     * Every header a page fetch sends: the generic User-Agent, HTML or text only, the question's language only. No
     * cookies, no Referer, no cache headers, no compression the fetcher didn't ask for itself.
     */
    fun pageHeaders(lang: String?): List<Pair<String, String>> = listOfNotNull(
        "User-Agent" to PAGE,
        "Accept" to "text/html,application/xhtml+xml,text/plain;q=0.8", // l10n: not text (an HTTP header)
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
        WebLogEvent.WEB_SEARCH -> "web_search svc=$a n=$b ms=$c" // l10n: not text (a log line)
        WebLogEvent.WEB_PAGES -> "web_pages ok=$a skipped=$b ms=$c why=$spare" // l10n: not text (a log line)
        WebLogEvent.WEB_ANSWER -> "web_answer in=${a * 10} out=$b first_ms=$c" // l10n: not text (a log line)
        WebLogEvent.WEB_FAILED -> "web_failed why=$a" // l10n: not text (a log line)
        WebLogEvent.BROWSER_SEARCH -> "browser_search engine=$a by=$b fallback=$c" // l10n: not text (a log line)
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
    /** 1.0.1: a request with a key, over http (core LanRule.keyOverHttp): refused before connecting. */
    KEY_OVER_HTTP(17, "key over http"),
    ;

    /** What Robot says and the card shows, naming [service] where it matters. */
    fun textPhrase(service: SearchService?): Phrase {
        val name = service?.labelText ?: WebStrings.theService()
        val cap = service?.capText ?: WebStrings.theServiceCap()
        return when (this) {
            NO_INTERNET -> WebStrings.failNoInternet()
            NO_NETWORK_PERMISSION -> WebStrings.failNoNetwork()
            NOT_HOME -> WebStrings.failNotHome("name" to name)
            NOT_ANSWERING -> WebStrings.failNotAnswering("name" to cap)
            JSON_OFF -> WebStrings.failJsonOff("name" to cap, "path" to "search: formats:") // l10n: not text (settings.yml keys)
            KEY_MISSING -> WebStrings.failKeyMissing("name" to name)
            KEY_REFUSED -> WebStrings.failKeyRefused("name" to cap)
            OUT_OF_CREDIT -> WebStrings.failOutOfCredit("name" to name)
            BUSY -> WebStrings.failBusy("name" to cap)
            SERVICE -> WebStrings.failService("name" to cap)
            TIMEOUT -> WebStrings.failTimeout()
            NO_RESULTS -> if (service == SearchService.DDG_INSTANT) WebStrings.failNoShortAnswer() else WebStrings.failNoResults()
            STOPPED -> WebStrings.failStopped()
            DAILY_CAP -> WebStrings.failDailyCap()
            QUERY_REFUSED -> WebStrings.failQueryRefused()
            OFF -> WebStrings.failOff()
            KEY_OVER_HTTP -> WebStrings.failKeyOverHttp()
        }
    }

    /** [textPhrase] as a sentence, ending with a full stop. */
    fun sentence(service: SearchService?): Phrase =
        if (this == QUERY_REFUSED) textPhrase(service) else WebStrings.sentence("text" to textPhrase(service))

}

/**
 * 1.0 audit (network-1): who gets a search's words besides the chosen service, decided once for the consent card, the run
 * and the card's "Sent to" line: Wikipedia (Encyclopedia lookups, for a who/what/where question) and Open Library (Book
 * lookups, for a question about books).
 */
object WebAlso {
    const val WIKIPEDIA = "Wikipedia"
    const val OPEN_LIBRARY = "Open Library"

    /**
     * "Who is…", "what is…", "where is…", "qui est…": a question Wikipedia's intro answers. In an explicit search ("search
     * the web for who is …", « cherche sur le web qui est … », WebRouter's forms) the words after the routing phrase.
     */
    fun encyclopedic(question: String): Boolean =
        ENCYCLOPEDIC.containsMatchIn(WebRouter.explicit(question) ?: question)

    /** A letter or digit can't follow: a lookahead, not \b, which the JVM reads as ASCII-only (« où » never ended a word there). */
    private val ENCYCLOPEDIC = Regex("^\\s*(?:who|what|where|qui|qu['’]est-ce|quel|quelle|ou|où)(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)

    fun wikipedia(s: WebSettings, service: SearchService, question: String): Boolean =
        service != SearchService.WIKIPEDIA_ONLY && s.wikipedia() && encyclopedic(question)

    fun books(s: WebSettings, question: String): Boolean = s.books && OpenLibraryWire.wanted(question)

    /** The others asked for [question] ([question]: your words, or the query when there are none). */
    fun names(s: WebSettings, service: SearchService, question: String): List<String> = listOfNotNull(
        WIKIPEDIA.takeIf { wikipedia(s, service, question) },
        OPEN_LIBRARY.takeIf { books(s, question) },
    )

    /** "your SearXNG", "your SearXNG and Open Library", "Brave Search, Wikipedia and Open Library". */
    fun toText(service: SearchService, also: List<String>): Phrase =
        Phrase.ListOf(listOf(service.labelText) + also.map { if (it == WIKIPEDIA) WebStrings.wikipedia() else Phrase.plain(it) })

    /** [toText] in English. */
    fun to(service: SearchService, also: List<String>): String = toText(service, also).en()
}
