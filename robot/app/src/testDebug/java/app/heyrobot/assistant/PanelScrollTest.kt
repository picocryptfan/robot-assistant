// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import app.heyrobot.core.LookSettings
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.ListCardRow
import app.heyrobot.ui.common.ListCardView
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.setShotContent
import app.heyrobot.ui.theme.RobotTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * fix-125-scroll (review-125): a turn taller than the list. Only a reply over a list card scrolls to its first line,
 * once per turn: the person's own scroll survives the turn's later phases and a long reply alone doesn't jump. A card
 * waiting for a tap is never taller than the list here (its text is capped, the test's panel keeps its own height), so
 * its rule is [ShowsTurnTopTest]'s.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class PanelScrollTest {
    @get:Rule val rule = createComposeRule()

    private val ask = UiTurn("u1", TurnRole.USER, "Tell me about it")
    private val long = (1..40).joinToString("\n\n") { "Paragraph $it of the reply." }
    private val shopping = ListCardView("0".repeat(31) + "1", "Shopping",
        open = listOf(ListCardRow("a".repeat(32), "Oat milk"), ListCardRow("b".repeat(32), "Eggs"), ListCardRow("c".repeat(32), "Bread")),
        done = emptyList())

    /** The panel for [first], which the test changes. */
    private fun show(first: PanelState): (PanelState) -> Unit {
        var st by mutableStateOf(first)
        rule.setShotContent {
            RobotTheme(dark = false, look = LookSettings(wallpaperColors = false)) {
                Surface(color = MaterialTheme.colorScheme.background) { AssistantPanel(st, RecordingActions()) {} }
            }
        }
        rule.waitForIdle()
        return { st = it; rule.waitForIdle() }
    }

    /** The turn's phases from the question on: thinking, the reply written, then [end]'s phases. */
    private fun turn(reply: UiTurn, vararg end: Phase): (PanelState) -> Unit {
        val base = PanelState("c1", turns = listOf(ask), phase = Phase.THINKING, speak = true)
        val set = show(base)
        set(base.copy(turns = listOf(ask, reply.copy(streaming = true)), phase = Phase.ANSWERING))
        for (p in end) set(base.copy(turns = listOf(ask, reply), phase = p))
        return set
    }

    private fun listNode() = rule.onAllNodes(hasScrollToIndexAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.height }

    private fun bounds(text: String): Rect? = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot

    /** [text]'s node starts inside the list: its first line shows. */
    private fun topInView(text: String): Boolean {
        val l = listNode().boundsInRoot; val b = bounds(text) ?: return false
        return b.top >= l.top - 1 && b.top < l.bottom
    }

    /** [text]'s node ends inside the list: its last line shows. */
    private fun footInView(text: String): Boolean {
        val l = listNode().boundsInRoot; val b = bounds(text) ?: return false
        return b.bottom <= l.bottom + 1 && b.bottom > l.top
    }

    private fun swipeToFoot() {
        val id = listNode().id
        repeat(4) { rule.onNode(SemanticsMatcher("the conversation") { it.id == id }).performTouchInput { swipeUp() } }
        rule.waitForIdle()
    }

    @Test fun aTallListCardShowsFromTheReplysFirstLine() {
        turn(UiTurn("r1", TurnRole.ASSISTANT, long, list = shopping), Phase.SPEAKING, Phase.IDLE)
        assertTrue("the reply's first line, once the turn has ended", topInView("Paragraph 1 of"))
    }

    @Test fun aScrollByThePersonSurvivesTheTurnsLaterPhases() {
        val reply = UiTurn("r1", TurnRole.ASSISTANT, long, list = shopping)
        val set = turn(reply, Phase.SPEAKING)
        assertTrue("first line while it speaks", topInView("Paragraph 1 of"))
        swipeToFoot()
        assertTrue("the card's foot after the person's scroll", footInView("Bread"))
        set(PanelState("c1", turns = listOf(ask, reply), phase = Phase.IDLE, speak = true))
        assertTrue("still the card's foot once speech ends", footInView("Bread"))
    }

    @Test fun aLongReplyWithNoCardDoesntJumpWhenItEnds() {
        turn(UiTurn("r1", TurnRole.ASSISTANT, long), Phase.SPEAKING, Phase.IDLE)
        assertTrue("the reply's last line stays in view", footInView("Paragraph 40 of"))
    }
}

