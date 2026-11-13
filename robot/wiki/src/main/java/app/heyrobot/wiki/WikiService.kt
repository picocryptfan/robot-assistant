// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// :WIKI PROCESS ONLY (android:process=":wiki", android:isolatedProcess="true", not exported, no permission, no intent
// filter). Wikipedia on this phone (wikipedia-offline-plan.md §3, P1): libzim reads a ZIM file here, under an isolated uid
// of its own: no permission, no network, none of Robot's files and no file it could open by name. It reads only the file
// the caller hands it as a read-only descriptor; a bug in libzim, Xapian or a decompressor, reached through a damaged
// file, runs here, not as Robot, and ends only this process. Whatever it answers, WikiReader cut it down first and
// WikiLink reads it again with bounds.
//
// Each call is checked in this order: this process is isolated (before anything of a call is read), the caller is
// Robot's own uid, then the interface token. Replies are a status and values, never an exception. The process ends
// itself after WikiIpc.IDLE_MS without a call and when the caller lets go. Nothing here logs.
class WikiService : Service() {
    private val lock = ReentrantLock()
    internal var reader = WikiReader(LibZim)
    /** Ends this process (a test's stand-in records it). */
    internal var kill: () -> Unit = { Process.killProcess(Process.myPid()) }
    @Volatile private var appUid = -1
    @Volatile private var lastCall = 0L
    private var idle: ScheduledExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        appUid = applicationInfo.uid
        lastCall = SystemClock.elapsedRealtime()
        idle = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "robot-wiki-idle").apply { isDaemon = true } } // l10n: not text (a thread's name)
            .also { it.scheduleWithFixedDelay(::checkIdle, WikiIpc.IDLE_MS, WikiIpc.IDLE_MS / 4, TimeUnit.MILLISECONDS) }
    }

    private fun checkIdle() {
        if (SystemClock.elapsedRealtime() - lastCall >= WikiIpc.IDLE_MS) end()
    }

    private fun end() {
        // review-wikipedia-p4-c: never wait on a call hung in the library (it holds the lock): close only when none does, then end.
        closeIfFree()
        kill()
    }

    private fun closeIfFree() {
        if (lock.tryLock()) try { reader.close() } finally { lock.unlock() }
    }

    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(WikiIpc.DESCRIPTOR)
                return true
            }
            if (code !in WikiIpc.OPEN..WikiIpc.EXIT) return false
            // First, before anything of a call is read: never read a ZIM file as Robot.
            if (!Process.isIsolated()) {
                reply?.writeInt(WikiIpc.REPLY_NOT_ISOLATED)
                return true
            }
            val caller = Binder.getCallingUid()
            if (appUid < 0 || caller != appUid) {
                reply?.writeInt(WikiIpc.REPLY_REFUSED)
                return true
            }
            lastCall = SystemClock.elapsedRealtime()
            try {
                data.enforceInterface(WikiIpc.DESCRIPTOR)
                if (code == WikiIpc.EXIT) { end(); return true }
                lock.withLock { answer(code, data, reply ?: return true) }
            } catch (_: Exception) {
                reply?.apply { setDataSize(0); setDataPosition(0); writeInt(WikiIpc.REPLY_REFUSED) }
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    /** The caller let go (or its process ended): nothing left to do here. */
    override fun onUnbind(intent: Intent?): Boolean {
        end()
        return false
    }

    private fun answer(code: Int, data: Parcel, reply: Parcel) {
        when (code) {
            WikiIpc.OPEN -> {
                if (data.readInt() != 1) throw IllegalArgumentException()
                val pfd = ParcelFileDescriptor.CREATOR.createFromParcel(data)
                pfd.use {
                    if (data.dataAvail() != 0) throw IllegalArgumentException()
                    if (!LibZim.available) return reply.writeInt(WikiIpc.REPLY_NO_LIBRARY)
                    val info = reader.open(it.fd) ?: return reply.writeInt(WikiIpc.REPLY_NOT_ZIM)
                    reply.writeInt(WikiIpc.REPLY_OK)
                    WikiIpc.writeInfo(reply, info)
                }
            }
            WikiIpc.TITLES, WikiIpc.SEARCH -> {
                val query = data.readString() ?: throw IllegalArgumentException()
                val max = data.readInt()
                if (data.dataAvail() != 0 || query.length > WikiReader.MAX_QUERY * 4) throw IllegalArgumentException()
                if (!reader.isOpen) return reply.writeInt(WikiIpc.REPLY_NOT_OPEN)
                val hits = if (code == WikiIpc.TITLES) reader.titles(query, max) else reader.search(query, max)
                reply.writeInt(WikiIpc.REPLY_OK)
                WikiIpc.writeHits(reply, hits)
            }
            WikiIpc.ARTICLE -> {
                val path = data.readString() ?: throw IllegalArgumentException()
                if (data.dataAvail() != 0 || path.length > WikiReader.MAX_PATH) throw IllegalArgumentException()
                if (!reader.isOpen) return reply.writeInt(WikiIpc.REPLY_NOT_OPEN)
                val a = reader.article(path) ?: return reply.writeInt(WikiIpc.REPLY_NONE)
                reply.writeInt(WikiIpc.REPLY_OK)
                WikiIpc.writeArticle(reply, a)
            }
            else -> throw IllegalArgumentException()
        }
    }

    override fun onDestroy() {
        idle?.shutdownNow()
        closeIfFree()
        super.onDestroy()
    }
}
