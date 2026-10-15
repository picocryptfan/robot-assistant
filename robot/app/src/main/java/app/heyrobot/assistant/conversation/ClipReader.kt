// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import java.nio.ByteOrder

// VOICE PROCESS. The Transcription check's "Import clips" (docs/conversation-mode.md 2.5): an audio file you picked
// (through Android's file picker, so Robot needs no storage permission) decoded with Android's own codecs to 16 kHz
// mono, in memory only. Nothing is written; the clip is dropped once scored.

object ClipReader {
    /** A clip longer than this is cut (the check is about short lines). */
    const val MAX_SECONDS = 60

    /** The clip at [uri] as 16 kHz mono floats, or null when Android can't decode it. */
    fun read(ctx: Context, uri: Uri): FloatArray? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            ex.setDataSource(ctx, uri, null)
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val out = ArrayList<FloatArray>()
            var samples = 0L
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var pcmFloat = false
            val limit = MAX_SECONDS.toLong() * 48_000 * 2
            while (!outputDone) {
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0) {
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        rate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == android.media.AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val x = if (pcmFloat) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                FloatArray(fb.remaining()).also { fb.get(it) }
                            } else {
                                val b = ByteArray(info.size)
                                buf.get(b)
                                Pcm.fromS16(b)
                            }
                            out += x
                            samples += x.size
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || samples > limit) outputDone = true
                    }
                }
            }
            val all = FloatArray(samples.toInt())
            var k = 0
            for (x in out) { System.arraycopy(x, 0, all, k, x.size); k += x.size }
            val mono = Pcm.mono(all, channels)
            val pcm = Pcm.resample(mono, rate)
            return if (pcm.size > MAX_SECONDS * Pcm.RATE) pcm.copyOf(MAX_SECONDS * Pcm.RATE) else pcm
        } catch (t: Throwable) {
            RLog.w("check_clip", t)
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            ex.release()
        }
    }

    /** The file's name as the picker shows it (for pairing it with its line). */
    fun name(ctx: Context, uri: Uri): String = try {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: uri.lastPathSegment ?: "clip"
    } catch (_: Throwable) { uri.lastPathSegment ?: "clip" }

    /** A small text file you picked (the reference lines), at most 1 MB. */
    fun text(ctx: Context, uri: Uri): String? = try {
        ctx.contentResolver.openInputStream(uri)?.use { s ->
            val b = s.readNBytes(1 shl 20)
            b.toString(Charsets.UTF_8)
        }
    } catch (t: Throwable) {
        RLog.w("check_text", t)
        null
    }
}
