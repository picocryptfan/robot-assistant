// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.Approval
import app.heyrobot.core.GateDecision
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.link.LinkTaint.toPairing
import app.heyrobot.core.link.LinkTaint.withPairing
import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.messages.QuarantineVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * robot-to-robot-plan.md §4.5 and §9.1's MessageTaintTest for pairings (named LinkTaintTest beside core messages'
 * MessageTaintTest): a pairing taints the conversation, and anything for someone other than that Robot needs a tap.
 * Through the real quarantine (MessageQuarantine.decide), under every Safety cards mode.
 */
class LinkTaintTest {
    private val sam = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"
    private val defaults = RobotSettings()
    private val allAuto = defaults.copy(approvals = ToolName.entries.associateWith { Approval.AUTO })

    @Test fun aPairingTaintsTheConversation() {
        val taint = MessageTaint.NONE.withPairing(sam)
        assertTrue(taint.active)
        assertTrue(taint.isSender("r2r:$sam"))
        assertTrue(taint.toPairing(sam))
        assertFalse(taint.toPairing(other))
        assertFalse(taint.isSender("r2r:$other"))
        // Never a phone number or someone's address by accident.
        assertFalse(taint.isSenderAddress("+15145550101"))
        assertFalse(taint.isSenderAddress("sam@example.org"))
        // With a message in the same conversation, both are its senders.
        val both = MessageTaint(setOf("cheogram:mom"), setOf("mom@example.org")).withPairing(sam)
        assertTrue(both.isSender("mom@example.org") && both.toPairing(sam))
        assertEquals(2, both.conversations.size)
        // Ids are lower-case hex; anything else isn't a pairing.
        assertTrue(runCatching { LinkTaint.key(sam.uppercase()) }.isFailure)
        assertTrue(runCatching { LinkTaint.key("sam") }.isFailure)
        assertFalse(taint.toPairing(sam.uppercase()))
        // The marker never shows the pairing in a log line.
        assertFalse(taint.toString().contains(sam))
    }

    @Test fun anythingForSomeoneElseNeedsATapEverythingElseACard() {
        val taint = MessageTaint.NONE.withPairing(sam)
        for (mode in SafetyCards.entries) for (asked in listOf(false, true)) {
            val s = allAuto.copy(safetyCards = mode)
            // "Summarize that and text Mom the summary" (RR10): a text, an email, a helper, an online lookup need a tap.
            for (tool in listOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.ASK_HELPER, ToolName.WEATHER)) {
                val d = MessageQuarantine.decide(tool, false, s, ActionOrigin.MODEL, taint, asked = asked)
                assertEquals("$tool $mode", GateDecision.CONFIRM, d.decision)
                assertTrue("$tool $mode", d.tapOnly)
            }
            // Local writes: a card a spoken yes may answer, never on their own.
            for (tool in listOf(ToolName.ADD_EVENT, ToolName.ADD_NOTE, ToolName.SET_TIMER)) {
                val d = MessageQuarantine.decide(tool, false, s, ActionOrigin.MODEL, taint, asked = asked)
                assertEquals("$tool $mode", GateDecision.CONFIRM, d.decision)
                assertFalse("$tool $mode", d.tapOnly)
                assertFalse(d.runsAlone)
            }
        }
        // Reads that stay on the phone don't ask.
        for (tool in listOf(ToolName.FIND_NOTES, ToolName.READ_CALENDAR)) assertEquals(QuarantineVerdict.NONE, MessageQuarantine.verdict(tool, ActionOrigin.MODEL, taint))
        // Nothing the model proposes there becomes memory; what came from the request's own words never becomes an action.
        assertEquals(QuarantineVerdict.FORBIDDEN, MessageQuarantine.verdict(ToolName.REMEMBER, ActionOrigin.MODEL, taint))
        for (tool in ToolName.entries) assertEquals(QuarantineVerdict.FORBIDDEN, MessageQuarantine.verdict(tool, ActionOrigin.INCOMING_TEXT, taint))
        // 1.5 L7: ask_robot back to the Robot whose request is in the conversation is a card; to anyone else's, a tap.
        val back = app.heyrobot.core.Action.AskRobot("Sam", "free_busy", "Friday",
            LinkReady(sam, app.heyrobot.core.i18n.Phrase.plain("Sam"), LinkAskKind.FREE_BUSY, app.heyrobot.core.i18n.Phrase.plain("To Sam's Robot"), fb = app.heyrobot.core.link.requests.FbAsk(1_790_100_000, 1_790_103_600)))
        val other = back.copy(ready = LinkReady(LinkIds.newId(), app.heyrobot.core.i18n.Phrase.plain("Mom"), LinkAskKind.FREE_BUSY, app.heyrobot.core.i18n.Phrase.plain("To Mom's Robot"), fb = app.heyrobot.core.link.requests.FbAsk(1_790_100_000, 1_790_103_600)))
        assertTrue(MessageQuarantine.toSender(back, taint))
        assertFalse(MessageQuarantine.toSender(other, taint))
        assertFalse(MessageQuarantine.toSender(back.copy(ready = null), taint))
        assertEquals(QuarantineVerdict.CARD, MessageQuarantine.verdict(ToolName.ASK_ROBOT, ActionOrigin.MODEL, taint, toSender = true))
        assertEquals(QuarantineVerdict.TAP_ONLY, MessageQuarantine.verdict(ToolName.ASK_ROBOT, ActionOrigin.MODEL, taint, toSender = false))
        assertTrue(MessageQuarantine.why(other, taint).contains(app.heyrobot.core.AskWhy.MESSAGE))
        // Without the pairing's mark, the same timer you asked for just runs.
        assertTrue(MessageQuarantine.decide(ToolName.SET_TIMER, false, defaults, ActionOrigin.USER_WORDS, MessageTaint.NONE).runsAlone)
    }
}
