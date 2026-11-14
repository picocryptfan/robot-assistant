// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.calendar

/**
 * fix-calendar-repeat-b: how a change made from one occurrence on the Calendar tab is written. An occurrence of a series
 * changes alone, as an exception in the provider's own way (Events.CONTENT_EXCEPTION_URI), never the series' start, rule
 * or other days; deleting it cancels only it. Without an occurrence (correct-last, undo, "delete that") the event as a
 * whole is changed or deleted, as before. A single event is changed in place.
 */
object OccurrenceEdit {
    /** The provider row: a single event, a series (an RRULE), or one occurrence already changed (an ORIGINAL_ID). */
    enum class Shape { SINGLE, SERIES, EXCEPTION }

    enum class Write {
        /** The row itself (a single event, an exception, or a whole series from correct-last). */
        UPDATE,
        /** The row and, for a series, every occurrence. */
        DELETE,
        /** A new exception row for the occurrence at its original begin. */
        NEW_EXCEPTION,
        /** A new cancelled exception row: that occurrence only is gone. */
        NEW_CANCEL,
        /** An exception row set to cancelled (deleting it would bring back the occurrence it replaced). */
        CANCEL,
        /** fix-calendar-repeat-f: the occurrence left out of the series (its EXDATE), for a series with no _SYNC_ID. */
        EXCLUDE,
        /** fix-calendar-repeat-f: the occurrence left out of the series (EXDATE) and added as an event of its own. */
        EXCLUDE_AND_ADD,
    }

    /**
     * [instance]: the occurrence's original begin (Instances.BEGIN), or null when the change isn't made from one.
     * fix-calendar-repeat-f: [synced], the series has a _SYNC_ID. Without one (a local calendar, or not yet synced) the
     * provider keeps only the exception's own instance in its expanded window and drops the series' other occurrences
     * (Pixel 8, Android's calendar provider, 14 Nov 2026), so the occurrence is excluded instead (EXDATE).
     */
    fun save(shape: Shape, instance: Long?, synced: Boolean): Write = when {
        shape != Shape.SERIES || instance == null -> Write.UPDATE
        synced -> Write.NEW_EXCEPTION
        else -> Write.EXCLUDE_AND_ADD
    }

    /**
     * fix-calendar-lows-b: one occurrence of a series with no _SYNC_ID ([Write.EXCLUDE_AND_ADD], [Write.EXCLUDE]) is
     * changed only while the series has no exception ([changed] false): the provider rebuilds a series' occurrences from
     * the one row written, so with an exception the others would vanish from view. With one, [apply] isn't run (nothing
     * is written) and you're told to make the change in your calendar app; unread, REFUSED. Any other write runs as is.
     */
    fun unlessChanged(write: Write, changed: () -> Boolean?, apply: () -> PrivateRefusal?): PrivateRefusal? =
        if (write != Write.EXCLUDE_AND_ADD && write != Write.EXCLUDE) apply()
        else when (changed()) { false -> apply(); true -> PrivateRefusal.SERIES_CHANGED_IN_APP; null -> PrivateRefusal.REFUSED }

    /**
     * fix-calendar-repeat-e: a new exception's outcome. [insert] gives the row's id, or null when the provider refused
     * it; a row it took is recorded as Robot's ([record]) and the change is saved (null), else REFUSED.
     */
    fun exceptionSaved(insert: () -> Long?, record: (Long) -> Unit): PrivateRefusal? {
        val id = insert() ?: return PrivateRefusal.REFUSED
        record(id)
        return null
    }

    fun delete(shape: Shape, instance: Long?, synced: Boolean): Write = when (shape) {
        Shape.SERIES -> if (instance == null) Write.DELETE else if (synced) Write.NEW_CANCEL else Write.EXCLUDE
        Shape.EXCEPTION -> Write.CANCEL
        Shape.SINGLE -> Write.DELETE
    }
}
