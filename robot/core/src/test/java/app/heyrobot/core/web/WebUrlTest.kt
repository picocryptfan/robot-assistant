package app.heyrobot.core.web

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which pages Robot may open (design A7, A10.4): https only, public hosts only in every spelling a browser would accept,
 * the default port, no credentials, at most 3 redirects each checked again, and only URLs the search service returned.
 */
class WebUrlTest {
    private fun refused(url: String) = (UrlPolicy.page(url) as? UrlCheck.Refused)?.why

    private fun ok(url: String) = UrlPolicy.page(url) as UrlCheck.Ok

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun anOrdinaryResultPasses() {
        val c = ok("https://www.nhl.com/news/panthers-win-stanley-cup?id=1#top")
        assertEquals("https://www.nhl.com/news/panthers-win-stanley-cup?id=1", c.url.toString())
        assertFalse(c.upgraded)
        assertEquals("nhl.com", WebUrl.domain("https://www.nhl.com/news"))
        assertEquals("en.wikipedia.org", WebUrl.domain("https://en.wikipedia.org/wiki/Stanley_Cup"))
    }

    @Test fun httpIsTriedOnceAsHttps() {
        val c = ok("http://tsn.ca/story")
        assertTrue(c.upgraded)
        assertEquals("https://tsn.ca/story", c.url.toString())
        assertEquals("https://tsn.ca/", ok("http://tsn.ca:80").url.toString())
    }

    @Test fun otherSchemesAreNoWebAddresses() {
        for (u in listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://example.com/x", "data:text/html,hi", "gopher://example.com",
            "//example.com/x", "example.com", "", "https://", "https:///x", "content://media/1", "intent://x#Intent;end")) {
            assertEquals(u, PageSkip.NOT_WEB, refused(u))
        }
    }

    @Test fun loopbackAndPrivateAddressesAreRefused() {
        for (u in listOf(
            "https://127.0.0.1/", "https://127.255.0.9/", "https://10.0.0.1/", "https://172.16.0.1/", "https://172.31.255.255/",
            "https://192.168.1.1/admin", "https://169.254.169.254/latest/meta-data/", "https://100.64.0.1/", "https://100.100.100.100/",
            "https://0.0.0.0/", "https://255.255.255.255/", "https://224.0.0.1/", "https://240.0.0.1/", "https://192.0.0.8/",
            "https://192.0.2.1/", "https://198.18.0.1/", "https://198.51.100.7/", "https://203.0.113.9/", "https://192.88.99.1/",
        )) assertEquals(u, PageSkip.NOT_PUBLIC, refused(u))
    }

    @Test fun everyNumericSpellingOfLoopbackIsRefused() {
        // WHATWG reads all of these as 127.0.0.1 (or 10.0.0.1, 192.168.0.1): a browser would connect there.
        for (u in listOf(
            "https://2130706433/", "https://0x7f000001/", "https://0x7f.0.0.1/", "https://0177.0.0.1/", "https://0177.1/", "https://127.1/",
            "https://127.0.1/", "https://017700000001/", "https://0x7F.0x00.0x00.0x01/", "https://127.0.0.1./", "https://0xa.0.0.1/",
            "https://3232235521/", "https://0300.0250.0.1/", "https://0/", "https://0x/",
        )) assertEquals(u, PageSkip.NOT_PUBLIC, refused(u))
        assertEquals("127.0.0.1", WebUrl.parse("https://0x7f.1/")!!.host)
        assertEquals(HostKind.IPV4, WebUrl.parse("https://2130706433/")!!.kind)
    }

    @Test fun malformedNumericHostsAreNoAddressAtAll() {
        for (u in listOf("https://256.0.0.1/", "https://1.2.3.4.5/", "https://example.123/", "https://1.2.3.999/",
            "https://4294967296/", "https://09.0.0.1/", "https://0x7g.0.0.1/")) assertEquals(u, PageSkip.NOT_WEB, refused(u))
    }

    @Test fun ipv6LiteralsAreCheckedLikeIpv4() {
        for (u in listOf(
            "https://[::1]/", "https://[::]/", "https://[0:0:0:0:0:0:0:1]/", "https://[fe80::1]/", "https://[febf::1]/", "https://[fc00::1]/",
            "https://[fd12:3456::1]/", "https://[ff02::1]/", "https://[::ffff:127.0.0.1]/", "https://[::ffff:7f00:1]/",
            "https://[0:0:0:0:0:ffff:192.168.1.1]/", "https://[::ffff:a9fe:a9fe]/", "https://[64:ff9b::7f00:1]/", "https://[64:ff9b::808:808]/",
            "https://[64:ff9b:1::1]/", "https://[::127.0.0.1]/", "https://[2001:db8::1]/", "https://[2001::1]/", "https://[2002:7f00:1::1]/",
            "https://[3fff::1]/", "https://[fec0::1]/", "https://[100::1]/",
        )) assertEquals(u, PageSkip.NOT_PUBLIC, refused(u))
        assertEquals("https://[2606:4700::1111]/", ok("https://[2606:4700::1111]/").url.toString())
        assertNotNull(UrlPolicy.page("https://[::ffff:8.8.8.8]/") as? UrlCheck.Ok)
    }

    @Test fun ipv6ZonesAndBadLiteralsAreRefused() {
        for (u in listOf("https://[fe80::1%25eth0]/", "https://[fe80::1%eth0]/", "https://[1::2::3]/", "https://[12345::1]/", "https://[::1/",
            "https://[::ffff:127.0.0.256]/", "https://[1:2:3:4:5:6:7:8:9]/", "https://[:1]/", "https://[g::1]/")) {
            assertEquals(u, PageSkip.NOT_WEB, refused(u))
        }
    }

    @Test fun localNamesAreRefused() {
        for (u in listOf("https://localhost/", "https://LOCALHOST./", "https://foo.localhost/", "https://router/", "https://nas.local/",
            "https://printer.lan/", "https://hub.home.arpa/", "https://metadata.google.internal/", "https://intranet.corp/",
            "https://x.test/", "https://facebookcorewwwi.onion/", "https://box.localdomain/")) {
            assertEquals(u, PageSkip.NOT_PUBLIC, refused(u))
        }
    }

    @Test fun credentialsBackslashesAndControlCharactersAreRefused() {
        for (u in listOf(
            "https://user:pass@example.com/", "https://example.com@127.0.0.1/", "https://example.com\\@127.0.0.1/", "https://exa\tmple.com/",
            "https://exa\nmple.com/", "https://example.com/a b", "https://%31%32%37.0.0.1/", "https://ex%61mple.com/", "https://exa mple.com/",
            "https://example.com\u0000.evil/", "https://-bad-.com/",
        )) assertEquals(u, PageSkip.NOT_WEB, refused(u))
        // "#@" is the fragment: the host is 127.0.0.1, refused as such.
        assertEquals(PageSkip.NOT_PUBLIC, refused("https://127.0.0.1#@example.com/"))
        assertEquals(PageSkip.NOT_PUBLIC, refused("https://127.0.0.1?@example.com/"))
    }

    @Test fun onlyTheDefaultPort() {
        assertEquals(PageSkip.PORT, refused("https://example.com:8443/"))
        assertEquals(PageSkip.PORT, refused("https://example.com:22/"))
        assertEquals(PageSkip.NOT_WEB, refused("https://example.com:0/"))
        assertEquals(PageSkip.NOT_WEB, refused("https://example.com:99999/"))
        assertEquals("https://example.com/", ok("https://example.com:443/").url.toString())
    }

    @Test fun internationalNamesBecomePunycodeAndPathsAreEncoded() {
        val u = ok("https://müller.de/straße?q=été").url
        assertEquals("xn--mller-kva.de", u.host)
        assertEquals("/stra%C3%9Fe?q=%C3%A9t%C3%A9", u.path)
        assertEquals("https://fr.wikipedia.org/wiki/Montr%C3%A9al", ok("https://fr.wikipedia.org/wiki/Montréal").url.toString())
    }

    @Test fun addressBytesFromDnsAreChecked() {
        assertTrue(PublicAddress.isPublic(bytes(93, 184, 216, 34)))
        assertTrue(PublicAddress.isPublic(bytes(8, 8, 8, 8)))
        assertFalse(PublicAddress.isPublic(bytes(127, 0, 0, 1)))
        assertFalse(PublicAddress.isPublic(bytes(192, 168, 0, 10)))
        assertFalse(PublicAddress.isPublic(bytes(100, 127, 255, 255)))
        assertTrue(PublicAddress.isPublic(bytes(100, 128, 0, 1)))
        assertFalse(PublicAddress.isPublic(bytes(172, 20, 0, 1)))
        assertTrue(PublicAddress.isPublic(bytes(172, 32, 0, 1)))
        assertFalse(PublicAddress.isPublic(ByteArray(16).also { it[15] = 1 }))
        assertFalse(PublicAddress.isPublic(PublicAddress.ipv6("::ffff:10.1.2.3")!!))
        assertTrue(PublicAddress.isPublic(PublicAddress.ipv6("2a00:1450:4001:82a::200e")!!))
        assertFalse(PublicAddress.isPublic(ByteArray(5)))
        assertFalse(PublicAddress.isPublic(ByteArray(0)))
    }

    @Test fun aNameThatAlsoResolvesPrivatelyKeepsOnlyItsPublicAddresses() {
        val public = bytes(93, 184, 216, 34)
        val rebound = listOf(bytes(127, 0, 0, 1), public, bytes(10, 0, 0, 5), PublicAddress.ipv6("fd00::1")!!)
        val kept = PublicAddress.publicOnly(rebound)
        assertEquals(1, kept.size)
        assertArrayEquals(public, kept[0])
        assertTrue(PublicAddress.publicOnly(listOf(bytes(192, 168, 1, 1))).isEmpty())
    }

    @Test fun ipv6Parsing() {
        assertArrayEquals(ByteArray(16).also { it[15] = 1 }, PublicAddress.ipv6("::1"))
        assertArrayEquals(ByteArray(16), PublicAddress.ipv6("::"))
        assertArrayEquals(ByteArray(16).also { it[10] = -1; it[11] = -1; it[12] = 127; it[15] = 1 }, PublicAddress.ipv6("::ffff:127.0.0.1"))
        assertArrayEquals(PublicAddress.ipv6("1:2:3:4:5:6:7:8"), PublicAddress.ipv6("1:2:3:4:5:6:0.7.0.8"))
        assertNull(PublicAddress.ipv6("1:2:3:4:5:6:7"))
        assertNull(PublicAddress.ipv6("::1::"))
        assertNull(PublicAddress.ipv6("::ffff:01.2.3.4"))
    }

    @Test fun redirectsAreCheckedAtEveryHop() {
        val from = WebUrl.parse("https://example.com/a/b?x=1")!!
        assertEquals("https://example.com/c", (UrlPolicy.redirect(from, "/c", 0) as UrlCheck.Ok).url.toString())
        assertEquals("https://example.com/a/d", (UrlPolicy.redirect(from, "d", 0) as UrlCheck.Ok).url.toString())
        assertEquals("https://example.com/a/b?y=2", (UrlPolicy.redirect(from, "?y=2", 1) as UrlCheck.Ok).url.toString())
        assertEquals("https://cdn.example.net/x", (UrlPolicy.redirect(from, "//cdn.example.net/x", 2) as UrlCheck.Ok).url.toString())
        // To the router, to http, to a local name, a fourth redirect, nowhere.
        assertEquals(UrlCheck.Refused(PageSkip.NOT_PUBLIC), UrlPolicy.redirect(from, "https://192.168.1.1/", 0))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_SECURE), UrlPolicy.redirect(from, "http://192.168.1.1/", 0))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_SECURE), UrlPolicy.redirect(from, "http://example.com/", 0))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_PUBLIC), UrlPolicy.redirect(from, "https://localhost/", 1))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_PUBLIC), UrlPolicy.redirect(from, "https://[::1]/", 1))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_PUBLIC), UrlPolicy.redirect(from, "https://0x7f000001/", 1))
        assertEquals(UrlCheck.Refused(PageSkip.TOO_MANY_REDIRECTS), UrlPolicy.redirect(from, "/ok", UrlPolicy.MAX_REDIRECTS))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_WEB), UrlPolicy.redirect(from, null, 0))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_WEB), UrlPolicy.redirect(from, "file:///etc/passwd", 0))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_WEB), UrlPolicy.redirect(from, "/a\\b", 0))
    }

    @Test fun onlyUrlsFromTheResultsAreRead() {
        val results = listOf(
            SearchResult("Panthers", "https://www.nhl.com/news/x", "", SearchProvider.SEARXNG),
            SearchResult("TSN", "http://tsn.ca/story", "", SearchProvider.SEARXNG),
            SearchResult("Router", "https://192.168.1.1/", "", SearchProvider.SEARXNG),
        )
        val urls = PageUrls(results)
        assertTrue(urls.check("https://www.nhl.com/news/x") is UrlCheck.Ok)
        assertTrue(urls.check("https://www.nhl.com/news/x#frag") is UrlCheck.Ok)
        assertTrue((urls.check("http://tsn.ca/story") as UrlCheck.Ok).upgraded)
        // The model's own URL, a link found in a page, a look-alike: never.
        assertEquals(UrlCheck.Refused(PageSkip.NOT_FROM_RESULTS), urls.check("https://www.nhl.com/news/y"))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_FROM_RESULTS), urls.check("https://evil.example/steal?d=secret"))
        assertEquals(UrlCheck.Refused(PageSkip.NOT_FROM_RESULTS), urls.check("https://www.nhl.com.evil.example/news/x"))
        // A result is still checked like any URL.
        assertEquals(UrlCheck.Refused(PageSkip.NOT_PUBLIC), urls.check("https://192.168.1.1/"))
    }

    @Test fun filesAndVideosAreSkippedByTheirUrl() {
        assertEquals(PageSkip.PDF, PageType.skipUrl(WebUrl.parse("https://example.com/manual.PDF")!!))
        assertEquals(PageSkip.NOT_HTML, PageType.skipUrl(WebUrl.parse("https://example.com/a.mp4?x=1")!!))
        assertEquals(PageSkip.VIDEO, PageType.skipUrl(WebUrl.parse("https://www.youtube.com/watch?v=1")!!))
        assertEquals(PageSkip.VIDEO, PageType.skipUrl(WebUrl.parse("https://m.youtube.com/watch?v=1")!!))
        assertNull(PageType.skipUrl(WebUrl.parse("https://example.com/article.html")!!))
        assertEquals(PageType.Kind.HTML, PageType.of("text/html; charset=utf-8"))
        assertEquals(PageType.Kind.HTML, PageType.of("application/xhtml+xml"))
        assertEquals(PageType.Kind.TEXT, PageType.of("TEXT/PLAIN"))
        assertNull(PageType.of("application/pdf"))
        assertNull(PageType.of(null))
        assertEquals(PageSkip.PDF, PageType.skip("application/pdf"))
        assertEquals(PageSkip.NOT_HTML, PageType.skip("image/png"))
    }
}
