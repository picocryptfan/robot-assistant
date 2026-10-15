// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.core.web.SearchRequest
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebLimits
import app.heyrobot.helpers.http.LanDns
import app.heyrobot.helpers.http.LanGuard
import app.heyrobot.helpers.http.NotLocalException
import app.heyrobot.helpers.http.textUpTo
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

// VOICE PROCESS ONLY, :helpers module (0.9 web search, design A6.1). How a search request goes out: core's SearchWire
// builds it, this sends it as it is, and core parses the answer. Two kinds of destination:
// - CLOUD, a company or a public SearXNG: https only (OkHttp's TLS specs alone, and the network security config denies
//   cleartext to the companies' domains), the system's DNS and proxy.
// - HOME, your own SearXNG: exactly as 0.6.0 reaches a home helper (core LanRule): http:// only to addresses on your
//   own network (a DNS filter that drops public ones, and a network interceptor on the socket really connected), never
//   through a proxy; https:// (Tailscale) anywhere.
// Everyone: no redirects, no retries, no cookies or cache, the honest API User-Agent the request carries. A request's
// URL can hold a key (Mojeek): nothing here logs a URL, a header or a body. Stop disconnects (Abort). 1.0.1: a request
// that carries a key (SearchRequest.carriesKey) never goes over http, even to your own SearXNG (core LanRule.keyOverHttp).

/** A service's answer: its HTTP status, the body (at most [WebTransport.MAX_BODY]) and its Retry-After. */
data class Reply(val status: Int, val body: String?, val retryAfter: String? = null)

/** Why a request got no reply at all. */
class WebFailure(val why: WebFail) : IOException(why.name)

/** Sends one search request as it is (WebTransport on the phone; tests answer for a service). */
fun interface WebSender {
    /** The reply, or [WebFailure] with why none came. */
    fun send(req: SearchRequest, route: WebTransport.Route, abort: Abort): Reply
}

class WebTransport(private val dns: Dns = Dns.SYSTEM, private val connectMs: Long = WebLimits.CONNECT_MS, private val readMs: Long = WebLimits.SEARCH_MS) : WebSender {
    enum class Route { CLOUD, HOME }

    private fun builder(): OkHttpClient.Builder = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
        .readTimeout(readMs, TimeUnit.MILLISECONDS)
        .writeTimeout(readMs, TimeUnit.MILLISECONDS)
        .callTimeout(readMs + connectMs, TimeUnit.MILLISECONDS)

    private val cloud: OkHttpClient by lazy { builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).dns(dns).build() }
    private val homeTls: OkHttpClient by lazy { builder().connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).dns(dns).proxy(Proxy.NO_PROXY).build() }
    private val homeClear: OkHttpClient by lazy {
        builder().connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).dns(LanDns(dns)).addNetworkInterceptor(LanGuard).proxy(Proxy.NO_PROXY).build()
    }

    /**
     * Sends [req] by [route] and reads the reply, or throws [WebFailure] with why nothing came back. [abort]: Stop and the
     * search's deadline disconnect it at once.
     */
    override fun send(req: SearchRequest, route: Route, abort: Abort): Reply {
        val url = req.url.toHttpUrlOrNull() ?: throw WebFailure(WebFail.SERVICE)
        val client = when {
            url.isHttps -> if (route == Route.HOME) homeTls else cloud
            route == Route.CLOUD -> throw WebFailure(WebFail.SERVICE)
            LanRule.keyOverHttp(https = false, key = if (req.carriesKey) "key" else null) -> throw WebFailure(WebFail.KEY_OVER_HTTP)
            else -> {
                // Your SearXNG over http: an address typed as a number is checked now (the DNS filter never sees it).
                if (LanRule.numericNotQuad(url.host)) throw WebFailure(WebFail.NOT_HOME)
                LanRule.literal(url.host)?.let { if (!LanRule.classify(it).cleartext) throw WebFailure(WebFail.NOT_HOME) }
                homeClear
            }
        }
        val b = Request.Builder().url(url)
        for ((k, v) in req.headers) b.header(k, v)
        if (req.method == "POST") b.post((req.body ?: "").toRequestBody((req.header("Content-Type") ?: "application/json").toMediaType())) else b.get()
        if (abort.cancelled) throw WebFailure(stopped(abort))
        val call = client.newCall(b.build())
        val forget = abort.onCancel { call.cancel() }
        try {
            call.execute().use { r ->
                val body = r.body?.source()?.textUpTo(MAX_BODY)
                return Reply(r.code, body, r.header("Retry-After"))
            }
        } catch (e: IOException) {
            if (abort.cancelled) throw WebFailure(stopped(abort))
            throw WebFailure(why(e, route))
        } finally {
            forget()
        }
    }

    private fun stopped(abort: Abort) = if (abort.expired) WebFail.TIMEOUT else WebFail.STOPPED

    companion object {
        /** A search answer is never near this long (a SearXNG answer with 30 results is about 60 KB). */
        const val MAX_BODY = 2L * 1024 * 1024

        /** A connection's failure in the card's words: your SearXNG off the home network, no internet, too slow… */
        fun why(e: IOException, route: Route): WebFail = when (e) {
            is WebFailure -> e.why
            is NotLocalException -> WebFail.NOT_HOME
            is app.heyrobot.helpers.http.KeyOverHttpException -> WebFail.KEY_OVER_HTTP
            is UnknownHostException -> if (route == Route.HOME) WebFail.NOT_HOME else WebFail.NO_INTERNET
            is ConnectException -> if (route == Route.HOME) WebFail.NOT_ANSWERING else WebFail.NO_INTERNET
            is InterruptedIOException -> WebFail.TIMEOUT
            is SSLException -> WebFail.NOT_ANSWERING
            else -> WebFail.NOT_ANSWERING
        }
    }
}
