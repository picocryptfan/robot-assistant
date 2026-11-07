// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Forecast
import app.heyrobot.core.WeatherDay
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.WeatherNow
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.WeatherUnits
import app.heyrobot.core.online.NetCause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Today H6: the page's forecast is asked for on a tap only, kept outside the backups, and a failed fetch keeps the last one. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TodayWeatherStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneId.of("America/Halifax")
    private val town = WeatherPlace("Halifax, Nova Scotia, Canada", 44.65, -63.58)
    private val morning = ZonedDateTime.of(2026, 11, 3, 6, 32, 0, 0, zone).toInstant().toEpochMilli()
    private fun day(d: Int) = WeatherDay(LocalDate.of(2026, 11, d), 61, 9.0, 3.0, 70, 2.0, 2.0, 0.0, 0.0, 21.0, 39.0, 98)

    @After fun clean() { TodayWeatherStore.file(ctx).delete() }

    @Test fun aTapAsksOpenMeteoOnceAsATapAndStoresTodayOutsideTheBackups() {
        val asked = mutableListOf<NetCause>()
        val r = TodayWeatherStore.fetch(ctx, town, WeatherUnits.METRIC, { _, _, c -> asked += c; Forecast(zone, WeatherNow(4.0, 3), listOf(day(3))) }, morning, zone)
        assertEquals(listOf(NetCause.TAP), asked)
        assertTrue(r.toString(), r is TodayWeatherStore.Fetched.Done)
        assertEquals(ctx.noBackupFilesDir, TodayWeatherStore.file(ctx).parentFile)
        assertEquals(morning, TodayWeatherStore.current(ctx)?.at)
        assertEquals(LocalDate.of(2026, 11, 3), TodayWeatherStore.current(ctx)?.today?.date)
    }

    @Test fun aFailedFetchSaysWhyAndKeepsTheLastForecast() {
        TodayWeatherStore.fetch(ctx, town, WeatherUnits.METRIC, { _, _, _ -> Forecast(zone, null, listOf(day(3))) }, morning, zone)
        val r = TodayWeatherStore.fetch(ctx, town, WeatherUnits.METRIC, { _, _, _ -> throw OpenMeteoClient.Failure(WeatherFail.NO_INTERNET) }, morning + 60_000, zone)
        assertEquals(WeatherFail.NO_INTERNET.phrase.en(), (r as TodayWeatherStore.Fetched.Failed).why.en())
        assertEquals(morning, TodayWeatherStore.current(ctx)?.at)
    }

    @Test fun aForecastWithoutTodayIsNotStored() {
        val r = TodayWeatherStore.fetch(ctx, town, WeatherUnits.METRIC, { _, _, _ -> Forecast(zone, null, listOf(day(5))) }, morning, zone)
        assertTrue(r is TodayWeatherStore.Fetched.Failed)
        assertNull(TodayWeatherStore.current(ctx))
    }
}
