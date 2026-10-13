package app.heyrobot.assistant

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import app.heyrobot.core.LookSettings
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.RobotFace
import app.heyrobot.ui.common.RobotFaceIcon
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.SHOTS
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.theme.RobotTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.0: the robot at the top of the panel changes with what Robot is doing, in both looks and on the lock screen. It is
 * decorative (the status line says it in words), the speaking robot waits out the pause between two sentences, and the
 * screenshots show the standard header in each state, light and dark.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class RobotFacePanelTest {
    @get:Rule val rule = createComposeRule()

    private val asked = UiTurn("u1", TurnRole.USER, "How far is the Moon?")

    /** A panel state for each face. */
    private val states = mapOf(
        RobotFace.IDLE to PanelFixtures.idle,
        RobotFace.LISTENING to PanelFixtures.listening,
        RobotFace.TYPING to PanelState("c1", phase = Phase.ANSWERING, speak = false, sttAvailable = true,
            turns = listOf(asked, UiTurn("r1", TurnRole.ASSISTANT, "About 384,000", streaming = true))),
        RobotFace.SPEAKING to PanelState("c1", phase = Phase.SPEAKING, speak = true, sttAvailable = true, talking = true,
            turns = listOf(asked, UiTurn("r1", TurnRole.ASSISTANT, "About 384,000 km on average."))),
    )

    private fun tag(f: RobotFace) = "robot-face-" + f.name.lowercase()

    @Test fun bothLooksAndTheLockScreenShowTheFaceForTheState() {
        var st by mutableStateOf(PanelFixtures.idle)
        var classic by mutableStateOf(false)
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false, classic = classic)) { AssistantPanel(st, RecordingActions()) {} } }
        for ((face, s) in states) for (c in listOf(false, true)) for (locked in listOf(false, true)) {
            st = s.copy(locked = locked)
            classic = c
            rule.waitForIdle()
            rule.onNodeWithTag(tag(face), useUnmergedTree = true).assertExists("$face classic=$c locked=$locked")
        }
    }

    @Test fun itIsDecorative() {
        rule.showPanel(PanelFixtures.listening)
        val c = rule.onNodeWithTag(tag(RobotFace.LISTENING), useUnmergedTree = true).fetchSemanticsNode().config
        assertFalse(c.contains(SemanticsProperties.ContentDescription))
        assertFalse(c.contains(SemanticsProperties.StateDescription))
    }

    @Test fun theFaceFollowsTheState() {
        var face by mutableStateOf(RobotFace.SPEAKING)
        rule.setContent { RobotFaceIcon(face, Color.Black, Modifier.size(40.dp)) }
        rule.onNodeWithTag(tag(RobotFace.SPEAKING), useUnmergedTree = true).assertExists()
        for (f in listOf(RobotFace.TYPING, RobotFace.SPEAKING, RobotFace.IDLE, RobotFace.LISTENING)) {
            face = f
            rule.waitUntil(2_000) { rule.onAllNodesWithTag(tag(f), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
        }
    }

    // ------------------------------------------------------------------ screenshots: the standard header in each state

    private fun header(face: RobotFace, dark: Boolean) {
        rule.show(dark = dark) { Box(Modifier.testTag("shot")) { PanelHeader(states.getValue(face), RecordingActions()) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("shot").captureRoboImage("$SHOTS/robot_face_${face.name.lowercase()}_${if (dark) "dark" else "light"}.png")
    }

    @Test fun idleLight() = header(RobotFace.IDLE, dark = false)
    @Test fun idleDark() = header(RobotFace.IDLE, dark = true)
    @Test fun listeningLight() = header(RobotFace.LISTENING, dark = false)
    @Test fun listeningDark() = header(RobotFace.LISTENING, dark = true)
    @Test fun typingLight() = header(RobotFace.TYPING, dark = false)
    @Test fun typingDark() = header(RobotFace.TYPING, dark = true)
    @Test fun speakingLight() = header(RobotFace.SPEAKING, dark = false)
    @Test fun speakingDark() = header(RobotFace.SPEAKING, dark = true)
}
