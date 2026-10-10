package app.heyrobot.sys

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars as Cal
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import app.heyrobot.core.CalendarEvent
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.DayRange
import app.heyrobot.core.EventPlan
import java.time.ZoneOffset

// Main and voice processes. CalendarContract (READ_CALENDAR / WRITE_CALENDAR, asked for by the main app only): the
// calendars Robot may add to, the events of a day or a week, adding one, and the calendar app's own editor when Robot
// can't (no permission, no writable calendar). Whatever calendar app the owner uses keeps its events in this provider.

object Calendars {
    fun readGranted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.READ_CALENDAR)
    fun writeGranted(ctx: Context): Boolean = Assistant.granted(ctx, Manifest.permission.WRITE_CALENDAR)

    /** Calendars Robot may write to (contributor access or more), in the provider's order; empty without READ_CALENDAR. */
    fun writable(ctx: Context): List<CalendarInfo> {
        if (!readGranted(ctx)) return emptyList()
        val out = mutableListOf<CalendarInfo>()
        val projection = arrayOf(Cal._ID, Cal.CALENDAR_DISPLAY_NAME, Cal.ACCOUNT_NAME, Cal.OWNER_ACCOUNT, Cal.IS_PRIMARY, Cal.VISIBLE)
        try {
            ctx.contentResolver.query(Cal.CONTENT_URI, projection, "${Cal.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Cal.CAL_ACCESS_CONTRIBUTOR.toString()), Cal._ID)
                ?.use { c ->
                    while (c.moveToNext()) {
                        out += CalendarInfo(
                            id = c.getLong(0),
                            name = c.getString(1)?.takeIf { it.isNotBlank() } ?: c.getString(2) ?: "Calendar",
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
     * The event instances of visible calendars around [range]. The query reaches a day further on each side: all-day
     * events are stored midnight to midnight UTC, and core's CalendarFormat.inRange keeps the ones that are really in it.
     */
    fun events(ctx: Context, range: DayRange): List<CalendarEvent> {
        val from = range.from.minusDays(1).toInstant().toEpochMilli()
        val to = range.to.plusDays(1).toInstant().toEpochMilli()
        val uri = Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
        val projection = arrayOf(Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.TITLE, Instances.EVENT_LOCATION)
        val out = mutableListOf<CalendarEvent>()
        ctx.contentResolver.query(uri, projection, "${Instances.VISIBLE} = 1", null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                out += CalendarEvent(c.getLong(0), c.getLong(1), !c.isNull(2) && c.getInt(2) == 1, c.getString(3).orEmpty(), c.getString(4))
            }
        }
        return out
    }

    /** Adds the event to [plan]'s calendar; its id, or null. An all-day event is midnight to midnight UTC, as the provider wants. */
    fun insert(ctx: Context, title: String, plan: EventPlan): Long? {
        val cal = plan.calendarId ?: return null
        val v = ContentValues().apply {
            put(Events.CALENDAR_ID, cal)
            put(Events.TITLE, title)
            if (plan.allDay) {
                put(Events.DTSTART, plan.start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.DTEND, plan.end.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, "UTC")
                put(Events.ALL_DAY, 1)
            } else {
                put(Events.DTSTART, plan.start.toInstant().toEpochMilli())
                put(Events.DTEND, plan.end.toInstant().toEpochMilli())
                put(Events.EVENT_TIMEZONE, plan.start.zone.id)
                put(Events.ALL_DAY, 0)
            }
        }
        return ctx.contentResolver.insert(Events.CONTENT_URI, v)?.let { ContentUris.parseId(it) }
    }

    /** The calendar app's own editor, prefilled; the owner saves it there. */
    fun insertIntent(title: String, plan: EventPlan): Intent = Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
        .putExtra(Events.TITLE, title)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, plan.start.toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, plan.end.toInstant().toEpochMilli())
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, plan.allDay)

    /** Any calendar app that takes ACTION_INSERT (the manifest's <queries> makes it visible). */
    fun editorIntent(): Intent = Intent(Intent.ACTION_INSERT).setData(Events.CONTENT_URI)
}
