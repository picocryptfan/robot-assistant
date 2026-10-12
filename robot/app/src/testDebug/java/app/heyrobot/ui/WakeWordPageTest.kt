package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.BargeRun
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BargeTestResult
import app.heyrobot.core.BenchState
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.show
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

/** Settings › Wake word, the barge-in test's page and the voice log's Copy, built from the settings blocks. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class WakeWordPageTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    private val two = RobotSettings(wakePhrases = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, true), WakePhrase(2, "computer", WakeSensitivity.LOW, true)))

    private fun open(start: RobotSettings = two): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> WakePhrasesScreen(s, u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    @Test fun theRowsAreLabelledAndEachPhraseHasTwoTargets() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Use wake words").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        rule.onNodeWithText("“hey robot”").assert(hasText("Sensitivity Medium"))
        rule.onNodeWithContentDescription("Listen for “hey robot”").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).assertIsOn()
        rule.onNodeWithContentDescription("Listen for “computer”").assertIsOn()
        rule.scrollToText("Test wake words")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theSwitchesWriteTheirSettings() {
        val host = open()
        rule.onNodeWithText("Only while charging").performClick()
        assertEquals(two.copy(wakeOnlyWhileCharging = true), host.last)
        rule.onNodeWithContentDescription("Listen for “computer”").performClick()
        assertEquals(listOf(true, false), host.last.wakePhrases.map { it.enabled })
        rule.onNodeWithText("Use wake words").performClick()
        assertEquals(false, host.last.wakeWord)
        assertEquals(3, host.saved.size)
    }

    @Test fun theMainSwitchDisablesTheRest() {
        open(two.copy(wakeWord = false))
        rule.onNodeWithText("Use wake words").assertIsOff()
        rule.onNodeWithText("Only while charging").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Listen for “hey robot”").assertIsNotEnabled()
        rule.onNodeWithText("“hey robot”").assertIsNotEnabled()
        rule.onNodeWithText("Add a wake phrase").assertIsNotEnabled()
        // Testing stays possible: it says why nothing is heard.
        rule.scrollToText("Test wake words")
        rule.onNodeWithText("Test wake words").assertIsEnabled().performClick()
        assertEquals(listOf("openPage ${Page.WakeTest}"), log)
    }

    @Test fun aPhraseOpensItsSensitivityAndRemove() {
        val host = open()
        rule.onNodeWithText("“computer”").assert(hasText("tap for a note", substring = true)).performClick()
        rule.onNode(isDialog()).assertExists()
        rule.onNodeWithText("Short words like", substring = true).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("High").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(WakeSensitivity.HIGH, host.last.wakePhrases[1].sensitivity)
        rule.onNodeWithText("“hey robot”").performClick()
        rule.onNodeWithText("Remove").performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(listOf("computer"), host.last.wakePhrases.map { it.text })
    }

    @Test fun noPhraseOnSaysOnlyTheSwipeOpensRobot() {
        open(RobotSettings(wakePhrases = listOf(WakePhrase(1, "hey robot", WakeSensitivity.MEDIUM, false))))
        rule.onNodeWithText("No wake phrase is on").assert(hasText("Only the corner swipe opens Robot", substring = true))
    }

    // Robolectric settles with a focused text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun addingAPhraseKeepsAddOffWhileItIsWrong() {
        val host = open()
        rule.scrollToText("Add a wake phrase")
        rule.onNodeWithText("Add a wake phrase").performClick()
        val field = rule.onNode(hasText("Phrase") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        rule.onNodeWithText("Add").assertIsNotEnabled()
        field.performTextReplacement("stop")
        rule.onNodeWithText("Add").assertIsNotEnabled()
        field.performTextReplacement("hey robot")
        rule.onNodeWithText("Add").assertIsNotEnabled()
        field.performTextReplacement("hello there")
        rule.onNodeWithText("Add").assertIsEnabled().performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(WakePhrase(3, "hello there", WakeSensitivity.MEDIUM, true), host.last.wakePhrases.last())
    }

    @Test fun theBargeInTestRunsFromItsRows() {
        val s = RobotSettings(bargeIn = BargeIn.ECHO_CANCEL, playback = Playback.ASSISTANT)
        rule.show { BargeTestScreen(s, Snapshot(), log.actions, SnackbarHostState(), onBack = {}) }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        for (t in listOf("Run the test", "Count run", "Silent run", "Tricky words")) rule.onNodeWithText(t).performClick()
        assertEquals(listOf(BargeRun.FIRST, BargeRun.COUNT, BargeRun.SILENT, BargeRun.TRICKY).map { "bargeTest $it" }, log)
        // The footer explains each run.
        rule.scrollToText("(step, steep, stamp, bus stop)", substring = true)
    }

    @Test fun aRunningTestDisablesTheRuns() {
        val r = BargeTestResult(state = BenchState.RUNNING, silent = false, started = System.currentTimeMillis(), mode = BargeIn.ECHO_CANCEL,
            playback = Playback.ASSISTANT)
        rule.show { BargeTestScreen(RobotSettings(), Snapshot(bargeTest = r), log.actions, SnackbarHostState(), onBack = {}) }
        rule.onNodeWithText("Run the test").assertIsNotEnabled().assert(hasText("Testing…"))
        rule.onNodeWithText("Tricky words").assertIsNotEnabled()
    }

    @Test fun theVoiceLogCopiesFromALabelledIcon() {
        rule.show { VoiceLogScreen(RobotSettings(), SnackbarHostState(), onBack = {}) }
        rule.onNodeWithContentDescription("Copy log").assertExists()
        assertTrue(rule.touchTargetProblems().isEmpty())
    }

    /** 0.7: "Send as bug report" opens Send feedback with the voice log ticked. */
    @Test fun theVoiceLogSendsABugReport() {
        var reports = 0
        rule.show { VoiceLogScreen(RobotSettings(), SnackbarHostState(), onBack = {}, onReport = { reports++ }) }
        rule.onNodeWithText("Send as bug report").performClick()
        assertEquals(1, reports)
        assertTrue(rule.touchTargetProblems().isEmpty())
    }
}
