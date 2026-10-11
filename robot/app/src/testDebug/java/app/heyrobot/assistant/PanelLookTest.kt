package app.heyrobot.assistant

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.LookSettings
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.LocalLook
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The panel in both looks (Settings › Display › Classic look): one panel, five leaf slots. The standard look has no
 * orb and no bubbles, the Classic look both; both have every control with a label TalkBack reads, 48 dp targets that
 * don't overlap, and the card's buttons; and the screenshots of each state.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PanelLookTest {
    @get:Rule val rule = createComposeRule()

    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun standardHasNoOrbAndNoBubbles() {
        rule.showPanel(PanelFixtures.answerWithCard)
        rule.onAllNodesWithTag("classic-orb").assertCountEquals(0)
        rule.onAllNodesWithTag("classic-bubble").assertCountEquals(0)
        noProblems()
        // A card waits: the main button answers it by voice, and a Cancel button sits beside it.
        for (label in listOf("Answer by voice", "Cancel", "Close", "New conversation", "Speak replies")) rule.onNode(hasContentDescription(label)).assertExists()
        rule.onNodeWithText("Do it").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithContentDescription("Speak replies").assertIsOn()
        // The handle closes the panel for TalkBack and Switch Access.
        assertEquals("Close", rule.onNodeWithContentDescription("Drag handle").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    @Test fun classicHasTheOrbAndTheBubbles() {
        rule.showPanel(PanelFixtures.answerWithCard, classic = true)
        rule.onAllNodesWithTag("classic-orb").assertCountEquals(1)
        assertTrue(rule.onAllNodes(hasTestTag("classic-bubble")).fetchSemanticsNodes().size >= 2)
        noProblems()
        // 0.6.0's words: the round mic is "Talk to Robot" (its TalkBack action "Talk"), the speaker a plain button.
        for (label in listOf("Talk to Robot", "Close", "New conversation", "Speaking replies: on")) rule.onNode(hasContentDescription(label)).assertExists()
        rule.onNodeWithText("Do it").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
        rule.onNodeWithText("On this phone, private").assertDoesNotExist()
    }

    @Test fun idleHasTalkInBothLooks() {
        var classic by mutableStateOf(false)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false, classic = classic)) { AssistantPanel(PanelFixtures.idle, RecordingActions()) {} } }
        rule.onNodeWithContentDescription("Talk").assertExists()
        classic = true
        rule.waitForIdle()
        // 0.6.0's round mic: "Talk to Robot", whose TalkBack action is "Talk".
        assertEquals("Talk", rule.onNodeWithContentDescription("Talk to Robot").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
    }

    /**
     * What conversation mode and the helpers put in the panel: a line ("Transcribing. …"), the card that asks to include
     * the conversation so far (its own button words), a helper's answer under its name and the offer to ask one. The
     * Classic look has no parts of its own for them beyond 0.6.0's, and both looks show them and answer them alike.
     */
    @Test fun conversationModeAndHelperTurnsInBothLooks() {
        val a = RecordingActions()
        var classic by mutableStateOf(false)
        val include = UiAction("a9", app.heyrobot.core.ToolName.ASK_CLAUDE, "Include the conversation so far?", app.heyrobot.core.ActionStatus.PENDING,
            "Start fresh: Claude sees only what you ask from now on.", confirm = "Include", cancel = "Start fresh")
        val st = PanelState("c1", phase = Phase.CONFIRMING, turns = listOf(
            UiTurn("u1", app.heyrobot.core.TurnRole.USER, "Transcribe this conversation"),
            UiTurn("r1", app.heyrobot.core.TurnRole.ASSISTANT, "Transcribing. Tell the people you're with."),
            UiTurn("u2", app.heyrobot.core.TurnRole.USER, "Go get Claude"),
            UiTurn("r2", app.heyrobot.core.TurnRole.ASSISTANT, "", actions = listOf(include)),
        ))
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false, classic = classic)) { AssistantPanel(st, a) {} } }
        for (c in listOf(false, true)) {
            classic = c
            rule.waitForIdle()
            a.calls.clear()
            rule.onNodeWithText("Transcribing. Tell the people you're with.").assertExists()
            rule.onNodeWithText("Include the conversation so far?").assertExists()
            rule.onNodeWithText("Include").performClick()
            rule.onNodeWithText("Start fresh").performClick()
            assertEquals("classic=$c", listOf("confirm a9 true", "confirm a9 false"), a.calls)
        }
    }

    @Test fun helperTurnsAndOffersInBothLooks() {
        val a = RecordingActions()
        var classic by mutableStateOf(false)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false, classic = classic)) { AssistantPanel(PanelFixtures.helperOffer, a) {} } }
        for ((c, ask, no) in listOf(Triple(false, "Also ask Jarvis?", "Not now"), Triple(true, "Want Jarvis's take as well?", "No thanks"))) {
            classic = c
            rule.waitForIdle()
            a.calls.clear()
            rule.onNodeWithText("Claude").assertExists()
            rule.onNodeWithText(ask).assertExists()
            rule.onNodeWithText(no).performClick()
            rule.onNodeWithText("Send").performClick()
            assertEquals("classic=$c", listOf("dismissOffer r4", "acceptOffer r4"), a.calls)
        }
    }

    @Test fun theSameTapsAskForTheSameActionsInBothLooks() {
        val a = RecordingActions()
        var closed = 0
        var classic by mutableStateOf(false)
        rule.setContent {
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false, classic = classic)) {
                AssistantPanel(PanelFixtures.answerWithCard, a) { closed++ }
            }
        }
        for (c in listOf(false, true)) {
            classic = c
            rule.waitForIdle()
            a.calls.clear()
            rule.onNodeWithText("Do it").performClick()
            rule.onNodeWithText("Cancel").performClick()
            rule.onNodeWithContentDescription("New conversation").performClick()
            rule.onNodeWithContentDescription("Close").performClick()
            assertEquals("classic=$c", listOf("confirm a1 true", "confirm a1 false", "newConversation"), a.calls)
        }
        assertEquals(2, closed)
    }

    @Test fun theLookSwitchRedrawsThePanel() {
        var look by mutableStateOf(LookSettings(wallpaperColors = false))
        rule.setContent {
            RobotTheme(dark = false, look = look) {
                Column {
                    Text("look: " + LocalLook.current.name)
                    AssistantPanel(PanelFixtures.idle, RecordingActions()) {}
                }
            }
        }
        rule.onAllNodesWithTag("classic-orb").assertCountEquals(0)
        rule.onNodeWithText("On this phone").assertExists()
        look = LookSettings(wallpaperColors = false, classic = true)
        rule.waitForIdle()
        rule.onAllNodesWithTag("classic-orb").assertCountEquals(1)
        rule.onNodeWithText("On this phone, private").assertExists()
        rule.onNode(hasText("Ask me anything.")).assertExists()
    }

    // ------------------------------------------------------------------ screenshots

    @Test fun idle() { rule.showPanel(PanelFixtures.idle); noProblems(); rule.shot("panel_idle") }
    @Test fun listening() { rule.showPanel(PanelFixtures.listening); noProblems(); rule.shot("panel_listening") }
    @Test fun thinking() { rule.showPanel(PanelFixtures.thinking); noProblems(); rule.shot("panel_thinking") }
    @Test fun answerWithCard() { rule.showPanel(PanelFixtures.choices); noProblems(); rule.shot("panel_answer_card") }
    @Test fun doneCard() { rule.showPanel(PanelFixtures.doneCard); noProblems(); rule.shot("panel_done_card") }
    @Test fun helperOffer() { rule.showPanel(PanelFixtures.helperOffer); noProblems(); rule.shot("panel_helper_offer") }
    @Test fun dark() { rule.showPanel(PanelFixtures.choices, dark = true); rule.shot("panel_dark") }

    @Test @Config(fontScale = 2f)
    fun atTwiceTheTextSize() { rule.showPanel(PanelFixtures.answerWithCard); noProblems(); rule.shot("panel_font200") }

    @Test fun classicIdle() { rule.showPanel(PanelFixtures.idle, classic = true); rule.shot("panel_classic_idle") }
    @Test fun classicListening() { rule.showPanel(PanelFixtures.listening, classic = true); rule.shot("panel_classic_listening") }
    @Test fun classicAnswerWithCard() { rule.showPanel(PanelFixtures.choices, classic = true); rule.shot("panel_classic_answer_card") }
    @Test fun classicHelperOffer() { rule.showPanel(PanelFixtures.helperOffer, classic = true); rule.shot("panel_classic_helper_offer") }
}
