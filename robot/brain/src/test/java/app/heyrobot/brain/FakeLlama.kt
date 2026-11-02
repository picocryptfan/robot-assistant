// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.brain

import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * llama.cpp's isolated process as LlamaEngine sees it, with no model and no library: LlamaService's side of LlamaIpc,
 * answering by script. Each answer can be replaced with one that breaks the layout, as a hostile file's process might.
 */
internal class FakeLlama : Binder() {
    class Prepared(val handle: Long, val roles: List<String>, val texts: List<String>, val think: Boolean)

    inner class Gen(val run: Long, val maxTokens: Int) {
        /** One piece through :brain's callback; false: :brain says stop. */
        fun piece(bytes: ByteArray): Boolean {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(LlamaIpc.CALLBACK)
                d.writeByteArray(bytes)
                return callback!!.transact(LlamaIpc.CB_PIECE, d, r, 0) && r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        }

        fun piece(text: String) = piece(text.toByteArray(Charsets.UTF_8))
        /** Waits for :brain's one-way stop of this run; false if none came. */
        fun awaitCancel(ms: Long = 5_000): Boolean = cancelled.await(ms, TimeUnit.MILLISECONDS)
    }

    @Volatile var callback: IBinder? = null
    @Volatile private var death: IBinder.DeathRecipient? = null
    val hellos = AtomicInteger()
    val loads = AtomicInteger()
    val exits = AtomicInteger()
    val prepared = CopyOnWriteArrayList<Prepared>()
    val runs = CopyOnWriteArrayList<Gen>()
    @Volatile var cancelled = CountDownLatch(1)
    /** The uid-proof call the real process makes before it answers HELLO: was it accepted? */
    @Volatile var proofAccepted: Boolean? = null
    /** It goes as a real process goes when told to: ending it is what frees the model's memory. */
    @Volatile var exitEnds = true

    var train = 131_072
    var control: List<ByteArray>? = listOf("<bos>".toByteArray(), "<start_of_turn>".toByteArray())
    var user: List<ByteArray>? = emptyList()
    var answerHello: (Parcel) -> Unit = { r -> r.writeInt(LlamaIpc.REPLY_OK); r.writeInt(1); r.writeString(null) }
    var answerLoad: (Parcel) -> Unit = { r ->
        r.writeInt(LlamaIpc.REPLY_OK)
        r.writeLong(HANDLE)
        r.writeInt(0)
        r.writeInt(train)
        LlamaIpc.writeArrays(r, control)
        LlamaIpc.writeArrays(r, user)
    }
    var prepare: (Prepared) -> Int = { 100 }
    var prepareFlags = 0
    /** The reply itself: its pieces, then a GEN_* code. */
    var generate: (Gen) -> Int = { LlamaIpc.GEN_END }
    var stats = LongArray(LlamaIpc.STATS_COUNT)
    /** Replaces GENERATE's reply (a broken layout), after the script has run. */
    var answerGenerate: ((Parcel, Int) -> Unit)? = null

    override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) { death = recipient }
    /** The process is gone, as Binder says it. */
    fun die() { death?.binderDied() }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(LlamaIpc.DESCRIPTOR)
        when (code) {
            LlamaIpc.HELLO -> {
                hellos.incrementAndGet()
                val cb = data.readStrongBinder()
                callback = cb
                val d = Parcel.obtain()
                val r = Parcel.obtain()
                d.writeInterfaceToken(LlamaIpc.CALLBACK)
                d.writeInt(1234)
                cb.transact(LlamaIpc.CB_HELLO, d, r, 0)
                proofAccepted = r.readInt() == 1
                d.recycle()
                r.recycle()
                answerHello(reply!!)
            }
            LlamaIpc.LOAD -> {
                loads.incrementAndGet()
                answerLoad(reply!!)
            }
            LlamaIpc.PREPARE -> {
                val h = data.readLong()
                fun strings() = LlamaIpc.readArrays(data, Int.MAX_VALUE, Int.MAX_VALUE).orEmpty().map { String(it, Charsets.UTF_8) }
                val roles = strings()
                val texts = strings()
                val p = Prepared(h, roles, texts, data.readInt() != 0)
                prepared += p
                val n = prepare(p)
                reply!!.writeInt(LlamaIpc.REPLY_OK)
                reply.writeInt(n)
                reply.writeInt(prepareFlags)
            }
            LlamaIpc.GENERATE -> {
                data.readLong()
                val g = Gen(data.readLong(), data.readInt())
                runs += g
                val c = generate(g)
                val a = answerGenerate
                if (a != null) a(reply!!, c) else {
                    reply!!.writeInt(LlamaIpc.REPLY_OK)
                    reply.writeInt(c)
                    reply.writeLongArray(stats)
                }
            }
            LlamaIpc.CANCEL -> cancelled.countDown()
            LlamaIpc.EXIT -> {
                exits.incrementAndGet()
                if (exitEnds) die()
            }
            else -> return false
        }
        return true
    }

    /** A new process for the next load (a test that reloads after its first one went). */
    fun resetLife() { cancelled = CountDownLatch(1) }

    /** What a process that crashes inside a call looks like to its caller. */
    fun crash(): Nothing {
        die()
        throw DeadObjectException()
    }

    companion object {
        const val HANDLE = 42L
    }
}
