// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import java.text.Normalizer
import java.util.Locale

// Pure. Your scripts in ~/robot as Robot knows them: the list
// Robot's fixed listing run writes (each executable file's name, its first comment line and its aliases), and which one
// your own words name ("run backup", "run my backup script", "lance mon script de sauvegarde"), so a script named in your
// words runs as termux_script without the model. Anything else ("run to the store") stays with Gemma.

/** One of your scripts: its file name in ~/robot, its first "#" comment line, and the other names a "# aliases:" line gives it. */
data class TermuxScriptInfo(val name: String, val comment: String? = null, val aliases: List<String> = emptyList())

/** What the listing run found: the scripts, or that ~/robot doesn't exist yet. */
data class ScriptListing(val scripts: List<TermuxScriptInfo>, val folderMissing: Boolean = false, val complete: Boolean = true)

/** Your words named a script: its file name, and the rest of what you said or typed after it (its arguments, as written). */
data class ScriptMatch(val name: String, val args: String)

object ScriptRegistry {
    /** At most this many scripts are kept and described to Gemma (B8.4: "at most 20 named"). */
    const val MAX_SCRIPTS = 20
    const val MAX_COMMENT = 120
    private const val MAX_ALIASES = 4

    /**
     * The fixed listing run (bash only, no other program): each executable regular file directly in ~/robot, its first
     * comment line that isn't the #! line, and a "# aliases: …" line, one tab-separated line each. Nothing is run.
     */
    const val LIST_SCRIPT = "d=\"\$HOME/robot\"\n" +
        "[ -d \"\$d\" ] || { echo robot-no-folder; exit 0; }\n" +
        "for f in \"\$d\"/*; do\n" +
        "  [ -f \"\$f\" ] && [ -x \"\$f\" ] || continue\n" +
        "  c=; a=; k=0\n" +
        "  while [ \$k -lt 30 ] && IFS= read -r l; do\n" +
        "    k=\$((k+1))\n" +
        "    case \"\$l\" in\n" +
        "      '#!'*) ;;\n" +
        "      '#'*) l=\"\${l#\\#}\"; l=\"\${l# }\"\n" +
        "        case \"\$l\" in [Aa]lias*:*) [ -z \"\$a\" ] && a=\"\${l#*:}\";; *) [ -z \"\$c\" ] && c=\"\$l\";; esac;;\n" +
        "    esac\n" +
        "  done < \"\$f\" 2>/dev/null\n" +
        "  printf 'robot-script\\t%s\\t%s\\t%s\\n' \"\${f##*/}\" \"\${c//\$'\\t'/ }\" \"\${a//\$'\\t'/ }\"\n" +
        "done\n" +
        "echo robot-end"

    /** What the card shows before the first listing run (B5.1: "a card the first time"). */
    const val LIST_SHOWN = "find ~/robot -maxdepth 1 -type f -perm -u+x  (and each script's first # comment line)"

    /** "Create it" for ~/robot. */
    const val MAKE_FOLDER = "mkdir -p ~/robot"

    /** The listing run's output read back; names Robot can't use safely are left out. */
    fun parse(output: String): ScriptListing {
        if (output.lineSequence().any { it.trim() == "robot-no-folder" }) return ScriptListing(emptyList(), folderMissing = true)
        val out = ArrayList<TermuxScriptInfo>()
        for (line in output.lineSequence()) {
            if (!line.startsWith("robot-script\t")) continue
            val p = line.split('\t')
            val name = p.getOrNull(1)?.trim().orEmpty()
            if (!TermuxScripts.validName(name) || out.any { it.name == name }) continue
            val comment = p.getOrNull(2)?.let(::tidy)?.takeIf { it.isNotEmpty() }?.take(MAX_COMMENT)
            val aliases = p.getOrNull(3)?.split(',', ';')?.map(::tidy)?.filter { it.length in 2..40 }?.distinct()?.take(MAX_ALIASES).orEmpty()
            out += TermuxScriptInfo(name, comment, aliases)
            if (out.size >= MAX_SCRIPTS) break
        }
        return ScriptListing(out.sortedBy { it.name.lowercase(Locale.ROOT) }, complete = output.lineSequence().any { it.trim() == "robot-end" })
    }

    private fun tidy(s: String): String = TermuxOutput.clean(s).replace(Regex("\\s+"), " ").trim()

    /** "backup — Copies my notes to the server". */
    fun line(s: TermuxScriptInfo): String = if (s.comment.isNullOrBlank()) s.name else "${s.name} — ${s.comment}"

    // ------------------------------------------------------------------ your words

    /** Run verbs, English and French, as plain words (no accents). */
    private val VERBS = setOf("run", "start", "launch", "execute", "exec", "lance", "lancer", "lances", "executer", "executes", "demarre", "demarrer")
    /** Allowed before the verb. */
    private val LEAD = listOf(listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("peux", "tu"),
        listOf("pourrais", "tu"), listOf("tu", "peux"), listOf("hey", "robot"), listOf("ok", "robot"), listOf("robot"), listOf("dis", "robot"))
    /** Between the verb and the name: "my", "the script", "mon script de", "le script". */
    private val BEFORE = setOf("my", "the", "mon", "ma", "le", "la", "script", "scripts", "de", "called", "named", "nomme", "appele", "for", "me")
    /** After the name: "script", "please", "for me", "now". */
    private val AFTER = listOf(listOf("script"), listOf("please"), listOf("for", "me"), listOf("now"), listOf("sil", "te", "plait"),
        listOf("sil", "vous", "plait"), listOf("stp"), listOf("svp"), listOf("maintenant"))

    /** [s] lower-case, accents and apostrophes off, "-", "_" and "." as spaces: how a name is compared with words. */
    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace("'", "").replace("’", "")

    private fun words(s: String): List<String> = plain(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

    /**
     * The script [text] names, with what follows its name as arguments ([ScriptMatch.args], your text as typed, unsplit);
     * null when [text] isn't "run <one of your scripts>" (an optional "please", "my", "the script", "mon script de"
     * around it). A name matches its words ("show-args" is "show args"), as do its aliases. Arguments are kept only when
     * [text] was typed with the name written exactly (spoken arguments are too easy to mishear: those go to Gemma).
     */
    fun match(text: String, scripts: List<TermuxScriptInfo>, typed: Boolean = false): ScriptMatch? {
        if (scripts.isEmpty() || text.isBlank() || text.length > 600) return null
        val w = words(text)
        var i = 0
        // Politeness and the wake phrase before the verb.
        loop@ while (i < w.size) {
            for (l in LEAD) if (at(w, i, l)) { i += l.size; continue@loop }
            break
        }
        if (i >= w.size || w[i] !in VERBS) return null
        i++
        var skipped = 0
        while (i < w.size && w[i] in BEFORE && skipped < 3 && !nameAt(w, i, scripts)) { i++; skipped++ }
        // The longest name (or alias) that starts here.
        var best: Pair<TermuxScriptInfo, Int>? = null
        for (s in scripts) for (n in listOf(s.name) + s.aliases) {
            val nw = words(n)
            if (nw.isNotEmpty() && at(w, i, nw) && (best == null || nw.size > best.second)) best = s to nw.size
        }
        val (script, len) = best ?: return null
        var j = i + len
        loop@ while (j < w.size) {
            for (a in AFTER) if (at(w, j, a)) { j += a.size; continue@loop }
            break
        }
        if (j == w.size) return ScriptMatch(script.name, "")
        if (!typed) return null
        // Typed with arguments: only "run <exact name> <arguments>", the arguments as written after the name.
        val m = Regex("""^\s*\S+\s+(?:(?:my|the|mon|ma|le|la)\s+)?(?:script\s+)?""" + Regex.escape(script.name) + """(?:\s+(.*))?$""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)).find(text.trim()) ?: return null
        val verb = words(text.trim().substringBefore(' ')).singleOrNull()
        if (verb !in VERBS) return null
        return ScriptMatch(script.name, m.groupValues[1].trim())
    }

    private fun nameAt(w: List<String>, i: Int, scripts: List<TermuxScriptInfo>): Boolean =
        scripts.any { s -> (listOf(s.name) + s.aliases).any { n -> words(n).let { it.isNotEmpty() && at(w, i, it) } } }

    private fun at(w: List<String>, i: Int, p: List<String>): Boolean = i + p.size <= w.size && p.indices.all { w[i + it] == p[it] }
}

/**
 * Whether a question calls the TERMUX tool group (B5.3): Termux, a terminal, the command line, a shell, a script, running
 * something, git, ssh, your server; in English and French. A word too many only costs the group's tokens (and only while
 * Settings › Termux is on); a word too few means Gemma can't offer a command.
 */
object TermuxRouter {
    private val WORDS = setOf("termux", "terminal", "shell", "bash", "script", "scripts", "run", "git", "ssh", "commande", "commandes", "lance",
        "lancer", "execute", "executer", "repo", "repository", "depot", "server", "serveur", "uptime", "grep", "ls", "df", "cron", "pkg", "apt",
        "rsync", "scp", "curl", "wget", "python", "chmod", "mkdir", "logs", "processus", "cpu", "htop", "top")
    private val PHRASES = listOf("command line", "ligne de commande", "my server", "mon serveur", "home server", "mon script", "my script", "in termux")

    fun calls(question: String): Boolean {
        val plain = ScriptRegistry.plain(question)
        val w = plain.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }
        if (w.any { it in WORDS }) return true
        val padded = " " + w.joinToString(" ") + " "
        return PHRASES.any { padded.contains(" $it ") }
    }
}
