// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.view.View
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import app.heyrobot.core.LookSettings
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
 * 1.0.2, from the phone: in landscape the keyboard covered the panel's text field, and what was typed couldn't be seen.
 * Robolectric has no keyboard, so its insets are given to the panel's view as Android gives them (about a landscape
 * keyboard's height). The text field and what is typed in it are always whole above the keyboard; the top of the panel
 * gives way first.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w891dp-h411dp-land-xhdpi")
class PanelLandscapeTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var view: View
    private val keyboardDp = 220

    private fun show(st: PanelState) {
        rule.setContent {
            view = LocalView.current
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(st, RecordingActions()) {} }
        }
        // The keyboard comes up: Android's insets for it reach the panel (WindowInsets.ime).
        rule.runOnUiThread {
            val px = (keyboardDp * view.resources.displayMetrics.density).toInt()
            ViewCompat.dispatchApplyWindowInsets(view, WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, px)).setVisible(WindowInsetsCompat.Type.ime(), true).build())
        }
        rule.waitForIdle()
    }

    /** The text field is placed whole above the keyboard (not cut by the sheet, not under the keyboard). */
    private fun assertFieldWhole() {
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val keyboardTop = root.bottom - keyboardDp * view.resources.displayMetrics.density
        val field = rule.onNode(hasSetTextAction()).fetchSemanticsNode()
        val b = field.boundsInRoot
        assertTrue("the field is cut: $b of ${field.size}", b.height >= field.size.height - 1f && b.width >= field.size.width - 1f)
        assertTrue("the field is under the keyboard: $b, keyboard from $keyboardTop", b.top >= root.top && b.bottom <= keyboardTop + 1f)
    }

    @Test fun theFieldStaysWholeOverTheSuggestions() {
        show(PanelFixtures.idle)
        assertFieldWhole()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasSetTextAction()).performTextInput("What's the time in Lisbon")
        assertFieldWhole()
        rule.shot("panel_classic_landscape_keyboard")
    }

    @Test fun theFieldStaysWholeInAConversation() {
        show(PanelFixtures.answerWithCard.copy(phase = Phase.IDLE))
        assertFieldWhole()
        rule.shot("panel_classic_landscape_keyboard_conversation")
    }
}
