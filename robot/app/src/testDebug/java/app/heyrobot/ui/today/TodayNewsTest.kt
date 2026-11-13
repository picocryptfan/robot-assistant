// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.today

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.R
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.news.FeedWire
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.news.NewsFeed
import app.heyrobot.core.news.NewsMode
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.core.news.NewsSettings
import app.heyrobot.core.news.NewsVia
import app.heyrobot.core.online.NetCause
import app.heyrobot.sys.FeedClient
import app.heyrobot.sys.FeedParseLink
import app.heyrobot.sys.NewsStore
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Today H7b: the news part as the page draws it from what's stored and what a fetch answers: no feeds, some feeds, a feed
 * that failed, Tor chosen and not there, and Today off. Made-up feeds; nothing goes online.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TodayNewsTest {
    @get:Rule val rule = createComposeRule()

    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val feed = NewsFeed("https://news.example.org/feed.xml", "Example")
    private val asked = mutableListOf<String>()
    private fun rss(vararg titles: String) = ("<rss><channel><title>Example</title>" +
        titles.mapIndexed { i, t -> "<item><title>$t</title><link>https://news.example.org/$i</link></item>" }.joinToString("") + "</channel></rss>").toByteArray()

    @Before fun pure() { NewsStore.parser = { NewsStore.Parse { b, base, at -> FeedWire.check(FeedWire.answer(b, base, at), at) } } }

    @After fun clean() { NewsStore.file(ctx).delete(); NewsStore.parser = { FeedParseLink(it) }; NewsStore.asker = NewsStore.LIVE }

    /** Each feed answers [body], or fails when it's null. */
    private fun answering(body: ByteArray?) {
        NewsStore.asker = { _, _ -> NewsStore.Ask { url, _, _, _ ->
            asked += url
            if (body == null) throw FeedClient.Failure(NewsFail.SERVER) else FeedClient.Reply(200, body, url, null, null)
        } }
    }

    /** fix-today-review 2: what Robot fetches by itself waits for an unmetered network unless mobile data is allowed. */
    private fun onWifi() {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, caps)
    }

    private fun page(news: NewsSettings, today: Boolean = true) = rule.show { TodayNews(HomeSettings(home = app.heyrobot.core.HomeChoice.of(today), news = news), Lang.EN) }
    private fun shows(text: String) = rule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun waitFor(id: Int) = rule.waitUntil(10_000) { shows(ctx.getString(id)) }

    @Test fun noFeedsShowNothingAndFetchNothing() {
        answering(rss("Harbour bridge reopens"))
        page(NewsSettings(mode = NewsMode.OPEN))
        rule.waitForIdle()
        rule.onAllNodes(hasText(ctx.getString(R.string.today_news_title))).assertCountEquals(0)
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun someFeedsShowTheirHeadlines() {
        answering(rss("Harbour bridge reopens", "Ferry schedule changes"))
        onWifi()
        page(NewsSettings(listOf(feed), mode = NewsMode.OPEN))
        rule.waitUntil(10_000) { shows("Harbour bridge reopens") }
        assertEquals(true, shows("Ferry schedule changes"))
        assertEquals(listOf(feed.url), asked)
    }

    @Test fun aFeedThatFailedSaysSoOnYourTap() {
        answering(null)
        page(NewsSettings(listOf(feed), mode = NewsMode.ASKED))
        waitFor(R.string.today_news_fetch)
        assertEquals(emptyList<String>(), asked)
        rule.onNode(hasText(ctx.getString(R.string.today_news_fetch))).performClick()
        waitFor(R.string.today_news_failed)
        assertEquals(listOf(feed.url), asked)
    }

    /** fix-today-tor: Tor still starting (Robot to Robot just turned on): the page says so and to try again, and sends nothing. */
    @Test fun torStillStartingSaysSoAndSendsNothing() {
        NewsStore.asker = { _, _ -> object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply { asked += url; error("nothing goes") }
            override fun torWhy(): NewsRefusal? = NewsRefusal.TOR_STARTING
        } }
        page(NewsSettings(listOf(feed), mode = NewsMode.ASKED, via = NewsVia.TOR))
        waitFor(R.string.today_news_fetch)
        rule.onNode(hasText(ctx.getString(R.string.today_news_fetch))).performClick()
        waitFor(R.string.today_news_tor_starting)
        assertEquals(emptyList<String>(), asked)
    }

    /** fix-today-tor-b: Find while Tor starts shows the page's same "Tor is still starting" line, EN and FR. */
    @Test fun findWhileTorStartsSaysTheSameLine() {
        assertEquals(R.string.today_news_tor_starting, failText(NewsFail.TOR_STARTING))
    }

    /** merge-today-124: Find with Robot to Robot off shows Get the news's own line, to turn it on or choose Direct, EN and FR. */
    @Test fun findWithRobotToRobotOffSaysTurnItOn() {
        assertEquals(R.string.today_news_tor_off, failText(NewsFail.TOR_OFF))
        assertTrue(ctx.getString(R.string.today_news_tor_off).contains("Robot to Robot is on"))
        val fr = ctx.createConfigurationContext(android.content.res.Configuration(ctx.resources.configuration).apply { setLocale(java.util.Locale.CANADA_FRENCH) })
        assertTrue(fr.getString(R.string.today_news_tor_off).contains("Robot à Robot est activé"))
    }

    @Test fun torChosenAndNotThereSaysWhyAndSendsNothing() {
        NewsStore.asker = { _, _ -> object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply { asked += url; error("nothing goes") }
            override fun torWhy(): NewsRefusal? = NewsRefusal.TOR_OFF
        } }
        page(NewsSettings(listOf(feed), mode = NewsMode.ASKED, via = NewsVia.TOR))
        waitFor(R.string.today_news_fetch)
        rule.onNode(hasText(ctx.getString(R.string.today_news_fetch))).performClick()
        waitFor(R.string.today_news_tor_off)
        assertEquals(emptyList<String>(), asked)
    }

    /**
     * today-h7-fix 10: Tor restored (or chosen) while Robot to Robot is off: the page says why with "once a day" too (no tap,
     * nothing sent), and Settings' dialog says it beside the choice, where Direct is one tap away.
     */
    @Test fun torThatCantWorkHereSaysWhyAndOffersDirect() {
        answering(rss("x"))
        val tor = NewsSettings(listOf(feed), via = NewsVia.TOR)
        assertEquals(true, torOff(ctx, tor))
        assertEquals(false, torOff(ctx, tor.copy(via = NewsVia.DIRECT)))
        page(tor)
        waitFor(R.string.today_news_tor_off)
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun todayOffShowsNothingAndFetchesNothing() {
        answering(rss("Harbour bridge reopens"))
        page(NewsSettings(listOf(feed), mode = NewsMode.OPEN), today = false)
        rule.waitForIdle()
        rule.onAllNodes(hasText(ctx.getString(R.string.today_news_title))).assertCountEquals(0)
        assertEquals(emptyList<String>(), asked)
    }
}
