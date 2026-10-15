// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.PageType
import app.heyrobot.core.web.PublicAddress
import app.heyrobot.core.web.SearchProvider
import app.heyrobot.core.web.SearchResult
import app.heyrobot.core.web.WebPlan
import app.heyrobot.core.web.WebUrl
import app.heyrobot.core.web.WebUserAgent
import okhttp3.Dns
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Design A7, A10.4 and A14 ":helpers network": the page fetcher against a local server. The phone's rules want https on
 * the default port to public addresses; a local test server is neither, so these tests loosen the scheme and the port
 * only, and treat 127.0.0.1 as the one "public" address (the names below resolve through a fake DNS). Every address rule
 * is the real one: a redirect to a private address is refused at each hop, before anything is asked of it.
 */
class PageFetcherTest {
    private lateinit var server: MockWebServer
    private val loopback = InetAddress.getByName("127.0.0.1")
    /** 127.0.0.1 stands for the public internet here; every other address is judged as on the phone. */
    private val isPublic: (ByteArray) -> Boolean = { a -> a.contentEquals(loopback.address) || PublicAddress.isPublic(a) && !(a.size == 4 && a[0].toInt() == 127) }
    private val rules = PageRules(httpsOnly = false, defaultPortOnly = false, isPublic = isPublic)
    private val dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = when (hostname) {
            "news.example.com" -> listOf(loopback)
            "router.example.com" -> listOf(InetAddress.getByName("192.168.1.1"))
            // A name with a private and a public address: only the public one is used.
            "mixed.example.com" -> listOf(InetAddress.getByName("10.0.0.1"), loopback)
            else -> throw UnknownHostException(hostname)
        }
    }

    @Before fun up() {
        server = MockWebServer()
        server.start(loopback, 0)
    }

    @After fun down() = server.shutdown()

    private fun url(host: String, path: String = "/a") = "http://$host:${server.port}$path"

    private fun pick(raw: String) = WebPlan.Pick(1, SearchResult("A page", raw, "snippet", SearchProvider.SEARXNG), WebUrl.parse(raw)!!)

    private fun fetcher(allMs: Long = 10_000, maxBytes: Int = 1 shl 20) = PageFetcher(dns, rules, pageMs = 6_000, allMs = allMs, maxBytes = maxBytes)

    private val html = "<html><head><title>T</title></head><body><p>Hello</p></body></html>"

    @Test fun aPageIsReadLikeABrowserWithoutCookiesOrReferer() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/b").setHeader("Set-Cookie", "id=tracking; Path=/"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(html))
        val r = fetcher().fetch(pick(url("news.example.com")), "fr") as PageFetcher.Result.Read
        assertEquals(PageType.Kind.HTML, r.page.kind)
        assertEquals(html, String(r.page.bytes))
        assertEquals(url("news.example.com", "/b"), r.page.url.toString())
        for (i in 1..2) {
            val req = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals(WebUserAgent.PAGE, req.getHeader("User-Agent"))
            assertEquals("fr", req.getHeader("Accept-Language"))
            assertNull(req.getHeader("Cookie"))
            assertNull(req.getHeader("Referer"))
            assertEquals("GET", req.method)
        }
    }

    @Test fun aRedirectToAPrivateAddressIsRefusedAtEveryHop() {
        // A literal private address: refused before any request goes there.
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "http://192.168.1.1/admin"))
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        // A name that resolves to your router (DNS rebinding included): refused by the DNS filter.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", url("router.example.com", "/")))
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        // A local name, and an IPv4-mapped private address in a redirect.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://printer.local/"))
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://[::ffff:10.0.0.1]/"))
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        assertEquals(4, server.requestCount)
        // The first URL itself, too.
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick("http://10.0.0.5/page"), null) as PageFetcher.Result.Skipped).why)
        assertEquals(PageSkip.NOT_PUBLIC, (fetcher().fetch(pick(url("router.example.com")), null) as PageFetcher.Result.Skipped).why)
        assertEquals(4, server.requestCount)
    }

    @Test fun aNameWithAPrivateAndAPublicAddressUsesOnlyThePublicOne() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
        assertTrue(fetcher().fetch(pick(url("mixed.example.com")), null) is PageFetcher.Result.Read)
    }

    @Test fun theSocketReallyConnectedIsCheckedToo() {
        // The DNS filter passes the address, then the socket check refuses it (as when a name rebinds between the two).
        var calls = 0
        val once: (ByteArray) -> Boolean = { a -> isPublic(a) && ++calls == 1 }
        server.enqueue(MockResponse().setBody(html))
        val f = PageFetcher(dns, PageRules(httpsOnly = false, defaultPortOnly = false, isPublic = once))
        assertEquals(PageSkip.NOT_PUBLIC, (f.fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        assertEquals(0, server.requestCount)
    }

    @Test fun atMostThreeRedirects() {
        repeat(4) { server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/next$it")) }
        assertEquals(PageSkip.TOO_MANY_REDIRECTS, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        assertEquals(4, server.requestCount)
    }

    @Test fun onlyHtmlOrTextAndAtMostTheByteCap() {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/pdf").setBody("%PDF-1.7"))
        assertEquals(PageSkip.PDF, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody("png"))
        assertEquals(PageSkip.NOT_HTML, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        server.enqueue(MockResponse().setResponseCode(404).setHeader("Content-Type", "text/html").setBody("gone"))
        assertEquals(PageSkip.FAILED, (fetcher().fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Skipped).why)
        val big = Buffer().write(ByteArray(300_000) { 'a'.code.toByte() })
        server.enqueue(MockResponse().setHeader("Content-Type", "text/plain").setBody(big))
        val r = fetcher(maxBytes = 100_000).fetch(pick(url("news.example.com")), null) as PageFetcher.Result.Read
        assertEquals(100_000, r.page.bytes.size)
        assertTrue(r.page.truncated)
    }

    @Test fun thePhonesRulesAreHttpsOnTheDefaultPort() {
        val strict = PageFetcher(dns)
        assertEquals(PageSkip.NOT_SECURE, (strict.fetch(pick("http://news.example.com/a"), null) as PageFetcher.Result.Skipped).why)
        assertEquals(PageSkip.PORT, (strict.fetch(pick("https://news.example.com:8443/a"), null) as PageFetcher.Result.Skipped).why)
        assertEquals(PageSkip.NOT_PUBLIC, (strict.fetch(pick("https://127.0.0.1/a"), null) as PageFetcher.Result.Skipped).why)
        // A redirect down to http is refused, not upgraded.
        val from = WebUrl.parse("https://news.example.com/a")!!
        assertEquals(PageSkip.NOT_SECURE, (PageRules.STRICT.redirect(from, "http://news.example.com/b", 0) as app.heyrobot.core.web.UrlCheck.Refused).why)
        assertTrue(PageRules.STRICT.redirect(from, "/b", 2) is app.heyrobot.core.web.UrlCheck.Ok)
        assertEquals(PageSkip.TOO_MANY_REDIRECTS, (PageRules.STRICT.redirect(from, "/b", 3) as app.heyrobot.core.web.UrlCheck.Refused).why)
        assertEquals(0, server.requestCount)
    }

    @Test fun pagesAreReadTogetherWithinOneDeadlineAndStopEndsThem() {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html).setBodyDelay(5, TimeUnit.SECONDS))
        val picks = listOf(pick(url("news.example.com", "/one")), pick(url("news.example.com", "/two")))
        val done = ArrayList<Int>()
        val t0 = System.nanoTime()
        val results = fetcher(allMs = 1_000).fetchAll(picks, null, Abort()) { synchronized(done) { done += it } }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("$ms ms", ms < 3_000)
        assertEquals(1, results.count { it is PageFetcher.Result.Read })
        assertEquals(PageSkip.TOO_SLOW, (results.first { it is PageFetcher.Result.Skipped } as PageFetcher.Result.Skipped).why)
        // Stop: the wait ends at once.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(html).setBodyDelay(5, TimeUnit.SECONDS))
        val abort = Abort()
        Thread { Thread.sleep(300); abort.cancel() }.start()
        val t1 = System.nanoTime()
        val stopped = fetcher().fetchAll(listOf(pick(url("news.example.com", "/three"))), null, abort)
        assertTrue(((System.nanoTime() - t1) / 1_000_000) < 3_000)
        assertTrue(stopped.single() is PageFetcher.Result.Skipped)
    }
}
