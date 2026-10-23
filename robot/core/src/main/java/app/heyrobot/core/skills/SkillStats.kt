// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.BrainModel
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings

// Pure. Phase B (plan §8, "Samples"; the user, 15 Oct; D16, D17): how well each model filled each skill, as a ring of the
// last 10 fills shown on a card, per skill, per rev and per model. A fill run without changing a value the model wrote
// is right; a value changed and then Run, a refused fill, and "Did you mean" answered no are wrong; Cancel and a run
// without a card add nothing. A new rev clears a skill's samples; a level change keeps them. A Careful skill at Just do it
// runs without its card once 9 of the last 10 were right, with at least 10. Kept in skills_state.json by :voice: counts
// only, never backed up, logged or sent, and cleared after a restore.

/** The models samples are kept for (D17: every on-device model counts as small in 1.1). */
enum class FillModel(val id: String) {
    FAST("fast"), SMARTER("smarter"), UNCENSORED("uncensored"), OWN("own");

    /** Its name on cards and in Settings ("Fast"). */
    val title: Phrase get() = when (this) {
        FAST -> BrainModel.FAST.titleText
        SMARTER -> BrainModel.SMARTER.titleText
        UNCENSORED -> BrainModel.UNCENSORED.titleText
        OWN -> BrainModel.OWN.titleText
    }

    companion object {
        fun of(id: String?): FillModel? = entries.firstOrNull { it.id == id }

        /** Which model a loaded one counts as: every build of Fast is Fast. */
        fun of(m: BrainModel): FillModel = when {
            m.isFast -> FAST
            m == BrainModel.SMARTER -> SMARTER
            m == BrainModel.UNCENSORED -> UNCENSORED
            else -> OWN
        }
    }
}

/** One skill's samples at its [rev]: by model, the last fills, oldest first (true: right). */
data class FillSamples(val rev: Int, val byModel: Map<FillModel, List<Boolean>> = emptyMap())

object SkillStats {
    const val RING = 10
    const val TRUST_RIGHT = 9
    /** Settings shows a model's line once it has this many samples. */
    const val SHOWN_AFTER = 5

    /** [state] with one sample for [s] by [model]; a new rev starts [s]'s samples over. */
    fun record(state: SkillState, s: Skill, model: FillModel, right: Boolean): SkillState {
        val now = state.fills[s.id]?.takeIf { it.rev == s.rev } ?: FillSamples(s.rev)
        val ring = (now.byModel[model].orEmpty() + right).takeLast(RING)
        return state.copy(fills = state.fills + (s.id to now.copy(byModel = now.byModel + (model to ring))))
    }

    /** [model]'s samples for [s] at its rev now. */
    fun samples(state: SkillState, s: Skill, model: FillModel): List<Boolean> = state.fills[s.id]?.takeIf { it.rev == s.rev }?.byModel?.get(model).orEmpty()

    /** 9 of the last 10 right, with at least 10. */
    fun trusted(state: SkillState, s: Skill, model: FillModel): Boolean = samples(state, s, model).let { it.size >= RING && it.count { r -> r } >= TRUST_RIGHT }

    /** "On Fast: 9 of the last 10 right", one per model with at least [SHOWN_AFTER] samples. */
    fun lines(state: SkillState, s: Skill): List<Phrase> = FillModel.entries.mapNotNull { m ->
        val r = samples(state, s, m)
        if (r.size < SHOWN_AFTER) null
        else SkillStrings.fills("model" to m.title, "right" to r.count { it }.toString(), "n" to r.size.toString())
    }

    /** After a restore the counts start over (plan §4): the safe side. */
    fun cleared(state: SkillState): SkillState = state.copy(fills = emptyMap())
}
