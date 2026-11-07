// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

/** Today H7b: a feed's GET through Tor, written and read strictly (home-design §6.1, "Through what" and "Limits and cost"). */
class FeedHttpTest {
    private fun read(s: String, max: Int = FeedXml.MAX_BYTES, late: Boolean = false): FeedHttp.Answer =
        FeedHttp.read(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)), max, {}, { late })

    private fun bad(s: String, max: Int = FeedXml.MAX_BYTES): FeedHttp.Bad? = try { read(s, max); null } catch (b: FeedHttp.Bad) { b }

    @Test fun targetsAreHttpsNamesOnly() {
        val t = FeedHttp.target("https://News.Example.org/feed.xml?x=1")!!
        assertEquals("news.example.org", t.host); assertEquals(443, t.port); assertEquals("/feed.xml?x=1", t.path)
        assertEquals("/", FeedHttp.target("https://example.org")!!.path)
        for (u in listOf("http://example.org/", "https://user@example.org/", "https://10.0.0.1/", "https://[::1]/", "https://localhost/", "ftp://example.org/", "nonsense"))
            assertNull(u, FeedHttp.target(u))
    }

    @Test fun theRequestSaysNothingAboutYou() {
        val r = String(FeedHttp.request(FeedHttp.target("https://example.org:8443/f")!!, "UA", "text/xml", "\"e1\"", "bad\nvalue"), Charsets.US_ASCII)
        assertTrue(r.startsWith("GET /f HTTP/1.1\r\nHost: example.org:8443\r\n"))
        assertTrue(r.contains("If-None-Match: \"e1\"\r\n") && r.contains("Accept-Encoding: identity\r\n") && r.endsWith("Connection: close\r\n\r\n"))
        // A validator that isn't printable ASCII isn't sent; no cookie, no referrer, ever.
        for (h in listOf("If-Modified-Since", "Cookie", "Referer")) assertTrue(h, !r.contains(h))
    }

    @Test fun sizedChunkedAndClosedBodies() {
        assertArrayEquals("hello".toByteArray(), read("HTTP/1.1 200 OK\r\nContent-Length: 5\r\nETag: \"a\"\r\n\r\nhelloEXTRA").body)
        assertEquals("\"a\"", read("HTTP/1.1 200 OK\r\nEtag: \"a\"\r\nContent-Length: 0\r\n\r\n").header("ETag"))
        assertArrayEquals("hello world".toByteArray(), read("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n5;x=y\r\nhello\r\n6\r\n world\r\n0\r\nTrailer: 1\r\n\r\n").body)
        assertArrayEquals("to the end".toByteArray(), read("HTTP/1.0 200 OK\r\n\r\nto the end").body)
        val moved = read("HTTP/1.1 301 Moved\r\nLocation: https://example.org/new\r\n\r\nignored")
        assertEquals(301, moved.code); assertNull(moved.body); assertEquals("https://example.org/new", moved.header("location"))
        assertEquals(304, read("HTTP/1.1 304 Not Modified\r\n\r\n").code)
    }

    /** today-h7-fix 7: Find's page keeps its first bytes past the cap, sized, chunked or closed; a feed is still refused. */
    @Test fun aPageIsCutAtTheCapAndAFeedRefused() {
        val big = "x".repeat(100)
        for (s in listOf("HTTP/1.1 200 OK\r\nContent-Length: 100\r\n\r\n$big", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n64\r\n$big\r\n0\r\n\r\n",
            "HTTP/1.1 200 OK\r\n\r\n$big")) {
            val a = FeedHttp.read(ByteArrayInputStream(s.toByteArray(Charsets.ISO_8859_1)), 10, {}, { false }, cut = true)
            assertEquals(s, "x".repeat(10), String(a.body!!))
            assertTrue(s, a.cut)
            assertTrue(s, bad(s, 10)!!.tooBig)
        }
        assertFalse(FeedHttp.read(ByteArrayInputStream("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nok".toByteArray()), 10, {}, { false }, cut = true).cut)
    }

    @Test fun hostileAnswersAreRefused() {
        val big = FeedXml.MAX_BYTES
        assertTrue(bad("HTTP/1.1 200 OK\r\nContent-Length: ${big + 1}\r\n\r\n")!!.tooBig)
        assertTrue(bad("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n${Integer.toHexString(big + 1)}\r\n")!!.tooBig)
        assertTrue(bad("HTTP/1.1 200 OK\r\n\r\n" + "x".repeat(11), max = 10)!!.tooBig)
        assertTrue(bad("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nab\r\n0\r\n\r\n")!!.tooBig.not())
        for (s in listOf("", "SPDY/3 200 OK\r\n\r\n", "HTTP/1.1 200 OK\r\nX: " + "y".repeat(FeedHttp.MAX_LINE + 1) + "\r\n\r\n",
            "HTTP/1.1 200 OK\r\n" + "X-A: 1\r\n".repeat(FeedHttp.MAX_HEADERS + 1) + "\r\n", "HTTP/1.1 200 OK\r\nBad Header: 1\r\n\r\n",
            "HTTP/1.1 200 OK\r\nX: a\u0000b\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\nzz", "HTTP/1.1 200 OK\r\nTransfer-Encoding: gzip\r\n\r\n",
            "HTTP/1.1 200 OK\r\nContent-Length: -1\r\n\r\n", "HTTP/1.1 200 OK\r\nContent-Length: 9\r\n\r\nshort", "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n"))
            assertTrue(s.take(40), bad(s) != null)
        try { read("HTTP/1.1 200 OK\r\n\r\nslow", late = true); fail() } catch (_: FeedHttp.Bad) {}
    }
}
