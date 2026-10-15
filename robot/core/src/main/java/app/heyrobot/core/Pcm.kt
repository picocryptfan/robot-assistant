// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. Turning decoded audio into what the speech model takes (16 kHz mono floats), for the Transcription check's
// imported clips (docs/conversation-mode.md 2.5). In memory only: nothing here writes audio anywhere.

object Pcm {
    const val RATE = 16_000

    /** Little-endian signed 16-bit samples ([len] bytes from [off]) as floats in -1..1. */
    fun fromS16(b: ByteArray, off: Int = 0, len: Int = b.size - off): FloatArray {
        val n = len / 2
        return FloatArray(n) { i ->
            val lo = b[off + 2 * i].toInt() and 0xff
            val hi = b[off + 2 * i + 1].toInt()
            ((hi shl 8) or lo).toShort() / 32768f
        }
    }

    /** Interleaved [channels] to mono, by averaging. */
    fun mono(x: FloatArray, channels: Int): FloatArray {
        if (channels <= 1) return x
        val n = x.size / channels
        return FloatArray(n) { i ->
            var s = 0f
            for (c in 0 until channels) s += x[i * channels + c]
            s / channels
        }
    }

    /**
     * [x] at [from] Hz as [to] Hz, by linear interpolation, with a moving average first when going down (a crude
     * low-pass, enough for speech recognition: Parakeet's features stop at 8 kHz).
     */
    fun resample(x: FloatArray, from: Int, to: Int = RATE): FloatArray {
        require(from > 0 && to > 0)
        if (from == to || x.isEmpty()) return x
        val src = if (from > to) smooth(x, Math.round(from.toDouble() / to).toInt().coerceAtLeast(1)) else x
        val n = ((src.size.toLong() * to) / from).toInt()
        val step = from.toDouble() / to
        return FloatArray(n) { i ->
            val p = i * step
            val k = p.toInt()
            val f = (p - k).toFloat()
            val a = src[k.coerceAtMost(src.size - 1)]
            val b = src[(k + 1).coerceAtMost(src.size - 1)]
            a + (b - a) * f
        }
    }

    /** A centred moving average over [w] samples (w = 1: unchanged). */
    private fun smooth(x: FloatArray, w: Int): FloatArray {
        if (w <= 1) return x
        // Prefix sums: the window [i - w/2, i - w/2 + w), clipped at both ends.
        val pre = DoubleArray(x.size + 1)
        for (i in x.indices) pre[i + 1] = pre[i] + x[i]
        val half = w / 2
        return FloatArray(x.size) { i ->
            val lo = (i - half).coerceAtLeast(0)
            val hi = (i - half + w).coerceAtMost(x.size)
            ((pre[hi] - pre[lo]) / (hi - lo).coerceAtLeast(1)).toFloat()
        }
    }

    /** Milliseconds of 16 kHz audio. */
    fun ms(samples: Int): Long = samples * 1000L / RATE
}
