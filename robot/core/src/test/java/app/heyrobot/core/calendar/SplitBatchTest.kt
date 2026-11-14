// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.calendar.SplitBatch.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

// fix-calendar-lows (review-fix-calendar-repeat-f 2 to 5, 8): an occurrence edit is one batch, the EXDATE fits the
// series, and a series' split-offs are found again.
class SplitBatchTest {
    private val nov20 = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
    private val at13 = nov20 + 13 * 3_600_000L

    @Test fun aSplitOffIsOneBatchSoAFailedExdateTakesTheCopyBack() {
        val steps = SplitBatch.split(listOf(10 to 1, 60 to 1), excludes = 2)
        assertEquals(listOf(Step.AddEvent, Step.AddAlert(10, 1, 0), Step.AddAlert(60, 1, 0), Step.Exclude(0), Step.Exclude(1)), steps)
        // The copy, its alerts and the series' writes all go in the one list: none is written on its own afterwards.
        assertTrue(steps.filterIsInstance<Step.AddAlert>().all { steps[it.eventAt] == Step.AddEvent })
    }

    @Test fun aDeletedOccurrenceWritesTheSeriesAndItsExceptionsTogether() {
        assertEquals(listOf(Step.Exclude(0), Step.Exclude(1), Step.Exclude(2)), SplitBatch.delete(3))
    }

    @Test fun anAllDaySeriesTakesADate() {
        assertEquals("20261120", EventValues.exdate(null, nov20, allDay = true))
        assertEquals("20261113,20261120", EventValues.exdate("20261113", nov20, allDay = true))
        assertEquals("20261120", EventValues.exdate("20261120", nov20, allDay = true))
        // Never among date-times, nor on a line with a zone (it would be that zone's midnight).
        assertEquals("20261113T000000Z\n20261120", EventValues.exdate("20261113T000000Z", nov20, allDay = true))
        assertEquals("America/Halifax;20261113\n20261120", EventValues.exdate("America/Halifax;20261113", nov20, allDay = true))
        assertEquals("20261120T000000Z", EventValues.exdate("20261120T000000Z", nov20, allDay = true))
    }

    @Test fun aZonedOrManyLinedExdateKeepsEveryLine() {
        assertEquals("America/Halifax;20261113T090000,20261120T130000Z", EventValues.exdate("America/Halifax;20261113T090000", at13))
        assertEquals("20261106T130000Z\nAmerica/Halifax;20261113T090000,20261120T130000Z",
            EventValues.exdate("20261106T130000Z\nAmerica/Halifax;20261113T090000", at13))
        assertEquals("20261120T130000Z\n20261127T130000Z", EventValues.exdate("20261120T130000Z\n20261127T130000Z", at13))
        assertEquals("20261113\n20261120T130000Z", EventValues.exdate("20261113", at13))
    }

    @Test fun aSeriesDeleteFindsOnlyItsOwnSplitOffsStillRobots() {
        var m = SeriesSplits.add(emptyMap(), 41, 7)
        m = SeriesSplits.add(m, 42, 7)
        m = SeriesSplits.add(m, 43, 8)
        assertEquals(m, SeriesSplits.decode(SeriesSplits.encode(m)))
        assertEquals(listOf(41L), SeriesSplits.of(m, 7, added = setOf(41, 43)))
        assertEquals(emptyMap<Long, Long>(), SeriesSplits.decode("x y\n\n12"))
        assertEquals(SeriesSplits.MAX, (0L until SeriesSplits.MAX + 5L).fold(emptyMap<Long, Long>()) { a, i -> SeriesSplits.add(a, i, 1) }.size)
    }
}
