package app.heyrobot.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import app.heyrobot.core.Attachment
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.theme.RobotColors
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel's floating action button: in each state its symbol, its label, its colour and what a tap asks for; a hold
 * is push-to-talk (micPressed on the press, micReleased on the release); and it stays one button, so a hold that starts
 * listening is still the same gesture when the button turns into "Stop listening".
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MicFabTest {
    @get:Rule val rule = createComposeRule()

    private val idle = PanelState("c1")

    private data class Row(val st: PanelState, val input: String, val mode: FabMode, val icon: Any, val label: String, val click: String)

    /** The design's table (docs/plans/ui-stock-design.md 3.1). */
    private val table = listOf(
        Row(idle, "", FabMode.TALK, RIcons.Mic, "Talk", "micPressed"),
        Row(idle, "hi", FabMode.SEND, RIcons.Send, "Send", "send"),
        Row(idle.copy(draft = Draft(Attachment.PHOTO, null)), "", FabMode.SEND, RIcons.Send, "Send", "send"),
        Row(idle.copy(phase = Phase.LISTENING), "", FabMode.STOP_LISTENING, RIcons.StopCircle, "Stop listening", "micReleased"),
        Row(idle.copy(phase = Phase.TRANSCRIBING), "", FabMode.STOP_LISTENING, RIcons.StopCircle, "Stop listening", "micReleased"),
        Row(idle.copy(phase = Phase.THINKING), "", FabMode.STOP_ANSWER, RIcons.Stop, "Stop answer", "stopGenerating"),
        Row(idle.copy(phase = Phase.ANSWERING), "yes", FabMode.STOP_ANSWER, RIcons.Stop, "Stop answer", "stopGenerating"),
        Row(idle.copy(phase = Phase.SPEAKING), "", FabMode.STOP_READING, RIcons.Stop, "Stop reading aloud", "stopSpeaking"),
        Row(idle.copy(phase = Phase.SPEAKING), "yes", FabMode.SEND, RIcons.Send, "Send", "send"),
        Row(idle.copy(phase = Phase.CONFIRMING), "", FabMode.ANSWER_BY_VOICE, RIcons.Mic, "Answer by voice", "micPressed"),
        Row(idle.copy(phase = Phase.CONFIRMING), "yes", FabMode.SEND, RIcons.Send, "Send", "send"),
        Row(idle.copy(phase = Phase.CONFIRMING, cardHearing = Phase.LISTENING), "", FabMode.STOP_LISTENING, RIcons.StopCircle, "Stop listening", "micReleased"),
    )

    @Test fun theStateTable() {
        for (r in table) {
            val m = fabMode(r.st, r.input)
            assertEquals(r.toString(), r.mode, m)
            assertEquals(r.toString(), r.icon, m.icon)
            assertEquals(r.toString(), r.label, m.label)
        }
        val c = RobotColors.Light
        assertEquals(c.primaryContainer, fabContainer(FabMode.TALK, c))
        assertEquals(c.primary, fabContainer(FabMode.STOP_LISTENING, c))
        assertEquals(c.secondaryContainer, fabContainer(FabMode.STOP_READING, c))
        for (m in listOf(FabMode.SEND, FabMode.STOP_ANSWER, FabMode.ANSWER_BY_VOICE)) assertEquals(c.primaryContainer, fabContainer(m, c))
    }

    @Test fun aTapAsksForTheRightAction() {
        val a = RecordingActions()
        var mode by mutableStateOf(FabMode.TALK)
        rule.show { MicFab(mode, null, a) { a.calls += "send" } }
        for (r in table) {
            mode = r.mode
            rule.waitForIdle()
            a.calls.clear()
            val fab = rule.onNodeWithContentDescription(r.label)
            fab.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
            fab.performClick()
            assertEquals(r.toString(), listOf(r.click), a.calls)
            // TalkBack's double tap does the same, and says what it does.
            a.calls.clear()
            assertEquals(r.label, fab.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            fab.performSemanticsAction(SemanticsActions.OnClick)
            assertEquals(r.toString(), listOf(r.click), a.calls)
        }
        // Listening says so in its state too.
        mode = FabMode.STOP_LISTENING
        rule.onNodeWithContentDescription("Stop listening").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening"))
    }

    @Test fun aHoldIsPushToTalk() {
        val a = RecordingActions()
        rule.show { MicFab(FabMode.TALK, null, a) {} }
        rule.onNodeWithContentDescription("Talk").performTouchInput {
            down(center)
            advanceEventTime(800)
            up()
        }
        assertEquals(listOf("micPressed", "micReleased"), a.calls)
        // A tap is not a hold: listening ends on silence.
        a.calls.clear()
        rule.onNodeWithContentDescription("Talk").performClick()
        assertEquals(listOf("micPressed"), a.calls)
        // The same with the long-press gesture TalkBack users' fingers make.
        a.calls.clear()
        rule.onNodeWithContentDescription("Talk").performTouchInput { longClick() }
        assertEquals(listOf("micPressed", "micReleased"), a.calls)
    }

    @Test fun theHoldSurvivesTheButtonTurningIntoStopListening() {
        val a = RecordingActions()
        // As in the panel: pressing the mic starts listening, which turns the button into "Stop listening".
        val actions = object : PanelActions by a {
            override fun micPressed() { a.micPressed(); phase = Phase.LISTENING }
        }
        phase = Phase.IDLE
        rule.show { MicFab(fabMode(idle.copy(phase = phase), ""), null, actions) {} }
        rule.onNodeWithContentDescription("Talk").performTouchInput {
            down(center)
            advanceEventTime(100)
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Stop listening").performTouchInput {
            advanceEventTime(700)
            up()
        }
        // The release ends the listening the press started, once; the release is not a "Stop listening" tap.
        assertEquals(listOf("micPressed", "micReleased"), a.calls)
    }

    @Test fun theHintIsSaidWithTheLabel() {
        rule.show { MicFab(FabMode.TALK, "Download voice models", RecordingActions()) {} }
        rule.onNodeWithContentDescription("Talk. Download voice models").assertExists()
    }

    @Test fun sendSendsWhatWasTyped() {
        val a = RecordingActions()
        rule.showPanel(idle, a)
        rule.onNodeWithText("Type a question").performTextInput("What's 2 + 2?")
        rule.onNodeWithContentDescription("Send").performClick()
        assertEquals(listOf("send What's 2 + 2?"), a.calls)
    }

    private companion object {
        var phase by mutableStateOf(Phase.IDLE)
    }
}
