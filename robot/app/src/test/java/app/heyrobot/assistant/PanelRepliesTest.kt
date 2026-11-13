// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.TurnRole
import app.heyrobot.core.ToolName
import app.heyrobot.ui.common.ReplyCardState
import app.heyrobot.ui.common.ReplyCardView
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The reply writes and card lookups that moved out of PanelController (panel-split-left). */
class PanelRepliesTest {
    private fun card(id: String, status: ActionStatus = ActionStatus.DONE, tool: ToolName? = null, detail: String? = null) =
        UiAction(id, tool, "s-$id", status, detail)

    private val state = MutableStateFlow(
        PanelState(
            convId = "t",
            phase = Phase.ANSWERING,
            helperStatus = "Claude is answering",
            notice = Notice("kept"),
            turns = listOf(
                UiTurn("q", TurnRole.USER, "question"),
                UiTurn("r1", TurnRole.ASSISTANT, "first", actions = listOf(card("a"), card("b"))),
                UiTurn("r2", TurnRole.ASSISTANT, "second", actions = listOf(card("c"))),
            ),
        ),
    )
    private val replies = PanelReplies(state)

    @Test fun lookups() {
        assertEquals("second", replies.turn("r2")?.text)
        assertNull(replies.turn("nope"))
        assertEquals("r1", replies.turnWithCard("b")?.id)
        assertNull(replies.turnWithCard("z"))
        assertEquals("s-c", replies.card("r2", "c")?.summary)
        assertNull(replies.card("r1", "c"))
    }

    @Test fun endKeepsNoticeAndEndsTheTurnsPhase() {
        replies.end("r1", speaking = true) { it.copy(text = "done", streaming = false) }
        val st = state.value
        assertEquals(Phase.SPEAKING, st.phase)
        assertNull(st.helperStatus)
        assertEquals("kept", st.notice?.text)
        assertEquals(listOf("question", "done", "second"), st.turns.map { it.text })
    }

    @Test fun endWithNoticeAndNoSpeechGoesIdle() {
        replies.end("r2", speaking = false, notice = Notice("failed")) { it.copy(error = true) }
        assertEquals(Phase.IDLE, state.value.phase)
        assertEquals("failed", state.value.notice?.text)
        assertEquals(true, state.value.turns.last().error)
    }

    @Test fun endDropsTheReply() {
        replies.end("r2", speaking = false) { null }
        assertEquals(listOf("q", "r1"), state.value.turns.map { it.id })
    }

    @Test fun endLeavesListeningAlone() {
        state.value = state.value.copy(phase = Phase.LISTENING)
        replies.end("r1", speaking = false) { it }
        assertEquals(Phase.LISTENING, state.value.phase)
    }

    @Test fun thinkingAndConfirming() {
        replies.thinking(confirming = true)
        assertEquals(Phase.CONFIRMING, state.value.phase)
        replies.thinking(confirming = false)
        assertEquals(Phase.THINKING, state.value.phase)
    }

    @Test fun setTextsChangesOnlyThoseNamed() {
        val before = state.value.turns[2]
        replies.setTexts(mapOf("r1" to "[not kept]", "gone" to "x"))
        assertEquals(listOf("question", "[not kept]", "second"), state.value.turns.map { it.text })
        assertSame(before, state.value.turns[2])
    }

    @Test fun setReplyOnlyOnItsTurn() {
        val kept = ReplyCardView("Tap Send to send it.", ReplyCardState.PICK, text = "kept")
        val mine = ReplyCardView("Send to Mom?", ReplyCardState.PICK, text = "mine")
        state.value = state.value.copy(turns = state.value.turns.map { if (it.id == "r2") it.copy(reply = kept) else it })
        replies.setReply("r1", mine)
        assertSame(mine, state.value.turns[1].reply)
        assertSame(kept, state.value.turns[2].reply)
        assertNull(state.value.turns[0].reply)
        assertEquals(3, state.value.turns.size)
        replies.setReply("r1", null)
        assertNull(state.value.turns[1].reply)
        assertSame(kept, state.value.turns[2].reply)
    }

    /** Two turns holding a card with the same id: the lookups find the first turn's, as the inline code in PanelController did. */
    @Test fun twoTurnsSharingACardIdFirstOneWins() {
        state.value = state.value.copy(turns = state.value.turns + UiTurn("r3", TurnRole.ASSISTANT, "third", actions = listOf(card("a").copy(summary = "s-a-again"))))
        assertEquals("r1", replies.turnWithCard("a")?.id)
        assertEquals("s-a", replies.cardById("a")?.summary)
        assertEquals("s-a", replies.card("r1", "a")?.summary)
        assertEquals("s-a-again", replies.card("r3", "a")?.summary)
    }

    @Test fun updateCardsInStaysInItsReply() {
        state.value = state.value.copy(turns = state.value.turns.map { t -> t.copy(actions = t.actions.map { it.copy(tool = ToolName.FREE_MEMORY) }) })
        replies.updateCardsIn("r1", { it.id != "b" }) { it.copy(detail = "freed") }
        assertEquals(listOf("freed", null, null), state.value.turns.flatMap { it.actions }.map { it.detail })
    }
}
