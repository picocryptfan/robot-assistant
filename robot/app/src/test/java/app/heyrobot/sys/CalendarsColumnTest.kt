// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.provider.CalendarContract.Events
import app.heyrobot.core.calendar.EventValues
import app.heyrobot.core.calendar.EventValues.ExceptionColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// fix-calendar-repeat-e: the app's column map for an occurrence edit sends a DURATION, never a DTEND (the provider
// rejects an exception with both), each column under its own provider name.
class CalendarsColumnTest {
    private val names = ExceptionColumn.entries.map { Calendars.column(it) }

    // fix-calendar-lows (review 5): an exception is sent again as it is stored, times and its tie to the series only.
    @Test fun anExceptionIsSentAgainWithItsTimesAndItsSeries() {
        val r = Calendars.REFRESH.toList()
        assertEquals(Events._ID, r.first())
        assertTrue(r.toString(), r.containsAll(listOf(Events.DTSTART, Events.DURATION, Events.ORIGINAL_ID, Events.ORIGINAL_INSTANCE_TIME)))
        for (c in listOf(Events.TITLE, Events.EVENT_LOCATION, Events.STATUS, Events.RRULE, Events.EXDATE)) assertFalse(c, c in r)
    }

    @Test fun anOccurrenceEditNeverWritesADtend() {
        assertFalse(names.toString(), Events.DTEND in names)
        assertTrue(names.toString(), Events.DURATION in names)
    }

    @Test fun eachColumnHasItsOwnName() {
        val expected = mapOf(
            ExceptionColumn.ORIGINAL_INSTANCE_TIME to Events.ORIGINAL_INSTANCE_TIME,
            ExceptionColumn.TITLE to Events.TITLE,
            ExceptionColumn.EVENT_LOCATION to Events.EVENT_LOCATION,
            ExceptionColumn.DTSTART to Events.DTSTART,
            ExceptionColumn.DURATION to Events.DURATION,
            ExceptionColumn.ALL_DAY to Events.ALL_DAY,
            ExceptionColumn.EVENT_TIMEZONE to Events.EVENT_TIMEZONE,
        )
        assertEquals(ExceptionColumn.entries.toSet(), expected.keys)
        expected.forEach { (c, name) -> assertEquals(c.name, name, Calendars.column(c)) }
        assertEquals(ExceptionColumn.entries.size, names.toSet().size)
    }

    // fix-calendar-repeat-f: the provider re-expands a series from the values an update brings: an EXDATE alone left the
    // Friday on screen, a DTSTART without the RRULE left one occurrence. Every time column goes in with it.
    @Test fun anExdateGoesInWithEveryTimeColumn() {
        val sent = EventValues.SeriesColumn.entries.map { Calendars.column(it) }.toSet()
        for (c in listOf(Events.DTSTART, Events.DURATION, Events.RRULE, Events.RDATE, Events.EXRULE, Events.EXDATE, Events.ALL_DAY, Events.EVENT_TIMEZONE))
            assertTrue(sent.toString(), c in sent)
        assertEquals(sent.size, EventValues.SeriesColumn.entries.size)
    }

    // fix-calendar-repeat-f: row 10 showed a cancelled Friday (eventStatus 2) on Robot's Calendar screen.
    @Test fun aCancelledOccurrenceIsNeverRead() {
        val sel = Calendars.DAY_SELECTION
        assertTrue(sel, "${android.provider.CalendarContract.Instances.STATUS} != ${Events.STATUS_CANCELED}" in sel)
        assertTrue(sel, "${android.provider.CalendarContract.Instances.VISIBLE} = 1" in sel)
    }
}
