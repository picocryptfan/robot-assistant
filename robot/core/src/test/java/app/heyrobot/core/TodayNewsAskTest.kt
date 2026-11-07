// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** Today H7b: "read me the news" as a whole utterance, in English and French, and held on the locked phone. */
class TodayNewsAskTest {
    private val now = ZonedDateTime.parse("2026-11-03T09:00:00-04:00")
    private fun route(s: String) = RoutineRouter.route(s, now)

    /** fix-today-tor: asked while Tor is still starting, Robot says so instead of old headlines or "no headlines". */
    @Test fun torStillStartingIsSaidInsteadOfTheHeadlines() {
        assertEquals("Tor is still starting. Ask me again in a minute.", app.heyrobot.core.news.NewsAsk.instead(app.heyrobot.core.news.NewsRefusal.TOR_STARTING)?.en())
        assertNull(app.heyrobot.core.news.NewsAsk.instead(null))
        assertNull(app.heyrobot.core.news.NewsAsk.instead(app.heyrobot.core.news.NewsRefusal.TOR_DOWN))
    }

    /** today-news-off: with no headlines, the reason the page gives, in English and in French. */
    @Test fun noHeadlinesSaysWhy() {
        val ask = app.heyrobot.core.news.NewsAsk
        val tor = app.heyrobot.core.news.NewsSettings(listOf(app.heyrobot.core.news.NewsFeed("https://news.example.org/feed.xml", "Example")),
            via = app.heyrobot.core.news.NewsVia.TOR)
        assertEquals("You have no news feeds. Add one in Settings › Display.", ask.nothing(app.heyrobot.core.news.NewsSettings(), torOff = false).en())
        assertEquals("No headlines: news goes only through Tor, and Tor runs only while Robot to Robot is on. Turn it on, or choose Direct.",
            ask.nothing(tor, torOff = true).en())
        assertEquals(ask.nothing(tor, torOff = true).en(), ask.nothing(tor, false, app.heyrobot.core.news.NewsRefusal.TOR_OFF).en())
        assertEquals("Tor is still starting. Ask me again in a minute.", ask.nothing(tor, false, app.heyrobot.core.news.NewsRefusal.TOR_STARTING).en())
        assertEquals("No headlines: Tor couldn't connect, and nothing went out without it. Ask me again later.",
            ask.nothing(tor, false, app.heyrobot.core.news.NewsRefusal.TOR_DOWN).en())
        assertEquals("None of your feeds answered. Ask me again later.", ask.nothing(tor, false, failedAll = true).en())
        assertEquals("No headlines yet. Ask me again later.", ask.nothing(tor, false).en())
        val fr = app.heyrobot.core.i18n.Langs.of(Lang.FR)
        assertTrue(ask.nothing(tor, torOff = true).render(fr).contains("Robot à Robot est activé"))
        assertTrue(ask.nothing(app.heyrobot.core.news.NewsSettings(), false).render(fr).contains("Réglages › Affichage"))
        assertTrue(ask.nothing(tor, false, failedAll = true).render(fr).startsWith("Aucun de tes fils n’a répondu."))
    }

    @Test fun readMeTheNewsIsTheWholeUtterance() {
        for (s in listOf("Read me the news", "read me the news please", "Hey Robot, what's in the news?", "The news.", "Lis-moi les nouvelles",
            "C'est quoi les nouvelles ?", "Quelles sont les nouvelles"))
            assertEquals(s, RoutineCommand.News, route(s))
        for (s in listOf("read me the news about the election", "what's in the news on the radio", "is the news good", "lis-moi les nouvelles du sport"))
            assertNotEquals(s, RoutineCommand.News, route(s))
    }

    @Test fun withoutTodaysNewsItStaysAQuestionForThePanel() {
        assertNull(app.heyrobot.core.news.NewsAsk.routed(RoutineCommand.News) { false })
        assertEquals(RoutineCommand.News, app.heyrobot.core.news.NewsAsk.routed(RoutineCommand.News) { true })
        assertEquals(RoutineCommand.Today, app.heyrobot.core.news.NewsAsk.routed(RoutineCommand.Today) { error("not read") })
    }

    @Test fun saidInFrenchItIsAnsweredInFrench() {
        assertEquals(Lang.FR, RoutineRouter.lang("Lis-moi les nouvelles", RoutineCommand.News))
        assertTrue(RoutineRouter.lang("Read me the news", RoutineCommand.News) != Lang.FR)
    }

    @Test fun theLockedPhoneHoldsTheNews() {
        assertEquals("Unlock first: the news is read with the phone unlocked.", RoutineLockedPhone.refuse(RoutineCommand.News, locked = true))
        assertTrue(RoutineLockedPhone.refuse(RoutineCommand.News, locked = true, fr = true)!!.startsWith("Déverrouille"))
        assertNull(RoutineLockedPhone.refuse(RoutineCommand.News, locked = false))
        assertEquals(16, RoutineCommandCode.of(RoutineCommand.News).id)
    }
}
