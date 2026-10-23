// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.Cited
import app.heyrobot.core.web.KeepSources
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.SearchProvider
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebSource
import app.heyrobot.core.web.WikipediaWire
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import app.heyrobot.readLine
import app.heyrobot.savedLine

/**
 * A web answer in the panel (0.9, design A4.4 and A9): the answer's text, then its card (which service searched and
 * what it was sent, the numbered sources, the pages read and skipped, Wikipedia's licence line) standing in for the
 * search's own action card; a source opens in the browser only on a tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class WebAnswerPanelTest {
    @get:Rule val rule = createComposeRule()

    private val sources = listOf(
        WebSource(1, "Panthers win back-to-back Stanley Cups", "https://www.nhl.com/news/panthers-win-cup", SearchProvider.SEARXNG, read = true),
        WebSource(2, "2026 Stanley Cup Final", "https://en.wikipedia.org/wiki/2026_Stanley_Cup_Final", SearchProvider.SEARXNG),
        WebSource(3, "Panthers repeat as champions", "https://www.tsn.ca/nhl/panthers-cup", SearchProvider.SEARXNG),
    )
    private val card = AnswerCard.of(SearchService.SEARXNG_HOME, "who won the stanley cup this year",
        Cited("According to NHL.com, the Florida Panthers won the Stanley Cup again this year [1], beating the Edmonton Oilers in six games [2].",
            sources.take(2), sources.drop(2).map { it.copy(n = 3) }),
        pagesRead = 1, skipped = listOf("example.org" to PageSkip.NEEDS_JS))
    private val searchCard = UiAction("w1", ToolName.WEB_SEARCH, card.savedLine(KeepSources.DOMAINS), ActionStatus.DONE, card.readLine, content = true)
    private val state = PanelState("c1", turns = listOf(
        UiTurn("u1", TurnRole.USER, "Who won the Stanley Cup this year?"),
        UiTurn("r1", TurnRole.ASSISTANT, "According to NHL.com, the Florida Panthers won the Stanley Cup again this year [1], beating the Edmonton Oilers in six games [2].",
            actions = listOf(searchCard), web = card),
    ), speak = true)

    @Test fun theCardsLinesAndItsSourcesOpenOnATap() {
        val actions = RecordingActions()
        rule.showPanel(state, actions)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Searched the web · your SearXNG").assertExists()
        rule.onNodeWithText("Read 1 page from this phone · Sent to your SearXNG: “who won the stanley cup this year”").assertExists()
        rule.onNodeWithText("example.org: needs JavaScript").assertExists()
        rule.onNodeWithText("From Wikipedia's article “2026 Stanley Cup Final”, CC BY-SA 4.0 (licence)").assertExists()
        // The search's own card gives way to the answer's.
        rule.onNodeWithText(card.savedLine(KeepSources.DOMAINS)).assertDoesNotExist()
        rule.onNode(hasContentDescription("Source 1: Panthers win back-to-back Stanley Cups, nhl.com")).performClick()
        rule.onNodeWithText("From Wikipedia's article “2026 Stanley Cup Final”, CC BY-SA 4.0 (licence)").performClick()
        assertEquals(listOf("openSource r1 https://www.nhl.com/news/panthers-win-cup", "openSource r1 ${WikipediaWire.LICENCE_URL}"), actions.calls)
        // History keeps the saved line (the domains), never the titles or the links.
        assertEquals("Searched the web (your SearXNG) · nhl.com, en.wikipedia.org, tsn.ca", card.savedLine(KeepSources.DOMAINS))
        assertTrue(card.historySources(KeepSources.DOMAINS).none { it.contains("/") })
    }

    @Test fun classic() {
        rule.showPanel(state)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("panel_classic_web_answer")
    }
}
