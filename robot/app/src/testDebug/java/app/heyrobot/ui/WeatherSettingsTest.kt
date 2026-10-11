package app.heyrobot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.WeatherPlace
import app.heyrobot.ui.pref.PreferenceGroup
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Weather, and the weather and phone status rows of Assistant setup. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class WeatherSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val montreal = WeatherPlace("Montreal", 45.5088, -73.5878)

    private fun open(start: RobotSettings, snap: Snapshot = Snapshot()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> WeatherSettingsScreen(s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    @Test fun theHomeAndLocationRows() {
        open(RobotSettings())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Home for weather").assert(hasText("Not set"))
        rule.onNodeWithText("Allow").performClick()
        assertEquals(listOf("askLocation"), log)
        rule.onNodeWithText("open-meteo.com", substring = true).assertExists()
    }

    @Test fun locationAllowedNeedsNoButton() {
        open(RobotSettings(), Snapshot(facts = facts().copy(locationGranted = true)))
        rule.onNodeWithText("Location (optional)").assert(hasText("Allowed, approximate only (about 1 km)"))
        rule.onNodeWithText("Allow").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun theDialogLooksUpAndClears() {
        val host = open(RobotSettings(weatherHome = montreal))
        rule.onNodeWithText("Home for weather").assert(hasText("Montreal", substring = true)).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Look up").assertIsNotEnabled()
        rule.onNodeWithText("Clear").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(RobotSettings(), host.last)
        rule.onNodeWithText("Home for weather").assert(hasText("Not set"))
    }

    @Test fun theSetupRowsAskForWhatIsMissing() {
        rule.show { PreferenceGroup("Weather and phone status") { Column { ToolsSetupRows(facts().copy(mediaPartial = true, mediaGranted = true), log.actions) } } }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Open").performClick()
        rule.onNodeWithText("Photos and videos (optional)").assert(hasText("Only the ones you picked"))
        assertEquals(listOf("openUsageAccess"), log)
    }
}
