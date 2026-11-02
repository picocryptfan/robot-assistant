// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.link

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Attendees
import android.provider.CalendarContract.Calendars as Cal
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.CalendarPick
import app.heyrobot.core.link.requests.Span
import app.heyrobot.r2r.DeskCalendar
import app.heyrobot.sys.LinkSettingsStore
import app.heyrobot.sys.SettingsStore
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

// THE :link PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §4.1, §4.4, Q3; job L7): your calendar as the desk's handlers
// (the link module's FreeBusyHandler and EventsHandler) use it, so free/busy and plans are answered on their own while the
// phone is locked and :voice isn't running. Deliberately narrow (the main session's condition of 28 Oct, for L15's review):
//
//   - Reading: the event instances' begin, end, all-day flag, availability and calendar id, and nothing else. No title, place,
//     description, organizer or attendee is ever loaded into :link's memory; declined and cancelled events are left out by
//     the query's selection, not by reading them. Only the calendars you chose for free/busy (link_settings.json's busyFrom;
//     none chosen: every visible one). "Free" events (availability FREE) don't count as busy.
//   - Writing: a plan you or Robot accepted goes into the calendar Settings › Calendar picks for add_event (its own pick when
//     none is chosen), with the plan's title and place only. Robot moves and removes only the events it added, by the ids
//     kept in each plan's sealed record: never another event of yours.
//
// Without READ_CALENDAR (or WRITE_CALENDAR to add) each call answers null or false, and the desk makes a card instead.

internal class LinkCalendar(private val app: Context) : DeskCalendar {
    override fun zone(): ZoneId = ZoneId.systemDefault()

    private fun granted(p: String) = app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    override fun busy(from: Long, to: Long): List<Span>? {
        if (to <= from || !granted(Manifest.permission.READ_CALENDAR)) return null
        val chosen = LinkSettingsStore.current(app).busyCalendars
        // A day further on each side: all-day events are stored midnight to midnight UTC.
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, (from - DAY) * 1000); ContentUris.appendId(it, (to + DAY) * 1000) }.build()
        val out = ArrayList<Span>()
        return try {
            app.contentResolver.query(uri, BUSY_PROJECTION, BUSY_SELECTION, null, null)?.use { c ->
                while (c.moveToNext()) {
                    if (!c.isNull(3) && c.getInt(3) == Events.AVAILABILITY_FREE) continue
                    if (chosen != null && c.getLong(4) !in chosen) continue
                    val allDay = !c.isNull(2) && c.getInt(2) == 1
                    val s = if (allDay) localDay(c.getLong(0)) else c.getLong(0) / 1000
                    val e = if (allDay) localDay(c.getLong(1)) else c.getLong(1) / 1000
                    if (e > s && e > from && s < to) out += Span(s, e)
                }
            }
            out
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            null
        }
    }

    /** An all-day event's UTC midnight as the same date's midnight in your zone. */
    private fun localDay(ms: Long): Long = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate().atStartOfDay(zone()).toEpochSecond()

    override fun add(title: String, place: String?, start: Long, minutes: Long): Long? {
        if (!granted(Manifest.permission.READ_CALENDAR) || !granted(Manifest.permission.WRITE_CALENDAR)) return null
        val cal = CalendarPick.choose(writable(), SettingsStore.load(app).eventCalendar) ?: return null
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal.id)
            put(Events.TITLE, title)
            if (place != null) put(Events.EVENT_LOCATION, place)
            put(Events.DTSTART, start * 1000)
            put(Events.DTEND, (start + minutes * 60) * 1000)
            put(Events.EVENT_TIMEZONE, zone().id)
            put(Events.ALL_DAY, 0)
        }
        return try { app.contentResolver.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }?.takeIf { it > 0 } } catch (_: RuntimeException) { null }
    }

    override fun move(id: Long, start: Long, minutes: Long): Boolean {
        if (id <= 0 || !granted(Manifest.permission.WRITE_CALENDAR)) return false
        val v = ContentValues().apply {
            put(Events.DTSTART, start * 1000)
            put(Events.DTEND, (start + minutes * 60) * 1000)
            put(Events.EVENT_TIMEZONE, zone().id)
        }
        return try { app.contentResolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, id), v, null, null) > 0 } catch (_: RuntimeException) { false }
    }

    override fun remove(id: Long): Boolean {
        if (id <= 0 || !granted(Manifest.permission.WRITE_CALENDAR)) return false
        return try { app.contentResolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, id), null, null) > 0 } catch (_: RuntimeException) { false }
    }

    /** The calendars Robot may add to, as CalendarPick reads them: ids, account and owner only (no calendar's name is needed). */
    private fun writable(): List<CalendarInfo> {
        val out = ArrayList<CalendarInfo>()
        try {
            val sel = "${Cal.CALENDAR_ACCESS_LEVEL} >= ? AND ${Cal.SYNC_EVENTS} = 1 AND ${Cal.VISIBLE} = 1"
            app.contentResolver.query(Cal.CONTENT_URI, CALENDAR_PROJECTION, sel, arrayOf(Cal.CAL_ACCESS_CONTRIBUTOR.toString()), Cal._ID)?.use { c ->
                while (c.moveToNext()) {
                    out += CalendarInfo(id = c.getLong(0), name = "", account = c.getString(1).orEmpty(), owner = c.getString(2),
                        primary = !c.isNull(3) && c.getInt(3) == 1, visible = true)
                }
            }
        } catch (_: RuntimeException) {}
        return out
    }

    companion object {
        private const val DAY = 86_400L

        /** What :link reads of an event, and only this (ProcessIsolationTest pins it). */
        val BUSY_PROJECTION = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.AVAILABILITY, Instances.CALENDAR_ID)

        /** Visible calendars, without the events you declined or that were cancelled. */
        val BUSY_SELECTION = "${Instances.VISIBLE} = 1 AND (${Instances.SELF_ATTENDEE_STATUS} IS NULL OR ${Instances.SELF_ATTENDEE_STATUS} != " +
            "${Attendees.ATTENDEE_STATUS_DECLINED}) AND (${Instances.STATUS} IS NULL OR ${Instances.STATUS} != ${Events.STATUS_CANCELED})"

        /** What :link reads of a calendar, to pick the one to add to. */
        val CALENDAR_PROJECTION = arrayOf(Cal._ID, Cal.ACCOUNT_NAME, Cal.OWNER_ACCOUNT, Cal.IS_PRIMARY)
    }
}
