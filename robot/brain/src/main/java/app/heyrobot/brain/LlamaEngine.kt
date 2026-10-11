package app.heyrobot.brain

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.system.Os
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainPolicy
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.ChannelByteSource
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.ControlText
import app.heyrobot.core.DoneReason
import app.heyrobot.core.EngineTeardown
import app.heyrobot.core.GgufCheck
import app.heyrobot.core.GgufReport
import app.heyrobot.core.IsolatedUid
import app.heyrobot.core.Limits
import app.heyrobot.core.LlamaCpu
import app.heyrobot.core.LoadCheck
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Prompt
import app.heyrobot.core.PromptFit
import app.heyrobot.core.PromptMarkers
import app.heyrobot.core.PromptRole
import app.heyrobot.core.PromptTurn
import app.heyrobot.core.RLog
import app.heyrobot.core.ThinkFilter
import app.heyrobot.core.Thinking
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.ToolMode
import app.heyrobot.core.readOrNull
import java.io.File
import java.nio.channels.FileChannel
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// :BRAIN PROCESS ONLY. Your own GGUF model file, run by llama.cpp on the CPU (0.4.5): the other of :brain's two engines
// (Engine.kt), under the same one-slot rule as LiteRT-LM. Text only (no vision), and Robot's own text protocol for actions
// (ToolMode.TEXT_CALLS): the system prompt shows the call syntax (Prompt.textCallHint) and the app runs a reply that is
// nothing but calls (ToolCalls.wholeReplyCalls). A file trained for 2,048 to 4,095 tokens runs with its own context as
// chat only (0.4.3's ToolMode.CHAT_ONLY, as a short .litertlm): no tools, no hint, and its replies are never read as calls.
// The conversation is formatted by the model's own chat template, counted by its own tokenizer to fit the model's context
// (core PromptFit), and llama.cpp reuses whatever beginning of it is already in memory, so a follow-up reads only the new
// turn.
//
// llama.cpp itself never runs here: it runs in LlamaService's android:isolatedProcess (":llama"), under an isolated uid
// with no permissions and none of Robot's files, which this engine starts for each GGUF load (LlamaLink) and ends when the
// model goes, its memory with it. Everything else is decided here, as before: the app's record of your file (unchanged
// since its check, a GGUF file, on the CPU, with the context the request asks for), the header read again (core
// GgufCheck) through the very file then handed over, open, to the isolated process (it can't open a file by name), and
// compared with the one recorded, byte for byte; that process proves an isolated uid before it gets the file; what
// llama.cpp read of the context must be what Robot read; the prompt and its special tokens (ControlText), the thinking
// filter, the reply's limits. Every call to it runs on one worker thread; replies come back to BrainService's work thread,
// where all the state below lives. llama.cpp that never comes back from a load or a prompt ends its process, not :brain,
// and a process that stops (a crash, Android, `kill`) ends the reply with BrainError.STOPPED: the assistant skips the file
// and answers with Fast, as when :brain stopped.

class LlamaEngine(private val ctx: Context, private val handler: Handler) : BrainEngine, SlotHolder {
    @Volatile override var destroyedAt = 0L
        private set
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-llama") }
    /** The isolated process that holds the loaded model; null when none is loaded. */
    private var link: LlamaLink? = null
    private var handle = 0L
    private var loaded: BrainMsg.Load? = null
    private var report: GgufReport? = null
    /** The loaded model's special tokens, broken in every text from outside Robot's own prompt (core ControlText). */
    private var controls = ControlText(PromptMarkers.KNOWN)
    private var running: Run? = null
    /** A request that arrived while another was being cancelled: it starts when that one ends. */
    private var queued: (() -> Unit)? = null
    /** Isolated processes asked to end that haven't yet: their memory isn't free, so the slot stays held (parked). */
    private val ending = HashSet<LlamaLink>()
    private var teardown: (() -> Unit)? = null
    private var runs = 0L

    /** [mode]: TEXT_CALLS, or CHAT_ONLY for a short-context file (its reply is never read as calls). */
    private inner class Run(val reqId: Int, val convId: String, val sink: BrainEngine.Sink, val handle: Long, val id: Long, val mode: ToolMode) {
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
            // Only while its model is the one loaded (one way: never waits on the isolated process).
            if (this@LlamaEngine.handle == handle) link?.cancel(handle, id)
        }
    }

    // ------------------------------------------------------------------ load

    override fun status() = BrainMsg.Status(loaded = handle != 0L, vision = false, constrained = false, busy = running != null,
        model = loaded?.model?.takeIf { handle != 0L }, backend = loaded?.backend?.takeIf { handle != 0L },
        maxTokens = loaded?.maxTokens?.takeIf { handle != 0L } ?: 0, format = ModelFormat.GGUF.takeIf { handle != 0L })

    /** The loaded model's context (4,096 when none is loaded: nothing is sent then anyway). */
    private fun maxTokens(): Int = loaded?.maxTokens ?: ContextWindow.MAX_TOKENS

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
            if (current.model == req.model && current.backend == req.backend && current.path == req.path && current.maxTokens == req.maxTokens) {
                return BrainMsg.Loaded(true, false, 0, null, req.model, req.backend, format = ModelFormat.GGUF)
            }
            RLog.i("brain_switch", "from" to RLog.Code(current.model.id), "to" to RLog.Code(req.model.id))
            unload("switch")
        }
        // Never beside a model whose isolated process hasn't ended yet, nor an engine parked in this process.
        if (ending.isNotEmpty()) return failed(req, LoadFailure.STOPPING, STOPPING)
        if (!EngineTeardown.mayLoad(EngineSlot.parked.get())) return failed(req, LoadFailure.STOPPING, STOPPING)
        val f = File(req.path)
        // Only your own GGUF file, in Robot's own storage, under its fixed name.
        val allowed = listOfNotNull(ctx.getExternalFilesDir(null), ctx.filesDir).any { f.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        if (!allowed || !f.isFile || f.name != ModelFiles.OWN_GGUF || req.model != BrainModel.OWN || req.format != ModelFormat.GGUF) {
            return failed(req, LoadFailure.NO_FILE, "The model file is missing.")
        }
        if (req.backend != ModelBackend.CPU) return failed(req, LoadFailure.NOT_ALLOWED, "A GGUF file runs on the CPU only.")
        // One open file from here to llama.cpp: its header is read through it, and the isolated process gets that very
        // descriptor, so nothing put at the path after the check is ever what llama.cpp parses.
        val pfd = try { ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY) } catch (_: Exception) {
            return failed(req, LoadFailure.NO_FILE, "The model file is missing.")
        }
        return ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> loadOpen(req, f, pfd, input.channel) }
    }

    /** True when [pfd] is the file at [f]'s path now (same device and inode) and of the size the path says. */
    private fun sameFile(pfd: ParcelFileDescriptor, f: File): Boolean = try {
        val open = Os.fstat(pfd.fileDescriptor)
        val named = Os.stat(f.path)
        open.st_dev == named.st_dev && open.st_ino == named.st_ino && open.st_size == named.st_size
    } catch (_: Exception) { false }

    private fun loadOpen(req: BrainMsg.Load, f: File, pfd: ParcelFileDescriptor, channel: FileChannel): BrainMsg.Loaded {
        // The app's record, read here again: your file as checked (size and time unchanged), a GGUF file, on the CPU, and the
        // context Robot's prompts are made for with it (4,096, or a short file's own: chat only).
        val record = readOrNull(File(File(ctx.filesDir, "robot"), ModelFiles.OWN_RECORD))
        LoadCheck.refuse(req.model, req.backend, ModelFiles.fingerprint(f.length(), f.lastModified()), null, record,
            maxTokens = req.maxTokens, format = ModelFormat.GGUF)?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        // The file open is the one whose size and time were just compared: the one at the path now.
        if (!sameFile(pfd, f)) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.CHANGED)
        // The header read again through the open file, and the same as the one the app checked and showed you; its
        // context too (never a cache larger than the model was trained for, nor a short one for a file that isn't).
        val r = GgufCheck.check(ChannelByteSource(channel), totalRam())
        r.unusable?.let { return failed(req, LoadFailure.NOT_ALLOWED, it) }
        if (r.contextTokens != req.maxTokens) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.wrongContext(r.contextTokens))
        val recorded = OwnModel.fromJson(record)?.gguf
        if (recorded == null || recorded.copy(totalRam = 0) != r.copy(totalRam = 0)) return failed(req, LoadFailure.NOT_ALLOWED, LoadCheck.CHANGED)
        // The library in this build, and a CPU it runs on (read here: the isolated process needn't see /proc).
        if (!File(ctx.applicationInfo.nativeLibraryDir, LIBRARY).isFile) return failed(req, LoadFailure.ENGINE, LlamaIpc.NOT_IN_BUILD)
        cpuProblem()?.let { return failed(req, LoadFailure.ENGINE, it) }
        // One engine in the whole process, whichever engine or service loads it (EngineSlot); its model's memory is the
        // isolated process's, and the slot is held until that process is gone.
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
        val l = LlamaLink(ctx) { gone -> handler.post { gone(gone) } }
        // Started, proven isolated, then given the file: all on the worker, so a process that never answers can't hold
        // this thread for longer than the limit.
        val loading = worker.submit<Any> {
            if (!l.bind(BIND_LIMIT_MS)) return@submit LoadFailure.PROCESS
            val hello = l.hello()
            when {
                !hello.isolated -> NOT_ISOLATED
                hello.problem != null -> hello.problem
                else -> l.load(pfd, req.maxTokens, DECODE_THREADS, PROMPT_THREADS, SAMPLING)
            }
        }
        val out = try {
            loading.get(LOAD_LIMIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            stuck(l, "load")
            return letGo(l, req, LoadFailure.TIMEOUT, "llama.cpp didn't finish loading the file.", t0)
        } catch (e: ExecutionException) {
            if (e.cause is LlamaLink.Gone) return letGo(l, req, LoadFailure.PROCESS, "llama.cpp stopped while it read the file.", t0)
            RLog.w("brain_load", e.cause ?: e, "format" to ModelFormat.GGUF)
            return letGo(l, req, LoadFailure.ENGINE, "llama.cpp couldn't load the file.", t0)
        }
        when (out) {
            LoadFailure.PROCESS -> return letGo(l, req, LoadFailure.PROCESS, "Robot couldn't start llama.cpp's process.", t0)
            NOT_ISOLATED -> return letGo(l, req, LoadFailure.NOT_ALLOWED, NOT_ISOLATED, t0)
            is String -> return letGo(l, req, LoadFailure.ENGINE, out, t0)
        }
        val res = out as LlamaIpc.LoadResult
        val control = res.control
        val user = res.userDefined
        if (res.handle == 0L || control == null || user == null) {
            return letGo(l, req, LoadFailure.ENGINE, when (res.why) {
                LlamaIpc.LOAD_CONTEXT -> "llama.cpp couldn't make room for its context (memory)."
                LlamaIpc.LOAD_TEMPLATE -> "llama.cpp couldn't read the file's chat template."
                else -> "llama.cpp couldn't load the file."
            }, t0)
        }
        // Both parsers succeeded but read the header differently: llama.cpp would run a file Robot didn't check.
        if (res.trainContext != r.contextLength) return letGo(l, req, LoadFailure.NOT_ALLOWED, DIFFERENT, t0)
        val ms = SystemClock.elapsedRealtime() - t0
        link = l
        handle = res.handle
        loaded = req
        report = r
        // Its special tokens, which no text from outside Robot's prompt may write: the control ones, the marker-shaped
        // user-defined ones (llama.cpp reads those from any text) and the known markers (core PromptMarkers).
        controls = ControlText(PromptMarkers.of(control.map { String(it, Charsets.UTF_8) }, user.map { String(it, Charsets.UTF_8) }))
        RLog.i("brain_load", "ok" to true, "ms" to ms, "model" to RLog.Code(req.model.id), "backend" to RLog.Code(req.backend.id), "format" to ModelFormat.GGUF,
            "vision" to false, "fileType" to r.fileType, "threads" to DECODE_THREADS, "promptThreads" to PROMPT_THREADS, "thinks" to r.thinks,
            "maxTokens" to req.maxTokens, "chatOnly" to ContextWindow.chatOnly(req.maxTokens), "controls" to controls.size,
            "isolated" to IsolatedUid.of(l.uid), "uid" to l.uid, "pid" to l.pid)
        return BrainMsg.Loaded(true, false, ms, null, req.model, req.backend, format = ModelFormat.GGUF)
    }

    /** Null when this CPU runs Robot's llama.cpp library (core LlamaCpu, from /proc/cpuinfo), else why not. */
    private fun cpuProblem(): String? {
        val missing = LlamaCpu.missing(try { File("/proc/cpuinfo").readText() } catch (_: Exception) { null })
        return if (missing.isEmpty()) null else "This phone's CPU lacks what Robot's llama.cpp is built for (${missing.joinToString(", ")})."
    }

    /** A load that ended without a model: its process ends, and the slot goes once it is gone. */
    private fun letGo(l: LlamaLink, req: BrainMsg.Load, failure: LoadFailure, error: String, t0: Long): BrainMsg.Loaded {
        l.end("load_failed")
        settle(l)
        return failed(req, failure, error, SystemClock.elapsedRealtime() - t0)
    }

    /**
     * Waits a little for [l]'s process to be gone (its memory with it; usually a fraction of a second): then the slot is
     * free, else it stays held (parked, "still stopping") until it is. :brain ends only if that process never goes.
     */
    private fun settle(l: LlamaLink) {
        if (l.awaitGone(UNLOAD_WAIT_MS.toLong())) {
            releaseIfFree()
            return
        }
        if (ending.add(l)) EngineSlot.parked.incrementAndGet()
        backstop(l)
    }

    /** The last resort, never seen: an isolated process asked to end that is still there after [GONE_LIMIT_MS] ends :brain (and itself with it). */
    private fun backstop(l: LlamaLink) {
        handler.postDelayed({
            if (l.isAlive) {
                RLog.i("brain_stuck", "in" to RLog.Code("end"), "format" to ModelFormat.GGUF)
                android.os.Process.killProcess(android.os.Process.myPid())
            }
        }, GONE_LIMIT_MS)
    }

    /** No model of this engine is loaded or in a process still ending: the process's slot is free (if this engine held it). */
    private fun releaseIfFree() {
        if (link == null && ending.isEmpty()) EngineSlot.release(this, ctx)
    }

    /**
     * On the work thread: [l]'s isolated process is gone, and its model's memory with it. When it held the loaded model and
     * nobody asked it to go (a crash, Android's low-memory killer, `kill`) or a watchdog ended it, the model is lost: a
     * reply in progress ends with STOPPED, and the assistant answers with Fast from then on.
     */
    private fun gone(l: LlamaLink) {
        if (link === l) {
            RLog.i("brain_llama_gone", "asked" to l.ending, "busy" to (running != null), "format" to ModelFormat.GGUF)
            link = null
            handle = 0L
            loaded = null
            report = null
            controls = ControlText(PromptMarkers.KNOWN)
            // A request queued behind the reply then runs, and is told the model isn't loaded.
            running?.let { fail(it, BrainError.STOPPED, "model process stopped") }
        }
        if (ending.remove(l)) {
            EngineSlot.parked.decrementAndGet()
            RLog.i("brain_unload_parked_closed", "format" to ModelFormat.GGUF)
        }
        releaseIfFree()
        teardown?.invoke()
    }

    override fun unload(why: String) {
        val l = link ?: return
        queued = null
        val r = running
        r?.stopNow(DoneReason.CANCELLED)
        link = null
        handle = 0L
        loaded = null
        report = null
        controls = ControlText(PromptMarkers.KNOWN)
        // The model's memory goes with its process, whatever llama.cpp is doing: never freed under a reply in :brain.
        l.end(why)
        if (r != null && !r.ended) finish(r, DoneReason.CANCELLED, null)
        settle(l)
        RLog.i("brain_unload", "why" to RLog.Code(why), "parked" to l.isAlive, "format" to ModelFormat.GGUF)
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
                val s = EngineTeardown.afterDestroy(ending.isNotEmpty(), SystemClock.elapsedRealtime() - t0)
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
        val l = link
        if (h == 0L || l == null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        // Text only: this model can't see (the panel says so before it asks).
        if (req.hasImage || image != null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, "text only"))
        if (afterRunning { generate(reqId, req, image, sink) }) return
        val max = maxTokens()
        // Robot's own text protocol for actions: the call syntax in the system prompt. Chat only (a short context): none.
        val mode = BrainPolicy.modeFor(max, ToolMode.TEXT_CALLS)
        val tools = BrainPolicy.toolsFor(mode, req.toolNames)
        val plan = Thinking.gguf(report, req.think)
        val system = plan.system(req.system + Prompt.textCallHint(tools))
        val think = plan.enableThinking ?: req.think
        val controls = controls
        val firstBudget = ContextWindow.historyBudget(Prompt.fixedTokens(req.system, tools), TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text)), max)
        val run = Run(reqId, req.convId, sink, h, ++runs, mode).also { it.strip = plan.stripThinking }
        running = run
        worker.execute {
            // Format with the model's template and count with its tokenizer; older turns go until it fits (PromptFit).
            var budget = firstBudget
            var rebuilds = 0
            val flags = IntArray(1)
            var tokens: Int
            var kept: Int
            // The template is the file's own program, and nothing stops it: one that never finishes ends its process.
            val watchdog = Runnable { stuck(l, "prepare") }
            handler.postDelayed(watchdog, PREPARE_LIMIT_MS)
            try {
                while (true) {
                    val history = PromptFit.alternating(ContextWindow.fit(req.history, budget))
                    val roles = ArrayList<ByteArray>()
                    val texts = ArrayList<ByteArray>()
                    fun add(role: String, text: String) { roles += role.toByteArray(); texts += text.toByteArray(Charsets.UTF_8) }
                    // Robot's own system prompt as it is; everything else without the model's control tokens (ControlText).
                    add("system", system)
                    for (t in history) add(if (t.role == PromptRole.USER) "user" else "assistant", controls.defuse(t.text))
                    add("user", controls.defuse(req.text))
                    tokens = try {
                        if (run.stop) LlamaIpc.PREP_TEMPLATE_FAILED else l.prepare(h, roles, texts, think, flags)
                    } catch (t: Exception) {
                        // Its process is gone (it stopped, or the watchdog ended it), or the call failed.
                        handler.post { onEnd(run, if (t is LlamaLink.Gone) LOST else LlamaIpc.GEN_DECODE_FAILED, null) }
                        return@execute
                    }
                    kept = history.size
                    if (run.stop) {
                        handler.post { onEnd(run, LlamaIpc.GEN_CANCELLED, null) }
                        return@execute
                    }
                    if (tokens < 0 && tokens != LlamaIpc.PREP_TOO_LONG) {
                        handler.post { fail(run, BrainError.ENGINE, "template") }
                        return@execute
                    }
                    val real = if (tokens == LlamaIpc.PREP_TOO_LONG) Int.MAX_VALUE / 2 else tokens
                    when (val step = PromptFit.after(real, history.size, budget, rebuilds, max)) {
                        is PromptFit.Step.Send -> break
                        is PromptFit.Step.Shorten -> { budget = step.historyBudget; rebuilds++ }
                        is PromptFit.Step.TooLong -> {
                            handler.post { fail(run, BrainError.TOO_LONG, null) }
                            return@execute
                        }
                    }
                }
            } finally {
                handler.removeCallbacks(watchdog)
            }
            run.startsInside = flags[0] and 1 != 0
            val stats = LongArray(LlamaIpc.STATS_COUNT)
            val code = try {
                // The pieces come back on this thread while the isolated process writes (LlamaLink's callback).
                l.generate(h, run.id, max, { bytes ->
                    val text = String(bytes, Charsets.UTF_8)
                    handler.post { onPiece(run, text) }
                    !run.stop
                }, stats)
            } catch (_: LlamaLink.Gone) {
                LOST
            } catch (t: Throwable) {
                RLog.w("brain_send", t, "format" to ModelFormat.GGUF)
                LlamaIpc.GEN_DECODE_FAILED
            }
            val history = kept
            handler.post {
                RLog.i("brain_prompt", "tokens" to tokens, "kept" to history, "of" to req.history.size, "rebuilds" to rebuilds,
                    "reused" to stats[LlamaIpc.STATS_REUSED], "promptMs" to stats[LlamaIpc.STATS_PROMPT_MS], "format" to ModelFormat.GGUF)
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
            LlamaIpc.GEN_END -> DoneReason.END
            // The context filled up while it wrote: the reply ends where it got to, as with LiteRT-LM.
            LlamaIpc.GEN_LENGTH, LlamaIpc.GEN_CONTEXT_FULL -> DoneReason.LENGTH
            LlamaIpc.GEN_CANCELLED -> DoneReason.CANCELLED
            // Its process stopped under the reply: the model is lost (gone() lets the slot go when it is).
            LOST -> return fail(run, BrainError.STOPPED, "model process stopped")
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
        val generated = stats?.get(LlamaIpc.STATS_GENERATED) ?: 0
        val genMs = stats?.get(LlamaIpc.STATS_GENERATE_MS) ?: 0
        val tps = if (genMs > 0) generated * 1000.0 / genMs else -1.0
        val tokens = stats?.get(LlamaIpc.STATS_POSITION)?.toInt() ?: -1
        // Robot's text protocol: the app may read a reply that is nothing but calls as calls; never in chat only.
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, tps, rebuilt = (stats?.get(LlamaIpc.STATS_REUSED) ?: 0L) == 0L,
            textCalls = BrainPolicy.acceptsCalls(run.mode)))
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens, "tps" to tps,
            "prompt" to (stats?.get(LlamaIpc.STATS_PROMPT) ?: -1L), "promptMs" to (stats?.get(LlamaIpc.STATS_PROMPT_MS) ?: -1L),
            "generated" to generated, "mode" to run.mode, "format" to ModelFormat.GGUF)
        runQueued()
    }

    /**
     * llama.cpp hasn't come back from [where] (a chat template that never finishes, a file it can't get through): nothing
     * stops it from here, so its isolated process ends (not :brain), the model's memory with it. A reply waiting on it ends
     * with STOPPED and the assistant answers with Fast (BrainKeeper), as after a crash; a load reports TIMEOUT.
     */
    private fun stuck(l: LlamaLink, where: String) {
        RLog.i("brain_stuck", "in" to RLog.Code(where), "format" to ModelFormat.GGUF)
        l.end("stuck_$where")
        backstop(l)
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
         * the X4 and the five A725s). A/B on the phone (README "On the phone (0.4.5)").
         */
        private const val DECODE_THREADS = 4
        private const val PROMPT_THREADS = 6
        /** llama.cpp's own default sampling: top-k 40, top-p 0.95, min-p 0.05, temperature 0.8. */
        private val SAMPLING = floatArrayOf(40f, 0.95f, 0.05f, 0.8f)
        /** How long unload waits for the isolated process to be gone before leaving the slot parked until it is. */
        private const val UNLOAD_WAIT_MS = 10_000
        /** How long an isolated process asked to end may take before :brain gives up on it and ends itself (never seen). */
        private const val GONE_LIMIT_MS = 30_000L
        /** How long Android may take to start the isolated process. */
        private const val BIND_LIMIT_MS = 15_000L
        /** The library tools/build_llama.sh builds (absent: this build can't run GGUF files). */
        private const val LIBRARY = "librobot_llama.so"
        /** generate()'s result when the isolated process went away under it. */
        private const val LOST = Int.MIN_VALUE
        private const val NOT_ISOLATED = "llama.cpp's process isn't an isolated one, so Robot won't give it the file."
        /** How long llama.cpp may take to load a file (a few seconds; minutes for a large one read from flash the first time)… */
        private const val LOAD_LIMIT_MS = 180_000L
        /** … and to format and count a prompt (milliseconds) before :brain gives it up for stuck and ends its process. */
        private const val PREPARE_LIMIT_MS = 30_000L
        private const val STOPPING = "The model is still stopping. Try again in a moment."
        private const val DIFFERENT = "llama.cpp read this file's header differently from Robot's check, so Robot won't run it."
    }
}
