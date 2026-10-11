package app.heyrobot.core

import org.json.JSONArray
import java.text.Normalizer
import java.util.Locale
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// Pure. "Directions to the nearest pharmacy" (0.4.0): Organic Maps can't route to a name, and its search, while it ranks
// around you, is a list to tap through. With Settings › Actions › "Find the nearest place online" on, Robot asks
// OpenStreetMap's Nominatim for that kind of place around where the phone is (two decimals, about 1 km, as for the
// weather), takes the closest, and opens Organic Maps' route to it. Off, or anything going wrong: today's search.

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

object Nearby {
    const val HOST = "nominatim.openstreetmap.org"
    private const val SEARCH = "https://$HOST/search"
    /** Nominatim's usage policy: an application that says who it is. */
    const val USER_AGENT = "Robot/0.4 (GPL app)"
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

    /** [a] as a plain Organic Maps search (the setting is off, or the nearest place couldn't be found in time). */
    fun plain(a: Action): Action = when (a) {
        is Action.MapsSearch -> a.copy(nearest = false)
        is Action.MapsNavigate -> a.copy(nearest = false)
        else -> a
    }

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
