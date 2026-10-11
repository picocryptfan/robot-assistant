package app.heyrobot.sys

import android.Manifest
import android.content.Context
import android.os.SystemClock
import app.heyrobot.core.Abort
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NearbyPlace
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// Voice process. The nearest place for "directions to the nearest pharmacy" (0.4.0): GETs to OpenStreetMap's Nominatim
// search, only when Settings › Actions › "Find the nearest place online" is on and you asked for the nearest one. HTTPS
// only, one host, no redirects, short timeouts cut to what is left of the lookup's deadline, at most 512 KB read, at most
// one request a second and answers kept for 10 minutes (Nominatim's usage policy), and a User-Agent that names the app
// and nothing about the phone. What goes: the words ("pharmacy") and a box around your position rounded to two decimals.
// Stop disconnects (Abort). Nothing is logged here; the tool logs codes and times.

object NominatimClient {
    class Failure(val why: NearbyFail, val http: Int = 0) : Exception(why.name)

    private const val TIMEOUT_MS = 8_000
    private const val MIN_MS = 500L
    private const val MAX_BYTES = 512 * 1024
    private val HOSTS = setOf(Nearby.HOST)

    /** When the last request went (elapsed time), for the one-a-second rule; and recent answers by URL. */
    private var lastAt = 0L
    private val cache = LinkedHashMap<String, Pair<Long, List<NearbyPlace>>>()

    /**
     * The places matching [query] around [lat], [lon]: the small box first, the larger one only if it found nothing. Each
     * box is a request (or a recent answer). Blocking. [requests] is told how many went online.
     */
    fun search(ctx: Context, query: String, lat: Double, lon: Double, abort: Abort = Abort(), requests: (Int) -> Unit = {}): List<NearbyPlace> {
        var sent = 0
        try {
            for (half in Nearby.BOXES) {
                val url = Nearby.searchUrl(query, lat, lon, half)
                val cached = synchronized(cache) { cache[url]?.takeIf { SystemClock.elapsedRealtime() - it.first < Nearby.CACHE_MS }?.second }
                val found = cached ?: (Nearby.places(get(ctx, url, abort)) ?: throw Failure(NearbyFail.SERVICE)).also { places ->
                    sent++
                    synchronized(cache) {
                        cache[url] = SystemClock.elapsedRealtime() to places
                        while (cache.size > 16) cache.remove(cache.keys.first())
                    }
                }
                if (found.isNotEmpty()) return found
            }
            return emptyList()
        } finally {
            requests(sent)
        }
    }

    private fun ended(abort: Abort) = Failure(if (abort.cancelled && !abort.expired) NearbyFail.STOPPED else NearbyFail.TIMEOUT)

    /** Waits out the one-a-second rule (Stop ends the wait). */
    private fun pace(abort: Abort) {
        while (true) {
            val wait = synchronized(this) {
                val now = SystemClock.elapsedRealtime()
                val left = lastAt + Nearby.MIN_GAP_MS - now
                if (left <= 0) { lastAt = now; 0L } else left
            }
            if (wait <= 0) return
            if (abort.cancelled || abort.remainingMs() < wait + MIN_MS) throw ended(abort)
            try { Thread.sleep(wait) } catch (_: InterruptedException) { throw ended(abort) }
        }
    }

    /** The body of a 200 answer; anything else throws [Failure]. Blocking: never on the main thread. */
    private fun get(ctx: Context, url: String, abort: Abort): String {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) throw Failure(NearbyFail.NO_NETWORK_PERMISSION)
        if (abort.cancelled || abort.remainingMs() < MIN_MS) throw ended(abort)
        val u = URL(url)
        require(u.protocol == "https" && u.host in HOSTS) { "not a Nominatim URL" }
        pace(abort)
        val c = u.openConnection() as HttpURLConnection
        val forget = abort.onCancel { c.disconnect() }
        try {
            val timeout = minOf(TIMEOUT_MS.toLong(), abort.remainingMs()).toInt()
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", Nearby.USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use(::read)
            return when {
                code == 200 && body != null -> body
                code == 429 || code == 503 -> throw Failure(NearbyFail.BUSY, code)
                else -> throw Failure(NearbyFail.SERVICE, code)
            }
        } catch (f: Failure) {
            throw f
        } catch (e: Exception) {
            if (abort.cancelled || abort.expired) throw ended(abort)
            throw when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> Failure(NearbyFail.NO_INTERNET)
                is SecurityException -> Failure(NearbyFail.NO_NETWORK_PERMISSION)
                is IOException -> Failure(if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) NearbyFail.NO_NETWORK_PERMISSION else NearbyFail.NO_INTERNET)
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
            if (out.size() > MAX_BYTES) throw Failure(NearbyFail.SERVICE)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
