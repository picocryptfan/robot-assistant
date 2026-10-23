// SPDX-License-Identifier: GPL-3.0-only
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
        assertNull(BrainMsg.decode(BrainProtocol.REQ_LOAD, """{"path":"/x","model":"e4b_cpu","backend":"vulkan"}"""))
        // 0.4.7: the general E2B on the GPU goes across as it is.
        val gpu = BrainMsg.Load("/data/gemma-4-E2B-it.litertlm", BrainModel.GENERAL, ModelBackend.GPU, 4096)
        assertEquals(gpu, BrainMsg.decode(BrainProtocol.REQ_LOAD, gpu.toJson().toString()))
        assertEquals(listOf("e2b", "gpu"), listOf(gpu.toJson().getString("model"), gpu.toJson().getString("backend")))
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

    /** 1.2 (job 4): a slow path's compact form and reading budget cross with the request; a fast path's request is as before. */
    @Test fun theReadingBudgetCrossesWithTheRequest() {
        val fast = BrainMsg.Generate("c", "s", listOf(PromptTurn(PromptRole.USER, "q"), PromptTurn(PromptRole.MODEL, "a")), 2, "t", false)
        // The same keys as before 1.2: nothing new in a fast path's JSON.
        assertEquals(setOf("convId", "system", "historyCount", "text", "hasImage", "history", "tools", "think"), fast.toJson().keys().asSequence().toSet())
        assertEquals(false, fast.compact)
        assertEquals(-1, fast.readBudget)
        assertEquals(fast, roundTrip(BrainProtocol.REQ_GENERATE, fast))
        val slow = fast.copy(compact = true, readBudget = 856)
        assertEquals(slow, roundTrip(BrainProtocol.REQ_GENERATE, slow))
        assertEquals(true, slow.toJson().getBoolean("compact"))
        assertEquals(856, slow.toJson().getInt("readBudget"))
        // A budget of 0 is a budget (the floor of history still applies in :brain), not none.
        assertEquals(0, (roundTrip(BrainProtocol.REQ_GENERATE, fast.copy(readBudget = 0)) as BrainMsg.Generate).readBudget)
        // Missing, negative or not a number: none; a compact switch that isn't true: the full form.
        fun dec(extra: String) = BrainMsg.decode(BrainProtocol.REQ_GENERATE, """{"convId":"c","system":"s","text":"t"$extra}""") as BrainMsg.Generate
        assertEquals(-1, dec("").readBudget)
        assertEquals(false, dec("").compact)
        assertEquals(-1, dec(""","readBudget":-500""").readBudget)
        assertEquals(-1, dec(""","readBudget":"lots"""").readBudget)
        assertEquals(false, dec(""","compact":"yes"""").compact)
        assertEquals(true, dec(""","compact":true""").compact)
        // A request the self-test makes (core GpuCheck) is a fast path's: its turns are fixed, and read whole.
        val check = GpuCheck.request(0, emptyList())
        assertEquals(false, check.compact)
        assertEquals(-1, check.readBudget)
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
            // 0.4.5: llama.cpp's isolated process stopped under a reply.
            BrainProtocol.EV_ERROR to BrainMsg.Error(BrainError.STOPPED, "model process stopped"),
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

    /**
     * 1.2 (design Step 5): a reply's reading speed and counts cross from :brain with the rest of Done, numbers only. An
     * older :brain sends none of them: they read as unknown, and the reply itself is unchanged.
     */
    @Test fun doneCarriesTheReadingSpeed() {
        val d = BrainMsg.Done(DoneReason.END, 1630, 3120, 17.4, true, false, prefillTokens = 1502, prefillTps = 481.25, decodeTokens = 96)
        assertEquals(d, roundTrip(BrainProtocol.EV_DONE, d))
        // A 1.1 :brain's Done, as it encodes it today.
        val old = BrainMsg.decode(BrainProtocol.EV_DONE,
            """{"reason":"END","tokens":1630,"ttftMs":3120,"decodeTps":17.4,"rebuilt":true,"textCalls":false}""") as BrainMsg.Done
        assertEquals(BrainMsg.Done(DoneReason.END, 1630, 3120, 17.4, true, false), old)
        assertEquals(-1, old.prefillTokens)
        assertEquals(-1.0, old.prefillTps, 0.0)
        assertEquals(-1, old.decodeTokens)
        // Nothing but numbers rides along: these keys, no more.
        assertEquals(setOf("reason", "tokens", "ttftMs", "decodeTps", "rebuilt", "textCalls", "prefillTokens", "prefillTps", "decodeTokens"),
            d.toJson().keys().asSequence().toSet())
    }

    @Test fun nonsenseSpeedsReadAsUnknown() {
        // Encoded: NaN, infinity, zero and negatives go as -1.
        for (x in listOf(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -3.0, 1e9)) {
            val d = roundTrip(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.END, 1, 1, 1.0, false, prefillTps = x)) as BrainMsg.Done
            assertEquals("$x", -1.0, d.prefillTps, 0.0)
        }
        assertEquals(-1, (roundTrip(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.END, 1, 1, 1.0, false, prefillTokens = -7)) as BrainMsg.Done).prefillTokens)
        // Decoded: whatever a payload says past the range, or not a number, is unknown too.
        val odd = BrainMsg.decode(BrainProtocol.EV_DONE,
            """{"reason":"END","prefillTokens":2000000,"prefillTps":"fast","decodeTokens":-4}""") as BrainMsg.Done
        assertEquals(-1, odd.prefillTokens)
        assertEquals(-1.0, odd.prefillTps, 0.0)
        assertEquals(-1, odd.decodeTokens)
        // llama.cpp's count and time: a zero time or an empty read is unknown, never a division by zero.
        assertEquals(500.0, BrainMsg.Done.Speed.rate(1000L, 2000L), 0.0)
        assertEquals(-1.0, BrainMsg.Done.Speed.rate(1000L, 0L), 0.0)
        assertEquals(-1.0, BrainMsg.Done.Speed.rate(0L, 100L), 0.0)
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

    @Test fun conversationModeMessagesRoundTrip() {
        // 0.5.0: conversation mode's one-shot requests, served by :brain's queue (phase 5.4).
        assertEquals(8, BrainProtocol.REQ_ONE_SHOT)
        assertEquals(108, BrainProtocol.EV_QUEUED)
        assertTrue(BrainProtocol.REQ_ONE_SHOT in BrainProtocol.REQUESTS)
        assertTrue(BrainProtocol.EV_QUEUED in BrainProtocol.EVENTS && BrainProtocol.EV_QUEUED !in BrainProtocol.TERMINAL)
        val one = BrainMsg.OneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, Prompt.TRANSLATE_FR_EN, "On se reprend-tu demain?", 120)
        assertEquals(one, roundTrip(BrainProtocol.REQ_ONE_SHOT, one))
        val map = BrainMsg.OneShot(OneShotKind.SUMMARY_MAP, Priority.SUMMARY, Prompt.SUMMARY_MAP, "Part 1 of 2 (14:02–14:10):\n[14:02] User (en): hi", 250)
        assertEquals(map, roundTrip(BrainProtocol.REQ_ONE_SHOT, map))
        assertEquals(BrainMsg.Queued(2), roundTrip(BrainProtocol.EV_QUEUED, BrainMsg.Queued(2)))
        // A cap past the reply limit is held to it; an unknown kind or priority is not a request.
        val big = BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"TRANSLATE","priority":"PANEL","system":"s","text":"t","maxChunks":99999}""") as BrainMsg.OneShot
        assertEquals(Limits.MAX_REPLY_CHUNKS, big.maxChunks)
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"POEM","priority":"PANEL","system":"s","text":"t"}"""))
        assertNull(BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"TRANSLATE","priority":"URGENT","system":"s","text":"t"}"""))
        // Panel first, summaries last.
        assertEquals(listOf(Priority.PANEL, Priority.INTERPRETER, Priority.TRANSCRIPT, Priority.SUMMARY), Priority.entries.sorted())
    }

    /**
     * 1.4 (Robot Files, plan §4.2 and §5.1): FILE_READ and FILE_ANSWER are one-shots like the others, appended to the kinds.
     * A FILE_READ of a page's picture says so with hasImage (the JPEG crosses as KEY_IMAGE, never in the JSON); no other
     * kind may, and every one-shot without a picture is the same JSON as before 1.4.
     */
    @Test fun fileOneShotsCrossAndOnlyAFileReadCarriesAPicture() {
        assertEquals(listOf("TRANSLATE", "SUMMARY_MAP", "SUMMARY_REDUCE", "WEB_ANSWER", "FILE_READ", "FILE_ANSWER"), OneShotKind.entries.map { it.name })
        val keys = setOf("kind", "priority", "system", "text", "maxChunks")
        val translate = BrainMsg.OneShot(OneShotKind.TRANSLATE, Priority.INTERPRETER, Prompt.TRANSLATE_FR_EN, "Salut", 120)
        assertEquals(keys, translate.toJson().keys().asSequence().toSet())
        assertEquals(mapOf("kind" to "TRANSLATE", "priority" to "INTERPRETER", "system" to "s", "text" to "t", "maxChunks" to 120),
            Json.toMap(translate.copy(system = "s", text = "t").toJson()))
        val picture = BrainMsg.OneShot(OneShotKind.FILE_READ, Priority.SUMMARY, "read", "[frame]", 700, hasImage = true)
        assertEquals(keys + "hasImage", picture.toJson().keys().asSequence().toSet())
        assertEquals(picture, roundTrip(BrainProtocol.REQ_ONE_SHOT, picture))
        val text = picture.copy(hasImage = false, text = "[frame]\n\"\"\"\nTOTAL 84.12\n\"\"\"")
        assertEquals(keys, text.toJson().keys().asSequence().toSet())
        assertEquals(text, roundTrip(BrainProtocol.REQ_ONE_SHOT, text))
        val answer = BrainMsg.OneShot(OneShotKind.FILE_ANSWER, Priority.PANEL, "answer", "Question: total?", 350)
        assertEquals(answer, roundTrip(BrainProtocol.REQ_ONE_SHOT, answer))
        // An older one-shot's JSON has no picture.
        assertEquals(false, (BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"FILE_READ","priority":"SUMMARY","system":"s","text":"t"}""") as BrainMsg.OneShot).hasImage)
        // A picture with any other kind isn't a request; and only JSON's true says there is one.
        for (k in OneShotKind.entries.filter { it != OneShotKind.FILE_READ })
            assertNull(k.name, BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"${k.name}","priority":"PANEL","system":"s","text":"t","hasImage":true}"""))
        for (v in listOf("\"true\"", "1", "false", "null"))
            assertEquals(v, false, (BrainMsg.decode(BrainProtocol.REQ_ONE_SHOT, """{"kind":"FILE_READ","priority":"SUMMARY","system":"s","text":"t","hasImage":$v}""") as BrainMsg.OneShot).hasImage)
        // NO_VISION is the refusal a FILE_READ's caller gets from a model that can't see.
        assertEquals(BrainMsg.Error(BrainError.NO_VISION, "text only"), roundTrip(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, "text only")))
    }
}

