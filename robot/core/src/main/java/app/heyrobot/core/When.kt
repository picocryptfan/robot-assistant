// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import java.text.Normalizer
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
// reads them here, so the model never builds a timestamp. 1.1: Québec French too (« vendredi à midi », « de 9 h à
// 10 h 30 »), through [Lexicon.FR], which rewrites French into the parser's English words.

/** What a `when` says: a start, an end if one was said, and whether it is a whole day. */
data class WhenSpan(val start: ZonedDateTime, val end: ZonedDateTime?, val allDay: Boolean)

/**
 * 1.1: a day or a time named in free words ([WhenParser.find]): what it reads as ([span]) and the words ([said]).
 * [dated]: it names a day; [clock]: a clock time, [marked] when it can be read only one way (am/pm, 15:00, noon), else
 * it may be either half of the day; [relative]: "in 20 minutes"; [bare]: a lone number or hour word.
 */
data class Mention(val span: WhenSpan, val said: String, val dated: Boolean, val clock: Boolean, val marked: Boolean, val relative: Boolean,
    val bare: Boolean = false) {
    /** The clock time it names, if any. */
    val time: java.time.LocalTime? get() = if (clock) span.start.toLocalTime() else null

    /** [time] matches [h]:[m]: the same, or (unmarked) the other half of the day ("7" is 07:00 or 19:00). */
    fun sameTime(h: Int, m: Int): Boolean {
        val t = time ?: return false
        return t.minute == m && (t.hour == h || (!marked && t.hour % 12 == h % 12))
    }
}

/** A day or a week of calendar: [from] inclusive, [to] exclusive (midnight to midnight). */
data class DayRange(val from: ZonedDateTime, val to: ZonedDateTime) {
    val days: Long get() = ChronoUnit.DAYS.between(from.toLocalDate(), to.toLocalDate())
}

/** The parser's answer: the value, or the short fixed-wording [Unreadable.detail] sent back to the model. */
sealed class Read<out T> {
    data class Ok<T>(val value: T) : Read<T>()
    data class Unreadable(val detail: String) : Read<Nothing>() {
        /** 1.1: [detail] in words without a language yet (the card says it in the reply language; the model reads [detail]). */
        var why: Phrase = Phrase.plain(detail)
            private set

        constructor(why: Phrase) : this(why.en()) {
            this.why = why
        }
    }
}

/**
 * The words of one language, mapped to the parser's own English words ("demain" → "tomorrow"). [rewrite] turns the
 * language's phrases into the parser's before the words are mapped (French's « 9 h 30 », « l'après-midi », « lundi en
 * huit »): a word is mapped only when it is all letters. English needs only its short forms and rewrites nothing.
 */
class Lexicon(private val words: Map<String, String>, private val rewrite: (String) -> String = { it }) {
    fun canonical(word: String): String = words[word] ?: word

    /** [s] (lower case, without most punctuation) with the language's phrases in the parser's words. */
    fun rewrite(s: String): String = rewrite.invoke(s)

    companion object {
        private val EN_WORDS = mapOf(
            "tmrw" to "tomorrow", "tmr" to "tomorrow", "tomorow" to "tomorrow", "tonite" to "tonight",
            "mon" to "monday", "tue" to "tuesday", "tues" to "tuesday", "wed" to "wednesday", "weds" to "wednesday",
            "thu" to "thursday", "thur" to "thursday", "thurs" to "thursday", "fri" to "friday", "sat" to "saturday", "sun" to "sunday",
            "jan" to "january", "feb" to "february", "mar" to "march", "apr" to "april", "jun" to "june", "jul" to "july",
            "aug" to "august", "sep" to "september", "sept" to "september", "oct" to "october", "nov" to "november", "dec" to "december",
            "min" to "minute", "mins" to "minutes", "hr" to "hour", "hrs" to "hours", "wk" to "week", "wks" to "weeks",
        )

        /** 1.1: and its spelled-out ordinals in a date ("the twentieth of November", EnglishWhen). */
        val EN = Lexicon(EN_WORDS, EnglishWhen::rewrite)

        /** 1.1: Québec French, with or without accents; English's short forms still read (« demain 3pm »). */
        val FR: Lexicon by lazy { Lexicon(EN_WORDS + FrenchWhen.WORDS, FrenchWhen::rewrite) }

        /** The words of [lang]. */
        fun of(lang: Lang): Lexicon = if (lang == Lang.FR) FR else EN
    }
}

object WhenParser {
    /** What the model is told when the words can't be read (docs/phase-2.md). */
    val SAY_DATE: String get() = InfoStrings.sayDate().en()
    /** 10/11 is the 10th of November or the 11th of October: Robot doesn't guess. */
    val SAY_MONTH: String get() = InfoStrings.sayMonth().en()

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
    fun parse(text: String, now: ZonedDateTime, lexicon: Lexicon = Lexicon.EN): Read<WhenSpan> = read(text, now, lexicon, null)

    /**
     * 1.1: how a reading came about ([find] and its users): [dated] a day was named (today, a weekday, a date, "in 3 days"),
     * [clock] a clock time ([marked]: am/pm, a 24-hour form, noon or midnight; [bare]: a lone number or hour word with
     * nothing before it that makes it a time), [relative] "in 20 minutes", [period] only a part of the day ("tonight").
     */
    private class Flags {
        var dated = false
        var clock = false
        var marked = false
        var bare = false
        var relative = false
        var period = false
    }

    private fun read(text: String, now: ZonedDateTime, lexicon: Lexicon, f: Flags?): Read<WhenSpan> {
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
            val date = date(g[1].toInt(), g[2].toInt(), g[3].toInt()) ?: return Read.Unreadable(InfoStrings.sayDate())
            var cut = m.range.last + 1
            var tail = ""
            if (g[4].isEmpty()) {
                dates += date
            } else {
                val t = time(g[4].toInt(), g[5].toInt()) ?: return Read.Unreadable(InfoStrings.sayDate())
                val rest = sc.s.substring(cut)
                val off = Regex("""^(z|[+-]\d{2}:?\d{2})(?![\d:])""").find(rest)
                val at = if (off != null && (g[6].isNotEmpty() || !off.value.startsWith("-"))) {
                    cut += off.value.length
                    val o = offset(off.value) ?: return Read.Unreadable(InfoStrings.sayDate())
                    OffsetDateTime.of(date, t, o).atZoneSameInstant(zone)
                } else {
                    // "T15:00-16:00": the end of a range, read like "15:00 to 16:00".
                    Regex("""^ ?- ?(\d{1,2}:\d{2})""").find(rest)?.let { r -> cut += r.value.length; tail = " to ${r.groupValues[1]}" }
                    ZonedDateTime.of(date, t, zone)
                }
                when {
                    instant == null -> instant = at
                    instantEnd == null && at.isAfter(instant) -> instantEnd = at
                    else -> return Read.Unreadable(InfoStrings.sayDate())
                }
                f?.apply { dated = true; clock = true; marked = true }
            }
            sc.s = (sc.s.substring(0, m.range.first) + " " + tail + " " + sc.s.substring(cut)).replace(Regex("\\s+"), " ").trim()
        }
        if (Regex("""\b\d{1,2}/\d{1,2}(?:/\d{2,4})?\b|\b\d{1,2}\.\d{1,2}\.(?:\d{2,4})?(?:\s|$)""").containsMatchIn(sc.s)) return Read.Unreadable(InfoStrings.sayMonth())
        // A dash between two times is a range ("9-10:30", "2-4pm"); only now, so an ISO date or offset keeps its dashes.
        sc.s = sc.s.replace(Regex("""(?<![\d:.h-])(\d{1,2}(?:[:.h]\d{2})?(?: ?[ap]m)?) ?- ?(\d{1,2})(?![\d-])"""), "$1 to $2") // l10n: not text (a pattern)
        // An ISO start with an end time after it ("2026-10-14T15:00 to 16:30").
        val isoStart = instant
        if (isoStart != null && instantEnd == null) {
            sc.take(Regex("""\b(?:to|until|till|til) ($CLOCK)(?=\s|$)"""))?.let { m ->
                val c = clock(m.groupValues[1]) ?: return Read.Unreadable(InfoStrings.sayDate())
                val e = endTime(c, isoStart.hour, isoStart.minute) ?: return Read.Unreadable(InfoStrings.sayDate())
                var end = ZonedDateTime.of(isoStart.toLocalDate(), e, zone)
                if (!end.isAfter(isoStart)) end = end.plusDays(1)
                instantEnd = end
            }
        }

        if (sc.take(Regex("""\b(?:all day|all-day|whole day|full day)\b""")) != null) allDay = true
        sc.take(Regex("""\b(?:the )?day after tomorrow\b"""))?.let { dates += today.plusDays(2) }
        // "in 20 minutes", "2 weeks from now", "in an hour and a half", "in one and a half hours", "in 3 months".
        sc.take(Regex("""\b(?:in half an hour|half an hour from now)\b"""))?.let { instant = minute(now).plusMinutes(30); f?.relative = true }
        val relative = listOf(
            Regex("""\bin ($NUM_RE)( and a half)? (minute|hour|day|week|month)s?( and a half)?\b"""),
            Regex("""\b($NUM_RE)( and a half)? (minute|hour|day|week|month)s?( and a half)? from now\b"""),
        )
        for (re in relative) {
            sc.takeAll(re) { m ->
                val g = m.groupValues
                val half = g[2].isNotEmpty() || g[4].isNotEmpty()
                val n = (number(g[1]) ?: return Read.Unreadable(InfoStrings.sayDate())) + if (half) 0.5 else 0.0
                when (g[3]) {
                    "minute" -> { instant = minute(now).plusSeconds((n * 60).toLong()); f?.relative = true }
                    "hour" -> { instant = minute(now).plusSeconds((n * 3600).toLong()); f?.relative = true }
                    else -> {
                        // Days, weeks and months are whole: "in 1.5 days" is not a date.
                        if (n % 1.0 != 0.0) return Read.Unreadable(InfoStrings.sayDate())
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
            val n = number(m.groupValues[1])?.takeIf { it % 1.0 == 0.0 } ?: return Read.Unreadable(InfoStrings.sayDate())
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
            val n = (number(g[1]) ?: return Read.Unreadable(InfoStrings.sayDate())) + if (g[2].isNotEmpty() || g[4].isNotEmpty()) 0.5 else 0.0
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
        if (weekShift != null && dates.isEmpty()) return Read.Unreadable(InfoStrings.sayDate())
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
                var d = date(year ?: today.year, month, day) ?: return Read.Unreadable(InfoStrings.sayDate())
                if (year == null && d.isBefore(today)) d = date(today.year + 1, month, day) ?: return Read.Unreadable(InfoStrings.sayDate())
                dates += d
            }
        }
        // "on the 14th": this month's, or next month's once it has passed.
        sc.takeAll(Regex("""\b(?:on )?(?:the )?(\d{1,2})(?:st|nd|rd|th)\b""")) { m ->
            val day = m.groupValues[1].toInt()
            val d = date(today.year, today.monthValue, day)?.takeIf { !it.isBefore(today) }
                ?: today.plusMonths(1).let { date(it.year, it.monthValue, day) } ?: return Read.Unreadable(InfoStrings.sayDate())
            dates += d
        }

        // Times: "half past 3", ranges ("9 to 10:30", "from 2 to 4pm", "between 2 and 3"), then one time.
        var startClock: Clock? = null
        var endClock: Clock? = null
        sc.take(Regex("""\b(half past|quarter past|quarter to) (\d{1,2}|${HOUR_WORDS.joinToString("|")})(?: ?(am|pm))?\b"""))?.let { m ->
            val h = m.groupValues[2].toIntOrNull() ?: (HOUR_WORDS.indexOf(m.groupValues[2]) + 1)
            if (h !in 1..12) return Read.Unreadable(InfoStrings.sayDate())
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
                startClock = clock(g[1].ifEmpty { g[3] }) ?: return Read.Unreadable(InfoStrings.sayDate())
                endClock = clock(g[2].ifEmpty { g[4] }) ?: return Read.Unreadable(InfoStrings.sayDate())
            }
        }
        if (startClock == null) {
            val all = Regex("""(?:^|\s)((?:at |around |about |by |from )?)($CLOCK)(?=\s|$)""").findAll(sc.s).toList()
            // A bare number is a time only after "at" (or a word like it), or when it is the only number left.
            val bareNumbers = all.count { it.groupValues[2].all(Char::isDigit) }
            val m = all.firstOrNull { it.groupValues[1].isNotEmpty() || !it.groupValues[2].all(Char::isDigit) || bareNumbers == 1 }
            if (m != null) {
                startClock = clock(m.groupValues[2]) ?: return Read.Unreadable(InfoStrings.sayDate())
                if (m.groupValues[1].isEmpty()) f?.bare = true
                sc.s = sc.s.removeRange(m.groups[2]!!.range)
            }
        }

        // Strict: whatever is left must be filler. A leftover number, number word or "of next month" means the text
        // said something this parser doesn't place, and guessing a date from the rest would be confidently wrong.
        val left = sc.s.split(' ').filter { w -> w.any(Char::isLetterOrDigit) && w !in FILLER }
        if (left.isNotEmpty()) return Read.Unreadable(InfoStrings.sayDate())

        val found = dates.isNotEmpty() || instant != null || startClock != null || period != null || allDay
        f?.let { g ->
            if (dates.isNotEmpty()) g.dated = true
            startClock?.let { c ->
                g.clock = true
                if (c.mer != null || c.h24) { g.marked = true; g.bare = false }
            }
            if (startClock == null) g.bare = false
            g.period = period != null && startClock == null && instant == null
        }
        if (!found) return Read.Unreadable(InfoStrings.sayDate())
        val dateSet = dates.distinct()
        if (dateSet.size > 1) return Read.Unreadable(InfoStrings.sayDate())
        val date = dateSet.singleOrNull()

        val at = instant
        if (at != null) {
            if (date != null && date != at.toLocalDate() || startClock != null || allDay) return Read.Unreadable(InfoStrings.sayDate())
            return Read.Ok(WhenSpan(at, instantEnd ?: duration?.let { at.plus(it) }, false))
        }
        if (allDay) {
            val start = (date ?: today).atStartOfDay(zone)
            return Read.Ok(WhenSpan(start, (date ?: today).plusDays(1).atStartOfDay(zone), true))
        }
        val sClock = startClock
        if (sClock != null) {
            val t = times(sClock, endClock, period) ?: return Read.Unreadable(InfoStrings.sayDate())
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
        val d = date ?: return Read.Unreadable(InfoStrings.sayDate())
        if (duration != null) return Read.Unreadable(InfoStrings.sayDate())
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

    /**
     * 1.1: [text] in [lang]'s words first, then the other language's when the first finds no time: the model may pass the
     * words in either, and people mix them (« demain 3pm »). When neither reads it, English's answer, so an English
     * phrase reads exactly as it does with [Lexicon.EN] whatever the turn's language.
     */
    fun parse(text: String, now: ZonedDateTime, lang: Lang): Read<WhenSpan> = either(lang) { parse(text, now, it) }

    /** [range] in [lang]'s words, then the other's, as [parse] with a language. */
    fun range(text: String, now: ZonedDateTime, lang: Lang): Read<DayRange> = either(lang) { range(text, now, it) }

    /**
     * 1.1: the days and clock times named anywhere in free words ([text]: what you said, or a reply), read in [lang]'s
     * words then the other's, as [parse] would read each on its own: "set an alarm for 7 on the 20th", « rappelle-moi
     * d'appeler le dentiste le vingt novembre ». The longest stretch of at most [MAX_WORDS] words that reads, left to
     * right. Only a day, a clock time or "in 20 minutes" counts (not "tonight" alone), and a bare number counts as a time
     * only after a word like "at", "for" or « à » and never before a unit ("for 5 minutes", "2 people"), unless it is all
     * of [text] (the answer to "What time?").
     */
    fun find(text: String, now: ZonedDateTime, lang: Lang): List<Mention> {
        val words = text.split(Regex("""\s+""")).map { it.trim(',', ';', '.', '!', '?', ':', '…', '"', '“', '”', '(', ')') }.filter { it.isNotEmpty() }
        val first = Lexicon.of(lang)
        val other = if (lang == Lang.FR) Lexicon.EN else Lexicon.FR
        val out = mutableListOf<Mention>()
        // Only stretches that end on a word a date or time can end on, with one starting within its first 4 words: a long
        // note or message is read in a few milliseconds, not one parse per word and length.
        val anchor = words.map(::anchors)
        val ends = words.indices.map { anchor[it] || plainWord(words[it]) in END_WORDS }
        var i = 0
        while (i < words.size) {
            var took = 0
            if ((i until minOf(i + 4, words.size)).none { anchor[it] }) { i++; continue }
            for (n in minOf(MAX_WORDS, words.size - i) downTo 1) {
                if (!ends[i + n - 1]) continue
                val said = words.subList(i, i + n).joinToString(" ")
                // In an English turn the English article "a" is never French's « à » without its accent ("a 5 minute timer").
                val lexicons = if (lang != Lang.FR && words.subList(i, i + n).any { it.equals("a", ignoreCase = true) }) listOf(first) else listOf(first, other)
                val m = lexicons.firstNotNullOfOrNull { lex -> mention(said, now, lex) } ?: continue
                if (m.bare && n < words.size && !(leads(words.subList(maxOf(0, i - 1), i + n)) && !unitAfter(words.getOrNull(i + n)))) continue
                out += m
                took = n
                break
            }
            i += maxOf(took, 1)
        }
        return out
    }

    /** 1.1: all of [text] read as one stretch of [find] (with what it named), in [lang]'s words then the other's; null when neither reads it. */
    fun reads(text: String, now: ZonedDateTime, lang: Lang): Mention? =
        mention(text, now, Lexicon.of(lang)) ?: mention(text, now, if (lang == Lang.FR) Lexicon.EN else Lexicon.FR)

    /** What [find] reads in one stretch of words, or null. */
    private fun mention(said: String, now: ZonedDateTime, lex: Lexicon): Mention? {
        val f = Flags()
        val r = read(said, now, lex, f) as? Read.Ok ?: return null
        if (!f.dated && !f.clock && !f.relative) return null
        return Mention(r.value, said, dated = f.dated, clock = f.clock, marked = f.marked, relative = f.relative, bare = f.bare)
    }

    private fun plainWord(w: String) = Normalizer.normalize(w, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "").lowercase(Locale.ROOT).replace('’', '\'')

    /** Words a date or time is made of ([find]): any with a digit, or one of [ANCHOR_WORDS] (parts of "l'après-midi" too). */
    private fun anchors(w: String): Boolean {
        val p = plainWord(w)
        return p.any(Char::isDigit) || p.split('-', '\'').any { it in ANCHOR_WORDS || Lexicon.EN.canonical(it) in ANCHOR_WORDS }
    }

    private val ANCHOR_WORDS: Set<String> by lazy {
        MONTHS.toSet() + DAYS.keys + HOUR_WORDS + NUMBER_WORDS.keys.flatMap { it.split(' ') }.filter { it !in setOf("a", "an", "couple", "few", "of") } +
            EnglishWhen.WORDS + FrenchWhen.ANCHORS + setOf("today", "tonight", "tomorrow", "yesterday", "noon", "midnight", "morning", "afternoon", "evening", // l10n: not text (the words a time is found by)
            "night", "day", "days", "week", "weeks", "weekend", "month", "months", "minute", "minutes", "hour", "hours", "now", "next", "half", "quarter") // l10n: not text (the words a time is found by)
    }

    /** Besides [ANCHOR_WORDS], what a date or time may end on ("7 pm", "7 o'clock"). */
    private val END_WORDS = setOf("am", "pm", "a.m", "p.m", "oclock", "o'clock", "sharp") // l10n: not text (the words a time is found by)

    /** At most this many words make one date or time in [find]. */
    private const val MAX_WORDS = 8

    /** Words that make a bare number a time ("at 7", "for 7", « à 7 », « vers 7 »). */
    private val LEADS = setOf("at", "for", "to", "until", "till", "by", "around", "about", "from", "à", "a", "pour", "vers", "avant", "après", "apres") // l10n: not text (the words a time is found by)

    private fun leads(ws: List<String>): Boolean = ws.any { it.lowercase(Locale.ROOT) in LEADS }

    /** A unit or a thing counted after a number: then it is not a time ("5 minutes", "2 people", « 3 fois »). */
    private val UNITS = Regex("""^(?:sec|secs|second|seconds|seconde|secondes|min|mins|minute|minutes|mn|hr|hrs|hour|hours|heure|heures|day|days|jour|jours|week|weeks|semaine|semaines|month|months|mois|year|years|an|ans|times|fois|people|persons|personnes|%)$""") // l10n: not text (the words a time is found by)

    private fun unitAfter(w: String?): Boolean = w != null && UNITS.matches(w.lowercase(Locale.ROOT))

    private fun <T> either(lang: Lang, read: (Lexicon) -> Read<T>): Read<T> {
        val first = Lexicon.of(lang)
        val other = if (lang == Lang.FR) Lexicon.EN else Lexicon.FR
        val a = read(first)
        if (a is Read.Ok) return a
        val b = read(other)
        if (b is Read.Ok) return b
        return if (first === Lexicon.EN) a else b
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
        s = lexicon.rewrite(s)
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

/**
 * 1.1: English's spelled-out ordinals, as the transcriber writes a spoken date ("the twentieth of November", "November
 * twenty-first", "on the third"), read as the parser's "20th"; and "November the 20th" as "November 20th". Only in a
 * date: after "the", or next to a month. Anywhere else an ordinal stays a word, and the text stays unreadable as before
 * ("first thing", "a second").
 */
private object EnglishWhen {
    private val UNITS = listOf("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth", "ninth")
    private val TEENS = listOf("tenth", "eleventh", "twelfth", "thirteenth", "fourteenth", "fifteenth", "sixteenth", "seventeenth", "eighteenth",
        "nineteenth")

    /** 1 to 31 in words ("twenty first" too, once its hyphen is a space). */
    private val ORDINALS: Map<String, Int> = buildMap {
        UNITS.forEachIndexed { i, w -> put(w, i + 1) }
        TEENS.forEachIndexed { i, w -> put(w, i + 10) }
        put("twentieth", 20)
        put("thirtieth", 30)
        UNITS.forEachIndexed { i, w -> put("twenty $w", 21 + i) }
        put("thirty first", 31)
    }
    private val ORD = ORDINALS.keys.sortedByDescending { it.length }.joinToString("|")

    /** The single words of the ordinals (WhenParser.find's anchors). */
    val WORDS: Set<String> get() = ORDINALS.keys.flatMap { it.split(' ') }.toSet()
    private const val MONTH = "january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec"

    private val RULES = listOf(
        """\bthe ($ORD)\b""" to { g: List<String> -> "the ${nth(g[1])}" }, // l10n: not text (the parser's words)
        """\b($ORD) (of )?($MONTH)\b""" to { g: List<String> -> "${nth(g[1])} ${g[2]}${g[3]}" },
        """\b($MONTH) (?:the )?($ORD)\b""" to { g: List<String> -> "${g[1]} ${nth(g[2])}" },
        """\b($MONTH) the (\d{1,2}(?:st|nd|rd|th))\b""" to { g: List<String> -> "${g[1]} ${g[2]}" },
    ).map { (re, by) -> Regex(re) to by }

    private fun nth(w: String): String {
        val n = ORDINALS.getValue(w)
        val suffix = if (n in 11..13) "th" else when (n % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" } // l10n: not text (the parser's words)
        return "$n$suffix"
    }

    fun rewrite(text: String): String {
        var s = text.replace(Regex("""\b(twenty|thirty)-(?=(?:${UNITS.joinToString("|")})\b)"""), "$1 ")
        for ((re, by) in RULES) s = re.replace(s) { by(it.groupValues) }
        return s
    }
}

/**
 * 1.1: Québec French read into the parser's English words ([Lexicon.FR]), with or without accents. Elisions, hyphens and
 * numbers in words go first (« l'après-midi », « dix-huit »), then the phrases: clock times (« 9 h 30 », « 9 heures et
 * quart », « midi moins le quart »), parts of the day (« du soir »), lengths (« pendant une heure et demie »), weeks
 * (« lundi prochain », « lundi en huit ») and ranges (« de 9 h à 10 h 30 »). A time said with « h » or « heures » reads
 * as a bare English one does: « 3 h » is 15:00 and « 9 h » 9:00 (as "at 3" and "at 9"), « 3 h du matin » 3:00, « 15 h »
 * and « 07 h » as said. Meal words (« dîner », « souper ») are not times, as "lunch" isn't in English.
 */
private object FrenchWhen {
    private val DAYS = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
    private val DAY = DAYS.joinToString("|")
    private const val MONTH = "janvier|fevrier|mars|avril|mai|juin|juillet|aout|septembre|octobre|novembre|decembre"

    /** French words onto the parser's, once the phrases are rewritten; an unmapped French word leaves the text unreadable. */
    val WORDS: Map<String, String> = DAYS.zip(DayOfWeek.entries.map { it.name.lowercase(Locale.ROOT) }).toMap() + mapOf(
        "janvier" to "january", "janv" to "january", "fevrier" to "february", "fevr" to "february", "fev" to "february", "mars" to "march",
        "avril" to "april", "avr" to "april", "mai" to "may", "juin" to "june", "juillet" to "july", "juil" to "july", "aout" to "august",
        "septembre" to "september", "octobre" to "october", "novembre" to "november", "decembre" to "december",
        "demain" to "tomorrow", "hier" to "yesterday", "matin" to "morning", "matinee" to "morning", "soir" to "evening", "soiree" to "evening",
        "nuit" to "night", "midi" to "noon", "minuit" to "midnight", "semaine" to "week", "semaines" to "weeks", "jour" to "day", "jours" to "days",
        "mois" to "months", "heure" to "hour", "heures" to "hours", "mn" to "minutes",
        "le" to "the", "la" to "the", "les" to "the", "l" to "the", "ce" to "this", "cet" to "this", "cette" to "this",
        "dans" to "in", "en" to "in", "pour" to "for", "pendant" to "for", "durant" to "for", "vers" to "around", "environ" to "about",
        "et" to "and", "pile" to "sharp", "precises" to "sharp", "precise" to "sharp", "tapant" to "sharp", "tapantes" to "sharp",
    )

    /** 0 to 59 and the lengths people say (70, 80, 90), in words: « vingt et une », « quarante-cinq ». */
    private val NUMBERS: Map<String, Int> = buildMap {
        val units = listOf("zero", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze", "treize",
            "quatorze", "quinze", "seize")
        units.forEachIndexed { i, w -> put(w, i) }
        put("une", 1)
        listOf("sept", "huit", "neuf").forEachIndexed { i, w -> put("dix $w", 17 + i) }
        for ((t, n) in listOf("vingt" to 20, "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60)) {
            put(t, n)
            put("$t et un", n + 1)
            put("$t et une", n + 1)
            for (u in 2..9) put("$t ${units[u]}", n + u)
        }
        put("soixante dix", 70)
        put("quatre vingt", 80)
        put("quatre vingts", 80)
        put("quatre vingt dix", 90)
    }
    private val NUMBER_RE = Regex("""(?<![a-z0-9])(${NUMBERS.keys.sortedByDescending { it.length }.joinToString("|")})(?![a-z0-9])""")

    /**
     * 1.1: the spelled-out ordinals of a date, 2 to 31 (« le vingtième », « le vingt et unième novembre », « trentième
     * de novembre »; the first is « premier », in [WHOLE]), as their numbers: only after « le » or before a month.
     */
    private val ORDINALS: Map<String, Int> = NUMBERS.filter { (w, n) -> n in 2..31 && w != "une" && !w.endsWith(" une") }.mapKeys { (w, _) ->
        val last = w.substringAfterLast(' ')
        val ord = when {
            last == "un" -> "unieme"
            last == "cinq" -> "cinquieme"
            last == "neuf" -> "neuvieme"
            last.endsWith("e") -> last.dropLast(1) + "ieme"
            else -> last + "ieme"
        }
        w.substringBeforeLast(' ', "").let { if (it.isEmpty()) ord else "$it $ord" }
    }
    private val ORD_RE = ORDINALS.keys.sortedByDescending { it.length }.joinToString("|")

    /** French's words of a date or time (WhenParser.find's anchors): its days, months, numbers, ordinals and time words, not its fillers. */
    val ANCHORS: Set<String> by lazy {
        WORDS.filterValues { it !in setOf("the", "this", "in", "for", "around", "about", "and") }.keys + NUMBERS.keys.flatMap { it.split(' ') }.filter { it != "et" } +
            ORDINALS.keys.flatMap { it.split(' ') }.filter { it != "et" } + setOf("aujourd", "hui", "aujourdhui", "apres", "avant", "fin", "quart", "demie", "demi", // l10n: not text (the words a time is found by)
            "h", "premier", "huit", "quinze") // l10n: not text (the words a time is found by)
    }
    private val ORDINAL_RULES = listOf(
        Regex("""\b($ORD_RE) (?:de )?($MONTH)\b""") to { g: List<String> -> "${ORDINALS.getValue(g[1])} ${g[2]}" },
        Regex("""\ble ($ORD_RE)\b""") to { g: List<String> -> "le ${ORDINALS.getValue(g[1])}" },
    )

    /** Phrases that are one thing, before the numbers (« une demi-heure » is not « 1 demi heure »). */
    private val WHOLE = listOf(
        """\baujourd ?hui\b""" to "today",
        """\bapres midi\b""" to "afternoon",
        // Québec's « avant-midi » is the morning.
        """\bavant midi\b""" to "morning",
        """\bapres demain\b""" to "day after tomorrow", // l10n: not text (the parser's words)
        """\b(?:fin de semaine|week end)\b""" to "weekend",
        """\b(?:toute la journee|(?:la )?journee (?:complete|entiere))\b""" to "all day", // l10n: not text (the parser's words)
        """\b(?:une |1 )?demie? heure\b""" to "30 minutes", // l10n: not text (the parser's words)
        """\b(?:un |1 )?quart d heure\b""" to "15 minutes", // l10n: not text (the parser's words)
        """\b(?:trois|3) quarts d heure\b""" to "45 minutes", // l10n: not text (the parser's words)
        """\bpremier\b""" to "1er",
        // « sept » alone is the month (« le 15 sept », and English's "Sept"); seven has its unit (« sept heures »), a tens
        // word before it (« dix-sept ») or a month after it (« le sept octobre »).
        """(?<!dix |vingt |trente |quarante |cinquante |soixante )\bsept\b(?! ?(?:h\b|heures?\b|minutes?\b|min\b|mn\b|jours?\b|semaines?\b|mois\b|et\b|moins\b|$MONTH\b))""" to "septembre",
    ).map { (re, by) -> Regex(re) to by }

    private const val H = """(?<![\d:.])(\d{1,2})"""
    private const val HU = """ ?(?:h|heures?)"""
    /** Not followed by a unit or another time: the number after « 9 h » is its minutes. */
    private const val NOT_UNIT = """(?![:\d]| ?(?:h\b|heures?\b|minutes?\b|min\b|mn\b|jours?\b|semaines?\b|mois\b|ans?\b))"""
    private const val CLK = """\d{1,2}:\d{2}(?: [ap]m)?|noon|midnight|\d{1,2}"""
    private const val CLKH = """\d{1,2}:\d{2}(?: [ap]m)?|noon|midnight"""

    /** After the clocks: parts of the day, weeks, ranges and dates, in that order. */
    private val AFTER = listOf(
        """\bce matin\b""" to "this morning", // l10n: not text (the parser's words)
        """\bcet (afternoon|morning)\b""" to "this $1",
        """\b(?:ce soir|cette nuit)\b""" to "tonight",
        """\bce noon\b""" to "today at noon", // l10n: not text (the parser's words)
        """\b(?:du|le|au|en|dans la|dans l|de la|de l|la|l) (?:matin|matinee|morning)\b""" to "in the morning", // l10n: not text (the parser's words)
        """\b(?:de l|dans l|l|en|dans la|de la|la) afternoon\b""" to "in the afternoon", // l10n: not text (the parser's words)
        """\b(?:du|le|en|dans la|de la|la) (?:soir|soiree)\b""" to "in the evening", // l10n: not text (the parser's words)
        """\b(?:de la|dans la|la|en) nuit\b""" to "at night", // l10n: not text (the parser's words)
        """\bd ici (?=\d)""" to "in ",
        """\bd ici\b""" to "by",
        // The week's own « prochaine » first: « la semaine prochaine, mardi » is not « prochain mardi ».
        """\b(?:de )?(?:la )?semaine (?:prochaine|qui vient)\b""" to "next week", // l10n: not text (the parser's words)
        """\b($DAY) prochaine?\b""" to "next $1",
        """\bprochaine? ($DAY)\b""" to "next $1",
        """\b($DAY) qui vient\b""" to "coming $1",
        """\b($DAY|demain|today) en 8\b""" to "1 week from $1", // l10n: not text (the parser's words)
        """\b($DAY|demain|today) en 15\b""" to "2 weeks from $1", // l10n: not text (the parser's words)
        """\b(?:de )?la semaine (?:suivante|d apres)\b""" to "the following week", // l10n: not text (the parser's words)
        """\b(?:de )?cette semaine\b""" to "this week", // l10n: not text (the parser's words)
        """\b(?:le |la )?weekend (?:prochaine?|qui vient)\b""" to "next weekend", // l10n: not text (the parser's words)
        """\b(?:ce|cette) weekend\b""" to "this weekend", // l10n: not text (the parser's words)
        """\b(?:en|la|le) weekend\b""" to "the weekend", // l10n: not text (the parser's words)
        """\bce ($DAY)\b""" to "this $1",
        """\ba partir de\b""" to "from",
        """\bde ($CLK) (?:a|jusqu a) ($CLK)(?=\s|$)""" to "from $1 to $2",
        """\bentre ($CLK) et ($CLK)(?=\s|$)""" to "between $1 and $2",
        """(?<![\d:])($CLKH) (?:a|jusqu a) ($CLK)(?=\s|$)""" to "$1 to $2", // l10n: not text (the parser's words)
        """\bjusqu a ($CLK)(?=\s|$)""" to "until $1",
        """\ba ($CLK)(?=\s|$)""" to "at $1",
        """\bvers (?:les )?($CLK)(?=\s|$)""" to "around $1",
        """\b1er\b""" to "1st",
        """\ble (\d{1,2})(?=\s|$)(?! (?:to|and|until)\b)""" to "the $1th",
        """\b($DAY) (?:le )?(\d{1,2})(?=\s|$)(?! (?:to|and|until)\b)""" to "$1 the $2th", // l10n: not text (the parser's words)
    ).map { (re, by) -> Regex(re) to by }

    private fun hm(h: Int, m: Int) = "$h:${m.toString().padStart(2, '0')}"

    /** The hour before [h] for « moins »: « 1 h moins le quart » is 12:45 as "quarter to 1" is. */
    private fun before(h: Int) = when (h) { 0 -> 23; 1 -> 12; else -> h - 1 }

    private fun String.sub(re: String, by: (List<String>) -> String?): String = Regex(re).replace(this) { m -> by(m.groupValues) ?: m.value }

    fun rewrite(text: String): String {
        var s = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
        s = s.replace(Regex("""[   «»]"""), " ").replace('\'', ' ').replace(Regex("""(?<=[a-z])-(?=[a-z])"""), " ")
            .replace(Regex("""(?<=[a-z])\.(?=\s|$)"""), " ").replace(Regex("""\s+"""), " ").trim()
        for ((re, by) in WHOLE) s = s.replace(re, by)
        for ((re, by) in ORDINAL_RULES) s = re.replace(s) { by(it.groupValues) }
        s = s.replace(NUMBER_RE) { NUMBERS.getValue(it.value).toString() }
        // A length in hours and minutes (« dans 1 heure 20 minutes »), before the clock times take its hours.
        s = s.sub("""\b(dans|d ici|pendant|durant|pour) (\d{1,2})$HU (?:et )?(\d{1,2}) ?(?:minutes?|min|mn)\b""") { g ->
            g[3].toInt().takeIf { it < 60 }?.let { "${g[1]} ${g[2].toInt() * 60 + it} minutes" } // l10n: not text (the parser's words)
        }
        // Clock times: noon and midnight with their minutes, then « 9 h moins le quart », « 9 h et demie », « 9 h 30 », « 9 h ».
        s = s.sub("""\b(midi|minuit) moins (?:le )?quart\b""") { g -> if (g[1] == "midi") "11:45 am" else "23:45" } // l10n: not text (the parser's words)
        s = s.sub("""\b(midi|minuit) moins (\d{1,2})\b$NOT_UNIT""") { g ->
            g[2].toInt().takeIf { it in 1..59 }?.let { if (g[1] == "midi") hm(11, 60 - it) + " am" else hm(23, 60 - it) }
        }
        s = s.sub("""\b(midi|minuit) et (quart|demie?)\b""") { g ->
            val m = if (g[2] == "quart") 15 else 30
            if (g[1] == "midi") hm(12, m) + " pm" else "00:$m"
        }
        s = s.sub("""\b(midi|minuit) (\d{1,2})\b$NOT_UNIT""") { g ->
            g[2].toInt().takeIf { it < 60 }?.let { if (g[1] == "midi") hm(12, it) + " pm" else "00:" + it.toString().padStart(2, '0') }
        }
        s = s.sub("""$H(?:$HU)? moins (?:le )?quart\b""") { g -> g[1].toInt().takeIf { it < 24 }?.let { hm(before(it), 45) } }
        s = s.sub("""$H$HU moins (\d{1,2})\b$NOT_UNIT""") { g ->
            val h = g[1].toInt()
            g[2].toInt().takeIf { h < 24 && it in 1..59 }?.let { hm(before(h), 60 - it) }
        }
        s = s.sub("""$H(?:$HU)? et (quart|demie?)\b""") { g -> g[1].toInt().takeIf { it < 24 }?.let { hm(it, if (g[2] == "quart") 15 else 30) } }
        s = s.sub("""$H(?: ?h ?| heures? )(\d{1,2})\b$NOT_UNIT""") { g ->
            val h = g[1].toInt()
            g[2].toInt().takeIf { h < 24 && it < 60 }?.let { hm(h, it) }
        }
        s = s.sub("""$H$HU\b""") { g -> g[1].takeIf { it.toInt() < 24 }?.let { "$it:00" } }
        s = s.replace(Regex("""\bmidi\b"""), "noon").replace(Regex("""\bminuit\b"""), "midnight")
        // A length after the clock times made it one (« dans 2 heures » is « dans 2:00 » here): minutes again.
        s = s.sub("""\b(dans|d ici|pendant|durant) (\d{1,2}):(\d{2})(?! ?[ap]m)(?=\s|$)""") { g ->
            val word = if (g[1] == "pendant" || g[1] == "durant") "for" else "in"
            "$word ${g[2].toInt() * 60 + g[3].toInt()} minutes" // l10n: not text (the parser's words)
        }
        // « pour une heure » is a length after a start (« à 9 h pour une heure »), else the time (« pour 15 h »).
        val started = Regex("""(?:^|\s)(?:\d{1,2}:\d{2}|noon|midnight)(?=\s|$)""")
        s = Regex("""\bpour (\d{1,2}):(\d{2})(?! ?[ap]m)(?=\s|$)""").replace(s) { m ->
            val (h, min) = m.groupValues[1].toInt() to m.groupValues[2].toInt()
            val length = started.containsMatchIn(s.substring(0, m.range.first))
            if (length) "for ${h * 60 + min} minutes" else "at ${m.groupValues[1]}:${m.groupValues[2]}" // l10n: not text (the parser's words)
        }
        s = s.replace(Regex("""\bpour (noon|midnight)\b"""), "at $1")
        for ((re, by) in AFTER) s = s.replace(re, by)
        return s.replace(Regex("""\s+"""), " ").trim()
    }
}
