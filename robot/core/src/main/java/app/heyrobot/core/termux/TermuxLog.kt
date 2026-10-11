package app.heyrobot.core.termux

import app.heyrobot.core.LogCode

// Pure. What the voice log will record about Termux runs (docs/plans/websearch-termux-design.md B9): codes and small
// numbers only, never the command, a path, an argument, a script name or output.
//
// Voice log ids 170–179 are reserved for the Termux agent. They are not in VoiceEvent yet (VoiceLog.kt is shared; they
// are added there when the agent is wired, after confirming the block is still free):
//   170 TERMUX_SENT    a = [TermuxRunKind], b = time limit in s
//   171 TERMUX_DONE    a = [TermuxExitBucket], b = ms, c = [TermuxLog.kbBucket] of the output × 2, + 1 when Termux cut it
//   172 TERMUX_FAILED  a = [TermuxFailure]
//   173 TERMUX_LATE    a result for a run Robot no longer waits for (its output dropped): a = [TermuxExitBucket]
//   174–179            free for the agent

object TermuxLog {
    const val FIRST_ID = 170
    const val LAST_ID = 179
    const val SENT = 170
    const val DONE = 171
    const val FAILED = 172
    const val LATE = 173

    /** The output's size in KB, rounded up to 0, 1, 2, 4, 8, 16, 32, 64 or 100 (Termux's cap). */
    fun kbBucket(bytes: Long): Int {
        if (bytes <= 0) return 0
        val kb = (bytes + 1023) / 1024
        return listOf(1, 2, 4, 8, 16, 32, 64).firstOrNull { kb <= it } ?: 100
    }

    /** TERMUX_DONE's c: the size bucket, doubled, plus 1 when Termux cut the output. */
    fun doneC(bytes: Long, truncated: Boolean): Int = kbBucket(bytes) * 2 + if (truncated) 1 else 0
}

/** TERMUX_SENT's a: what kind of run. */
enum class TermuxRunKind(override val id: Int, override val title: String) : LogCode {
    SCRIPT(1, "your script"),
    ALLOWLISTED(2, "read-only command, no card"),
    CARDED(3, "command after its card"),
    STOP(4, "stop"),
    TEST(5, "set-up test"),
    TERMINAL(6, "opened in Termux"),
}

/** TERMUX_DONE's a: the exit code, bucketed. */
enum class TermuxExitBucket(override val id: Int, override val title: String) : LogCode {
    OK(0, "exit 0"),
    ERROR(1, "exit 1–125"),
    TIMEOUT(2, "exit 124 (time limit)"),
    NOT_RUN(3, "exit 126/127 (not found or not executable)"),
    STOPPED(4, "exit 137/143 (stopped)"),
    OTHER(5, "other exit");

    companion object {
        fun of(exit: Int): TermuxExitBucket = when (exit) {
            0 -> OK
            124 -> TIMEOUT
            126, 127 -> NOT_RUN
            137, 143 -> STOPPED
            in 1..125 -> ERROR
            else -> OTHER
        }
    }
}
