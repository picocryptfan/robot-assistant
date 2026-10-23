// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import app.heyrobot.core.IsolatedUid
import app.heyrobot.core.RLog
import app.heyrobot.core.StreamBudget
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// :BRAIN PROCESS ONLY. One binding to llama.cpp's isolated process (LlamaService, 0.4.5), for one GGUF model: bound when
// LlamaEngine loads a file, ended when that model goes (end(): the process ends, and the model's memory with it). The
// calls are LlamaIpc's, made from LlamaEngine's one llama worker (cancel() and end() from its work thread, one way); a
// call into a process that is gone throws [Gone]. [onGone] is told once when the process is gone, whoever ended it.
//
// 1.1: what the isolated process answers is untrusted (a hostile file may have taken it over), so its replies are read as
// numbers and bytes only (LlamaIpc.Reply): a header that isn't a plain REPLY_OK, a reply cut short or with more in it, an
// answer outside its codes, ends the process as a crash does ([Gone]); nothing it sends is unparcelled or thrown here.

internal class LlamaLink(private val ctx: Context, private val onGone: (LlamaLink) -> Unit) {
    /** The isolated process went away (it crashed, Android or [end] ended it): what was asked of it is lost. */
    class Gone : Exception()

    private val connected = CountDownLatch(1)
    private val gone = CountDownLatch(1)
    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false
    /** [end] asked it to go (an unload, a watchdog): not news when it does. */
    @Volatile var ending = false
        private set
    /** The isolated uid and pid its HELLO came from (the kernel's, as Binder reports them); -1 before. */
    @Volatile var uid = -1
        private set
    @Volatile var pid = -1
        private set
    /** Where the reply's pieces go while a GENERATE runs (false: stop). */
    @Volatile private var sink: ((ByteArray) -> Boolean)? = null
    /** The running GENERATE's byte budget (core StreamBudget): past it no piece reaches [sink] and the request ends. */
    @Volatile private var budget: StreamBudget? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return lost()
            try {
                binder.linkToDeath({ lost() }, 0)
            } catch (_: RemoteException) {
                return lost()
            }
            remote = binder
            connected.countDown()
        }

        // The process died (a crash, Android, end()): gone for good; unbinding keeps Android from starting a new one.
        override fun onServiceDisconnected(name: ComponentName?) = lost()
        override fun onBindingDied(name: ComponentName?) = lost()
        override fun onNullBinding(name: ComponentName?) = lost()
    }

    /** :brain's side of the callback: HELLO from an isolated uid, then the reply's pieces from that same uid only. */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LlamaIpc.CALLBACK)
                return true
            }
            data.enforceInterface(LlamaIpc.CALLBACK)
            val from = Binder.getCallingUid()
            when (code) {
                LlamaIpc.CB_HELLO -> {
                    val ok = IsolatedUid.of(from) && uid == -1
                    if (ok) {
                        uid = from
                        pid = Binder.getCallingPid()
                    }
                    reply?.writeInt(if (ok) 1 else 0)
                }
                LlamaIpc.CB_PIECE -> {
                    val bytes = data.createByteArray()
                    val s = sink
                    val b = budget
                    val more = from == uid && bytes != null && b != null && b.take(bytes.size) && s != null && s(bytes)
                    reply?.writeInt(if (more) 1 else 0)
                }
                // Nothing else (not the framework's dump or shell commands, which would unparcel descriptors and binders).
                else -> return false
            }
            return true
        }
    }

    /** Starts the isolated process (Android gives it a fresh isolated uid) and connects; false when it didn't in [timeoutMs]. */
    fun bind(timeoutMs: Long): Boolean {
        bound = try {
            // As :voice binds :brain, minus the capabilities: llama.cpp gets :brain's priority (a background process is
            // throttled hard), nothing it could use the microphone or location with.
            ctx.bindService(Intent().setClassName(ctx.packageName, LlamaIpc.SERVICE_CLASS),
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, EVENTS, conn)
        } catch (t: Throwable) {
            RLog.w("brain_llama_bind", t)
            false
        }
        if (!bound) {
            lost()
            return false
        }
        return connected.await(timeoutMs, TimeUnit.MILLISECONDS) && remote != null && gone.count > 0
    }

    /** HELLO's answer: [isolated], the process proved an isolated uid (the kernel's); [problem], why the library didn't load. */
    class Hello(val isolated: Boolean, val problem: String?)

    /** Loads the library there, and has the process prove an isolated uid (its CB_HELLO) before it gets any file. */
    fun hello(): Hello = call(LlamaIpc.HELLO, { it.writeStrongBinder(callback) }) { r ->
        val accepted = when (r.int()) {
            1 -> true
            0 -> false
            else -> throw LlamaIpc.BadReply()
        }
        // One of its two codes or none: never words of its own, which the app would show.
        val problem = r.string()
        if (problem != null && problem != LlamaIpc.NOT_IN_BUILD && problem != LlamaIpc.NOT_ISOLATED) throw LlamaIpc.BadReply()
        Hello(accepted && IsolatedUid.of(uid), problem)
    }

    fun load(file: ParcelFileDescriptor, nCtx: Int, threads: Int, batchThreads: Int, sampling: FloatArray): LlamaIpc.LoadResult =
        call(LlamaIpc.LOAD, { d ->
            d.writeInt(1)
            file.writeToParcel(d, 0)
            d.writeInt(nCtx)
            d.writeInt(threads)
            d.writeInt(batchThreads)
            d.writeFloatArray(sampling)
        }) { r ->
            val h = r.long()
            val why = r.int()
            val train = Integer.toUnsignedLong(r.int())
            val control = r.arrays(LlamaIpc.MAX_SPECIALS, LlamaIpc.MAX_SPECIAL_BYTES)
            val user = r.arrays(LlamaIpc.MAX_SPECIALS, LlamaIpc.MAX_SPECIAL_BYTES)
            LlamaIpc.LoadResult(h, why, train, control, user)
        }

    /** The token count or a PREP_* code; [flags] (out): bit 0 the prompt opens a <think> block. Too large to send: PREP_TOO_LONG. */
    fun prepare(handle: Long, roles: List<ByteArray>, texts: List<ByteArray>, think: Boolean, flags: IntArray): Int = try {
        call(LlamaIpc.PREPARE, { d ->
            d.writeLong(handle)
            LlamaIpc.writeArrays(d, roles)
            LlamaIpc.writeArrays(d, texts)
            d.writeInt(if (think) 1 else 0)
        }) { r ->
            val n = r.int()
            flags[0] = r.int()
            n
        }
    } catch (_: android.os.TransactionTooLargeException) {
        if (!isAlive) throw Gone()
        LlamaIpc.PREP_TOO_LONG
    }

    /** A GEN_* code; the pieces go to [piece] on this thread (false stops); [stats] (out) as LlamaIpc's STATS_* slots. */
    fun generate(handle: Long, run: Long, maxTokens: Int, piece: (ByteArray) -> Boolean, stats: LongArray): Int {
        sink = piece
        val b = StreamBudget(LlamaIpc.MAX_PIECE, LlamaIpc.MAX_REPLY_BYTES)
        budget = b
        try {
            return call(LlamaIpc.GENERATE, { d ->
                d.writeLong(handle)
                d.writeLong(run)
                d.writeInt(maxTokens)
            }) { r ->
                val code = r.int()
                val s = r.longs(LlamaIpc.STATS_COUNT)
                System.arraycopy(s, 0, stats, 0, minOf(s.size, stats.size))
                code
            }
        } finally {
            sink = null
            budget = null
            if (b.over) RLog.i("brain_llama_overflow")
        }
    }

    /** One way, from any thread: stops run [run] there, also inside a prompt being read. */
    fun cancel(handle: Long, run: Long) {
        oneWay(LlamaIpc.CANCEL) { d ->
            d.writeLong(handle)
            d.writeLong(run)
        }
    }

    /** Ends the process, whatever it is doing (one way, then unbinding: Android ends an isolated process nothing binds). */
    fun end(why: String) {
        if (ending) return
        ending = true
        RLog.i("brain_llama_end", "why" to RLog.Code(why), "gone" to !isAlive)
        val connectedOnce = remote != null
        oneWay(LlamaIpc.EXIT) {}
        unbind()
        // Never connected: it never got a file, and unbinding ends a process that may still be starting.
        if (!connectedOnce) lost()
    }

    val isAlive: Boolean get() = gone.count > 0

    /** True once the process is gone (its memory freed), within [ms]. */
    fun awaitGone(ms: Long): Boolean = gone.await(ms, TimeUnit.MILLISECONDS)

    private fun unbind() {
        if (!bound) return
        bound = false
        try { ctx.unbindService(conn) } catch (_: Exception) {}
    }

    private fun lost() {
        if (gone.count == 0L) return
        gone.countDown()
        connected.countDown()
        remote = null
        unbind()
        onGone(this)
    }

    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (LlamaIpc.Reply) -> T): T {
        // Asked to go: nothing more is sent to it.
        if (ending) throw Gone()
        val b = remote ?: throw Gone()
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(LlamaIpc.DESCRIPTOR)
            write(d)
            val answered = b.transact(code, d, r, 0)
            return try {
                // A live process that refuses one of its own calls breaks the link as well.
                if (!answered) throw LlamaIpc.BadReply()
                val reply = LlamaIpc.Reply(r)
                read(reply).also { reply.end() }
            } catch (_: LlamaIpc.BadReply) {
                broken(code)
            }
        } catch (e: android.os.TransactionTooLargeException) {
            throw e
        } catch (e: RemoteException) {
            throw Gone()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    /**
     * A reply that broke LlamaIpc's layout: the process may be a hostile file's now. It ends, as after a crash, and the call
     * throws [Gone]; [onGone] follows when Android says the process is gone (its memory with it).
     */
    private fun broken(code: Int): Nothing {
        RLog.i("brain_llama_bad_reply", "call" to code - IBinder.FIRST_CALL_TRANSACTION)
        end("bad_reply") // l10n: not text (a code for the log)
        throw Gone()
    }

    private fun oneWay(code: Int, write: (Parcel) -> Unit) {
        val b = remote ?: return
        val d = Parcel.obtain()
        try {
            d.writeInterfaceToken(LlamaIpc.DESCRIPTOR)
            write(d)
            b.transact(code, d, null, IBinder.FLAG_ONEWAY)
        } catch (_: Exception) {
        } finally {
            d.recycle()
        }
    }

    private companion object {
        /** Where every link's connection callbacks run (never BrainService's work thread, which may be waiting for them). */
        val EVENTS = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-llama-link").apply { isDaemon = true } }
    }
}
