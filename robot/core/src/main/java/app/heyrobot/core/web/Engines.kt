package app.heyrobot.core.web

// Pure. 1.0: the search engines Robot can hand your words to in your browser, and every engine and service with its
// privacy level (the user: "include some public search engines… easy and free… listing them with their privacy level").
// Google's Custom Search JSON API is closed to new customers, Bing's Search APIs were retired on 11 August 2025, and
// DuckDuckGo and Startpage have no full-results API; scraping their pages breaks their terms (and breaks often), so Robot
// never reads these engines' results itself: it opens your browser at the engine's own search address ([BrowserEngine.url])
// and never sees what comes back. Each level is made from the facts its policy states ([PrivacyFacts], read on 12 October
// 2026, sources in README's "Search engines and their privacy levels"), never from how an engine describes itself. The
// lines people read are in the app's strings; core keeps the names, the addresses and the facts.

/** How private an engine or a service is, made from [PrivacyFacts] ([of]). */
enum class PrivacyLevel(val title: String) {
    /** Your words aren't kept with your IP address or an account (beyond security logs of days), and no profile is made of them. */
    HIGH("High"),
    /**
     * Your words go with your IP address (or part of it, or an account) to a company that keeps them for weeks or months (itself
     * or its results partner), but by its policy no advertising profile is made of them without your consent.
     */
    MEDIUM("Medium"),
    /** Your searches are kept tied to you and used for advertising or personalisation, or whoever sees them answers to no policy. */
    LOW("Low"),
    ;

    companion object {
        fun of(f: PrivacyFacts): PrivacyLevel = when {
            f.profiles || !f.accountable -> LOW
            f.keptWithIp -> MEDIUM
            else -> HIGH
        }
    }
}

/** What a policy says happens to your words: the facts a [PrivacyLevel] is made from. */
data class PrivacyFacts(
    /** Your words are kept with your IP address (or part of it) or an account for weeks or longer, by the service or its results partner. */
    val keptWithIp: Boolean,
    /** An advertising or personalisation profile is made from your searches without your opting in. */
    val profiles: Boolean,
    /** A published policy, under a named company and its law, binds whoever sees your words (false: a stranger's public server). */
    val accountable: Boolean = true,
) {
    val level: PrivacyLevel get() = PrivacyLevel.of(this)
}

/**
 * An engine Robot opens in your browser: free, no key, nothing to set up. [template]'s `{q}` is your words, percent-encoded
 * (RFC 3986 unreserved characters only, a space as %20). [operator], [country] and [index] say who runs it, under which
 * law, and whose results it shows; [policy] is the policy its [facts] come from. [names]: how you say it (folded, as
 * [WebWords.fold] writes words). [code] is what the voice log keeps.
 */
enum class BrowserEngine(
    val code: Int,
    val id: String,
    val title: String,
    private val template: String,
    val operator: String,
    val country: String,
    val index: String,
    val facts: PrivacyFacts,
    val policy: String,
    val names: List<String>,
) {
    STARTPAGE(1, "startpage", "Startpage", "https://www.startpage.com/sp/search?query={q}", "Surfboard Holding B.V. (part-owned by System1, a US advertising company, since 2019)",
        "Netherlands", "Google", PrivacyFacts(keptWithIp = false, profiles = false), "https://www.startpage.com/en/privacy-policy/", listOf("startpage", "start page")),
    DUCKDUCKGO(2, "duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q={q}", "Duck Duck Go, Inc.", "United States", "mostly Microsoft Bing, with its own crawler",
        PrivacyFacts(keptWithIp = false, profiles = false), "https://duckduckgo.com/privacy", listOf("duckduckgo", "duck duck go", "duckduck go", "duck duckgo")),
    BRAVE(3, "brave", "Brave Search", "https://search.brave.com/search?q={q}", "Brave Software, Inc.", "United States", "its own index",
        PrivacyFacts(keptWithIp = false, profiles = false), "https://search.brave.com/help/privacy-policy", listOf("brave search", "brave")),
    MOJEEK(4, "mojeek", "Mojeek", "https://www.mojeek.com/search?q={q}", "Mojeek Ltd", "United Kingdom", "its own index",
        PrivacyFacts(keptWithIp = false, profiles = false), "https://www.mojeek.com/about/privacy", listOf("mojeek", "mo jeek")),
    QWANT(5, "qwant", "Qwant", "https://www.qwant.com/?q={q}", "Qwant", "France", "its own index (Staan, with Ecosia) and Microsoft Bing",
        PrivacyFacts(keptWithIp = true, profiles = false), "https://about.qwant.com/en/legal/confidentialite/", listOf("qwant", "kwant")),
    ECOSIA(6, "ecosia", "Ecosia", "https://www.ecosia.org/search?q={q}", "Ecosia GmbH", "Germany", "Microsoft Bing, Staan, and Google's if you agree",
        PrivacyFacts(keptWithIp = true, profiles = false), "https://support.ecosia.org/article/377-ip-addresses", listOf("ecosia")),
    GOOGLE(7, "google", "Google", "https://www.google.com/search?q={q}", "Google LLC", "United States", "its own index",
        PrivacyFacts(keptWithIp = true, profiles = true), "https://policies.google.com/privacy", listOf("google")),
    BING(8, "bing", "Bing", "https://www.bing.com/search?q={q}", "Microsoft Corporation", "United States", "its own index",
        PrivacyFacts(keptWithIp = true, profiles = true), "https://privacy.microsoft.com/privacystatement", listOf("bing")),
    ;

    val level: PrivacyLevel get() = facts.level

    /** The engine's own search page for [query]: what Robot hands your browser (ACTION_VIEW), never fetches itself. */
    fun url(query: String): String = template.replace("{q}", SearchWire.encode(query))

    /** The host the search opens on ("www.startpage.com"). */
    val host: String get() = template.substringAfter("://").substringBefore('/').substringBefore('?')

    companion object {
        /**
         * Startpage: Google's results without Google seeing you (it asks Google for you, without your IP address or cookies),
         * and by its policy it records neither your IP address nor your searches and sets no tracking cookies, under Dutch and
         * EU law. DuckDuckGo is as private by its policy, but most of its results are Bing's and it is a US company.
         */
        val DEFAULT = STARTPAGE

        /** The engine an [id] names (the tool's argument, the settings file), or null. */
        fun of(id: String?): BrowserEngine? {
            val k = WebWords.fold(id ?: return null).replace(Regex("[^a-z0-9]"), "")
            return entries.firstOrNull { e -> e.id == k || k == e.id + "search" || e.names.any { it.replace(" ", "") == k } }
        }
    }
}

/**
 * Every service that searches for Robot (Settings › Web search's set-up) with the facts of its level, as [BrowserEngine]
 * has them for the browser. A key ties a request to your account; the paid services' policies say what they keep of it.
 */
val SearchService.facts: PrivacyFacts get() = when (this) {
    // Your server asks the engines without cookies: they see your home's IP address, nobody else sees your words.
    SearchService.SEARXNG_HOME -> PrivacyFacts(keptWithIp = false, profiles = false)
    // "We do not store API queries against your account or use them to build a profile" (load-balancer logs 7 days).
    SearchService.KAGI -> PrivacyFacts(keptWithIp = false, profiles = false)
    // Kept "for a maximum of 90 days" with your API account, for billing and troubleshooting.
    SearchService.BRAVE -> PrivacyFacts(keptWithIp = true, profiles = false)
    // Mojeek's no-tracking policy: the IP address is replaced by a country code in its logs.
    SearchService.MOJEEK -> PrivacyFacts(keptWithIp = false, profiles = false)
    // Someone you don't know sees your words and the phone's IP address, and no policy you can hold them to says what they keep.
    SearchService.SEARXNG_PUBLIC -> PrivacyFacts(keptWithIp = true, profiles = false, accountable = false)
    // Wikimedia keeps readers' IP addresses in its server logs for up to 90 days (its data retention guidelines); no ads.
    SearchService.WIKIPEDIA_ONLY -> PrivacyFacts(keptWithIp = true, profiles = false)
    // DuckDuckGo's policy: searches aren't saved, the IP address is used only for the moment.
    SearchService.DDG_INSTANT -> PrivacyFacts(keptWithIp = false, profiles = false)
}

val SearchService.level: PrivacyLevel get() = facts.level

/**
 * A public SearXNG to pick at set-up (or "Other address"). Picked from searx.space on 12 October 2026: a year's uptime of
 * 99.7 % or more, every test search answered, A+ for HTTPS and TLS, no analytics. Each is a stranger's server (Low): its
 * operator sees your words and the phone's IP address, and SearXNG asks Google, DuckDuckGo, Startpage, Bing… for you. Public
 * servers' limiter allows an app about 4 searches an hour from one address (API_MAX) and many refuse apps altogether (JSON
 * off, or a bot check), so set-up's Test says whether one answers from your phone.
 */
object PublicSearxng {
    val LIST: List<String> = listOf(
        "https://searx.tiekoetter.com",
        "https://search.hbubli.cc",
        "https://baresearch.org",
        "https://priv.au",
        "https://anonsearch.win",
    )

    /** When the list was picked, for README and Settings. */
    const val PICKED = "2026-10-12"

    /** The list's host for [url] ("searx.tiekoetter.com"). */
    fun host(url: String): String = url.substringAfter("://").substringBefore('/')
}
