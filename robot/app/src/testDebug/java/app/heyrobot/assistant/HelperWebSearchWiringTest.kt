package app.heyrobot.assistant

import android.Manifest
import android.app.Application
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.WeatherPlace
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebSettings
import app.heyrobot.sys.WebStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * review-helper-web-search: the app's own WebTool, as the panel prepares a helper's web_search (PanelActions' three-argument
 * prepare, ToolExecutor.prepareForHelper): your town never goes with a helper's query, and the helpers switch is read at
 * each call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HelperWebSearchWiringTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val said = "find me a pharmacy"
    private val settings = RobotSettings(weatherHome = WeatherPlace("Dartmouth, Nova Scotia, Canada", 44.67, -63.57))

    private fun tool(web: WebSettings): WebTool {
        Shadows.shadowOf(context).grantPermissions(Manifest.permission.INTERNET)
        WebStore.file(context).also { it.parentFile?.mkdirs() }.writeText(web.toJson().toString())
        return WebTool(context, settings, Abort(), said, { QueryContext(listOf(said)) }, { _, _ -> })
    }

    private fun query(p: Prepared): String = ((p as Prepared.Ready).action as Action.WebSearch).query

    @Test fun aHelpersQueryGoesWithoutYourTown() {
        val t = tool(WebSettings(on = true, service = SearchService.BRAVE, addTown = true))
        assertEquals("pharmacy near Dartmouth", query(t.prepare(Action.WebSearch("pharmacy near me"))))
        assertEquals("pharmacy near me", query(t.prepare(Action.WebSearch("pharmacy near me"), helper = true)))
    }

    @Test fun theHelpersSwitchOffRefusesOnlyAHelpersSearch() {
        val t = tool(WebSettings(on = true, service = SearchService.BRAVE, helpersMayUse = false))
        assertTrue(t.prepare(Action.WebSearch("ferry times")) is Prepared.Ready)
        val p = t.prepare(Action.WebSearch("ferry times"), helper = true)
        assertTrue(p is Prepared.Refused)
        assertEquals(ActionStatus.DISABLED, (p as Prepared.Refused).status)
    }
}
