// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import android.content.Context
import android.os.SystemClock
import app.heyrobot.BuildConfig
import app.heyrobot.core.Abort
import app.heyrobot.core.Nearby
import app.heyrobot.core.NearbyFail
import app.heyrobot.core.NearbyFailure
import app.heyrobot.core.NearbyPlace
import app.heyrobot.core.NearbySearch
import app.heyrobot.core.NearbyServer
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

// Voice process. The nearest place for "directions to the nearest pharmacy" (0.4.0): GETs to a Nominatim search (OpenStreetMap's
// unless Settings › Actions › Search server names another), only when you asked for the nearest one and Settings › Actions ›
// "Find the nearest place online" allows it. HTTPS only, to the one server chosen (core NearbyServer checks it), no
// redirects, short timeouts cut to what is left of the lookup's deadline, at most 512 KB read, and a User-Agent that
// names the app and its version and nothing about the phone (Nearby.userAgent).
// Nominatim's usage policy: that User-Agent, one request a second at most, answers cached, only what you asked for (no
// autocomplete, no lists), and the card credits OpenStreetMap (Nearby.ATTRIBUTION). The searches themselves
// (the boxes, one request a second at most, answers kept 10 minutes: Nominatim's usage policy) are core's NearbySearch.
// What goes: the words ("pharmacy") and a box around your position rounded to two decimals. Stop disconnects (Abort).
// Nothing is logged here; the tool logs codes and times.

object NominatimClient {
    private const val TIMEOUT_MS = 8_000
    private const val MAX_BYTES = 512 * 1024

    /** Every search in this process: one pace and one cache. */
    private val searches = NearbySearch(SystemClock::elapsedRealtime) { ms -> Thread.sleep(ms) }

    /**
     * The places matching [query] around [lat], [lon], asked of [server] (core NearbySearch: the small box first, the larger
     * one only if it found nothing). Blocking: never on the main thread. Throws NearbyFailure. [requests] is told how many
     * went online.
     */
    fun search(ctx: Context, query: String, lat: Double, lon: Double, server: NearbyServer, abort: Abort = Abort(), requests: (Int) -> Unit = {}): List<NearbyPlace> =
        searches.search(query, lat, lon, abort, { url, a -> get(ctx, url, server, a) }, server, requests)

    /** The body of a 200 answer from [server]; anything else throws [NearbyFailure]. Blocking. */
    private fun get(ctx: Context, url: String, server: NearbyServer, abort: Abort): String {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) throw NearbyFailure(NearbyFail.NO_NETWORK_PERMISSION)
        if (abort.cancelled || abort.remainingMs() < NearbySearch.MIN_MS) throw NearbySearch.ended(abort)
        val u = URL(url)
        require(u.protocol == "https" && u.host == server.host && u.port == server.port && u.path.startsWith(server.path + "/")) { "not the chosen Nominatim server" }
        val c = u.openConnection() as HttpURLConnection
        val forget = abort.onCancel { c.disconnect() }
        try {
            val timeout = minOf(TIMEOUT_MS.toLong(), abort.remainingMs()).toInt()
            c.connectTimeout = timeout
            c.readTimeout = timeout
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", Nearby.userAgent(BuildConfig.VERSION_NAME))
            c.setRequestProperty("Accept", "application/json")
            val code = c.responseCode
            Nearby.httpFail(code)?.let { throw NearbyFailure(it, code) }
            return c.inputStream.use(::read)
        } catch (f: NearbyFailure) {
            throw f
        } catch (e: Exception) {
            if (abort.cancelled || abort.expired) throw NearbySearch.ended(abort)
            throw when (e) {
                is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketTimeoutException -> NearbyFailure(NearbyFail.NO_INTERNET)
                is SecurityException -> NearbyFailure(NearbyFail.NO_NETWORK_PERMISSION)
                is IOException -> NearbyFailure(if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) NearbyFail.NO_NETWORK_PERMISSION else NearbyFail.NO_INTERNET)
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
            if (out.size() > MAX_BYTES) throw NearbyFailure(NearbyFail.SERVICE)
        }
        return out.toString(Charsets.UTF_8.name())
    }
}
