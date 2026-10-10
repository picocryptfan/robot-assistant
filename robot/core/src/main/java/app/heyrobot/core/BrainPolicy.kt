package app.heyrobot.core

import java.util.Locale

// Pure. The decisions :brain's GemmaEngine takes around LiteRT-LM (kept here so they are unit-tested): which tool
// level to use, when a live conversation may be reused, and what to do when creating a conversation or sending a
// message fails. GemmaEngine only acts on them.

/**
 * How tools reach the model on this TPU build, best last. :brain starts at [TOOLS_CONSTRAINED] and steps down for
 * the rest of the process only when the build's tool-call parser rejects a level ([EngineErrors.isToolParseError]),
 * never because of other errors (a full context, a native failure).
 */
enum class ToolMode {
    /** No tool API: the system prompt shows the call syntax and the app reads a reply that is nothing but calls. */
    TEXT_CALLS,
    /** LiteRT-LM tools, no constrained decoding. */
    TOOLS,
    /** LiteRT-LM tools with conversation constrained decoding. */
    TOOLS_CONSTRAINED;

    /** The next level down, or null at the bottom. */
    val lower: ToolMode? get() = entries.getOrNull(ordinal - 1)
}

/** Classifies LiteRT-LM's error messages (the messages themselves are never logged). */
object EngineErrors {
    /** Off Grid's contextCompaction.ts CONTEXT_FULL_PATTERNS (as the GPU lab uses them) and the NPU executor's wording. */
    val CONTEXT_FULL = listOf(
        "context is full", "not enough context space", "context window exceeded", "context length exceeded",
        "context window out of bounds", "exceeding the maximum number of tokens", "reached maximum number of tokens",
        "exceeds max sequence length", "exceeds npu context size", "maximum sequence length",
    )

    private val TOOL_PARSE = Regex("parse (tool|FC) calls|Status Code: 3", RegexOption.IGNORE_CASE)
    private val TOOL_FEATURE = Regex("constrain|tool", RegexOption.IGNORE_CASE)

    fun isContextFull(msg: String?): Boolean {
        val m = msg?.lowercase(Locale.US) ?: return false
        return CONTEXT_FULL.any { m.contains(it) }
    }

    /** The TPU build could not parse the model's tool call (the GPU lab's isToolParseError, verbatim). */
    fun isToolParseError(msg: String?): Boolean = msg != null && TOOL_PARSE.containsMatchIn(msg) && !isContextFull(msg)

    /** Creating a conversation failed over the tool setup itself (the parse signature, or the tool / constrained-decoding feature). */
    fun isToolSetupError(msg: String?): Boolean =
        msg != null && !isContextFull(msg) && (isToolParseError(msg) || TOOL_FEATURE.containsMatchIn(msg))
}

/** The state of a live conversation that matters for reusing it. */
data class LiveConv(
    val historyCount: Int,
    val system: String,
    val mode: ToolMode,
    val dirty: Boolean,
    val awaitingTool: Boolean,
    /** The engine's own count; Int.MAX_VALUE / 2 when it could not say. */
    val tokens: Int,
)

object BrainPolicy {
    /** Sends of one request at most (the first, then a step down or a shorter history, then one more). */
    const val MAX_SENDS = 3
    /** Rebuilds of one request at most when the engine's real count shows the history still too long. */
    const val MAX_REBUILDS = 3

    /**
     * Reuse the live conversation (its KV cache) for the next message: it is clean, it ended its last reply, it holds
     * exactly the history the client has, its system prompt is the same, it runs at the current tool level, and the
     * engine's own count leaves room for the new turn and a reply.
     */
    fun canReuse(live: LiveConv?, historyCount: Int, system: String, mode: ToolMode, newTurnTokens: Int): Boolean =
        live != null && !live.dirty && !live.awaitingTool && live.historyCount == historyCount && live.system == system &&
            live.mode == mode && !ContextWindow.needsRebuild(live.tokens, newTurnTokens)

    /** What GemmaEngine does next. */
    sealed class Next {
        /** Create the conversation again at this (lower) tool level and carry on. */
        data class StepDown(val to: ToolMode) : Next()
        /** Rebuild with this smaller history budget and try again. */
        data class Shorten(val historyBudget: Int) : Next()
        /** End the reply as stopped, keeping what was already streamed. */
        data class Stop(val reason: DoneReason) : Next()
        /** Give up with this error for the app. */
        data class Fail(val code: BrainError) : Next()
    }

    /** Creating a conversation threw [msg] at [mode] with [historyTurns] turns of history in a [historyBudget]. */
    fun afterCreateError(msg: String?, mode: ToolMode, historyTurns: Int, historyBudget: Int, attempt: Int): Next {
        val lower = mode.lower
        return when {
            attempt >= MAX_REBUILDS + ToolMode.entries.size -> Next.Fail(BrainError.ENGINE)
            EngineErrors.isContextFull(msg) -> if (historyTurns > 0) Next.Shorten(historyBudget / 2) else Next.Fail(BrainError.TOO_LONG)
            lower != null && EngineErrors.isToolSetupError(msg) -> Next.StepDown(lower)
            else -> Next.Fail(BrainError.ENGINE)
        }
    }

    /**
     * Sending a message threw [msg]. [hadOutput]: text or calls already reached the app (never send again then).
     * [sends]: sends this request already made. Only the tool-parse signature steps tools down; a full context
     * rebuilds with less history; everything else is an engine error.
     */
    fun afterSendError(msg: String?, mode: ToolMode, hadOutput: Boolean, historyTurns: Int, historyBudget: Int, sends: Int): Next {
        val full = EngineErrors.isContextFull(msg)
        val lower = mode.lower
        return when {
            hadOutput -> if (full) Next.Stop(DoneReason.LENGTH) else Next.Fail(BrainError.ENGINE)
            sends >= MAX_SENDS -> Next.Fail(if (full) BrainError.TOO_LONG else BrainError.ENGINE)
            lower != null && EngineErrors.isToolParseError(msg) -> Next.StepDown(lower)
            full -> if (historyTurns > 0) Next.Shorten(historyBudget / 2) else Next.Fail(BrainError.TOO_LONG)
            else -> Next.Fail(BrainError.ENGINE)
        }
    }

    /**
     * The tool mode for the rest of the process after an error that came with output (the reply can't be sent
     * again): a genuine tool-parse error still steps the level down for the next questions.
     */
    fun modeAfter(msg: String?, mode: ToolMode): ToolMode = if (EngineErrors.isToolParseError(msg)) mode.lower ?: mode else mode
}
