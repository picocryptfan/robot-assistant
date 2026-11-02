// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.connect.ConnPrepared
import app.heyrobot.core.connect.ConsentAnswer
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.StepKind

// Pure. Skills connections' two tools (docs/plans/skills-connections-plan.md D5, §5.4; C4): a skill's step that reaches
// one of your connections (ToolName.CONNECTION: an operation of Home Assistant, ntfy or a webhook, or the travel time from
// your routing server) and a hand-off to an app you picked (ToolName.HAND_OFF, C7). A skill's run makes the call
// (SkillRun), with a typed carrier in its arguments ([ConnCalls.KEY]): the step's values with where each came from. Only
// that carrier reads as one ([ConnCalls.parse]), so no model, helper, text call or reply can make a connection's call,
// whatever it writes. Then the step runs through TurnRunner like a typed command: the message quarantine, the gate, the
// panel's prepare (ConnRunner: the connection, its tests, the Network toggle, the limits, the first-use answer, the
// effects, the values' sources), the gate again with the prepared reasons, the card, the lock after the card, then run.
// Nothing here is ever logged: toString shows the step's number only.

/** One argument of a connection step, as the skill's run filled it. */
sealed interface StepArg {
    /** A constant of the skill: a String, a whole number (Long), a number (Double) or a yes or no (Boolean); typed by its operation when prepared. */
    data class Fixed(val value: Any) : StepArg {
        override fun toString(): String = "Fixed(…)" // l10n: not text (toString, for the logs)
    }

    /** A question's value or an earlier step's output, typed, with where it came from. */
    data class Value(val value: SkillValue) : StepArg {
        override fun toString(): String = "Value(…)" // l10n: not text (toString, for the logs)
    }

    /** An earlier step's output that didn't come back: the step isn't sent (never guessed, never a default). */
    data object Missing : StepArg
}

/**
 * A connection step as its skill's run calls it: [kind] CONN (operation [op] of connection [conn]) or TRAVEL_TIME ([conn]
 * the routing server it names, or null), its [args], the skill's [skill] name (for the first-use card), the step's number
 * [step] (from 1), and its own [check] (a pointer's value in the reply).
 */
class ConnStepCall(val kind: StepKind, val conn: String?, val op: String?, val args: Map<String, StepArg>, val skill: String, val step: Int,
    val check: StepCheck.Equals? = null) {
    override fun equals(other: Any?): Boolean = other is ConnStepCall && kind == other.kind && conn == other.conn && op == other.op && args == other.args &&
        skill == other.skill && step == other.step && check == other.check
    override fun hashCode(): Int = listOf(kind, conn, op, args, skill, step, check).hashCode()
    override fun toString(): String = "ConnStepCall($step)" // l10n: not text (toString, for the logs)
}

/**
 * The connection step as an action. [prepared]: what ConnRunner found when it prepared it (the card's words, its effects,
 * where it goes); null until then (its card then says only "Use one of your connections"). [consent]: the first-use card's
 * answer it carries (D13), recorded when it runs, never before.
 */
data class ConnStepAction(val call: ConnStepCall, val prepared: ConnPrepared? = null, val consent: ConsentAnswer? = null) : Action() {
    override val tool get() = ToolName.CONNECTION
    override fun summary(): Phrase = prepared?.summary ?: if (call.kind == StepKind.TRAVEL_TIME) SkillStrings.stepTravel() else SkillStrings.stepConnection()
    override fun describe(): String = summary().en()
    override fun bodyText(): Phrase? = prepared?.body
    override fun body(): String? = bodyText()?.en()
    override val confirmText: Phrase get() = UiStrings.run()
    override val confirmLabel: String get() = confirmText.en()
    /** D8: never on the lock screen. */
    override fun worksLocked(lockedTools: Set<ToolName>) = false
    /** D14: a spoken yes may answer its card only for a change at home, a read or an app opening, with the connection's switch on. */
    override val tapOnly: AskWhy? get() = if (prepared?.spokenYes == true) null else AskWhy.CONNECTION_TAP
    /** A request that isn't a read may have gone out when Stop came: the card says so, and nothing sends it again. */
    override val stoppedText: Phrase? get() = prepared?.takeIf { !it.read }?.let { ConnStrings.stoppedMaybe("name" to it.name) }
    override fun toString(): String = "ConnStepAction(${call.step})" // l10n: not text (toString, for the logs)
}

/** A hand-off step as its skill's run calls it (C7 fills what it opens): connection [conn], its [args], the skill's name and the step's number. */
class HandOffCall(val conn: String, val args: Map<String, StepArg>, val skill: String, val step: Int) {
    override fun equals(other: Any?): Boolean = other is HandOffCall && conn == other.conn && args == other.args && skill == other.skill && step == other.step
    override fun hashCode(): Int = listOf(conn, args, skill, step).hashCode()
    override fun toString(): String = "HandOffCall($step)" // l10n: not text (toString, for the logs)
}

/**
 * The hand-off step as an action (C7, core HandOffStep prepares it; the panel's HandOffRunner opens the app). [prepared]:
 * what its card shows and what its run checks again (its origin pins the app, its signer and what it asks); null until
 * then.
 */
data class HandOffStepAction(val call: HandOffCall, val prepared: ConnPrepared? = null) : Action() {
    override val tool get() = ToolName.HAND_OFF
    override fun summary(): Phrase = prepared?.summary ?: SkillStrings.stepHandOff()
    override fun describe(): String = summary().en()
    override fun bodyText(): Phrase? = prepared?.body
    override fun body(): String? = bodyText()?.en()
    override val confirmText: Phrase get() = UiStrings.run()
    override val confirmLabel: String get() = confirmText.en()
    override fun worksLocked(lockedTools: Set<ToolName>) = false
    /** D14: a spoken yes may answer its card only when all it does is open the app, with the connection's switch on. */
    override val tapOnly: AskWhy? get() = if (prepared?.spokenYes == true) null else AskWhy.CONNECTION_TAP
    override fun toString(): String = "HandOffStepAction(${call.step})" // l10n: not text (toString, for the logs)
}

object ConnCalls {
    /** The argument a skill's run puts its typed call under. */
    const val KEY = "step" // l10n: not text (a call's argument)

    /** [tool]'s call: only the typed carrier a skill's run made, never anything a model, a helper or a reply could write. */
    fun parse(tool: ToolName, a: Map<String, Any?>): ParsedCall {
        val refused = ParsedCall.Invalid(tool.id, app.heyrobot.core.i18n.ToolStrings.unknownTool())
        if (a.keys != setOf(KEY)) return refused
        return when (tool) {
            ToolName.CONNECTION -> (a[KEY] as? ConnStepCall)?.takeIf { it.kind == StepKind.CONN || it.kind == StepKind.TRAVEL_TIME }
                ?.let { ParsedCall.Ok(ConnStepAction(it)) } ?: refused
            ToolName.HAND_OFF -> (a[KEY] as? HandOffCall)?.let { ParsedCall.Ok(HandOffStepAction(it)) } ?: refused
            else -> refused
        }
    }
}
