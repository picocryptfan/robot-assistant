// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.ask

import app.heyrobot.core.Pcm

// Pure. A shared voice message's decoded audio as it comes out of Android's decoder (any rate, any channel count, 16-bit
// or float), turned into what the speech model takes (16 kHz mono floats) chunk by chunk (E7, D20). Pcm.mono and
// Pcm.resample work on a whole clip: three minutes of 48 kHz stereo held whole, then smoothed through a prefix sum, is
// well over 100 MB in :voice beside the speech models. Here only the 16 kHz result is kept, at most [maxSamples] of it.
//
// Going down, each output sample is the mean of the input samples that fall in its slot (a box filter: enough for
// speech, whose features Parakeet takes below 8 kHz). Going up (an 8 kHz AMR message), linear interpolation.

class PcmSink(private val maxSamples: Int) {
    private var out = FloatArray(minOf(maxSamples, Pcm.RATE * 10).coerceAtLeast(1))
    private var n = 0
    private var rate = 0
    private var channels = 0
    /** Input samples per output sample. */
    private var step = 1.0
    private var acc = 0.0
    private var count = 0
    private var phase = 0.0
    /** Going up: the last input sample, and where between it and the next the next output falls. */
    private var prev = Float.NaN
    private var t = 0.0

    /** Every sample it may hold is taken: the decoder can stop. */
    val full: Boolean get() = n >= maxSamples

    /** Seconds of 16 kHz audio taken so far. */
    val seconds: Int get() = n / Pcm.RATE

    /**
     * The decoder's output format, before its first samples and whenever it changes. False (and nothing taken until a
     * good one) outside 8 to 192 kHz or 1 to 8 channels.
     */
    fun format(sampleRate: Int, channelCount: Int): Boolean {
        if (sampleRate !in MIN_RATE..MAX_RATE || channelCount !in 1..MAX_CHANNELS) {
            rate = 0
            return false
        }
        if (sampleRate != rate) {
            acc = 0.0
            count = 0
            phase = 0.0
            prev = Float.NaN
            t = 0.0
        }
        rate = sampleRate
        channels = channelCount
        step = rate.toDouble() / Pcm.RATE
        return true
    }

    /** Interleaved little-endian 16-bit samples, [len] bytes from [off]. */
    fun s16(b: ByteArray, off: Int = 0, len: Int = b.size - off) {
        if (rate == 0) return
        val frame = 2 * channels
        var i = off
        val end = off + len - len % frame
        while (i < end && !full) {
            var s = 0f
            for (c in 0 until channels) {
                val lo = b[i + 2 * c].toInt() and 0xff
                val hi = b[i + 2 * c + 1].toInt()
                s += ((hi shl 8) or lo).toShort() / 32768f
            }
            take(s / channels)
            i += frame
        }
    }

    /** Interleaved float samples, [len] of them from [off]. */
    fun floats(x: FloatArray, off: Int = 0, len: Int = x.size - off) {
        if (rate == 0) return
        var i = off
        val end = off + len - len % channels
        while (i < end && !full) {
            var s = 0f
            for (c in 0 until channels) s += x[i + c].let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
            take(s / channels)
            i += channels
        }
    }

    /** What was taken, as 16 kHz mono (a partly filled last slot included). */
    fun finish(): FloatArray {
        if (count > 0 && step > 1.0 && !full) put((acc / count).toFloat())
        count = 0
        acc = 0.0
        return out.copyOf(n)
    }

    private fun take(s: Float) {
        when {
            step == 1.0 -> put(s)
            step > 1.0 -> {
                acc += s
                count++
                phase += 1.0
                if (phase >= step) {
                    put((acc / count).toFloat())
                    acc = 0.0
                    count = 0
                    phase -= step
                }
            }
            else -> {
                val p = if (prev.isNaN()) s else prev
                while (t < 1.0 && !full) {
                    put(p + (s - p) * t.toFloat())
                    t += step
                }
                t -= 1.0
                prev = s
            }
        }
    }

    private fun put(v: Float) {
        if (n >= maxSamples) return
        if (n == out.size) out = out.copyOf(minOf(maxSamples, out.size * 2))
        out[n++] = v
    }

    companion object {
        const val MIN_RATE = 8_000
        const val MAX_RATE = 192_000
        const val MAX_CHANNELS = 8
    }
}
