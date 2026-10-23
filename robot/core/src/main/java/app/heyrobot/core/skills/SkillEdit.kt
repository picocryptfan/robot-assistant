// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.Lang
import java.util.Locale

// Pure. What Settings › Skills changes on a skill's page (plan §9): its name and other names, its level, its questions
// (their words, their default, or the value fixed at it again), its steps (moved or removed), and "These steps are mine".
// [saved] is the one rule for every write: `rev` goes up only when the name, the other names, a question or a step
// changed (a level change and "These steps are mine" leave it), and changing a step or a question means the steps are
// yours now (`from_content` false, D13).

object SkillEdit {
    /** [new] as Settings saves it over [old] at [now]. */
    fun saved(old: Skill, new: Skill, now: Long): Skill {
        val content = !old.sameContent(new)
        val mine = old.steps != new.steps || old.params != new.params
        val fromContent = new.provenance.fromContent && !mine
        val level = if (fromContent && new.level == SkillLevel.JUST_DO_IT) SkillLevel.ASK_FIRST else new.level
        return new.copy(rev = if (content) old.rev + 1 else old.rev, level = level,
            provenance = new.provenance.copy(fromContent = fromContent, edited = now))
    }

    /** **These steps are mine**: no longer from content, so Just do it may be chosen. */
    fun mine(s: Skill): Skill = s.copy(provenance = s.provenance.copy(fromContent = false))

    /**
     * The levels a skill's page offers: a skill made from content, Ask first and Off only (D13); an Off one (a restored skill
     * that isn't yours arrives so) turns on at Ask first, and Just do it is offered from there (plan §4, §9).
     */
    fun levels(s: Skill): List<SkillLevel> =
        if (s.provenance.fromContent || s.level == SkillLevel.OFF) listOf(SkillLevel.ASK_FIRST, SkillLevel.OFF) else SkillLevel.entries

    /** Renamed [name]: the old name as you said it, in its language's list, becomes the new one. */
    fun rename(s: Skill, name: String): Skill {
        val n = name.trim()
        val old = SkillDraft.otherName(s.name)
        val new = SkillDraft.otherName(n)
        fun swap(list: List<String>) = if (old != null && list.any { SkillKey.of(it) == SkillKey.of(old) })
            list.map { if (SkillKey.of(it) == SkillKey.of(old)) new ?: it else it }.filter { it.isNotEmpty() }.distinctBy { SkillKey.of(it) } else list
        return s.copy(name = n, namesEn = swap(s.namesEn), namesFr = swap(s.namesFr))
    }

    fun addName(s: Skill, name: String, lang: Lang): Skill {
        val n = name.trim().lowercase(Locale.ROOT)
        return if (lang == Lang.FR) s.copy(namesFr = (s.namesFr + n).distinctBy { SkillKey.of(it) }) else s.copy(namesEn = (s.namesEn + n).distinctBy { SkillKey.of(it) })
    }

    fun removeName(s: Skill, name: String, lang: Lang): Skill =
        if (lang == Lang.FR) s.copy(namesFr = s.namesFr - name) else s.copy(namesEn = s.namesEn - name)

    /** Step [i] moved [by] places (up is -1). */
    fun move(s: Skill, i: Int, by: Int): Skill {
        val j = i + by
        if (i !in s.steps.indices || j !in s.steps.indices) return s
        return s.copy(steps = s.steps.toMutableList().apply { add(j, removeAt(i)) })
    }

    /** Step [i] removed (at least one step stays), and the questions only it asked. */
    fun removeStep(s: Skill, i: Int): Skill {
        if (s.steps.size <= 1 || i !in s.steps.indices) return s
        val steps = s.steps.filterIndexed { j, _ -> j != i }
        val named = steps.flatMap { it.templates }.toSet()
        return s.copy(steps = steps, params = s.params.filter { it.name in named })
    }

    /** Question [name] removed: its value is fixed at its default in every step that asked it. */
    fun removeQuestion(s: Skill, name: String): Skill {
        val p = s.param(name) ?: return s
        val steps = s.steps.map { st ->
            if (name !in st.templates) st else {
                val args = st.args.toMutableMap()
                for ((k, v) in st.args) if (SkillTemplate.name(v) == name) {
                    if (p.type == ParamType.TIME) {
                        val (h, m) = p.default.split(':').map { it.toLong() }
                        args[k] = h
                        args["minute"] = m
                    } else {
                        args[k] = SkillTemplate.value(p.type, p.default)
                    }
                }
                SkillStep.of(st.kind, args)
            }
        }
        return s.copy(params = s.params.filter { it.name != name }, steps = steps)
    }

    /** Question [name]'s words or default changed (null: as they are). */
    fun question(s: Skill, name: String, askEn: String? = null, askFr: String? = null, default: String? = null): Skill =
        s.copy(params = s.params.map { p -> if (p.name != name) p else p.copy(askEn = askEn ?: p.askEn, askFr = askFr ?: p.askFr, default = default ?: p.default) })

    /** A default as typed on the page in [type]'s words ("12" minutes for a duration), as the skills file keeps it; null when it isn't one. */
    fun typedDefault(type: ParamType, typed: String): String? {
        val t = typed.trim()
        val v = when (type) {
            ParamType.DURATION -> t.toLongOrNull()?.let { (it * 60).toString() }
            ParamType.TIME -> Regex("^(\\d{1,2})[:h.](\\d{2})$").matchEntire(t)?.let { m -> "%02d:%s".format(m.groupValues[1].toInt(), m.groupValues[2]) } // l10n: not text (a value in the skills file)
            else -> t
        } ?: return null
        return v.takeIf { SkillCheck.validDefault(type, it) && !SkillCheck.secret(it) }
    }

    /** [default] as the page's field shows it to edit: a duration in minutes. */
    fun editableDefault(type: ParamType, default: String): String =
        if (type == ParamType.DURATION) default.toLongOrNull()?.let { (it / 60).toString() } ?: default else default
}
