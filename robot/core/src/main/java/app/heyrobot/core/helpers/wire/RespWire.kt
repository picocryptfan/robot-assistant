// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers.wire

import app.heyrobot.core.HelperBlock
import app.heyrobot.core.Json
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.helpers.EffortStyle
import app.heyrobot.core.helpers.HelperUsage
import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

// Pure (org.json). The OpenAI Responses API, needed for OpenAI and xAI: OpenAI's GPT-6 models call tools only there, and
// both companies' web search is only there. Requests always say `store: false`, so the provider keeps nothing for later
// retrieval, and the whole conversation is sent each time (as for Claude). With nothing stored, a reasoning item can go
// back only with its encrypted content, which comes only when the request asks for it (`include:
// ["reasoning.encrypted_content"]`, OpenAI's "Encrypted reasoning items"): without it, the follow-up after a tool round
// or a conversation's next question names a reasoning item the provider no longer has, and is refused. The reasoning
// items then go back exactly as they came. Events read (OpenAI's streaming events reference, 2026-10-11):
// response.output_text.delta, response.refusal.delta, response.reasoning_text.delta and
// response.reasoning_summary_text.delta (thinking, never shown), response.function_call_arguments.delta/done,
// response.output_item.done, response.output_text.annotation.added, response.completed, response.incomplete,
// response.failed and error.

object RespWire {
    /** The request body: [input] is the conversation (input items, the helper's echoed output items, function results). */
    fun request(o: ChatOptions, input: List<JSONObject>): JSONObject = JSONObject().apply {
        put("model", o.model)
        put("stream", true)
        put("store", false)
        if (Extra.ENCRYPTED_REASONING !in o.dropped) put("include", JSONArray().put(ENCRYPTED_REASONING))
        put("instructions", o.system)
        put("input", JSONArray().apply { for (i in input) put(if (Extra.IMAGES in o.dropped) withoutImages(i) else i) })
        put("max_output_tokens", o.plan.maxTokens)
        if (Extra.EFFORT !in o.dropped) o.effort.wire(o.plan.effort)?.let { if (o.effort != EffortStyle.NONE) put("reasoning", JSONObject().put("effort", it)) }
        val tools = JSONArray()
        if (Extra.TOOLS !in o.dropped) for (t in o.tools) tools.put(tool(t, strict = o.quirks.strictTools && Extra.STRICT !in o.dropped))
        if (o.search && Extra.SEARCH !in o.dropped) tools.put(JSONObject().put("type", "web_search"))
        if (tools.length() > 0) {
            put("tools", tools)
            if (o.quirks.toolChoice && Extra.TOOL_CHOICE !in o.dropped) put("tool_choice", "auto")
        }
    }

    /** The optional extras the request carries (see [ChatWire.extras]). */
    fun extras(o: ChatOptions): Set<Extra> = buildSet {
        val r = request(o, emptyList())
        if (r.has("reasoning")) add(Extra.EFFORT)
        if (r.has("include")) add(Extra.ENCRYPTED_REASONING)
        if (r.has("tool_choice")) add(Extra.TOOL_CHOICE)
        val tools = r.optJSONArray("tools")
        if (tools != null) {
            if ((0 until tools.length()).any { tools.getJSONObject(it).optString("type") == "function" }) add(Extra.TOOLS)
            if ((0 until tools.length()).any { tools.getJSONObject(it).optString("type") == "web_search" }) add(Extra.SEARCH)
            if ((0 until tools.length()).any { tools.getJSONObject(it).optBoolean("strict") }) add(Extra.STRICT)
        }
    }

    /** What a stateless request asks to get back so its reasoning items can be sent again. */
    const val ENCRYPTED_REASONING = "reasoning.encrypted_content"

    /** A function tool, Responses style (flat: name, description, parameters, strict). */
    fun tool(spec: ToolSpec, strict: Boolean): JSONObject {
        val fn = ChatWire.tool(spec, strict).getJSONObject("function")
        return JSONObject().put("type", "function").put("name", fn.getString("name")).put("description", fn.getString("description"))
            .put("parameters", fn.getJSONObject("parameters")).apply { if (strict) put("strict", true) }
    }

    /** The user's message: input_text parts, and input_image parts when the model can see ([images]). */
    fun user(blocks: List<HelperBlock>, images: Boolean = true): JSONObject {
        val parts = JSONArray()
        for (b in blocks) when (b) {
            is HelperBlock.Text -> parts.put(JSONObject().put("type", "input_text").put("text", b.text))
            is HelperBlock.Image -> if (images) parts.put(JSONObject().put("type", "input_image").put("image_url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(b.jpeg))) // l10n: not text (the wire format)
        }
        return JSONObject().put("role", "user").put("content", parts)
    }

    fun withoutImages(i: JSONObject): JSONObject {
        val c = i.optJSONArray("content") ?: return i
        if ((0 until c.length()).none { c.optJSONObject(it)?.optString("type") == "input_image" }) return i
        val kept = JSONArray().apply { for (k in 0 until c.length()) c.optJSONObject(k)?.takeIf { it.optString("type") != "input_image" }?.let(::put) }
        return JSONObject(i.toString()).put("content", kept)
    }

    /** One function result. */
    fun toolResult(callId: String, result: Map<String, Any?>): JSONObject =
        JSONObject().put("type", "function_call_output").put("call_id", callId).put("output", Json.toJson(result).toString())
}

/** A reply put together from a Responses stream, one SSE payload at a time. */
class RespReply {
    private val items = sortedMapOf<Int, JSONObject>()
    private var finalOutput: JSONArray? = null
    private val args = HashMap<String, StringBuilder>()
    private val refusal = StringBuilder()
    private val sources = LinkedHashSet<String>()
    private var status: String? = null
    private var incompleteReason: String? = null
    private val visible = StringBuilder()

    var usage: HelperUsage? = null
        private set
    var error: WireError? = null
        private set
    var sawThinking = false
        private set

    fun add(payload: String): Step {
        val p = payload.trim()
        if (p == "[DONE]") return Step() // l10n: not text (the wire format)
        val o = JSONObject(p)
        return when (o.optString("type")) {
            "response.output_text.delta" -> o.optString("delta").let { visible.append(it); Step(it) }
            "response.refusal.delta" -> { refusal.append(o.optString("delta")); Step() }
            "response.reasoning_text.delta", "response.reasoning_summary_text.delta", "response.reasoning_summary_part.added" -> {
                sawThinking = true
                Step(thinking = true)
            }
            "response.function_call_arguments.delta" -> { args.getOrPut(o.optString("item_id")) { StringBuilder() }.append(o.optString("delta")); Step() }
            "response.function_call_arguments.done" -> { args[o.optString("item_id")] = StringBuilder(o.optString("arguments")); Step() }
            "response.output_item.added" -> {
                if (o.optJSONObject("item")?.optString("type") == "reasoning") { sawThinking = true; Step(thinking = true) } else Step()
            }
            "response.output_item.done" -> {
                o.optJSONObject("item")?.let { items[o.optInt("output_index", items.size)] = it }
                Step()
            }
            "response.output_text.annotation.added" -> {
                o.optJSONObject("annotation")?.takeIf { it.optString("type") == "url_citation" }?.optString("url")?.takeIf { it.isNotEmpty() }?.let(sources::add)
                Step()
            }
            "response.completed", "response.incomplete" -> {
                val r = o.optJSONObject("response")
                status = r?.optString("status")?.takeIf { it.isNotEmpty() } ?: o.optString("type").removePrefix("response.")
                incompleteReason = r?.optJSONObject("incomplete_details")?.optString("reason")
                r?.optJSONArray("output")?.takeIf { it.length() > 0 }?.let { finalOutput = it }
                r?.optJSONObject("usage")?.let { usage = ChatUsage.of(it) }
                Step()
            }
            "response.failed" -> {
                status = "failed"
                val e = o.optJSONObject("response")?.optJSONObject("error")
                error = WireError(e?.optString("type")?.takeIf { it.isNotEmpty() }, e?.optString("code")?.takeIf { it.isNotEmpty() })
                o.optJSONObject("response")?.optJSONObject("usage")?.let { usage = ChatUsage.of(it) }
                Step()
            }
            "error" -> {
                error = WireError(o.optString("type").takeIf { it.isNotEmpty() && it != "error" }, o.optString("code").takeIf { it.isNotEmpty() && it != "null" })
                Step()
            }
            else -> Step()
        }
    }

    /** The finished reply. [items] in [RespDone] go back as they came; on a cut, only the messages (no half-done calls). */
    fun done(): RespDone {
        val out = finalOutput?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: items.values.toList()
        val calls = ArrayList<Pair<String, RawToolCall>>()
        var searches = 0
        for ((i, item) in out.withIndex()) {
            when (item.optString("type")) {
                "function_call" -> {
                    val callId = item.optString("call_id").ifEmpty { item.optString("id") }.ifEmpty { "call_robot_$i" } // l10n: not text (the wire format)
                    if (!item.has("call_id")) item.put("call_id", callId)
                    val a = item.optString("arguments").ifEmpty { args[item.optString("id")]?.toString().orEmpty() }
                    if (!item.has("arguments") || item.optString("arguments").isEmpty()) item.put("arguments", a.ifBlank { "{}" })
                    calls += callId to RawToolCall(item.optString("name"), ChatReply.parseArgs(a))
                }
                "web_search_call" -> searches++
                "message" -> item.optJSONArray("content")?.let { c ->
                    for (k in 0 until c.length()) {
                        val part = c.optJSONObject(k) ?: continue
                        if (part.optString("type") == "refusal") refusal.append(part.optString("refusal"))
                        part.optJSONArray("annotations")?.let { an ->
                            for (j in 0 until an.length()) an.optJSONObject(j)?.takeIf { it.optString("type") == "url_citation" }?.optString("url")?.takeIf { it.isNotEmpty() }?.let(sources::add)
                        }
                    }
                }
            }
        }
        val cut = status == "incomplete" && (incompleteReason == "max_output_tokens" || incompleteReason.isNullOrEmpty())
        val stop = when {
            refusal.isNotEmpty() || (status == "incomplete" && incompleteReason == "content_filter") -> Stop.DECLINED
            cut -> Stop.CUT
            calls.isNotEmpty() -> Stop.CALLS
            else -> Stop.END
        }
        val echo = if (stop == Stop.CUT) out.filter { it.optString("type") == "message" } else out
        val u = usage?.let { it.copy(searches = searches.toLong()) } ?: if (searches > 0) HelperUsage(searches = searches.toLong()) else null
        return RespDone(echo, stop, if (stop == Stop.CUT) emptyList() else calls, u, sources.toList(), complete = status == "completed" || status == "incomplete",
            thought = sawThinking || out.any { it.optString("type") == "reasoning" }, visible = visible.isNotBlank(), searches = searches)
    }
}

/** A finished Responses reply: the output [items] to echo, how it ended, its calls, usage, cited pages and searches. */
data class RespDone(
    val items: List<JSONObject>,
    val stop: Stop,
    val calls: List<Pair<String, RawToolCall>>,
    val usage: HelperUsage?,
    val sources: List<String>,
    val complete: Boolean,
    val thought: Boolean,
    val visible: Boolean,
    val searches: Int,
)

/** The rules a Responses conversation keeps: every function_call is answered by a function_call_output, and only once. */
object RespHistory {
    fun check(input: List<JSONObject>): String? {
        if (input.isEmpty()) return "empty"
        if (input.first().optString("role") != "user") return "first_not_user"
        val calls = input.filter { it.optString("type") == "function_call" }.map { it.optString("call_id") }
        val outputs = input.filter { it.optString("type") == "function_call_output" }.map { it.optString("call_id") }
        if (calls.toSet().size != calls.size) return "tool_use_repeated"
        if (outputs.toSet().size != outputs.size) return "tool_result_repeated"
        if (!calls.toSet().containsAll(outputs)) return "tool_result_unasked"
        if (!outputs.toSet().containsAll(calls)) return "tool_result_missing"
        val last = input.last()
        if (last.optString("role") != "user" && last.optString("type") != "function_call_output") return "ends_with_assistant"
        return null
    }
}
