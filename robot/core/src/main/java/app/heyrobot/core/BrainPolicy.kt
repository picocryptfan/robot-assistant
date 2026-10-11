package app.heyrobot.core

import java.util.Locale

// Pure. The decisions :brain's GemmaEngine takes around LiteRT-LM (kept here so they are unit-tested): which tool
// level to use, when a live conversation may be reused, and what to do when creating a conversation or sending a
// message fails. GemmaEngine only acts on them.

/**
 * How tools reach the model, best last. :brain starts at [TOOLS_CONSTRAINED] and steps down for
 * the rest of the process (or until another model loads, 0.4.1) only when the build's tool-call parser rejects a level ([EngineErrors.isToolParseError]),
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

    /** The model's build could not parse its tool call (the GPU lab's isToolParseError, verbatim). */
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
    /** The tool ids it describes. */
    val tools: List<String> = Prompt.CORE_TOOLS.map { it.id },
)

object BrainPolicy {
    /** Sends of one request at most (the first, then a step down or a shorter history, then one more). */
    const val MAX_SENDS = 3
    /** Rebuilds of one request at most when the engine's real count shows the history still too long. */
    const val MAX_REBUILDS = 3

    /**
     * Reuse the live conversation (its KV cache) for the next message: it is clean, it ended its last reply, it holds
     * exactly the history the client has, its system prompt and its tools are the same (a question that added a tool
     * group rebuilds it once), it runs at the current tool level, and the engine's own count leaves room for the new
     * turn and a reply.
     */
    fun canReuse(live: LiveConv?, historyCount: Int, system: String, mode: ToolMode, newTurnTokens: Int, tools: List<String> = Prompt.CORE_TOOLS.map { it.id }): Boolean =
        live != null && !live.dirty && !live.awaitingTool && live.historyCount == historyCount && live.system == system &&
            live.mode == mode && live.tools == tools && !ContextWindow.needsRebuild(live.tokens, newTurnTokens)

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

/**
 * An engine unloaded while LiteRT still decodes into one of its conversations is parked, never closed under it
 * (GemmaEngine); LiteRT's onDone/onError, posted to :brain's work thread, closes it. These rules keep that
 * callback deliverable after the service is destroyed, and keep a second engine from loading beside a parked one.
 */
object EngineTeardown {
    /** How long :brain waits after its service was destroyed for a parked engine's callback before ending itself. */
    const val PARKED_LIMIT_MS = 20_000L

    enum class Step {
        /** Nothing is parked: the work thread can stop. */
        QUIT_THREAD,
        /** Keep the work thread: LiteRT's callback is posted to it, and closes the parked engine. */
        WAIT,
        /** LiteRT never let go: ending the process is the only way to free the engine's memory (and the TPU). */
        END_PROCESS,
    }

    /** A new engine may load only when no engine is parked anywhere in the :brain process (2 GB on the TPU, 4 GB on the CPU each). */
    fun mayLoad(parkedInProcess: Int): Boolean = parkedInProcess <= 0

    enum class LoadStep {
        /** The process's one engine slot is free (or this service's own): load. */
        LOAD,
        /** An earlier BrainService of this process still holds an engine (loading, loaded or being let go of): ask again in a moment. */
        WAIT,
        /** It has held on for [PARKED_LIMIT_MS] since its service was destroyed: its thread is stuck in LiteRT, and only ending the process frees it. */
        END_PROCESS,
    }

    /**
     * 0.4.1: one engine in the whole :brain process, whichever BrainService loads it. A service destroyed while its work
     * thread was still loading, or still letting its engine go (the unload waits up to 10 s for a cancelled reply), keeps
     * that engine until its queued unload runs; a new service in the same process must not load a second one meanwhile
     * (Fast about 2 GB, an E4B about 4 GB). [heldByAnother]: an earlier service holds the slot; [sinceHolderDestroyedMs]:
     * how long ago that service was destroyed (-1: not known yet).
     */
    fun forLoad(heldByAnother: Boolean, sinceHolderDestroyedMs: Long): LoadStep = when {
        !heldByAnother -> LoadStep.LOAD
        sinceHolderDestroyedMs < PARKED_LIMIT_MS -> LoadStep.WAIT
        else -> LoadStep.END_PROCESS
    }

    /** At, and after, the service's destroy ([sinceDestroyMs] ago), with an engine still [parked] or not. */
    fun afterDestroy(parked: Boolean, sinceDestroyMs: Long): Step = when {
        !parked -> Step.QUIT_THREAD
        sinceDestroyMs < PARKED_LIMIT_MS -> Step.WAIT
        else -> Step.END_PROCESS
    }
}

/**
 * 0.4.4: the two engines :brain can run, by the kind of file asked for: LiteRT-LM (Robot's models and your own .litertlm)
 * and llama.cpp (your own .gguf). Still one engine in the process: the other one lets its model go before this one loads,
 * and the process's one engine slot (EngineTeardown.forLoad) keeps the new one waiting until it has.
 */
enum class EngineKind {
    LITERT_LM, LLAMA_CPP;

    companion object {
        fun of(format: ModelFormat): EngineKind = if (format == ModelFormat.GGUF) LLAMA_CPP else LITERT_LM
    }
}

object EngineSwitch {
    enum class Step {
        /** The engine for this file is the one in use (or none is): load with it. */
        LOAD,
        /** The other engine is in use: it unloads first, then this one loads (if the slot is free by then). */
        UNLOAD_OTHER_FIRST,
    }

    /** [active]: the engine last asked to load in this service (null: none yet); [wanted]: the one for the file asked for. */
    fun step(active: EngineKind?, wanted: EngineKind): Step =
        if (active == null || active == wanted) Step.LOAD else Step.UNLOAD_OTHER_FIRST
}

/**
 * llama.cpp's prompt (0.4.4): the conversation formatted by the model's own template is counted by the model's own
 * tokenizer, and must leave the reply its room in Robot's 4,096 tokens ([ContextWindow]); else older turns go first, as
 * with LiteRT-LM (at most [BrainPolicy.MAX_REBUILDS] times, then all of them). A question alone that the app's estimate let
 * through but the real count doesn't still gets a shorter reply when at least [MIN_REPLY] tokens are left.
 */
object PromptFit {
    const val MIN_REPLY = 128

    sealed class Step {
        data object Send : Step()
        /** Format it again with this history budget (0: no history). */
        data class Shorten(val historyBudget: Int) : Step()
        data object TooLong : Step()
    }

    /**
     * The history as a chat template wants it: user and model turns alternating, starting with a user turn and ending
     * with a model turn (the new question follows). Strict templates (Gemma 3's) stop with an error otherwise, and Robot's
     * history can have two in a row: a question whose reply failed (it isn't replayed) is dropped when another follows, and
     * two model turns in a row (Robot's reply, then Claude's) are joined.
     */
    fun alternating(history: List<PromptTurn>): List<PromptTurn> {
        val out = ArrayList<PromptTurn>()
        for (t in history) {
            val last = out.lastOrNull()
            when {
                t.role == PromptRole.USER && last?.role == PromptRole.USER -> out[out.size - 1] = t
                t.role == PromptRole.MODEL && last == null -> {}
                t.role == PromptRole.MODEL && last?.role == PromptRole.MODEL -> out[out.size - 1] = last.copy(text = last.text + "\n\n" + t.text)
                else -> out += t
            }
        }
        if (out.lastOrNull()?.role == PromptRole.USER) out.removeAt(out.size - 1)
        return out
    }

    /** [realTokens]: the whole prompt as the model's tokenizer counts it; [historyTurns] of history in it, chosen within [budget]. */
    fun after(realTokens: Int, historyTurns: Int, budget: Int, rebuilds: Int): Step = when {
        realTokens + ContextWindow.REPLY_RESERVE <= ContextWindow.MAX_TOKENS -> Step.Send
        historyTurns > 0 && rebuilds < BrainPolicy.MAX_REBUILDS -> Step.Shorten(ContextWindow.shrink(budget, realTokens, 0) ?: 0)
        historyTurns > 0 -> Step.Shorten(0)
        realTokens + MIN_REPLY <= ContextWindow.MAX_TOKENS -> Step.Send
        else -> Step.TooLong
    }
}

/**
 * The CPU features Robot's llama.cpp library is built for (tools/build_llama.sh: Armv8.6-A with dotprod, i8mm and fp16;
 * the Tensor G5 has them all). :brain reads /proc/cpuinfo before it loads the library: on a CPU without one of them the
 * library would stop the process at its first such instruction.
 */
object LlamaCpu {
    /** /proc/cpuinfo's names: dotprod, i8mm, and half-precision arithmetic (scalar and vector). */
    val REQUIRED = listOf("asimddp", "i8mm", "fphp", "asimdhp")

    /**
     * The required features some core doesn't list ("Features : …" lines of /proc/cpuinfo; every core must have them).
     * Empty: all there, or unknown (no readable list: a hardened /proc may hide it, and the phone Robot is for has them; a
     * CPU without them would stop only the :brain process, and Robot falls back to Fast).
     */
    fun missing(cpuinfo: String?): List<String> {
        val lines = cpuinfo?.lines()?.filter { it.trimStart().startsWith("Features") }.orEmpty()
        if (lines.isEmpty()) return emptyList()
        return REQUIRED.filter { f -> lines.any { l -> f !in l.substringAfter(':').trim().split(Regex("\\s+")) } }
    }
}
