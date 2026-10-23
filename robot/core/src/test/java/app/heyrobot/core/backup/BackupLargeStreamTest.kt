// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32C

/**
 * Design §13 phase 1's "done when": a 1 GiB backup (one 1 GiB recording, the biggest entry the format allows) is written
 * and read back through the cipher and the records in one pass, through a pipe, so neither side ever holds the file; the
 * heap stays flat while it runs.
 */
class BackupLargeStreamTest {
    private val total = BackupCaps.RECORDING
    private val name = "20261011-1402-7f3a/audio.ogg"

    /** The synthetic stream's bytes from [pos] on, into [b] (a fixed 64-bit pattern of each 8-byte word's index). */
    private fun fill(b: ByteArray, pos: Long) {
        val bb = ByteBuffer.wrap(b)
        var w = pos / 8
        while (bb.remaining() >= 8) bb.putLong(w++ * -0x61c8864680b583ebL)
    }

    @Test fun oneGibRoundTripsWithFlatMemory() {
        val rt = Runtime.getRuntime()
        fun used(): Long {
            System.gc()
            return rt.totalMemory() - rt.freeMemory()
        }

        val pipeIn = PipedInputStream(1 shl 20)
        val pipeOut = PipedOutputStream(pipeIn)
        val header = BackupKit.header()
        val written = CRC32C()
        val failure = AtomicReference<Throwable?>()
        val writer = Thread {
            try {
                BackupWriter(pipeOut, header, BackupKit.key()).use { w ->
                    val c = BackupContainerWriter(w)
                    c.manifest(BackupKit.manifest(BackupCategory.TRANSCRIPTS))
                    c.entry(BackupCategory.TRANSCRIPTS, name, total, object : java.io.InputStream() {
                        var pos = 0L
                        val buf = ByteArray(BackupFormat.CHUNK)
                        override fun read(): Int = throw UnsupportedOperationException()
                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            if (pos >= total) return -1
                            val n = minOf(len.toLong(), total - pos, buf.size.toLong()).toInt() and 0x7FFF_FFF8.toInt()
                            fill(buf, pos)
                            System.arraycopy(buf, 0, b, off, n)
                            written.update(buf, 0, n)
                            pos += n
                            return n
                        }
                    })
                    c.end()
                }
            } catch (t: Throwable) {
                failure.set(t)
                pipeOut.close()
            }
        }
        val before = used()
        writer.start()
        val start = ByteArray(BackupFormat.HEADER_SIZE)
        var k = 0
        while (k < start.size) k += pipeIn.read(start, k, start.size - k)
        val reader = BackupReader(pipeIn, BackupHeader.parse(start), BackupKit.key())
        val records = BackupContainerReader(reader)
        val got = CRC32C()
        var read = 0L
        var peak = 0L
        val t0 = System.nanoTime()
        var sawEnd = false
        while (true) {
            when (val r = records.next() ?: break) {
                is BackupRecord.Entry -> {
                    assertEquals(name, r.name)
                    assertEquals(total, r.length)
                    val buf = ByteArray(BackupFormat.CHUNK)
                    var nextSample = 0L
                    while (true) {
                        val n = r.data.read(buf)
                        if (n < 0) break
                        got.update(buf, 0, n)
                        read += n
                        if (read >= nextSample) {
                            peak = maxOf(peak, used() - before)
                            nextSample += 128L * BackupCaps.MIB
                        }
                    }
                }
                is BackupRecord.End -> sawEnd = true
                is BackupRecord.Manifest -> {}
            }
        }
        writer.join()
        failure.get()?.let { throw it }
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.out.println("BackupLargeStreamTest: 1 GiB written and read back in $ms ms; heap growth at most ${peak / 1024} KiB")
        assertTrue(sawEnd)
        assertEquals(total, read)
        assertEquals(written.value, got.value)
        assertTrue(reader.released > total && reader.chunks == total / BackupFormat.CHUNK + 1)
        // Two 64 KiB chunks, a pipe of 1 MiB and the records' buffers: far below this.
        assertTrue("heap grew by $peak bytes", peak < 32L * BackupCaps.MIB)
    }
}
