package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
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
    /** Claude as installs before 1.0.0 had it (a new install has no helper). */
    private val claude = listOf(HelperConfig.claude())

    @Test fun eachMissingThingHasItsButton() {
        val f = facts(recognizerIsRobot = false, micGranted = false).copy(claudeApp = ClaudeAppFacts(installed = true, appLink = true, webLink = false, send = true))
        rule.show { SetupScreen(f, log.actions, SnackbarHostState(), onBack = {}, helpers = claude) }
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

    @Test fun theAppRowOnlyForAHelperThatOpensIt() {
        // Claude with its key goes through the API: nobody needs its app, so there is no row (and no to-do) for it.
        val keyed = facts().copy(keys = mapOf("claude" to "sk-ant-…AB12"))
        rule.show { SetupScreen(keyed, log.actions, SnackbarHostState(), onBack = {}, helpers = claude) }
        rule.scrollToText("Claude API key")
        rule.onNode(hasText("sk-ant-…AB12")).assertExists()
        rule.scrollToText("ADB fixes")
        rule.onNode(hasText("Claude app")).assertDoesNotExist()
    }

    @Test fun noHelpersNoAppRow() {
        rule.show { SetupScreen(facts(), log.actions, SnackbarHostState(), onBack = {}, helpers = emptyList()) }
        rule.scrollToText("ADB fixes")
        rule.onNode(hasText("Claude app")).assertDoesNotExist()
    }

    @Test fun eachHelperThatOpensAnAppHasItsRow() {
        // 1.0.0: ChatGPT and Grok without keys open their apps; Groq has none. Each app's own row, by its own package.
        val helpers = listOf(HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck"), HelperConfig.of(Presets.XAI, "hgrk", "bf_emma"),
            HelperConfig.of(Presets.GROQ, "hgrq", "af_bella"))
        val f = facts().copy(helperApps = mapOf("com.openai.chatgpt" to ClaudeAppFacts(installed = true, appLink = false, webLink = false, send = true),
            "ai.x.grok" to ClaudeAppFacts(installed = false, appLink = false, webLink = false, send = false)))
        rule.show { SetupScreen(f, log.actions, SnackbarHostState(), onBack = {}, helpers = helpers) }
        rule.scrollToText("ChatGPT API key")
        rule.onNode(hasText("None: questions open the ChatGPT app instead (Settings › Helpers › ChatGPT)")).assertExists()
        rule.scrollToText("ChatGPT app")
        rule.onNode(hasText("Installed · opens with: share")).assertExists()
        rule.scrollToText("Grok app")
        rule.onNode(hasText("Not installed")).assertExists()
        rule.scrollToText("ADB fixes")
        rule.onNode(hasText("Groq app")).assertDoesNotExist()
        rule.onNode(hasText("Claude app")).assertDoesNotExist()
    }

    @Test fun withoutAKeyTheAppRowSaysItIsMissing() {
        rule.show { SetupScreen(facts(), log.actions, SnackbarHostState(), onBack = {}, helpers = claude) }
        rule.scrollToText("Claude app")
        rule.onNode(hasText("Claude app") and hasText("Not installed")).assertExists()
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
