// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.ListCardRow
import app.heyrobot.ui.common.ListCardView
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-125-scroll (review-125): which newest turn scrolls to its first line, and in which phases. */
class ShowsTurnTopTest {
    private val shopping = ListCardView("0".repeat(31) + "1", "Shopping", open = listOf(ListCardRow("a".repeat(32), "Eggs")), done = emptyList())
    private val listReply = UiTurn("r1", TurnRole.ASSISTANT, "Yes, Shopping has eggs.", list = shopping)
    private val pending = UiAction("a1", ToolName.SEND_TEXT, "Text **Mom** in Cheogram", ActionStatus.PENDING, null, body = "I'm running late")

    @Test fun aReplyOverAListCardOnceTheTurnIsHeardAndWritten() {
        for (p in listOf(Phase.SPEAKING, Phase.IDLE)) assertTrue("$p", showsTurnTop(listReply, p))
        for (p in listOf(Phase.LISTENING, Phase.TRANSCRIBING, Phase.THINKING, Phase.ANSWERING)) assertFalse("$p", showsTurnTop(listReply, p))
    }

    /** review-125 item 2: a card waiting for a tap, then its result and Undo, stay where the list follows them. */
    @Test fun neverATurnWithACardOfRobotsOwn() {
        val waiting = UiTurn("r1", TurnRole.ASSISTANT, "", actions = listOf(pending))
        for (p in Phase.entries) assertFalse("waiting $p", showsTurnTop(waiting, p))
        val done = waiting.copy(actions = listOf(pending.copy(status = ActionStatus.DONE)))
        for (p in Phase.entries) assertFalse("done $p", showsTurnTop(done, p))
        for (p in Phase.entries) assertFalse("with a list $p", showsTurnTop(listReply.copy(actions = listOf(pending)), p))
    }

    /** review-125 item 4: a long reply alone keeps its foot in view when it ends. */
    @Test fun neverAReplyWithNoListCard() {
        val long = UiTurn("r1", TurnRole.ASSISTANT, (1..40).joinToString("\n\n") { "Paragraph $it." })
        for (p in Phase.entries) assertFalse("$p", showsTurnTop(long, p))
    }

    @Test fun neverTheQuestion() {
        for (p in Phase.entries) assertFalse("$p", showsTurnTop(UiTurn("u1", TurnRole.USER, "Is eggs on my list?", list = shopping), p))
    }
}
