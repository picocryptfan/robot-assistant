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

    /**
     * A clock time as said. [h24]: it can be read only one way (15:00, 15h, 09:30, noon). [midnight]: the word, the
     * end of the day the date names.
     */
    private data class Clock(val h: Int, val m: Int, val mer: String?, val h24: Boolean, val midnight: Boolean = false)

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

    /** Words that may be left over without making the text unreadable ("on Friday at 3", "this coming Friday"). */
    private val FILLER = setOf("at", "on", "the", "for", "by", "around", "about", "in", "and", "from", "to", "this", "coming", "starting", "sharp")

    /**
     * Reads [text] at [now] (whose zone the result is in). A time without a date is the next time it comes; a month
     * and day already past this year is next year's; a weekday is the next one ("next Friday": the one in the
     * following week); a date without a time is a whole day. Anything it can't place (a leftover number or word such
     * as "of next month", conflicting dates, a numeric date like 10/11) gives [Read.Unreadable]: a wrong date is worse
     * than asking again.
     */
    fun parse(text: String, now: ZonedDateTime, lexicon: Lexicon = Lexicon.EN): Read<WhenSpan> {
        val zone = now.zone
        val today = now.toLocalDate()
        val sc = Scan(normalize(text, lexicon))
        val dates = mutableListOf<LocalDate>()
        var instant: ZonedDateTime? = null
        var instantEnd: ZonedDateTime? = null
        var duration: Duration? = null
        var period: Period? = null
        var allDay = false

        // ISO first: its digits and dashes would read as other things. RFC 3339 puts an offset after the seconds, so
        // "15:00-16:00" (no seconds) is a range while "15:00:00-04:00" is an offset; "+02:00" and "Z" are offsets either way.
        while (true) {
            val m = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})(?:[t ](\d{1,2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?""").find(sc.s) ?: break
            val g = m.groupValues
            val date = date(g[1].toInt(), g[2].toInt(), g[3].toInt()) ?: return Read.Unreadable(SAY_DATE)
            var cut = m.range.last + 1
            var tail = ""
            if (g[4].isEmpty()) {
                dates += date
            } else {
                val t = time(g[4].toInt(), g[5].toInt()) ?: return Read.Unreadable(SAY_DATE)
                val rest = sc.s.substring(cut)
                val off = Regex("""^(z|[+-]\d{2}:?\d{2})(?![\d:])""").find(rest)
                val at = if (off != null && (g[6].isNotEmpty() || !off.value.startsWith("-"))) {
                    cut += off.value.length
                    val o = offset(off.value) ?: return Read.Unreadable(SAY_DATE)
                    OffsetDateTime.of(date, t, o).atZoneSameInstant(zone)
                } else {
                    // "T15:00-16:00": the end of a range, read like "15:00 to 16:00".
                    Regex("""^ ?- ?(\d{1,2}:\d{2})""").find(rest)?.let { r -> cut += r.value.length; tail = " to ${r.groupValues[1]}" }
                    ZonedDateTime.of(date, t, zone)
                }
                when {
                    instant == null -> instant = at
                    instantEnd == null && at.isAfter(instant) -> instantEnd = at
                    else -> return Read.Unreadable(SAY_DATE)
                }
            }
            sc.s = (sc.s.substring(0, m.range.first) + " " + tail + " " + sc.s.substring(cut)).replace(Regex("\\s+"), " ").trim()
        }
        if (Regex("""\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b\d{1,2}\.\d{1,2}\.(?:\d{2,4})?(?:\s|$)""").containsMatchIn(sc.s)) return Read.Unreadable(SAY_MONTH)
        // A dash between two times is a range ("9-10:30", "2-4pm"); only now, so an ISO date or offset keeps its dashes.
        sc.s = sc.s.replace(Regex("""(?<![\d:.h-])(\d{1,2}(?:[:.h]\d{2})?(?: ?[ap]m)?) ?- ?(\d{1,2})(?![\d-])"""), "$1 to $2")
        // An ISO start with an end time after it ("2026-10-14T15:00 to 16:30").
        val isoStart = instant
        if (isoStart != null && instantEnd == null) {
            sc.take(Regex("""\b(?:to|until|till|til) ($CLOCK)(?=\s|$)"""))?.let { m ->
                val c = clock(m.groupValues[1]) ?: return Read.Unreadable(SAY_DATE)
                val e = endTime(c, isoStart.hour, isoStart.minute) ?: return Read.Unreadable(SAY_DATE)
                var end = ZonedDateTime.of(isoStart.toLocalDate(), e, zone)
                if (!end.isAfter(isoStart)) end = end.plusDays(1)
                instantEnd = end
            }
        }

        if (sc.take(Regex("""\b(?:all day|all-day|whole day|full day)\b""")) != null) allDay = true
        sc.take(Regex("""\b(?:the )?day after tomorrow\b"""))?.let { dates += today.plusDays(2) }
        // "in 20 minutes", "2 weeks from now", "in an hour and a half", "in one and a half hours", "in 3 months".
        sc.take(Regex("""\b(?:in half an hour|half an hour from now)\b"""))?.let { instant = minute(now).plusMinutes(30) }
        val relative = listOf(
            Regex("""\bin ($NUM_RE)( and a half)? (minute|hour|day|week|month)s?( and a half)?\b"""),
            Regex("""\b($NUM_RE)( and a half)? (minute|hour|day|week|month)s?( and a half)? from now\b"""),
        )
        for (re in relative) {
            sc.takeAll(re) { m ->
                val g = m.groupValues
                val half = g[2].isNotEmpty() || g[4].isNotEmpty()
                val n = (number(g[1]) ?: return Read.Unreadable(SAY_DATE)) + if (half) 0.5 else 0.0
                when (g[3]) {
                    "minute" -> instant = minute(now).plusSeconds((n * 60).toLong())
                    "hour" -> instant = minute(now).plusSeconds((n * 3600).toLong())
                    else -> {
                        // Days, weeks and months are whole: "in 1.5 days" is not a date.
                        if (n % 1.0 != 0.0) return Read.Unreadable(SAY_DATE)
                        dates += when (g[3]) {
                            "day" -> today.plusDays(n.toLong())
                            "week" -> today.plusWeeks(n.toLong())
                            else -> today.plusMonths(n.toLong())
                        }
                    }
                }
            }
        }
        // "a week from Friday", "two weeks from tomorrow"; "Friday after next".
        sc.takeAll(Regex("""\b($NUM_RE) weeks? from ($DAY_RE|today|tomorrow)\b""")) { m ->
            val n = number(m.groupValues[1])?.takeIf { it % 1.0 == 0.0 } ?: return Read.Unreadable(SAY_DATE)
            val base = when (val d = m.groupValues[2]) {
                "today" -> today
                "tomorrow" -> today.plusDays(1)
                else -> today.with(TemporalAdjusters.next(DAYS.getValue(d)))
            }
            dates += base.plusWeeks(n.toLong())
        }
        sc.takeAll(Regex("""\b($DAY_RE) after next\b""")) { m -> dates += today.with(TemporalAdjusters.next(DAYS.getValue(m.groupValues[1]))).plusWeeks(1) }
        sc.take(Regex("""\bfor (?:half an hour|30 minutes)\b"""))?.let { duration = Duration.ofMinutes(30) }
        sc.take(Regex("""\bfor ($NUM_RE)( and a half)? (minute|hour)s?( and a half)?\b"""))?.let { m ->
            val g = m.groupValues
            val n = (number(g[1]) ?: return Read.Unreadable(SAY_DATE)) + if (g[2].isNotEmpty() || g[4].isNotEmpty()) 0.5 else 0.0
            duration = Duration.ofSeconds(if (g[3] == "hour") (n * 3600).toLong() else (n * 60).toLong())
        }
        // "next week" / "this week" / "the week after next" only shift a weekday ("Friday next week"); alone they are
        // not a time for an event (read_calendar reads them as ranges, see [range]).
        var weekShift: Long? = null
        sc.take(Regex("""\b(?:the )?week after next\b"""))?.let { weekShift = 2 }
        sc.take(Regex("""\b(next|this|the following|following) week\b"""))?.let { weekShift = if (it.groupValues[1] == "this") 0 else 1 }

        sc.take(Regex("""\btoday\b"""))?.let { dates += today }
        sc.take(Regex("""\byesterday\b"""))?.let { dates += today.minusDays(1) }
        // Before 05:00, "tonight" is the night already under way.
        sc.take(Regex("""\btonight\b"""))?.let { dates += if (now.hour < 5) today.minusDays(1) else today; period = Period.NIGHT }
        sc.take(Regex("""\btomorrow\b"""))?.let { dates += today.plusDays(1) }
        sc.take(Regex("""\b(this |in the |at )?(morning|afternoon|evening|night)\b"""))?.let { m ->
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

        // Strict: whatever is left must be filler. A leftover number, number word or "of next month" means the text
        // said something this parser doesn't place, and guessing a date from the rest would be confidently wrong.
        val left = sc.s.split(' ').filter { w -> w.any(Char::isLetterOrDigit) && w !in FILLER }
        if (left.isNotEmpty()) return Read.Unreadable(SAY_DATE)

        val found = dates.isNotEmpty() || instant != null || startClock != null || period != null || allDay
        if (!found) return Read.Unreadable(SAY_DATE)
        val dateSet = dates.distinct()
        if (dateSet.size > 1) return Read.Unreadable(SAY_DATE)
        val date = dateSet.singleOrNull()

        val at = instant
        if (at != null) {
            if (date != null && date != at.toLocalDate() || startClock != null || allDay) return Read.Unreadable(SAY_DATE)
            return Read.Ok(WhenSpan(at, instantEnd ?: duration?.let { at.plus(it) }, false))
        }
        if (allDay) {
            val start = (date ?: today).atStartOfDay(zone)
            return Read.Ok(WhenSpan(start, (date ?: today).plusDays(1).atStartOfDay(zone), true))
        }
        val sClock = startClock
        if (sClock != null) {
            val t = times(sClock, endClock, period) ?: return Read.Unreadable(SAY_DATE)
            // A night hour after "tonight" or "Friday night" is that night's, the next calendar day.
            val day = date?.plusDays(t.nextDay) ?: if (ZonedDateTime.of(today, t.start, zone).isAfter(now)) today else today.plusDays(1)
            val start = ZonedDateTime.of(day, t.start, zone)
            val end = t.end?.let { var e = ZonedDateTime.of(day, it, zone); if (!e.isAfter(start)) e = e.plusDays(1); e } ?: duration?.let { start.plus(it) }
            return Read.Ok(WhenSpan(start, end, false))
        }
        val p = period
        if (p != null) {
            val day = date ?: if (ZonedDateTime.of(today, p.default, zone).isAfter(now)) today else today.plusDays(1)
            val start = ZonedDateTime.of(day, p.default, zone)
            return Read.Ok(WhenSpan(start, duration?.let { start.plus(it) }, false))
        }
        val d = date ?: return Read.Unreadable(SAY_DATE)
        if (duration != null) return Read.Unreadable(SAY_DATE)
        return Read.Ok(WhenSpan(d.atStartOfDay(zone), d.plusDays(1).atStartOfDay(zone), true))
    }

    /**
     * The day or week [text] names, for read_calendar: "today", "Friday", "October 14", an ISO date, "this week" (from
     * today to Sunday night), "next week" (Monday to Sunday), "the week after next", "this weekend", "next weekend",
     * "the weekend after next". The week words count only as the whole text. Blank means today.
     */
    fun range(text: String, now: ZonedDateTime, lexicon: Lexicon = Lexicon.EN): Read<DayRange> {
        val zone = now.zone
        val today = now.toLocalDate()
        fun days(from: LocalDate, to: LocalDate) = Read.Ok(DayRange(from.atStartOfDay(zone), to.atStartOfDay(zone)))
        val s = normalize(text, lexicon)
        if (s.isBlank()) return days(today, today.plusDays(1))
        val nextMonday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        Regex("""^(?:the )?(?:(this|next|following|coming) )?weekend( after next)?$""").find(s)?.let { m ->
            val saturday = if (today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY) today
            else today.with(TemporalAdjusters.next(DayOfWeek.SATURDAY))
            val from = when {
                m.groupValues[2].isNotEmpty() -> nextMonday.plusDays(12)
                m.groupValues[1] == "next" || m.groupValues[1] == "following" -> nextMonday.plusDays(5)
                else -> saturday
            }
            return days(from, from.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
        }
        Regex("""^(?:the )?(?:(this|next|following|coming) )?week( after next)?$""").find(s)?.let { m ->
            return when {
                m.groupValues[2].isNotEmpty() -> days(nextMonday.plusWeeks(1), nextMonday.plusWeeks(2))
                m.groupValues[1] == "next" || m.groupValues[1] == "following" -> days(nextMonday, nextMonday.plusWeeks(1))
                else -> days(today, nextMonday)
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
        // "12 midnight" and "12 noon" are one time each.
        s = s.replace(Regex("""\b12 ?(?:am|pm)? ?(midnight|noon)\b"""), "$1")
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
        if (t == "midnight") return Clock(0, 0, null, true, midnight = true)
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

    /** A start time, [nextDay] 1 when it is the night's early hours after the date said, and an end time if one was said. */
    private class Times(val start: LocalTime, val nextDay: Long, val end: LocalTime?)

    /**
     * The hour a clock means, and 1 when it falls after midnight of the night the date names. Without am/pm or a
     * 24-hour form: the period decides ("this afternoon at 3"), else 1 to 7 are afternoon and evening hours
     * (appointments), 8 to 11 morning ones, 12 is noon. At night (tonight, "Friday night", midnight): 12 is midnight,
     * 1 to 4 that night's early morning, 5 to 11 the evening; in the evening 12 is midnight too.
     */
    private fun hour(c: Clock, period: Period?): Pair<Int, Long> {
        val night = period == Period.NIGHT
        return when {
            c.midnight -> 0 to 1L
            c.mer == "am" -> (if (c.h == 12) 0 else c.h).let { h -> h to if (night && h < 5) 1L else 0L }
            c.mer == "pm" -> (if (c.h == 12) 12 else c.h + 12) to 0L
            c.h24 -> c.h to if (night && c.h < 5) 1L else 0L
            period == Period.MORNING -> c.h to 0L
            night -> when (c.h) { 12 -> 0 to 1L; in 1..4 -> c.h to 1L; else -> c.h + 12 to 0L }
            period == Period.EVENING -> if (c.h == 12) 0 to 1L else c.h + 12 to 0L
            period != null -> (if (c.h == 12) 12 else c.h + 12) to 0L
            c.h in 1..7 -> c.h + 12 to 0L
            else -> c.h to 0L
        }
    }

    /**
     * An end time after a start at [startH]:[startM]: an end without am/pm is the first of its two readings after the
     * start ("10 to 2" ends at 14:00); none is: it ends after midnight, at b.h % 12 ("10pm to 2" ends at 02:00).
     */
    private fun endTime(b: Clock, startH: Int, startM: Int): LocalTime? {
        if (b.midnight) return time(0, 0)
        val h = when {
            b.mer == "am" -> if (b.h == 12) 0 else b.h
            b.mer == "pm" -> if (b.h == 12) 12 else b.h + 12
            b.h24 -> b.h
            else -> listOf(b.h % 12, b.h % 12 + 12).firstOrNull { it * 60 + b.m > startH * 60 + startM } ?: (b.h % 12)
        }
        return time(h, b.m)
    }

    private fun times(a: Clock, b: Clock?, period: Period?): Times? {
        if (b == null) {
            val (h, shift) = hour(a, period)
            return Times(time(h, a.m) ?: return null, shift, null)
        }
        val start: Pair<Int, Long>
        if (a.mer == null && !a.h24 && !a.midnight && b.mer != null) {
            // "from 2 to 4pm": the start takes the end's half of the day when that keeps it first.
            val endH = hour(b, period).first
            val same = hour(a.copy(mer = b.mer), period)
            start = if (same.first * 60 + a.m <= endH * 60 + b.m) same else hour(a.copy(mer = if (b.mer == "pm") "am" else "pm"), period)
        } else {
            start = hour(a, period)
        }
        val s = time(start.first, a.m) ?: return null
        return Times(s, start.second, endTime(b, start.first, a.m) ?: return null)
    }

    private fun time(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null

    private fun date(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (_: Exception) { null }

    private fun minute(now: ZonedDateTime): ZonedDateTime = now.truncatedTo(ChronoUnit.MINUTES)

    private fun number(s: String): Double? = s.toDoubleOrNull() ?: NUMBER_WORDS[s]

    /** "z", "+02:00", "-0400": an offset from -12:00 to +14:00 (the ones in use), else null. */
    private fun offset(s: String): ZoneOffset? {
        if (s == "z") return ZoneOffset.UTC
        val m = Regex("""^([+-])(\d{2}):?(\d{2})$""").find(s) ?: return null
        val minutes = (m.groupValues[2].toInt() * 60 + m.groupValues[3].toInt()) * if (m.groupValues[1] == "-") -1 else 1
        if (m.groupValues[3].toInt() > 59 || minutes !in -12 * 60..14 * 60) return null
        return ZoneOffset.ofTotalSeconds(minutes * 60)
    }
}
