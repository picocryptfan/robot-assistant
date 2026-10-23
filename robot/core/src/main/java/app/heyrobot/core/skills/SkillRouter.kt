// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.StepPhrases
import app.heyrobot.core.StepPhrases.Words
import app.heyrobot.core.i18n.Lang

// Pure. Skills by voice (plan §5), English and Québec French, decided by code before anything reaches the model, anchored
// at the start of what you said (only "hey robot", "please" and the like in front). Teaching needs "skill" or
// « compétence », or "when I say <name>," followed by a step that parses; "save that" needs "skill" too; a name alone must
// be the whole utterance or be followed only by values Robot can read (D10). Speech to text gives no colon: the name ends
// where the first step phrase parses (D9). Everything else is ordinary talk, left to the rest of Robot.

sealed class SkillCommand {
    abstract val lang: Lang?

    /**
     * "Teach a skill called <name>: <steps>". [steps]: what follows the name, as said; null when nothing did (D8: Robot asks
     * for the steps). [whenISay]: "when I say <name>, …" (then the steps parsed).
     */
    data class Teach(val name: String, val steps: String?, override val lang: Lang, val whenISay: Boolean = false) : SkillCommand()

    /** "Save that as a skill (called <name>)". */
    data class SaveThat(val name: String?, override val lang: Lang) : SkillCommand()

    /** A skill named ([skill]) with the values read from the words after its name ([values], by question). */
    data class Run(val skill: Skill, val values: Map<String, String>, override val lang: Lang?) : SkillCommand()

    /** "What does <name> do": [skill] null when "skill" was said and none is called [said]. */
    data class About(val skill: Skill?, val said: String, override val lang: Lang?) : SkillCommand()

    data class ListAll(override val lang: Lang?) : SkillCommand()

    /** "Delete the skill <name>" (always a tap): [skill] null when none is called [said]. */
    data class Delete(val skill: Skill?, val said: String, override val lang: Lang?) : SkillCommand()
}

object SkillRouter {
    /**
     * The skill command at the start of [text], or null. [skills]: yours (only their names run them). [people]: People's
     * names and aliases, for a person's value after a skill's name. [apps]: the installed apps' names, for an app's value.
     */
    fun route(text: String, skills: List<Skill>, people: List<String> = emptyList(), apps: List<String> = emptyList()): SkillCommand? {
        val p = Words(text)
        val i = lead(p)
        if (i >= p.size) return null
        teach(p, i)?.let { return it }
        save(p, i)?.let { return it }
        if (whole(p, i, SkillWords.LIST)) return SkillCommand.ListAll(if (p.at(i, SkillWords.FRENCH_LEAD) > 0) Lang.FR else Lang.EN)
        delete(p, i, skills)?.let { return it }
        about(p, i, skills)?.let { return it }
        return run(p, i, skills, people, apps)
    }

    /**
     * The language [cmd] was surely said in, for the reply (core ReplyLang's router match): a teach or save by its words,
     * a name by the list it is in, a French lead; else null (LangId decides).
     */
    fun lang(text: String, cmd: SkillCommand): Lang? {
        cmd.lang?.let { return it }
        val p = Words(text)
        return if (p.at(lead(p), SkillWords.FRENCH_LEAD) > 0) Lang.FR else null
    }

    /** [text] without "hey robot", "please" and the like in front, nor "please" or "now" after: the steps or a name said after a pause. */
    fun withoutLead(text: String): String {
        val p = Words(text)
        val i = lead(p)
        return if (i >= p.size) "" else said(p, i, end(p, i))
    }

    /** The words of [name] as the router compares them. */
    fun words(name: String): List<String> = Words(name).t.map { it.w }.filter { it != "," }

    /**
     * Phase B (D10): your skill whose name your words start with ("pasta", "run pasta"), when the words after it couldn't all
     * be read as its values (so [route] passed): the model gets it described, and the run counts as asked.
     */
    fun namedWithWords(text: String, skills: List<Skill>): Skill? {
        if (skills.isEmpty()) return null
        val p = Words(text)
        val i = lead(p)
        if (i >= p.size) return null
        named(p, i, skills)?.let { return it.first }
        for (verbs in listOf(SkillWords.RUN_EN, SkillWords.RUN_FR)) {
            val v = p.at(i, verbs)
            if (v == 0) continue
            var j = i + v
            while (p.w(j) in SkillWords.VALUE_FILLER && j < p.size) j++
            (named(p, j, skills) ?: named(p, i + v, skills))?.let { return it.first }
        }
        return null
    }

    /**
     * D18: your words hold one of [s]'s names whole, its words in order, anywhere ("I'm heading home now" names "heading
     * home"; "tell the team standup moved" doesn't name "team reminder").
     */
    fun saysName(text: String, s: Skill): Boolean {
        val w = words(text)
        return s.allNames.map(::words).any { n -> n.isNotEmpty() && (0..w.size - n.size).any { i -> n.indices.all { w[i + it] == n[it] } } }
    }

    /** Your skill [said] names, or null. */
    fun find(said: String, skills: List<Skill>): Skill? {
        val w = words(said)
        if (w.isEmpty()) return null
        return skills.firstOrNull { s -> s.allNames.any { words(it) == w } }
    }

    // ------------------------------------------------------------------ shared

    private fun lead(p: Words): Int {
        var i = 0
        while (true) {
            val f = p.at(i, SkillWords.FILLER)
            if (f == 0) return i
            i += f
        }
    }

    /** Where the words end once trailing "please", "now" and commas are left out. */
    private fun end(p: Words, from: Int): Int {
        var end = p.size
        while (true) {
            val k = (from until end).firstOrNull { p.at(it, SkillWords.TAIL) == end - it } ?: return end
            end = k
        }
    }

    private fun whole(p: Words, i: Int, phrases: List<String>): Boolean {
        val n = p.at(i, phrases)
        return n > 0 && i + n >= end(p, i)
    }

    /** What was said from [from] to [to], without quotes or a final period. */
    private fun said(p: Words, from: Int, to: Int): String = p.span(from, to).trim().trim('«', '»', '"', '“', '”', ':', ',', '.', '!', '?').trim()

    /** Your skill whose name is said at [i], the longest one, and where its name ends. */
    private fun named(p: Words, i: Int, skills: List<Skill>): Pair<Skill, Int>? {
        var best: Pair<Skill, Int>? = null
        for (s in skills) for (n in s.allNames) {
            val w = words(n)
            if (w.isEmpty() || !w.indices.all { p.w(i + it) == w[it] }) continue
            if (best == null || w.size > best.second - i) best = s to i + w.size
        }
        return best
    }

    /** The name said at [i] is in [s]'s French list (and not its English one). */
    private fun saidInFrench(p: Words, i: Int, end: Int, s: Skill): Lang? {
        val w = (i until end).map { p.w(it) }
        return when {
            s.namesFr.any { words(it) == w } && s.namesEn.none { words(it) == w } -> Lang.FR
            s.namesEn.any { words(it) == w } -> Lang.EN
            else -> null
        }
    }

    // ------------------------------------------------------------------ teach

    private fun teach(p: Words, i: Int): SkillCommand? {
        val en = p.at(i, SkillWords.TEACH_EN)
        val fr = p.at(i, SkillWords.TEACH_FR)
        if (en > 0 || fr > 0) {
            val lang = if (fr > en) Lang.FR else Lang.EN
            val j = i + maxOf(en, fr)
            val end = end(p, j)
            if (j >= end) return null
            // "…called heading home: text Sam …": the colon or a comma ends the name.
            val comma = (j until end).firstOrNull { p.w(it) == "," }
            if (comma != null && comma > j) {
                val name = said(p, j, comma)
                val rest = said(p, comma + 1, end)
                return if (name.isEmpty()) null else SkillCommand.Teach(name, rest.ifEmpty { null }, lang)
            }
            // No punctuation (D9): the name ends where the first step phrase parses, at most 5 words in.
            for (k in j + 1..minOf(j + SkillCheck.MAX_NAME_WORDS, end - 1)) {
                if (StepPhrases.stepAt(p, k)) return SkillCommand.Teach(said(p, j, k), said(p, k, end), lang)
            }
            // Only a name (D8): Robot asks for the steps.
            if (end - j <= SkillCheck.MAX_NAME_WORDS) return SkillCommand.Teach(said(p, j, end), null, lang)
            return null
        }
        val we = p.at(i, SkillWords.WHEN_I_SAY_EN)
        val wf = p.at(i, SkillWords.WHEN_I_SAY_FR)
        if (we == 0 && wf == 0) return null
        val lang = if (wf > we) Lang.FR else Lang.EN
        val j = i + maxOf(we, wf)
        val end = end(p, j)
        // "When I say <name>, <steps>": only when a step parses there, never "when I say so".
        for (k in j + 1..minOf(j + SkillCheck.MAX_NAME_WORDS + 1, end - 1)) {
            val s = if (p.w(k) == ",") k + 1 else k
            if (s < end && StepPhrases.stepAt(p, s)) {
                val name = said(p, j, k)
                return if (name.isEmpty()) null else SkillCommand.Teach(name, said(p, s, end), lang, whenISay = true)
            }
        }
        return null
    }

    // ------------------------------------------------------------------ save that

    private fun save(p: Words, i: Int): SkillCommand? {
        val en = p.at(i, SkillWords.SAVE_EN)
        val fr = p.at(i, SkillWords.SAVE_FR)
        if (en == 0 && fr == 0) return null
        val lang = if (fr > en) Lang.FR else Lang.EN
        var j = i + maxOf(en, fr)
        j += p.at(j, SkillWords.CALLED)
        val end = end(p, j)
        if (end - j > SkillCheck.MAX_NAME_WORDS) return null
        return SkillCommand.SaveThat(said(p, j, end).ifEmpty { null }, lang)
    }

    // ------------------------------------------------------------------ ask about, delete

    private fun delete(p: Words, i: Int, skills: List<Skill>): SkillCommand? {
        val n = p.at(i, SkillWords.DELETE)
        if (n == 0) return null
        val end = end(p, i + n)
        if (i + n >= end) return null
        val said = said(p, i + n, end)
        val fr = p.at(i, SkillWords.FRENCH_LEAD) > 0
        return SkillCommand.Delete(find(said, skills), said, if (fr) Lang.FR else Lang.EN)
    }

    private fun about(p: Words, i: Int, skills: List<Skill>): SkillCommand? {
        val end = end(p, i)
        // "What does <name> do", "what does the skill <name> do", « qu'est-ce que fait <nom> ».
        val n = p.at(i, SkillWords.ABOUT_LEAD)
        if (n > 0) {
            val skillSaid = (i until i + n).any { p.w(it) in SkillWords.SKILL }
            var e = end
            if (e - 1 > i + n && p.w(e - 1) in SkillWords.ABOUT_TAIL) e--
            val english = p.w(i) == "what" || p.w(i) == "tell"
            if (english && p.w(end - 1) !in SkillWords.ABOUT_TAIL && p.w(i + 1) == "does") return null
            if (i + n >= e) return null
            val said = said(p, i + n, e)
            val s = find(said, skills)
            if (s == null && !skillSaid) return null
            return SkillCommand.About(s, said, if (english) Lang.EN else Lang.FR)
        }
        // « Qu'est-ce que <nom> fait ».
        val f = p.at(i, SkillWords.ABOUT_FR_LEAD)
        if (f > 0 && end - 1 > i + f && p.w(end - 1) in SkillWords.ABOUT_FR_TAIL) {
            val said = said(p, i + f, end - 1)
            val s = find(said, skills) ?: return null
            return SkillCommand.About(s, said, Lang.FR)
        }
        return null
    }

    // ------------------------------------------------------------------ run (D10)

    private fun run(p: Words, i: Int, skills: List<Skill>, people: List<String>, apps: List<String>): SkillCommand? {
        if (skills.isEmpty()) return null
        val end = end(p, i)
        // "<name>" or "<name> <values>".
        named(p, i, skills)?.let { (s, e) -> values(p, e, end, s, people, apps)?.let { return SkillCommand.Run(s, it, saidInFrench(p, i, e, s)) } }
        // "run <name>", « lance <nom> », with values after it.
        for ((verbs, lang) in listOf(SkillWords.RUN_EN to Lang.EN, SkillWords.RUN_FR to Lang.FR)) {
            val v = p.at(i, verbs)
            if (v == 0) continue
            var j = i + v
            while (p.w(j) in SkillWords.VALUE_FILLER && j < end) j++
            val (s, e) = named(p, j, skills) ?: named(p, i + v, skills) ?: continue
            val vals = values(p, e, end, s, people, apps) ?: continue
            return SkillCommand.Run(s, vals, lang)
        }
        return null
    }

    private fun values(p: Words, from: Int, to: Int, s: Skill, people: List<String>, apps: List<String>): Map<String, String>? =
        if (from >= to) emptyMap() else SkillValues.read(p, from, to, s, people, apps)
}
