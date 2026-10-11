@file:OptIn(ExperimentalApi::class)

package app.heyrobot.brain

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ControlText
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.EngineErrors
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.FileByteSource
import app.heyrobot.core.Json
import app.heyrobot.core.Limits
import app.heyrobot.core.LiteRtLmCheck
import app.heyrobot.core.LiteRtLmReport
import app.heyrobot.core.LiveConv
import app.heyrobot.core.LoadCheck
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptMarkers
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ThinkFilter
import app.heyrobot.core.Thinking
import app.heyrobot.core.ThinkingPlan
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolMode
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.readOrNull
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
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolProvider
import com.google.ai.edge.litertlm.tool
import java.io.File
import java.util.concurrent.CancellationException

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
// shorten, give up) are core's BrainPolicy; this file only acts on them. 0.4.3: your own file built for 2,048 to 4,095
// tokens loads with exactly its own context (LoadCheck and the header read here must both say so) and runs as chat
// only (ToolMode.CHAT_ONLY): no tools described, no text-call hint, no step down, its calls dropped, its history
// alternating, every budget from its context. 0.4.5: one of :brain's two engines (Engine.kt); the process's one slot is
// EngineSlot, shared with LlamaEngine. Your own Qwen3 or MiniCPM 5 file is told what Settings › Model › "Let the model
// think first" says (core Thinking), and its thinking is kept out of the reply. LiteRT-LM encodes the rendered
// conversation with the model's tokenizer, which reads Gemma 4's "<|turn>", "<|tool_call>"… (SentencePiece user-defined
// pieces) wherever the text has them: your question, the history and the tools' results reach it with the file's special
// tokens broken (core ControlText, from its tokenizer: LiteRtLmCheck.tokens); Robot's own system prompt as it is.

class GemmaEngine(private val ctx: Context, private val handler: Handler) : BrainEngine, SlotHolder {
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
    /** When BrainService.onDestroy ran (elapsed ms; 0: it lives), read by a newer service waiting for the engine slot. */
    @Volatile override var destroyedAt = 0L
        private set
    /** The loaded file's special tokens, broken in every text from outside Robot's own prompt (core ControlText). */
    private var controls = ControlText(PromptMarkers.KNOWN)
    /** Your own file's LlmMetadata model type (for its thinking switch); null for Robot's own models. */
    private var ownModelType: String? = null
    /**
     * The fixed part's token count (system prompt plus tools) is logged once per tool set and mode per engine load
     * (docs/phase-2.md "Logging"): counts only. [counted] holds the sets logged exactly; [estimated] those logged as an
     * estimate, which an exact count later replaces.
     */
    private val counted = HashSet<String>()
    private val estimated = HashSet<String>()

    private class Parked(val engine: Engine, val convs: List<Conversation>, val run: Run)

    /** [system]: the system prompt as this conversation was made with it, its thinking switch included (a change rebuilds it). */
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
        val sink: BrainEngine.Sink,
        val rebuilt: Boolean,
        /** Send the same request again on a fresh conversation ([BrainPolicy.Next.StepDown] / [BrainPolicy.Next.Shorten]); null for tool results. */
        val resend: ((BrainPolicy.Next) -> Unit)?,
        val attempt: Attempt,
        /** Takes the thinking out of the reply (your own Qwen3 or MiniCPM file); null: the text passes as it is. */
        val filter: ThinkFilter? = null,
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

    override fun status() = BrainMsg.Status(loaded = engine != null, vision = vision, constrained = mode() == ToolMode.TOOLS_CONSTRAINED, busy = running != null,
        model = loaded?.model?.takeIf { engine != null }, backend = loaded?.backend?.takeIf { engine != null },
        maxTokens = loaded?.maxTokens?.takeIf { engine != null } ?: 0, format = ModelFormat.LITERT_LM.takeIf { engine != null })

    /** The loaded engine's context (4,096 when none is loaded: nothing is sent then anyway). */
    private fun maxTokens(): Int = loaded?.maxTokens ?: ContextWindow.MAX_TOKENS

    /** The level conversations are built at now: chat only for a short-context model, else the tool level. */
    private fun mode(): ToolMode = BrainPolicy.modeFor(maxTokens(), toolMode)

    private fun failed(req: BrainMsg.Load, failure: LoadFailure, error: String, ms: Long = 0): BrainMsg.Loaded {
        RLog.i("brain_load", "ok" to false, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "failure" to failure)
        return BrainMsg.Loaded(false, false, ms, error, req.model, req.backend, failure, ModelFormat.LITERT_LM)
    }

    override fun load(req: BrainMsg.Load): BrainMsg.Loaded {
        // A GGUF file is LlamaEngine's (Engines sends it there); never parsed by LiteRT-LM.
        if (req.format != ModelFormat.LITERT_LM) return failed(req, LoadFailure.NOT_ALLOWED, "LiteRT-LM runs .litertlm files only.")
        val current = loaded
        if (engine != null && current != null) {
            if (current.model == req.model && current.backend == req.backend && current.path == req.path && current.maxTokens == req.maxTokens) {
                return BrainMsg.Loaded(true, vision, 0, null, req.model, req.backend, format = ModelFormat.LITERT_LM)
            }
            // Another model: the engine in memory goes first, so two never share the process (Fast about 2 GB on the
            // TPU, an E4B about 4 GB on the CPU). If a reply still holds it, it is parked and this load waits (STOPPING).
            RLog.i("brain_switch", "from" to RLog.Code(current.model.id), "to" to RLog.Code(req.model.id))
            unload("switch")
        }
        parked?.let { p -> if (p.run.nativeEnded) closeParked() else return failed(req, LoadFailure.STOPPING, STOPPING) }
        // Nor beside an engine an earlier BrainService (or the other engine) in this process parked: that would be a second model in memory.
        if (!EngineTeardown.mayLoad(EngineSlot.parked.get())) return failed(req, LoadFailure.STOPPING, STOPPING)
        val f = File(req.path)
        // Only a model file in Robot's own storage, and Fast only from its own pinned file.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile || f.name != req.model.file) return failed(req, LoadFailure.NO_FILE, "The model file is missing.")
        // The app's own records, read here again rather than trusted from the request: a built-in model's SHA-256 mark
        // for this very file, or your own file's record (unchanged since its check; the TPU only once you allowed it); and
        // the context Robot's prompts are made for with it (4,096, or a short file's own: 0.4.3).
        val records = File(ctx.filesDir, "robot")
        LoadCheck.refuse(
            req.model, req.backend, ModelFiles.fingerprint(f.length(), f.lastModified()),
            mark = if (req.model.builtIn) readOrNull(File(records, ModelFiles.verifiedMark(req.model))) else null,
            ownRecord = if (req.model == BrainModel.OWN) readOrNull(File(records, ModelFiles.OWN_RECORD)) else null,
            maxTokens = req.maxTokens, format = ModelFormat.LITERT_LM,
        )?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        // The TPU only for Fast's pinned file (its SHA-256 mark, just above) or a file whose contents prove a Tensor G5
        // build and that you allowed there; and never TPU code on the CPU. Read here again, from the file about to load,
        // with its context: never a KV cache larger than the file was built with, nor a short one for a file that isn't.
        var header: LiteRtLmReport? = null
        if (req.model != BrainModel.FAST || req.backend != ModelBackend.TPU) {
            val r = LiteRtLmCheck.check(f)
            val ok = when (req.backend) {
                ModelBackend.TPU -> r.tensorG5Build
                ModelBackend.CPU -> r.backend == ModelBackend.CPU
            }
            if (!ok) return failed(req, LoadFailure.NOT_ALLOWED, r.unusable ?: "Robot's check of the file says it can't run on the ${req.backend.title}.")
            if (r.contextTokens != req.maxTokens) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.wrongContext(r.contextTokens))
            header = r
        }
        val cacheDir = ModelFiles.cacheDir(req.model, req.backend)?.let { File(ctx.filesDir, it) }
        if (cacheDir != null) {
            cacheDir.mkdirs()
            // The first load writes the weight cache (about 2.5 GB for E4B): never start one that can't finish.
            // Every file under it, as Settings › Model counts it (ModelStore.cacheBytes), so a cache already written is
            // never taken for one still to write.
            val cached = try { cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
            val need = (if (req.model == BrainModel.OWN) ModelFiles.cacheEstimate(f.length()) else req.model.cacheBytes) - cached
            if (need > 0 && cacheDir.usableSpace in 0 until need + CACHE_SPARE) {
                return failed(req, LoadFailure.NO_SPACE, "There isn't enough free space for the cache it writes on its first load (about ${gb(need)}).")
            }
        }
        // One engine in the whole process, whichever BrainService loads it: an earlier service destroyed while its thread
        // was still loading or letting its engine go keeps that engine until its queued unload runs. Marked from the start
        // too: the app must not delete the file (or its cache) while it loads, either.
        when (EngineTeardown.forLoad(!EngineSlot.claim(this, ctx, req), EngineSlot.holderDestroyedMs(this))) {
            EngineTeardown.LoadStep.LOAD -> {}
            EngineTeardown.LoadStep.WAIT -> return failed(req, LoadFailure.STOPPING, STOPPING)
            EngineTeardown.LoadStep.END_PROCESS -> {
                RLog.i("brain_teardown", "step" to EngineTeardown.LoadStep.END_PROCESS)
                android.os.Process.killProcess(android.os.Process.myPid())
                return failed(req, LoadFailure.STOPPING, STOPPING)
            }
        }
        if (current != null && current.model != req.model) toolMode = ToolMode.TOOLS_CONSTRAINED
        ExperimentalFlags.enableBenchmark = true
        // MTP drafting: LiteRT-LM's default (off) on the TPU, as since 0.1; the A/B constant on the CPU.
        ExperimentalFlags.enableSpeculativeDecoding = if (req.backend == ModelBackend.CPU) CPU_MTP else null
        val t0 = SystemClock.elapsedRealtime()
        var e: Engine? = null
        var visionError: String? = null
        var ok = false
        try {
            // Vision with a fixed 280-token image budget (read when the engine initialises), so the 300 tokens per image
            // Robot plans for hold; if this build refuses the budget, vision without it; then text only. 0.4.3: the budget
            // is Gemma 4's (other models' encoders give a fixed count: MedGemma's adapter 257), so only a Gemma 4 file gets
            // it; and a file whose header has no vision encoder goes straight to text only (one load, not two). Your own
            // file only: a built-in model (its SHA-256 checked just above) loads exactly as in 0.4.2, whatever this
            // header reader makes of its sections, so Fast and the E4B models never lose their vision to it.
            var budgetSet = false
            val budgets = when {
                req.model.builtIn || header == null -> listOf(TokenEstimate.VISUAL_TOKEN_BUDGET, null)
                !header.vision -> emptyList()
                header.gemma4 -> listOf(TokenEstimate.VISUAL_TOKEN_BUDGET, null)
                else -> listOf(null)
            }
            if (budgets.isEmpty()) visionError = "NoVisionEncoder"
            for (budget in budgets) {
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
                        req.model, req.backend, LoadFailure.ENGINE, ModelFormat.LITERT_LM)
                }
            }
            engine = e
            loaded = req
            ownModelType = if (req.model == BrainModel.OWN) header?.modelType else null
            ok = true
            counted.clear()
            estimated.clear()
            val ms = SystemClock.elapsedRealtime() - t0
            // Its tokenizer's special tokens, which no text from outside Robot's prompt may write. A built-in file (its
            // SHA-256 checked above) is Gemma 4's, whose tokenizer encodes the joiner as itself; your own file whose tokenizer
            // can't be read gets the known markers broken by a plain space, which every tokenizer reads as a space.
            val tokens = try { FileByteSource(f).use { LiteRtLmCheck.tokens(it) } } catch (_: Exception) { null }
            controls = tokens?.controlText() ?: ControlText(PromptMarkers.KNOWN, if (req.model.builtIn) ControlText.JOINER else ControlText.SPACE)
            val tokensMs = SystemClock.elapsedRealtime() - t0 - ms
            RLog.i("brain_load", "ok" to true, "ms" to ms, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "vision" to vision,
                "visualBudget" to budgetSet, "visionError" to visionError?.let { RLog.Code(it) }, "mtp" to (req.backend == ModelBackend.CPU && CPU_MTP == true),
                "sampler" to (req.backend == ModelBackend.CPU && CPU_SAMPLER), "maxTokens" to req.maxTokens,
                "chatOnly" to ContextWindow.chatOnly(req.maxTokens), "controls" to controls.size, "tokenizer" to (tokens != null),
                "breaker" to RLog.Code(breakerCode(controls.breaker)), "tokensMs" to tokensMs)
            return BrainMsg.Loaded(true, vision, ms, null, req.model, req.backend, format = ModelFormat.LITERT_LM)
        } finally {
            ExperimentalFlags.enableSpeculativeDecoding = null
            if (!ok) releaseSlot()
        }
    }

    private fun breakerCode(b: String) = when (b) {
        ControlText.JOINER -> "joiner"
        ControlText.ZERO_WIDTH_SPACE -> "zwsp"
        else -> "space"
    }

    /** This engine's model is gone (closed, or never made): the process's slot is free and the marker goes. */
    private fun releaseSlot() = EngineSlot.release(this, ctx)

    override fun destroyed() {
        destroyedAt = SystemClock.elapsedRealtime()
    }

    /** The sampler for a conversation: none on the TPU (as since 0.1), the A/B constants on the CPU. */
    private fun sampler(): SamplerConfig? =
        if (loaded?.backend == ModelBackend.CPU && CPU_SAMPLER) SamplerConfig(CPU_TOP_K, CPU_TOP_P, CPU_TEMPERATURE, 0) else null

    private fun gb(bytes: Long) = String.format(java.util.Locale.US, "%.1f GB", bytes / 1e9)

    override fun unload(why: String) {
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
            EngineSlot.parked.incrementAndGet()
            RLog.i("brain_unload", "why" to RLog.Code(why), "parked" to true)
            return
        }
        all.forEach(::closeQuietly)
        try { e.close() } catch (ex: Exception) { RLog.w("brain_unload", ex) }
        releaseSlot()
        RLog.i("brain_unload", "why" to RLog.Code(why))
    }

    private fun closeParked() {
        val p = parked ?: return
        parked = null
        try {
            p.convs.forEach(::closeQuietly)
            try { p.engine.close() } catch (ex: Exception) { RLog.w("brain_unload", ex) }
        } finally {
            EngineSlot.parked.decrementAndGet()
            // A parked engine kept the slot (and the marker); no other engine loads before it is closed.
            if (engine == null) releaseSlot()
        }
        RLog.i("brain_unload_parked_closed")
        teardown?.invoke()
    }

    /**
     * BrainService was destroyed (work thread): unload, then tell [step] once when the work thread may stop. With an
     * engine parked the thread must stay, since LiteRT posts the callback that closes it there; if that callback
     * never comes, END_PROCESS (EngineTeardown).
     */
    override fun shutdown(step: (EngineTeardown.Step) -> Unit) {
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

    /** What Settings › Model › "Let the model think first" means for the model in memory (nothing for Robot's own models). */
    private fun thinking(think: Boolean): ThinkingPlan = Thinking.liteRt(loaded?.model ?: BrainModel.FAST, ownModelType, think)

    private fun newConversation(system: String, history: List<PromptTurn>, mode: ToolMode, tools: List<ToolName>, plan: ThinkingPlan): Conversation {
        val e = engine ?: throw IllegalStateException("not loaded")
        val cfg = ConversationConfig(
            systemInstruction = Contents.of(plan.system(if (mode == ToolMode.TEXT_CALLS) system + Prompt.textCallHint(tools) else system)),
            // Robot's own system prompt as it is; the turns (your questions with the screen's text, Robot's and Claude's
            // replies) without the file's special tokens.
            initialMessages = history.map { if (it.role == PromptRole.USER) Message.user(controls.defuse(it.text)) else Message.model(controls.defuse(it.text)) },
            // Only the tool API's levels describe tools: never chat only (its list is empty anyway, BrainPolicy.toolsFor).
            tools = if (mode == ToolMode.TOOLS || mode == ToolMode.TOOLS_CONSTRAINED) providers(tools) else emptyList(),
            samplerConfig = sampler(),
            automaticToolCalling = false,
            // Your own MiniCPM 5 file's template (LiteRT-LM 0.18 passes both on); nothing for any other model.
            extraContext = plan.enableThinking?.let { mapOf("enable_thinking" to it) } ?: emptyMap(),
            thinkingConfig = plan.enableThinking?.let { ThinkingConfig(it) },
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

    override fun closeConv(convId: String) {
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

    override fun generate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (req.hasImage && image == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "image missing"))
        if (image != null && !vision) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, null))
        if (afterRunning { generate(reqId, req, image, sink) }) return
        startGenerate(reqId, req, image, sink, Attempt(sends = 1, historyBudget = null, historyTurns = 0))
    }

    private fun startGenerate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink, attempt: Attempt) {
        val max = maxTokens()
        // Chat only (a short-context model): no tools, whatever the request lists.
        val tools = BrainPolicy.toolsFor(mode(), req.toolNames)
        val toolIds = tools.map { it.id }
        val plan = thinking(req.think)
        // The conversation is reused only with the same thinking switch (its system prompt or its template input differ).
        val system = plan.system(req.system) + if (plan.enableThinking == true) THINK_KEY else ""
        val fixed = Prompt.fixedTokens(req.system, tools)
        val newTurn = TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text, image != null))
        // The app cuts the new turn to fit (Prompt.fitTurn); a turn that still can't fit is never sent anyway.
        if (newTurn > ContextWindow.newTurnLimit(fixed, max)) {
            RLog.i("brain_too_long", "newTurn" to newTurn, "fixed" to fixed, "maxTokens" to max)
            return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.TOO_LONG, null))
        }
        var live = convs[req.convId]
        val reuse = attempt.historyBudget == null && BrainPolicy.canReuse(
            live?.let { LiveConv(it.historyCount, it.system, it.mode, it.dirty, it.awaitingTool, tokenCount(it.conv), it.tools) },
            req.historyCount, system, mode(), newTurn, toolIds, max,
        )
        var rebuilt = false
        var budget = attempt.historyBudget ?: ContextWindow.historyBudget(fixed, newTurn, max)
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
                val mode = mode()
                // Chat only: strictly alternating turns (the chat templates of models other than Gemma 4 demand it).
                val history = ContextWindow.fit(BrainPolicy.historyFor(mode, req.history), budget)
                val conv = try {
                    newConversation(req.system, history, mode, tools, plan)
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
                val smaller = ContextWindow.shrink(budget, real, newTurn, max)
                if (smaller != null && history.isNotEmpty() && rebuilds < BrainPolicy.MAX_REBUILDS) {
                    closeQuietly(conv)
                    rebuilds++
                    RLog.i("brain_rebuild_shorter", "real" to real, "budget" to budget, "to" to smaller)
                    budget = smaller
                    continue
                }
                live = Live(conv, req.historyCount, mode, system, toolIds).also { convs[req.convId] = it }
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
        val run = Run(reqId, req.convId, l, sink, rebuilt, resend, Attempt(attempt.sends, budget, turns), if (plan.stripThinking) ThinkFilter() else null)
        val parts = buildList {
            image?.let { add(Content.ImageBytes(it)) }
            add(Content.Text(controls.defuse(req.text)))
        }
        send(run) { cb -> l.conv.sendMessageAsync(Message.user(Contents.of(parts)), cb) }
    }

    override fun toolResult(reqId: Int, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (afterRunning { toolResult(reqId, req, sink) }) return
        val l = convs[req.convId]
        if (l == null || !l.awaitingTool || l.dirty) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "no tool call pending"))
        l.awaitingTool = false
        // Room for the results and a reply after them? If not, the actions stand and the reply ends here; the
        // conversation is rebuilt (shorter) from the history next time.
        val responses = req.results.sumOf { (n, r) -> TokenEstimate.text(n) + TokenEstimate.text(Json.obj(r).toString()) } + TokenEstimate.TURN_OVERHEAD
        val live = tokenCount(l.conv)
        if (!ContextWindow.roomForToolRound(live, responses, maxTokens())) {
            convs.remove(req.convId)
            closeQuietly(l.conv)
            RLog.i("brain_tool_round_full", "tokens" to live, "responses" to responses)
            return sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.CONTEXT_FULL, live, -1, -1.0, false, l.mode == ToolMode.TEXT_CALLS))
        }
        val run = Run(reqId, req.convId, l, sink, rebuilt = false, resend = null, attempt = Attempt(1, null, 0),
            filter = if (thinking(false).stripThinking) ThinkFilter() else null)
        // What a tool found (an event's title, a contact's name, a note) is text from outside Robot's prompt too.
        val parts = req.results.map { (name, response) -> Content.ToolResponse(name, controls.defuse(response)) }
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

    override fun cancel(target: Int) {
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
        // Chat only: no tool was described, so a call is never passed on (it would reach the approval gate). The
        // conversation now holds a call no answer will follow: it is rebuilt from the history (text only) next time.
        if (m.toolCalls.isNotEmpty()) {
            if (BrainPolicy.acceptsCalls(run.live.mode)) run.calls += m.toolCalls.map { RawToolCall(it.name, it.arguments) }
            else {
                run.live.dirty = true
                RLog.i("brain_calls_dropped", "calls" to m.toolCalls.size, "mode" to run.live.mode)
            }
        }
        val raw = m.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
        // Your own file's <think> block, when LiteRT-LM leaves it in the text: never shown, never spoken.
        val text = run.filter?.feed(raw) ?: raw
        if (!run.thoughtSent && (!m.channels["thought"].isNullOrEmpty() || run.filter?.thought == true)) {
            run.thoughtSent = true
            run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk("", thought = true))
        }
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
        // What the thinking filter still held back (the end of a reply shorter than a tag).
        if (run.stopReason == null && run.chunks < Limits.MAX_REPLY_CHUNKS) run.filter?.flush()?.takeIf { it.isNotEmpty() }?.let { rest ->
            run.reply.append(rest)
            run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk(rest))
        }
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
                // Only a step down lowers the level (chat only never steps: it is no level).
                if (after != run.live.mode && after < toolMode) {
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
        /** Marks a conversation made with thinking on through the template (no system prompt change shows it). */
        private const val THINK_KEY = "\u0000think"
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
