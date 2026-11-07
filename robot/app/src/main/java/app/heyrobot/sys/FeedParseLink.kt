// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

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
import app.heyrobot.core.news.FeedAnswer
import app.heyrobot.core.news.FeedParseIpc
import app.heyrobot.core.news.FeedWire
import app.heyrobot.core.news.FeedXml
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

// MAIN AND :VOICE. Today H7b (home-design §6.1, "Limits and cost"): how a round of news fetching reaches :feedparse, the
// isolated reader of feeds (FeedParseService), as :link's LinkParseLink reaches :linkparse. One instance for a round
// (NewsStore.fetch or Find), bound by name (bindIsolatedService under a name of its own: a process and an isolated uid of
// its own) at the first feed and ended when the round closes, and after any failure, so the next feed gets a fresh one:
// a crash or a hostile feed there takes down only it, and that one feed counts as failed. Before it is sent anything it
// proves an isolated uid (HELLO, core IsolatedUid). Each feed's bytes go in a fresh read-only SharedMemory region of
// exactly their size; the answer is one frame, kept only as core FeedWire.check reads and cleans it. Logs carry codes only.

class FeedParseLink internal constructor(
    private val ctx: Context,
    private val callMs: Long,
    private val bindMs: Long,
    /** How an instance is bound: bindIsolatedService on the phone; the JVM tests' stand-in otherwise. */
    private val binding: Binding,
) : NewsStore.Parse, Closeable {
    constructor(ctx: Context) : this(ctx, FeedParseIpc.CALL_MS, FeedParseIpc.BIND_MS,
        Binding { i, f, n, e, c -> ctx.bindIsolatedService(i, f, n, e, c) })

    internal fun interface Binding {
        fun bind(intent: Intent, flags: Int, instanceName: String, executor: Executor, conn: ServiceConnection): Boolean
    }

    @Volatile private var current: Instance? = null

    /** [bytes] from [base] as :feedparse reads them, checked here; null when it couldn't, or failed on them. Never on the main thread. */
    override fun read(bytes: ByteArray, base: String, now: Long): FeedAnswer? {
        check(Looper.myLooper() != Looper.getMainLooper())
        if (bytes.isEmpty() || bytes.size > FeedXml.MAX_BYTES || base.length > FeedParseIpc.MAX_BASE) return null
        val i = current?.takeIf { it.isAlive } ?: Instance().takeIf { it.start() }?.also { current = it } ?: return null
        val a = i.ask(bytes, base, now)
        if (a == null) { i.end("failed"); current = null } // l10n: not text (a code)
        return a
    }

    /** The round is over: the reader ends now. */
    override fun close() {
        current?.end("done") // l10n: not text (a code)
        current = null
    }

    /** One bound :feedparse process: bound, proven, used, ended. Never reused once ended. */
    private inner class Instance {
        private val connected = CountDownLatch(1)
        private val gone = CountDownLatch(1)
        @Volatile private var remote: IBinder? = null
        @Volatile private var bound = false
        @Volatile private var proven = false
        @Volatile private var uid = -1
        @Volatile private var ending = false
        private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-feedparse-link").apply { isDaemon = true } } // l10n: not text (a thread's name)

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
                    reply?.writeString(FeedParseIpc.CALLBACK)
                    return true
                }
                if (code != FeedParseIpc.CB_HELLO) return false
                try {
                    data.enforceInterface(FeedParseIpc.CALLBACK)
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
                // No capability flags: :feedparse gets the fetch's priority and nothing more.
                binding.bind(Intent().setClassName(ctx.packageName, FeedParseIpc.SERVICE_CLASS), Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
                    "feedparse_${Process.myPid()}_${SEQ.incrementAndGet()}", EVENTS, conn) // l10n: not text (an instance name)
            } catch (t: Throwable) {
                RLog.w("feed_parse_bind", t)
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
                    d.writeInterfaceToken(FeedParseIpc.DESCRIPTOR)
                    d.writeStrongBinder(callback)
                    if (!b.transact(FeedParseIpc.HELLO, d, r, 0)) return@submit false
                    r.dataAvail() == 8 && r.readInt() == FeedParseIpc.REPLY_OK && r.readInt() == 1
                } finally {
                    d.recycle()
                    r.recycle()
                }
            }
            return job.get(bindMs, TimeUnit.MILLISECONDS)
        }

        /** The checked answer about [bytes], or null when this process failed on them (then it is ended). */
        fun ask(bytes: ByteArray, base: String, now: Long): FeedAnswer? {
            if (!isAlive) return null
            val b = remote ?: return null
            val region = try {
                SharedMemory.create("robot-feedparse-in", bytes.size) // l10n: not text (a region's name)
            } catch (e: Exception) {
                RLog.w("feed_parse_region", e)
                end("region") // l10n: not text (a code)
                return null
            }
            try {
                val w = region.mapReadWrite()
                try {
                    w.put(bytes)
                } finally {
                    SharedMemory.unmap(w)
                }
                region.setProtect(OsConstants.PROT_READ)
                val job = calls.submit<ByteArray?> { transact(b, base, now, region) }
                val answer = try {
                    job.get(callMs, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    end("timeout") // l10n: not text (a code)
                    return null
                } catch (_: ExecutionException) {
                    end("crash") // l10n: not text (a code)
                    return null
                }
                val read = answer?.let { FeedWire.check(it, now) }
                if (read == null) end("malformed") // l10n: not text (a code)
                return read
            } finally {
                try { region.close() } catch (_: Exception) {}
            }
        }

        /** READ on this instance's own thread: the reply frame, or null for any reply that isn't REPLY_OK and one frame within its cap. */
        private fun transact(b: IBinder, base: String, now: Long, region: SharedMemory): ByteArray? {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(FeedParseIpc.DESCRIPTOR)
                d.writeString(base)
                d.writeLong(now)
                d.writeInt(1)
                region.writeToParcel(d, 0)
                if (!b.transact(FeedParseIpc.READ, d, r, 0)) throw RemoteException()
                if (r.dataAvail() < 8 || r.readInt() != FeedParseIpc.REPLY_OK) return null
                val at = r.dataPosition()
                val n = r.readInt()
                if (n < 1 || n > FeedParseIpc.MAX_REPLY || r.dataAvail() < n) return null
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
            RLog.i("feed_parse_end", "why" to RLog.Code(why))
            val connectedOnce = remote != null
            remote?.let { b ->
                val d = Parcel.obtain()
                try {
                    d.writeInterfaceToken(FeedParseIpc.DESCRIPTOR)
                    b.transact(FeedParseIpc.EXIT, d, null, IBinder.FLAG_ONEWAY)
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
        /** Where every instance's connection callbacks run (never the fetching thread, which may be waiting for them). */
        val EVENTS = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-feedparse-events").apply { isDaemon = true } } // l10n: not text (a thread's name)
        val SEQ = AtomicInteger()
    }
}
