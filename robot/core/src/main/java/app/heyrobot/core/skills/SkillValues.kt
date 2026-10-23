// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Durations
import app.heyrobot.core.StepPhrases
import app.heyrobot.core.StepPhrases.Words
import app.heyrobot.core.i18n.Phrase

// Pure. The values Robot reads in your words after a skill's name (plan §7.2): a duration ("pasta for 8 minutes",
// « pâtes pour 8 minutes »), a time ("at 7", « à 7 h »), one of your People by name or alias, an installed app, a place
// after "to" or "at", and a text question's words, only after "saying", "that says", "to say" (« que », « qui dit »,
// « pour dire », D11). Every value fills a question of its type, in order. Words that can't all be read as values give
// null: in phase A the router then passes and the words go on as ordinary talk (D10). Nothing is guessed.

object SkillValues {
    /** The values in words [from] to [to] of [p] for [skill]'s questions, by question name; null when a word isn't read. */
    internal fun read(p: Words, from: Int, to: Int, skill: Skill, people: List<String> = emptyList(), apps: List<String> = emptyList()): Map<String, String>? {
        val values = LinkedHashMap<String, String>()
        fun out(k: String, v: String) { values[k] = v }
        val free = skill.params.toMutableList()
        fun has(t: ParamType) = free.any { it.type == t }
        fun put(t: ParamType, v: String) {
            val q = free.first { it.type == t }
            free.remove(q)
            out(q.name, v)
        }
        var i = from
        while (i < to) {
            if (p.w(i) in SkillWords.VALUE_FILLER) { i++; continue }
            // D11: a text question only after an explicit word, and only for a skill with exactly one.
            val say = p.at(i, SkillWords.SAYING)
            if (say > 0 && i + say < to && has(ParamType.TEXT) && skill.params.count { it.type == ParamType.TEXT } == 1) {
                put(ParamType.TEXT, p.span(i + say, to).trim())
                return values
            }
            var next: Int? = null
            if (has(ParamType.DURATION)) duration(p, i, to)?.let { (secs, e) -> put(ParamType.DURATION, secs.toString()); next = e }
            if (next == null && has(ParamType.TIME)) time(p, i, to)?.let { (hm, e) -> put(ParamType.TIME, hm); next = e }
            if (next == null && has(ParamType.PERSON)) among(p, i, to, people, SkillWords.TO)?.let { (n, e) -> put(ParamType.PERSON, n); next = e }
            if (next == null && has(ParamType.APP)) among(p, i, to, apps, SkillWords.APP_LEAD)?.let { (n, e) -> put(ParamType.APP, n); next = e }
            if (next == null && has(ParamType.PLACE)) {
                val n = p.at(i, SkillWords.PLACE_LEAD)
                if (n > 0 && i + n < to) { put(ParamType.PLACE, p.span(i + n, to).trim()); next = to }
            }
            i = next ?: return null
        }
        return values
    }

    /** "8 minutes", "for an hour", « pour 8 minutes », "1 hour and 30 minutes": seconds, and where it ends. */
    internal fun duration(p: Words, from: Int, to: Int): Pair<Long, Int>? {
        var i = from + p.at(from, SkillWords.FOR)
        var total = 0L
        var groups = 0
        while (i < to) {
            // "forty five minutes": two words for one number first.
            val two = SkillWords.NUMBERS[listOfNotNull(p.w(i), p.w(i + 1)).joinToString(" ")]?.takeIf { SkillWords.UNITS[p.w(i + 2)] != null }
            val n = if (two != null) two.also { i++ } else p.w(i)?.toLongOrNull() ?: SkillWords.NUMBERS[p.w(i)] ?: break
            val unit = SkillWords.UNITS[p.w(i + 1)] ?: break
            total += n * unit
            groups++
            i += 2
            if (p.w(i) in setOf("and", "et") && i + 2 <= to) i++ else break
        }
        if (groups == 0 || total !in 1..Durations.MAX_TIMER_S) return null
        // "1 hour and" with nothing after it: the "and" isn't a value.
        if (p.w(i - 1) in setOf("and", "et")) i--
        return total to i
    }

    /** "at 7", "7:30 pm", « à 7 h », « vers midi »: "HH:MM", and where it ends. */
    internal fun time(p: Words, from: Int, to: Int): Pair<String, Int>? {
        val lead = p.at(from, SkillWords.AT)
        val c = StepPhrases.clock(p, from + lead) ?: return null
        if (c.end > to) return null
        val (per, n) = StepPhrases.clockPeriod(p, c.end)
        val t = StepPhrases.resolve(c, per, mutableListOf<Phrase>())
        return "%02d:%02d".format(t.hour, t.minute) to c.end + n // l10n: not text (a value in the skills file)
    }

    /** The longest of [names] said at [from] (after "to", « à », "with" when said), and where it ends. */
    internal fun among(p: Words, from: Int, to: Int, names: List<String>, leads: List<String>): Pair<String, Int>? {
        for (start in listOf(from + p.at(from, leads), from).distinct()) {
            var best: Pair<String, Int>? = null
            for (n in names) {
                val w = SkillRouter.words(n)
                if (w.isEmpty() || start + w.size > to || !w.indices.all { p.w(start + it) == w[it] }) continue
                if (best == null || start + w.size > best.second) best = n to start + w.size
            }
            if (best != null) return best
        }
        return null
    }
}
