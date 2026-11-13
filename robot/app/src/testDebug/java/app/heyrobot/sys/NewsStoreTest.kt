// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.news.NewsFeed
import app.heyrobot.core.news.NewsMode
import app.heyrobot.core.news.NewsMoment
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.core.news.NewsSettings
import app.heyrobot.core.news.NewsVia
import app.heyrobot.core.online.NetCause
import org.junit.After
import app.heyrobot.core.news.FeedWire
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Today H7: news is fetched only when the policy says so, kept outside the backups, and never with Tor chosen and down. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewsStoreTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val now = 1_762_000_000_000L
    private val feed = NewsFeed("https://news.example.org/feed.xml", "Example")
    private val daily = NewsSettings(listOf(feed))
    private fun rss(vararg titles: String) = ("<rss><channel><title>Example</title>" +
        titles.mapIndexed { i, t -> "<item><title>$t</title><link>https://news.example.org/$i</link></item>" }.joinToString("") + "</channel></rss>").toByteArray()

    private val wire = NewsStore.Parse { b, base, at -> FeedWire.check(FeedWire.answer(b, base, at), at) }

    // H7b: each round reads through :feedparse's wire (core FeedWire), here without the process.
    @Before fun pure() { NewsStore.parser = { wire } }

    @After fun clean() {
        NewsStore.file(ctx).delete(); NewsStore.parser = { FeedParseLink(it) }; HomeSettingsStore.file(ctx).delete()
        FeedClient.timeoutMs = FeedClient.TIMEOUT_MS; FeedClient.resolve = { java.net.InetAddress.getAllByName(it) }
        java.util.TimeZone.setDefault(zone0)
    }
    private val zone0 = java.util.TimeZone.getDefault()
    private fun jobs() = ctx.getSystemService(android.app.job.JobScheduler::class.java)
    private fun home(today: Boolean, news: NewsSettings) = HomeSettingsStore.update(ctx) { app.heyrobot.core.HomeSettings(home = app.heyrobot.core.HomeChoice.of(today), news = news) }
    private fun reply(url: String, body: ByteArray?, etag: String? = null, at: String = url) = FeedClient.Reply(if (body == null) 304 else 200, body, at, etag, null)

    /** today-h7-fix 1 and 3a: Today off cancels the daily job and deletes the headlines; an app open never schedules it then. */
    @Test fun todayOffCancelsTheDailyJobAndDeletesTheHeadlines() {
        home(true, daily)
        assertTrue(jobs().getPendingJob(app.heyrobot.update.UpdateJobs.NEWS) != null)
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ -> reply(url, rss("One")) }, now)
        assertTrue(NewsStore.file(ctx).isFile)
        home(false, daily)
        assertEquals(null, jobs().getPendingJob(app.heyrobot.update.UpdateJobs.NEWS))
        assertEquals(false, NewsStore.file(ctx).exists())
        // What the app open runs (Updater): with Today off, no job.
        app.heyrobot.update.UpdateJobs.ensureNews(ctx, NewsStore.dailyNews(HomeSettingsStore.current(ctx)))
        assertEquals(null, jobs().getPendingJob(app.heyrobot.update.UpdateJobs.NEWS))
    }

    /** today-h7-fix 3a: a removed feed's headlines are deleted at once, not at the next fetch. */
    @Test fun aRemovedFeedsHeadlinesGoAtOnce() {
        val other = NewsFeed("https://other.example.org/feed.xml", "Other")
        val two = NewsSettings(listOf(feed, other))
        home(true, two)
        NewsStore.fetch(ctx, NewsMoment.TAP, two, { url, _, _, _ -> reply(url, rss("One")) }, now)
        assertEquals(setOf(feed.url, other.url), NewsStore.current(ctx).feeds.keys)
        home(true, two.removed(other.url))
        assertEquals(setOf(feed.url), NewsStore.current(ctx).feeds.keys)
    }

    /** today-h7-fix 6: a tap at 21:00 doesn't hold back the next morning's daily fetch. */
    @Test fun anEveningTapDoesNotDelayTheMorningFetch() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        val evening = java.time.Instant.parse("2025-11-01T21:00:00Z").toEpochMilli()
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ -> reply(url, rss("Evening")) }, evening)
        var asked = 0
        val r = NewsStore.fetch(ctx, NewsMoment.DAILY, daily, { url, _, _, _ -> asked++; reply(url, rss("Morning")) }, evening + 9 * 3_600_000L, unmetered = true, saver = false)
        assertTrue(r is NewsStore.Fetched.Done)
        assertEquals(1, asked)
        // And the daily job's own pace still holds: a second run that morning waits.
        assertEquals(NewsStore.Fetched.Waited, NewsStore.fetch(ctx, NewsMoment.DAILY, daily, { _, _, _, _ -> error("no request") }, evening + 9 * 3_600_000L + 60_000, unmetered = true, saver = false))
    }

    /** today-h7-fix 4: validators go back only to the host that gave them, and never through Tor. */
    @Test fun validatorsGoOnlyToTheirHostAndNeverThroughTor() {
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ -> reply(url, rss("One"), "\"cookie\"", "https://cdn.example.net/feed.xml") }, now)
        val sent = mutableListOf<String?>()
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, e, _ -> sent += e; reply(url, rss("Two")) }, now + 60_000)
        assertEquals(listOf<String?>(null), sent)
        NewsStore.file(ctx).delete()
        val tor = object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply { sent += etag; return reply(url, rss("T"), "\"e\"") }
            override fun torWhy(): NewsRefusal? = null
        }
        NewsStore.fetch(ctx, NewsMoment.TAP, daily.copy(via = NewsVia.TOR), tor, now)
        assertEquals(null, NewsStore.current(ctx).feeds[feed.url]!!.etag)
        NewsStore.fetch(ctx, NewsMoment.TAP, daily.copy(via = NewsVia.TOR), tor, now + 60_000)
        assertEquals(listOf<String?>(null, null, null), sent)
    }

    /** today-h7-fix 9: the store's lock isn't held across the network: while a round waits on a server, the rest goes on. */
    @Test fun aSlowServerHoldsNothingElseUp() {
        val inside = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<NewsStore.Fetched> { NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ -> inside.countDown(); release.await(); reply(url, rss("Slow")) }, now) }
            assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<NewsStore.Fetched> { NewsStore.fetch(ctx, NewsMoment.TAP, daily, { _, _, _, _ -> error("one round at a time") }, now) }
            assertEquals(NewsStore.Fetched.Waited, second.get(2, java.util.concurrent.TimeUnit.SECONDS))
            NewsStore.settled(ctx, app.heyrobot.core.HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY, news = daily))
            release.countDown()
            assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS) is NewsStore.Fetched.Done)
        } finally {
            release.countDown(); pool.shutdownNow()
        }
    }

    /** today-h7-fix 9: 15 s is the whole cap per feed, the name lookup included (here 300 ms against a lookup of 5 s). */
    @Test fun theNameLookupIsInsideTheFeedsTime() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        FeedClient.timeoutMs = 300
        FeedClient.resolve = { Thread.sleep(5_000); arrayOf() }
        val t = System.currentTimeMillis()
        val why = try { FeedClient.fetch(ctx, feed.url, NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
        assertEquals(NewsFail.NO_INTERNET, why)
        assertTrue("${System.currentTimeMillis() - t} ms", System.currentTimeMillis() - t < 2_000)
    }

    /** today-h7-fix 7 and 11: Find reads a big page's first 512 KB for its feed link; a feed link at an IP address isn't taken. */
    @Test fun findReadsABigPagesHeadAndRefusesAnAddressItWouldDrop() {
        val head = "<html><head><link rel=\"alternate\" type=\"application/rss+xml\" href=\"https://news.example.org/feed.xml\"></head><body>"
        fun pages(link: String = "https://news.example.org/feed.xml", cut: Boolean = true) = object : NewsStore.Ask, NewsStore.Pages {
            override fun page(url: String, cause: NetCause) = FeedClient.Reply(200, head.replace("https://news.example.org/feed.xml", link).toByteArray(), url, null, null, cut)
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?) = reply(url, rss("Found"))
        }
        val f = NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, pages(), now)
        assertEquals(feed.url, (f as NewsStore.Found.Feed).url)
        assertEquals(NewsStore.Found.Nothing(NewsFail.BAD_ADDRESS), NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, pages("https://203.0.113.9/feed.xml"), now))
        val bigFeed = object : NewsStore.Ask, NewsStore.Pages {
            override fun page(url: String, cause: NetCause) = FeedClient.Reply(200, rss("Cut"), url, null, null, cut = true)
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?) = error("no request")
        }
        assertEquals(NewsStore.Found.Nothing(NewsFail.TOO_BIG), NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, bigFeed, now))
    }

    /** today-h7-fix 11: Add keeps what its preview read, so the page isn't empty until a tap. */
    @Test fun addKeepsThePreview() {
        home(true, daily)
        NewsStore.added(ctx, feed.url, app.heyrobot.core.news.ParsedFeed("Example", listOf(app.heyrobot.core.news.FeedItem("Seen", "https://news.example.org/s", now))), now)
        assertEquals(listOf("Seen"), NewsStore.current(ctx).pick(daily, now).map { it.title })
        // Not for a feed you didn't add.
        NewsStore.added(ctx, "https://else.example.org/feed.xml", app.heyrobot.core.news.ParsedFeed("Else", emptyList()), now)
        assertEquals(setOf(feed.url), NewsStore.current(ctx).feeds.keys)
    }

    /** H7b: a reader that fails on one feed (a crash or a hostile feed in :feedparse) fails that feed alone. */
    @Test fun aReaderThatFailsOnOneFeedFailsOnlyThatFeed() {
        val two = NewsSettings(listOf(feed, NewsFeed("https://other.example.org/feed.xml", "Other")))
        val bad = "<rss><channel><title>Bad</title></channel></rss>".toByteArray()
        var closed = 0
        NewsStore.parser = { object : NewsStore.Parse, java.io.Closeable {
            override fun read(bytes: ByteArray, base: String, now: Long) = if (bytes.contentEquals(bad)) null else wire.read(bytes, base, now)
            override fun close() { closed++ }
        } }
        val r = NewsStore.fetch(ctx, NewsMoment.TAP, two, { url, _, _, _ -> FeedClient.Reply(200, if (url.contains("other")) bad else rss("Kept"), url, null, null) }, now)
        r as NewsStore.Fetched.Done
        assertEquals(1, r.failed)
        assertEquals(listOf("Kept"), r.stored.feeds[feed.url]!!.items.map { it.title })
        assertEquals(1, closed)
    }

    /** H7b: Tor chosen and able to carry it (a client through Tor that says so): the round goes, through it only. */
    @Test fun withTorChosenAndUpTheRoundGoes() {
        val asked = mutableListOf<String>()
        val tor = object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply {
                asked += url
                return FeedClient.Reply(200, rss("Through Tor"), url, null, null)
            }
            override fun torWhy(): app.heyrobot.core.news.NewsRefusal? = null
        }
        val r = NewsStore.fetch(ctx, NewsMoment.TAP, daily.copy(via = NewsVia.TOR), tor, now)
        assertEquals(listOf(feed.url), asked)
        assertEquals(listOf("Through Tor"), (r as NewsStore.Fetched.Done).stored.feeds[feed.url]!!.items.map { it.title })
    }

    /** H7b: with Today off nothing shows the news, so the daily job fetches none. */
    @Test fun withTodayOffTheDailyJobFetchesNothing() {
        assertEquals(false, NewsStore.dailyNews(app.heyrobot.core.HomeSettings(home = app.heyrobot.core.HomeChoice.START, news = daily)).on)
        assertEquals(daily, NewsStore.dailyNews(app.heyrobot.core.HomeSettings(home = app.heyrobot.core.HomeChoice.TODAY, news = daily)))
    }

    /** H7b: with Tor chosen and down, Find sends nothing either. */
    @Test fun findWithTorChosenAndDownAsksNothing() {
        assertEquals(NewsStore.Found.Nothing(NewsFail.NO_TOR), NewsStore.find(ctx, "news.example.org", NewsVia.TOR, { _, _, _, _ -> error("no request") }, now))
    }

    /** fix-today-tor-b: with Tor chosen and still starting, Find says so (not "couldn't carry") and sends nothing. */
    @Test fun findWhileTorStartsSaysStartingAndAsksNothing() {
        val ask = object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: app.heyrobot.core.online.NetCause, etag: String?, modified: String?): FeedClient.Reply = error("no request")
            override fun torWhy(): NewsRefusal? = NewsRefusal.TOR_STARTING
        }
        assertEquals(NewsStore.Found.Nothing(NewsFail.TOR_STARTING), NewsStore.find(ctx, "news.example.org", NewsVia.TOR, ask, now))
    }

    /** merge-today-124: Tor chosen and Robot to Robot off, Tor never starts: Find says to turn it on (not "couldn't carry"), and sends nothing. */
    @Test fun findWithRobotToRobotOffSaysTurnItOnAndAsksNothing() {
        val ask = object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: app.heyrobot.core.online.NetCause, etag: String?, modified: String?): FeedClient.Reply = error("no request")
            override fun torWhy(): NewsRefusal? = NewsRefusal.TOR_OFF
        }
        assertEquals(NewsStore.Found.Nothing(NewsFail.TOR_OFF), NewsStore.find(ctx, "news.example.org", NewsVia.TOR, ask, now))
    }

    /** H7b: through Tor, a stream Tor can't give fails the feed, and nothing goes direct instead (a direct try fails otherwise). */
    @Test fun throughTorWithNoStreamNothingGoesDirect() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        var asked = 0
        val f = try { FeedClient.fetch(ctx, "https://news.example.org/feed.xml", NetCause.TAP, null, null, FeedClient.Tor { _, _ -> asked++; null }); null } catch (x: FeedClient.Failure) { x.why }
        assertEquals(NewsFail.NO_TOR, f)
        assertEquals(1, asked)
    }

    @Test fun aTapFetchesAsATapAndStoresOutsideTheBackups() {
        val asked = mutableListOf<NetCause>()
        val r = NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, c, _, _ -> asked += c; FeedClient.Reply(200, rss("One", "Two"), url, "\"e1\"", null) }, now)
        assertEquals(listOf(NetCause.TAP), asked)
        assertEquals(0, (r as NewsStore.Fetched.Done).failed)
        assertEquals(ctx.noBackupFilesDir, NewsStore.file(ctx).parentFile)
        assertEquals(listOf("One", "Two"), NewsStore.current(ctx).pick(daily, now).map { it.title })
        // The next fetch sends the validator back, and a 304 keeps the headlines.
        val sent = mutableListOf<String?>()
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, e, _ -> sent += e; FeedClient.Reply(304, null, url, e, null) }, now + 60_000)
        assertEquals(listOf<String?>("\"e1\""), sent)
        assertEquals(2, NewsStore.current(ctx).pick(daily, now).size)
    }

    /** fix-today-review finding 1: in "Once a day" an open on Wi-Fi catches up a missed morning once, then fetches nothing. */
    @Test fun openingTodayWithOnceADayCatchesUpOnceThenFetchesNothing() {
        var asked = 0
        NewsStore.fetch(ctx, NewsMoment.OPENED, daily, { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("x"), url, null, null) }, now, unmetered = true)
        assertEquals(1, asked)
        val r = NewsStore.fetch(ctx, NewsMoment.OPENED, daily, { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("x"), url, null, null) }, now + 60_000, unmetered = true)
        assertEquals(NewsStore.Fetched.Waited, r)
        assertEquals(1, asked)
        val open = daily.copy(mode = NewsMode.OPEN)
        val later = now + 2 * 3_600_000L
        NewsStore.fetch(ctx, NewsMoment.OPENED, open, { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("x"), url, null, null) }, later, unmetered = true)
        // Within the hour, the next open waits.
        NewsStore.fetch(ctx, NewsMoment.OPENED, open, { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("x"), url, null, null) }, later + 60_000, unmetered = true)
        assertEquals(2, asked)
    }

    /** fix-today-tor: Tor still starting refuses the round as such; nothing is asked, so What went online gets no row. */
    @Test fun torStillStartingRefusesAndNothingGoesOnline() {
        var asked = 0
        val ask = object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: app.heyrobot.core.online.NetCause, etag: String?, modified: String?): FeedClient.Reply { asked++; error("nothing goes") }
            override fun torWhy(): NewsRefusal? = NewsRefusal.TOR_STARTING
        }
        assertEquals(NewsStore.Fetched.Refused(NewsRefusal.TOR_STARTING), NewsStore.fetch(ctx, NewsMoment.TAP, daily.copy(via = NewsVia.TOR), ask, now))
        assertEquals(0, asked)
        assertTrue(!NewsStore.file(ctx).exists())
    }

    /**
     * fix-today-tor: through Tor, the dial (a new circuit per host, slow while Tor warms up) doesn't use up the request's
     * time: a stream that took longer than the direct bound still gets its request (here it fails at TLS, not the deadline).
     */
    @Test fun aSlowTorStreamStillGetsItsRequest() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        val empty = java.io.File(ctx.cacheDir, "tor-stream").apply { writeBytes(ByteArray(0)) }
        FeedClient.timeoutMs = 50; FeedClient.torTimeoutMs = 5_000
        try {
            val why = try {
                FeedClient.fetch(ctx, "https://news.example.org/rss", app.heyrobot.core.online.NetCause.TAP, null, null,
                    FeedClient.Tor { _, _ -> Thread.sleep(300); android.os.ParcelFileDescriptor.open(empty, android.os.ParcelFileDescriptor.MODE_READ_ONLY) })
                null
            } catch (f: FeedClient.Failure) { f.why }
            assertTrue("$why", why != null && why != NewsFail.NO_INTERNET)
        } finally {
            FeedClient.torTimeoutMs = FeedClient.TOR_TIMEOUT_MS
        }
    }

    @Test fun withTorChosenAndDownNothingGoesOnline() {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT-06:26")) // now is 6:00, inside the daily window
        var asked = 0
        val tor = daily.copy(via = NewsVia.TOR)
        for (m in listOf(NewsMoment.TAP, NewsMoment.DAILY)) {
            val r = NewsStore.fetch(ctx, m, tor, { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("x"), url, null, null) }, now, unmetered = true, saver = false)
            assertEquals(NewsStore.Fetched.Refused(NewsRefusal.TOR_DOWN), r)
        }
        assertEquals(0, asked)
        assertTrue(!NewsStore.file(ctx).exists())
    }

    @Test fun aFailingFeedKeepsWhatItHadAndHostileRepliesStoreNothing() {
        NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ -> FeedClient.Reply(200, rss("Kept"), url, null, null) }, now)
        val r = NewsStore.fetch(ctx, NewsMoment.TAP, daily, { url, _, _, _ ->
            FeedClient.Reply(200, "<!DOCTYPE x [<!ENTITY a \"b\">]><rss/>".toByteArray(), url, null, null) }, now + 60_000)
        assertEquals(1, (r as NewsStore.Fetched.Done).failed)
        assertEquals(listOf("Kept"), NewsStore.current(ctx).pick(daily, now + 60_000).map { it.title })
    }

    @Test fun findReadsAFeedOrThePagesFeedLink() {
        val page = """<html><head><link rel="alternate" type="application/rss+xml" href="/feed.xml"></head></html>""".toByteArray()
        val urls = mutableListOf<String>()
        val f = NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, { url, c, _, _ ->
            urls += url
            assertEquals(NetCause.TAP, c)
            FeedClient.Reply(200, if (url.endsWith("feed.xml")) rss("A", "B", "C", "D") else page, url, null, null)
        }, now)
        assertEquals(listOf("https://news.example.org/", "https://news.example.org/feed.xml"), urls)
        assertEquals("https://news.example.org/feed.xml", (f as NewsStore.Found.Feed).url)
        assertEquals("Example", f.feed.name)
        assertEquals(NewsStore.Found.Nothing(NewsFail.BAD_ADDRESS), NewsStore.find(ctx, "http://8.8.8.8/feed", NewsVia.DIRECT, { _, _, _, _ -> error("no request") }, now)) // H8: plain http is taken now; an internet address typed as a number never
        assertEquals(NewsStore.Found.Nothing(NewsFail.NOT_A_FEED), NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, { url, _, _, _ -> FeedClient.Reply(200, "<html/>".toByteArray(), url, null, null) }, now))
    }

    /** H8: a feed reader on your own server, plain http, fetched for real over a socket on the phone's own network (loopback here). */
    @Test fun aFeedOnYourOwnNetworkIsFetchedOverHttpAndOneOnTheInternetIsNot() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        val body = rss("Home one")
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val asked = StringBuilder()
            val serve = Thread {
                server.accept().use { c ->
                    val r = c.getInputStream().bufferedReader()
                    asked.append(r.readLine())
                    while (r.readLine()?.isNotEmpty() == true) {}
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray() + body)
                }
            }.apply { start() }
            FeedClient.resolve = { arrayOf(java.net.InetAddress.getByName("127.0.0.1")) }
            val r = FeedClient.fetch(ctx, "http://reader.lan:${server.localPort}/i/?a=rss", NetCause.TAP, null, null)
            serve.join(5_000)
            assertEquals(200, r.code)
            assertEquals(body.toList(), r.body!!.toList())
            assertEquals("GET /i/?a=rss HTTP/1.1", asked.toString())
        }
        // Refused before any connection: plain http to a name on the internet, and your own server's name resolving there.
        FeedClient.resolve = { arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }
        for (u in listOf("http://reader.example.org/rss", "https://reader.lan/rss")) {
            val why = try { FeedClient.fetch(ctx, u, NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
            assertEquals(u, NewsFail.NOT_HOME, why) // today-h8-fix 7: it says why
        }
    }

    /** H8: with Tor chosen and down, a feed on your own network still goes (direct); one more on the internet fails the round closed. */
    @Test fun withTorChosenYourOwnNetworkGoesDirectAndTheRestFailsClosed() {
        val own = NewsFeed("http://192.168.1.5:8080/rss", "Mine")
        var asked = 0
        val ask = NewsStore.Ask { url, _, _, _ -> asked++; FeedClient.Reply(200, rss("Home"), url, null, null) }
        val r = NewsStore.fetch(ctx, NewsMoment.TAP, NewsSettings(listOf(own), via = NewsVia.TOR), ask, now, unmetered = true, saver = false)
        assertTrue("$r", r !is NewsStore.Fetched.Refused)
        assertEquals(1, asked)
        assertEquals(NewsStore.Fetched.Refused(NewsRefusal.TOR_DOWN),
            NewsStore.fetch(ctx, NewsMoment.TAP, NewsSettings(listOf(own, feed), via = NewsVia.TOR), ask, now + 3_600_000, unmetered = true, saver = false))
        assertEquals(1, asked)
        // Find too: your own server's address is asked directly, Tor down or not.
        assertTrue(NewsStore.find(ctx, "http://192.168.1.5:8080/rss", NewsVia.TOR, ask, now) is NewsStore.Found.Feed)
        assertEquals(2, asked)
    }

    /** H8: a feed whose address is a secret is fetched only from its key file; with none (restored, say) it asks nothing. */
    @Test fun aSecretFeedWithNoKeyFileAsksNothing() {
        val id = app.heyrobot.core.news.NewsSecret.id()
        val secret = NewsFeed(app.heyrobot.core.news.NewsSecret.standIn("http://192.168.1.5/i/?token=abcdef123456XYZ9", id)!!, "Mine", id)
        var asked = 0
        NewsStore.fetch(ctx, NewsMoment.TAP, NewsSettings(listOf(secret)), { url, _, _, _ -> asked++; reply(url, rss("x")) }, now, unmetered = true, saver = false)
        assertEquals(0, asked)
        assertEquals(null, NewsKeys.address(ctx, secret))
    }

    /** Q35: a token over plain http to anything but this phone (Tailscale's 100.64/10 too) is refused before connecting; Find says so. */
    @Test fun aTokenOverPlainHttpIsRefusedBeyondThisPhone() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        FeedClient.resolve = { arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5))) }
        val why = try { FeedClient.fetch(ctx, "http://nas:8080/i/?a=rss&token=abcdef123456XYZ9", NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
        assertEquals(NewsFail.TOKEN_OVER_HTTP, why)
        assertEquals(NewsStore.Found.Nothing(NewsFail.TOKEN_OVER_HTTP),
            NewsStore.find(ctx, "http://192.168.1.5:8080/i/?a=rss&token=abcdef123456XYZ9", NewsVia.DIRECT, { _, _, _, _ -> error("no request") }, now))
        for (a in listOf(byteArrayOf(100, 64, 0, 9), byteArrayOf(10, 0, 0, 2))) {
            FeedClient.resolve = { arrayOf(java.net.InetAddress.getByAddress(a)) }
            assertEquals(NewsFail.TOKEN_OVER_HTTP, try { FeedClient.fetch(ctx, "http://nas:8080/i/?a=rss&token=abcdef123456XYZ9", NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why })
        }
        assertEquals(NewsStore.Found.Nothing(NewsFail.TOKEN_OVER_HTTP),
            NewsStore.find(ctx, "http://100.70.1.2:8080/i/?token=abcdef123456XYZ9", NewsVia.DIRECT, { _, _, _, _ -> error("no request") }, now))
        // On this phone it goes, not refused as a token over http (nothing listens here, so it fails to connect).
        FeedClient.resolve = { arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))) }
        val local = try { FeedClient.fetch(ctx, "http://localhost:9/i/?token=abcdef123456XYZ9", NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
        assertTrue("$local", local != NewsFail.TOKEN_OVER_HTTP && local != NewsFail.NOT_HOME)
        var asked = 0
        val got = NewsStore.find(ctx, "http://127.0.0.1:8080/i/?a=rss&token=abcdef123456XYZ9", NewsVia.DIRECT, { url, _, _, _ -> asked++; reply(url, rss("x")) }, now)
        assertEquals("$got", 1, asked)
        // Plain http to the internet says what's wrong (finding 7).
        FeedClient.resolve = { arrayOf(java.net.InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))) }
        val out = try { FeedClient.fetch(ctx, "http://reader.example.org/rss", NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
        assertEquals(NewsFail.NOT_HOME, out)
    }

    /** today-h8-fix 4: your server's redirect to an internet name is refused before that name is looked up. */
    @Test fun aHomeRedirectOutIsRefusedBeforeAnyLookup() {
        org.robolectric.Shadows.shadowOf(ctx as android.app.Application).grantPermissions(android.Manifest.permission.INTERNET)
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val serve = Thread {
                server.accept().use { c ->
                    val r = c.getInputStream().bufferedReader()
                    while (r.readLine()?.isNotEmpty() == true) {}
                    c.getOutputStream().write("HTTP/1.1 302 Found\r\nLocation: https://id-1234.example.net/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }.apply { start() }
            val looked = mutableListOf<String>()
            FeedClient.resolve = { looked += it; arrayOf(java.net.InetAddress.getByName("127.0.0.1")) }
            val why = try { FeedClient.fetch(ctx, "http://reader.lan:${server.localPort}/rss", NetCause.TAP, null, null); null } catch (f: FeedClient.Failure) { f.why }
            serve.join(5_000)
            assertEquals(NewsFail.SERVER, why)
            assertEquals(listOf("reader.lan"), looked)
        }
    }

    /** today-h8-fix 3: a page on the internet can't send Find to your network. */
    @Test fun findOnAnInternetPageRefusesALinkToYourNetwork() {
        for (link in listOf("https://192.168.1.1:8443/", "https://router.local/feed")) {
            val page = """<html><head><link rel="alternate" type="application/rss+xml" href="$link"></head></html>""".toByteArray()
            val urls = mutableListOf<String>()
            val f = NewsStore.find(ctx, "news.example.org", NewsVia.DIRECT, { url, _, _, _ -> urls += url; FeedClient.Reply(200, page, url, null, null) }, now)
            assertEquals(link, NewsStore.Found.Nothing(NewsFail.BAD_ADDRESS), f)
            assertEquals(listOf("https://news.example.org/"), urls)
        }
    }

    /** today-h8-fix 6: a secret feed's item link that carries its token is dropped, before it's stored or opened. */
    @Test fun aSecretFeedsItemLinksDropTheToken() {
        val q = "https://192.168.1.5/i/?a=rss&token=abcdef123456XYZ9"
        val body = ("<rss><channel><title>Mine</title><item><title>Leak</title><link>https://192.168.1.5/i/?a=item&amp;token=abcdef123456XYZ9</link></item>" +
            "<item><title>Fine</title><link>https://news.example.org/story</link></item></channel></rss>").toByteArray()
        val f = NewsStore.find(ctx, q, NewsVia.DIRECT, { url, _, _, _ -> FeedClient.Reply(200, body, url, null, null) }, now)
        assertEquals(listOf("Fine"), (f as NewsStore.Found.Feed).feed.items.map { it.title })
    }
}
