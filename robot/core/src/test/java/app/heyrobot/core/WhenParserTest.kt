// SPDX-License-Identifier: GPL-3.0-only
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
        // Without seconds a "-hh:mm" is the end of a range (docs: RFC 3339 puts an offset after the seconds).
        timed("2026-10-14T15:00-04:00", at(10, 14, 15), at(10, 15, 4))
        timed("2026-10-14T15:00:00-04:00", at(10, 14, 15))
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

    // ------------------------------------------------------------------ 0.2.0 review A (B1, M1, M2, M3, minor 10)

    @Test fun relativeFormsAreReadOrRefusedNeverGuessed() {
        // B1: these read a stray number as a clock time in 0.2.0.
        day("two weeks from now", 10, 24)
        day("3 days from now", 10, 13)
        day("in 1 month", 11, 10)
        day("in 6 months", 4, 10, 2027)
        timed("1 hour from now", at(10, 10, 10, 5))
        timed("an hour from now", at(10, 10, 10, 5))
        timed("20 minutes from now", at(10, 10, 9, 25))
        timed("half an hour from now", at(10, 10, 9, 35))
        timed("in one and a half hours", at(10, 10, 10, 35))
        timed("in 2 and a half hours", at(10, 10, 11, 35))
        timed("in an hour and a half", at(10, 10, 10, 35))
        day("a week from Friday", 10, 23)
        day("two weeks from today", 10, 24)
        day("a week from tomorrow", 10, 18)
        day("Friday after next", 10, 23)
        timed("Friday after next at 3", at(10, 23, 15))
        day("Friday the week after next", 10, 23)
        // Allowed to stay unreadable, as long as they never give a wrong date.
        unreadable("first Monday of November")
        unreadable("the 14th of next month")
        unreadable("dentist in 2 months")
        unreadable("in 1.5 days")
        unreadable("3 days ago")
        unreadable("Tuesday or Wednesday")
        unreadable("week of the 19th")
    }

    @Test fun leftoverWordsOrNumbersMakeItUnreadable() {
        unreadable("Tuesday at 3 with Sam")
        unreadable("Tuesday 3 4")
        unreadable("at 3 and 4")
        unreadable("half 3")
        unreadable("tomorrow at 3 before lunch")
        // Filler is fine.
        timed("on Tuesday at 3", at(10, 13, 15))
        day("this coming Friday", 10, 16)
        timed("Tuesday at 3pm sharp", at(10, 13, 15))
    }

    @Test fun nightHoursAreThatNightsEarlyMorning() {
        // M1: tonight at 12 / 12 midnight became noon, tonight at 1 became 13:00, tonight at 2am was in the past.
        timed("tonight at 12", at(10, 11, 0))
        timed("at 12 midnight", at(10, 11, 0))
        timed("12 midnight", at(10, 11, 0))
        timed("tonight at midnight", at(10, 11, 0))
        timed("tomorrow at midnight", at(10, 12, 0))
        timed("tonight at 1", at(10, 11, 1))
        timed("tonight at 2am", at(10, 11, 2))
        timed("tonight at 4", at(10, 11, 4))
        timed("tonight at 5", at(10, 10, 17))
        timed("tonight at 11", at(10, 10, 23))
        timed("tomorrow night at 1", at(10, 12, 1))
        timed("Friday night at 2", at(10, 17, 2))
        timed("this evening at 12", at(10, 11, 0))
        timed("this evening at 8", at(10, 10, 20))
        timed("12 noon", at(10, 10, 12))
        // Said after midnight, "tonight" is the night under way.
        timed("tonight at 1", at(10, 11, 1), n = ZonedDateTime.of(2026, 10, 11, 0, 30, 0, 0, zone))
    }

    @Test fun aRangeCrossingMidnightEndsThatNight() {
        // M2: these ended the next afternoon.
        timed("10pm to 2", at(10, 10, 22), at(10, 11, 2))
        timed("11pm to 12", at(10, 10, 23), at(10, 11, 0))
        timed("9pm to 1", at(10, 10, 21), at(10, 11, 1))
        timed("8pm-2", at(10, 10, 20), at(10, 11, 2))
        timed("20:00 to 2", at(10, 10, 20), at(10, 11, 2))
        timed("22h to 1", at(10, 10, 22), at(10, 11, 1))
        timed("tonight at 11 to 1", at(10, 10, 23), at(10, 11, 1))
        timed("11pm to midnight", at(10, 10, 23), at(10, 11, 0))
        timed("22:00 to 03:00", at(10, 10, 22), at(10, 11, 3))
    }

    @Test fun anIsoTimeWithADashIsARangeNotAnOffset() {
        // M3: read as a UTC offset in 0.2.0.
        timed("2026-10-14 15:00-16:00", at(10, 14, 15), at(10, 14, 16))
        timed("2026-10-14T09:00-10:30", at(10, 14, 9), at(10, 14, 10, 30))
        timed("2026-10-14 9:00-10:00", at(10, 14, 9), at(10, 14, 10))
        timed("2026-10-14T15:00 to 16:30", at(10, 14, 15), at(10, 14, 16, 30))
        timed("2026-10-14T15:00 to 2026-10-14T16:30", at(10, 14, 15), at(10, 14, 16, 30))
        // With seconds (RFC 3339), a "-hh:mm" is the offset; "+hh:mm" and Z are offsets either way.
        timed("2026-10-14T15:00:00-04:00", at(10, 14, 15))
        timed("2026-10-14T19:00:00Z", at(10, 14, 15))
        timed("2026-10-14T21:00+02:00", at(10, 14, 15))
        // An offset that doesn't exist is unreadable (0.2.0 threw, which became "Something went wrong").
        unreadable("2026-10-14T15:00:00+19:00")
        unreadable("2026-10-14T15:00+15:00")
        unreadable("2026-10-14T15:00:00-13:00")
    }

    @Test fun addEventRefusesATimeThatHasPassed() {
        fun times(w: String, n: ZonedDateTime = now) = Events.times(Action.AddEvent("x", w), n, 60)
        val evening = ZonedDateTime.of(2026, 10, 10, 21, 0, 0, 0, zone)
        assertEquals(Read.Unreadable(Events.PASSED), times("Friday this week"))
        assertEquals(Read.Unreadable(Events.PASSED), times("today at 8", evening))
        assertEquals(Read.Unreadable(Events.PASSED), times("tonight", evening))
        assertEquals(Read.Unreadable(Events.PASSED), times("this evening", evening))
        assertEquals(Read.Unreadable(Events.PASSED), times("yesterday"))
        assertEquals("that time has passed", Events.PASSED)
        // Today all day, and later today, are fine; read_calendar may still read past days.
        assertTrue(times("today") is Read.Ok)
        assertTrue(times("tonight at 11", evening) is Read.Ok)
        assertTrue(WhenParser.range("yesterday", now) is Read.Ok)
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
        range("the week after next", at(10, 19), at(10, 26))
        range("the weekend after next", at(10, 24), at(10, 26))
        range("week", at(10, 10), at(10, 12))
        assertEquals(7L, (WhenParser.range("next week", now) as Read.Ok).value.days)
        // The week words count only as the whole text (0.2.0 read "two weeks from now" as today).
        range("two weeks from now", at(10, 24), at(10, 25))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.range("week of the 19th", now))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.range("my weekend plans", now))
        assertEquals(Read.Unreadable(WhenParser.SAY_MONTH), WhenParser.range("10/11", now))
        assertEquals(Read.Unreadable(WhenParser.SAY_DATE), WhenParser.range("my birthday", now))
    }
}
