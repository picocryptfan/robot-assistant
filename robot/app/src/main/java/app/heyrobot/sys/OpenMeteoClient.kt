package app.heyrobot.sys

import android.Manifest
import android.content.Context
import app.heyrobot.core.Forecast
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

// Main and voice processes. Robot's only HTTP code outside the Ask Claude SDK (0.4.0): GETs to Open-Meteo's two hosts,
// for the weather tool (the :voice process, where tools run) and to look up your Home for weather once, when you save it
// in Settings (the main process). HTTPS only, no redirects, short timeouts, at most 256 KB read, and a plain User-Agent
// (Android's default names the phone model). Nothing is logged here; the tool logs codes and times.

object OpenMeteoClient {
    /** Why a request gave no usable answer, and Open-Meteo's HTTP status if it answered at all. */
    class Failure(val why: WeatherFail, val http: Int = 0) : Exception(why.name)

    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 256 * 1024
    private val HOSTS = setOf("api.open-meteo.com", "geocoding-api.open-meteo.com")

    /** The place [name] means ("Paris, Texas" picks the one in Texas). Blocking. */
    fun geocode(ctx: Context, name: String): WeatherPlace {
        val found = OpenMeteo.places(get(ctx, OpenMeteo.geocodeUrl(name))) ?: throw Failure(WeatherFail.SERVICE)
        return OpenMeteo.pick(name, found)?.place() ?: throw Failure(WeatherFail.NO_PLACE)
    }

    /** [days] days of forecast for [place] in [units]. Blocking. */
    fun forecast(ctx: Context, place: WeatherPlace, days: Int, units: WeatherUnits): Forecast =
        OpenMeteo.forecast(get(ctx, OpenMeteo.forecastUrl(place.lat, place.lon, days, units))) ?: throw Failure(WeatherFail.SERVICE)

    /** The body of a 200 answer; anything else throws [Failure]. Blocking: never on the main thread. */
    fun get(ctx: Context, url: String): String {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) throw Failure(WeatherFail.NO_NETWORK_PERMISSION)
        val u = URL(url)
        require(u.protocol == "https" && u.host in HOSTS) { "not an Open-Meteo URL" }
        val c = u.openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
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
        } catch (_: UnknownHostException) {
            throw Failure(WeatherFail.NO_INTERNET)
        } catch (_: ConnectException) {
            throw Failure(WeatherFail.NO_INTERNET)
        } catch (_: NoRouteToHostException) {
            throw Failure(WeatherFail.NO_INTERNET)
        } catch (_: SocketTimeoutException) {
            throw Failure(WeatherFail.NO_INTERNET)
        } catch (_: SecurityException) {
            throw Failure(WeatherFail.NO_NETWORK_PERMISSION)
        } catch (e: IOException) {
            // A socket refused by the Network toggle fails with EACCES; anything else is the connection.
            throw Failure(if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) WeatherFail.NO_NETWORK_PERMISSION else WeatherFail.NO_INTERNET)
        } finally {
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
