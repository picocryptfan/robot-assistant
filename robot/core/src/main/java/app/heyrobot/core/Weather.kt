// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.ListKind
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiStrings
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// Pure. The weather tool (0.4.0): what Robot asks Open-Meteo (open-meteo.com, no key and no account) and what it makes
// of the answer. The app process finds the place (a town you name, your Home for weather, or where the phone is) and
// fetches; this file builds the two URLs (coordinates rounded to two decimals, about 1 km), reads the JSON, turns WMO
// weather codes into words, picks °F or °C by the phone's region and writes the short lines the model speaks from.
// Nothing here is logged: the voice log gets codes and milliseconds only (VoiceEvent.WEATHER).

/** A place to ask the weather for: [name] as shown and spoken ("Montreal, Quebec, Canada") and its coordinates. */
data class WeatherPlace(val name: String, val lat: Double, val lon: Double)

/** °F, mph and inches where the phone's region uses them; °C, km/h, mm and cm elsewhere. */
enum class WeatherUnits(val temp: String, val wind: String, val rain: String, val snow: String) {
    METRIC("°C", "km/h", "mm", "cm"),
    IMPERIAL("°F", "mph", "in", "in");

    companion object {
        /**
         * The United States and its territories (Puerto Rico, Guam, the Virgin Islands, American Samoa, the Northern
         * Marianas, the outlying islands), Liberia, Myanmar, and the Bahamas, Belize, the Cayman Islands, Palau, Micronesia
         * and the Marshall Islands.
         */
        private val FAHRENHEIT = setOf("US", "PR", "GU", "VI", "AS", "MP", "UM", "LR", "MM", "BS", "BZ", "KY", "PW", "FM", "MH")

        /** [region]: the phone region (Settings, else the phone's own), two letters; unknown means metric. */
        fun of(region: String?): WeatherUnits = if (region?.trim()?.uppercase(Locale.ROOT) in FAHRENHEIT) IMPERIAL else METRIC
    }
}

/** Where the forecast is for, as the tool's place argument says it. */
enum class WeatherWhere {
    /** Where the phone is (coarse location), else your Home for weather. */
    HERE,
    /** Your Home for weather (Settings › Weather). */
    HOME,
    /** A place you named, looked up with Open-Meteo's geocoding. */
    NAMED,
}

/** How the place was found, for the voice log (codes only). */
enum class WeatherSource(override val id: Int, override val title: String) : LogCode {
    NAMED(1, "a place you named"), HOME(2, "your Home for weather"), FIX(3, "a new location fix"),
    LAST_KNOWN(4, "the phone's last known location"), HOME_NO_FIX(5, "your Home for weather (no location fix)"),
}

/**
 * Why a weather lookup failed: [text] for the model (fixed wording, English), [phrase] the same words for the card (in the
 * reply language), [title] for the voice log.
 */
enum class WeatherFail(override val id: Int, override val title: String, private val msg: Msg) : LogCode {
    NO_INTERNET(1, "no internet", InfoStrings.weatherNoInternet),
    NO_NETWORK_PERMISSION(2, "no network permission", InfoStrings.weatherNoNetwork),
    BUSY(3, "rate limited", InfoStrings.weatherBusy),
    SERVICE(4, "service error", InfoStrings.weatherService),
    NO_PLACE(5, "place not found", InfoStrings.weatherNoPlace),
    NO_LOCATION(6, "no location fix", InfoStrings.weatherNoLocation),
    LOCATION_OFF(7, "location off", InfoStrings.weatherLocationOff),
    NO_DAYS(8, "no forecast for those days", InfoStrings.weatherNoDays),
    NO_HOME(9, "no Home for weather", InfoStrings.weatherNoHome),
    /** The whole lookup (place and forecast) took longer than [WeatherAsk.DEADLINE_MS]. */
    TIMEOUT(10, "took too long", InfoStrings.weatherTimeout),
    /** Stop ended it. */
    STOPPED(11, "stopped", TurnStrings.stopped),
    /** Android refused the location to the assistant's process (it counted as in the background). */
    LOCATION_BACKGROUND(12, "location refused in the background", InfoStrings.weatherBackground);

    val phrase: Phrase get() = if (this == NO_HOME) msg("page" to UiStrings.inSettings(UiStrings.weather)) else msg()

    val text: String get() = phrase.en()
}

/** How the phone's position was looked for, for the voice log (VoiceEvent.WEATHER_LOCATION): codes only, never where. */
enum class LocationCode(override val id: Int, override val title: String) : LogCode {
    FIX(1, "a new fix"), RECENT(2, "the last known position, under 30 minutes old"), LAST_HOUR(3, "the last known position, under an hour old"),
    NONE(4, "no position in time"), BACKGROUND(5, "refused: Android counted Robot as in the background"), OFF(6, "location is off"),
    NOT_ALLOWED(7, "location not allowed"), TOO_OLD(8, "only a position over an hour old, not used"), STOPPED(9, "stopped"),
}

/** WMO weather interpretation codes (as Open-Meteo gives them) in plain words. */
object Wmo {
    private val WORDS: Map<Int, Msg> = mapOf(
        0 to InfoStrings.wmoClear, 1 to InfoStrings.wmoMostlyClear, 2 to InfoStrings.wmoPartlyCloudy, 3 to InfoStrings.wmoOvercast,
        45 to InfoStrings.wmoFog, 48 to InfoStrings.wmoFreezingFog,
        51 to InfoStrings.wmoLightDrizzle, 53 to InfoStrings.wmoDrizzle, 55 to InfoStrings.wmoHeavyDrizzle,
        56 to InfoStrings.wmoLightFreezingDrizzle, 57 to InfoStrings.wmoFreezingDrizzle,
        61 to InfoStrings.wmoLightRain, 63 to InfoStrings.wmoRain, 65 to InfoStrings.wmoHeavyRain, 66 to InfoStrings.wmoLightFreezingRain,
        67 to InfoStrings.wmoFreezingRain,
        71 to InfoStrings.wmoLightSnow, 73 to InfoStrings.wmoSnow, 75 to InfoStrings.wmoHeavySnow, 77 to InfoStrings.wmoSnowGrains,
        80 to InfoStrings.wmoLightShowers, 81 to InfoStrings.wmoShowers, 82 to InfoStrings.wmoHeavyShowers, 85 to InfoStrings.wmoLightSnowShowers,
        86 to InfoStrings.wmoSnowShowers,
        95 to InfoStrings.wmoThunderstorms, 96 to InfoStrings.wmoThunderstormsHail, 99 to InfoStrings.wmoThunderstormsHeavyHail,
    )
    private val SNOW = setOf(71, 73, 75, 77, 85, 86)

    /** Null for a code WMO doesn't define (or none): the line then goes without it. In English (the model's). */
    fun words(code: Int?): String? = text(code)?.en()

    /** [words] in words without a language yet (the card). */
    fun text(code: Int?): Phrase? = code?.let { WORDS[it]?.invoke() }

    fun snowy(code: Int?): Boolean = code in SNOW
}

/** Today's conditions at the place, as Open-Meteo's "current" block has them. */
data class WeatherNow(val temp: Double?, val code: Int?)

/** One day of Open-Meteo's daily forecast, in the units asked for. Any value may be missing. */
data class WeatherDay(
    val date: LocalDate,
    val code: Int?,
    val high: Double?,
    val low: Double?,
    /** The highest chance of precipitation in the day, %. */
    val chance: Int?,
    val precip: Double?,
    val rain: Double?,
    val showers: Double?,
    val snow: Double?,
    val wind: Double?,
    val gusts: Double?,
    /** Mean cloud cover, %. */
    val cloud: Int?,
)

/** A forecast as read: the place's time zone (the days are its days), now, and the days. */
data class Forecast(val zone: ZoneId?, val now: WeatherNow?, val days: List<WeatherDay>)

/**
 * One name search for a place: [name] goes to Open-Meteo, and the place found must be in each of [quals] (a region,
 * county or country, as said: "Texas", "TX", "Ontario", "USA").
 */
data class GeoSearch(val name: String, val quals: List<String> = emptyList())

/** What the searches for a place found: it, a town by that name but none where it was said to be, or nothing. */
sealed class GeoAnswer {
    data class Found(val place: GeoResult) : GeoAnswer()
    data class NotThere(val detail: String) : GeoAnswer() {
        /** 1.1: [detail] in words without a language yet. */
        var why: Phrase = Phrase.plain(detail)
            private set

        constructor(why: Phrase) : this(why.en()) {
            this.why = why
        }
    }
    data object None : GeoAnswer()
}

/** One geocoding result. */
data class GeoResult(val name: String, val admin1: String?, val admin2: String?, val country: String?, val countryCode: String?, val lat: Double, val lon: Double) {
    /** "Montreal, Quebec, Canada"; a region named like the town is left out ("New York, United States"). */
    val display: String get() = listOfNotNull(name, admin1?.takeIf { !it.equals(name, true) }, country).filter { it.isNotBlank() }.joinToString(", ")

    fun place() = WeatherPlace(display, lat, lon)
}

object OpenMeteo {
    const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    const val GEOCODING = "https://geocoding-api.open-meteo.com/v1/search"
    /** Open-Meteo forecasts today and the next 15 days. */
    const val MAX_DAYS = 16
    /** Geocoding candidates asked for; with a region or country to match, more ("Springfield, Oregon" is the 11th). */
    const val CANDIDATES = 10
    const val QUALIFIED_CANDIDATES = 30
    /** Name searches for one place at most (each a request). */
    const val MAX_SEARCHES = 3
    /** The credit Open-Meteo's CC BY 4.0 data asks for wherever it is shown: the last line of the weather card. */
    const val ATTRIBUTION = "Weather data by Open-Meteo.com, CC BY 4.0" // l10n: not text (a licence credit, as Open-Meteo asks for it)

    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
        "rain_sum,showers_sum,snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,cloud_cover_mean"
    private const val CURRENT = "temperature_2m,weather_code"

    /** Two decimals (about 1 km): all Open-Meteo ever learns of where you are. */
    fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** The forecast for [days] days from the place's today, in its own time zone and the phone region's units. */
    fun forecastUrl(lat: Double, lon: Double, days: Int, units: WeatherUnits): String =
        "$FORECAST?latitude=${coord(lat)}&longitude=${coord(lon)}&current=$CURRENT&daily=$DAILY&timezone=auto" + // l10n: not text (a URL)
            "&forecast_days=${days.coerceIn(1, MAX_DAYS)}" +
            if (units == WeatherUnits.IMPERIAL) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else ""

    /** One name search: only the town's name goes (the region or country is matched here, [pick]). */
    fun geocodeUrl(s: GeoSearch): String =
        "$GEOCODING?name=${UriText.strict(s.name)}&count=${if (s.quals.isEmpty()) CANDIDATES else QUALIFIED_CANDIDATES}&language=en&format=json" // l10n: not text (a URL)

    /**
     * The searches that may find [asked], in order. With a comma, the town and what follows it ("Paris, TX"). Without one
     * (speech has none), the whole name first ("New York", "Le Mans", "Kansas City"), then without "city" ("Quebec City"),
     * then its last word or two as the region or country ("Paris Texas", "Washington DC", "Springfield New Jersey").
     */
    fun searches(asked: String): List<GeoSearch> {
        val text = asked.replace(Regex("\\s+"), " ").trim().trimEnd('.', '?', '!').trim()
        val parts = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.isEmpty()) return emptyList()
        val name = parts[0]
        val quals = parts.drop(1)
        val out = LinkedHashSet<GeoSearch>()
        out += GeoSearch(name, quals)
        val noCity = name.replace(Regex("""(?i)^(?:the )?(?:city of|ville de|la ville de)\s+|\s+city$"""), "").trim().takeIf { it.isNotEmpty() && it != name }
        noCity?.let { out += GeoSearch(it, quals) }
        if (quals.isEmpty()) {
            val w = (noCity ?: name).split(' ')
            if (w.size >= 2) out += GeoSearch(w.dropLast(1).joinToString(" "), listOf(w.last()))
            if (w.size >= 3) out += GeoSearch(w.dropLast(2).joinToString(" "), listOf(w.takeLast(2).joinToString(" ")))
        }
        return out.toList()
    }

    /**
     * Runs [searches] (at most [MAX_SEARCHES], each through [search], which may throw) until one finds the place. A town
     * found but not in the region or country said is refused, never swapped for the biggest town of that name.
     */
    fun resolve(asked: String, search: (GeoSearch) -> List<GeoResult>): GeoAnswer {
        var missed: GeoSearch? = null
        for (s in searches(asked).take(MAX_SEARCHES)) {
            val found = search(s)
            if (found.isEmpty()) continue
            pick(s, found)?.let { return GeoAnswer.Found(it) }
            if (missed == null) missed = s
        }
        return missed?.let { GeoAnswer.NotThere(notThereText(it)) } ?: GeoAnswer.None
    }

    /** "Open-Meteo knows places called Paris, but none in Ontario among the biggest. …" (your words only). */
    fun notThere(s: GeoSearch): String = notThereText(s).en()

    /** [notThere] in words without a language yet. */
    fun notThereText(s: GeoSearch): Phrase = InfoStrings.notThere("name" to s.name, "where" to s.quals.joinToString(", "))

    /** Open-Meteo's error object ({"error": true, "reason": …}): true when [json] is one. */
    fun isError(json: String?): Boolean = try { JSONObject(json ?: return false).optBoolean("error", false) } catch (_: Exception) { false }

    /** The forecast in [json], or null when it isn't one (an error object, a damaged answer). */
    fun forecast(json: String?): Forecast? = try {
        val o = JSONObject(json ?: return null)
        if (o.optBoolean("error", false)) return null
        val d = o.getJSONObject("daily")
        val times = d.getJSONArray("time")
        val zone = o.optString("timezone").takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val now = o.optJSONObject("current")?.let { c -> WeatherNow(num(c, "temperature_2m"), num(c, "weather_code")?.roundToInt()) }
        fun col(k: String): JSONArray? = d.optJSONArray(k)
        val days = (0 until times.length()).map { i ->
            WeatherDay(
                date = LocalDate.parse(times.getString(i)),
                code = at(col("weather_code"), i)?.roundToInt(),
                high = at(col("temperature_2m_max"), i),
                low = at(col("temperature_2m_min"), i),
                chance = at(col("precipitation_probability_max"), i)?.roundToInt(),
                precip = at(col("precipitation_sum"), i),
                rain = at(col("rain_sum"), i),
                showers = at(col("showers_sum"), i),
                snow = at(col("snowfall_sum"), i),
                wind = at(col("wind_speed_10m_max"), i),
                gusts = at(col("wind_gusts_10m_max"), i),
                cloud = at(col("cloud_cover_mean"), i)?.roundToInt(),
            )
        }
        Forecast(zone, now, days)
    } catch (_: Exception) {
        null
    }

    /** The geocoding answer's places, best first (none: an empty list; not an answer at all: null). */
    fun places(json: String?): List<GeoResult>? = try {
        val o = JSONObject(json ?: return null)
        if (o.optBoolean("error", false)) return null
        val a = o.optJSONArray("results") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            val name = r.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!r.has("latitude") || !r.has("longitude")) return@mapNotNull null
            GeoResult(name, str(r, "admin1"), str(r, "admin2"), str(r, "country"), str(r, "country_code"), r.getDouble("latitude"), r.getDouble("longitude"))
        }
    } catch (_: Exception) {
        null
    }

    /**
     * The place [s] means among [results]: without a region or country, the first (Open-Meteo ranks by population). With
     * them, the first whose region, county, country or country code matches each ("TX", "Texas", "ON", "USA", "UK"),
     * towns of that very name before others Open-Meteo matched by another name; none: null, never the first anyway.
     */
    fun pick(s: GeoSearch, results: List<GeoResult>): GeoResult? {
        if (s.quals.isEmpty()) return results.firstOrNull()
        val name = PlaceNames.plain(s.name)
        return results.sortedByDescending { r -> PlaceNames.plain(r.name).let { it == name || it.startsWith("$name ") } }
            .firstOrNull { r -> s.quals.all { q -> PlaceNames.fits(r, q) } }
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

    private fun num(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { it.isFinite() }

    private fun at(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i).takeIf { it.isFinite() }
}

/** Regions and countries as people say them: US states, Canadian provinces and the usual names of a few countries. */
object PlaceNames {
    private val US = mapOf("al" to "alabama", "ak" to "alaska", "az" to "arizona", "ar" to "arkansas", "ca" to "california", "co" to "colorado",
        "ct" to "connecticut", "de" to "delaware", "fl" to "florida", "ga" to "georgia", "hi" to "hawaii", "id" to "idaho", "il" to "illinois",
        "in" to "indiana", "ia" to "iowa", "ks" to "kansas", "ky" to "kentucky", "la" to "louisiana", "me" to "maine", "md" to "maryland",
        "ma" to "massachusetts", "mi" to "michigan", "mn" to "minnesota", "ms" to "mississippi", "mo" to "missouri", "mt" to "montana",
        "ne" to "nebraska", "nv" to "nevada", "nh" to "new hampshire", "nj" to "new jersey", "nm" to "new mexico", "ny" to "new york", // l10n: not text (place names Robot matches)
        "nc" to "north carolina", "nd" to "north dakota", "oh" to "ohio", "ok" to "oklahoma", "or" to "oregon", "pa" to "pennsylvania", // l10n: not text (place names Robot matches)
        "ri" to "rhode island", "sc" to "south carolina", "sd" to "south dakota", "tn" to "tennessee", "tx" to "texas", "ut" to "utah", // l10n: not text (place names Robot matches)
        "vt" to "vermont", "va" to "virginia", "wa" to "washington", "wv" to "west virginia", "wi" to "wisconsin", "wy" to "wyoming", // l10n: not text (place names Robot matches)
        "dc" to "district of columbia", "d c" to "district of columbia") // l10n: not text (place names Robot matches)
    private val CANADA = mapOf("ab" to "alberta", "bc" to "british columbia", "mb" to "manitoba", "nb" to "new brunswick", // l10n: not text (place names Robot matches)
        "nl" to "newfoundland and labrador", "ns" to "nova scotia", "nt" to "northwest territories", "nu" to "nunavut", "on" to "ontario", // l10n: not text (place names Robot matches)
        "pe" to "prince edward island", "qc" to "quebec", "pq" to "quebec", "sk" to "saskatchewan", "yt" to "yukon") // l10n: not text (place names Robot matches)
    private val COUNTRIES = mapOf(
        "usa" to "united states", "us" to "united states", "u s" to "united states", "u s a" to "united states", "america" to "united states", // l10n: not text (place names Robot matches)
        "united states of america" to "united states", "etats unis" to "united states", "uk" to "united kingdom", "u k" to "united kingdom", // l10n: not text (place names Robot matches)
        "britain" to "united kingdom", "great britain" to "united kingdom", "royaume uni" to "united kingdom", "grande bretagne" to "united kingdom") // l10n: not text (place names Robot matches)

    /** Letters and digits only, no accents, lower case: "Île-de-France Region" → "ile de france region". */
    fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace(Regex("[^a-z0-9]+"), " ").trim()

    /** What [qual] may be written as in Open-Meteo's answer: itself, and the state, province or country it stands for. */
    fun expand(qual: String): Set<String> = plain(qual).let { q -> setOfNotNull(q, US[q], CANADA[q], COUNTRIES[q]) }

    /** [r] is in [qual]: its region, county, country or country code says so ("TX" fits Texas, "CA" California or Canada). */
    fun fits(r: GeoResult, qual: String): Boolean {
        val fields = listOfNotNull(r.admin1, r.admin2, r.country, r.countryCode).map(::plain)
        return expand(qual).any { e -> fields.any { it == e || it.startsWith("$e ") } }
    }
}

/** The tool's place and day arguments, read before anything goes online. */
object WeatherAsk {
    /** At most this many days in one answer (about 30 tokens each, WeatherText.short). */
    const val MAX_SHOWN = 7
    /** The whole lookup, place and forecast, at most: then it gives up ([WeatherFail.TIMEOUT]). */
    const val DEADLINE_MS = 15_000L
    /** How long to wait for a new location fix: without a Home for weather to fall back on, and with one. */
    const val FIX_MS = 10_000L
    const val FIX_WITH_HOME_MS = 3_000L

    private val HERE = setOf("here", "my location", "current location", "my current location", "where i am", "where im", "around here",
        "nearby", "near me", "local", "outside", "out", "out there", "my city", "my town", "this city", "this town", "ici", "dehors",
        "autour de moi", "ma position", "ma ville", "ici meme")
    private val HOME = setOf("home", "at home", "my home", "at my home", "my house", "at my house", "my place", "at my place", "our house",
        "at our house", "chez moi", "chez nous", "a la maison", "maison", "a ma maison")

    /**
     * What [place] says: where the phone is, your Home for weather, or a named place (returned without "in" or "à", and
     * without "here in": "here in Montreal" is Montreal).
     */
    fun where(place: String?): Pair<WeatherWhere, String?> {
        val p = place?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.', '?', '!')?.trim()
        if (p.isNullOrEmpty()) return WeatherWhere.HERE to null
        val k = key(p)
        if (k in HERE) return WeatherWhere.HERE to null
        if (k in HOME) return WeatherWhere.HOME to null
        val named = p.replace(Regex("""^(?i)(?:(?:out )?here|ici|right here)\s*,?\s+(?=(?:in|at|à|au|en)\s)"""), "")
            .replace(Regex("""^(?i)(?:in|at|for|à|au|en)\s+"""), "").trim()
        return WeatherWhere.NAMED to named.ifEmpty { p }
    }

    /** "today", "tomorrow", "Friday", "this week", "this weekend", "next 3 days", and the French words for them. */
    val SAY_DAY: String get() = InfoStrings.sayDay().en()

    /** Whole French phrases first, then words; a weekday followed by "prochain" is next week's. */
    private val FRENCH = listOf(
        "aujourdhui et demain" to "next 2 days", "samedi et dimanche" to "this weekend", "les prochains jours" to "next 3 days", // l10n: not text (heard words)
        "apres demain" to "day after tomorrow", "aujourdhui" to "today", "demain" to "tomorrow", "ce soir" to "tonight", "cette nuit" to "tonight", // l10n: not text (heard words)
        "ce matin" to "this morning", "cet apres midi" to "this afternoon", "cette semaine" to "this week", "la semaine prochaine" to "next week", // l10n: not text (heard words)
        "semaine prochaine" to "next week", "ce week end" to "this weekend", "ce weekend" to "this weekend", "le week end" to "this weekend", // l10n: not text (heard words)
        "le weekend" to "this weekend", "week end" to "weekend", "lundi" to "monday", "mardi" to "tuesday", "mercredi" to "wednesday", // l10n: not text (heard words)
        "jeudi" to "thursday", "vendredi" to "friday", "samedi" to "saturday", "dimanche" to "sunday", "maintenant" to "today",
        "prochains jours" to "next 3 days", "matin" to "morning", "apres midi" to "afternoon", "soir" to "evening", "nuit" to "night", // l10n: not text (heard words)
    )
    private val FRENCH_NEXT = Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday) prochaine?\b""")
    private val FRENCH_DAYS = Regex("""\bles (\d{1,2}) prochains jours\b""")
    private val TODAY = setOf("", "now", "right now", "currently", "today", "at the moment", "this moment", "later", "later today", "rest of the day",
        // The weather "tonight" is today's night, also after midnight (the calendar reads 01:00 "tonight" as the night under way).
        "tonight", "this evening", "this afternoon", "this morning")
    private val TWO_DAYS = setOf("today and tomorrow", "today tomorrow")
    private val NEXT_DAYS = Regex("""^(?:the )?(?:next|coming|following) (\d{1,2}|few|couple of|couple|two|three|four|five|six|seven) days$|^(?:the )?(?:next|coming) days$|^(?:a )?few days$|^(\d{1,2}) days?$""")
    /** A weekday, a month or a written date: a calendar day of its own, the same wherever the place is. */
    private val CALENDAR_DAY = Regex("""\b(monday|tuesday|wednesday|thursday|friday|saturday|sunday|january|february|march|april|may|june|july|august|september|october|november|december|weekend|week)\b|\d{4}-\d{1,2}-\d{1,2}|\b\d{1,2}(st|nd|rd|th)\b""")

    /**
     * The day words as the parser reads them: no accents, French translated, ISO dates and times kept ("2026-10-12"); a
     * hyphen between letters is a space ("après-demain").
     */
    private fun words(day: String?): String {
        var t = Normalizer.normalize(day.orEmpty(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .replace("'", "").replace("’", "").replace(Regex("""(?<=[a-z])-(?=[a-z])"""), " ").replace(Regex("""[^a-z0-9:/\- ]+"""), " ")
            .replace(Regex("\\s+"), " ").trim()
        t = FRENCH_DAYS.replace(" $t ") { "next ${it.groupValues[1]} days" }.trim() // l10n: not text (heard words)
        t = FRENCH.fold(" $t ") { acc, (fr, en) -> acc.replace(" $fr ", " $en ") }.replace(Regex("\\s+"), " ").trim()
        return FRENCH_NEXT.replace(t) { "next ${it.groupValues[1]}" } // l10n: not text (heard words)
    }

    /**
     * Whether [day] counts from today ("today", "tomorrow", "next 3 days": the place's own today, once known) rather than
     * naming a calendar day ("Friday", "October 14", "2026-10-12": the same date anywhere).
     */
    fun relative(day: String?): Boolean = !CALENDAR_DAY.containsMatchIn(words(day))

    /**
     * The days [day] names, read once in your own time zone at [now]: today when it is blank, a bare number is that many
     * days ("3"), never a clock time. Days before today, or all of them beyond Open-Meteo's 16, are refused; a range is cut
     * to those it covers.
     */
    fun days(day: String?, now: ZonedDateTime): Read<DayRange> {
        val today = now.toLocalDate()
        fun range(from: LocalDate, to: LocalDate) = DayRange(from.atStartOfDay(now.zone), to.atStartOfDay(now.zone))
        val k = words(day)
        val r = when {
            k in TODAY -> range(today, today.plusDays(1))
            k in TWO_DAYS -> range(today, today.plusDays(2))
            Regex("""^\d{1,2}$""").matches(k) -> range(today, today.plusDays(k.toLong().coerceIn(1, OpenMeteo.MAX_DAYS.toLong())))
            NEXT_DAYS.matches(k) -> {
                val g = NEXT_DAYS.find(k)!!.groupValues
                val n = g[1].ifEmpty { g[2] }
                val count = n.toIntOrNull() ?: when (n) { "couple of", "couple", "two" -> 2; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7; else -> 3 }
                range(today, today.plusDays(count.coerceIn(1, OpenMeteo.MAX_DAYS).toLong()))
            }
            k == "weekend" -> return days("this weekend", now)
            else -> when (val w = WhenParser.range(k, now)) {
                is Read.Ok -> w.value
                is Read.Unreadable -> return Read.Unreadable(InfoStrings.sayDay())
            }
        }
        val last = today.plusDays(OpenMeteo.MAX_DAYS.toLong())
        val from = maxOf(r.from.toLocalDate(), today)
        val to = minOf(r.to.toLocalDate(), last)
        if (!to.isAfter(from)) return Read.Unreadable(if (r.to.toLocalDate() <= today) InfoStrings.noPastWeather() else WeatherFail.NO_DAYS.phrase)
        return Read.Ok(range(from, to))
    }

    /** Days to ask Open-Meteo for so the place's own days cover [range] (one more: the place may be a day ahead). */
    fun fetchDays(range: DayRange, now: ZonedDateTime): Int =
        (ChronoUnit.DAYS.between(now.toLocalDate(), range.to.toLocalDate()) + 1).toInt().coerceIn(1, OpenMeteo.MAX_DAYS)

    private fun key(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim()
}

/**
 * What the tool hands back: one line per day for the card and the model, and the line text-call mode reads out. 1.1:
 * [linesText] and [spokenText], the same in words without a language yet (the card and the voice, in the reply language).
 */
data class WeatherReport(val results: List<Map<String, Any?>>, val lines: String, val spoken: String,
    val linesText: Phrase = Phrase.plain(lines), val spokenText: Phrase = Phrase.plain(spoken))

object WeatherText {
    /**
     * The days of [f] that [range] names: [range] was read in your own zone (WeatherAsk.days); a [relative] one ("today",
     * "tomorrow", "next 3 days") moves to the place's own today (today in Tokyo is Tokyo's today), a calendar day
     * ("Friday", "October 14") keeps its date. At most [maxDays]; when more were asked, the model is told so. Null when
     * none of them is in the forecast.
     */
    fun report(f: Forecast, range: DayRange, relative: Boolean, phoneNow: ZonedDateTime, units: WeatherUnits,
        maxDays: Int = WeatherAsk.MAX_SHOWN): WeatherReport? {
        val today = (f.zone?.let { phoneNow.withZoneSameInstant(it) } ?: phoneNow).toLocalDate()
        val shift = if (relative) ChronoUnit.DAYS.between(phoneNow.toLocalDate(), today) else 0L
        val from = range.from.toLocalDate().plusDays(shift)
        val to = range.to.toLocalDate().plusDays(shift)
        val wanted = f.days.filter { !it.date.isBefore(from) && it.date.isBefore(to) }
        val days = wanted.take(maxDays.coerceAtLeast(1))
        if (days.isEmpty()) return null
        // The card and the voice get whole lines; the model one line for one day, or a few tokens a day for several.
        val dayLines = days.map { d -> InfoStrings.dayLine("day" to labelText(d.date, today), "weather" to lineText(d, if (d.date == today) f.now else null, units)) }
        val lines = Phrase.Join(dayLines, "\n").en()
        val results: List<Map<String, Any?>> = (if (days.size == 1) listOf(linkedMapOf<String, Any?>("day" to label(days[0].date, today),
            "weather" to line(days[0], if (days[0].date == today) f.now else null, units)))
        else listOf(linkedMapOf<String, Any?>("units" to unitsLine(units),
            "days" to days.joinToString("\n") { d -> short(d, label(d.date, today), if (d.date == today) f.now else null) }))) +
            listOfNotNull(if (wanted.size > days.size) mapOf("note" to "Only the first ${days.size} of the ${wanted.size} days asked are given.") else null) // l10n: not text (model prompt)
        return WeatherReport(results, lines, lines.replace("\n", ". "), Phrase.Join(dayLines, "\n"), Phrase.Join(dayLines, ". "))
    }

    /**
     * [report] with as many days as fit in [roomTokens] (what is left of the context for this tool's answer beside the
     * place), at least one; the model is told when days were left out.
     */
    fun reportFitting(f: Forecast, range: DayRange, relative: Boolean, phoneNow: ZonedDateTime, units: WeatherUnits, roomTokens: Int): WeatherReport? {
        var n = WeatherAsk.MAX_SHOWN
        while (true) {
            val r = report(f, range, relative, phoneNow, units, n) ?: return null
            if (n == 1 || tokens(r.results) <= roomTokens) return r
            n--
        }
    }

    /** The estimated tokens of [results] as the model gets them (JSON). */
    fun tokens(results: List<Map<String, Any?>>): Int = TokenEstimate.text(Json.toJson(results).toString())

    /** Said once above several short lines: what their numbers are. */
    fun unitsLine(u: WeatherUnits) = "conditions, high/low ${u.temp}, chance of rain or snow, wind ${u.wind}, cloud cover" // l10n: not text (model prompt)

    /**
     * "Mon Oct 12: drizzle 16/8 67% wind 21 cloud 98%": about 30 tokens (35 estimated on the high side), the units in
     * [unitsLine]; the conditions say whether it is rain or snow.
     */
    fun short(d: WeatherDay, label: String, now: WeatherNow?): String {
        val parts = mutableListOf<String>()
        now?.temp?.let { t -> parts += "now ${t.roundToInt()}" + (Wmo.words(now.code)?.let { " $it;" } ?: ";") } // l10n: not text (model prompt)
        Wmo.words(d.code)?.let { parts += it }
        if (d.high != null || d.low != null) parts += "${d.high?.roundToInt() ?: "?"}/${d.low?.roundToInt() ?: "?"}"
        d.chance?.let { parts += "$it%" }
        d.wind?.let { parts += "wind ${it.roundToInt()}" } // l10n: not text (model prompt)
        d.cloud?.let { parts += "cloud $it%" } // l10n: not text (model prompt)
        return "$label: " + parts.joinToString(" ")
    }

    /** "today, Sat Oct 10", "tomorrow, Sun Oct 11", "Mon Oct 12". */
    fun label(date: LocalDate, today: LocalDate): String = labelText(date, today).en()

    /** [label] in words without a language yet. */
    fun labelText(date: LocalDate, today: LocalDate): Phrase {
        val d = EventText.dayText(date, today.year)
        return when (date) {
            today -> InfoStrings.today("day" to d)
            today.plusDays(1) -> InfoStrings.tomorrow("day" to d)
            else -> d
        }
    }

    /**
     * "now 8°C and clear; overcast, high 13°C, low 3°C, 67% chance of rain (about 1.3 mm), wind up to 21 km/h (gusts 39),
     * cloud cover 98%". [now] only for today.
     */
    fun line(d: WeatherDay, now: WeatherNow?, u: WeatherUnits): String = lineText(d, now, u).en()

    /** [line] in words without a language yet. */
    fun lineText(d: WeatherDay, now: WeatherNow?, u: WeatherUnits): Phrase {
        val parts = mutableListOf<Phrase>()
        Wmo.text(d.code)?.let { parts += it }
        d.high?.let { parts += InfoStrings.high("t" to deg(it, u)) }
        d.low?.let { parts += InfoStrings.low("t" to deg(it, u)) }
        precipitationText(d, u)?.let { parts += it }
        d.wind?.let { w ->
            val gusts = d.gusts?.takeIf { it.roundToInt() > w.roundToInt() }
            parts += if (gusts == null) InfoStrings.windUpTo("n" to w.roundToInt().toString(), "unit" to u.wind)
            else InfoStrings.windUpToGusts("n" to w.roundToInt().toString(), "unit" to u.wind, "gusts" to gusts.roundToInt().toString())
        }
        d.cloud?.let { parts += InfoStrings.cloudCover("n" to it.toString()) }
        val nowText = now?.temp?.let { t ->
            Wmo.text(now.code)?.let { InfoStrings.nowAnd("t" to deg(t, u), "conditions" to it) } ?: InfoStrings.now("t" to deg(t, u))
        }
        return Phrase.Join(listOfNotNull(nowText, parts.takeIf { it.isNotEmpty() }?.let { Phrase.Join(it, ", ") }), "; ")
    }

    /** "67% chance of rain (about 1.3 mm)", "40% chance of snow (about 2 cm)", "about 4 mm of rain", or null without data. */
    fun precipitation(d: WeatherDay, u: WeatherUnits): String? = precipitationText(d, u)?.en()

    /** [precipitation] in words without a language yet. */
    fun precipitationText(d: WeatherDay, u: WeatherUnits): Phrase? {
        val rainAmt = if (d.rain == null && d.showers == null) d.precip?.takeIf { d.snow == null || d.snow < snowMin(u) } else (d.rain ?: 0.0) + (d.showers ?: 0.0)
        val rain = rainAmt?.takeIf { it >= rainMin(u) }
        val snow = d.snow?.takeIf { it >= snowMin(u) }
        val kind = when {
            rain != null && snow != null -> InfoStrings.kindRainAndSnow()
            snow != null || (rain == null && Wmo.snowy(d.code)) -> InfoStrings.kindSnow()
            else -> InfoStrings.kindRain()
        }
        // Both: each amount names its kind ("3.0 mm of rain and 2.0 cm of snow"); one: the kind is said once.
        val both = rain != null && snow != null
        fun unit(name: String): Any = if (name == "in") InfoStrings.inches() else name
        val amounts = listOfNotNull(
            rain?.let { (if (both) InfoStrings.amountOfRain else InfoStrings.amount)("n" to amount(it, u), "unit" to unit(u.rain)) },
            snow?.let { (if (both) InfoStrings.amountOfSnow else InfoStrings.amount)("n" to amount(it, u), "unit" to unit(u.snow)) })
        val list = Phrase.ListOf(amounts)
        return when {
            d.chance != null -> if (amounts.isEmpty()) InfoStrings.chanceOf("p" to d.chance.toString(), "kind" to kind)
                else InfoStrings.chanceOfAbout("p" to d.chance.toString(), "kind" to kind, "about" to InfoStrings.about("amounts" to list))
            amounts.isNotEmpty() -> if (both) InfoStrings.about("amounts" to list) else InfoStrings.aboutOf("amounts" to list, "kind" to kind)
            d.precip != null || d.rain != null -> InfoStrings.dry()
            else -> null
        }
    }

    private fun deg(v: Double, u: WeatherUnits): Phrase =
        (if (u == WeatherUnits.IMPERIAL) InfoStrings.fahrenheit else InfoStrings.celsius)("n" to v.roundToInt().toString())

    private fun rainMin(u: WeatherUnits) = if (u == WeatherUnits.IMPERIAL) 0.01 else 0.1
    private fun snowMin(u: WeatherUnits) = if (u == WeatherUnits.IMPERIAL) 0.05 else 0.1

    /** 1.3 (mm, cm below 10), 12 (mm, cm from 10), 0.05 (inches below 1), 1.2 (inches from 1); « 1,3 » in French. */
    private fun amount(v: Double, u: WeatherUnits): Phrase = InfoStrings.decimal(when {
        u == WeatherUnits.IMPERIAL && v < 1 -> String.format(Locale.US, "%.2f", v)
        v < 10 -> String.format(Locale.US, "%.1f", v)
        else -> v.roundToInt().toString()
    })
}
