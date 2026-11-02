// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.UiAction
import app.heyrobot.assistant.UiTurn
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.ReplyCardState
import app.heyrobot.ui.common.ReplyCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTurnsTest {
    private var clock = 100L
    private val turns = SpokenTurns { clock }
    private val user = UiTurn("u", TurnRole.USER, "what time is it")
    private fun reply(text: String, streaming: Boolean = false, actions: List<UiAction> = emptyList()) =
        UiTurn("r", TurnRole.ASSISTANT, text, streaming = streaming, actions = actions)
    private fun seen(a: UiTurn, phase: Phase = Phase.IDLE) = turns.observe(PanelState("c", listOf(user, a), phase), "SumsRouter")

    @Test fun aReplyBeingWrittenGivesNoLine() {
        assertEquals(0, seen(reply("It", streaming = true), Phase.ANSWERING).size)
        assertEquals(0, seen(reply(""), Phase.THINKING).size)
        assertEquals(0, seen(reply("")).size)
    }

    @Test fun aSettledReplyGivesOneLineWithTheProtocolsFields() {
        assertEquals(0, turns.observe(PanelState("c", listOf(user)), "SumsRouter").size)
        clock = 350
        val l = seen(reply("It's noon")).single()
        assertEquals("turn", l.getString("type"))
        assertEquals("what time is it", l.getString("heard"))
        assertEquals("SumsRouter", l.getString("router"))
        assertEquals("It's noon", l.getString("reply"))
        assertEquals(0, l.getJSONArray("cards").length())
        assertEquals(0, l.getJSONArray("tools").length())
        assertEquals(250, l.getJSONObject("times").getLong("replyMs"))
        assertEquals("the same state again says nothing", 0, seen(reply("It's noon")).size)
    }

    @Test fun aWaitingCardGivesALineEvenWhileTheModelIsStillBusy() {
        val tool = ToolName.entries.first()
        val l = seen(reply("", actions = listOf(UiAction("a", tool, "", ActionStatus.PENDING, null))), Phase.CONFIRMING).single()
        assertEquals(tool.id, l.getJSONArray("cards").getJSONObject(0).getString("kind"))
        assertEquals(tool.id, l.getJSONArray("tools").getString(0))
    }

    @Test fun aMessagesCardIsACardToo() {
        val l = seen(reply("").copy(reply = ReplyCardView("", ReplyCardState.PICK))).single()
        assertEquals("other", l.getJSONArray("cards").getJSONObject(0).getString("kind"))
    }

    @Test fun anAnsweredCardGivesAnotherLine() {
        val tool = ToolName.entries.first()
        val done = ActionStatus.entries.first { it != ActionStatus.PENDING }
        assertEquals(1, seen(reply("", actions = listOf(UiAction("a", tool, "", ActionStatus.PENDING, null)))).size)
        val l = seen(reply("", actions = listOf(UiAction("a", tool, "", done, null)))).single()
        assertEquals(0, l.getJSONArray("cards").length())
        assertEquals(tool.id, l.getJSONArray("tools").getString(0))
    }

    @Test fun clearForgetsWhatWasSaid() {
        assertEquals(1, seen(reply("x")).size)
        turns.clear()
        assertEquals(1, seen(reply("x")).size)
        assertTrue(seen(reply("x")).isEmpty())
    }
}
