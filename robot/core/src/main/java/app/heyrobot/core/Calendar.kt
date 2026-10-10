package app.heyrobot.core

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Pure. The calendar side of add_event and read_calendar (docs/phase-2.md "Calendar"): which calendar a new event goes
// into, the times an add_event means, the text of the cards, and what read_calendar tells the model. The app process
// reads and writes CalendarContract; GrapheneOS ships no calendar app, so the owner's own (Etar, Fossify Calendar,
// DAVx5's calendars) is whatever the provider holds.

/** A calendar Robot may add events to (CalendarContract.Calendars with at least contributor access). */
data class CalendarInfo(val id: Long, val name: String, val account: String, val owner: String?, val primary: Boolean, val visible: Boolean = true)

object CalendarPick {
    /** Settings value: no calendar chosen, Robot picks ([choose]). */
    const val AUTO = -1L

    /**
     * The calendar new events go into: the one chosen in settings while it is still there, else the first visible
     * writable one, the primary calendar or one of the owner's own account first. Null: none, so the calendar app's
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
    const val PASSED = "that time has passed"

    /**
     * The start and end add_event means: the end that was said, else the tool's duration_minutes, else
     * [defaultMinutes] (Settings); a whole day ends at the next midnight. A start already past (a day before today for
     * a whole day) is refused rather than added silently.
     */
    fun times(a: Action.AddEvent, now: ZonedDateTime, defaultMinutes: Int): Read<WhenSpan> =
        when (val r = WhenParser.parse(a.whenText, now)) {
            is Read.Unreadable -> r
            is Read.Ok -> {
                val s = r.value
                val passed = if (s.allDay) s.start.toLocalDate().isBefore(now.toLocalDate()) else s.start.isBefore(now.truncatedTo(ChronoUnit.MINUTES))
                if (passed) Read.Unreadable(PASSED)
                else {
                    val end = s.end ?: if (s.allDay) s.start.toLocalDate().plusDays(1).atStartOfDay(s.start.zone)
                    else s.start.plusMinutes((a.minutes ?: defaultMinutes).toLong())
                    Read.Ok(s.copy(end = end))
                }
            }
        }
}

object EventText {
    private val DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

    fun day(d: LocalDate, currentYear: Int): String = d.format(DAY) + if (d.year != currentYear) " ${d.year}" else ""

    /** "Tue Oct 14, 15:00–16:00", "Tue Oct 14 (all day)", "Tue Oct 14, 23:00 – Wed Oct 15, 01:00". */
    fun span(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean, currentYear: Int): String {
        if (allDay) {
            val last = end.toLocalDate().minusDays(1).let { if (it.isBefore(start.toLocalDate())) start.toLocalDate() else it }
            return if (last == start.toLocalDate()) "${day(start.toLocalDate(), currentYear)} (all day)"
            else "${day(start.toLocalDate(), currentYear)} – ${day(last, currentYear)} (all day)"
        }
        return if (start.toLocalDate() == end.toLocalDate()) "${day(start.toLocalDate(), currentYear)}, ${start.format(TIME)}–${end.format(TIME)}"
        else "${day(start.toLocalDate(), currentYear)}, ${start.format(TIME)} – ${day(end.toLocalDate(), currentYear)}, ${end.format(TIME)}"
    }

    /** "Sun Oct 11", or "Mon Oct 12 – Sun Oct 18" for a week. */
    fun range(r: DayRange): String {
        val y = r.from.year
        return if (r.days <= 1) day(r.from.toLocalDate(), y) else "${day(r.from.toLocalDate(), y)} – ${day(r.to.toLocalDate().minusDays(1), y)}"
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
    fun whenText(e: CalendarEvent, range: DayRange): String {
        val zone = range.from.zone
        val y = range.from.year
        val oneDay = range.days <= 1
        if (e.allDay) {
            val first = utcDate(e.begin)
            val last = utcDate(e.end).minusDays(1).let { if (it.isBefore(first)) first else it }
            return when {
                first == last && oneDay -> "all day"
                first == last -> "${EventText.day(first, y)} all day"
                else -> "${EventText.day(first, y)} – ${EventText.day(last, y)} all day"
            }
        }
        val s = Instant.ofEpochMilli(e.begin).atZone(zone)
        val t = Instant.ofEpochMilli(e.end).atZone(zone)
        if (s.toLocalDate() == t.toLocalDate() || !t.isAfter(s)) {
            val times = if (!t.isAfter(s)) s.format(TIME) else "${s.format(TIME)}–${t.format(TIME)}"
            return if (oneDay && s.toLocalDate() == range.from.toLocalDate()) times else "${EventText.day(s.toLocalDate(), y)} $times"
        }
        return "${EventText.day(s.toLocalDate(), y)} ${s.format(TIME)} – ${EventText.day(t.toLocalDate(), y)} ${t.format(TIME)}"
    }

    /** read_calendar's answer for the model: up to [MAX_EVENTS] events with when, title (60 characters) and location (40). */
    fun results(events: List<CalendarEvent>, range: DayRange): List<Map<String, Any?>> = inRange(events, range).take(MAX_EVENTS).map { e ->
        buildMap {
            put("when", whenText(e, range))
            put("title", NoteSearch.cut(e.title.ifBlank { "(no title)" }.replace(Regex("\\s+"), " "), TITLE))
            e.location?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }?.let { put("location", NoteSearch.cut(it, LOCATION)) }
        }
    }

    /** The card's lines (and what text-call mode reads out): one event per line. */
    fun lines(results: List<Map<String, Any?>>): String =
        results.joinToString("\n") { r -> "${r["when"]} · ${r["title"]}" + (r["location"]?.let { " ($it)" } ?: "") }

    /** "3 events on Sun Oct 11.", "Showing 10 of 14 events from Mon Oct 12 – Sun Oct 18.", "Nothing on Sun Oct 11." */
    fun detail(total: Int, range: DayRange): String {
        val r = EventText.range(range)
        val on = if (range.days <= 1) "on $r" else "from $r"
        return when {
            total == 0 -> "Nothing $on."
            total == 1 -> "1 event $on."
            total > MAX_EVENTS -> "Showing $MAX_EVENTS of $total events $on."
            else -> "$total events $on."
        }
    }

    private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
}
