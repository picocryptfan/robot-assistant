package app.heyrobot.core

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
// first time, the default, shows a card the first time; Always; Never), Robot asks OpenStreetMap's Nominatim for that
// kind of place around where the phone is (two decimals, about 1 km, as for the weather), takes the closest, and opens
// Organic Maps' route to it. Never, a "No" on the card, or anything going wrong: today's search.

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
enum class NearbyFail(override val id: Int, override val title: String, val text: String) : LogCode {
    NO_INTERNET(1, "no internet", "No internet: Robot couldn't reach OpenStreetMap"),
    NO_NETWORK_PERMISSION(2, "no network permission", "Robot isn't allowed to use the network"),
    BUSY(3, "rate limited", "OpenStreetMap's search is busy just now"),
    SERVICE(4, "service error", "OpenStreetMap's search didn't answer properly"),
    NOTHING(5, "nothing nearby", "OpenStreetMap knows no such place near you"),
    NO_LOCATION(6, "no location", "Robot couldn't tell where you are"),
    NOT_ALLOWED(7, "location not allowed", "Robot may not see where you are (allow location in the Robot app)"),
    TIMEOUT(8, "took too long", "Finding it online took too long"),
    STOPPED(9, "stopped", "Stopped"),
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

    /** The places matching [query] around [lat], [lon]. Blocking. [requests] is told how many went online. */
    fun search(query: String, lat: Double, lon: Double, abort: Abort, fetch: (String, Abort) -> String, requests: (Int) -> Unit = {}): List<NearbyPlace> {
        var sent = 0
        try {
            for (half in Nearby.BOXES) {
                val url = Nearby.searchUrl(query, lat, lon, half)
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
     * The one place the nearest place is asked for (OpenStreetMap's public Nominatim). Its usage policy
     * (operations.osmfoundation.org/policies/nominatim) asks that apps can switch it without an update; for now it is this
     * one constant, and "Never" in Settings (or anything going wrong) falls back to Organic Maps' own search.
     */
    const val HOST = "nominatim.openstreetmap.org"
    private const val SEARCH = "https://$HOST/search"
    /** The card's credit for what OpenStreetMap found (its data is ODbL; Nominatim's usage policy asks for attribution). */
    const val ATTRIBUTION = "Data © OpenStreetMap contributors, ODbL"

    /**
     * Nominatim's usage policy: a User-Agent that identifies the application. The app, its version ([version], the
     * build's versionName) and where it comes from; nothing about the phone or the person.
     */
    fun userAgent(version: String): String =
        "Robot/${version.filter { it.isLetterOrDigit() || it == '.' }.take(20).ifEmpty { "0" }} (app.heyrobot; Android voice assistant, GPL-3.0)"

    /**
     * [query] may go to Nominatim: a kind of place or a name ("pharmacy", "Tim Hortons", "7-Eleven"), at most 6 words and 60
     * characters, with no run of 3 or more digits and no "@" (an address, a phone number or an email is personal, and the
     * policy asks for none). Anything else: Organic Maps' search, which stays on the phone.
     */
    fun sendable(query: String): Boolean {
        val q = query.trim()
        return q.isNotEmpty() && q.length <= 60 && q.split(Regex("\\s+")).size <= 6 && !q.contains('@') && !Regex("\\d{3,}").containsMatchIn(q)
    }

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

    /** The first-use card's buttons and its tick box. */
    const val ALLOW_ALWAYS = "Allow always"
    const val JUST_THIS_TIME = "Just this time"
    const val NO_SEARCH = "No, search the map"
    const val DONT_ASK = "Don't ask again"

    /** The first-use card's line: exactly what would leave the phone, and to whom. */
    fun consentNote(what: String): String =
        "To find the closest one, Robot would send “$what” and your area (about 1 km) to OpenStreetMap's Nominatim. Nothing " +
            "else leaves the phone. “$NO_SEARCH” uses Organic Maps' search instead, on the phone."

    /**
     * The maps action [a] ready for its card: [wanted] is "the nearest …" in the model's or your words, and something
     * that may go (Nearby.sendable). Never, or not wanted: Organic Maps' search. Always: online. Ask the first time: a card
     * that always shows ([AskWhy.ONLINE]) with [ALLOW_ALWAYS], [JUST_THIS_TIME] and [NO_SEARCH]; "Don't ask again" ticked
     * keeps the answer chosen (Always, or Never with No).
     */
    fun prepare(a: Action, what: String, wanted: Boolean, mode: NearestOnline): Prepared.Ready {
        if (!wanted || mode == NearestOnline.NEVER) return Prepared.Ready(plain(a))
        if (mode == NearestOnline.ALWAYS) return Prepared.Ready(withNearest(a, nearest = true, consent = null))
        val always = withNearest(a, nearest = true, consent = NearestConsent.ALWAYS)
        return Prepared.Ready(
            withNearest(a, nearest = true, consent = null),
            why = setOf(AskWhy.ONLINE),
            choices = listOf(
                Choice(ALLOW_ALWAYS, always, always),
                Choice(JUST_THIS_TIME, withNearest(a, nearest = true, consent = NearestConsent.ONCE), always),
                Choice(NO_SEARCH, withNearest(a, nearest = false, consent = NearestConsent.NO), withNearest(a, nearest = false, consent = NearestConsent.NEVER)),
            ),
            note = consentNote(what),
            remember = DONT_ASK,
        )
    }

    /** The card's line when the nearest place couldn't be found and Organic Maps' search opened instead ([navigate]: directions). */
    fun instead(why: NearbyFail, navigate: Boolean): String =
        "${why.text}, so Robot opened Organic Maps' search instead" + if (navigate) ": pick the place, then tap Route." else "."

    /** The card while it waits or runs: no place yet, and the words it was asked for. */
    fun describe(what: String) = "Find the nearest “$what” online (OpenStreetMap) and open the route in Organic Maps"

    /** Two decimals (about 1 km): all OpenStreetMap learns of where you are. */
    fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

    /**
     * One bounded search for [query] in a box of [half] degrees around the rounded position. Only the words and the box go;
     * no language, no address details, no e-mail.
     */
    fun searchUrl(query: String, lat: Double, lon: Double, half: Double): String {
        val la = coord(lat).toDouble()
        val lo = coord(lon).toDouble()
        val box = listOf(lo - half, la + half, lo + half, la - half).joinToString(",") { coord(it) }
        return "$SEARCH?q=${UriText.strict(query.trim())}&format=jsonv2&limit=$LIMIT&bounded=1&viewbox=$box"
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
    fun distance(metres: Double, units: WeatherUnits): String = if (units == WeatherUnits.IMPERIAL) {
        val mi = metres / 1609.344
        when {
            mi < 0.1 -> "about ${(metres * 3.28084 / 50).roundToInt().coerceAtLeast(1) * 50} ft"
            mi < 10 -> "about ${String.format(Locale.US, "%.1f", mi)} mi"
            else -> "about ${mi.roundToInt()} mi"
        }
    } else when {
        metres < 1_000 -> "about ${(metres / 50).roundToInt().coerceAtLeast(1) * 50} m"
        metres < 10_000 -> "about ${String.format(Locale.US, "%.1f", metres / 1_000)} km"
        else -> "about ${(metres / 1_000).roundToInt()} km"
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
