// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers.wire

import app.heyrobot.core.HelperBlock
import app.heyrobot.core.HelperContext
import app.heyrobot.core.HelperPlan
import app.heyrobot.core.HelperTools
import app.heyrobot.core.Json
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolSpec
import app.heyrobot.core.helpers.EffortStyle
import app.heyrobot.core.helpers.HelperUsage
import app.heyrobot.core.helpers.Quirks
import app.heyrobot.core.helpers.SearchStyle
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). OpenAI Chat Completions, the format every company's OpenAI-compatible API and every home server
// speaks (Mistral, DeepSeek, Groq, OpenRouter, Google's compatibility layer, Ollama, LM Studio, llama.cpp, vLLM): the
// request, the stream read one SSE payload at a time, and the rules a conversation's history must keep. The :helpers
// module's OpenAiSession sends and receives; everything that decides is here, tested against recorded shapes.
//
// The request is byte-stable for the same conversation (a fixed order, no timestamp), so providers' automatic prefix
// caching hits. The helper's own turn is kept exactly as it came, unknown fields included (DeepSeek's reasoning_content
// must go back with tools or it is a 400, OpenRouter wants reasoning_details back, Google keeps thought signatures,
// Mistral its thinking chunks), and echoed back unchanged. `name` is never sent in a message (Groq refuses it).

/**
 * Optional parts of a request a server may refuse: dropped once after a 400 or 422 before any answer, then remembered.
 * [ZDR] is listed only so a request can say it carries it: OpenRouter's "only providers that keep nothing" is your
 * privacy setting and is never dropped by Robot (a server that refuses it fails, and the helper's page has the switch).
 */
enum class Extra {
    STREAM_OPTIONS, STRICT, TOOL_CHOICE, EFFORT, TOOLS, IMAGES, SEARCH, ZDR,
    /** The Responses API's `include: ["reasoning.encrypted_content"]` (RespWire). */
    ENCRYPTED_REASONING,
}

/**
 * One request's settings: [model], the provider's [quirks], how [effort] is sent for this model, the [plan] (effort and
 * length), Robot's [tools] (empty: none), web [search], the fixed [system] prompt, OpenRouter's [zdr], and the extras the
 * server refused before ([dropped]).
 */
data class ChatOptions(
    val model: String,
    val quirks: Quirks,
    val effort: EffortStyle,
    val plan: HelperPlan,
    val tools: List<ToolSpec>,
    val search: Boolean,
    val system: String,
    val zdr: Boolean = false,
    val dropped: Set<Extra> = emptySet(),
)

object ChatWire {
    /** The request body. [messages]: the conversation without the system prompt (it goes first, from [ChatOptions.system]). */
    fun request(o: ChatOptions, messages: List<JSONObject>): JSONObject = JSONObject().apply {
        put("model", o.model)
        put("stream", true)
        if (o.quirks.streamUsage && Extra.STREAM_OPTIONS !in o.dropped) put("stream_options", JSONObject().put("include_usage", true))
        put(o.quirks.lengthParam, o.plan.maxTokens)
        put("messages", JSONArray().apply {
            put(JSONObject().put("role", "system").put("content", o.system))
            for (m in messages) put(if (Extra.IMAGES in o.dropped) withoutImages(m) else m)
        })
        val tools = JSONArray()
        if (Extra.TOOLS !in o.dropped) for (t in o.tools) tools.put(tool(t, strict = o.quirks.strictTools && Extra.STRICT !in o.dropped))
        if (o.search && Extra.SEARCH !in o.dropped) searchTool(o.quirks.search)?.let(tools::put)
        if (tools.length() > 0) {
            put("tools", tools)
            if (o.quirks.toolChoice && Extra.TOOL_CHOICE !in o.dropped) put("tool_choice", "auto")
        }
        if (Extra.EFFORT !in o.dropped) o.effort.wire(o.plan.effort)?.let { e ->
            if (o.effort == EffortStyle.OPENROUTER) put("reasoning", JSONObject().put("effort", e)) else put("reasoning_effort", e)
        }
        // Never left out once on, whatever a server refused before (a file from a build that dropped it included).
        if (o.zdr && o.quirks.zdr) put("provider", JSONObject().put("zdr", true))
    }

    /** The optional extras [request] carries: what a 400 might be about. */
    fun extras(o: ChatOptions): Set<Extra> = buildSet {
        val r = request(o, emptyList())
        if (r.has("stream_options")) add(Extra.STREAM_OPTIONS)
        if (r.has("tool_choice")) add(Extra.TOOL_CHOICE)
        if (r.has("reasoning_effort") || r.has("reasoning")) add(Extra.EFFORT)
        if (r.has("provider")) add(Extra.ZDR)
        val tools = r.optJSONArray("tools")
        if (tools != null) {
            if ((0 until tools.length()).any { tools.getJSONObject(it).optString("type") == "function" }) add(Extra.TOOLS)
            if ((0 until tools.length()).any { tools.getJSONObject(it).optString("type") != "function" }) add(Extra.SEARCH)
            if ((0 until tools.length()).any { tools.getJSONObject(it).optJSONObject("function")?.optBoolean("strict") == true }) add(Extra.STRICT)
        }
    }

    /**
     * Which extra to try without after a 400 or 422 that named no part of the request ([carried]: what the request had,
     * [refused]: already dropped), from the least to the most useful. Never [Extra.ZDR]. Null: nothing left to drop, the
     * request really is wrong.
     */
    fun nextToDrop(carried: Set<Extra>, refused: Set<Extra>): Extra? =
        DROP_ORDER.firstOrNull { it in carried && it !in refused }

    private val DROP_ORDER = listOf(Extra.STREAM_OPTIONS, Extra.TOOL_CHOICE, Extra.STRICT, Extra.EFFORT, Extra.SEARCH, Extra.IMAGES, Extra.TOOLS,
        Extra.ENCRYPTED_REASONING)

    /**
     * What to try without after a 400 or 422 before any answer. When the error names the part it refused ([param], as
     * OpenAI-style errors do), that part only, if the request carried it and Robot may drop it; nothing when it names
     * another part of the request (the request itself is wrong: dropping something else would only lose it for good) or
     * OpenRouter's zero data retention. Without a name, the next in [nextToDrop]'s order. [functions]: how many of the
     * request's tools are Robot's (a provider's search tool comes after them).
     */
    fun toDrop(carried: Set<Extra>, refused: Set<Extra>, param: String?, functions: Int): Extra? {
        if (param.isNullOrBlank()) return nextToDrop(carried, refused)
        val named = extraNamed(param, functions)?.let { if (it == Extra.TOOLS && Extra.TOOLS !in carried) Extra.SEARCH else it } ?: return null
        // A refused tool list may only be its strict form: that goes first.
        if (named == Extra.TOOLS && Extra.STRICT in carried && Extra.STRICT !in refused) return Extra.STRICT
        return named.takeIf { it in DROP_ORDER && it in carried && it !in refused }
    }

    /** The extra an error's `param` names ("stream_options", "tool_choice", "reasoning_effort", "tools[2].function.strict"…), or null. */
    fun extraNamed(param: String, functions: Int): Extra? {
        val p = param.trim().lowercase(java.util.Locale.ROOT)
        return when {
            p.startsWith("stream_options") -> Extra.STREAM_OPTIONS
            p.startsWith("tool_choice") -> Extra.TOOL_CHOICE
            p.startsWith("reasoning") -> Extra.EFFORT
            p.startsWith("include") -> Extra.ENCRYPTED_REASONING
            p.startsWith("provider") || p.startsWith("zdr") -> Extra.ZDR
            p.startsWith("tools") -> when {
                "strict" in p -> Extra.STRICT
                (Regex("""^tools\[(\d+)]""").find(p)?.groupValues?.get(1)?.toIntOrNull() ?: -1) >= functions -> Extra.SEARCH
                else -> Extra.TOOLS
            }
            "image" in p -> Extra.IMAGES
            else -> null
        }
    }

    /**
     * One of Robot's tools: its properties in order and no others. Strict (OpenAI's rules): every property listed as
     * required, the optional ones typed [type, "null"] (a null counts as absent: HelperTools.check). Otherwise the
     * schema as Gemma has it.
     */
    fun tool(spec: ToolSpec, strict: Boolean): JSONObject {
        val props = JSONObject()
        for (p in spec.params) {
            val type: Any = if (strict && !p.required) JSONArray().put(p.type).put("null") else p.type
            props.put(p.name, JSONObject().put("type", type).put("description", p.description))
        }
        val required = JSONArray().apply { for (p in spec.params) if (strict || p.required) put(p.name) }
        val params = JSONObject().put("type", "object").put("properties", props).put("required", required).put("additionalProperties", false)
        val fn = JSONObject().put("name", spec.tool.id).put("description", spec.description).put("parameters", params)
        if (strict) fn.put("strict", true)
        return JSONObject().put("type", "function").put("function", fn)
    }

    /** The provider's own web search, as a tool entry (null: none through Chat Completions). */
    private fun searchTool(s: SearchStyle): JSONObject? = when (s) {
        SearchStyle.OPENROUTER -> JSONObject().put("type", "openrouter:web_search").put("parameters", JSONObject().put("max_results", SEARCH_RESULTS))
        else -> null
    }

    /** Results per OpenRouter search. */
    const val SEARCH_RESULTS = 5

    /**
     * The user's message from HelperContext's blocks ([before]: tool results it must follow, as their own messages). Text
     * only: one string, the blocks one paragraph each. With a picture ([images]: the model can see): content parts.
     */
    fun user(blocks: List<HelperBlock>, images: Boolean = true): JSONObject {
        val pics = blocks.filterIsInstance<HelperBlock.Image>()
        val texts = blocks.filterIsInstance<HelperBlock.Text>().map { it.text }
        val m = JSONObject().put("role", "user")
        if (pics.isEmpty() || !images) return m.put("content", texts.joinToString("\n\n"))
        val parts = JSONArray()
        for (b in blocks) when (b) {
            is HelperBlock.Text -> parts.put(JSONObject().put("type", "text").put("text", b.text))
            is HelperBlock.Image -> parts.put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(b.jpeg))))
        }
        return m.put("content", parts)
    }

    /** [m] without its pictures (a server that refused them), text kept. */
    fun withoutImages(m: JSONObject): JSONObject {
        val c = m.optJSONArray("content") ?: return m
        val texts = (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("type") == "text" }.map { it.optString("text") }
        return JSONObject(m.toString()).put("content", texts.joinToString("\n\n"))
    }

    /** One tool result: Robot's fixed-vocabulary response as JSON. */
    fun toolResult(id: String, result: Map<String, Any?>): JSONObject =
        JSONObject().put("role", "tool").put("tool_call_id", id).put("content", Json.toJson(result).toString())
}

/** How a helper's reply ended. */
enum class Stop { END, CUT, CALLS, DECLINED }

/** What the session does with one stream payload: show [text], note thinking ([thinking]), or nothing visible. */
data class Step(val text: String = "", val thinking: Boolean = false)

/**
 * A reply put together from a Chat Completions stream, one SSE `data:` payload at a time, never failing on a part it
 * doesn't know. Reasoning (`reasoning_content`, `reasoning`, Mistral's thinking chunks, `<think>` tags) is kept for the
 * echo and never shown; tool calls are put together from their pieces by index; usage is read however it comes
 * (OpenAI's final chunk, Mistral's last chunk, Groq's x_groq, DeepSeek's cache hit and miss, OpenRouter's cost); an error
 * inside the stream (OpenRouter after a 200) is a failure.
 */
class ChatReply {
    private val think = ThinkTagFilter()
    /** The echoed message's content: plain text as it came, or Mistral's chunks. */
    private val content = StringBuilder()
    private var chunks: JSONArray? = null
    private val other = JSONObject()
    private val calls = sortedMapOf<Int, Call>()
    private var finish: String? = null
    private var doneSeen = false
    private val refusal = StringBuilder()
    private val sources = LinkedHashSet<String>()

    var usage: HelperUsage? = null
        private set
    /** An error the stream itself reported. */
    var error: WireError? = null
        private set
    /** Reasoning arrived (in any of its forms). */
    var sawThinking = false
        private set

    private class Call(var id: String? = null, var name: String? = null, val args: StringBuilder = StringBuilder(), val extra: JSONObject = JSONObject())

    /** One payload (the text after `data: `). Throws only on a payload that isn't JSON; the caller skips it. */
    fun add(payload: String): Step {
        val p = payload.trim()
        if (p == "[DONE]") {
            doneSeen = true
            return Step(think.flush())
        }
        val o = JSONObject(p)
        o.optJSONObject("error")?.let {
            error = WireErrors.fromObject(o)
            return Step()
        }
        readUsage(o)
        val choices = o.optJSONArray("choices") ?: return Step()
        val c = choices.optJSONObject(0) ?: return Step()
        c.optString("finish_reason").takeIf { it.isNotEmpty() && it != "null" }?.let { finish = it }
        val d = c.optJSONObject("delta") ?: c.optJSONObject("message") ?: return Step()
        var shown = ""
        var thinking = false
        for (k in d.keys()) {
            val v = d.opt(k)
            when (k) {
                "role" -> {}
                "content" -> when (v) {
                    is String -> {
                        content.append(v)
                        if (chunks != null) appendChunk(JSONObject().put("type", "text").put("text", v))
                        shown += think.feed(v)
                        if (think.thinking) thinking = true
                    }
                    is JSONArray -> for (i in 0 until v.length()) {
                        val chunk = v.optJSONObject(i) ?: continue
                        // Mistral at high effort: [{"type":"thinking","thinking":[...]}, {"type":"text","text":"..."}].
                        if (chunks == null) chunks = JSONArray().apply { if (content.isNotEmpty()) put(JSONObject().put("type", "text").put("text", content.toString())) }
                        appendChunk(chunk)
                        if (chunk.optString("type") == "text") {
                            val t = chunk.optString("text")
                            content.append(t)
                            shown += think.feed(t)
                        } else if (chunk.optString("type") == "thinking") thinking = true
                    }
                    else -> {}
                }
                "tool_calls" -> (v as? JSONArray)?.let(::addCalls)
                "refusal" -> (v as? String)?.let(refusal::append)
                "annotations" -> (v as? JSONArray)?.let(::addSources)
                "reasoning_content", "reasoning", "thinking", "reasoning_details" -> {
                    if (v != null && v != JSONObject.NULL && v.toString().isNotEmpty()) thinking = true
                    keep(k, v)
                }
                else -> keep(k, v)
            }
        }
        if (thinking) sawThinking = true
        return Step(shown, thinking)
    }

    /** A field the echo keeps: strings joined, lists appended, objects and anything else replaced by the newest. */
    private fun keep(k: String, v: Any?) {
        if (v == null || v == JSONObject.NULL) return
        when (val old = other.opt(k)) {
            is String -> if (v is String) other.put(k, old + v) else other.put(k, v)
            is JSONArray -> if (v is JSONArray) for (i in 0 until v.length()) old.put(v.opt(i)) else other.put(k, v)
            else -> other.put(k, v)
        }
    }

    /** Mistral's chunks: a piece of the same type as the last joins it (texts concatenated), another starts a new chunk. */
    private fun appendChunk(chunk: JSONObject) {
        val list = chunks ?: return
        val last = list.optJSONObject(list.length() - 1)
        val type = chunk.optString("type")
        if (last != null && last.optString("type") == type) {
            when (type) {
                "text" -> { last.put("text", last.optString("text") + chunk.optString("text")); return }
                "thinking" -> {
                    val a = last.optJSONArray("thinking")
                    val b = chunk.optJSONArray("thinking")
                    if (a != null && b != null) {
                        val lastText = a.optJSONObject(a.length() - 1)
                        for (i in 0 until b.length()) {
                            val piece = b.optJSONObject(i) ?: continue
                            if (lastText != null && lastText.optString("type") == "text" && piece.optString("type") == "text") lastText.put("text", lastText.optString("text") + piece.optString("text"))
                            else a.put(piece)
                        }
                        return
                    }
                }
            }
        }
        list.put(JSONObject(chunk.toString()))
    }

    private fun addCalls(a: JSONArray) {
        for (i in 0 until a.length()) {
            val t = a.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function")
            val newId = t.optString("id").takeIf { it.isNotEmpty() && it != "null" }
            val named = fn?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" } != null
            val index = when {
                t.has("index") -> t.optInt("index")
                calls.isEmpty() -> 0
                // No index (some servers): a piece with neither a name nor another id continues the last call (its
                // arguments in pieces); anything else starts a new one.
                !named && (newId == null || newId == calls.getValue(calls.lastKey()).id) -> calls.lastKey()
                else -> calls.lastKey() + 1
            }
            val call = calls.getOrPut(index) { Call() }
            newId?.let { call.id = it }
            // The name comes once (OpenAI), or again with every piece (some servers), or in pieces (others).
            fn?.optString("name")?.takeIf { it.isNotEmpty() && it != "null" }?.let { n -> call.name = call.name.let { old -> if (old.isNullOrEmpty() || old == n) n else old + n } }
            fn?.opt("arguments")?.let { args ->
                when (args) {
                    is String -> call.args.append(args)
                    // Some servers send the arguments as an object, whole.
                    is JSONObject -> call.args.append(args.toString())
                    else -> {}
                }
            }
            for (k in t.keys()) if (k !in setOf("index", "id", "type", "function")) t.opt(k)?.let { call.extra.put(k, it) }
        }
    }

    private fun addSources(a: JSONArray) {
        for (i in 0 until a.length()) {
            val n = a.optJSONObject(i) ?: continue
            (n.optJSONObject("url_citation")?.optString("url") ?: n.optString("url")).takeIf { it.isNotEmpty() }?.let(sources::add)
        }
    }

    private fun readUsage(o: JSONObject) {
        val u = o.optJSONObject("usage") ?: o.optJSONObject("x_groq")?.optJSONObject("usage") ?: return
        usage = ChatUsage.of(u)
    }

    /** The finished reply ([complete] false: the stream stopped before saying how it ended). */
    fun done(): ChatDone {
        val tail = think.flush()
        val message = JSONObject().put("role", "assistant")
        val list = chunks
        message.put("content", when {
            list != null -> list
            content.isNotEmpty() -> content.toString()
            else -> JSONObject.NULL
        })
        for (k in other.keys()) message.put(k, other.get(k))
        val out = ArrayList<Pair<String, RawToolCall>>()
        if (calls.isNotEmpty()) {
            val arr = JSONArray()
            for ((i, c) in calls) {
                // A call without an id (some local servers): one is made up, the same in the echo and the result.
                val id = c.id ?: "call_robot_$i"
                val name = c.name.orEmpty()
                val args = parseArgs(c.args.toString())
                arr.put(JSONObject().put("id", id).put("type", "function")
                    .put("function", JSONObject().put("name", name).put("arguments", c.args.toString().ifBlank { "{}" })).apply { for (k in c.extra.keys()) put(k, c.extra.get(k)) })
                out += id to RawToolCall(name, args)
            }
            message.put("tool_calls", arr)
        }
        val stop = when {
            refusal.isNotEmpty() || finish == "content_filter" -> Stop.DECLINED
            finish == "length" -> Stop.CUT
            out.isNotEmpty() -> Stop.CALLS
            else -> Stop.END
        }
        val visible = ThinkTagFilter().let { f -> (f.feed(content.toString()) + f.flush()).isNotBlank() }
        return ChatDone(message, stop, out, usage, sources.toList(), complete = finish != null || doneSeen, tail = tail, thought = sawThinking || think.sawThinking,
            visible = visible)
    }

    companion object {
        /** A call's arguments as plain values; text that isn't a JSON object gets [HelperTools.BAD_JSON] (an error result). */
        fun parseArgs(s: String): Map<String, Any?> {
            if (s.isBlank()) return emptyMap()
            return try { Json.toMap(JSONObject(s)) } catch (_: Exception) { mapOf(HelperTools.BAD_JSON to true) }
        }
    }
}

/**
 * A finished Chat Completions reply: [message] to echo back as it came, how it ended ([stop]), its calls (id, call),
 * [usage] if the server sent it, the web pages it cited, and [tail], visible text held back until the end.
 */
data class ChatDone(
    val message: JSONObject,
    val stop: Stop,
    val calls: List<Pair<String, RawToolCall>>,
    val usage: HelperUsage?,
    val sources: List<String>,
    val complete: Boolean,
    val tail: String = "",
    val thought: Boolean = false,
    /** Something besides reasoning was said. */
    val visible: Boolean = true,
)

/** Usage from any Chat Completions server, normalized ([HelperUsage.input] is the uncached input only). */
object ChatUsage {
    fun of(u: JSONObject): HelperUsage {
        val prompt = u.optLong("prompt_tokens", u.optLong("input_tokens", 0))
        val output = u.optLong("completion_tokens", u.optLong("output_tokens", 0))
        val reported = u.opt("cost")?.let { (it as? Number)?.toDouble() ?: it.toString().toDoubleOrNull() }?.takeIf { it.isFinite() && it >= 0 }
        // DeepSeek: hit and miss.
        if (u.has("prompt_cache_hit_tokens") || u.has("prompt_cache_miss_tokens")) {
            val hit = u.optLong("prompt_cache_hit_tokens", 0)
            val miss = u.optLong("prompt_cache_miss_tokens", (prompt - hit).coerceAtLeast(0))
            return HelperUsage(input = miss, output = output, cacheRead = hit, reported = reported)
        }
        val details = u.optJSONObject("prompt_tokens_details") ?: u.optJSONObject("input_tokens_details")
        val cached = details?.optLong("cached_tokens", 0) ?: 0
        // Whether OpenAI counts cache writes inside prompt_tokens is unverified: taken as inside (never counted twice).
        val written = details?.optLong("cache_write_tokens", 0) ?: 0
        return HelperUsage(input = (prompt - cached - written).coerceAtLeast(0), output = output, cacheRead = cached, cacheWrite = written, reported = reported)
    }
}

/** The rules a Chat Completions conversation keeps (checked before every request: a broken one would only get a 400). */
object ChatHistory {
    /**
     * Null when [messages] (without the system prompt) is a request the server takes; otherwise a short code for the log.
     * It starts with the user; every assistant tool call is answered by a `tool` message right after it (in any order),
     * and every `tool` message answers one; the last message is the user's or a tool result.
     */
    fun check(messages: List<JSONObject>): String? {
        if (messages.isEmpty()) return "empty"
        if (messages.first().optString("role") != "user") return "first_not_user"
        var open: MutableSet<String>? = null
        for ((i, m) in messages.withIndex()) {
            when (m.optString("role")) {
                "user" -> {
                    if (open != null && open.isNotEmpty()) return "tool_result_missing"
                    open = null
                }
                "assistant" -> {
                    if (open != null && open.isNotEmpty()) return "tool_result_missing"
                    if (i > 0 && messages[i - 1].optString("role") == "assistant") return "roles_repeat"
                    val ids = m.optJSONArray("tool_calls")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("id") } }.orEmpty()
                    if (ids.toSet().size != ids.size) return "tool_use_repeated"
                    open = ids.toMutableSet()
                    if (i == messages.lastIndex) return if (ids.isNotEmpty()) "tool_use_unanswered" else "ends_with_assistant"
                }
                "tool" -> {
                    val o = open ?: return "tool_result_unasked"
                    if (!o.remove(m.optString("tool_call_id"))) return "tool_result_unasked"
                }
                else -> return "role"
            }
        }
        if (open != null && open.isNotEmpty()) return "tool_result_missing"
        return null
    }
}
