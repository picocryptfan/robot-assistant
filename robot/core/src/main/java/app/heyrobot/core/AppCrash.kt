// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.time.ZoneId

// Pure (1.0). "Robot closed unexpectedly": the last crash or "not responding" of the app itself or of the assistant (the
// :voice process: the panel, the voice and the tools), from Android's records of how Robot's processes ended
// (ActivityManager.getHistoricalProcessExitReasons, ApplicationExitInfo), told once, the next time you open Robot, with
// Send feedback. The model's processes (:brain, :llama) have their own problem reports (ModelProblem); a memory kill, an
// update, a stop you asked for or Robot ending a process itself is not a crash. The report is built like the problem
// report (ProblemReport): codes, Robot's version, the phone and its Android build, Android's record (a native crash's
// frames as library names and offsets, core Tombstone) and the voice log; never anything you said, typed or kept.

/** One of Android's records: [process] is the part of the process name after ':' ("main" for the app's own). */
data class AppExit(val process: String, val facts: ExitFacts)

object AppCrash {
    /** ApplicationExitInfo's REASON_CRASH, REASON_CRASH_NATIVE and REASON_ANR. */
    const val REASON_CRASH = 4
    const val REASON_CRASH_NATIVE = 5
    const val REASON_ANR = 6

    /** The processes whose crash closes what you see: the app, and the assistant. */
    val PROCESSES = setOf("main", "voice")

    /** "main" for [pkg]'s own process, the part after ':' for one of its others, null for another app's. */
    fun processOf(name: String?, pkg: String): String? = when {
        name == null -> null
        name == pkg -> "main"
        name.startsWith("$pkg:") -> name.substringAfter(':')
        else -> null
    }

    fun unexpected(reason: Int): Boolean = reason == REASON_CRASH || reason == REASON_CRASH_NATIVE || reason == REASON_ANR

    /** The newest crash or "not responding" of the app or the assistant after [seen] (the last one told); null if none. */
    fun pick(exits: List<AppExit>, seen: Long): AppExit? =
        exits.filter { it.process in PROCESSES && unexpected(it.facts.reason) && it.facts.time > seen }.maxByOrNull { it.facts.time }

    /**
     * What the "seen" mark becomes after a look at [exits] with [seen] (0: never looked): on the very first look, the
     * newest record's time (or [now] when there is none), so crashes from before 1.0 or before this install aren't news.
     */
    fun firstSeen(exits: List<AppExit>, now: Long): Long = exits.maxOfOrNull { it.facts.time } ?: now

    private fun what(e: AppExit): String = if (e.process == "main") "Robot" else "Robot's assistant"

    fun headline(e: AppExit): String =
        if (e.facts.reason == REASON_ANR) "${what(e)} stopped responding, and Android closed it." else "${what(e)} closed unexpectedly."

    const val TITLE = "Robot closed unexpectedly"

    fun text(e: AppExit, zone: ZoneId = ZoneId.systemDefault()): String =
        headline(e) + " (" + ProblemReport.time(e.facts.time, zone) + ")\n\n" +
            "Send feedback writes a bug report with a crash report of codes only: Android's record of how Robot ended, this " +
            "phone and the voice log. You read it before you send it, and Robot sends nothing itself."

    /** The crash report, as you'll see it in Send feedback before you send it. */
    fun report(e: AppExit, f: ReportFacts, now: Long, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        append("Robot crash report\n")
        append(headline(e)).append('\n')
        append('\n')
        append("When: ").append(ProblemReport.time(e.facts.time, zone)).append('\n')
        append("Robot: ").append(f.robotVersion).append(" (").append(f.versionCode).append(")\n")
        append(ProblemReport.phoneLines(f))
        append("Process: ").append(if (e.process == "main") "the app (main)" else "the assistant (:${e.process})").append('\n')
        append('\n')
        append("How it ended (Android's record):\n")
        append(ProblemReport.exitLines(e.facts, zone))
        for (x in f.exits.filter { it.time != e.facts.time || it.pid != e.facts.pid }) append(ProblemReport.exitLines(x, zone, " (another)"))
        append('\n')
        append(ProblemReport.logAndEnd(f, now, zone))
    }
}
