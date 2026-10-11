package app.heyrobot.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.heyrobot.core.LockScreen
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The standard look's status line: for each phase its words and its symbol (only where it means something), a polite
 * live region (TalkBack says each new state once), and the progress bar only while speech is written down, Robot works
 * or the model loads.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PanelStatusTest {
    @get:Rule val rule = createComposeRule()

    private val base = PanelState("c1")
    private val loading = BrainKeeper.State(readiness = BrainKeeper.Readiness.LOADING)

    @Test fun eachPhaseSaysWhatIsHappening() {
        val expected = mapOf(
            Phase.IDLE to PanelStatus("On this phone"),
            Phase.LISTENING to PanelStatus("Listening…", RIcons.Mic),
            Phase.TRANSCRIBING to PanelStatus("Transcribing…"),
            Phase.THINKING to PanelStatus("Working…"),
            Phase.ANSWERING to PanelStatus("Answering…"),
            Phase.CONFIRMING to PanelStatus("Confirm or cancel below"),
            Phase.SPEAKING to PanelStatus("Reading aloud · tap to stop", RIcons.VolumeUp),
        )
        assertEquals(Phase.entries.toSet(), expected.keys)
        for ((p, s) in expected) assertEquals(p.name, s, panelStatus(base.copy(phase = p)))
    }

    @Test fun theOtherStates() {
        assertEquals(PanelStatus("Locked: " + LockScreen.allowed(setOf(ToolName.SET_TIMER)), RIcons.Lock),
            panelStatus(base.copy(locked = true, lockedTools = setOf(ToolName.SET_TIMER))))
        assertEquals(PanelStatus("Talking with Claude: questions go online", RIcons.Cloud), panelStatus(base.copy(helperMode = "claude", helperName = "Claude")))
        assertEquals(PanelStatus("Talking with Jarvis: questions go to your server", RIcons.Home),
            panelStatus(base.copy(helperMode = "h2", helperName = "Jarvis", helperHome = true)))
        assertEquals(PanelStatus("Loading the model…"), panelStatus(base.copy(brain = loading)))
        assertEquals(PanelStatus("Loading the model…"), panelStatus(base.copy(phase = Phase.THINKING, brain = loading)))
        // A helper's own words, but "Waiting for", not "thinking".
        assertEquals(PanelStatus("Waiting for Claude…"), panelStatus(base.copy(phase = Phase.THINKING, helperStatus = "Claude is thinking…")))
        assertEquals(PanelStatus("Checking with Jarvis…"), panelStatus(base.copy(phase = Phase.THINKING, helperStatus = "Checking with Jarvis…")))
        assertEquals(PanelStatus("Jarvis is answering…"),
            panelStatus(base.copy(phase = Phase.ANSWERING, turns = listOf(UiTurn("r", TurnRole.ASSISTANT, "Hi", claude = true, by = "Jarvis")))))
        // A card listened to for its yes or no.
        assertEquals(PanelStatus("Say yes or no", RIcons.Mic), panelStatus(base.copy(phase = Phase.CONFIRMING, cardHearing = Phase.LISTENING)))
        assertEquals(PanelStatus("Transcribing…"), panelStatus(base.copy(phase = Phase.CONFIRMING, cardHearing = Phase.TRANSCRIBING)))
    }

    @Test fun theProgressBarOnlyWhileWorking() {
        for (p in Phase.entries) {
            val on = p == Phase.TRANSCRIBING || p == Phase.THINKING
            assertEquals(p.name, on, showsProgress(base.copy(phase = p)))
        }
        assertEquals(true, showsProgress(base.copy(brain = loading)))
        assertEquals(true, showsProgress(base.copy(phase = Phase.CONFIRMING, cardHearing = Phase.TRANSCRIBING)))
        assertEquals(false, showsProgress(base.copy(phase = Phase.CONFIRMING, cardHearing = Phase.LISTENING)))
    }

    @Test fun theStatusLineIsALiveRegionAndTheBarShowsWhenItShould() {
        var st by mutableStateOf(base)
        rule.show { PanelHeader(st, RecordingActions()) {} }
        val bar = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
        for (p in Phase.entries) {
            st = base.copy(phase = p)
            rule.waitForIdle()
            rule.onNodeWithText(panelStatus(st).text).assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
            val shown = rule.onAllNodes(bar).fetchSemanticsNodes().size
            assertEquals(p.name, if (showsProgress(st)) 1 else 0, shown)
        }
        st = base.copy(brain = loading)
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(bar).fetchSemanticsNodes().size)
    }
}
