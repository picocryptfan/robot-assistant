// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.routines

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineStep
import app.heyrobot.sys.FeedClient
import app.heyrobot.core.news.NewsFeed
import app.heyrobot.core.news.NewsFail
import app.heyrobot.core.news.NewsMode
import app.heyrobot.core.news.NewsRefusal
import app.heyrobot.core.news.NewsSettings
import app.heyrobot.core.news.NewsVia
import app.heyrobot.core.online.NetCause
import app.heyrobot.sys.HomeSettingsStore
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.NewsStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * today-news-off: "read me the news" with no headlines says the reason the page gives (merge-today-124 found it said "No
 * headlines yet" with Tor chosen and Robot to Robot off): no feeds, Tor off, Tor starting or down, no feed answered.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class NewsNothingSaidTest {
    private val ctx: Context by lazy { VoiceHub.init(ApplicationProvider.getApplicationContext()); VoiceHub.app }
    private val feed = NewsFeed("https://news.example.org/feed.xml", "Example")

    private class Fake : PanelRoutines.Host {
        val said = ArrayList<String>()
        override val speak = true
        override val locked = false
        override fun text(replyId: String, text: String) = Unit
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) { said += sentence }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?) = false
        override fun show(replyId: String, card: Card) = Unit
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() = Unit
    }

    @Before fun hub() { ctx; NewsStore.file(ctx).delete() }

    @After fun clean() {
        NewsStore.asker = NewsStore.LIVE
        HomeSettingsStore.update(ctx) { it.copy(news = NewsSettings()) }
        LinkSettingsStore.update(ctx) { it.copy(on = false) }
        NewsStore.file(ctx).delete()
    }

    private fun ask(news: NewsSettings, linkOn: Boolean = false): String {
        LinkSettingsStore.update(ctx) { it.copy(on = linkOn) }
        HomeSettingsStore.update(ctx) { it.copy(home = app.heyrobot.core.HomeChoice.TODAY, news = news) }
        val host = Fake()
        runBlocking { PanelRoutines.handle("reply", RoutineCommand.News, host, "read me the news") }
        return host.said.joinToString(" ")
    }

    private fun tor(why: NewsRefusal?) {
        NewsStore.asker = { _, _ -> object : NewsStore.Ask, NewsStore.ThroughTor {
            override fun get(url: String, cause: NetCause, etag: String?, modified: String?): FeedClient.Reply = error("nothing goes")
            override fun torWhy(): NewsRefusal? = why
        } }
    }

    @Test fun noFeeds() = assertEquals("You have no news feeds. Add one in Settings › Display.", ask(NewsSettings()))

    @Test fun torOnlyWithRobotToRobotOff() = assertEquals(
        "No headlines: news goes only through Tor, and Tor runs only while Robot to Robot is on. Turn it on, or choose Direct.",
        ask(NewsSettings(listOf(feed), via = NewsVia.TOR)))

    @Test fun torOnlyWithRobotToRobotOffWhenAsked() {
        tor(NewsRefusal.TOR_OFF)
        assertEquals("No headlines: news goes only through Tor, and Tor runs only while Robot to Robot is on. Turn it on, or choose Direct.",
            ask(NewsSettings(listOf(feed), mode = NewsMode.ASKED, via = NewsVia.TOR)))
    }

    @Test fun torStillStarting() {
        tor(NewsRefusal.TOR_STARTING)
        assertEquals("Tor is still starting. Ask me again in a minute.", ask(NewsSettings(listOf(feed), mode = NewsMode.ASKED, via = NewsVia.TOR), linkOn = true))
    }

    @Test fun torDown() {
        tor(NewsRefusal.TOR_DOWN)
        assertEquals("No headlines: Tor couldn't connect, and nothing went out without it. Ask me again later.",
            ask(NewsSettings(listOf(feed), mode = NewsMode.ASKED, via = NewsVia.TOR), linkOn = true))
    }

    @Test fun noFeedAnswered() {
        NewsStore.asker = { _, _ -> NewsStore.Ask { _, _, _, _ -> throw FeedClient.Failure(NewsFail.SERVER) } }
        assertEquals("None of your feeds answered. Ask me again later.", ask(NewsSettings(listOf(feed), mode = NewsMode.ASKED)))
    }
}
