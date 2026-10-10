package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationTest {
    private val turns = listOf(
        ChatTurn("1", TurnRole.USER, "Set a timer for five minutes please", 1000),
        ChatTurn("2", TurnRole.ASSISTANT, "Done, five minutes.", 1100,
            actions = listOf(ActionRecord(ToolName.SET_TIMER, "Set a timer for 5 minutes", ActionStatus.DONE, "Timer set for 5 minutes."))),
        ChatTurn("3", TurnRole.USER, "What's this?", 1200, attachment = Attachment.PHOTO),
        ChatTurn("4", TurnRole.ASSISTANT, "A cat, mostly", 1300, stopped = true),
        ChatTurn("5", TurnRole.USER, "and now?", 1400),
        ChatTurn("6", TurnRole.ASSISTANT, "The model ran into a problem.", 1500, error = true),
    )
    private val conv = StoredConversation("abc-123", 1000, 1500, turns)

    @Test fun codecRoundTrips() {
        assertEquals(conv, ConversationCodec.fromJson(ConversationCodec.toJson(conv)))
    }

    @Test fun badJsonIsNull() {
        assertNull(ConversationCodec.fromJson("nope"))
        assertNull(ConversationCodec.fromJson("{\"turns\":[]}"))
    }

    @Test fun unknownToolsAndRolesAreDroppedNotFatal() {
        val json = """{"id":"x","created":1,"updated":2,"turns":[{"id":"a","role":"ALIEN","text":"?"},
            {"id":"b","role":"ASSISTANT","text":"ok","actions":[{"tool":"launch_rocket","summary":"s","status":"DONE"}]}]}"""
        val c = ConversationCodec.fromJson(json)!!
        assertEquals(1, c.turns.size)
        assertEquals(emptyList<ActionRecord>(), c.turns[0].actions)
    }

    @Test fun titleAndPreview() {
        assertEquals("Set a timer for five minutes please", conv.title)
        assertEquals("A cat, mostly", conv.preview)
        val long = StoredConversation("x", 0, 0, listOf(ChatTurn("1", TurnRole.USER, "word ".repeat(40), 0)))
        assertEquals(58, long.title.length)
        assertEquals("Photo", StoredConversation.titleOf(listOf(ChatTurn("1", TurnRole.USER, "", 0, Attachment.PHOTO))))
        assertEquals("New conversation", StoredConversation.titleOf(emptyList()))
    }

    @Test fun promptHistoryReplaysTextOnly() {
        val p = turns.toPromptTurns()
        assertEquals(5, p.size)
        assertEquals(PromptTurn(PromptRole.USER, "Set a timer for five minutes please"), p[0])
        assertEquals(PromptTurn(PromptRole.MODEL, "(Set a timer for 5 minutes: done.) Done, five minutes."), p[1])
        assertEquals(PromptTurn(PromptRole.USER, "[I showed you a photo] What's this?"), p[2])
        // Errors are not replayed; the question before one is.
        assertEquals(PromptTurn(PromptRole.USER, "and now?"), p[4])
    }
}
