// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.mt

import android.app.Service
import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.os.RemoteException
import android.os.SystemClock
import app.heyrobot.core.LangPair
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.MtMemory
import app.heyrobot.core.MtMsg
import app.heyrobot.core.MtPolicy
import app.heyrobot.core.MtProtocol
import app.heyrobot.core.MtQueue
import app.heyrobot.core.MtWire
import app.heyrobot.core.RLog
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// :MT PROCESS ONLY (android:process=":mt", docs/conversation-mode.md 4.5). The fast translation engine runs here and
// nowhere else: slimt calls abort() on some model errors, which then ends this process alone; a client sees its binding
// drop (MtError.DISCONNECTED) and Android starts the service again for the next request. Nothing here goes online, reads
// anything but the verified model files and its own /proc/self/status, or logs a word: codes and numbers only.
//
// Messages arrive on the inbox thread; one worker thread does everything with the engine, in order: loads and unloads
// first, then one line at a time from MtQueue (an interactive line waits at most one line of a backlog). The engine
// loads when a request first needs it and unloads after five idle minutes, when Android is short of memory and this
// process is in the background, when asked, and when the last client unbinds.

class MtService : Service() {
    private lateinit var engine: BergamotEngine
    private lateinit var inbox: HandlerThread
    private lateinit var messenger: Messenger
    private lateinit var worker: Thread

    private val lock = ReentrantLock()
    private val wake = lock.newCondition()
    // Guarded by lock.
    private val queue = MtQueue()
    private val controls = ArrayDeque<Control>()
    private val clients = HashMap<Int, Client>()
    private val failures = HashMap<Int, MtError>()
    private var nextId = 1
    private var lastActive = SystemClock.elapsedRealtime()
    private var stopping = false

    /** The directions in memory, for STATUS (written by the worker). */
    @Volatile private var loadedNow: List<String> = emptyList()

    private data class Client(val reply: Messenger, val reqId: Int)

    private sealed class Control {
        data class Load(val pair: LangPair, val client: Client) : Control()
        /** [client] null: idle, short of memory, or no one bound (then only when nothing waits). */
        data class Unload(val client: Client?) : Control()
    }

    override fun onCreate() {
        super.onCreate()
        engine = BergamotEngine(filesDir)
        inbox = HandlerThread("robot-mt-inbox").apply { start() }
        messenger = Messenger(Handler(inbox.looper) { m -> handle(m); true })
        worker = Thread(::work, "robot-mt").apply { start() }
        RLog.i("mt_service", "op" to RLog.Code("create"))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    /** Every client left: free the engine's memory (about 300 MB) now; onDestroy follows. */
    override fun onUnbind(intent: Intent?): Boolean {
        control(Control.Unload(null))
        return false
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // This process is in the background and Android is short of memory: let the engine go while nothing waits.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) control(Control.Unload(null))
    }

    override fun onDestroy() {
        lock.withLock {
            stopping = true
            wake.signalAll()
        }
        inbox.quitSafely()
        RLog.i("mt_service", "op" to RLog.Code("destroy"))
        super.onDestroy()
    }

    private fun control(c: Control) = lock.withLock {
        controls.addLast(c)
        wake.signalAll()
    }

    private fun send(c: Client, what: Int, payload: MtMsg) {
        try { c.reply.send(MtWire.message(what, c.reqId, payload)) } catch (_: RemoteException) {}
    }

    private fun memory(): MtMemory = MtMemory.parse(try { File("/proc/self/status").readText() } catch (_: Exception) { null })

    // ---------------------------------------------------------------- inbox thread

    private fun handle(msg: Message) {
        val reply = msg.replyTo ?: return
        // Not exported, so only Robot's own processes can bind; checked again here (Messenger sets sendingUid).
        if (msg.sendingUid != -1 && msg.sendingUid != Process.myUid()) return
        val c = Client(reply, msg.arg1)
        val payload = MtWire.decode(msg)
        if (payload == null || msg.what !in MtProtocol.REQUESTS) return send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.BAD_REQUEST))
        when (payload) {
            is MtMsg.Load -> {
                val pair = LangPair.entries.firstOrNull { it.id == payload.pair && MtBundle.of(it) != null }
                    ?: return send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.BAD_REQUEST))
                control(Control.Load(pair, c))
            }
            is MtMsg.Translate -> {
                payload.problem()?.let { return send(c, MtProtocol.EV_ERROR, MtMsg.Error(it)) }
                val busy = lock.withLock {
                    val now = SystemClock.elapsedRealtime()
                    val id = nextId++
                    val job = queue.add(id, payload.direction, payload.lines, payload.priority, now + payload.deadlineMs)
                    if (job != null) {
                        clients[id] = c
                        lastActive = now
                        wake.signalAll()
                    }
                    job == null
                }
                if (busy) send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.BUSY))
            }
            is MtMsg.Cancel -> lock.withLock {
                clients.entries.firstOrNull { it.value.reply == reply && it.value.reqId == payload.target }?.let {
                    queue.cancel(it.key)
                    wake.signalAll()
                }
            }
            is MtMsg.Empty -> when (msg.what) {
                MtProtocol.REQ_UNLOAD -> control(Control.Unload(c))
                else -> send(c, MtProtocol.EV_STATUS, MtMsg.Status(Slimt.available, loadedNow, lock.withLock { queue.waiting }, memory()))
            }
            else -> send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.BAD_REQUEST))
        }
    }

    // ---------------------------------------------------------------- worker thread

    /** The next thing for the worker (a [Control] or an [MtQueue.Task]), or null to stop. */
    private fun next(): Any? {
        lock.lock()
        try {
            while (!stopping) {
                controls.removeFirstOrNull()?.let { return it }
                val now = SystemClock.elapsedRealtime()
                queue.take(now)?.let { return it }
                val idle = now - lastActive
                if (loadedNow.isEmpty()) wake.await()
                else if (idle >= MtPolicy.IDLE_UNLOAD_MS) return Control.Unload(null)
                else wake.await(MtPolicy.IDLE_UNLOAD_MS - idle, TimeUnit.MILLISECONDS)
            }
            return null
        } finally {
            lock.unlock()
        }
    }

    private fun work() {
        try {
            while (true) {
                when (val n = next() ?: break) {
                    is Control.Load -> load(n)
                    is Control.Unload -> unload(n.client)
                    is MtQueue.Task.Line -> line(n.job, n.index)
                    is MtQueue.Task.Finished -> finish(n.job)
                }
            }
        } finally {
            val left = lock.withLock { queue.drain().mapNotNull { clients.remove(it.id) } }
            for (c in left) send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.CANCELLED))
            engine.unload()
            loadedNow = emptyList()
        }
    }

    private fun touch() = lock.withLock { lastActive = SystemClock.elapsedRealtime() }

    /** Loads [b]'s directions not in memory yet: their load times, and the error when it failed. */
    private fun ensure(b: MtBundle): Pair<Map<String, Long>, MtError?> {
        val t0 = SystemClock.elapsedRealtime()
        return try {
            val ms = engine.load(b)
            if (ms.isNotEmpty()) RLog.i("mt_load", "ms" to (SystemClock.elapsedRealtime() - t0), "ok" to true, "dirs" to ms.size)
            ms to null
        } catch (e: MtException) {
            RLog.i("mt_load", "ms" to (SystemClock.elapsedRealtime() - t0), "ok" to false, "error" to e.error)
            emptyMap<String, Long>() to e.error
        } catch (t: Throwable) {
            RLog.w("mt_load", t)
            emptyMap<String, Long>() to MtError.LOAD_FAILED
        } finally {
            loadedNow = engine.loaded
            touch()
        }
    }

    private fun load(c: Control.Load) {
        val b = MtBundle.of(c.pair) ?: return send(c.client, MtProtocol.EV_ERROR, MtMsg.Error(MtError.BAD_REQUEST))
        val (ms, err) = ensure(b)
        if (err != null) send(c.client, MtProtocol.EV_ERROR, MtMsg.Error(err))
        else send(c.client, MtProtocol.EV_LOADED, MtMsg.Loaded(c.pair.id, ms, memory()))
    }

    private fun unload(client: Client?) {
        val cancelled = lock.withLock {
            // Asked for: whatever waits is cancelled. Otherwise (idle, memory, unbound) only when nothing waits.
            if (client == null && !queue.isEmpty) return
            queue.drain().mapNotNull { clients.remove(it.id) }
        }
        for (c in cancelled) send(c, MtProtocol.EV_ERROR, MtMsg.Error(MtError.CANCELLED))
        if (engine.loaded.isNotEmpty()) RLog.i("mt_unload", "asked" to (client != null))
        engine.unload()
        loadedNow = emptyList()
        client?.let { send(it, MtProtocol.EV_UNLOADED, MtMsg.Empty) }
    }

    private fun line(job: MtQueue.Job, index: Int) {
        if (!engine.has(job.direction)) {
            val b = MtBundle.forDirection(job.direction)?.first
            val err = if (b == null) MtError.BAD_REQUEST else ensure(b).second
            if (err != null) {
                lock.withLock {
                    failures[job.id] = err
                    queue.cancel(job.id)
                }
                return
            }
        }
        val t0 = SystemClock.elapsedRealtime()
        val out = try { engine.translate(job.direction, job.lines[index]) } catch (t: Throwable) {
            RLog.w("mt_translate", t)
            null
        }
        job.ms[index] = SystemClock.elapsedRealtime() - t0
        job.out[index] = out
        if (out == null) job.failed++
        touch()
    }

    private fun finish(job: MtQueue.Job) {
        val (c, err) = lock.withLock { clients.remove(job.id) to failures.remove(job.id) }
        c ?: return
        if (err != null) send(c, MtProtocol.EV_ERROR, MtMsg.Error(err))
        else send(c, MtProtocol.EV_TRANSLATED, MtMsg.Translated(job.out.toList(), job.ms.toList(), job.timedOut, job.failed, memory()))
    }
}
