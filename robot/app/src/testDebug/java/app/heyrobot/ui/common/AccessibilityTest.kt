package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.heyrobot.ui.SpeakerChip
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 1.0, the accessibility findings A1 to A7 of the release plan: the panel's answer is read by TalkBack once it is complete
 * (a polite live region, never while it streams), a tick box and its words are one target, a tappable chip reserves 48 dp,
 * and the screen button's "not ready" colour keeps 3:1 against the panel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = PHONE)
class AccessibilityTest {
    @get:Rule val rule = createComposeRule()

    private val answer = "The sky is blue because air scatters blue light the most."
    private val liveRegion = SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)
    private val noLiveRegion = SemanticsMatcher.keyNotDefined(SemanticsProperties.LiveRegion)

    private fun answerIsALiveRegionOnceComplete(classic: Boolean) {
        var streaming by mutableStateOf(true)
        rule.show(classic = classic) { TurnView(TurnUi("r1", user = false, text = answer, streaming = streaming, announce = true), TurnActions.None) }
        // While it streams TalkBack isn't told each word; once complete, the whole answer is one polite live region.
        rule.onNodeWithText(answer, substring = true).assert(noLiveRegion)
        streaming = false
        rule.waitForIdle()
        rule.onNodeWithText(answer, substring = true).assert(liveRegion)
        rule.onAllNodesWithText(answer, substring = true).assertCountEquals(1)
    }

    @Test fun thePanelsAnswerIsALiveRegionOnceComplete() = answerIsALiveRegionOnceComplete(classic = false)

    @Test fun theClassicPanelsAnswerToo() = answerIsALiveRegionOnceComplete(classic = true)

    @Test fun historyAndSpokenRepliesAreNotAnnounced() {
        rule.show { TurnView(TurnUi("r1", user = false, text = answer), TurnActions.None) }
        rule.onNodeWithText(answer, substring = true).assert(noLiveRegion)
    }

    @Test fun aTickBoxAndItsWordsAreOneTarget() {
        var ticked by mutableStateOf(false)
        rule.show { TickRow("I'll tell everyone that I'm transcribing", ticked, { ticked = it }) }
        rule.onAllNodes(isToggleable()).assertCountEquals(1)
        rule.onNodeWithText("I'll tell everyone", substring = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.Off))
            .performClick()
        assertTrue(ticked)
        rule.onNodeWithText("I'll tell everyone", substring = true).assert(SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, ToggleableState.On))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun aTappableChipReservesAFullTarget() {
        rule.show { Row(Modifier.testTag("line")) { SpeakerChip("S1", "Speaker 1", onClick = {}) } }
        rule.onNodeWithTag("line").assertHeightIsAtLeast(48.dp)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** WCAG 2.1 contrast ratio. */
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance() + 0.05, b.luminance() + 0.05).sortedDescending()
        return hi.toDouble() / lo
    }

    @Test fun theNotReadyScreenButtonKeepsThreeToOne() {
        for (c in listOf(RobotColors.Light, RobotColors.Dark)) {
            for (bg in listOf(c.surface, c.surfaceContainerLow, c.surfaceContainer, c.surfaceContainerHigh)) {
                assertTrue("outline on $bg: ${contrast(c.outline, bg)}", contrast(c.outline, bg) >= 3.0)
            }
        }
        // What it replaced: the 38 % alpha of a disabled button, about 2:1 on the light panel.
        val light = RobotColors.Light
        assertTrue(contrast(light.onSurfaceVariant.copy(alpha = 0.38f).compositeOver(light.surfaceContainerLow), light.surfaceContainerLow) < 3.0)
    }
}
