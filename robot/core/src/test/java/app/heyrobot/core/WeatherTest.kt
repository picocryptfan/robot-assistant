package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class WeatherTest {
    /** Recorded from Open-Meteo on 10 October 2026 (core/src/test/resources/weather). */
    private fun sample(name: String) = javaClass.classLoader!!.getResourceAsStream("weather/$name")!!.readBytes().toString(Charsets.UTF_8)

    private val montreal = ZoneId.of("America/Toronto")
    /** Saturday 10 October 2026, 19:50 in Montreal (the sample's "current" time). */
    private val now = ZonedDateTime.of(2026, 10, 10, 19, 50, 0, 0, montreal)

    @Test fun theUrlsSendTwoDecimalsAndTheUnitsOfTheRegion() {
        val metric = OpenMeteo.forecastUrl(45.508841, -73.587810, 2, WeatherUnits.METRIC)
        assertTrue(metric, metric.startsWith("https://api.open-meteo.com/v1/forecast?latitude=45.51&longitude=-73.59&"))
        assertTrue(metric.contains("&timezone=auto&forecast_days=2"))
        assertTrue(metric.contains("daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,precipitation_sum,rain_sum,showers_sum,snowfall_sum,wind_speed_10m_max,wind_gusts_10m_max,cloud_cover_mean"))
        assertFalse(metric.contains("fahrenheit"))
        val imperial = OpenMeteo.forecastUrl(40.7128, -74.006, 7, WeatherUnits.IMPERIAL)
        assertTrue(imperial.contains("latitude=40.71&longitude=-74.01"))
        assertTrue(imperial.endsWith("&forecast_days=7&temperature_unit=fahrenheit&wind_speed_unit=mph&precipitation_unit=inch"))
        // Never more than Open-Meteo forecasts, never less than a day.
        assertTrue(OpenMeteo.forecastUrl(0.0, 0.0, 40, WeatherUnits.METRIC).contains("forecast_days=16"))
        assertTrue(OpenMeteo.forecastUrl(0.0, 0.0, 0, WeatherUnits.METRIC).contains("forecast_days=1"))
        // Rounded, not cut, and always with a dot.
        assertEquals("45.51", OpenMeteo.coord(45.5061))
        assertEquals("-0.01", OpenMeteo.coord(-0.0051))
        // Only the town's name is searched; what follows a comma is matched against the results.
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Saint-Jean-sur-Richelieu&count=10&language=en&format=json",
            OpenMeteo.geocodeUrl("Saint-Jean-sur-Richelieu, Quebec"))
        assertEquals("https://geocoding-api.open-meteo.com/v1/search?name=Montr%C3%A9al%20Nord&count=10&language=en&format=json", OpenMeteo.geocodeUrl(" Montréal Nord "))
    }

    @Test fun unitsFollowThePhoneRegion() {
        for (r in listOf("US", "us", "LR", "MM")) assertEquals(r, WeatherUnits.IMPERIAL, WeatherUnits.of(r))
        for (r in listOf("CA", "FR", "GB", "", null)) assertEquals(r.toString(), WeatherUnits.METRIC, WeatherUnits.of(r))
    }

    @Test fun wmoCodesReadAsWords() {
        assertEquals("clear", Wmo.words(0))
        assertEquals("overcast", Wmo.words(3))
        assertEquals("drizzle", Wmo.words(53))
        assertEquals("heavy rain", Wmo.words(65))
        assertEquals("snow showers", Wmo.words(86))
        assertEquals("thunderstorms with heavy hail", Wmo.words(99))
        assertNull(Wmo.words(4))
        assertNull(Wmo.words(null))
        assertTrue(Wmo.snowy(73) && !Wmo.snowy(63))
    }

    @Test fun aRecordedForecastReadsAsDays() {
        val f = OpenMeteo.forecast(sample("forecast_montreal.json"))!!
        assertEquals(montreal, f.zone)
        assertEquals(WeatherNow(8.0, 0), f.now)
        assertEquals(listOf(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 12)), f.days.map { it.date })
        val mon = f.days[2]
        assertEquals(53, mon.code)
        assertEquals(15.5, mon.high!!, 0.0)
        assertEquals(67, mon.chance)
        assertEquals(1.3, mon.rain!!, 0.0)
        assertEquals(98, mon.cloud)
        // Error objects and damaged answers are not forecasts.
        assertNull(OpenMeteo.forecast(sample("error_limit.json")))
        assertTrue(OpenMeteo.isError(sample("error_limit.json")))
        assertFalse(OpenMeteo.isError(sample("forecast_montreal.json")))
        assertNull(OpenMeteo.forecast("<html>"))
        assertNull(OpenMeteo.forecast(null))
    }

    @Test fun tomorrowInMontrealIsOneSpeakableLine() {
        val f = OpenMeteo.forecast(sample("forecast_montreal.json"))!!
        val r = WeatherText.report(f, "tomorrow", now, WeatherUnits.METRIC)!!
        assertEquals(1, r.results.size)
        assertEquals("tomorrow, Sun Oct 11", r.results[0]["day"])
        assertEquals("overcast, high 18°C, low 6°C, 5% chance of rain, wind up to 11 km/h (gusts 25), cloud cover 100%", r.results[0]["weather"])
        // Today carries the conditions now; Monday its rain.
        val today = WeatherText.report(f, null, now, WeatherUnits.METRIC)!!
        assertEquals("now 8°C and clear; overcast, high 13°C, low 3°C, 0% chance of rain, wind up to 10 km/h (gusts 17), cloud cover 8%",
            today.results.single()["weather"])
        val monday = WeatherText.report(f, "Monday", now, WeatherUnits.METRIC)!!
        assertEquals("Mon Oct 12: drizzle, high 16°C, low 8°C, 67% chance of rain (about 1.3 mm), wind up to 21 km/h (gusts 39), cloud cover 98%", monday.lines)
        // Every day it has, at most a week: this week is today to Sunday.
        val week = WeatherText.report(f, "this week", now, WeatherUnits.METRIC)!!
        assertEquals(listOf("today, Sat Oct 10", "tomorrow, Sun Oct 11"), week.results.map { it["day"] })
        assertEquals(week.lines.replace("\n", ". "), week.spoken)
        // A day the answer doesn't hold.
        assertNull(WeatherText.report(f, "Friday", now, WeatherUnits.METRIC))
    }

    @Test fun anImperialForecastSpeaksFahrenheitMilesAndInches() {
        val f = OpenMeteo.forecast(sample("forecast_newyork_imperial.json"))!!
        val ny = ZonedDateTime.of(2026, 10, 10, 20, 5, 0, 0, ZoneId.of("America/New_York"))
        val r = WeatherText.report(f, "tomorrow", ny, WeatherUnits.IMPERIAL)!!
        assertEquals("rain, high 65°F, low 55°F, 92% chance of rain (about 0.49 in), wind up to 14 mph (gusts 32), cloud cover 100%", r.results.single()["weather"])
        // Next week from a Saturday is Monday to Sunday: the sample holds Monday to Friday.
        val next = WeatherText.report(f, "next week", ny, WeatherUnits.IMPERIAL)!!
        assertEquals(listOf("Mon Oct 12", "Tue Oct 13", "Wed Oct 14", "Thu Oct 15", "Fri Oct 16"), next.results.map { it["day"] })
        assertTrue(next.lines.contains("Wed Oct 14: drizzle, high 78°F, low 61°F, 13% chance of rain (about 0.03 in)"))
    }

    @Test fun theDaysAreThePlacesOwn() {
        val f = OpenMeteo.forecast(sample("forecast_montreal.json"))!!
        // 01:30 on Sunday in Paris is still Saturday evening in Montreal: "today" is Montreal's Saturday.
        val paris = ZonedDateTime.of(2026, 10, 11, 1, 30, 0, 0, ZoneId.of("Europe/Paris"))
        assertEquals("today, Sat Oct 10", WeatherText.report(f, "today", paris, WeatherUnits.METRIC)!!.results.single()["day"])
        // A day more is fetched for that.
        val range = (WeatherAsk.days("tomorrow", now) as Read.Ok).value
        assertEquals(3, WeatherAsk.fetchDays(range, now))
    }

    @Test fun snowAndMixedDaysSayWhatFalls() {
        val base = WeatherDay(LocalDate.of(2026, 12, 3), 73, -2.4, -8.6, 70, 4.0, 0.0, 0.0, 2.8, 15.0, 15.2, 90)
        assertEquals("70% chance of snow (about 2.8 cm)", WeatherText.precipitation(base, WeatherUnits.METRIC))
        val mixed = base.copy(code = 61, rain = 3.0, snow = 1.5, precip = 4.5)
        assertEquals("70% chance of rain and snow (about 3.0 mm of rain and 1.5 cm of snow)", WeatherText.precipitation(mixed, WeatherUnits.METRIC))
        // No chance given: the amounts alone, or "dry".
        assertEquals("about 12 mm of rain", WeatherText.precipitation(base.copy(code = 63, chance = null, rain = 12.4, snow = 0.0), WeatherUnits.METRIC))
        assertEquals("dry", WeatherText.precipitation(base.copy(code = 0, chance = null, rain = 0.0, showers = 0.0, snow = 0.0, precip = 0.0), WeatherUnits.METRIC))
        assertNull(WeatherText.precipitation(base.copy(chance = null, precip = null, rain = null, showers = null, snow = null), WeatherUnits.METRIC))
        // Gusts no stronger than the wind aren't repeated; a missing value is left out.
        val calm = base.copy(code = null, high = null, wind = 10.0, gusts = 10.2, cloud = null, chance = 0, snow = 0.0, precip = 0.0)
        assertEquals("low -9°C, 0% chance of rain, wind up to 10 km/h", WeatherText.line(calm, null, WeatherUnits.METRIC))
    }

    @Test fun theDayWordsAreReadBeforeAnythingGoesOnline() {
        fun days(s: String?) = (WeatherAsk.days(s, now) as Read.Ok).value.let { it.from.toLocalDate() to it.days }
        val sat = LocalDate.of(2026, 10, 10)
        assertEquals(sat to 1L, days(null))
        assertEquals(sat to 1L, days("now"))
        assertEquals(sat to 1L, days("tonight"))
        assertEquals(sat.plusDays(1) to 1L, days("tomorrow"))
        assertEquals(sat.plusDays(1) to 1L, days("tomorrow morning"))
        assertEquals(sat.plusDays(6) to 1L, days("Friday"))
        assertEquals(sat to 2L, days("this week"))
        assertEquals(sat to 2L, days("this weekend"))
        assertEquals(sat to 3L, days("next 3 days"))
        assertEquals(sat to 3L, days("the next few days"))
        // French.
        assertEquals(sat.plusDays(1) to 1L, days("demain"))
        assertEquals(sat to 1L, days("aujourd'hui"))
        assertEquals(sat.plusDays(2) to 1L, days("après-demain"))
        assertEquals(sat.plusDays(2) to 7L, days("la semaine prochaine"))
        assertEquals(sat.plusDays(6) to 1L, days("vendredi"))
        // Past days and days beyond Open-Meteo's 16 are refused; a range is cut to what it covers.
        assertTrue(WeatherAsk.days("yesterday", now) is Read.Unreadable)
        assertEquals(WeatherFail.NO_DAYS.text, (WeatherAsk.days("November 20", now) as Read.Unreadable).detail)
        assertEquals(sat.plusDays(9) to 7L, days("the week after next"))
        assertEquals(WeatherAsk.SAY_DAY, (WeatherAsk.days("whenever", now) as Read.Unreadable).detail)
    }

    @Test fun thePlaceArgumentSaysWhere() {
        assertEquals(WeatherWhere.HERE to null, WeatherAsk.where(null))
        assertEquals(WeatherWhere.HERE to null, WeatherAsk.where(" "))
        assertEquals(WeatherWhere.HERE to null, WeatherAsk.where("my location"))
        assertEquals(WeatherWhere.HERE to null, WeatherAsk.where("Here."))
        assertEquals(WeatherWhere.HERE to null, WeatherAsk.where("ici"))
        assertEquals(WeatherWhere.HOME to null, WeatherAsk.where("home"))
        assertEquals(WeatherWhere.HOME to null, WeatherAsk.where("chez moi"))
        assertEquals(WeatherWhere.HOME to null, WeatherAsk.where("à la maison"))
        assertEquals(WeatherWhere.NAMED to "Paris", WeatherAsk.where("in Paris"))
        assertEquals(WeatherWhere.NAMED to "Québec", WeatherAsk.where("à Québec"))
        assertEquals(WeatherWhere.NAMED to "Le Mans", WeatherAsk.where("Le Mans"))
        assertEquals(WeatherWhere.NAMED to "Paris, Texas", WeatherAsk.where("Paris, Texas"))
    }

    @Test fun geocodingPicksTheOneAskedFor() {
        val all = OpenMeteo.places(sample("geocode_paris.json"))!!
        assertEquals(3, all.size)
        assertEquals("Paris, Île-de-France Region, France", OpenMeteo.pick("Paris", all)!!.display)
        assertEquals("Paris, Texas, United States", OpenMeteo.pick("Paris, Texas", all)!!.display)
        assertEquals("Paris, Tennessee, United States", OpenMeteo.pick("Paris, tennessee", all)!!.display)
        assertEquals("Paris, Île-de-France Region, France", OpenMeteo.pick("Paris, FR", all)!!.display)
        // An unknown qualifier: the biggest town of that name.
        assertEquals(48.85341, OpenMeteo.pick("Paris, Ontario", all)!!.lat, 0.0)
        val p = OpenMeteo.pick("Paris, Texas", all)!!.place()
        assertEquals(WeatherPlace("Paris, Texas, United States", 33.66094, -95.55551), p)
        // None found, or no answer at all.
        assertEquals(emptyList<GeoResult>(), OpenMeteo.places(sample("geocode_none.json")))
        assertNull(OpenMeteo.pick("x", emptyList()))
        assertNull(OpenMeteo.places(sample("error_limit.json")))
        assertNull(OpenMeteo.places("nope"))
        // A region named like the town is said once.
        assertEquals("New York, United States", GeoResult("New York", "New York", null, "United States", "US", 40.71, -74.01).display)
        assertNotNull(WeatherFail.entries.firstOrNull { it.id == 1 })
    }
}
