package app.heyrobot.brain

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.SystemClock
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.DoneReason
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.GgufReport
import app.heyrobot.core.Limits
import app.heyrobot.core.LoadCheck
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptFit
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.RLog
import app.heyrobot.core.ThinkFilter
import app.heyrobot.core.Thinking
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolMode
import app.heyrobot.core.readOrNull
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

// :BRAIN PROCESS ONLY. Your own GGUF model file, run by llama.cpp on the CPU (0.4.4): the other of :brain's two engines
// (Engine.kt), under the same one-slot rule as LiteRT-LM. Text only (no vision), and always Robot's own text protocol for
// actions (ToolMode.TEXT_CALLS): the system prompt shows the call syntax (Prompt.textCallHint) and the app runs a reply
// that is nothing but calls (ToolCalls.wholeReplyCalls). The conversation is formatted by the model's own chat template,
// counted by its own tokenizer to fit Robot's 4,096 tokens (core PromptFit), and llama.cpp reuses whatever beginning of it
// is already in memory, so a follow-up reads only the new turn.
//
// Before a load, as for LiteRT-LM: the app's record of your file (unchanged since its check, a GGUF file, on the CPU), and
// the header read again here (core GgufCheck) and compared with the one recorded; llama.cpp's own parser runs only after
// that, and only in this process. Every native call runs on one worker thread; replies come back to BrainService's work
// thread, where all the state below lives. The model is never freed under a reply llama.cpp hasn't returned from.

class LlamaEngine(private val ctx: Context, private val handler: Handler) : BrainEngine, SlotHolder {
    @Volatile override var destroyedAt = 0L
        private set
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-llama") }
    private var handle = 0L
    private var loaded: BrainMsg.Load? = null
    private var report: GgufReport? = null
    private var running: Run? = null
    /** A request that arrived while another was being cancelled: it starts when that one ends. */
    private var queued: (() -> Unit)? = null
    /** The free of a model unloaded while llama.cpp was still in a reply: it runs when that returns; the slot stays held. */
    private var parked: Future<*>? = null
    private var teardown: (() -> Unit)? = null
    private var runs = 0L

    private inner class Run(val reqId: Int, val convId: String, val sink: BrainEngine.Sink, val handle: Long, val id: Long) {
        val tSend = SystemClock.elapsedRealtime()
        var tFirst = -1L
        var chunks = 0
        val reply = StringBuilder()
        var stopReason: DoneReason? = null
        var ended = false
        var thoughtSent = false
        /** Read by the native sink on the worker thread: false stops the reply. */
        @Volatile var stop = false
        /** Set on the worker before the first piece is posted: the prompt opened a <think> block. */
        @Volatile var startsInside = false
        var strip = true
        val filter: ThinkFilter? by lazy { if (strip) ThinkFilter(startsInside) else null }

        fun stopNow(reason: DoneReason) {
            if (stopReason == null) stopReason = reason
            stop = true
            LlamaNative.cancel(handle, id)
        }
    }

    // ------------------------------------------------------------------ load

    override fun status() = BrainMsg.Status(loaded = handle != 0L, vision = false, constrained = false, busy = running != null,
        model = loaded?.model?.takeIf { handle != 0L }, backend = loaded?.backend?.takeIf { handle != 0L },
        format = ModelFormat.GGUF.takeIf { handle != 0L })

    private fun failed(req: BrainMsg.Load, failure: LoadFailure, error: String, ms: Long = 0): BrainMsg.Loaded {
        RLog.i("brain_load", "ok" to false, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "format" to ModelFormat.GGUF,
            "failure" to failure)
        return BrainMsg.Loaded(false, false, ms, error, req.model, req.backend, failure, ModelFormat.GGUF)
    }

    private fun totalRam(): Long = try {
        ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem
    } catch (_: Exception) { 0L }

    override fun load(req: BrainMsg.Load): BrainMsg.Loaded {
        val current = loaded
        if (handle != 0L && current != null) {
            if (current.model == req.model && current.backend == req.backend && current.path == req.path) {
                return BrainMsg.Loaded(true, false, 0, null, req.model, req.backend, format = ModelFormat.GGUF)
            }
            RLog.i("brain_switch", "from" to RLog.Code(current.model.id), "to" to RLog.Code(req.model.id))
            unload("switch")
        }
        if (parked?.isDone == true) closeParked()
        if (parked != null) return failed(req, LoadFailure.STOPPING, STOPPING)
        if (!EngineTeardown.mayLoad(EngineSlot.parked.get())) return failed(req, LoadFailure.STOPPING, STOPPING)
        val f = File(req.path)
        // Only your own GGUF file, in Robot's own storage, under its fixed name.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile || f.name != ModelFiles.OWN_GGUF || req.model != BrainModel.OWN || req.format != ModelFormat.GGUF) {
            return failed(req, LoadFailure.NO_FILE, "The model file is missing.")
        }
        if (req.backend != ModelBackend.CPU) return failed(req, LoadFailure.NOT_ALLOWED, "A GGUF file runs on the CPU only.")
        if (req.maxTokens != ContextWindow.MAX_TOKENS) return failed(req, LoadFailure.NOT_ALLOWED, "Robot's prompts are made for ${ContextWindow.MAX_TOKENS} tokens.")
        // The app's record, read here again: your file as checked (size and time unchanged), a GGUF file, on the CPU.
        val record = readOrNull(File(File(ctx.filesDir, "robot"), ModelFiles.OWN_RECORD))
        LoadCheck.refuse(req.model, req.backend, ModelFiles.fingerprint(f.length(), f.lastModified()), null, record, ModelFormat.GGUF)
            ?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        // The header read again from the file about to load, and the same as the one the app checked and showed you.
        val r = GgufCheck.check(f, totalRam())
        r.unusable?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        val recorded = OwnModel.fromJson(record)?.gguf
        if (recorded == null || recorded.copy(totalRam = 0) != r.copy(totalRam = 0)) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.CHANGED)
        LlamaNative.ready()?.let { return failed(req, LoadFailure.ENGINE, it) }
        // One engine in the whole process, whichever engine or service loads it (EngineSlot).
        when (EngineTeardown.forLoad(!EngineSlot.claim(this, ctx, req), EngineSlot.holderDestroyedMs(this))) {
            EngineTeardown.LoadStep.LOAD -> {}
            EngineTeardown.LoadStep.WAIT -> return failed(req, LoadFailure.STOPPING, STOPPING)
            EngineTeardown.LoadStep.END_PROCESS -> {
                RLog.i("brain_teardown", "step" to EngineTeardown.LoadStep.END_PROCESS)
                android.os.Process.killProcess(android.os.Process.myPid())
                return failed(req, LoadFailure.STOPPING, STOPPING)
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val why = IntArray(1)
        val h = try {
            worker.submit<Long> {
                LlamaNative.load(f.path.toByteArray(Charsets.UTF_8), ContextWindow.MAX_TOKENS, DECODE_THREADS, PROMPT_THREADS, SAMPLING, why)
            }.get()
        } catch (t: Exception) {
            RLog.w("brain_load", t, "format" to ModelFormat.GGUF)
            0L
        }
        val ms = SystemClock.elapsedRealtime() - t0
        if (h == 0L) {
            EngineSlot.release(this, ctx)
            return failed(req, LoadFailure.ENGINE, when (why[0]) {
                LlamaNative.LOAD_CONTEXT -> "llama.cpp couldn't make room for its context (memory)."
                LlamaNative.LOAD_TEMPLATE -> "llama.cpp couldn't read the file's chat template."
                else -> "llama.cpp couldn't load the file."
            }, ms)
        }
        handle = h
        loaded = req
        report = r
        RLog.i("brain_load", "ok" to true, "ms" to ms, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "format" to ModelFormat.GGUF,
            "vision" to false, "fileType" to r.fileType, "threads" to DECODE_THREADS, "promptThreads" to PROMPT_THREADS, "thinks" to r.thinks)
        return BrainMsg.Loaded(true, false, ms, null, req.model, req.backend, format = ModelFormat.GGUF)
    }

    override fun unload(why: String) {
        val h = handle
        if (h == 0L) return
        queued = null
        val r = running
        r?.stopNow(DoneReason.CANCELLED)
        handle = 0L
        loaded = null
        report = null
        // Freed on the worker, after any reply it is still in: never under it.
        val freed = worker.submit { LlamaNative.free(h) }
        var waited = 0
        while (!freed.isDone && waited < UNLOAD_WAIT_MS) { Thread.sleep(50); waited += 50 }
        if (r != null && !r.ended) finish(r, DoneReason.CANCELLED, null)
        if (!freed.isDone) {
            // Still inside llama.cpp after the wait: the slot (and the marker) stay until the free has run.
            parked = freed
            EngineSlot.parked.incrementAndGet()
            worker.execute { handler.post { closeParked() } }
            RLog.i("brain_unload", "why" to RLog.Code(why), "parked" to true, "format" to ModelFormat.GGUF)
            return
        }
        EngineSlot.release(this, ctx)
        RLog.i("brain_unload", "why" to RLog.Code(why), "format" to ModelFormat.GGUF)
    }

    private fun closeParked() {
        val p = parked ?: return
        if (!p.isDone) return
        parked = null
        EngineSlot.parked.decrementAndGet()
        if (handle == 0L) EngineSlot.release(this, ctx)
        RLog.i("brain_unload_parked_closed", "format" to ModelFormat.GGUF)
        teardown?.invoke()
    }

    override fun destroyed() {
        destroyedAt = SystemClock.elapsedRealtime()
    }

    override fun shutdown(step: (EngineTeardown.Step) -> Unit) {
        unload("destroy")
        val t0 = SystemClock.elapsedRealtime()
        var told = false
        val check = {
            if (!told) {
                if (parked?.isDone == true) closeParked()
                val s = EngineTeardown.afterDestroy(parked != null, SystemClock.elapsedRealtime() - t0)
                if (s != EngineTeardown.Step.WAIT) {
                    told = true
                    teardown = null
                    if (s == EngineTeardown.Step.QUIT_THREAD) worker.shutdown()
                    RLog.i("brain_teardown", "step" to s, "format" to ModelFormat.GGUF)
                    step(s)
                }
            }
        }
        teardown = check
        check()
        if (!told) handler.postDelayed({ check() }, EngineTeardown.PARKED_LIMIT_MS)
    }

    // ------------------------------------------------------------------ generate

    override fun generate(reqId: Int, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink) {
        val h = handle
        if (h == 0L) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        // Text only: this model can't see (the panel says so before it asks).
        if (req.hasImage || image != null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, "text only"))
        if (afterRunning { generate(reqId, req, image, sink) }) return
        val tools = req.toolNames
        val plan = Thinking.gguf(report, req.think)
        // Robot's own text protocol for actions, whatever the model: the call syntax in the system prompt.
        val system = plan.system(req.system + Prompt.textCallHint(tools))
        val think = plan.enableThinking ?: req.think
        val firstBudget = ContextWindow.historyBudget(Prompt.fixedTokens(req.system, tools), TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text)))
        val run = Run(reqId, req.convId, sink, h, ++runs).also { it.strip = plan.stripThinking }
        running = run
        worker.execute {
            // Format with the model's template and count with its tokenizer; older turns go until it fits (PromptFit).
            var budget = firstBudget
            var rebuilds = 0
            val flags = IntArray(1)
            var tokens: Int
            var kept: Int
            while (true) {
                val history = ContextWindow.fit(req.history, budget)
                val roles = ArrayList<ByteArray>()
                val texts = ArrayList<ByteArray>()
                fun add(role: String, text: String) { roles += role.toByteArray(); texts += text.toByteArray(Charsets.UTF_8) }
                add("system", system)
                for (t in history) add(if (t.role == PromptRole.USER) "user" else "assistant", t.text)
                add("user", req.text)
                tokens = if (run.stop) LlamaNative.PREP_TEMPLATE_FAILED else LlamaNative.prepare(h, roles.toTypedArray(), texts.toTypedArray(), think, flags)
                kept = history.size
                if (run.stop) {
                    handler.post { onEnd(run, LlamaNative.GEN_CANCELLED, null) }
                    return@execute
                }
                if (tokens < 0 && tokens != LlamaNative.PREP_TOO_LONG) {
                    handler.post { fail(run, BrainError.ENGINE, "template") }
                    return@execute
                }
                val real = if (tokens == LlamaNative.PREP_TOO_LONG) Int.MAX_VALUE / 2 else tokens
                when (val step = PromptFit.after(real, history.size, budget, rebuilds)) {
                    is PromptFit.Step.Send -> break
                    is PromptFit.Step.Shorten -> { budget = step.historyBudget; rebuilds++ }
                    is PromptFit.Step.TooLong -> {
                        handler.post { fail(run, BrainError.TOO_LONG, null) }
                        return@execute
                    }
                }
            }
            run.startsInside = flags[0] and 1 != 0
            val stats = LongArray(LlamaNative.STATS_COUNT)
            val code = try {
                LlamaNative.generate(h, run.id, ContextWindow.MAX_TOKENS, { bytes ->
                    val text = String(bytes, Charsets.UTF_8)
                    handler.post { onPiece(run, text) }
                    !run.stop
                }, stats)
            } catch (t: Throwable) {
                RLog.w("brain_send", t, "format" to ModelFormat.GGUF)
                LlamaNative.GEN_DECODE_FAILED
            }
            val history = kept
            handler.post {
                RLog.i("brain_prompt", "tokens" to tokens, "kept" to history, "of" to req.history.size, "rebuilds" to rebuilds,
                    "reused" to stats[LlamaNative.STATS_REUSED], "promptMs" to stats[LlamaNative.STATS_PROMPT_MS], "format" to ModelFormat.GGUF)
                onEnd(run, code, stats)
            }
        }
    }

    override fun toolResult(reqId: Int, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) {
        // Text calls only: the app runs them and the conversation goes on from the history; no results come back here.
        sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "no tool call pending"))
    }

    /** If a reply is running, stops it and queues [next] for when it ends (the newest request wins). */
    private fun afterRunning(next: () -> Unit): Boolean {
        val r = running ?: return false
        r.stopNow(DoneReason.CANCELLED)
        queued = next
        return true
    }

    override fun cancel(target: Int) {
        val r = running ?: return
        if (r.reqId == target) r.stopNow(DoneReason.CANCELLED)
    }

    /** The conversation itself is the history the app sends; llama.cpp keeps only what it can reuse. A reply into it stops. */
    override fun closeConv(convId: String) {
        val r = running ?: return
        if (r.convId == convId) r.stopNow(DoneReason.CANCELLED)
    }

    private fun onPiece(run: Run, text: String) {
        if (run.ended || run.stopReason != null) return
        val visible = run.filter?.feed(text) ?: text
        if (!run.thoughtSent && run.filter?.thought == true) {
            run.thoughtSent = true
            run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk("", thought = true))
        }
        if (visible.isEmpty()) return
        emitText(run, visible)
    }

    private fun emitText(run: Run, text: String) {
        if (run.tFirst < 0) run.tFirst = SystemClock.elapsedRealtime()
        run.chunks++
        run.reply.append(text)
        run.sink.emit(BrainProtocol.EV_CHUNK, BrainMsg.Chunk(text))
        when {
            garbage(text, run.reply) -> run.stopNow(DoneReason.GARBAGE)
            run.chunks >= Limits.MAX_REPLY_CHUNKS -> run.stopNow(DoneReason.LENGTH)
        }
    }

    private fun onEnd(run: Run, code: Int, stats: LongArray?) {
        if (run.ended) return
        if (run.stopReason == null) run.filter?.flush()?.takeIf { it.isNotEmpty() }?.let { emitText(run, it) }
        val reason = run.stopReason ?: when (code) {
            LlamaNative.GEN_END -> DoneReason.END
            // The context filled up while it wrote: the reply ends where it got to, as with LiteRT-LM.
            LlamaNative.GEN_LENGTH, LlamaNative.GEN_CONTEXT_FULL -> DoneReason.LENGTH
            LlamaNative.GEN_CANCELLED -> DoneReason.CANCELLED
            else -> return fail(run, BrainError.ENGINE, "decode")
        }
        finish(run, reason, stats)
    }

    private fun fail(run: Run, code: BrainError, detail: String?) {
        if (run.ended) return
        run.ended = true
        if (running === run) running = null
        RLog.i("brain_reply", "error" to code, "format" to ModelFormat.GGUF)
        run.sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(code, detail))
        runQueued()
    }

    private fun finish(run: Run, reason: DoneReason, stats: LongArray?) {
        run.ended = true
        if (running === run) running = null
        val ttft = if (run.tFirst >= 0) run.tFirst - run.tSend else -1
        val generated = stats?.get(LlamaNative.STATS_GENERATED) ?: 0
        val genMs = stats?.get(LlamaNative.STATS_GENERATE_MS) ?: 0
        val tps = if (genMs > 0) generated * 1000.0 / genMs else -1.0
        val tokens = stats?.get(LlamaNative.STATS_POSITION)?.toInt() ?: -1
        // Robot's text protocol: the app may read a reply that is nothing but calls as calls.
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, tps, rebuilt = (stats?.get(LlamaNative.STATS_REUSED) ?: 0L) == 0L, textCalls = true))
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens, "tps" to tps,
            "prompt" to (stats?.get(LlamaNative.STATS_PROMPT) ?: -1L), "promptMs" to (stats?.get(LlamaNative.STATS_PROMPT_MS) ?: -1L),
            "generated" to generated, "mode" to ToolMode.TEXT_CALLS, "format" to ModelFormat.GGUF)
        runQueued()
    }

    private fun runQueued() {
        val q = queued ?: return
        queued = null
        q()
    }

    /** Broken output (as GemmaEngine detects it): one character 32+ times in a row. */
    private fun garbage(chunk: String, reply: CharSequence): Boolean {
        if (chunk.contains("<pad>") || chunk.contains("<unused")) return true
        if (reply.length < 32) return false
        val c = reply[reply.length - 1]
        if (c.isWhitespace()) return false
        for (i in reply.length - 32 until reply.length) if (reply[i] != c) return false
        return true
    }

    companion object {
        /**
         * Threads for writing (memory-bound: more than the big cores don't help) and for reading a prompt (compute-bound:
         * the X4 and the five A725s). A/B on the phone (README "On the phone (0.4.4)").
         */
        private const val DECODE_THREADS = 4
        private const val PROMPT_THREADS = 6
        /** llama.cpp's own default sampling: top-k 40, top-p 0.95, min-p 0.05, temperature 0.8. */
        private val SAMPLING = floatArrayOf(40f, 0.95f, 0.05f, 0.8f)
        /** How long unload waits for a stopped reply to return before leaving the free to run after it. */
        private const val UNLOAD_WAIT_MS = 10_000
        private const val STOPPING = "The model is still stopping. Try again in a moment."
    }
}
