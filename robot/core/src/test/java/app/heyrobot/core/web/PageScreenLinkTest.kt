// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.WebStrings
import app.heyrobot.core.web.PageSummaryRouter.From
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-routing-1.4 (review-routing-1.4 M1): "summarize this page" after a screen question read the first link in the
// screen's text with no card, so the screen chose the address the phone contacts. Now a card names its host and only a
// tap opens it; hands-free and in Car mode it isn't opened; a page you named, shared or had read stays as it was.
class PageScreenLinkTest {
    private val ask = PageSummaryAsk(null, "en")
    private fun go(from: From, handsFree: Boolean, driving: Boolean, onScreen: Boolean = false) = PageSummaryRouter.go(from, handsFree, driving, onScreen)

    @Test fun aTrackingLinkAheadOfTheArticleGetsACardNamingItsHost() {
        val screen = "New message\nhttps://click.tracker.example/c/abc123\nRead the article: https://news.example.com/2026/story"
        val p = PageSummaryRouter.pick(ask, null, screen, null)!!
        assertEquals(From.SCREEN, p.from)
        assertEquals(PageGo.CARD, go(p.from, false, false))
        val card = PageSummaryRouter.screenCard("c1", p.url, Langs.EN)
        assertTrue(card.summary, card.summary.contains("click.tracker.example"))
        assertEquals("https://click.tracker.example/c/abc123", card.detail)
        assertEquals(AskWhy.FROM_CONTENT, card.tapFor)
        assertEquals(ActionStatus.PENDING, card.status)
    }

    @Test fun handsFreeAndCarModeNeverOpenAScreenLink() {
        assertEquals(PageGo.LOOK, go(From.SCREEN, true, false))
        assertEquals(PageGo.LOOK, go(From.SCREEN, false, true))
        // fix-routing-1.4-b (review-fix-routing-1.4 L2): a wake-word turn with the panel on screen gets the tap-only card;
        // Car mode never does.
        assertEquals(PageGo.CARD, go(From.SCREEN, true, false, onScreen = true))
        assertEquals(PageGo.LOOK, go(From.SCREEN, true, true, onScreen = true))
        assertEquals(PageGo.LOOK, go(From.SCREEN, false, true, onScreen = true))
        assertTrue(WebStrings.pageScreenLook().render(Langs.EN).contains("phone"))
    }

    /** review-batch-17-18 M2: in Car mode the line said aloud never sends the driver's eyes to the phone. */
    @Test fun carModeWaitsUntilParked() {
        val en = PageSummaryRouter.look(driving = true).render(Langs.EN)
        val fr = PageSummaryRouter.look(driving = true).render(Langs.of(app.heyrobot.core.i18n.Lang.FR))
        for (w in listOf("look", "phone", "parked")) assertEquals(w, w == "parked", en.lowercase().contains(w))
        for (w in listOf("regarde", "téléphone", "stationné")) assertEquals(w, w == "stationné", fr.lowercase().contains(w))
        assertEquals(WebStrings.pageScreenLook().render(Langs.EN), PageSummaryRouter.look(driving = false).render(Langs.EN))
    }

    /** review-fix-batch-19 M1: the router alone, called with driving = true, gives a screen link the parked line every
     *  time, never a tap card or "look at your phone"; the French names no card, since none is shown. That the panel
     *  hands it Car mode unchanged on a second ask is PanelPageSummaryTest.carModeReachesTheScreenLinkRouterUnchanged. */
    @Test fun drivingScreenLinkAlwaysGetsTheParkedLine() {
        val en = WebStrings.pageScreenParked().render(Langs.EN)
        for (hf in listOf(false, true)) for (screen in listOf(false, true)) repeat(2) {
            assertEquals(PageGo.LOOK, go(From.SCREEN, hf, true, onScreen = screen))
            val said = PageSummaryRouter.look(driving = true).render(Langs.EN)
            assertEquals(en, said)
            assertEquals(false, said.contains("Look at your phone"))
        }
        val fr = Langs.of(app.heyrobot.core.i18n.Lang.FR)
        assertEquals(false, PageSummaryRouter.look(driving = true).render(fr).contains("Regarde ton téléphone"))
        for (s in listOf(WebStrings.pageScreenParked(), WebStrings.pageScreenLook()))
            assertEquals(false, s.render(fr).contains("carte"))
    }

    @Test fun aPageYouSharedNamedOrHadReadTakesNoCard() {
        for (f in listOf(From.SAID, From.SHARED, From.LAST_ANSWER)) for (hf in listOf(false, true))
            assertEquals(f.name, PageGo.READ, go(f, hf, hf))
        val shared = PageSummaryRouter.pick(ask, "https://news.example.com/a", "https://click.tracker.example/c", null)!!
        assertEquals(From.SHARED, shared.from)
        assertEquals(PageGo.READ, go(shared.from, false, false))
    }
}
