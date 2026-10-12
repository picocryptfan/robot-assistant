package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebAsk
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.web.WebSetup
import app.heyrobot.sys.WebTest
import app.heyrobot.sys.WebWindow
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Web search (0.9, design A4.1 and A5): Off until set up; set-up picks a service, its address (or its key in the
 * assistant's window), then the consent screen; then every behaviour a row with its default. Screenshots in light and
 * dark, and the page before set-up.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class WebSearchSettingsTest {
    @get:Rule val rule = createComposeRule()

    private class Host(start: WebSettings, val facts: WebFacts = WebFacts()) {
        var now by mutableStateOf(start)
        val saved = ArrayList<WebSettings>()
        val windows = ArrayList<String>()
        val deleted = ArrayList<SearchService>()
    }

    private fun open(start: WebSettings = WebSettings(), facts: WebFacts = WebFacts(), dark: Boolean = false): Host {
        val h = Host(start, facts)
        rule.show(dark = dark) {
            WebSearchSettingsScreen(h.now, h.facts, onChange = { h.saved += it; h.now = it }, window = { h.windows += it }, deleteKey = { h.deleted += it },
                onBack = {})
        }
        return h
    }

    private val searxng = WebSettings(on = true, service = SearchService.SEARXNG_HOME, searxng = "192.168.1.20:8888", askAt = 1)
    private val tested = WebFacts(today = 3, test = WebTest(SearchService.SEARXNG_HOME, true, "JSON on · 9 results · engines: brave, duckduckgo, wikipedia", 1))

    @Test fun offUntilSetUp() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Use web search").assertIsNotEnabled()
        rule.onRow("Set up").assertIsEnabled()
        rule.onNodeWithText(WebSetup.NOT_OFFERED, substring = true).assertExists()
        rule.shot("web_search_setup")
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun settingUpYourSearxngEndsWithTheConsentAndATest() {
        val h = open(facts = WebFacts(homeServer = true))
        rule.onRow("Set up").performClick()
        // Your own SearXNG first with a home server, suggested; Wikipedia only not offered yet (no contact for its rules).
        rule.inDialog("Your own SearXNG (suggested)").assertExists()
        rule.onNode(hasText("Wikipedia only", substring = true) and androidx.compose.ui.test.hasAnyAncestor(isDialog())).assertDoesNotExist()
        rule.inDialog("Your own SearXNG (suggested)").performClick()
        assertEquals(WebSettings(service = SearchService.SEARXNG_HOME), h.saved.last())
        // Its address: an address on your network, or https (plain http to a public address is refused).
        val field = rule.onNode(hasText("Address") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        field.performTextReplacement("http://8.8.8.8:8888")
        rule.inDialog("Save").assertIsNotEnabled()
        field.performTextReplacement("192.168.1.20:8888")
        rule.inDialog("Save").performClick()
        // The consent screen, once: the words, where they go, and that sites see the phone's IP address.
        rule.onNode(isDialog()).assertExists()
        rule.inDialog(WebSetup.consent(SearchService.SEARXNG_HOME)).assertExists()
        assertTrue(WebSetup.consent(SearchService.SEARXNG_HOME).contains("those sites see your IP address"))
        rule.inDialog("Turn on web search").performClick()
        val on = h.saved.last()
        assertTrue(on.on && on.ready && on.searxng == "192.168.1.20:8888" && on.ask == WebAsk.FIRST_TIME)
        assertEquals(listOf(WebWindow.MODE_TEST), h.windows)
    }

    @Test fun aKeyServiceAsksForItsKeyAfterTheConsent() {
        val h = open(WebSettings(service = SearchService.BRAVE))
        rule.onRow("Use web search").performClick()
        rule.inDialog("Turn on web search").performClick()
        assertEquals(listOf(WebWindow.MODE_KEY), h.windows)
        assertTrue(h.saved.last().on)
    }

    @Test fun aSavedKeyIsShownMaskedAndCanBeDeleted() {
        val k = open(WebSettings(on = true, service = SearchService.BRAVE, askAt = 1), WebFacts(masked = "BSA…x7Q2"))
        rule.onRow("API key").assert(hasText("BSA…x7Q2")).performClick()
        assertEquals(listOf(WebWindow.MODE_KEY), k.windows)
        rule.onRow("Delete key").performClick()
        rule.inDialog("Delete").performClick()
        assertEquals(listOf(SearchService.BRAVE), k.deleted)
    }

    @Test fun everyBehaviourIsARowWithItsDefault() {
        val h = open(searxng, tested)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Use web search").assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On))
        rule.onRow("Ask before searching").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, WebAsk.FIRST_TIME.title)).performClick()
        rule.inDialog(WebAsk.NEVER.title).performClick()
        assertEquals(WebAsk.NEVER, h.saved.last().ask)
        for (row in listOf("Read result pages", "Read pages through", "Safe search", "Encyclopedia lookups", "Book lookups", "Add my town to local searches",
            "Current information", "Keep sources in history")) {
            rule.scrollToText(row)
            assertEquals(row, emptyList<String>(), rule.touchTargetProblems())
        }
        // Wikipedia needs a contact Robot doesn't publish yet; Read pages through needs Brave or Kagi.
        rule.onRow("Encyclopedia lookups").assertIsNotEnabled()
        rule.onRow("Read pages through").assertIsNotEnabled()
        rule.onRow("Book lookups").performClick()
        assertTrue(h.saved.last().books)
        // Off again: the rows say so.
        rule.scrollToText("Use web search")
        rule.onRow("Use web search").performClick()
        assertTrue(!h.saved.last().on)
    }

    @Test fun light() {
        open(searxng, tested)
        rule.shot("web_search_light")
    }

    @Test fun dark() {
        open(searxng, tested, dark = true)
        rule.shot("web_search_dark")
    }
}
