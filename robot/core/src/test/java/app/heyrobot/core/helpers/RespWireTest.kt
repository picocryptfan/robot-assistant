// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperEffort
import app.heyrobot.core.HelperFailure
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperPrompt
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.helpers.wire.ChatOptions
import app.heyrobot.core.helpers.wire.ChatWire
import app.heyrobot.core.helpers.wire.Extra
import app.heyrobot.core.helpers.wire.RespDone
import app.heyrobot.core.helpers.wire.RespHistory
import app.heyrobot.core.helpers.wire.RespReply
import app.heyrobot.core.helpers.wire.RespWire
import app.heyrobot.core.helpers.wire.Stop
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RespWireTest {
    private val timer = ToolSpecs.of(ToolName.SET_TIMER)
    private fun options(p: Preset = Presets.OPENAI, search: Boolean = true) = ChatOptions(p.defaultModel, p.quirks, p.quirks.effort, HelperPlan(HelperEffort.HIGH, 16_000),
        listOf(timer), search, HelperPrompt.system(p.defaultName, true, search))

    @Test fun requestIsStatelessWithToolsSearchAndEffort() {
        val r = RespWire.request(options(), listOf(RespWire.user(listOf(HelperBlock.Text("hi")))))
        assertEquals("gpt-6.1-sol", r.getString("model"))
        assertTrue(r.getBoolean("stream"))
        // Nothing kept by OpenAI for later retrieval: the whole conversation goes each time.
        assertFalse(r.getBoolean("store"))
        assertFalse(r.has("previous_response_id"))
        // So the reasoning items can go back with nothing stored: their encrypted content is asked for.
        assertEquals("reasoning.encrypted_content", r.getJSONArray("include").getString(0))
        assertFalse(RespWire.request(options().copy(dropped = setOf(Extra.ENCRYPTED_REASONING)), emptyList()).has("include"))
        assertTrue(r.getString("instructions").startsWith("You are ChatGPT. "))
        assertEquals(16_000, r.getInt("max_output_tokens"))
        assertEquals("high", r.getJSONObject("reasoning").getString("effort"))
        val tools = r.getJSONArray("tools")
        val fn = tools.getJSONObject(0)
        assertEquals("function", fn.getString("type"))
        assertEquals("set_timer", fn.getString("name"))
        assertTrue(fn.getBoolean("strict"))
        assertFalse(fn.getJSONObject("parameters").getBoolean("additionalProperties"))
        assertEquals("web_search", tools.getJSONObject(1).getString("type"))
        assertEquals("auto", r.getString("tool_choice"))
        assertEquals(setOf(Extra.EFFORT, Extra.TOOL_CHOICE, Extra.TOOLS, Extra.SEARCH, Extra.STRICT, Extra.ENCRYPTED_REASONING), RespWire.extras(options()))
        // Dropped last when a server names nothing, and at once when it names it.
        assertEquals(Extra.TOOL_CHOICE, ChatWire.toDrop(RespWire.extras(options()), emptySet(), null, 1))
        assertEquals(Extra.ENCRYPTED_REASONING, ChatWire.toDrop(RespWire.extras(options()), emptySet(), "include[0]", 1))
        // xAI: the same shape, its own model.
        assertEquals("grok-4.7", RespWire.request(options(Presets.XAI), emptyList()).getString("model"))
        val user = RespWire.user(listOf(HelperBlock.Text("what is this"), HelperBlock.Image(byteArrayOf(1, 2, 3))))
        assertEquals("input_image", user.getJSONArray("content").getJSONObject(1).getString("type"))
        assertEquals("data:image/jpeg;base64,AQID", user.getJSONArray("content").getJSONObject(1).getString("image_url"))
        assertEquals(1, RespWire.withoutImages(user).getJSONArray("content").length())
        assertEquals("""{"status":"done"}""", RespWire.toolResult("call_1", mapOf("status" to "done")).getString("output"))
    }

    private fun play(vararg events: String): Pair<String, RespDone> {
        val r = RespReply()
        val shown = StringBuilder()
        for (e in events) shown.append(r.add(e).text)
        return shown.toString() to r.done()
    }

    private val reasoning = """{"type":"reasoning","id":"rs_1","summary":[],"encrypted_content":"gAAAAB-secret"}"""
    private val message = """{"type":"message","id":"msg_1","role":"assistant","status":"completed","content":[{"type":"output_text","text":"Blue light scatters more.",
        "annotations":[{"type":"url_citation","url":"https://nasa.gov/sky","title":"t","start_index":0,"end_index":4}]}]}"""

    @Test fun textReasoningEchoUsageAndSources() {
        val (shown, d) = play(
            """{"type":"response.created","response":{"id":"resp_1","status":"in_progress"}}""",
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"reasoning","id":"rs_1"}}""",
            """{"type":"response.output_item.done","output_index":0,"item":$reasoning}""",
            """{"type":"response.output_item.added","output_index":1,"item":{"type":"web_search_call","id":"ws_1","status":"in_progress"}}""",
            """{"type":"response.output_item.done","output_index":1,"item":{"type":"web_search_call","id":"ws_1","status":"completed","action":{"type":"search","query":"sky"}}}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":2,"content_index":0,"delta":"Blue light "}""",
            """{"type":"response.output_text.delta","item_id":"msg_1","output_index":2,"content_index":0,"delta":"scatters more."}""",
            """{"type":"response.output_item.done","output_index":2,"item":$message}""",
            """{"type":"response.completed","response":{"id":"resp_1","status":"completed","output":[$reasoning,{"type":"web_search_call","id":"ws_1","status":"completed"},$message],
              "usage":{"input_tokens":5000,"input_tokens_details":{"cached_tokens":4000,"cache_write_tokens":0},"output_tokens":300,"output_tokens_details":{"reasoning_tokens":200}}}}""",
        )
        assertEquals("Blue light scatters more.", shown)
        assertEquals(Stop.END, d.stop)
        assertTrue(d.complete && d.thought && d.visible)
        // The reasoning item goes back exactly as it came, encrypted content included.
        assertEquals("gAAAAB-secret", d.items[0].getString("encrypted_content"))
        assertEquals(3, d.items.size)
        assertEquals(1, d.searches)
        assertEquals(HelperUsage(input = 1000, output = 300, cacheRead = 4000, searches = 1), d.usage)
        assertEquals(listOf("https://nasa.gov/sky"), d.sources)
    }

    @Test fun functionCallArgumentsArriveInPieces() {
        val (_, d) = play(
            """{"type":"response.output_item.added","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"set_timer","arguments":""}}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":"{\"minutes\""}""",
            """{"type":"response.function_call_arguments.delta","item_id":"fc_1","output_index":0,"delta":":10}"}""",
            """{"type":"response.function_call_arguments.done","item_id":"fc_1","output_index":0,"arguments":"{\"minutes\":10}"}""",
            """{"type":"response.output_item.done","output_index":0,"item":{"type":"function_call","id":"fc_1","call_id":"call_9","name":"set_timer","arguments":"{\"minutes\":10}","status":"completed"}}""",
            """{"type":"response.completed","response":{"status":"completed","output":[],"usage":{"input_tokens":10,"output_tokens":5}}}""",
        )
        assertEquals(Stop.CALLS, d.stop)
        assertEquals(listOf("call_9" to RawToolCall("set_timer", mapOf("minutes" to 10))), d.calls)
        assertEquals("call_9", d.items.single().getString("call_id"))
    }

    @Test fun cutRefusalAndFailures() {
        val cut = play(
            """{"type":"response.output_text.delta","delta":"It"}""",
            """{"type":"response.incomplete","response":{"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
              "output":[$reasoning,{"type":"function_call","id":"fc","call_id":"c","name":"set_timer","arguments":"{"}],"usage":{"input_tokens":1,"output_tokens":9}}}""",
        ).second
        assertEquals(Stop.CUT, cut.stop)
        // No half-done call goes back, and none runs.
        assertTrue(cut.calls.isEmpty())
        assertTrue(cut.items.none { it.optString("type") == "function_call" })
        assertEquals(Stop.DECLINED, play("""{"type":"response.refusal.delta","delta":"I can't"}""", """{"type":"response.completed","response":{"status":"completed"}}""").second.stop)
        val failed = RespReply()
        failed.add("""{"type":"response.failed","response":{"status":"failed","error":{"code":"server_error","message":"x"}}}""")
        assertEquals(HelperFailure.SERVER, failed.error!!.failure(200))
        val err = RespReply()
        err.add("""{"type":"error","code":"rate_limit_exceeded","message":"slow down","param":null}""")
        assertEquals(HelperFailure.RATE_LIMITED, err.error!!.failure(200))
        // No completed event: cut off.
        assertFalse(play("""{"type":"response.output_text.delta","delta":"half"}""").second.complete)
    }

    @Test fun historyRules() {
        fun u() = JSONObject().put("role", "user").put("content", "q")
        fun call(id: String) = JSONObject().put("type", "function_call").put("call_id", id)
        fun out(id: String) = JSONObject().put("type", "function_call_output").put("call_id", id)
        assertNull(RespHistory.check(listOf(u())))
        assertNull(RespHistory.check(listOf(u(), JSONObject(reasoning), call("a"), out("a"))))
        assertNull(RespHistory.check(listOf(u(), call("a"), out("a"), JSONObject(message), u())))
        assertEquals("tool_result_missing", RespHistory.check(listOf(u(), call("a"), call("b"), out("a"))))
        assertEquals("tool_result_unasked", RespHistory.check(listOf(u(), out("x"))))
        assertEquals("ends_with_assistant", RespHistory.check(listOf(u(), JSONObject(message))))
        assertEquals("first_not_user", RespHistory.check(listOf(JSONObject(message))))
    }
}
