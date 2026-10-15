// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

// Pure. Settings › Conversation › Test translation speed, the phone gate of phase 5.0(b) (docs/conversation-mode.md 9.1):
// the fr-CA test set (tools/lang/fr-CA/mt-test.tsv, also an asset of this module) through the :mt process, timed line by
// line in the engine, with the :mt process's memory, and each output compared with the reference the build machine got
// from the same engine and files (the set's "fast engine output" column, tools/build_robotmt.sh --check). Passes when
// the median is at most 60 ms a line, the peak memory at most 450 MB with both directions loaded, and every output is
// the same as the reference.

/** One line of a translation test set: [reference] the fast engine's output on the build machine, [bergamot] the bergamot wheel's. */
data class MtTestLine(val direction: String, val source: String, val bergamot: String, val reference: String)

object MtTestSet {
    /** A copy of tools/lang/fr-CA/mt-test.tsv (MtSpeedTest keeps the two the same). */
    const val ASSET = "mt/fr-CA-test.tsv"

    /**
     * The test lines of a set (comments start with "#"): direction, source, meaning, Bergamot output, verdict, Gemma
     * output, verdict, fast engine output. A line without the last column has no reference and is left out.
     */
    fun parse(tsv: String): List<MtTestLine> = tsv.lines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { l ->
        val c = l.split('\t')
        if (c.size < 8 || c[7].isEmpty() || MtBundle.forDirection(c[0]) == null) null else MtTestLine(c[0], c[1], c[3], c[7])
    }
}

/** One test line's result: [ms] in the engine, [output] null when it failed. */
data class MtSpeedLine(val direction: String, val ms: Long, val output: String?, val same: Boolean, val sameAsBergamot: Boolean)

data class MtSpeedReport(
    val at: Long,
    /** Load time per direction ("fr-en" to ms); empty when the engine was loaded already. */
    val loadMs: Map<String, Long>,
    val lines: List<MtSpeedLine>,
    /** The :mt process's memory once every line is done: now and its peak. */
    val memory: MtMemory,
    /** Why the test couldn't run (then [lines] is empty or partial). */
    val error: MtError? = null,
) {
    val times: List<Long> get() = lines.filter { it.output != null }.map { it.ms }
    val medianMs: Long get() = median(times)
    val maxMs: Long get() = times.maxOrNull() ?: 0
    val same: Int get() = lines.count { it.same }
    val sameAsBergamot: Int get() = lines.count { it.sameAsBergamot }
    val peakMb: Long get() = if (memory.peakKb < 0) -1 else memory.peakKb / 1024
    val rssMb: Long get() = if (memory.rssKb < 0) -1 else memory.rssKb / 1024

    val speedOk: Boolean get() = error == null && times.isNotEmpty() && medianMs <= MEDIAN_GATE_MS
    val memoryOk: Boolean get() = error == null && peakMb in 0..RSS_GATE_MB
    val outputsOk: Boolean get() = error == null && lines.isNotEmpty() && same == lines.size
    val passed: Boolean get() = speedOk && memoryOk && outputsOk

    /** The result in plain lines: the three parts of the gate, then the load. */
    fun summary(total: Int): List<String> {
        error?.let { return listOf(it.text()) }
        fun mark(ok: Boolean) = if (ok) "passes" else "misses"
        return listOf(
            "Speed: median $medianMs ms, slowest $maxMs ms a line (the mark is $MEDIAN_GATE_MS ms or less): ${mark(speedOk)}.",
            "Memory: ${if (peakMb >= 0) "$peakMb MB at most, $rssMb MB now" else "unknown"} with both directions loaded " +
                "(the mark is $RSS_GATE_MB MB or less): ${mark(memoryOk)}.",
            "Translations: $same of $total the same as on the build machine (the mark is all of them): ${mark(outputsOk && lines.size == total)}.",
            if (loadMs.isEmpty()) "The engine was loaded already." else "Loaded in " + loadMs.entries.joinToString(", ") { "${it.value} ms (${it.key})" } + ".",
        )
    }

    /** The report as text for Copy: the summary, then each line that wasn't the same as the reference. */
    fun plain(set: List<MtTestLine>, header: String): String = buildString {
        append(header).append('\n')
        summary(set.size).forEach { append(it).append('\n') }
        append("Also the same as the bergamot 0.4.5 wheel's phase-4 outputs: $sameAsBergamot of ${set.size} (another build of the engine; not part of the mark).\n")
        lines.forEachIndexed { i, l ->
            val t = set.getOrNull(i) ?: return@forEachIndexed
            append(String.format(Locale.US, "%2d. %s %4d ms %s\n", i + 1, l.direction, l.ms, if (l.same) "same" else "DIFFERENT"))
            if (!l.same) {
                append("    source:   ").append(t.source).append('\n')
                append("    expected: ").append(t.reference).append('\n')
                append("    got:      ").append(l.output ?: "(no translation)").append('\n')
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("at", at)
        put("loadMs", JSONObject().apply { loadMs.forEach { (k, v) -> put(k, v) } })
        put("lines", JSONArray().apply {
            lines.forEach { put(JSONObject().put("dir", it.direction).put("ms", it.ms).put("out", it.output ?: JSONObject.NULL).put("same", it.same).put("bergamot", it.sameAsBergamot)) }
        })
        put("memory", memory.toJson())
        error?.let { put("error", it.name) }
    }.toString()

    companion object {
        const val MEDIAN_GATE_MS = 60L
        const val RSS_GATE_MB = 450L

        fun median(xs: List<Long>): Long {
            if (xs.isEmpty()) return 0
            val s = xs.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** The report for [set] given the engine's [outputs] and [ms], line by line in the set's order. */
        fun of(set: List<MtTestLine>, outputs: List<String?>, ms: List<Long>, loadMs: Map<String, Long>, memory: MtMemory, at: Long): MtSpeedReport =
            MtSpeedReport(at, loadMs, set.mapIndexed { i, t ->
                val out = outputs.getOrNull(i)
                MtSpeedLine(t.direction, ms.getOrElse(i) { 0 }, out, out != null && out == t.reference, out != null && out == t.bergamot)
            }, memory)

        fun failed(error: MtError, at: Long): MtSpeedReport = MtSpeedReport(at, emptyMap(), emptyList(), MtMemory.UNKNOWN, error)

        fun fromJson(s: String?): MtSpeedReport? = try {
            val o = JSONObject(s ?: return null)
            val lm = o.optJSONObject("loadMs") ?: JSONObject()
            val a = o.optJSONArray("lines") ?: JSONArray()
            MtSpeedReport(
                o.getLong("at"),
                lm.keys().asSequence().toList().sorted().associateWith { lm.getLong(it) },
                (0 until a.length()).map { i ->
                    val l = a.getJSONObject(i)
                    MtSpeedLine(l.getString("dir"), l.getLong("ms"), if (l.isNull("out")) null else l.getString("out"), l.getBoolean("same"), l.optBoolean("bergamot"))
                },
                MtMemory.of(o.optJSONObject("memory")),
                MtError.of(o.optString("error").takeIf { o.has("error") }),
            )
        } catch (_: Exception) {
            null
        }
    }
}
