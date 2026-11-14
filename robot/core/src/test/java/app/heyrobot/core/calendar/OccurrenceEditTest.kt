// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

import app.heyrobot.core.calendar.OccurrenceEdit.Shape
import app.heyrobot.core.calendar.OccurrenceEdit.Write
import org.junit.Assert.assertEquals
import org.junit.Test

// fix-calendar-repeat-b: editing one occurrence on Robot's Calendar screen moved the whole series' start to it.
class OccurrenceEditTest {
    private val friday = 1_779_000_000_000L

    @Test fun anOccurrenceOfASeriesChangesAlone() {
        assertEquals(Write.NEW_EXCEPTION, OccurrenceEdit.save(Shape.SERIES, friday, synced = true))
        assertEquals(Write.NEW_CANCEL, OccurrenceEdit.delete(Shape.SERIES, friday, synced = true))
    }

    @Test fun aChangedOccurrenceStaysOne() {
        assertEquals(Write.UPDATE, OccurrenceEdit.save(Shape.EXCEPTION, friday, synced = true))
        assertEquals(Write.CANCEL, OccurrenceEdit.delete(Shape.EXCEPTION, friday, synced = true)) // deleting the row would bring back the old one
    }

    @Test fun singleEventsAndWholeSeriesAsBefore() {
        assertEquals(Write.UPDATE, OccurrenceEdit.save(Shape.SINGLE, friday, synced = true))
        assertEquals(Write.DELETE, OccurrenceEdit.delete(Shape.SINGLE, friday, synced = true))
        assertEquals(Write.UPDATE, OccurrenceEdit.save(Shape.SERIES, null, synced = true)) // correct-last, right after adding
        assertEquals(Write.DELETE, OccurrenceEdit.delete(Shape.SERIES, null, synced = true)) // undo, "delete that"
    }

    // fix-calendar-repeat-e: an exception the provider took said "nothing was saved", and saving again added a second one.
    @Test fun anExceptionTheProviderTakesIsSavedAndRecorded() {
        val recorded = mutableListOf<Long>()
        assertEquals(null, OccurrenceEdit.exceptionSaved({ 42L }) { recorded += it })
        assertEquals(listOf(42L), recorded)
    }

    @Test fun anExceptionTheProviderRefusesSaysSoAndRecordsNothing() {
        val recorded = mutableListOf<Long>()
        assertEquals(PrivateRefusal.REFUSED, OccurrenceEdit.exceptionSaved({ null }) { recorded += it })
        assertEquals(emptyList<Long>(), recorded)
    }

    // fix-calendar-repeat-f: on rc2 an exception to a series with no _SYNC_ID (a local calendar) left only the exception:
    // the provider dropped the other Fridays. Such a series leaves the occurrence out (EXDATE) instead.
    @Test fun aSeriesWithoutASyncIdNeverTakesAnException() {
        assertEquals(Write.EXCLUDE_AND_ADD, OccurrenceEdit.save(Shape.SERIES, friday, synced = false))
        assertEquals(Write.EXCLUDE, OccurrenceEdit.delete(Shape.SERIES, friday, synced = false))
        assertEquals(Write.UPDATE, OccurrenceEdit.save(Shape.SERIES, null, synced = false))
        assertEquals(Write.DELETE, OccurrenceEdit.delete(Shape.SERIES, null, synced = false))
        assertEquals(Write.CANCEL, OccurrenceEdit.delete(Shape.EXCEPTION, friday, synced = false))
    }

    // fix-calendar-lows-b (review-fix-calendar-lows 1): on a series with no _SYNC_ID that already has an exception, any
    // exception write makes the provider rebuild that row alone, so the other occurrences vanish. Nothing is written.
    @Test fun aSeriesWithAnExceptionRefusesOneOccurrenceAndWritesNothing() {
        for (w in listOf(Write.EXCLUDE_AND_ADD, Write.EXCLUDE)) {
            var wrote = 0
            assertEquals(PrivateRefusal.SERIES_CHANGED_IN_APP, OccurrenceEdit.unlessChanged(w, { true }) { wrote++; null })
            assertEquals(PrivateRefusal.REFUSED, OccurrenceEdit.unlessChanged(w, { null }) { wrote++; null })
            assertEquals(0, wrote)
            assertEquals(null, OccurrenceEdit.unlessChanged(w, { false }) { wrote++; null })
            assertEquals(1, wrote)
        }
    }

    // fix-calendar-lows-b: the whole-series edit and delete (and a synced series' exceptions) go on as before, unasked.
    @Test fun otherWritesRunWhateverTheSeriesExceptions() {
        for (w in Write.entries - Write.EXCLUDE_AND_ADD - Write.EXCLUDE) {
            var asked = 0; var wrote = 0
            assertEquals(null, OccurrenceEdit.unlessChanged(w, { asked++; true }) { wrote++; null })
            assertEquals(w.name, listOf(0, 1), listOf(asked, wrote))
        }
    }
}
