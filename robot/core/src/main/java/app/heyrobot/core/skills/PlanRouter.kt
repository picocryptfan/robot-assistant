// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.i18n.Lang

// Pure. Skills 1.3, plan-then-act (design §9, "When"): your words ask for two or more actions at once, joined by "then",
// "and then", "after that", "first … then", « puis », « ensuite », « après ça » or « pis » (Québec), each part counted by
// ToolRouter.asked or a skill's match; or by "and" / « et » when the parts ask for different actions ("what's the
// weather and set a ten minute timer"); or you say "plan this: …" / « planifie : … ». Anything else isn't a plan, and
// goes on as before. A match here only asks the planning model: nothing runs without the plan card.

object PlanRouter {
    /** A plan asked for: [words] to plan (without "plan this:"), [lang] when the words surely say it, [explicit]: you said "plan this". */
    data class Ask(val words: String, val lang: Lang?, val explicit: Boolean)

    private val EXPLICIT_EN = Regex("""^\s*(?:please\s+)?(?:plan this|make a plan)\s*[:,]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val EXPLICIT_FR = Regex("""^\s*(?:planifie|fais un plan)(?:\s+ça|\s+ca)?\s*[:,]?\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val STRONG_EN = Regex("""\s*,?\s*\b(?:and then|after that|then)\b\s*,?\s*""", RegexOption.IGNORE_CASE)
    private val STRONG_FR = Regex("""\s*,?\s*(?<![\p{L}'’])(?:et puis|et ensuite|après ça|après ca|puis|ensuite|pis)(?![\p{L}])\s*,?\s*""", RegexOption.IGNORE_CASE)
    private val WEAK_EN = Regex("""\s*(?:,\s*|\s)\band\b\s*""", RegexOption.IGNORE_CASE)
    private val WEAK_FR = Regex("""\s*(?:,\s*|\s)(?<![\p{L}'’])et(?![\p{L}])\s*""", RegexOption.IGNORE_CASE)
    private val FIRST = Regex("""^\s*(?:first|d'abord|d’abord|premièrement)\s*,?\s*""", RegexOption.IGNORE_CASE)

    /** The longest words planned: a plan is a few actions, never a page. */
    const val MAX_WORDS = 400

    /** [q] as a plan, or null. [skill]: a part names one of your skills (SkillRouter's match). */
    fun route(q: String, skill: (String) -> Boolean = { false }): Ask? {
        val t = q.trim()
        if (t.isEmpty() || t.length > MAX_WORDS) return null
        EXPLICIT_EN.matchEntire(t)?.let { return Ask(it.groupValues[1].trim(), Lang.EN, explicit = true) }
        EXPLICIT_FR.matchEntire(t)?.let { return Ask(it.groupValues[1].trim(), Lang.FR, explicit = true) }
        val body = FIRST.replace(t, "")
        for ((rx, lang) in listOf(STRONG_EN to Lang.EN, STRONG_FR to Lang.FR)) {
            val parts = split(body, rx)
            if (parts.size >= 2 && parts.count { asks(it, skill) } >= 2) return Ask(t, lang, explicit = false)
        }
        for ((rx, lang) in listOf(WEAK_EN to Lang.EN, WEAK_FR to Lang.FR)) {
            val parts = split(body, rx)
            if (parts.size < 2) continue
            val asked = parts.map { ToolRouter.asked(it) }
            val acting = parts.indices.filter { asked[it].isNotEmpty() || skill(parts[it]) }
            // "and" alone also joins two things one action takes ("text Sam and tell him I'm late"): only different actions count.
            if (acting.size >= 2 && (acting.map { asked[it] }.toSet().size >= 2 || acting.any { asked[it].isEmpty() })) return Ask(t, lang, explicit = false)
        }
        return null
    }

    /** The actions [q]'s words asked for, each part's (a plan's step that isn't among them shows "you didn't ask for this"). */
    fun asked(q: String): Set<ToolName> = (listOf(STRONG_EN, STRONG_FR, WEAK_EN, WEAK_FR).fold(listOf(q)) { parts, rx -> parts.flatMap { split(it, rx) } })
        .flatMap { ToolRouter.asked(it) }.toSet() + ToolRouter.asked(q)

    private fun asks(part: String, skill: (String) -> Boolean): Boolean = ToolRouter.asked(part).isNotEmpty() || skill(part)

    private fun split(s: String, rx: Regex): List<String> = s.split(rx).map { it.trim().trimEnd('.', ',', '!', '?') }.filter { it.isNotEmpty() }
}
