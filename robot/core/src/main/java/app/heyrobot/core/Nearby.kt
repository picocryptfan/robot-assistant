// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Pure. "Directions to the nearest pharmacy" (0.4.0): Organic Maps can't route to a name, and its search, while it ranks
// around you, is a list to tap through. With Settings › Actions › "Find the nearest place online" allowing it (Ask the
// first time, the default, shows a card the first time; Always; Never), Robot asks a Nominatim search server
// (OpenStreetMap's, or the one you chose in Settings: NearbyServer) for that kind of place around where the phone is
// (two decimals, about 1 km, as for the weather), takes the closest, and opens Organic Maps' route to it. Never, a "No"
// on the card, or anything going wrong: today's search.

/** The first-use card's answer a maps action carries (Nearby.prepare): what it keeps when it runs. */
enum class NearestConsent {
    /** "Allow always": Settings becomes Always, and it goes online now. */
    ALWAYS,
    /** "Just this time": online now, the card again next time. */
    ONCE,
    /** "No, search the map": Organic Maps' search now, the card again next time. */
    NO,
    /** "No" with "Don't ask again" ticked: Settings becomes Never, and Organic Maps' search now. */
    NEVER,
}

/**
 * The panel's lasting answer to the first-use card ("Allow always", or "No" with "Don't ask again"), [at] wall clock ms.
 * The voice process keeps it in a file of its own (settings.json has one writer, the main app); it counts while it is
 * newer than what Settings last chose ([RobotSettings.nearestOnlineAt], [Nearby.mode]).
 */
data class NearestAnswer(val choice: NearestOnline, val at: Long) {
    fun toJson(): String = JSONObject().put("choice", choice.name).put("at", at).toString()

    companion object {
        fun fromJson(text: String?): NearestAnswer? = try {
            val o = JSONObject(text ?: return null)
            val c = NearestOnline.entries.firstOrNull { it.name == o.optString("choice") } ?: return null
            NearestAnswer(c, o.optLong("at", 0))
        } catch (_: Exception) {
            null
        }
    }
}

/** A place Nominatim found: its name, the rest of its address (a line or two), where it is. */
data class NearbyPlace(val name: String, val address: String?, val lat: Double, val lon: Double)

/** Why the nearest place couldn't be found online, for the card and the log (codes only, never where). */
enum class NearbyFail(override val id: Int, override val title: String, private val msg: Msg) : LogCode {
    NO_INTERNET(1, "no internet", InfoStrings.nearbyNoInternet),
    NO_NETWORK_PERMISSION(2, "no network permission", InfoStrings.nearbyNoNetwork),
    BUSY(3, "rate limited", InfoStrings.nearbyBusy),
    SERVICE(4, "service error", InfoStrings.nearbyService),
    NOTHING(5, "nothing nearby", InfoStrings.nearbyNothing),
    NO_LOCATION(6, "no location", InfoStrings.nearbyNoLocation),
    NOT_ALLOWED(7, "location not allowed", InfoStrings.nearbyNotAllowed),
    TIMEOUT(8, "took too long", InfoStrings.nearbyTimeout),
    STOPPED(9, "stopped", InfoStrings.nearbyStopped),
    ;

    /** Why, in English (the model and the log's line); [phrase] in words without a language yet (the card). */
    val text: String get() = phrase().en()

    fun phrase(server: NearbyServer = NearbyServer.DEFAULT): Phrase = when {
        this == NO_INTERNET -> msg("server" to server.sentTo)
        server.isDefault -> msg()
        this == BUSY -> InfoStrings.nearbyServerBusy("server" to server.name)
        this == SERVICE -> InfoStrings.nearbyServerService("server" to server.name)
        else -> msg()
    }

    /** [text], naming the search server you chose when it isn't OpenStreetMap's (Settings › Actions › Search server). */
    fun text(server: NearbyServer): String = phrase(server).en()
}

/**
 * The Nominatim server the nearest place is asked for (Settings › Actions › "Find the nearest place online" › Search
 * server, schema 12). Nominatim's usage policy asks that an app can switch the service at the operator's request without
 * a software update, so it is a setting: OpenStreetMap's public server ([DEFAULT]) unless you type another, such as your
 * own Nominatim. HTTPS only, a host name or an IPv4 address, an optional port and path; never a query, a fragment or a
 * user name. [base] is "https://host[:port][/path]", without a trailing slash; Robot adds "/search".
 */
class NearbyServer private constructor(
    /** Lowercase host name or IPv4 address. */
    val host: String,
    /** -1: the HTTPS default (443). */
    val port: Int,
    /** "" or "/a/b": no trailing slash. */
    val path: String,
) {
    val base: String get() = "https://" + name + path

    /** "host" or "host:port": what Settings and the cards call a server that isn't OpenStreetMap's. */
    val name: String get() = if (port > 0) "$host:$port" else host

    val isDefault: Boolean get() = this == DEFAULT

    /** Who gets the words and the area, for the first-use card and Settings. */
    val label: String get() = labelText.en()

    /** [label] in words without a language yet (1.1). */
    val labelText: Phrase get() = if (isDefault) InfoStrings.nominatimOsm() else InfoStrings.nominatimAt("server" to name)

    /** The card's "Sent to …" line. */
    val sentTo: String get() = if (isDefault) "OpenStreetMap" else name

    /** What Settings saves ([RobotSettings.nearestServer]): "" for OpenStreetMap's, so a later default follows. */
    val saved: String get() = if (isDefault) "" else base

    override fun equals(other: Any?) = other is NearbyServer && other.host == host && other.port == port && other.path == path
    override fun hashCode() = (host.hashCode() * 31 + port) * 31 + path.hashCode()
    override fun toString() = base

    /** What checking a typed address found: the server, or why it can't be used ([problem], a line for Settings). */
    /** [problem]: why not, in English; [problemText] the same words without a language yet (1.1: Settings shows them). */
    data class Checked(val server: NearbyServer?, val problem: String?) {
        var problemText: Phrase? = problem?.let(Phrase::plain)
            private set

        constructor(server: NearbyServer?, problem: Phrase) : this(server, problem.en()) {
            problemText = problem
        }
    }

    companion object {
        val DEFAULT = NearbyServer(Nearby.HOST, -1, "")
        const val MAX_LENGTH = 200

        private val LABEL = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")
        private val SEGMENT = Regex("[A-Za-z0-9._~-]+")

        /** A saved value: "" or anything unusable means OpenStreetMap's server. */
        fun of(saved: String?): NearbyServer = parse(saved) ?: DEFAULT

        /** [text] as a server, or null when it isn't one Robot may use ([check] says why). */
        fun parse(text: String?): NearbyServer? = check(text).server

        /**
         * Checks an address typed in Settings: "nominatim.example.org", "https://example.org:8443/nominatim", even a
         * pasted search link ("…/search?q=x" without its query is the same server). Empty: OpenStreetMap's.
         */
        fun check(text: String?): Checked {
            fun no(why: Phrase) = Checked(null, why)
            var t = text?.trim().orEmpty()
            if (t.isEmpty()) return Checked(DEFAULT, null)
            if (t.length > MAX_LENGTH) return no(InfoStrings.serverTooLong())
            val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*)://").find(t)
            if (scheme != null) {
                if (!scheme.groupValues[1].equals("https", ignoreCase = true)) return no(InfoStrings.serverHttps("scheme" to "https://"))
                t = t.substring(scheme.value.length)
            }
            if (t.any { it.isWhitespace() }) return no(InfoStrings.serverSpaces())
            if ('?' in t || '#' in t) return no(InfoStrings.serverQuery())
            if ('@' in t) return no(InfoStrings.serverUser())
            val slash = t.indexOf('/')
            val authority = if (slash < 0) t else t.substring(0, slash)
            var path = if (slash < 0) "" else t.substring(slash)
            val colon = authority.lastIndexOf(':')
            val host = (if (colon < 0) authority else authority.substring(0, colon)).lowercase(java.util.Locale.ROOT).removeSuffix(".")
            var port = -1
            if (colon >= 0) {
                port = authority.substring(colon + 1).toIntOrNull()?.takeIf { it in 1..65535 } ?: return no(InfoStrings.serverPort())
                if (port == 443) port = -1
            }
            if (!hostOk(host)) return no(InfoStrings.serverHost())
            path = path.trimEnd('/')
            for (end in listOf("/search.php", "/search")) if (path.endsWith(end, ignoreCase = true)) path = path.dropLast(end.length).trimEnd('/')
            if (path.isNotEmpty()) {
                val segments = path.removePrefix("/").split('/')
                if (segments.any { it.isEmpty() || it == "." || it == ".." || !SEGMENT.matches(it) }) {
                    return no(InfoStrings.serverPath())
                }
            }
            return Checked(NearbyServer(host, port, path), null)
        }

        /** A DNS name with at least two labels and a non-numeric last one, or a dotted IPv4 address. */
        private fun hostOk(h: String): Boolean {
            if (h.isEmpty() || h.length > 253) return false
            val labels = h.split('.')
            if (labels.size == 4 && labels.all { l -> l.isNotEmpty() && l.length <= 3 && l.all(Char::isDigit) }) {
                return labels.all { it.toInt() <= 255 && (it.length == 1 || !it.startsWith("0")) }
            }
            return labels.size >= 2 && labels.all { LABEL.matches(it) } && !labels.last().all(Char::isDigit)
        }
    }
}

/** A search that failed ([NearbySearch]'s fetch, the app's NominatimClient): why, and the HTTP status when there was one. */
class NearbyFailure(val why: NearbyFail, val http: Int = 0) : Exception(why.name)

/** How "the nearest …" went ([Nearby.lookup]): the closest place found, or why Organic Maps' search opens instead. */
sealed class NearbyLookup {
    /** [place], [metres] from where the phone is ([lat], [lon], never sent), among [places] found. */
    data class Found(val place: NearbyPlace, val metres: Double, val lat: Double, val lon: Double, val places: Int) : NearbyLookup()
    data class Instead(val why: NearbyFail) : NearbyLookup()
}

/**
 * The searches for the nearest place, the network aside (0.4.0): [search]'s fetch GETs one URL and throws
 * [NearbyFailure] (the app's NominatimClient: HTTPS to [Nearby.HOST] only, no redirects). The small box first, the
 * larger only if it found nothing; at most one request a second and each answer kept [Nearby.CACHE_MS] (Nominatim's
 * usage policy). [clock]: elapsed milliseconds; [sleep] waits (an interrupt ends the search). One instance per process,
 * so the pace and the cache hold for every search in it.
 */
class NearbySearch(private val clock: () -> Long, private val sleep: (Long) -> Unit) {
    /** When the last request went ([clock]), for the one-a-second rule; null: none yet. */
    private var lastAt: Long? = null
    private val cache = LinkedHashMap<String, Pair<Long, List<NearbyPlace>>>()

    /**
     * The places matching [query] around [lat], [lon], asked of [server]. Blocking. [requests] is told how many went
     * online. The pace counts every server together; the cache is by URL, so a different server is asked afresh.
     */
    fun search(query: String, lat: Double, lon: Double, abort: Abort, fetch: (String, Abort) -> String,
        server: NearbyServer = NearbyServer.DEFAULT, requests: (Int) -> Unit = {}): List<NearbyPlace> {
        var sent = 0
        try {
            for (half in Nearby.BOXES) {
                val url = Nearby.searchUrl(query, lat, lon, half, server)
                val found = synchronized(cache) { cache[url]?.takeIf { clock() - it.first < Nearby.CACHE_MS }?.second } ?: run {
                    if (abort.cancelled || abort.remainingMs() < MIN_MS) throw ended(abort)
                    pace(abort)
                    sent++
                    val places = Nearby.places(fetch(url, abort)) ?: throw NearbyFailure(NearbyFail.SERVICE)
                    synchronized(cache) {
                        cache[url] = clock() to places
                        while (cache.size > MAX_CACHED) cache.remove(cache.keys.first())
                    }
                    places
                }
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        } finally {
            requests(sent)
        }
    }

    /** Waits out the one-a-second rule (Stop or too little time left ends the search instead). */
    private fun pace(abort: Abort) {
        while (true) {
            val wait = synchronized(this) {
                val now = clock()
                val left = lastAt?.let { it + Nearby.MIN_GAP_MS - now } ?: 0L
                if (left <= 0) { lastAt = now; 0L } else left
            }
            if (wait <= 0) return
            if (abort.cancelled || abort.remainingMs() < wait + MIN_MS) throw ended(abort)
            try { sleep(wait) } catch (_: InterruptedException) { throw ended(abort) }
        }
    }

    companion object {
        /** Less time than this left: no new request. */
        const val MIN_MS = 500L
        const val MAX_CACHED = 16

        /** Stop, or the deadline. */
        fun ended(abort: Abort) = NearbyFailure(if (abort.cancelled && !abort.expired) NearbyFail.STOPPED else NearbyFail.TIMEOUT)
    }
}

object Nearby {
    /**
     * OpenStreetMap's public Nominatim, the default search server ([NearbyServer.DEFAULT]). Its usage policy
     * (operations.osmfoundation.org/policies/nominatim) asks that apps can switch it without an update: Settings › Actions ›
     * Search server takes any HTTPS Nominatim instead, and "Never" in Settings (or anything going wrong) falls back to
     * Organic Maps' own search.
     */
    const val HOST = "nominatim.openstreetmap.org"
    /** The card's credit for what OpenStreetMap found (its data is ODbL; Nominatim's usage policy asks for attribution). */
    const val ATTRIBUTION = "Data © OpenStreetMap contributors, ODbL" // l10n: not text (a licence credit, as OpenStreetMap writes it)

    /**
     * Nominatim's usage policy: a User-Agent that identifies the application. The app, its version ([version], the
     * build's versionName), its app ID ([AppId]; app.heyrobot before 1.0) and where it comes from; nothing about the phone
     * or the person.
     */
    fun userAgent(version: String): String =
        "${AppId.product(version)} (${AppId.PACKAGE}; Android voice assistant, GPL-3.0)" // l10n: not text (an HTTP header)

    /**
     * [query] may go to Nominatim: a kind of place or a name ("pharmacy", "Tim Hortons", "7-Eleven"), at most 6 words and 60
     * characters, with no run of 3 or more digits and no "@" (an address, a phone number or an email is personal, and the
     * policy asks for none). Anything else: Organic Maps' search, which stays on the phone.
     */
    fun sendable(query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && q.length <= 60 && q.split(Regex("\\s+")).size <= 6 && !q.contains('@') && !Regex("\\d{3,}").containsMatchIn(q)
    }

    /**
     * A town named for the weather may go to Open-Meteo's geocoder by the same rule: every name its searches would send
     * (OpenMeteo.searches: the part before a comma, and shorter forms of it; what follows a comma stays on the phone) is
     * [sendable]. A place the model wrote from what it read could otherwise carry an address, a number or a few words of
     * anything to Open-Meteo, with no card when you asked about the weather.
     */
    fun weatherSendable(place: String): Boolean = OpenMeteo.searches(place).all { sendable(it.name) }

    /** Nominatim's usage policy: at most one request a second. */
    const val MIN_GAP_MS = 1_000L
    /** The whole lookup (position, at most two searches a second apart, then Organic Maps). */
    const val DEADLINE_MS = 20_000L
    /** How long a position may take (Organic Maps searches around you by itself if none comes). */
    const val FIX_MS = 8_000L
    /** Nominatim's most results per search. */
    const val LIMIT = 40
    /**
     * The searches' half widths in degrees around you, in order: about 2 km, then about 10 km if nothing is that close.
     * Nominatim doesn't sort by distance and gives at most [LIMIT], so a small box first keeps the nearest among them.
     */
    val BOXES = listOf(0.02, 0.1)
    /** Nominatim's answer is cached this long (its policy asks for it): the same question again needn't go online. */
    const val CACHE_MS = 10 * 60_000L

    /** [a] as a plain Organic Maps search (Never, or the nearest place couldn't be found in time). */
    fun plain(a: Action): Action = withNearest(a, nearest = false, consent = null)

    /** [a] (a maps action) going online or not, carrying the first-use card's [consent]. */
    private fun withNearest(a: Action, nearest: Boolean, consent: NearestConsent?): Action = when (a) {
        is Action.MapsSearch -> a.copy(nearest = nearest, consent = consent)
        is Action.MapsNavigate -> a.copy(nearest = nearest, consent = consent)
        else -> a
    }

    /** "Find the nearest place online" now: Settings' choice, or the panel's answer when that is newer. */
    fun mode(settings: RobotSettings, answer: NearestAnswer?): NearestOnline =
        if (answer != null && answer.at > settings.nearestOnlineAt) answer.choice else settings.nearestOnline

    /** The first-use card's buttons and its tick box (English; the card shows them in the menus' language). */
    val ALLOW_ALWAYS: String get() = UiStrings.allowAlways().en()
    val JUST_THIS_TIME: String get() = InfoStrings.justThisTime().en()
    val NO_SEARCH: String get() = InfoStrings.noSearchMap().en()
    val DONT_ASK: String get() = InfoStrings.dontAskAgain().en()

    /** The first-use card's line: exactly what would leave the phone, and to whom (the search server in Settings). */
    fun consentNote(what: String, server: NearbyServer = NearbyServer.DEFAULT): String = consentText(what, server).en()

    /** [consentNote] in words without a language yet. */
    fun consentText(what: String, server: NearbyServer = NearbyServer.DEFAULT): Phrase =
        InfoStrings.nearbyConsent("what" to what, "server" to server.labelText, "no" to UiLabel(InfoStrings.noSearchMap))

    /**
     * The maps action [a] ready for its card: [wanted] is "the nearest …" in the model's or your words, and something
     * that may go (Nearby.sendable). Never, or not wanted: Organic Maps' search. Always: online. Ask the first time: a card
     * that always shows ([AskWhy.ONLINE]) with [ALLOW_ALWAYS], [JUST_THIS_TIME] and [NO_SEARCH]; "Don't ask again" ticked
     * keeps the answer chosen (Always, or Never with No).
     */
    fun prepare(a: Action, what: String, wanted: Boolean, mode: NearestOnline, server: NearbyServer = NearbyServer.DEFAULT): Prepared.Ready {
        if (!wanted || mode == NearestOnline.NEVER) return Prepared.Ready(plain(a))
        if (mode == NearestOnline.ALWAYS) return Prepared.Ready(withNearest(a, nearest = true, consent = null))
        val always = withNearest(a, nearest = true, consent = NearestConsent.ALWAYS)
        return Prepared.Ready(
            withNearest(a, nearest = true, consent = null),
            why = setOf(AskWhy.ONLINE),
            choices = listOf(
                Choice(UiStrings.allowAlways(), always, always),
                Choice(InfoStrings.justThisTime(), withNearest(a, nearest = true, consent = NearestConsent.ONCE), always),
                Choice(InfoStrings.noSearchMap(), withNearest(a, nearest = false, consent = NearestConsent.NO), withNearest(a, nearest = false, consent = NearestConsent.NEVER)),
            ),
            note = consentNote(what, server),
            remember = DONT_ASK,
            noteText = consentText(what, server),
            rememberText = InfoStrings.dontAskAgain(),
        )
    }

    /** The card's line when the nearest place couldn't be found and Organic Maps' search opened instead ([navigate]: directions). */
    fun instead(why: NearbyFail, navigate: Boolean, server: NearbyServer = NearbyServer.DEFAULT): String = insteadText(why, navigate, server).en()

    /** [instead] in words without a language yet. */
    fun insteadText(why: NearbyFail, navigate: Boolean, server: NearbyServer = NearbyServer.DEFAULT): Phrase =
        (if (navigate) InfoStrings.nearbyInsteadRoute else InfoStrings.nearbyInstead)("why" to why.phrase(server))

    /** The card while it waits or runs: no place yet, and the words it was asked for. */
    fun describe(what: String) = describeText(what).en()

    /** [describe] in words without a language yet. */
    fun describeText(what: String): Phrase = InfoStrings.nearbyDescribe("what" to what)

    /** Two decimals (about 1 km): all OpenStreetMap learns of where you are. */
    fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * One bounded search for [query] in a box of [half] degrees around the rounded position, asked of [server]. Only the
     * words and the box go; no language, no address details, no e-mail.
     */
    fun searchUrl(query: String, lat: Double, lon: Double, half: Double, server: NearbyServer = NearbyServer.DEFAULT): String {
        val la = coord(lat).toDouble()
        val lo = coord(lon).toDouble()
        val box = listOf(lo - half, la + half, lo + half, la - half).joinToString(",") { coord(it) }
        return "${server.base}/search?q=${UriText.strict(query.trim())}&format=jsonv2&limit=$LIMIT&bounded=1&viewbox=$box" // l10n: not text (a URL)
    }

    /** Nominatim's answer (jsonv2), or null when it isn't one. Places without a usable position are left out. */
    fun places(json: String?): List<NearbyPlace>? {
        val a = try { JSONArray(json ?: return null) } catch (_: Exception) { return null }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optString("lat").toDoubleOrNull()?.takeIf { it.isFinite() && it in -90.0..90.0 } ?: return@mapNotNull null
            val lon = o.optString("lon").toDoubleOrNull()?.takeIf { it.isFinite() && it in -180.0..180.0 } ?: return@mapNotNull null
            val display = o.optString("display_name").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val name = o.optString("name").trim().ifEmpty { display.firstOrNull().orEmpty() }.ifEmpty { return@mapNotNull null }
            // "5692, Avenue du Parc, Mile-End": the street (with its number) and the neighbourhood, not the whole region.
            val rest = display.dropWhile { it == name }.let { parts ->
                val street = if (parts.size >= 2 && parts[0].all { c -> c.isDigit() || c in "-/ " }) listOf("${parts[0]} ${parts[1]}") + parts.drop(2) else parts
                street.take(2).joinToString(", ").ifEmpty { null }
            }
            NearbyPlace(ToolCalls.safeTake(name, 80), rest?.let { ToolCalls.safeTake(it, 120) }, lat, lon)
        }
    }

    /** Metres between two points (haversine, the Earth as a sphere: plenty for "about 1.2 km"). */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Nominatim's answer [code]: null for 200 (read it), BUSY when it asks to slow down, SERVICE otherwise. */
    fun httpFail(code: Int): NearbyFail? = when (code) {
        200 -> null
        429, 503 -> NearbyFail.BUSY
        else -> NearbyFail.SERVICE
    }

    /**
     * The nearest [query] around where the phone is: [allowed] (approximate location), [locate] (the position, or null
     * when none came), [search] (the places around a position; throws [NearbyFailure]). Anything in the way, and Stop or
     * the deadline ([abort]), is [NearbyLookup.Instead] with why; the app then opens Organic Maps' search.
     */
    fun lookup(allowed: Boolean, abort: Abort, locate: () -> Pair<Double, Double>?, search: (Double, Double) -> List<NearbyPlace>): NearbyLookup {
        fun ended() = NearbyLookup.Instead(if (abort.cancelled && !abort.expired) NearbyFail.STOPPED else NearbyFail.TIMEOUT)
        if (!allowed) return NearbyLookup.Instead(NearbyFail.NOT_ALLOWED)
        if (abort.cancelled) return ended()
        val fix = locate()
        if (abort.cancelled) return ended()
        val (lat, lon) = fix ?: return NearbyLookup.Instead(NearbyFail.NO_LOCATION)
        val places = try { search(lat, lon) } catch (e: NearbyFailure) { return NearbyLookup.Instead(e.why) }
        val (place, metres) = closest(places, lat, lon) ?: return NearbyLookup.Instead(NearbyFail.NOTHING)
        if (abort.cancelled) return ended()
        return NearbyLookup.Found(place, metres, lat, lon, places.size)
    }

    /** The closest of [places] to the phone ([lat], [lon] as Android gave them, never sent anywhere), and how far. */
    fun closest(places: List<NearbyPlace>, lat: Double, lon: Double): Pair<NearbyPlace, Double>? =
        places.map { it to metres(lat, lon, it.lat, it.lon) }.minByOrNull { it.second }

    /** "about 450 m", "about 1.2 km", "about 12 km"; in miles for the phone regions that use them (WeatherUnits). */
    fun distance(metres: Double, units: WeatherUnits): String = distanceText(metres, units).en()

    /** [distance] in words without a language yet (« environ 1,2 km »). */
    fun distanceText(metres: Double, units: WeatherUnits): Phrase = if (units == WeatherUnits.IMPERIAL) {
        val mi = metres / 1609.344
        when {
            mi < 0.1 -> InfoStrings.aboutFeet("n" to ((metres * 3.28084 / 50).roundToInt().coerceAtLeast(1) * 50).toString())
            mi < 10 -> InfoStrings.aboutMiles("n" to InfoStrings.decimal(String.format(Locale.US, "%.1f", mi)))
            else -> InfoStrings.aboutMiles("n" to mi.roundToInt().toString())
        }
    } else when {
        metres < 1_000 -> InfoStrings.aboutMetres("n" to ((metres / 50).roundToInt().coerceAtLeast(1) * 50).toString())
        metres < 10_000 -> InfoStrings.aboutKm("n" to InfoStrings.decimal(String.format(Locale.US, "%.1f", metres / 1_000)))
        else -> InfoStrings.aboutKm("n" to (metres / 1_000).roundToInt().toString())
    }
}

/**
 * Organic Maps' own link for a route (its map/mwm_url.cpp, "route"): every one of sll, saddr, dll, daddr and type, in that
 * order, or Organic Maps refuses the link. It has no "my position" start, so the start is where Robot found the phone;
 * Organic Maps then picks walking or driving itself (RoutingController.prepare, Router.getBest).
 */
object OrganicMapsRoute {
    /** Six decimals: these never leave the phone (Organic Maps is offline). */
    private fun c(v: Double) = String.format(Locale.US, "%.6f", v)

    fun uri(fromLat: Double, fromLon: Double, fromName: String, toLat: Double, toLon: Double, toName: String): String =
        "om://route?sll=${c(fromLat)},${c(fromLon)}&saddr=${UriText.strict(fromName)}&dll=${c(toLat)},${c(toLon)}&daddr=${UriText.strict(toName)}&type=vehicle"

    /** Any maps app: the place itself, named. */
    fun geo(lat: Double, lon: Double, name: String): String = "geo:${c(lat)},${c(lon)}?q=${c(lat)},${c(lon)}(${UriText.strict(name)})"
}

/** Whether a maps request asks for the nearest place (English and French), from the model's words or the person's own. */
object NearestAsk {
    private val NEAREST = Regex(
        "\\b(?:nearest|closest)\\b|\\b(?:le|la|les)\\s+plus\\s+(?:pres|proches?)\\b",
        RegexOption.IGNORE_CASE,
    )

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** [raw] (a query or destination, before MapsQuery.clean) says nearest or closest. */
    fun said(raw: String?): Boolean = raw != null && NEAREST.containsMatchIn(plain(raw))

    /**
     * The model's [query] (already without "nearest") is the nearest one the person asked for in their own words ([said],
     * this turn's question): their words say nearest and hold every word of the query. Never screen text.
     */
    fun inWords(said: String?, query: String): Boolean {
        if (said == null || !said(said)) return false
        val words = plain(said).split(' ').toSet()
        val q = plain(query).split(' ').filter { it.isNotEmpty() }
        return q.isNotEmpty() && q.all { it in words }
    }
}
