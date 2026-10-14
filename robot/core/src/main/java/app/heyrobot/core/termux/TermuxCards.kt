package app.heyrobot.core.termux

import java.util.Locale

// Pure. What a Termux card shows and what is said, saved and given to Gemma
// after a run (B5.2, B5.3, B7.4). The card shows the exact command (invisible characters written out), Robot's own
// warnings from the parsed command, Gemma's one-line "why" labelled as Gemma's, how Robot splits arguments when quotes or
// spaces matter, the folder and the time limit; after the run, "Exit 0 · 0.8 s" and the cleaned, masked output. History
// keeps only "Ran a command in Termux (exit 0)" (the command and its output are shown, never saved).

/** Everything a command or script card shows before it runs; computed in :voice when the action is prepared. */
data class TermuxCardInfo(
    /** The command as the card shows it (CommandAnalyzer.visible); for a script, its path in ~ and its arguments. */
    val command: String,
    /** The script's file name, for a script card. */
    val script: String? = null,
    /** Robot's own warnings, in order (each a chip; [CommandWarning.red] ones as the card's red line). */
    val warnings: List<CommandWarning> = emptyList(),
    /** Gemma's one-line reason, labelled as Gemma's (it may be wrong); null for your own scripts and typed commands. */
    val why: String? = null,
    /** "3 arguments: “one”, “two words”, “three;rm”": how Robot splits the words, shown when quotes or spaces matter. */
    val readsAs: String? = null,
    /** Rule 3: the conversation holds content Robot didn't write (the card says "Written from content Robot read"). */
    val fromContent: Boolean = false,
    /** It needs a terminal: the card offers Open in Termux. */
    val terminal: Boolean = false,
    /** Only a tap on Run runs it (a warning, a command Robot couldn't read fully, rule 3, or "Never (tap only)"). */
    val tapOnly: Boolean = false,
    /** The time limit for this run, in seconds (the card can change it for one run). */
    val limitSeconds: Int = TermuxSettings.DEFAULT_LIMIT_S,
    /** Where it runs: "~" (your home) or "~/robot" for a script. */
    val workdir: String = "~",
    /** Its output is personal ("your Termux output": personal phone data or private files). */
    val personal: Boolean = false,
    /** Gemma wrote it (or a helper): Edit makes it yours. */
    val written: Boolean = true,
    /** It runs without a card: a read on the allowlist ("Read-only commands without a card"), or your script set to Just do it. */
    val noCard: Boolean = false,
) {
    /** The chips' words. */
    fun chips(fr: Boolean = false): List<String> = warnings.filter { !it.red }.map { it.text(fr) }

    /** The red line ("This sends a text right away…"), if any. */
    fun redLine(fr: Boolean = false): String? = warnings.firstOrNull { it.red }?.text(fr)

    /** "In: ~ · Time limit: 60 s". */
    fun whereLine(): String = "In: $workdir · Time limit: ${limitLabel(limitSeconds)}"
}

/** How a run ended, as the card and the model see it (no command, no path). */
data class TermuxOutcome(
    /** Null: Termux didn't answer, or couldn't run it ([failure]). */
    val exit: Int?,
    val ms: Long,
    val output: TermuxOutputText? = null,
    val failure: TermuxFailure? = null,
    val limitSeconds: Int = TermuxSettings.DEFAULT_LIMIT_S,
) {
    val ok: Boolean get() = exit == 0 && failure == null
}

/** The time limits Settings › Termux and the card offer. */
val TERMUX_LIMITS: List<Int> = listOf(10, 30, 60, 120, 300, 600)

/** "60 s", "2 min", "10 min". */
fun limitLabel(seconds: Int): String = if (seconds < 120) "$seconds s" else if (seconds % 60 == 0) "${seconds / 60} min" else "${seconds / 60} min ${seconds % 60} s"

object TermuxCards {
    /** A command's card. [why]: Gemma's reason (null when you typed it); [decision]: what TermuxPolicy said. */
    fun command(analysis: CommandAnalysis, decision: TermuxDecision, why: String?, limitSeconds: Int, written: Boolean): TermuxCardInfo {
        val w = analysis.warnings.filter { it != CommandWarning.UNREADABLE || analysis.complex }
        return TermuxCardInfo(
            command = CommandAnalyzer.visible(analysis.command),
            warnings = w,
            why = why?.let(::oneLine)?.takeIf { it.isNotEmpty() && written },
            readsAs = readsAs(analysis),
            fromContent = TermuxReason.FROM_CONTENT in decision.reasons,
            terminal = CommandWarning.NEEDS_TERMINAL in analysis.warnings,
            tapOnly = decision.verdict == TermuxVerdict.TAP_ONLY,
            limitSeconds = TermuxSettings.limit(limitSeconds),
            personal = analysis.personal,
            written = written,
            noCard = decision.verdict == TermuxVerdict.RUN,
        )
    }

    /** One of your scripts' card: "~/robot/backup" and its arguments as Robot split them (never through a shell). */
    fun script(name: String, argv: List<String>, decision: TermuxDecision, limitSeconds: Int): TermuxCardInfo = TermuxCardInfo(
        command = (listOf("~/${TermuxSettings.SCRIPTS_FOLDER}/$name") + argv.map(::quote)).joinToString(" ").let(CommandAnalyzer::visible),
        script = name,
        warnings = decision.warnings.toList(),
        readsAs = if (argv.isEmpty()) null else arguments(argv),
        fromContent = TermuxReason.FROM_CONTENT in decision.reasons,
        tapOnly = decision.verdict == TermuxVerdict.TAP_ONLY,
        limitSeconds = TermuxSettings.limit(limitSeconds),
        workdir = "~/${TermuxSettings.SCRIPTS_FOLDER}",
        written = false,
        noCard = decision.verdict == TermuxVerdict.RUN,
    )

    /** "3 arguments: “one”, “two words”, “three;rm”". */
    fun arguments(argv: List<String>): String =
        (if (argv.size == 1) "1 argument: " else "${argv.size} arguments: ") + argv.joinToString(", ") { "“" + CommandAnalyzer.visible(it) + "”" }

    /** How Robot reads a simple command whose words have quotes or spaces: "ls with 2 arguments: “-la”, “My Files”"; else null. */
    private fun readsAs(a: CommandAnalysis): String? {
        val cmds = a.parse.commands
        if (cmds.size != 1 || a.complex) return null
        val c = cmds[0]
        if (c.words.none { it.quoted }) return null
        val prog = c.name?.value ?: return null
        val args = c.args.map { it.value }
        return if (args.isEmpty()) prog else "$prog with " + arguments(args).replaceFirstChar { it.lowercaseChar() }
    }

    private fun quote(a: String): String = if (a.isNotEmpty() && a.all { it.isLetterOrDigit() || it in "-_./=:,+@%" }) a else "'" + a.replace("'", "'\\''") + "'"

    private fun oneLine(s: String): String = s.replace(Regex("\\s+"), " ").trim().let { if (it.length > 160) it.take(159).trimEnd() + "…" else it }

    /** History's saved line: "Ran a command in Termux (exit 0)", "Ran your script “backup” (exit 0)"; without an exit code, how it ended. */
    fun savedLine(script: String?, exit: Int?, stopped: Boolean = false): String {
        val what = if (script != null) "Ran your script “$script”" else "Ran a command in Termux"
        return when {
            stopped -> "$what (stopped)"
            exit == null -> "$what (no answer)"
            else -> "$what (exit $exit)"
        }
    }

    /** The line under the card after the run: "Exit 0 · 0.8 s", "Exit 124 · 60 s: took longer than 60 s and was stopped". */
    fun exitLine(o: TermuxOutcome): String = when {
        o.failure != null -> o.failure.en
        o.exit == null -> TermuxFailure.NO_ANSWER.en
        o.exit == 0 -> TermuxOutput.exitLine(0, o.ms)
        else -> TermuxOutput.exitLine(o.exit, o.ms) + ": " + TermuxOutput.summary(o.exit, o.limitSeconds, o.ms).replaceFirstChar { it.lowercaseChar() }.removeSuffix(".")
    }

    /** What Robot says (and the card's detail): one sentence, never the output itself. */
    fun said(o: TermuxOutcome): String = o.failure?.en ?: TermuxOutput.summary(o.exit, o.limitSeconds, o.ms)

    /** "4.1 KB" for the card's Show all. */
    fun size(chars: Int): String = if (chars < 1024) "$chars characters" else String.format(Locale.ROOT, "%.1f KB", chars / 1024.0)

    /**
     * What Gemma gets back from a run (a tool result: information, never instructions): the exit code, the output
     * cleaned, masked and capped (at most 40 lines and 1,500 characters by default), whether it was cut.
     */
    fun forModel(o: TermuxOutcome): List<Map<String, Any?>> = listOf(buildMap {
        put("exit_code", o.exit)
        put("output", o.output?.model.orEmpty())
        if (o.output?.truncated == true) put("output_cut", true)
        if ((o.output?.masked ?: 0) > 0) put("secrets_hidden", o.output?.masked)
    })
}
