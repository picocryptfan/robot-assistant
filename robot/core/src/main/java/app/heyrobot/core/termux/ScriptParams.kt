// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.connect.OpParam
import app.heyrobot.core.skills.LinkedScript
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillLinks
import app.heyrobot.core.skills.SlotType
import java.util.Locale

// Pure. What one of your scripts declares about itself for skills (docs/plans/skills-connections-plan.md §3.6, D18; C8):
// `# param:` and `# effect:` lines in its first 30 lines, which the listing run returns beside its name and comment.
//
//   # param: folder choice photos|documents|music "Which folder"
//   # param: days int 1..30 "Keep how many days"
//   # param: note text max=80 dash=ok "What to write"
//   # param: dry yes_no
//   # effect: online, deletes
//
// Types: `int min..max`, `choice a|b|c` (2 to 12 values), `text` (one line, `max=` 1 to 200 characters, 80 by default,
// never starting with "-" unless `dash=ok`) and `yes_no` (sent as "yes" or "no"). At most 3, each a name a skill's step
// can use. A skill's step gives each one value, and Robot sends them to Termux as an argument list in this order, one
// element each, never through a shell. A `# param:` line Robot can't read, a fourth one or a name used twice leaves the
// whole script untyped (its steps need attention): skipping one would move every later value to the wrong place.
// `# effect:` words only add to what the card and the tier count (running code always): online, deletes, sends, money;
// other words are ignored. Nothing here reaches a log.

/** One `# param:` line: [name], its type and limits ([min], [max] for an int; [options] for a choice; [maxLen], [dashOk] for text), and your [label]. */
data class ScriptParam(
    val name: String,
    val type: ParamType,
    val options: List<String> = emptyList(),
    val min: Long? = null,
    val max: Long? = null,
    val maxLen: Int? = null,
    val dashOk: Boolean = false,
    val label: String? = null,
) {
    /** The slot a step's value fills (SkillCheck's and SkillEncode's view). */
    val slot: SlotType get() = SlotType(type, options)

    /** As a connection's argument, so ConnStep.values checks each value the same way (its type, choices, limits and length). */
    val op: OpParam get() = OpParam(type, options, min?.toDouble(), max?.toDouble(), maxLen)
}

/** What a script declares: its [params] in order and its [effects] ("online", "deletes", "sends", "money"); [ok] false: a `# param:` line was unreadable. */
data class ScriptDecl(val params: List<ScriptParam>, val effects: Set<String>, val ok: Boolean = true) {
    companion object {
        val NONE = ScriptDecl(emptyList(), emptySet())
        val BROKEN = ScriptDecl(emptyList(), emptySet(), ok = false)
    }
}

object ScriptParams {
    const val MAX_PARAMS = 3
    const val MAX_OPTIONS = 12
    const val MIN_OPTIONS = 2
    const val MAX_TEXT = 200
    const val DEFAULT_TEXT = 80
    const val MAX_LABEL = 60
    /** An int's limits stay within ± this (SkillCheck.MAX_NUMBER). */
    const val MAX_NUMBER = 1_000_000_000L
    /** The longest declaration line the listing run returns (it cuts there). */
    const val MAX_LINE = 200
    /** Declaration lines kept per script ([MAX_PARAMS] + 1, so a fourth is seen and refused; effects likewise). */
    const val MAX_LINES = 4

    private val NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val OPTION = Regex("^[\\p{L}\\p{N}][\\p{L}\\p{N}._+-]{0,59}$")
    private val INT = Regex("^(-?\\d{1,10})\\.\\.(-?\\d{1,10})$")
    // name, type, the rest (its spec), then an optional "label" at the end.
    private val LINE = Regex("^([^\\s\"]+)\\s+([^\\s\"]+)((?:\\s+[^\\s\"]+)*)\\s*(?:\"([^\"]*)\")?$")

    /** A declaration line as the listing run gives it ("param: days int 1..30 …", "# param: …" or just "days int 1..30 …"), after its keyword. */
    fun body(line: String, keyword: String): String {
        var l = line.trim().removePrefix("#").trim()
        if (l.lowercase(Locale.ROOT).startsWith("$keyword:")) l = l.substring(keyword.length + 1)
        return l.trim()
    }

    /** [paramLines] and [effectLines] read together; [ScriptDecl.BROKEN] when a `# param:` line can't be read, one name repeats or there are more than 3. */
    fun parse(paramLines: List<String>, effectLines: List<String>): ScriptDecl {
        if (paramLines.size > MAX_PARAMS) return ScriptDecl.BROKEN
        val params = ArrayList<ScriptParam>()
        for (l in paramLines) {
            val p = param(body(l, "param")) ?: return ScriptDecl.BROKEN // l10n: not text (the script's keyword)
            if (params.any { it.name == p.name }) return ScriptDecl.BROKEN
            params += p
        }
        return ScriptDecl(params, effects(effectLines))
    }

    /** One `# param:` line's body ("days int 1..30 \"Keep how many days\""), or null when Robot can't read it whole. */
    fun param(body: String): ScriptParam? {
        if (body.length > MAX_LINE || body.any { it < ' ' || it == '\u007f' }) return null
        val m = LINE.matchEntire(body.trim()) ?: return null
        val name = m.groupValues[1]
        if (!NAME.matches(name) || name == "script") return null // l10n: not text (a skill step's own argument)
        val spec = m.groupValues[3].trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val label = m.groups[4]?.value?.let(::label)
        if (m.groups[4] != null && label == null) return null
        return when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "int", "integer" -> { // l10n: not text (the script's type names)
                val r = spec.singleOrNull()?.let { INT.matchEntire(it) } ?: return null
                val lo = r.groupValues[1].toLongOrNull() ?: return null
                val hi = r.groupValues[2].toLongOrNull() ?: return null
                if (lo > hi || Math.abs(lo) > MAX_NUMBER || Math.abs(hi) > MAX_NUMBER) return null
                ScriptParam(name, ParamType.INTEGER, min = lo, max = hi, label = label)
            }
            "choice" -> { // l10n: not text (the script's type name)
                val opts = spec.singleOrNull()?.split('|') ?: return null
                if (opts.size !in MIN_OPTIONS..MAX_OPTIONS || opts.any { !OPTION.matches(it) } || opts.toSet().size != opts.size) return null
                ScriptParam(name, ParamType.CHOICE, options = opts, label = label)
            }
            "text" -> { // l10n: not text (the script's type name)
                var max = DEFAULT_TEXT
                var dash = false
                for (o in spec) when {
                    o.startsWith("max=") -> max = o.removePrefix("max=").toIntOrNull()?.takeIf { it in 1..MAX_TEXT } ?: return null
                    o == "dash=ok" -> dash = true // l10n: not text (the script's option)
                    else -> return null
                }
                ScriptParam(name, ParamType.TEXT, maxLen = max, dashOk = dash, label = label)
            }
            "yes_no", "yesno" -> if (spec.isEmpty()) ScriptParam(name, ParamType.YES_NO, label = label) else null // l10n: not text (the script's type names)
            else -> null
        }
    }

    /** Why a script's `# param:` lines leave it untyped (C9: Settings says which line), or null when they read. */
    sealed class Why {
        /** More than [MAX_PARAMS] lines. */
        data object TooMany : Why()
        /** Line [line] (from 1) can't be read whole. */
        data class Unreadable(val line: Int) : Why()
        /** Line [line] uses a name an earlier line used. */
        data class Repeated(val line: Int, val name: String) : Why()
    }

    /** The first reason [paramLines] leave a script untyped, as [parse] decides it. */
    fun why(paramLines: List<String>): Why? {
        if (paramLines.size > MAX_PARAMS) return Why.TooMany
        val names = mutableSetOf<String>()
        paramLines.forEachIndexed { i, l ->
            val p = param(body(l, "param")) ?: return Why.Unreadable(i + 1) // l10n: not text (the script's keyword)
            if (!names.add(p.name)) return Why.Repeated(i + 1, p.name)
        }
        return null
    }

    /** Your label, cleaned: one line, at most [MAX_LABEL] characters; null when it is empty or too long. */
    private fun label(s: String): String? = TermuxOutput.clean(s).replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= MAX_LABEL }

    /** The effects `# effect:` lines name ("online, deletes"), in their usual words; any other word is ignored (effects only add). */
    fun effects(lines: List<String>): Set<String> = buildSet {
        for (l in lines) for (w in body(l, "effect").lowercase(Locale.ROOT).split(Regex("[\\s,;]+"))) when (w) { // l10n: not text (the script's keyword)
            "online" -> add("online") // l10n: not text (an effect's word)
            "deletes", "delete" -> add("deletes") // l10n: not text (an effect's word)
            "sends", "send" -> add("sends") // l10n: not text (an effect's word)
            "money" -> add("money") // l10n: not text (an effect's word)
        }
    }
}

/**
 * Your scripts as a skill's checks, effects and tier see them (C1's SkillLinks.script, C8): a script the listing run found
 * whose declarations Robot could read, with its parameters in order and its effects. ConnLinks takes it as its `others`.
 */
class ScriptLinks(private val scripts: List<TermuxScriptInfo>) : SkillLinks {
    override fun script(name: String): LinkedScript? {
        val d = scripts.firstOrNull { it.name == name }?.decl?.takeIf { it.ok } ?: return null
        return LinkedScript(d.params.map { it.name to it.slot }, SkillEffects.script(d.effects))
    }
}
