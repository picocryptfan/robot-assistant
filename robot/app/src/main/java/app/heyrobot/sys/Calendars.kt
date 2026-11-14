// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.R
import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as Cal
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import app.heyrobot.core.calendar.EventValues
import app.heyrobot.core.calendar.OccurrenceEdit
import app.heyrobot.core.calendar.PrivateRefusal
import app.heyrobot.core.calendar.SplitBatch
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.DayRange
import app.heyrobot.core.EventAlert
import app.heyrobot.core.EventPlan
import java.time.ZoneOffset

// Main and voice processes. CalendarContract (READ_CALENDAR / WRITE_CALENDAR, asked for by the main app only): the
// calendars Robot may add to, the events of a day or a week, adding one, and the calendar app's own editor when Robot
// can't (no permission, no writable calendar). Whatever calendar app you use keeps its events in this provider.

object Calendars {
    fun readGranted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_CALENDAR)
    fun writeGranted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.WRITE_CALENDAR)

    /**
     * Calendars Robot may write to: contributor access or more, shown in calendar apps (visible) and synced
     * (sync_events = 1: an unsynced calendar's events never leave the phone and some apps hide them). In the
     * provider's order; empty without READ_CALENDAR.
     */
    fun writable(ctx: Context): List<CalendarInfo> {
        if (!readGranted(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(Cal._ID, Cal.CALENDAR_DISPLAY_NAME, Cal.ACCOUNT_NAME, Cal.OWNER_ACCOUNT, Cal.IS_PRIMARY, Cal.VISIBLE)
        try {
            val sel = "${Cal.CALENDAR_ACCESS_LEVEL} >= ? AND ${Cal.SYNC_EVENTS} = 1 AND ${Cal.VISIBLE} = 1"
            ctx.contentResolver.query(Cal.CONTENT_URI, projection, sel, arrayOf(Cal.CAL_ACCESS_CONTRIBUTOR.toString()), Cal._ID)
                ?.use { c ->
                    while (c.moveToNext()) {
                        out += CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: ctx.getString(R.string.settings_calendar_unnamed),
                            account = c.getString(2).orEmpty(),
                            owner = c.getString(3),
                            primary = !c.isNull(4) && c.getInt(4) == 1,
                            visible = c.isNull(5) || c.getInt(5) == 1,
                        )
                    }
                }
        } catch (_: SecurityException) {}
        return out
    }

    /**
     * 1.5 L7: every calendar shown in your calendar apps (read-only ones too), for Settings › Robot to Robot's "Calendars other
     * Robots see as busy": ids and names only. Empty without READ_CALENDAR.
     */
    fun visible(ctx: Context): List<CalendarInfo> {
        if (!readGranted(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        try {
            ctx.contentResolver.query(Cal.CONTENT_URI, arrayOf(Cal._ID, Cal.CALENDAR_DISPLAY_NAME, Cal.ACCOUNT_NAME), "${Cal.VISIBLE} = 1", null, Cal._ID)?.use { c ->
                while (c.moveToNext()) out += CalendarInfo(c.getLong(0), c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: ctx.getString(R.string.settings_calendar_unnamed),
                    c.getString(2).orEmpty(), null, false)
            }
        } catch (_: SecurityException) {}
        return out
    }

    /** fix-calendar-repeat-f: visible calendars, never a cancelled occurrence (an exception with eventStatus 2), on screen or read aloud. */
    internal val DAY_SELECTION = "${Instances.VISIBLE} = 1 AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})"

    /**
     * The event instances of visible calendars around [range]. The query reaches a day further on each side: all-day
     * events are stored midnight to midnight UTC, and core's CalendarFormat.inRange keeps the ones that are really in it.
     */
    fun events(ctx: Context, range: DayRange): List<CalendarEvent> {
        val from = range.from.minusDays(1).toInstant().toEpochMilli()
        val to = range.to.plusDays(1).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.TITLE, Instances.EVENT_LOCATION, Instances.EVENT_ID, Instances.CALENDAR_DISPLAY_NAME)
        val out = mutableListOf<CalendarEvent>()
        ctx.contentResolver.query(uri, projection, DAY_SELECTION, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += CalendarEvent(c.getLong(0), c.getLong(1), !c.isNull(2) && c.getInt(2) == 1, c.getString(3).orEmpty(), c.getString(4), c.getLong(5), c.getString(6))
            }
        }
        return out
    }

    /**
     * H3: an event Robot added (PrivateEventsFiles.added), changed in place from the Calendar tab's form; any other id is
     * refused here too, as LinkCalendar keeps the rule. Null when it was written, else why not (fix-calendar-repeat-d: a
     * write the provider refuses is REFUSED, never NOT_FOUND).
     */
    fun update(ctx: Context, id: Long, title: String, place: String, begin: Long, end: Long, allDay: Boolean, zone: java.time.ZoneId,
        instance: Long? = null): PrivateRefusal? {
        if (!writeGranted(ctx)) return PrivateRefusal.NOT_FOUND
        if (id !in PrivateEventsFiles.added(ctx)) return if (changedInApp(ctx, id)) PrivateRefusal.CHANGED_IN_APP else PrivateRefusal.NOT_FOUND
        // fix-calendar-repeat-b: from one occurrence of a series (the Calendar tab), that occurrence only (OccurrenceEdit).
        // fix-calendar-repeat-d: its end as a DURATION, never a DTEND (EventValues.exception).
        // fix-calendar-repeat-e: an exception the provider takes is saved (null), recorded as Robot's (OccurrenceEdit.exceptionSaved).
        // fix-calendar-repeat-f: a series with no _SYNC_ID: the occurrence becomes its own event, left out of the series.
        val write = OccurrenceEdit.save(shape(ctx, id), instance, synced(ctx, id))
        // fix-calendar-lows-b: refused, nothing written, when the series already has an exception (OccurrenceEdit.unlessChanged).
        if (write == OccurrenceEdit.Write.EXCLUDE_AND_ADD) return OccurrenceEdit.unlessChanged(write, { changed(ctx, id) }) {
            splitOff(ctx, id, instance!!, title, place, begin, end, allDay, zone) }
        if (write == OccurrenceEdit.Write.NEW_EXCEPTION) return OccurrenceEdit.exceptionSaved({
            exception(ctx, id, ContentValues().apply {
                for ((c, x) in EventValues.exception(instance!!, title, place, begin, end, allDay, zone.id)) when (x) {
                    is Long -> put(column(c), x); is Int -> put(column(c), x); else -> put(column(c), x.toString())
                }
            })
        }) { PrivateEventsFiles.recordAdded(ctx, it) }
        val v = ContentValues().apply {
            put(Events.TITLE, title)
            put(Events.EVENT_LOCATION, place)
            put(Events.DTSTART, begin)
            // 1.4.2: a repeating event Robot added keeps its RRULE and takes a DURATION, as the provider requires.
            if (repeats(ctx, id)) { putNull(Events.DTEND); put(Events.DURATION, EventValues.seriesDuration(begin, end, allDay)) }
            else put(Events.DTEND, end)
            put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id) // l10n: not text (a zone id)
        }
        return try { if (ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null) > 0) null else PrivateRefusal.NOT_FOUND }
        catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_update", t); PrivateRefusal.REFUSED }
    }

    internal fun column(c: EventValues.ExceptionColumn): String = when (c) {
        EventValues.ExceptionColumn.ORIGINAL_INSTANCE_TIME -> Events.ORIGINAL_INSTANCE_TIME
        EventValues.ExceptionColumn.TITLE -> Events.TITLE
        EventValues.ExceptionColumn.EVENT_LOCATION -> Events.EVENT_LOCATION
        EventValues.ExceptionColumn.DTSTART -> Events.DTSTART
        EventValues.ExceptionColumn.DURATION -> Events.DURATION
        EventValues.ExceptionColumn.ALL_DAY -> Events.ALL_DAY
        EventValues.ExceptionColumn.EVENT_TIMEZONE -> Events.EVENT_TIMEZONE
    }

    internal fun column(c: EventValues.SeriesColumn): String = when (c) {
        EventValues.SeriesColumn.DTSTART -> Events.DTSTART
        EventValues.SeriesColumn.DURATION -> Events.DURATION
        EventValues.SeriesColumn.RRULE -> Events.RRULE
        EventValues.SeriesColumn.RDATE -> Events.RDATE
        EventValues.SeriesColumn.EXRULE -> Events.EXRULE
        EventValues.SeriesColumn.EXDATE -> Events.EXDATE
        EventValues.SeriesColumn.ALL_DAY -> Events.ALL_DAY
        EventValues.SeriesColumn.EVENT_TIMEZONE -> Events.EVENT_TIMEZONE
    }

    /** fix-calendar-repeat-f: series [id] has a _SYNC_ID, so the provider keeps its other occurrences around an exception. */
    private fun synced(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events._SYNC_ID), null, null, null)?.use { c ->
            c.moveToFirst() && !c.getString(0).isNullOrEmpty()
        } == true
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_sync_id", t); false }

    /**
     * fix-calendar-lows: an exception's columns sent again after its series' EXDATE, so the provider expands its instance
     * again (experiment G, fix-calendar-repeat-f: resending the series' time columns alone drops it). Each is sent as it
     * is stored, so the row itself never changes.
     */
    internal val REFRESH = arrayOf(Events._ID, Events.DTSTART, Events.DTEND, Events.DURATION, Events.ALL_DAY, Events.EVENT_TIMEZONE, Events.ORIGINAL_ID,
        Events.ORIGINAL_INSTANCE_TIME)

    /**
     * fix-calendar-repeat-f: the occurrence at [instance] left out of series [id]: its EXDATE, sent with every time column
     * (EventValues.SeriesColumn), expecting one row, for core SplitBatch's one batch. fix-calendar-lows-b: no exception is
     * sent again: a series that has one is refused first ([changed]). Null when the series can't be read.
     */
    private fun excludeOps(ctx: Context, id: Long, instance: Long): List<ContentProviderOperation>? = try {
        val uri = ContentUris.withAppendedId(Events.CONTENT_URI, id)
        val cols = EventValues.SeriesColumn.entries
        val v = ContentValues()
        val read = ctx.contentResolver.query(uri, cols.map { column(it) }.toTypedArray(), null, null, null)?.use { c ->
            if (c.moveToFirst()) cols.forEachIndexed { i, col ->
                if (!c.isNull(i)) when (col) {
                    EventValues.SeriesColumn.DTSTART -> v.put(column(col), c.getLong(i))
                    EventValues.SeriesColumn.ALL_DAY -> v.put(column(col), c.getInt(i))
                    else -> v.put(column(col), c.getString(i))
                }
            }
            v.containsKey(Events.DTSTART) && v.containsKey(Events.RRULE)
        } == true
        if (!read) null else {
            v.put(Events.EXDATE, EventValues.exdate(v.getAsString(Events.EXDATE), instance, v.getAsInteger(Events.ALL_DAY) == 1))
            listOf(ContentProviderOperation.newUpdate(uri).withValues(v).withExpectedCount(1).build())
        }
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_exdate", t); null }

    /** fix-calendar-lows-b: series [id] has an exception (changed or cancelled in your calendar app, or left by rc2); null when unread. */
    private fun changed(ctx: Context, id: Long): Boolean? = try {
        ctx.contentResolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.ORIGINAL_ID} = ? AND ${Events.DELETED} = 0", arrayOf(id.toString()), null)?.use { it.count > 0 }
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_changed", t); null }

    /** fix-calendar-lows: [ops] in one batch (one transaction in the provider): all written, or none. */
    private fun applied(ctx: Context, ops: List<ContentProviderOperation>): Array<android.content.ContentProviderResult>? = try {
        ctx.contentResolver.applyBatch(CalendarContract.AUTHORITY, ArrayList(ops))
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_batch", t); null }

    /**
     * fix-calendar-repeat-f: occurrence [instance] of series [id] changed, as an event of its own in the series' calendar
     * with the series' alerts, recorded as Robot's; the series leaves it out ([exclude]). Null when saved, else why not.
     */
    private fun splitOff(ctx: Context, id: Long, instance: Long, title: String, place: String, begin: Long, end: Long, allDay: Boolean,
        zone: java.time.ZoneId): PrivateRefusal? {
        val cr = ctx.contentResolver
        val cal = try { cr.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.CALENDAR_ID), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else null } } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_split", t); null } ?: return PrivateRefusal.NOT_FOUND
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal); put(Events.TITLE, title); put(Events.EVENT_LOCATION, place)
            put(Events.DTSTART, begin); put(Events.DTEND, end); put(Events.ALL_DAY, if (allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (allDay) "UTC" else zone.id) // l10n: not text (a zone id)
        }
        // fix-calendar-lows: the series' alerts read first; unread, the edit is refused (you're told), never saved without them.
        val alerts = try {
            cr.query(CalendarContract.Reminders.CONTENT_URI, arrayOf(CalendarContract.Reminders.MINUTES, CalendarContract.Reminders.METHOD),
                "${CalendarContract.Reminders.EVENT_ID} = ?", arrayOf(id.toString()), null)?.use { c -> buildList<Pair<Int, Int>> { while (c.moveToNext()) add(c.getInt(0) to c.getInt(1)) } }
        } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_split_alert", t); null } ?: return PrivateRefusal.REFUSED
        val exclude = excludeOps(ctx, id, instance) ?: return PrivateRefusal.REFUSED
        if (alerts.isNotEmpty()) v.put(Events.HAS_ALARM, 1)
        val ops = SplitBatch.split(alerts, exclude.size).map { s ->
            when (s) {
                SplitBatch.Step.AddEvent -> ContentProviderOperation.newInsert(Events.CONTENT_URI).withValues(v).build()
                is SplitBatch.Step.AddAlert -> ContentProviderOperation.newInsert(CalendarContract.Reminders.CONTENT_URI)
                    .withValue(CalendarContract.Reminders.MINUTES, s.minutes).withValue(CalendarContract.Reminders.METHOD, s.method)
                    .withValueBackReference(CalendarContract.Reminders.EVENT_ID, s.eventAt).build()
                is SplitBatch.Step.Exclude -> exclude[s.index]
            }
        }
        // fix-calendar-lows: one batch, so a failed write leaves nothing behind (no copy Robot can't remove, none without its alerts).
        val added = applied(ctx, ops)?.firstOrNull()?.uri?.let { ContentUris.parseId(it) }?.takeIf { it > 0 } ?: return PrivateRefusal.REFUSED
        PrivateEventsFiles.recordAdded(ctx, added)
        PrivateEventsFiles.recordSplit(ctx, added, id)
        return null
    }

    /** fix-calendar-lows: series [id] deleted whole by Robot: the events Robot split off it go too, as its exceptions did. */
    private fun deleteSplits(ctx: Context, id: Long) {
        val splits = app.heyrobot.core.calendar.SeriesSplits.of(PrivateEventsFiles.splits(ctx), id, PrivateEventsFiles.added(ctx))
        if (splits.isEmpty()) return
        val gone = splits.filter { s ->
            try { ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, s), null, null) >= 0 } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_splits", t); false }
        }
        gone.forEach { PrivateEventsFiles.forgetAdded(ctx, it) } // fix-calendar-lows-b: gone, so no longer Robot's either.
        PrivateEventsFiles.forgetSplits(ctx, gone)
    }

    /** fix-calendar-repeat-d: [id] is one occurrence of a series Robot added, changed in your calendar app (its own id). */
    private fun changedInApp(ctx: Context, id: Long): Boolean = try {
        ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.ORIGINAL_ID), null, null, null)?.use { c ->
            c.moveToFirst() && !c.isNull(0) && c.getLong(0) in PrivateEventsFiles.added(ctx)
        } == true
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_original", t); false }

    /** 1.4.2: event [id] has an RRULE (a series). */
    private fun repeats(ctx: Context, id: Long): Boolean = shape(ctx, id) == OccurrenceEdit.Shape.SERIES

    /** fix-calendar-repeat-b: event [id] is a series (an RRULE), one changed occurrence of one (an ORIGINAL_ID), or single. */
    private fun shape(ctx: Context, id: Long): OccurrenceEdit.Shape = try {
        ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), arrayOf(Events.RRULE, Events.ORIGINAL_ID), null, null, null)?.use { c ->
            when {
                !c.moveToFirst() -> null
                !c.getString(0).isNullOrEmpty() -> OccurrenceEdit.Shape.SERIES
                !c.isNull(1) -> OccurrenceEdit.Shape.EXCEPTION
                else -> null
            }
        } ?: OccurrenceEdit.Shape.SINGLE
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_rrule", t); OccurrenceEdit.Shape.SINGLE }

    /** fix-calendar-repeat-b: a new exception to series [id] ([v] has its ORIGINAL_INSTANCE_TIME): its id, or null. */
    private fun exception(ctx: Context, id: Long, v: ContentValues): Long? = try {
        ctx.contentResolver.insert(ContentUris.withAppendedId(Events.CONTENT_EXCEPTION_URI, id), v)?.let { ContentUris.parseId(it) }?.takeIf { it > 0 }
    } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_exception", t); null }

    /** correct-last: event [id] as it is now, only one Robot added; null when it's gone or can't be read. */
    fun row(ctx: Context, id: Long): app.heyrobot.core.CalendarEvent? {
        if (id !in PrivateEventsFiles.added(ctx) || !readGranted(ctx)) return null
        val cols = arrayOf(Events.TITLE, Events.DTSTART, Events.DTEND, Events.ALL_DAY, Events.EVENT_LOCATION, Events.DELETED, Events.DURATION)
        return try {
            ctx.contentResolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, id), cols, null, null, null)?.use { c ->
                if (!c.moveToFirst() || c.getInt(5) != 0) null
                // 1.4.2: a repeating one has no DTEND: its first occurrence ends DURATION after DTSTART.
                else app.heyrobot.core.CalendarEvent(c.getLong(1), if (c.isNull(2)) c.getLong(1) + (app.heyrobot.core.Repeat.durationMillis(c.getString(6)) ?: 0L) else c.getLong(2),
                    c.getInt(3) != 0, c.getString(0).orEmpty(), c.getString(4), id = id)
            }
        } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_row", t); null }
    }

    /** H3: an event Robot added, deleted from the Calendar tab's form; any other id is refused. Null when deleted, else why not. */
    fun delete(ctx: Context, id: Long, instance: Long? = null): PrivateRefusal? {
        if (id !in PrivateEventsFiles.added(ctx) || !writeGranted(ctx)) return PrivateRefusal.NOT_FOUND
        // fix-calendar-repeat-b: one occurrence of a series (the Calendar tab) is cancelled alone (OccurrenceEdit).
        when (val write = OccurrenceEdit.delete(shape(ctx, id), instance, synced(ctx, id))) {
            // fix-calendar-repeat-f: a series with no _SYNC_ID leaves the occurrence out (EXDATE): a cancelled exception drops the rest.
            // fix-calendar-lows-b: refused, nothing written, when the series already has an exception.
            OccurrenceEdit.Write.EXCLUDE -> return OccurrenceEdit.unlessChanged(write, { changed(ctx, id) }) {
                val ex = excludeOps(ctx, id, instance!!) ?: return@unlessChanged PrivateRefusal.REFUSED
                if (applied(ctx, SplitBatch.delete(ex.size).map { ex[(it as SplitBatch.Step.Exclude).index] }) != null) null else PrivateRefusal.REFUSED }
            OccurrenceEdit.Write.NEW_CANCEL -> return if (exception(ctx, id, ContentValues().apply {
                put(Events.ORIGINAL_INSTANCE_TIME, instance!!); put(Events.STATUS, Events.STATUS_CANCELED) }) != null) null else PrivateRefusal.REFUSED
            OccurrenceEdit.Write.CANCEL -> return if (try {
                ctx.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), ContentValues().apply { put(Events.STATUS, Events.STATUS_CANCELED) }, null, null) > 0
            } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_delete", t); false }) null else PrivateRefusal.REFUSED
            else -> {}
        }
        val gone = try { ctx.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0 } catch (t: Exception) { app.heyrobot.core.RLog.w("calendar_delete", t); false }
        if (gone) deleteSplits(ctx, id)
        return if (gone) null else PrivateRefusal.REFUSED
    }

    /** An event Robot added: its [id], and whether its alert ([EventPlan.alertAt]) was written too. */
    data class Inserted(val id: Long, val alert: Boolean)

    /**
     * Adds the event to [plan]'s calendar, or null. An all-day event is midnight to midnight UTC, as the provider wants.
     * 1.1: a dated reminder's alert goes in as its Reminders row (core EventAlert); [Inserted.alert] false when that failed.
     */
    fun insert(ctx: Context, title: String, plan: EventPlan): Inserted? {
        val cal = plan.calendarId ?: return null
        val alert = EventAlert.row(plan)
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal)
            put(Events.TITLE, title)
            // 1.4.2: its times and zone; a repeating event has its RRULE and a DURATION, never a DTEND (EventValues).
            val ev = EventValues.of(plan)
            put(Events.DTSTART, ev.dtStart)
            ev.dtEnd?.let { put(Events.DTEND, it) }
            put(Events.EVENT_TIMEZONE, ev.zone)
            put(Events.ALL_DAY, if (ev.allDay) 1 else 0)
            ev.rrule?.let { put(Events.RRULE, it) }
            ev.duration?.let { put(Events.DURATION, it) }
            if (alert != null) put(Events.HAS_ALARM, 1)
        }
        val id = ctx.contentResolver.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) } ?: return null
        if (alert == null) return Inserted(id, alert = false)
        val r = ContentValues().apply {
            put(CalendarContract.Reminders.EVENT_ID, id)
            put(CalendarContract.Reminders.MINUTES, alert.minutes)
            put(CalendarContract.Reminders.METHOD, alert.method)
        }
        val written = try { ctx.contentResolver.insert(CalendarContract.Reminders.CONTENT_URI, r) != null } catch (_: Exception) { false }
        return Inserted(id, alert = written)
    }

    /**
     * The calendar app's own editor, prefilled; you save it there. An all-day event's times are UTC midnights:
     * AOSP-derived editors (Etar) read them in UTC, so local midnight would be the day before east of UTC.
     */
    fun insertIntent(title: String, plan: EventPlan): Intent {
        val begin = if (plan.allDay) plan.start.toLocalDate().atStartOfDay(ZoneOffset.UTC) else plan.start
        val end = if (plan.allDay) plan.end.toLocalDate().atStartOfDay(ZoneOffset.UTC) else plan.end
        return Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
            .putExtra(Events.TITLE, title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, plan.allDay)
            .apply { plan.repeat?.let { putExtra(Events.RRULE, it.rrule()) } }
    }

    /** Any calendar app that takes ACTION_INSERT (the manifest's <queries> makes it visible). */
    fun editorIntent(): Intent = Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
}
