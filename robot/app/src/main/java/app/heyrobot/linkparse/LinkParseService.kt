// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.linkparse

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import app.heyrobot.core.link.LinkParseIpc
import app.heyrobot.core.link.LinkParseWire
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// :LINKPARSE PROCESS ONLY (android:process=":linkparse", android:isolatedProcess="true", not exported, no permission, no
// intent filter). 1.5, Robot to Robot (robot-to-robot-plan.md §5.1, §5.3, §5.4; job L4): the isolated reader of what
// other Robots send, as :files is Robot Files' (FileParseService). Android runs it under an isolated uid of its own: no
// permission, no network, no Keystore, none of Robot's files (the link vault, its keys, its sessions, People, settings) and
// no file it could open by name. A bug in the decoder, reached through a hostile request, runs here, not as Robot; and
// whatever this process answers, :link keeps a request only when it rebuilds exactly the plaintext it sent
// (core LinkParseWire.check), so even a reader that was taken over can refuse requests but never change one.
//
// :link binds a fresh instance (LinkParseLink: bindIsolatedService under a name of its own) while it is awake, and lets it
// go when it sleeps or after any failure. Each call is checked in this order: this process is isolated (Process.isIsolated,
// before anything of a call is read), the caller is Robot's own uid (:link's; any other process, isolated ones included,
// is refused), then the interface token and a strict frame. HELLO has this process prove its isolated uid to the link (the
// kernel's, through the link's callback) before the link sends anything. DECODE: the request frame and the plaintext's
// region (read-only, exactly its size); the work runs on one worker under LinkParseIpc.SERVICE_MS, past which the process
// ends itself. Replies are numbers and bytes, never an exception (writeException and writeNoException are never used).
// Nothing here logs.

class LinkParseService : Service() {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-linkparse").apply { isDaemon = true } } // l10n: not text (a thread's name)
    /** Robot's own uid (:link's), as Android gave this process its application: the one caller answered. */
    @Volatile private var appUid = -1

    override fun onCreate() {
        super.onCreate()
        appUid = applicationInfo.uid
    }

    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LinkParseIpc.DESCRIPTOR)
                return true
            }
            if (code !in LinkParseIpc.HELLO..LinkParseIpc.EXIT) return false
            // First, before anything of a call is read: never decode as Robot.
            if (!Process.isIsolated()) {
                reply?.writeInt(LinkParseIpc.REPLY_NOT_ISOLATED)
                return true
            }
            // Robot's own uid alone (:link): a process of another uid, an isolated one included, gets nothing.
            val caller = Binder.getCallingUid()
            if (appUid < 0 || caller != appUid) {
                reply?.writeInt(LinkParseIpc.REPLY_REFUSED)
                return true
            }
            try {
                data.enforceInterface(LinkParseIpc.DESCRIPTOR)
                when (code) {
                    LinkParseIpc.EXIT -> Process.killProcess(Process.myPid())
                    LinkParseIpc.HELLO -> synchronized(lock) { hello(data, reply ?: return true) }
                    else -> synchronized(lock) { decode(data, reply ?: return true) }
                }
            } catch (_: Exception) {
                reply?.apply { setDataSize(0); setDataPosition(0); writeInt(LinkParseIpc.REPLY_REFUSED) }
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    /** The link let go (:link sleeps, its process ended, or it gave up on this one): nothing left to do here. */
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
                d.writeInterfaceToken(LinkParseIpc.CALLBACK)
                d.writeInt(Process.myPid())
                cb.transact(LinkParseIpc.CB_HELLO, d, r, 0) && r.dataAvail() >= 4 && r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        } catch (_: Exception) { false }
        reply.writeInt(LinkParseIpc.REPLY_OK)
        reply.writeInt(if (ok) 1 else 0)
    }

    private fun decode(data: Parcel, reply: Parcel) {
        val frame = bytes(data, LinkParseIpc.REQUEST_FRAME)
        need(data, 4)
        if (data.readInt() != 1) throw IllegalArgumentException()
        val region = SharedMemory.CREATOR.createFromParcel(data)
        try {
            if (data.dataAvail() != 0) throw IllegalArgumentException()
            val ask = LinkParseWire.decodeRequest(frame, region.size) ?: throw IllegalArgumentException()
            val buf = region.mapReadOnly()
            val plain = try {
                ByteArray(ask.size).also { buf.get(it) }
            } finally {
                SharedMemory.unmap(buf)
            }
            val job = worker.submit<ByteArray> { LinkParser.answer(plain, ask.cap) }
            val answer = try {
                job.get(LinkParseIpc.SERVICE_MS, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                // A request that doesn't finish: the process ends, and whatever it held with it.
                Process.killProcess(Process.myPid())
                throw IllegalStateException()
            }
            reply.writeInt(LinkParseIpc.REPLY_OK)
            reply.writeByteArray(answer)
        } finally {
            try { region.close() } catch (_: Exception) {}
        }
    }

    /** Exactly [n] bytes, the array's length checked before anything is made for it. */
    private fun bytes(p: Parcel, n: Int): ByteArray {
        val at = p.dataPosition()
        need(p, 4)
        if (p.readInt() != n) throw IllegalArgumentException()
        need(p, n)
        p.setDataPosition(at)
        val b = p.createByteArray()
        if (b == null || b.size != n) throw IllegalArgumentException()
        return b
    }

    private fun need(p: Parcel, n: Int) {
        if (p.dataAvail() < n) throw IllegalArgumentException()
    }
}
