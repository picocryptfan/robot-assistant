// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.spoken

import app.heyrobot.assistant.Offer
import app.heyrobot.assistant.PanelState
import app.heyrobot.assistant.Phase
import app.heyrobot.assistant.UiAction
import app.heyrobot.assistant.UiTurn
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.TurnRole
import app.heyrobot.ui.common.ReplyCardState
import app.heyrobot.ui.common.ReplyCardView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenCardsTest {
    private fun action(tool: app.heyrobot.core.ToolName?, status: ActionStatus = ActionStatus.PENDING) = UiAction("a", tool, "", status, null)
    private fun state(t: UiTurn, phase: Phase = Phase.IDLE) = PanelState("c", listOf(t), phase)
    private fun reply(actions: List<UiAction> = emptyList(), text: String = "") = UiTurn("r", TurnRole.ASSISTANT, text, actions = actions)

    @Test fun aWaitingActionIsItsToolsKind() {
        val tool = app.heyrobot.core.ToolName.entries.first()
        assertEquals(listOf(tool.id), SpokenCards.showing(state(reply(listOf(action(tool))))))
    }

    @Test fun aWaitingActionWithNoToolIsOther() = assertEquals(listOf("other"), SpokenCards.showing(state(reply(listOf(action(null))))))

    @Test fun anAnsweredActionIsNotWaiting() {
        val tool = app.heyrobot.core.ToolName.entries.first()
        val done = ActionStatus.entries.first { it != ActionStatus.PENDING }
        assertEquals(emptyList<String>(), SpokenCards.showing(state(reply(listOf(action(tool, done))))))
    }

    @Test fun anOpenMessageCardIsOtherAndAFinishedOneIsNot() {
        val open = setOf(ReplyCardState.PICK, ReplyCardState.WAITING, ReplyCardState.STOP_WINDOW, ReplyCardState.SENDING)
        for (s in open) assertEquals(s.name, listOf("other"), SpokenCards.showing(state(reply().copy(reply = ReplyCardView("", s)))))
        assertEquals(emptyList<String>(), SpokenCards.showing(state(reply().copy(reply = ReplyCardView("", ReplyCardState.SENT)))))
    }

    @Test fun anOfferUnderTheLastReplyIsOther() =
        assertEquals(listOf("other"), SpokenCards.showing(state(reply().copy(offer = Offer(api = false, text = "", sends = emptyList())))))

    @Test fun aCardThePanelMentionsAndThisCodeCantFindIsOther() =
        assertEquals(listOf("other"), SpokenCards.showing(state(reply(), Phase.CONFIRMING)))

    @Test fun onlyANoGetsPastARiskyCard() {
        val risky = spokenJson().getJSONArray("riskyCards").let { a -> List(a.length()) { a.getString(it) } }
        assertTrue(risky.size > 5)
        for (k in risky) {
            assertFalse(k, SpokenCards.allows("say-0000000000000000.s16", listOf(k)))
            assertTrue(k, SpokenCards.allows("no-0000000000000000.s16", listOf(k)))
        }
    }

    @Test fun aKindNobodyListedStopsAClip() = assertFalse(SpokenCards.allows("say-0000000000000000.s16", listOf("set_timer", "brand_new_tool")))

    @Test fun harmlessCardsLetAClipThrough() {
        assertTrue(SpokenCards.allows("say-0000000000000000.s16", SpokenProtocol.HARMLESS_CARDS.toList()))
        assertTrue(SpokenCards.allows("say-0000000000000000.s16", emptyList()))
    }

    @Test fun whenTheCardsCantBeReadOnlyANoPlays() {
        assertFalse(SpokenCards.allows("say-0000000000000000.s16", null))
        assertTrue(SpokenCards.allows("no-0000000000000000.s16", null))
    }

    @Test fun everyActionThatIsntPendingIsNamedHereSoANewWaitingStatusIsSeen() =
        assertEquals("a second waiting status would need adding to SpokenCards", 1, ActionStatus.entries.count { it.name.contains("PENDING") || it.name.contains("WAIT") })
}
