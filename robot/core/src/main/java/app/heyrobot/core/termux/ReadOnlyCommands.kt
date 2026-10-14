package app.heyrobot.core.termux

// Pure. The strict read-only allowlist: with "Read-only commands without a
// card" on (off by default) and nothing in the conversation Robot didn't write, a command on it runs without a card. A
// command passes only if every part is understood and allowed: one simple command, optionally piped into head, tail,
// wc, grep, sort, uniq or cut; no ;, &&, ||, &, redirection, $( ), backticks, $VAR other than ~ and $HOME, here-doc or
// comment; paths in ~ or $PREFIX and not private; content read only from files that aren't hidden. The list is code,
// not a setting, so it can't drift; it can grow by release. Never on it, whatever the flags: env, printenv, set (they
// print tokens), history, and anything CommandAnalyzer warns about.

object ReadOnlyCommands {
    /** Programs that print facts about the phone or Termux; options only, no other arguments. */
    private val FACTS = setOf("pwd", "whoami", "arch", "nproc", "uptime", "free", "uname")
    /** Programs that read only names, sizes and dates of files. */
    private val METADATA = setOf("ls", "du", "stat", "file", "wc", "df")
    /** Programs that read what is in files. */
    private val CONTENT = setOf("cat", "head", "tail")
    /** What a pipe may lead into. */
    private val FILTERS = setOf("head", "tail", "wc", "grep", "egrep", "fgrep", "sort", "uniq", "cut")
    /** Termux:API's harmless reads that may run without a card. */
    private val API = setOf("termux-battery-status", "termux-audio-info", "termux-camera-info", "termux-tts-engines")
    private val GREP = setOf("grep", "egrep", "fgrep")

    /** Features allowed at all: | (into a filter), globs (for listings), ~ and $HOME. */
    private val FEATURES = setOf(ShellFeature.PIPE, ShellFeature.GLOB, ShellFeature.TILDE, ShellFeature.PARAMETER)

    /** [parse] is on the allowlist. */
    fun allows(parse: ShellParse): Boolean {
        if (!parse.readable || parse.features.any { it !in FEATURES }) return false
        val cmds = parse.commands
        if (cmds.isEmpty()) return false
        if (cmds.any { it.parent != null || it.contexts.isNotEmpty() || it.assignments.isNotEmpty() || it.redirects.isNotEmpty() || it.background ||
                it.function != null || it.words.isEmpty() }) return false
        if (cmds.map { it.pipeline }.distinct().size != 1) return false
        val stages = cmds.sortedBy { it.stage }
        if (stages.map { it.stage } != stages.indices.toList() || stages.size != stages[0].stages) return false
        if (cmds.any { c -> c.words.any { !wordOk(it) } }) return false
        if (!first(stages[0], piped = stages.size > 1)) return false
        return stages.drop(1).all { filter(it) }
    }

    /** No substitution, $'…', or parameter but $HOME; a name of the program without a path. */
    private fun wordOk(w: ShellWord): Boolean =
        !w.substitutes && !w.ansiC && w.params.all { it == "HOME" } && w.value.length <= 1024 && !CommandAnalyzer.hidden(w.value)

    private fun first(c: ShellCommand, piped: Boolean): Boolean {
        val name = c.words[0].value
        val args = c.args
        val v = args.map { it.value }
        return when (name) {
            in FACTS -> v.all { it.startsWith("-") }
            "id" -> true
            "date" -> v.none { it == "-s" || it.startsWith("--set") || it == "-f" || it.startsWith("--file") || (it.startsWith("-") && !it.startsWith("--") && 's' in it) } &&
                positionals(v).all { it.startsWith("+") }
            "hostname" -> v.all { it in setOf("-s", "-f", "-i", "-I", "-d", "-a", "-A", "--short", "--fqdn", "--long", "--ip-address", "--all-ip-addresses", "--domain", "--alias") }
            in METADATA -> v.none { it.startsWith("--files0-from") || it == "-C" || it == "--compile" } && paths(args, content = false)
            in CONTENT -> v.none { it == "-f" || it == "-F" || it.startsWith("--follow") || it == "--retry" || it.startsWith("--pid") ||
                (name == "tail" && it.startsWith("-") && !it.startsWith("--") && ('f' in it || 'F' in it)) } && paths(args, content = true)
            "find" -> find(args)
            in GREP -> grep(args)
            // Not BSD-style "e" (it prints each process's environment, where tokens live).
            "ps" -> v.none { it.contains("env", ignoreCase = true) || (!it.startsWith("-") && 'e' in it) }
            "top" -> top(v)
            "which", "type" -> v.all { !it.contains('/') }
            "command" -> v.firstOrNull() in setOf("-v", "-V") && v.drop(1).all { !it.startsWith("-") && !it.contains('/') }
            // 1.0 audit (native-02): never git. Even status, log, diff and show read the repository's own config, which can
            // name programs git runs (core.fsmonitor, gpg.program with log.showSignature…): a git command always shows its card.
            "pkg" -> v == listOf("list-installed")
            "dpkg" -> v.firstOrNull() in setOf("-l", "--list") && v.drop(1).all { !it.startsWith("-") && !it.contains('/') }
            "apt" -> v.toSet() == setOf("list", "--installed") && v.size == 2
            in API -> v.isEmpty()
            "echo" -> piped
            "printf" -> piped && v.firstOrNull() != "-v"
            else -> false
        }
    }

    private fun filter(c: ShellCommand): Boolean {
        val name = c.words[0].value
        val args = c.args
        val v = args.map { it.value }
        return when (name) {
            "head", "tail" -> first(c, piped = true)
            "wc" -> v.none { it.startsWith("--files0-from") } && paths(args, content = true)
            in GREP -> grep(args)
            "sort" -> v.none { it == "-o" || it.startsWith("--output") || it.startsWith("--compress-program") || it == "-T" || it.startsWith("--temporary-directory") ||
                it.startsWith("--files0-from") || it.startsWith("--random-source") || (it.startsWith("-") && !it.startsWith("--") && ('o' in it || 'T' in it)) } &&
                paths(args, content = true)
            "uniq" -> positionals(v).size <= 1 && paths(args, content = true)
            "cut" -> paths(args, content = true)
            else -> false
        } && name in FILTERS
    }

    /**
     * Every argument that isn't an option is a path in ~ or $PREFIX, not private; [content] (the program reads what is in
     * it): no glob and nothing hidden (dotfiles such as ~/.bash_history hold what you typed).
     */
    private fun paths(args: List<ShellWord>, content: Boolean, skip: Set<Int> = emptySet()): Boolean {
        var rest = false
        for ((k, w) in args.withIndex()) {
            val a = w.value
            if (!rest && a == "--") { rest = true; continue }
            if (!rest && a.startsWith("-") && a != "-") continue
            if (k in skip || a == "-") continue
            if (!pathOk(w, content)) return false
        }
        return true
    }

    private fun pathOk(w: ShellWord, content: Boolean): Boolean {
        val path = TermuxPaths.resolve(w) ?: return false
        if (!(TermuxPaths.inHome(path) || TermuxPaths.inPrefix(path))) return false
        if (CommandAnalyzer.privatePath(w.value) || CommandAnalyzer.privatePath(path)) return false
        if (content) {
            if (w.glob) return false
            if (path.removePrefix(TermuxPaths.FILES).split('/').any { it.startsWith(".") }) return false
            // Shared storage holds your own documents and photos: reading them asks first.
            if (TermuxPaths.shared(path)) return false
        }
        return true
    }

    private fun positionals(v: List<String>): List<String> {
        val out = ArrayList<String>()
        var rest = false
        for (a in v) {
            if (!rest && a == "--") { rest = true; continue }
            if (rest || !a.startsWith("-") || a == "-") out += a
        }
        return out
    }

    /**
     * find: start paths in ~ or $PREFIX; no -delete, -exec, -execdir, -ok, -okdir, -fprint…, -fls. 1.0.1: the options that
     * come before the start paths (-H, -L, -P) are skipped first, so "find -L /" has its "/" checked; GNU find's -D and -O
     * (also before the paths) are refused.
     */
    private fun find(args: List<ShellWord>): Boolean {
        val forbidden = setOf("-delete", "-exec", "-execdir", "-ok", "-okdir", "-fprint", "-fprint0", "-fprintf", "-fls")
        if (args.any { it.value in forbidden }) return false
        val lead = args.takeWhile { it.value in setOf("-H", "-L", "-P") }.size
        val rest = args.drop(lead)
        val first = rest.firstOrNull()?.value
        if (first != null && (first.startsWith("-D") || first.startsWith("-O"))) return false
        val starts = rest.takeWhile { !it.value.startsWith("-") && it.value != "(" && it.value != "!" }
        return starts.all { pathOk(it, content = false) }
    }

    /** grep: files as for cat; -r only below a folder in ~ (not ~ itself, whose hidden folders hold keys). */
    private fun grep(args: List<ShellWord>): Boolean {
        val v = args.map { it.value }
        val recursive = v.any { it == "--recursive" || it == "--dereference-recursive" || it.startsWith("--directories=recurse") ||
            (it.startsWith("-") && !it.startsWith("--") && ('r' in it || 'R' in it)) } || (v.indexOf("-d").let { it >= 0 && v.getOrNull(it + 1) == "recurse" })
        val patternGiven = v.any { it == "-e" || it == "-f" || it.startsWith("--regexp") || it.startsWith("--file") || (it.startsWith("-e") && it.length > 2) }
        // -f FILE reads patterns from a file: a content read.
        v.indexOf("-f").takeIf { it >= 0 }?.let { k -> if (args.getOrNull(k + 1)?.let { pathOk(it, content = true) } != true) return false }
        if (v.any { it.startsWith("--file=") }) return false
        var rest = false
        var skippedPattern = patternGiven
        val files = ArrayList<ShellWord>()
        var k = 0
        while (k < args.size) {
            val a = v[k]
            when {
                !rest && a == "--" -> rest = true
                !rest && a.startsWith("-") && a != "-" -> if (a in setOf("-e", "-f", "-m", "-A", "-B", "-C", "-d", "-D", "--regexp", "--file", "--max-count",
                        "--after-context", "--before-context", "--context")) k++
                !skippedPattern -> skippedPattern = true
                else -> files += args[k]
            }
            k++
        }
        if (recursive) {
            if (files.isEmpty()) return false
            return files.all { w -> pathOk(w, content = true) && TermuxPaths.resolve(w)?.let { it != TermuxPaths.HOME && TermuxPaths.inHome(it) } == true }
        }
        return files.all { pathOk(it, content = true) }
    }

    private fun top(v: List<String>): Boolean {
        val joined = v.joinToString(" ")
        return joined.length <= 16 && Regex("^-b -n ?\\d{1,3}$|^-n ?\\d{1,3} -b$|^-bn ?\\d{1,3}$").matches(joined)
    }
}
