// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

class CalendarTest {
    private val zone = ZoneId.of("America/Toronto")
    private val now = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, zone)
    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, zone)
    private fun ms(t: ZonedDateTime) = t.toInstant().toEpochMilli()
    /** An all-day event as CalendarContract stores it: midnight to midnight UTC. */
    private fun allDay(month: Int, day: Int, days: Long = 1, title: String = "Holiday") = CalendarEvent(
        LocalDate.of(2026, month, day).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        LocalDate.of(2026, month, day).plusDays(days).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), true, title, null)
    private fun timed(start: ZonedDateTime, end: ZonedDateTime, title: String = "Dentist", location: String? = null) =
        CalendarEvent(ms(start), ms(end), false, title, location)

    private val today = DayRange(at(10, 10), at(10, 11))
    private val week = DayRange(at(10, 12), at(10, 19))

    @Test fun theCalendarForNewEvents() {
        val cals = listOf(
            CalendarInfo(1, "Holidays", "local", null, primary = false),
            CalendarInfo(2, "Work", "me@work.example", "me@work.example", primary = false),
            CalendarInfo(3, "Personal", "me@example.org", "me@example.org", primary = true),
            CalendarInfo(4, "Hidden", "x", null, primary = true, visible = false),
        )
        assertEquals(3L, CalendarPick.choose(cals, CalendarPick.AUTO)?.id)
        assertEquals(2L, CalendarPick.choose(cals.filter { it.id != 3L }, CalendarPick.AUTO)?.id)
        assertEquals(1L, CalendarPick.choose(cals, 1)?.id)
        // The chosen one was removed: back to Robot's pick. None at all: the calendar app's editor.
        assertEquals(3L, CalendarPick.choose(cals, 99)?.id)
        assertNull(CalendarPick.choose(emptyList(), CalendarPick.AUTO))
        assertNull(CalendarPick.choose(cals.filter { !it.visible }, CalendarPick.AUTO))
    }

    @Test fun eventTimesUseTheEndSaidThenTheLengthThenTheDefault() {
        fun times(w: String, minutes: Int? = null) = (Events.times(Action.AddEvent("Dentist", w, minutes), now, 60) as Read.Ok).value
        assertEquals(WhenSpan(at(10, 13, 15), at(10, 13, 16), false), times("Tuesday at 3"))
        assertEquals(WhenSpan(at(10, 13, 15), at(10, 13, 15, 30), false), times("Tuesday at 3", 30))
        assertEquals(WhenSpan(at(10, 16, 9), at(10, 16, 10, 30), false), times("Friday 9 to 10:30", 30))
        assertEquals(WhenSpan(at(10, 16), at(10, 17), true), times("Friday", 30))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), Events.times(Action.AddEvent("Dentist", "someday"), now, 60))
        assertEquals(Read.Unreadable(WhenParser.SAY_MONTH), Events.times(Action.AddEvent("Dentist", "10/14"), now, 60))
    }

    @Test fun cardTextShowsWeekdayDateTimesAndOnlyAnotherYear() {
        assertEquals("Tue Oct 13, 15:00–16:00", EventText.span(at(10, 13, 15), at(10, 13, 16), false, 2026))
        assertEquals("Tue Oct 13 (all day)", EventText.span(at(10, 13), at(10, 14), true, 2026))
        assertEquals("Tue Oct 13 – Thu Oct 15 (all day)", EventText.span(at(10, 13), at(10, 16), true, 2026))
        assertEquals("Tue Oct 13, 23:00 – Wed Oct 14, 01:00", EventText.span(at(10, 13, 23), at(10, 14, 1), false, 2026))
        assertEquals("Tue Oct 13 2026, 15:00–16:00", EventText.span(at(10, 13, 15), at(10, 13, 16), false, 2025))
        assertEquals("Sat Oct 10", EventText.range(today))
        assertEquals("Mon Oct 12 – Sun Oct 18", EventText.range(week))
    }

    @Test fun allDayEventsAreReadInUtcAndTimedOnesByOverlap() {
        val events = listOf(
            allDay(10, 11, title = "Tomorrow's holiday"),     // 20:00 tonight in Toronto, but it is tomorrow's
            allDay(10, 10, title = "Today's holiday"),
            allDay(10, 9, days = 3, title = "Long weekend"),
            timed(at(10, 10, 15), at(10, 10, 16)),
            timed(at(10, 9, 23), at(10, 10, 1), "Overnight"),
            timed(at(10, 11, 0), at(10, 11, 1), "After midnight"),
            timed(at(10, 10, 12), at(10, 10, 12), "Reminder"),
        )
        // All-day events first, then by start; tomorrow's holiday and the event after midnight are not today's.
        assertEquals(listOf("Long weekend", "Today's holiday", "Overnight", "Reminder", "Dentist"), CalendarFormat.inRange(events, today).map { it.title })
        val r = CalendarFormat.inRange(events, today)
        assertTrue(r.take(2).all { it.allDay })
        assertFalse(r.any { it.title == "Tomorrow's holiday" || it.title == "After midnight" })
        assertEquals(listOf("Overnight", "Reminder", "Dentist"), r.drop(2).map { it.title })
    }

    @Test fun resultsForTheModelAreShortAndCapped() {
        val one = CalendarFormat.results(listOf(timed(at(10, 10, 15), at(10, 10, 16), "Dentist", "123 Main St")), today)
        assertEquals(listOf(mapOf("when" to "15:00–16:00", "title" to "Dentist", "location" to "123 Main St")), one)
        val long = CalendarFormat.results(listOf(timed(at(10, 10, 15), at(10, 10, 16), "T".repeat(100), "L".repeat(100) + "\n")), today).single()
        assertEquals(CalendarFormat.TITLE, (long["title"] as String).length)
        assertEquals(CalendarFormat.LOCATION, (long["location"] as String).length)
        assertTrue((long["title"] as String).endsWith("…"))
        assertEquals("(no title)", CalendarFormat.results(listOf(timed(at(10, 10, 15), at(10, 10, 16), " ", " ")), today).single()["title"])
        assertFalse(CalendarFormat.results(listOf(timed(at(10, 10, 15), at(10, 10, 16), "x", "  ")), today).single().containsKey("location"))
        // At most ten.
        val many = (0 until 14).map { timed(at(10, 10, 8).plusMinutes(it * 30L), at(10, 10, 8).plusMinutes(it * 30L + 20), "E$it") }
        assertEquals(CalendarFormat.MAX_EVENTS, CalendarFormat.results(many, today).size)
        assertEquals("Showing 10 of 14 events on Sat Oct 10.", CalendarFormat.detail(14, today))
        assertEquals("Nothing on Sat Oct 10.", CalendarFormat.detail(0, today))
        assertEquals("1 event on Sat Oct 10.", CalendarFormat.detail(1, today))
        assertEquals("3 events from Mon Oct 12 – Sun Oct 18.", CalendarFormat.detail(3, week))
    }

    @Test fun aWeekShowsEachEventsDay() {
        val r = CalendarFormat.results(listOf(timed(at(10, 14, 15), at(10, 14, 16)), allDay(10, 16, title = "Trip"), allDay(10, 17, days = 2, title = "Away"),
            timed(at(10, 13, 23), at(10, 14, 1), "Late")), week)
        assertEquals(listOf("Fri Oct 16 all day", "Sat Oct 17 – Sun Oct 18 all day", "Tue Oct 13 23:00 – Wed Oct 14 01:00", "Wed Oct 14 15:00–16:00"),
            r.map { it["when"] })
        assertEquals("Fri Oct 16 all day · Trip\nSat Oct 17 – Sun Oct 18 all day · Away\nTue Oct 13 23:00 – Wed Oct 14 01:00 · Late\nWed Oct 14 15:00–16:00 · Dentist",
            CalendarFormat.lines(r))
        assertEquals("15:00–16:00 · Dentist (123 Main St)", CalendarFormat.lines(CalendarFormat.results(listOf(timed(at(10, 10, 15), at(10, 10, 16), "Dentist", "123 Main St")), today)))
        assertEquals("all day", CalendarFormat.results(listOf(allDay(10, 10)), today).single()["when"])
        assertEquals("Fri Oct 9 23:00 – Sat Oct 10 01:00", CalendarFormat.results(listOf(timed(at(10, 9, 23), at(10, 10, 1))), today).single()["when"])
    }
}
