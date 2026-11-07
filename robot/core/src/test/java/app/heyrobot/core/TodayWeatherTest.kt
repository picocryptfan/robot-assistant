// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Today H6: the weather line's age, old forecasts never shown, the stored file and the switch (RestoreSafetyTest guards it). */
class TodayWeatherTest {
    private val zone = ZoneId.of("America/Halifax")
    private val town = WeatherPlace("Halifax, Nova Scotia, Canada", 44.6488, -63.5752)
    private fun at(h: Int, m: Int = 0, day: Int = 3) = ZonedDateTime.of(2026, 11, day, h, m, 0, 0, zone)
    private fun ms(h: Int, m: Int = 0, day: Int = 3) = at(h, m, day).toInstant().toEpochMilli()
    private fun wday(day: Int = 3) = WeatherDay(LocalDate.of(2026, 11, day), 61, 9.4, 3.1, 70, 2.0, 2.0, 0.0, 0.0, 21.0, 39.0, 98)
    private fun stored(fetched: Long = ms(6, 32), day: Int = 3, place: WeatherPlace = town, units: WeatherUnits = WeatherUnits.METRIC) =
        StoredForecast(fetched, place, units, zone, WeatherNow(4.2, 3), wday(day))
    private fun line(g: WeatherGlance) = (g as WeatherGlance.Shown).line.en()

    @Test fun aForecastFromThisMorningShowsWithItsAge() {
        val g = TodayWeather.glance(stored(), town, WeatherUnits.METRIC, at(6, 50), Lang.EN)
        assertTrue(g is WeatherGlance.Shown)
        assertEquals(ms(6, 32), (g as WeatherGlance.Shown).at)
        // Fresh: "right now" is said, as the brief says it.
        assertTrue(line(g), line(g).startsWith("Right now it's 4 degrees"))
        assertTrue(line(g), line(g).contains("high of 9"))
    }

    @Test fun pastAnHourRightNowIsNoLongerSaidButTheDayIs() {
        val g = TodayWeather.glance(stored(), town, WeatherUnits.METRIC, at(13, 0), Lang.EN)
        assertFalse(line(g), line(g).contains("Right now"))
        assertTrue(line(g), line(g).startsWith("Today:"))
        assertTrue(line(g), line(g).contains("high of 9"))
        assertEquals(ms(6, 32), (g as WeatherGlance.Shown).at)
    }

    @Test fun yesterdaysForecastIsNeverShown() {
        // Fetched last night: today has no forecast yet, whatever yesterday's said.
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(stored(ms(22, 0, day = 2), day = 2), town, WeatherUnits.METRIC, at(7, 0), Lang.EN))
        // Midnight passes with the page open: the line goes.
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(stored(), town, WeatherUnits.METRIC, at(0, 5, day = 4), Lang.EN))
    }

    @Test fun anotherPlaceOtherUnitsOrAFetchFromTheFutureIsNotShown() {
        val montreal = WeatherPlace("Montreal", 45.5017, -73.5673)
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(stored(place = montreal), town, WeatherUnits.METRIC, at(7, 0), Lang.EN))
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(stored(), town, WeatherUnits.IMPERIAL, at(7, 0), Lang.EN))
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(stored(ms(9, 0)), town, WeatherUnits.METRIC, at(7, 0), Lang.EN))
        // The same place at Open-Meteo's two decimals is the same place.
        assertTrue(TodayWeather.glance(stored(place = town.copy(lat = 44.6512)), town, WeatherUnits.METRIC, at(7, 0), Lang.EN) is WeatherGlance.Shown)
    }

    @Test fun noTownAsksForOneAndNoForecastOffersAFetch() {
        assertEquals(WeatherGlance.NoTown, TodayWeather.glance(stored(), null, WeatherUnits.METRIC, at(7, 0), Lang.EN))
        assertEquals(WeatherGlance.NotYet, TodayWeather.glance(null, town, WeatherUnits.METRIC, at(7, 0), Lang.EN))
    }

    @Test fun aFetchKeepsTodaysDayWhereThePlaceIs() {
        val f = Forecast(zone, WeatherNow(4.2, 3), listOf(wday(2), wday(3), wday(4)))
        assertEquals(LocalDate.of(2026, 11, 3), TodayWeather.stored(f, town, WeatherUnits.METRIC, ms(6, 32), ZoneId.of("UTC"))?.today?.date)
        assertNull(TodayWeather.stored(Forecast(zone, null, listOf(wday(4))), town, WeatherUnits.METRIC, ms(6, 32), zone))
    }

    @Test fun theStoredFileRoundTripsAndABadOneIsNoForecast() {
        val s = stored()
        assertEquals(s, StoredForecast.fromJson(s.toJson()))
        val bare = StoredForecast(ms(6, 32), town, WeatherUnits.IMPERIAL, null, null, WeatherDay(LocalDate.of(2026, 11, 3), null, null, null, null, null, null, null, null, null, null, null))
        assertEquals(bare, StoredForecast.fromJson(bare.toJson()))
        for (bad in listOf(null, "", "not json", "{}", """{"v":2}""", s.toJson().replace("\"v\":1", "\"v\":0"))) assertNull(bad, StoredForecast.fromJson(bad))
    }

    @Test fun theWeatherLineIsOffUntilTurnedOnAndRoundTrips() {
        assertFalse(HomeSettings().weather)
        assertFalse(HomeSettings.fromJson("""{"v":1,"today":true}""").weather)
        val on = HomeSettings(weather = true).turnedOn()
        assertEquals(on, HomeSettings.fromJson(on.toJson()))
        assertTrue(HomeSettings().restored(on).weather)
    }
}
