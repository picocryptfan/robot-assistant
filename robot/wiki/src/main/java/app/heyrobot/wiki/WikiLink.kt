// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.wiki

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.Closeable
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * The caller's side of the :wiki process: binds WikiService, hands it one ZIM file read-only, asks, and lets it go on
 * [close] (the process then ends). Blocking: never on the main thread. Every reply is read again with bounds.
 *
 * M3 (review-wikipedia): the process ends itself after a minute idle, or dies on a bad file. The link notices (the
 * binder's death, the service's disconnection or a call that finds it dead), lets that binding go and, on the next call,
 * binds once more and opens the same file again; when that fails too the link is [gone] for good and every call reads as
 * [WikiIpc.REPLY_GONE]: "the copy isn't available", never "nothing found".
 */
class WikiLink internal constructor(private val bind: (onDeath: () -> Unit) -> Bound?) : Closeable {
    /** One binding of WikiService: its binder, and how to let it go. */
    class Bound(val binder: IBinder, val unbind: () -> Unit)

    companion object {
        /** Binds the :wiki process, or null when it didn't answer within [timeoutMs]. */
        fun connect(context: Context, timeoutMs: Long = 5_000): WikiLink? {
            val app = context.applicationContext
            val link = WikiLink { onDeath ->
                bindOnce({ c -> app.bindService(Intent(app, WikiService::class.java), Context.BIND_AUTO_CREATE, executor, c) },
                    { c -> app.unbindService(c) }, timeoutMs, onDeath)
            }
            return if (link.revive() != null) link else null
        }

        /**
         * One binding through [bind] (Context.bindService), waiting up to [timeoutMs] for the service's binder.
         * fix-wiki-rc1-b: the binder is read only after the wait; read before it (9cc565aff), it was always still null, as
         * Android connects later on another thread, so every connect failed and the copy never answered.
         */
        internal fun bindOnce(bind: (ServiceConnection) -> Boolean, unbind: (ServiceConnection) -> Unit, timeoutMs: Long, onDeath: () -> Unit): Bound? {
            val latch = CountDownLatch(1)
            var got: IBinder? = null
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    got = service
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) = onDeath()
                override fun onBindingDied(name: ComponentName?) = onDeath()
            }
            val bound = bind(conn)
            // M1 (review-fix-wiki-rc1-b): a stop interrupts the wait; the binding is let go before the stop goes on.
            val b = try {
                if (bound && latch.await(timeoutMs, TimeUnit.MILLISECONDS)) got else null
            } catch (e: InterruptedException) {
                runCatching { unbind(conn) }
                throw e
            }
            if (b == null || !b.isBinderAlive) {
                runCatching { unbind(conn) }
                return null
            }
            if (!runCatching { b.linkToDeath({ onDeath() }, 0) }.isSuccess) {
                runCatching { unbind(conn) }
                return null
            }
            return Bound(b) { runCatching { unbind(conn) } }
        }

        private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-wiki-link").apply { isDaemon = true } } // l10n: not text (a thread's name)
    }

    private val lock = Any()
    @Volatile private var bound: Bound? = null
    private var opened: File? = null
    private var closed = false

    /** True once the reader is gone and binding again failed: the copy isn't available (not "nothing found"). */
    @Volatile var gone: Boolean = false
        private set

    /** The status of the last call (WikiIpc.REPLY_*), for the caller to tell "nothing found" from "can't read". */
    @Volatile var lastStatus: Int = WikiIpc.REPLY_NONE
        private set

    /** Opens [file] in the :wiki process; its info, or null (see [lastStatus]). Opened again after the process restarts. */
    fun open(file: File): WikiInfo? = call(WikiIpc.OPEN, { writeOpen(file) }) { WikiIpc.readInfo(this) }.also {
        if (it != null) synchronized(lock) { opened = file }
    }

    fun titles(query: String, max: Int = 5): List<WikiHit> =
        call(WikiIpc.TITLES, { writeString(query.take(WikiReader.MAX_QUERY)); writeInt(max) }) { WikiIpc.readHits(this) } ?: emptyList()

    fun search(query: String, max: Int = 5): List<WikiHit> =
        call(WikiIpc.SEARCH, { writeString(query.take(WikiReader.MAX_QUERY)); writeInt(max) }) { WikiIpc.readHits(this) } ?: emptyList()

    fun article(path: String): WikiArticle? =
        if (path.length > WikiReader.MAX_PATH) null else call(WikiIpc.ARTICLE, { writeString(path) }) { WikiIpc.readArticle(this) }

    override fun close() {
        val b = synchronized(lock) { closed = true; bound.also { bound = null } } ?: return
        runCatching { transact(b, WikiIpc.EXIT, {}, oneWay = true) { null } }
        b.unbind()
    }

    private fun Parcel.writeOpen(file: File) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
        writeInt(1)
        pfd.writeToParcel(this, 0)
    }

    /** Lets a dead binding go (from its death notice or a call that found it dead). */
    private fun drop(b: Bound) {
        // M2 (review-fix-wiki-rc1-b): a stale notice returns at once, never waiting on a rebind that holds the lock.
        if (bound !== b) return
        synchronized(lock) { if (bound === b) bound = null else return }
        b.unbind()
    }

    /** Binds again and opens the same file again, or marks the link gone. */
    private fun revive(): Bound? = synchronized(lock) {
        if (closed || gone) return null
        bound?.let { return it }
        var b: Bound? = null
        b = bind { b?.let(::drop) }
        val f = opened
        if (b != null && f != null && runCatching { transact(b, WikiIpc.OPEN, { writeOpen(f) }, oneWay = false) { WikiIpc.readInfo(this) } }.getOrNull() == null) {
            b.unbind()
            b = null
        }
        if (b == null) gone = true else bound = b
        b
    }

    private class Dead : Exception()

    private fun <T> call(code: Int, args: Parcel.() -> Unit, read: Parcel.() -> T?): T? {
        repeat(2) {
            val b = bound ?: revive() ?: return unavailable()
            try {
                return transact(b, code, args, oneWay = false, read)
            } catch (_: Dead) {
                drop(b)
            }
        }
        // Dead again right after binding again: not tried a third time.
        synchronized(lock) { gone = true }
        return unavailable()
    }

    private fun <T> unavailable(): T? {
        lastStatus = WikiIpc.REPLY_GONE
        return null
    }

    private fun <T> transact(b: Bound, code: Int, args: Parcel.() -> Unit, oneWay: Boolean, read: Parcel.() -> T?): T? {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        return try {
            d.writeInterfaceToken(WikiIpc.DESCRIPTOR)
            d.args()
            if (!b.binder.transact(code, d, if (oneWay) null else r, if (oneWay) IBinder.FLAG_ONEWAY else 0)) {
                if (!b.binder.isBinderAlive) throw Dead()
                return null
            }
            if (oneWay) return null
            if (r.dataAvail() < 4) {
                if (!b.binder.isBinderAlive) throw Dead()
                return null
            }
            lastStatus = r.readInt()
            if (lastStatus != WikiIpc.REPLY_OK) null else r.read()
        } catch (e: Dead) {
            throw e
        } catch (_: DeadObjectException) {
            throw Dead()
        } catch (_: Exception) {
            if (!b.binder.isBinderAlive) throw Dead()
            lastStatus = WikiIpc.REPLY_REFUSED
            null
        } finally {
            d.recycle()
            r.recycle()
        }
    }
}
