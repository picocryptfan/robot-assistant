// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.ask

import app.heyrobot.core.Pcm
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * A shared voice message's audio, chunk by chunk, into 16 kHz mono (E7, D20): the lengths at each rate, the cap, the
 * formats it refuses, and a tone that stays the same tone.
 */
class PcmSinkTest {
    private fun tone(rate: Int, seconds: Double, hz: Double = 440.0, channels: Int = 1): FloatArray {
        val n = (rate * seconds).toInt()
        return FloatArray(n * channels) { i -> (0.5 * sin(2 * PI * hz * (i / channels) / rate)).toFloat() }
    }

    private fun s16(x: FloatArray): ByteArray = ByteArray(x.size * 2).also { b ->
        for (i in x.indices) {
            val v = (x[i] * 32767).toInt()
            b[2 * i] = (v and 0xFF).toByte()
            b[2 * i + 1] = (v shr 8).toByte()
        }
    }

    @Test fun eachRateComesOutAt16kHz() {
        for ((rate, channels) in listOf(48_000 to 2, 44_100 to 1, 16_000 to 1, 22_050 to 2, 8_000 to 1, 12_000 to 1)) {
            val sink = PcmSink(Pcm.RATE * 60)
            assertTrue(sink.format(rate, channels))
            val x = tone(rate, 2.0, channels = channels)
            // In uneven chunks, as a decoder hands them over.
            var off = 0
            var k = 0
            while (off < x.size) {
                val len = minOf(x.size - off, (1_000 + 337 * k++) / channels * channels)
                sink.floats(x, off, len)
                off += len
            }
            val out = sink.finish()
            assertEquals("$rate Hz", 32_000.0, out.size.toDouble(), 2.0)
            assertTrue(out.all { it in -1f..1f })
        }
    }

    @Test fun sixteenBitAndFloatAgree() {
        val x = tone(48_000, 1.0, channels = 2)
        val a = PcmSink(Pcm.RATE * 10).apply { format(48_000, 2); floats(x) }.finish()
        val b = PcmSink(Pcm.RATE * 10).apply { format(48_000, 2); s16(s16(x)) }.finish()
        assertEquals(a.size, b.size)
        for (i in a.indices) assertEquals(a[i], b[i], 0.001f)
    }

    @Test fun aToneStaysTheSameTone() {
        // 440 Hz at 48 kHz, down to 16 kHz: count the zero crossings (2 per cycle).
        val out = PcmSink(Pcm.RATE * 10).apply { format(48_000, 1); floats(tone(48_000, 1.0)) }.finish()
        val crossings = (1 until out.size).count { (out[it - 1] < 0) != (out[it] < 0) }
        assertEquals(880.0, crossings.toDouble(), 4.0)
        // And up from 8 kHz.
        val up = PcmSink(Pcm.RATE * 10).apply { format(8_000, 1); floats(tone(8_000, 1.0)) }.finish()
        val upCrossings = (1 until up.size).count { (up[it - 1] < 0) != (up[it] < 0) }
        assertEquals(880.0, upCrossings.toDouble(), 4.0)
    }

    @Test fun itStopsAtTheCap() {
        val sink = PcmSink(Pcm.RATE * 3)
        sink.format(44_100, 2)
        val x = tone(44_100, 1.0, channels = 2)
        repeat(10) { sink.floats(x) }
        assertTrue(sink.full)
        assertEquals(3, sink.seconds)
        assertEquals(Pcm.RATE * 3, sink.finish().size)
    }

    @Test fun outOfBoundsFormatsTakeNothing() {
        for ((rate, ch) in listOf(7_999 to 1, 192_001 to 1, 16_000 to 0, 16_000 to 9, 0 to 1, -1 to 2)) {
            val sink = PcmSink(Pcm.RATE)
            assertFalse("$rate/$ch", sink.format(rate, ch))
            sink.floats(FloatArray(100) { 0.1f })
            sink.s16(ByteArray(200))
            assertEquals(0, sink.finish().size)
        }
        // A good format after a bad one counts again.
        val sink = PcmSink(Pcm.RATE)
        assertFalse(sink.format(1, 1))
        assertTrue(sink.format(16_000, 1))
        sink.floats(FloatArray(160) { 0.1f })
        assertEquals(160, sink.finish().size)
    }

    @Test fun badSamplesAreSilenceAndPartFramesAreLeft() {
        val sink = PcmSink(Pcm.RATE)
        sink.format(16_000, 2)
        // NaN, infinity and out-of-range values: silence or clipped; a half frame at the end is left out.
        sink.floats(floatArrayOf(Float.NaN, Float.NaN, Float.POSITIVE_INFINITY, 1f, 4f, 4f, 0.5f))
        assertArrayEquals(floatArrayOf(0f, 0.5f, 1f), sink.finish(), 0f)
        val s = PcmSink(Pcm.RATE)
        s.format(16_000, 1)
        s.s16(byteArrayOf(0, 0x40, 0x7F))
        assertEquals(1, s.finish().size)
        assertTrue(abs(PcmSink(1).apply { format(16_000, 1); floats(floatArrayOf(0.25f)) }.finish()[0] - 0.25f) < 1e-6)
    }
}
