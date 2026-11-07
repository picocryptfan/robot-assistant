// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import app.heyrobot.core.RLog
import app.heyrobot.core.BriefReminders
import app.heyrobot.core.DayInput
import app.heyrobot.core.DayRange
import app.heyrobot.core.HomeSettings
import app.heyrobot.core.ListGlance
import app.heyrobot.core.PageStamp
import app.heyrobot.core.TodayRead
import app.heyrobot.core.TodayPage
import app.heyrobot.core.TodayTimers
import app.heyrobot.core.TodayView
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsLoad
import java.io.File
import java.time.ZonedDateTime

// Main and voice processes. Today (home-design H1, H2): what the day line (core Today) is made of, read now on the phone. Nothing goes online: the
// calendar provider, the brief reminders, the lists' vault (read only when it opens) and Robot's own record of the timers
// it set. Blocking: call it off the main thread.

object TodaySource {
    /** Robot's record of its timers: transient, so outside Robot's backup and Android's (noBackupFilesDir). */
    private fun timersFile(ctx: Context) = File(ctx.noBackupFilesDir, "today_timers.json")

    fun input(ctx: Context, now: ZonedDateTime = ZonedDateTime.now()): DayInput = day(ctx, now, DayRange(
        now.toLocalDate().atStartOfDay(now.zone), now.toLocalDate().plusDays(2).atStartOfDay(now.zone))).let { d -> input(ctx, now, d.events, lists(ctx), d.privateLocked) }

    private fun input(ctx: Context, now: ZonedDateTime, events: List<app.heyrobot.core.CalendarEvent>?, lists: List<ListsList>?, privateLocked: Boolean = false): DayInput {
        val reminders = try { BriefReminders.due(RoutineFiles.store(ctx).reminders(), now.toLocalDate(), now.zone).map { it.text } } catch (t: Throwable) { RLog.w("today_reminders", t); emptyList() }
        val todo = lists?.firstOrNull { it.role == ListRole.TODO }?.items?.count { it.live && !it.done }
        return DayInput(now, events, reminders, todo, timers(ctx, now.toInstant().toEpochMilli()), privateLocked)
    }

    /** H3: the phone's events and the private ones; null only when the calendar can't be read and there is no private event. */
    fun events(ctx: Context, now: ZonedDateTime, range: DayRange): List<app.heyrobot.core.CalendarEvent>? = day(ctx, now, range).events

    /** [events] and whether private events wait for the unlock ([PrivateEventsFiles.now]). */
    class DayEvents(val events: List<app.heyrobot.core.CalendarEvent>?, val privateLocked: Boolean)

    fun day(ctx: Context, now: ZonedDateTime, range: DayRange): DayEvents {
        val phone = if (Calendars.readGranted(ctx)) {
            try { Calendars.events(ctx, range) } catch (t: Throwable) { RLog.w("today_calendar", t); null }
        } else null
        val private = try { PrivateEventsFiles.now(ctx) } catch (t: Throwable) { RLog.w("today_private", t); PrivateNow(emptyList(), false) }
        return DayEvents(if (phone == null && private.events.isEmpty()) null else app.heyrobot.core.calendar.CalendarAgenda.join(phone, private.events), private.locked)
    }

    /** Your lists; null when they don't open now (locked, off, none yet). */
    private fun lists(ctx: Context): List<ListsList>? = try {
        if (ListsFiles.locked(ctx)) null
        else (ListsFiles.vault(ctx).load(firstUse = false) as? ListsLoad.Loaded)?.value?.lists
    } catch (t: Throwable) {
        RLog.w("today_lists", t)
        null
    }

    /**
     * H2: the Today page now, in [lang]. The week's events in one read; the lists' vault opened once; Files' and Robot to
     * Robot's counts only as their brief parts allow (on, and the phone unlocked); the pinned note or your newest.
     */
    fun page(ctx: Context, lang: Lang, home: HomeSettings, now: ZonedDateTime = ZonedDateTime.now()): TodayView = read(ctx, lang, home, now).view(now)

    /**
     * today-fix-b: [page] as read, for the page to show again each minute without reading (core PageRefresh). [robots]: Robot
     * to Robot's count read earlier in this open, kept while its part stays on and the phone unlocked (:link is asked once
     * per open); [choices]: how many notes to offer to pin, from the same read of the notes.
     */
    fun read(ctx: Context, lang: Lang, home: HomeSettings, now: ZonedDateTime = ZonedDateTime.now(), robots: Int? = null, choices: Int = 0): TodayRead {
        val read = day(ctx, now, TodayPage.range(now.toLocalDate(), now.zone))
        val week = read.events
        val lists = lists(ctx)
        val notes = try { NotesFiles.store(ctx).list() } catch (t: Throwable) { RLog.w("today_note", t); null }
        val note = notes?.let { n -> TodayPage.pick(n, home.note)?.let { (n, pinned) -> TodayPage.note(n, pinned) } }
        return TodayRead(input(ctx, now, week, lists, read.privateLocked), week, glances(lists, lang), looseEnds(ctx, now), fromRobots(ctx, robots), note,
            notes.orEmpty().take(choices).map { it.id to it.displayTitle }, if (home.weather) weather(ctx, lang) else null)
    }

    /**
     * H6: the weather line from the stored forecast (never fetched here), for your saved town in your region's units. Read
     * once with the page (today-fix-b); the line at a minute comes from what was read, so "right now" still ages out.
     */
    fun weather(ctx: Context, lang: Lang): (ZonedDateTime) -> app.heyrobot.core.WeatherGlance {
        val s = SettingsStore.load(ctx)
        val units = app.heyrobot.core.WeatherUnits.of(s.regionFor(Phones.deviceRegion(ctx)))
        val stored = TodayWeatherStore.current(ctx)
        val town = s.weatherHome
        return { now -> app.heyrobot.core.TodayWeather.glance(stored, town, units, now, lang) }
    }

    /** today-fix-b: what [read] stands on, at [now], with nothing opened (core PageStamp); the page adds its own keys. */
    fun stamp(ctx: Context, now: ZonedDateTime): List<Any?> = listOf(now.toLocalDate(), now.zone, Calendars.readGranted(ctx), Assistant.keyguard(ctx),
        Assistant.locked(ctx), PageStamp.files(PrivateEventsFiles.dir(ctx), ListsFiles.dir(ctx), NotesFiles.dir(ctx), RobotFiles.dir(ctx),
            File(Paths.dir(ctx), app.heyrobot.core.RoutineStore.ROUTINES), ListsSettingsStore.file(ctx), FilesSettingsStore.file(ctx),
            LinkSettingsStore.file(ctx), HomeSettingsStore.file(ctx), timersFile(ctx), TodayWeatherStore.file(ctx)))

    /** The lists as the page shows them, shopping and to-do first, named in [lang]. */
    private fun glances(lists: List<ListsList>?, lang: Lang): List<ListGlance> =
        lists.orEmpty().filter { it.yours || it.role != null }.sortedBy { if (it.role == ListRole.SHOPPING) 0 else if (it.role == ListRole.TODO) 1 else 2 }
            .mapNotNull { l -> (l.name ?: l.role?.names?.getOrNull(if (lang == Lang.FR) 1 else 0))?.let { ListGlance(l.id.hex, it, l.open.size) } }

    /** H5: the page's lists for the brief (none when the vault doesn't open now). */
    fun glances(ctx: Context, lang: Lang): List<ListGlance> = glances(lists(ctx), lang)

    /** The page's note: the one you pinned while it exists, else your newest; null when there is none or it can't be read. */
    fun note(ctx: Context, home: HomeSettings): app.heyrobot.core.NoteGlance? =
        try { TodayPage.pick(NotesFiles.store(ctx).list(), home.note)?.let { (n, pinned) -> TodayPage.note(n, pinned) } } catch (t: Throwable) { RLog.w("today_note", t); null }

    /** Files' dates due, as the brief's files part counts them (BriefSource.files): Files on, its brief part on, unlocked. */
    private fun looseEnds(ctx: Context, now: ZonedDateTime): Int = try {
        val s = FilesSettingsStore.current(ctx)
        if (!s.on || !s.brief || Assistant.keyguard(ctx) || RobotFiles.locked(ctx)) 0
        else RobotFiles.shown(ctx)?.let { app.heyrobot.core.files.FileBrief.of(it, now.toLocalDate(), now.zone) }?.reminders?.size ?: 0
    } catch (t: Throwable) {
        RLog.w("today_files", t)
        0
    }

    /** Requests from other Robots waiting, as the brief's Robot to Robot part counts them (BriefSource.link). */
    private fun fromRobots(ctx: Context, kept: Int? = null): Int = try {
        val s = LinkSettingsStore.current(ctx)
        if (!s.on || !s.brief || Assistant.locked(ctx)) 0 else kept ?: run {
            val client = LinkClient(ctx)
            try { client.desk()?.let { d -> d.cards.size + d.more.values.sum() } ?: 0 } finally { client.close() }
        }
    } catch (t: Throwable) {
        RLog.w("today_link", t)
        0
    }

    /**
     * fix-today-timers: a record whose timers have all ended (nothing in it is shown any more) is deleted as it's read, as
     * RobotTimers' is. Only a record that decodes with entries: an empty or half-written one (another process writing) stays.
     */
    fun timers(ctx: Context, nowMs: Long): List<Long> = try {
        val f = timersFile(ctx)
        val all = if (f.exists()) TodayTimers.decode(f.readText()) else emptyList()
        TodayTimers.running(all, nowMs).also { if (it.isEmpty() && all.isNotEmpty()) f.delete() }
    } catch (t: Throwable) {
        RLog.w("today_timers", t)
        emptyList()
    }

    /** A timer of [seconds] was handed to the clock now. */
    @Synchronized
    fun timerSet(ctx: Context, seconds: Long) {
        try {
            val now = System.currentTimeMillis()
            timersFile(ctx).writeText(TodayTimers.encode(TodayTimers.add(timers(ctx, now), now + seconds * 1000, now)))
        } catch (t: Throwable) {
            RLog.w("today_timer_set", t)
        }
        changed(ctx)
    }

    /** "Cancel the timer" reached the clock: every timer is stopped. Also with your data, through RobotTimers.clear. */
    @Synchronized
    fun timersStopped(ctx: Context) {
        try { timersFile(ctx).delete() } catch (t: Throwable) { RLog.w("today_timer_stop", t) }
        changed(ctx)
    }

    /**
     * fix-today-review 3: Robot changed what the widget and the lock screen summary show (a timer, an event it added):
     * redraw them now, in main through TodayRefresh, as LockEngine does, not at the next screen-off.
     */
    fun changed(ctx: Context) {
        try {
            ctx.sendBroadcast(android.content.Intent().setComponent(android.content.ComponentName(ctx.packageName, app.heyrobot.lock.LockSignals.TODAY_REFRESH)))
        } catch (t: Throwable) {
            RLog.w("today_redraw", t)
        }
    }
}
