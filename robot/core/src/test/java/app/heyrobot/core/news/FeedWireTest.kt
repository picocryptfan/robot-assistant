// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/** Today H7b: :feedparse's answer is one fixed frame, and the caller keeps only what it reads and cleans again. */
class FeedWireTest {
    private val now = 1_762_000_000_000L
    private val base = "https://news.example.org/feed.xml"

    @Test fun aFeedAndAPageRoundTrip() {
        val rss = "<rss><channel><title>Example</title><item><title>One</title><link>https://news.example.org/1</link></item></channel></rss>"
        val f = FeedWire.check(FeedWire.answer(rss.toByteArray(), base, now), now) as FeedAnswer.Feed
        assertEquals("Example", f.feed.name)
        assertEquals(listOf("One"), f.feed.items.map { it.title })
        val page = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"/rss\"></head></html>"
        assertEquals(FeedAnswer.Links(listOf("https://news.example.org/rss")), FeedWire.check(FeedWire.answer(page.toByteArray(), base, now), now))
        assertEquals(FeedAnswer.Links(emptyList()), FeedWire.check(FeedWire.answer("<!DOCTYPE x [<!ENTITY a \"b\">]><rss/>".toByteArray(), base, now), now))
    }

    /** What a taken-over reader could answer: every field is cleaned again, and items that don't survive are dropped. */
    @Test fun aTakenOverReaderCanDropButNeverAddDirt() {
        val dirty = FeedWire.encode(FeedAnswer.Feed(ParsedFeed("Na‮me<b>", listOf(
            FeedItem("Ti\u0007tle ‮gnp.exe", "https://news.example.org/a?utm_source=x", now),
            FeedItem("Plain http", "http://news.example.org/b", now),
            FeedItem("javascript", "javascript:alert(1)", now),
            FeedItem("From the future", "https://news.example.org/c", now + 2 * FeedRead.AHEAD_MS),
            FeedItem("", "https://news.example.org/d", now)))))
        val f = FeedWire.check(dirty, now) as FeedAnswer.Feed
        assertEquals(FeedText.clean("Na‮me<b>", FeedRead.NAME_MAX), f.feed.name)
        assertEquals(listOf(FeedText.clean("Ti\u0007tle ‮gnp.exe", FeedRead.TITLE_MAX)), f.feed.items.map { it.title })
        assertEquals(listOf(FeedLinks.clean("https://news.example.org/a?utm_source=x", null)), f.feed.items.map { it.link })
    }

    @Test fun anythingButOneFrameWithinItsCapsIsNoAnswer() {
        val ok = FeedWire.encode(FeedAnswer.Links(emptyList()))
        assertNull(FeedWire.check(ok + 0, now))
        assertNull(FeedWire.check(ok.copyOf(ok.size - 1), now))
        assertNull(FeedWire.check(byteArrayOf(0x47, 1, 2, 0, 0), now))
        assertNull(FeedWire.check(ByteArray(FeedParseIpc.MAX_REPLY + 1), now))
        assertNull(FeedWire.check(FeedWire.encode(FeedAnswer.Links(List(FeedWire.MAX_LINKS + 1) { "https://e.org/$it" })), now))
        assertNull(FeedWire.check(FeedWire.encode(FeedAnswer.Feed(ParsedFeed("x", List(FeedRead.MAX_ITEMS + 1) { FeedItem("t", "https://e.org/$it", now) }))), now))
        assertNull(FeedWire.check(FeedWire.encode(FeedAnswer.Feed(ParsedFeed("", emptyList()))), now))
        // A length that claims more than the frame holds.
        val b = ByteArrayOutputStream(); DataOutputStream(b).apply { writeByte(0x46); writeByte(1); writeByte(1); writeShort(60_000) }
        assertNull(FeedWire.check(b.toByteArray(), now))
    }
}
