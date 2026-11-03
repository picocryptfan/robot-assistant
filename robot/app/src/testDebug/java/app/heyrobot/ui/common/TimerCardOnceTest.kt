// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.common

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.ui.classic.AssistantBubble
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
 * fix-timers-1.2.1 (everyday-1.2.1, findings 2 and 3): a timer's result shows once, on its card, not again in the bubble
 * under it; and with a confirm card waiting, its Cancel and Open are in the accessibility tree, and the line above it is a
 * polite live region, so TalkBack reads it while the card waits rather than after.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class TimerCardOnceTest {
    @get:Rule val rule = createComposeRule()

    private val timer = ActionView("a1", ToolName.SET_TIMER, "Set a timer for 3 minutes", ActionStatus.DONE, "Timer set for 3 minutes.")

    @Test fun aTimersResultShowsOnce() {
        rule.show(classic = true) { AssistantBubble("Timer set for 3 minutes.", streaming = false, stopped = false, error = false, actions = listOf(timer)) }
        rule.onAllNodesWithText("Timer set for 3 minutes.").assertCountEquals(1)
    }

    @Test fun whatTheCardDoesntSayStays() {
        assertEquals("Sure!", bubbleText("Sure!\n\nTimer set for 3 minutes.", listOf(timer)))
        assertEquals("", bubbleText("Timer set for 3 minutes. Alarm set for 18:00.",
            listOf(timer, ActionView("a2", ToolName.SET_ALARM, "Alarm", ActionStatus.DONE, "Alarm set for 18:00"))))
        // A card that didn't happen, or no card, leaves the reply as it is.
        assertEquals("Timer set for 3 minutes.", bubbleText("Timer set for 3 minutes.", listOf(timer.copy(status = ActionStatus.FAILED))))
        assertEquals("It is 10:59 PM.", bubbleText("It is 10:59 PM.", emptyList()))
    }

    @Test fun aWaitingConfirmCardIsReachable() {
        val log = ArrayList<String>()
        val clock = ActionView("a2", ToolName.OPEN_APP, "Open your clock app", ActionStatus.PENDING, null, confirm = "Open", cancel = "Cancel")
        val line = "Robot can't cancel or turn off alarms: do it in your clock app."
        rule.show(classic = true) {
            AssistantBubble(line, streaming = true, stopped = false, error = false, actions = listOf(clock),
                onConfirm = { id, yes -> log += "$id $yes" }, announce = true)
        }
        rule.onNodeWithText("Cancel").assertHasClickAction()
        rule.onNodeWithText("Open").assertHasClickAction().performClick()
        assertEquals(listOf("a2 true"), log)
        rule.onNode(hasText(line, substring = true) and SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
    }
}
