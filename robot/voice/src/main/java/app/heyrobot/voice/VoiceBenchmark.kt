package app.heyrobot.voice

import android.content.Context
import app.heyrobot.core.BenchState
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsBench
import app.heyrobot.core.VoiceBench
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.writeAtomic
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// VOICE PROCESS. Settings › Voice › "Test voice speed" (docs/voice-plan.md 4.14): load each downloaded model
// afresh and time it on fixed inputs, so Kokoro vs Kitten can be decided on the phone. Speech recognition decodes
// the built-in clip (synthetic speech made on the build machine by tools/make_test_clip.py: 5.3 s, 16 kHz, raw
// 16-bit, in the APK); each voice synthesises a fixed 5-word first chunk and a 16-word sentence without playing
// them. Results go to voice_bench.json for the settings screen; the log gets the numbers only.

internal object VoiceBenchmark {
    const val FIRST_CHUNK = "Sure, here is the weather."
    const val SENTENCE = "The weather today is sunny with a light breeze, and it should stay warm until the evening."

    fun clip(ctx: Context): FloatArray {
        val b = ctx.assets.open("bench/test-clip.s16").use { it.readBytes() }
        val s = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(s.remaining()) { s.get(it) / 32768f }
    }

    fun run(ctx: Context, stt: SherpaSpeechToText, tts: SherpaTextToSpeech, out: File) {
        val started = System.currentTimeMillis()
        fun write(b: VoiceBench) = try { writeAtomic(out, b.toJson().toString()) } catch (t: Throwable) { RLog.w("voice_bench_write", t) }
        try {
            val voices = listOf(VoiceChoice.HEART, VoiceChoice.KITTEN).filter { tts.usable(it) }
            if (!stt.usable && voices.isEmpty()) {
                write(VoiceBench(BenchState.FAILED, "Download the voice models first (Model › Voice models).", started))
                return
            }
            var res = VoiceBench(BenchState.RUNNING, "Loading speech recognition and writing down a test sentence…", started)
            if (stt.usable) {
                write(res)
                val expected = ctx.assets.open("bench/test-clip.txt").use { it.readBytes().toString(Charsets.UTF_8) }.trim()
                val b = stt.bench(clip(ctx), expected)
                    ?: return write(VoiceBench(BenchState.FAILED, "Robot was listening, or speech recognition couldn't load. Try again in a moment.", started))
                res = res.copy(stt = b)
                RLog.i("voice_bench", "part" to RLog.Code("stt"), "loadMs" to b.loadMs, "clipMs" to b.clipMs, "decodeMs" to b.decodeMs,
                    "partialMs" to b.partialMs, "rtf" to b.rtf, "wordAccuracy" to b.wordAccuracy)
            }
            val done = mutableListOf<TtsBench>()
            for (v in voices) {
                res = res.copy(step = "Loading the voice “${v.title}” and timing it…", voices = done.toList())
                write(res)
                val b = tts.bench(v, FIRST_CHUNK, SENTENCE)
                    ?: return write(res.copy(state = BenchState.FAILED, step = "Robot was speaking, or the voice “${v.title}” couldn't load. Try again in a moment."))
                done += b
                RLog.i("voice_bench", "part" to RLog.Code(v.id), "loadMs" to b.loadMs, "firstChunkMs" to b.firstChunkMs,
                    "sentenceFirstMs" to b.sentenceFirstMs, "audioMs" to b.audioMs, "genMs" to b.genMs, "rtf" to b.rtf)
            }
            val rss = rssMb()
            write(res.copy(state = BenchState.DONE, step = "", voices = done.toList(), rssMb = rss))
            RLog.i("voice_bench", "part" to RLog.Code("done"), "rssMb" to rss)
        } catch (t: Throwable) {
            RLog.w("voice_bench", t)
            write(VoiceBench(BenchState.FAILED, "The test stopped with an error (${t.javaClass.simpleName}).", started))
        }
    }

    /** VmRSS of this process from /proc/self/status, in MB, or -1. */
    private fun rssMb(): Int = try {
        File("/proc/self/status").readLines().firstOrNull { it.startsWith("VmRSS:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()?.let { (it / 1024).toInt() } ?: -1
    } catch (_: Exception) { -1 }
}
