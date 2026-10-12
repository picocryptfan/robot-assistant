package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.BrowserSearchAction
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.web.BrowserEngine
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebRoute
import app.heyrobot.core.web.WebSettings
import app.heyrobot.sys.WebStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0: a search in your browser (BrowserSearchTool): the engine's own address handed to the browser as a link (ACTION_VIEW,
 * nothing else), the card's line from the app's words, and the call your own phrase makes, with Robot's own web search
 * set up or not. Nothing here goes online.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BrowserSearchToolTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @After fun clean() {
        WebStore.file(ctx).delete()
    }

    private fun settings(w: WebSettings) {
        WebStore.file(ctx).parentFile?.mkdirs()
        WebStore.file(ctx).writeText(w.toJson().toString())
    }

    @Test fun theSearchOpensAsALinkInYourBrowser() {
        val sent = mutableListOf<Intent>()
        val before = VoiceLog.total
        val r = BrowserSearchTool.open(ctx, BrowserSearchAction("café crème à Montréal", BrowserEngine.DUCKDUCKGO, own = true)) { sent += it; true }
        assertEquals(ActionStatus.DONE, r.status)
        assertTrue(r.launchedApp)
        assertEquals("Opened DuckDuckGo in your browser.", r.detail)
        val i = sent.single()
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals("https://duckduckgo.com/?q=caf%C3%A9%20cr%C3%A8me%20%C3%A0%20Montr%C3%A9al", i.dataString)
        assertTrue(i.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertTrue(i.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        // No extras: nothing but the address goes to the browser (no package forced, no referrer).
        assertNull(i.extras)
        assertNull(i.`package`)
        // The voice log: the engine and whose words, never the words.
        assertEquals(before + 1, VoiceLog.total)
        val e = VoiceLog.entries().first { it.event == VoiceEvent.BROWSER_SEARCH }
        assertEquals(listOf(BrowserEngine.DUCKDUCKGO.code, 1, 1), listOf(e.a, e.b, e.c))
    }

    @Test fun noEngineNamedIsYourDefaultAndNoBrowserIsSaid() {
        settings(WebSettings(browserEngine = BrowserEngine.MOJEEK))
        val sent = mutableListOf<Intent>()
        BrowserSearchTool.open(ctx, BrowserSearchAction("pizza")) { sent += it; true }
        assertEquals("https://www.mojeek.com/search?q=pizza", sent.single().dataString)
        val none = BrowserSearchTool.open(ctx, BrowserSearchAction("pizza", BrowserEngine.GOOGLE)) { false }
        assertEquals(ActionStatus.NO_APP, none.status)
        assertEquals("No browser opened the search.", none.detail)
    }

    @Test fun theModelsWordsShowTheCardWithTheAppsLine() {
        val p = BrowserSearchTool.prepare(ctx, BrowserSearchAction("Stanley Cup winner"), "look it up on Google", QueryContext(said = listOf("look it up on Google")))
        val r = p as Prepared.Ready
        assertEquals(setOf(AskWhy.SEARCH), r.why)
        assertEquals("Robot opens Google in your browser with these words: your browser sends them, and Robot doesn't see the results.", r.note)
        assertEquals("Search Google for “Stanley Cup winner” in your browser", r.action.describe())
    }

    /** Every engine and service has its line (who runs it, whose results, what is kept), and an engine's names its country. */
    @Test fun everyEngineAndServiceHasItsLine() {
        val country = mapOf("Netherlands" to "Dutch", "United States" to "US", "United Kingdom" to "UK", "France" to "French", "Germany" to "German")
        for (e in BrowserEngine.entries) {
            val line = ctx.getString(app.heyrobot.ui.settings.engineLine(e))
            assertTrue(e.name, line.length in 60..260)
            assertTrue("${e.name}: $line", line.contains(country.getValue(e.country)))
        }
        val lines = SearchService.entries.map { ctx.getString(app.heyrobot.ui.settings.serviceLine(it)) }
        assertTrue(lines.all { it.length in 60..260 })
        assertEquals(lines.size, lines.toSet().size)
        assertEquals("Privacy: Medium", ctx.getString(app.heyrobot.R.string.privacy_level_desc, app.heyrobot.core.web.PrivacyLevel.MEDIUM.title))
    }

    @Test fun yourPhraseOpensYourBrowserAndRobotsOwnSearchWhileItIsOff() {
        assertEquals(RawToolCall("browser_search", mapOf("query" to "tide times", "engine" to "duckduckgo")),
            BrowserSearchTool.directCall(ctx, WebRoute.Browser("tide times", BrowserEngine.DUCKDUCKGO)))
        // Robot's own web search not set up: "search the web for …" opens your default; "google …" opens Google.
        assertEquals(RawToolCall("browser_search", mapOf("query" to "cats")), BrowserSearchTool.directCall(ctx, WebRoute.Search("cats")))
        assertEquals(RawToolCall("browser_search", mapOf("query" to "cats", "engine" to "google")), BrowserSearchTool.directCall(ctx, WebRoute.Search("cats", google = true)))
        // Set up and on: Robot's own search, as in 0.9.
        settings(WebSettings(on = true, service = SearchService.DDG_INSTANT, askAt = 1))
        assertNull(BrowserSearchTool.directCall(ctx, WebRoute.Search("cats")))
        assertEquals("browser_search", BrowserSearchTool.directCall(ctx, WebRoute.Browser("cats", null))?.name)
    }
}
