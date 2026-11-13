// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.AppMissed
import app.heyrobot.core.messages.MissedAnswer
import app.heyrobot.core.messages.MissedSummary
import app.heyrobot.core.messages.SenderKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-what-did-i-miss, finding 1: the panel's Missed answer is saved as PanelController saves a messages turn
 * (finishReply: messages = kept != null, the file's text is kept), so History and the backup bundle (which copies the
 * conversation files as they are) hold its counts and app names, never a sender, a group or a title.
 */
class PanelMissedHistoryTest {
    private val en = Langs.of(Lang.EN)
    private val a = MissedAnswer(on = true, apps = 2, access = true, countsOnly = false, list = listOf(
        AppMissed("Signal", 3, SenderKind.PERSON, "Alex Doe", 2L), AppMissed("Mail", 2, SenderKind.APP, "Invoice 4417", 1L)))

    private fun saved(answer: MissedAnswer): Pair<ChatTurn, String> {
        val shown = MissedSummary.reply(answer).render(en)
        val kept = MissedSummary.kept(answer)?.render(en)
        val turn = ChatTurn("r1", TurnRole.ASSISTANT, shown, 0L, messages = kept != null, kept = kept)
        return turn to ConversationCodec.toJson(StoredConversation("c1", 0L, 0L, listOf(ChatTurn("q1", TurnRole.USER, "what did I miss", 0L), turn)))
    }

    @Test fun aMissedTurnIsSavedAsMessagesWithNoTitle() {
        val (turn, file) = saved(a)
        assertTrue(turn.text.contains("Alex Doe") && turn.text.contains("Invoice 4417"))
        assertTrue(turn.messages)
        assertTrue(file.contains("\"messages\":true"))
        for (w in listOf("Alex", "Invoice", "4417")) assertFalse(w, file.contains(w))
        assertEquals("Since you last looked, 5 notifications: 3 in Signal and 2 in Mail.", turn.kept)
        // Read back as History and the bundle's restore do: the placeholder only.
        val back = ConversationCodec.fromJson(file)!!.turns.last()
        assertEquals(turn.kept, back.text)
        assertTrue(back.messages)
    }

    /**
     * fix-what-did-i-miss-c, D: the panel's own Missed branch applies the guard (History's counts, the taint, the echo
     * check) before it speaks. The answer comes from the main process over a Messenger and PanelMessages runs on
     * VoiceHub's context, so no JVM test reaches that branch: this reads it, and goes red if the guard goes.
     */
    @Test fun thePanelsMissedBranchKeepsTheCountsBeforeItSpeaks() {
        val root = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
        val code = File(root, "app/src/main/java/app/heyrobot/assistant/PanelMessages.kt").readText()
        val start = code.indexOf("if (cmd is MessageCommand.Missed) {")
        val end = code.indexOf("return say(replyId, reply, lang)", start)
        assertTrue("the Missed branch", start in 0 until end)
        val branch = code.substring(start, end)
        for (k in listOf("MissedSummary.kept(a)?.let { k ->", "kept[replyId] = k.render(langs(lang))", "taint.conversations + MISSED", "saidAloud(reply.render(langs(lang)))"))
            assertTrue(k, branch.contains(k))
    }

    @Test fun anAnswerWithNothingFromAnotherAppIsAnOrdinaryTurn() {
        assertNull(MissedSummary.kept(a.copy(countsOnly = true, list = a.list.map { it.copy(title = "") })))
        assertNull(MissedSummary.kept(a.copy(list = emptyList())))
        assertNull(MissedSummary.kept(a.copy(access = false)))
        assertFalse(saved(a.copy(list = a.list.map { it.copy(title = "") })).first.messages)
    }
}
