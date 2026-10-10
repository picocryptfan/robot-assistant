package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject

// Pure. The IPC contract between the assistant (in :voice) and BrainService (in :brain): Messenger
// messages whose `what` is a code below, `arg1` the request id, and data a Bundle holding [KEY_JSON]
// (one of the payloads below, encoded here) and, for an image turn, [KEY_IMAGE] (a read-only
// SharedMemory with the JPEG, so the picture never touches the disk). Streaming tokens come back as
// CHUNK messages with the same request id.

object BrainProtocol {
    /** Bound by name: no client process ever loads the service class (or LiteRT with it). */
    const val SERVICE_CLASS = "app.heyrobot.brain.BrainService"
    const val KEY_JSON = "json"
    const val KEY_IMAGE = "image"

    // Requests (client -> brain).
    const val REQ_STATUS = 1
    const val REQ_LOAD = 2
    const val REQ_UNLOAD = 3
    const val REQ_GENERATE = 4
    const val REQ_TOOL_RESULT = 5
    const val REQ_CANCEL = 6
    const val REQ_CLOSE_CONV = 7

    // Events (brain -> client).
    const val EV_STATUS = 101
    const val EV_LOADED = 102
    const val EV_CHUNK = 103
    const val EV_TOOL_CALLS = 104
    const val EV_DONE = 105
    const val EV_ERROR = 106
    const val EV_UNLOADED = 107

    val REQUESTS = setOf(REQ_STATUS, REQ_LOAD, REQ_UNLOAD, REQ_GENERATE, REQ_TOOL_RESULT, REQ_CANCEL, REQ_CLOSE_CONV)
    val EVENTS = setOf(EV_STATUS, EV_LOADED, EV_CHUNK, EV_TOOL_CALLS, EV_DONE, EV_ERROR, EV_UNLOADED)
    /** Events that end a request: the client stops listening for its id. */
    val TERMINAL = setOf(EV_STATUS, EV_LOADED, EV_DONE, EV_ERROR, EV_UNLOADED)
}

/** Why a reply ended. */
enum class DoneReason {
    /** The model finished its turn. */
    END,
    /** The model asked for tools; the client runs them and answers with REQ_TOOL_RESULT (or REQ_CLOSE_CONV). */
    TOOL_CALLS,
    CANCELLED,
    /** Stopped at [Limits.MAX_REPLY_CHUNKS]. */
    LENGTH,
    /** Stopped because the output turned into garbage (repeated characters, pad tokens). */
    GARBAGE,
}

enum class BrainError {
    NOT_LOADED, NO_VISION, BAD_REQUEST, ENGINE, BUSY;

    /** Plain language for the panel. */
    fun text(): String = when (this) {
        NOT_LOADED -> "The model isn't loaded."
        NO_VISION -> "Pictures aren't available: the vision part of the model didn't load on the TPU."
        BAD_REQUEST -> "Robot sent the model a request it couldn't read."
        ENGINE -> "The model ran into a problem."
        BUSY -> "The model is busy with another answer."
    }
}

sealed class BrainMsg {
    abstract fun toJson(): JSONObject

    // ---------------------------------------------------------------- requests
    data class Load(val path: String) : BrainMsg() {
        override fun toJson() = JSONObject().put("path", path)
    }

    data class Generate(
        val convId: String,
        val system: String,
        /** Past turns, for (re)building the conversation; the brain reuses its live one when [historyCount] matches. */
        val history: List<PromptTurn>,
        val historyCount: Int,
        val text: String,
        val hasImage: Boolean,
    ) : BrainMsg() {
        override fun toJson() = JSONObject().apply {
            put("convId", convId); put("system", system); put("historyCount", historyCount); put("text", text); put("hasImage", hasImage)
            put("history", JSONArray().apply { history.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text)) } })
        }
    }

    data class ToolResult(val convId: String, val results: List<Pair<String, Map<String, Any?>>>) : BrainMsg() {
        override fun toJson() = JSONObject().apply {
            put("convId", convId)
            put("results", JSONArray().apply { results.forEach { (n, r) -> put(JSONObject().put("name", n).put("response", Json.obj(r))) } })
        }
    }

    data class Cancel(val target: Int) : BrainMsg() {
        override fun toJson() = JSONObject().put("target", target)
    }

    data class CloseConv(val convId: String) : BrainMsg() {
        override fun toJson() = JSONObject().put("convId", convId)
    }

    data object Empty : BrainMsg() {
        override fun toJson() = JSONObject()
    }

    // ---------------------------------------------------------------- events
    data class Status(val loaded: Boolean, val vision: Boolean, val constrained: Boolean, val busy: Boolean) : BrainMsg() {
        override fun toJson() = JSONObject().put("loaded", loaded).put("vision", vision).put("constrained", constrained).put("busy", busy)
    }

    data class Loaded(val ok: Boolean, val vision: Boolean, val loadMs: Long, val error: String?) : BrainMsg() {
        override fun toJson() = JSONObject().apply { put("ok", ok); put("vision", vision); put("loadMs", loadMs); error?.let { put("error", it) } }
    }

    data class Chunk(val text: String, val thought: Boolean = false) : BrainMsg() {
        override fun toJson() = JSONObject().put("text", text).put("thought", thought)
    }

    data class ToolCallsMsg(val calls: List<RawToolCall>) : BrainMsg() {
        override fun toJson() = JSONObject().put("calls", JSONArray().apply {
            calls.forEach { put(JSONObject().put("name", it.name).put("args", Json.obj(it.args))) }
        })
    }

    data class Done(val reason: DoneReason, val tokens: Int, val ttftMs: Long, val decodeTps: Double, val rebuilt: Boolean) : BrainMsg() {
        override fun toJson() = JSONObject().put("reason", reason.name).put("tokens", tokens).put("ttftMs", ttftMs)
            .put("decodeTps", if (decodeTps.isFinite()) decodeTps else -1.0).put("rebuilt", rebuilt)
    }

    data class Error(val code: BrainError, val detail: String?) : BrainMsg() {
        override fun toJson() = JSONObject().apply { put("code", code.name); detail?.let { put("detail", it) } }
    }

    companion object {
        /** Decodes the payload of a message with code [what]; null when the code or the JSON is not understood. */
        fun decode(what: Int, json: String?): BrainMsg? = try {
            val o = JSONObject(json ?: "{}")
            when (what) {
                BrainProtocol.REQ_LOAD -> Load(o.getString("path"))
                BrainProtocol.REQ_GENERATE -> {
                    val h = o.optJSONArray("history") ?: JSONArray()
                    Generate(
                        convId = o.getString("convId"),
                        system = o.getString("system"),
                        history = (0 until h.length()).map { i ->
                            val t = h.getJSONObject(i)
                            PromptTurn(enumValueOf(t.getString("role")), t.getString("text"))
                        },
                        historyCount = o.optInt("historyCount", h.length()),
                        text = o.getString("text"),
                        hasImage = o.optBoolean("hasImage", false),
                    )
                }
                BrainProtocol.REQ_TOOL_RESULT -> {
                    val r = o.getJSONArray("results")
                    ToolResult(o.getString("convId"), (0 until r.length()).map { i ->
                        val x = r.getJSONObject(i)
                        x.getString("name") to Json.toMap(x.optJSONObject("response") ?: JSONObject())
                    })
                }
                BrainProtocol.REQ_CANCEL -> Cancel(o.getInt("target"))
                BrainProtocol.REQ_CLOSE_CONV -> CloseConv(o.getString("convId"))
                BrainProtocol.REQ_STATUS, BrainProtocol.REQ_UNLOAD, BrainProtocol.EV_UNLOADED -> Empty
                BrainProtocol.EV_STATUS -> Status(o.optBoolean("loaded"), o.optBoolean("vision"), o.optBoolean("constrained"), o.optBoolean("busy"))
                BrainProtocol.EV_LOADED -> Loaded(o.optBoolean("ok"), o.optBoolean("vision"), o.optLong("loadMs"), o.optString("error").takeIf { o.has("error") })
                BrainProtocol.EV_CHUNK -> Chunk(o.getString("text"), o.optBoolean("thought", false))
                BrainProtocol.EV_TOOL_CALLS -> {
                    val c = o.getJSONArray("calls")
                    ToolCallsMsg((0 until c.length()).map { i ->
                        val x = c.getJSONObject(i)
                        RawToolCall(x.getString("name"), Json.toMap(x.optJSONObject("args") ?: JSONObject()))
                    })
                }
                BrainProtocol.EV_DONE -> Done(enumValueOf(o.getString("reason")), o.optInt("tokens"), o.optLong("ttftMs", -1), o.optDouble("decodeTps", -1.0), o.optBoolean("rebuilt"))
                BrainProtocol.EV_ERROR -> Error(enumOr(o.optString("code"), BrainError.ENGINE), o.optString("detail").takeIf { o.has("detail") })
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
