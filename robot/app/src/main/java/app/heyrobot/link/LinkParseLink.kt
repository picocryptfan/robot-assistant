// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import app.heyrobot.core.IsolatedUid
import app.heyrobot.core.RLog
import app.heyrobot.core.link.LinkParseIpc
import app.heyrobot.core.link.LinkParseWire
import app.heyrobot.core.link.LinkProblem
import app.heyrobot.core.link.requests.RequestRead
import app.heyrobot.r2r.RequestDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

// THE :link PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §5.1, §5.4; job L4): :link's way to :linkparse, the
// isolated reader (LinkParseService), and the node's RequestDecoder. LinkNode hands it each plaintext it decrypted; it never
// reads the request's text itself.
//
// One instance of :linkparse at a time, bound by name (bindIsolatedService under a name of its own: a process and an
// isolated uid of its own), started at the first request and kept while :link is awake; ended when :link sleeps or starts
// over, and after any failure, so the next request gets a fresh one. Before it is sent anything it proves an isolated uid:
// HELLO has it call this link's callback, whose calling uid is the kernel's (core IsolatedUid), as FileParseLink does with
// :files. Each plaintext goes in a fresh read-only SharedMemory region of exactly its size; the answer is one frame, read as
// numbers and bytes (never readException or anything :linkparse could have this process unparcel) and checked by core
// LinkParseWire.check: every field again, and the request rebuilt must be the plaintext exactly. A process that doesn't
// answer within LinkParseIpc.CALL_MS (the node's one thread waits for it), dies, or answers outside the rules is ended, and
// the request it was asked about is dropped as PARSER (never answered: the other Robot sends it again until its lifetime
// ends). Logs carry codes only.

class LinkParseLink internal constructor(
    private val ctx: Context,
    private val callMs: Long,
    private val bindMs: Long,
    /** After a :linkparse that couldn't start, how long before the next try (requests in between aren't read). */
    private val retryMs: Long,
    /** How an instance is bound: bindIsolatedService on the phone; the JVM tests' stand-in otherwise. */
    private val binding: Binding,
) : RequestDecoder {
    constructor(ctx: Context) : this(ctx, LinkParseIpc.CALL_MS, LinkParseIpc.BIND_MS, LinkParseIpc.RETRY_MS,
        Binding { i, f, n, e, c -> ctx.bindIsolatedService(i, f, n, e, c) })

    internal fun interface Binding {
        fun bind(intent: Intent, flags: Int, instanceName: String, executor: Executor, conn: ServiceConnection): Boolean
    }

    private val current = AtomicReference<Instance?>(null)
    /** When a start last failed (System.nanoTime), or 0: the node's one thread doesn't wait for a bind again before [retryMs]. */
    @Volatile private var failedAt = 0L

    /**
     * What the request in [plain] is, read under [cap] by :linkparse and checked here; null when no :linkparse could be
     * started or [end] cut the read (nothing read, nothing answered). Called on the node's thread, never the main one.
     */
    override fun decode(plain: ByteArray, cap: Int): RequestRead? {
        check(Looper.myLooper() != Looper.getMainLooper())
        // Its length alone, the decoder's first rule: nothing to ask :linkparse about.
        if (!LinkParseWire.sizeOk(plain.size, cap)) return RequestRead.Bad(if (plain.size > cap) LinkProblem.TOO_LONG else LinkProblem.BAD_PADDING)
        val i = instance() ?: return null
        val read = try { i.ask(plain, cap) } catch (_: java.util.concurrent.RejectedExecutionException) { null }
        if (read == null) {
            current.compareAndSet(i, null)
            // :link went to sleep under this read ([end]): nothing was learned about the request, so nothing was read.
            if (i.cut) return null
            // It failed on this request: ended; the request is dropped, a fresh process reads the next one.
            return RequestRead.Bad(LinkProblem.PARSER)
        }
        return read
    }

    /** :link sleeps, or starts over: the reader ends now (a fresh one comes with the next request). Never blocks. */
    fun end(why: String) {
        failedAt = 0L
        current.getAndSet(null)?.let { it.cut = true; it.end(why) }
    }

    private fun instance(): Instance? {
        current.get()?.takeIf { it.isAlive }?.let { return it }
        val last = failedAt
        if (last != 0L && System.nanoTime() - last < TimeUnit.MILLISECONDS.toNanos(retryMs)) return null
        val n = Instance()
        if (!n.start()) {
            failedAt = System.nanoTime()
            return null
        }
        failedAt = 0L
        val old = current.getAndSet(n)
        if (old != null && old !== n) old.end("replaced") // l10n: not text (a code)
        return n
    }

    /** One bound :linkparse process: bound, proven, used, ended. Never reused once ended. */
    private inner class Instance {
        private val connected = CountDownLatch(1)
        private val gone = CountDownLatch(1)
        @Volatile private var remote: IBinder? = null
        @Volatile private var bound = false
        @Volatile private var proven = false
        @Volatile private var uid = -1
        @Volatile private var ending = false
        /** Ended from outside ([LinkParseLink.end]), not for anything it did. */
        @Volatile var cut = false
        private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-linkparse-link").apply { isDaemon = true } } // l10n: not text (a thread's name)

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

            override fun onServiceDisconnected(name: ComponentName?) = lost()
            override fun onBindingDied(name: ComponentName?) = lost()
            override fun onNullBinding(name: ComponentName?) = lost()
        }

        /** The callback the process calls during HELLO, from its own uid: accepted once, and only from an isolated uid. */
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == INTERFACE_TRANSACTION) {
                    reply?.writeString(LinkParseIpc.CALLBACK)
                    return true
                }
                if (code != LinkParseIpc.CB_HELLO) return false
                try {
                    data.enforceInterface(LinkParseIpc.CALLBACK)
                } catch (_: Exception) {
                    return false
                }
                val from = Binder.getCallingUid()
                val ok = IsolatedUid.of(from) && uid == -1
                if (ok) uid = from
                reply?.writeInt(if (ok) 1 else 0)
                return true
            }
        }

        val isAlive: Boolean get() = gone.count > 0 && !ending && proven

        fun start(): Boolean {
            bound = try {
                // No capability flags: :linkparse gets :link's priority and nothing more.
                binding.bind(Intent().setClassName(ctx.packageName, LinkParseIpc.SERVICE_CLASS), Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                    "linkparse_${Process.myPid()}_${SEQ.incrementAndGet()}", EVENTS, conn) // l10n: not text (an instance name)
            } catch (t: Throwable) {
                RLog.w("link_parse_bind", t)
                false
            }
            if (!bound) {
                lost()
                return false
            }
            if (!connected.await(bindMs, TimeUnit.MILLISECONDS) || remote == null) {
                end("bind") // l10n: not text (a code)
                return false
            }
            val accepted = try { hello() } catch (_: Exception) { null }
            proven = accepted == true && IsolatedUid.of(uid)
            if (!proven) end(if (accepted != null) "not_isolated" else "hello") // l10n: not text (a code)
            return proven
        }

        /** HELLO: an answer of REPLY_OK and 1, and the process's call back from an isolated uid in between; anything else is no proof. */
        private fun hello(): Boolean {
            val b = remote ?: return false
            val job = calls.submit<Boolean> {
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                try {
                    d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR)
                    d.writeStrongBinder(callback)
                    if (!b.transact(LinkParseIpc.HELLO, d, r, 0)) return@submit false
                    r.dataAvail() == 8 && r.readInt() == LinkParseIpc.REPLY_OK && r.readInt() == 1
                } finally {
                    d.recycle()
                    r.recycle()
                }
            }
            return job.get(bindMs, TimeUnit.MILLISECONDS)
        }

        /** The checked answer about [plain], or null when this process failed on it (then it is ended). */
        fun ask(plain: ByteArray, cap: Int): RequestRead? {
            if (!isAlive) return null
            val b = remote ?: return null
            val region = try {
                SharedMemory.create("robot-linkparse-in", plain.size) // l10n: not text (a region's name)
            } catch (e: Exception) {
                RLog.w("link_parse_region", e)
                end("region") // l10n: not text (a code)
                return null
            }
            try {
                val w = region.mapReadWrite()
                try {
                    w.put(plain)
                } finally {
                    SharedMemory.unmap(w)
                }
                region.setProtect(OsConstants.PROT_READ)
                val frame = LinkParseWire.encodeRequest(cap, plain.size)
                val job = calls.submit<ByteArray?> { transact(b, frame, region) }
                val answer = try {
                    job.get(callMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    end("timeout") // l10n: not text (a code)
                    return null
                } catch (_: ExecutionException) {
                    end("crash") // l10n: not text (a code)
                    return null
                }
                if (answer == null) {
                    end("malformed") // l10n: not text (a code)
                    return null
                }
                val read = LinkParseWire.check(answer, plain, cap)
                if (read == null) end("malformed") // l10n: not text (a code)
                return read
            } finally {
                try { region.close() } catch (_: Exception) {}
            }
        }

        /** DECODE on this instance's own thread: the reply frame, or null for any reply that isn't REPLY_OK and one frame within its cap. */
        private fun transact(b: IBinder, frame: ByteArray, region: SharedMemory): ByteArray? {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR)
                d.writeByteArray(frame)
                d.writeInt(1)
                region.writeToParcel(d, 0)
                if (!b.transact(LinkParseIpc.DECODE, d, r, 0)) throw RemoteException()
                // Numbers first, each only while the reply holds it; the frame's length checked before anything is made for it.
                if (r.dataAvail() < 8 || r.readInt() != LinkParseIpc.REPLY_OK) return null
                val at = r.dataPosition()
                val n = r.readInt()
                if (n < 1 || n > LinkParseIpc.MAX_REPLY || r.dataAvail() < n) return null
                r.setDataPosition(at)
                val out = r.createByteArray() ?: return null
                if (out.size != n || r.dataAvail() != 0) return null
                return out
            } finally {
                d.recycle()
                r.recycle()
            }
        }

        /** Ends the process, whatever it is doing: EXIT (one way), then unbinding. */
        fun end(why: String) {
            if (ending) return
            ending = true
            RLog.i("link_parse_end", "why" to RLog.Code(why))
            val connectedOnce = remote != null
            remote?.let { b ->
                val d = Parcel.obtain()
                try {
                    d.writeInterfaceToken(LinkParseIpc.DESCRIPTOR)
                    b.transact(LinkParseIpc.EXIT, d, null, IBinder.FLAG_ONEWAY)
                } catch (_: Exception) {
                } finally {
                    d.recycle()
                }
            }
            unbind()
            calls.shutdownNow()
            if (!connectedOnce) lost()
        }

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
        }
    }

    private companion object {
        /** Where every instance's connection callbacks run (never the node's thread, which may be waiting for them). */
        val EVENTS = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-linkparse-events").apply { isDaemon = true } } // l10n: not text (a thread's name)
        /** Each instance's own service: this process's pid and a count. */
        val SEQ = AtomicInteger()
    }
}
