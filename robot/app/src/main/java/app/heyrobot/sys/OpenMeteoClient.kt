package app.heyrobot.sys

import android.Manifest
import android.content.Context
import app.heyrobot.core.Abort
import app.heyrobot.core.Forecast
import app.heyrobot.core.GeoAnswer
import app.heyrobot.core.OpenMeteo
import app.heyrobot.core.WeatherFail
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.WeatherUnits
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// Main and voice processes. Robot's only HTTP code outside the helpers' clients (0.4.0): GETs to Open-Meteo's two hosts,
// for the weather tool (the :voice process, where tools run) and to look up your Home for weather once, when you save it
// in Settings (the main process). HTTPS only, no redirects, short timeouts cut to what is left of the lookup's deadline,
// at most 256 KB read, and a plain User-Agent (Android's default names the phone model). Stop disconnects (Abort).
// Nothing is logged here; the tool logs codes and times.

object OpenMeteoClient {
    /**
     * Why a request gave no usable answer, and Open-Meteo's HTTP status if it answered at all. [detail]: the words for it
     * when they say more than [WeatherFail.text] (a town found, but not where it was said to be).
     */
    class Failure(val why: WeatherFail, val http: Int = 0, val detail: String? = null) : Exception(why.name) {
        val text: String get() = detail ?: why.text
    }

    private const val TIMEOUT_MS = 8_000
    /** Less time than this left before the deadline: don't start another request. */
    private const val MIN_MS = 500L
    private const val MAX_BYTES = 256 * 1024
    private val HOSTS = setOf("api.open-meteo.com", "geocoding-api.open-meteo.com")

    /**
     * The place [name] means ("Paris, Texas" and "Paris Texas" pick the one in Texas; a town not where it was said to be is
     * refused). Up to three requests. Blocking.
     */
    fun geocode(ctx: Context, name: String, abort: Abort = Abort()): WeatherPlace =
        when (val a = OpenMeteo.resolve(name) { s -> OpenMeteo.places(get(ctx, OpenMeteo.geocodeUrl(s), abort)) ?: throw Failure(WeatherFail.SERVICE) }) {
            is GeoAnswer.Found -> a.place.place()
            is GeoAnswer.NotThere -> throw Failure(WeatherFail.NO_PLACE, detail = a.detail)
            GeoAnswer.None -> throw Failure(WeatherFail.NO_PLACE)
        }

    /** [days] days of forecast for [place] in [units]. Blocking. */
    fun forecast(ctx: Context, place: WeatherPlace, days: Int, units: WeatherUnits, abort: Abort = Abort()): Forecast =
        OpenMeteo.forecast(get(ctx, OpenMeteo.forecastUrl(place.lat, place.lon, days, units), abort)) ?: throw Failure(WeatherFail.SERVICE)

    /** Stop, or the deadline: why the lookup ended without an answer. */
    private fun ended(abort: Abort) = Failure(if (abort.cancelled && !abort.expired) WeatherFail.STOPPED else WeatherFail.TIMEOUT)

    /** The body of a 200 answer; anything else throws [Failure]. Blocking: never on the main thread. */
    private fun get(ctx: Context, url: String, abort: Abort): String {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) throw Failure(WeatherFail.NO_NETWORK_PERMISSION)
        if (abort.cancelled || abort.remainingMs() < MIN_MS) throw ended(abort)
        val u = URL(url)
        require(u.protocol == "https" && u.host in HOSTS) { "not an Open-Meteo URL" }
        val c = u.openConnection() as HttpURLConnection
        // Stop (or the deadline) disconnects: a blocked connect or read then fails at once.
        val forget = abort.onCancel { c.disconnect() }
        try {
            val timeout = minOf(TIMEOUT_MS.toLong(), abort.remainingMs()).toInt()
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", "Robot")
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use(::read)
            return when {
                code == 200 && body != null -> body
                // Open-Meteo's free tier has daily and per-minute limits.
                code == 429 || (OpenMeteo.isError(body) && body?.contains("limit", ignoreCase = true) == true) -> throw Failure(WeatherFail.BUSY, code)
                else -> throw Failure(WeatherFail.SERVICE, code)
            }
        } catch (f: Failure) {
            throw f
        } catch (e: Exception) {
            if (abort.cancelled || abort.expired) throw ended(abort)
            throw when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> Failure(WeatherFail.NO_INTERNET)
                is SecurityException -> Failure(WeatherFail.NO_NETWORK_PERMISSION)
                // A socket refused by the Network toggle fails with EACCES; anything else is the connection.
                is IOException -> Failure(if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) WeatherFail.NO_NETWORK_PERMISSION else WeatherFail.NO_INTERNET)
                else -> e
            }
        } finally {
            forget()
            c.disconnect()
        }
    }

    private fun read(s: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BYTES) throw Failure(WeatherFail.SERVICE)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
