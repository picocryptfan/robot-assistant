package app.heyrobot.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 1.0: a conversation begun on the lock screen and continued unlocked (core PanelContinuity): in the panel its turns stay
 * under "Said while the phone was locked" (a heading for TalkBack), and a rule shows where your own, unlocked turns begin.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class LockedTurnsDividerTest {
    @get:Rule val rule = createComposeRule()

    private val turns = listOf(
        TurnUi("u1", user = true, text = "text Mom I'm stuck") to true,
        TurnUi("r1", user = false, text = "Unlock your phone, then ask again.") to true,
        TurnUi("u2", user = true, text = "what's on my calendar today?") to false,
        TurnUi("r2", user = false, text = "Nothing today.") to false,
    )

    /** The panel's list, oldest first, each turn with the divider the panel puts above it. */
    private fun show(turns: List<Pair<TurnUi, Boolean>>, classic: Boolean = false) = rule.show(classic = classic) {
        Column {
            val locked = turns.map { it.second }
            turns.forEachIndexed { i, (t, _) -> WithLockedDivider(lockedDividerAbove(locked, i)) { TurnView(t, TurnActions.None) } }
        }
    }

    @Test fun theDividersGoAboveTheFirstLockedTurnAndYourFirstOwn() {
        fun above(vararg locked: Boolean) = locked.indices.map { lockedDividerAbove(locked.toList(), it) }
        assertEquals(listOf(LockedDivider.LABEL, null, LockedDivider.END, null), above(true, true, false, false))
        assertEquals(listOf(LockedDivider.LABEL, null), above(true, true))
        assertEquals(listOf(null, null), above(false, false))
    }

    @Test fun theLockScreensTurnsSitUnderTheirLabel() {
        show(turns)
        rule.onAllNodesWithText("Said while the phone was locked").assertCountEquals(1)
        rule.onNodeWithText("Said while the phone was locked").assert(isHeading())
        rule.onAllNodesWithTag(LOCKED_END_TAG).assertCountEquals(1)
        // Top to bottom: the label, the lock screen's turns, the rule, then yours.
        val label = rule.onNodeWithTag(LOCKED_LABEL_TAG).getBoundsInRoot()
        val stranger = rule.onNodeWithContentDescription("You: text Mom I'm stuck").getBoundsInRoot()
        val unlock = rule.onNodeWithText("Unlock your phone, then ask again.").getBoundsInRoot()
        val end = rule.onNodeWithTag(LOCKED_END_TAG).getBoundsInRoot()
        val yours = rule.onNodeWithContentDescription("You: what's on my calendar today?").getBoundsInRoot()
        assertTrue(label.bottom <= stranger.top)
        assertTrue(unlock.bottom <= end.top)
        assertTrue(end.bottom <= yours.top)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun theClassicLookHasThemToo() {
        show(turns, classic = true)
        rule.onNodeWithText("Said while the phone was locked").assert(isHeading())
        rule.onAllNodesWithTag(LOCKED_END_TAG).assertCountEquals(1)
    }

    @Test fun aConversationWithNoneHasNoDivider() {
        show(turns.map { it.first to false })
        rule.onAllNodesWithText("Said while the phone was locked").assertCountEquals(0)
        rule.onAllNodesWithTag(LOCKED_END_TAG).assertCountEquals(0)
    }
}
