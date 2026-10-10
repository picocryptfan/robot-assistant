package app.heyrobot.core

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Pure. Dates and times in the person's words ("tomorrow 3pm", "Friday 9 to 10:30", ISO) read against "now" and its
// zone, for add_event and read_calendar (docs/phase-2.md "Date and time words"). The model passes the words; the app
// reads them here, so the model never builds a timestamp. English only: another language plugs in a [Lexicon].

/** What a `when` says: a start, an end if one was said, and whether it is a whole day. */
data class WhenSpan(val start: ZonedDateTime, val end: ZonedDateTime?, val allDay: Boolean)

/** A day or a week of calendar: [from] inclusive, [to] exclusive (midnight to midnight). */
data class DayRange(val from: ZonedDateTime, val to: ZonedDateTime) {
    val days: Long get() = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate())
}

/** The parser's answer: the value, or the short fixed-wording [Unreadable.detail] sent back to the model. */
sealed class Read<out T> {
    data class Ok<T>(val value: T) : Read<T>()
    data class Unreadable(val detail: String) : Read<Nothing>()
}

/**
 * The words of one language, mapped to the parser's own English words ("demain" → "tomorrow"): the seam for French
 * in phase 4 (with translation). English needs only its short forms.
 */
class Lexicon(private val words: Map<String, String>) {
    fun canonical(word: String): String = words[word] ?: word

    companion object {
        val EN = Lexicon(mapOf(
            "tmrw" to "tomorrow", "tmr" to "tomorrow", "tomorow" to "tomorrow", "tonite" to "tonight",
            "mon" to "monday", "tue" to "tuesday", "tues" to "tuesday", "wed" to "wednesday", "weds" to "wednesday",
            "thu" to "thursday", "thur" to "thursday", "thurs" to "thursday", "fri" to "friday", "sat" to "saturday", "sun" to "sunday",
            "jan" to "january", "feb" to "february", "mar" to "march", "apr" to "april", "jun" to "june", "jul" to "july",
            "aug" to "august", "sep" to "september", "sept" to "september", "oct" to "october", "nov" to "november", "dec" to "december",
            "min" to "minute", "mins" to "minutes", "hr" to "hour", "hrs" to "hours", "wk" to "week", "wks" to "weeks",
        ))
    }
}

object WhenParser {
    /** What the model is told when the words can't be read (docs/phase-2.md). */
    const val SAY_DATE = "say the date like \"tomorrow 3pm\" or \"October 14 at 9\""
    /** 10/11 is the 10th of November or the 11th of October: Robot doesn't guess. */
    const val SAY_MONTH = "say the month's name, like \"October 11\""

    private enum class Period(val default: LocalTime) {
        MORNING(LocalTime.of(9, 0)), AFTERNOON(LocalTime.of(14, 0)), EVENING(LocalTime.of(18, 0)), NIGHT(LocalTime.of(20, 0))
    }

    /** A clock time as said. [h24]: it can be read only one way (15:00, 15h, 09:30, noon, midnight). */
    private data class Clock(val h: Int, val m: Int, val mer: String?, val h24: Boolean)

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val DAYS = DayOfWeek.entries.associateBy { it.name.lowercase(Locale.ROOT) }
    private val HOUR_WORDS = listOf("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve")
    private val NUMBER_WORDS = (HOUR_WORDS.mapIndexed { i, w -> w to (i + 1).toDouble() } + listOf(
        "a" to 1.0, "an" to 1.0, "thirteen" to 13.0, "fourteen" to 14.0, "fifteen" to 15.0, "twenty" to 20.0, "thirty" to 30.0,
        "forty" to 40.0, "forty five" to 45.0, "fifty" to 50.0, "sixty" to 60.0, "ninety" to 90.0, "a couple of" to 2.0, "a few" to 3.0,
    )).toMap()

    private val MONTH_RE = MONTHS.joinToString("|")
    private val DAY_RE = DAYS.keys.joinToString("|")
    private val NUM_RE = """\d+(?:\.\d+)?|${NUMBER_WORDS.keys.sortedByDescending { it.length }.joinToString("|")}"""
    private val CLOCK = """noon|midnight|\d{1,2}(?:[:.h]\d{2})?(?: ?(?:am|pm|h))?|(?:${HOUR_WORDS.joinToString("|")})(?: (?:fifteen|thirty|forty five))?(?: ?(?:am|pm))?"""

    /**
     * Reads [text] at [now] (whose zone the result is in). A time without a date is the next time it comes; a month
     * and day already past this year is next year's; a weekday is the next one ("next Friday": the one in the
     * following week); a date without a time is a whole day. Unreadable words, conflicting dates and numeric dates
     * like 10/11 give [Read.Unreadable].
     */
    fun parse(text: String, now: ZonedDateTime, lexicon: Lexicon = Lexicon.EN): Read<WhenSpan> {
        val zone = now.zone
        val today = now.toLocalDate()
        val sc = Scan(normalize(text, lexicon))
        val dates = mutableListOf<LocalDate>()
        var instant: ZonedDateTime? = null
        var duration: Duration? = null
        var period: Period? = null
        var allDay = false

        // ISO first: its digits and dashes would read as other things.
        sc.takeAll(Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})(?:[t ](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(z|[+-]\d{2}:?\d{2})?)?\b""")) { m ->
            val g = m.groupValues
            val date = date(g[1].toInt(), g[2].toInt(), g[3].toInt()) ?: return Read.Unreadable(SAY_DATE)
            if (g[4].isEmpty()) {
                dates += date
            } else {
                val t = time(g[4].toInt(), g[5].toInt()) ?: return Read.Unreadable(SAY_DATE)
                val at = if (g[7].isEmpty()) ZonedDateTime.of(date, t, zone)
                else OffsetDateTime.of(date, t, if (g[7] == "z") ZoneOffset.UTC else ZoneOffset.of(offset(g[7]))).atZoneSameInstant(zone)
                if (instant != null && instant != at) return Read.Unreadable(SAY_DATE)
                instant = at
            }
        }
        if (Regex("""\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b\d{1,2}\.\d{1,2}\.(?:\d{2,4})?(?:\s|$)""").containsMatchIn(sc.s)) return Read.Unreadable(SAY_MONTH)
        // A dash between two times is a range ("9-10:30", "2-4pm"); only now, so an ISO date or offset keeps its dashes.
        sc.s = sc.s.replace(Regex("""(?<![\d:.h-])(\d{1,2}(?:[:.h]\d{2})?(?: ?[ap]m)?) ?- ?(\d{1,2})(?![\d-])"""), "$1 to $2")

        if (sc.take(Regex("""\b(?:all day|all-day|whole day|full day)\b""")) != null) allDay = true
        sc.take(Regex("""\b(?:the )?day after tomorrow\b"""))?.let { dates += today.plusDays(2) }
        sc.take(Regex("""\bin half an hour\b"""))?.let { instant = minute(now).plusMinutes(30) }
        sc.takeAll(Regex("""\bin ($NUM_RE) (minute|hour|day|week)s?\b""")) { m ->
            val n = number(m.groupValues[1]) ?: return Read.Unreadable(SAY_DATE)
            when (m.groupValues[2]) {
                "minute" -> instant = minute(now).plusMinutes(n.toLong())
                "hour" -> instant = minute(now).plusMinutes((n * 60).toLong())
                "day" -> dates += today.plusDays(n.toLong())
                else -> dates += today.plusWeeks(n.toLong())
            }
        }
        sc.take(Regex("""\bfor (?:an hour and a half|one and a half hours?|1\.5 hours?)\b"""))?.let { duration = Duration.ofMinutes(90) }
        sc.take(Regex("""\bfor half an hour\b"""))?.let { duration = Duration.ofMinutes(30) }
        sc.take(Regex("""\bfor ($NUM_RE) (minute|hour)s?\b"""))?.let { m ->
            val n = number(m.groupValues[1]) ?: return Read.Unreadable(SAY_DATE)
            duration = Duration.ofMinutes(if (m.groupValues[2] == "hour") (n * 60).toLong() else n.toLong())
        }
        // "next week" / "this week" only shift a weekday ("Friday next week"); alone they are not a time for an event.
        var weekShift: Long? = null
        sc.take(Regex("""\b(next|this|the following|following) week\b"""))?.let { weekShift = if (it.groupValues[1] == "this") 0 else 1 }

        sc.take(Regex("""\btoday\b"""))?.let { dates += today }
        sc.take(Regex("""\byesterday\b"""))?.let { dates += today.minusDays(1) }
        sc.take(Regex("""\btonight\b"""))?.let { dates += today; period = Period.NIGHT }
        sc.take(Regex("""\btomorrow\b"""))?.let { dates += today.plusDays(1) }
        sc.take(Regex("""\b(this |in the )?(morning|afternoon|evening|night)\b"""))?.let { m ->
            period = Period.valueOf(m.groupValues[2].uppercase(Locale.ROOT))
            if (m.groupValues[1] == "this ") dates += today
        }
        sc.takeAll(Regex("""\b(next |this |coming |on )?($DAY_RE)\b""")) { m ->
            val dow = DAYS.getValue(m.groupValues[2])
            val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            dates += when {
                m.groupValues[1] == "next " -> monday.plusWeeks(1).plusDays(dow.value - 1L)
                weekShift != null -> monday.plusWeeks(weekShift!!).plusDays(dow.value - 1L)
                m.groupValues[1] == "this " -> today.with(TemporalAdjusters.nextOrSame(dow))
                else -> today.with(TemporalAdjusters.next(dow))
            }
        }
        if (weekShift != null && dates.isEmpty()) return Read.Unreadable(SAY_DATE)
        // Month names with a day, either order, an optional year; a day already past this year is next year's.
        val monthDay = listOf(
            Regex("""\b($MONTH_RE)\.? (\d{1,2})(?:st|nd|rd|th)?(?: (\d{4}))?\b""") to Triple(1, 2, 3),
            Regex("""\b(\d{1,2})(?:st|nd|rd|th)? (?:of )?($MONTH_RE)\.?(?: (\d{4}))?\b""") to Triple(2, 1, 3),
        )
        for ((re, idx) in monthDay) {
            sc.takeAll(re) { m ->
                val month = MONTHS.indexOf(m.groupValues[idx.first]) + 1
                val day = m.groupValues[idx.second].toInt()
                val year = m.groupValues[idx.third].toIntOrNull()
                var d = date(year ?: today.year, month, day) ?: return Read.Unreadable(SAY_DATE)
                if (year == null && d.isBefore(today)) d = date(today.year + 1, month, day) ?: return Read.Unreadable(SAY_DATE)
                dates += d
            }
        }
        // "on the 14th": this month's, or next month's once it has passed.
        sc.takeAll(Regex("""\b(?:on )?(?:the )?(\d{1,2})(?:st|nd|rd|th)\b""")) { m ->
            val day = m.groupValues[1].toInt()
            val d = date(today.year, today.monthValue, day)?.takeIf { !it.isBefore(today) }
                ?: today.plusMonths(1).let { date(it.year, it.monthValue, day) } ?: return Read.Unreadable(SAY_DATE)
            dates += d
        }

        // Times: "half past 3", ranges ("9 to 10:30", "from 2 to 4pm", "between 2 and 3"), then one time.
        var startClock: Clock? = null
        var endClock: Clock? = null
        sc.take(Regex("""\b(half past|quarter past|quarter to) (\d{1,2}|${HOUR_WORDS.joinToString("|")})(?: ?(am|pm))?\b"""))?.let { m ->
            val h = m.groupValues[2].toIntOrNull() ?: (HOUR_WORDS.indexOf(m.groupValues[2]) + 1)
            if (h !in 1..12) return Read.Unreadable(SAY_DATE)
            val mer = m.groupValues[3].ifEmpty { null }
            startClock = when (m.groupValues[1]) {
                "half past" -> Clock(h, 30, mer, false)
                "quarter past" -> Clock(h, 15, mer, false)
                else -> Clock(if (h == 1) 12 else h - 1, 45, mer, false)
            }
        }
        if (startClock == null) {
            sc.take(Regex("""\b(?:between ($CLOCK) and ($CLOCK)|(?:from |at )?($CLOCK) ?(?:to|until|till|til) ($CLOCK))(?=\s|$)"""))?.let { m ->
                val g = m.groupValues
                startClock = clock(g[1].ifEmpty { g[3] }) ?: return Read.Unreadable(SAY_DATE)
                endClock = clock(g[2].ifEmpty { g[4] }) ?: return Read.Unreadable(SAY_DATE)
            }
        }
        if (startClock == null) {
            val all = Regex("""(?:^|\s)((?:at |around |about |by |from )?)($CLOCK)(?=\s|$)""").findAll(sc.s).toList()
            // A bare number is a time only after "at" (or a word like it), or when it is the only number left.
            val bareNumbers = all.count { it.groupValues[2].all(Char::isDigit) }
            val m = all.firstOrNull { it.groupValues[1].isNotEmpty() || !it.groupValues[2].all(Char::isDigit) || bareNumbers == 1 }
            if (m != null) {
                startClock = clock(m.groupValues[2]) ?: return Read.Unreadable(SAY_DATE)
                sc.s = sc.s.removeRange(m.groups[2]!!.range)
            }
        }

        val found = dates.isNotEmpty() || instant != null || startClock != null || period != null || allDay
        if (!found) return Read.Unreadable(SAY_DATE)
        val dateSet = dates.distinct()
        if (dateSet.size > 1) return Read.Unreadable(SAY_DATE)
        val date = dateSet.singleOrNull()

        val at = instant
        if (at != null) {
            if (date != null && date != at.toLocalDate() || startClock != null) return Read.Unreadable(SAY_DATE)
            return Read.Ok(WhenSpan(at, duration?.let { at.plus(it) }, false))
        }
        if (allDay) {
            val start = (date ?: today).atStartOfDay(zone)
            return Read.Ok(WhenSpan(start, (date ?: today).plusDays(1).atStartOfDay(zone), true))
        }
        val sClock = startClock
        if (sClock != null) {
            val (st, et) = times(sClock, endClock, period) ?: return Read.Unreadable(SAY_DATE)
            val day = date ?: if (ZonedDateTime.of(today, st, zone).isAfter(now)) today else today.plusDays(1)
            val start = ZonedDateTime.of(day, st, zone)
            val end = et?.let { var e = ZonedDateTime.of(day, it, zone); if (!e.isAfter(start)) e = e.plusDays(1); e } ?: duration?.let { start.plus(it) }
            return Read.Ok(WhenSpan(start, end, false))
        }
        val p = period
        if (p != null) {
            val day = date ?: if (ZonedDateTime.of(today, p.default, zone).isAfter(now)) today else today.plusDays(1)
            val start = ZonedDateTime.of(day, p.default, zone)
            return Read.Ok(WhenSpan(start, duration?.let { start.plus(it) }, false))
        }
        val d = date ?: return Read.Unreadable(SAY_DATE)
        return Read.Ok(WhenSpan(d.atStartOfDay(zone), d.plusDays(1).atStartOfDay(zone), true))
    }

    /**
     * The day or week [text] names, for read_calendar: "today", "Friday", "October 14", an ISO date, "this week" (from
     * today to Sunday night), "next week" (Monday to Sunday), "the weekend". Blank means today.
     */
    fun range(text: String, now: ZonedDateTime, lexicon: Lexicon = Lexicon.EN): Read<DayRange> {
        val zone = now.zone
        val today = now.toLocalDate()
        fun days(from: LocalDate, to: LocalDate) = Read.Ok(DayRange(from.atStartOfDay(zone), to.atStartOfDay(zone)))
        val s = normalize(text, lexicon)
        if (s.isBlank()) return days(today, today.plusDays(1))
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        val hasDay = Regex("""\b($DAY_RE)\b""").containsMatchIn(s)
        Regex("""\b(next |this |the |following |the following |coming )?weekend\b""").find(s)?.let { m ->
            val saturday = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today
            else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            val next = m.groupValues[1].trim() in setOf("next", "following", "the following")
            val from = if (next) nextMonday.plusDays(5) else saturday
            return days(from, from.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
        }
        if (!hasDay) {
            Regex("""\b(next |this |the following |following |coming )?week\b""").find(s)?.let { m ->
                return if (m.groupValues[1].trim() in setOf("next", "following", "the following")) days(nextMonday, nextMonday.plusWeeks(1))
                else days(today, nextMonday)
            }
        }
        return when (val r = parse(text, now, lexicon)) {
            is Read.Ok -> r.value.start.toLocalDate().let { days(it, it.plusDays(1)) }
            is Read.Unreadable -> r
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The text the regexes see: lower case, "p.m." as "pm", no punctuation but what times and dates use, the lexicon's words. */
    private fun normalize(text: String, lexicon: Lexicon): String {
        var s = text.lowercase(Locale.ROOT).replace('’', '\'').replace('–', '-').replace('—', '-')
        s = s.replace(Regex("""\b([ap])\.\s?m\b\.?"""), "$1m")
        s = s.replace(Regex("""o'?clock"""), " ").replace("@", " at ")
        s = s.replace(Regex("""[,;!?()"\[\]]"""), " ").replace(Regex("""(?<=[a-z])\.(?=\s|$)"""), " ")
        s = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ") { if (it.all(Char::isLetter)) lexicon.canonical(it) else it }
        // "3pm" and "3 pm" read the same; "15h30" stays one token.
        s = s.replace(Regex("""(\d)(am|pm)\b"""), "$1 $2")
        return s.trim()
    }

    private class Scan(var s: String) {
        fun take(re: Regex): MatchResult? {
            val m = re.find(s) ?: return null
            s = (s.substring(0, m.range.first) + " " + s.substring(m.range.last + 1)).replace(Regex("\\s+"), " ").trim()
            return m
        }

        inline fun takeAll(re: Regex, each: (MatchResult) -> Unit) {
            while (true) each(take(re) ?: return)
        }
    }

    private fun clock(token: String): Clock? {
        val t = token.trim()
        if (t == "noon") return Clock(12, 0, "pm", true)
        if (t == "midnight") return Clock(0, 0, null, true)
        Regex("""^(\d{1,2})(?:([:.h])(\d{2}))?(?: ?(am|pm|h))?$""").find(t)?.let { m ->
            val h = m.groupValues[1].toInt()
            val min = m.groupValues[3].toIntOrNull() ?: 0
            val mer = m.groupValues[4].takeIf { it == "am" || it == "pm" }
            val hMark = m.groupValues[2] == "h" || m.groupValues[4] == "h"
            if (min > 59) return null
            if (mer != null) return if (h in 1..12) Clock(h, min, mer, false) else null
            if (h > 23) return null
            val h24 = hMark || h >= 13 || h == 0 || (m.groupValues[1].length == 2 && m.groupValues[1].startsWith("0"))
            return Clock(h, min, null, h24)
        }
        Regex("""^(${HOUR_WORDS.joinToString("|")})(?: (fifteen|thirty|forty five))?(?: ?(am|pm))?$""").find(t)?.let { m ->
            val min = when (m.groupValues[2]) { "fifteen" -> 15; "thirty" -> 30; "forty five" -> 45; else -> 0 }
            return Clock(HOUR_WORDS.indexOf(m.groupValues[1]) + 1, min, m.groupValues[3].ifEmpty { null }, false)
        }
        return null
    }

    /**
     * The hour a clock means. Without am/pm or a 24-hour form: the period decides ("this afternoon at 3"), else 1 to 7
     * are afternoon and evening hours (appointments), 8 to 11 morning ones, 12 is noon.
     */
    private fun hour(c: Clock, period: Period?): Int = when {
        c.mer == "am" -> if (c.h == 12) 0 else c.h
        c.mer == "pm" -> if (c.h == 12) 12 else c.h + 12
        c.h24 -> c.h
        period == Period.MORNING -> c.h
        period != null -> if (c.h == 12) 12 else c.h + 12
        c.h in 1..7 -> c.h + 12
        else -> c.h
    }

    /** Start and end times; an end without am/pm is the first of its two readings after the start ("10 to 2" ends at 14:00). */
    private fun times(a: Clock, b: Clock?, period: Period?): Pair<LocalTime, LocalTime?>? {
        if (b == null) return (time(hour(a, period), a.m) ?: return null) to null
        val startH: Int
        val endH: Int
        if (a.mer == null && !a.h24 && b.mer != null) {
            // "from 2 to 4pm": the start takes the end's half of the day when that keeps it first.
            endH = hour(b, period)
            val same = hour(a.copy(mer = b.mer), period)
            startH = if (same * 60 + a.m <= endH * 60 + b.m) same else hour(a.copy(mer = if (b.mer == "pm") "am" else "pm"), period)
        } else {
            startH = hour(a, period)
            endH = if (b.mer == null && !b.h24) {
                listOf(b.h % 12, b.h % 12 + 12).firstOrNull { it * 60 + b.m > startH * 60 + a.m } ?: hour(b, period)
            } else hour(b, period)
        }
        val s = time(startH, a.m) ?: return null
        val e = time(endH, b.m) ?: return null
        return s to e
    }

    private fun time(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null

    private fun date(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (_: Exception) { null }

    private fun minute(now: ZonedDateTime): ZonedDateTime = now.truncatedTo(ChronoUnit.MINUTES)

    private fun number(s: String): Double? = s.toDoubleOrNull() ?: NUMBER_WORDS[s]

    private fun offset(s: String): String = if (s.length == 5 && s[3] != ':') s.substring(0, 3) + ":" + s.substring(3) else s
}
