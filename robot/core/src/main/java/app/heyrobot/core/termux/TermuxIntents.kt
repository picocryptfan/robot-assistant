package app.heyrobot.core.termux

// Pure. The strings another app needs to run a command in Termux, and Termux's folders. Copied from Termux's
// TermuxConstants.java (termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java, MIT licence,
// "Version: v0.53.0", termux/termux-app master 8629e632fcb95da272221be327db653fb24befe9, 26 Sep 2026), with credit in
// THIRD_PARTY.md when the agent ships; Robot doesn't link termux-shared. Values are checked by TermuxIntentsTest.

/** Termux's RUN_COMMAND intent (TermuxConstants.TERMUX_APP.RUN_COMMAND_SERVICE) and its result bundle. */
object TermuxIntents {
    /** TERMUX_PACKAGE_NAME. */
    const val PACKAGE = "com.termux"
    /** TERMUX_APP.RUN_COMMAND_SERVICE_NAME: the background runner other apps start. */
    const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"
    /** PERMISSION_RUN_COMMAND: a dangerous permission Termux defines; you allow it for Robot. */
    const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"
    const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"

    const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
    const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
    /** Termux ≥ 0.109. */
    const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    /** Deprecated for [EXTRA_RUNNER] but understood by every version that has [EXTRA_PENDING_INTENT]. */
    const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    const val EXTRA_RUNNER = "com.termux.RUN_COMMAND_RUNNER"
    const val EXTRA_SESSION_ACTION = "com.termux.RUN_COMMAND_SESSION_ACTION"
    /** Termux logs the label (never the command, at its default log level): Robot's is always [LABEL]. */
    const val EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL"
    /** Termux ≥ 0.109: the PendingIntent Termux fills with the result bundle. */
    const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"

    /** ExecutionCommand.Runner names (RUN_COMMAND wiki): the background shell, or a terminal session. */
    const val RUNNER_APP_SHELL = "app-shell"
    const val RUNNER_TERMINAL_SESSION = "terminal-session"
    /** RUN_COMMAND wiki: "switch to new session and don't open activity", for Open in Termux (B5.4). */
    const val SESSION_ACTION_SWITCH_NO_ACTIVITY = "2"

    /** The label Termux shows and logs for Robot's runs. */
    const val LABEL = "Robot"

    /** EXTRA_PLUGIN_RESULT_BUNDLE and its keys. */
    const val RESULT_BUNDLE = "result"
    const val RESULT_STDOUT = "stdout"
    const val RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length"
    const val RESULT_STDERR = "stderr"
    const val RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length"
    const val RESULT_EXIT_CODE = "exitCode"
    /** -1 (Errno.ERRNO_SUCCESS): no internal error. */
    const val RESULT_ERR = "err"
    const val RESULT_ERRMSG = "errmsg"
    const val ERR_NONE = -1

    /** DataUtils.TRANSACTION_SIZE_LIMIT_IN_BYTES: stdout and stderr together are cut from the start to this. */
    const val RESULT_LIMIT_BYTES = 100 * 1024
}

/** Termux's folders (TermuxConstants.TERMUX_*_DIR_PATH) and how a path in a command is read against them. */
object TermuxPaths {
    /** TERMUX_FILES_DIR_PATH. */
    const val FILES = "/data/data/com.termux/files"
    /** TERMUX_HOME_DIR_PATH: ~ */
    const val HOME = "$FILES/home"
    /** TERMUX_PREFIX_DIR_PATH: $PREFIX */
    const val PREFIX = "$FILES/usr"
    /** TERMUX_BIN_PREFIX_DIR_PATH. */
    const val BIN = "$PREFIX/bin"
    /** TERMUX_TMP_PREFIX_DIR_PATH: $TMPDIR */
    const val TMP = "$PREFIX/tmp"
    const val BASH = "$BIN/bash"
    /** Android's other name for the same app data folder. */
    private const val FILES_ALIAS = "/data/user/0/com.termux/files"
    /** Termux's links to shared storage (termux-setup-storage): ~/storage/shared is /storage/emulated/0. */
    const val STORAGE_LINKS = "$HOME/storage"

    /** Parameters a path may start with; any other expansion makes it unknown. */
    private val KNOWN = mapOf("HOME" to HOME, "PREFIX" to PREFIX, "TMPDIR" to TMP)

    /**
     * The absolute path [w] names, as far as it can be known before running: ~, $HOME, $PREFIX and $TMPDIR at its start are
     * read as Termux sets them; a relative path is under [cwd]; . and .. are resolved without looking at the disk (a
     * symbolic link may lead elsewhere). Null for anything else expanded ($X, $(…), ~user).
     */
    fun resolve(w: ShellWord, cwd: String = HOME): String? {
        if (w.substitutes) return null
        var v = w.value
        if (w.params.isNotEmpty()) {
            if (w.params.size != 1) return null
            val name = w.params[0]
            val base = KNOWN[name] ?: return null
            v = when {
                v.startsWith("\${$name}") -> base + v.substring(name.length + 3)
                v.startsWith("$$name") && (v.length == name.length + 1 || !v[name.length + 1].isLetterOrDigit() && v[name.length + 1] != '_') ->
                    base + v.substring(name.length + 1)
                else -> return null
            }
            return resolve(v, cwd)
        }
        if (w.tilde) {
            v = when {
                v == "~" -> HOME
                v.startsWith("~/") -> HOME + v.substring(1)
                else -> return null
            }
        }
        return resolve(v, cwd)
    }

    /** [value] (no expansions) as an absolute, normalized path. */
    fun resolve(value: String, cwd: String = HOME): String {
        val abs = if (value.startsWith("/")) value else "$cwd/$value"
        return normalize(abs)
    }

    /** "." and ".." resolved, repeated slashes removed, Android's /data/user/0 alias read as /data/data. */
    fun normalize(path: String): String {
        val parts = ArrayList<String>()
        for (p in path.split('/')) {
            when (p) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts += p
            }
        }
        val out = "/" + parts.joinToString("/")
        return if (out == FILES_ALIAS || out.startsWith("$FILES_ALIAS/")) FILES + out.substring(FILES_ALIAS.length) else out
    }

    fun under(path: String, dir: String): Boolean = path == dir || path.startsWith("$dir/")

    fun inHome(path: String): Boolean = under(path, HOME)

    fun inPrefix(path: String): Boolean = under(path, PREFIX)

    /** Shared storage: /sdcard, /storage, or Termux's links to it under ~/storage. */
    fun shared(path: String): Boolean = under(path, "/sdcard") || under(path, "/storage") || under(path, "/mnt") || under(path, STORAGE_LINKS)

    /** A write here changes nothing that lasts: /dev/null, the standard streams, a descriptor. */
    fun harmlessSink(path: String): Boolean =
        path == "/dev/null" || path == "/dev/stdout" || path == "/dev/stderr" || path == "/dev/tty" || under(path, "/dev/fd") || under(path, "/proc/self/fd")

    /** Writing here stays inside Termux's own home (or its temporary folder), not shared storage or the system. */
    fun writableInside(path: String): Boolean = harmlessSink(path) || under(path, TMP) || (inHome(path) && !shared(path))
}
