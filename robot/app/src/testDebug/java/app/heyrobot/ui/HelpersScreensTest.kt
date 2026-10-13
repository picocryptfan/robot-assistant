package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import app.heyrobot.sys.HelperWindow
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.facts
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.showSettings
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
 * Settings › Helpers, a helper's page and Add a helper, as Android-style pages: each choice writes its own setting, the
 * key is only ever handled in the helper's window, and the consent texts are all there.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class HelpersScreensTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val snap = Snapshot(facts = facts())
    /** Claude as installs before 1.0.0 had it (a new install has no helper: [aNewInstallOffersToAddOne]). */
    private val legacy = RobotSettings(helpers = listOf(HelperConfig.claude()))

    private fun helpers(start: RobotSettings = legacy): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> HelpersScreen(s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    private fun claude(start: RobotSettings = legacy): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> HelperScreen(HelperConfig.CLAUDE_ID, s, snap, u, log.actions, SnackbarHostState(), onBack = { log += "back" }) }
        return host
    }

    @Test fun theListOpensEachHelperAndAddAHelper() {
        helpers()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Claude").assert(hasText("Anthropic · Claude Opus 5.5", substring = true)).performClick()
        rule.onRow("Add a helper").performClick()
        assertEquals(listOf("openPage ${Page.Helper(HelperConfig.CLAUDE_ID)}", "openPage ${Page.AddHelper}"), log)
    }

    @Test fun theChoicesWriteTheirSettings() {
        val host = helpers()
        rule.onRow("When Robot needs help").performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog("Off").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(legacy.copy(help = Escalation.OFF), host.last)
        rule.scrollToText("Daily budget for all helpers")
        rule.onRow("Daily budget for all helpers")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Today: $0.00 of $1.00 (an estimate)")).performClick()
        rule.inDialog("$5").performClick()
        assertEquals(500, host.last.helperBudgetCents)
        rule.scrollToText("Show cost")
        rule.onRow("Show cost").performClick()
        assertEquals(false, host.last.helperShowCost)
        assertEquals(3, host.saved.size)
    }

    @Test fun theFooterSaysWhatGoesToAHelper() {
        helpers()
        rule.scrollToText("anything more goes only after you tap a card that lists it", substring = true)
        rule.scrollToText("A helper whose prices Robot doesn't know isn't counted", substring = true)
    }

    @Test fun aHelpersMainSwitchTurnsTheRestOff() {
        val host = claude()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow("Use Claude").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        assertEquals(false, host.last.claude!!.enabled)
        rule.onRow("Use Claude").assertIsOff()
        rule.scrollToText("Let Claude search the web")
        rule.onRow("Let Claude search the web").assertIsNotEnabled()
        rule.scrollToText("Remove Claude")
        rule.onRow("Remove Claude").assertIsEnabled()
    }

    @Test fun theKeyIsAddedInItsWindowAndTheChoicesSave() {
        val host = claude()
        rule.onRow("Add key").performClick()
        assertEquals("helperWindow claude ${HelperWindow.MODE_KEY}", log.last())
        rule.scrollToText("Claude's voice")
        rule.onRow("Claude's voice").performClick()
        rule.inDialog(ClaudeVoices.title("bf_emma")).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals("bf_emma", host.last.claude!!.voice)
        rule.scrollToText("Trusted with personal content")
        rule.onRow("Trusted with personal content").performClick()
        assertEquals(true, host.last.claude!!.trust)
        rule.scrollToText("Allow on lock screen")
        rule.onRow("Allow on lock screen").performClick()
        assertEquals(true, host.last.claude!!.locked)
        // The consent text, word for word, in the footer.
        rule.scrollToText("Turn it on only for a helper you'd show your calendar to", substring = true)
    }

    @Test fun removingAHelperAsksFirst() {
        claude()
        rule.scrollToText("Remove Claude")
        rule.onRow("Remove Claude").performClick()
        rule.inDialog("Remove").performClick()
        assertEquals("removeHelper claude", log.last())
    }

    // The address dialog has a text field: Robolectric settles with one only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun addingACompanyOpensItsKeyWindow() {
        val host = SettingsHost()
        rule.showSettings(host) { s, u -> AddHelperScreen(s, u, log.actions, SnackbarHostState(), onAdded = { log += "added $it" }, onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onRow(Presets.OPENAI.title).performClick()
        val added = host.last.helpers.last()
        assertEquals(Presets.OPENAI.id, added.preset)
        assertEquals(listOf("added ${added.id}", "helperWindow ${added.id} ${HelperWindow.MODE_KEY}"), log)
        rule.scrollToText(Presets.HOME.title)
        rule.onRow(Presets.HOME.title).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.inDialog("Your server's address").assertExists()
        assertTrue(rule.touchTargetProblems().isEmpty())
    }

    @Test fun light() {
        helpers()
        rule.shot("helpers_light")
    }

    @Test fun aNewInstallOffersToAddOne() {
        // 1.0.0: no helper until you add one; "Add a helper" lists every company (its app, or its API) and your server.
        helpers(RobotSettings())
        rule.onNode(hasText("No helpers")).assertExists()
        rule.onRow("Add a helper").assert(hasText("A company's model or its app, or a model on your own computer", substring = true)).performClick()
        assertEquals(listOf("openPage ${Page.AddHelper}"), log)
        rule.shot("helpers_none_light")
    }

    // The address dialog has a text field: Robolectric settles with one only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun eachCompanyWithAnAppSaysSo() {
        rule.showSettings(SettingsHost()) { s, u -> AddHelperScreen(s, u, log.actions, SnackbarHostState(), onAdded = {}, onBack = {}) }
        rule.onRow(Presets.OPENAI.title).assert(hasText("or the ChatGPT app, without a key", substring = true))
        rule.onRow(Presets.ANTHROPIC.title).assert(hasText("or the Claude app, without a key", substring = true))
        rule.scrollToText(Presets.GROQ.title)
        rule.onRow(Presets.GROQ.title).assert(hasText("GPT-OSS 120B, GPT-OSS 20B, Qwen 3.8 27B"))
        rule.scrollToText(Presets.XAI.title)
        rule.onRow(Presets.XAI.title).assert(hasText("or the Grok app, without a key", substring = true))
    }

    @Test fun aHelperWithAnAppChoosesItOrItsApi() {
        val gpt = HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")
        val host = SettingsHost(RobotSettings(helpers = listOf(gpt)))
        rule.showSettings(host) { s, u -> HelperScreen("hgpt", s, snap, u, log.actions, SnackbarHostState(), onBack = {}) }
        rule.scrollToText("Use")
        rule.onRow("Use").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "ChatGPT app")).performClick()
        rule.inDialog("OpenAI API").performClick()
        assertEquals(app.heyrobot.core.ClaudeRoute.API, host.last.helper("hgpt")!!.route)
    }
}
