// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ClockAsk
import app.heyrobot.core.NoteDictation
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RoutineCommand
import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.StepPhrases
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.lists.ListRef
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsCommand
import app.heyrobot.core.lists.ListsRouter
import java.time.LocalTime
import java.time.ZonedDateTime

// Pure. Idea 6 (docs/plans/multi-request-plan.md): several requests in one sentence, split in code only where every
// piece parses by itself on Robot's no-model paths (a list's add, a timer, an alarm, a reminder, a note). Pieces are cut
// only at commas, "and", "then", « et », « puis », « pis », « ensuite »; the cut with the fewest pieces wins, so "add salt
// and pepper to the shopping list" stays one item, and a sentence that is one request whole is never split. A sentence
// naming money or security is never split; messages and calls never parse as a piece, so "text Sam and Kim" stays one
// request. Anything else is null: the routers and the models, as before.

object MultiAsk {
    enum class Kind(val tool: ToolName) { LIST(ToolName.ADD_TO_LIST), TIMER(ToolName.SET_TIMER), ALARM(ToolName.SET_ALARM), REMINDER(ToolName.ADD_EVENT), NOTE(ToolName.ADD_NOTE) }

    /** One request: its tool call, as a typed command would make it, and [said]: your words for it (its card line). */
    data class Part(val kind: Kind, val call: RawToolCall, val said: String)

    /** The most requests split from one sentence: a plan's default cap. */
    const val MAX = SkillPlans.CAP
    private const val MAX_CHARS = 400
    private const val MAX_PIECES = 12

    private val SEP = Regex("""\s*,\s*(?:(?:and then|and also|and|then|also|et puis|et ensuite|et|puis|pis|ensuite|aussi)\s+)?|\s+(?:and then|and also|and|then|also|et puis|et ensuite|et|puis|pis|ensuite|aussi)\s+""", RegexOption.IGNORE_CASE) // l10n: not text (words Robot hears)
    private val NEVER = Regex("""\b(?:pay|pays|paid|payment|transfer|e transfer|etransfer|bank|banking|password|passcode|pin|unlock|credit card|debit card|bitcoin|crypto|paie|paye|payer|paiement|virement|banque|mot de passe|nip|code secret|deverrouille|deverrouiller|carte de credit|carte de debit)\b""") // l10n: not text (words Robot hears)
    private val FRENCH = Regex("""\b(?:mets|met|ajoute|rappelle|regle|reveille|minuterie|minuteur|reveil|liste|epicerie|et|puis|pis|ensuite|prends|note)\b""") // l10n: not text (words Robot hears)

    private fun fold(s: String): String = StepPhrases.plain(s.lowercase()).replace(Regex("[^a-z0-9]+"), " ").trim()

    /** [text]'s requests, two to [MAX], each its own no-model request; null when it isn't that (today's path). [lists]: Lists is on. */
    fun split(text: String, now: ZonedDateTime, lists: Boolean = true): List<Part>? {
        val t = text.trim().trimEnd('.', '!', '?', ' ')
        if (t.isEmpty() || t.length > MAX_CHARS || NEVER.containsMatchIn(fold(t))) return null
        val atoms = mutableListOf<String>()
        val seps = mutableListOf<String>()
        var at = 0
        for (m in SEP.findAll(t)) {
            atoms += t.substring(at, m.range.first)
            seps += m.value
            at = m.range.last + 1
        }
        atoms += t.substring(at)
        if (atoms.size < 2 || atoms.size > MAX_PIECES || atoms.any { it.isBlank() }) return null
        // One request whole is today's path; but a list's add takes any words, so a sentence that also cuts into
        // whole requests ("add tomatoes to the shopping list then add nails to the hardware list") is those requests.
        val whole = part(t, now, lists)
        if (whole != null && whole.kind != Kind.LIST) return null
        val n = atoms.size
        val memo = HashMap<Int, Part?>()
        fun parsed(i: Int, j: Int): Part? = memo.getOrPut(i * 64 + j) {
            part(buildString { for (k in i..j) { append(atoms[k]); if (k < j) append(seps[k]) } }, now, lists)
        }
        // best[i]: the fewest requests that say atoms i until the end, each a whole request.
        val best = arrayOfNulls<List<Part>>(n + 1)
        best[n] = emptyList()
        for (i in n - 1 downTo 0) for (j in i until n) {
            val rest = best[j + 1] ?: continue
            if (i == 0 && j == n - 1 && whole != null) continue
            val p = parsed(i, j) ?: continue
            if (best[i] == null || rest.size + 1 < best[i]!!.size) best[i] = listOf(p) + rest
        }
        return best[0]?.takeIf { it.size in 2..MAX }
    }

    /** The language [text] surely speaks, for the card and the voice. */
    fun lang(text: String): Lang = if (FRENCH.containsMatchIn(fold(text))) Lang.FR else Lang.EN

    /** [s] as one request on a no-model path, or null. */
    internal fun part(s: String, now: ZonedDateTime, lists: Boolean): Part? {
        val said = s.trim().trim(',', ' ').trimEnd('.', '!', '?', ' ')
        if (said.isEmpty()) return null
        (ClockAsk.route(said, now) as? ClockAsk.Answer.Call)?.let { return Part(Kind.TIMER, it.call, said) }
        alarm(said)?.let { return Part(Kind.ALARM, it, said) }
        NoteDictation.direct(said)?.let { return Part(Kind.NOTE, it, said) }
        if (lists) list(said)?.let { return Part(Kind.LIST, it, said) }
        reminder(said, now)?.let { return Part(Kind.REMINDER, it, said) }
        return null
    }

    private fun alarm(s: String): RawToolCall? {
        // Without punctuation first (as ClockAsk reads a timer), then with it ("6:30").
        for (text in listOf(ClockAsk.plain(s), StepPhrases.plain(s.lowercase()).trim())) {
            val acts = StepPhrases.steps(text)
            if (acts.unparsed.isNotEmpty() || acts.refused.isNotEmpty()) continue
            val a = (acts.steps.singleOrNull() as? RoutineStep.Tool)?.action as? Action.SetAlarm ?: continue
            return RawToolCall(ToolName.SET_ALARM.id, mapOf("hour" to a.hour, "minute" to a.minute))
        }
        return null
    }

    private fun list(s: String): RawToolCall? {
        val c = ListsRouter.route(s) as? ListsCommand.Add ?: return null
        val name: String? = when (val r = c.list) {
            is ListRef.Role -> if (r.role == ListRole.SHOPPING) null else r.role.names.first()
            is ListRef.Named -> r.name
            else -> return null
        }
        if (c.words.isBlank()) return null
        return RawToolCall(ToolName.ADD_TO_LIST.id, if (name == null) mapOf("items" to c.words) else mapOf("items" to c.words, "list" to name))
    }

    private const val TIME = """(\d{1,2})(?:\s*(?::|h)\s*(\d{2})?)?\s*(a\.?\s?m\.?|p\.?\s?m\.?)?""" // l10n: not text (a pattern)
    private val AT_FIRST = listOf(
        Regex("""^(?:please\s+)?(?:remind me|set a reminder|set me a reminder)\s+(?:at|for)\s+$TIME\s+(?:to|that|about)\s+(.+)$""", RegexOption.IGNORE_CASE), // l10n: not text (words Robot hears)
        Regex("""^(?:rappelle[- ]moi|fais[- ]moi penser)\s+(?:à|a)\s+$TIME\s+(?:de\s+|d['’]|que\s+|qu['’]|à\s+|a\s+)(.+)$""", RegexOption.IGNORE_CASE)) // l10n: not text (words Robot hears)
    private val AT_LAST = listOf(
        Regex("""^(?:please\s+)?remind me\s+(?:to|that|about)\s+(.+?)\s+at\s+$TIME$""", RegexOption.IGNORE_CASE), // l10n: not text (words Robot hears)
        Regex("""^(?:rappelle[- ]moi|fais[- ]moi penser)\s+(?:de\s+|d['’]|que\s+|qu['’]|à\s+|a\s+)(.+?)\s+(?:à|a)\s+$TIME$""", RegexOption.IGNORE_CASE)) // l10n: not text (words Robot hears)

    /** A dated reminder (RoutineRouter's), or "remind me at 6 to …": the next time the clock shows 6, as an alarm reads it. */
    private fun reminder(s: String, now: ZonedDateTime): RawToolCall? {
        (RoutineRouter.route(s, now) as? RoutineCommand.RemindOn)?.let { return event(it) }
        val (title, time) = bare(s, now) ?: return null
        val day = if (time.isAfter(now.toLocalTime())) "today" else "tomorrow" // l10n: not text (words RoutineRouter reads)
        val r = RoutineRouter.route("remind me to $title $day at %02d:%02d".format(time.hour, time.minute), now) as? RoutineCommand.RemindOn // l10n: not text (words RoutineRouter reads)
        return r?.let(::event)
    }

    private fun event(r: RoutineCommand.RemindOn) = RawToolCall(ToolName.ADD_EVENT.id, mapOf("title" to r.title, "when" to r.whenText, "alert" to true))

    private fun bare(s: String, now: ZonedDateTime): Pair<String, LocalTime>? {
        for (rx in AT_FIRST) rx.matchEntire(s)?.let { m -> return clock(m.groupValues[1], m.groupValues[2], m.groupValues[3], now)?.let { m.groupValues[4].trim() to it } }
        for (rx in AT_LAST) rx.matchEntire(s)?.let { m -> return clock(m.groupValues[2], m.groupValues[3], m.groupValues[4], now)?.let { m.groupValues[1].trim() to it } }
        return null
    }

    /** An hour and minute you said; with no am or pm, an hour of 1 to 12 is the next time the clock shows it. */
    internal fun clock(h: String, m: String, ap: String, now: ZonedDateTime): LocalTime? {
        var hour = h.toIntOrNull() ?: return null
        val minute = if (m.isEmpty()) 0 else m.toIntOrNull() ?: return null
        if (hour > 23 || minute > 59) return null
        val p = ap.lowercase().filter { it.isLetter() }
        if (p.isNotEmpty()) {
            if (hour !in 1..12) return null
            hour = hour % 12 + if (p == "pm") 12 else 0
            return LocalTime.of(hour, minute)
        }
        if (hour !in 1..12) return LocalTime.of(hour, minute)
        val first = LocalTime.of(hour % 12, minute)
        val later = LocalTime.of(hour % 12 + 12, minute)
        val t = now.toLocalTime()
        return when {
            first.isAfter(t) -> first
            later.isAfter(t) -> later
            else -> first
        }
    }
}
