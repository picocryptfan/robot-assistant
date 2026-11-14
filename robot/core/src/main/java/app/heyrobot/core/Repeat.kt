// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Pure. 1.4.2 (fix-calendar-repeat; a Pixel 10, 13 Nov 2026: "put on my calendar every second Friday …" was refused
// with "say the date like …"): a repeating event, read from your words by Robot (never a rule the model writes), written
// as the event's RRULE with a DURATION (the provider wants one, not DTEND, for a repeating event).

/**
 * How an event repeats: every [interval] days, weeks, months or years; a weekly one on [days]; a monthly one on
 * [monthDay] or on the [nth] (1 to 4, -1 the last) of [days]' one weekday; a yearly one on [month] [monthDay]. Read from
 * words ([RepeatWords]) it may leave the day out ("every 2 weeks"); [resolve] takes it from the first date.
 */
data class Repeat(val freq: Freq, val interval: Int = 1, val days: Set<DayOfWeek> = emptySet(), val monthDay: Int? = null,
    val nth: Int? = null, val month: Int? = null) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    /** The day it leaves out, from [anchor] (the first date it may fall on). */
    fun resolve(anchor: LocalDate): Repeat = when {
        freq == Freq.WEEKLY && days.isEmpty() -> copy(days = setOf(anchor.dayOfWeek))
        freq == Freq.MONTHLY && nth == null && monthDay == null -> copy(monthDay = anchor.dayOfMonth)
        freq == Freq.YEARLY && month == null -> copy(month = anchor.monthValue, monthDay = anchor.dayOfMonth)
        else -> this
    }

    /** [d] is a day of its pattern (any period: [occurrences] keeps to the [interval]). */
    fun matches(d: LocalDate): Boolean = when (freq) {
        Freq.DAILY -> true
        Freq.WEEKLY -> d.dayOfWeek in days
        Freq.MONTHLY -> if (nth == null) d.dayOfMonth == monthDay
        else d.dayOfWeek in days && (if (nth > 0) (d.dayOfMonth - 1) / 7 + 1 == nth else d.plusWeeks(1).monthValue != d.monthValue)
        Freq.YEARLY -> d.monthValue == month && d.dayOfMonth == monthDay
    }

    /** The first day of the pattern from [anchor] on ([skipAnchor]: after it), or null (none within eight years). */
    fun first(anchor: LocalDate, skipAnchor: Boolean): LocalDate? =
        generateSequence(if (skipAnchor) anchor.plusDays(1) else anchor) { it.plusDays(1) }.take(366 * 8 + 1).firstOrNull(::matches)

    /** The days it falls on from [first], as the provider expands its RRULE (the [interval] counted from [first]'s period). */
    fun occurrences(first: LocalDate): Sequence<LocalDate> =
        generateSequence(first) { it.plusDays(1) }.take(366 * 40).filter { d ->
            matches(d) && when (freq) {
                Freq.DAILY -> ChronoUnit.DAYS.between(first, d) % interval == 0L
                Freq.WEEKLY -> ChronoUnit.WEEKS.between(first.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)),
                    d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) % interval == 0L
                Freq.MONTHLY -> ChronoUnit.MONTHS.between(YearMonth.from(first), YearMonth.from(d)) % interval == 0L
                Freq.YEARLY -> (d.year - first.year) % interval == 0
            }
        }

    /** The RRULE the provider stores (RFC 5545), weeks starting on Monday. */
    fun rrule(): String = buildString {
        append("FREQ=").append(freq.name) // l10n: not text (RFC 5545)
        if (interval > 1) append(";INTERVAL=").append(interval) // l10n: not text (RFC 5545)
        when (freq) {
            Freq.WEEKLY -> append(";WKST=MO;BYDAY=").append(days.sorted().joinToString(",") { code(it) }) // l10n: not text (RFC 5545)
            Freq.MONTHLY -> if (nth != null) append(";BYDAY=").append(nth).append(code(days.single())) // l10n: not text (RFC 5545)
            else append(";BYMONTHDAY=").append(monthDay) // l10n: not text (RFC 5545)
            Freq.YEARLY -> append(";BYMONTH=").append(month).append(";BYMONTHDAY=").append(monthDay) // l10n: not text (RFC 5545)
            Freq.DAILY -> Unit
        }
    }

    /** "Every 2 weeks on Friday" / « Toutes les 2 semaines le vendredi ». */
    fun text(): Phrase = when (freq) {
        Freq.DAILY -> ToolStrings.repeatDaily()
        Freq.WEEKLY -> when {
            days == WEEKDAYS -> ToolStrings.repeatWeekdays()
            interval == 1 -> ToolStrings.repeatWeekly("days" to dayList())
            else -> ToolStrings.repeatWeeks("n" to interval.toString(), "days" to dayList())
        }
        Freq.MONTHLY -> if (nth != null) ToolStrings.repeatMonthNth("nth" to NTH.getValue(nth)(), "day" to dayName(days.single()))
        else ToolStrings.repeatMonthDay("day" to monthDayText(monthDay!!))
        Freq.YEARLY -> ToolStrings.repeatYearly("month" to EventText.monthText(month!!), "day" to monthDayText(monthDay!!))
    }

    private fun dayList(): Phrase = days.sorted().map(::dayName).reduce { a, b -> ToolStrings.repeatAnd("a" to a, "b" to b) }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
        private val NAMES = listOf(ToolStrings.dayMonday, ToolStrings.dayTuesday, ToolStrings.dayWednesday, ToolStrings.dayThursday,
            ToolStrings.dayFriday, ToolStrings.daySaturday, ToolStrings.daySunday)
        private val NTH = mapOf(1 to ToolStrings.nthFirst, 2 to ToolStrings.nthSecond, 3 to ToolStrings.nthThird, 4 to ToolStrings.nthFourth,
            -1 to ToolStrings.nthLast)

        private fun code(d: DayOfWeek): String = d.name.take(2)
        private fun dayName(d: DayOfWeek): Phrase = NAMES[d.value - 1]()
        private fun monthDayText(day: Int): Phrase = if (day == 1) InfoStrings.firstOfMonth() else Phrase.plain(day.toString())

        /** The provider's DURATION for [start] to [end]: whole days for an all-day event ("P1D"), else seconds ("P3600S"). */
        fun duration(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean): String =
            if (allDay) "P${maxOf(1, ChronoUnit.DAYS.between(start.toLocalDate(), end.toLocalDate()))}D" // l10n: not text (RFC 5545)
            else "P${maxOf(60, ChronoUnit.SECONDS.between(start, end))}S" // l10n: not text (RFC 5545)

        /** A DURATION as the provider stores it ("P3600S", "PT1H30M", "P1D", "P1W") in milliseconds, or null. */
        fun durationMillis(text: String?): Long? {
            val m = Regex("""([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?""").matchEntire(text?.trim()?.uppercase(Locale.ROOT) ?: return null) ?: return null
            val g = m.groupValues
            val n = { i: Int -> g[i].toLongOrNull() ?: 0L }
            val ms = ((((n(2) * 7 + n(3)) * 24 + n(4)) * 60 + n(5)) * 60 + n(6)) * 1000
            return if (g[1] == "-") -ms else ms
        }
    }
}

/** A repeating add_event read: its first occurrence ([span], with its end) and how it repeats. */
data class Repeating(val span: WhenSpan, val repeat: Repeat)

/** fix-calendar-repeat-c: what ToolExecutor.prepareEvent writes for an add_event: its first span and its repeat (null: once). */
data class EventTimes(val span: WhenSpan, val repeat: Repeat?)

object RepeatWords {
    /** What [read] found: no repeat words, a repeat ([said] the words, [rest] the text without them), or one it can't write. */
    sealed class Found {
        data object None : Found()
        data object Unsupported : Found()
        data class Ok(val repeat: Repeat, val said: String, val rest: String) : Found()
    }

    private val DAY_WORDS = mapOf(
        "monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "lundi" to DayOfWeek.MONDAY,
        "tuesday" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY, "mardi" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "mercredi" to DayOfWeek.WEDNESDAY,
        "thursday" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY, "jeudi" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "vendredi" to DayOfWeek.FRIDAY,
        "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY, "samedi" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY, "dimanche" to DayOfWeek.SUNDAY,
    )
    private val MONTH_WORDS = listOf(
        listOf("january", "jan", "janvier"), listOf("february", "feb", "fevrier"), listOf("march", "mar", "mars"), listOf("april", "apr", "avril"),
        listOf("may", "mai"), listOf("june", "jun", "juin"), listOf("july", "jul", "juillet"), listOf("august", "aug", "aout"),
        listOf("september", "sept", "sep", "septembre"), listOf("october", "oct", "octobre"), listOf("november", "nov", "novembre"),
        listOf("december", "dec", "decembre"),
    ).flatMapIndexed { i, ws -> ws.map { it to i + 1 } }.toMap()
    private val NUMBERS = mapOf("one" to 1, "un" to 1, "two" to 2, "deux" to 2, "other" to 2, "second" to 2, "2nd" to 2, "three" to 3, "trois" to 3,
        "four" to 4, "quatre" to 4, "five" to 5, "cinq" to 5, "six" to 6)
    private val NTHS = mapOf("first" to 1, "1st" to 1, "premier" to 1, "premiere" to 1, "1er" to 1, "1re" to 1, "second" to 2, "2nd" to 2,
        "deuxieme" to 2, "2e" to 2, "third" to 3, "3rd" to 3, "troisieme" to 3, "3e" to 3, "fourth" to 4, "4th" to 4, "quatrieme" to 4, "4e" to 4,
        "last" to -1, "dernier" to -1, "derniere" to -1)

    private val D = "(${DAY_WORDS.keys.sortedByDescending { it.length }.joinToString("|")})s?"
    private val DS = "($D(?:(?: and| et|) $D)*)" // l10n: not text (a pattern)
    private val M = "(${MONTH_WORDS.keys.sortedByDescending { it.length }.joinToString("|")})"
    private val N = "(\\d{1,2}|${NUMBERS.keys.sortedByDescending { it.length }.joinToString("|")})"
    private val NTH = "(${NTHS.keys.sortedByDescending { it.length }.joinToString("|")})"
    private const val DN = "(\\d{1,2}(?:st|nd|rd|th|er|e)?|premier)"
    private const val EVERY = "(?:every|each|chaque|tous les|toutes les)"

    private fun day(w: String): DayOfWeek = DAY_WORDS.getValue(w.removeSuffix("s").let { if (it in DAY_WORDS) it else w })
    private fun days(s: String): Set<DayOfWeek> = Regex(D).findAll(s).map { day(it.groupValues[1]) }.toSet()
    private fun num(w: String): Int = w.toIntOrNull() ?: NUMBERS.getValue(w)
    private fun dayNum(w: String): Int = if (w == "premier") 1 else w.takeWhile { it.isDigit() }.toInt()

    private class Rule(pattern: String, val make: (List<String>) -> Repeat?) {
        val re = Regex("\\b(?:$pattern)\\b")
    }

    private fun weekly(n: Int, ds: Set<DayOfWeek> = emptySet()) = Repeat(Repeat.Freq.WEEKLY, n, ds)
    private fun monthly(day: Int) = Repeat(Repeat.Freq.MONTHLY, monthDay = day)
    private fun yearly(month: String, day: String) = Repeat(Repeat.Freq.YEARLY, month = MONTH_WORDS.getValue(month), monthDay = dayNum(day))

    // The most precise first: "the first Monday of the month" before "every Monday", "every weekday" before "every day".
    private val RULES = listOf(
        // fix-calendar-repeat-c: "every" or "each" is said ("the last Friday of the month" alone is one day).
        Rule("(?:every|each|chaque) (?:the |le )?$NTH $D (?:of|du|de) (?:the |le )?(?:month|mois)|(?:(?:the|le) )?$NTH $D (?:of|de) (?:every|each|chaque|tous les) (?:month|mois)") { g ->
            val i = if (g[1].isNotEmpty()) 1 else 3; Repeat(Repeat.Freq.MONTHLY, days = setOf(day(g[i + 1])), nth = NTHS.getValue(g[i])) },
        Rule("(?:monthly|every month|each month|chaque mois|tous les mois) (?:on )?(?:the |le )?$NTH $D") { g ->
            Repeat(Repeat.Freq.MONTHLY, days = setOf(day(g[2])), nth = NTHS.getValue(g[1])) },
        Rule("(?:every|each) (?:other|second|2nd) $DS|un $D sur deux|(?:tous les|toutes les) deux $D") { g ->
            weekly(2, if (g[1].isNotEmpty()) days(g[1]) else setOf(day(g.drop(4).first { it.isNotEmpty() }))) },
        Rule("(?:every|each|chaque|toutes les|aux|a toutes les) $N (?:weeks?|semaines?)(?: (?:on|le) (?:a |the )?$D)?") { g ->
            weekly(num(g[1]), if (g[2].isEmpty()) emptySet() else setOf(day(g[2]))) },
        Rule("(?:biweekly|bi weekly|fortnightly)(?: on (?:a |the )?$D)?") { g -> weekly(2, if (g[1].isEmpty()) emptySet() else setOf(day(g[1]))) },
        Rule("(?:on |le )?$D (?:every|toutes les|aux) $N (?:weeks|semaines)") { g -> weekly(num(g[2]), setOf(day(g[1]))) },
        Rule("(?:every|each) week ?days?|(?:on )?weekdays|(?:tous les|chaque) jours? (?:de|en) semaine|en semaine|(?:(?:chaque|tous les) jours? |(?:chaque semaine|toutes les semaines) )du lundis? au vendredis?|du lundi au vendredi (?:chaque semaine|toutes les semaines)|(?:chaque|tous les) lundis? au vendredis?|(?:every|each) (?:day |week )?(?:from )?monday (?:to|through|thru) friday|(?:from )?monday (?:to|through|thru) friday (?:every|each) week") {
            weekly(1, Repeat.WEEKDAYS) },
        Rule("every ?day|each day|daily|tous les jours|chaque jour|quotidiennement") { Repeat(Repeat.Freq.DAILY) }, // l10n: not text (a pattern)
        Rule("$EVERY $DS") { g -> weekly(1, days(g[1])) },
        Rule("(?:weekly|every week|each week|chaque semaine|toutes les semaines) (?:on |le )?(?:a |the )?$DS") { g -> weekly(1, days(g[1])) },
        // "on Fridays" and "on Mondays and Wednesdays", never "on Friday" or "on Weds" (one Wednesday).
        Rule("on $DS") { g -> if (Regex("[a-z]+").findAll(g[1]).all { it.value in setOf("and", "et") || it.value.endsWith("days") }) weekly(1, days(g[1])) else null }, // l10n: not text (words read)
        Rule("(?:every|each) month on (?:the )?$DN|(?:monthly|chaque mois|tous les mois) (?:on )?(?:the |le )?$DN") { g ->
            monthly(dayNum(g.drop(1).first { it.isNotEmpty() })) },
        Rule("(?:on )?the $DN (?:of )?(?:every|each) month|le $DN (?:de chaque|de tous les|chaque) mois") { g ->
            monthly(dayNum(g.drop(1).first { it.isNotEmpty() })) },
        Rule("(?:every|each) year on (?:the )?$M $DN|(?:yearly|annually) on $M $DN|(?:every|each) $M $DN|(?:on )?$M $DN every year") { g ->
            val i = listOf(1, 3, 5, 7).first { g[it].isNotEmpty() }; yearly(g[i], g[i + 1]) },
        Rule("(?:every|each) year on the $DN of $M|(?:chaque annee|tous les ans) le $DN $M|le $DN $M (?:de chaque annee|chaque annee|tous les ans)|(?:tous les|chaque) $DN $M") { g ->
            val i = listOf(1, 3, 5, 7).first { g[it].isNotEmpty() }; yearly(g[i + 1], g[i]) },
        Rule("every week|each week|weekly|chaque semaine|toutes les semaines") { weekly(1) }, // l10n: not text (a pattern)
        Rule("every month|each month|monthly|chaque mois|tous les mois") { Repeat(Repeat.Freq.MONTHLY) }, // l10n: not text (a pattern)
        Rule("every year|each year|yearly|annually|chaque annee|tous les ans") { Repeat(Repeat.Freq.YEARLY) }, // l10n: not text (a pattern)
    )

    /** Words that ask for a repeat no rule above reads ("every last weekday of the month", "every 3 hours"). */
    private val ASKS = Regex("\\b(?:every|each|daily|weekly|monthly|yearly|annually|biweekly|fortnightly|repeat\\w*|recurring|weekdays|chaque|tous les|toutes les|sur deux|aux deux|quotidien\\w*|hebdomadaire\\w*|mensuel\\w*|annuel\\w*)\\b") // l10n: not text (a pattern)

    /** fix-calendar-repeat-c: a time after "until" (an end time: "9 until 10am", « jusqu'à 17 h »), not the series' end. */
    private val TIME_NEXT = "(?:noon|midnight|midi|minuit|\\d{1,2}(?:[:.h]\\d{2})?(?: ?(?:am|pm|a\\.m\\.?|p\\.m\\.?|h|heures?|o.?clock))?\\b(?! ?(?:st|nd|rd|th|er|e)\\b| $M\\b| ?[/-] ?\\d))" // l10n: not text (a pattern)

    /** fix-calendar-repeat-c: words that end a series ("until June 30", "for 6 weeks", « jusqu'au 30 juin »): no UNTIL or COUNT yet. */
    private val ENDS = Regex("\\b(?:until|till|through|thru|ending|jusqu(?:['\\u2019] ?| )(?:aux|au|a|en))\\b(?! (?:on |at |le |a )?$TIME_NEXT)" + // l10n: not text (a pattern)
        "|\\b(?:for|pendant|durant)(?: the next| the following| les)? (?:\\d{1,3}|$N|a|an|un|une) (?:weeks?|months?|years?|days?|times|occurrences?|semaines?|mois|ans|annees?|jours?|fois)\\b" + // l10n: not text (a pattern)
        "|\\b(?:\\d{1,3}|$N) (?:times|fois)\\b(?! (?:a|per|par)\\b)") // l10n: not text (a pattern)

    /** [text] lower case, without accents or most punctuation (times keep their ':' and '.'). */
    fun normalize(text: String): String = Normalizer.normalize(text.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[,;!?«»\"“”()]"), " ").replace(Regex("\\s+"), " ").trim()

    /** The repeat [text] asks for, if any. A rule that reads an impossible day (the 32nd) counts as one it can't write. */
    fun read(text: String): Found {
        val t = normalize(text)
        for (rule in RULES) {
            val m = rule.re.find(t) ?: continue
            val r = try { rule.make(m.groupValues) } catch (_: NoSuchElementException) { null } ?: continue
            if (!valid(r)) return Found.Unsupported
            // fix-calendar-fr-daily-142: « à 9 h à partir du 18 novembre » keeps its « le », else "9 h 18" reads as 9:18.
            val rest = (t.removeRange(m.range)).replace(Regex("\\ba partir du (?=\\d)"), "starting le ").replace(Regex("\\ba partir d(?:u|e|')"), "from").replace(Regex("\\s+"), " ").trim()
            // fix-calendar-repeat-c: an end Robot doesn't write yet gets its line, never an endless series or the date hint.
            if (ENDS.containsMatchIn(rest)) return Found.Unsupported
            return Found.Ok(r, m.value, rest)
        }
        return if (ASKS.containsMatchIn(t)) Found.Unsupported else Found.None
    }

    /** Words that may be left over beside a repeat ("every Friday starting …", « à partir du … »). */
    private val FILLER = setOf("on", "at", "the", "from", "starting", "le", "a", "de", "du", "des") // l10n: not text (words read)

    /**
     * fix-calendar-repeat-b: "starting the 20th", « à partir du 20 » (read as "starting 20"): a day of the month that starts the
     * series, never a time ("starting 9 am", "starting 20 h"), nor a date with its month (fix-calendar-repeat-d: "starting 4
     * December", « à partir du 20 janvier » go to WhenParser whole).
     */
    private val START_DAY = Regex("\\b(?:starting|beginning)(?: on| from)?(?: the| le)? (\\d{1,2})(?:st|nd|rd|th|er|e)?\\b(?!\\s?(?:[:.h]|am\\b|pm\\b|a\\.m|p\\.m|o.?clock)| (?:of |de )?$M\\b)") // l10n: not text (a pattern)
    private val START = Regex("\\b(?:starting|beginning)(?: on| from)?\\b") // l10n: not text (a pattern)

    /** The next day [day] of a month on or after [today], or null (none in a year: the 31st is in most). */
    private fun nextMonthDay(day: Int, today: LocalDate): LocalDate? = (0L..12L).asSequence().map { today.withDayOfMonth(1).plusMonths(it) }
        .filter { day <= it.lengthOfMonth() }.map { it.withDayOfMonth(day) }.firstOrNull { !it.isBefore(today) }

    /**
     * fix-calendar-repeat-b: the add_event's status for [r]: a repeat Robot can't write ends the turn on Robot's own line
     * (FAILED settles it, ActionOutcome.settles); words the model may put right stay INVALID.
     */
    fun status(r: Read.Unreadable): ActionStatus = if (r.detail == ToolStrings.repeatUnsupported().en()) ActionStatus.FAILED else ActionStatus.INVALID

    private fun valid(r: Repeat): Boolean = r.interval in 1..52 && r.monthDay?.let { it in 1..31 } != false &&
        (r.month == null || r.monthDay!! <= java.time.Month.of(r.month).maxLength())

    /**
     * An add_event's [whenText] that names a repeat: its first occurrence from [now] (the next day of the pattern on or
     * after the day named, today counting while its time is still to come; no time, a whole day) and its end, as
     * [Events.times] has it. Null: no repeat words. [Read.Unreadable]: a repeat Robot can't write, or the rest unreadable.
     */
    fun plan(whenText: String, minutes: Int?, now: ZonedDateTime, defaultMinutes: Int, lang: Lang): Read<Repeating>? {
        val found = when (val f = read(whenText)) {
            Found.None -> return null
            Found.Unsupported -> return Read.Unreadable(ToolStrings.repeatUnsupported())
            is Found.Ok -> f
        }
        val today = now.toLocalDate()
        var anchor = today
        var time: LocalTime? = null
        var length: Duration? = null
        // fix-calendar-repeat-b: "starting the 20th" names the first date; "starting next Friday" leaves the date to WhenParser.
        var rest = found.rest
        START_DAY.find(rest)?.let { m ->
            anchor = m.groupValues[1].toInt().takeIf { it in 1..31 }?.let { nextMonthDay(it, today) } ?: return Read.Unreadable(ToolStrings.repeatUnsupported())
            rest = rest.removeRange(m.range)
        }
        rest = rest.replace(START, " ").replace(Regex("\\s+"), " ").trim()
        if (rest.split(' ').any { it.isNotEmpty() && it !in FILLER }) {
            val s = when (val r = WhenParser.parse(rest, now, lang)) {
                is Read.Unreadable -> return r
                is Read.Ok -> r.value
            }
            if (WhenParser.find(rest, now, lang).any { it.dated }) anchor = s.start.toLocalDate()
            if (!s.allDay) {
                time = s.start.toLocalTime()
                length = s.end?.let { Duration.between(s.start, it) }
            }
        }
        if (anchor.isBefore(today)) return Read.Unreadable(InfoStrings.timePassed())
        val repeat = found.repeat.resolve(anchor)
        val passed = time != null && anchor == today && now.toLocalDate().atTime(time).atZone(now.zone).isBefore(now.truncatedTo(ChronoUnit.MINUTES))
        val first = repeat.first(anchor, passed) ?: return Read.Unreadable(ToolStrings.repeatUnsupported())
        val start = if (time == null) first.atStartOfDay(now.zone) else ZonedDateTime.of(first, time, now.zone)
        val end = if (time == null) first.plusDays(1).atStartOfDay(now.zone)
        else start.plus(length ?: Duration.ofMinutes((minutes ?: defaultMinutes).toLong()))
        return Read.Ok(Repeating(WhenSpan(start, end, time == null), repeat))
    }

    /**
     * fix-calendar-repeat-c: an add_event's first span and its repeat (null: once), as ToolExecutor.prepareEvent writes it:
     * a repeat in the words as [plan] reads it, else [Events.times]. [Read.Unreadable]: [status] gives the add's status.
     */
    fun eventTimes(a: Action.AddEvent, now: ZonedDateTime, defaultMinutes: Int, lang: Lang): Read<EventTimes> =
        when (val r = plan(a.whenText, a.minutes, now, defaultMinutes, lang)) {
            null -> when (val t = Events.times(a, now, defaultMinutes, lang)) {
                is Read.Unreadable -> t
                is Read.Ok -> Read.Ok(EventTimes(t.value, null))
            }
            is Read.Unreadable -> r
            is Read.Ok -> Read.Ok(EventTimes(r.value.span, r.value.repeat))
        }
}
