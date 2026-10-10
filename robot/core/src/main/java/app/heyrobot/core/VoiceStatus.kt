package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// Pure. What the :voice process reports to the main app about its speech engines: two small JSON files it writes
// (voice_engines.json, voice_bench.json in filesDir/robot) and the main app reads once a second while a screen
// shows them; the requests go the other way as Cmd broadcasts (voice_models, voice_reset, voice_bench). Numbers,
// states and fixed identifiers only: nothing that was said or read aloud.

/** The files the :voice process writes in filesDir/robot for the main app. */
object VoiceFiles {
    const val ENGINES = "voice_engines.json"
    const val BENCH = "voice_bench.json"
    /** The voice log (core VoiceLog.kt), written by the :voice process only. */
    const val LOG = "voice_log.txt"
    /** The barge-in test's result (core BargeIn.kt). */
    const val BARGE = "voice_barge_test.json"
}

/** One speech engine (a downloaded model bundle) as the :voice process sees it. */
enum class EngineState {
    /** The model is not downloaded (or not verified): the placeholder runs instead. */
    MISSING,
    /** Downloaded and verified, not loaded yet (it loads on first use). */
    IDLE,
    LOADING,
    READY,
    /** Loading threw an error in this process; it is tried again after "Try again" or new files. */
    FAILED,
    /** The voice process died twice in a row while loading it: off until "Try again". */
    BROKEN;

    /** May the real engine be used (else the placeholder)? */
    val usable: Boolean get() = this == IDLE || this == LOADING || this == READY

    fun text(): String = when (this) {
        MISSING -> "Not downloaded"
        IDLE -> "Ready (loads when first used)"
        LOADING -> "Loading"
        READY -> "Loaded"
        FAILED -> "Couldn't start. Robot uses the stand-in for now."
        BROKEN -> "Turned off after it crashed twice while loading. Robot uses the stand-in."
    }
}

data class VoiceEngineStatus(val engines: Map<VoiceBundle, EngineState>, val updated: Long) {
    fun state(b: VoiceBundle): EngineState = engines[b] ?: EngineState.MISSING

    fun toJson(): JSONObject = JSONObject().apply {
        put("updated", updated)
        put("engines", JSONObject().apply { for ((b, s) in engines) put(b.id, s.name) })
    }

    companion object {
        fun fromJson(text: String?): VoiceEngineStatus? = try {
            val o = JSONObject(text ?: return null)
            val e = o.optJSONObject("engines") ?: JSONObject()
            VoiceEngineStatus(VoiceBundle.entries.filter { e.has(it.id) }.associateWith { enumOr(e.optString(it.id), EngineState.MISSING) }, o.optLong("updated"))
        } catch (_: Exception) { null }
    }
}

/** Speech recognition on the built-in clip: load, median of 3 decodes, one partial-sized decode, word accuracy. */
data class SttBench(val loadMs: Long, val clipMs: Long, val decodeMs: Long, val partialMs: Long, val wordAccuracy: Int) {
    val rtf: Double get() = if (clipMs > 0) decodeMs.toDouble() / clipMs else Double.NaN
}

/** One voice: load, time to first audio for a 5-word first chunk and a 16-word sentence, and the sentence's speed. */
data class TtsBench(val voice: VoiceChoice, val loadMs: Long, val firstChunkMs: Long, val sentenceFirstMs: Long, val audioMs: Long, val genMs: Long) {
    val rtf: Double get() = if (audioMs > 0) genMs.toDouble() / audioMs else Double.NaN
}

enum class BenchState { RUNNING, DONE, FAILED }

data class VoiceBench(
    val state: BenchState,
    /** What runs now, in plain words (RUNNING), or why it failed (FAILED). */
    val step: String,
    val started: Long,
    val stt: SttBench? = null,
    val voices: List<TtsBench> = emptyList(),
    /** Resident memory of the voice process at the end (MB), or -1. */
    val rssMb: Int = -1,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("state", state.name); put("step", step); put("started", started); put("rssMb", rssMb)
        stt?.let { put("stt", JSONObject().put("loadMs", it.loadMs).put("clipMs", it.clipMs).put("decodeMs", it.decodeMs).put("partialMs", it.partialMs).put("wordAccuracy", it.wordAccuracy)) }
        put("voices", JSONArray().apply {
            voices.forEach {
                put(JSONObject().put("voice", it.voice.id).put("loadMs", it.loadMs).put("firstChunkMs", it.firstChunkMs)
                    .put("sentenceFirstMs", it.sentenceFirstMs).put("audioMs", it.audioMs).put("genMs", it.genMs))
            }
        })
    }

    companion object {
        fun fromJson(text: String?): VoiceBench? = try {
            val o = JSONObject(text ?: return null)
            val s = o.optJSONObject("stt")
            val v = o.optJSONArray("voices") ?: JSONArray()
            VoiceBench(
                state = enumOr(o.optString("state"), BenchState.FAILED),
                step = o.optString("step"),
                started = o.optLong("started"),
                stt = s?.let { SttBench(it.getLong("loadMs"), it.getLong("clipMs"), it.getLong("decodeMs"), it.getLong("partialMs"), it.getInt("wordAccuracy")) },
                voices = (0 until v.length()).map { i ->
                    val x = v.getJSONObject(i)
                    TtsBench(VoiceChoice.of(x.getString("voice")), x.getLong("loadMs"), x.getLong("firstChunkMs"), x.getLong("sentenceFirstMs"), x.getLong("audioMs"), x.getLong("genMs"))
                },
                rssMb = o.optInt("rssMb", -1),
            )
        } catch (_: Exception) { null }
    }
}

object VoiceBenchLogic {
    /** docs/voice-plan.md 1, the decision rule: Kitten becomes the default if Kokoro's RTF > 0.7 or its first chunk takes > 1.2 s. */
    const val MAX_KOKORO_RTF = 0.7
    const val MAX_FIRST_CHUNK_MS = 1_200L

    fun kokoroTooSlow(k: TtsBench): Boolean = !(k.rtf <= MAX_KOKORO_RTF) || k.firstChunkMs > MAX_FIRST_CHUNK_MS

    /** Plain-language lines for the settings screen. */
    fun lines(b: VoiceBench): List<String> {
        val out = mutableListOf<String>()
        b.stt?.let {
            out += String.format(Locale.US, "Speech recognition: %.1f s of speech written down in %.2f s (real-time factor %.2f), %d%% of the words right. Loading took %.1f s.",
                it.clipMs / 1000.0, it.decodeMs / 1000.0, it.rtf, it.wordAccuracy, it.loadMs / 1000.0)
        }
        for (v in b.voices) {
            out += String.format(Locale.US, "%s: first words after %.2f s (a whole sentence: %.2f s), real-time factor %.2f. Loading took %.1f s.",
                v.voice.title, v.firstChunkMs / 1000.0, v.sentenceFirstMs / 1000.0, v.rtf, v.loadMs / 1000.0)
        }
        b.voices.firstOrNull { it.voice.bundle == VoiceBundle.KOKORO }?.let { k ->
            out += if (kokoroTooSlow(k)) "Kokoro is slow on this phone: pick Kitten for quicker replies."
            else "Kokoro is fast enough on this phone."
        }
        if (b.rssMb > 0) out += "Memory used by the voice process: ${b.rssMb} MB."
        return out
    }
}

/** Word accuracy (1 − word error rate, floored at 0) for the benchmark clip; case and punctuation do not count. */
object WordAccuracy {
    fun words(s: String): List<String> = s.lowercase(Locale.ROOT).map { if (it.isLetterOrDigit() || it == '\'') it else ' ' }.joinToString("").split(' ').filter { it.isNotEmpty() }

    fun percent(reference: String, hypothesis: String): Int {
        val r = words(reference)
        val h = words(hypothesis)
        var prev = IntArray(h.size + 1) { it }
        for (i in 1..r.size) {
            val cur = IntArray(h.size + 1)
            cur[0] = i
            for (j in 1..h.size) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (r[i - 1] == h[j - 1]) 0 else 1)
            prev = cur
        }
        val errors = prev[h.size]
        return maxOf(0, r.size - errors) * 100 / maxOf(1, r.size)
    }
}
