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

    /** fix-car-stay-c: a reply in its 3 seconds, or on its way, holds a turn with no panel (CarModeController.quietCheck). */
    @Test fun aReplyInItsStopWindowOrOnItsWayIsSending() {
        fun with(st: ReplyCardState) = state(listOf(said("").copy(reply = ReplyCardView("Reply to Marie", st, text = "On my way"))))
        assertTrue(CarTurnLines.sending(with(ReplyCardState.STOP_WINDOW)))
        assertTrue(CarTurnLines.sending(with(ReplyCardState.SENDING)))
        for (st in listOf(ReplyCardState.WAITING, ReplyCardState.SENT, ReplyCardState.FAILED)) assertFalse(CarTurnLines.sending(with(st)))
        assertFalse(CarTurnLines.sending(state()))
    }

    @Test fun aNoticeShows() {
        assertEquals("Not ready", CarTurnLines.of(state(notice = Notice("Not ready")))!!.ui.words)
    }

    @Test fun nothingAtAllIsNoLines() {
        assertNull(CarTurnLines.of(state()))
    }

    // fix-car-long-card: every card stays on the car screen, whole (it scrolls), never the panel over it; what Robot
    // can't read out well takes a tap (Approve). The 1.1.2 audit's rule holds: a Yes approves only what the pane shows whole.
    private fun pending(a: UiAction) = CarTurnLines.of(state(turns = listOf(said("", a))))!!
    private val plain = card(ActionStatus.PENDING)

    @Test fun aPlainCardKeepsItsLookAndItsYes() {
        val n = pending(plain)
        assertTrue(n.ui.ask!!.canYes)
        assertTrue(n.ui.ask!!.whole)
        assertFalse(n.tapOnly)
        assertEquals("Call Marie?", n.ui.ask!!.text)
    }

    @Test fun aMessagesWordsShowWholeInThePaneWithItsYes() {
        val n = pending(plain.copy(body = "The words\non three\nlines"))
        assertTrue(n.ui.ask!!.canYes)
        assertFalse(n.ui.ask!!.whole)
        assertFalse(n.tapOnly)
        assertEquals("Call Marie?\nThe words\non three\nlines", n.ui.ask!!.text)
    }

    @Test fun termuxShowsItsCommandAndTakesATap() {
        val n = pending(plain.copy(termux = app.heyrobot.ui.common.TermuxCardView("k", "rm -r x")))
        assertTrue(n.tapOnly)
        assertTrue(n.ui.ask!!.tapOnly)
        assertTrue(n.ui.ask!!.canYes)
        assertTrue(n.ui.ask!!.text.contains("rm -r x"))
    }

    @Test fun aCardThatNeedsATapShowsItsApprove() {
        val n = pending(plain.copy(tapFor = app.heyrobot.core.AskWhy.WHO))
        assertTrue(n.tapOnly)
        assertTrue(n.ui.ask!!.canYes)
    }

    @Test fun aLongReminderStaysInThePaneAndOverAMinuteAloudTakesATap() {
        val long = pending(plain.copy(detail = "word ".repeat(100)))
        assertFalse(long.ui.ask!!.whole)
        assertFalse(long.tapOnly)
        assertTrue(pending(plain.copy(detail = "word ".repeat(200))).tapOnly)
    }

    @Test fun choicesShowInThePaneAndAreAnsweredAloud() {
        val n = pending(plain.copy(choices = listOf("Marie Martin", "Marie Roy"), more = 2))
        assertFalse(n.ui.ask!!.canYes)
        assertTrue(n.ui.ask!!.text.endsWith("Marie Martin\nMarie Roy"))
    }

    @Test fun aButtonWordTooLongForItsButtonGivesWayToTheCarScreensOwn() {
        val a = pending(plain.copy(confirm = "Send to the whole group", cancel = "Keep")).ui.ask!!
        assertEquals(null, a.yes)
        assertEquals("Keep", a.no)
    }

    @Test fun theReasonAndTheButtonsOwnWordsShow() {
        val a = pending(plain.copy(detail = "Your reason", confirm = "Delete", cancel = "Keep")).ui.ask!!
        assertEquals("Call Marie?\nYour reason", a.text)
        assertEquals("Delete", a.yes)
        assertEquals("Keep", a.no)
        assertTrue(a.canYes)
    }
}
