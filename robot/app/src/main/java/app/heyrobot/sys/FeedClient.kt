// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import app.heyrobot.core.news.FeedHttp
import app.heyrobot.core.news.FeedTls
import app.heyrobot.core.news.FeedLinks
import app.heyrobot.core.news.FeedXml
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnWhere
import app.heyrobot.core.news.NewsRules
import app.heyrobot.core.news.NewsSecret
import app.heyrobot.core.news.OwnFeed
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext

// Today H7 (home-design §6.1, "Through what" and "Limits and cost"): the one client for news feeds, direct. https only, no
// cookies (Robot sets no CookieHandler, and none is sent), a common browser's user agent, no referrer, redirects followed by hand
// (at most 3, each checked again: https, a name that resolves to public addresses only), If-None-Match and
// If-Modified-Since (to the host that gave them only), at most 512 KB and 15 s a feed, all hops and the name lookup included. Each request is recorded in What went online (NetPurpose.FEED, its
// host only) in its request function's finally block. Blocking: never on the main thread.
//
// H7b, through Tor: with a [Tor] given, a request never goes direct. :link dials the feed's host through Tor's SOCKS (its
// own circuit per host) and hands the stream over (LinkClient.feedStream); here the platform's TLS runs over it (core
// FeedTls: the system's CAs, the name checked twice) and core FeedHttp writes the one GET and reads the answer, capped as
// the direct path is. No DNS lookup happens on the phone (Tor resolves the name at its exit), and a stream Tor can't give
// fails the feed (NewsFail.NO_TOR).
//
// H8, your own server: ConnRules decides where each request may go (core OwnFeed.lookup): a name's addresses all on the
// internet or all on your network, plain http only to your network, and every hop of a request of the kind its first was.
// A request to your network connects its own socket to the address the lookup checked, and checks the socket really
// connected (ConnRules.socket), so a second lookup can't move it ([homeGet]); https there runs the platform's TLS over it as
// the Tor path does. NewsStore sends an own feed here direct even with Tor chosen (Tor can't reach your network).
object FeedClient {
    const val TIMEOUT_MS = 15_000L
    const val TOR_TIMEOUT_MS = 60_000L
    /** today-h7-fix 9: the whole time a feed gets ([TIMEOUT_MS]; the tests set less). */
    @Volatile internal var timeoutMs = TIMEOUT_MS
    /** fix-today-tor: through Tor the dial (a new circuit per host) counts too, so its own bound, [TOR_TIMEOUT_MS]. */
    @Volatile internal var torTimeoutMs = TOR_TIMEOUT_MS
    /** The name lookup; the tests set a slow one. */
    @Volatile internal var resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) }
    const val MAX_REDIRECTS = 3
    /**
     * today-h7-fix 3c: the whole reduced user agent Chrome on Android sends (its version a recent stable one, raised with each
     * release), so a request looks like a phone's browser and says nothing about Robot or the phone.
     */
    const val AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36" // l10n: not text (a header)
    private const val ACCEPT = "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.9, text/html;q=0.5" // l10n: not text (a header)

    /** A reply: its status, its body (null on 304), the address it finally came from, and its validators. [cut]: a page's first bytes. */
    class Reply(val code: Int, val body: ByteArray?, val url: String, val etag: String?, val modified: String?, val cut: Boolean = false)

    class Failure(val why: NewsFail) : IOException(why.name)

    /** Through Tor: a stream to [host]:[port] through it, or null when it can't give one. Owned and closed here. */
    fun interface Tor {
        fun stream(host: String, port: Int): ParcelFileDescriptor?
    }

    /**
     * [url]'s reply, following at most [MAX_REDIRECTS] redirects; anything but 200 or 304 throws [Failure]. today-h7-fix 4:
     * [etag] and [modified] go only to [url]'s host (the caller gives them only when that host gave them); a redirect to
     * another host sends neither. 9: one deadline for all of it. 7: [page] (Find's page) keeps its first 512 KB.
     */
    fun fetch(ctx: Context, url: String, cause: NetCause, etag: String?, modified: String?, tor: Tor? = null, page: Boolean = false): Reply {
        val deadline = System.currentTimeMillis() + if (tor != null) torTimeoutMs else timeoutMs
        val host = FeedLinks.host(url)
        var at = url
        val kind = arrayOfNulls<ConnWhere>(1)
        // Q35: a fetch whose address carries a token keeps to https, or plain http on this phone, on every hop (OwnFeed.hopRefused).
        val secret = NewsSecret.carries(url)
        repeat(MAX_REDIRECTS + 1) {
            val same = FeedLinks.host(at) == host
            val e = etag.takeIf { same }; val m = modified.takeIf { same }
            if (OwnFeed.hopRefused(url, at, secret)) throw Failure(NewsFail.TOKEN_OVER_HTTP)
            val r = if (tor != null) torGet(ctx, tor, at, cause, e, m, deadline, page) else get(ctx, at, cause, e, m, deadline, page, kind, secret)
            if (r.code !in 300..399) return r
            at = r.url
        }
        throw Failure(NewsFail.SERVER)
    }

    private fun left(deadline: Long): Long = (deadline - System.currentTimeMillis()).also { if (it <= 0) throw Failure(NewsFail.NO_INTERNET) }

    /**
     * The lookup, bounded by [deadline] (InetAddress has no timeout of its own: it runs on a lookup thread, left behind when
     * late), and what ConnRules decided for it (H8: core OwnFeed.lookup; refused, the feed fails).
     */
    private fun where(url: String, host: String, deadline: Long): ConnRules.Lookup.Ok {
        val found = try {
            LOOKUP.submit<Array<InetAddress>> { resolve(host) }.get(left(deadline), TimeUnit.MILLISECONDS)
        } catch (_: Exception) {
            throw Failure(NewsFail.NO_INTERNET)
        }
        return when (val l = OwnFeed.lookup(url, found.map { it.address })) {
            is ConnRules.Lookup.Ok -> l
            // today-h8-fix 7: plain http to the internet, or your own server's name resolving there, says so.
            is ConnRules.Lookup.Refused -> throw Failure(if (l.why == ConnFailure.MOVED) NewsFail.NOT_HOME else NewsFail.SERVER)
        }
    }

    private val LOOKUP = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r, "robot-feed-lookup").apply { isDaemon = true } } // l10n: not text (a thread's name)

    /** One request. A redirect comes back as its code with the next address (checked) as [Reply.url]. */
    private fun get(ctx: Context, url: String, cause: NetCause, etag: String?, modified: String?, deadline: Long, page: Boolean, kind: Array<ConnWhere?>, secret: Boolean = false): Reply {
        // GrapheneOS's Network toggle is the INTERNET permission at run time.
        if (ctx.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) throw Failure(NewsFail.NO_PERMISSION)
        val u = URL(url)
        if ((u.protocol != "https" && u.protocol != "http") || u.userInfo != null) throw Failure(NewsFail.SERVER)
        // H8: ConnRules decides. Your network goes through homeGet (plain http only there); the internet only over https.
        val at = where(url, u.host, deadline)
        if (!OwnFeed.sameKind(kind[0], at.where)) throw Failure(NewsFail.SERVER)
        kind[0] = at.where
        val near = secret && u.protocol != "https"
        if (OwnFeed.tokenRefused(url, secret)) throw Failure(NewsFail.TOKEN_OVER_HTTP)
        if (at.where == ConnWhere.HOME) return homeGet(url, at.addresses, cause, etag, modified, deadline, page, near)
        if (u.protocol != "https") throw Failure(NewsFail.NOT_HOME)
        val c = u.openConnection() as HttpURLConnection
        val got = longArrayOf(0)
        var outcome: NetResult? = NetResult.FAILED
        // Past the deadline the connection is shut, which ends a connect or read still waiting.
        val stop = WATCHDOG.schedule({ try { c.disconnect() } catch (_: Exception) {} }, left(deadline), TimeUnit.MILLISECONDS)
        try {
            c.connectTimeout = left(deadline).toInt()
            c.readTimeout = left(deadline).toInt()
            c.instanceFollowRedirects = false
            c.useCaches = false
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", ACCEPT)
            // 11: asked for by hand, so the bytes counted are the ones that came over the network (unzipped here, capped after).
            c.setRequestProperty("Accept-Encoding", "gzip") // l10n: not text (a header)
            etag?.let { c.setRequestProperty("If-None-Match", it) }
            modified?.let { c.setRequestProperty("If-Modified-Since", it) }
            val code = c.responseCode
            when {
                code == 304 -> { outcome = NetResult.DONE; return Reply(304, null, url, etag, modified) }
                code in 301..308 && code != 304 && code != 305 && code != 306 -> {
                    outcome = NetResult.DONE
                    val next = c.getHeaderField("Location")?.let { FeedLinks.clean(it, url) }?.takeIf { it.startsWith("https://") } ?: throw Failure(NewsFail.SERVER)
                    return Reply(code, null, next, null, null)
                }
                code != 200 -> throw Failure(NewsFail.SERVER)
            }
            if (!page && c.contentLengthLong > FeedXml.MAX_BYTES) throw Failure(NewsFail.TOO_BIG)
            val zipped = c.contentEncoding?.trim()?.lowercase()
            if (zipped != null && zipped != "gzip" && zipped != "identity") throw Failure(NewsFail.SERVER)
            val cut = BooleanArray(1)
            val body = Counted(c.inputStream, got).use { raw -> (if (zipped == "gzip") java.util.zip.GZIPInputStream(raw) else raw).use { read(it, deadline, page, cut) } }
            outcome = NetResult.DONE
            return Reply(200, body, url, c.getHeaderField("ETag"), c.getHeaderField("Last-Modified"), cut[0])
        } catch (f: Failure) {
            throw f
        } catch (e: SecurityException) {
            outcome = null
            throw Failure(NewsFail.NO_PERMISSION)
        } catch (e: IOException) {
            // A socket refused by the Network toggle fails with EACCES: nothing went online.
            if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) { outcome = null; throw Failure(NewsFail.NO_PERMISSION) } // l10n: not text (the system's error message, matched)
            throw Failure(NewsFail.NO_INTERNET)
        } finally {
            stop.cancel(false)
            c.disconnect()
            outcome?.let { NetRecord.record(NetPurpose.FEED, u.host, cause, 0, got[0], it) }
        }
    }

    /**
     * H8: one request to your own network, at the [addresses] its lookup checked (all home), on a socket of its own whose
     * connected address is checked again; https runs the platform's TLS over it (the name checked twice, as through Tor).
     * A redirect may go only to an address Robot would keep as a feed's (the next hop's lookup keeps it home). Recorded in
     * its finally block, as [get] is.
     */
    /** Internal for tests (FeedOffNetworkTest). */
    internal fun homeGet(url: String, addresses: List<ByteArray>, cause: NetCause, etag: String?, modified: String?, deadline: Long, page: Boolean, near: Boolean = false): Reply {
        val t = FeedHttp.target(url, home = true) ?: throw Failure(NewsFail.SERVER)
        val got = longArrayOf(0)
        var outcome: NetResult? = NetResult.FAILED
        try {
            homeSocket(addresses, t.port, deadline, near).use { s ->
                // The feed's deadline: past it the socket is shut, which ends any read waiting on it.
                val stop = WATCHDOG.schedule({ try { s.close() } catch (_: Exception) {} }, left(deadline), TimeUnit.MILLISECONDS)
                try {
                    s.soTimeout = left(deadline).toInt()
                    val input = s.getInputStream(); val output = s.getOutputStream()
                    val a = if (t.https) {
                        val engine = SSLContext.getDefault().createSSLEngine(t.host, t.port).apply {
                            useClientMode = true
                            sslParameters = sslParameters.apply {
                                endpointIdentificationAlgorithm = "HTTPS" // l10n: not text (JSSE's name)
                                if (FeedHttp.name(t.host)) serverNames = listOf(SNIHostName(t.host))
                            }
                        }
                        val tls = FeedTls(engine, input, output)
                        tls.handshake()
                        if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(t.host.removePrefix("[").removeSuffix("]"), engine.session)) throw Failure(NewsFail.SERVER)
                        tls.write(FeedHttp.request(t, AGENT, ACCEPT, etag, modified))
                        FeedHttp.read(tls.reader, FeedXml.MAX_BYTES, { got[0] += it }, { System.currentTimeMillis() > deadline }, cut = page).also { tls.close() }
                    } else {
                        output.write(FeedHttp.request(t, AGENT, ACCEPT, etag, modified)); output.flush()
                        FeedHttp.read(input, FeedXml.MAX_BYTES, { got[0] += it }, { System.currentTimeMillis() > deadline }, cut = page)
                    }
                    outcome = NetResult.DONE
                    return when {
                        a.code == 304 -> Reply(304, null, url, etag, modified)
                        a.code in 301..308 && a.code != 304 && a.code != 305 && a.code != 306 -> {
                            val next = a.header("Location")?.let { FeedLinks.clean(it, url) }?.takeIf { NewsRules.address(it) == it && OwnFeed.homeHop(url, it) } ?: throw Failure(NewsFail.SERVER)
                            Reply(a.code, null, next, null, null)
                        }
                        a.code != 200 -> throw Failure(NewsFail.SERVER)
                        else -> Reply(200, a.body, url, a.header("ETag"), a.header("Last-Modified"), a.cut)
                    }
                } finally {
                    stop.cancel(false)
                }
            }
        } catch (f: Failure) {
            // review-home-net-bind M1: refused off your network, nothing sent: no row in What went online.
            if (f.why == NewsFail.OFF_NETWORK) outcome = null
            throw f
        } catch (b: FeedHttp.Bad) {
            throw Failure(if (b.tooBig) NewsFail.TOO_BIG else NewsFail.SERVER)
        } catch (e: SecurityException) {
            outcome = null
            throw Failure(NewsFail.NO_PERMISSION)
        } catch (e: IOException) {
            if (e.message?.contains("EACCES") == true || e.message?.contains("Permission denied") == true) { outcome = null; throw Failure(NewsFail.NO_PERMISSION) } // l10n: not text (the system's error message, matched)
            throw Failure(NewsFail.NO_INTERNET)
        } finally {
            outcome?.let { NetRecord.record(NetPurpose.FEED, t.host, cause, 0, got[0], it) }
        }
    }

    /** The first of [addresses] that answers on [port] by [deadline], connected and checked again to be on your network. */
    private fun homeSocket(addresses: List<ByteArray>, port: Int, deadline: Long, near: Boolean): java.net.Socket {
        var off = false
        var tried = false
        for (a in addresses) {
            // review-link-net M1: only over the network that holds the address, bound there (core HomeNets).
            val s = try { app.heyrobot.core.net.HomeNets.socket(a) } catch (_: app.heyrobot.core.net.OffNetworkException) { off = true; continue }
            val was = tried
            tried = true
            try {
                s.connect(java.net.InetSocketAddress(InetAddress.getByAddress(a), port), left(deadline).toInt())
                if (ConnRules.socket(s.inetAddress.address, ConnWhere.HOME) && (!near || OwnFeed.loopback(s.inetAddress.address))) return s
                s.close()
            } catch (_: app.heyrobot.core.net.OffNetworkException) {
                // review-home-net-bind-b N2: it left off the network that holds it, closed before anything was written.
                s.close(); off = true; tried = was
            } catch (f: Failure) {
                s.close(); throw f
            } catch (_: IOException) {
                s.close()
            }
        }
        // Off your network only when no address was tried: one that was is a request that failed.
        throw Failure(if (off && !tried) NewsFail.OFF_NETWORK else NewsFail.NO_INTERNET)
    }

    /** One request through Tor; a redirect comes back as in [get]. Recorded in its finally block, once Tor gave a stream. */
    private fun torGet(ctx: Context, tor: Tor, url: String, cause: NetCause, etag: String?, modified: String?, deadline: Long, page: Boolean): Reply {
        if (ctx.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED) throw Failure(NewsFail.NO_PERMISSION)
        val t = FeedHttp.target(url) ?: throw Failure(NewsFail.SERVER)
        val got = longArrayOf(0)
        var outcome: NetResult? = null
        try {
            left(deadline)
            val pfd = try { tor.stream(t.host, t.port) } catch (_: Exception) { null } ?: throw Failure(NewsFail.NO_TOR)
            outcome = NetResult.FAILED
            pfd.use { p ->
                val fd = p.fileDescriptor
                // The feed's deadline: past it the stream is shut, which ends any read waiting on it.
                val stop = WATCHDOG.schedule({ try { Os.shutdown(fd, OsConstants.SHUT_RDWR) } catch (_: Exception) {} }, left(deadline), TimeUnit.MILLISECONDS)
                try {
                    val engine = SSLContext.getDefault().createSSLEngine(t.host, t.port).apply {
                        useClientMode = true
                        sslParameters = sslParameters.apply {
                            endpointIdentificationAlgorithm = "HTTPS" // l10n: not text (JSSE's name)
                            serverNames = listOf(SNIHostName(t.host))
                        }
                    }
                    val tls = FeedTls(engine, FileInputStream(fd), FileOutputStream(fd))
                    tls.handshake()
                    if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(t.host, engine.session)) throw Failure(NewsFail.SERVER)
                    tls.write(FeedHttp.request(t, AGENT, ACCEPT, etag, modified))
                    val a = FeedHttp.read(tls.reader, FeedXml.MAX_BYTES, { got[0] += it }, { System.currentTimeMillis() > deadline }, cut = page)
                    tls.close()
                    outcome = NetResult.DONE
                    return when {
                        a.code == 304 -> Reply(304, null, url, etag, modified)
                        a.code in 301..308 && a.code != 304 && a.code != 305 && a.code != 306 -> {
                            val next = a.header("Location")?.let { FeedLinks.clean(it, url) }?.takeIf { it.startsWith("https://") } ?: throw Failure(NewsFail.SERVER)
                            Reply(a.code, null, next, null, null)
                        }
                        a.code != 200 -> throw Failure(NewsFail.SERVER)
                        else -> Reply(200, a.body, url, a.header("ETag"), a.header("Last-Modified"), a.cut)
                    }
                } finally {
                    stop.cancel(false)
                }
            }
        } catch (f: Failure) {
            throw f
        } catch (b: FeedHttp.Bad) {
            throw Failure(if (b.tooBig) NewsFail.TOO_BIG else NewsFail.SERVER)
        } catch (e: Exception) {
            throw Failure(NewsFail.NO_TOR)
        } finally {
            outcome?.let { NetRecord.record(NetPurpose.FEED, t.host, cause, 0, got[0], it) }
        }
    }

    private val WATCHDOG = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-feed-watchdog").apply { isDaemon = true } } // l10n: not text (a thread's name)

    /** Counts the bytes that came over the network into [got] (What went online). */
    private class Counted(s: InputStream, private val got: LongArray) : java.io.FilterInputStream(s) {
        override fun read(): Int = super.read().also { if (it >= 0) got[0]++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) got[0] += it }
    }

    /**
     * The body, at most FeedXml.MAX_BYTES unzipped, by [deadline] (a server trickling bytes is cut off too). [page]: past the
     * cap its first bytes are kept and [cut] says so.
     */
    private fun read(s: InputStream, deadline: Long, page: Boolean, cut: BooleanArray): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = s.read(buf)
            if (n < 0) break
            if (out.size() + n > FeedXml.MAX_BYTES) {
                if (!page) throw Failure(NewsFail.TOO_BIG)
                out.write(buf, 0, FeedXml.MAX_BYTES - out.size())
                cut[0] = true
                break
            }
            if (System.currentTimeMillis() > deadline) throw Failure(NewsFail.NO_INTERNET)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
