package app.heyrobot.core.termux

import app.heyrobot.core.LogCode

// Pure. What the voice log will record about Termux runs (docs/plans/websearch-termux-design.md B9): codes and small
// numbers only, never the command, a path, an argument, a script name or output.
//
// Voice log ids 170–179 are the Termux agent's (VoiceEvent.TERMUX_SENT to TERMUX_LATE, 0.9):
//   170 TERMUX_SENT    a = [TermuxRunKind], b = time limit in s
//   171 TERMUX_DONE    a = [TermuxExitBucket], b = ms, c = [TermuxLog.kbBucket] of the output × 2, + 1 when Termux cut it
//   172 TERMUX_FAILED  a = [TermuxFailure]
//   173 TERMUX_LATE    a result for a run Robot no longer waits for (its output dropped): a = [TermuxExitBucket]
//   174 TERMUX_UPDATE_CHECK (1.0, main process) a = [TermuxUpdateVerdict], b = the installed one's [TermuxSource] (ordinal + 1)
//   175 TERMUX_LOCKED  (1.0) a call refused by the update lock, another signer or the test key: a = [TermuxLock]
//   176 TERMUX_TRUST   (1.0, main process) a = [TermuxTrustOp], b = [TermuxSource] (ordinal + 1)
//   177 TERMUX_ADB_FIX (1.0) an ADB fix sent through Shizuku: a = its number, b = ShizukuOutcome, c = ShizukuRefusal or TermuxFailure
//   178–179            free for the agent

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
    /** Settings › Termux: creating ~/robot, or listing your scripts in it (fixed commands, shown before the first run). */
    SETUP(7, "set-up step"),
    /** 1.0, Settings › Advanced › ADB fixes: a fixed command sent as rish -c '…' after a tap. */
    SHIZUKU(8, "ADB fix through Shizuku"),
}

/** 1.0, TERMUX_TRUST's a: what you did in Settings › Termux about which Termux Robot trusts. */
enum class TermuxTrustOp(override val id: Int, override val title: String) : LogCode {
    TRUSTED(1, "trusted this Termux"),
    VERSION(2, "trusted this version"),
    RISK_ON(3, "chose Run anyway (test key)"),
    RISK_OFF(4, "turned Run anyway off"),
    SET_UP_AGAIN(5, "set up again (another signer)"),
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
