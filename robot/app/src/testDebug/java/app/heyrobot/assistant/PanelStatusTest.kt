package app.heyrobot.assistant

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasText
import app.heyrobot.core.LockScreen
import app.heyrobot.core.LookSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The status line under "Robot" in the panel's header, as 0.6.0 said it: for each phase its words, the other states
 * (locked, a helper's conversation, the model loading, a card listened to), and a polite live region in every phase, so
 * TalkBack says each new state once.
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
            Phase.IDLE to "On this phone, private",
            Phase.LISTENING to "Listening…",
            Phase.TRANSCRIBING to "Writing it down…",
            Phase.THINKING to "Thinking…",
            Phase.ANSWERING to "Answering…",
            Phase.CONFIRMING to "Waiting for you: tap the card, or answer yes or no",
            Phase.SPEAKING to "Speaking · tap to stop",
        )
        assertEquals(Phase.entries.toSet(), expected.keys)
        for ((p, s) in expected) assertEquals(p.name, s, statusLine(base.copy(phase = p)))
    }

    @Test fun theOtherStates() {
        assertEquals("Locked: " + LockScreen.allowed(setOf(ToolName.SET_TIMER)), statusLine(base.copy(locked = true, lockedTools = setOf(ToolName.SET_TIMER))))
        assertEquals("Talking with Claude: your questions go online", statusLine(base.copy(helperMode = "claude", helperName = "Claude")))
        assertEquals("Talking with Jarvis: your questions go to your server", statusLine(base.copy(helperMode = "h2", helperName = "Jarvis", helperHome = true)))
        assertEquals("Getting ready…", statusLine(base.copy(brain = loading)))
        assertEquals("Waking up the model…", statusLine(base.copy(phase = Phase.THINKING, brain = loading)))
        // A helper's own words until its first ones arrive.
        assertEquals("Claude is thinking…", statusLine(base.copy(phase = Phase.THINKING, helperStatus = "Claude is thinking…")))
        assertEquals("Jarvis is answering…",
            statusLine(base.copy(phase = Phase.ANSWERING, turns = listOf(UiTurn("r", TurnRole.ASSISTANT, "Hi", fromHelper = true, by = "Jarvis")))))
        // A card listened to for its yes or no.
        assertEquals("Say yes or no…", statusLine(base.copy(phase = Phase.CONFIRMING, cardHearing = Phase.LISTENING)))
    }

    @Test fun theStatusLineIsALiveRegionInEveryPhase() {
        var st by mutableStateOf(base)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(st, RecordingActions()) {} } }
        for (p in Phase.entries) {
            st = base.copy(phase = p)
            rule.waitForIdle()
            // While listening the mic's row says it too; the header's line is the live region.
            rule.onNode(hasText(statusLine(st)) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
        }
    }
}
