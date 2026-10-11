package app.heyrobot.helpers.http

import app.heyrobot.core.helpers.LanRule
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSource
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

// VOICE PROCESS ONLY, :claude module. The plain HTTP transport for every helper that isn't Claude (0.6.0): OkHttp 4.12.0,
// which the Anthropic SDK already brings. One client per helper and key.
// - A company (https only): OkHttp itself refuses http:// (no CLEARTEXT connection spec), on top of the network security
//   config that denies cleartext to the companies' domains.
// - Your own server: http:// only to addresses on your own network (core LanRule), checked three times: the address you
//   typed, every address its name resolves to (a DNS filter that drops public ones), and the socket actually connected
//   (a network interceptor), so a name that rebinds to a public host can't take Robot's words there. Never through a
//   proxy. Short connect timeout (a wrong address fails fast), long reads (a cold model may load for minutes).
// - Everyone: no redirects (a server can't bounce Robot elsewhere), no automatic retries (Robot retries by its own
//   rules), no cookies or cache, and a neutral User-Agent ("Robot") instead of OkHttp's, without device details. The
//   key goes in an Authorization header only, and never into a log: Robot logs status codes and exception class names.

/** At most [max] bytes of a reply's body as text: a server can't fill the voice process's memory with one answer. */
fun BufferedSource.textUpTo(max: Long): String {
    request(max)
    return buffer.readUtf8(minOf(buffer.size, max))
}

/** The connection is refused because the address isn't on your network (core LanRule). */
class NotLocalException : UnknownHostException("not on your network")

class HelperHttp private constructor(val client: OkHttpClient, val base: HttpUrl, private val key: String?) : AutoCloseable {
    /** A streamed POST of [body] to [path] under the base URL ("chat/completions"). */
    fun post(path: String, body: JSONObject, stream: Boolean = true): Call =
        client.newCall(request(path).post(body.toString().toRequestBody(JSON)).apply { if (stream) header("Accept", "text/event-stream") }.build())

    fun get(path: String): Call = client.newCall(request(path).get().header("Accept", "application/json").build())

    /** [path] relative to the base ("models"), or absolute on the same host ("/api/tags"). */
    fun url(path: String): HttpUrl = if (path.startsWith("/")) base.newBuilder().encodedPath(path).build()
        else base.newBuilder().addPathSegments(path).build()

    private fun request(path: String): Request.Builder = Request.Builder().url(url(path)).header("User-Agent", USER_AGENT).apply {
        if (!key.isNullOrEmpty()) header("Authorization", "Bearer $key")
    }

    override fun close() {
        try {
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        } catch (_: Throwable) {}
    }

    companion object {
        const val USER_AGENT = "Robot"
        private val JSON = "application/json".toMediaType()

        /**
         * A client for [baseUrl] with [key] (none for a server that doesn't ask for one). [home]: a server on your network
         * (cleartext allowed on private addresses only). [dns]: the system's in the app, a fake in tests. Null when the
         * address isn't one Robot will use at all (not http or https, or http to a public address for a company).
         */
        fun create(baseUrl: String, key: String?, home: Boolean, dns: Dns = Dns.SYSTEM, connectMs: Long = if (home) HOME_CONNECT_MS else CLOUD_CONNECT_MS): HelperHttp? {
            val base = baseUrl.trimEnd('/').toHttpUrlOrNull() ?: return null
            val https = base.isHttps
            val b = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .retryOnConnectionFailure(false)
                .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
                // The session watches silence itself (per effort); the socket's own limit is the longest of those.
                .readTimeout(READ_MS, TimeUnit.MILLISECONDS)
                .writeTimeout(WRITE_MS, TimeUnit.MILLISECONDS)
                .callTimeout(0, TimeUnit.MILLISECONDS)
            when {
                https -> b.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
                home -> b.connectionSpecs(listOf(ConnectionSpec.CLEARTEXT)).dns(LanDns(dns)).addNetworkInterceptor(LanGuard).proxy(Proxy.NO_PROXY)
                // A company over http: never. (OkHttp would refuse anyway with only TLS specs; refused here first.)
                else -> return null
            }
            if (home && https) b.proxy(Proxy.NO_PROXY)
            if (!https) {
                // An address typed as a number is never looked up: check it now. One in a form the rule doesn't read
                // ("3232235777", "10.1") would skip the DNS filter too: refused.
                if (LanRule.numericNotQuad(base.host)) return null
                LanRule.literal(base.host)?.let { if (!LanRule.classify(it).cleartext) return null }
            }
            return HelperHttp(b.build(), base, key)
        }

        const val CLOUD_CONNECT_MS = 15_000L
        const val HOME_CONNECT_MS = 5_000L
        const val READ_MS = 300_000L
        const val WRITE_MS = 30_000L
    }
}

/** Only the addresses on your network, for a cleartext connection: none left is a refusal. */
class LanDns(private val delegate: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val all = delegate.lookup(hostname)
        val ok = all.filter { LanRule.classify(it.address).cleartext }
        if (ok.isEmpty()) throw NotLocalException()
        return ok
    }
}

/** The socket really connected (an address typed as a number skips the DNS filter): cleartext only to your network. */
object LanGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.isHttps) {
            val addr = chain.connection()?.route()?.socketAddress?.address ?: throw NotLocalException()
            if (!LanRule.classify(addr.address).cleartext) throw NotLocalException()
        }
        return chain.proceed(req)
    }
}

/**
 * Server-sent events, one `data:` payload at a time (several data lines join with a newline). Comment lines (": …",
 * OpenRouter's keep-alives) and `event:` lines only show the line is alive.
 */
class SseReader(private val source: BufferedSource, private val maxLine: Long = MAX_LINE) {
    /** The next event's data, or null at the end of the stream. [alive] is called for every line read. */
    @Throws(IOException::class)
    fun next(alive: () -> Unit = {}): String? {
        val data = StringBuilder()
        var any = false
        while (true) {
            val line = line() ?: return if (any) data.toString() else null
            if (data.length + line.length > maxLine) throw IOException("event too long")
            alive()
            when {
                line.isEmpty() -> if (any) return data.toString()
                line.startsWith(":") -> {}
                line.startsWith("data:") -> {
                    if (any) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                    any = true
                }
                // event:, id:, retry: and anything else.
                else -> {}
            }
        }
    }

    /** One line, never longer than [maxLine] (a server can't fill the voice process's memory with one). */
    private fun line(): String? {
        if (source.indexOf('\n'.code.toByte(), 0, maxLine) < 0 && source.buffer.size >= maxLine) throw IOException("line too long")
        return source.readUtf8Line()
    }

    companion object {
        /** No event of a helper's stream is near this long (a whole answer sent as one event included). */
        const val MAX_LINE = 8L * 1024 * 1024
    }
}
