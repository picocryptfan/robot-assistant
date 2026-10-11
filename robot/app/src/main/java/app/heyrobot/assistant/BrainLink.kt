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
import app.heyrobot.core.FallbackWhy
import app.heyrobot.core.LoadFailure
import app.heyrobot.core.LoadPlan
import app.heyrobot.core.ModelAvailability
import app.heyrobot.core.ModelBackend
import app.heyrobot.core.ModelChoice
import app.heyrobot.core.ModelCode
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.ModelStore
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
 * [onDeath]: :brain stopped. [onModelStopped] (0.4.5): the model's own process stopped under a reply while :brain lives
 * (llama.cpp's isolated process, for a GGUF file): the same for the file it ran, nothing for :brain.
 */
class BrainClient(private val ctx: Context, private val onDeath: () -> Unit, private val onModelStopped: () -> Unit = {}) {
    private val death = DeathOnce()
    @Volatile private var messenger: Messenger? = null
    private var conn: ServiceConnection? = null
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    private val seq = AtomicInteger()
    private val streams = ConcurrentHashMap<Int, Channel<BrainMsg>>()
    private val replyThread = HandlerThread("robot-brain-reply").apply { start() }
    private val replies = Messenger(Handler(replyThread.looper) { m -> onReply(m); true })

    private fun onReply(m: Message) {
        val msg = BrainWire.decode(m) ?: BrainMsg.Error(BrainError.BAD_REQUEST, "unreadable reply")
        // News of the file even for a reply nobody waits for any more: the panel gave up on a reply that went silent, and
        // :brain then ended llama.cpp's process for not coming back from the stop.
        if (msg is BrainMsg.Error && msg.code == BrainError.STOPPED) onModelStopped()
        val ch = streams[m.arg1] ?: return
        ch.trySend(msg)
        if (m.what in BrainProtocol.TERMINAL) {
            streams.remove(m.arg1)
            ch.close()
        }
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
                    messenger = null
                    endAll()
                    RLog.i("brain_disconnected")
                    if (death.died()) {
                        VoiceLog.add(VoiceEvent.BRAIN_DIED)
                        onDeath()
                    }
                }

                // The binding will never connect again (Android gave up restarting :brain). Keeping it would make every
                // later question wait 15 s for nothing; unbind, so the next connect() binds afresh.
                override fun onBindingDied(name: ComponentName?) {
                    RLog.i("brain_binding_died")
                    drop(this)
                    connected?.complete(false)
                    if (death.died()) {
                        VoiceLog.add(VoiceEvent.BRAIN_DIED)
                        onDeath()
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

    /** Every open request ends: the model and its conversations are gone with the process. */
    private fun endAll() {
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
        val ch = Channel<BrainMsg>(Channel.UNLIMITED)
        streams[id] = ch
        val m = messenger
        val mem = image?.let { BrainWire.share(it) }
        try {
            if (m == null) throw RemoteException("not connected")
            m.send(BrainWire.message(what, id, payload, mem).apply { replyTo = replies })
        } catch (e: Exception) {
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
 * model ([hold]): the unload after the panel closes waits until the last hold goes.
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
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    private fun planOf(ctx: Context, m: BrainModel) = try { ModelStore.plan(ctx, m) } catch (t: Throwable) {
        RLog.w("brain_plan", t)
        ModelAvailability.NOT_CHECKED to null
    }

    private fun failedBefore(p: LoadPlan?): FallbackWhy? = p?.let { failed[it.model.id]?.takeIf { f -> f.first == it.fingerprint }?.second }

    /** The model to load now and why (pure core ModelChoice on what the files say). */
    private fun decide(ctx: Context): Pair<ModelChoice.Decision, LoadPlan?> {
        val chosen = VoiceHub.settings.brainModelChoice
        val (chosenState, chosenPlan) = planOf(ctx, chosen)
        val (fastState, fastPlan) = if (chosen == BrainModel.FAST) chosenState to chosenPlan else planOf(ctx, BrainModel.FAST)
        val earlier = failedBefore(chosenPlan)
        val d = ModelChoice.decide(chosen, chosenState, fastState, earlier != null, earlier ?: FallbackWhy.FAILED_EARLIER)
        return d to if (d is ModelChoice.Decision.Load && d.model == chosen) chosenPlan else fastPlan
    }

    /** The connected client with a model loaded, or null (then [state] says why). */
    suspend fun ensureLoaded(ctx: Context): BrainClient? = mutex.withLock {
        touch()
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
        val c = client ?: BrainClient(ctx.applicationContext, ::onDeath, ::onModelStopped).also { client = it }
        if (!c.connect()) {
            _state.value = State(Readiness.FAILED, problem = "Robot couldn't start its model process.")
            return@withLock null
        }
        loadInto(ctx, c, plan!!, load)
    }

    /**
     * Loads [plan] (unloading another model first); on failure, Fast instead when that makes sense. [detail]: why the
     * model you chose didn't load, for the fallback's message.
     */
    private suspend fun loadInto(ctx: Context, c: BrainClient, plan: LoadPlan, load: ModelChoice.Decision.Load, detail: String? = null): BrainClient? {
        val m = plan.model
        val st = c.call(BrainProtocol.REQ_STATUS, BrainMsg.Empty, 10_000) as? BrainMsg.Status
        val textOnly = plan.format == ModelFormat.GGUF
        // The same model with the same context (0.4.3: a file checked again may now say another) and the same kind of file
        // (the engine that runs it): kept, else loaded again.
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
        val r = c.call(BrainProtocol.REQ_LOAD, BrainMsg.Load(plan.path, m, plan.backend, plan.contextTokens, plan.format), 300_000) as? BrainMsg.Loaded
        if (r?.ok == true) {
            if (m != BrainModel.FAST) failed.remove(m.id)
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
        if (m != BrainModel.FAST && ModelChoice.remember(failure)) failed[m.id] = plan.fingerprint to FallbackWhy.FAILED_EARLIER
        val (fastState, fastPlan) = planOf(ctx, BrainModel.FAST)
        val next = ModelChoice.afterFailure(m, failure, fastState, r?.error ?: "The model didn't load in time.")
        if (next is ModelChoice.Decision.Load && fastPlan != null && m != BrainModel.FAST) {
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
            VoiceLog.add(VoiceEvent.MODEL_FALLBACK, ModelCode.of(wanted!!), why!!, ModelCode.FAST.id)
            RLog.i("brain_fallback", "wanted" to RLog.Code(wanted.id), "why" to why)
        }
        lastFallbackKey = key
        lastFallback = if (wanted != null && why != null) ModelChoice.fallbackText(wanted, why, detail) else null
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
     * model in memory is no longer the one to use, let it go now (its memory, and so the app may delete it).
     */
    fun settingsChanged(ctx: Context, modelChanged: Boolean) {
        scope.launch {
            mutex.withLock { failed.clear() }
            if (!modelChanged) return@launch
            val inMemory = _state.value.model ?: return@launch
            val (decision, _) = mutex.withLock { decide(ctx) }
            if ((decision as? ModelChoice.Decision.Load)?.model != inMemory) release("model_changed")
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

    private fun onDeath() {
        synchronized(deaths) { deaths.addLast(System.currentTimeMillis()) }
        modelStopped()
    }

    /**
     * 0.4.5: llama.cpp's isolated process stopped under a reply (a crash, its watchdog, Android), and :brain lives: the
     * same lesson for that file as when :brain stopped, without counting toward the three an hour (Fast is unharmed).
     */
    private fun onModelStopped() {
        VoiceLog.add(VoiceEvent.BRAIN_DIED)
        RLog.i("brain_model_stopped")
        modelStopped()
    }

    private fun modelStopped() {
        // A model you chose that took its process down while in memory (a native crash as it answered, or Android
        // reclaiming its memory) isn't loaded again at the next question, nor at every one until the lockout above stops
        // Fast too: Fast instead, with a notice, until you choose it again. (A death during its load is the load's failure.)
        val p = holding
        holding = null
        if (p != null && p.model != BrainModel.FAST) scope.launch {
            mutex.withLock { if (failed[p.model.id]?.first != p.fingerprint) failed[p.model.id] = p.fingerprint to FallbackWhy.STOPPED }
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
