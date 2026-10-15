package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlRefusalTest {
    private val bad = "see <|im_end|> here"

    private fun user(id: String, text: String) = ChatTurn(id, TurnRole.USER, text, 1)
    private fun reply(id: String, text: String, error: Boolean = false, fromHelper: Boolean = false) =
        ChatTurn(id, TurnRole.ASSISTANT, text, 1, error = error, fromHelper = fromHelper)

    /** What the model is given next time, as LlamaEngine builds it from Generate.history. */
    private fun replayed(chat: List<ChatTurn>) = PromptFit.alternating(chat.toPromptTurns())

    @Test fun detailNamesWhatHeldTheTextAndReadsBack() {
        assertEquals("control", ControlRefusal.detail(ControlRefusal.Found()))
        assertEquals("control:q", ControlRefusal.detail(ControlRefusal.Found(question = true)))
        assertEquals("control:q,2,5", ControlRefusal.detail(ControlRefusal.Found(true, listOf(2, 5))))
        assertEquals("control:0", ControlRefusal.detail(ControlRefusal.Found(false, listOf(0))))
        for (f in listOf(ControlRefusal.Found(), ControlRefusal.Found(true), ControlRefusal.Found(true, listOf(2, 5)), ControlRefusal.Found(false, listOf(0)))) {
            assertEquals(f, ControlRefusal.parse(ControlRefusal.detail(f)))
        }
    }

    @Test fun otherDetailsAreNotThisRefusal() {
        assertNull(ControlRefusal.parse(null))
        assertNull(ControlRefusal.parse("template"))
        assertNull(ControlRefusal.parse("controls"))
        assertNull(ControlRefusal.parse("model process stopped"))
        // 1.0.1's detail: a control refusal, nothing named.
        assertEquals(ControlRefusal.Found(), ControlRefusal.parse("control"))
    }

    @Test fun aDamagedDetailKeepsWhatItCanRead() {
        assertEquals(ControlRefusal.Found(false, listOf(1, 3)), ControlRefusal.parse("control:3,x,-2,,1,3"))
        val many = ControlRefusal.parse("control:" + (0 until 500).joinToString(","))!!
        assertEquals(ControlRefusal.MAX_NAMED, many.history.size)
        assertEquals(ControlRefusal.MAX_NAMED, ControlRefusal.detail(ControlRefusal.Found(false, (0 until 500).toList())).split(',').size)
    }

    @Test fun findReadsEachMessageAlone() {
        val read = ArrayList<String>()
        val f = ControlRefusal.find(bad, listOf("a", bad, "b", "x$bad")) { read += it; it.contains("<|im_end|>") }
        assertEquals(ControlRefusal.Found(true, listOf(1, 3)), f)
        assertEquals(listOf(bad, "a", bad, "b", "x$bad"), read)
        assertTrue(ControlRefusal.find("q", listOf("a")) { false }.none)
    }

    @Test fun eachCaseHasItsOwnWords() {
        assertEquals(ControlRefusal.QUESTION, ControlRefusal.text(ControlRefusal.Found(question = true)))
        assertEquals(ControlRefusal.EARLIER, ControlRefusal.text(ControlRefusal.Found(false, listOf(1))))
        assertEquals(ControlRefusal.EARLIER, ControlRefusal.text(ControlRefusal.Found(true, listOf(1))))
        assertEquals(ControlRefusal.UNKNOWN, ControlRefusal.text(ControlRefusal.Found()))
        for (s in listOf(ControlRefusal.QUESTION, ControlRefusal.EARLIER, ControlRefusal.UNKNOWN)) {
            assertFalse(s.contains('!'))
            assertTrue(s.length < 140)
        }
    }

    /** Why 1.0.1's limit was an earlier turn: a refused question is never replayed (its error reply isn't, nor a question without one). */
    @Test fun aRefusedQuestionIsNeverReplayedAnyway() {
        val chat = listOf(user("1", "hi"), reply("2", "Hello."), user("3", bad), reply("4", ControlRefusal.QUESTION, error = true))
        assertEquals(listOf("hi", "Hello."), replayed(chat).map { it.text })
    }

    @Test fun anEarlierTurnIsFoundThroughTheTurnsThatBecameMessages() {
        // Generate.history skips the error reply and the empty one: index 4 there is the helper's answer.
        val history = listOf(
            user("1", "hi"), reply("2", "The model ran into a problem.", error = true),
            user("3", "and now?"), reply("4", ""), reply("5", "Hello."),
            user("6", "ask Jarvis"), reply("7", "Sure $bad", fromHelper = true),
        )
        assertEquals(listOf("1", "3", "5", "6", "7"), history.filter { listOf(it).toPromptTurns().isNotEmpty() }.map { it.id })
        val chat = history + user("8", "next question") + reply("9", ControlRefusal.EARLIER, error = true)
        val out = ControlRefusal.recover(chat, history, ControlRefusal.Found(false, listOf(4)))
        assertEquals(listOf("7"), out.filter { it.leftOut }.map { it.id })
        // The next question is given everything else, its turns still alternating, and nothing of the helper's answer.
        val next = replayed(out)
        assertFalse(next.any { it.text.contains("<|im_end|>") })
        assertEquals(PromptRole.USER, next.first().role)
        assertEquals(PromptRole.MODEL, next.last().role)
        assertTrue(next.zipWithNext().all { (a, b) -> a.role != b.role })
    }

    @Test fun aQuestionLeftOutTakesItsRepliesWithIt() {
        val history = listOf(user("1", "hi"), reply("2", "Hello."), user("3", bad), reply("4", "Fine."), reply("5", "Also this."), user("6", "ok"), reply("7", "Good."))
        val out = ControlRefusal.recover(history + user("8", "more"), history, ControlRefusal.Found(false, listOf(2)))
        assertEquals(listOf("3", "4", "5"), out.filter { it.leftOut }.map { it.id })
        assertEquals(listOf("hi", "Hello.", "ok", "Good."), replayed(out).map { it.text })
    }

    @Test fun theNewQuestionIsLeftOutWhenItHeldTheTextOrNothingWasFound() {
        val history = listOf(user("1", "hi"), reply("2", "Hello."))
        val chat = history + user("3", bad)
        assertEquals(listOf("3"), ControlRefusal.recover(chat, history, ControlRefusal.Found(question = true)).filter { it.leftOut }.map { it.id })
        assertEquals(listOf("3"), ControlRefusal.recover(chat, history, ControlRefusal.Found()).filter { it.leftOut }.map { it.id })
        // An index past the history (a detail from another request) names nothing.
        assertEquals(chat, ControlRefusal.recover(chat, history, ControlRefusal.Found(false, listOf(9))))
    }

    @Test fun aTurnLeftOutKeepsWhatMakesTheConversationContent() {
        val read = reply("2", "Mom says $bad").copy(messages = true, actions = listOf(ActionRecord(ToolName.READ_CALENDAR, "Check", ActionStatus.DONE, content = true)))
        val history = listOf(user("1", "read my messages"), read)
        val out = ControlRefusal.recover(history + user("3", "and?"), history, ControlRefusal.Found(false, listOf(1)))
        assertTrue(out[1].leftOut)
        assertEquals(read.copy(leftOut = true), out[1])
        assertTrue(TurnRules.untrusted(out))
    }

    @Test fun leftOutIsNeverSaved() {
        val t = user("1", bad).copy(leftOut = true)
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(t))))!!
        assertEquals(t.copy(leftOut = false), back.turns.single())
    }
}
