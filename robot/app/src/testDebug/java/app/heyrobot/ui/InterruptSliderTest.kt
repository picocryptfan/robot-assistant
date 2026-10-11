package app.heyrobot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import app.heyrobot.core.InterruptSensitivity
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Settings › Voice › "Interrupt sensitivity" (0.4.6): a tap on the slider's track, and TalkBack's adjust, save the level
 * tapped. Material's slider moves and ends the gesture in one go, so until 0.4.6 the level saved was the one drawn before
 * the tap: Low and High showed on screen but Medium stayed in the settings.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class InterruptSliderTest {
    @get:Rule val rule = createComposeRule()

    @Test fun aTapOnTheTrackSavesTheLevelTapped() {
        val saved = ArrayList<InterruptSensitivity>()
        rule.show {
            var v by remember { mutableStateOf(InterruptSensitivity.MEDIUM) }
            Column(Modifier.padding(horizontal = 20.dp)) { InterruptSlider(v) { v = it; saved += it } }
        }
        val slider = rule.onNodeWithContentDescription("Interrupt sensitivity")
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Medium"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())

        slider.performTouchInput { click(Offset(width * 0.9f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(listOf(InterruptSensitivity.HIGH), saved)
        slider.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "High"))

        slider.performTouchInput { click(Offset(width * 0.1f, height / 2f)) }
        rule.waitForIdle()
        assertEquals(InterruptSensitivity.LOW, saved.last())

        slider.performTouchInput { click(center) }
        rule.waitForIdle()
        assertEquals(InterruptSensitivity.MEDIUM, saved.last())
    }

    @Test fun talkBacksAdjustSavesTheLevelToo() {
        val saved = ArrayList<InterruptSensitivity>()
        rule.show {
            var v by remember { mutableStateOf(InterruptSensitivity.MEDIUM) }
            InterruptSlider(v) { v = it; saved += it }
        }
        val slider = rule.onNodeWithContentDescription("Interrupt sensitivity")
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        rule.waitForIdle()
        assertEquals(listOf(InterruptSensitivity.HIGH), saved)
        slider.performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        rule.waitForIdle()
        assertEquals(InterruptSensitivity.LOW, saved.last())
    }
}
