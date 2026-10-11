package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.LangPair
import app.heyrobot.core.MtError
import app.heyrobot.core.MtException
import app.heyrobot.core.MtPriority
import app.heyrobot.core.MtSpeedReport
import app.heyrobot.core.MtTestLine
import app.heyrobot.core.MtTestSet
import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Conversation › Test translation speed, the phone gate of phase 5.0(b): the fr-CA test set
// (core's asset, the same as tools/lang/fr-CA/mt-test.tsv) through the :mt process, each line timed in the engine, then
// the :mt process's memory, compared with the build machine's outputs (core MtSpeedReport). Only the test's own
// sentences go to the engine; the result is kept in Robot's files until the next test.

object MtSpeedRun {
    /** A line per direction after loading, not counted: the first decode after a load is slower. */
    private val WARM_UP = mapOf("fr-en" to "Bonjour, comment ça va aujourd'hui?", "en-fr" to "Hello, how are you today?")
    /** The whole set's deadline per direction (23 lines at 60 ms are under 2 s; a slow phone still finishes). */
    private const val SET_DEADLINE_MS = 120_000L

    fun testSet(ctx: Context): List<MtTestLine> = try {
        MtTestSet.parse(ctx.assets.open(MtTestSet.ASSET).bufferedReader().use { it.readText() })
    } catch (_: Exception) { emptyList() }

    fun last(ctx: Context): MtSpeedReport? = MtSpeedReport.fromJson(readOrNull(Paths.mtSpeed(ctx)))

    /** Runs the test through [link] (which stays bound, so `dumpsys meminfo` can be read with the engine loaded). */
    suspend fun run(ctx: Context, link: MtLink, step: (String) -> Unit): MtSpeedReport {
        val set = testSet(ctx)
        val now = System.currentTimeMillis()
        val r = try {
            if (set.isEmpty()) throw MtException(MtError.BAD_REQUEST)
            step("Loading the engine…")
            val loaded = link.load(LangPair.FR_EN)
            step("Warming up…")
            for ((dir, line) in WARM_UP) link.translate(dir, listOf(line), SET_DEADLINE_MS, MtPriority.INTERACTIVE)
            val out = arrayOfNulls<String>(set.size)
            val ms = LongArray(set.size)
            for (dir in set.map { it.direction }.distinct()) {
                val idx = set.indices.filter { set[it].direction == dir }
                step("Translating ${idx.size} lines, $dir…")
                val t = link.translate(dir, idx.map { set[it].source }, SET_DEADLINE_MS, MtPriority.INTERACTIVE)
                idx.forEachIndexed { k, i ->
                    out[i] = t.out.getOrNull(k)
                    ms[i] = t.ms.getOrElse(k) { 0L }
                }
            }
            MtSpeedReport.of(set, out.toList(), ms.toList(), loaded.ms, link.status().memory, now)
        } catch (e: MtException) {
            MtSpeedReport.failed(e.error, now)
        }
        RLog.i("mt_speed", "ok" to r.passed, "median" to r.medianMs, "max" to r.maxMs, "peakMb" to r.peakMb, "same" to r.same,
            "error" to (r.error ?: RLog.Code("none")))
        // A test stopped by leaving the screen doesn't replace the last result.
        if (r.error != MtError.CANCELLED) withContext(Dispatchers.IO) {
            try { writeAtomic(Paths.mtSpeed(ctx), r.toJson()) } catch (t: Throwable) { RLog.w("mt_speed_save", t) }
        }
        return r
    }
}
