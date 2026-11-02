// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.StepArg
import app.heyrobot.core.connect.CheckOutcome
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.connect.ConnRan
import app.heyrobot.core.connect.ConnReply
import app.heyrobot.core.connect.ConnStep
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillEncode
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.ValueSource

// Pure. A typed script's step between its skill and Termux (docs/plans/skills-connections-plan.md §3.6, D15, D18; C8).
// Before: each declared parameter gets exactly one value, checked as a connection's argument is (ConnStep.values: its type,
// its choices, its limits, its length), then written as one argument-list element (SkillEncode.argument: one line, no
// control characters, no leading "-" unless `dash=ok`, and nothing read on the phone or from outside). The elements keep
// the order of the `# param:` lines and are never joined or split again. After: `exit` and `out` for later steps (`out`
// is the cleaned output, free text from outside Robot), and the step's own check (exit 0, and its regular expression on
// the cleaned output).

object ScriptStep {
    /** The longest output a check's regular expression reads (the cleaned output is capped long before this). */
    const val MAX_CHECKED = 16 * 1024

    sealed class Argv {
        /** [argv] for Termux, in order; [modelWords]: a value holds words Robot's model wrote (a tap, always). */
        class Ok(val argv: List<String>, val modelWords: Boolean) : Argv() {
            override fun toString(): String = "Argv.Ok(${argv.size})" // l10n: not text (toString, for the logs)
        }

        /** Not run: [why] (a value that doesn't fit, or one that didn't come back). */
        data class Refused(val why: ConnFailure) : Argv()
    }

    /** [decl]'s argument list from a step's [args] (every parameter given once, nothing else). */
    fun argv(decl: ScriptDecl, args: Map<String, StepArg>): Argv {
        if (!decl.ok) return Argv.Refused(ConnFailure.GONE)
        if (args.keys != decl.params.map { it.name }.toSet()) return Argv.Refused(ConnFailure.BAD_VALUE)
        val values = when (val v = ConnStep.values(decl.params.associate { it.name to it.op }, args)) {
            is ConnStep.Values.Ok -> v.values
            is ConnStep.Values.Refused -> return Argv.Refused(v.why)
        }
        val out = ArrayList<String>(decl.params.size)
        var model = false
        for (p in decl.params) {
            val v = values[p.name] ?: return Argv.Refused(ConnFailure.BAD_VALUE)
            out += SkillEncode.argument(v, p.slot, p.dashOk) ?: return Argv.Refused(ConnFailure.BAD_VALUE)
            if (ConnStep.notYours(v)) model = true
        }
        return Argv.Ok(out, model)
    }

    /** What Robot says when [why] stopped script [name]'s step before it ran. */
    fun refusal(why: ConnFailure, name: String): Phrase = when (why) {
        ConnFailure.NO_VALUE -> ConnStrings.scriptNoValue("name" to name)
        else -> ConnStrings.scriptBadValue("name" to name)
    }

    /**
     * What the run of script [name] brought back for the rest of its skill: [exit] and [output] (cleaned and masked, as
     * the card shows it) as `exit` and `out`, both from outside Robot, and [check]'s outcome. Null [exit]: Termux didn't
     * answer, so nothing came back and no check ran.
     */
    fun ran(name: String, exit: Int?, output: String?, check: StepCheck.Matches?): ConnRan {
        if (exit == null) return ConnRan()
        val text = output.orEmpty()
        val outputs = mapOf(
            "exit" to SkillValue(exit.toLong(), ParamType.INTEGER, setOf(ValueSource.ONLINE), emptySet()), // l10n: not text (the step's output)
            "out" to SkillValue(ConnReply.clean(text), ParamType.TEXT, setOf(ValueSource.ONLINE), setOf(ValueSource.ONLINE)), // l10n: not text (the step's output)
        )
        val outcome = when {
            check == null -> CheckOutcome.NONE
            exit == 0 && matches(check, text) -> CheckOutcome.PASSED
            else -> CheckOutcome.FAILED
        }
        return ConnRan(outputs, content = true, check = outcome, what = if (outcome == CheckOutcome.FAILED) ConnStrings.scriptCheckWhat("name" to name) else null)
    }

    /** [check]'s regular expression found in [text] (at most [MAX_CHECKED] characters of it); an expression Java can't read never passes. */
    fun matches(check: StepCheck.Matches, text: String): Boolean = try {
        Regex(check.regex).containsMatchIn(text.take(MAX_CHECKED))
    } catch (_: Exception) {
        false
    }
}
