package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class WhenParserTest {
    private val zone = ZoneId.of("America/Toronto")
    /** Saturday 10 October 2026, 09:05. */
    private val now = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, zone)
    /** Monday 12 October 2026, 10:00. */
    private val monday = ZonedDateTime.of(2026, 10, 12, 10, 0, 0, 0, zone)

    private fun at(month: Int, day: Int, h: Int = 0, m: Int = 0, year: Int = 2026) = ZonedDateTime.of(year, month, day, h, m, 0, 0, zone)

    private fun ok(text: String, n: ZonedDateTime = now): WhenSpan {
        val r = WhenParser.parse(text, n)
        assertTrue("“$text” → $r", r is Read.Ok)
        return (r as Read.Ok).value
    }

    private fun timed(text: String, start: ZonedDateTime, end: ZonedDateTime? = null, n: ZonedDateTime = now) {
        val s = ok(text, n)
        assertEquals("“$text” start", start, s.start)
        assertEquals("“$text” end", end, s.end)
        assertEquals("“$text” all day", false, s.allDay)
    }

    private fun day(text: String, month: Int, d: Int, year: Int = 2026, n: ZonedDateTime = now) {
        val s = ok(text, n)
        assertEquals("“$text”", WhenSpan(at(month, d, year = year), at(month, d, year = year).plusDays(1), true), s)
    }

    private fun unreadable(text: String, detail: String = WhenParser.SAY_DATE, n: ZonedDateTime = now) {
        assertEquals("“$text”", Read.Unreadable(detail), WhenParser.parse(text, n))
    }

    @Test fun todayTomorrowAndTheParts() {
        timed("tomorrow 3pm", at(10, 11, 15))
        timed("tomorrow at 3:30 pm", at(10, 11, 15, 30))
        timed("today at 5", at(10, 10, 17))
        timed("this afternoon at 3", at(10, 10, 15))
        timed("this morning at 11", at(10, 10, 11))
        timed("tonight at 8", at(10, 10, 20))
        timed("tonight", at(10, 10, 20))
        timed("this evening", at(10, 10, 18))
        timed("tomorrow morning", at(10, 11, 9))
        timed("day after tomorrow at 10am", at(10, 12, 10))
        day("today", 10, 10)
        day("tomorrow", 10, 11)
    }

    @Test fun weekdaysAreTheNextOneAndNextMeansTheFollowingWeek() {
        timed("Tuesday at 3", at(10, 13, 15))
        timed("Friday 9 to 10:30", at(10, 16, 9), at(10, 16, 10, 30))
        timed("Monday morning", at(10, 12, 9))
        day("Friday", 10, 16)
        // Today is Saturday: the next Saturday is a week away.
        day("Saturday", 10, 17)
        // On a Saturday "next Friday" (the Friday of the following week) is the coming Friday ...
        day("next Friday", 10, 16)
        // ... on a Monday it is the one after.
        day("Friday", 10, 16, n = monday)
        day("next Friday", 10, 23, n = monday)
        day("Friday next week", 10, 23, n = monday)
        day("this Monday", 10, 12, n = monday)
        day("on Wednesday", 10, 14, n = monday)
    }

    @Test fun inSoManyMinutesHoursOrDays() {
        timed("in 20 minutes", at(10, 10, 9, 25))
        timed("in 2 hours", at(10, 10, 11, 5))
        timed("in an hour", at(10, 10, 10, 5))
        timed("in half an hour", at(10, 10, 9, 35))
        timed("in 90 mins", at(10, 10, 10, 35))
        day("in 3 days", 10, 13)
        day("in 2 weeks", 10, 24)
    }

    @Test fun monthNamesWithADayAndAnOptionalYear() {
        day("October 14", 10, 14)
        day("14 Oct", 10, 14)
        day("Oct. 14th", 10, 14)
        day("the 14th of October", 10, 14)
        day("October 14, 2027", 10, 14, 2027)
        timed("October 14 at 9", at(10, 14, 9))
        timed("Oct 14 2pm", at(10, 14, 14))
        // Already past this year: next year's, for a month and a day only.
        day("March 3", 3, 3, 2027)
        day("March 3 2026", 3, 3, 2026)
        // Today is not past.
        day("October 10", 10, 10)
        day("the 14th", 10, 14)
        day("the 5th", 11, 5)
        unreadable("February 30")
    }

    @Test fun isoDatesAndTimes() {
        day("2026-10-14", 10, 14)
        timed("2026-10-14T15:00", at(10, 14, 15))
        timed("2026-10-14 15:30", at(10, 14, 15, 30))
        // With an offset: the same instant in the phone's zone (EDT is UTC-4).
        timed("2026-10-14T19:00Z", at(10, 14, 15))
        timed("2026-10-14T15:00-04:00", at(10, 14, 15))
        timed("2026-10-14T21:00+02:00", at(10, 14, 15))
        // An ISO date in the past stays (the year was said).
        day("2026-01-02", 1, 2)
        unreadable("2026-13-01")
    }

    @Test fun clockTimesInEveryForm() {
        timed("3pm", at(10, 10, 15))
        timed("3 p.m.", at(10, 10, 15))
        timed("3:30 pm", at(10, 10, 15, 30))
        timed("15:00", at(10, 10, 15))
        timed("15h", at(10, 10, 15))
        timed("15h30", at(10, 10, 15, 30))
        timed("3 o'clock", at(10, 10, 15))
        timed("noon", at(10, 10, 12))
        timed("half past 3", at(10, 10, 15, 30))
        timed("at three thirty", at(10, 10, 15, 30))
        // A time with no date is the next time it comes: 8:00 and 9:00 have passed today, midnight is tonight's.
        timed("8am", at(10, 11, 8))
        timed("9", at(10, 11, 9))
        timed("quarter to 9", at(10, 11, 8, 45))
        timed("midnight", at(10, 11, 0))
        timed("tomorrow at noon", at(10, 11, 12))
        timed("09:30", at(10, 10, 9, 30))
    }

    @Test fun rangesAndLengths() {
        // 9:00 has passed (it is 9:05): the next 9 to 10:30 is tomorrow's.
        timed("9 to 10:30", at(10, 11, 9), at(10, 11, 10, 30))
        timed("10 to 11:30", at(10, 10, 10), at(10, 10, 11, 30))
        timed("from 2 to 4pm", at(10, 10, 14), at(10, 10, 16))
        timed("2-4pm", at(10, 10, 14), at(10, 10, 16))
        timed("Friday 9-10:30", at(10, 16, 9), at(10, 16, 10, 30))
        timed("between 2 and 3", at(10, 10, 14), at(10, 10, 15))
        timed("10 to 2", at(10, 10, 10), at(10, 10, 14))
        timed("11 to 1pm", at(10, 10, 11), at(10, 10, 13))
        timed("tomorrow 11pm to 1am", at(10, 11, 23), at(10, 12, 1))
        timed("15:00-16:30", at(10, 10, 15), at(10, 10, 16, 30))
        timed("3pm for 2 hours", at(10, 10, 15), at(10, 10, 17))
        timed("tomorrow 3pm for 30 minutes", at(10, 11, 15), at(10, 11, 15, 30))
        timed("Tuesday at 2 for an hour and a half", at(10, 13, 14), at(10, 13, 15, 30))
    }

    @Test fun allDay() {
        day("all day tomorrow", 10, 11)
        day("tomorrow all day", 10, 11)
        day("all day", 10, 10)
        day("Friday all-day", 10, 16)
    }

    @Test fun numericDatesAreNeverGuessed() {
        unreadable("10/11", WhenParser.SAY_MONTH)
        unreadable("10/11 at 3pm", WhenParser.SAY_MONTH)
        unreadable("10/11/2026", WhenParser.SAY_MONTH)
        unreadable("10.11.", WhenParser.SAY_MONTH)
        assertTrue(WhenParser.SAY_MONTH.startsWith("say the month's name"))
    }

    @Test fun whatCannotBeReadSaysHow() {
        assertEquals("say the date like \"tomorrow 3pm\" or \"October 14 at 9\"", WhenParser.SAY_DATE)
        unreadable("")
        unreadable("whenever")
        unreadable("soon")
        unreadable("next week")
        // Two different days: not a guess.
        unreadable("tomorrow Friday")
        unreadable("in 2 hours on Friday")
        unreadable("at 25:00")
    }

    @Test fun shortFormsAndTheLexiconSeam() {
        timed("tmrw 3pm", at(10, 11, 15))
        timed("Tues at 3", at(10, 13, 15))
        day("Sept 14", 9, 14, 2027)
        // Another language maps its words onto the parser's own (French comes in phase 4).
        val fr = Lexicon(mapOf("demain" to "tomorrow", "vendredi" to "friday"))
        assertEquals(at(10, 11, 15), (WhenParser.parse("demain 15h", now, fr) as Read.Ok).value.start)
        assertEquals(at(10, 16), (WhenParser.parse("vendredi", now, fr) as Read.Ok).value.start)
        unreadable("demain")
    }

    @Test fun daylightSavingTimeIsTheZones() {
        // Clocks go back on Sunday 1 November 2026 in Toronto: 15:00 that day is EST.
        val s = ok("tomorrow 3pm", ZonedDateTime.of(2026, 10, 31, 10, 0, 0, 0, zone))
        assertEquals("-05:00", s.start.offset.id)
        assertEquals(15, s.start.hour)
        assertNull(s.end)
    }

    // ------------------------------------------------------------------ read_calendar's days and weeks

    private fun range(text: String, from: ZonedDateTime, to: ZonedDateTime, n: ZonedDateTime = now) {
        assertEquals("“$text”", Read.Ok(DayRange(from, to)), WhenParser.range(text, n))
    }

    @Test fun rangesForTheCalendar() {
        range("today", at(10, 10), at(10, 11))
        range("", at(10, 10), at(10, 11))
        range("tomorrow", at(10, 11), at(10, 12))
        range("tomorrow afternoon", at(10, 11), at(10, 12))
        range("Friday", at(10, 16), at(10, 17))
        range("October 14", at(10, 14), at(10, 15))
        range("2026-10-20", at(10, 20), at(10, 21))
        // Weeks run Monday to Sunday; "this week" is what's left of it.
        range("next week", at(10, 12), at(10, 19))
        range("this week", at(10, 10), at(10, 12))
        range("this week", at(10, 12), at(10, 19), monday)
        range("next week", at(10, 19), at(10, 26), monday)
        range("the weekend", at(10, 10), at(10, 12))
        range("this weekend", at(10, 17), at(10, 19), monday)
        range("next weekend", at(10, 17), at(10, 19))
        assertEquals(7L, (WhenParser.range("next week", now) as Read.Ok).value.days)
        assertEquals(Read.Unreadable(WhenParser.SAY_MONTH), WhenParser.range("10/11", now))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.range("my birthday", now))
    }
}
