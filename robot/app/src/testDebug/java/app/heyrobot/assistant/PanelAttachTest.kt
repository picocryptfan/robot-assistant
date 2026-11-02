// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.DoneReason
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.TurnRole
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.ReplyVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A file attached in the panel (chat-files): what its question's answer leaves on the reply, a text file or a PDF's words alike. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelAttachTest {
    private val state = MutableStateFlow(PanelState("c", turns = listOf(UiTurn("r", TurnRole.ASSISTANT, "", streaming = true))))
    private val replies = PanelReplies(state)
    private val voice = ReplyVoice().also { it.start("r") }

    private inner class Port(private val pieces: List<String>, private val end: OneShotResult) : PanelFileAnswer.Port {
        var finished: DoneReason? = null
        var failed: String? = null
        val notes = mutableListOf<String>()
        override val ctx: Context get() = RuntimeEnvironment.getApplication()
        override val turnLangs: Langs get() = Langs.EN
        override fun sayInto(replyId: String, text: Phrase) = replies.setText(replyId, text.en())
        override fun finishReply(replyId: String, reason: DoneReason) { finished = reason }
        override fun failReply(replyId: String, message: String) { failed = message }
        override fun speakSentence(s: String) {}
        override fun privateAnswer(replyId: String) {}
        override fun noteRead(text: String) { notes += text }
        override val contextTokens: Int get() = 4_096
        override suspend fun ask(req: BrainMsg.OneShot, onText: (String) -> Unit): OneShotResult {
            var soFar = ""
            for (p in pieces) { soFar += p; onText(soFar) }
            return end
        }
    }

    private val answer = "The third line says the blue kettle sits on the second shelf."
    private val pieces = answer.chunked(4)
    private val lines = "The red bucket.\nThe green ladder.\nThe blue kettle sits on the second shelf.\n"

    private fun reply() = state.value.turns.single { it.id == "r" }

    private fun run(port: Port, held: PanelAttach.Held) = runBlocking { PanelAttach(state, replies, voice, port).answer("r", "what's on the third line?", held) }

    @Test fun aShortFastAnswerToATextFileIsLeftOnTheReply() {
        val port = Port(pieces, OneShotResult.Done(answer, DoneReason.END))
        run(port, PanelAttach.Held("cf-three-lines.txt", lines, false, null))
        assertEquals(answer, reply().text)
        assertEquals(DoneReason.END, port.finished)
        assertNull(port.failed)
        assertEquals(listOf(answer), port.notes)
    }

    @Test fun aPdfsWordsGoTheSameWay() {
        val port = Port(pieces, OneShotResult.Done(answer, DoneReason.END))
        run(port, PanelAttach.Held("cf-two-pages.pdf", lines, false, null))
        assertEquals(answer, reply().text)
        assertEquals(DoneReason.END, port.finished)
    }

    @Test fun aFileReadOnlyInPartSaysSoAboveTheAnswer() {
        val port = Port(pieces, OneShotResult.Done(answer, DoneReason.END))
        run(port, PanelAttach.Held("cf-long.pdf", lines, true, null))
        assertTrue(reply().text, reply().text.endsWith("\n\n$answer"))
        assertEquals(DoneReason.END, port.finished)
    }

    @Test fun noWordsFromTheModelFailInWordsRatherThanLeaveNothing() {
        val port = Port(emptyList(), OneShotResult.Done("", DoneReason.END))
        run(port, PanelAttach.Held("cf-three-lines.txt", lines, false, null))
        assertTrue(port.failed!!.isNotBlank())
        assertNull(port.finished)
    }

    @Test fun aRequestThatGaveWayStartsOverWithoutTheOldWords() {
        val port = object : PanelFileAnswer.Port by Port(emptyList(), OneShotResult.Done("", DoneReason.END)) {
            var finished: DoneReason? = null
            override fun finishReply(replyId: String, reason: DoneReason) { finished = reason }
            override suspend fun ask(req: BrainMsg.OneShot, onText: (String) -> Unit): OneShotResult {
                onText("Something else entirely")
                onText("")
                onText(answer)
                return OneShotResult.Done(answer, DoneReason.END)
            }
        }
        runBlocking { PanelAttach(state, replies, voice, port).answer("r", "q", PanelAttach.Held("a.txt", lines, false, null)) }
        assertEquals(answer, reply().text)
        assertEquals(DoneReason.END, port.finished)
    }
}
