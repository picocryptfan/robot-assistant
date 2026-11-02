// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Durations
import app.heyrobot.core.StepPhrases.Words
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings

// Pure. Phase B (plan §8, "Values"): the values a model's skill call gives, checked and marked. Each is [FillSource.YOURS]
// when its words are in what you said (folded, numbers in both languages, one of the same person's names or aliases), and
// [FillSource.MODEL] otherwise: its chip then says "written by Robot's model". A time must read as a time and a duration
// within a timer's limit; a person, an app or a place is resolved later by the step's own prepare. Robot never builds a
// number or an address from the model's words. The questions the call left out keep the values your words gave (the
// router's), then their defaults.

/** Where a value came from. */
enum class FillSource { YOURS, MODEL }

sealed class SkillFill {
    /**
     * [values]: every question's value; [sources]: the questions the model's call gave, and where each value came from
     * (D16: any of them makes the run a model fill, whatever its source).
     */
    data class Ok(val values: Map<String, String>, val sources: Map<String, FillSource>) : SkillFill() {
        /** At least one value came from the model's call (D16). */
        val fromModel: Boolean get() = sources.isNotEmpty()
        val written: Set<String> get() = sources.filterValues { it == FillSource.MODEL }.keys
    }

    /** A value can't be used: [param]'s question. Counts as a wrong fill (D16). */
    data class Refused(val param: SkillParam, val why: Phrase) : SkillFill()
}

object SkillFills {
    /**
     * The values of [skill] from [call] (the model's, by question), over [heard] (what the router read from your words) and
     * the defaults. [said]: your words this turn. [people]: each of your People's names and aliases, one list per person.
     */
    fun of(skill: Skill, call: Map<String, String>, said: String, people: List<List<String>> = emptyList(), heard: Map<String, String> = emptyMap()): SkillFill {
        val values = LinkedHashMap<String, String>()
        val sources = LinkedHashMap<String, FillSource>()
        for (p in skill.params) {
            val raw = call[p.name]
            if (raw == null) {
                values[p.name] = heard[p.name] ?: p.default
                continue
            }
            val v = read(p, raw) ?: return SkillFill.Refused(p, SkillStrings.fillRefused("ask" to p.askEn))
            values[p.name] = v
            sources[p.name] = if (yours(p.type, raw, v, said, people)) FillSource.YOURS else FillSource.MODEL
        }
        return SkillFill.Ok(values, sources)
    }

    /** [raw] as a value of [type] as the skills file holds it (a duration's seconds, a time's "HH:MM"), or null. */
    fun read(type: ParamType, raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val v = when (type) {
            ParamType.DURATION -> duration(t)?.toString()
            ParamType.TIME -> time(t)
            ParamType.TEXT, ParamType.PERSON, ParamType.APP, ParamType.PLACE, ParamType.CHOICE -> t
            ParamType.INTEGER -> t.toLongOrNull()?.toString()
            ParamType.NUMBER -> t.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let(SkillNumbers::text)
            ParamType.YES_NO -> when (SkillKey.of(t)) {
                "yes", "true", "oui", "vrai" -> "true" // l10n: not text (words a model's call may give, both languages)
                "no", "false", "non", "faux" -> "false" // l10n: not text (words a model's call may give, both languages)
                else -> null
            }
        } ?: return null
        return v.takeIf { SkillCheck.validDefault(type, it) && !SkillCheck.secret(it) }
    }

    /**
     * [raw] as a value [p] may take (connections plan D11): a choice only one of its values, or one of their labels in
     * either language (never anything else, never guessed); a number within its limits. Null when it can't be.
     */
    fun read(p: SkillParam, raw: String): String? {
        if (p.type == ParamType.CHOICE) {
            val k = SkillKey.of(raw)
            val o = p.options.firstOrNull { it.value == raw.trim() } ?: p.options.firstOrNull { o -> (listOf(o.en, o.fr) + o.more.values).any { SkillKey.of(it) == k } }
            return o?.value
        }
        return read(p.type, raw)?.takeIf { SkillCheck.validValue(p, it) }
    }

    /** "8 minutes", "1 hour and 30 minutes", « 8 minutes », or a bare number of minutes ("8"): seconds, within a timer's limit. */
    fun duration(t: String): Long? {
        t.toLongOrNull()?.let { m -> return (m * 60).takeIf { m > 0 && it <= Durations.MAX_TIMER_S } }
        val p = Words(t)
        val (secs, end) = SkillValues.duration(p, 0, p.size) ?: return null
        return secs.takeIf { end == p.size }
    }

    /** "7:30", "7 pm", « 19 h », "at 7": "HH:MM". */
    fun time(t: String): String? {
        val p = Words(t)
        val (hm, end) = SkillValues.time(p, 0, p.size) ?: return null
        return hm.takeIf { end == p.size }
    }

    /**
     * The value is yours: its words are in what you said ([said]): the same words in order, folded; a duration or a time you
     * said; one of the names of the person you said.
     */
    fun yours(type: ParamType, raw: String, value: String, said: String, people: List<List<String>>): Boolean {
        val heard = SkillKey.words(said)
        if (contains(heard, SkillKey.words(raw))) return true
        val p = Words(said)
        when (type) {
            ParamType.DURATION -> for (i in 0 until p.size) if (SkillValues.duration(p, i, p.size)?.first?.toString() == value) return true
            ParamType.TIME -> for (i in 0 until p.size) if (SkillValues.time(p, i, p.size)?.first == value) return true
            ParamType.PERSON -> {
                val person = people.firstOrNull { names -> names.any { SkillKey.of(it) == SkillKey.of(raw) } } ?: return false
                return person.any { contains(heard, SkillKey.words(it)) }
            }
            else -> {}
        }
        return false
    }

    private fun contains(hay: List<String>, needle: List<String>): Boolean =
        needle.isNotEmpty() && (0..hay.size - needle.size).any { i -> needle.indices.all { hay[i + it] == needle[it] } }
}
