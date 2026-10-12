package app.heyrobot.assistant

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import app.heyrobot.core.BackendCode
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainModel
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.BrainLoaded
import app.heyrobot.core.FallbackWhy
import app.heyrobot.core.GpuFailure
import app.heyrobot.core.GpuTrying
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.LoadPlan
import app.heyrobot.core.ModelAvailability
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelCatalogue
import app.heyrobot.core.ModelChoice
import app.heyrobot.core.ModelCode
import app.heyrobot.core.ModelFiles
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelProblem
import app.heyrobot.core.ModelProcess
import app.heyrobot.core.ProblemStage
import app.heyrobot.core.RLog
import app.heyrobot.core.RequestStages
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.Problems
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// VOICE PROCESS. The client side of BrainProtocol. No LiteRT here: :brain is bound by class name.

/**
 * One death of :brain is one death, however Android reports it: onServiceDisconnected, then often onBindingDied for the
 * same failure. Counted once per connection (pure, tested).
 */
class DeathOnce {
    private var counted = false

    @Synchronized fun connected() { counted = false }

    /** True the first time this connection is reported dead. */
    @Synchronized fun died(): Boolean = (!counted).also { counted = true }
}

/**
 * [onDeath]: :brain stopped, with where the model was then (reading a prompt or writing; null between answers).
 * [onModelStopped] (0.4.5): the model's own process stopped under a reply while :brain lives (llama.cpp's isolated
 * process, for a GGUF file): the same for the file it ran, nothing for :brain; with that reply's stage. [onEngineError]
 * (0.4.7): the engine itself failed a request it was answering (BrainError.ENGINE) at this stage; on the GPU that is the
 * GPU failing. 0.6.0: every request the model answers is followed (core RequestStages), the panel's and the one-shots
 * (translations, summary steps) alike, and each callback comes after the request's own event reached its waiter: a
 * fallback it starts (which may let go of this binding) never takes that request's one terminal event away.
 */
class BrainClient(
    private val ctx: Context,
    private val onDeath: (ProblemStage?) -> Unit,
    private val onModelStopped: (ProblemStage?) -> Unit = {},
    private val onEngineError: (ProblemStage) -> Unit = {},
) {
    private val death = DeathOnce()
    @Volatile private var messenger: Messenger? = null
    private var conn: ServiceConnection? = null
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    private val seq = AtomicInteger()
    private val streams = ConcurrentHashMap<Int, Channel<BrainMsg>>()
    private val replyThread = HandlerThread("robot-brain-reply").apply { start() }
    private val replies = Messenger(Handler(replyThread.looper) { m -> onReply(m); true })
    /** The requests the model answers, until their end (RequestStages.tracked), even one nobody waits for any more. */
    private val tracked: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    /** Each tracked request on the model now and its stage (none while it waits in :brain's queue). */
    private val stages = ConcurrentHashMap<Int, ProblemStage>()

    /** 0.4.7, for a problem note: where the model is now (writing, or reading a prompt), null between answers. */
    val stage: ProblemStage? get() = RequestStages.now(stages.values)

    private fun onReply(m: Message) {
        val msg = BrainWire.decode(m) ?: BrainMsg.Error(BrainError.BAD_REQUEST, "unreadable reply")
        // Where this request was before this event: an engine error or a stopped model is about that stage.
        val at = track(m.arg1, m.what, msg)
        val ch = streams[m.arg1]
        if (ch != null) {
            ch.trySend(msg)
            if (m.what in BrainProtocol.TERMINAL) {
                streams.remove(m.arg1)
                ch.close()
            }
        }
        val code = (msg as? BrainMsg.Error)?.code
        // News of the file even for a reply nobody waits for any more: the panel gave up on a reply that went silent, and
        // :brain then ended llama.cpp's process for not coming back from the stop.
        if (code == BrainError.STOPPED) onModelStopped(at)
        // 0.4.7: the engine failed a request it was answering (a one-shot too; never one still waiting in :brain's queue).
        if (code == BrainError.ENGINE && at != null) onEngineError(at)
    }

    /** Follows request [id] through event [what] (core RequestStages); its stage before the event (null: untracked, or waiting). */
    private fun track(id: Int, what: Int, msg: BrainMsg): ProblemStage? {
        if (id !in tracked) return null
        val before = stages[id]
        val next = RequestStages.after(before, what, (msg as? BrainMsg.Queued)?.position ?: -1)
        if (next == null) stages.remove(id) else stages[id] = next
        if (what in BrainProtocol.TERMINAL) tracked.remove(id)
        return before
    }

    suspend fun connect(timeoutMs: Long = 15_000): Boolean {
        if (messenger != null) return true
        val ready = CompletableDeferred<Boolean>().also { connected = it }
        if (conn == null) {
            val c = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    death.connected()
                    messenger = binder?.let { Messenger(it) }
                    connected?.complete(binder != null)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    val at = stage
                    messenger = null
                    endAll()
                    RLog.i("brain_disconnected")
                    if (death.died()) {
                        VoiceLog.add(VoiceEvent.BRAIN_DIED)
                        onDeath(at)
                    }
                }

                // The binding will never connect again (Android gave up restarting :brain). Keeping it would make every
                // later question wait 15 s for nothing; unbind, so the next connect() binds afresh.
                override fun onBindingDied(name: ComponentName?) {
                    val at = stage
                    RLog.i("brain_binding_died")
                    drop(this)
                    connected?.complete(false)
                    if (death.died()) {
                        VoiceLog.add(VoiceEvent.BRAIN_DIED)
                        onDeath(at)
                    }
                }

                override fun onNullBinding(name: ComponentName?) {
                    drop(this)
                    connected?.complete(false)
                }
            }
            // Foreground priority for :brain while the assistant uses it (a background process is throttled hard).
            val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or Context.BIND_INCLUDE_CAPABILITIES
            val ok = try {
                ctx.bindService(Intent().setClassName(ctx.packageName, BrainProtocol.SERVICE_CLASS), c, flags)
            } catch (t: Throwable) { RLog.w("brain_bind", t); false }
            if (!ok) return false
            conn = c
        }
        return withTimeoutOrNull(timeoutMs) { ready.await() } ?: false
    }

    /** Every open request ends (once each): the model and its conversations are gone with the process. */
    private fun endAll() {
        tracked.clear()
        stages.clear()
        for ((id, ch) in streams) {
            ch.trySend(BrainMsg.Error(BrainError.ENGINE, "model process stopped"))
            ch.close()
            streams.remove(id)
        }
    }

    private fun drop(c: ServiceConnection) {
        messenger = null
        endAll()
        if (conn === c) {
            try { ctx.unbindService(c) } catch (_: Exception) {}
            conn = null
        }
    }

    /** Gives up on request [id] (nothing came back in time): its flow ends now with an error. */
    fun abandon(id: Int) {
        streams.remove(id)?.let {
            it.trySend(BrainMsg.Error(BrainError.ENGINE, "no answer"))
            it.close()
        }
    }

    /** Sends a request; its events (ending with a terminal one) come out of the returned channel. */
    fun request(what: Int, payload: BrainMsg, image: ByteArray? = null): Pair<Int, Flow<BrainMsg>> {
        val id = seq.incrementAndGet()
        if (RequestStages.tracked(what)) tracked += id
        val ch = Channel<BrainMsg>(Channel.UNLIMITED)
        streams[id] = ch
        val m = messenger
        val mem = image?.let { BrainWire.share(it) }
        try {
            if (m == null) throw RemoteException("not connected")
            m.send(BrainWire.message(what, id, payload, mem).apply { replyTo = replies })
        } catch (e: Exception) {
            tracked.remove(id)
            streams.remove(id)
            ch.trySend(BrainMsg.Error(BrainError.ENGINE, "not connected"))
            ch.close()
        } finally {
            // The receiving process holds its own duplicate of the region's file descriptor.
            mem?.close()
        }
        return id to ch.consumeAsFlow()
    }

    /** A request with a single answer; null on timeout. */
    suspend fun call(what: Int, payload: BrainMsg, timeoutMs: Long): BrainMsg? {
        val (_, flow) = request(what, payload)
        var last: BrainMsg? = null
        withTimeoutOrNull(timeoutMs) { flow.collect { last = it } } ?: return null
        return last
    }

    /** Fire and forget (cancel, close a conversation). */
    fun post(what: Int, payload: BrainMsg) {
        try { messenger?.send(BrainWire.message(what, 0, payload).apply { replyTo = replies }) } catch (_: RemoteException) {}
    }

    val isConnected: Boolean get() = messenger != null

    fun close() {
        conn?.let { try { ctx.unbindService(it) } catch (_: Exception) {} }
        conn = null
        messenger = null
        tracked.clear()
        stages.clear()
        for ((_, ch) in streams) ch.close()
        streams.clear()
        replyThread.quitSafely()
    }
}

/**
 * VOICE PROCESS. Keeps the binding to :brain: loads the model when the panel opens (so it is warm by the time the
 * question is asked), and unloads it a few minutes after the panel closes (setting). 0.4.1: the model is the one chosen
 * in Settings › Model when it is ready, else Fast with a message that says why (core ModelChoice); another model in
 * memory is unloaded first, so two never coexist; a chosen model that fails to load is skipped (Fast instead) until you
 * choose it again or its file changes. 0.5.0: a conversation session that translates, or a summary being made, holds the
 * model ([hold]): the unload after the panel closes waits until the last hold goes. 0.4.7: "Fast" is this phone's (core
 * ModelCatalogue: the TPU build on a Tensor G5, the general build elsewhere), and the general build runs on the GPU where
 * Robot offers it and Settings › Model › "Use the GPU" is on. A GPU that doesn't load the model, or whose process stops
 * while it loads or answers, or whose engine fails a reply, is remembered (sys Problems: across restarts, so a GPU crash
 * never loops) and Robot runs the model on the CPU at once, says so once, and keeps a problem report for you to copy or
 * share. Any model's crash (on the CPU or the TPU too) keeps one as well. 0.6.0: one-shots (translations, summary steps)
 * run on the model as it is loaded, on the GPU too, and a GPU failure under one (an engine error, or :brain stopping)
 * counts as under a question: the CPU from the next request on. A GGUF file's crash is llama.cpp's process's (:llama),
 * noted as such, never as Fast's or the GPU's.
 */
object BrainKeeper {
    enum class Readiness { UNKNOWN, NO_MODEL, LOADING, READY, FAILED }

    data class State(
        val readiness: Readiness = Readiness.UNKNOWN,
        val vision: Boolean = false,
        val problem: String? = null,
        /** The model loaded (READY) or loading, and where it runs. */
        val model: BrainModel? = null,
        val backend: ModelBackend? = null,
        /** The model reads text only (a GGUF file, run by llama.cpp): it can't see a photo or the screen. */
        val textOnly: Boolean = false,
        /** Its context (0.4.3): under 4,096 it is chat only ([chatOnly]), whichever engine runs it. */
        val contextTokens: Int = ContextWindow.MAX_TOKENS,
        /** Its file has a vision encoder (your own file as its check found; every built-in model does; a GGUF file never). */
        val fileVision: Boolean = true,
        /** Robot is using Fast instead of the model you chose, and why (the panel shows it once per [fallbackSeq]). */
        val fallback: String? = null,
        val fallbackSeq: Int = 0,
    ) {
        /** A short-context model: a compact prompt, no tools, Robot's own answer to a request for an action (core ChatOnly). */
        val chatOnly: Boolean get() = ContextWindow.chatOnly(contextTokens)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var client: BrainClient? = null
    private var unloadJob: Job? = null
    /** Who keeps the model loaded besides the panel (conversation mode, a summary): tokens, so each lets go of its own. */
    private val holds = HashSet<String>()
    private val deaths = ArrayDeque<Long>()
    /** Models that failed to load in this process, or stopped it, by id: their file's fingerprint then, and which it was. */
    private val failed = HashMap<String, Pair<String, FallbackWhy>>()
    /** The model loading or in memory (any fallback included), for [onDeath]. */
    @Volatile private var holding: LoadPlan? = null
    /** The fallback in force, and which one it is (the panel shows a new one once, not the same one at every question). */
    private var lastFallback: String? = null
    private var lastFallbackKey: String? = null
    private var fallbackSeq = 0
    /** 0.4.7: a GPU fallback to say once, at the next state the panel sees (with any model fallback's words). */
    @Volatile private var pendingNotice: String? = null
    /** A load is under way: a :brain death during it is that load's failure (loadInto), not [onDeath]'s. */
    @Volatile private var loadUnderWay = false
    @Volatile private var appCtx: Context? = null
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun planOf(ctx: Context, m: BrainModel) = try { ModelStore.plan(ctx, m) } catch (t: Throwable) {
        RLog.w("brain_plan", t)
        ModelAvailability.NOT_CHECKED to null
    }

    private fun failedBefore(p: LoadPlan?): FallbackWhy? = p?.let { failed[it.model.id]?.takeIf { f -> f.first == it.fingerprint }?.second }

    /** What counts as the GPU failing a load (0.4.7). */
    private val GPU_FAILURES = setOf(LoadFailure.ENGINE, LoadFailure.TIMEOUT, LoadFailure.PROCESS, LoadFailure.NOT_ALLOWED)

    /** This phone's Fast (0.4.7). */
    private fun fastHere(ctx: Context): BrainModel = ModelCatalogue.fast(ModelStore.phone(ctx))

    /** The model to load now and why (pure core ModelChoice on what the files say), placed where it runs ([placed]). */
    private fun decide(ctx: Context): Pair<ModelChoice.Decision, LoadPlan?> {
        val phone = ModelStore.phone(ctx)
        val fast = ModelCatalogue.fast(phone)
        // Either Fast in the settings is this phone's (a Pixel 10's choice, or the default, on a Pixel 9a: the general build).
        val chosen = ModelCatalogue.resolve(VoiceHub.settings.brainModelChoice, phone)
        val (chosenState, chosenPlan) = planOf(ctx, chosen)
        val (fastState, fastPlan) = if (chosen == fast) chosenState to chosenPlan else planOf(ctx, fast)
        val earlier = failedBefore(chosenPlan)
        val d = ModelChoice.decide(chosen, chosenState, fastState, earlier != null, earlier ?: FallbackWhy.FAILED_EARLIER, fast)
        return d to placed(ctx, if (d is ModelChoice.Decision.Load && d.model == chosen) chosenPlan else fastPlan)
    }

    /**
     * [p] where it runs now (core ModelCatalogue.backend): the general E2B on the GPU when Robot offers it here, "Use the
     * GPU" is on and the GPU hasn't failed; else the plan's own backend. A GPU load that another voice process started and
     * never saw answered (the phone or Robot restarted in the middle of it) counts as a GPU failure first.
     */
    private fun placed(ctx: Context, p: LoadPlan?): LoadPlan? {
        if (p == null || p.model == BrainModel.OWN) return p
        Problems.staleGpuTry(ctx)?.let { t ->
            Problems.gpuTrying(ctx, null)
            if (Problems.gpuFailure(ctx) == null) {
                Problems.gpuFailed(ctx, GpuFailure(t.model, t.time, ProblemStage.LOADING, null))
                VoiceLog.add(VoiceEvent.GPU_FALLBACK, ModelCode.of(t.model), ProblemStage.LOADING)
                RLog.i("brain_gpu_failed", "stage" to ProblemStage.LOADING, "stale" to true)
                val problem = ModelProblem(t.time, t.model, ModelBackend.GPU, ProblemStage.LOADING, null, t.model, ModelBackend.CPU)
                Problems.note(ctx, problem)
                pendingNotice = gpuNotice(problem)
            }
        }
        val gpuFailed = Problems.gpuFailure(ctx)?.model == p.model
        val b = ModelCatalogue.backend(p.model, ModelStore.phone(ctx), VoiceHub.settings.modelGpu, gpuFailed)
        return if (b == p.backend) p else p.copy(backend = b)
    }

    private fun gpuNotice(p: ModelProblem) = p.headline() + " The Robot app › Model has a report you can copy or share."

    /**
     * Keeps a problem note (codes and numbers, core ModelProblem) with the memory now and the process id of :brain when it
     * is the [process] that failed, and tells you once. 0.6.0: [process] is :llama for a GGUF file unless :brain itself went.
     */
    private fun noteProblem(ctx: Context, plan: LoadPlan, stage: ProblemStage, failure: LoadFailure?, then: BrainModel?, thenBackend: ModelBackend?,
        process: ModelProcess = ModelProcess.of(plan.format)): ModelProblem {
        val (free, total, low) = Problems.memory(ctx)
        val pid = if (process != ModelProcess.BRAIN) 0
            else BrainLoaded.decode(readOrNull(java.io.File(Paths.dir(ctx), ModelFiles.LOADED)))?.takeIf { it.model == plan.model }?.pid ?: 0
        val p = ModelProblem(System.currentTimeMillis(), plan.model, plan.backend, stage, failure, then, thenBackend, free, total, low, pid,
            plan.format, process)
        Problems.note(ctx, p)
        RLog.i("brain_problem", "model" to RLog.Code(plan.model.id), "backend" to RLog.Code(plan.backend.id), "stage" to stage,
            "failure" to (failure?.let { RLog.Code(it.name) }), "process" to RLog.Code(process.name), "low" to low)
        return p
    }

    /** The connected client with a model loaded, or null (then [state] says why). */
    suspend fun ensureLoaded(ctx: Context): BrainClient? = mutex.withLock {
        touch()
        appCtx = ctx.applicationContext
        val (decision, plan) = decide(ctx)
        if (decision is ModelChoice.Decision.NoLoad) {
            noteFallback(null, null, null)
            _state.value = State(if (decision.openApp) Readiness.NO_MODEL else Readiness.FAILED, problem = decision.problem)
            return@withLock null
        }
        val load = decision as ModelChoice.Decision.Load
        synchronized(deaths) {
            val now = System.currentTimeMillis()
            while (deaths.isNotEmpty() && now - deaths.first() > 3_600_000) deaths.removeFirst()
            if (deaths.size >= 3) {
                _state.value = State(Readiness.FAILED, problem = "The model stopped unexpectedly three times in the last hour. Try again later.")
                return@withLock null
            }
        }
        val c = client ?: BrainClient(ctx.applicationContext, ::onDeath, ::onModelStopped, ::onEngineError).also { client = it }
        if (!c.connect()) {
            _state.value = State(Readiness.FAILED, problem = "Robot couldn't start its model process.")
            return@withLock null
        }
        loadInto(ctx, c, plan!!, load)
    }

    /**
     * Loads [plan] (unloading another model first); on failure, Fast instead when that makes sense (0.4.7: the CPU instead
     * when the GPU failed it). [detail]: why the model you chose didn't load, for the fallback's message.
     */
    private suspend fun loadInto(ctx: Context, c: BrainClient, plan: LoadPlan, load: ModelChoice.Decision.Load, detail: String? = null): BrainClient? {
        val m = plan.model
        val st = c.call(BrainProtocol.REQ_STATUS, BrainMsg.Empty, 10_000) as? BrainMsg.Status
        val textOnly = plan.format == ModelFormat.GGUF
        // The same model with the same context (0.4.3: a file checked again may now say another) and the same kind of file
        // (the engine that runs it) where it should run (0.4.7: the GPU or the CPU): kept, else loaded again.
        if (st?.loaded == true && st.model == m && st.backend == plan.backend && st.maxTokens == plan.contextTokens &&
            (st.format ?: ModelFormat.LITERT_LM) == plan.format) {
            holding = plan
            noteFallback(load.instead, load.why, detail)
            _state.value = State(Readiness.READY, vision = st.vision, model = m, backend = plan.backend, textOnly = textOnly,
                contextTokens = plan.contextTokens, fileVision = plan.vision).withFallback()
            return c
        }
        if (st?.loaded == true) {
            // Another model is in memory: it goes first (GemmaEngine does the same if asked to load beside it).
            st.model?.let { VoiceLog.add(VoiceEvent.MODEL_SWITCH, ModelCode.of(it), ModelCode.of(m)) }
            c.call(BrainProtocol.REQ_UNLOAD, BrainMsg.Empty, 30_000)
        }
        noteFallback(load.instead, load.why, detail)
        holding = plan
        _state.value = State(Readiness.LOADING, model = m, backend = plan.backend, textOnly = textOnly, contextTokens = plan.contextTokens,
            fileVision = plan.vision).withFallback()
        val fast = fastHere(ctx)
        // A GPU load is marked first: if it takes the phone or Robot down, the next start knows (placed).
        val gpu = plan.backend == ModelBackend.GPU
        if (gpu) Problems.gpuTrying(ctx, GpuTrying(m, System.currentTimeMillis(), android.os.Process.myPid()))
        loadUnderWay = true
        val r = try {
            c.call(BrainProtocol.REQ_LOAD, BrainMsg.Load(plan.path, m, plan.backend, plan.contextTokens, plan.format), 300_000) as? BrainMsg.Loaded
        } finally {
            loadUnderWay = false
            if (gpu) Problems.gpuTrying(ctx, null)
        }
        if (r?.ok == true) {
            if (m != fast) failed.remove(m.id)
            _state.value = State(Readiness.READY, vision = r.vision, model = m, backend = plan.backend, textOnly = textOnly,
                contextTokens = plan.contextTokens, fileVision = plan.vision).withFallback()
            VoiceLog.add(VoiceEvent.MODEL_LOADED, ModelCode.of(m).id, BackendCode.of(plan.backend).id, r.loadMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
            RLog.i("brain_ready", "loadMs" to r.loadMs, "vision" to r.vision, "model" to RLog.Code(m.id), "backend" to RLog.Code(plan.backend.id),
                "format" to plan.format, "chatOnly" to plan.chatOnly)
            return c
        }
        val failure = r?.failure ?: if (r == null) (if (c.isConnected) LoadFailure.TIMEOUT else LoadFailure.PROCESS) else LoadFailure.ENGINE
        holding = null
        VoiceLog.add(VoiceEvent.MODEL_LOAD_FAILED, ModelCode.of(m), failure)
        // 0.4.7: the GPU didn't load it (an error, a refusal, no answer, or its process stopped): the CPU from now on,
        // remembered across restarts until you try the GPU again, said once, with a report to copy or share. (No room
        // for a cache, a missing file or the last model still stopping aren't the GPU's doing: they go on as before.)
        if (gpu && failure in GPU_FAILURES) {
            Problems.gpuFailed(ctx, GpuFailure(m, System.currentTimeMillis(), ProblemStage.LOADING, failure))
            VoiceLog.add(VoiceEvent.GPU_FALLBACK, ModelCode.of(m), ProblemStage.LOADING, failure.id)
            RLog.i("brain_gpu_failed", "stage" to ProblemStage.LOADING, "failure" to RLog.Code(failure.name))
            pendingNotice = gpuNotice(noteProblem(ctx, plan, ProblemStage.LOADING, failure, m, ModelBackend.CPU))
            if (failure == LoadFailure.TIMEOUT) {
                // :brain may still be stuck in the GPU load: let it go (its teardown ends the process if it has to) and load
                // on the CPU from the next question, in a fresh binding.
                client = null
                c.close()
                _state.value = State(Readiness.FAILED, problem = "Fast didn't load on the GPU in time. Robot uses the CPU from the next question.")
                return null
            }
            if (!c.isConnected && !c.connect()) {
                _state.value = State(Readiness.FAILED, problem = "Robot couldn't start its model process.")
                return null
            }
            return loadInto(ctx, c, plan.copy(backend = ModelBackend.CPU), load, detail)
        }
        if (m != fast && ModelChoice.remember(failure)) failed[m.id] = plan.fingerprint to FallbackWhy.FAILED_EARLIER
        val (fastState, fastPlanFound) = planOf(ctx, fast)
        val fastPlan = placed(ctx, fastPlanFound)
        val next = ModelChoice.afterFailure(m, failure, fastState, r?.error ?: "The model didn't load in time.", fast)
        // A crash or no answer (not a refusal Robot explains itself) keeps a report too, on the CPU or the TPU; a GGUF file's
        // in llama.cpp's process (:llama) unless :brain itself went (no answer, and the binding gone).
        if (failure == LoadFailure.ENGINE || failure == LoadFailure.TIMEOUT || failure == LoadFailure.PROCESS) {
            val then = if (next is ModelChoice.Decision.Load && fastPlan != null && m != fast) fastPlan else null
            noteProblem(ctx, plan, ProblemStage.LOADING, failure, then?.model, then?.backend,
                if (r == null && !c.isConnected) ModelProcess.BRAIN else ModelProcess.of(plan.format))
        }
        if (next is ModelChoice.Decision.Load && fastPlan != null && m != fast) {
            // :brain may have died with the model: it comes back (or is bound again) for Fast.
            if (!c.isConnected && !c.connect()) {
                _state.value = State(Readiness.FAILED, problem = "Robot couldn't start its model process.")
                return null
            }
            // Fast's own failure ends there (m is Fast in that call): once only.
            val why = when (failure) {
                LoadFailure.ENGINE, LoadFailure.TIMEOUT, LoadFailure.PROCESS -> failure.title
                else -> r?.error?.removeSuffix(".")?.replaceFirstChar { it.lowercase() }
            }
            return loadInto(ctx, c, fastPlan, next, why)
        }
        val problem = (next as? ModelChoice.Decision.NoLoad)?.problem ?: r?.error ?: "The model didn't load in time."
        _state.value = State(if ((next as? ModelChoice.Decision.NoLoad)?.openApp == true) Readiness.NO_MODEL else Readiness.FAILED, problem = problem)
        return null
    }

    /**
     * Remembers the fallback in force ([wanted] null: none) and counts and logs a new one. A model that didn't load
     * and is then skipped for having failed earlier is the same fallback: its words change, the panel isn't told again.
     */
    private fun noteFallback(wanted: BrainModel?, why: FallbackWhy?, detail: String?) {
        val key = if (wanted != null && why != null) wanted.id + "/" + (if (why == FallbackWhy.FAILED_EARLIER) FallbackWhy.LOAD_FAILED else why).name else null
        if (key != null && key != lastFallbackKey) {
            fallbackSeq++
            val fast = appCtx?.let { fastHere(it) } ?: BrainModel.FAST
            VoiceLog.add(VoiceEvent.MODEL_FALLBACK, ModelCode.of(wanted!!), why!!, ModelCode.of(fast).id)
            RLog.i("brain_fallback", "wanted" to RLog.Code(wanted.id), "why" to why)
        }
        lastFallbackKey = key
        lastFallback = if (wanted != null && why != null) ModelChoice.fallbackText(wanted, why, detail) else null
        // A GPU fallback is said once, beside any model fallback's words (its own count: never the model fallback again).
        pendingNotice?.let { n ->
            pendingNotice = null
            fallbackSeq++
            lastFallback = listOfNotNull(lastFallback, n).joinToString(" ")
        }
    }

    private fun State.withFallback() = copy(fallback = lastFallback, fallbackSeq = fallbackSeq)

    fun prewarm(ctx: Context) {
        scope.launch { ensureLoaded(ctx) }
    }

    /**
     * [ensureLoaded] on the keeper's own scope: a one-shot that stops waiting for it at its start deadline (the fast
     * engine stands in) leaves the check (or a load) to finish rather than cutting it short.
     */
    fun loading(ctx: Context): Deferred<BrainClient?> = scope.async { ensureLoaded(ctx) }

    /**
     * Settings changed (any save, so tapping the chosen model again retries it): forget load failures, and when the
     * model in memory is no longer the one to use, let it go now (its memory, and so the app may delete it). 0.4.7: also
     * the same model elsewhere ("Use the GPU" switched, VoiceHub's [modelChanged]).
     */
    fun settingsChanged(ctx: Context, modelChanged: Boolean) {
        scope.launch {
            mutex.withLock { failed.clear() }
            if (!modelChanged) return@launch
            val inMemory = _state.value.model ?: return@launch
            val (decision, plan) = mutex.withLock { decide(ctx) }
            // Another model, or the same one elsewhere ("Use the GPU" switched): let go of it now.
            if ((decision as? ModelChoice.Decision.Load)?.model != inMemory || (plan != null && plan.backend != _state.value.backend)) release("model_changed")
        }
    }

    fun touch() {
        unloadJob?.cancel()
        unloadJob = null
    }

    /** After the panel closed: unload after [minutes] (KEEP_ALWAYS = never, 0 = now), unless something [hold]s it. */
    fun scheduleUnload(minutes: Int) {
        touch()
        if (minutes < 0 || held()) return
        unloadJob = scope.launch {
            delay(minutes * 60_000L)
            if (!held()) release("idle")
        }
    }

    private fun held(): Boolean = synchronized(holds) { holds.isNotEmpty() }

    /**
     * [token] keeps the model loaded (docs/conversation-mode.md 1.1): a session that translates, or a summary being
     * made, so the unload after the panel closes doesn't drop it midway. Free memory (the tool) still unloads it.
     */
    fun hold(token: String) {
        synchronized(holds) { holds += token }
        touch()
    }

    /** [token] lets go; with no hold left and the panel closed, the usual unload after Settings › Model's minutes. */
    fun releaseHold(token: String) {
        val last = synchronized(holds) { holds.remove(token) && holds.isEmpty() }
        if (last && !PanelController.shown && _state.value.readiness == Readiness.READY) scheduleUnload(VoiceHub.settings.keepModelMinutes)
    }

    /** Ready now, and on what (for translation's choice of engine and deadlines). */
    val ready: Boolean get() = _state.value.readiness == Readiness.READY

    /** The model you chose as this phone reads it (0.4.7: either Fast is this phone's), before anything loads. */
    fun modelHere(ctx: Context): BrainModel = ModelCatalogue.resolve(VoiceHub.settings.brainModelChoice, ModelStore.phone(ctx))

    /**
     * Where [modelHere] would run now (0.4.7: the general E2B on the GPU unless "Use the GPU" is off or the GPU failed), for
     * deadlines chosen before the model loads (translations).
     */
    fun backendHere(ctx: Context): ModelBackend {
        val m = modelHere(ctx)
        return ModelCatalogue.backend(m, ModelStore.phone(ctx), VoiceHub.settings.modelGpu, Problems.gpuFailure(ctx)?.model == m)
    }

    /**
     * 0.4.7: the engine failed a request on the GPU (an OpenCL error, as a Tensor G4's Mali GPU can give after a few turns;
     * 0.6.0: a one-shot's as much as a question's): the GPU counts as failed, as if it had crashed. The engine goes now, so
     * the next question (or one-shot) loads the model on the CPU; this request's own error has already reached its waiter,
     * its one end. On the CPU or the TPU nothing changes (the panel says the model failed).
     */
    private fun onEngineError(stage: ProblemStage) {
        val p = holding ?: return
        val ctx = appCtx ?: return
        if (p.backend != ModelBackend.GPU || loadUnderWay) return
        scope.launch {
            if (Problems.gpuFailure(ctx)?.model == p.model) return@launch
            Problems.gpuFailed(ctx, GpuFailure(p.model, System.currentTimeMillis(), stage, LoadFailure.ENGINE))
            VoiceLog.add(VoiceEvent.GPU_FALLBACK, ModelCode.of(p.model), stage, LoadFailure.ENGINE.id)
            RLog.i("brain_gpu_failed", "stage" to stage, "failure" to RLog.Code(LoadFailure.ENGINE.name))
            pendingNotice = gpuNotice(noteProblem(ctx, p, stage, LoadFailure.ENGINE, p.model, ModelBackend.CPU))
            release("gpu_failed")
        }
    }

    private fun onDeath(stage: ProblemStage?) {
        synchronized(deaths) { deaths.addLast(System.currentTimeMillis()) }
        modelStopped(stage, ModelProcess.BRAIN)
    }

    /**
     * 0.4.5: llama.cpp's isolated process stopped under a reply (a crash, its watchdog, Android), and :brain lives: the
     * same lesson for that file as when :brain stopped, without counting toward the three an hour (Fast is unharmed).
     * 0.6.0: noted as :llama's (your GGUF file's), never as Fast's or the GPU's.
     */
    private fun onModelStopped(stage: ProblemStage?) {
        VoiceLog.add(VoiceEvent.BRAIN_DIED)
        RLog.i("brain_model_stopped")
        modelStopped(stage, ModelProcess.LLAMA)
    }

    private fun modelStopped(stage: ProblemStage?, process: ModelProcess) {
        // A model you chose that took its process down while in memory (a native crash as it answered, or Android
        // reclaiming its memory) isn't loaded again at the next question, nor at every one until the lockout above stops
        // Fast too: Fast instead, with a notice, until you choose it again. (A death during its load is the load's failure.)
        val p = holding
        holding = null
        val ctx = appCtx
        val duringLoad = loadUnderWay
        val fast = ctx?.let { fastHere(it) } ?: BrainModel.FAST
        if (p != null && p.model != fast) scope.launch {
            mutex.withLock { if (failed[p.model.id]?.first != p.fingerprint) failed[p.model.id] = p.fingerprint to FallbackWhy.STOPPED }
        }
        // 0.4.7: a death while it answered (reading the prompt or writing: a question, or 0.6.0 a one-shot) is a crash worth
        // a report; one between answers is most likely Android reclaiming memory, and is left as before. :brain's on the GPU:
        // the CPU from now on. (llama.cpp's process runs only a GGUF file: a late word of it under another model is no note.)
        if (p != null && ctx != null && !duringLoad && stage != null && (process == ModelProcess.BRAIN || p.format == ModelFormat.GGUF)) scope.launch {
            if (p.backend == ModelBackend.GPU && process == ModelProcess.BRAIN) {
                Problems.gpuFailed(ctx, GpuFailure(p.model, System.currentTimeMillis(), stage, null))
                VoiceLog.add(VoiceEvent.GPU_FALLBACK, ModelCode.of(p.model), stage)
                RLog.i("brain_gpu_failed", "stage" to stage)
                pendingNotice = gpuNotice(noteProblem(ctx, p, stage, null, p.model, ModelBackend.CPU, process))
            } else {
                noteProblem(ctx, p, stage, null, if (p.model != fast) fast else null, null, process)
            }
        }
        _state.value = State(Readiness.UNKNOWN)
    }

    /** Unbinds now (in the background); join the job to wait for it (free_memory does). */
    fun release(why: String): Job = scope.launch {
        mutex.withLock {
            val c = client ?: return@withLock
            client = null
            holding = null
            // Unbinding is enough: BrainService unloads when its last client leaves.
            c.close()
            _state.value = State(Readiness.UNKNOWN)
            RLog.i("brain_release", "why" to RLog.Code(why))
        }
    }
}
