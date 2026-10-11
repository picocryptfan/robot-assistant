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
import app.heyrobot.core.BrainScheduler
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
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.OwnModel
import app.heyrobot.core.Priority
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
// where all the state below lives. llama.cpp that never comes back from a load, a prompt or a stop ends its process, not
// :brain, and a process that stops (a crash, Android, `kill`) ends the reply with BrainError.STOPPED: the assistant skips
// the file and answers with Fast, as when :brain stopped.
//
// 0.6.0: requests wait their turn in conversation mode's priority queue (core BrainScheduler), as in GemmaEngine: the
// panel, then the interpreter, then transcript translations, then summaries, the panel's newest question still winning
// over its older ones. REQ_ONE_SHOT formats its own system prompt and its one turn with the model's template (no history,
// no tools, chat only, thinking off), writes up to its own cap and is never read as calls. There are no tool rounds here
// (text calls run in the app), so nothing holds the queue for tool results. All of that is decided here: the isolated
// process only runs the one prompt it is given (a one-shot after a panel question simply reuses less of llama.cpp's
// memory), and a give-way or a cancel reaches it as the same one-way stop, inside a prompt being read too.

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
    /** Every request waits its turn here (0.6.0): one runs at a time, in priority order. */
    private val scheduler = BrainScheduler({ SystemClock.elapsedRealtime() })
    /** An unload is under way: nothing starts meanwhile (a request that would is refused as not loaded). */
    private var unloading = false
    /** Isolated processes asked to end that haven't yet: their memory isn't free, so the slot stays held (parked). */
    private val ending = HashSet<LlamaLink>()
    private var teardown: (() -> Unit)? = null
    private var runs = 0L

    /**
     * [job]: the scheduler's id for the request this run serves; [mode]: TEXT_CALLS, or CHAT_ONLY for a short-context file
     * or a one-shot (its reply is never read as calls); [maxChunks]: the reply's cap.
     */
    private inner class Run(
        val job: Int, val convId: String, val sink: BrainEngine.Sink, val handle: Long, val id: Long, val mode: ToolMode,
        val oneShot: Boolean = false, val maxChunks: Int = Limits.MAX_REPLY_CHUNKS,
    ) {
        val tSend = SystemClock.elapsedRealtime()
        var tFirst = -1L
        var chunks = 0
        val reply = StringBuilder()
        var stopReason: DoneReason? = null
        var ended = false
        var thoughtSent = false
        /** Stopped to give way to a request with a higher priority: no terminal event, it starts over later. */
        var giveWay = false
        /** Set on the worker once llama.cpp has returned from this reply (its end is on its way to the work thread). */
        @Volatile var nativeEnded = false
        /** Read by the native sink on the worker thread: false stops the reply. */
        @Volatile var stop = false
        /** Set on the worker before the first piece is posted: the prompt opened a <think> block. */
        @Volatile var startsInside = false
        var strip = true
        val filter: ThinkFilter? by lazy { if (strip) ThinkFilter(startsInside) else null }
        /** Its stop is watched ([watchStop]); [overdue]: llama.cpp didn't come back from it in time, and its process was ended. */
        var stopWatched = false
        var overdue = false

        fun stopNow(reason: DoneReason) {
            if (stopReason == null) stopReason = reason
            stop = true
            // Only while its model is the one loaded (one way: never waits on the isolated process).
            if (this@LlamaEngine.handle == handle) link?.let { l ->
                l.cancel(handle, id)
                watchStop(this, l)
            }
        }
    }

    // ------------------------------------------------------------------ load

    override fun status() = BrainMsg.Status(loaded = handle != 0L, vision = false, constrained = false, busy = running != null || scheduler.running != null,
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
            // The requests waiting for it end (not loaded); the one it was answering ends with STOPPED.
            scheduler.dropWaiting()
            running?.let { r ->
                r.giveWay = false
                fail(r, BrainError.STOPPED, "model process stopped")
            }
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
        unloading = true
        val r = running
        try {
            // Requests waiting for the model end now (not loaded); the running one ends as cancelled, never queued again.
            scheduler.dropWaiting()
            r?.giveWay = false
            r?.stopNow(DoneReason.CANCELLED)
            link = null
            handle = 0L
            loaded = null
            report = null
            controls = ControlText(PromptMarkers.KNOWN)
            // The model's memory goes with its process, whatever llama.cpp is doing: never freed under a reply in :brain.
            l.end(why)
            if (r != null && !r.ended) finish(r, DoneReason.CANCELLED, null)
            scheduler.dropWaiting()
        } finally {
            unloading = false
        }
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

    /** The panel's question: it waits its turn (the panel's newest question cancels its older ones). */
    override fun generate(key: BrainScheduler.Key, req: BrainMsg.Generate, image: ByteArray?, sink: BrainEngine.Sink) {
        if (handle == 0L) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        // Text only: this model can't see (the panel says so before it asks).
        if (req.hasImage || image != null) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NO_VISION, "text only"))
        submit(key, Priority.PANEL, sink) { job -> startGenerate(job, req, sink) }
    }

    private fun startGenerate(job: Int, req: BrainMsg.Generate, sink: BrainEngine.Sink) {
        val h = handle
        if (h == 0L || unloading) return failJob(job, sink, BrainError.NOT_LOADED)
        val max = maxTokens()
        // Robot's own text protocol for actions: the call syntax in the system prompt. Chat only (a short context): none.
        val mode = BrainPolicy.modeFor(max, ToolMode.TEXT_CALLS)
        val tools = BrainPolicy.toolsFor(mode, req.toolNames)
        val plan = Thinking.gguf(report, req.think)
        val system = plan.system(req.system + Prompt.textCallHint(tools))
        val think = plan.enableThinking ?: req.think
        val firstBudget = ContextWindow.historyBudget(Prompt.fixedTokens(req.system, tools), TokenEstimate.turn(PromptTurn(PromptRole.USER, req.text)), max)
        val run = Run(job, req.convId, sink, h, ++runs, mode).also { it.strip = plan.stripThinking }
        running = run
        write(run, system, req.history, req.text, think, firstBudget)
    }

    /**
     * Formats [system], [history] (fitted within [firstBudget], shorter as needed) and [question] with the model's
     * template, then writes [run]'s reply, on the worker; its end comes back to the work thread.
     */
    private fun write(run: Run, system: String, history: List<PromptTurn>, question: String, think: Boolean, firstBudget: Int) {
        val h = run.handle
        val l = link
        if (l == null) {
            run.nativeEnded = true
            return fail(run, BrainError.NOT_LOADED, null)
        }
        val max = maxTokens()
        val controls = controls
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
                    val turns = PromptFit.alternating(ContextWindow.fit(history, budget))
                    val roles = ArrayList<ByteArray>()
                    val texts = ArrayList<ByteArray>()
                    fun add(role: String, text: String) { roles += role.toByteArray(); texts += text.toByteArray(Charsets.UTF_8) }
                    // Robot's own system prompt as it is; everything else without the model's control tokens (ControlText).
                    add("system", system)
                    for (t in turns) add(if (t.role == PromptRole.USER) "user" else "assistant", controls.defuse(t.text))
                    add("user", controls.defuse(question))
                    tokens = try {
                        if (run.stop) LlamaIpc.PREP_TEMPLATE_FAILED else l.prepare(h, roles, texts, think, flags)
                    } catch (t: Exception) {
                        // Its process is gone (it stopped, or the watchdog ended it), or the call failed.
                        run.nativeEnded = true
                        handler.post { onEnd(run, if (t is LlamaLink.Gone) LOST else LlamaIpc.GEN_DECODE_FAILED, null) }
                        return@execute
                    }
                    kept = turns.size
                    if (run.stop) {
                        run.nativeEnded = true
                        handler.post { onEnd(run, LlamaIpc.GEN_CANCELLED, null) }
                        return@execute
                    }
                    if (tokens < 0 && tokens != LlamaIpc.PREP_TOO_LONG) {
                        run.nativeEnded = true
                        handler.post { fail(run, BrainError.ENGINE, "template") }
                        return@execute
                    }
                    val real = if (tokens == LlamaIpc.PREP_TOO_LONG) Int.MAX_VALUE / 2 else tokens
                    when (val step = PromptFit.after(real, turns.size, budget, rebuilds, max)) {
                        is PromptFit.Step.Send -> break
                        is PromptFit.Step.Shorten -> { budget = step.historyBudget; rebuilds++ }
                        is PromptFit.Step.TooLong -> {
                            run.nativeEnded = true
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
                // The pieces come back on this thread while the isolated process writes (LlamaLink's callback). It reuses
                // whatever beginning of this prompt is still in llama.cpp's memory: after a one-shot, little or none.
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
            run.nativeEnded = true
            val keptTurns = kept
            handler.post {
                RLog.i("brain_prompt", "tokens" to tokens, "kept" to keptTurns, "of" to history.size, "rebuilds" to rebuilds, "oneShot" to run.oneShot,
                    "reused" to stats[LlamaIpc.STATS_REUSED], "promptMs" to stats[LlamaIpc.STATS_PROMPT_MS], "format" to ModelFormat.GGUF)
                onEnd(run, code, stats)
            }
        }
    }

    override fun toolResult(key: BrainScheduler.Key, req: BrainMsg.ToolResult, sink: BrainEngine.Sink) {
        // Text calls only: the app runs them and the conversation goes on from the history; no results come back here.
        sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.BAD_REQUEST, "no tool call pending"))
    }

    // ------------------------------------------------------------------ one-shot

    /**
     * REQ_ONE_SHOT (0.6.0 for GGUF files): a translation or a summary step at its [BrainMsg.OneShot.priority], its own
     * system prompt and its one turn (core OneShotRules), thinking off; its budget is the loaded model's context.
     */
    override fun oneShot(key: BrainScheduler.Key, req: BrainMsg.OneShot, sink: BrainEngine.Sink) {
        if (handle == 0L) return sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(BrainError.NOT_LOADED, null))
        submit(key, req.priority, sink) { job -> startOneShot(job, req, sink) }
    }

    private fun startOneShot(job: Int, req: BrainMsg.OneShot, sink: BrainEngine.Sink) {
        val h = handle
        if (h == 0L || unloading) return failJob(job, sink, BrainError.NOT_LOADED)
        val max = maxTokens()
        if (!OneShotRules.fits(req, max)) {
            RLog.i("brain_one_shot_too_long", "kind" to req.kind, "tokens" to OneShotRules.tokens(req), "maxTokens" to max, "format" to ModelFormat.GGUF)
            return failJob(job, sink, BrainError.TOO_LONG)
        }
        val spec = OneShotRules.conversation(req)
        // Thinking off (the template's switch, or Qwen3's "/no_think"), and a <think> block it writes anyway is never the answer.
        val plan = Thinking.gguf(report, false)
        val run = Run(job, ONE_SHOT_CONV, sink, h, ++runs, spec.mode, oneShot = true, maxChunks = OneShotRules.maxChunks(req, max))
            .also { it.strip = plan.stripThinking }
        running = run
        RLog.i("brain_one_shot", "kind" to req.kind, "priority" to req.priority, "tokens" to OneShotRules.tokens(req), "maxChunks" to run.maxChunks,
            "format" to ModelFormat.GGUF)
        // Robot's own system prompt as it is; its one turn (a transcript line, a summary's notes) defused in write().
        write(run, plan.system(spec.system), spec.history, req.text, plan.enableThinking ?: false, firstBudget = 0)
    }

    // ------------------------------------------------------------------ the queue

    /** One request in the scheduler: [begin] starts it (with its id), the rest tells its client. */
    private inner class Work(override val priority: Priority, private val sink: BrainEngine.Sink, private val begin: (Int) -> Unit) : BrainScheduler.Work {
        /** The scheduler's id for it, once it starts. */
        private var id = -1

        override fun start(id: Int) {
            this.id = id
            try {
                begin(id)
            } catch (t: Throwable) {
                // A failure before llama.cpp got it: the request ends here, the next one starts.
                RLog.w("brain_request_start", t, "format" to ModelFormat.GGUF)
                if (running?.job == id) running = null
                failJob(id, sink, BrainError.ENGINE, t.javaClass.simpleName)
            }
        }

        override fun stop(giveWay: Boolean) {
            val r = running?.takeIf { it.job == id } ?: return
            // llama.cpp already returned from this reply (its end is on its way to this thread): it ends as it is, with its
            // terminal event, and is never run again. Stopping it here would throw a whole reply away and start it over.
            if (giveWay && r.nativeEnded && r.stopReason == null) return
            if (r.stopReason == null) {
                r.giveWay = giveWay
                r.stopNow(DoneReason.CANCELLED)
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

    /** Request [job] ends with an error before a reply started; the next one starts. */
    private fun failJob(job: Int, sink: BrainEngine.Sink, code: BrainError, detail: String? = null) {
        sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(code, detail))
        scheduler.ended(job)
    }

    override fun cancel(key: BrainScheduler.Key) = scheduler.cancel(key)

    /** The conversation itself is the history the app sends; llama.cpp keeps only what it can reuse. A reply into it stops. */
    override fun closeConv(convId: String) {
        val r = running ?: return
        if (!r.oneShot && r.convId == convId) {
            r.giveWay = false
            r.stopNow(DoneReason.CANCELLED)
        }
    }

    private fun onPiece(run: Run, text: String) {
        if (run.ended || run.stopReason != null) return
        val visible = run.filter?.feed(text) ?: text
        // A one-shot's thinking (it is asked not to) is never a sign to its client: only its text counts.
        if (!run.oneShot && !run.thoughtSent && run.filter?.thought == true) {
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
            run.chunks >= run.maxChunks -> run.stopNow(DoneReason.LENGTH)
        }
    }

    private fun onEnd(run: Run, code: Int, stats: LongArray?) {
        if (run.ended) return
        if (code == LOST) {
            // Its process stopped under it: the model is lost (gone() lets the slot go when Binder says the process is).
            // The requests waiting for it end now as not loaded, as gone() ends them, rather than start on a process that
            // isn't there; this one can't start over on it either. It ends with STOPPED unless its client had already
            // stopped it (a cancel, its cap): one that never came back from its stop (watchStop) is lost, as in a crash.
            scheduler.dropWaiting()
            if (run.overdue || run.giveWay || run.stopReason == null) {
                run.giveWay = false
                return fail(run, BrainError.STOPPED, "model process stopped")
            }
        }
        if (run.stopReason == null) run.filter?.flush()?.takeIf { it.isNotEmpty() }?.let { emitText(run, it) }
        val reason = run.stopReason ?: when (code) {
            LlamaIpc.GEN_END -> DoneReason.END
            // The context filled up while it wrote: the reply ends where it got to, as with LiteRT-LM.
            LlamaIpc.GEN_LENGTH, LlamaIpc.GEN_CONTEXT_FULL -> DoneReason.LENGTH
            LlamaIpc.GEN_CANCELLED -> DoneReason.CANCELLED
            else -> return fail(run, BrainError.ENGINE, "decode")
        }
        finish(run, reason, stats)
    }

    private fun fail(run: Run, code: BrainError, detail: String?) {
        if (run.ended) return
        run.ended = true
        if (running === run) running = null
        RLog.i("brain_reply", "error" to code, "oneShot" to run.oneShot, "format" to ModelFormat.GGUF)
        run.sink.emit(BrainProtocol.EV_ERROR, BrainMsg.Error(code, detail))
        scheduler.ended(run.job)
    }

    private fun finish(run: Run, reason: DoneReason, stats: LongArray?) {
        run.ended = true
        if (running === run) running = null
        val ttft = if (run.tFirst >= 0) run.tFirst - run.tSend else -1
        val generated = stats?.get(LlamaIpc.STATS_GENERATED) ?: 0
        val genMs = stats?.get(LlamaIpc.STATS_GENERATE_MS) ?: 0
        val tps = if (genMs > 0) generated * 1000.0 / genMs else -1.0
        val tokens = stats?.get(LlamaIpc.STATS_POSITION)?.toInt() ?: -1
        val gaveWay = run.giveWay && reason == DoneReason.CANCELLED
        RLog.i("brain_reply", "reason" to reason, "chunks" to run.chunks, "ttftMs" to ttft, "tokens" to tokens, "tps" to tps,
            "prompt" to (stats?.get(LlamaIpc.STATS_PROMPT) ?: -1L), "promptMs" to (stats?.get(LlamaIpc.STATS_PROMPT_MS) ?: -1L),
            "generated" to generated, "mode" to run.mode, "oneShot" to run.oneShot, "gaveWay" to gaveWay, "format" to ModelFormat.GGUF)
        // It gave way: no terminal event; the scheduler queues it again (EV_QUEUED) and it starts over later.
        if (gaveWay) return scheduler.ended(run.job, terminalSent = false)
        // Robot's text protocol: the app may read a reply that is nothing but calls as calls; never in chat only.
        run.sink.emit(BrainProtocol.EV_DONE, BrainMsg.Done(reason, tokens, ttft, tps, rebuilt = (stats?.get(LlamaIpc.STATS_REUSED) ?: 0L) == 0L,
            textCalls = BrainPolicy.acceptsCalls(run.mode)))
        scheduler.ended(run.job)
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

    /**
     * [run] was told to stop (a cancel, a give-way, its cap, an unload). The one-way stop reaches llama.cpp between tokens
     * and inside a prompt being read, so it comes back within a second or two; one that hasn't after [STOP_LIMIT_MS]
     * (llama.cpp stuck where the stop can't reach it, or a process a hostile file took over) would leave the request
     * without its end and every later one waiting behind it until an unload. Its process ends instead, as for the other
     * watchdogs: the reply ends with STOPPED (gone(), or onEnd), the waiting requests as not loaded.
     */
    private fun watchStop(run: Run, l: LlamaLink) {
        if (run.stopWatched) return
        run.stopWatched = true
        handler.postDelayed({
            if (!run.nativeEnded && !run.ended && link === l) {
                run.overdue = true
                stuck(l, "stop")
            }
        }, STOP_LIMIT_MS)
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
        /** … and to format and count a prompt (milliseconds) before :brain gives it up for stuck and ends its process… */
        private const val PREPARE_LIMIT_MS = 30_000L
        /** … and to come back from a reply it was told to stop (a second or two: a 512-token batch's slowest step). */
        private const val STOP_LIMIT_MS = 15_000L
        private const val STOPPING = "The model is still stopping. Try again in a moment."
        /** A one-shot's run has no conversation id of the app's. */
        private const val ONE_SHOT_CONV = ""
        private const val DIFFERENT = "llama.cpp read this file's header differently from Robot's check, so Robot won't run it."
    }
}
