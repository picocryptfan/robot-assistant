package app.heyrobot.core

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
        /** The United States, Liberia and Myanmar. */
        private val FAHRENHEIT = setOf("US", "LR", "MM")

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

/** Why a weather lookup failed: [text] for the card and the model (fixed wording), [title] for the voice log. */
enum class WeatherFail(override val id: Int, override val title: String, val text: String) : LogCode {
    NO_INTERNET(1, "no internet", "No internet: Robot couldn't reach Open-Meteo, the weather service."),
    NO_NETWORK_PERMISSION(2, "no network permission", "Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings."),
    BUSY(3, "rate limited", "The weather service is busy just now. Try again in a while."),
    SERVICE(4, "service error", "The weather service didn't answer properly. Try again later."),
    NO_PLACE(5, "place not found", "Open-Meteo doesn't know that place. Try the town's name, or add the country."),
    NO_LOCATION(6, "no location fix", "Robot couldn't tell where you are just now. Name a town, or set a Home for weather in Robot's settings."),
    LOCATION_OFF(7, "location off", "Location is off on this phone. Turn it on, name a town, or set a Home for weather in Robot's settings."),
    NO_DAYS(8, "no forecast for those days", "Open-Meteo has forecasts for today and the next 15 days only."),
    NO_HOME(9, "no Home for weather", "No Home for weather is set. Set one in Robot's Settings › Weather, or name a town."),
    /** The whole lookup (place and forecast) took longer than [WeatherAsk.DEADLINE_MS]. */
    TIMEOUT(10, "took too long", "The weather took too long to come. Try again in a moment."),
    /** Stop ended it. */
    STOPPED(11, "stopped", "Stopped."),
}

/** WMO weather interpretation codes (as Open-Meteo gives them) in plain words. */
object Wmo {
    private val WORDS = mapOf(
        0 to "clear", 1 to "mostly clear", 2 to "partly cloudy", 3 to "overcast",
        45 to "fog", 48 to "freezing fog",
        51 to "light drizzle", 53 to "drizzle", 55 to "heavy drizzle", 56 to "light freezing drizzle", 57 to "freezing drizzle",
        61 to "light rain", 63 to "rain", 65 to "heavy rain", 66 to "light freezing rain", 67 to "freezing rain",
        71 to "light snow", 73 to "snow", 75 to "heavy snow", 77 to "snow grains",
        80 to "light showers", 81 to "showers", 82 to "heavy showers", 85 to "light snow showers", 86 to "snow showers",
        95 to "thunderstorms", 96 to "thunderstorms with hail", 99 to "thunderstorms with heavy hail",
    )
    private val SNOW = setOf(71, 73, 75, 77, 85, 86)

    /** Null for a code WMO doesn't define (or none): the line then goes without it. */
    fun words(code: Int?): String? = code?.let { WORDS[it] }

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
    /** Geocoding candidates asked for, so "Paris, Texas" can find the one in Texas. */
    const val CANDIDATES = 10

    private const val DAILY = "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum," +
        "rain_sum,showers_sum,snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,cloud_cover_mean"
    private const val CURRENT = "temperature_2m,weather_code"

    /** Two decimals (about 1 km): all Open-Meteo ever learns of where you are. */
    fun coord(v: Double): String = String.format(Locale.US, "%.2f", v)

    /** The forecast for [days] days from the place's today, in its own time zone and the phone region's units. */
    fun forecastUrl(lat: Double, lon: Double, days: Int, units: WeatherUnits): String =
        "$FORECAST?latitude=${coord(lat)}&longitude=${coord(lon)}&current=$CURRENT&daily=$DAILY&timezone=auto" +
            "&forecast_days=${days.coerceIn(1, MAX_DAYS)}" +
            if (units == WeatherUnits.IMPERIAL) "&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch" else ""

    /** The name search for a place: only the town's name goes (what follows a comma is matched here, [pick]). */
    fun geocodeUrl(name: String): String =
        "$GEOCODING?name=${UriText.strict(name.substringBefore(',').trim())}&count=$CANDIDATES&language=en&format=json"

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
     * The place [asked] means among [results]: with a qualifier after a comma ("Paris, Texas", "London, Canada", "Paris,
     * FR"), the first result whose region, county, country or country code matches it; else (or with none matching) the
     * first, which Open-Meteo ranks by population.
     */
    fun pick(asked: String, results: List<GeoResult>): GeoResult? {
        val quals = asked.split(',').drop(1).map(::plain).filter { it.isNotEmpty() }
        if (quals.isNotEmpty()) {
            results.firstOrNull { r ->
                val fields = listOfNotNull(r.admin1, r.admin2, r.country, r.countryCode).map(::plain)
                quals.all { q -> fields.any { it == q || it.startsWith("$q ") } }
            }?.let { return it }
        }
        return results.firstOrNull()
    }

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT).trim()

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotBlank() }

    private fun num(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { it.isFinite() }

    private fun at(a: JSONArray?, i: Int): Double? = if (a == null || i >= a.length() || a.isNull(i)) null else a.optDouble(i).takeIf { it.isFinite() }
}

/** The tool's place and day arguments, read before anything goes online. */
object WeatherAsk {
    /** At most this many days in one answer (about 40 tokens each). */
    const val MAX_SHOWN = 7
    /** The whole lookup, place and forecast, at most: then it gives up ([WeatherFail.TIMEOUT]). */
    const val DEADLINE_MS = 15_000L
    /** How long to wait for a new location fix: without a Home for weather to fall back on, and with one. */
    const val FIX_MS = 10_000L
    const val FIX_WITH_HOME_MS = 3_000L

    private val HERE = setOf("here", "my location", "current location", "my current location", "where i am", "where im", "around here",
        "nearby", "near me", "local", "ici", "autour de moi", "ma position")
    private val HOME = setOf("home", "at home", "my home", "my house", "my place", "chez moi", "a la maison", "maison")

    /** What [place] says: where the phone is, your Home for weather, or a named place (returned without "in" or "à"). */
    fun where(place: String?): Pair<WeatherWhere, String?> {
        val p = place?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.', '?', '!')?.trim()
        if (p.isNullOrEmpty()) return WeatherWhere.HERE to null
        val k = key(p)
        if (k in HERE) return WeatherWhere.HERE to null
        if (k in HOME) return WeatherWhere.HOME to null
        val named = p.replace(Regex("""^(?i)(?:in|at|for|à|au|en)\s+"""), "").trim()
        return WeatherWhere.NAMED to named.ifEmpty { p }
    }

    /** "today", "tomorrow", "Friday", "this week", "this weekend", "next 3 days", and the French words for them. */
    const val SAY_DAY = "say the day like today, tomorrow, Friday or this week"

    private val FRENCH = listOf(
        "apres demain" to "day after tomorrow", "aujourdhui" to "today", "demain" to "tomorrow", "ce soir" to "tonight", "cette nuit" to "tonight",
        "ce matin" to "this morning", "cet apres midi" to "this afternoon", "cette semaine" to "this week", "la semaine prochaine" to "next week",
        "semaine prochaine" to "next week", "ce week end" to "this weekend", "ce weekend" to "this weekend", "le week end" to "this weekend",
        "le weekend" to "this weekend", "week end" to "weekend", "lundi" to "monday", "mardi" to "tuesday", "mercredi" to "wednesday",
        "jeudi" to "thursday", "vendredi" to "friday", "samedi" to "saturday", "dimanche" to "sunday", "maintenant" to "today",
        "prochains jours" to "next 3 days",
    )
    private val TODAY = setOf("", "now", "right now", "currently", "today", "at the moment", "this moment", "later", "later today", "rest of the day")
    private val NEXT_DAYS = Regex("""^(?:the )?(?:next|coming|following) (\d{1,2}|few|couple of|couple|two|three|four|five|six|seven) days$|^(?:the )?(?:next|coming) days$|^(?:a )?few days$""")

    /**
     * The days [day] names at [now] (the place's own now, once its time zone is known): today when it is blank. Days
     * before today, or all of them beyond Open-Meteo's 16, are refused; a range is cut to those it covers.
     */
    fun days(day: String?, now: ZonedDateTime): Read<DayRange> {
        val today = now.toLocalDate()
        fun range(from: LocalDate, to: LocalDate) = DayRange(from.atStartOfDay(now.zone), to.atStartOfDay(now.zone))
        val k = key(day.orEmpty()).let { s -> FRENCH.fold(" $s ") { acc, (fr, en) -> acc.replace(" $fr ", " $en ") }.trim() }
        val r = when {
            k in TODAY -> range(today, today.plusDays(1))
            NEXT_DAYS.matches(k) -> {
                val n = NEXT_DAYS.find(k)?.groupValues?.get(1).orEmpty()
                val count = n.toIntOrNull() ?: when (n) { "couple of", "couple", "two" -> 2; "four" -> 4; "five" -> 5; "six" -> 6; "seven" -> 7; else -> 3 }
                range(today, today.plusDays(count.coerceIn(1, OpenMeteo.MAX_DAYS).toLong()))
            }
            k == "weekend" -> return days("this weekend", now)
            else -> when (val w = WhenParser.range(k, now)) {
                is Read.Ok -> w.value
                is Read.Unreadable -> return Read.Unreadable(SAY_DAY)
            }
        }
        val last = today.plusDays(OpenMeteo.MAX_DAYS.toLong())
        val from = maxOf(r.from.toLocalDate(), today)
        val to = minOf(r.to.toLocalDate(), last)
        if (!to.isAfter(from)) return Read.Unreadable(if (r.to.toLocalDate() <= today) "Robot has forecasts only, not past weather" else WeatherFail.NO_DAYS.text)
        return Read.Ok(range(from, to))
    }

    /** Days to ask Open-Meteo for so the place's own days cover [range] (one more: the place may be a day ahead). */
    fun fetchDays(range: DayRange, now: ZonedDateTime): Int =
        (ChronoUnit.DAYS.between(now.toLocalDate(), range.to.toLocalDate()) + 1).toInt().coerceIn(1, OpenMeteo.MAX_DAYS)

    private fun key(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim()
}

/** What the tool hands back: one line per day for the card and the model, and the line text-call mode reads out. */
data class WeatherReport(val results: List<Map<String, Any?>>, val lines: String, val spoken: String)

object WeatherText {
    /**
     * The asked days of [f] (read again at the place's own now, so "today" in Tokyo is Tokyo's today), at most
     * [WeatherAsk.MAX_SHOWN]; null when none of them is in the forecast.
     */
    fun report(f: Forecast, day: String?, phoneNow: ZonedDateTime, units: WeatherUnits): WeatherReport? {
        val now = f.zone?.let { phoneNow.withZoneSameInstant(it) } ?: phoneNow
        val range = (WeatherAsk.days(day, now) as? Read.Ok)?.value ?: return null
        val today = now.toLocalDate()
        val days = f.days.filter { !it.date.isBefore(range.from.toLocalDate()) && it.date.isBefore(range.to.toLocalDate()) }.take(WeatherAsk.MAX_SHOWN)
        if (days.isEmpty()) return null
        val results = days.map { d ->
            linkedMapOf<String, Any?>("day" to label(d.date, today), "weather" to line(d, if (d.date == today) f.now else null, units))
        }
        val lines = results.joinToString("\n") { "${it["day"]}: ${it["weather"]}" }
        return WeatherReport(results, lines, lines.replace("\n", ". "))
    }

    /** "today, Sat Oct 10", "tomorrow, Sun Oct 11", "Mon Oct 12". */
    fun label(date: LocalDate, today: LocalDate): String {
        val d = EventText.day(date, today.year)
        return when (date) {
            today -> "today, $d"
            today.plusDays(1) -> "tomorrow, $d"
            else -> d
        }
    }

    /**
     * "now 8°C and clear; overcast, high 13°C, low 3°C, 67% chance of rain (about 1.3 mm), wind up to 21 km/h (gusts 39),
     * cloud cover 98%". [now] only for today.
     */
    fun line(d: WeatherDay, now: WeatherNow?, u: WeatherUnits): String {
        val parts = mutableListOf<String>()
        Wmo.words(d.code)?.let { parts += it }
        d.high?.let { parts += "high ${deg(it, u)}" }
        d.low?.let { parts += "low ${deg(it, u)}" }
        precipitation(d, u)?.let { parts += it }
        d.wind?.let { w -> parts += "wind up to ${w.roundToInt()} ${u.wind}" + (d.gusts?.takeIf { it.roundToInt() > w.roundToInt() }?.let { " (gusts ${it.roundToInt()})" } ?: "") }
        d.cloud?.let { parts += "cloud cover $it%" }
        val day = parts.joinToString(", ")
        val nowText = now?.temp?.let { t -> "now ${deg(t, u)}" + (Wmo.words(now.code)?.let { " and $it" } ?: "") }
        return listOfNotNull(nowText, day.ifEmpty { null }).joinToString("; ")
    }

    /** "67% chance of rain (about 1.3 mm)", "40% chance of snow (about 2 cm)", "about 4 mm of rain", or null without data. */
    fun precipitation(d: WeatherDay, u: WeatherUnits): String? {
        val rainAmt = if (d.rain == null && d.showers == null) d.precip?.takeIf { d.snow == null || d.snow < snowMin(u) } else (d.rain ?: 0.0) + (d.showers ?: 0.0)
        val rain = rainAmt?.takeIf { it >= rainMin(u) }
        val snow = d.snow?.takeIf { it >= snowMin(u) }
        val kind = when {
            rain != null && snow != null -> "rain and snow"
            snow != null || (rain == null && Wmo.snowy(d.code)) -> "snow"
            else -> "rain"
        }
        // Both: each amount names its kind ("3.0 mm of rain and 2.0 cm of snow"); one: the kind is said once.
        val both = rain != null && snow != null
        val amounts = listOfNotNull(rain?.let { "${amount(it, u)} ${u.rain}" + if (both) " of rain" else "" },
            snow?.let { "${amount(it, u)} ${u.snow}" + if (both) " of snow" else "" })
        val about = if (amounts.isEmpty()) null else "about " + amounts.joinToString(" and ")
        return when {
            d.chance != null -> "${d.chance}% chance of $kind" + (about?.let { " ($it)" } ?: "")
            about != null -> if (both) about else "$about of $kind"
            d.precip != null || d.rain != null -> "dry"
            else -> null
        }
    }

    private fun deg(v: Double, u: WeatherUnits) = "${v.roundToInt()}${u.temp}"

    private fun rainMin(u: WeatherUnits) = if (u == WeatherUnits.IMPERIAL) 0.01 else 0.1
    private fun snowMin(u: WeatherUnits) = if (u == WeatherUnits.IMPERIAL) 0.05 else 0.1

    /** 1.3 (mm, cm below 10), 12 (mm, cm from 10), 0.05 (inches below 1), 1.2 (inches from 1). */
    private fun amount(v: Double, u: WeatherUnits): String = when {
        u == WeatherUnits.IMPERIAL && v < 1 -> String.format(Locale.US, "%.2f", v)
        v < 10 -> String.format(Locale.US, "%.1f", v)
        else -> v.roundToInt().toString()
    }
}
