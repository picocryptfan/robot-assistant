package app.heyrobot.ui

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.LockScreen
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
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

/** Settings › Safety & privacy as Android-style rows: each switch and choice writes its own setting and nothing else. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class SafetyScreenTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    private fun open(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> SafetyScreen(s, u, log.actions, SnackbarHostState(), onBack = { log += "back" }) }
        return host
    }

    @Test fun everyRowIsLabelledAndLargeEnough() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        for (t in listOf("Maps directions", "Direct answers", "Remove saved screenshots and photos", "Robot helps with whatever you ask")) {
            rule.scrollToText(t, substring = t.startsWith("Robot"))
            assertEquals(emptyList<String>(), rule.touchTargetProblems())
        }
    }

    @Test fun eachSwitchWritesItsOwnSetting() {
        val host = open()
        val d = RobotSettings()
        val cases = listOf<Pair<String, (RobotSettings) -> RobotSettings>>(
            "Card when a helper asks Robot to do something" to { it.copy(helperActionCards = !d.helperActionCards) },
            "Card when who-to isn't certain" to { it.copy(whoCards = !d.whoCards) },
            "Card for names learned from notifications" to { it.copy(learnedCards = !d.learnedCards) },
            "Answer cards by voice" to { it.copy(voiceCards = !d.voiceCards) },
            "Card before deleting files" to { it.copy(deleteCards = !d.deleteCards) },
            "Send personal content to helpers automatically" to { it.copy(personalToHelpers = !d.personalToHelpers) },
            "Voice yes can send personal content" to { it.copy(voiceYesPersonal = !d.voiceYesPersonal) },
            "Direct answers" to { it.copy(directAnswers = !d.directAnswers) },
            "Keep screenshots and photos in history" to { it.copy(keepMedia = !d.keepMedia) },
        )
        var expected = d
        for ((title, change) in cases) {
            rule.scrollToText(title)
            val row = rule.onNodeWithText(title)
            row.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            row.performClick()
            expected = change(expected)
            assertEquals(title, expected, host.last)
        }
        assertEquals(cases.size, host.saved.size)
    }

    @Test fun whenRobotAsksFirstIsARadioDialogThatClosesOnAPick() {
        val host = open()
        val row = rule.onNodeWithText("When Robot asks first")
        row.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, SafetyCards.UNASKED.title))
        row.performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText(SafetyCards.UNSURE_ONLY.title).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(RobotSettings(safetyCards = SafetyCards.UNSURE_ONLY), host.last)
    }

    @Test fun theLockScreenSwitchesAndTheWayBack() {
        val host = open()
        rule.scrollToText(ToolName.FLASHLIGHT.title)
        rule.onNodeWithText(ToolName.SET_TIMER.title).assertIsOn()
        rule.onNodeWithText(ToolName.FLASHLIGHT.title).assertIsOff().performClick()
        assertEquals(LockScreen.DEFAULT + ToolName.FLASHLIGHT, host.last.lockedTools)
        rule.scrollToText("Back to timers and alarms")
        rule.onNodeWithText("Back to timers and alarms").performClick()
        assertEquals(LockScreen.DEFAULT, host.last.lockedTools)
        rule.onNodeWithText("Back to timers and alarms").assertDoesNotExist()
    }

    @Test fun keepingPicturesNeedsHistoryOn() {
        open(RobotSettings(saveHistory = false))
        rule.scrollToText("Keep screenshots and photos in history")
        rule.onNodeWithText("Keep screenshots and photos in history").assertIsNotEnabled().assert(hasText("Saving conversations is off"))
    }

    @Test fun removingPicturesAsksFirst() {
        open()
        rule.scrollToText("Remove saved screenshots and photos")
        rule.onNodeWithText("Remove saved screenshots and photos").performClick()
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(log.none { it == "removeSavedMedia" })
        rule.onNodeWithText("Remove saved screenshots and photos").performClick()
        rule.onNodeWithText("Remove").performClick()
        assertEquals(listOf("removeSavedMedia"), log)
    }

    @Test fun theFooterKeepsEveryExplanation() {
        open()
        for (t in listOf("“annule”", "Android's confirmation for the largest one", "Bringing a helper into the conversation and “ask a helper about my screen” always ask first",
            "a screen conversation is still saved only after Keep")) {
            rule.scrollToText(t, substring = true)
        }
    }

    @Test fun light() {
        open()
        rule.shot("safety_light")
    }
}
