// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.core.web.PublicSearxng
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
        rule.shot("web_search_setup")
        rule.scrollToText(WebSetup.NOT_OFFERED, substring = true)
        rule.onNodeWithText(WebSetup.NOT_OFFERED, substring = true).assertExists()
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun settingUpYourSearxngEndsWithTheConsentAndATest() {
        val h = open(facts = WebFacts(homeServer = true))
        rule.onRow("Set up").performClick()
        // Your own SearXNG first with a home server, suggested; 1.0: Wikipedia only offered too (Robot publishes a contact).
        rule.inDialog("Your own SearXNG (suggested)").assertExists()
        rule.onNode(hasText("Wikipedia only", substring = true) and androidx.compose.ui.test.hasAnyAncestor(isDialog())).assertExists()
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
        rule.inDialog(WebSetup.consent(SearchService.SEARXNG_HOME, WebSettings())).assertExists()
        assertTrue(WebSetup.consent(SearchService.SEARXNG_HOME, WebSettings()).contains("those sites see your IP address"))
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
        // 1.0: Robot publishes a contact, so Encyclopedia lookups can be turned on (and is, by default); Read pages through
        // needs Brave or Kagi.
        rule.onRow("Encyclopedia lookups").assertIsEnabled()
        rule.onRow("Read pages through").assertIsNotEnabled()
        rule.onRow("Book lookups").performClick()
        assertTrue(h.saved.last().books)
        // Off again: the rows say so.
        rule.scrollToText("Use web search")
        rule.onRow("Use web search").performClick()
        assertTrue(!h.saved.last().on)
    }

    /**
     * 1.0: every service and every engine as a list, grouped "Robot reads the results" and "Opens in your browser", each row
     * with its privacy level's chip and its line; a tap sets a service up, or makes an engine the default.
     */
    @Test fun everyServiceAndEngineIsListedWithItsLevel() {
        val h = open(searxng, tested)
        rule.scrollToText("Robot reads the results")
        rule.onNode(hasText("Your own SearXNG") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(hasText("In use · ", substring = true)).assert(hasContentDescription("Privacy: High"))
        for ((name, level) in listOf("Kagi" to "High", "Brave Search API" to "Medium", "DuckDuckGo Instant Answers" to "High", "A public SearXNG" to "Low",
            "Wikipedia only" to "Medium")) {
            rule.scrollToText(name)
            rule.onNode(hasText(name) and hasClickAction()).assert(hasContentDescription("Privacy: $level"))
        }
        rule.scrollToText("Opens in your browser")
        rule.onNode(hasText("Startpage") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assert(hasText("Default · ", substring = true)).assert(hasContentDescription("Privacy: High"))
        for ((name, level) in listOf("DuckDuckGo" to "High", "Brave Search" to "High", "Qwant" to "Medium", "Ecosia" to "Medium", "Google" to "Low", "Bing" to "Low")) {
            rule.scrollToText(name)
            rule.onNode(hasText(name) and hasClickAction()).assert(hasContentDescription("Privacy: $level"))
            assertEquals(name, emptyList<String>(), rule.touchTargetProblems())
        }
        rule.scrollToText("DuckDuckGo")
        rule.onNode(hasText("DuckDuckGo") and hasClickAction()).performClick()
        assertEquals(BrowserEngine.DUCKDUCKGO, h.saved.last().browserEngine)
        // Another one goes through its set-up: DuckDuckGo's short answers need only the consent (no pages are read).
        rule.scrollToText("DuckDuckGo Instant Answers")
        rule.onNode(hasText("DuckDuckGo Instant Answers") and hasClickAction()).performClick()
        assertEquals(SearchService.DDG_INSTANT, h.saved.last().service)
        rule.inDialog(WebSetup.consent(SearchService.DDG_INSTANT, WebSettings())).assertExists()
        rule.inDialog("Turn on web search").performClick()
        assertTrue(h.saved.last().on && h.saved.last().ready)
        assertEquals(listOf(WebWindow.MODE_TEST), h.windows)
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theServiceInUseKeepsItsSetUp() {
        val h = open(searxng, tested)
        rule.scrollToText("In use · ", substring = true)
        rule.onNode(hasText("Your own SearXNG") and hasClickAction()).performClick()
        // Its address, as typed; nothing changes unless you save another.
        rule.inDialog("Cancel").performClick()
        assertTrue(h.saved.isEmpty())
    }

    @Test fun aPublicSearxngFromTheShortListOrYourOwnAddress() {
        val h = open()
        rule.scrollToText("A public SearXNG")
        rule.onNode(hasText("A public SearXNG") and hasClickAction()).performClick()
        rule.inDialog("Other address").assertExists()
        rule.inDialog("A stranger runs each one", substring = true).assertExists()
        rule.inDialog(PublicSearxng.host(PublicSearxng.LIST.first())).performClick()
        assertEquals(PublicSearxng.LIST.first(), h.saved.last().publicSearxng)
        assertEquals(SearchService.SEARXNG_PUBLIC, h.saved.last().service)
        rule.inDialog(WebSetup.consent(SearchService.SEARXNG_PUBLIC, WebSettings())).assertExists()
        rule.inDialog("Not now").performClick()
        assertTrue(!h.saved.last().on)
    }

    @Test fun enginesLight() {
        open(searxng, tested)
        rule.scrollToText("Opens in your browser")
        rule.shot("web_engines_light")
    }

    @Test fun enginesDark() {
        open(searxng, tested, dark = true)
        rule.scrollToText("Robot reads the results")
        rule.shot("web_engines_dark")
    }

    @Test fun light() {
        open(searxng, tested)
        rule.shot("web_search_light")
    }

    @Test fun dark() {
        open(searxng, tested, dark = true)
        rule.shot("web_search_dark")
    }

    /** 1.1: French menus (values-fr-rCA and core's WebStrings): the page, its choices and the consent screen in Québec French. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        open(searxng, tested)
        rule.onNodeWithText("Recherche web").assertExists()
        rule.onRow("Utiliser la recherche web").assertIsEnabled()
        rule.onRow("Demander avant de chercher").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Demander la première fois"))
        rule.onNodeWithText("Ton propre SearXNG").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("web_search_fr")
        rule.scrollToText("S’ouvre dans ton navigateur")
        assertTrue(rule.onAllNodes(hasContentDescription("Confidentialité\u00A0: Élevé")).fetchSemanticsNodes().isNotEmpty())
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun consentInFrench() {
        open()
        rule.onRow("Configurer").performClick()
        rule.inDialog("Réponses instantanées DuckDuckGo").performClick()
        // Encyclopedia lookups are on by default: a who, what or where question goes to Wikipedia too, and the consent says so.
        rule.inDialog("Quand tu le demandes, Robot envoie tes mots de recherche à DuckDuckGo. Une question qui commence par «\u00A0qui\u00A0», " +
            "«\u00A0quel\u00A0», «\u00A0quelle\u00A0», «\u00A0qu’est-ce\u00A0» ou «\u00A0où\u00A0» va aussi à Wikipédia. Rien d’autre ne quitte le téléphone.").assertExists()
        rule.inDialog("Pas maintenant").performClick()
    }
}
