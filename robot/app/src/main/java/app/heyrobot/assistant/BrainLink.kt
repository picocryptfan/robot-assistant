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
import app.heyrobot.core.BrainError
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.BrainProtocol
import app.heyrobot.core.BrainWire
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.sys.ModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

class BrainClient(private val ctx: Context, private val onDeath: () -> Unit) {
    private val death = DeathOnce()
    @Volatile private var messenger: Messenger? = null
    private var conn: ServiceConnection? = null
    @Volatile private var connected: CompletableDeferred<Boolean>? = null
    private val seq = AtomicInteger()
    private val streams = ConcurrentHashMap<Int, Channel<BrainMsg>>()
    private val replyThread = HandlerThread("robot-brain-reply").apply { start() }
    private val replies = Messenger(Handler(replyThread.looper) { m -> onReply(m); true })

    private fun onReply(m: Message) {
        val ch = streams[m.arg1] ?: return
        val msg = BrainWire.decode(m) ?: BrainMsg.Error(BrainError.BAD_REQUEST, "unreadable reply")
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
 * VOICE PROCESS. Keeps the binding to :brain: loads Gemma when the panel opens (so it is warm by the
 * time the question is asked), and unloads it a few minutes after the panel closes (setting).
 */
object BrainKeeper {
    enum class Readiness { UNKNOWN, NO_MODEL, LOADING, READY, FAILED }

    data class State(val readiness: Readiness = Readiness.UNKNOWN, val vision: Boolean = false, val problem: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var client: BrainClient? = null
    private var unloadJob: Job? = null
    private val deaths = ArrayDeque<Long>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** The connected client with Gemma loaded, or null (then [state] says why). */
    suspend fun ensureLoaded(ctx: Context): BrainClient? = mutex.withLock {
        touch()
        val path = ModelStore.verifiedPath(ctx)
        if (path == null) {
            _state.value = State(Readiness.NO_MODEL, problem = "Robot's model isn't on this phone yet. Open the Robot app to download it.")
            return@withLock null
        }
        synchronized(deaths) {
            val now = System.currentTimeMillis()
            while (deaths.isNotEmpty() && now - deaths.first() > 3_600_000) deaths.removeFirst()
            if (deaths.size >= 3) {
                _state.value = State(Readiness.FAILED, problem = "The model stopped unexpectedly three times in the last hour. Try again later.")
                return@withLock null
            }
        }
        val c = client ?: BrainClient(ctx.applicationContext) { onDeath() }.also { client = it }
        if (!c.connect()) {
            _state.value = State(Readiness.FAILED, problem = "Robot couldn't start its model process.")
            return@withLock null
        }
        val st = c.call(BrainProtocol.REQ_STATUS, BrainMsg.Empty, 10_000) as? BrainMsg.Status
        if (st?.loaded == true) {
            _state.value = State(Readiness.READY, vision = st.vision)
            return@withLock c
        }
        _state.value = State(Readiness.LOADING)
        val r = c.call(BrainProtocol.REQ_LOAD, BrainMsg.Load(path), 300_000) as? BrainMsg.Loaded
        if (r?.ok == true) {
            _state.value = State(Readiness.READY, vision = r.vision)
            RLog.i("brain_ready", "loadMs" to r.loadMs, "vision" to r.vision)
            c
        } else {
            _state.value = State(Readiness.FAILED, problem = r?.error ?: "The model didn't load in time.")
            null
        }
    }

    fun prewarm(ctx: Context) {
        scope.launch { ensureLoaded(ctx) }
    }

    fun touch() {
        unloadJob?.cancel()
        unloadJob = null
    }

    /** After the panel closed: unload after [minutes] (KEEP_ALWAYS = never, 0 = now). */
    fun scheduleUnload(minutes: Int) {
        touch()
        if (minutes < 0) return
        unloadJob = scope.launch {
            delay(minutes * 60_000L)
            release("idle")
        }
    }

    private fun onDeath() {
        synchronized(deaths) { deaths.addLast(System.currentTimeMillis()) }
        _state.value = State(Readiness.UNKNOWN)
    }

    /** Unbinds now (in the background); join the job to wait for it (free_memory does). */
    fun release(why: String): Job = scope.launch {
        mutex.withLock {
            val c = client ?: return@withLock
            client = null
            // Unbinding is enough: BrainService unloads when its last client leaves.
            c.close()
            _state.value = State(Readiness.UNKNOWN)
            RLog.i("brain_release", "why" to RLog.Code(why))
        }
    }
}
