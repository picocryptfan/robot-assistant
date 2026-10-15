// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.Process

// :LLAMA PROCESS ONLY (android:process=":llama", android:isolatedProcess="true", not exported). llama.cpp for your own
// GGUF file (0.4.5), the one place that reaches its bridge (LlamaNative). Android runs this service under an isolated
// uid of its own: no permission at all (contacts, calendar, the network…), none of Robot's files (the helpers' keys, the
// settings, the notes), and no file it could open by name. A memory-safety bug in llama.cpp's GGUF parser, its Jinja
// engine or ggml, reached through a hostile file, runs code here, not as Robot.
//
// :brain binds it for one GGUF load and lets it go when that model goes: unbinding (or EXIT) ends the process, and the
// model's memory with it. :brain gives it the file it checked as an open descriptor (LOAD), the conversation as the
// template's roles and texts (PREPARE), and asks for a reply (GENERATE), whose pieces come back through :brain's
// callback; it decides everything else (LlamaEngine). Calls come from :brain's one llama worker, one at a time (one lock
// here as well); CANCEL and EXIT from any of its threads, one way, on another binder thread here. One model per process:
// a second LOAD is refused.

class LlamaService : Service() {
    private val lock = Any()
    @Volatile private var handle = 0L
    private var loads = 0
    @Volatile private var callback: IBinder? = null

    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(LlamaIpc.DESCRIPTOR)
                return true
            }
            data.enforceInterface(LlamaIpc.DESCRIPTOR)
            when (code) {
                LlamaIpc.EXIT -> Process.killProcess(Process.myPid())
                LlamaIpc.CANCEL -> {
                    val h = data.readLong()
                    val run = data.readLong()
                    // The handle is never freed while this process lives (the model goes with the process), so a cancel
                    // can never touch freed memory.
                    if (h != 0L && h == handle) LlamaNative.cancel(h, run)
                }
                LlamaIpc.HELLO -> synchronized(lock) { hello(data, reply ?: return false) }
                LlamaIpc.LOAD -> synchronized(lock) { load(data, reply ?: return false) }
                LlamaIpc.PREPARE -> synchronized(lock) { prepare(data, reply ?: return false) }
                LlamaIpc.GENERATE -> synchronized(lock) { generate(data, reply ?: return false) }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    /** :brain let go (the model's unload, or :brain itself ending): this process has nothing left to do. */
    override fun onUnbind(intent: Intent?): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    private fun hello(data: Parcel, reply: Parcel) {
        val cb = data.readStrongBinder()
        callback = cb
        // :brain checks, from the kernel's uid of this call, that this process is an isolated one before it sends a file.
        val problem = LlamaNative.ready()
        val ok = cb != null && try {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(LlamaIpc.CALLBACK)
                d.writeInt(Process.myPid())
                cb.transact(LlamaIpc.CB_HELLO, d, r, 0)
                r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        } catch (_: Exception) { false }
        reply.writeNoException()
        reply.writeInt(if (ok) 1 else 0)
        reply.writeString(problem)
    }

    private fun load(data: Parcel, reply: Parcel) {
        val pfd: ParcelFileDescriptor? = if (data.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(data) else null
        val nCtx = data.readInt()
        val threads = data.readInt()
        val batchThreads = data.readInt()
        val sampling = data.createFloatArray() ?: FloatArray(0)
        val why = IntArray(1)
        var h = 0L
        var train = 0
        var control: List<ByteArray>? = null
        var user: List<ByteArray>? = null
        try {
            if (pfd != null && loads++ == 0 && LlamaNative.ready() == null) {
                h = LlamaNative.load(pfd.fd, nCtx, threads, batchThreads, sampling, why)
                if (h != 0L) {
                    control = LlamaNative.specials(h, false)?.toList()
                    user = LlamaNative.specials(h, true)?.toList()
                    train = LlamaNative.trainContext(h)
                    if (control == null || user == null) {
                        LlamaNative.free(h)
                        h = 0L
                    }
                }
            }
        } finally {
            // llama.cpp read the file through its own copy of the descriptor and closed it; the mapping keeps the file.
            try { pfd?.close() } catch (_: Exception) {}
        }
        handle = h
        reply.writeNoException()
        reply.writeLong(h)
        reply.writeInt(why[0])
        reply.writeInt(train)
        // Only marker-length user-defined tokens (core PromptMarkers takes those): no need to send a vocabulary's worth.
        // More than :brain reads goes as none (:brain then lets the file go).
        LlamaIpc.writeArrays(reply, capped(control))
        LlamaIpc.writeArrays(reply, capped(user?.filter { it.size <= 4 * 64 }))
    }

    private fun capped(items: List<ByteArray>?): List<ByteArray>? =
        items?.takeIf { it.size <= LlamaIpc.MAX_SPECIALS && it.sumOf { b -> b.size.toLong() } <= LlamaIpc.MAX_SPECIAL_BYTES }

    private fun prepare(data: Parcel, reply: Parcel) {
        val h = data.readLong()
        val roles = LlamaIpc.readArrays(data, Int.MAX_VALUE, Int.MAX_VALUE).orEmpty()
        val texts = LlamaIpc.readArrays(data, Int.MAX_VALUE, Int.MAX_VALUE).orEmpty()
        val think = data.readInt() != 0
        val flags = IntArray(1)
        val tokens = if (h == 0L || h != handle) LlamaIpc.PREP_TEMPLATE_FAILED
        else LlamaNative.prepare(h, roles.toTypedArray(), texts.toTypedArray(), think, flags)
        reply.writeNoException()
        reply.writeInt(tokens)
        reply.writeInt(flags[0])
    }

    private fun generate(data: Parcel, reply: Parcel) {
        val h = data.readLong()
        val run = data.readLong()
        val maxTokens = data.readInt()
        val stats = LongArray(LlamaIpc.STATS_COUNT)
        val cb = callback
        val code = if (h == 0L || h != handle || cb == null) LlamaIpc.GEN_NOT_PREPARED
        else LlamaNative.generate(h, run, maxTokens, { bytes -> piece(cb, bytes) }, stats)
        reply.writeNoException()
        reply.writeInt(code)
        reply.writeLongArray(stats)
    }

    /** One piece to :brain (the worker waiting for this GENERATE runs it); false: stop, also when :brain is gone. */
    private fun piece(cb: IBinder, bytes: ByteArray): Boolean = try {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(LlamaIpc.CALLBACK)
            d.writeByteArray(bytes)
            cb.transact(LlamaIpc.CB_PIECE, d, r, 0) && r.readInt() == 1
        } finally {
            d.recycle()
            r.recycle()
        }
    } catch (_: Exception) { false }
}
