package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainProtocolTest {
    private fun <T : BrainMsg> roundTrip(what: Int, m: T): BrainMsg? = BrainMsg.decode(what, m.toJson().toString())

    @Test fun requestsRoundTrip() {
        val gen = BrainMsg.Generate(
            convId = "c-1", system = "sys \"quoted\"\nline",
            history = listOf(PromptTurn(PromptRole.USER, "q1"), PromptTurn(PromptRole.MODEL, "a1 — üml")),
            historyCount = 3, text = "what now?", hasImage = true,
        )
        assertEquals(gen, roundTrip(BrainProtocol.REQ_GENERATE, gen))
        assertEquals(BrainMsg.Load("/data/x.litertlm"), roundTrip(BrainProtocol.REQ_LOAD, BrainMsg.Load("/data/x.litertlm")))
        assertEquals(BrainMsg.Cancel(42), roundTrip(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(42)))
        assertEquals(BrainMsg.CloseConv("c-1"), roundTrip(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv("c-1")))
        assertEquals(BrainMsg.Empty, roundTrip(BrainProtocol.REQ_STATUS, BrainMsg.Empty))
        val tr = BrainMsg.ToolResult("c-1", listOf("set_timer" to mapOf("status" to "done", "detail" to "Timer set"), "flashlight" to mapOf("status" to "failed")))
        assertEquals(tr, roundTrip(BrainProtocol.REQ_TOOL_RESULT, tr))
    }

    @Test fun eventsRoundTrip() {
        val events = listOf(
            BrainProtocol.EV_STATUS to BrainMsg.Status(loaded = true, vision = false, constrained = true, busy = false),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(true, true, 1800, null),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(false, false, 12, "The model file is missing."),
            BrainProtocol.EV_CHUNK to BrainMsg.Chunk("Hello, "),
            BrainProtocol.EV_CHUNK to BrainMsg.Chunk("", thought = true),
            BrainProtocol.EV_DONE to BrainMsg.Done(DoneReason.TOOL_CALLS, 812, 260, 17.4, true),
            BrainProtocol.EV_ERROR to BrainMsg.Error(BrainError.NO_VISION, null),
            BrainProtocol.EV_ERROR to BrainMsg.Error(BrainError.ENGINE, "LiteRtLmJniException"),
        )
        for ((what, m) in events) assertEquals(m, roundTrip(what, m))
    }

    @Test fun toolCallArgumentsKeepTheirTypes() {
        val m = BrainMsg.ToolCallsMsg(listOf(
            // 30.5, not 30.0: org.json writes a whole double as an integer.
            RawToolCall("set_timer", mapOf("minutes" to 5, "seconds" to 30.5)),
            RawToolCall("flashlight", mapOf("on" to true)),
            RawToolCall("open_app", mapOf("name" to "Signal", "extra" to null, "nested" to mapOf("a" to listOf(1, "b")))),
        ))
        val back = roundTrip(BrainProtocol.EV_TOOL_CALLS, m) as BrainMsg.ToolCallsMsg
        assertEquals(m, back)
        assertEquals(Action.SetTimer(331), (ToolCalls.parse(back.calls[0]) as ParsedCall.Ok).action)
    }

    @Test fun nonFiniteSpeedIsEncodedAsUnknown() {
        val d = roundTrip(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.END, 1, -1, Double.NaN, false)) as BrainMsg.Done
        assertEquals(-1.0, d.decodeTps, 0.0)
    }

    @Test fun garbageDecodesToNull() {
        assertNull(BrainMsg.decode(BrainProtocol.REQ_GENERATE, "{not json"))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_GENERATE, "{}"))
        assertNull(BrainMsg.decode(999, "{}"))
        assertNull(BrainMsg.decode(BrainProtocol.EV_DONE, "{\"reason\":\"NOPE\"}"))
        assertEquals(BrainMsg.Error(BrainError.ENGINE, null), BrainMsg.decode(BrainProtocol.EV_ERROR, "{\"code\":\"WHAT\"}"))
    }

    @Test fun codesAreDistinctAndTerminalOnesAreEvents() {
        assertTrue((BrainProtocol.REQUESTS intersect BrainProtocol.EVENTS).isEmpty())
        assertTrue(BrainProtocol.EVENTS.containsAll(BrainProtocol.TERMINAL))
        assertTrue(BrainProtocol.EV_CHUNK !in BrainProtocol.TERMINAL)
        assertTrue(BrainProtocol.EV_TOOL_CALLS !in BrainProtocol.TERMINAL)
        assertEquals("app.heyrobot.brain.BrainService", BrainProtocol.SERVICE_CLASS)
    }
}
