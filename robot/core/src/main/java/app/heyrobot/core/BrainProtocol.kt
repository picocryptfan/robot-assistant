// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ModelStrings
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
    /**
     * 0.5.0 (docs/conversation-mode.md 4.2): one message to a fresh conversation with no tools and constrained decoding
     * off, streamed, then closed: a translation or a summary step ([BrainMsg.OneShot]), at its own priority in :brain's
     * queue ([BrainScheduler]). Its events: EV_QUEUED while it waits (and 0 as it starts), EV_CHUNK, then EV_DONE or
     * EV_ERROR. EV_QUEUED after chunks means it gave way to a request with a higher priority and will start over: the
     * client drops what came so far.
     */
    const val REQ_ONE_SHOT = 8

    // Events (brain -> client).
    const val EV_STATUS = 101
    const val EV_LOADED = 102
    const val EV_CHUNK = 103
    const val EV_TOOL_CALLS = 104
    const val EV_DONE = 105
    const val EV_ERROR = 106
    const val EV_UNLOADED = 107
    /**
     * 0.5.0: the request waits behind others, at [BrainMsg.Queued.position] (the client may cancel it, REQ_CANCEL), or
     * starts after waiting (position 0). Not terminal.
     */
    const val EV_QUEUED = 108

    /** The requests :brain answers (anything else gets EV_ERROR BAD_REQUEST). */
    val REQUESTS = setOf(REQ_STATUS, REQ_LOAD, REQ_UNLOAD, REQ_GENERATE, REQ_TOOL_RESULT, REQ_CANCEL, REQ_CLOSE_CONV, REQ_ONE_SHOT)
    val EVENTS = setOf(EV_STATUS, EV_LOADED, EV_CHUNK, EV_TOOL_CALLS, EV_DONE, EV_ERROR, EV_UNLOADED, EV_QUEUED)
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
    /**
     * No room left in the context for the tool results and a reply after them: the actions ran, the conversation
     * was closed (it is rebuilt from the history, shorter, next time) and the reply ends here.
     */
    CONTEXT_FULL,
}

/**
 * Who is waiting on a request to the model, first served first (docs/conversation-mode.md 4.2): the panel, then the
 * interpreter's turn, then a transcript line's better translation, then a summary step.
 */
enum class Priority { PANEL, INTERPRETER, TRANSCRIPT, SUMMARY }

/**
 * What a one-shot request is for: 0.9's WEB_ANSWER is a web search's answer (core web WebPrompt), at the panel's priority.
 * 1.4 (Robot Files, plan §4.2 and §5.1): FILE_READ reads one file for filing, a page's picture or its text (core files
 * FilePrompt), and is the only kind that may carry a picture; FILE_ANSWER answers a question from one file's text.
 */
enum class OneShotKind { TRANSLATE, SUMMARY_MAP, SUMMARY_REDUCE, WEB_ANSWER, FILE_READ, FILE_ANSWER }

enum class BrainError {
    NOT_LOADED, NO_VISION, BAD_REQUEST, ENGINE, BUSY,
    /** The new message alone does not fit in the model's context (it is never sent cut off by the engine). */
    TOO_LONG,
    /**
     * The model's own process stopped while it answered (0.4.5: llama.cpp's isolated process, for a GGUF file; :brain
     * itself lives). The assistant treats it as it treats :brain stopping: that file is skipped, Fast answers next.
     */
    STOPPED;

    /** Plain language for the panel. */
    fun textPhrase(): app.heyrobot.core.i18n.Phrase = when (this) {
        NOT_LOADED -> ModelStrings.errorNotLoaded()
        NO_VISION -> ModelStrings.errorNoVision()
        BAD_REQUEST -> ModelStrings.errorBadRequest()
        ENGINE -> ModelStrings.errorEngine()
        BUSY -> ModelStrings.errorBusy()
        TOO_LONG -> ModelStrings.errorTooLong()
        STOPPED -> ModelStrings.errorStopped()
    }

}

sealed class BrainMsg {
    abstract fun toJson(): JSONObject

    // ---------------------------------------------------------------- requests
    /**
     * Load [model]'s file at [path] on [backend] with a context of [maxTokens] (0.4.1: the catalogue entry's; 0.4.3: your
     * own file's when it is shorter, chat only), a file of [format] (a GGUF file runs with llama.cpp). :brain unloads any
     * other model first, by either engine: one engine in the process; and loads only with the context LoadCheck expects
     * for that file.
     */
    data class Load(
        val path: String,
        val model: BrainModel = BrainModel.FAST,
        val backend: ModelBackend = model.backend,
        val maxTokens: Int = model.contextTokens,
        val format: ModelFormat = ModelFormat.LITERT_LM,
    ) : BrainMsg() {
        override fun toJson() = JSONObject().put("path", path).put("model", model.id).put("backend", backend.id).put("maxTokens", maxTokens)
            .put("format", format.id)
    }

    data class Generate(
        val convId: String,
        val system: String,
        /** Past turns, for (re)building the conversation; the brain reuses its live one when [historyCount] matches. */
        val history: List<PromptTurn>,
        val historyCount: Int,
        val text: String,
        val hasImage: Boolean,
        /** The tools this conversation describes (ToolRouter: its groups, minus what's turned off), by id. */
        val tools: List<String> = Prompt.CORE_TOOLS.map { it.id },
        /** Settings › Model › "Let the model think first" (0.4.5): :brain tells a model that can think whether it may (core Thinking). */
        val think: Boolean = false,
        /**
         * 1.1, Skills phase B (plan §8): your skills described to the model as tools beside [tools], at most 3 (core skills
         * SkillSpecs). Not ToolName ids: :brain builds each from its spec. A spec it can't read is never described.
         */
        val skills: List<app.heyrobot.core.skills.SkillToolSpec> = emptyList(),
        /**
         * 1.2 (design Step 3, core PrefillBudget): describe [tools] in their compact form, as [system] was made. On a slow
         * path only; false everywhere else, and then nothing about the request changes.
         */
        val compact: Boolean = false,
        /** 1.2: the most estimated tokens a rebuild should read (PrefillBudget.tokens), or -1 for no budget (the fast paths). */
        val readBudget: Int = -1,
    ) : BrainMsg() {
        override fun toJson() = JSONObject().apply {
            put("convId", convId); put("system", system); put("historyCount", historyCount); put("text", text); put("hasImage", hasImage)
            put("history", JSONArray().apply { history.forEach { put(JSONObject().put("role", it.role.name).put("text", it.text)) } })
            put("tools", JSONArray(tools)); put("think", think)
            if (skills.isNotEmpty()) put("skills", JSONArray().apply { skills.forEach { put(it.toJson()) } })
            // Only on a slow path: a fast path's request is the same JSON as before 1.2.
            if (compact) put("compact", true)
            if (readBudget >= 0) put("readBudget", readBudget)
        }

        /** The known tools among [tools], in ToolName order (an unknown id is never described). */
        val toolNames: List<ToolName> get() = ToolName.entries.filter { it.id in tools }
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

    /**
     * One message, no tools, no history (REQ_ONE_SHOT): [system] one of Prompt's fixed conversation-mode prompts, [text]
     * the line or the part to work on, [maxChunks] the reply's cap. 1.4: [hasImage], a FILE_READ of a page's picture: the
     * JPEG travels as a panel question's does ([BrainProtocol.KEY_IMAGE], a read-only SharedMemory), never as a path. Only
     * then is the key written, so every other one-shot's JSON is as before 1.4; any other kind with it isn't a request.
     */
    data class OneShot(val kind: OneShotKind, val priority: Priority, val system: String, val text: String, val maxChunks: Int,
        val hasImage: Boolean = false) : BrainMsg() {
        override fun toJson() = JSONObject().put("kind", kind.name).put("priority", priority.name).put("system", system).put("text", text)
            .put("maxChunks", maxChunks).apply { if (hasImage) put("hasImage", true) }
    }

    data object Empty : BrainMsg() {
        override fun toJson() = JSONObject()
    }

    // ---------------------------------------------------------------- events
    /** [model], [backend], [maxTokens] (its context, 0.4.3) and [format]: what is loaded (null and 0 when nothing is). */
    data class Status(
        val loaded: Boolean, val vision: Boolean, val constrained: Boolean, val busy: Boolean,
        val model: BrainModel? = null, val backend: ModelBackend? = null, val maxTokens: Int = 0,
        val format: ModelFormat? = null,
    ) : BrainMsg() {
        override fun toJson() = JSONObject().put("loaded", loaded).put("vision", vision).put("constrained", constrained).put("busy", busy).apply {
            model?.let { put("model", it.id) }
            backend?.let { put("backend", it.id) }
            if (maxTokens > 0) put("maxTokens", maxTokens)
            format?.let { put("format", it.id) }
        }
    }

    /**
     * [failure]: why it didn't load, when it didn't ([error] says it in plain words, English). 1.1: [errorPhrase] is the same
     * without a language yet (PhraseWire across processes), so the app says it in your language ([errorText]).
     */
    data class Loaded(
        val ok: Boolean, val vision: Boolean, val loadMs: Long, val error: String?,
        val model: BrainModel? = null, val backend: ModelBackend? = null, val failure: LoadFailure? = null,
        val format: ModelFormat? = null, val errorPhrase: app.heyrobot.core.i18n.Phrase? = null,
    ) : BrainMsg() {
        /** Why it didn't load, in words without a language yet (an older :brain's English as it is). */
        val errorText: app.heyrobot.core.i18n.Phrase? get() = errorPhrase ?: error?.let { app.heyrobot.core.i18n.Phrase.plain(it) }

        override fun toJson() = JSONObject().apply {
            put("ok", ok); put("vision", vision); put("loadMs", loadMs); error?.let { put("error", it) }
            errorPhrase?.let { put("errorPhrase", app.heyrobot.core.i18n.PhraseWire.toJson(it)) }
            model?.let { put("model", it.id) }
            backend?.let { put("backend", it.id) }
            failure?.let { put("failure", it.name) }
            format?.let { put("format", it.id) }
        }
    }

    data class Chunk(val text: String, val thought: Boolean = false) : BrainMsg() {
        override fun toJson() = JSONObject().put("text", text).put("thought", thought)
    }

    data class ToolCallsMsg(val calls: List<RawToolCall>) : BrainMsg() {
        override fun toJson() = JSONObject().put("calls", JSONArray().apply {
            calls.forEach { put(JSONObject().put("name", it.name).put("args", Json.obj(it.args))) }
        })
    }

    /**
     * [textCalls]: this conversation runs without LiteRT-LM's tool API ([ToolMode.TEXT_CALLS]); only then may the
     * app read a reply that is nothing but a call as a tool call.
     * 1.2 (design Step 5): [prefillTokens] and [prefillTps], the tokens the model read for this reply and how fast (LiteRT-LM's
     * BenchmarkInfo; llama.cpp's own count and time), and [decodeTokens], the tokens it wrote (so a reply of 2 tokens doesn't
     * pass for a writing speed). Numbers only; -1 unknown, as from a :brain older than 1.2 ([Speed]).
     */
    data class Done(
        val reason: DoneReason, val tokens: Int, val ttftMs: Long, val decodeTps: Double, val rebuilt: Boolean, val textCalls: Boolean = false,
        val prefillTokens: Int = -1, val prefillTps: Double = -1.0, val decodeTokens: Int = -1,
    ) : BrainMsg() {
        override fun toJson() = JSONObject().put("reason", reason.name).put("tokens", tokens).put("ttftMs", ttftMs)
            .put("decodeTps", if (decodeTps.isFinite()) decodeTps else -1.0).put("rebuilt", rebuilt).put("textCalls", textCalls)
            .put("prefillTokens", Speed.count(prefillTokens)).put("prefillTps", Speed.rate(prefillTps)).put("decodeTokens", Speed.count(decodeTokens))

        /** 1.2: a reply's numbers, either engine's, read the same way (anything absent or out of range is -1). */
        object Speed {
            /** More than any context Robot loads, many times over: a count past it is nonsense. */
            const val MAX_TOKENS = 1_000_000
            /** Tokens a second no phone reaches: a rate past it (or a division by a zero time) is nonsense. */
            const val MAX_TPS = 100_000.0

            fun count(n: Int): Int = if (n in 0..MAX_TOKENS) n else -1
            fun rate(x: Double): Double = if (x.isFinite() && x > 0.0 && x <= MAX_TPS) x else -1.0

            /** Tokens a second from [tokens] in [ms] (llama.cpp's counts), -1 when either is unknown or zero. */
            fun rate(tokens: Long, ms: Long): Double = if (tokens > 0 && ms > 0) rate(tokens * 1000.0 / ms) else -1.0
        }
    }

    data class Error(val code: BrainError, val detail: String?) : BrainMsg() {
        override fun toJson() = JSONObject().apply { put("code", code.name); detail?.let { put("detail", it) } }
    }

    /** EV_QUEUED: the request waits, [position] 1 when it is next. */
    data class Queued(val position: Int) : BrainMsg() {
        override fun toJson() = JSONObject().put("position", position)
    }

    companion object {
        /** The skill specs of a Generate, at most 3, each one the app could have made (anything else is left out). */
        fun skillsOf(a: JSONArray?): List<app.heyrobot.core.skills.SkillToolSpec> {
            a ?: return emptyList()
            return (0 until a.length()).mapNotNull { app.heyrobot.core.skills.SkillToolSpec.fromJson(a.optJSONObject(it)) }
                .distinctBy { it.name }.take(app.heyrobot.core.skills.SkillSelect.MAX)
        }

        /** Decodes the payload of a message with code [what]; null when the code or the JSON is not understood. */
        fun decode(what: Int, json: String?): BrainMsg? = try {
            val o = JSONObject(json ?: "{}")
            when (what) {
                BrainProtocol.REQ_LOAD -> {
                    // A request without the model (0.4.0's) is Fast's; an unknown model, backend or kind of file isn't loaded.
                    val model = if (o.has("model")) BrainModel.byId(o.getString("model")) ?: return null else BrainModel.FAST
                    val backend = if (o.has("backend")) ModelBackend.of(o.getString("backend")) ?: return null else model.backend
                    val format = if (o.has("format")) ModelFormat.of(o.getString("format")) ?: return null else ModelFormat.LITERT_LM
                    Load(o.getString("path"), model, backend, o.optInt("maxTokens", model.contextTokens), format)
                }
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
                        tools = o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: Prompt.CORE_TOOLS.map { it.id },
                        think = o.optBoolean("think", false),
                        skills = BrainMsg.skillsOf(o.optJSONArray("skills")),
                        compact = o.optBoolean("compact", false),
                        // A budget below none reads as none: never a negative cap.
                        readBudget = o.optInt("readBudget", -1).coerceAtLeast(-1),
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
                BrainProtocol.REQ_ONE_SHOT -> OneShot(enumValueOf(o.getString("kind")), enumValueOf(o.getString("priority")), o.getString("system"),
                    o.getString("text"), o.optInt("maxChunks", Limits.MAX_REPLY_CHUNKS).coerceIn(1, Limits.MAX_REPLY_CHUNKS),
                    // 1.4: a picture only with FILE_READ, and only when the key is exactly true.
                    hasImage = o.opt("hasImage") == true).takeIf { !it.hasImage || it.kind == OneShotKind.FILE_READ }
                BrainProtocol.REQ_STATUS, BrainProtocol.REQ_UNLOAD, BrainProtocol.EV_UNLOADED -> Empty
                BrainProtocol.EV_STATUS -> Status(o.optBoolean("loaded"), o.optBoolean("vision"), o.optBoolean("constrained"), o.optBoolean("busy"),
                    BrainModel.byId(o.optString("model")), ModelBackend.of(o.optString("backend")), o.optInt("maxTokens", 0).coerceAtLeast(0),
                    ModelFormat.of(o.optString("format")))
                BrainProtocol.EV_LOADED -> Loaded(o.optBoolean("ok"), o.optBoolean("vision"), o.optLong("loadMs"), o.optString("error").takeIf { o.has("error") },
                    BrainModel.byId(o.optString("model")), ModelBackend.of(o.optString("backend")),
                    LoadFailure.entries.firstOrNull { it.name == o.optString("failure") }, ModelFormat.of(o.optString("format")),
                    app.heyrobot.core.i18n.PhraseWire.fromJson(o.optJSONObject("errorPhrase")))
                BrainProtocol.EV_CHUNK -> Chunk(o.getString("text"), o.optBoolean("thought", false))
                BrainProtocol.EV_TOOL_CALLS -> {
                    val c = o.getJSONArray("calls")
                    ToolCallsMsg((0 until c.length()).map { i ->
                        val x = c.getJSONObject(i)
                        RawToolCall(x.getString("name"), Json.toMap(x.optJSONObject("args") ?: JSONObject()))
                    })
                }
                BrainProtocol.EV_DONE -> Done(enumValueOf(o.getString("reason")), o.optInt("tokens"), o.optLong("ttftMs", -1), o.optDouble("decodeTps", -1.0),
                    o.optBoolean("rebuilt"), o.optBoolean("textCalls", false),
                    // 1.2: absent from an older :brain, so unknown; never trusted past its range.
                    Done.Speed.count(o.optInt("prefillTokens", -1)), Done.Speed.rate(o.optDouble("prefillTps", -1.0)),
                    Done.Speed.count(o.optInt("decodeTokens", -1)))
                BrainProtocol.EV_ERROR -> Error(enumOr(o.optString("code"), BrainError.ENGINE), o.optString("detail").takeIf { o.has("detail") })
                BrainProtocol.EV_QUEUED -> Queued(o.getInt("position"))
                else -> null
            }
        } catch (_: Exception) { null }
    }
}
