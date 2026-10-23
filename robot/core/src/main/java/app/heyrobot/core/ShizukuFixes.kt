// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxStatus

// Pure (1.0). Settings › Advanced › ADB fixes without a computer: Shizuku (RikkaApps), started with Android's Wireless
// debugging, runs a command with ADB's shell rights; its "rish" exported into Termux runs one as `rish -c '<command>'`.
// Each fix's command is the adb one without "adb shell". With Termux set up and trusted (not the GitHub test-key build,
// not paused by the update lock), Robot can send exactly that fixed command to Termux after a tap on its card in
// Settings: only the commands of AdbFixes.ALL, by the fix's id, never one Gemma or a helper wrote, never on the lock
// screen. No Shizuku library: Robot only sends Termux the text below.

object ShizukuPath {
    /** Shizuku's package (RikkaApps), as its GitHub releases and IzzyOnDroid name it. */
    const val PACKAGE = "moe.shizuku.privileged.api"

    /** Settings asks the :voice process for this set-up run (Cmd.TERMUX's run, with the fix's id). */
    const val RUN = "shizuku"

    private const val ADB_SHELL = "adb shell " // l10n: not text (a shell command)

    /** [adb] as the phone's shell runs it ("adb shell settings put …" → "settings put …"); null when it isn't an adb shell command. */
    fun shell(adb: String): String? {
        val t = adb.trim()
        if (!t.startsWith(ADB_SHELL)) return null
        return t.removePrefix(ADB_SHELL).trim().takeIf { it.isNotEmpty() && '\n' !in it && '\u0000' !in it }
    }

    /** [s] as one single-quoted shell word: a ' inside becomes '\'' (so nothing in it is ever read by the shell). */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** "rish -c '<command>'": how rish runs [adb] in Termux; null when it isn't an adb shell command. */
    fun rish(adb: String): String? = shell(adb)?.let { "rish -c " + quote(it) }

    /** The fix's commands as rish runs them, one per line, for Copy (null for a fix with a command rish can't run). */
    fun rishLines(fix: AdbFix): List<String>? = fix.commands.map { rish(it) ?: return null }

    /**
     * The only thing "Run with Shizuku through Termux" may send: fix [fixId]'s fixed commands as rish runs them (joined
     * with "&&"), looked up by id in AdbFixes.ALL. Anything else (an unknown id, a command from anywhere else): null.
     */
    fun command(fixId: String?): String? {
        val fix = AdbFixes.ALL.firstOrNull { it.id == fixId } ?: return null
        return rishLines(fix)?.takeIf { it.isNotEmpty() }?.joinToString(" && ")
    }

    /** Fix [fixId]'s place in AdbFixes.ALL from 1 (the voice log's TERMUX_ADB_FIX a); 0 for none. */
    fun number(fixId: String?): Int = AdbFixes.ALL.indexOfFirst { it.id == fixId } + 1

    /** The fix to do first with Shizuku: once Robot may write that one setting, it sets its recognizer again by itself. */
    const val FIRST = "repair"

    /**
     * The line to paste in Termux after Shizuku › "Use Shizuku in terminal apps" › Export files saved rish and
     * rish_shizuku.dex in Termux's home: they go to $PREFIX/bin, rish is told it runs in Termux (Shizuku's script asks
     * for the terminal's package in place of "PKG"), and the dex is made read-only (Android 14 and later won't load a
     * writable one).
     */
    const val TERMUX_LINE = "mv ~/rish ~/rish_shizuku.dex \$PREFIX/bin/ && sed -i 's/\"PKG\"/\"com.termux\"/' \$PREFIX/bin/rish && " + // l10n: not text (shell for Termux)
        "chmod 700 \$PREFIX/bin/rish && chmod 400 \$PREFIX/bin/rish_shizuku.dex"

    /** Run once by hand in Termux: Shizuku asks whether Termux may use it (allow Termux, and only Termux). Prints uid=2000(shell). */
    const val FIRST_RUN = "rish -c id"
}

/** Why "Run with Shizuku through Termux" didn't send a fix (voice log TERMUX_ADB_FIX's c with [ShizukuOutcome.REFUSED]). */
enum class ShizukuRefusal(override val id: Int, override val title: String) : LogCode {
    NOT_A_FIX(1, "not one of the ADB fixes"),
    LOCKED(2, "phone locked"),
    TERMUX_NOT_READY(3, "Termux not set up and trusted"),
    TEST_KEY(4, "Termux's public test key"),
    TERMUX_CHANGED(5, "Termux changed since you trusted it"),
    /** 1.2: the recognizer fix while another installed speech recognizer is set: it would replace that one (AdbFixes.notNeeded). */
    NOT_NEEDED(6, "another speech recognizer is set"),
}

/** How a fix sent through Shizuku ended (voice log TERMUX_ADB_FIX's b). */
enum class ShizukuOutcome(override val id: Int, override val title: String) : LogCode {
    /** It ran, and Robot's check says the fix is done now. */
    APPLIED(1, "done"),
    /** It ran (exit 0), but the check says it isn't done. */
    NOT_APPLIED(2, "ran, not done"),
    /** Termux has no rish (exit 127): export it from Shizuku first. */
    NO_RISH(3, "rish not in Termux"),
    /** rish ran but failed: Shizuku not running, or Termux not allowed in it. */
    FAILED(4, "rish failed"),
    /** Termux couldn't run it at all (TermuxFailure in c). */
    TERMUX_FAILED(5, "Termux couldn't run it"),
    REFUSED(6, "not sent"),
}

/** What the last "Run with Shizuku through Termux" found (termux_status.json, written by :voice). */
data class ShizukuResult(val fixId: String, val at: Long, val outcome: ShizukuOutcome, val exit: Int? = null, val refusal: ShizukuRefusal? = null)

object ShizukuRun {
    /**
     * Whether Settings offers "Run with Shizuku through Termux": Termux set up ([ready]: trusted, the permission, a Test
     * that passed) and the installed one is trusted as it is, and not the GitHub build (not even with "Run anyway").
     */
    fun offered(check: TermuxCheck, ready: Boolean): Boolean = ready && check.status == TermuxStatus.READY

    /**
     * Why fix [fixId] isn't sent now, or null: it may be ([command] is then what goes). Checked again in :voice before it
     * goes. 1.2: [kept], what keeps Robot the assistant now (RecognizerRepair.kept): the recognizer fix isn't sent while
     * another installed recognizer is set.
     */
    fun refusal(fixId: String?, check: TermuxCheck, ready: Boolean, locked: Boolean, kept: RecognizerRepair.Kept): ShizukuRefusal? = when {
        ShizukuPath.command(fixId) == null -> ShizukuRefusal.NOT_A_FIX
        locked -> ShizukuRefusal.LOCKED
        AdbFixes.ALL.any { it.id == fixId && AdbFixes.notNeeded(it, kept) } -> ShizukuRefusal.NOT_NEEDED
        check.status == TermuxStatus.TEST_KEY -> ShizukuRefusal.TEST_KEY
        check.status == TermuxStatus.UPDATED || check.status == TermuxStatus.CHANGED -> ShizukuRefusal.TERMUX_CHANGED
        !offered(check, ready) -> ShizukuRefusal.TERMUX_NOT_READY
        else -> null
    }

    /** What the run's exit code says, with [applied] (Robot's own check of the fix afterwards). */
    fun outcome(exit: Int, applied: Boolean): ShizukuOutcome = when {
        exit == 127 -> ShizukuOutcome.NO_RISH
        exit != 0 -> ShizukuOutcome.FAILED
        applied -> ShizukuOutcome.APPLIED
        else -> ShizukuOutcome.NOT_APPLIED
    }
}
