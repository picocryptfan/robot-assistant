// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

// :LINK PROCESS. What went online (everyday-set-plan.md §5.2, §5.3; job E4): the direct carrier's row, written when the
// connection closes, with the bytes it carried. The other phone's address and who it belongs to never reach the record.
// A connection that came in and that Robot sent nothing on isn't a row: nothing left the phone, and anyone on the network
// could otherwise fill the day's record by knocking on the port.

internal object DirectRecord {
    fun dialled(d: Duplex): Duplex = CountedDuplex(d) { out, inBytes -> NetRecord.linkDirect(out, inBytes, NetResult.DONE) }
    fun accepted(d: Duplex): Duplex = CountedDuplex(d) { out, inBytes -> if (out > 0) NetRecord.linkDirect(out, inBytes, NetResult.DONE) }
    fun failed() = NetRecord.linkDirect(0, 0, NetResult.FAILED)
}

/** [inner] counting what goes through it; [ended] hears the bytes sent and received once, at the first [close]. */
internal class CountedDuplex(private val inner: Duplex, private val ended: (Long, Long) -> Unit) : Duplex {
    private val sent = AtomicLong()
    private val got = AtomicLong()
    private val closed = AtomicBoolean()

    override val input: InputStream = object : InputStream() {
        private val from = inner.input
        override fun read(): Int = from.read().also { if (it >= 0) got.incrementAndGet() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = from.read(b, off, len).also { if (it > 0) got.addAndGet(it.toLong()) }
        override fun available(): Int = from.available()
        override fun close() = from.close()
    }

    override val output: OutputStream = object : OutputStream() {
        private val to = inner.output
        override fun write(b: Int) {
            to.write(b)
            sent.incrementAndGet()
        }
        override fun write(b: ByteArray, off: Int, len: Int) {
            to.write(b, off, len)
            sent.addAndGet(len.toLong())
        }
        override fun flush() = to.flush()
        override fun close() = to.close()
    }

    override fun readTimeout(ms: Int) = inner.readTimeout(ms)

    override fun close() {
        inner.close()
        if (closed.compareAndSet(false, true)) ended(sent.get(), got.get())
    }
}
