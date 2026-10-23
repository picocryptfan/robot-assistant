// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.LogCode
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.ToolStrings

// Pure. Phase B (plan §8, "Calls"): a model's `skill_*` call, read before any other (TurnRunner), against the skills
// described to it this turn. Refused, never repaired: a skill that wasn't described, one that changed since (its rev),
// a key it doesn't have, a value that isn't text. A call that reads becomes [SkillCall.Ok], and the panel's
// TurnActions.runSkill runs it through the same path as a skill you named (SkillRun: each step its own gate and card).
// A helper is never offered skills: its skill call is refused as not offered.

/** The skills described this turn ([tools]), and how to find a skill as it is now ([find], by id). */
class SkillMenu(val tools: List<SkillTool>, private val find: (String) -> Skill?) {
    fun tool(name: String): SkillTool? = tools.firstOrNull { it.name == name }
    fun skill(id: String): Skill? = find(id)

    /** The names the model may call. */
    val names: List<String> get() = tools.map { it.name }

    companion object {
        val NONE = SkillMenu(emptyList()) { null }
    }
}

/** The voice log's codes for a model's skill call that didn't run (SKILL_WARN's a). Stable numbers. */
enum class SkillWarnCode(override val id: Int, override val title: String) : LogCode {
    UNKNOWN(1, "a skill not described"), CHANGED(2, "the skill changed since"), BAD_ARGS(3, "arguments refused"),
    BAD_VALUE(4, "a value refused"), NOT_OFFERED(5, "a helper's skill call"), DECLINED(6, "did you mean: no"), EDITED(7, "a value changed on the card"),
    TRUSTED(8, "ran without its card: 9 of the last 10 right"),
}

/** The voice log's codes for skills and the model (SKILL_PICK's a). Stable numbers. */
enum class SkillPickCode(override val id: Int, override val title: String) : LogCode {
    DESCRIBED(1, "skills described"), CALLED(2, "a skill called"), RIGHT(3, "its card run with the model's values: right"),
    WRONG(4, "its card run after a value changed: wrong"), CANCELLED(5, "its card cancelled"), NO_CARD(6, "ran without a skill card"),
}

sealed class SkillCall {
    /** [values]: the call's arguments as text, by question name (only the questions it gave). */
    data class Ok(val skill: Skill, val tool: SkillTool, val values: Map<String, String>) : SkillCall()

    /** Not run: [why] for the card (and its English for the model); [skill] when it is known. */
    data class Refused(val why: Phrase, val code: SkillWarnCode, val status: ActionStatus = ActionStatus.INVALID, val skill: Skill? = null) : SkillCall()
}

object SkillCalls {
    /** A name the model gave is a skill's ("skill_…"): read here, never by ToolCalls. */
    fun isSkill(name: String): Boolean = name.trim().startsWith(SkillSpecs.PREFIX)

    /**
     * [raw] against [menu]. [remote]: a helper made it (skills are never offered to one). A value may be a number ("8"
     * written bare by the model, or a text call's scalar): it is read as its text. Anything else, or a key the skill
     * doesn't ask for, refuses the call.
     */
    fun parse(raw: RawToolCall, menu: SkillMenu, remote: Boolean = false): SkillCall {
        if (remote) return SkillCall.Refused(ToolStrings.notOffered(), SkillWarnCode.NOT_OFFERED)
        val tool = menu.tool(raw.name.trim()) ?: return SkillCall.Refused(ToolStrings.unknownTool(), SkillWarnCode.UNKNOWN)
        val skill = menu.skill(tool.skillId)?.takeIf { it.rev == tool.rev && it.level != SkillLevel.OFF }
            ?: return SkillCall.Refused(SkillStrings.changed(), SkillWarnCode.CHANGED, ActionStatus.FAILED)
        val asks = skill.params.associateBy { it.name }
        val values = LinkedHashMap<String, String>()
        for ((k, v) in raw.args) {
            if (k !in asks) return SkillCall.Refused(SkillStrings.unknownValue("name" to k.take(16)), SkillWarnCode.BAD_ARGS, skill = skill)
            val text = when (v) {
                null -> continue
                is String -> v
                is Int, is Long -> v.toString()
                is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toString()
                else -> return SkillCall.Refused(SkillStrings.notText("name" to k), SkillWarnCode.BAD_ARGS, skill = skill)
            }.trim()
            if (text.isNotEmpty()) values[k] = text
        }
        return SkillCall.Ok(skill, tool, values)
    }
}

/**
 * How a model's skill call ended, for TurnRunner: its card's [status] (DONE only when every step happened), what the model
 * is told ([forModel]), [launched] (an app opened: the turn ends), [content] (a step brought content in: every later
 * action asks), [asked] (your words named the skill), Robot's own line ([text]). [endTurn]: nothing ran and the turn ends
 * here (you are changing a value). [withoutSkills]: "Did you mean" answered no: the model answers again without skills.
 */
data class SkillRan(val status: ActionStatus, val forModel: Map<String, Any?>, val launched: Boolean = false, val content: Boolean = false,
    val asked: Boolean = false, val text: Phrase? = null, val endTurn: Boolean = false, val withoutSkills: Boolean = false) {
    companion object {
        /** The default port's answer: this conversation runs no skills. */
        val NOT_OFFERED = SkillRan(ActionStatus.SKIPPED, ActionStatus.SKIPPED.toolResponse(ToolStrings.notOffered().en()), text = ToolStrings.notOffered())

        /**
         * A run's status as one card's: the first step that didn't happen; else a draft opened when one did (never "done":
         * nothing was sent); else done. A run stopped before an app because notifications are off: skipped.
         */
        fun statusOf(o: SkillOutcome): ActionStatus = o.results.firstOrNull { !it.happened }?.status
            ?: if (o.noNotifications) ActionStatus.SKIPPED
            else if (o.results.any { it.status == ActionStatus.DRAFT_OPENED }) ActionStatus.DRAFT_OPENED else ActionStatus.DONE

        /** What the model is told of a run: its status, and each step's (TurnRunner adds FOR_MODEL when it didn't all happen). */
        fun forModel(o: SkillOutcome): Map<String, Any?> =
            statusOf(o).toolResponse("${o.done} of ${o.total} steps done") + ("steps" to o.results.map { r -> // l10n: not text (a tool result for the model)
                mapOf("step" to r.index + 1, "status" to r.status.toolResponse(null)["status"])
            })
    }
}
