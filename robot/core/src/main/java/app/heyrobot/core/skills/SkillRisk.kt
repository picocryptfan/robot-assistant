// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.i18n.TurnStrings
import app.heyrobot.core.i18n.UiStrings

// Pure. Phase B (plan §8, "Tiers" and "The rule"; the user, 15 Oct; D16 to D18): how careful a run the model filled is,
// and whether its card shows. Points (EST): +1 for each step past 2, +2 for each text value the model's call gave, +1 for
// each other value it gave, and for each step by its effects (SkillEffects; connections plan D11): +3 when it tells
// someone (a text, an email, a notification), +3 when it writes online, +3 when it runs code, +1 when it changes your
// home; +2 for each place a later step uses free text a step brought back. Simple is 0 to 2, Careful 3 to 5, Risky 6 or
// more; a step that deletes, locks or unlocks, or spends money makes the skill Risky whatever its points (D11). A run with
// nothing from the model's call scores 0 (no model writes anything), so only those effects make it Risky: that only makes
// a "Did you mean" card tap-only (each such step's own card is tap-only anyway, C4). Every on-device model counts as small
// in 1.1 (D17). Steps' own cards always still show (D6): this only ever adds the skill's card.

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

    /** Each step's points by what it does (D11, per step as 1.1 counted sends). */
    private val EFFECT_POINTS: Map<SkillEffect, Int> = mapOf(SkillEffect.MESSAGES_SOMEONE to 3, SkillEffect.WRITES_ONLINE to 3,
        SkillEffect.RUNS_CODE to 3, SkillEffect.CHANGES_HOME to 1)

    /** [s]'s points when the model's call gave [given] (question names), its steps' links read from [links]. */
    // C4: no default for [links] in these three, which fail open without them (fewer effects, fewer points): pass ConnLinks.
    fun points(s: Skill, given: Set<String>, links: SkillLinks): Int {
        if (given.isEmpty()) return 0
        val steps = maxOf(0, s.steps.size - 2)
        val values = s.params.filter { it.name in given }.map { if (it.type == ParamType.TEXT) 2 else 1 }.sum()
        val effects = s.steps.sumOf { st -> SkillEffects.step(s, st, links).sumOf { EFFECT_POINTS[it] ?: 0 } }
        return steps + values + effects + 2 * replyText(s, links)
    }

    /** How many places a later step uses free text an earlier step brought back (a reply's or a script's words, D11: +2 each). */
    fun replyText(s: Skill, links: SkillLinks): Int = s.steps.withIndex().sumOf { (i, st) ->
        (st.refs + listOfNotNull(st.cond?.value)).count { r -> r.output != null && SkillCheck.outputType(r, s.steps.take(i), links)?.type == ParamType.TEXT }
    }

    fun tier(s: Skill, given: Set<String>, links: SkillLinks): SkillTier = when {
        SkillEffects.of(s, links).any { it in SkillEffect.RISKY } -> SkillTier.RISKY
        given.isEmpty() -> SkillTier.SIMPLE
        points(s, given, links) >= RISKY -> SkillTier.RISKY
        points(s, given, links) >= CAREFUL -> SkillTier.CAREFUL
        else -> SkillTier.SIMPLE
    }

    /**
     * The rule (plan §8). [named]: your words this turn hold one of the skill's names (D18). [filled]: the model's call gave
     * a value. [trusted]: this model filled this skill right 9 of the last 10 times (SkillStats). [messages]: a message
     * someone sent is in the conversation. Off never runs; a locked phone runs nothing (a phone lock says so).
     */
    fun decide(s: Skill, named: Boolean, filled: Boolean, tier: SkillTier, trusted: Boolean, locked: Boolean, phoneLock: Boolean, messages: Boolean,
        links: SkillLinks = SkillLinks.NONE): ModelGo {
        if (s.level == SkillLevel.OFF) return ModelGo.Refused(SkillStrings.isOff("name" to s.name, "page" to UiStrings.inSettings(UiStrings.skills)))
        if (locked) return ModelGo.Refused(if (phoneLock) LockStrings.closedNow() else TurnStrings.unlockThenAsk())
        // Connections plan §5.2: a step whose link is gone, or that this Robot can't run yet, keeps the whole skill from running.
        if (SkillCheck.attention(s, links).isNotEmpty()) return ModelGo.Refused(SkillPolicy.needsAttention(s))
        val tap = s.provenance.fromContent || messages
        // D18: a pick your words didn't name always asks first, whatever the level.
        if (!named) return ModelGo.Card(tapOnly = tap || tier == SkillTier.RISKY, didYouMean = true)
        if (!filled) return when (SkillPolicy.decide(s, locked, phoneLock, messages, links)) {
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
