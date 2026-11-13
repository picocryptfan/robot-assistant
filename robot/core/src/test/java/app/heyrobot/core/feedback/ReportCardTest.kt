// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.feedback

import app.heyrobot.core.ActionRecord
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCardTest {
    private val facts = FeedbackFacts(robot = "1.4.0 (140, release)", phone = "Google Pixel 8, Android 16", model = "Fast · GPU",
        look = "Robot", language = "en-CA", chip = "Tensor G3")
    private val turn = ReportTurn("Text Marie Tremblay at 902 555 0142", "Texting Marie Tremblay at 902-555-0142.", ToolName.SEND_TEXT,
        ActionStatus.DRAFT_OPENED, "en", "fr", at = 1_000)

    @Test fun askingAgainKeepsTheFirstTurnNeverMyUnlockReply() {
        val chat = listOf(
            ChatTurn("u1", TurnRole.USER, "set a timer for 5 minutes", 1),
            ChatTurn("a1", TurnRole.ASSISTANT, "Timer set for 5 minutes.", 2),
            ChatTurn("u2", TurnRole.USER, "report a problem", 3),
            ChatTurn("a2", TurnRole.ASSISTANT, "Unlock your phone, then say it again.", 4),
            ChatTurn("u3", TurnRole.USER, "report a problem", 5),
            ChatTurn("a3", TurnRole.ASSISTANT, "", 6),
        )
        val t = ReportTurn.from(chat, exclude = "a3", heard = "en", replied = "en", now = 7)!!
        assertEquals("set a timer for 5 minutes", t.said)
        assertEquals("Timer set for 5 minutes.", t.answer)
        assertNull(t.heard)
        assertNull(ReportTurn.from(chat.drop(2), exclude = "a3", heard = null, replied = null, now = 7))
    }

    @Test fun theTurnKeepsTheToolAndItsCodeNeverItsText() {
        val chat = listOf(
            ChatTurn("u1", TurnRole.USER, "set a timer for 5 minutes", 1),
            ChatTurn("a1", TurnRole.ASSISTANT, "Timer set for 5 minutes.", 2,
                actions = listOf(ActionRecord(ToolName.SET_TIMER, "Timer · 5 min · kitchen", ActionStatus.DONE, "the detail"))),
            ChatTurn("u2", TurnRole.USER, "report a problem", 3),
            ChatTurn("a2", TurnRole.ASSISTANT, "", 4),
        )
        val t = ReportTurn.from(chat, exclude = "a2", heard = "en-CA", replied = "en", now = 5)!!
        assertEquals("set a timer for 5 minutes", t.said)
        assertEquals("Timer set for 5 minutes.", t.answer)
        assertEquals("set_timer · DONE", t.did)
        assertFalse(t.toJson(), t.toJson().contains("kitchen") || t.toJson().contains("detail"))
        assertEquals(t, ReportTurn.fromJson(t.toJson()))
        assertNull(ReportTurn.from(chat.take(1), null, null, null, 5))
        assertNull(ReportTurn.fromJson("{\"v\":2,\"at\":5}"))
        assertNull(ReportTurn.fromJson("not json"))
    }

    @Test fun itWaitsTenMinutes() {
        assertTrue(turn.fresh(1_000 + 600_000))
        assertFalse(turn.fresh(1_000 + 600_001))
        assertFalse(turn.fresh(999))
    }

    @Test fun hiddenAsTheCardShowsItAndAsSaidWhenOff() {
        val on = ReportCard.shown(turn, hide = true, names = listOf("Marie Tremblay"), numbers = listOf("+19025550142"))
        assertFalse(on.toString(), on.said!!.contains("Marie") || on.answer!!.contains("Tremblay") || on.answer.contains("555"))
        assertTrue(on.said, on.said.contains(ReportHide.NAME) && on.said.contains(ReportHide.NUMBER))
        assertEquals("send_text · DRAFT_OPENED", on.tool)
        val off = ReportCard.shown(turn, hide = false, names = listOf("Marie Tremblay"))
        assertEquals(turn.said, off.said)
        assertEquals(turn.answer, off.answer)
    }

    @Test fun onlyTheTickedPartsGo() {
        val s = ReportCard.shown(turn, hide = true, names = listOf("Marie Tremblay"))
        assertNull(ReportCard.section(s, setOf(ReportPart.FACTS)))
        val one = ReportCard.section(s, setOf(ReportPart.SAID))!!
        assertTrue(one, one.startsWith("What you said:\n") && !one.contains("My answer") && !one.contains("What I did"))
        assertEquals(setOf(ReportPart.ANSWER, ReportPart.FACTS), ReportCard.parts(s, setOf(ReportPart.ANSWER, ReportPart.FACTS)))
        assertEquals(setOf(ReportPart.FACTS), ReportCard.parts(null, setOf(ReportPart.SAID, ReportPart.FACTS)))
        val draft = FeedbackDraft(FeedbackKind.BUG, "It texted the wrong person", "", "", emptySet())
        val v2 = ReportHeader.V2(ReportFeature.PEOPLE, ReportProblem.WRONG_ACTION, ReportCard.parts(s, ReportCard.TURN_PARTS))
        val text = FeedbackReport.compose(draft, "RFB-ABCD2345", ReportCard.facts(facts, false), emptyList(), sorted = v2,
            turn = ReportCard.section(s, ReportCard.TURN_PARTS)).text
        val h = ReportHeader.read(text)!!
        assertEquals(ReportFeature.PEOPLE, h.feature)
        assertEquals(ReportProblem.WRONG_ACTION, h.problem)
        assertEquals(ReportCard.TURN_PARTS, h.parts)
        assertTrue(text, text.contains("It texted the wrong person\n---\nWhat you said:\n") && text.contains("What I did:\nsend_text · DRAFT_OPENED\n"))
        assertFalse(text, text.contains("Pixel") || text.contains("Tensor") || text.contains("Marie"))
        assertTrue(text, text.contains("Robot: 1.4.0 (140, release)"))
    }

    @Test fun factsShowWhatTheHeaderWrites() {
        assertEquals("Robot: 1.4.0 (140, release)\nPhone: Google Pixel 8, Android 16\nChip: Tensor G3\nModel: Fast · GPU\nLook: Robot · Language: en-CA",
            ReportCard.factsText(facts))
    }

    @Test fun sortingGetsTheToolAndBothLanguages() {
        val sorted = ReportSort.sort(ReportCard.input(FeedbackEntry.VOICE, turn, ""))
        assertEquals(ReportFeature.PEOPLE, sorted.feature)
        assertEquals(ReportProblem.LANGUAGE, sorted.problem)
        assertEquals(ReportProblem.SLOW, ReportSort.sort(ReportCard.input(FeedbackEntry.VOICE, turn.copy(replied = "en"), "it was slow")).problem)
    }

    @Test fun theVoiceLogKeepsItsLastTenMinutes() {
        val log = listOf(VoiceLogEntry(900_000, VoiceEvent.FEEDBACK_OPENED, 1), VoiceLogEntry(100_000, VoiceEvent.FEEDBACK_OPENED, 2))
        assertEquals(1, FeedbackDiagnostics.voiceLog(log, since = 900_000 - ReportCard.LOG_MS).lines.size)
        assertEquals(2, FeedbackDiagnostics.voiceLog(log).lines.size)
    }

    @Test fun myAnswerStartsUntickedWhenTheTurnReadAnotherApp() {
        val chat = listOf(
            ChatTurn("u1", TurnRole.USER, "what's on my calendar, then a timer", 1),
            ChatTurn("a1", TurnRole.ASSISTANT, "Dentist with Dr. Patel at 3. Timer set.", 2,
                actions = listOf(ActionRecord(ToolName.READ_CALENDAR, "Calendar", ActionStatus.DONE), ActionRecord(ToolName.SET_TIMER, "Timer", ActionStatus.DONE))))
        val t = ReportTurn.from(chat, null, "en", "en", 5)!!
        assertEquals(ToolName.SET_TIMER, t.tool)
        assertEquals(setOf(ReportPart.SAID, ReportPart.ACTIONS, ReportPart.FACTS), ReportCard.ticked(t))
        assertEquals(ReportCard.ticked(t), ReportCard.ticked(ReportTurn.fromJson(t.toJson())!!))
        assertEquals(setOf(ReportPart.SAID, ReportPart.ACTIONS, ReportPart.FACTS), ReportCard.ticked(turn.copy(tool = ToolName.WEATHER)))
        // A text I drafted carries only what you said: every part starts ticked.
        assertEquals(ReportCard.TURN_PARTS + ReportPart.FACTS, ReportCard.ticked(turn))
    }

    @Test fun myAnswerStartsUntickedWhenItHoldsWordsReadWithNoToolOrAReadingTool() {
        val q = ChatTurn("u1", TurnRole.USER, "read my messages from Mom", 1)
        fun ticks(a: ChatTurn, vararg before: ChatTurn, said: ChatTurn = q) = ReportCard.ticked(ReportTurn.from(listOf(*before, said, a), null, "en", "en", 5)!!)
        val off = setOf(ReportPart.SAID, ReportPart.ACTIONS, ReportPart.FACTS)
        // A thread read aloud has no action in the chat (PanelMessages, ChatTurn.messages), in English and in French.
        assertEquals(off, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "Mom said: I'm at Jen's.", 2, messages = true, kept = "Robot read 1 message")))
        assertEquals(off, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "Maman a dit : je suis chez Jen.", 2, messages = true), said = ChatTurn("u1", TurnRole.USER, "lis mes messages de maman", 1)))
        // Every tool whose result is content (ToolName.reads) counts, phone_status too, and the next one added.
        assertEquals(off, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "You're on Home-5G.", 2, actions = listOf(ActionRecord(ToolName.PHONE_STATUS, "Phone", ActionStatus.DONE)))))
        for (t in ToolName.entries.filter { it.reads }) assertTrue(t.id, t in ReportCard.OTHER_APPS)
        // An answer from memory, a helper's, one with web sources, Claude's read from the screen, one about a file.
        for (a in listOf(ChatTurn("a1", TurnRole.ASSISTANT, "Jen's code is 4471.", 2, memoryIds = listOf("m_1")),
            ChatTurn("a1", TurnRole.ASSISTANT, "Jen said yes.", 2, fromHelper = true), ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2, sources = listOf("https://example.com")),
            ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2, readBack = true), ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2, files = true)))
            assertEquals(a.toString(), off, ticks(a))
        // What you shared, a picture or your screen: my answer can repeat it.
        for (u in listOf(q.copy(shared = true), q.copy(picture = "p.jpg"), q.copy(screenText = "Jen: 4471")))
            assertEquals(u.toString(), off, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2), said = u))
        // A screen or picture asked about with "Keep screenshots and photos in history" off (none kept), a file you attached,
        // and words said while the phone was locked (perhaps by someone else): my answer can repeat them too.
        for (u in listOf(q.copy(attachment = Attachment.SCREEN), q.copy(attachment = Attachment.PHOTO), q.copy(attachment = Attachment.FILE, fileName = "lease.pdf"),
            q.copy(fileName = "lease.pdf")))
            assertEquals(u.toString(), off, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2), said = u))
        // Review M2: words said while locked are What you said too, so it starts unticked as well, and stays so once saved.
        val lockedOff = setOf(ReportPart.ACTIONS, ReportPart.FACTS)
        assertEquals(lockedOff, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2), said = q.copy(locked = true)))
        assertEquals(lockedOff, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2, locked = true)))
        val locked = ReportTurn.of(listOf(q.copy(locked = true), ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2)), "a1", null, null, 5)!!
        assertTrue(locked.locked)
        assertEquals(lockedOff, ReportCard.ticked(ReportTurn.fromJson(locked.toJson())!!))
        assertFalse(ReportTurn.from(listOf(q, ChatTurn("a1", TurnRole.ASSISTANT, "It says 4471.", 2)), null, null, null, 5)!!.locked)
        // An earlier answer read the calendar; this one follows it up with no tool.
        assertEquals(off, ticks(ChatTurn("a2", TurnRole.ASSISTANT, "It's with Dr. Patel.", 4), ChatTurn("u0", TurnRole.USER, "what's on my calendar", 0),
            ChatTurn("a0", TurnRole.ASSISTANT, "Two things.", 0, actions = listOf(ActionRecord(ToolName.READ_CALENDAR, "Calendar", ActionStatus.DONE)))))
        // A turn with none of these still starts with every part ticked.
        assertEquals(ReportCard.TURN_PARTS + ReportPart.FACTS, ticks(ChatTurn("a1", TurnRole.ASSISTANT, "Timer set.", 2)))
    }

    /** Item 7: a contact's number in the forms a log holds it (spaces, dashes, +1, its 5-digit tail), and their address. */
    @Test fun aContactsNumberAndAddressHideInEveryForm() {
        for (n in listOf("(902) 555-0199", "+19025550199", "902 555 0199")) for (s in listOf("902-555-0199", "9025550199", "+1 902 555 0199", "55 01 99", "5 01 99", "50199"))
            assertEquals("$n / $s", "call [number]", ReportCard.shown(ReportTurn("call $s", null, at = 1), true, numbers = listOf(n)).said)
        assertEquals("call 55 01 99", ReportCard.shown(ReportTurn("call 55 01 99", null, at = 1), true).said)
        val home = listOf("The Old Mill\nPleasant Bay NS")
        assertEquals("drive to [address]", ReportCard.shown(ReportTurn("drive to the old mill", null, at = 1), true, addresses = home).said)
        assertEquals("Je t\u2019emmène à [address].", ReportCard.shown(ReportTurn(null, "Je t\u2019emmène à The Old Mill.", at = 1), true, addresses = home).answer)
    }

    /** Item 6: the page's saved state keeps the turn only while it's fresh. */
    @Test fun theSavedTurnLeavesOnceStale() {
        val t = ReportTurn("set a timer", "Timer set.", at = 1_000)
        assertEquals(t.toJson(), ReportCard.saved(t.toJson(), 1_000 + ReportTurn.FRESH_MS))
        assertNull(ReportCard.saved(t.toJson(), 1_001 + ReportTurn.FRESH_MS))
        assertNull(ReportCard.saved(null, 5))
        assertEquals(1L + ReportTurn.FRESH_MS, ReportCard.staleIn(t, 1_000))
        assertEquals(0L, ReportCard.staleIn(t, 1_000 + 2 * ReportTurn.FRESH_MS))
    }

    /** Item 8: heard and replied are the turn before the report phrase's; the log window only with a turn. */
    @Test fun heardAndRepliedComeFromTheTurnBefore() {
        val l = ReportLangs()
        l.asked("fr", null) // « mets une minuterie », answered in English
        l.asked("en", "en") // then "report a problem"
        assertEquals("fr", l.heardBefore)
        assertEquals("en", l.replyBefore)
        assertEquals(900_000 - ReportCard.LOG_MS, ReportCard.logSince(true, 900_000))
        assertNull(ReportCard.logSince(false, 900_000))
    }

    /** RP3: pressed and held (or the bug button, null): the turn that ends with that answer; its languages only when it's the newest. */
    @Test fun aTurnByItsAnswer() {
        val chat = listOf(
            ChatTurn("u1", TurnRole.USER, "set a timer for 5 minutes", 1),
            ChatTurn("a1", TurnRole.ASSISTANT, "Timer set for 5 minutes.", 2),
            ChatTurn("u2", TurnRole.USER, "what's 2 plus 2", 3),
            ChatTurn("a2", TurnRole.ASSISTANT, "5", 4),
        )
        val old = ReportTurn.of(chat, "a1", "fr", "en", 9)!!
        assertEquals("set a timer for 5 minutes", old.said)
        assertEquals("Timer set for 5 minutes.", old.answer)
        assertNull(old.heard)
        assertNull(old.replied)
        val newest = ReportTurn.of(chat, "a2", "fr", "en", 9)!!
        assertEquals("what's 2 plus 2", newest.said)
        assertEquals("fr", newest.heard)
        assertEquals("en", newest.replied)
        assertEquals("5", ReportTurn.of(chat, null, null, null, 9)!!.answer)
        assertNull(ReportTurn.of(chat, "u2", null, null, 9))
        assertNull(ReportTurn.of(chat, "gone", null, null, 9))
        assertNull(ReportTurn.of(emptyList(), null, null, null, 9))
        // Review L2: a reply stopped before any words or actions is still a turn (no answer), from the bug button and by its id;
        // a blank reply still being written is not, nor an older stopped blank one when the bug button picks the newest.
        val stopped = chat + ChatTurn("u3", TurnRole.USER, "tell me a long story", 5) + ChatTurn("a3", TurnRole.ASSISTANT, "", 6, stopped = true)
        for (t in listOf(ReportTurn.of(stopped, null, null, null, 9)!!, ReportTurn.of(stopped, "a3", null, null, 9)!!)) {
            assertEquals("tell me a long story", t.said)
            assertNull(t.answer)
        }
        assertEquals("5", ReportTurn.of(chat + ChatTurn("u3", TurnRole.USER, "and 3 plus 3", 5) + ChatTurn("a3", TurnRole.ASSISTANT, "", 6), null, null, null, 9)!!.answer)
        assertEquals("Six.", ReportTurn.of(stopped + ChatTurn("u4", TurnRole.USER, "3 plus 3", 7) + ChatTurn("a4", TurnRole.ASSISTANT, "Six.", 8), null, null, null, 9)!!.answer)
        val langs = ReportLangs()
        langs.asked("fr", null)
        langs.asked("en", "fr")
        assertEquals("en", langs.heardLast)
    }
}
