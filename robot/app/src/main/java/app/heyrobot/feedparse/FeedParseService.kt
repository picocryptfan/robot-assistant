// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.feedparse

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import app.heyrobot.core.news.FeedParseIpc
import app.heyrobot.core.news.FeedWire
import app.heyrobot.core.news.FeedXml
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// :FEEDPARSE PROCESS ONLY (android:process=":feedparse", android:isolatedProcess="true", not exported, no permission, no
// intent filter). Today H7b (home-design §6.1, "Limits and cost"): the isolated reader of news feeds, as :linkparse is of
// what other Robots send. Android runs it under an isolated uid of its own: no permission, no network, none of Robot's
// files or stores (settings, the news it keeps, memory, people) and no file it could open by name. A bug in the XML or
// page reader, reached through a hostile feed, runs here, not as Robot, and ends only this process; whatever it answers,
// the caller keeps only what core FeedWire.check reads and cleans again.
//
// The fetch (NewsStore, in main or :voice) binds a fresh instance (FeedParseLink: bindIsolatedService under a name of its
// own) for one round of feeds and lets it go after, or after any failure. Each call is checked in this order: this process
// is isolated (before anything of a call is read), the caller is Robot's own uid, then the interface token and a strict
// frame. HELLO has this process prove its isolated uid (the kernel's, through the caller's callback) before it is sent
// anything. READ: the base address, the time and the bytes' region (read-only, exactly their size); the work runs on one
// worker under FeedParseIpc.SERVICE_MS, past which the process ends itself. Replies are numbers and bytes, never an
// exception. Nothing here logs.

class FeedParseService : Service() {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-feedparse").apply { isDaemon = true } } // l10n: not text (a thread's name)
    /** Robot's own uid (main's and :voice's), as Android gave this process its application: the one caller answered. */
    @Volatile private var appUid = -1

    override fun onCreate() {
        super.onCreate()
        appUid = applicationInfo.uid
    }

    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(FeedParseIpc.DESCRIPTOR)
                return true
            }
            if (code !in FeedParseIpc.HELLO..FeedParseIpc.EXIT) return false
            // First, before anything of a call is read: never read a feed as Robot.
            if (!Process.isIsolated()) {
                reply?.writeInt(FeedParseIpc.REPLY_NOT_ISOLATED)
                return true
            }
            // Robot's own uid alone (main, :voice): a process of another uid, an isolated one included, gets nothing.
            val caller = Binder.getCallingUid()
            if (appUid < 0 || caller != appUid) {
                reply?.writeInt(FeedParseIpc.REPLY_REFUSED)
                return true
            }
            try {
                data.enforceInterface(FeedParseIpc.DESCRIPTOR)
                when (code) {
                    FeedParseIpc.EXIT -> Process.killProcess(Process.myPid())
                    FeedParseIpc.HELLO -> synchronized(lock) { hello(data, reply ?: return true) }
                    else -> synchronized(lock) { read(data, reply ?: return true) }
                }
            } catch (_: Exception) {
                reply?.apply { setDataSize(0); setDataPosition(0); writeInt(FeedParseIpc.REPLY_REFUSED) }
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    /** The fetch let go (it ended, its process ended, or it gave up on this one): nothing left to do here. */
    override fun onUnbind(intent: Intent?): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    private fun hello(data: Parcel, reply: Parcel) {
        val cb = data.readStrongBinder() ?: throw IllegalArgumentException()
        if (data.dataAvail() != 0) throw IllegalArgumentException()
        val ok = try {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(FeedParseIpc.CALLBACK)
                d.writeInt(Process.myPid())
                cb.transact(FeedParseIpc.CB_HELLO, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        } catch (_: Exception) { false }
        reply.writeInt(FeedParseIpc.REPLY_OK)
        reply.writeInt(if (ok) 1 else 0)
    }

    private fun read(data: Parcel, reply: Parcel) {
        need(data, 4)
        val base = data.readString() ?: throw IllegalArgumentException()
        if (base.length > FeedParseIpc.MAX_BASE) throw IllegalArgumentException()
        need(data, 12)
        val now = data.readLong()
        if (data.readInt() != 1) throw IllegalArgumentException()
        val region = SharedMemory.CREATOR.createFromParcel(data)
        try {
            if (data.dataAvail() != 0 || region.size !in 1..FeedXml.MAX_BYTES) throw IllegalArgumentException()
            val buf = region.mapReadOnly()
            val bytes = try {
                ByteArray(region.size).also { buf.get(it) }
            } finally {
                SharedMemory.unmap(buf)
            }
            val job = worker.submit<ByteArray> { FeedWire.answer(bytes, base, now) }
            val answer = try {
                job.get(FeedParseIpc.SERVICE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A feed that doesn't finish: the process ends, and whatever it held with it.
                Process.killProcess(Process.myPid())
                throw IllegalStateException()
            }
            if (answer.size > FeedParseIpc.MAX_REPLY) throw IllegalStateException()
            reply.writeInt(FeedParseIpc.REPLY_OK)
            reply.writeByteArray(answer)
        } finally {
            try { region.close() } catch (_: Exception) {}
        }
    }

    private fun need(p: Parcel, n: Int) {
        if (p.dataAvail() < n) throw IllegalArgumentException()
    }
}
