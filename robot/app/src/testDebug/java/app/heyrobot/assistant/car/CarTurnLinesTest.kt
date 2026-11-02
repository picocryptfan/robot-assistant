// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.assistant.Notice
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The information panel's lines in a turn with no panel (handoff-car-mode-drive-fixes, item 3). */
class CarTurnLinesTest {
    private fun state(turns: List<UiTurn> = emptyList(), phase: Phase = Phase.IDLE, partial: String = "", notice: Notice? = null) =
        PanelState("c", turns, phase, partial = partial, notice = notice)
    private fun asked(text: String) = UiTurn("u", TurnRole.USER, text)
    private fun said(text: String, vararg a: UiAction) = UiTurn("a", TurnRole.ASSISTANT, text, actions = a.toList())
    private fun card(status: ActionStatus, choices: List<String> = emptyList()) = UiAction("act", null, "Call Marie?", status, null, choices = choices)

    @Test fun listeningShowsTheLiveWords() {
        val n = CarTurnLines.of(state(phase = Phase.LISTENING, partial = "what's the"))!!
        assertTrue(n.ui.listening)
        assertEquals("what's the", n.ui.words)
        assertFalse(CarTurnLines.kept(n))
    }

    @Test fun theLastReplyDoesntShowWhileListening() {
        val n = CarTurnLines.of(state(phase = Phase.LISTENING, turns = listOf(said("An old answer"))))!!
        assertTrue(n.ui.listening)
        assertNull(n.ui.words)
    }

    @Test fun thinkingShowsWhatYouSaid() {
        assertEquals("What time is it", CarTurnLines.of(state(phase = Phase.THINKING, turns = listOf(asked("What time is it"))))!!.ui.words)
    }

    @Test fun theReplyShowsAndIsKept() {
        val n = CarTurnLines.of(state(phase = Phase.SPEAKING, turns = listOf(asked("Hi"), said("Hello there"))))
        assertEquals("Hello there", n!!.ui.words)
        assertTrue(CarTurnLines.kept(n))
    }

    @Test fun aReplyToAnEarlierQuestionIsntShownForThisOne() {
        assertNull(CarTurnLines.of(state(phase = Phase.SPEAKING, turns = listOf(said("Old"), asked("New")))))
    }

    @Test fun aCardWaitingAsksYesAndCancel() {
        val n = CarTurnLines.of(state(phase = Phase.CONFIRMING, turns = listOf(said("", card(ActionStatus.PENDING)))))!!
        assertEquals("Call Marie?", n.ui.ask!!.text)
        assertTrue(n.ui.ask!!.canYes)
        assertEquals("act", n.actionId)
        assertFalse(CarTurnLines.kept(n))
    }

    @Test fun aCardOfChoicesHasNoYes() {
        val n = CarTurnLines.of(state(turns = listOf(said("", card(ActionStatus.PENDING, listOf("Marie", "Mario"))))))!!
        assertFalse(n.ui.ask!!.canYes)
    }

    @Test fun aCardDoneAsksNothing() {
        val n = CarTurnLines.of(state(phase = Phase.SPEAKING, turns = listOf(said("Done", card(ActionStatus.DONE)))))!!
        assertNull(n.ui.ask)
        assertEquals("Done", n.ui.words)
    }

    /** L16: a Yes in the car sends on the tap, so the words it sends are on the screen. */
    @Test fun aReplyCardShowsTheWordsItsYesSends() {
        val mine = said("").copy(reply = ReplyCardView("Reply to **Marie** · Texts", ReplyCardState.WAITING, text = "On my way"))
        assertEquals("Reply to Marie · Texts\nOn my way", CarTurnLines.of(state(turns = listOf(mine)))!!.ui.ask!!.text)
        val theirs = said("").copy(reply = ReplyCardView("Reply to Marie", ReplyCardState.WAITING, text = "On my way", translation = "Je m'en viens", translate = true))
        assertEquals("Reply to Marie\nJe m'en viens", CarTurnLines.of(state(turns = listOf(theirs)))!!.ui.ask!!.text)
        val unticked = said("").copy(reply = ReplyCardView("Reply to Marie", ReplyCardState.WAITING, text = "On my way", translation = "Je m'en viens"))
        assertEquals("Reply to Marie\nOn my way", CarTurnLines.of(state(turns = listOf(unticked)))!!.ui.ask!!.text)
    }

    @Test fun aReplyCardWaitingAnswersThroughItsTurn() {
        val t = said("").copy(reply = ReplyCardView("Reply to Marie?", ReplyCardState.WAITING))
        val n = CarTurnLines.of(state(turns = listOf(t)))!!
        assertEquals("Reply to Marie?", n.ui.ask!!.text)
        assertEquals(t.id, n.replyId)
        assertTrue(n.ui.ask!!.canYes)
    }

    @Test fun aNoticeShows() {
        assertEquals("Not ready", CarTurnLines.of(state(notice = Notice("Not ready")))!!.ui.words)
    }

    @Test fun nothingAtAllIsNoLines() {
        assertNull(CarTurnLines.of(state()))
    }

    // The 1.1.2 audit: a Yes approves only what the panel showed whole; anything else goes to the panel's window.
    private fun pending(a: UiAction) = CarTurnLines.of(state(turns = listOf(said("", a))))!!
    private val plain = card(ActionStatus.PENDING)

    @Test fun aPlainCardKeepsItsYesAndNoWindow() {
        val n = pending(plain)
        assertTrue(n.ui.ask!!.canYes)
        assertFalse(n.needsWindow)
        assertEquals("Call Marie?", n.ui.ask!!.text)
    }

    @Test fun noYesWithABody() {
        val n = pending(plain.copy(body = "The words"))
        assertFalse(n.ui.ask!!.canYes)
        assertTrue(n.needsWindow)
        assertTrue(n.ui.ask!!.text.contains("The words"))
    }

    @Test fun noYesForTermux() {
        val n = pending(plain.copy(termux = app.heyrobot.ui.common.TermuxCardView("k", "rm -r x")))
        assertFalse(n.ui.ask!!.canYes)
        assertTrue(n.needsWindow)
    }

    @Test fun noYesForATapOnlyCard() {
        val n = pending(plain.copy(tapFor = app.heyrobot.core.AskWhy.WHO))
        assertFalse(n.ui.ask!!.canYes)
        assertTrue(n.needsWindow)
    }

    @Test fun noYesWhenTheWordsDontFitOrChoicesOverflow() {
        assertTrue(pending(plain.copy(summary = "x".repeat(61))).needsWindow)
        assertFalse(pending(plain.copy(detail = "y".repeat(80))).ui.ask!!.canYes)
        assertTrue(pending(plain.copy(choices = listOf("a"), more = 2)).needsWindow)
    }

    @Test fun theReasonAndTheButtonsOwnWordsShow() {
        val a = pending(plain.copy(detail = "Your reason", confirm = "Delete", cancel = "Keep")).ui.ask!!
        assertEquals("Call Marie?\nYour reason", a.text)
        assertEquals("Delete", a.yes)
        assertEquals("Keep", a.no)
        assertTrue(a.canYes)
    }
}
