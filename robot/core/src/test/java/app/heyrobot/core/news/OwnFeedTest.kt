// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnRules
import app.heyrobot.core.connect.ConnWhere
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Today H8: a feed on your own server, decided by ConnRules; an address carrying a token kept as a secret. */
class OwnFeedTest {
    private fun ip(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val home = ip(192, 168, 1, 5)
    private val public = ip(93, 184, 216, 34)
    private fun where(l: ConnRules.Lookup) = (l as? ConnRules.Lookup.Ok)?.where

    @Test fun plainHttpGoesOnlyToYourOwnNetwork() {
        assertEquals("http://192.168.1.5:8080/i/?a=rss", NewsRules.address("http://192.168.1.5:8080/i/?a=rss"))
        assertEquals(ConnWhere.HOME, where(OwnFeed.lookup("http://192.168.1.5:8080/i/?a=rss", listOf(home))))
        assertEquals(ConnWhere.HOME, where(OwnFeed.lookup("http://reader.example.org/rss", listOf(home))))
        // Refused: plain http to a name on the internet.
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.MOVED), OwnFeed.lookup("http://reader.example.org/rss", listOf(public)))
    }

    @Test fun httpsMayBeEitherKindButOneKind() {
        assertEquals(ConnWhere.INTERNET, where(OwnFeed.lookup("https://news.example.org/feed", listOf(public))))
        assertEquals(ConnWhere.HOME, where(OwnFeed.lookup("https://reader.example.org/feed", listOf(home))))
        // A dual-stack server at home keeps its home address (ConnRules.lookupHomeWins).
        assertEquals(ConnWhere.HOME, where(OwnFeed.lookup("http://reader.example.org/feed", listOf(public, home))))
        assertEquals(ConnRules.Lookup.Refused(ConnFailure.NOWHERE), OwnFeed.lookup("https://news.example.org/feed", listOf(ip(0, 0, 0, 0))))
    }

    @Test fun anOwnFeedMustResolveToYourNetwork() {
        for (u in listOf("https://nas.lan/feed", "http://reader.local/rss", "http://nas:8080/rss", "https://pi.home.arpa/f")) {
            assertTrue(u, OwnFeed.own(u))
            assertEquals(u, ConnRules.Lookup.Refused(ConnFailure.MOVED), OwnFeed.lookup(u, listOf(public)))
            assertEquals(u, ConnWhere.HOME, where(OwnFeed.lookup(u, listOf(home))))
        }
        assertFalse(OwnFeed.own("https://news.example.org/feed"))
        assertTrue(OwnFeed.own("http://192.168.1.5/rss"))
        assertTrue(OwnFeed.own("http://[fd00::5]/rss"))
    }

    @Test fun anAddressOnTheInternetTypedAsANumberIsRefused() {
        assertNull(NewsRules.address("http://8.8.8.8/feed"))
        assertNull(NewsRules.address("https://93.184.216.34/feed"))
        assertNull(NewsRules.address("http://user:pw@192.168.1.5/feed"))
        // A name without a dot only with its scheme ("cbc" alone is a typo, not a server).
        assertNull(NewsRules.address("nas"))
        assertEquals("http://nas:8080/rss", NewsRules.address("http://nas:8080/rss"))
        assertEquals("https://example.com/", NewsRules.address("example.com"))
    }

    @Test fun everyHopOfARequestIsOfItsFirstsKind() {
        assertTrue(OwnFeed.sameKind(null, ConnWhere.HOME))
        assertTrue(OwnFeed.sameKind(ConnWhere.HOME, ConnWhere.HOME))
        assertFalse(OwnFeed.sameKind(ConnWhere.INTERNET, ConnWhere.HOME))
        assertFalse(OwnFeed.sameKind(ConnWhere.HOME, ConnWhere.INTERNET))
    }

    @Test fun withTorChosenOnlyYourOwnNetworkGoesDirect() {
        assertEquals(OwnFeed.Route.HOME, OwnFeed.route("http://192.168.1.5/rss", NewsVia.TOR))
        assertEquals(OwnFeed.Route.HOME, OwnFeed.route("https://nas.lan/rss", NewsVia.TOR))
        assertEquals(OwnFeed.Route.TOR, OwnFeed.route("https://news.example.org/feed", NewsVia.TOR))
        // An http name that isn't a local one isn't known to be yours: through Tor, which takes https only, so it fails closed.
        assertEquals(OwnFeed.Route.TOR, OwnFeed.route("http://reader.example.org/rss", NewsVia.TOR))
        assertEquals(OwnFeed.Route.DIRECT, OwnFeed.route("http://192.168.1.5/rss", NewsVia.DIRECT))
    }

    @Test fun aHomeTargetTakesHttpAndNumbers() {
        assertNull(FeedHttp.target("http://192.168.1.5:8080/rss"))
        val t = FeedHttp.target("http://192.168.1.5:8080/rss?x=1", home = true)!!
        assertEquals(listOf("192.168.1.5", "8080", "/rss?x=1", "false"), listOf(t.host, "${t.port}", t.path, "${t.https}"))
        assertEquals(80, FeedHttp.target("http://nas/rss", home = true)!!.port)
        assertTrue(String(FeedHttp.request(t, "a", "b", null, null)).contains("Host: 192.168.1.5:8080\r\n"))
        assertTrue(String(FeedHttp.request(FeedHttp.target("http://nas/rss", home = true)!!, "a", "b", null, null)).contains("Host: nas\r\n"))
    }

    @Test fun anAddressCarryingATokenIsMaskedAndNeverKept() {
        val url = "http://192.168.1.5/i/?a=rss&user=me&token=abcdef123456XYZ9&hours=24"
        assertEquals("http://192.168.1.5/i/?a=rss&user=me&token=…XYZ9&hours=24", NewsSecret.masked(url))
        assertTrue(NewsSecret.carries("https://reader.example.org/share/AbC123dEf456GhI7"))
        assertTrue(NewsSecret.carries("https://tt.example.org/public.php?op=rss&id=-3&key=zz9yx8wv7u"))
        assertTrue(NewsSecret.carries("https://r.example.org/feed?rssToken=abc123abc123"))
        assertFalse(NewsSecret.carries("https://news.example.org/2026/11/04/a-long-story-title/feed?page=2"))
        assertEquals("http://192.168.1.5/i/", NewsSecret.base(url)) // today-h8-fix 6: the path without the query, which held the token
        val id = NewsSecret.id()
        val standIn = NewsSecret.standIn(url, id)!!
        assertEquals("http://192.168.1.5/#$id", standIn)
        val s = NewsSettings(listOf(NewsFeed(standIn, "Mine", id)))
        val json = s.toJson().toString()
        assertFalse(json, json.contains("abcdef123456XYZ9") || json.contains("token"))
        assertEquals(s, NewsSettings.of(JSONObject(json)))
        assertFalse(NewsFeed(standIn, "Mine", id).toString().contains(id))
        // An address that carries a token, kept as it is, is dropped (only a stand-in and its key are kept), as is a bad key.
        fun kept(url: String, key: String? = null) = NewsSettings.of(JSONObject().put("feeds",
            org.json.JSONArray().put(JSONObject().put("url", url).put("name", "x").also { o -> key?.let { o.put("key", it) } }))).feeds.size
        assertEquals(0, kept(url))
        assertEquals(0, kept(standIn, "notanid"))
        assertEquals(0, kept("http://192.168.1.6/#$id", id.reversed()))
        assertEquals(1, kept("http://192.168.1.5:8080/rss"))
    }

    /** Q35 (the user, 7 Nov 2026): a token goes over https; over plain http only to this phone, labelled; Tailscale and your Wi-Fi refused. */
    @Test fun aTokenOverPlainHttpGoesOnlyToThisPhone() {
        val t = "/i/?a=rss&token=abcdef123456XYZ9"
        for (h in listOf("100.64.0.9", "100.70.1.2", "192.168.1.5", "10.0.0.2", "[fd7a:115c:a1e0::1]", "nas", "reader.example.org"))
            assertTrue(h, OwnFeed.tokenRefused("http://$h:8080$t"))
        for (h in listOf("127.0.0.1", "127.1.2.3", "[::1]", "localhost", "LocalHost")) {
            assertFalse(h, OwnFeed.tokenRefused("http://$h:8080$t"))
            assertEquals(h, OwnFeed.Note.NOT_ENCRYPTED_ON_PHONE, OwnFeed.note("http://$h:8080$t"))
        }
        // https, whose certificate the connection checks, goes anywhere; plain http without a token stays, noted.
        for (h in listOf("192.168.1.5", "100.64.0.9", "nas")) {
            assertFalse(OwnFeed.tokenRefused("https://$h$t")); assertNull(OwnFeed.note("https://$h$t"))
            assertFalse(OwnFeed.tokenRefused("http://$h/rss")); assertEquals(OwnFeed.Note.PLAIN_HTTP, OwnFeed.note("http://$h/rss"))
        }
        assertNull(OwnFeed.note("http://127.0.0.1:8080/rss"))
        // A key kept apart (a listed feed's stand-in) counts as a token.
        assertEquals(OwnFeed.Note.NOT_ENCRYPTED_ON_PHONE, OwnFeed.note("http://127.0.0.1:8080/rss", secret = true))
        assertTrue(OwnFeed.tokenRefused("http://192.168.1.5/rss", secret = true))
        // No hop off the phone: a redirect from this phone's plain http goes nowhere else, not even over https.
        assertFalse(OwnFeed.hopRefused("http://127.0.0.1:8080$t", "http://localhost:8080/next", true))
        assertTrue(OwnFeed.hopRefused("http://127.0.0.1:8080$t", "https://reader.example.org/next", true))
        assertTrue(OwnFeed.hopRefused("https://nas$t", "http://192.168.1.5/next", true))
        assertFalse(OwnFeed.hopRefused("https://nas$t", "https://nas/next", true))
        // The connection itself must be loopback.
        assertTrue(OwnFeed.loopback(ip(127, 0, 0, 1))); assertFalse(OwnFeed.loopback(ip(100, 64, 0, 9))); assertFalse(OwnFeed.loopback(ip(192, 168, 1, 5)))
    }

    /** today-h8-fix 3 and 4: Find follows a page's link only of the page's kind; a home hop's redirect only to your own or the same host. */
    @Test fun aPageOnTheInternetCantSendFindHomeAndAHomeHopCantSendItOut() {
        for (l in listOf("https://192.168.1.1:8443/", "https://router.local/feed", "https://nas/feed", "https://[fd00::1]/feed"))
            assertFalse(l, OwnFeed.findLink("https://news.example.org/", l))
        assertTrue(OwnFeed.findLink("https://news.example.org/", "https://news.example.org/feed.xml"))
        assertTrue(OwnFeed.findLink("http://nas:8080/", "https://nas:8080/feed"))
        assertFalse(OwnFeed.homeHop("http://192.168.1.5/rss", "https://id-1234.example.net/"))
        assertTrue(OwnFeed.homeHop("http://192.168.1.5/rss", "http://192.168.1.5/rss/"))
        assertTrue(OwnFeed.homeHop("https://reader.example.org/rss", "https://reader.example.org/rss/"))
        assertTrue(OwnFeed.homeHop("http://nas/rss", "http://reader.lan/rss"))
    }

    /** today-h8-fix 5 and 6: a token path part with its extension is seen; a secret feed's item links keep no token and resolve against its path. */
    @Test fun aTokenWithAnExtensionIsSeenAndItemLinksDontCarryIt() {
        val feedUrl = "https://feeds.example.net/feeds/a1b2c3d4e5f6g7h8i9j0.xml"
        assertTrue(NewsSecret.carries(feedUrl))
        assertFalse(NewsSecret.carries("https://news.example.org/feeds/world-news.xml"))
        assertTrue(NewsSecret.values(feedUrl).contains("a1b2c3d4e5f6g7h8i9j0"))
        val q = "http://192.168.1.5:8080/i/?a=rss&token=abcdef123456XYZ9"
        assertEquals("http://192.168.1.5:8080/i/", NewsSecret.base(q))
        assertEquals("https://feeds.example.net/", NewsSecret.base(feedUrl))
        val items = listOf(FeedItem("a", "http://192.168.1.5:8080/i/?a=item&token=abcdef123456XYZ9", 1), FeedItem("b", "http://192.168.1.5:8080/i/?x=abcdef123456XYZ9", 1),
            FeedItem("c", "https://news.example.org/story", 1))
        assertEquals(listOf("c"), NewsSecret.cleaned(ParsedFeed("F", items), q).items.map { it.title })
        assertEquals(3, NewsSecret.cleaned(ParsedFeed("F", items), "https://news.example.org/feed").items.size)
    }
}
