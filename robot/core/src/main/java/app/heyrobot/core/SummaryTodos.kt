// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import java.text.Normalizer
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// summary-todos (docs/plans/summary-todos-plan.md): the to-dos in a conversation's summary, Robot's own ("To do (who, what,
// when)", Prompt.SUMMARY_REDUCE) or a stronger AI's, each with the day or time it names. A day is read from the
// conversation's own start ("the 14th", "Friday", "tomorrow"), never from today, and one without a month named is a guess
// and says so. Only a dated or timed to-do still ahead gets a card (SummaryActivity), and its card only opens the panel's
// add_event card, which takes a tap. Reading only: nothing here is stored or sent.

/** One to-do: its words without the date ([title]), its item as written ([item]), what kind, and when. */
data class SummaryTodo(val title: String, val item: String, val kind: Kind, val span: WhenSpan? = null, val guess: Boolean = false) {
    enum class Kind { DATED, TIMED, UNDATED, PAST }

    /** A dated or timed to-do still ahead: the only ones with a card. */
    val card: Boolean get() = kind == Kind.DATED || kind == Kind.TIMED

    /** add_event's "when" ("2026-11-14", "2026-11-14 15:00"), for a card; null otherwise. */
    val whenArg: String? get() = span?.takeIf { card }?.start?.let { s ->
        if (kind == Kind.TIMED) s.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) else s.toLocalDate().toString()
    }

    /** Its line under the summary: "Fri Nov 14, all day" or "Fri Nov 14 at 15:00", then the guess when it is one. */
    fun line(said: ZonedDateTime): Phrase? {
        val s = span?.takeIf { card }?.start ?: return null
        val day = EventText.dayText(s.toLocalDate(), said.year)
        val main = if (kind == Kind.TIMED) ConversationStrings.todoAt("day" to day, "time" to EventText.clock(s.hour, s.minute))
        else ConversationStrings.todoAllDay("day" to day)
        return if (!guess) main else Phrase.join(main, ConversationStrings.todoGuess("day" to EventText.dayText(said.toLocalDate(), said.year)))
    }
}

object SummaryTodos {
    /** At most this many to-dos are read from one summary. */
    const val MAX = 12

    /** A title's length at most (the calendar card shows it whole). */
    const val TITLE_MAX = 80

    // Headings, without accents, in lower case: the to-do section's, and the other sections' (which end it).
    private val TODO_HEAD = Regex("""^(to[ -]?dos?|to do|things to do|action items?|actions?|next steps?|tasks?|follow[ -]?ups?|a faire|choses a faire|actions? a (faire|prendre)|prochaines etapes|taches|suivis?)\b""")
    private val OTHER_HEAD = Regex("""^(overview|summary|decisions?|numbers( and dates)?|dates|open questions?|questions|notes|apercu|resume|en bref|decisions?|chiffres( et dates)?|questions ouvertes|questions en suspens)\b""")
    /** A day read more than this far after the talk, with no year said, is the one just before it. */
    private const val BACK_DAYS = 300L
    private val YEAR = Regex("""\b\d{4}\b""")
    private val BULLET = Regex("""^\s*([-*•–·]|\d{1,2}[.)])\s+""")
    private val REF = Regex("""\[\d{1,2}:\d{2}(\s*[–-]\s*\d{1,2}:\d{2})?]""")
    private val NONE = Regex("""^(none|nothing|n/?a|aucune?|rien)\b""")
    private val MONTH = Regex("""\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec|january|february|march|april|june|july|august|september|october|november|december|janv|fevr|fev|avr|mai|juin|juil|juillet|aout|janvier|fevrier|mars|avril|septembre|octobre|novembre|decembre)\b|\d{4}-\d{2}-\d{2}|\b\d{1,2}/\d{1,2}\b""")
    private val DANGLING = Regex("""[\s,;:–—-]*\b(for|on|by|at|before|until|due|from|le|la|pour|avant|d'ici|à|au|du|de|des|vers|dès|en)$""", RegexOption.IGNORE_CASE)

    /**
     * The to-dos in [text] (a summary), each one's day read from [said] (the conversation's start), in [lang]'s words then
     * the other's; one before [now] is PAST. Only the items under a to-do heading: a summary without one has none.
     */
    fun of(text: String, said: ZonedDateTime, now: ZonedDateTime, lang: Lang): List<SummaryTodo> =
        items(text).mapNotNull { item(it, said, now, lang) }.take(MAX)

    /** The to-do section's items, as written (bullets, markup and [14:03] references taken off). */
    fun items(text: String): List<String> {
        val out = mutableListOf<String>()
        var inside = false
        for (raw in text.lines()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val bulleted = BULLET.containsMatchIn(line)
            val plain = plain(line)
            val head = if (bulleted) null else heading(plain)
            when {
                head == true -> {
                    inside = true
                    // "To do (who, what, when): Sam books the furnace check for the 14th." — an item on the heading's line.
                    rest(line)?.let(out::add)
                }
                head == false -> inside = false
                inside -> clean(line).takeIf { it.isNotEmpty() && !NONE.containsMatchIn(plain(it)) }?.let(out::add)
            }
        }
        return out
    }

    /** True for a to-do heading, false for another heading, null for a line that is neither. */
    private fun heading(plain: String): Boolean? {
        val words = plain.split(' ').filter { it.isNotEmpty() }
        val head = plain.substringBefore(':').trim()
        return when {
            TODO_HEAD.containsMatchIn(head) && (plain.contains(':') || words.size <= 6) -> true
            OTHER_HEAD.containsMatchIn(head) && (plain.contains(':') || words.size <= 6) -> false
            plain.startsWith("#") -> false
            else -> null
        }
    }

    /** What follows a heading's colon, when it is a to-do itself. */
    private fun rest(line: String): String? {
        val after = line.substringAfter(':', "").trim().trim('*', '_').trim()
        return clean(after).takeIf { it.length > 3 && !NONE.containsMatchIn(plain(it)) }
    }

    private fun clean(line: String): String = line.replace(BULLET, "").replace(REF, "").replace("**", "").replace("__", "")
        .replace(Regex("""\s+"""), " ").trim().trim('*', '_').trim()

    /** Lower case, accents off, markup and bullets off: for the headings and the month check. */
    private fun plain(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "")
        .replace(Regex("""^[\s#*_>]+"""), "").replace("*", "").replace("_", " ").trim()

    private fun item(item: String, said: ZonedDateTime, now: ZonedDateTime, lang: Lang): SummaryTodo? {
        if (item.isBlank()) return null
        val m = WhenParser.find(item, said, lang).firstOrNull { it.dated || it.clock }
            ?: return SummaryTodo(title(item, null), item, SummaryTodo.Kind.UNDATED)
        // "Return the ladder on November 2" in a talk on 6 Nov reads as next year's: a day close behind the talk is that one.
        val span = if (m.span.start.isAfter(said.plusDays(BACK_DAYS)) && !YEAR.containsMatchIn(m.said))
            m.span.copy(start = m.span.start.minusYears(1), end = m.span.end?.minusYears(1)) else m.span
        val timed = m.clock && !span.allDay
        val past = if (timed) span.start.isBefore(now) else span.start.toLocalDate().isBefore(now.toLocalDate())
        val kind = when {
            past -> SummaryTodo.Kind.PAST
            timed -> SummaryTodo.Kind.TIMED
            else -> SummaryTodo.Kind.DATED
        }
        return SummaryTodo(title(item, m.said), item, kind, span, guess = m.relative || !MONTH.containsMatchIn(plain(m.said)))
    }

    /** [item] without its date words and the "for" or « pour » left hanging, at most [TITLE_MAX] characters. */
    private fun title(item: String, said: String?): String {
        var t = if (said == null) item else item.replaceFirst(said, " ")
        t = t.replace(Regex("""\s+"""), " ").trim()
        var before: String
        do { before = t; t = t.replace(DANGLING, "").trim() } while (t != before)
        t = t.trim(' ', ',', ';', ':', '.', '–', '—', '-')
        if (t.isEmpty()) t = item.trim()
        return if (t.length <= TITLE_MAX) t else t.take(TITLE_MAX - 1).trimEnd() + "…"
    }
}
