// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import app.heyrobot.core.Attachment
import app.heyrobot.core.LockScreen
import app.heyrobot.core.LookSettings
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.touchTargetProblems
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.0.2, from the phone: the lock screen's status line was cut ("…email drafts, co…"), and in a screen conversation the
 * title read "Rob…" beside Keep. The status line wraps whole, and Keep sits beside it, so "Robot" keeps its room, at the
 * usual text size and at twice it.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PanelHeaderFitTest {
    @get:Rule val rule = createComposeRule()

    /** A screen question not kept yet: the header offers Keep. */
    private val screenTurns = listOf(UiTurn("u1", TurnRole.USER, "What's on my screen?", Attachment.SCREEN), UiTurn("r1", TurnRole.ASSISTANT, "A recipe for bread."))
    private val many = LockScreen.CHOICES.take(9).toSet()

    private fun show(st: PanelState) =
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(st, RecordingActions()) {} } }

    private fun ComposeContentTestRule.layoutOf(text: String): TextLayoutResult {
        val out = ArrayList<TextLayoutResult>()
        onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(out) }
        return out.first()
    }

    private fun assertWhole(text: String) {
        val l = rule.layoutOf(text)
        assertFalse("'$text' is cut", l.hasVisualOverflow)
        for (i in 0 until l.lineCount) assertFalse("'$text' line $i ends in …", l.isLineEllipsized(i))
    }

    private fun lockedWithKeep() {
        val st = PanelState("c1", turns = screenTurns, keep = false, locked = true, lockedTools = many)
        show(st)
        rule.onNodeWithText("Keep").assertExists()
        assertWhole("Robot")
        assertWhole(statusLine(st))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theTitleAndTheLockedLineFit() {
        lockedWithKeep()
        rule.shot("panel_classic_locked_keep")
    }

    @Test @Config(fontScale = 2f)
    fun theTitleAndTheLockedLineFitAtTwiceTheTextSize() {
        lockedWithKeep()
        rule.shot("panel_classic_locked_keep_font200")
    }

    @Test fun keepStillKeeps() {
        val actions = RecordingActions()
        rule.setContent { RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) { AssistantPanel(PanelState("c1", turns = screenTurns, keep = false), actions) {} } }
        rule.onNodeWithText("Keep").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("keepConversation"), actions.calls)
    }
}
