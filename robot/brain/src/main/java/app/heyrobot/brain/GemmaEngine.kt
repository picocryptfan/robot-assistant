@file:OptIn(ExperimentalApi::class)

package app.heyrobot.brain

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.EngineErrors
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.Json
import app.heyrobot.core.Limits
import app.heyrobot.core.LiveConv
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolMode
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.ToolSpecs
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

// :BRAIN PROCESS ONLY. The one file that uses LiteRT-LM (ProcessIsolationTest enforces that).
//
// Gemma 4 E2B on the Tensor G5 TPU exactly as the probe and the GPU lab (config T) load it:
// Backend.NPU(nativeLibraryDir), the vision encoder on the NPU with a text-only fallback, maxNumTokens
// 4096, cacheDir null, no sampler on the NPU. Every method runs on BrainService's work thread; LiteRT's
// callbacks are posted back to it, so the state below has a single thread. The decisions (reuse, step down,
// shorten, give up) are core's BrainPolicy; this file only acts on them.

class GemmaEngine(private val ctx: Context, private val handler: Handler) {
    fun interface Sink { fun emit(what: Int, msg: BrainMsg) }

    private var engine: Engine? = null
    private var vision = false
    /** How tools reach the model; stepped down for good (until the process restarts) only on the tool-parse error. */
    private var toolMode = ToolMode.TOOLS_CONSTRAINED
    private val convs = HashMap<String, Live>()
    private var running: Run? = null
    /** A request that arrived while another was being cancelled: it starts when that one ends. */
    private var queued: (() -> Unit)? = null
    /**
     * An engine unloaded while LiteRT was still decoding into one of its conversations: never closed under it.
     * Closed when that run's native callback arrives (or left to the process's end).
     */
    private var parked: Parked? = null
    /** Set by [shutdown] while a parked engine keeps the work thread: runs when that engine is closed. */
    private var teardown: (() -> Unit)? = null
    /**
     * The fixed part's real token count (system prompt plus tools) is logged once per tool set and mode per engine
     * load (docs/phase-2.md "Logging"): counts only. [uncounted]: sets seen with history, measured when idle.
     */
    private val counted = HashSet<String>()
    private val uncounted = LinkedHashMap<String, Triple<String, List<ToolName>, ToolMode>>()

    private class Parked(val engine: Engine, val convs: List<Conversation>, val run: Run)

    private class Live(val conv: Conversation, var historyCount: Int, val mode: ToolMode, val system: String, val tools: List<String>) {
        var dirty = false
        var awaitingTool = false
    }

    /** One request's sends: the first, and those after a step down or a shorter rebuild. */
    private class Attempt(val sends: Int, val historyBudget: Int?, val historyTurns: Int)

    private inner class Run(
        val reqId: Int,
        val convId: String,
        val live: Live,
        val sink: Sink,
        val rebuilt: Boolean,
        /** Send the same request again on a fresh conversation ([BrainPolicy.Next.StepDown] / [BrainPolicy.Next.Shorten]); null for tool results. */
        val resend: ((BrainPolicy.Next) -> Unit)?,
        val attempt: Attempt,
    ) {
        val tSend = SystemClock.elapsedRealtime()
        var tFirst = -1L
        var chunks = 0
        val reply = StringBuilder()
        val calls = mutableListOf<RawToolCall>()
        var stopReason: DoneReason? = null
        var thoughtSent = false
        var ended = false
        /** Set from LiteRT's own thread when it called onDone/onError: the native side is finished with the conversation. */
        @Volatile var nativeEnded = false
    }

    // ------------------------------------------------------------------ load

    private fun npu() = Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)

    private fun config(path: String, withVision: Boolean) = EngineConfig(
        modelPath = path,
        backend = npu(),
        visionBackend = if (withVision) npu() else null,
        audioBackend = null,
        maxNumTokens = ContextWindow.MAX_TOKENS,
        cacheDir = null,
    )

    fun status() = BrainMsg.Status(loaded = engine != null, vision = vision, constrained = toolMode == ToolMode.TOOLS_CONSTRAINED, busy = running != null)

    fun load(path: String): BrainMsg.Loaded {
        if (engine != null) return BrainMsg.Loaded(true, vision, 0, null)
        parked?.let { p -> if (p.run.nativeEnded) closeParked() else return BrainMsg.Loaded(false, false, 0, STOPPING) }
        // Nor beside an engine an earlier BrainService in this process parked: that would be a second ~2 GB on the TPU.
        if (!EngineTeardown.mayLoad(parkedInProcess.get())) return BrainMsg.Loaded(false, false, 0, STOPPING)
        val f = File(path)
        // Only the model file in Robot's own storage.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile) return BrainMsg.Loaded(false, false, 0, "The model file is missing.")
        ExperimentalFlags.enableBenchmark = true
        val t0 = SystemClock.elapsedRealtime()
        var e: Engine? = null
        var visionError: String? = null
        // Vision with a fixed 280-token image budget (read when the engine initialises), so the 300 tokens per image
        // Robot plans for hold; if this TPU build refuses the budget, vision without it; then text only.
        var budgetSet = false
        for (budget in listOf(TokenEstimate.VISUAL_TOKEN_BUDGET, null)) {
            try {
                ExperimentalFlags.visualTokenBudget = budget
                e = Engine(config(path, withVision = true))
                e.initialize()
                // The vision encoder starts with the first conversation: open one so a TPU failure shows here.
                e.createConversation().close()
                budgetSet = budget != null
                break
            } catch (ex: Exception) {
                // The lab and Off Grid catch Exception only here; anything else is a real crash.
                visionError = ex.javaClass.simpleName
                try { e?.close() } catch (_: Exception) {}
                e = null
            }
        }
        vision = e != null
        if (e == null) {
            ExperimentalFlags.visualTokenBudget = null
            try {
                e = Engine(config(path, withVision = false))
                e.initialize()
            } catch (ex: Exception) {
                try { e?.close() } catch (_: Exception) {}
                RLog.w("brain_load", ex, "ok" to false)
                return BrainMsg.Loaded(false, false, SystemClock.elapsedRealtime() - t0, "The model didn't load (${ex.javaClass.simpleName}).")
            }
        }
        engine = e
        counted.clear()
        uncounted.clear()
        val ms = SystemClock.elapsedRealtime() - t0
        RLog.i("brain_load", "ok" to true, "ms" to ms, "vision" to vision, "visualBudget" to budgetSet, "visionError" to visionError?.let { RLog.Code(it) })
        return BrainMsg.Loaded(true, vision, ms, null)
    }

    fun unload(why: String) {
        val e = engine ?: return
        queued = null
        uncounted.clear()
        val r = running
        if (r != null) {
            if (r.stopReason == null) r.stopReason = DoneReason.CANCELLED
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            // Never close a conversation LiteRT is still decoding into: wait (bounded) for its callback.
            var waited = 0
            while (!r.nativeEnded && waited < UNLOAD_WAIT_MS) { Thread.sleep(50); waited += 50 }
            finish(r, DoneReason.CANCELLED)
        }
        val all = convs.values.map { it.conv }
        convs.clear()
        engine = null
        if (r != null && !r.nativeEnded) {
            // Still decoding after the wait: park the engine rather than close it under LiteRT (a native crash here
            // would count towards the assistant's crash lockout). The run's callback closes it.
            parked = Parked(e, all, r)
            parkedInProcess.incrementAndGet()
            RLog.i("brain_unload", "why" to RLog.Code(why), "parked" to true)
            return
        }
        all.forEach(::closeQuietly)
        try { e.close() } catch (ex: Exception) { RLog.w("brain_unload", ex) }
        RLog.i("brain_unload", "why" to RLog.Code(why))
    }

    private fun closeParked() {
        val p = parked ?: return
        parked = null
        try {
            p.convs.forEach(::closeQuietly)
            try { p.engine.close() } catch (ex: Exception) { RLog.w("brain_unload", ex) }
        } finally {
            parkedInProcess.decrementAndGet()
        }
        RLog.i("brain_unload_parked_closed")
        teardown?.invoke()
    }

    /**
     * BrainService was destroyed (work thread): unload, then tell [step] once when the work thread may stop. With an
     * engine parked the thread must stay, since LiteRT posts the callback that closes it there; if that callback
     * never comes, END_PROCESS (EngineTeardown).
     */
    fun shutdown(step: (EngineTeardown.Step) -> Unit) {
        unload("destroy")
        parked?.let { if (it.run.nativeEnded) closeParked() }
        val t0 = SystemClock.elapsedRealtime()
        var told = false
        val check = {
            if (!told) {
                val s = EngineTeardown.afterDestroy(parked != null, SystemClock.elapsedRealtime() - t0)
                if (s != EngineTeardown.Step.WAIT) {
                    told = true
                    teardown = null
                    RLog.i("brain_teardown", "step" to s)
                    step(s)
                }
            }
        }
        teardown = check
        check()
        if (!told) handler.postDelayed({ check() }, EngineTeardown.PARKED_LIMIT_MS)
    }

    // ------------------------------------------------------------------ conversations

    /** One provider per tool, made once: a conversation gets those of its own tool groups. */
    private val providers = HashMap<ToolName, ToolProvider>()

    private fun providers(tools: List<ToolName>): List<ToolProvider> = tools.map { t ->
        providers.getOrPut(t) {
            val spec = ToolSpecs.of(t)
            tool(object : OpenApiTool {
                override fun getToolDescriptionJsonString(): String = spec.openApiJson()
                // Never called: automaticToolCalling is off, the app process runs tools after its approval gate.
                override fun execute(paramsJsonString: String): String = "{\"status\":\"failed\",\"detail\":\"tools run in the app\"}"
            })
        }
    }

    private fun newConversation(system: String, history: List<PromptTurn>, mode: ToolMode, tools: List<ToolName>): Conversation {
        val e = engine ?: throw IllegalStateException("not loaded")
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(if (mode == ToolMode.TEXT_CALLS) system + Prompt.textCallHint(tools) else system),
            initialMessages = history.map { if (it.role == PromptRole.USER) Message.user(it.text) else Message.model(it.text) },
            tools = if (mode == ToolMode.TEXT_CALLS) emptyList() else providers(tools),
            samplerConfig = null,
            automaticToolCalling = false,
        )
        try {
            ExperimentalFlags.enableConversationConstrainedDecoding = mode == ToolMode.TOOLS_CONSTRAINED
            return e.createConversation(cfg)
        } finally {
            ExperimentalFlags.enableConversationConstrainedDecoding = false
        }
    }

    /** Only for conversations LiteRT is not decoding into (every caller's run has ended natively). */
    private fun closeQuietly(c: Conversation) {
        try { if (c.isAlive) c.close() } catch (_: Exception) {}
    }

    fun closeConv(convId: String) {
        val r = running
        if (r != null && r.convId == convId) {
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            r.live.dirty = true
            r.stopReason = DoneReason.CANCELLED
            return
        }
        convs.remove(convId)?.let { closeQuietly(it.conv) }
    }

    // ------------------------------------------------------------------ generate

    fun generate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (req.hasImage && image == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "image missing"))
        if (image != null && !vision) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, null))
        if (afterRunning { generate(reqId, req, image, sink) }) return
        startGenerate(reqId, req, image, sink, Attempt(sends = 1, historyBudget = null, historyTurns = 0))
    }

    private fun startGenerate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: Sink, attempt: Attempt) {
        val tools = req.toolNames
        val toolIds = tools.map { it.id }
        val fixed = Prompt.fixedTokens(req.system, tools)
        val newTurn = TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text, image != null))
        // The app cuts the new turn to fit (Prompt.fitTurn); a turn that still can't fit is never sent anyway.
        if (newTurn > ContextWindow.newTurnLimit(fixed)) {
            RLog.i("brain_too_long", "newTurn" to newTurn, "fixed" to fixed)
            return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.TOO_LONG, null))
        }
        var live = convs[req.convId]
        val reuse = attempt.historyBudget == null && BrainPolicy.canReuse(
            live?.let { LiveConv(it.historyCount, it.system, it.mode, it.dirty, it.awaitingTool, tokenCount(it.conv), it.tools) },
            req.historyCount, req.system, toolMode, newTurn, toolIds,
        )
        var rebuilt = false
        var budget = attempt.historyBudget ?: ContextWindow.historyBudget(fixed, newTurn)
        // Turns of history in the conversation this message goes to (a reused one holds the client's whole history).
        var turns = if (reuse) req.history.size else 0
        if (!reuse) {
            live?.let { closeQuietly(it.conv) }
            convs.remove(req.convId)
            var tries = 0
            var rebuilds = 0
            while (true) {
                val history = ContextWindow.fit(req.history, budget)
                val mode = toolMode
                val conv = try {
                    newConversation(req.system, history, mode, tools)
                } catch (ex: Exception) {
                    when (val next = BrainPolicy.afterCreateError(ex.message, mode, history.size, budget, tries++)) {
                        is BrainPolicy.Next.StepDown -> {
                            RLog.w("brain_tool_mode_down", ex, "from" to mode, "to" to next.to, "at" to RLog.Code("create"))
                            toolMode = next.to
                        }
                        is BrainPolicy.Next.Shorten -> budget = next.historyBudget
                        is BrainPolicy.Next.Fail -> {
                            RLog.w("brain_conversation", ex, "code" to next.code)
                            return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(next.code, ex.javaClass.simpleName))
                        }
                        is BrainPolicy.Next.Stop -> return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.ENGINE, ex.javaClass.simpleName))
                    }
                    continue
                }
                // The estimate chose the history; the engine's own count says whether the turn and a reply still fit.
                val real = tokenCount(conv, unknown = -1)
                val smaller = ContextWindow.shrink(budget, real, newTurn)
                if (smaller != null && history.isNotEmpty() && rebuilds < BrainPolicy.MAX_REBUILDS) {
                    closeQuietly(conv)
                    rebuilds++
                    RLog.i("brain_rebuild_shorter", "real" to real, "budget" to budget, "to" to smaller)
                    budget = smaller
                    continue
                }
                live = Live(conv, req.historyCount, mode, req.system, toolIds).also { convs[req.convId] = it }
                turns = history.size
                rebuilt = true
                RLog.i("brain_rebuild", "kept" to history.size, "of" to req.history.size, "budget" to budget, "real" to real, "mode" to mode,
                    "groups" to RLog.Code(ToolRouter.label(tools)))
                noteFixed(req.system, tools, mode, if (history.isEmpty()) real else null, fixed)
                break
            }
        }
        val l = live!!
        // After this exchange the client's history holds two more turns (the question and the reply).
        l.historyCount = req.historyCount + 2
        val resend: (BrainPolicy.Next) -> Unit = { next ->
            l.dirty = true
            val nextBudget = (next as? BrainPolicy.Next.Shorten)?.historyBudget ?: attempt.historyBudget
            startGenerate(reqId, req, image, sink, Attempt(attempt.sends + 1, nextBudget, turns))
        }
        val run = Run(reqId, req.convId, l, sink, rebuilt, resend, Attempt(attempt.sends, budget, turns))
        val parts = buildList {
            image?.let { add(Content.ImageBytes(it)) }
            add(Content.Text(req.text))
        }
        send(run) { cb -> l.conv.sendMessageAsync(Message.user(Contents.of(parts)), cb) }
    }

    fun toolResult(reqId: Int, req: BrainMsg.ToolResult, sink: Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (afterRunning { toolResult(reqId, req, sink) }) return
        val l = convs[req.convId]
        if (l == null || !l.awaitingTool || l.dirty) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "no tool call pending"))
        l.awaitingTool = false
        // Room for the results and a reply after them? If not, the actions stand and the reply ends here; the
        // conversation is rebuilt (shorter) from the history next time.
        val responses = req.results.sumOf { (n, r) -> TokenEstimate.text(n) + TokenEstimate.text(Json.obj(r).toString()) } + TokenEstimate.TURN_OVERHEAD
        val live = tokenCount(l.conv)
        if (!ContextWindow.roomForToolRound(live, responses)) {
            convs.remove(req.convId)
            closeQuietly(l.conv)
            RLog.i("brain_tool_round_full", "tokens" to live, "responses" to responses)
            return sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.CONTEXT_FULL, live, -1, -1.0, false, l.mode == ToolMode.TEXT_CALLS))
        }
        val run = Run(reqId, req.convId, l, sink, rebuilt = false, resend = null, attempt = Attempt(1, null, 0))
        val parts = req.results.map { (name, response) -> Content.ToolResponse(name, response) }
        send(run) { cb -> l.conv.sendMessageAsync(Message.tool(Contents.of(parts)), cb) }
    }

    /** If a reply is running, cancels it and queues [next] for when it ends (the newest request wins). */
    private fun afterRunning(next: () -> Unit): Boolean {
        val r = running ?: return false
        if (r.stopReason == null) {
            r.stopReason = DoneReason.CANCELLED
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
        }
        queued = next
        return true
    }

    fun cancel(target: Int) {
        val r = running ?: return
        if (r.reqId != target || r.stopReason != null) return
        r.stopReason = DoneReason.CANCELLED
        try { r.live.conv.cancelProcess() } catch (_: Exception) {}
    }

    private fun tokenCount(c: Conversation, unknown: Int = Int.MAX_VALUE / 2): Int = try { c.getTokenCount() } catch (_: Exception) { unknown }

    private fun send(run: Run, start: (MessageCallback) -> Unit) {
        running = run
        val cb = object : MessageCallback {
            override fun onMessage(message: Message) { handler.post { onChunk(run, message) } }
            override fun onDone() { run.nativeEnded = true; handler.post { onEnd(run, null); afterNative(run) } }
            override fun onError(throwable: Throwable) { run.nativeEnded = true; handler.post { onEnd(run, throwable); afterNative(run) } }
        }
        try {
            start(cb)
        } catch (ex: Exception) {
            run.nativeEnded = true
            onEnd(run, ex)
        }
    }

    /** LiteRT is done with this run's conversation: an engine parked under it can be closed now. */
    private fun afterNative(run: Run) {
        if (parked?.run === run) closeParked()
    }

    private fun onChunk(run: Run, m: Message) {
        if (run.ended) return
        if (m.toolCalls.isNotEmpty()) run.calls += m.toolCalls.map { RawToolCall(it.name, it.arguments) }
        if (!run.thoughtSent && !m.channels["thought"].isNullOrEmpty()) {
            run.thoughtSent = true
            run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk("", thought = true))
        }
        val text = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        if (text.isEmpty() || run.stopReason != null) return
        if (run.tFirst < 0) run.tFirst = SystemClock.elapsedRealtime()
        run.chunks++
        run.reply.append(text)
        run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk(text))
        val stop = when {
            garbage(text, run.reply) -> DoneReason.GARBAGE
            run.chunks >= Limits.MAX_REPLY_CHUNKS -> DoneReason.LENGTH
            else -> null
        }
        if (stop != null) {
            run.stopReason = stop
            try { run.live.conv.cancelProcess() } catch (_: Exception) {}
        }
    }

    private fun onEnd(run: Run, error: Throwable?) {
        if (run.ended) return
        run.ended = true
        if (error == null || error is CancellationException || run.stopReason != null) {
            val reason = run.stopReason ?: if (error != null) DoneReason.CANCELLED else if (run.calls.isNotEmpty()) DoneReason.TOOL_CALLS else DoneReason.END
            if (reason == DoneReason.TOOL_CALLS) {
                run.live.awaitingTool = true
                run.sink.emit(BrainProtocol.EV_TOOL_CALLS, BrainMsg.ToolCallsMsg(run.calls))
            }
            // A stopped reply leaves the conversation in an unknown state: rebuild it next time.
            if (reason != DoneReason.END && reason != DoneReason.TOOL_CALLS) run.live.dirty = true
            finish(run, reason)
            return
        }
        // A failed send. The message is classified, never logged (it could quote the model's output).
        run.live.dirty = true
        val msg = error.message
        val hadOutput = run.chunks > 0 || run.calls.isNotEmpty()
        val next = if (run.resend != null) {
            BrainPolicy.afterSendError(msg, run.live.mode, hadOutput, run.attempt.historyTurns, run.attempt.historyBudget ?: 0, run.attempt.sends)
        } else {
            // Tool results can't be sent again (the conversation that asked for the tools is gone with the error).
            if (!hadOutput && EngineErrors.isContextFull(msg)) BrainPolicy.Next.Stop(DoneReason.CONTEXT_FULL)
            else BrainPolicy.afterSendError(msg, run.live.mode, hadOutput = true, historyTurns = 0, historyBudget = 0, sends = BrainPolicy.MAX_SENDS)
        }
        RLog.w("brain_send", error, "chunks" to run.chunks, "next" to RLog.Code(next.javaClass.simpleName), "sends" to run.attempt.sends, "mode" to run.live.mode)
        when (next) {
            is BrainPolicy.Next.StepDown -> {
                if (next.to < toolMode) toolMode = next.to
                RLog.i("brain_tool_mode_down", "to" to toolMode, "at" to RLog.Code("send"))
                running = null
                run.resend!!.invoke(next)
            }
            is BrainPolicy.Next.Shorten -> {
                running = null
                run.resend!!.invoke(next)
            }
            is BrainPolicy.Next.Stop -> finish(run, next.reason)
            is BrainPolicy.Next.Fail -> {
                // Output already reached the app, so nothing is resent; a genuine parse error still lowers the level for next time.
                val after = BrainPolicy.modeAfter(msg, run.live.mode)
                if (after < toolMode) {
                    toolMode = after
                    RLog.i("brain_tool_mode_down", "to" to toolMode, "at" to RLog.Code("reply"))
                }
                running = null
                run.sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(next.code, error.javaClass.simpleName))
                runQueued()
            }
        }
    }

    private fun finish(run: Run, reason: DoneReason) {
        run.ended = true
        if (running === run) running = null
        // Only ask the conversation for numbers once LiteRT is done with it.
        val idle = run.nativeEnded
        val bench = if (idle) try { run.live.conv.getBenchmarkInfo() } catch (_: Throwable) { null } else null
        val tokens = if (idle && run.live.conv.isAlive) tokenCount(run.live.conv) else -1
        val ttft = if (run.tFirst >= 0) run.tFirst - run.tSend else -1
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, bench?.lastDecodeTokensPerSecond ?: -1.0, run.rebuilt, run.live.mode == ToolMode.TEXT_CALLS))
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens,
            "tps" to (bench?.lastDecodeTokensPerSecond ?: -1.0), "rebuilt" to run.rebuilt, "calls" to run.calls.size, "mode" to run.live.mode)
        runQueued()
    }

    private fun runQueued() {
        val q = queued
        if (q == null) {
            countIdle()
            return
        }
        queued = null
        q()
    }

    /**
     * The real token count of the system prompt plus [tools] at [mode], logged once per engine load: [real] is the
     * engine's count of a conversation built without history (exactly that); with history it is measured later, when
     * :brain is idle, on an empty conversation of its own ([countIdle]).
     */
    private fun noteFixed(system: String, tools: List<ToolName>, mode: ToolMode, real: Int?, estimate: Int) {
        val key = "${ToolRouter.label(tools)}/${tools.size}/$mode"
        if (key in counted) return
        if (real == null || real < 0) {
            uncounted[key] = Triple(system, tools, mode)
            return
        }
        counted += key
        uncounted.remove(key)
        logFixed(tools, mode, real, estimate)
    }

    private fun logFixed(tools: List<ToolName>, mode: ToolMode, real: Int, estimate: Int) {
        RLog.i("brain_fixed_tokens", "groups" to RLog.Code(ToolRouter.label(tools)), "tools" to tools.size, "mode" to mode, "tokens" to real, "estimate" to estimate)
    }

    /**
     * Between replies: measures one tool set seen only with history (an empty conversation, closed at once). Not while
     * a conversation waits for tool results: those come right away and go first.
     */
    private fun countIdle() {
        if (running != null || engine == null || parked != null || convs.values.any { it.awaitingTool }) return
        val (key, v) = uncounted.entries.firstOrNull()?.toPair() ?: return
        uncounted.remove(key)
        if (key in counted) return
        counted += key
        val (system, tools, mode) = v
        if (mode != toolMode) return
        val conv = try { newConversation(system, emptyList(), mode, tools) } catch (ex: Exception) { RLog.w("brain_fixed_tokens", ex); return }
        try {
            logFixed(tools, mode, tokenCount(conv, unknown = -1), Prompt.fixedTokens(system, tools))
        } finally {
            closeQuietly(conv)
        }
    }

    companion object {
        /** How long unload waits for a cancelled decode to end before parking the engine instead of closing it. */
        private const val UNLOAD_WAIT_MS = 10_000
        private const val STOPPING = "The model is still stopping. Try again in a moment."
        /** Engines parked in this process, by any BrainService instance (a new one may be created before the old one's closes). */
        private val parkedInProcess = AtomicInteger()
    }

    /** Broken output (as the lab detects it): pad/unused tokens, or one character 32+ times in a row. */
    private fun garbage(chunk: String, reply: CharSequence): Boolean {
        if (chunk.contains("<pad>") || chunk.contains("<unused")) return true
        if (reply.length < 32) return false
        val c = reply[reply.length - 1]
        if (c.isWhitespace()) return false
        for (i in reply.length - 32 until reply.length) if (reply[i] != c) return false
        return true
    }
}
