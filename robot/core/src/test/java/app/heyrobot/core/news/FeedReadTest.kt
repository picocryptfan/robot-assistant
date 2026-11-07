// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today H7: what a feed's server sends, read strictly; the hostile fixtures home-design §6.1 names. */
class FeedReadTest {
    private val now = 1_762_000_000_000L
    private val base = "https://news.example.org/feed.xml"

    private fun rss(items: String, title: String = "Example News") =
        """<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>$title</title>$items</channel></rss>""".toByteArray()

    @Test fun readsRssTitlesLinksAndDates() {
        val f = FeedRead.read(rss("""<item><title>First &amp; best</title><link>https://news.example.org/a?utm_source=rss&amp;id=7</link>
            <pubDate>Sat, 01 Nov 2025 10:00:00 GMT</pubDate></item><item><title><![CDATA[Second]]></title><link>/b</link>
            <pubDate>Fri, 31 Oct 2025 09:00:00 +0000</pubDate><enclosure url="https://cdn.example.org/a.mp3"/></item>"""), base, now)!!
        assertEquals("Example News", f.name)
        assertEquals(listOf("First & best", "Second"), f.items.map { it.title })
        // Tracking taken off, a relative link made whole, newest first.
        assertEquals(listOf("https://news.example.org/a?id=7", "https://news.example.org/b"), f.items.map { it.link })
        assertTrue(f.items[0].at > f.items[1].at)
    }

    @Test fun readsAtomAndRss1() {
        val atom = """<feed xmlns="http://www.w3.org/2005/Atom"><title>Atom site</title><entry><title type="html">&lt;b&gt;Bold&lt;/b&gt; news</title>
            <link rel="enclosure" href="https://x.example.org/pic.jpg"/><link href="https://x.example.org/1"/><updated>2025-11-01T10:00:00Z</updated></entry></feed>"""
        val a = FeedRead.read(atom.toByteArray(), base, now)!!
        assertEquals("Atom site", a.name)
        assertEquals(FeedItem("Bold news", "https://x.example.org/1", 1_761_991_200_000L), a.items.single())
        val rdf = """<rdf:RDF xmlns:rdf="r" xmlns="p"><channel><title>Old style</title></channel><item><title>One</title><link>https://o.example.org/1</link>
            <dc:date>2025-11-01T10:00:00+00:00</dc:date></item></rdf:RDF>"""
        assertEquals("One", FeedRead.read(rdf.toByteArray(), base, now)!!.items.single().title)
    }

    @Test fun entityBombsAndExternalEntitiesAreRefused() {
        val laughs = """<?xml version="1.0"?><!DOCTYPE lolz [<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
            <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">]><rss><channel><title>&lol3;</title></channel></rss>"""
        assertNull(FeedXml.parse(laughs.toByteArray()))
        val xxe = """<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///data/data/app.heyrobot/files/settings.json">]><rss><channel><title>&x;</title></channel></rss>"""
        assertNull(FeedRead.read(xxe.toByteArray(), base, now))
        // With no DTD, an undefined entity is dropped, never expanded.
        assertEquals("ab", FeedXml.parse("<rss><channel><title>a&lol9;b</title></channel></rss>".toByteArray())!!.child("channel")!!.child("title")!!.text)
    }

    @Test fun hugeTitlesDeepNestingAndTooBigRepliesAreBounded() {
        val huge = "A".repeat(300_000)
        val t = FeedRead.read(rss("<item><title>$huge</title><link>https://n.example.org/1</link></item>"), base, now)!!.items.single().title
        assertEquals(FeedRead.TITLE_MAX, t.length)
        assertTrue(t.endsWith("…"))
        assertNull(FeedXml.parse(("<a>".repeat(FeedXml.MAX_DEPTH + 1) + "</a>".repeat(FeedXml.MAX_DEPTH + 1)).toByteArray()))
        assertNull(FeedXml.parse(ByteArray(FeedXml.MAX_BYTES + 1) { 'a'.code.toByte() }))
        assertNull(FeedXml.parse(("<r>" + "<i/>".repeat(FeedXml.MAX_NODES) + "</r>").toByteArray()))
        // Not well formed, two roots, an unknown encoding: refused, not guessed.
        assertNull(FeedXml.parse("<rss><channel></rss>".toByteArray()))
        assertNull(FeedXml.parse("<a/><b/>".toByteArray()))
        assertNull(FeedXml.parse("""<?xml version="1.0" encoding="EBCDIC-XX"?><a/>""".toByteArray()))
        assertNull(FeedRead.read("<html><body>not a feed</body></html>".toByteArray(), base, now))
    }

    @Test fun nonHttpLinksAreDropped() {
        val f = FeedRead.read(rss("""<item><title>Script</title><link>javascript:alert(1)</link></item><item><title>Data</title><link>data:text/html,x</link></item>
            <item><title>File</title><link>file:///etc/passwd</link></item><item><title>Creds</title><link>https://user:pw@evil.example.org/</link></item>
            <item><title>Intent</title><link>intent://x#Intent;end</link></item><item><title>Fine</title><link>https://ok.example.org/</link></item>"""), base, now)!!
        assertEquals(listOf("Fine"), f.items.map { it.title })
    }

    @Test fun rtlTricksAndControlsAreTakenOut() {
        val f = FeedRead.read(rss("<item><title>abc\u202Etxt.exe\u2066 x\u200B\u0007y&#x202E;z&#0;</title><link>https://n.example.org/1</link></item>",
            title = "Na\u200Fme"), base, now)!!
        assertEquals("abctxt.exe xyz", f.items.single().title)
        assertEquals("Name", f.name)
        assertFalse(f.items.single().title.any { Character.getType(it) == Character.FORMAT.toInt() })
    }

    @Test fun aFutureDateIsTheFetchsTime() {
        val f = FeedRead.read(rss("<item><title>Pinned</title><link>https://n.example.org/1</link><pubDate>Fri, 01 Jan 2100 00:00:00 GMT</pubDate></item>"), base, now)!!
        assertEquals(now, f.items.single().at)
    }

    @Test fun findsAPagesFeedLinks() {
        val html = """<html><head><link rel="stylesheet" href="/a.css"><link rel="alternate" type="application/rss+xml" title="News" href="/feed?utm_medium=x&amp;s=1">
            <LINK REL="alternate" TYPE="application/atom+xml" HREF="http://plain.example.org/atom"><link rel="alternate" type="application/atom+xml" href="https://news.example.org/atom"></head>"""
        assertEquals(listOf("https://news.example.org/feed?s=1", "https://news.example.org/atom"), FeedFind.links(html, "https://news.example.org/"))
    }

    @Test fun addressesAreHttpsNamesOnly() {
        assertEquals("https://example.org/", NewsRules.address(" example.org "))
        assertEquals("https://example.org/rss", NewsRules.address("https://example.org/rss"))
        // H8, deliberately: plain http and your own network's addresses are taken now (ConnRules decides each lookup, OwnFeedTest).
        for (own in listOf("http://example.org/", "https://192.168.1.1/", "https://[::1]/")) assertEquals(own, own, NewsRules.address(own))
        for (bad in listOf("http://8.8.8.8/", "https://u:p@example.org/", "localhost", "ftp://example.org", ""))
            assertNull(bad, NewsRules.address(bad))
        assertNotNull(FeedLinks.clean("https://example.org/?fbclid=1&a=2", null))
        assertEquals("https://example.org/?a=2", FeedLinks.clean("https://example.org/?fbclid=1&a=2", null))
    }
}
