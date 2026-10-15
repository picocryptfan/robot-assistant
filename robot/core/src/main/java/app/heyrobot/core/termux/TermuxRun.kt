// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

// Pure. How one run is sent to Termux's RunCommandService: the
// executable and argv, the working folder, stdin, and the intent's extras as plain data; :app builds the Intent and the
// PendingIntent from a [TermuxRunPlan] and sends it from the :voice process. The command never becomes part of a script
// Robot writes: it is an argument ("$3") of a fixed wrapper, which runs it under GNU timeout in a process group of its
// own (timeout calls setpgid(0, 0) unless --foreground, and signals the whole group at the limit: coreutils timeout.c),
// records that group's id in a pid file so Stop can end the whole tree, closes stdin, and passes the exit code through.

/** One extra of the RUN_COMMAND intent, as plain data. */
sealed class TermuxExtra {
    data class Text(val value: String) : TermuxExtra()
    data class Texts(val values: List<String>) : TermuxExtra()
    data class Flag(val value: Boolean) : TermuxExtra()
    /**
     * Where :app puts the result PendingIntent: one-shot, mutable (Termux fills the result in), explicit to Robot's own
     * non-exported receiver in :voice, with [execId] as its request code and in its data URI (robot-termux://run/<id>).
     */
    data class ResultIntent(val execId: Int) : TermuxExtra()
}

/** Everything about one run that :app needs to send it and to wait for its result. */
data class TermuxRunPlan(
    /** A positive 31-bit id, unique while the run is pending; the result PendingIntent's request code. */
    val execId: Int,
    val kind: TermuxRunKind,
    /** RUN_COMMAND_PATH: always Termux's bash. */
    val executable: String,
    /** RUN_COMMAND_ARGUMENTS. */
    val arguments: List<String>,
    /** RUN_COMMAND_WORKDIR. */
    val workdir: String,
    /** RUN_COMMAND_STDIN (Termux ≥ 0.109), or null: the command's stdin is /dev/null. */
    val stdin: String?,
    /** The time limit given to timeout; Robot waits [deadlineMs]. */
    val limitSeconds: Int,
    val runner: String = TermuxIntents.RUNNER_APP_SHELL,
    val sessionAction: String? = null,
) {
    /** Robot stops waiting after the limit, timeout's own grace and 10 s more: "Termux didn't answer". */
    val deadlineMs: Long get() = (limitSeconds + TermuxRun.KILL_AFTER_S + TermuxRun.DEADLINE_MARGIN_S) * 1000L

    /** The extras, in the order :app puts them. The label is always "Robot": Termux logs labels, never Robot's commands. */
    fun extras(): Map<String, TermuxExtra> {
        val m = LinkedHashMap<String, TermuxExtra>()
        m[TermuxIntents.EXTRA_COMMAND_PATH] = TermuxExtra.Text(executable)
        m[TermuxIntents.EXTRA_ARGUMENTS] = TermuxExtra.Texts(arguments)
        m[TermuxIntents.EXTRA_WORKDIR] = TermuxExtra.Text(workdir)
        stdin?.let { m[TermuxIntents.EXTRA_STDIN] = TermuxExtra.Text(it) }
        m[TermuxIntents.EXTRA_BACKGROUND] = TermuxExtra.Flag(runner == TermuxIntents.RUNNER_APP_SHELL)
        m[TermuxIntents.EXTRA_RUNNER] = TermuxExtra.Text(runner)
        sessionAction?.let { m[TermuxIntents.EXTRA_SESSION_ACTION] = TermuxExtra.Text(it) }
        m[TermuxIntents.EXTRA_COMMAND_LABEL] = TermuxExtra.Text(TermuxIntents.LABEL)
        m[TermuxIntents.EXTRA_PENDING_INTENT] = TermuxExtra.ResultIntent(execId)
        return m
    }
}

object TermuxRun {
    /** timeout -k: KILL this long after TERM, for a command that ignores TERM. */
    const val KILL_AFTER_S = 5
    /** Robot's own deadline past the limit and the kill delay. */
    const val DEADLINE_MARGIN_S = 10
    /** The Stop run's own limit. */
    const val STOP_LIMIT_S = 10
    /** The set-up test's limit ("no answer in 15 s"). */
    const val TEST_LIMIT_S = 15

    /** $0 of every wrapper (what Termux's process list shows). */
    private const val NAME = "robot"
    private const val TMP = "\${TMPDIR:-${TermuxPaths.TMP}}"
    /** Keep git and ssh from asking for anything (stdin is closed anyway). */
    private const val ENV = "GIT_TERMINAL_PROMPT=0 SSH_ASKPASS_REQUIRE=never"
    private const val PID = "p=\"$TMP/robot-\$1.pid\""
    private const val WAIT = "echo \$! > \"\$p\"; wait \$!; s=\$?; rm -f \"\$p\"; exit \$s"

    /**
     * The fixed wrapper for a command: $1 the run's id, $2 the limit in seconds, $3 the command, run by an inner bash
     * under timeout in its own process group. [profile]: "Load my shell profile" (bash -l) instead of --noprofile --norc.
     * [stdin]: the command reads the run's stdin (RUN_COMMAND_STDIN) instead of /dev/null (a background job's stdin is
     * /dev/null unless redirected, so the wrapper keeps a copy on descriptor 3).
     */
    fun wrapper(profile: Boolean = false, stdin: Boolean = false): String {
        val bash = if (profile) "bash -l" else "bash --noprofile --norc"
        val input = if (stdin) "<&3 3<&-" else "</dev/null"
        val keep = if (stdin) "exec 3<&0\n" else ""
        return "$PID\n$keep$ENV timeout -k $KILL_AFTER_S \"\$2\" $bash -c \"\$3\" $input &\n$WAIT"
    }

    /**
     * The fixed wrapper for one of your scripts: $3 the script's path, $4 the scripts folder, then your arguments as
     * separate argv entries, with no shell reading them. The script must resolve (symbolic links followed) to an
     * executable regular file directly in the folder, or the run ends with 126.
     */
    val WRAP_SCRIPT: String = "$PID\n" +
        "r=\$(realpath -e -- \"\$3\" 2>/dev/null) && d=\$(realpath -e -- \"\$4\" 2>/dev/null) && [ \"\${r%/*}\" = \"\$d\" ] && [ -f \"\$r\" ] && [ -x \"\$r\" ] || exit 126\n" +
        "$ENV timeout -k $KILL_AFTER_S \"\$2\" \"\$r\" \"\${@:5}\" </dev/null &\n" +
        WAIT

    /**
     * The fixed Stop run: $1 the run to stop. TERM to its whole process group, KILL 2 s later; nothing when its pid file
     * is gone (it already ended) or doesn't hold a number.
     */
    val WRAP_STOP: String = "$PID\n" +
        "[ -f \"\$p\" ] || exit 0\n" +
        "g=\$(cat \"\$p\"); case \"\$g\" in ''|*[!0-9]*) exit 0;; esac\n" +
        "kill -TERM -- \"-\$g\" 2>/dev/null; sleep 2; kill -KILL -- \"-\$g\" 2>/dev/null; exit 0"

    /** The set-up test (B5.1 step 5): bash runs, and GNU timeout is there. */
    const val TEST_SCRIPT = "echo robot-ok; command -v timeout"

    fun pidFile(execId: Int): String = "${TermuxPaths.TMP}/robot-$execId.pid"

    /** The result PendingIntent's data: robot-termux://run/<id>, unique per run (B8.2). */
    fun resultUri(execId: Int): String = "robot-termux://run/$execId"

    /** The run a result is for, from its data URI; null for anything that isn't exactly [resultUri] of a positive id. */
    fun execId(uri: String?): Int? {
        val id = uri?.removePrefix("robot-termux://run/")?.takeIf { it != uri && it.length in 1..10 && it.all { c -> c in '0'..'9' } } ?: return null
        return id.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun checkId(execId: Int) = require(execId > 0) { "execId must be positive" }

    private fun bashArgs(script: String, vararg rest: String): List<String> = listOf("--noprofile", "--norc", "-c", script, NAME) + rest

    /** A command written in full, after the policy said it may run. */
    fun command(execId: Int, command: String, limitSeconds: Int, kind: TermuxRunKind = TermuxRunKind.CARDED, profile: Boolean = false,
        stdin: String? = null, workdir: String = TermuxPaths.HOME): TermuxRunPlan {
        checkId(execId)
        require(command.isNotBlank() && '\u0000' !in command) { "no command" }
        require(workdir.startsWith("/") && workdir.none { it < ' ' }) { "workdir must be an absolute path" }
        val limit = TermuxSettings.limit(limitSeconds)
        return TermuxRunPlan(execId, kind, TermuxPaths.BASH, bashArgs(wrapper(profile, stdin != null), "$execId", "$limit", command), workdir, stdin, limit)
    }

    /** One of your scripts with its arguments (already split by ShellLexer.argv), never through a shell. */
    fun script(execId: Int, name: String, args: List<String>, limitSeconds: Int, stdin: String? = null): TermuxRunPlan {
        checkId(execId)
        require(args.none { a -> a.any { it == '\u0000' } }) { "NUL in an argument" }
        val limit = TermuxSettings.limit(limitSeconds)
        require(stdin == null) { "scripts read /dev/null" }
        return TermuxRunPlan(execId, TermuxRunKind.SCRIPT, TermuxPaths.BASH,
            bashArgs(WRAP_SCRIPT, "$execId", "$limit", TermuxScripts.path(name), TermuxScripts.FOLDER, *args.toTypedArray()), TermuxScripts.FOLDER, null, limit)
    }

    /** Stop run [target] (its whole process tree); card-free, since it can only end Robot's own run. */
    fun stop(execId: Int, target: Int): TermuxRunPlan {
        checkId(execId)
        checkId(target)
        return TermuxRunPlan(execId, TermuxRunKind.STOP, TermuxPaths.BASH, bashArgs(WRAP_STOP, "$target"), TermuxPaths.HOME, null, STOP_LIMIT_S)
    }

    /** The set-up test: "robot-ok" and timeout's path, or a failure in words. */
    fun test(execId: Int): TermuxRunPlan {
        checkId(execId)
        return TermuxRunPlan(execId, TermuxRunKind.TEST, TermuxPaths.BASH, bashArgs(TEST_SCRIPT), TermuxPaths.HOME, null, TEST_LIMIT_S)
    }

    /**
     * "Open in Termux" for a command that needs a terminal (B5.4): a new terminal session ("switch to new session and
     * don't open activity"; Robot opens Termux itself). Only the exit code comes back.
     */
    fun terminal(execId: Int, command: String): TermuxRunPlan {
        checkId(execId)
        require(command.isNotBlank() && '\u0000' !in command) { "no command" }
        return TermuxRunPlan(execId, TermuxRunKind.TERMINAL, TermuxPaths.BASH, listOf("--noprofile", "--norc", "-c", command), TermuxPaths.HOME, null,
            TermuxSettings.MAX_LIMIT_S, runner = TermuxIntents.RUNNER_TERMINAL_SESSION, sessionAction = TermuxIntents.SESSION_ACTION_SWITCH_NO_ACTIVITY)
    }
}
