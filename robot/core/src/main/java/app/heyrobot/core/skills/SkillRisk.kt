// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiStrings

// Pure. Phase B (plan §8, "Tiers" and "The rule"; the user, 15 Oct; D16 to D18): how careful a run the model filled is,
// and whether its card shows. Points (EST, for 1.1's step kinds): +1 for each step past 2, +2 for each text value the
// model's call gave, +1 for each other value it gave, +3 for each step that texts or emails someone. Simple is 0 to 2,
// Careful 3 to 5, Risky 6 or more; a run with nothing from the model's call is Simple. Every on-device model counts as
// small in 1.1 (D17). Steps' own cards always still show (D6): this only ever adds the skill's card.

enum class SkillTier { SIMPLE, CAREFUL, RISKY }

/** What a model's skill call does before its steps. */
sealed class ModelGo {
    data class Refused(val why: Phrase) : ModelGo()

    /**
     * The skill's card: "Did you mean" ([didYouMean], D18: the model picked it without your words naming it) or the run
     * card. [tapOnly]: a spoken yes doesn't answer it.
     */
    data class Card(val tapOnly: Boolean, val didYouMean: Boolean) : ModelGo()

    /** The steps at once. [trusted]: a Careful fill run without its card (9 of the last 10 right): Robot says it ran it. */
    data class Run(val trusted: Boolean = false) : ModelGo()
}

object SkillRisk {
    const val CAREFUL = 3
    const val RISKY = 6

    /** [s]'s points when the model's call gave [given] (question names). */
    fun points(s: Skill, given: Set<String>): Int {
        if (given.isEmpty()) return 0
        val steps = maxOf(0, s.steps.size - 2)
        val values = s.params.filter { it.name in given }.map { if (it.type == ParamType.TEXT) 2 else 1 }.sum()
        val sends = s.steps.count { it.kind == StepKind.SEND_TEXT || it.kind == StepKind.DRAFT_EMAIL } * 3
        return steps + values + sends
    }

    fun tier(s: Skill, given: Set<String>): SkillTier = when {
        given.isEmpty() -> SkillTier.SIMPLE
        points(s, given) >= RISKY -> SkillTier.RISKY
        points(s, given) >= CAREFUL -> SkillTier.CAREFUL
        else -> SkillTier.SIMPLE
    }

    /**
     * The rule (plan §8). [named]: your words this turn hold one of the skill's names (D18). [filled]: the model's call gave
     * a value. [trusted]: this model filled this skill right 9 of the last 10 times (SkillStats). [messages]: a message
     * someone sent is in the conversation. Off never runs; a locked phone runs nothing (a phone lock says so).
     */
    fun decide(s: Skill, named: Boolean, filled: Boolean, tier: SkillTier, trusted: Boolean, locked: Boolean, phoneLock: Boolean, messages: Boolean): ModelGo {
        if (s.level == SkillLevel.OFF) return ModelGo.Refused(SkillStrings.isOff("name" to s.name, "page" to UiStrings.inSettings(UiStrings.skills)))
        if (locked) return ModelGo.Refused(if (phoneLock) LockStrings.closedNow() else TurnStrings.unlockThenAsk())
        val tap = s.provenance.fromContent || messages
        // D18: a pick your words didn't name always asks first, whatever the level.
        if (!named) return ModelGo.Card(tapOnly = tap || tier == SkillTier.RISKY, didYouMean = true)
        if (!filled) return when (SkillPolicy.decide(s, locked, phoneLock, messages)) {
            is SkillGo.Card -> ModelGo.Card(tapOnly = tap, didYouMean = false)
            else -> ModelGo.Run()
        }
        return when {
            tier == SkillTier.RISKY -> ModelGo.Card(tapOnly = true, didYouMean = false)
            s.level == SkillLevel.ASK_FIRST || tap -> ModelGo.Card(tapOnly = tap, didYouMean = false)
            tier == SkillTier.CAREFUL && !trusted -> ModelGo.Card(tapOnly = false, didYouMean = false)
            tier == SkillTier.CAREFUL -> ModelGo.Run(trusted = true)
            else -> ModelGo.Run()
        }
    }
}
