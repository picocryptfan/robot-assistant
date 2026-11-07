// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure. Today H6 (home-design §2 part 4, §6.1): the weather line, from the forecast Robot stored the last time you tapped
// to fetch it, with its age. Robot fetches only on that tap (NetCause.TAP), for your saved town (RobotSettings.weatherHome),
// at two decimals as every Open-Meteo request is. Old weather misleads: a forecast for another day, another place or other
// units isn't shown at all, and "now" is said only while the fetch is fresh. The stored forecast is never backed up.

/**
 * The forecast fetched for the page: when ([at], epoch ms), for which [place] and [units], in the place's [zone], and what
 * it said: [now] and [today].
 */
data class StoredForecast(val at: Long, val place: WeatherPlace, val units: WeatherUnits, val zone: ZoneId?, val now: WeatherNow?, val today: WeatherDay) {
    fun toJson(): String = JSONObject().put("v", VERSION).put("at", at)
        .put("place", JSONObject().put("name", place.name).put("lat", place.lat).put("lon", place.lon))
        .put("units", units.name.lowercase()).apply { zone?.let { put("zone", it.id) } }
        .apply { now?.let { n -> put("now", JSONObject().apply { n.temp?.let { put("temp", it) }; n.code?.let { put("code", it) } }) } }
        .put("today", day(today)).toString()

    companion object {
        const val VERSION = 1

        private fun day(d: WeatherDay): JSONObject = JSONObject().put("date", d.date.toString()).apply {
            d.code?.let { put("code", it) }; d.high?.let { put("high", it) }; d.low?.let { put("low", it) }
            d.chance?.let { put("chance", it) }; d.precip?.let { put("precip", it) }; d.rain?.let { put("rain", it) }
            d.showers?.let { put("showers", it) }; d.snow?.let { put("snow", it) }; d.wind?.let { put("wind", it) }
            d.gusts?.let { put("gusts", it) }; d.cloud?.let { put("cloud", it) }
        }

        private fun num(o: JSONObject, k: String): Double? = if (!o.has(k) || o.isNull(k)) null else o.optDouble(k).takeIf { it.isFinite() }
        private fun int(o: JSONObject, k: String): Int? = num(o, k)?.toInt()

        /** Null for anything this version can't read: no forecast, so the page offers to fetch one. */
        fun fromJson(text: String?): StoredForecast? = try {
            val o = JSONObject(text ?: return null)
            if (o.optInt("v", 0) != VERSION) null else {
                val p = o.getJSONObject("place")
                val d = o.getJSONObject("today")
                val n = o.optJSONObject("now")
                StoredForecast(o.getLong("at"), WeatherPlace(p.getString("name").take(ToolCalls.MAX_PLACE), p.getDouble("lat"), p.getDouble("lon")),
                    WeatherUnits.entries.first { it.name.lowercase() == o.getString("units") },
                    o.optString("zone").takeIf { o.has("zone") && it.isNotBlank() }?.let { ZoneId.of(it) },
                    n?.let { WeatherNow(num(it, "temp"), int(it, "code")) },
                    WeatherDay(LocalDate.parse(d.getString("date")), int(d, "code"), num(d, "high"), num(d, "low"), int(d, "chance"), num(d, "precip"),
                        num(d, "rain"), num(d, "showers"), num(d, "snow"), num(d, "wind"), num(d, "gusts"), int(d, "cloud")))
            }
        } catch (_: Exception) {
            null
        }
    }
}

/** What the weather line shows. */
sealed class WeatherGlance {
    /** No town saved: the line asks for one (Settings › Weather). */
    data object NoTown : WeatherGlance()

    /** No forecast for today, here, in these units: "No forecast yet today", with a tap to fetch. */
    data object NotYet : WeatherGlance()

    /** Today's forecast as the brief says it ([line]), fetched at [at]: "Updated 6:32", a tap to fetch again. */
    data class Shown(val line: Phrase, val at: Long) : WeatherGlance()
}

object TodayWeather {
    const val FILE = "today_weather.json"

    /** One day: today's. */
    const val DAYS = 1

    /** "Right now" is said only this long after the fetch (a guess: Open-Meteo's current block is about 15 minutes). */
    const val FRESH_MS = 60 * 60_000L

    /** A fetch later than now by more than this (the clock went back) has no age Robot can tell: not shown. */
    const val SKEW_MS = 5 * 60_000L

    /** The same place, at the two decimals Open-Meteo is asked for. */
    fun samePlace(a: WeatherPlace, b: WeatherPlace): Boolean = OpenMeteo.coord(a.lat) == OpenMeteo.coord(b.lat) && OpenMeteo.coord(a.lon) == OpenMeteo.coord(b.lon)

    /**
     * What a fetch at [at] for [place] keeps: the forecast's day that is today where the place is (the phone's zone when
     * Open-Meteo gave none), as the brief picks it. Null when the forecast has no such day.
     */
    fun stored(f: Forecast, place: WeatherPlace, units: WeatherUnits, at: Long, phoneZone: ZoneId): StoredForecast? {
        val zone = f.zone ?: phoneZone
        val today = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
        val d = f.days.firstOrNull { it.date == today } ?: return null
        return StoredForecast(at, place.copy(name = place.name.take(ToolCalls.MAX_PLACE)), units, f.zone, f.now, d)
    }

    /**
     * The line for [stored] on your page at [now], with [town] saved and the phone's [units]. Nothing is shown from a
     * forecast for another day, another place or other units, or from a fetch after now.
     */
    fun glance(stored: StoredForecast?, town: WeatherPlace?, units: WeatherUnits, now: ZonedDateTime, lang: Lang): WeatherGlance {
        if (town == null) return WeatherGlance.NoTown
        val s = stored ?: return WeatherGlance.NotYet
        val ms = now.toInstant().toEpochMilli()
        if (!samePlace(s.place, town) || s.units != units || s.at > ms + SKEW_MS) return WeatherGlance.NotYet
        if (s.today.date != now.withZoneSameInstant(s.zone ?: now.zone).toLocalDate()) return WeatherGlance.NotYet
        val fresh = ms - s.at < FRESH_MS
        return WeatherGlance.Shown(BriefComposer.weatherText(BriefWeather(if (fresh) s.now else null, s.today, s.units), null, lang, null), s.at)
    }
}
