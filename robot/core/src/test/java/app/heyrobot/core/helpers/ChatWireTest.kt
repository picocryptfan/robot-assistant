package app.heyrobot.core.helpers

import app.heyrobot.core.ClaudeBlock
import app.heyrobot.core.ClaudeEffort
import app.heyrobot.core.ClaudeFailure
import app.heyrobot.core.ClaudePlan
import app.heyrobot.core.ClaudePrompt
import app.heyrobot.core.ClaudeTools
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.helpers.wire.ChatDone
import app.heyrobot.core.helpers.wire.ChatHistory
import app.heyrobot.core.helpers.wire.ChatOptions
import app.heyrobot.core.helpers.wire.ChatReply
import app.heyrobot.core.helpers.wire.ChatWire
import app.heyrobot.core.helpers.wire.Extra
import app.heyrobot.core.helpers.wire.Stop
import app.heyrobot.core.helpers.wire.ThinkTagFilter
import app.heyrobot.core.helpers.wire.WireError
import app.heyrobot.core.helpers.wire.WireErrors
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatWireTest {
    private val voice = ClaudePlan(ClaudeEffort.LOW, 4_000)
    private val timer = ToolSpecs.of(ToolName.SET_TIMER)
    private fun options(p: Preset, model: String = p.defaultModel, tools: Boolean = true, search: Boolean = false, zdr: Boolean = true, plan: ClaudePlan = voice) =
        ChatOptions(model, p.quirks, p.model(model)?.effort ?: p.quirks.effort, plan, if (tools) listOf(timer) else emptyList(), search,
            ClaudePrompt.system(p.defaultName, tools, search), zdr)

    /** JSON compared as values (key order in the test JVM's org.json isn't the device's). */
    private fun same(expected: String, actual: JSONObject) = assertEquals(norm(JSONObject(expected)), norm(actual))
    private fun norm(v: Any?): Any? = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().associateWith { norm(v.opt(it)) }
        is JSONArray -> (0 until v.length()).map { norm(v.opt(it)) }
        is Number -> v.toDouble()
        else -> v
    }

    private val user = JSONObject().put("role", "user").put("content", "why is the sky blue")

    @Test fun groqRequest() {
        val r = ChatWire.request(options(Presets.GROQ), listOf(user))
        same("""{"model":"openai/gpt-oss-120b","stream":true,"stream_options":{"include_usage":true},"max_completion_tokens":4000,
            "messages":[{"role":"system","content":${JSONObject.quote(ClaudePrompt.system("Groq", true, false))}},{"role":"user","content":"why is the sky blue"}],
            "tools":[{"type":"function","function":{"name":"set_timer","description":${JSONObject.quote(timer.description)},
              "parameters":{"type":"object","additionalProperties":false,"required":${JSONArray(timer.params.filter { it.required }.map { it.name })},
              "properties":${props(strict = false)}}}}],
            "tool_choice":"auto","reasoning_effort":"low"}""", r)
    }

    private fun props(strict: Boolean): String = JSONObject().apply {
        for (p in timer.params) put(p.name, JSONObject().put("type", if (strict && !p.required) JSONArray().put(p.type).put("null") else p.type).put("description", p.description))
    }.toString()

    @Test fun openRouterStrictSearchZdr() {
        val r = ChatWire.request(options(Presets.OPENROUTER, "openai/gpt-oss-120b", search = true, plan = ClaudePlan(ClaudeEffort.HIGH, 16_000)), listOf(user))
        // No stream_options (OpenRouter: deprecated, no effect), max_tokens, the strict nullable schema, its search tool, reasoning as an object, zdr.
        assertFalse(r.has("stream_options"))
        assertEquals(16_000, r.getInt("max_tokens"))
        val fn = r.getJSONArray("tools").getJSONObject(0).getJSONObject("function")
        assertTrue(fn.getBoolean("strict"))
        assertEquals(timer.params.map { it.name }, (0 until fn.getJSONObject("parameters").getJSONArray("required").length()).map { fn.getJSONObject("parameters").getJSONArray("required").getString(it) })
        same(props(strict = true), fn.getJSONObject("parameters").getJSONObject("properties"))
        same("""{"type":"openrouter:web_search","parameters":{"max_results":5}}""", r.getJSONArray("tools").getJSONObject(1))
        same("""{"effort":"high"}""", r.getJSONObject("reasoning"))
        assertFalse(r.has("reasoning_effort"))
        same("""{"zdr":true}""", r.getJSONObject("provider"))
        // Without zdr when you turned it off.
        assertFalse(ChatWire.request(options(Presets.OPENROUTER, "x", zdr = false), listOf(user)).has("provider"))
    }

    @Test fun mistralDeepSeekGeminiHome() {
        val m = ChatWire.request(options(Presets.MISTRAL), listOf(user))
        assertFalse(m.has("stream_options"))
        assertEquals(4000, m.getInt("max_tokens"))
        assertEquals("none", m.getString("reasoning_effort"))
        assertFalse(m.getJSONArray("tools").getJSONObject(0).getJSONObject("function").has("strict"))
        assertEquals("high", ChatWire.request(options(Presets.MISTRAL, plan = ClaudePlan(ClaudeEffort.MEDIUM, 16_000)), listOf(user)).getString("reasoning_effort"))
        assertEquals("max", ChatWire.request(options(Presets.DEEPSEEK, plan = ClaudePlan(ClaudeEffort.HIGH, 16_000)), listOf(user)).getString("reasoning_effort"))
        val g = ChatWire.request(options(Presets.GEMINI), listOf(user))
        assertTrue(g.has("stream_options") && g.has("tool_choice"))
        assertEquals("low", g.getString("reasoning_effort"))
        // A home server: no tool_choice (Ollama doesn't take it).
        val home = ChatWire.request(options(Presets.HOME, "qwen3.8:27b"), listOf(user))
        assertFalse(home.has("tool_choice"))
        assertTrue(home.has("tools") && home.has("stream_options"))
        // Groq's Qwen takes no effort.
        assertFalse(ChatWire.request(options(Presets.GROQ, "qwen/qwen3.8-27b"), listOf(user)).has("reasoning_effort"))
        // No tools, no search: neither tools nor tool_choice.
        val bare = ChatWire.request(options(Presets.GROQ, tools = false), listOf(user))
        assertFalse(bare.has("tools") || bare.has("tool_choice"))
        // The system prompt names the helper and leaves out what it can't do.
        val sys = bare.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(sys.startsWith("You are Groq. "))
        assertFalse(sys.contains("tools") || sys.contains("web search"))
        assertEquals(ClaudePrompt.SYSTEM, ClaudePrompt.system("Claude", tools = true, search = true))
    }

    @Test fun refusedExtrasAreDroppedInOrder() {
        val o = options(Presets.OPENROUTER, "x", search = true)
        val carried = ChatWire.extras(o)
        assertEquals(setOf(Extra.ZDR, Extra.TOOL_CHOICE, Extra.STRICT, Extra.EFFORT, Extra.SEARCH, Extra.TOOLS), carried)
        // Zero data retention is your privacy setting: Robot never drops it by itself.
        assertEquals(Extra.TOOL_CHOICE, ChatWire.nextToDrop(carried, emptySet()))
        assertEquals(Extra.STRICT, ChatWire.nextToDrop(carried, setOf(Extra.TOOL_CHOICE)))
        assertNull(ChatWire.nextToDrop(carried, carried - Extra.ZDR))
        assertNull(ChatWire.nextToDrop(carried, carried))
        val dropped = ChatWire.request(o.copy(dropped = setOf(Extra.TOOLS, Extra.EFFORT, Extra.ZDR)), listOf(user))
        // Only the search tool is left, and zero data retention stays on (even for a file that once said it was dropped).
        assertEquals(1, dropped.getJSONArray("tools").length())
        assertFalse(dropped.has("reasoning"))
        assertTrue(dropped.getJSONObject("provider").getBoolean("zdr"))
        assertFalse(ChatWire.request(o.copy(zdr = false), listOf(user)).has("provider"))
        val noTools = ChatWire.request(o.copy(dropped = setOf(Extra.TOOLS, Extra.SEARCH)), listOf(user))
        assertFalse(noTools.has("tools") || noTools.has("tool_choice"))
    }

    @Test fun aRefusalThatNamesItsPartDropsOnlyThatPart() {
        val o = options(Presets.OPENROUTER, "x", search = true)
        val carried = ChatWire.extras(o)
        assertEquals(Extra.EFFORT, ChatWire.toDrop(carried, emptySet(), "reasoning.effort", 1))
        assertEquals(Extra.TOOL_CHOICE, ChatWire.toDrop(carried, emptySet(), "tool_choice", 1))
        // Robot's one tool is tools[0]; the search tool after it.
        assertEquals(Extra.SEARCH, ChatWire.toDrop(carried, emptySet(), "tools[1]", 1))
        assertEquals(Extra.STRICT, ChatWire.toDrop(carried, emptySet(), "tools[0].function.strict", 1))
        assertEquals(Extra.STRICT, ChatWire.toDrop(carried, emptySet(), "tools[0]", 1))
        assertEquals(Extra.TOOLS, ChatWire.toDrop(carried, setOf(Extra.STRICT), "tools[0]", 1))
        assertEquals(Extra.SEARCH, ChatWire.toDrop(carried - Extra.TOOLS - Extra.STRICT, emptySet(), "tools", 0))
        // Another part of the request, or zero data retention: nothing is dropped (the request itself is wrong).
        assertNull(ChatWire.toDrop(carried, emptySet(), "messages[1].content", 1))
        assertNull(ChatWire.toDrop(carried, emptySet(), "provider.zdr", 1))
        assertNull(ChatWire.toDrop(carried, emptySet(), "model", 1))
        // A part the request didn't carry, or one already dropped: nothing.
        assertNull(ChatWire.toDrop(carried, emptySet(), "stream_options", 1))
        assertNull(ChatWire.toDrop(carried, setOf(Extra.EFFORT), "reasoning", 1))
        // No name: the order.
        assertEquals(Extra.TOOL_CHOICE, ChatWire.toDrop(carried, emptySet(), null, 1))
        // Errors carry the name of the part.
        assertEquals("tool_choice", WireErrors.parse("""{"error":{"message":"…","type":"invalid_request_error","param":"tool_choice","code":"unsupported_parameter"}}""").param)
        assertNull(WireErrors.parse("""{"error":{"message":"…","type":"invalid_request_error","param":null}}""").param)
    }

    @Test fun userMessagesAndResults() {
        val text = ChatWire.user(listOf(ClaudeBlock.Text("[Today is Monday.]"), ClaudeBlock.Text("why")))
        same("""{"role":"user","content":"[Today is Monday.]\n\nwhy"}""", text)
        val pic = ChatWire.user(listOf(ClaudeBlock.Text("what is this"), ClaudeBlock.Image(byteArrayOf(1, 2, 3))))
        same("""{"role":"user","content":[{"type":"text","text":"what is this"},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,AQID"}}]}""", pic)
        // A model that can't see: the text only.
        same("""{"role":"user","content":"what is this"}""", ChatWire.user(listOf(ClaudeBlock.Text("what is this"), ClaudeBlock.Image(byteArrayOf(1))), images = false))
        same("""{"role":"user","content":"what is this"}""", ChatWire.withoutImages(pic))
        same("""{"role":"tool","tool_call_id":"call_1","content":"{\"status\":\"done\"}"}""", ChatWire.toolResult("call_1", mapOf("status" to "done")))
    }

    private fun play(vararg payloads: String): Pair<String, ChatDone> {
        val r = ChatReply()
        val shown = StringBuilder()
        for (p in payloads) shown.append(r.add(p).text)
        val d = r.done()
        return shown.append(d.tail).toString() to d
    }

    private fun chunk(delta: String, finish: String? = null) =
        """{"id":"x","object":"chat.completion.chunk","choices":[{"index":0,"delta":$delta,"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    @Test fun openAiStyleStreamWithUsageInAFinalChunk() {
        val (shown, d) = play(
            chunk("""{"role":"assistant","content":""}"""),
            chunk("""{"content":"The sky "}"""),
            chunk("""{"content":"is blue."}"""),
            chunk("{}", "stop"),
            """{"id":"x","choices":[],"usage":{"prompt_tokens":1200,"completion_tokens":30,"prompt_tokens_details":{"cached_tokens":1000},"completion_tokens_details":{"reasoning_tokens":10}}}""",
            "[DONE]",
        )
        assertEquals("The sky is blue.", shown)
        assertEquals(Stop.END, d.stop)
        assertTrue(d.complete)
        assertEquals(HelperUsage(input = 200, output = 30, cacheRead = 1000), d.usage)
        same("""{"role":"assistant","content":"The sky is blue."}""", d.message)
    }

    @Test fun mistralThinkingChunksAreKeptButNeverShown() {
        val (shown, d) = play(
            chunk("""{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"The user asks "}]}]}"""),
            chunk("""{"content":[{"type":"thinking","thinking":[{"type":"text","text":"about the sky."}]}]}"""),
            chunk("""{"content":[{"type":"text","text":"Rayleigh "}]}"""),
            chunk("""{"content":[{"type":"text","text":"scattering."}]}"""),
            """{"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":12,"total_tokens":32}}""",
            "[DONE]",
        )
        assertEquals("Rayleigh scattering.", shown)
        assertTrue(d.thought)
        same("""{"role":"assistant","content":[{"type":"thinking","thinking":[{"type":"text","text":"The user asks about the sky."}]},
            {"type":"text","text":"Rayleigh scattering."}]}""", d.message)
        assertEquals(HelperUsage(input = 20, output = 12), d.usage)
    }

    @Test fun deepSeekReasoningContentIsEchoedWithTheCalls() {
        val (shown, d) = play(
            chunk("""{"role":"assistant","content":null,"reasoning_content":"Need a "}"""),
            chunk("""{"reasoning_content":"timer."}"""),
            chunk("""{"tool_calls":[{"index":0,"id":"call_0_abc","type":"function","function":{"name":"set_timer","arguments":""}}]}"""),
            chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"minu"}}]}"""),
            chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"tes\": 10}"}}]}"""),
            """{"choices":[{"index":0,"delta":{},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":900,"completion_tokens":40,"prompt_cache_hit_tokens":800,"prompt_cache_miss_tokens":100}}""",
            "[DONE]",
        )
        assertEquals("", shown)
        assertEquals(Stop.CALLS, d.stop)
        assertEquals(listOf("call_0_abc" to RawToolCall("set_timer", mapOf("minutes" to 10))), d.calls)
        assertEquals("Need a timer.", d.message.getString("reasoning_content"))
        assertEquals("""{"minutes": 10}""", d.message.getJSONArray("tool_calls").getJSONObject(0).getJSONObject("function").getString("arguments"))
        assertEquals(HelperUsage(input = 100, output = 40, cacheRead = 800), d.usage)
        assertFalse(d.visible)
    }

    @Test fun vllmReasoningAndOllamaWithoutIds() {
        val (shown, d) = play(
            chunk("""{"reasoning":"hmm"}"""),
            chunk("""{"content":"Hi"}"""),
            chunk("""{"tool_calls":[{"function":{"name":"set_timer","arguments":{"minutes":3}}}]}""", "stop"),
            "[DONE]",
        )
        assertEquals("Hi", shown)
        assertEquals("hmm", d.message.getString("reasoning"))
        // Ollama sends the call whole, without an id, and "stop": still a call, with a made-up id.
        assertEquals(Stop.CALLS, d.stop)
        assertEquals("call_robot_0", d.calls.single().first)
        assertEquals(RawToolCall("set_timer", mapOf("minutes" to 3)), d.calls.single().second)
    }

    @Test fun twoCallsByIndexAndBadArguments() {
        val (_, d) = play(
            chunk("""{"tool_calls":[{"index":1,"id":"b","type":"function","function":{"name":"add_note","arguments":"{\"text\":"}}]}"""),
            chunk("""{"tool_calls":[{"index":0,"id":"a","type":"function","function":{"name":"set_timer","arguments":"not json"}}]}"""),
            chunk("""{"tool_calls":[{"index":1,"function":{"arguments":"\"milk\"}"}}]}""", "tool_calls"),
        )
        assertEquals(listOf("a", "b"), d.calls.map { it.first })
        assertEquals(mapOf(ClaudeTools.BAD_JSON to true), d.calls[0].second.args)
        assertEquals("the arguments weren't valid JSON", ClaudeTools.check(timer, d.calls[0].second.args))
        assertEquals(mapOf("text" to "milk"), d.calls[1].second.args)
        assertTrue(d.complete)
    }

    @Test fun callPiecesWithoutAnIndexJoinTheirCall() {
        // A server that leaves out "index": the arguments' later pieces belong to the call they follow; a new id or name
        // starts the next call.
        val (_, d) = play(
            chunk("""{"tool_calls":[{"id":"a","type":"function","function":{"name":"set_timer","arguments":""}}]}"""),
            chunk("""{"tool_calls":[{"function":{"arguments":"{\"minutes\":"}}]}"""),
            chunk("""{"tool_calls":[{"id":"a","function":{"arguments":"5}"}}]}"""),
            chunk("""{"tool_calls":[{"id":"b","type":"function","function":{"name":"add_note","arguments":"{\"text\":\"milk\"}"}}]}"""),
            chunk("""{"tool_calls":[{"type":"function","function":{"name":"add_note","arguments":"{\"text\":\"eggs\"}"}}]}""", "tool_calls"),
        )
        assertEquals(listOf("a", "b", "call_robot_2"), d.calls.map { it.first })
        assertEquals(listOf(mapOf("minutes" to 5), mapOf("text" to "milk"), mapOf("text" to "eggs")), d.calls.map { it.second.args })
    }

    @Test fun thinkTagsSplitAnywhereNeverReachTheVoice() {
        val text = "<think>Let me think about it.</think>The answer is 4."
        for (cut in 0..text.length) for (cut2 in cut..text.length) {
            val (shown, d) = play(chunk("""{"content":${JSONObject.quote(text.substring(0, cut))}}"""),
                chunk("""{"content":${JSONObject.quote(text.substring(cut, cut2))}}"""),
                chunk("""{"content":${JSONObject.quote(text.substring(cut2))}}""", "stop"))
            assertEquals("$cut/$cut2", "The answer is 4.", shown)
            // The echo keeps the text exactly as it came.
            assertEquals(text, d.message.getString("content"))
            assertTrue(d.thought)
        }
        // A "<" that isn't a tag shows, even held back to the end.
        val f = ThinkTagFilter()
        assertEquals("a ", f.feed("a <"))
        assertEquals("<b", f.feed("b") + f.flush())
        // An unclosed block stays hidden.
        val g = ThinkTagFilter()
        assertEquals("x", g.feed("x<think>never closed") + g.flush())
    }

    @Test fun refusalLengthAndCutOff() {
        assertEquals(Stop.DECLINED, play(chunk("""{"refusal":"I can't help with that."}"""), chunk("{}", "stop")).second.stop)
        assertEquals(Stop.DECLINED, play(chunk("""{"content":"x"}""", "content_filter")).second.stop)
        assertEquals(Stop.CUT, play(chunk("""{"content":"x"}""", "length")).second.stop)
        // No finish reason and no [DONE]: cut off, never a finished answer.
        assertFalse(play(chunk("""{"content":"half an ans"}""")).second.complete)
    }

    @Test fun errorsInsideTheStreamAndInBodies() {
        // OpenRouter after a 200: an error chunk.
        val r = ChatReply()
        r.add(chunk("""{"content":"Hel"}"""))
        r.add("""{"id":"x","object":"chat.completion.chunk","error":{"code":502,"message":"Provider disconnected","metadata":{"error_type":"provider_error"}},"choices":[]}""")
        assertEquals(WireError("provider_error", "502"), r.error)
        // Bodies: OpenAI, Google's list (unverified shape), Ollama, FastAPI, Mistral.
        assertEquals(WireError("insufficient_quota", "insufficient_quota"), WireErrors.parse("""{"error":{"message":"…","type":"insufficient_quota","code":"insufficient_quota"}}"""))
        assertEquals(ClaudeFailure.NO_CREDITS, WireErrors.parse("""{"error":{"message":"…","type":"insufficient_quota","code":"insufficient_quota"}}""").failure(429))
        assertEquals(WireError("INVALID_ARGUMENT", "400"), WireErrors.parse("""[{"error":{"code":400,"message":"…","status":"INVALID_ARGUMENT"}}]"""))
        assertEquals(WireError(null, null), WireErrors.parse("""{"error":"model 'x' not found"}"""))
        assertEquals(ClaudeFailure.MODEL_UNAVAILABLE, WireErrors.parse("""{"error":"model 'x' not found"}""").failure(404))
        assertEquals(WireError(null, null), WireErrors.parse("""{"detail":"Not Found"}"""))
        assertEquals(WireError("invalid_request_error", "3051"), WireErrors.parse("""{"object":"error","message":"…","type":"invalid_request_error","code":3051}"""))
        assertEquals(WireError(null, null), WireErrors.parse("<html>502 Bad Gateway</html>"))
        assertEquals(WireError(null, null), WireErrors.parse(null))
    }

    @Test fun openRouterCostAndCitations() {
        val (_, d) = play(
            chunk("""{"content":"Sunny.","annotations":[{"type":"url_citation","url_citation":{"url":"https://weather.example/today","title":"t"}}]}"""),
            """{"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":100,"completion_tokens":5,"cost":0.0042}}""",
        )
        assertEquals(listOf("https://weather.example/today"), d.sources)
        assertEquals(0.0042, d.usage!!.reported!!, 0.0)
        // Groq's usage under x_groq.
        val (_, g) = play("""{"choices":[{"index":0,"delta":{"content":"x"},"finish_reason":"stop"}],"x_groq":{"usage":{"prompt_tokens":7,"completion_tokens":1}}}""")
        assertEquals(HelperUsage(input = 7, output = 1), g.usage)
    }

    @Test fun historyRules() {
        fun u(t: String = "q") = JSONObject().put("role", "user").put("content", t)
        fun a(vararg ids: String) = JSONObject().put("role", "assistant").put("content", "x").apply {
            if (ids.isNotEmpty()) put("tool_calls", JSONArray().apply { for (i in ids) put(JSONObject().put("id", i).put("type", "function")) })
        }
        fun t(id: String) = JSONObject().put("role", "tool").put("tool_call_id", id).put("content", "{}")
        assertNull(ChatHistory.check(listOf(u())))
        assertNull(ChatHistory.check(listOf(u(), a(), u())))
        assertNull(ChatHistory.check(listOf(u(), a("1", "2"), t("2"), t("1"))))
        assertNull(ChatHistory.check(listOf(u(), a("1"), t("1"), u("next"))))
        assertEquals("empty", ChatHistory.check(emptyList()))
        assertEquals("first_not_user", ChatHistory.check(listOf(a())))
        assertEquals("tool_result_missing", ChatHistory.check(listOf(u(), a("1", "2"), t("1"))))
        assertEquals("tool_result_missing", ChatHistory.check(listOf(u(), a("1"), u())))
        assertEquals("tool_result_unasked", ChatHistory.check(listOf(u(), a("1"), t("1"), t("3"))))
        assertEquals("tool_use_unanswered", ChatHistory.check(listOf(u(), a("1"))))
        assertEquals("ends_with_assistant", ChatHistory.check(listOf(u(), a())))
        assertEquals("roles_repeat", ChatHistory.check(listOf(u(), a(), a(), u())))
        assertEquals("tool_use_repeated", ChatHistory.check(listOf(u(), a("1", "1"), t("1"), t("1"))))
    }
}
