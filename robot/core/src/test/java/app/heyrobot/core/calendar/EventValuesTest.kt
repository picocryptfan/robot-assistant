// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.EventPlan
import app.heyrobot.core.Read
import app.heyrobot.core.RepeatWords
import app.heyrobot.core.i18n.Lang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** fix-calendar-repeat-c: the columns Calendars.insert writes (EventValues), and a series' DURATION after an edit. */
class EventValuesTest {
    private val zone = ZoneId.of("America/Halifax")
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, zone) // a Friday

    private fun plan(text: String): EventPlan {
        val r = (RepeatWords.plan(text, null, now, 60, Lang.EN) as Read.Ok).value
        return EventPlan(r.span.start, r.span.end!!, r.span.allDay, 7L, "Personal", 2026, repeat = r.repeat)
    }

    @Test fun anAllDaySeriesIsUtcMidnightsWithADayAndNoEnd() {
        val v = EventValues.of(plan("every second Friday"))
        assertEquals(java.time.LocalDate.of(2026, 11, 13).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), v.dtStart)
        assertEquals("UTC", v.zone)
        assertEquals(true, v.allDay)
        assertNull(v.dtEnd)
        assertEquals("P1D", v.duration)
        assertEquals("FREQ=WEEKLY;INTERVAL=2;WKST=MO;BYDAY=FR", v.rrule)
    }

    @Test fun aOneOffKeepsItsEndAndHasNoRule() {
        val start = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, zone)
        val v = EventValues.of(EventPlan(start, start.plusMinutes(90), false, 7L, "Personal", 2026))
        assertEquals(start.toInstant().toEpochMilli(), v.dtStart)
        assertEquals(start.plusMinutes(90).toInstant().toEpochMilli(), v.dtEnd)
        assertEquals("America/Halifax", v.zone)
        assertNull(v.rrule)
        assertNull(v.duration)
    }

    @Test fun anEditedSeriesTakesADuration() {
        assertEquals("P5400S", EventValues.seriesDuration(0, 5_400_000, false))
        assertEquals("P60S", EventValues.seriesDuration(0, 0, false))
        assertEquals("P2D", EventValues.seriesDuration(0, 2 * 86_400_000L, true))
    }

    // fix-calendar-repeat-d: an occurrence's exception sends its end as a DURATION; the provider refuses a DTEND there.
    @Test fun anOccurrenceEditSendsADurationNeverADtend() {
        val v = EventValues.exception(1_000L, "Timesheet", "", 3_600_000L, 9_000_000L, false, "America/Halifax")
        assertEquals(EventValues.ExceptionColumn.entries.toSet(), v.keys)
        assertEquals("P5400S", v[EventValues.ExceptionColumn.DURATION])
        assertEquals(1_000L, v[EventValues.ExceptionColumn.ORIGINAL_INSTANCE_TIME])
        assertEquals("America/Halifax", v[EventValues.ExceptionColumn.EVENT_TIMEZONE])
        val day = EventValues.exception(0L, "Off", "", 0L, 86_400_000L, true, "America/Halifax")
        assertEquals("P1D", day[EventValues.ExceptionColumn.DURATION]); assertEquals("UTC", day[EventValues.ExceptionColumn.EVENT_TIMEZONE])
        assertEquals(1, day[EventValues.ExceptionColumn.ALL_DAY])
    }

    // fix-calendar-repeat-f: an occurrence left out of a series is its UTC start in the EXDATE, added once.
    @Test fun anExdateAddsTheOccurrenceOnce() {
        val nov20 = 1_795_132_800_000L
        assertEquals("20261120T000000Z", EventValues.exdate(null, nov20))
        assertEquals("20261113T000000Z,20261120T000000Z", EventValues.exdate("20261113T000000Z", nov20))
        assertEquals("20261120T000000Z", EventValues.exdate("20261120T000000Z", nov20))
        assertEquals("20261120T160000Z", EventValues.exdate("", nov20 + 16 * 3_600_000L))
    }
}
