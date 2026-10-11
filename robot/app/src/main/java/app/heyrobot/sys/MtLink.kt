package app.heyrobot.sys

import android.app.Application
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
import app.heyrobot.core.LangPair
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.MtMsg
import app.heyrobot.core.MtPolicy
import app.heyrobot.core.MtPriority
import app.heyrobot.core.MtProtocol
import app.heyrobot.core.MtTransport
import app.heyrobot.core.MtWire
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// Main and voice processes (0.5.0). The client of the :mt process (core MtProtocol, docs/conversation-mode.md 4.5): it
// binds MtService by class name, so neither process ever loads a class of the :mt module or librobotmt.so; it sends a
// request and waits for its answer, or for the :mt process to die (MtError.DISCONNECTED: an abort in slimt ends that
// process only, and the binding brings it back for the next request). The main app uses it for Test translation speed;
// from phase 5.4 the voice process's BergamotTranslator does (core Translate.kt). close() unbinds: once no one is bound,
// :mt frees the engine.

class MtLink(ctx: Context) : MtTransport, AutoCloseable {
    private val app = ctx.applicationContext
    private val thread = HandlerThread("robot-mt-link").apply { start() }
    private val replies = Messenger(Handler(thread.looper) { m -> onReply(m); true })
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<MtMsg>>()
    private val ids = AtomicInteger(1)

    @Volatile private var service: Messenger? = null
    // Guarded by this.
    private var connecting: CompletableDeferred<Messenger?>? = null
    private var bound = false
    private var closed = false

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val m = binder?.let(::Messenger)
            service = m
            synchronized(this@MtLink) { connecting?.complete(m) }
        }

        /** The :mt process died: every request waiting fails; Android starts the service again for the next one. */
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            failAll(MtError.DISCONNECTED)
        }

        override fun onBindingDied(name: ComponentName?) {
            service = null
            failAll(MtError.DISCONNECTED)
            synchronized(this@MtLink) {
                if (bound) try { app.unbindService(this) } catch (_: Exception) {}
                bound = false
            }
        }

        override fun onNullBinding(name: ComponentName?) {
            synchronized(this@MtLink) { connecting?.complete(null) }
        }
    }

    private fun failAll(e: MtError) {
        for (d in pending.values) d.complete(MtMsg.Error(e))
    }

    private fun onReply(m: Message) {
        val p = MtWire.decode(m) ?: return
        pending[m.arg1]?.complete(p)
    }

    private suspend fun messenger(): Messenger {
        service?.let { return it }
        val wait = synchronized(this) {
            if (closed) throw MtException(MtError.CANCELLED)
            connecting?.takeIf { !it.isCompleted } ?: CompletableDeferred<Messenger?>().also { d ->
                connecting = d
                if (!bound) {
                    bound = try {
                        app.bindService(Intent().setClassName(app.packageName, MtProtocol.SERVICE_CLASS), conn, Context.BIND_AUTO_CREATE)
                    } catch (_: Exception) { false }
                    if (!bound) d.complete(null)
                }
            }
        }
        service?.let { return it }
        return withTimeoutOrNull(MtPolicy.CONNECT_MS) { wait.await() } ?: throw MtException(MtError.DISCONNECTED)
    }

    /** Sends one request and waits up to [timeoutMs] for its answer; an error answer throws [MtException]. */
    private suspend fun request(what: Int, payload: MtMsg, timeoutMs: Long): MtMsg {
        val to = messenger()
        val id = ids.getAndIncrement()
        val d = CompletableDeferred<MtMsg>()
        pending[id] = d
        try {
            try {
                to.send(MtWire.message(what, id, payload).apply { replyTo = replies })
            } catch (_: RemoteException) {
                throw MtException(MtError.DISCONNECTED)
            }
            val r = withTimeoutOrNull(timeoutMs) { d.await() }
            if (r == null) {
                if (what == MtProtocol.REQ_TRANSLATE) cancel(to, id)
                throw MtException(MtError.TIMEOUT)
            }
            if (r is MtMsg.Error) throw MtException(r.error)
            return r
        } catch (e: CancellationException) {
            // The caller stopped waiting: the lines not started yet aren't translated for nothing.
            if (what == MtProtocol.REQ_TRANSLATE) cancel(to, id)
            throw e
        } finally {
            pending.remove(id)
        }
    }

    private fun cancel(to: Messenger, target: Int) {
        try { to.send(MtWire.message(MtProtocol.REQ_CANCEL, ids.getAndIncrement(), MtMsg.Cancel(target)).apply { replyTo = replies }) } catch (_: RemoteException) {}
    }

    override suspend fun translate(direction: String, lines: List<String>, deadlineMs: Long, priority: MtPriority): MtMsg.Translated =
        request(MtProtocol.REQ_TRANSLATE, MtMsg.Translate(direction, lines, deadlineMs, priority), deadlineMs + MtPolicy.REPLY_GRACE_MS) as? MtMsg.Translated
            ?: throw MtException(MtError.BAD_REQUEST)

    /** Loads [pair]'s directions now (the voice log gets MT_LOAD when something loaded, or didn't). */
    suspend fun load(pair: LangPair): MtMsg.Loaded {
        val t0 = System.currentTimeMillis()
        try {
            val r = request(MtProtocol.REQ_LOAD, MtMsg.Load(pair.id), MtPolicy.LOAD_MS) as? MtMsg.Loaded ?: throw MtException(MtError.BAD_REQUEST)
            if (r.ms.isNotEmpty()) log(r.ms.values.sum().toInt(), 1, r.ms.size)
            return r
        } catch (e: MtException) {
            log((System.currentTimeMillis() - t0).toInt(), 0, e.error.id)
            throw e
        }
    }

    suspend fun status(): MtMsg.Status = request(MtProtocol.REQ_STATUS, MtMsg.Empty, MtPolicy.CONNECT_MS) as? MtMsg.Status
        ?: throw MtException(MtError.BAD_REQUEST)

    suspend fun unload() {
        request(MtProtocol.REQ_UNLOAD, MtMsg.Empty, MtPolicy.CONNECT_MS)
    }

    /** Each process logs to its own file: the voice process to the voice log, the main app to its small one. */
    private fun log(a: Int, b: Int, c: Int) {
        try {
            VoiceLog.init(if (Application.getProcessName().contains(':')) Paths.voiceLog(app) else Paths.voiceLogMain(app))
            VoiceLog.add(VoiceEvent.MT_LOAD, a, b, c)
            VoiceLog.flush()
        } catch (t: Throwable) {
            RLog.w("mt_log", t)
        }
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            if (bound) try { app.unbindService(conn) } catch (_: Exception) {}
            bound = false
            connecting?.complete(null)
        }
        service = null
        failAll(MtError.CANCELLED)
        thread.quitSafely()
    }
}
