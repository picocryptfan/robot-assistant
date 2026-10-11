package app.heyrobot.core.termux

import java.util.EnumSet

// Pure. Robot's own reading of a Termux command (docs/plans/websearch-termux-design.md B7.2, B3, B5.4): the warnings the
// card shows as chips, in plain English and French, from the parsed command and never from the model's explanation.
// Anything Robot can't follow (nested shells, substitutions, keywords, a program known only by running something, a
// script) makes the command "complex": always a card, answered by a tap, its warnings still shown. TermuxPolicy decides
// from this; ReadOnlyCommands is the strict allowlist.

/** One thing a command does that the card warns about. Every warning makes the card need a tap (B7.1 rule 4). */
enum class CommandWarning(val en: String, val fr: String, /** The card's red line. */ val red: Boolean = false) {
    UNREADABLE("Robot couldn't read this command fully", "Robot n'a pas pu lire cette commande en entier"),
    HIDDEN_CHARACTERS("Has hidden or look-alike characters", "Contient des caractères cachés ou trompeurs"),
    /** rm, rmdir, unlink, shred, find -delete, git clean, truncate, a > redirection over a file (what was in it is lost). */
    DELETES("Deletes files or empties them", "Supprime ou vide des fichiers"),
    OVERWRITES("Changes, overwrites or moves files", "Modifie, écrase ou déplace des fichiers"),
    OUTSIDE_HOME("Changes files outside Termux's home folder", "Modifie des fichiers hors du dossier de Termux"),
    INTERNET("Uses the internet", "Utilise Internet"),
    INTERNET_CODE("Runs code from the internet", "Exécute du code venu d'Internet"),
    PACKAGES("Changes installed packages", "Modifie les paquets installés"),
    /** su, sudo, tsu: GrapheneOS isn't rooted, but such a command is never silent. */
    ROOT("Asks for root rights", "Demande les droits root"),
    KEEPS_RUNNING("Keeps running later, without Robot", "Continue de tourner ensuite, sans Robot"),
    RUNS_FOREVER("May never end by itself: the time limit stops it", "Peut ne jamais finir : la limite de temps l'arrête"),
    /** Refused outright (TermuxPolicy): a function that starts copies of itself. */
    FORK_BOMB("Starts copies of itself without end until the phone stalls", "Se recopie sans fin jusqu'à bloquer le téléphone"),
    SENDS("Sends a text or places a call right away. Robot's own texting only opens a draft.",
        "Envoie un texto ou passe un appel tout de suite. Robot, lui, ouvre seulement un brouillon.", red = true),
    PERSONAL_DATA("Reads your personal phone data", "Lit vos données personnelles"),
    CAPTURES("Uses the camera or the microphone", "Utilise l'appareil photo ou le micro"),
    PRIVATE_FILES("Reads private files", "Lit des fichiers privés"),
    SECRET("Contains what looks like a password or key: Termux's process list and error reports would show it",
        "Contient ce qui ressemble à un mot de passe ou une clé : la liste des processus et les rapports d'erreur de Termux l'afficheraient"),
    NEEDS_TERMINAL("Needs a terminal: open it in Termux", "A besoin d'un terminal : ouvrez-la dans Termux"),
    ;

    fun text(fr: Boolean): String = if (fr) this.fr else en
}

/** Termux:API's commands by what they do (B3, the scripts of termux-api-package). */
enum class TermuxApi {
    /** termux-sms-send, termux-telephony-call: a red line, a tap only. */
    SENDS,
    /** Reads personal data: always a card; the output makes the conversation personal. */
    PERSONAL,
    /** Camera, microphone, speech: always a card, a tap only. */
    CAPTURES,
    /** Changes the phone (Wi-Fi, volume, torch, notifications…): the ordinary rules. */
    CHANGES_PHONE,
    /** termux-job-scheduler: keeps running later. */
    RUNS_LATER,
    /** Battery, audio, camera and TTS information: may be on the read-only allowlist. */
    HARMLESS;

    companion object {
        private val SENDS_ = setOf("termux-sms-send", "termux-telephony-call")
        private val PERSONAL_ = setOf("termux-sms-list", "termux-sms-inbox", "termux-call-log", "termux-contact-list", "termux-location", "termux-clipboard-get",
            "termux-notification-list", "termux-wifi-connectioninfo", "termux-wifi-scaninfo", "termux-telephony-cellinfo", "termux-telephony-deviceinfo")
        private val CAPTURES_ = setOf("termux-camera-photo", "termux-microphone-record", "termux-speech-to-text")
        private val HARMLESS_ = setOf("termux-battery-status", "termux-audio-info", "termux-camera-info", "termux-tts-engines", "termux-infrared-frequencies",
            "termux-saf-ls", "termux-saf-stat", "termux-saf-dirs")
        /** termux-tools' own commands (not Termux:API). */
        val TOOLS = setOf("termux-setup-storage", "termux-reload-settings", "termux-open", "termux-open-url", "termux-info", "termux-change-repo", "termux-reset",
            "termux-backup", "termux-restore", "termux-wake-lock", "termux-wake-unlock", "termux-fix-shebang", "termux-setup-package-manager", "termux-am",
            "termux-am-socket", "termux-exec")

        /** The class of a termux-* program; null for anything else, including termux-tools' own commands. */
        fun of(program: String): TermuxApi? = when {
            !program.startsWith("termux-") || program in TOOLS -> null
            program in SENDS_ -> SENDS
            program in PERSONAL_ -> PERSONAL
            program in CAPTURES_ -> CAPTURES
            program == "termux-job-scheduler" -> RUNS_LATER
            program in HARMLESS_ -> HARMLESS
            else -> CHANGES_PHONE
        }
    }
}

/** What Robot read in one command. */
class CommandAnalysis internal constructor(
    val command: String,
    val parse: ShellParse,
    /** In the order of [CommandWarning]. */
    val warnings: Set<CommandWarning>,
    /** Robot couldn't follow all of it: always a card, answered by a tap. */
    val complex: Boolean,
    /** On the strict read-only allowlist (ReadOnlyCommands): the only free-form commands that may run without a card. */
    val readOnly: Boolean,
    val api: Set<TermuxApi>,
    /** The programs it runs, as Robot read them (the card's "how Robot reads it"). */
    val programs: List<String>,
    /** Its output is personal ("your Termux output"): it reads personal phone data or private files. */
    val personal: Boolean,
) {
    /** A spoken or typed yes can't run it: a tap is needed. */
    val tapOnly: Boolean get() = complex || warnings.isNotEmpty()

    fun chips(fr: Boolean = false): List<String> = warnings.map { it.text(fr) }
}

object CommandAnalyzer {
    /** Longer commands are not offered to run (TermuxPolicy refuses them): the card couldn't show them whole. */
    const val MAX_CHARS = 4096
    /** Beyond this, the text isn't even read. */
    private const val MAX_READ = 64 * 1024
    /** Nested shells (sh -c "sh -c '…'") followed at most this deep. */
    private const val MAX_NESTING = 4

    /** Features of a command Robot doesn't follow fully: the command is complex. */
    private val COMPLEX = EnumSet.of(ShellFeature.SUBSHELL, ShellFeature.GROUP, ShellFeature.COMMAND_SUBSTITUTION, ShellFeature.BACKTICK,
        ShellFeature.PROCESS_SUBSTITUTION, ShellFeature.ARITHMETIC, ShellFeature.KEYWORD, ShellFeature.FUNCTION, ShellFeature.CONDITION,
        ShellFeature.ARRAY, ShellFeature.ANSI_C, ShellFeature.ASSIGNMENT)
    /** Parameters Robot reads as Termux sets them; any other $X makes a command complex. */
    private val KNOWN_PARAMS = setOf("HOME", "PREFIX", "TMPDIR")

    fun analyze(command: String): CommandAnalysis {
        val acc = Acc()
        val text = if (command.length > MAX_READ) command.substring(0, MAX_READ) else command
        val parse = ShellLexer.parse(text)
        scan(parse, acc, 0)
        if (hidden(command)) acc += CommandWarning.HIDDEN_CHARACTERS
        if (SecretMask.find(text).isNotEmpty()) acc += CommandWarning.SECRET
        val params = parse.commands.flatMap { c -> (c.words + c.assignments + c.redirects.mapNotNull { it.target }).flatMap { it.params } }
        val complex = acc.complex || !parse.readable || parse.features.any { it in COMPLEX } || params.any { it !in KNOWN_PARAMS } ||
            command.length > MAX_CHARS || command.isBlank()
        if (complex) acc += CommandWarning.UNREADABLE
        val readOnly = !complex && acc.warnings.isEmpty() && ReadOnlyCommands.allows(parse)
        val personal = TermuxApi.PERSONAL in acc.api || CommandWarning.PRIVATE_FILES in acc.warnings
        return CommandAnalysis(command, parse, EnumSet.copyOf(acc.warnings), complex, readOnly,
            acc.api.toSet(), acc.programs.toList(), personal)
    }

    /**
     * [command] as the card shows it: characters that are invisible or move text around (bidi controls, zero-width
     * characters, control characters but newline and tab, unusual spaces) written as ⟨U+202E⟩.
     */
    fun visible(command: String): String {
        val sb = StringBuilder(command.length)
        var k = 0
        while (k < command.length) {
            val cp = command.codePointAt(k)
            if (invisible(cp)) sb.append("⟨U+").append(Integer.toHexString(cp).uppercase().padStart(4, '0')).append('⟩') else sb.appendCodePoint(cp)
            k += Character.charCount(cp)
        }
        return sb.toString()
    }

    // --- Collecting -----------------------------------------------------------------------------------------------

    private class Acc {
        val warnings: EnumSet<CommandWarning> = EnumSet.noneOf(CommandWarning::class.java)
        val api: EnumSet<TermuxApi> = EnumSet.noneOf(TermuxApi::class.java)
        val programs = LinkedHashSet<String>()
        var complex = false
        /** Commands (by id, in the parse being scanned) that download, and those that run code given as an argument or on stdin. */
        val downloaders = HashSet<Int>()
        val argCode = HashSet<Int>()
        var stdinCode = false
        var anyDownloader = false

        operator fun plusAssign(w: CommandWarning) { warnings += w }
    }

    private fun scan(parse: ShellParse, acc: Acc, depth: Int) {
        if (!parse.readable) acc.complex = true
        val local = Acc()
        for (c in parse.commands) command(c, parse, local, depth)
        // Something downloaded, then run: piped into a shell, or in a $( ) / <( ) that a shell, eval or source runs.
        for (d in local.downloaders) {
            var p = parse.command(parse.command(d)?.parent)
            while (p != null) {
                if (p.id in local.argCode || p.name?.literal == false) { local += CommandWarning.INTERNET_CODE; break }
                p = parse.command(p.parent)
            }
        }
        // Downloaded, then run from a file or fed to a shell (curl -o i.sh … && sh i.sh, curl … | bash): said, to be safe.
        if (local.anyDownloader && (local.stdinCode || local.argCode.isNotEmpty())) local += CommandWarning.INTERNET_CODE
        loops(parse, local)
        for (c in parse.commands) {
            val f = c.function ?: continue
            if (c.name?.value == f) local += if (c.stages > 1 || c.background) CommandWarning.FORK_BOMB else CommandWarning.RUNS_FOREVER
        }
        if (ShellFeature.BACKGROUND in parse.features) local += CommandWarning.KEEPS_RUNNING
        acc.warnings += local.warnings
        acc.api += local.api
        acc.programs += local.programs
        acc.complex = acc.complex || local.complex
        acc.anyDownloader = acc.anyDownloader || local.anyDownloader
        acc.stdinCode = acc.stdinCode || local.stdinCode
    }

    /** [text], run by a shell (sh -c, eval, a here-document fed to bash…): read too, and always complex. */
    private fun script(text: String, acc: Acc, depth: Int) {
        acc.complex = true
        if (depth >= MAX_NESTING) return
        if (hidden(text)) acc += CommandWarning.HIDDEN_CHARACTERS
        if (SecretMask.find(text).isNotEmpty()) acc += CommandWarning.SECRET
        scan(ShellLexer.parse(text), acc, depth + 1)
    }

    private fun loops(parse: ShellParse, acc: Acc) {
        for (l in parse.loops) {
            val forever = when (l.kind) {
                ShellLoop.Kind.FOR_ARITHMETIC -> l.arithmetic?.split(';')?.let { it.size == 3 && it[1].isBlank() } == true
                ShellLoop.Kind.WHILE -> l.arithmetic?.trim()?.toLongOrNull()?.let { it != 0L } ?: parse.command(l.condition)?.let { alwaysTrue(it) } ?: false
                ShellLoop.Kind.UNTIL -> l.arithmetic?.trim() == "0" || parse.command(l.condition)?.name?.value == "false"
            }
            if (forever) acc += CommandWarning.RUNS_FOREVER
        }
    }

    /** while true, while :, while sleep 1, while [ 1 ]. */
    private fun alwaysTrue(c: ShellCommand): Boolean {
        val v = c.words.map { it.value }
        return when (v.firstOrNull()) {
            "true", ":", "sleep" -> true
            "[", "test" -> v.drop(1).filter { it != "]" }.let { it.size == 1 && it[0].isNotEmpty() }
            else -> false
        }
    }

    // --- One command ----------------------------------------------------------------------------------------------

    private fun command(c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int) {
        for (a in c.assignments) if (privatePath(a.value.substringAfter('='))) acc += CommandWarning.PRIVATE_FILES
        for (r in c.redirects) redirect(r, acc)
        for (w in c.words) if (privateWord(w)) acc += CommandWarning.PRIVATE_FILES
        val name = c.name ?: return
        if (!name.literal) {
            // The program is only known by running something.
            acc.complex = true
            return
        }
        invoke(name.value, c.args, c, parse, acc, depth)
    }

    private fun redirect(r: ShellRedirect, acc: Acc) {
        val t = r.target ?: return
        if (r.op == "<<" || r.op == "<<-" || r.op == "<<<") return
        if (privateWord(t)) acc += CommandWarning.PRIVATE_FILES
        if (!r.writesFile) return
        val path = TermuxPaths.resolve(t)
        if (path == null) {
            acc.complex = true
            acc += if (r.truncates) CommandWarning.DELETES else CommandWarning.OVERWRITES
            return
        }
        if (TermuxPaths.harmlessSink(path)) return
        acc += if (r.truncates) CommandWarning.DELETES else CommandWarning.OVERWRITES
        if (!TermuxPaths.writableInside(path)) acc += CommandWarning.OUTSIDE_HOME
    }

    /** The program's name: a bare name, or a path in a system folder; null for a script or program named by another path. */
    private fun program(value: String): String? {
        if ('/' !in value) return value
        val dir = TermuxPaths.resolve(value).substringBeforeLast('/')
        return if (dir in SYSTEM_BIN) value.substringAfterLast('/') else null
    }

    private val SYSTEM_BIN = setOf(TermuxPaths.BIN, "/bin", "/usr/bin", "/sbin", "/usr/sbin", "/system/bin", "/system/xbin", "/vendor/bin", "/usr/local/bin")

    /** Runs program [name] with [args] (the command [c], or a wrapper's inner command). */
    private fun invoke(name: String, args: List<ShellWord>, c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int) {
        val prog = program(name)
        if (prog == null) {
            // A script or program by its path (~/robot/backup, ./x): what it does can't be read here.
            acc.complex = true
            acc.argCode += c.id
            outsideIfAny(args, acc)
            return
        }
        acc.programs += prog
        val v = args.map { it.value }
        val pos = positionals(v)
        if (prog in OPAQUE) acc.complex = true
        if (runsAnotherProgram(prog, v)) acc.complex = true
        when (prog) {
            // Wrappers: the command they run is what matters.
            "sudo", "doas", "pkexec" -> {
                acc += CommandWarning.ROOT
                val inner = after(args, setOf("-u", "-g", "-C", "-p", "-h", "-U", "-r", "-t", "-D", "-R", "--user", "--group", "--prompt", "--host", "--other-user",
                    "--role", "--type", "--chdir", "--chroot", "--close-from"))
                if (inner.isEmpty()) acc += CommandWarning.NEEDS_TERMINAL else runInner(inner, c, parse, acc, depth)
            }
            "su", "tsu" -> {
                acc += CommandWarning.ROOT
                val code = optionValue(args, setOf("-c", "--command"))
                when {
                    code == null -> acc += CommandWarning.NEEDS_TERMINAL
                    code.literal -> script(code.value, acc, depth)
                    else -> acc.complex = true
                }
            }
            "env" -> {
                var k = 0
                while (k < args.size) {
                    val a = args[k].value
                    when {
                        a == "--" -> { k++; break }
                        a == "-S" || a == "--split-string" -> {
                            args.getOrNull(k + 1)?.let { if (it.literal) script((listOf(it) + args.drop(k + 2)).joinToString(" ") { w -> w.value }, acc, depth) else acc.complex = true }
                            return
                        }
                        a.startsWith("--split-string=") -> { script(a.substringAfter('='), acc, depth); return }
                        a in setOf("-u", "--unset", "-C", "--chdir") -> k += 2
                        a.startsWith("-") && a.length > 1 -> k++
                        '=' in a && !a.startsWith("=") -> { acc.complex = true; k++ }
                        else -> break
                    }
                }
                val inner = args.drop(k)
                if (inner.isNotEmpty()) runInner(inner, c, parse, acc, depth)
            }
            "nice" -> runInner(after(args, setOf("-n", "--adjustment")), c, parse, acc, depth)
            "nohup" -> { acc += CommandWarning.KEEPS_RUNNING; runInner(after(args, emptySet()), c, parse, acc, depth) }
            "setsid", "daemonize", "disown" -> { acc += CommandWarning.KEEPS_RUNNING; runInner(after(args, emptySet()), c, parse, acc, depth) }
            "stdbuf" -> runInner(after(args, setOf("-i", "-o", "-e", "--input", "--output", "--error")), c, parse, acc, depth)
            "timeout" -> runInner(after(args, setOf("-k", "--kill-after", "-s", "--signal")).drop(1), c, parse, acc, depth)
            "time" -> {
                if (hasOpt(v, 'o', "--output")) acc += CommandWarning.OVERWRITES
                runInner(after(args, setOf("-f", "--format", "-o", "--output")), c, parse, acc, depth)
            }
            "command" -> if (!hasOpt(v, 'v') && !hasOpt(v, 'V')) runInner(after(args, emptySet()), c, parse, acc, depth)
            "builtin", "unbuffer", "catchsegv" -> runInner(after(args, emptySet()), c, parse, acc, depth)
            "exec" -> runInner(after(args, setOf("-a")), c, parse, acc, depth)
            "ionice" -> if (!hasOpt(v, 'p', "--pid")) runInner(after(args, setOf("-c", "-n", "--class", "--classdata")), c, parse, acc, depth)
            "taskset", "chrt" -> if (!hasOpt(v, 'p', "--pid")) runInner(after(args, emptySet()).drop(1), c, parse, acc, depth)
            "flock" -> {
                val code = optionValue(args, setOf("-c", "--command"))
                if (code != null) { if (code.literal) script(code.value, acc, depth) else acc.complex = true }
                else runInner(after(args, setOf("-w", "--timeout", "-E", "--conflict-exit-code")).drop(1), c, parse, acc, depth)
            }
            "strace", "ltrace" -> {
                if (hasOpt(v, 'o', "--output")) acc += CommandWarning.OVERWRITES
                runInner(after(args, setOf("-o", "-e", "-p", "-s", "-u", "-E", "-P", "-a", "-b", "-I", "-O", "-S", "-X")), c, parse, acc, depth)
            }
            "torsocks", "proxychains", "proxychains4", "tsocks" -> { acc += CommandWarning.INTERNET; runInner(after(args, setOf("-f", "-a", "-P", "-u", "-p")), c, parse, acc, depth) }
            "busybox", "toybox" -> if (args.isNotEmpty() && args[0].literal) invoke(args[0].value, args.drop(1), c, parse, acc, depth)
            "xargs" -> {
                // The arguments come from its input: the program is known, what it gets isn't.
                acc.complex = true
                val inner = after(args, setOf("-a", "-d", "-E", "-I", "-L", "-n", "-P", "-s", "--arg-file", "--delimiter", "--eof", "--replace", "--max-lines",
                    "--max-args", "--max-procs", "--max-chars", "--process-slot-var"))
                if (inner.isNotEmpty()) runInner(inner, c, parse, acc, depth)
            }
            "watch" -> {
                acc += CommandWarning.RUNS_FOREVER
                acc += CommandWarning.NEEDS_TERMINAL
                val inner = after(args, setOf("-n", "--interval", "-q", "--equexit", "--color"))
                if (inner.isNotEmpty()) { if (inner.all { it.literal }) script(inner.joinToString(" ") { it.value }, acc, depth) else acc.complex = true }
            }
            "parallel" -> acc.complex = true
            // Shell state that changes what later commands do: alias and trap hold commands of their own.
            "alias" -> {
                acc.complex = true
                for (a in args) if ('=' in a.value) { if (a.literal) script(a.value.substringAfter('='), acc, depth) }
            }
            "trap" -> {
                acc.complex = true
                args.firstOrNull { !it.value.startsWith("-") }?.let { if (it.literal) script(it.value, acc, depth) }
            }
            "unalias", "bind", "complete", "enable", "shopt", "export", "declare", "typeset", "readonly", "local", "hash" -> acc.complex = true
            "set" -> if (args.isNotEmpty()) acc.complex = true
            "termux-open" -> if (v.any { webAddress(it) }) acc += CommandWarning.INTERNET
            "eval" -> { acc.argCode += c.id; if (args.all { it.literal }) script(v.joinToString(" "), acc, depth) else acc.complex = true }
            "source", "." -> { acc.argCode += c.id; acc.complex = true }
            in SHELLS -> shell(args, c, parse, acc, depth)
            in INTERPRETERS -> interpreter(prog, args, c, parse, acc)
            "awk", "gawk", "mawk", "nawk" -> awk(args, acc)
            "sed" -> sed(args, acc)
            "find" -> find(args, c, parse, acc, depth)

            // Deleting.
            "rm", "rmdir", "unlink", "shred", "srm", "wipe", "truncate" -> { acc += CommandWarning.DELETES; outside(pos, args, acc) }
            "mkfs", "wipefs", "fdisk", "sfdisk", "parted" -> acc += CommandWarning.DELETES
            "git" -> git(args, acc)

            // Changing files.
            "mv" -> { acc += CommandWarning.OVERWRITES; outside(pos, args, acc) }
            "cp", "install", "ln", "link", "rsync" -> {
                if (prog == "rsync" && remote(pos)) acc += CommandWarning.INTERNET
                if (prog == "rsync" && v.any { it.startsWith("--delete") || it == "--remove-source-files" }) acc += CommandWarning.DELETES
                acc += CommandWarning.OVERWRITES
                val target = optionValue(args, setOf("-t", "--target-directory"))
                outsideWords(listOfNotNull(target ?: args.lastOrNull { !it.value.startsWith("-") }), acc)
            }
            "dd" -> {
                val of = v.firstOrNull { it.startsWith("of=") }
                if (of != null) {
                    acc += CommandWarning.OVERWRITES
                    if (!writableValue(of.substring(3))) acc += CommandWarning.OUTSIDE_HOME
                }
                if (v.none { it.startsWith("count=") } && v.any { it == "if=/dev/zero" || it == "if=/dev/urandom" || it == "if=/dev/random" }) acc += CommandWarning.RUNS_FOREVER
            }
            "tee" -> {
                val files = args.filter { !it.value.startsWith("-") }.filter { w -> TermuxPaths.resolve(w)?.let { !TermuxPaths.harmlessSink(it) } ?: true }
                if (files.isNotEmpty()) { acc += CommandWarning.OVERWRITES; outsideWords(files, acc) }
            }
            "chmod", "chown", "chgrp", "chattr", "setfacl", "patch", "split", "csplit", "rename" -> { acc += CommandWarning.OVERWRITES; outside(pos.drop(1), args, acc) }
            "touch", "mkdir", "mkfifo", "mknod" -> outside(pos, args, acc)
            "unzip", "tar", "zip", "7z", "7za", "gzip", "gunzip", "bzip2", "bunzip2", "xz", "unxz", "lzma", "zstd", "unzstd", "lz4", "brotli" -> archive(prog, v, args, acc)
            "sort" -> if (hasOpt(v, 'o', "--output")) { acc += CommandWarning.OVERWRITES; optionValue(args, setOf("-o", "--output"))?.let { outsideWords(listOf(it), acc) } }
            "uniq" -> if (pos.size >= 2) { acc += CommandWarning.OVERWRITES; outsideWords(args.filter { it.value == pos[1] }, acc) }

            // The internet.
            "curl" -> {
                acc += CommandWarning.INTERNET
                acc.downloaders += c.id
                acc.anyDownloader = true
                if (hasOpt(v, 'o', "--output") || hasOpt(v, 'O', "--remote-name") || v.contains("--remote-name-all") || hasOpt(v, 'D', "--dump-header") ||
                    hasOpt(v, 'c', "--cookie-jar")) {
                    acc += CommandWarning.OVERWRITES
                    optionValue(args, setOf("-o", "--output", "--output-dir"))?.let { outsideWords(listOf(it), acc) }
                }
                if (optionValue(args, setOf("-u", "--user", "-U", "--proxy-user"))?.value?.contains(':') == true) acc += CommandWarning.SECRET
            }
            "wget", "wget2" -> {
                acc += CommandWarning.INTERNET
                acc.downloaders += c.id
                acc.anyDownloader = true
                val toStdout = v.any { it == "-O-" || it == "--output-document=-" || (it.startsWith("-") && !it.startsWith("--") && it.endsWith("O-")) } ||
                    optionValue(args, setOf("-O", "--output-document"))?.value == "-" || v.contains("--spider")
                if (!toStdout) {
                    acc += CommandWarning.OVERWRITES
                    optionValue(args, setOf("-O", "--output-document", "-P", "--directory-prefix"))?.let { outsideWords(listOf(it), acc) }
                }
                if (v.any { it.startsWith("--password=") || it.startsWith("--http-password=") || it.startsWith("--ftp-password=") }) acc += CommandWarning.SECRET
            }
            "aria2c", "axel", "lftp", "ftp", "tftp", "telnet", "lynx", "w3m", "links", "elinks", "http", "https", "xh", "yt-dlp", "youtube-dl",
            "rclone", "gh", "glab", "mosh", "autossh", "sshfs", "ssh-copy-id", "ssh-keyscan", "nmap", "dig", "nslookup", "host", "whois", "traceroute",
            "tracepath", "mtr", "socat", "openssl", "tor" -> {
                if (prog != "openssl" || v.firstOrNull() == "s_client") acc += CommandWarning.INTERNET
                if (prog in setOf("lynx", "w3m", "links", "elinks", "http", "https", "xh")) { acc.downloaders += c.id; acc.anyDownloader = true }
                if (prog in setOf("lynx", "w3m", "links", "elinks", "mosh", "telnet", "ftp", "lftp") && !v.any { it.startsWith("-dump") || it == "-source" || it == "-c" })
                    acc += CommandWarning.NEEDS_TERMINAL
                if (prog in setOf("aria2c", "axel", "yt-dlp", "youtube-dl", "rclone")) acc += CommandWarning.OVERWRITES
                if (prog == "tor" || prog == "sshfs") acc += CommandWarning.KEEPS_RUNNING
            }
            "nc", "ncat", "netcat" -> {
                acc += CommandWarning.INTERNET
                acc.downloaders += c.id
                acc.anyDownloader = true
                if (hasOpt(v, 'l', "--listen")) acc += CommandWarning.RUNS_FOREVER
                if (hasOpt(v, 'e', "--exec") || hasOpt(v, 'c', "--sh-exec")) acc.complex = true
            }
            "ping", "ping6" -> {
                acc += CommandWarning.INTERNET
                if (!hasOpt(v, 'c') && !hasOpt(v, 'w')) acc += CommandWarning.RUNS_FOREVER
            }
            "ssh" -> ssh(args, acc, depth)
            "scp" -> { acc += CommandWarning.INTERNET; acc += CommandWarning.OVERWRITES }
            "sftp" -> { acc += CommandWarning.INTERNET; if (!hasOpt(v, 'b')) acc += CommandWarning.NEEDS_TERMINAL }
            "sshpass" -> {
                if (optionValue(args, setOf("-p")) != null || v.any { it.startsWith("-p") && it.length > 2 }) acc += CommandWarning.SECRET
                runInner(after(args, setOf("-f", "-d", "-p", "-P")), c, parse, acc, depth)
            }
            "sshd", "crond", "syncthing", "service-daemon", "termux-wake-lock", "at", "batch", "atd" -> acc += CommandWarning.KEEPS_RUNNING
            "sv", "sv-enable" -> if (pos.firstOrNull() in setOf("up", "start", "once", "u", "o") || prog == "sv-enable") acc += CommandWarning.KEEPS_RUNNING
            "crontab" -> when {
                hasOpt(v, 'r') -> acc += CommandWarning.DELETES
                hasOpt(v, 'e') -> acc += CommandWarning.NEEDS_TERMINAL
                hasOpt(v, 'l') -> {}
                else -> acc += CommandWarning.KEEPS_RUNNING
            }
            "screen" -> if (v.any { it == "-d" || it.startsWith("-dm") || it == "-m" || it.startsWith("-Dm") }) acc += CommandWarning.KEEPS_RUNNING else acc += CommandWarning.NEEDS_TERMINAL
            "tmux" -> if (v.any { it == "-d" } || pos.firstOrNull() in setOf("kill-server", "kill-session", "ls", "list-sessions")) {
                if (v.any { it == "-d" }) acc += CommandWarning.KEEPS_RUNNING
            } else acc += CommandWarning.NEEDS_TERMINAL

            // Packages.
            "pkg" -> packages(pos.firstOrNull(), v, acc, network = setOf("install", "i", "add", "reinstall", "upgrade", "update", "search", "download"),
                change = setOf("install", "i", "add", "reinstall", "upgrade", "update", "uninstall", "remove", "rm", "autoclean", "clean"))
            "apt", "apt-get" -> packages(pos.firstOrNull(), v, acc,
                network = setOf("install", "reinstall", "upgrade", "full-upgrade", "dist-upgrade", "update", "download", "source", "build-dep"),
                change = setOf("install", "reinstall", "remove", "purge", "autoremove", "upgrade", "full-upgrade", "dist-upgrade", "autoclean", "clean", "update"))
            "dpkg" -> if (v.any { it in setOf("-i", "--install", "-r", "--remove", "-P", "--purge", "--configure", "--unpack") }) acc += CommandWarning.PACKAGES
            "pacman" -> if (v.any { it.startsWith("-S") || it.startsWith("-R") || it.startsWith("-U") }) {
                acc += CommandWarning.PACKAGES
                if (v.any { it.startsWith("-S") || it.startsWith("-U") }) acc += CommandWarning.INTERNET
            }
            "pip", "pip3", "pipx", "uv", "gem", "cargo", "go", "conda", "mamba", "cpan", "cpanm", "luarocks", "composer" ->
                languagePackages(prog, pos, v, acc)
            "npm", "pnpm", "yarn", "bun" -> jsPackages(prog, pos, v, acc)
            "npx", "pnpx", "bunx", "uvx" -> { acc += CommandWarning.INTERNET; acc += CommandWarning.INTERNET_CODE; acc.complex = true }
            "termux-change-repo" -> { acc += CommandWarning.PACKAGES; acc += CommandWarning.NEEDS_TERMINAL }
            "termux-reset" -> { acc += CommandWarning.DELETES; acc += CommandWarning.PACKAGES }
            "termux-restore" -> { acc += CommandWarning.DELETES; acc += CommandWarning.OVERWRITES }
            "termux-backup" -> { acc += CommandWarning.OVERWRITES; outside(pos, args, acc) }
            "termux-open-url" -> acc += CommandWarning.INTERNET

            // Endless or interactive.
            "yes" -> if (!pipedInto(c, parse, "head")) acc += CommandWarning.RUNS_FOREVER
            "tail" -> if (hasOpt(v, 'f', "--follow") || hasOpt(v, 'F')) acc += CommandWarning.RUNS_FOREVER
            "cat" -> if (pos.any { it in setOf("/dev/zero", "/dev/urandom", "/dev/random") } && !pipedInto(c, parse, "head")) acc += CommandWarning.RUNS_FOREVER
            "sleep" -> if (pos.any { it.startsWith("inf") }) acc += CommandWarning.RUNS_FOREVER
            "logcat" -> if (!hasOpt(v, 'd') && !hasOpt(v, 't') && !hasOpt(v, 'T')) acc += CommandWarning.RUNS_FOREVER
            "journalctl" -> if (hasOpt(v, 'f', "--follow")) acc += CommandWarning.RUNS_FOREVER
            "top" -> if (!hasOpt(v, 'b') || !(hasOpt(v, 'n') || v.any { it.startsWith("-bn") })) { acc += CommandWarning.NEEDS_TERMINAL; acc += CommandWarning.RUNS_FOREVER }
            "emacs" -> if (!v.contains("--batch") && !v.contains("-batch")) acc += CommandWarning.NEEDS_TERMINAL
            "ssh-keygen" -> if (!(hasOpt(v, 'N') && hasOpt(v, 'f')) && !hasOpt(v, 'l') && !hasOpt(v, 'y') && !hasOpt(v, 'F') && !hasOpt(v, 'R')) acc += CommandWarning.NEEDS_TERMINAL
            "sqlite3", "mysql", "mariadb", "psql", "redis-cli" -> if (pos.size < 2) acc += CommandWarning.NEEDS_TERMINAL
            in INTERACTIVE -> acc += CommandWarning.NEEDS_TERMINAL

            else -> TermuxApi.of(prog)?.let { api(prog, it, acc) }
        }
    }

    private fun runInner(words: List<ShellWord>, c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int) {
        val first = words.firstOrNull() ?: return
        if (!first.literal) { acc.complex = true; return }
        invoke(first.value, words.drop(1), c, parse, acc, depth)
    }

    private fun api(prog: String, api: TermuxApi, acc: Acc) {
        acc.api += api
        when (api) {
            TermuxApi.SENDS -> acc += CommandWarning.SENDS
            TermuxApi.PERSONAL -> acc += CommandWarning.PERSONAL_DATA
            TermuxApi.CAPTURES -> acc += CommandWarning.CAPTURES
            TermuxApi.RUNS_LATER -> acc += CommandWarning.KEEPS_RUNNING
            TermuxApi.CHANGES_PHONE -> when (prog) {
                "termux-saf-rm" -> acc += CommandWarning.DELETES
                "termux-saf-write", "termux-saf-create", "termux-saf-mkdir" -> acc += CommandWarning.OVERWRITES
                "termux-download" -> acc += CommandWarning.INTERNET
            }
            TermuxApi.HARMLESS -> {}
        }
    }

    /** Programs whose effect Robot can't read from their arguments: build tools, sandboxes, Android's own shell tools. */
    private val OPAQUE = setOf("make", "gmake", "ninja", "just", "task", "script", "expect", "proot", "proot-distro", "chroot", "unshare", "nsenter",
        "runuser", "setarch", "am", "cmd", "pm", "settings", "content", "input", "svc", "wm", "appops", "dumpsys", "termux-am", "gdb", "lldb")

    /** Options that name a program to run (ssh's ProxyCommand, tar's --to-command, rsync's -e…): the command is complex. */
    private fun runsAnotherProgram(prog: String, v: List<String>): Boolean {
        fun opt(vararg o: String) = v.any { a -> o.any { a == it || a.startsWith("$it=") || (it.length == 2 && a.startsWith(it) && !a.startsWith("--")) } }
        return when (prog) {
            "ssh", "scp", "sftp", "autossh", "sshfs" -> v.any { it.contains("command", ignoreCase = true) } || opt("-F", "-S")
            "rsync" -> opt("-e", "--rsh", "--rsync-path")
            "tar" -> opt("-I", "--use-compress-program", "--to-command", "--checkpoint-action", "--rsh-command", "--info-script", "--new-volume-script", "-F")
            "zip", "unzip" -> opt("-TT", "--unzip-command")
            "sort" -> opt("--compress-program")
            "split" -> opt("--filter")
            "man" -> opt("-P", "--pager", "-H", "--html")
            "less", "more" -> v.any { it.startsWith("+") }
            "git" -> {
                val sub = v.firstOrNull { !it.startsWith("-") }
                opt("--exec", "--extcmd", "--upload-pack", "--receive-pack") || sub == "bisect" || sub == "difftool" || (sub == "rebase" && opt("-x"))
            }
            "curl" -> opt("-K", "--config")
            "vim", "vi", "nvim", "emacs" -> opt("-c", "--cmd", "--eval", "-S")
            else -> false
        }
    }

    /** An argument that is a web address (http: or https:), not a file. */
    private fun webAddress(a: String): Boolean = a.startsWith("http:") || a.startsWith("https:")

    private val SHELLS = setOf("sh", "bash", "zsh", "dash", "ksh", "mksh", "ash", "fish", "tcsh", "csh", "yash")
    private val INTERPRETERS = setOf("python", "python2", "python3", "perl", "ruby", "node", "nodejs", "php", "lua", "luajit", "lua5.4", "tclsh", "Rscript",
        "julia", "deno", "ts-node", "irb", "ipython", "ipython3", "pypy", "pypy3", "guile", "racket", "scheme", "clisp", "sbcl", "ghci", "R")
    private val INTERACTIVE = setOf("vim", "vi", "nvim", "nano", "micro", "joe", "ne", "mg", "kak", "hx", "helix", "less", "more", "most", "man", "info", "htop",
        "btop", "atop", "glances", "nethack", "mc", "ranger", "nnn", "lf", "vifm", "fzf", "tig", "lazygit", "cmatrix", "passwd", "login", "visudo", "vipw",
        "irssi", "weechat", "tmate", "bc", "gdb", "vimtutor", "alsamixer", "newsboat", "mutt", "neomutt", "aerc", "cfdisk")

    /** sh/bash -c 'text' (read as a script), a script file (unknown), or code from stdin (a here-document, a pipe). */
    private fun shell(args: List<ShellWord>, c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int) {
        acc.complex = true
        var k = 0
        var dashC = false
        var dashS = false
        while (k < args.size) {
            val a = args[k].value
            if (a == "--" || a == "-") { k++; break }
            if (a.startsWith("--")) { if (a == "--rcfile" || a == "--init-file") k++; k++; continue }
            if ((a.startsWith("-") || a.startsWith("+")) && a.length > 1) {
                if ('c' in a) dashC = true
                if ('s' in a && a.startsWith("-")) dashS = true
                if (a.startsWith("-") && ('o' in a || 'O' in a)) k++
                k++
                continue
            }
            break
        }
        val rest = args.drop(k)
        when {
            dashC -> {
                acc.argCode += c.id
                val code = rest.firstOrNull()
                if (code == null) acc.complex = true else if (code.literal) script(code.value, acc, depth) else acc.complex = true
            }
            rest.isNotEmpty() && !dashS -> {
                // A script file, or a <( ) it reads as one.
                acc.argCode += c.id
            }
            else -> stdinCode(c, parse, acc, depth, shell = true)
        }
    }

    /** A shell or interpreter reading its program from stdin: a here-document or string is read as a script; a pipe isn't known. */
    private fun stdinCode(c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int, shell: Boolean) {
        val doc = c.redirects.lastOrNull { it.op == "<<" || it.op == "<<-" || it.op == "<<<" || it.op == "<" }
        acc.complex = true
        when {
            doc != null && !shell -> {}
            doc != null && doc.op != "<" && doc.op != "<<<" -> doc.heredoc?.let { script(it, acc, depth) }
            doc != null && doc.op == "<<<" -> doc.target?.let { if (it.literal) script(it.value, acc, depth) }
            doc != null -> {}
            c.stage > 0 -> {
                acc.stdinCode = true
                val before = parse.commands.firstOrNull { it.pipeline == c.pipeline && it.stage == c.stage - 1 }
                val name = before?.name?.value
                if (shell && (name == "echo" || name == "printf") && before.args.all { it.literal }) {
                    script(before.args.filter { !it.value.startsWith("-") }.joinToString(" ") { it.value }.replace("\\n", "\n"), acc, depth)
                }
            }
            c.parent != null -> acc.stdinCode = true
            else -> acc += CommandWarning.NEEDS_TERMINAL
        }
    }

    private fun interpreter(prog: String, args: List<ShellWord>, c: ShellCommand, parse: ShellParse, acc: Acc) {
        val v = args.map { it.value }
        if (v.any { webAddress(it) }) {
            acc += CommandWarning.INTERNET
            acc += CommandWarning.INTERNET_CODE
        }
        if (prog.startsWith("python") || prog.startsWith("pypy")) {
            val m = v.indexOf("-m")
            if (m >= 0) {
                when (v.getOrNull(m + 1)) {
                    "pip" -> { languagePackages("pip", positionals(v.drop(m + 2)), v.drop(m + 2), acc); return }
                    "http.server", "SimpleHTTPServer" -> { acc += CommandWarning.INTERNET; acc += CommandWarning.RUNS_FOREVER }
                    "venv", "virtualenv" -> acc += CommandWarning.OVERWRITES
                }
            }
        }
        if (prog == "perl" && v.any { it == "-i" || (it.startsWith("-") && !it.startsWith("--") && it.drop(1).startsWith("i")) }) acc += CommandWarning.OVERWRITES
        val code = v.withIndex().firstOrNull { (k, a) -> k + 1 < v.size && a in setOf("-c", "-e", "-E", "-r", "--eval", "-p", "--print") }?.let { v[it.index + 1] }
        if (code != null && "fork" in code && ("while" in code || "loop" in code || "for(;;)" in code || "for (;;)" in code)) acc += CommandWarning.FORK_BOMB
        acc.complex = true
        if (args.isEmpty() || (args.size == 1 && v[0] == "-")) {
            stdinCode(c, parse, acc, 0, shell = false)
        } else {
            acc.argCode += c.id
        }
    }

    private fun awk(args: List<ShellWord>, acc: Acc) {
        val v = args.map { it.value }
        if (hasOpt(v, 'f', "--file")) { acc.complex = true; return }
        if (v.any { it == "inplace" } ) acc += CommandWarning.OVERWRITES
        val program = after(args, setOf("-F", "-v", "--field-separator", "--assign", "-i", "--include")).firstOrNull() ?: return
        if (!program.literal) { acc.complex = true; return }
        val p = program.value
        if ("system" in p || '|' in p || "getline" in p) acc.complex = true
        if (printsToFile(p.take(4096))) { acc.complex = true; acc += CommandWarning.OVERWRITES }
    }

    /** An awk program with print > "file" (a > after print, before the statement ends, outside a string). */
    private fun printsToFile(p: String): Boolean {
        var at = p.indexOf("print")
        while (at >= 0) {
            var k = at + 5
            var quoted = false
            while (k < p.length && (quoted || p[k] !in ";}\n")) {
                if (p[k] == '"') quoted = !quoted
                if (!quoted && p[k] == '>') return true
                k++
            }
            at = p.indexOf("print", k)
        }
        return false
    }

    private fun sed(args: List<ShellWord>, acc: Acc) {
        val v = args.map { it.value }
        if (v.any { it == "--in-place" || it.startsWith("--in-place=") || (it.startsWith("-") && !it.startsWith("--") && 'i' in it.drop(1).substringBefore('.')) }) {
            acc += CommandWarning.OVERWRITES
            outside(positionals(v).drop(if (hasOpt(v, 'e', "--expression") || hasOpt(v, 'f', "--file")) 0 else 1), args, acc)
        }
        if (hasOpt(v, 'f', "--file")) { acc.complex = true; return }
        val scripts = ArrayList<String>()
        var k = 0
        while (k < v.size) {
            if ((v[k] == "-e" || v[k] == "--expression") && k + 1 < v.size) { scripts += v[k + 1]; k += 2; continue }
            if (v[k].startsWith("--expression=")) scripts += v[k].substringAfter('=')
            k++
        }
        if (scripts.isEmpty()) positionals(v).firstOrNull()?.let { scripts += it }
        for (s in scripts) for (piece in s.split(';', '\n')) {
            val t = piece.trim()
            if (t.isEmpty()) continue
            if (t[0] == 'e' || ((t[0] == 'w' || t[0] == 'W') && (t.length == 1 || t[1] == ' '))) { acc.complex = true; if (t[0] != 'e') acc += CommandWarning.OVERWRITES }
            if (t[0] == 's' && t.length > 1) {
                val d = t[1]
                var seen = 0
                var j = 2
                while (j < t.length && seen < 2) { if (t[j] == '\\') j++ else if (t[j] == d) seen++; j++ }
                val flags = if (seen == 2) t.substring(minOf(j, t.length)).takeWhile { it.isLetterOrDigit() } else ""
                if ('e' in flags) acc.complex = true
                if ('w' in flags) { acc.complex = true; acc += CommandWarning.OVERWRITES }
            }
        }
    }

    private fun find(args: List<ShellWord>, c: ShellCommand, parse: ShellParse, acc: Acc, depth: Int) {
        val v = args.map { it.value }
        val starts = args.takeWhile { !it.value.startsWith("-") && it.value != "(" && it.value != "!" && it.value != ")" }
        if ("-delete" in v) {
            acc += CommandWarning.DELETES
            outsideWords(starts.ifEmpty { listOf() }, acc)
        }
        var k = 0
        while (k < args.size) {
            when (v[k]) {
                "-exec", "-execdir", "-ok", "-okdir" -> {
                    val end = (k + 1 until args.size).firstOrNull { v[it] == ";" || v[it] == "+" } ?: args.size
                    val inner = args.subList(k + 1, end)
                    if (inner.isNotEmpty()) {
                        val before = acc.warnings.contains(CommandWarning.DELETES)
                        runInner(inner, c, parse, acc, depth)
                        if (!before && CommandWarning.DELETES in acc.warnings) outsideWords(starts, acc)
                    }
                    k = end + 1
                    continue
                }
                "-fprint", "-fprint0", "-fprintf", "-fls" -> {
                    acc += CommandWarning.OVERWRITES
                    args.getOrNull(k + 1)?.let { outsideWords(listOf(it), acc) }
                }
            }
            k++
        }
    }

    private fun git(args: List<ShellWord>, acc: Acc) {
        val v = args.map { it.value }
        var k = 0
        var dir: ShellWord? = null
        while (k < v.size && v[k].startsWith("-")) {
            when {
                v[k] == "-C" -> { dir = args.getOrNull(k + 1); k += 2; continue }
                v[k] == "-c" || v[k].startsWith("--config-env") || v[k].startsWith("--exec-path") -> {
                    // Configuration on the command line can name programs to run (core.sshCommand, core.pager…).
                    acc.complex = true
                    if (v[k] == "-c") k++
                }
                v[k] == "--git-dir" || v[k] == "--work-tree" || v[k] == "--namespace" -> k++
            }
            k++
        }
        val sub = v.getOrNull(k) ?: return
        val rest = v.drop(k + 1)
        fun opt(vararg o: String) = rest.any { a -> o.any { a == it || a.startsWith("$it=") } }
        when (sub) {
            "clone", "pull" -> {
                acc += CommandWarning.INTERNET
                acc += CommandWarning.OVERWRITES
                // What it brings may be run next (git clone … && ./install.sh).
                acc.anyDownloader = true
            }
            "push", "fetch", "ls-remote", "send-email", "request-pull" -> acc += CommandWarning.INTERNET
            "remote" -> if (rest.firstOrNull() in setOf("update", "prune", "show") ) acc += CommandWarning.INTERNET
            "submodule" -> { if (rest.firstOrNull() in setOf("update", "sync", "add")) acc += CommandWarning.INTERNET; if (rest.firstOrNull() == "foreach") acc.complex = true }
            "archive" -> if (opt("--remote")) acc += CommandWarning.INTERNET
            "clean" -> if (!opt("-n", "--dry-run")) acc += CommandWarning.DELETES
            "reset" -> acc += if (opt("--hard", "--merge", "--keep")) CommandWarning.DELETES else CommandWarning.OVERWRITES
            "checkout" -> acc += if (opt("-f", "--force", "--", ".")) CommandWarning.DELETES else CommandWarning.OVERWRITES
            "restore", "rm" -> acc += CommandWarning.DELETES
            "stash" -> acc += if (rest.firstOrNull() in setOf("drop", "clear")) CommandWarning.DELETES else if (rest.firstOrNull() in setOf("list", "show")) return else CommandWarning.OVERWRITES
            "branch" -> if (opt("-d", "-D", "--delete")) acc += CommandWarning.DELETES else if (rest.any { !it.startsWith("-") } || opt("-m", "-M", "-c", "-C")) acc += CommandWarning.OVERWRITES
            "prune" -> acc += CommandWarning.DELETES
            "gc" -> if (rest.any { it.startsWith("--prune") }) acc += CommandWarning.DELETES
            "reflog" -> if (rest.firstOrNull() in setOf("expire", "delete")) acc += CommandWarning.DELETES
            "merge", "rebase", "switch", "apply", "am", "cherry-pick", "revert", "mv", "filter-branch" -> {
                acc += CommandWarning.OVERWRITES
                if (sub == "rebase" && opt("-i", "--interactive")) acc += CommandWarning.NEEDS_TERMINAL
            }
            "config" -> if (!opt("--get", "--get-all", "--list", "-l", "--get-regexp") && rest.count { !it.startsWith("-") } >= 2) acc += CommandWarning.OVERWRITES
            "commit" -> if (!opt("-m", "--message", "-F", "--file", "-C", "--reuse-message", "--no-edit") && rest.none { it.startsWith("-m") || it.startsWith("-am") })
                acc += CommandWarning.NEEDS_TERMINAL
            "add" -> if (opt("-p", "--patch", "-i", "--interactive")) acc += CommandWarning.NEEDS_TERMINAL
            "mergetool", "difftool" -> acc += CommandWarning.NEEDS_TERMINAL
        }
        if (dir != null && (CommandWarning.OVERWRITES in acc.warnings || CommandWarning.DELETES in acc.warnings)) outsideWords(listOf(dir), acc)
    }

    private fun ssh(args: List<ShellWord>, acc: Acc, depth: Int) {
        acc += CommandWarning.INTERNET
        val v = args.map { it.value }
        val withValue = "bcDEeFIiJLlmOopQRSWw"
        var k = 0
        var noCommand = false
        while (k < v.size && v[k].startsWith("-") && v[k].length > 1) {
            val a = v[k]
            if (a == "--") { k++; break }
            if ('N' in a) noCommand = true
            if ('f' in a) acc += CommandWarning.KEEPS_RUNNING
            k += if (a.length == 2 && a[1] in withValue) 2 else 1
        }
        val remote = args.drop(k + 1)
        when {
            k >= args.size -> acc += CommandWarning.NEEDS_TERMINAL
            remote.isEmpty() && !noCommand -> acc += CommandWarning.NEEDS_TERMINAL
            remote.isNotEmpty() -> {
                // The command runs on the other computer: its own warnings still say what it does there.
                if (remote.any { !it.literal } || depth >= MAX_NESTING) { acc.complex = true; return }
                val text = remote.joinToString(" ") { it.value }
                val parse = ShellLexer.parse(text)
                val there = Acc()
                scan(parse, there, depth + 1)
                if (there.complex || parse.features.any { it in COMPLEX } || hidden(text)) acc.complex = true
                if (SecretMask.find(text).isNotEmpty()) acc += CommandWarning.SECRET
                acc.warnings += there.warnings.filter { it in REMOTE_KEPT }
            }
        }
    }

    private val REMOTE_KEPT = EnumSet.of(CommandWarning.DELETES, CommandWarning.OVERWRITES, CommandWarning.INTERNET_CODE, CommandWarning.PACKAGES,
        CommandWarning.ROOT, CommandWarning.KEEPS_RUNNING, CommandWarning.RUNS_FOREVER, CommandWarning.FORK_BOMB, CommandWarning.SECRET, CommandWarning.HIDDEN_CHARACTERS)

    private fun archive(prog: String, v: List<String>, args: List<ShellWord>, acc: Acc) {
        val toStdout = hasOpt(v, 'c', "--stdout") || hasOpt(v, 't', "--test") || hasOpt(v, 'l', "--list")
        when (prog) {
            "tar" -> {
                val first = v.firstOrNull().orEmpty()
                val mode = if (first.isNotEmpty() && !first.startsWith("-")) first else v.filter { it.startsWith("-") && !it.startsWith("--") }.joinToString("")
                if ('x' in mode || 'c' in mode || 'r' in mode || 'u' in mode || v.any { it in setOf("--extract", "--get", "--create", "--append", "--update") }) {
                    acc += CommandWarning.OVERWRITES
                    optionValue(args, setOf("-C", "--directory"))?.let { outsideWords(listOf(it), acc) }
                }
            }
            "unzip" -> if (!hasOpt(v, 'l') && !hasOpt(v, 'p') && !hasOpt(v, 't')) {
                acc += CommandWarning.OVERWRITES
                optionValue(args, setOf("-d"))?.let { outsideWords(listOf(it), acc) }
            }
            "zip", "7z", "7za" -> {
                acc += CommandWarning.OVERWRITES
                if (prog == "zip" && hasOpt(v, 'm')) acc += CommandWarning.DELETES
            }
            else -> if (!toStdout) { acc += CommandWarning.OVERWRITES; outside(positionals(v), args, acc) }
        }
    }

    private fun packages(sub: String?, v: List<String>, acc: Acc, network: Set<String>, change: Set<String>) {
        if (sub == null) return
        if (sub in network) acc += CommandWarning.INTERNET
        if (sub in change) {
            acc += CommandWarning.PACKAGES
            // Without -y it stops at "Do you want to continue? [Y/n]".
            if (sub != "update" && sub != "clean" && sub != "autoclean" && !v.any { it == "-y" || it == "--yes" || it == "--assume-yes" || (it.startsWith("-") && !it.startsWith("--") && 'y' in it) })
                acc += CommandWarning.NEEDS_TERMINAL
        }
    }

    private fun languagePackages(prog: String, pos: List<String>, v: List<String>, acc: Acc) {
        val sub = pos.firstOrNull() ?: return
        val second = pos.getOrNull(1)
        when {
            prog == "pipx" && sub == "run" -> { acc += CommandWarning.INTERNET; acc += CommandWarning.INTERNET_CODE; acc.complex = true }
            prog == "uv" && sub == "run" -> acc.complex = true
            prog == "uv" && (sub == "pip" || sub == "tool") && second in setOf("install", "uninstall", "sync") -> { acc += CommandWarning.PACKAGES; if (second != "uninstall") acc += CommandWarning.INTERNET }
            prog == "go" && sub in setOf("install", "get") -> { acc += CommandWarning.PACKAGES; acc += CommandWarning.INTERNET }
            prog == "go" && sub in setOf("run", "generate") -> acc.complex = true
            prog == "cargo" && sub in setOf("install", "uninstall") -> { acc += CommandWarning.PACKAGES; if (sub == "install") acc += CommandWarning.INTERNET }
            prog == "cargo" && sub in setOf("build", "fetch", "update", "run", "test") -> { acc += CommandWarning.INTERNET; if (sub == "run" || sub == "test") acc.complex = true }
            sub in setOf("install", "uninstall", "remove", "update", "upgrade", "add") -> {
                acc += CommandWarning.PACKAGES
                if (sub != "uninstall" && sub != "remove") acc += CommandWarning.INTERNET
                if (prog in setOf("conda", "mamba") && !v.any { it == "-y" || it == "--yes" }) acc += CommandWarning.NEEDS_TERMINAL
            }
            sub in setOf("download", "wheel", "search", "fetch") -> acc += CommandWarning.INTERNET
        }
    }

    private fun jsPackages(prog: String, pos: List<String>, v: List<String>, acc: Acc) {
        val sub = pos.firstOrNull()
        when {
            sub == null -> if (prog == "yarn") { acc += CommandWarning.INTERNET; acc += CommandWarning.PACKAGES }
            sub in setOf("install", "i", "add", "ci", "update", "up", "upgrade", "link", "rebuild") -> { acc += CommandWarning.INTERNET; acc += CommandWarning.PACKAGES }
            sub in setOf("uninstall", "remove", "rm", "un", "unlink", "prune") -> acc += CommandWarning.PACKAGES
            sub in setOf("exec", "x", "dlx", "create", "init") && v.size > 1 -> { acc += CommandWarning.INTERNET; acc += CommandWarning.INTERNET_CODE; acc.complex = true }
            sub in setOf("publish", "view", "info", "search", "outdated", "audit", "login") -> acc += CommandWarning.INTERNET
            sub in setOf("run", "run-script", "start", "test", "t", "restart", "stop") || (prog == "yarn" && sub !in setOf("list", "why", "info")) || prog == "bun" -> acc.complex = true
        }
    }

    // --- Helpers --------------------------------------------------------------------------------------------------

    /** Arguments that aren't options (everything after "--"). */
    private fun positionals(v: List<String>): List<String> {
        val out = ArrayList<String>()
        var rest = false
        for (a in v) {
            if (!rest && a == "--") { rest = true; continue }
            if (rest || !a.startsWith("-") || a == "-") out += a
        }
        return out
    }

    /** A short option [short] (alone or in a cluster such as -rf) or a long one ([long], alone or with =). */
    private fun hasOpt(v: List<String>, short: Char?, vararg long: String): Boolean {
        for (a in v) {
            if (a == "--") return false
            if (a.startsWith("--")) { if (long.any { a == it || a.startsWith("$it=") }) return true; continue }
            if (short != null && a.startsWith("-") && a.length > 1 && short in a.substring(1)) return true
        }
        return false
    }

    /** The value after an option among [names] (separate, or attached with = for a long one), or null. */
    private fun optionValue(args: List<ShellWord>, names: Set<String>): ShellWord? {
        for ((k, w) in args.withIndex()) {
            val a = w.value
            if (a == "--") return null
            if (a in names) return args.getOrNull(k + 1)
            val eq = a.indexOf('=')
            if (a.startsWith("--") && eq > 0 && a.substring(0, eq) in names) {
                return ShellWord(a.substring(eq + 1), a.substring(eq + 1), w.quoted, w.params, w.substitutes, w.glob, false, w.ansiC)
            }
        }
        return null
    }

    /** What follows a wrapper's own options ([withValue]: those that take the next word). */
    private fun after(args: List<ShellWord>, withValue: Set<String>): List<ShellWord> {
        var k = 0
        while (k < args.size) {
            val a = args[k].value
            if (a == "--") { k++; break }
            if (a.startsWith("-") && a.length > 1) {
                k += if (a in withValue) 2 else 1
                continue
            }
            break
        }
        return if (k >= args.size) emptyList() else args.subList(k, args.size)
    }

    /** [words] that write somewhere outside Termux's home make [CommandWarning.OUTSIDE_HOME]; an unknown one, complex. */
    private fun outsideWords(words: List<ShellWord>, acc: Acc) {
        for (w in words) {
            val path = TermuxPaths.resolve(w)
            if (path == null) { acc.complex = true; continue }
            if (!TermuxPaths.writableInside(path)) acc += CommandWarning.OUTSIDE_HOME
        }
    }

    /** The positional arguments [pos] of [args], checked as places written to. */
    private fun outside(pos: List<String>, args: List<ShellWord>, acc: Acc) {
        val set = pos.toHashSet()
        var rest = false
        val words = args.filter { w ->
            if (!rest && w.value == "--") { rest = true; false } else (rest || !w.value.startsWith("-") || w.value == "-") && w.value in set
        }
        outsideWords(words, acc)
    }

    /** Any absolute or home path among [args] (a script's arguments, unknown use) outside home: said, to be safe. */
    private fun outsideIfAny(args: List<ShellWord>, acc: Acc) {
        for (w in args) {
            if (!(w.value.startsWith("/") || w.tilde)) continue
            val path = TermuxPaths.resolve(w) ?: continue
            if (!TermuxPaths.writableInside(path) && !TermuxPaths.inPrefix(path)) acc += CommandWarning.OUTSIDE_HOME
        }
    }

    /** A path written as text (dd's of=…): inside Termux's home? Unknown counts as outside. */
    private fun writableValue(value: String): Boolean {
        val path = when {
            value == "~" || value.startsWith("~/") -> TermuxPaths.resolve(TermuxPaths.HOME + value.substring(1))
            '$' in value || '`' in value || value.startsWith("~") -> return false
            else -> TermuxPaths.resolve(value)
        }
        return TermuxPaths.writableInside(path)
    }

    /** rsync's source or destination on another computer (host:path, rsync://). */
    private fun remote(pos: List<String>): Boolean = pos.any { it.startsWith("rsync://") || (':' in it && !it.startsWith("/") && it.indexOf(':') < (it.indexOf('/').takeIf { s -> s >= 0 } ?: Int.MAX_VALUE)) }

    /** [c] is piped into [program] later in its pipeline (yes | head). */
    private fun pipedInto(c: ShellCommand, parse: ShellParse, program: String): Boolean =
        parse.commands.any { it.pipeline == c.pipeline && it.stage > c.stage && it.name?.value == program }

    private val PRIVATE_DIRS = setOf(".ssh", ".gnupg", ".password-store", ".termux", ".aws", ".kube", ".docker", ".pki")
    private val PRIVATE_FILES = setOf(".env", ".netrc", "_netrc", ".git-credentials", ".pgpass", ".npmrc", ".pypirc", ".my.cnf", "credentials", ".htpasswd",
        "authorized_keys")
    private val PRIVATE_SUFFIXES = listOf(".pem", ".key", ".p12", ".pfx", ".jks", ".keystore", ".kdbx", ".gpg", ".ovpn")

    private fun privateWord(w: ShellWord): Boolean {
        if (privatePath(w.value)) return true
        val eq = w.value.indexOf('=')
        return eq > 0 && privatePath(w.value.substring(eq + 1))
    }

    /**
     * [value] names a private file (B7.2): under ~/.ssh, ~/.gnupg, ~/.password-store, ~/.termux…, a key or certificate
     * (*.pem, *.key, id_*), .env, .netrc, .git-credentials; or a glob that could reach into a hidden folder (~/.*).
     */
    fun privatePath(value: String): Boolean {
        if (value.isEmpty() || value.length > 4096) return false
        val parts = value.split('/')
        for ((k, raw) in parts.withIndex()) {
            val p = raw.trim('"', '\'')
            if (p.isEmpty()) continue
            if (p in PRIVATE_DIRS) return true
            if (p.startsWith(".") && p.length > 1 && p != ".." && p.any { it == '*' || it == '?' || it == '[' }) return true
            if (k == parts.size - 1 || parts.drop(k + 1).all { it.isEmpty() }) {
                if (p in PRIVATE_FILES || p.startsWith(".env.") || PRIVATE_SUFFIXES.any { p.endsWith(it) && p.length > it.length }) return true
                if (p.startsWith("id_") && p.length > 3 && !p.endsWith(".pub")) return true
            }
        }
        return value.contains(".config/gh/") || value.endsWith(".config/gh") || value.contains(".config/rclone")
    }

    /** Characters that hide or disguise text: bidi controls, zero-width and other format characters, control characters but newline and tab, odd spaces; look-alike letters. */
    fun hidden(text: String): Boolean {
        var k = 0
        var lastAscii = false
        while (k < text.length) {
            val cp = text.codePointAt(k)
            if (invisible(cp)) return true
            val type = Character.getType(cp)
            if ((type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) && lastAscii) return true
            if (cp in 0xFF01..0xFF5E || cp in 0x1D400..0x1D7FF) return true
            lastAscii = cp < 0x80
            k += Character.charCount(cp)
        }
        return lookAlike(text)
    }

    private fun invisible(cp: Int): Boolean {
        if (cp == '\n'.code || cp == '\t'.code) return false
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.PRIVATE_USE.toInt(), Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt() -> true
            Character.SPACE_SEPARATOR.toInt() -> cp != ' '.code
            else -> false
        }
    }

    /** A word mixing Latin letters with Cyrillic, Greek or other look-alike scripts ("rм", "сurl"). */
    private fun lookAlike(text: String): Boolean {
        for (word in text.split(' ', '\t', '\n', '/', '=', ';', '|', '&', '"', '\'')) {
            if (word.all { it.code < 0x80 }) continue
            var latin = false
            var other = false
            var k = 0
            while (k < word.length) {
                val cp = word.codePointAt(k)
                if (Character.isLetter(cp)) {
                    when (Character.UnicodeScript.of(cp)) {
                        Character.UnicodeScript.LATIN -> latin = true
                        Character.UnicodeScript.CYRILLIC, Character.UnicodeScript.GREEK, Character.UnicodeScript.ARMENIAN, Character.UnicodeScript.CHEROKEE,
                        Character.UnicodeScript.COPTIC, Character.UnicodeScript.GEORGIAN, Character.UnicodeScript.LISU -> other = true
                        else -> {}
                    }
                }
                k += Character.charCount(cp)
            }
            if (latin && other) return true
        }
        return false
    }
}
