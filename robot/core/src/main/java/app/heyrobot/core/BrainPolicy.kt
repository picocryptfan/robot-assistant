package app.heyrobot.core

import java.util.Locale

// Pure. The decisions :brain's GemmaEngine takes around LiteRT-LM (kept here so they are unit-tested): which tool
// level to use, when a live conversation may be reused, and what to do when creating a conversation or sending a
// message fails. GemmaEngine only acts on them.

/**
 * How tools reach the model, best last. :brain starts at [TOOLS_CONSTRAINED] and steps down for
 * the rest of the process (or until another model loads, 0.4.1) only when the build's tool-call parser rejects a level ([EngineErrors.isToolParseError]),
 * never because of other errors (a full context, a native failure). [CHAT_ONLY] (0.4.3) is no level of that ladder: a
 * short-context model gets it for as long as it is loaded, and nothing steps to it or from it.
 */
enum class ToolMode {
    /**
     * 0.4.3, a model loaded with less than 4,096 tokens of context (ContextWindow.chatOnly): no tools described, no
     * text-call hint, no constrained decoding, and any call it makes anyway is dropped (BrainPolicy.acceptsCalls).
     */
    CHAT_ONLY,
    /** No tool API: the system prompt shows the call syntax and the app reads a reply that is nothing but calls. */
    TEXT_CALLS,
    /** LiteRT-LM tools, no constrained decoding. */
    TOOLS,
    /** LiteRT-LM tools with conversation constrained decoding. */
    TOOLS_CONSTRAINED;

    /** A level of the tool ladder (every mode but [CHAT_ONLY]). */
    val tools: Boolean get() = this != CHAT_ONLY

    /** The next level down, or null at the bottom (and for [CHAT_ONLY], which is off the ladder). */
    val lower: ToolMode? get() = if (this == CHAT_ONLY || this == TEXT_CALLS) null else entries[ordinal - 1]

    companion object {
        /** The ladder's levels: TEXT_CALLS, TOOLS, TOOLS_CONSTRAINED. */
        val LEVELS: Int = entries.count { it.tools }
    }
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
    fun canReuse(live: LiveConv?, historyCount: Int, system: String, mode: ToolMode, newTurnTokens: Int, tools: List<String> = Prompt.CORE_TOOLS.map { it.id },
        maxTokens: Int = ContextWindow.MAX_TOKENS): Boolean =
        live != null && !live.dirty && !live.awaitingTool && live.historyCount == historyCount && live.system == system &&
            live.mode == mode && live.tools == tools && !ContextWindow.needsRebuild(live.tokens, newTurnTokens, maxTokens)

    /**
     * The tool level a conversation is built at: [ToolMode.CHAT_ONLY] for a model loaded with a short context (0.4.3),
     * else the engine's current level [level] (which only the tool-parse error lowers).
     */
    fun modeFor(maxTokens: Int, level: ToolMode): ToolMode = if (ContextWindow.chatOnly(maxTokens)) ToolMode.CHAT_ONLY else level

    /** The tools described to a conversation at [mode]: none in chat only, whatever the request lists. */
    fun toolsFor(mode: ToolMode, requested: List<ToolName>): List<ToolName> = if (mode.tools) requested else emptyList()

    /** Whether a call the model makes at [mode] reaches the app: never in chat only (no tool was described to it). */
    fun acceptsCalls(mode: ToolMode): Boolean = mode.tools

    /**
     * The history a conversation at [mode] is built from: chat only, strictly alternating ([Prompt.alternate]), as the
     * chat templates of models other than Gemma 4 insist; otherwise as the app sent it.
     */
    fun historyFor(mode: ToolMode, history: List<PromptTurn>): List<PromptTurn> = if (mode.tools) history else Prompt.alternate(history)

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
            attempt >= MAX_REBUILDS + ToolMode.LEVELS -> Next.Fail(BrainError.ENGINE)
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
 * The order :brain serves requests in once conversation mode shares the model (docs/conversation-mode.md 4.2). Pure, so
 * every rule is tested (BrainQueueTest); GemmaEngine acts on its steps when REQ_ONE_SHOT is wired. Today's rule for the
 * panel stays: a new panel question cancels the panel reply still running, and only the newest waiting one is kept. An
 * interpreter turn (about a second) is never cancelled by the panel: the question waits for it. Interpreter and
 * transcript requests run first in, first out. A summary step gives way to anything else: it is cancelled and queued
 * again (map steps start over cleanly).
 */
class BrainQueue {
    data class Job(val id: Int, val priority: Priority)

    /**
     * What :brain does now: cancel the running request ([cancelRunning]), end the [dropped] waiting ones as cancelled,
     * start [start]. [requeued]: a summary step that gave way is waiting again.
     */
    data class Step(val cancelRunning: Boolean = false, val dropped: List<Job> = emptyList(), val start: Job? = null, val requeued: Job? = null)

    var running: Job? = null
        private set
    private val waiting = ArrayList<Job>()
    /** Running jobs cancelled to give way, to be queued again when they end. */
    private val yielding = HashSet<Int>()
    private var cancelling = false

    /** The waiting requests, next first. */
    val queue: List<Job> get() = waiting.toList()

    fun submit(job: Job): Step {
        val dropped = if (job.priority == Priority.PANEL) waiting.filter { it.priority == Priority.PANEL }.also { waiting.removeAll(it.toSet()) } else emptyList()
        val r = running
        if (r == null) {
            running = job
            return Step(dropped = dropped, start = job)
        }
        val cancel = !cancelling && when {
            job.priority == Priority.PANEL && r.priority == Priority.PANEL -> true
            r.priority == Priority.SUMMARY && job.priority != Priority.SUMMARY -> true.also { yielding += r.id }
            else -> false
        }
        if (cancel) cancelling = true
        enqueue(job, front = false)
        return Step(cancelRunning = cancel, dropped = dropped)
    }

    /** The running request ended (done, failed, or cancelled): the next one starts. */
    fun done(id: Int): Step {
        val r = running?.takeIf { it.id == id } ?: return Step()
        running = null
        cancelling = false
        val again = if (yielding.remove(r.id)) r.also { enqueue(it, front = true) } else null
        val next = if (waiting.isEmpty()) null else waiting.removeAt(0)
        running = next
        return Step(start = next, requeued = again)
    }

    /** The client cancelled request [id] (REQ_CANCEL): dropped if it waits, stopped if it runs (and not queued again). */
    fun cancel(id: Int): Step {
        waiting.firstOrNull { it.id == id }?.let {
            waiting.remove(it)
            return Step(dropped = listOf(it))
        }
        if (running?.id != id) return Step()
        yielding.remove(id)
        if (cancelling) return Step()
        cancelling = true
        return Step(cancelRunning = true)
    }

    /** 0 while it runs, its place in line from 1 (EV_QUEUED), -1 when unknown. */
    fun position(id: Int): Int = if (running?.id == id) 0 else waiting.indexOfFirst { it.id == id }.let { if (it < 0) -1 else it + 1 }

    /** In priority order; first in, first out within a priority ([front]: ahead of its priority, for a job queued again). */
    private fun enqueue(job: Job, front: Boolean) {
        val at = if (front) waiting.indexOfFirst { it.priority >= job.priority } else waiting.indexOfFirst { it.priority > job.priority }
        if (at < 0) waiting += job else waiting.add(at, job)
    }
}
