package app.heyrobot.assistant

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.LookSettings
import app.heyrobot.sys.LookStore
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
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
 * The panel, always in the classic look since 1.0 (Robot 0.6.0's orb, chat bubbles and round mic): every control with a
 * label TalkBack reads, 48 dp targets that don't overlap, the speaker a toggle, the status line a polite live region,
 * the card's and the offer's buttons, a look.json from before 1.0 that still loads, and the screenshots of each state,
 * light, dark and at twice the text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PanelLookTest {
    @get:Rule val rule = createComposeRule()

    private fun noProblems() = assertEquals(emptyList<String>(), rule.touchTargetProblems())

    @Test fun theOrbTheBubblesAndEveryControlNamed() {
        rule.showPanel(PanelFixtures.answerWithCard)
        rule.onAllNodesWithTag("classic-orb").assertCountEquals(1)
        assertTrue(rule.onAllNodes(hasTestTag("classic-bubble")).fetchSemanticsNodes().size >= 2)
        noProblems()
        // 0.6.0's words: the round mic is "Talk to Robot" (its TalkBack action "Talk").
        for (label in listOf("Talk to Robot", "Close", "New conversation", "Settings", "Speak replies")) rule.onNode(hasContentDescription(label)).assertExists()
        rule.onNodeWithText("Do it").assertExists()
        rule.onNodeWithText("Cancel").assertExists()
    }

    /** The panel for [st], which the test can change. */
    private fun showChanging(first: PanelState): (PanelState) -> Unit {
        var st by mutableStateOf(first)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(st, RecordingActions()) {} } }
        return { st = it; rule.waitForIdle() }
    }

    @Test fun theSpeakerIsAToggle() {
        val show = showChanging(PanelFixtures.answerWithCard)
        rule.onNodeWithContentDescription("Speak replies").assertIsOn()
        show(PanelFixtures.doneCard)
        rule.onNodeWithContentDescription("Speak replies").assertIsOff()
    }

    @Test fun theStatusLineIsAPoliteLiveRegion() {
        rule.showPanel(PanelFixtures.idle)
        rule.onNodeWithText("On this phone, private").assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
    }

    @Test fun idleHasTalkAndTheListeningMicSaysSo() {
        val show = showChanging(PanelFixtures.idle)
        assertEquals("Talk", rule.onNodeWithContentDescription("Talk to Robot").fetchSemanticsNode().config[SemanticsActions.OnClick].label)
        assertTrue(SemanticsProperties.StateDescription !in rule.onNodeWithContentDescription("Talk to Robot").fetchSemanticsNode().config)
        show(PanelFixtures.listening)
        rule.onNodeWithContentDescription("Talk to Robot").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Listening"))
    }

    /**
     * What conversation mode and the helpers put in the panel: a line ("Transcribing. …"), the card that asks to include
     * the conversation so far (its own button words), and both buttons answer it.
     */
    @Test fun conversationModeTurns() {
        val a = RecordingActions()
        val include = UiAction("a9", app.heyrobot.core.ToolName.ASK_HELPER, "Include the conversation so far?", app.heyrobot.core.ActionStatus.PENDING,
            "Start fresh: Claude sees only what you ask from now on.", confirm = "Include", cancel = "Start fresh")
        val st = PanelState("c1", phase = Phase.CONFIRMING, turns = listOf(
            UiTurn("u1", app.heyrobot.core.TurnRole.USER, "Transcribe this conversation"),
            UiTurn("r1", app.heyrobot.core.TurnRole.ASSISTANT, "Transcribing. Tell the people you're with."),
            UiTurn("u2", app.heyrobot.core.TurnRole.USER, "Go get Claude"),
            UiTurn("r2", app.heyrobot.core.TurnRole.ASSISTANT, "", actions = listOf(include)),
        ))
        rule.showPanel(st, a)
        rule.onNodeWithText("Transcribing. Tell the people you're with.").assertExists()
        rule.onNodeWithText("Include the conversation so far?").assertExists()
        rule.onNodeWithText("Include").performClick()
        rule.onNodeWithText("Start fresh").performClick()
        assertEquals(listOf("confirm a9 true", "confirm a9 false"), a.calls)
    }

    @Test fun helperTurnsAndOffers() {
        val a = RecordingActions()
        rule.showPanel(PanelFixtures.helperOffer, a)
        rule.onNodeWithText("Claude").assertExists()
        rule.onNodeWithText("Want Jarvis's take as well?").assertExists()
        rule.onNodeWithText("No thanks").performClick()
        rule.onNodeWithText("Send").performClick()
        assertEquals(listOf("dismissOffer r4", "acceptOffer r4"), a.calls)
    }

    @Test fun eachTapAsksForItsAction() {
        val a = RecordingActions()
        var closed = 0
        rule.showPanel(PanelFixtures.answerWithCard, a) { closed++ }
        rule.onNodeWithText("Do it").performClick()
        rule.onNodeWithText("Cancel").performClick()
        rule.onNodeWithContentDescription("Speak replies").performClick()
        rule.onNodeWithContentDescription("New conversation").performClick()
        // 0.7.0: a Settings button in the header (the tester's 0.6.0 report).
        rule.onNodeWithContentDescription("Settings").performClick()
        rule.onNodeWithContentDescription("Close").performClick()
        assertEquals(listOf("confirm a1 true", "confirm a1 false", "toggleSpeak", "newConversation", "openSettings"), a.calls)
        assertEquals(1, closed)
    }

    /** 1.0: the photo or the screen waiting for the next question is removed by a button that says which. */
    @Test fun removeSaysWhatItRemoves() {
        val show = showChanging(PanelFixtures.idle.copy(draft = Draft(app.heyrobot.core.Attachment.PHOTO, null)))
        rule.onNodeWithContentDescription("Remove photo").assertExists()
        show(PanelFixtures.idle.copy(draft = Draft(app.heyrobot.core.Attachment.SCREEN, null)))
        rule.onNodeWithContentDescription("Remove screen").assertExists()
        noProblems()
    }

    /** look.json from before 1.0 with the Classic look off (the standard panel then): it loads, and the panel is the classic one. */
    @Test fun anOldLookFileStillOpensTheClassicPanel() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        LookStore.file(ctx).apply { parentFile?.mkdirs() }.writeText("""{"v": 1, "wallpaperColors": false, "classic": false}""")
        try {
            assertEquals(LookSettings(wallpaperColors = false), LookStore.reload(ctx))
            // The theme reads LookStore, as in the voice process.
            rule.setContent { RobotTheme(dark = false) { AssistantPanel(PanelFixtures.idle, RecordingActions()) {} } }
            rule.onAllNodesWithTag("classic-orb").assertCountEquals(1)
            rule.onNodeWithText("Ask me anything.").assertExists()
        } finally {
            LookStore.file(ctx).delete()
            LookStore.reload(ctx)
        }
    }

    // ------------------------------------------------------------------ screenshots

    @Test fun classicIdle() { rule.showPanel(PanelFixtures.idle); noProblems(); rule.shot("panel_classic_idle") }
    @Test fun classicListening() { rule.showPanel(PanelFixtures.listening); noProblems(); rule.shot("panel_classic_listening") }
    @Test fun classicAnswerWithCard() { rule.showPanel(PanelFixtures.choices); noProblems(); rule.shot("panel_classic_answer_card") }
    @Test fun classicHelperOffer() { rule.showPanel(PanelFixtures.helperOffer); noProblems(); rule.shot("panel_classic_helper_offer") }
    @Test fun classicDark() { rule.showPanel(PanelFixtures.choices, dark = true); rule.shot("panel_classic_dark") }

    @Test @Config(fontScale = 2f)
    fun classicAtTwiceTheTextSize() { rule.showPanel(PanelFixtures.answerWithCard); noProblems(); rule.shot("panel_classic_font200") }
}
