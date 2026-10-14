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
import app.heyrobot.core.BrainScheduler
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
import app.heyrobot.core.ModelMove
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Phone
import app.heyrobot.core.Priority
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
import java.io.FileInputStream
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
// think first" says (core Thinking), and its thinking is kept out of the reply. 0.4.7: the general E2B (Fast on a phone
// without the Tensor G5's TPU build, a Pixel 9a) on the GPU: Backend.GPU() (LiteRT-LM's OpenCL accelerator, linked into
// liblitertlm_jni.so, with the phone's own libOpenCL.so, declared in this module's manifest), its vision encoder on the
// CPU (the general file's vision runs only there), the model's cache folder for the GPU's program and weight caches, no
// sampler and MTP off, as on the TPU. Backend.NPU, and with it the TPU dispatch library, only ever for a TPU load, and only
// with the build for this phone's own chip (core LoadCheck, with this phone's Build fields; 0.8: the Tensor G5's, or in beta
// the Tensor G6's: the phone's TPU library refuses another chip's code, and so does Robot first). LiteRT-LM encodes the rendered
// conversation with the model's tokenizer, which reads Gemma 4's "<|turn>", "<|tool_call>"… (SentencePiece user-defined
// pieces) wherever the text has them: your question, the history and the tools' results reach it with the file's special
// tokens broken (core ControlText, from its tokenizer: LiteRtLmCheck.tokens); Robot's own system prompt as it is.
//
// 0.5.0 (docs/conversation-mode.md 4.2): requests wait their turn in a priority queue (core BrainScheduler: the panel,
// then the interpreter, then transcript translations, then summaries; the panel's newest question still wins over its
// older ones), and REQ_ONE_SHOT runs one message in a conversation of its own (core OneShotRules: its own system prompt,
// no history, no tools, constrained decoding off, thinking off), streamed, then closed. Still one LiteRT conversation at
// a time: a one-shot closes the assistant's, which is rebuilt from the app's history at its next question; a one-shot
// never touches the tool level, is never sent again, and a call it writes is dropped.

class GemmaEngine(private val ctx: Context, private val handler: Handler) : BrainEngine, SlotHolder {
    private var engine: Engine? = null
    /** What [engine] is: the model, where it runs, its file. */
    private var loaded: BrainMsg.Load? = null
    private var vision = false
    /** How tools reach the model; stepped down for good (until the process restarts or another model loads) only on the tool-parse error. */
    private var toolMode = ToolMode.TOOLS_CONSTRAINED
    private val convs = HashMap<String, Live>()
    private var running: Run? = null
    /** Every request waits its turn here (0.5.0): one runs at a time, in priority order. */
    private val scheduler = BrainScheduler({ SystemClock.elapsedRealtime() })
    /** The lapse of a hold for tool results, posted on the work thread. */
    private var holdCheck: Runnable? = null
    /** Conversations a one-shot closed while they waited for tool results (after the hold lapsed): their results end that reply. */
    private val evicted = HashSet<String>()
    /** An unload is under way: nothing starts meanwhile (a request that would is refused as not loaded). */
    private var unloading = false
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
        /** The scheduler's id for the request this run serves (a step down or a shorter rebuild keeps it). */
        val job: Int,
        val convId: String,
        val live: Live,
        val sink: BrainEngine.Sink,
        val rebuilt: Boolean,
        /** Send the same request again on a fresh conversation ([BrainPolicy.Next.StepDown] / [BrainPolicy.Next.Shorten]); null for tool results. */
        val resend: ((BrainPolicy.Next) -> Unit)?,
        val attempt: Attempt,
        /** Takes the thinking out of the reply (your own Qwen3 or MiniCPM file); null: the text passes as it is. */
        val filter: ThinkFilter? = null,
        /** A one-shot request: its own conversation (never in [convs]), closed when it ends. */
        val oneShot: Boolean = false,
        /** The reply's cap in chunks. */
        val maxChunks: Int = Limits.MAX_REPLY_CHUNKS,
    ) {
        val tSend = SystemClock.elapsedRealtime()
        var tFirst = -1L
        var chunks = 0
        val reply = StringBuilder()
        val calls = mutableListOf<RawToolCall>()
        var stopReason: DoneReason? = null
        var thoughtSent = false
        var ended = false
        /** Stopped to give way to a request with a higher priority: no terminal event, it starts over later. */
        var giveWay = false
        /** Set from LiteRT's own thread when it called onDone/onError: the native side is finished with the conversation. */
        @Volatile var nativeEnded = false
    }

    // ------------------------------------------------------------------ load

    private fun npu() = Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir)

    private fun backend(b: ModelBackend): Backend = when (b) {
        ModelBackend.TPU -> npu()
        ModelBackend.GPU -> Backend.GPU()
        ModelBackend.CPU -> Backend.CPU()
    }

    /** The general file's vision encoder runs on the CPU only (LiteRT-LM's ModelInfo: vision backends CPU), whatever the text does on the GPU. */
    private fun visionBackend(b: ModelBackend): Backend = if (b == ModelBackend.GPU) Backend.CPU() else backend(b)

    /** This phone, as :brain checks it (core Phones): the TPU only with its own chip's build, the GPU only where Robot offers it. */
    private val phone: Phone by lazy {
        Phone(android.os.Build.MANUFACTURER.orEmpty(), android.os.Build.MODEL.orEmpty(), android.os.Build.SOC_MANUFACTURER.orEmpty(),
            android.os.Build.SOC_MODEL.orEmpty())
    }

    private fun config(req: BrainMsg.Load, withVision: Boolean, cacheDir: File?) = EngineConfig(
        modelPath = req.path,
        backend = backend(req.backend),
        visionBackend = if (withVision) visionBackend(req.backend) else null,
        audioBackend = null,
        maxNumTokens = req.maxTokens,
        cacheDir = cacheDir?.path,
    )

    override fun status() = BrainMsg.Status(loaded = engine != null, vision = vision, constrained = mode() == ToolMode.TOOLS_CONSTRAINED,
        busy = running != null || scheduler.running != null,
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
        val ownRecord = if (req.model == BrainModel.OWN) readOrNull(File(records, ModelFiles.OWN_RECORD)) else null
        LoadCheck.refuse(
            req.model, req.backend, ModelFiles.fingerprint(f.length(), f.lastModified()),
            mark = if (req.model.builtIn) readOrNull(File(records, ModelFiles.verifiedMark(req.model))) else null,
            ownRecord = ownRecord,
            maxTokens = req.maxTokens, format = ModelFormat.LITERT_LM, phone = phone,
        )?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        // 1.0.1: a file still outside internal storage (1.0's place, until the app moves it in) is trusted on its whole
        // SHA-256, read now, not on its size and time (core ModelMove).
        if (ModelMove.hashBeforeLoad(f, ctx.filesDir)) {
            val want = if (req.model.builtIn) req.model.sha256 else OwnModel.fromJson(ownRecord)?.sha256
            val got = try { FileInputStream(f).use { ModelMove.sha256(it) } } catch (_: Exception) { null }
            RLog.i("brain_hash", "model" to RLog.Code(req.model.id), "ok" to (want != null && got == want))
            if (want == null || got != want) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.CHANGED)
        }
        // The TPU only for a built-in TPU build's pinned file (its SHA-256 mark, just above; LoadCheck matched its chip to
        // this phone's) or a file whose contents prove a TPU build for this phone's own chip and that you allowed there; and
        // never TPU code on the CPU. Read here again, from the file about to load, with its context: never a KV cache larger
        // than the file was built with, nor a short one for a file that isn't.
        var header: LiteRtLmReport? = null
        if (req.model.tpuChip == null || req.backend != ModelBackend.TPU) {
            // 1.0 (native-01): your own file's container checked strictly again, from the file about to load (core LiteRtLmCheck).
            val r = LiteRtLmCheck.check(f, strict = req.model == BrainModel.OWN)
            val ok = when (req.backend) {
                ModelBackend.TPU -> r.runsOnTpuOf(phone)
                // The GPU runs a general (not NPU-compiled) file, as the CPU does; LoadCheck allowed the general E2B only.
                ModelBackend.CPU, ModelBackend.GPU -> r.backend == ModelBackend.CPU
            }
            if (!ok) return failed(req, LoadFailure.NOT_ALLOWED, r.unusable ?: "Robot's check of the file says it can't run on the ${req.backend.title}.")
            if (r.contextTokens != req.maxTokens) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.wrongContext(r.contextTokens))
            header = r
        }
        val cacheDir = ModelFiles.cacheDir(req.model, req.backend)?.let { File(ctx.filesDir, it) }
        if (cacheDir != null) {
            cacheDir.mkdirs()
            // The first load writes the weight cache (about 2.5 GB for E4B; 0.4.7: about 0.8 GB on the GPU): never start one
            // that can't finish. Every file under it, as Settings › Model counts it (ModelStore.cacheBytes), so a cache already
            // written is never taken for one still to write.
            val cached = try { cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } } catch (_: Exception) { 0L }
            val need = ModelFiles.cacheBytes(req.model, req.backend, f.length()) - cached
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
        // MTP drafting: LiteRT-LM's default (off) on the TPU, as since 0.1; the A/B constants on the CPU and the GPU.
        ExperimentalFlags.enableSpeculativeDecoding = when (req.backend) {
            ModelBackend.CPU -> CPU_MTP
            ModelBackend.GPU -> GPU_MTP
            ModelBackend.TPU -> null
        }
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
            // Its tokenizer's special tokens, which no text from outside Robot's prompt may write, read from the file just
            // loaded, whichever backend runs it: a Tensor build's on the TPU, 0.4.7's general E2B's on the GPU or the CPU
            // (the same SentencePiece model), an E4B file's, your own. A built-in file (its SHA-256 checked above) is Gemma
            // 4's, whose tokenizer encodes the joiner as itself; your own file whose tokenizer can't be read gets the known
            // markers broken by a plain space, which every tokenizer reads as a space.
            val tokens = try { FileByteSource(f).use { LiteRtLmCheck.tokens(it) } } catch (_: Exception) { null }
            controls = tokens?.controlText() ?: ControlText(PromptMarkers.KNOWN, if (req.model.builtIn) ControlText.JOINER else ControlText.SPACE)
            val tokensMs = SystemClock.elapsedRealtime() - t0 - ms
            RLog.i("brain_load", "ok" to true, "ms" to ms, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "vision" to vision,
                "visualBudget" to budgetSet, "visionError" to visionError?.let { RLog.Code(it) }, "mtp" to ((req.backend == ModelBackend.CPU && CPU_MTP == true) || (req.backend == ModelBackend.GPU && GPU_MTP == true)),
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
        unloading = true
        val r = running
        try {
            // Requests waiting for the model end now (not loaded); the running one ends as cancelled, never queued again.
            scheduler.dropWaiting()
            if (r != null) {
                r.giveWay = false
                if (r.stopReason == null) r.stopReason = DoneReason.CANCELLED
                try { r.live.conv.cancelProcess() } catch (_: Exception) {}
                // Never close a conversation LiteRT is still decoding into: wait (bounded) for its callback.
                var waited = 0
                while (!r.nativeEnded && waited < UNLOAD_WAIT_MS) { Thread.sleep(50); waited += 50 }
                finish(r, DoneReason.CANCELLED)
            }
            scheduler.dropWaiting()
        } finally {
            unloading = false
        }
        // A one-shot's conversation is in no map: it goes (or is parked) with the others.
        val all = convs.values.map { it.conv } + listOfNotNull(r?.takeIf { it.oneShot && it.live.conv.isAlive }?.live?.conv)
        convs.clear()
        evicted.clear()
        engine = null
        syncHold()
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
            // Robot's own system prompt as it is; the turns (your questions with the screen's text, Robot's and the helpers'
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
        evicted.remove(convId)
        val r = running
        if (r != null && !r.oneShot && r.convId == convId) {
            try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            r.live.dirty = true
            r.giveWay = false
            r.stopReason = DoneReason.CANCELLED
            return
        }
        convs.remove(convId)?.let { closeQuietly(it.conv) }
        syncHold()
    }

    // ------------------------------------------------------------------ the queue

    /** One request in the scheduler: [begin] sends it (with its id), the rest tells its client. */
    private inner class Work(override val priority: Priority, private val sink: BrainEngine.Sink, private val begin: (Int) -> Unit) : BrainScheduler.Work {
        /** The scheduler's id for it, once it starts. */
        private var id = -1

        override fun start(id: Int) {
            this.id = id
            try {
                begin(id)
            } catch (t: Throwable) {
                // A failure before the send: the request ends here, the next one starts.
                RLog.w("brain_request_start", t)
                if (running?.job == id) running = null
                fail(id, sink, BrainError.ENGINE, t.javaClass.simpleName)
            }
        }

        override fun stop(giveWay: Boolean) {
            val r = running?.takeIf { it.job == id } ?: return
            // LiteRT already ended this reply by itself (its callback is on its way to this thread): it ends as it is, with
            // its terminal event, and is never run again (the scheduler's ended with terminalSent). Stopping it here would
            // throw a whole reply (or an error) away and start it over.
            if (giveWay && r.nativeEnded && r.stopReason == null) return
            if (r.stopReason == null) {
                r.stopReason = DoneReason.CANCELLED
                r.giveWay = giveWay
                try { r.live.conv.cancelProcess() } catch (_: Exception) {}
            } else if (!giveWay) {
                // Giving way already, and now cancelled by its client: it ends as cancelled.
                r.giveWay = false
            }
        }

        override fun queued(position: Int) = sink.emit(BrainProtocol.EV_QUEUED, BrainMsg.Queued(position))

        override fun dropped(unloaded: Boolean) {
            if (unloaded) sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
            else sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.CANCELLED, -1, -1, -1.0, false, false))
        }
    }

    private fun submit(key: BrainScheduler.Key, priority: Priority, sink: BrainEngine.Sink, begin: (Int) -> Unit) {
        scheduler.submit(key, Work(priority, sink, begin))
    }

    /** Request [job] ends with an error before (or instead of) a reply; the next one starts. */
    private fun fail(job: Int, sink: BrainEngine.Sink, code: BrainError, detail: String? = null) {
        sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(code, detail))
        syncHold()
        scheduler.ended(job)
    }

    /** Not loaded, or being unloaded: nothing may start on it. */
    private fun usable(): Boolean = engine != null && !unloading

    /**
     * The panel's conversation waits for its tool results: only panel requests start, until it stops waiting or the
     * hold lapses (BrainScheduler.TOOL_HOLD_MS), checked by a timer on this thread.
     */
    private fun syncHold() {
        scheduler.holdForTools(convs.values.any { it.awaitingTool && !it.dirty })
        holdCheck?.let(handler::removeCallbacks)
        holdCheck = null
        val at = scheduler.holdEndsAt() ?: return
        val r = Runnable {
            holdCheck = null
            scheduler.expire()
        }
        holdCheck = r
        handler.postDelayed(r, (at - SystemClock.elapsedRealtime()).coerceAtLeast(0) + 50)
    }

    override fun cancel(key: BrainScheduler.Key) = scheduler.cancel(key)

    // ------------------------------------------------------------------ generate

    /** The panel's question: it waits its turn (the panel's newest question cancels its older ones). */
    override fun generate(key: BrainScheduler.Key, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        if (req.hasImage && image == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "image missing"))
        if (image != null && !vision) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, null))
        submit(key, Priority.PANEL, sink) { job -> startGenerate(job, req, image, sink, Attempt(sends = 1, historyBudget = null, historyTurns = 0)) }
    }

    private fun startGenerate(job: Int, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink, attempt: Attempt) {
        if (!usable()) return fail(job, sink, BrainError.NOT_LOADED)
        if (image != null && !vision) return fail(job, sink, BrainError.NO_VISION)
        evicted.remove(req.convId)
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
            return fail(job, sink, BrainError.TOO_LONG)
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
            syncHold()
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
                            return fail(job, sink, next.code, ex.javaClass.simpleName)
                        }
                        is BrainPolicy.Next.Stop -> return fail(job, sink, BrainError.ENGINE, ex.javaClass.simpleName)
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
            startGenerate(job, req, image, sink, Attempt(attempt.sends + 1, nextBudget, turns))
        }
        val run = Run(job, req.convId, l, sink, rebuilt, resend, Attempt(attempt.sends, budget, turns), if (plan.stripThinking) ThinkFilter() else null)
        val parts = buildList {
            image?.let { add(Content.ImageBytes(it)) }
            add(Content.Text(controls.defuse(req.text)))
        }
        send(run) { cb -> l.conv.sendMessageAsync(Message.user(Contents.of(parts)), cb) }
    }

    /** The panel's tool results: a panel request too (it may start while the hold for them keeps one-shots waiting). */
    override fun toolResult(key: BrainScheduler.Key, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        submit(key, Priority.PANEL, sink) { job -> startToolResult(job, req, sink) }
    }

    private fun startToolResult(job: Int, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) {
        if (!usable()) return fail(job, sink, BrainError.NOT_LOADED)
        if (evicted.remove(req.convId)) {
            // A one-shot closed this conversation after the hold lapsed: the actions stand and the reply ends here, as
            // when the results leave no room; the conversation is rebuilt from the history next time.
            RLog.i("brain_tool_round_evicted")
            sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.CONTEXT_FULL, -1, -1, -1.0, false, false))
            syncHold()
            return scheduler.ended(job)
        }
        val l = convs[req.convId]
        if (l == null || !l.awaitingTool || l.dirty) return fail(job, sink, BrainError.BAD_REQUEST, "no tool call pending")
        l.awaitingTool = false
        syncHold()
        // Room for the results and a reply after them? If not, the actions stand and the reply ends here; the
        // conversation is rebuilt (shorter) from the history next time.
        val responses = req.results.sumOf { (n, r) -> TokenEstimate.text(n) + TokenEstimate.text(Json.obj(r).toString()) } + TokenEstimate.TURN_OVERHEAD
        val live = tokenCount(l.conv)
        if (!ContextWindow.roomForToolRound(live, responses, maxTokens())) {
            convs.remove(req.convId)
            closeQuietly(l.conv)
            RLog.i("brain_tool_round_full", "tokens" to live, "responses" to responses)
            sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(DoneReason.CONTEXT_FULL, live, -1, -1.0, false, l.mode == ToolMode.TEXT_CALLS))
            syncHold()
            return scheduler.ended(job)
        }
        val run = Run(job, req.convId, l, sink, rebuilt = false, resend = null, attempt = Attempt(1, null, 0),
            filter = if (thinking(false).stripThinking) ThinkFilter() else null)
        // What a tool found (an event's title, a contact's name, a note) is text from outside Robot's prompt too.
        val parts = req.results.map { (name, response) -> Content.ToolResponse(name, controls.defuse(response)) }
        send(run) { cb -> l.conv.sendMessageAsync(Message.tool(Contents.of(parts)), cb) }
    }

    // ------------------------------------------------------------------ one-shot

    /**
     * REQ_ONE_SHOT (0.5.0): a translation or a summary step at its [BrainMsg.OneShot.priority]. Any model may serve it,
     * a short-context one too (it is chat only by nature); its budget is the loaded model's context.
     */
    override fun oneShot(key: BrainScheduler.Key, req: BrainMsg.OneShot, sink: BrainEngine.Sink) {
        if (engine == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        submit(key, req.priority, sink) { job -> startOneShot(job, req, sink) }
    }

    private fun startOneShot(job: Int, req: BrainMsg.OneShot, sink: BrainEngine.Sink) {
        if (!usable()) return fail(job, sink, BrainError.NOT_LOADED)
        val max = maxTokens()
        if (!OneShotRules.fits(req, max)) {
            RLog.i("brain_one_shot_too_long", "kind" to req.kind, "tokens" to OneShotRules.tokens(req), "maxTokens" to max)
            return fail(job, sink, BrainError.TOO_LONG)
        }
        // One LiteRT conversation at a time: the assistant's (idle: this request waited for its reply to end) is closed
        // and rebuilt from the app's own history at its next question. One that still waited for tool results (the hold
        // lapsed) is remembered, so its results end that reply instead of failing.
        for ((id, l) in convs) {
            if (l.awaitingTool && !l.dirty) evicted += id
            closeQuietly(l.conv)
        }
        convs.clear()
        syncHold()
        val spec = OneShotRules.conversation(req)
        // Thinking off (your own Qwen3 or MiniCPM file is told so), and a <think> block it writes anyway is never the answer.
        val plan = thinking(false)
        val conv = try {
            newConversation(spec.system, spec.history, spec.mode, spec.tools, plan)
        } catch (ex: Exception) {
            val code = (OneShotRules.afterError(ex.message, hadOutput = false) as? BrainPolicy.Next.Fail)?.code ?: BrainError.ENGINE
            RLog.w("brain_one_shot", ex, "kind" to req.kind, "code" to code)
            return fail(job, sink, code, ex.javaClass.simpleName)
        }
        // Never put in [convs]: no panel request can reuse it, and it is closed when this run ends.
        val live = Live(conv, 0, spec.mode, spec.system, emptyList())
        val run = Run(job, ONE_SHOT_CONV, live, sink, rebuilt = true, resend = null, attempt = Attempt(1, null, 0),
            filter = if (plan.stripThinking) ThinkFilter() else null, oneShot = true, maxChunks = OneShotRules.maxChunks(req, max))
        RLog.i("brain_one_shot", "kind" to req.kind, "priority" to req.priority, "tokens" to OneShotRules.tokens(req), "maxChunks" to run.maxChunks)
        // Its system prompt is Robot's own; its one turn (a transcript line, a summary's notes) is text from outside it.
        send(run) { cb -> conv.sendMessageAsync(Message.user(Contents.of(listOf(Content.Text(controls.defuse(req.text))))), cb) }
    }

    /** A one-shot's conversation goes once LiteRT is done with it (an unload parks it otherwise). */
    private fun closeOneShot(run: Run) {
        if (run.oneShot && run.nativeEnded) closeQuietly(run.live.conv)
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
        // A one-shot's thinking (it is asked not to) is never a sign to its client: only its text counts.
        if (!run.oneShot && !run.thoughtSent && (!m.channels["thought"].isNullOrEmpty() || run.filter?.thought == true)) {
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
            run.chunks >= run.maxChunks -> DoneReason.LENGTH
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
        if (run.stopReason == null && run.chunks < run.maxChunks) run.filter?.flush()?.takeIf { it.isNotEmpty() }?.let { rest ->
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
        if (run.oneShot) {
            // Never sent again, never a step down: what streamed is kept when a full context cut it, else an error.
            when (val next = OneShotRules.afterError(msg, hadOutput)) {
                is BrainPolicy.Next.Stop -> finish(run, next.reason)
                else -> {
                    RLog.w("brain_one_shot_send", error, "chunks" to run.chunks)
                    running = null
                    closeOneShot(run)
                    fail(run.job, run.sink, (next as? BrainPolicy.Next.Fail)?.code ?: BrainError.ENGINE, error.javaClass.simpleName)
                }
            }
            return
        }
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
                fail(run.job, run.sink, next.code, error.javaClass.simpleName)
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
        closeOneShot(run)
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens,
            "tps" to (bench?.lastDecodeTokensPerSecond ?: -1.0), "rebuilt" to run.rebuilt, "calls" to run.calls.size, "mode" to run.live.mode,
            "oneShot" to run.oneShot, "gaveWay" to (run.giveWay && reason == DoneReason.CANCELLED))
        if (run.giveWay && reason == DoneReason.CANCELLED) {
            // It gave way: no terminal event; the scheduler queues it again (EV_QUEUED) and it starts over later.
            syncHold()
            return scheduler.ended(run.job, terminalSent = false)
        }
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, bench?.lastDecodeTokensPerSecond ?: -1.0, run.rebuilt, run.live.mode == ToolMode.TEXT_CALLS))
        // A reply that ended asking for tools holds one-shots back until its results come (or the hold lapses).
        syncHold()
        scheduler.ended(run.job)
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
         * A/B on the phone (README "On the phone (0.4.7)"): MTP on the GPU. Google recommends it for GPU backends; on the
         * Pixel 10 Pro's PowerVR GPU it made writing slower (the lab). Unmeasured on a Mali GPU: null (off) until it is.
         */
        private val GPU_MTP: Boolean? = null
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
        /** A one-shot's run has no conversation id of the app's. */
        private const val ONE_SHOT_CONV = ""
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
