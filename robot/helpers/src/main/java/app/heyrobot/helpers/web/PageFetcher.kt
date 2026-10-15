// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.web.HostKind
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.PageType
import app.heyrobot.core.web.PublicAddress
import app.heyrobot.core.web.UrlCheck
import app.heyrobot.core.web.UrlPolicy
import app.heyrobot.core.web.WebLimits
import app.heyrobot.core.web.WebPlan
import app.heyrobot.core.web.WebUrl
import app.heyrobot.core.web.WebUserAgent
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray

// VOICE PROCESS ONLY, :helpers module (0.9 web search, design A7 and A10.4). Reads up to 3 result pages from the phone,
// like a browser without cookies: only URLs from the search service's own answer (core WebPlan.pickPages), HTTPS only,
// the default port, and public unicast addresses only (an SSRF guard, the inverse of LanRule), checked on every address
// a name resolves to (a DNS filter: a name with a public and a private address keeps the public one) and on the socket
// really connected (a network interceptor, so DNS rebinding can't reach your router), at every redirect hop (at most 3,
// each checked again; never a <meta refresh>, never JavaScript). At most 1 MiB of decompressed body a page, HTML or
// plain text only (a PDF or a video is skipped with its reason), no cookies, no Referer, no cache, no images or other
// resources, never through a proxy (one would hide the address really reached). Pages are read in parallel within one
// deadline (6 s a page, 10 s in all); Stop disconnects them all. Nothing here logs a URL or a page.

/** The rules a page fetch follows ([STRICT] on the phone; tests loosen the scheme and port for a local server, never the address rules). */
class PageRules(
    val httpsOnly: Boolean = true,
    val defaultPortOnly: Boolean = true,
    /** Whether an address may be reached: core PublicAddress.isPublic. */
    val isPublic: (ByteArray) -> Boolean = PublicAddress::isPublic,
) {
    /** Why [u] may not be fetched, or null. */
    fun check(u: WebUrl): PageSkip? = when {
        httpsOnly && !u.https -> PageSkip.NOT_SECURE
        defaultPortOnly && u.port != -1 -> PageSkip.PORT
        u.kind == HostKind.NAME && PublicAddress.localName(u.host) -> PageSkip.NOT_PUBLIC
        u.kind == HostKind.IPV4 && PublicAddress.ipv4Loose(u.host)?.let(isPublic) != true -> PageSkip.NOT_PUBLIC
        u.kind == HostKind.IPV6 && PublicAddress.ipv6(u.host)?.let(isPublic) != true -> PageSkip.NOT_PUBLIC
        else -> null
    }

    /** A redirect from [from] to [location] after [n] redirects: at most [UrlPolicy.MAX_REDIRECTS], resolved, then [check]ed. */
    fun redirect(from: WebUrl, location: String?, n: Int): UrlCheck {
        if (n >= UrlPolicy.MAX_REDIRECTS) return UrlCheck.Refused(PageSkip.TOO_MANY_REDIRECTS)
        val target = location?.trim()?.takeIf { it.isNotEmpty() }?.let { UrlPolicy.resolve(from, it) } ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        val u = WebUrl.parse(target) ?: return UrlCheck.Refused(PageSkip.NOT_WEB)
        return check(u)?.let { UrlCheck.Refused(it) } ?: UrlCheck.Ok(u)
    }

    companion object {
        val STRICT = PageRules()
    }
}

/** No public address left for a name: the page is skipped as "not a public address". */
class NotPublicException : UnknownHostException("not a public address")

/** Only the public addresses a name resolves to (none left: refused). */
class PublicOnlyDns(private val delegate: Dns, private val isPublic: (ByteArray) -> Boolean) : Dns {
    override fun lookup(hostname: String): List<InetAddress> {
        val ok = delegate.lookup(hostname).filter { isPublic(it.address) }
        if (ok.isEmpty()) throw NotPublicException()
        return ok
    }
}

/** The socket really connected (an address typed as a number skips the DNS filter, a name may rebind): public only. */
class PublicGuard(private val isPublic: (ByteArray) -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val addr = chain.connection()?.route()?.socketAddress?.address ?: throw NotPublicException()
        if (!isPublic(addr.address)) throw NotPublicException()
        return chain.proceed(chain.request())
    }
}

class PageFetcher(dns: Dns = Dns.SYSTEM, private val rules: PageRules = PageRules.STRICT, private val pageMs: Long = WebLimits.PAGE_MS,
    private val allMs: Long = WebLimits.PAGES_MS, private val maxBytes: Int = WebLimits.PAGE_BYTES) {
    /** A page read: where it ended up ([url], after redirects), what it is, and its bytes ([truncated] at the cap). */
    class Page(val pick: WebPlan.Pick, val url: WebUrl, val kind: PageType.Kind, val contentType: String?, val bytes: ByteArray, val truncated: Boolean)

    sealed class Result {
        abstract val pick: WebPlan.Pick
        data class Read(val page: Page) : Result() { override val pick get() = page.pick }
        data class Skipped(override val pick: WebPlan.Pick, val why: PageSkip) : Result()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .connectTimeout(WebLimits.CONNECT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(pageMs, TimeUnit.MILLISECONDS)
        .callTimeout(pageMs, TimeUnit.MILLISECONDS)
        .connectionSpecs(if (rules.httpsOnly) listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.COMPATIBLE_TLS) else listOf(ConnectionSpec.MODERN_TLS, ConnectionSpec.CLEARTEXT))
        .dns(PublicOnlyDns(dns, rules.isPublic))
        .addNetworkInterceptor(PublicGuard(rules.isPublic))
        .proxy(Proxy.NO_PROXY)
        .build()

    /**
     * Reads [picks] in parallel within one deadline ([allMs], or what [abort] has left), in the question's [lang]
     * (Accept-Language). [progress] gets the number done after each page. A page not back in time is "took too long";
     * Stop ([abort]) disconnects them all.
     */
    fun fetchAll(picks: List<WebPlan.Pick>, lang: String?, abort: Abort, progress: (Int) -> Unit = {}): List<Result> {
        if (picks.isEmpty()) return emptyList()
        val results = AtomicReferenceArray<Result>(picks.size)
        val calls = java.util.Collections.synchronizedList(ArrayList<Call>())
        val done = CountDownLatch(picks.size)
        val count = AtomicInteger()
        val pool = Executors.newFixedThreadPool(picks.size.coerceAtMost(3)) { r -> Thread(r, "robot-page").apply { isDaemon = true } }
        val forget = abort.onCancel { synchronized(calls) { calls.forEach { it.cancel() } } }
        val got: List<Result?>
        try {
            picks.forEachIndexed { i, p ->
                pool.execute {
                    results.set(i, try { fetch(p, lang, calls, abort) } catch (_: Throwable) { Result.Skipped(p, PageSkip.FAILED) })
                    progress(count.incrementAndGet())
                    done.countDown()
                }
            }
            val wait = minOf(allMs, abort.remainingMs())
            val inTime = done.await(wait, TimeUnit.MILLISECONDS)
            // What came back in time; the rest took too long, and are disconnected.
            got = List(picks.size) { results.get(it) }
            if (!inTime) synchronized(calls) { calls.forEach { it.cancel() } }
        } finally {
            forget()
            pool.shutdownNow()
        }
        return picks.mapIndexed { i, p -> got[i] ?: Result.Skipped(p, PageSkip.TOO_SLOW) }
    }

    /** One page: its URL and every redirect hop checked, then at most [maxBytes] of an HTML or text answer. */
    fun fetch(pick: WebPlan.Pick, lang: String?, calls: MutableList<Call> = ArrayList(), abort: Abort = Abort()): Result {
        var url = pick.url
        rules.check(url)?.let { return Result.Skipped(pick, it) }
        var hops = 0
        while (true) {
            if (abort.cancelled) return Result.Skipped(pick, PageSkip.TOO_SLOW)
            val http = url.toString().toHttpUrlOrNull() ?: return Result.Skipped(pick, PageSkip.NOT_WEB)
            val req = Request.Builder().url(http).get().apply { for ((k, v) in WebUserAgent.pageHeaders(lang)) header(k, v) }.build()
            val call = client.newCall(req)
            calls += call
            try {
                call.execute().use { r ->
                    if (r.isRedirect) {
                        when (val next = rules.redirect(url, r.header("Location"), hops)) {
                            is UrlCheck.Refused -> return Result.Skipped(pick, next.why)
                            is UrlCheck.Ok -> {
                                url = next.url
                                hops++
                            }
                        }
                        return@use
                    }
                    if (r.code != 200) return Result.Skipped(pick, PageSkip.FAILED)
                    val type = r.header("Content-Type")
                    val kind = PageType.of(type) ?: return Result.Skipped(pick, PageType.skip(type))
                    val source = r.body?.source() ?: return Result.Skipped(pick, PageSkip.FAILED)
                    val more = source.request(maxBytes.toLong() + 1)
                    val bytes = source.buffer.readByteArray(minOf(source.buffer.size, maxBytes.toLong()))
                    return Result.Read(Page(pick, url, kind, type, bytes, truncated = more))
                }
            } catch (e: IOException) {
                return Result.Skipped(pick, skipFor(e, abort))
            } finally {
                calls -= call
            }
        }
    }

    private fun skipFor(e: IOException, abort: Abort): PageSkip = when {
        e is NotPublicException || e.cause is NotPublicException -> PageSkip.NOT_PUBLIC
        abort.cancelled || e is InterruptedIOException -> PageSkip.TOO_SLOW
        else -> PageSkip.FAILED
    }
}
