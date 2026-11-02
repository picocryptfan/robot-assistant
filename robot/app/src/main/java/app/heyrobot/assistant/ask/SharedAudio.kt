// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.ask

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import app.heyrobot.core.Pcm
import app.heyrobot.core.RLog
import app.heyrobot.core.ask.AskCaps
import app.heyrobot.core.ask.PcmSink
import java.nio.ByteOrder

// VOICE PROCESS. Ask Robot's voice messages (docs/plans/everyday-set-plan.md §7.4, D20; job E7): a shared audio file,
// already read into memory once (AskRobotActivity) and sniffed (core AudioSniff), decoded with Android's own extractor and
// codecs to 16 kHz mono, in memory only, then written down by Parakeet (ConversationEngine.transcribe).
//
// D20, E7's spike (23 Oct 2026): Android's decoders can't run in the isolated :files process, so this runs in :voice.
//   - PUBLISHED (AOSP main, read in source): system/sepolicy private/isolated_app_all.te lets an isolated process find only
//     activity_service, display_service and webviewupdate_service with the service manager, as a neverallow (a build that
//     loosens it fails, so no AOSP-based OS can, GrapheneOS included), and denies hwservice_manager outright. MediaExtractor
//     (NuMediaExtractor::initMediaExtractor -> MediaExtractorFactory::Create, frameworks/av) asks the "media.extractor"
//     service for its extractor ("media.stagefright.extractremote", default true); MediaCodec needs the codec services
//     (Codec2's HAL or "media.codec"). Neither is reachable from :files: setDataSource and createDecoderByType would fail.
//   - Not tested on the phone (no phone in E7). The check: a build whose FileParseService calls MediaExtractor.setDataSource
//     on a small OGG and logs only whether it threw; expect an IOException ("extractor service not running" in logcat).
//   - What runs where, then: :voice sniffs the first bytes, hands the bytes to Android through a MediaDataSource over this
//     array (no file, no URI kept), and gets PCM back. The container is parsed inside Android's own media.extractor
//     sandbox (the remote path above, PUBLISHED), and the software decoders (AAC, Opus, AMR, MP3, FLAC, Vorbis) run in
//     media.swcodec. :voice parses nothing of the file itself but the sniff; it reads the format's two numbers (checked by
//     PcmSink.format) and the PCM (capped by PcmSink). The choice and its reason go to E9's review and the audit.
// Caps: the bytes at most AskCaps.MAX_AUDIO_BYTES (read before this); at most AskCaps.MAX_SECONDS of the message (by its
// own timestamps: past them nothing more is fed to the decoder); the whole decode within AskCaps.DECODE_MS. ClipReader
// (the Transcription check's 60 s import) is left as it is.

object SharedAudio {
    /** [samples]: 16 kHz mono; [seconds]: the message's length as the file says it (or as decoded); [cut]: past the cap. */
    class Decoded(val samples: FloatArray, val seconds: Int, val cut: Boolean)

    /** [bytes] decoded, or null when Android can't (no audio track, a format out of bounds, a decoder that failed or hung). */
    fun decode(bytes: ByteArray, maxSeconds: Int = AskCaps.MAX_SECONDS, cancelled: () -> Boolean = { false }): Decoded? {
        val ex = MediaExtractor()
        var codec: MediaCodec? = null
        val started = SystemClock.elapsedRealtime()
        try {
            ex.setDataSource(InMemory(bytes))
            val track = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: return null
            val limitUs = maxSeconds * 1_000_000L
            val durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION).takeIf { it > 0 } else null
            val sink = PcmSink(maxSeconds * Pcm.RATE)
            if (!sink.format(fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE), fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) return null
            var pcmFloat = false
            val c = MediaCodec.createDecoderByType(mime)
            codec = c
            c.configure(fmt, null, null, 0)
            c.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var past = false
            var outputDone = false
            while (!outputDone) {
                if (cancelled() || SystemClock.elapsedRealtime() - started > AskCaps.DECODE_MS) return null
                if (!inputDone) {
                    val i = c.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        val buf = c.getInputBuffer(i) ?: return null
                        val n = ex.readSampleData(buf, 0)
                        val at = ex.sampleTime
                        if (n < 0 || at > limitUs) {
                            // The end, or past the cap: nothing after it reaches the decoder.
                            past = n >= 0
                            c.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            c.queueInputBuffer(i, 0, n, at.coerceAtLeast(0), 0)
                            ex.advance()
                        }
                    }
                }
                val o = c.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = c.outputFormat
                        if (!sink.format(f.getInteger(MediaFormat.KEY_SAMPLE_RATE), f.getInteger(MediaFormat.KEY_CHANNEL_COUNT))) return null
                        pcmFloat = f.containsKey(MediaFormat.KEY_PCM_ENCODING) && f.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    o >= 0 -> {
                        val buf = c.getOutputBuffer(o)
                        if (buf != null && info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            if (pcmFloat) {
                                val fb = buf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                                val x = FloatArray(fb.remaining()).also { fb.get(it) }
                                sink.floats(x)
                            } else {
                                val b = ByteArray(info.size).also { buf.get(it) }
                                sink.s16(b)
                            }
                        }
                        c.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0 || sink.full) outputDone = true
                    }
                }
            }
            val pcm = sink.finish()
            val longer = durationUs != null && durationUs > limitUs + 500_000L
            val seconds = durationUs?.let { ((it + 500_000L) / 1_000_000L).toInt() } ?: (pcm.size / Pcm.RATE)
            return Decoded(pcm, seconds.coerceAtLeast(pcm.size / Pcm.RATE), cut = past || sink.full || longer)
        } catch (t: Throwable) {
            RLog.w("ask_audio", t)
            return null
        } finally {
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            try { ex.release() } catch (_: Throwable) {}
        }
    }

    /** The bytes as Android's extractor reads them: from this array, never a file or the sender's URI. */
    private class InMemory(private val b: ByteArray) : MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position < 0 || position >= b.size) return -1
            val n = minOf(size.toLong(), b.size - position).toInt()
            System.arraycopy(b, position.toInt(), buffer, offset, n)
            return n
        }

        override fun getSize(): Long = b.size.toLong()
        override fun close() {}
    }
}
