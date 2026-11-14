// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.EventPlan
import app.heyrobot.core.Repeat
import java.time.ZoneOffset

/**
 * fix-calendar-repeat-c: the provider columns Calendars writes for a new event from its [EventPlan]. An all-day one is UTC
 * midnights in UTC (as calendar apps read it); a timed one is in its region's zone, so a series keeps its clock time across
 * a DST change. A series has its RRULE and a DURATION and never a DTEND, as the provider requires.
 */
data class EventValues(val dtStart: Long, val dtEnd: Long?, val zone: String, val allDay: Boolean, val rrule: String?, val duration: String?) {
    companion object {
        fun of(plan: EventPlan): EventValues {
            val start = if (plan.allDay) plan.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else plan.start.toInstant().toEpochMilli()
            val end = if (plan.allDay) plan.end.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else plan.end.toInstant().toEpochMilli()
            val r = plan.repeat
            val zone = if (plan.allDay) "UTC" else plan.start.zone.id // l10n: not text (a zone id)
            return EventValues(start, if (r == null) end else null, zone, plan.allDay, r?.rrule(), r?.let { Repeat.duration(plan.start, plan.end, plan.allDay) })
        }

        /** A series' DURATION when the whole of it is moved to [begin]..[end] (milliseconds): whole days, or seconds. */
        fun seriesDuration(begin: Long, end: Long, allDay: Boolean): String =
            if (allDay) "P${maxOf(1L, (end - begin) / 86_400_000L)}D" else "P${maxOf(60L, (end - begin) / 1000L)}S" // l10n: not text (RFC 5545)

        /**
         * fix-calendar-repeat-f: EXDATE [existing] with the occurrence at [instance] (its Instances.BEGIN) added once, in UTC.
         * fix-calendar-lows: an [allDay] series' occurrence is a date (VALUE=DATE, RFC 5545 §3.8.5.1), as its DTSTART is a
         * UTC midnight, so a calendar that syncs it keeps the day out. The provider keeps one line per EXDATE property, each
         * "TZID;" prefixed or not (AOSP RecurrenceSet, from memory): every line is kept as it is, a date never goes on a line
         * with a prefix (it would be read in that zone) or among date-times, and a date-time stamp ends in Z, right on any line.
         */
        fun exdate(existing: String?, instance: Long, allDay: Boolean = false): String {
            fun stamp(p: String) = java.time.format.DateTimeFormatter.ofPattern(p).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(instance))
            val time = stamp("yyyyMMdd'T'HHmmss'Z'") // l10n: not text (RFC 5545)
            val date = stamp("yyyyMMdd") // l10n: not text (RFC 5545)
            val lines = existing?.split('\n')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().toMutableList()
            fun values(line: String) = line.substringAfter(';').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            fun dates(line: String) = ';' !in line && values(line).all { it.length == 8 }
            if (lines.any { time in values(it) || (allDay && dates(it) && date in values(it)) }) return lines.joinToString("\n")
            val last = lines.lastOrNull()
            val add = if (allDay) date else time
            if (last == null) lines += add
            else if (if (allDay) dates(last) else !dates(last)) lines[lines.size - 1] = values(last).let { v -> (if (';' in last) last.substringBefore(';') + ";" else "") + (v + add).joinToString(",") }
            else lines += add
            return lines.joinToString("\n")
        }

        /**
         * fix-calendar-repeat-d: a new exception for the occurrence at [instance] (its Instances.BEGIN), through
         * Events.CONTENT_EXCEPTION_URI. Never a DTEND: the provider refuses one in an exception ("Exceptions can't overwrite
         * dtend", review-fix-calendar-repeat-c, from AOSP CalendarProvider2 as remembered, not checked here) and sets DTEND
         * from DTSTART + DURATION itself.
         */
        fun exception(instance: Long, title: String, place: String, begin: Long, end: Long, allDay: Boolean, zoneId: String): Map<ExceptionColumn, Any> =
            linkedMapOf(ExceptionColumn.ORIGINAL_INSTANCE_TIME to instance, ExceptionColumn.TITLE to title, ExceptionColumn.EVENT_LOCATION to place,
                ExceptionColumn.DTSTART to begin, ExceptionColumn.DURATION to seriesDuration(begin, end, allDay), ExceptionColumn.ALL_DAY to (if (allDay) 1 else 0),
                ExceptionColumn.EVENT_TIMEZONE to (if (allDay) "UTC" else zoneId)) // l10n: not text (a zone id)
    }

    /**
     * fix-calendar-repeat-f: the series' columns an EXDATE goes in with. The provider re-expands a series from the values
     * an update brings, not the stored row: an EXDATE alone changes nothing on screen, a DTSTART without the RRULE leaves
     * one occurrence (Pixel 8, 14 Nov 2026). So every time column is read and sent again with it.
     */
    enum class SeriesColumn { DTSTART, DURATION, RRULE, RDATE, EXRULE, EXDATE, ALL_DAY, EVENT_TIMEZONE }

    /** fix-calendar-repeat-d: the columns an occurrence's exception writes (app/sys/Calendars maps each to CalendarContract.Events). */
    enum class ExceptionColumn { ORIGINAL_INSTANCE_TIME, TITLE, EVENT_LOCATION, DTSTART, DURATION, ALL_DAY, EVENT_TIMEZONE }
}
