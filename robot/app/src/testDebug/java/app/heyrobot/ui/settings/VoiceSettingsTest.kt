// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import app.heyrobot.core.BargeIn
import app.heyrobot.core.InterruptSensitivity
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.StopKey
import app.heyrobot.core.VoiceChoice
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SettingsHost
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.showSettings
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings › Voice: every choice is a radio dialog that closes on a pick and writes only its own setting. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class VoiceSettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()

    private fun open(start: RobotSettings = RobotSettings()): SettingsHost {
        val host = SettingsHost(start)
        rule.showSettings(host) { s, u -> VoiceSettingsScreen(s, Snapshot(), u, log.actions, SnackbarHostState(), onBack = {}) }
        return host
    }

    /** Opens [row]'s dialog, picks [choice], and checks the dialog closed. */
    private fun pick(row: String, choice: String) {
        rule.scrollToText(row)
        rule.onRow(row).performClick()
        rule.onNode(isDialog()).assertExists()
        assertEquals(row, emptyList<String>(), rule.touchTargetProblems())
        rule.inDialog(choice).performClick()
        rule.onNode(isDialog()).assertDoesNotExist()
    }

    @Test fun eachChoiceWritesItsOwnSetting() {
        val host = open()
        var want = RobotSettings()
        pick("Voice", VoiceChoice.KITTEN.title); want = want.copy(voice = VoiceChoice.KITTEN.id); assertEquals(want, host.last)
        pick("Speak replies", "Always"); want = want.copy(speakReplies = SpeakReplies.ALWAYS); assertEquals(want, host.last)
        pick("Hear you while Robot talks", BargeIn.ALWAYS.title); want = want.copy(bargeIn = BargeIn.ALWAYS); assertEquals(want, host.last)
        pick("Voice plays as", Playback.CALL.title); want = want.copy(playback = Playback.CALL); assertEquals(want, host.last)
        pick("Volume key stops Robot", StopKey.EITHER.title); want = want.copy(stopKey = StopKey.EITHER); assertEquals(want, host.last)
        rule.scrollToText("Show words while you speak")
        rule.onNodeWithText("Show words while you speak").performClick(); want = want.copy(livePartials = false); assertEquals(want, host.last)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Interrupt sensitivity"))
        rule.onNodeWithContentDescription("Interrupt sensitivity").performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        want = want.copy(interrupt = InterruptSensitivity.HIGH); assertEquals(want, host.last)
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription("Speed"))
        rule.onNodeWithContentDescription("Speed").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1.0×"))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.5f) }
        want = want.copy(speechRate = 1.5f); assertEquals(want, host.last)
        assertEquals(8, host.saved.size)
    }

    @Test fun theRowsAreLabelledAndTheToolsOpen() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithText("Download voice models").assertExists()
        rule.onNodeWithText("Open").performClick()
        for (t in listOf("Test the voice", "Test voice speed", "Barge-in test", "Transcription check", "Voice log")) {
            rule.scrollToText(t)
            assertEquals(t, emptyList<String>(), rule.touchTargetProblems())
            rule.onRow(t).performClick()
        }
        assertEquals(listOf("openTab ${Tab.MODEL}", "speakTest", "voiceBench", "openPage ${Page.Settings(SettingsRoutes.BARGE_IN)}",
            "openTranscriptionCheck", "openPage ${Page.VoiceLog}"), log)
        rule.scrollToText("back to the best mode for questions", substring = true)
    }

    @Test fun light() {
        open()
        rule.shot("voice_light")
    }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() {
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("voice_font200")
    }
}
