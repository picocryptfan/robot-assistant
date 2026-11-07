// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.Abort
import app.heyrobot.core.Forecast
import app.heyrobot.core.RLog
import app.heyrobot.core.StoredForecast
import app.heyrobot.core.TodayWeather
import app.heyrobot.core.WeatherAsk
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.WeatherUnits
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.writeAtomic
import java.io.File
import java.time.ZoneId

// Main process. Today H6 (home-design §6.1): the forecast the weather line shows, fetched from Open-Meteo only when you tap
// on the page (NetCause.TAP, so What went online says so), for your saved town. Kept in noBackupFilesDir: fetched weather is
// never in Robot's backup or Android's. A failed fetch keeps the forecast already stored. Blocking: never on the main thread.

object TodayWeatherStore {
    fun file(ctx: Context): File = File(ctx.noBackupFilesDir, TodayWeather.FILE)

    fun current(ctx: Context): StoredForecast? = try {
        file(ctx).takeIf { it.isFile }?.let { StoredForecast.fromJson(it.readText()) }
    } catch (t: Throwable) {
        RLog.w("today_weather_read", t)
        null
    }

    /** today-fix-c 12: the stored forecast deleted (the weather line or Today turned off): no town kept at rest. */
    fun forget(ctx: Context) {
        try { file(ctx).delete() } catch (t: Throwable) { RLog.w("today_weather_forget", t) }
    }

    /** The fetch's answer: the forecast stored, or why there is none ([why], in words without a language yet). */
    sealed class Fetched {
        data class Done(val stored: StoredForecast) : Fetched()
        data class Failed(val why: Phrase) : Fetched()
    }

    /** The one way Robot asks for the page's forecast: a tap. [ask]: Open-Meteo, replaced in tests. */
    fun interface Ask {
        fun forecast(place: WeatherPlace, units: WeatherUnits, cause: NetCause): Forecast
    }

    fun openMeteo(ctx: Context): Ask = Ask { place, units, cause ->
        OpenMeteoClient.forecast(ctx, place, TodayWeather.DAYS, units, Abort(WeatherAsk.DEADLINE_MS), cause)
    }

    /** Your tap on the weather line: today's forecast for [town], stored with the time it came. */
    fun fetch(ctx: Context, town: WeatherPlace, units: WeatherUnits, ask: Ask = openMeteo(ctx), now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()): Fetched = try {
        val f = ask.forecast(town, units, NetCause.TAP)
        val s = TodayWeather.stored(f, town, units, now, zone)
        if (s == null) Fetched.Failed(WeatherFail.SERVICE.phrase) else {
            file(ctx).parentFile?.mkdirs()
            writeAtomic(file(ctx), s.toJson())
            Fetched.Done(s)
        }
    } catch (e: OpenMeteoClient.Failure) {
        Fetched.Failed(e.phrase)
    } catch (t: Throwable) {
        RLog.w("today_weather_fetch", t)
        Fetched.Failed(WeatherFail.SERVICE.phrase)
    }
}
