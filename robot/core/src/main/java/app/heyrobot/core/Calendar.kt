// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.InfoStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Pure. The calendar side of add_event and read_calendar (docs/phase-2.md "Calendar"): which calendar a new event goes
// into, the times an add_event means, the text of the cards, and what read_calendar tells the model. The app process
// reads and writes CalendarContract; GrapheneOS ships no calendar app, so your own (Etar, Fossify Calendar,
// DAVx5's calendars) is whatever the provider holds.

/** A calendar Robot may add events to (CalendarContract.Calendars with at least contributor access). */
data class CalendarInfo(val id: Long, val name: String, val account: String, val owner: String?, val primary: Boolean, val visible: Boolean = true)

object CalendarPick {
    /** Settings value: no calendar chosen, Robot picks ([choose]). */
    const val AUTO = -1L

    /**
     * The calendar new events go into: the one chosen in settings while it is still there, else the first visible
     * writable one, the primary calendar or one its own account owns
     * (OWNER_ACCOUNT) first. Null: none, so the calendar app's
     * editor opens instead (Intent.ACTION_INSERT).
     */
    fun choose(calendars: List<CalendarInfo>, chosen: Long): CalendarInfo? {
        calendars.firstOrNull { it.id == chosen && chosen != AUTO }?.let { return it }
        return calendars.filter { it.visible }
            .sortedBy { if (it.primary) 0 else if (it.owner != null && it.owner == it.account) 1 else 2 }
            .firstOrNull()
    }
}

/** One event (an instance of it) as CalendarContract.Instances has it. All-day events are midnight to midnight UTC. */
data class CalendarEvent(val begin: Long, val end: Long, val allDay: Boolean, val title: String, val location: String?)

object Events {
    /** add_event for a time already gone ("today at 8" said at 9, "Friday this week" on a Saturday). */
    val PASSED: String get() = InfoStrings.timePassed().en()

    /**
     * The start and end add_event means: the end that was said, else the tool's duration_minutes, else
     * [defaultMinutes] (Settings); a whole day ends at the next midnight. A start already past (a day before today for
     * a whole day) is refused rather than added silently. 1.1: the words are read in [lang] first, then the other
     * language (WhenParser.parse with a language).
     */
    fun times(a: Action.AddEvent, now: ZonedDateTime, defaultMinutes: Int, lang: Lang = Lang.EN): Read<WhenSpan> =
        when (val r = WhenParser.parse(a.whenText, now, lang)) {
            is Read.Unreadable -> r
            is Read.Ok -> {
                val s = r.value
                val passed = if (s.allDay) s.start.toLocalDate().isBefore(now.toLocalDate()) else s.start.isBefore(now.truncatedTo(ChronoUnit.MINUTES))
                if (passed) Read.Unreadable(InfoStrings.timePassed())
                else {
                    val end = s.end ?: if (s.allDay) s.start.toLocalDate().plusDays(1).atStartOfDay(s.start.zone)
                    else s.start.plusMinutes((a.minutes ?: defaultMinutes).toLong())
                    Read.Ok(s.copy(end = end))
                }
            }
        }
}

object EventText {
    private val WEEKDAYS = listOf(InfoStrings.mon, InfoStrings.tue, InfoStrings.wed, InfoStrings.thu, InfoStrings.fri, InfoStrings.sat, InfoStrings.sun)
    private val MONTHS = listOf(InfoStrings.jan, InfoStrings.feb, InfoStrings.mar, InfoStrings.apr, InfoStrings.may, InfoStrings.jun, InfoStrings.jul,
        InfoStrings.aug, InfoStrings.sep, InfoStrings.oct, InfoStrings.nov, InfoStrings.dec)
    private val HOURS = listOf(InfoStrings.hour0, InfoStrings.hour1, InfoStrings.hour2, InfoStrings.hour3, InfoStrings.hour4, InfoStrings.hour5,
        InfoStrings.hour6, InfoStrings.hour7, InfoStrings.hour8, InfoStrings.hour9)

    fun day(d: LocalDate, currentYear: Int): String = dayText(d, currentYear).en()

    /** "Tue Oct 14", "Tue Oct 14 2027" (another year) / « mardi 14 octobre », « vendredi 1er octobre 2027 ». */
    fun dayText(d: LocalDate, currentYear: Int): Phrase {
        val weekday = WEEKDAYS[d.dayOfWeek.value - DayOfWeek.MONDAY.value]()
        val month = monthText(d.monthValue)
        val day = dayOfMonth(d)
        return if (d.year != currentYear) InfoStrings.dayShortYear("weekday" to weekday, "month" to month, "day" to day, "year" to d.year.toString())
        else InfoStrings.dayShort("weekday" to weekday, "month" to month, "day" to day)
    }

    /** A month's name: "Oct", « octobre ». */
    fun monthText(month: Int): Phrase = MONTHS[month - 1]()

    /** The day of the month: "1", « 1er ». */
    fun dayOfMonth(d: LocalDate): Phrase = if (d.dayOfMonth == 1) InfoStrings.firstOfMonth() else Phrase.plain(d.dayOfMonth.toString())

    /** A time of day: "07:30", "15:00" (a 24-hour clock, as 1.0 wrote it) / « 7 h 30 », « 15 h ». */
    fun clock(hour: Int, minute: Int): Phrase {
        val h: Any = if (hour in 0..9) HOURS[hour]() else hour.toString()
        return if (minute == 0) InfoStrings.clockHour("h" to h) else InfoStrings.clock("h" to h, "m" to minute.toString().padStart(2, '0'))
    }

    private fun clock(t: ZonedDateTime): Phrase = clock(t.hour, t.minute)

    /** "Tue Oct 14, 15:00–16:00", "Tue Oct 14 (all day)", "Tue Oct 14, 23:00 – Wed Oct 15, 01:00". */
    fun span(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean, currentYear: Int): String = spanText(start, end, allDay, currentYear).en()

    /** [span] in words without a language yet. */
    fun spanText(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean, currentYear: Int): Phrase {
        val first = dayText(start.toLocalDate(), currentYear)
        if (allDay) {
            val last = end.toLocalDate().minusDays(1).let { if (it.isBefore(start.toLocalDate())) start.toLocalDate() else it }
            return if (last == start.toLocalDate()) InfoStrings.dayAllDayParen("day" to first)
            else InfoStrings.daysAllDayParen("from" to first, "to" to dayText(last, currentYear))
        }
        return if (start.toLocalDate() == end.toLocalDate()) InfoStrings.dayTimes("day" to first, "from" to clock(start), "to" to clock(end))
        else InfoStrings.daysTimes("day" to first, "from" to clock(start), "day2" to dayText(end.toLocalDate(), currentYear), "to" to clock(end))
    }

    /**
     * 1.1: Robot's own line after it added [title] as [plan] says, the card's words: the calendar, the title, when, and the
     * alert when one was asked for ([alert]: it went in; otherwise the line says to add it in the calendar app).
     */
    fun added(title: String, plan: EventPlan, alert: Boolean): Phrase {
        val span = spanText(plan.start, plan.end, plan.allDay, plan.currentYear)
        val calendar = plan.calendarName.orEmpty()
        val at = plan.alertAt
        return when {
            at == null -> ToolStrings.eventAdded("calendar" to calendar, "title" to title, "when" to span)
            alert -> ToolStrings.eventAddedAlert("calendar" to calendar, "title" to title, "when" to span, "alert" to clock(at.hour, at.minute))
            else -> ToolStrings.eventAddedNoAlert("calendar" to calendar, "title" to title, "when" to span)
        }
    }

    /** "Sun Oct 11", or "Mon Oct 12 – Sun Oct 18" for a week. */
    fun range(r: DayRange): String = rangeText(r).en()

    /** [range] in words without a language yet. */
    fun rangeText(r: DayRange): Phrase {
        val y = r.from.year
        return if (r.days <= 1) dayText(r.from.toLocalDate(), y)
        else InfoStrings.dayRange("from" to dayText(r.from.toLocalDate(), y), "to" to dayText(r.to.toLocalDate().minusDays(1), y))
    }
}

object CalendarFormat {
    const val MAX_EVENTS = 10
    const val TITLE = 60
    const val LOCATION = 40

    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    /** The events of [range], all-day ones first, then by start; an all-day event's dates are read in UTC. */
    fun inRange(events: List<CalendarEvent>, range: DayRange): List<CalendarEvent> = events.filter { e ->
        if (e.allDay) {
            val first = utcDate(e.begin)
            val last = utcDate(e.end).minusDays(1).let { if (it.isBefore(first)) first else it }
            !last.isBefore(range.from.toLocalDate()) && first.isBefore(range.to.toLocalDate())
        } else {
            e.begin < range.to.toInstant().toEpochMilli() && e.end > range.from.toInstant().toEpochMilli() ||
                e.begin == e.end && e.begin >= range.from.toInstant().toEpochMilli() && e.begin < range.to.toInstant().toEpochMilli()
        }
    }.sortedWith(compareBy<CalendarEvent> { if (it.allDay) 0 else 1 }.thenBy { it.begin })

    /** When an event is, for one day ("15:00–16:00", "all day") or with its day in a longer range ("Tue Oct 14 15:00–16:00"). */
    fun whenText(e: CalendarEvent, range: DayRange): String = whenPhrase(e, range).en()

    /** [whenText] in words without a language yet. */
    fun whenPhrase(e: CalendarEvent, range: DayRange): Phrase {
        val zone = range.from.zone
        val y = range.from.year
        val oneDay = range.days <= 1
        if (e.allDay) {
            val first = utcDate(e.begin)
            val last = utcDate(e.end).minusDays(1).let { if (it.isBefore(first)) first else it }
            return when {
                first == last && oneDay -> InfoStrings.allDay()
                first == last -> InfoStrings.dayAllDay("day" to EventText.dayText(first, y))
                else -> InfoStrings.daysAllDay("from" to EventText.dayText(first, y), "to" to EventText.dayText(last, y))
            }
        }
        val s = Instant.ofEpochMilli(e.begin).atZone(zone)
        val t = Instant.ofEpochMilli(e.end).atZone(zone)
        if (s.toLocalDate() == t.toLocalDate() || !t.isAfter(s)) {
            val times = if (!t.isAfter(s)) EventText.clock(s.hour, s.minute)
            else InfoStrings.times("from" to EventText.clock(s.hour, s.minute), "to" to EventText.clock(t.hour, t.minute))
            return if (oneDay && s.toLocalDate() == range.from.toLocalDate()) times else Phrase.join(EventText.dayText(s.toLocalDate(), y), times)
        }
        return Phrase.Join(listOf(EventText.dayText(s.toLocalDate(), y), EventText.clock(s.hour, s.minute), Phrase.plain("–"),
            EventText.dayText(t.toLocalDate(), y), EventText.clock(t.hour, t.minute)))
    }

    /** read_calendar's answer for the model: up to [MAX_EVENTS] events with when, title (60 characters) and location (40). */
    fun results(events: List<CalendarEvent>, range: DayRange): List<Map<String, Any?>> = inRange(events, range).take(MAX_EVENTS).map { e ->
        buildMap {
            put("when", whenText(e, range))
            put("title", title(e).en())
            e.location?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { put("location", NoteSearch.cut(it, LOCATION)) }
        }
    }

    /** An event's title as shown (60 characters), or "(no title)". */
    private fun title(e: CalendarEvent): Phrase = e.title.takeIf { it.isNotBlank() }?.let { Phrase.plain(NoteSearch.cut(it.replace(Regex("\\s+"), " "), TITLE)) }
        ?: InfoStrings.noTitle()

    /** The card's lines (and what text-call mode reads out): one event per line. */
    fun lines(results: List<Map<String, Any?>>): String =
        results.joinToString("\n") { r -> "${r["when"]} · ${r["title"]}" + (r["location"]?.let { " ($it)" } ?: "") }

    /**
     * [lines] of [events] (as [results] picks them) in words without a language yet: the card's body, in the reply language;
     * [spoken], as text-call mode reads them out (sentences, without the dots between when and what).
     */
    fun linesText(events: List<CalendarEvent>, range: DayRange, spoken: Boolean = false): Phrase = Phrase.Join(inRange(events, range).take(MAX_EVENTS).map { e ->
        val place = e.location?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { NoteSearch.cut(it, LOCATION) }
        val title = if (spoken) Phrase.plain(title(e).en().replace(" · ", " ")).takeIf { e.title.isNotBlank() } ?: title(e) else title(e)
        when {
            place == null && spoken -> Phrase.join(whenPhrase(e, range), title)
            place == null -> InfoStrings.eventLine("when" to whenPhrase(e, range), "title" to title)
            spoken -> InfoStrings.eventSpokenPlace("when" to whenPhrase(e, range), "title" to title, "place" to place.replace(" · ", " "))
            else -> InfoStrings.eventLinePlace("when" to whenPhrase(e, range), "title" to title, "place" to place)
        }
    }, if (spoken) ". " else "\n")

    /** "3 events on Sun Oct 11.", "Showing 10 of 14 events from Mon Oct 12 – Sun Oct 18.", "Nothing on Sun Oct 11." */
    fun detail(total: Int, range: DayRange): String = detailText(total, range).en()

    /** [detail] in words without a language yet. */
    fun detailText(total: Int, range: DayRange): Phrase {
        val y = range.from.year
        if (range.days <= 1) {
            val r = EventText.dayText(range.from.toLocalDate(), y)
            return when {
                total == 0 -> InfoStrings.nothingOn("when" to r)
                total > MAX_EVENTS -> InfoStrings.showingOn("shown" to MAX_EVENTS.toString(), "total" to total.toString(), "when" to r)
                else -> InfoStrings.eventsOn(total, "when" to r)
            }
        }
        // A week: "from Mon Oct 12 – Sun Oct 18", « du lundi 12 octobre au dimanche 18 octobre ».
        val from = EventText.dayText(range.from.toLocalDate(), y)
        val to = EventText.dayText(range.to.toLocalDate().minusDays(1), y)
        return when {
            total == 0 -> InfoStrings.nothingFrom("from" to from, "to" to to)
            total > MAX_EVENTS -> InfoStrings.showingFrom("shown" to MAX_EVENTS.toString(), "total" to total.toString(), "from" to from, "to" to to)
            else -> InfoStrings.eventsFrom(total, "from" to from, "to" to to)
        }
    }

    private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
}

/**
 * 1.1, dated reminders (docs/pm/handoff-dated-reminders.md point 4): the alert of a reminder's event. Events another app
 * inserts get none of the calendar app's defaults, so Robot writes the alert itself, as one CalendarContract.Reminders
 * row. An all-day reminder rings at [ALL_DAY_AT] on its day; one at a time rings at that time.
 */
object EventAlert {
    /** When an all-day reminder's alert rings on its day. */
    val ALL_DAY_AT: LocalTime = LocalTime.of(9, 0)

    /** CalendarContract.Reminders.METHOD_ALERT. */
    const val METHOD_ALERT = 1

    /** A Reminders row: [minutes] before the event's start (negative: after it), and its [method]. */
    data class Row(val minutes: Int, val method: Int = METHOD_ALERT)

    /** When the alert of a reminder on [span] rings: 9:00 on an all-day one's day, else its start; null when that has passed at [now]. */
    fun at(span: WhenSpan, now: ZonedDateTime): ZonedDateTime? {
        val at = if (span.allDay) span.start.toLocalDate().atTime(ALL_DAY_AT).atZone(span.start.zone) else span.start
        return at.takeIf { it.isAfter(now) }
    }

    /**
     * [plan]'s Reminders row, or null without an alert. A timed event's is minutes before its start (0: at the start). An
     * all-day event's start is its day's midnight, which the provider reads in the phone's own zone for its alarms, so
     * 9:00 that day is 540 minutes after it: -540.
     */
    fun row(plan: EventPlan): Row? {
        val at = plan.alertAt ?: return null
        return if (plan.allDay) Row(-(at.hour * 60 + at.minute))
        else Row(Duration.between(at, plan.start).toMinutes().toInt())
    }
}
