@file:OptIn(ExperimentalApi::class)

package app.heyrobot.brain

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.EngineErrors
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.Json
import app.heyrobot.core.Limits
import app.heyrobot.core.LiteRtLmCheck
import app.heyrobot.core.LiveConv
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
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
import app.heyrobot.core.writeAtomic
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
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger

// :BRAIN PROCESS ONLY. The one file that uses LiteRT-LM (ProcessIsolationTest enforces that).
//
// The model the assistant asks for (0.4.1, BrainMsg.Load), one engine at a time: another model is unloaded first.
// On the TPU (Fast, Gemma 4 E2B) exactly as the probe and the GPU lab (config T) load it: Backend.NPU(nativeLibraryDir),
// the vision encoder on the NPU with a text-only fallback, maxNumTokens 4096, cacheDir null, no sampler. On the CPU
// (Gemma 4 E4B, or your own file) as the lab's run E: Backend.CPU() with LiteRT-LM's default threads (6 threads were no
// faster), the vision encoder on the CPU too (the generic files' vision runs only there), and the model's own cache
// folder for XNNPACK's weight cache (about 2.5 GB, written on the first load). MTP and the sampler on the CPU are the
// constants below, for an A/B test on the phone. Every method runs on BrainService's work thread; LiteRT's
// callbacks are posted back to it, so the state below has a single thread. The decisions (reuse, step down,
// shorten, give up) are core's BrainPolicy; this file only acts on them.

class GemmaEngine(private val ctx: Context, private val handler: Handler) {
    fun interface Sink { fun emit(what: Int, msg: BrainMsg) }

    private var engine: Engine? = null
    /** What [engine] is: the model, where it runs, its file. */
    private var loaded: BrainMsg.Load? = null
    private var vision = false
    /** How tools reach the model; stepped down for good (until the process restarts or another model loads) only on the tool-parse error. */
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
     * The fixed part's token count (system prompt plus tools) is logged once per tool set and mode per engine load
     * (docs/phase-2.md "Logging"): counts only. [counted] holds the sets logged exactly; [estimated] those logged as an
     * estimate, which an exact count later replaces.
     */
    private val counted = HashSet<String>()
    private val estimated = HashSet<String>()

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

    private fun backend(b: ModelBackend): Backend = if (b == ModelBackend.TPU) npu() else Backend.CPU()

    private fun config(req: BrainMsg.Load, withVision: Boolean, cacheDir: File?) = EngineConfig(
        modelPath = req.path,
        backend = backend(req.backend),
        visionBackend = if (withVision) backend(req.backend) else null,
        audioBackend = null,
        maxNumTokens = req.maxTokens,
        cacheDir = cacheDir?.path,
    )

    fun status() = BrainMsg.Status(loaded = engine != null, vision = vision, constrained = toolMode == ToolMode.TOOLS_CONSTRAINED, busy = running != null,
        model = loaded?.model?.takeIf { engine != null }, backend = loaded?.backend?.takeIf { engine != null })

    private fun failed(req: BrainMsg.Load, failure: LoadFailure, error: String, ms: Long = 0): BrainMsg.Loaded {
        RLog.i("brain_load", "ok" to false, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "failure" to failure)
        return BrainMsg.Loaded(false, false, ms, error, req.model, req.backend, failure)
    }

    fun load(req: BrainMsg.Load): BrainMsg.Loaded {
        val current = loaded
        if (engine != null && current != null) {
            if (current.model == req.model && current.backend == req.backend && current.path == req.path) {
                return BrainMsg.Loaded(true, vision, 0, null, req.model, req.backend)
            }
            // Another model: the engine in memory goes first, so two never share the process (Fast about 2 GB on the
            // TPU, an E4B about 4 GB on the CPU). If a reply still holds it, it is parked and this load waits (STOPPING).
            RLog.i("brain_switch", "from" to RLog.Code(current.model.id), "to" to RLog.Code(req.model.id))
            unload("switch")
        }
        parked?.let { p -> if (p.run.nativeEnded) closeParked() else return failed(req, LoadFailure.STOPPING, STOPPING) }
        // Nor beside an engine an earlier BrainService in this process parked: that would be a second model in memory.
        if (!EngineTeardown.mayLoad(parkedInProcess.get())) return failed(req, LoadFailure.STOPPING, STOPPING)
        val f = File(req.path)
        // Only a model file in Robot's own storage, and Fast only from its own pinned file.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile || f.name != req.model.file) return failed(req, LoadFailure.NO_FILE, "The model file is missing.")
        if (req.maxTokens != ContextWindow.MAX_TOKENS) return failed(req, LoadFailure.NOT_ALLOWED, "Robot's prompts are made for ${ContextWindow.MAX_TOKENS} tokens.")
        // The TPU only for Fast's pinned file (checked by its SHA-256 in the app) or a file whose contents prove a Tensor G5
        // build; and never TPU code on the CPU. Read here again, from the file about to load, whatever the app recorded.
        if (req.model != BrainModel.FAST || req.backend != ModelBackend.TPU) {
            val r = LiteRtLmCheck.check(f)
            val ok = when (req.backend) {
                ModelBackend.TPU -> r.tensorG5Build
                ModelBackend.CPU -> r.backend == ModelBackend.CPU
            }
            if (!ok) return failed(req, LoadFailure.NOT_ALLOWED, r.unusable ?: "Robot's check of the file says it can't run on the ${req.backend.title}.")
        }
        val cacheDir = ModelFiles.cacheDir(req.model, req.backend)?.let { File(ctx.filesDir, it) }
        if (cacheDir != null) {
            cacheDir.mkdirs()
            // The first load writes the weight cache (about 2.5 GB for E4B): never start one that can't finish.
            val cached = cacheDir.listFiles()?.sumOf { it.length() } ?: 0L
            val need = (if (req.model == BrainModel.OWN) ModelFiles.cacheEstimate(f.length()) else req.model.cacheBytes) - cached
            if (need > 0 && cacheDir.usableSpace in 0 until need + CACHE_SPARE) {
                return failed(req, LoadFailure.NO_SPACE, "There isn't enough free space for the cache it writes on its first load (about ${gb(need)}).")
            }
        }
        if (current != null && current.model != req.model) toolMode = ToolMode.TOOLS_CONSTRAINED
        ExperimentalFlags.enableBenchmark = true
        // MTP drafting: LiteRT-LM's default (off) on the TPU, as since 0.1; the A/B constant on the CPU.
        ExperimentalFlags.enableSpeculativeDecoding = if (req.backend == ModelBackend.CPU) CPU_MTP else null
        val t0 = SystemClock.elapsedRealtime()
        var e: Engine? = null
        var visionError: String? = null
        try {
            // Vision with a fixed 280-token image budget (read when the engine initialises), so the 300 tokens per image
            // Robot plans for hold; if this build refuses the budget, vision without it; then text only.
            var budgetSet = false
            for (budget in listOf(TokenEstimate.VISUAL_TOKEN_BUDGET, null)) {
                try {
                    ExperimentalFlags.visualTokenBudget = budget
                    e = Engine(config(req, withVision = true, cacheDir))
                    e.initialize()
                    // The vision encoder starts with the first conversation: open one so a failure shows here.
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
                    e = Engine(config(req, withVision = false, cacheDir))
                    e.initialize()
                } catch (ex: Exception) {
                    try { e?.close() } catch (_: Exception) {}
                    RLog.w("brain_load", ex, "ok" to false, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id))
                    return BrainMsg.Loaded(false, false, SystemClock.elapsedRealtime() - t0, "The model didn't load (${ex.javaClass.simpleName}).",
                        req.model, req.backend, LoadFailure.ENGINE)
                }
            }
            engine = e
            loaded = req
            counted.clear()
            estimated.clear()
            markLoaded(req)
            val ms = SystemClock.elapsedRealtime() - t0
            RLog.i("brain_load", "ok" to true, "ms" to ms, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "vision" to vision,
                "visualBudget" to budgetSet, "visionError" to visionError?.let { RLog.Code(it) }, "mtp" to (req.backend == ModelBackend.CPU && CPU_MTP == true),
                "sampler" to (req.backend == ModelBackend.CPU && CPU_SAMPLER))
            return BrainMsg.Loaded(true, vision, ms, null, req.model, req.backend)
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = null
        }
    }

    /** filesDir/robot/brain_loaded.txt: which model is in memory, so the app never deletes it under the engine. */
    private fun loadedFile() = File(File(ctx.filesDir, "robot"), ModelFiles.LOADED)

    private fun markLoaded(req: BrainMsg.Load) {
        try { writeAtomic(loadedFile(), BrainLoaded(req.model, req.backend, android.os.Process.myPid()).encode()) } catch (t: Throwable) { RLog.w("brain_loaded_mark", t) }
    }

    private fun markUnloaded() {
        try { loadedFile().delete() } catch (_: Throwable) {}
    }

    /** The sampler for a conversation: none on the TPU (as since 0.1), the A/B constants on the CPU. */
    private fun sampler(): SamplerConfig? =
        if (loaded?.backend == ModelBackend.CPU && CPU_SAMPLER) SamplerConfig(CPU_TOP_K, CPU_TOP_P, CPU_TEMPERATURE, 0) else null

    private fun gb(bytes: Long) = String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)

    fun unload(why: String) {
        val e = engine ?: return
        queued = null
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
        // Parked or closed, it no longer answers; the marker goes once its memory does (closeParked), so the app still
        // keeps its file meanwhile.
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
        markUnloaded()
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
            if (engine == null) markUnloaded()
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
            samplerConfig = sampler(),
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
            // One LiteRT conversation at a time: this one's old state and any other (idle: a request waits for a
            // running reply to end) are closed before a new one is built.
            convs.values.forEach { closeQuietly(it.conv) }
            convs.clear()
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
                noteFixed(tools, mode, real, history, fixed)
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
        val q = queued ?: return
        queued = null
        q()
    }

    /**
     * Logs the fixed cost of [tools] at [mode] once per engine load, from the conversation just built (never a second
     * one: one LiteRT conversation at a time is what the TPU was proven with, and the CPU runs the same way). LiteRT-LM 0.18 takes the history only
     * at creation, so [real] counts system, tools and [history]: exact when there was no history, otherwise [real] less
     * the history's estimate, logged as estimated until an exact count comes.
     */
    private fun noteFixed(tools: List<ToolName>, mode: ToolMode, real: Int, history: List<PromptTurn>, estimate: Int) {
        if (real <= 0) return
        val key = "${ToolRouter.label(tools)}/${tools.size}/$mode"
        if (key in counted) return
        val exact = history.isEmpty()
        if (!exact && key in estimated) return
        val tokens = if (exact) real else real - history.sumOf(TokenEstimate::turn)
        if (exact) counted += key else estimated += key
        RLog.i("brain_fixed_tokens", "groups" to RLog.Code(ToolRouter.label(tools)), "tools" to tools.size, "mode" to mode, "tokens" to tokens,
            "kind" to RLog.Code(if (exact) "exact" else "estimated"), "estimate" to estimate)
    }

    companion object {
        /**
         * A/B on the phone (README "On the phone (0.4.1)"): MTP speculative decoding for the CPU models, whose files carry
         * an MTP drafter. null is LiteRT-LM's default (off). Google measured 1.2 to 1.7× faster writing on a Galaxy S26's
         * CPU; on this phone's GPU it was slower, and on its CPU it is unmeasured. Try true.
         */
        private val CPU_MTP: Boolean? = null
        /**
         * A/B: a sampler for the CPU models (false: none, as on the TPU). Abliterated models can ramble without one; try
         * true with Gemma's own values below, and compare the answers.
         */
        private const val CPU_SAMPLER = false
        private const val CPU_TOP_K = 64
        private const val CPU_TOP_P = 0.95
        private const val CPU_TEMPERATURE = 1.0
        /** Free space left over once a first-load cache is written. */
        private const val CACHE_SPARE = 500_000_000L
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
