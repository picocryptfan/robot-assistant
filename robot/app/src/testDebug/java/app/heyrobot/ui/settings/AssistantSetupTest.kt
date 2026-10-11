package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.heyrobot.core.RobotSettings
import app.heyrobot.sys.ClaudeAppFacts
import app.heyrobot.ui.Page
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Assistant setup: a status row per check, the button that fixes each, and the Claude app's hand-offs in words. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class AssistantSetupTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    @Test fun eachMissingThingHasItsButton() {
        val f = facts(recognizerIsRobot = false, micGranted = false).copy(claudeApp = ClaudeAppFacts(installed = true, appLink = true, webLink = false, send = true))
        rule.show { SetupScreen(f, log.actions, SnackbarHostState(), onBack = {}, helpers = RobotSettings().helpers) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("Survives restarts")).assert(hasText("Not yet", substring = true))
        rule.onNodeWithContentDescription("Fix it with ADB fixes").performClick()
        rule.scrollToText("Microphone")
        rule.onNodeWithContentDescription("Allow the microphone").performClick()
        rule.scrollToText("Contacts")
        rule.onNodeWithContentDescription("Allow contacts").performClick()
        rule.onNodeWithContentDescription("Allow the calendar").performClick()
        rule.scrollToText("Installed · opens with: claude:// link, share")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("ADB fixes")
        rule.onRow("ADB fixes").performClick()
        assertEquals(listOf("openPage ${Page.AdbFixes}", "askPermissions", "askContacts", "askCalendar", "openPage ${Page.AdbFixes}"), log)
        rule.scrollToText("Robot checks 3 minutes after every restart", substring = true)
    }

    @Test fun fromACardsAllowThePermissionsComeFirst() {
        rule.show { SetupScreen(facts(), log.actions, SnackbarHostState(), onBack = {}, peopleFirst = true) }
        // At the top, before anything is scrolled.
        rule.onNode(hasText("People, notes and calendar")).assertExists()
        rule.onNode(hasText("Contacts")).assertExists()
    }

    @Test fun beforeTheFactsArrive() {
        rule.show { SetupScreen(null, log.actions, SnackbarHostState(), onBack = {}) }
        rule.scrollToText("Checking…")
    }
}
