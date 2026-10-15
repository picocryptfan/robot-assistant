// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun readContentIsRememberedAndCardsReplayWithoutMarkers() {
        val read = ChatTurn("7", TurnRole.ASSISTANT, "You have the dentist at 3.", 1600, actions = listOf(
            ActionRecord(ToolName.READ_CALENDAR, "Check your calendar for Sun Oct 11", ActionStatus.DONE, "1 event on Sun Oct 11.", content = true),
            ActionRecord(ToolName.SEND_TEXT, "Text **Mom** (+1 514-555-0101) in Cheogram", ActionStatus.DONE, "Opened Cheogram."),
        ))
        val c = StoredConversation("r", 1, 2, listOf(ChatTurn("6", TurnRole.USER, "calendar?", 1500), read))
        assertEquals(c, ConversationCodec.fromJson(ConversationCodec.toJson(c)))
        assertEquals("(Check your calendar for Sun Oct 11: done.) (Text Mom (+1 514-555-0101) in Cheogram: done.) You have the dentist at 3.",
            c.turns.toPromptTurns()[1].text)
        // Every later action in that conversation asks first, also after it was saved and opened again.
        assertTrue(TurnRules.untrusted(c.turns))
        assertFalse(TurnRules.untrusted(turns.filter { it.attachment == Attachment.NONE }))
        assertTrue(TurnRules.untrusted(turns))
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

    @Test fun replayNeverCarriesTheClock() {
        // A user turn is stored as asked (the time goes only into the turn sent); a reply saved by 0.2.1 that began
        // with a copy of the clock is replayed without it, while a time given as an answer stays.
        val old = listOf(
            ChatTurn("1", TurnRole.USER, "What time is it?", 1000),
            ChatTurn("2", TurnRole.ASSISTANT, "[Now 14:05] It's 14:05.", 1100),
            ChatTurn("3", TurnRole.USER, "Tell me a joke", 1200),
            ChatTurn("4", TurnRole.ASSISTANT, "[Now 14:06] Why did the robot cross the road?", 1300,
                actions = listOf(ActionRecord(ToolName.SET_TIMER, "Set a timer for 5 minutes", ActionStatus.DONE))),
        )
        val p = old.toPromptTurns()
        assertEquals(listOf("What time is it?", "It's 14:05.", "Tell me a joke", "(Set a timer for 5 minutes: done.) Why did the robot cross the road?"),
            p.map { it.text })
        assertTrue(p.none { it.text.contains("[Now") || it.text.contains("Current time") })
    }

    /**
     * "Keep screenshots and photos in history": a kept thumbnail and screen text round-trip, are never replayed to the
     * model or described to Claude, and can be removed again; nothing is kept unless the turn holds it.
     */
    @Test fun keptPicturesAndScreenTextStayOutOfPromptsAndCanBeRemoved() {
        val q = ChatTurn("q", TurnRole.USER, "what is this?", 1, Attachment.SCREEN, picture = "QUJD", screenText = "Invoice 42, due Friday")
        val c = StoredConversation("m", 1, 2, listOf(q, ChatTurn("a", TurnRole.ASSISTANT, "An invoice.", 2)))
        assertEquals(c, ConversationCodec.fromJson(ConversationCodec.toJson(c)))
        val prompt = c.turns.toPromptTurns().joinToString(" ") { it.text }
        assertFalse(prompt, prompt.contains("Invoice 42"))
        assertFalse(HelperContext.transcript(c.turns)!!.contains("Invoice 42"))
        assertEquals(setOf(Personal.SCREEN), HelperContext.personal(c.turns))
        val stripped = ConversationCodec.withoutMedia(c)!!
        assertEquals(listOf<String?>(null, null), stripped.turns.map { it.picture ?: it.screenText })
        assertEquals(Attachment.SCREEN, stripped.turns[0].attachment)
        assertEquals(null, ConversationCodec.withoutMedia(stripped))
        // Nothing kept: the file has no such keys. A damaged picture is dropped on reading, never fatal.
        assertFalse(ConversationCodec.toJson(conv).contains("picture") || ConversationCodec.toJson(conv).contains("screenText"))
        val bad = ConversationCodec.toJson(c).replace("QUJD", "not base64!")
        assertEquals(null, ConversationCodec.fromJson(bad)!!.turns[0].picture)
        assertEquals(false, RobotSettings().keepMedia)
        assertEquals(true, RobotSettings.fromJson(RobotSettings(keepMedia = true).toJson().toString()).keepMedia)
    }
}
