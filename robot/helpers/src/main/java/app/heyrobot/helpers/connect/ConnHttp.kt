// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.connect

import app.heyrobot.core.Abort
import app.heyrobot.core.connect.ConnAuth
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnKind
import app.heyrobot.core.connect.ConnReply
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnSecrets
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.connect.ConnWire
import app.heyrobot.core.connect.Connection
import app.heyrobot.core.connect.HttpMethod
import app.heyrobot.core.connect.HttpRequest
import app.heyrobot.core.connect.ReplyRead
import app.heyrobot.core.connect.SecretPart
import app.heyrobot.core.connect.Wire
import app.heyrobot.core.helpers.LanRule
import app.heyrobot.helpers.http.HelperHttp
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.helpers.http.NetCount
import app.heyrobot.helpers.http.NetTag
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLPeerUnverifiedException

// VOICE PROCESS ONLY, :helpers module. Skills connections' HTTP client (docs/plans/skills-connections-plan.md §4.2; C3):
// one OkHttp client, with a connection pool of its own, for each call, so nothing from one call (a socket, a lookup's
// kind) is ever reused by another. The order of a call, every rule decided in core:
// 1. ConnRules.refusal: no client for a connection Robot won't use (no address, a secret over http, a typed address
//    that isn't of a kind D1 allows).
// 2. ConnWire.assemble puts the connection's secrets (just read by ConnKeys, in this process) into the request, last,
//    and checks D2 again; the address it gives is parsed again here and must be the connection's own origin (scheme,
//    host, port), so a difference between two parsers can never send a key elsewhere.
// 3. D1 on every lookup (ConnDns: ConnRules.lookupHomeWins; one kind per call, a second lookup of another kind refused)
//    and on the socket really connected (ConnGuard, a network interceptor: ConnRules.socket), for http and https alike.
//    A host typed as an address is never looked up (OkHttp skips the resolver for it), so its kind is decided before the
//    call; the socket check refuses a socket whose call has no kind.
// 4. Everyone: no redirects (followed by neither OkHttp nor Robot: a 3xx is a failure), no retries, no cookies, no cache,
//    no proxy (one would hide the address really reached), `User-Agent: Robot`, http only with OkHttp's cleartext spec
//    and https only with its modern TLS one, the system's certificate checks as they are (Android's: system CAs only, the
//    network security config), connect 5 s at home and at most the call's 10 s (EST) otherwise, and the body read as
//    bytes, at most the cap and one byte more (ConnReply refuses a reply cut at the cap, never reads it in part; OkHttp's
//    transparent gzip means the cap counts what is decompressed).
// 5. ConnReply.read: 2xx only, JSON when wanted, from the bytes.
// C6: a routing server learns where you are, so over plain http its lookups and its socket must be loopback or
// Tailscale's 100.64/10 (ConnRules.nearOnly), whatever the address said: on someone else's Wi-Fi a private address can be
// anyone's machine. ConnRules.refusal already refuses any other typed address before the call.
// Stop ([Abort.cancel]) cancels the call at once; a write that was cancelled or timed out may have happened and is never
// sent again by itself ([ConnCall.sent]). Nothing here logs: a call's codes go to the voice log in C4, never a host, a
// path, a body or a reply. Every result is a core type, so the app's code names no network class.

/** What one call gave. [ms]: how long it took. */
sealed class ConnCall {
    abstract val ms: Long

    /**
     * The server answered: [read] is ConnReply's reading of the answer (a 2xx and its JSON, or a failure with its status).
     * [where]: the kind of address it came from (D1: Test keeps it). [bytes]: the body's bytes read (2xx only, at most the cap).
     */
    class Answered(val read: ReplyRead, val where: ConnWhere, val bytes: Int, override val ms: Long) : ConnCall() {
        override fun toString(): String = "Answered($read, ${where.id})" // l10n: not text (toString, for the logs)
    }

    /** No answer: [why]. [sent]: the request may have reached the server (a write may have happened: never sent again by itself). */
    data class Failed(val why: ConnFailure, val sent: Boolean, override val ms: Long) : ConnCall()

    /** Stop cancelled it at once. [sent] as for [Failed]. */
    data class Stopped(val sent: Boolean, override val ms: Long) : ConnCall()

    /** ConnReply's reading: an answer's, or the failure's; null when stopped. */
    val reply: ReplyRead? get() = when (this) {
        is Answered -> read
        is Failed -> ReplyRead.Failed(why)
        is Stopped -> null
    }
}

/** One exchange for MCP ([ConnHttp.exchange]): the reply as it came ([Got]: any status, its body read up to the limit), or how it [Ended]. */
sealed class ConnExchange {
    class Got(val status: Int, val contentType: String?, val bytes: ByteArray, val over: Boolean, val where: ConnWhere, val session: String?, val ms: Long) : ConnExchange() {
        override fun toString(): String = "Got($status)" // l10n: not text (toString, for the logs; never the body or the session)
    }

    /** [call]: a [ConnCall.Failed] or a [ConnCall.Stopped]. */
    class Ended(val call: ConnCall) : ConnExchange()
}

class ConnHttp internal constructor(
    /** The resolver; tests give a fake (the app never does: the constructor is internal to this module). */
    private val dns: Dns,
    /** Tests only: a local server's sockets and its test CA. Applied before every rule below, so it can't change one. */
    private val setup: OkHttpClient.Builder.() -> Unit,
    private val callMs: Long,
) {
    constructor() : this(Dns.SYSTEM, {}, CALL_MS)

    /**
     * Sends [r], built from connection [c] (ConnOps), with [secrets] (ConnKeys' read, just now) put in last, and reads at
     * most [max] bytes of the answer ([ConnReply.MAX_BYTES] for a step; [PICKER_MAX_BYTES] only for Test's picker reads).
     * [abort]: Stop cancels it at once. [cause]: what What went online says allowed it (a skill's step, or a tap on Test).
     * Blocks: a background thread. Never throws.
     */
    fun call(c: Connection, r: HttpRequest, secrets: ConnSecrets, abort: Abort = Abort(), max: Int = ConnReply.MAX_BYTES, cause: NetCause = NetCause.SKILL): ConnCall {
        require(max in 1..PICKER_MAX_BYTES)
        return when (val x = send(c, r, secrets, abort, max, cause, callMs, all = false, enough = null)) {
            is ConnExchange.Got -> ConnCall.Answered(ConnReply.read(x.status, x.contentType, x.bytes, x.over, r.json, max), x.where, x.bytes.size, x.ms)
            is ConnExchange.Ended -> x.call
        }
    }

    /**
     * One MCP exchange (McpClient, in :voice): [call]'s rules, the same checks in the same order, with three changes. Every
     * status's body is read (at most [max] bytes: McpWire reads only JSON-RPC from it, never shows the server's words), the
     * Mcp-Session-Id header is kept, and the call has [ms] (10 s to list, 30 s for a tool). [enough]: read so far, the reply
     * is whole (a stream's answer came), so the rest isn't read and closing the stream ends it.
     */
    fun exchange(c: Connection, r: HttpRequest, secrets: ConnSecrets, abort: Abort, ms: Long, max: Int = ConnReply.MAX_BYTES, cause: NetCause = NetCause.SKILL,
        enough: ((String?, ByteArray) -> Boolean)? = null): ConnExchange {
        require(max in 1..ConnReply.MAX_BYTES && c.kind == ConnKind.MCP)
        return send(c, r, secrets, abort, max, cause, ms.coerceAtLeast(1), all = true, enough = enough)
    }

    private fun send(c: Connection, r: HttpRequest, secrets: ConnSecrets, abort: Abort, max: Int, cause: NetCause, callMs: Long, all: Boolean,
        enough: ((String?, ByteArray) -> Boolean)?): ConnExchange {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        fun end(call: ConnCall) = ConnExchange.Ended(call)
        fun no(why: ConnFailure) = end(ConnCall.Failed(why, sent = false, ms = ms()))
        if (abort.cancelled) return end(ConnCall.Stopped(sent = false, ms = 0))
        if (abort.expired) return no(ConnFailure.TIMEOUT)
        // 1. No client for a connection Robot won't use; and the request must be this connection's own.
        ConnRules.refusal(c)?.let { return no(it) }
        val base = c.address ?: return no(ConnFailure.NOWHERE)
        if (r.base != base || r.secretAddress != c.secretAddress || r.auth != c.auth) return no(ConnFailure.NOWHERE)
        // 2. The secrets, last; then the address parsed again, which must be the connection's own origin.
        // Question 35's one exception (ConnRules.mcpUnencrypted): an MCP server on this phone, its token over plain http.
        val phone = ConnRules.mcpUnencrypted(c)
        val wire = when (val w = ConnWire.assemble(r, secrets, phone)) {
            is ConnWire.Result.Ok -> w.wire
            is ConnWire.Result.Refused -> return no(w.why)
        }
        val url = wire.url.toHttpUrlOrNull() ?: return no(ConnFailure.BAD_VALUE)
        if (!sameOrigin(url, base.https, base.host, base.port)) return no(ConnFailure.NOWHERE)
        // D2, once more where it is sent: nothing secret over http.
        // Question 35: an MCP server only over https, token or none, but for one typed as this phone (ConnRules.httpsOnly).
        if (!url.isHttps && ConnRules.mcpOverHttp(c)) return no(ConnFailure.MCP_HTTP)
        if (!url.isHttps && r.secrets.isNotEmpty() && !(phone && r.secrets == setOf(SecretPart.TOKEN))) return no(ConnFailure.KEY_OVER_HTTP)
        // 3. D1: a typed address is never looked up, so its kind is decided now.
        val place = CallPlace(url.isHttps, nearOnly = !url.isHttps && c.kind == ConnKind.ROUTING, keyless = c.kind == ConnKind.MCP && c.auth == ConnAuth.None,
            phoneOnly = !url.isHttps && ConnRules.httpsOnly(c.kind), homeOnly = ConnRules.homeOnly(c.kind))
        base.literal?.let { a ->
            when (val l = ConnRules.lookupHomeWins(url.isHttps, listOf(a))) {
                is ConnRules.Lookup.Refused -> return no(l.why)
                is ConnRules.Lookup.Ok -> place.decide(l)
            }
        }
        val request = try {
            request(wire, url).newBuilder().tag(NetTag::class.java, NetTag(NetPurpose.CONNECTION, cause)).build()
        } catch (_: IllegalArgumentException) {
            return no(ConnFailure.BAD_VALUE)
        }
        val home = !url.isHttps || c.where == ConnWhere.HOME || place.where == ConnWhere.HOME
        val events = Events()
        val client = client(place, events, if (home) HelperHttp.HOME_CONNECT_MS else HelperHttp.CLOUD_CONNECT_MS, minOf(callMs, abort.remainingMs()).coerceAtLeast(1))
        val call = client.newCall(request)
        val stopped = AtomicBoolean(false)
        val forget = abort.onCancel { stopped.set(true); call.cancel() }
        try {
            call.execute().use { resp ->
                val where = place.where ?: return no(ConnFailure.NOWHERE)
                val status = resp.code
                val type = resp.header(CONTENT_TYPE)
                var bytes = ByteArray(0)
                var over = false
                // Only a 2xx is read for a connection (ConnReply never reads another: a server's own error text is never used).
                if (all || status in 200..299) {
                    val source = resp.body?.source() ?: return end(ConnCall.Failed(ConnFailure.UNREACHABLE, sent = true, ms = ms()))
                    if (enough == null) {
                        over = source.request(max + 1L)
                        bytes = source.buffer.readByteArray(minOf(source.buffer.size, max.toLong()))
                    } else {
                        val got = Buffer()
                        while (source.read(got, CHUNK) != -1L) {
                            if (got.size > max) { over = true; break }
                            if (enough(type, got.copy().readByteArray())) break
                        }
                        bytes = got.readByteArray(minOf(got.size, max.toLong()))
                    }
                }
                return ConnExchange.Got(status, type, bytes, over, where, resp.header(SESSION), ms())
            }
        } catch (e: IOException) {
            return end(failed(e, events.sent, stopped.get(), ms()))
        } catch (e: RuntimeException) {
            // OkHttp's own checks (a header it won't send, say): nothing was sent.
            return end(if (stopped.get()) ConnCall.Stopped(events.sent, ms()) else ConnCall.Failed(ConnFailure.UNREACHABLE, events.sent, ms()))
        } finally {
            forget()
            client.connectionPool.evictAll()
        }
    }

    /** The client for one call: [setup] first (tests), then every rule, which nothing after changes. */
    private fun client(place: CallPlace, events: Events, connectMs: Long, callMs: Long): OkHttpClient = OkHttpClient.Builder()
        .apply(setup)
        .connectionPool(ConnectionPool())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .proxy(Proxy.NO_PROXY)
        .connectionSpecs(listOf(if (place.https) ConnectionSpec.MODERN_TLS else ConnectionSpec.CLEARTEXT))
        .dns(ConnDns(dns, place))
        .addInterceptor(NetCount(NetPurpose.CONNECTION))
        .addNetworkInterceptor(ConnGuard(place))
        .eventListener(events)
        .connectTimeout(minOf(connectMs, callMs), TimeUnit.MILLISECONDS)
        .readTimeout(callMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callMs, TimeUnit.MILLISECONDS)
        .callTimeout(callMs, TimeUnit.MILLISECONDS)
        .build()

    /** The request on the wire: [wire]'s method, headers (the key's last) and body, and Robot's User-Agent. */
    private fun request(wire: Wire, url: HttpUrl): Request {
        val b = Request.Builder().url(url)
        for ((name, value) in wire.headers) b.addHeader(name, value)
        b.header(USER_AGENT, HelperHttp.USER_AGENT)
        val body: RequestBody? = wire.body?.toRequestBody(wire.contentType?.let { it.toMediaTypeOrNull() ?: throw IllegalArgumentException() })
        when (wire.method) {
            HttpMethod.GET -> if (body != null) throw IllegalArgumentException() else b.get()
            HttpMethod.POST -> b.post(body ?: EMPTY.toRequestBody(null))
            HttpMethod.PUT -> b.put(body ?: EMPTY.toRequestBody(null))
            HttpMethod.PATCH -> b.patch(body ?: EMPTY.toRequestBody(null))
            HttpMethod.DELETE -> b.delete(body)
        }
        return b.build()
    }

    /** Why a call that threw failed: a refusal of D1's, Stop, a certificate Android doesn't trust, no answer in time, or no connection. */
    private fun failed(e: IOException, sent: Boolean, stopped: Boolean, ms: Long): ConnCall {
        causes(e).firstNotNullOfOrNull { (it as? ConnRefused)?.why }?.let { return ConnCall.Failed(it, sent, ms) }
        // Stop, told apart from the call's own timeout (which cancels the call too) by the hook Stop ran.
        if (stopped) return ConnCall.Stopped(sent, ms)
        // Only a certificate's path or the name in it: any other handshake failure is "can't reach".
        if (causes(e).any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException }) return ConnCall.Failed(ConnFailure.CERTIFICATE, sent, ms)
        if (causes(e).any { it is InterruptedIOException }) return ConnCall.Failed(ConnFailure.TIMEOUT, sent, ms)
        return ConnCall.Failed(ConnFailure.UNREACHABLE, sent, ms)
    }

    /** Whether the request may have reached the server: its headers began to go out. */
    private class Events : EventListener() {
        @Volatile var sent = false

        override fun requestHeadersStart(call: Call) {
            sent = true
        }
    }

    companion object {
        /** The whole call, connecting included (D15, EST). */
        const val CALL_MS = 10_000L
        private const val CHUNK = 8_192L
        private const val SESSION = "Mcp-Session-Id" // l10n: not text (a header)
        /** Test's picker reads only (Home Assistant's /api/states and /api/services can be far over a step's 256 KiB: C5; EST). */
        const val PICKER_MAX_BYTES = 4 * 1024 * 1024
        private const val USER_AGENT = "User-Agent" // l10n: not text (a header name)
        private const val CONTENT_TYPE = "Content-Type" // l10n: not text (a header name)
        private val EMPTY = ByteArray(0)

        /** [url] is the connection's own origin: the scheme, the host (a typed address by its bytes) and the port. */
        internal fun sameOrigin(url: HttpUrl, https: Boolean, host: String, port: Int?): Boolean {
            if (url.isHttps != https) return false
            if (url.port != (port ?: if (https) 443 else 80)) return false
            val typed = LanRule.literal(host)
            return if (typed != null) LanRule.literal(url.host)?.contentEquals(typed) == true else url.host == host
        }

        internal fun causes(t: Throwable): Sequence<Throwable> = generateSequence(t) { it.cause?.takeIf { c -> c !== it } }.take(12)
    }
}

/** D1 refused this call ([why]), at a lookup or at the socket: nothing was sent. Its message names no host. */
internal class ConnRefused(val why: ConnFailure) : IOException("refused") // l10n: not text (an exception's message)

/**
 * The kind of address one call uses (D1): decided by its first lookup (or, for a typed address, before the call), and
 * every later lookup in the call must agree.
 */
internal class CallPlace(val https: Boolean, val nearOnly: Boolean = false, val keyless: Boolean = false, val homeOnly: Boolean = false, val phoneOnly: Boolean = false) {
    @Volatile var where: ConnWhere? = null
        private set

    @Synchronized
    fun decide(l: ConnRules.Lookup.Ok) {
        val was = where
        if (was != null && was != l.where) throw ConnRefused(ConnFailure.BOTH_KINDS)
        where = l.where
    }

    /** ConnRules.lookupHomeWins on [resolved]: the addresses to use, or a refusal ([ConnRefused]). */
    fun lookup(resolved: List<ByteArray>): List<ByteArray> = when (val l = ConnRules.lookupHomeWins(https, resolved)) {
        is ConnRules.Lookup.Refused -> throw ConnRefused(l.why)
        is ConnRules.Lookup.Ok -> {
            // C6: where you are, over http, only to loopback or Tailscale.
            if (nearOnly && l.addresses.any { !ConnRules.nearOnly(it) }) throw ConnRefused(ConnFailure.LOCATION_OVER_HTTP)
            // D2 for MCP: a server on this phone without a token is refused, whatever name led there (any app can reach it).
            if (keyless && l.addresses.any(ConnRules::loopback)) throw ConnRefused(ConnFailure.LOOPBACK_NO_KEY)
            // Question 35: an MCP server over plain http only on this phone, whatever name led there.
            if (phoneOnly && l.addresses.any { !ConnRules.loopback(it) }) throw ConnRefused(ConnFailure.MCP_HTTP)
            // Never the public internet for an MCP server (ConnRules.homeOnly), whatever name led there.
            if (homeOnly && l.where != ConnWhere.HOME) throw ConnRefused(ConnFailure.MCP_PUBLIC)
            decide(l)
            l.addresses
        }
    }

    /** Why the socket really connected to [a] is refused (null: it is of this call's kind). No kind decided: refused. */
    fun socket(a: ByteArray): ConnFailure? {
        val w = where ?: return ConnFailure.NOWHERE
        if (nearOnly && !ConnRules.nearOnly(a)) return ConnFailure.LOCATION_OVER_HTTP
        if (keyless && ConnRules.loopback(a)) return ConnFailure.LOOPBACK_NO_KEY
        if (phoneOnly && !ConnRules.loopback(a)) return ConnFailure.MCP_HTTP
        if (homeOnly && w != ConnWhere.HOME) return ConnFailure.MCP_PUBLIC
        return if (ConnRules.socket(a, w)) null else if (w == ConnWhere.HOME) ConnFailure.MOVED else ConnFailure.NOWHERE
    }
}

/** Every lookup through D1: only the addresses it keeps, as the bytes it checked; a refusal throws. */
internal class ConnDns(private val delegate: Dns, private val place: CallPlace) : Dns {
    override fun lookup(hostname: String): List<InetAddress> =
        place.lookup(delegate.lookup(hostname).map { it.address }).map { InetAddress.getByAddress(hostname, it) }
}

/**
 * The socket really connected (a typed address skips the resolver; a lookup's kind is the call's): of the call's kind,
 * on every scheme, before a byte of the request is written.
 */
internal class ConnGuard(private val place: CallPlace) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val a = chain.connection()?.route()?.socketAddress?.address ?: throw ConnRefused(ConnFailure.NOWHERE)
        place.socket(a.address)?.let { throw ConnRefused(it) }
        return chain.proceed(chain.request())
    }
}
