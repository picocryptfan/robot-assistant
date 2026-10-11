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
        // The conversation's tools travel with the request; an unknown id is never described.
        val withTools = gen.copy(tools = listOf("set_timer", "send_text", "launch_rocket"))
        assertEquals(withTools, roundTrip(BrainProtocol.REQ_GENERATE, withTools))
        assertEquals(listOf(ToolName.SET_TIMER, ToolName.SEND_TEXT), withTools.toolNames)
        // An older request without the list: the phase 1 tools.
        val old = BrainMsg.decode(BrainProtocol.REQ_GENERATE, """{"convId":"c","system":"s","text":"t"}""") as BrainMsg.Generate
        assertEquals(Prompt.CORE_TOOLS, old.toolNames)
        assertEquals(BrainMsg.Load("/data/x.litertlm"), roundTrip(BrainProtocol.REQ_LOAD, BrainMsg.Load("/data/x.litertlm")))
        // 0.4.1: the model, where it runs and its context travel with the load.
        val e4b = BrainMsg.Load("/data/e4b.litertlm", BrainModel.SMARTER, ModelBackend.CPU, 4096)
        assertEquals(e4b, roundTrip(BrainProtocol.REQ_LOAD, e4b))
        assertEquals(e4b, BrainMsg.Load("/data/e4b.litertlm", BrainModel.SMARTER))
        val own = BrainMsg.Load("/data/own_model.litertlm", BrainModel.OWN, ModelBackend.TPU, 4096)
        assertEquals(own, roundTrip(BrainProtocol.REQ_LOAD, own))
        // 0.4.3: your own short-context file, with its own context (:brain's LoadCheck decides whether it may).
        val short = BrainMsg.Load("/data/own_model.litertlm", BrainModel.OWN, ModelBackend.CPU, 2048)
        assertEquals(short, roundTrip(BrainProtocol.REQ_LOAD, short))
        assertEquals(BrainMsg.Cancel(42), roundTrip(BrainProtocol.REQ_CANCEL, BrainMsg.Cancel(42)))
        assertEquals(BrainMsg.CloseConv("c-1"), roundTrip(BrainProtocol.REQ_CLOSE_CONV, BrainMsg.CloseConv("c-1")))
        assertEquals(BrainMsg.Empty, roundTrip(BrainProtocol.REQ_STATUS, BrainMsg.Empty))
        val tr = BrainMsg.ToolResult("c-1", listOf("set_timer" to mapOf("status" to "done", "detail" to "Timer set"), "flashlight" to mapOf("status" to "failed")))
        assertEquals(tr, roundTrip(BrainProtocol.REQ_TOOL_RESULT, tr))
    }

    /** A load without the model (0.4.0's) is Fast's on the TPU; an unknown model or backend isn't understood at all. */
    @Test fun loadDefaultsAndRefusals() {
        val old = BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/data/x.litertlm"}""") as BrainMsg.Load
        assertEquals(BrainModel.FAST, old.model)
        assertEquals(ModelBackend.TPU, old.backend)
        assertEquals(4096, old.maxTokens)
        assertNull(BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/x","model":"e4b_tpu"}"""))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/x","model":"e4b_cpu","backend":"gpu"}"""))
        val j = BrainMsg.Load("/x", BrainModel.SMARTER).toJson()
        assertEquals(listOf("e4b_cpu", "cpu", "4096"), listOf(j.getString("model"), j.getString("backend"), j.getInt("maxTokens").toString()))
    }

    /**
     * 0.4.5: the kind of file travels with the load (a GGUF file runs with llama.cpp), and is what :brain says it holds; an
     * older load without it is a LiteRT-LM file, an unknown kind isn't understood. The thinking switch travels with each
     * question (off when absent).
     */
    @Test fun ggufLoadsAndTheThinkingSwitchTravel() {
        val gguf = BrainMsg.Load("/data/own_model.gguf", BrainModel.OWN, ModelBackend.CPU, 4096, ModelFormat.GGUF)
        assertEquals(gguf, roundTrip(BrainProtocol.REQ_LOAD, gguf))
        assertEquals("gguf", gguf.toJson().getString("format"))
        assertEquals(ModelFormat.LITERT_LM, (BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/x","model":"own","backend":"cpu"}""") as BrainMsg.Load).format)
        assertEquals(ModelFormat.LITERT_LM, BrainMsg.Load("/x").format)
        assertNull(BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/x","model":"own","backend":"cpu","format":"onnx"}"""))
        val st = BrainMsg.Status(true, false, false, false, BrainModel.OWN, ModelBackend.CPU, 2048, ModelFormat.GGUF)
        assertEquals(st, roundTrip(BrainProtocol.EV_STATUS, st))
        val ld = BrainMsg.Loaded(true, false, 2100, null, BrainModel.OWN, ModelBackend.CPU, null, ModelFormat.GGUF)
        assertEquals(ld, roundTrip(BrainProtocol.EV_LOADED, ld))
        assertNull((roundTrip(BrainProtocol.EV_STATUS, BrainMsg.Status(false, false, false, false)) as BrainMsg.Status).format)
        val gen = BrainMsg.Generate("c", "s", emptyList(), 0, "t", false, think = true)
        assertEquals(gen, roundTrip(BrainProtocol.REQ_GENERATE, gen))
        assertEquals(false, (BrainMsg.decode(BrainProtocol.REQ_GENERATE, """{"convId":"c","system":"s","text":"t"}""") as BrainMsg.Generate).think)
    }

    @Test fun eventsRoundTrip() {
        val events = listOf(
            BrainProtocol.EV_STATUS to BrainMsg.Status(loaded = true, vision = false, constrained = true, busy = false),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(true, true, 1800, null),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(false, false, 12, "The model file is missing."),
            BrainProtocol.EV_STATUS to BrainMsg.Status(true, true, true, false, BrainModel.UNCENSORED, ModelBackend.CPU),
            BrainProtocol.EV_STATUS to BrainMsg.Status(false, false, true, false, null, null),
            BrainProtocol.EV_STATUS to BrainMsg.Status(true, true, false, false, BrainModel.OWN, ModelBackend.CPU, 2048),
            BrainProtocol.EV_STATUS to BrainMsg.Status(true, false, true, false, BrainModel.FAST, ModelBackend.TPU, 4096),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(true, true, 8900, null, BrainModel.SMARTER, ModelBackend.CPU),
            BrainProtocol.EV_LOADED to BrainMsg.Loaded(false, false, 3, "Not enough free space for its cache.", BrainModel.OWN, ModelBackend.CPU, LoadFailure.NO_SPACE),
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
