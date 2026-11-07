// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZonedDateTime

// Pure. Today (home-design.md, H1): the day's data and the day line, said for "how's my day" (RoutineRouter's DAY_NOW)
// and, from H2, drawn on the Today page. Fixed sentences, no model, nothing online: the phone's calendar (today and
// tomorrow), your brief reminders due today, the open things on your to-do list, and the timers Robot set. The line says
// only what is here; a title or a reminder is your words, cut short, never read by the model.

/**
 * What the day line is made from, read now. [events]: the phone's calendar from the start of today to the end of tomorrow,
 * null when Robot can't read it (no permission, or the read failed). [reminders]: brief reminders due today, as written.
 * [todo]: open items on your to-do list, null when the lists can't be read (locked, off, none yet). [timers]: when each
 * timer Robot set ends (ms), ended ones included (they're dropped here).
 */
data class DayInput(
    val now: ZonedDateTime,
    val events: List<CalendarEvent>?,
    val reminders: List<String> = emptyList(),
    val todo: Int? = null,
    val timers: List<Long> = emptyList(),
    /** Private events wait for the unlock: the phone is locked and Robot has some sealed (known without opening them). */
    val privateLocked: Boolean = false,
)

/**
 * The day as Today shows and says it. [current]: a timed event under way; [allDay]: today's all-day events; [next]: the
 * next timed event today, [later] the count after it; [tomorrow]: tomorrow's first event (all-day first), looked at when
 * nothing timed is left today or in the [evening]; [timerEnds]: the soonest running timer, of [timers].
 */
data class DayPicture(
    val now: ZonedDateTime,
    val readable: Boolean,
    val current: CalendarEvent?,
    val allDay: List<CalendarEvent>,
    val next: CalendarEvent?,
    val later: Int,
    val tomorrow: CalendarEvent?,
    val reminders: List<String>,
    val todo: Int,
    val timers: Int,
    val timerEnds: Long?,
    val evening: Boolean,
    /** [DayInput.privateLocked]: the line says to unlock for them, never that the day is done. */
    val privateLocked: Boolean = false,
) {
    /** Nothing timed is under way or left today: the line ends "Nothing else today." Never while private events are locked. */
    val doneForToday: Boolean get() = readable && !privateLocked && current == null && next == null
}

object Today {
    /** From this hour the line ends with tomorrow's first event even when today isn't over (a default; tune by use). */
    const val EVENING_HOUR = 18
    /** All-day events named in the line; the rest are on the page. */
    const val MAX_ALL_DAY = 2
    /** A reminder said in the line is cut here (a calendar title at CalendarFormat.TITLE, as in the brief). */
    const val REMINDER = 80

    fun picture(input: DayInput): DayPicture {
        val now = input.now
        val ms = now.toInstant().toEpochMilli()
        val evening = now.hour >= EVENING_HOUR
        val timers = input.timers.filter { it > ms }.sorted()
        val events = input.events
        if (events == null) return DayPicture(now, false, null, emptyList(), null, 0, null, input.reminders, input.todo ?: 0, timers.size,
            timers.firstOrNull(), evening, input.privateLocked)
        val today = BriefComposer.todays(events, now)
        val (allDay, timed) = today.partition { it.allDay }
        val current = timed.filter { it.begin <= ms && it.end > ms }.minByOrNull { it.end }
        val upcoming = timed.filter { it.begin > ms }.sortedBy { it.begin }
        val day = now.toLocalDate().plusDays(1)
        val tomorrowRange = DayRange(day.atStartOfDay(now.zone), day.plusDays(1).atStartOfDay(now.zone))
        val tomorrow = CalendarFormat.inRange(events, tomorrowRange).sortedWith(compareBy({ !it.allDay }, { it.begin })).firstOrNull()
        return DayPicture(now, true, current, allDay, upcoming.firstOrNull(), (upcoming.size - 1).coerceAtLeast(0), tomorrow,
            input.reminders, input.todo ?: 0, timers.size, timers.firstOrNull(), evening, input.privateLocked)
    }

    /** The day line, every sentence of it, in [lang] (times as [lang] says them). */
    fun line(p: DayPicture, lang: Lang, page: List<TodayPart>? = null, note: NoteGlance? = null): Phrase {
        val parts = mutableListOf<Phrase>()
        timer(p, lang)?.let(parts::add)
        val calendar = mutableListOf<Phrase>()
        p.current?.let { calendar += now(it, p, lang) }
        if (p.allDay.isNotEmpty()) calendar += BriefStrings.dayAllDay("list" to Phrase.Join(p.allDay.take(MAX_ALL_DAY).map { title(it) }, ", "))
        p.next?.let { calendar += next(it, p, lang, withLater = true) }
        if (!p.readable) calendar += BriefStrings.calendarUnreadable()
        if (p.privateLocked) calendar += BriefStrings.privateLocked()
        val lists = mutableListOf<Phrase>()
        when (p.reminders.size) {
            0 -> Unit
            1 -> lists += BriefStrings.dayReminder("text" to BriefText.clean(p.reminders[0], REMINDER))
            else -> lists += BriefStrings.dayReminders(p.reminders.size, "text" to BriefText.clean(p.reminders[0], REMINDER))
        }
        if (p.todo > 0) lists += BriefStrings.dayTodo(p.todo)
        val end = listOfNotNull(BriefStrings.dayNothingElse().takeIf { p.doneForToday }, tomorrow(p, lang))
        // H5: with Today on ([page]), the page's parts in its order, the note among them; off, as H1 said it.
        if (page == null) parts += calendar + lists + end
        else for (part in TodayOrder.of(page.map { it.key })) parts += when (part) {
            TodayPart.CALENDAR -> calendar + end
            TodayPart.LISTS -> lists
            TodayPart.NOTE -> listOfNotNull(note?.let(BriefComposer::noteText))
        }
        return Phrase.Join(parts)
    }

    /** The one "right now" sentence that changes through the day: what's on now, a timer, what's next, or that's all. */
    fun rightNow(p: DayPicture, lang: Lang): Phrase = when {
        p.current != null -> now(p.current, p, lang)
        p.timerEnds != null -> timer(p, lang)!!
        p.next != null -> next(p.next, p, lang, withLater = false)
        p.privateLocked -> BriefStrings.privateLocked()
        !p.readable -> BriefStrings.calendarUnreadable()
        else -> BriefStrings.dayNothingElse()
    }

    private fun clock(ms: Long, p: DayPicture, lang: Lang): String = clock(Instant.ofEpochMilli(ms).atZone(p.now.zone).toLocalTime(), lang)

    private fun clock(t: LocalTime, lang: Lang): String = if (lang == Lang.EN) BriefClock.en(t) else BriefClock.fr(t)

    private fun title(e: CalendarEvent): Phrase = BriefText.clean(e.title, CalendarFormat.TITLE).ifEmpty { null }?.let { Phrase.plain(it) } ?: BriefStrings.untitled()

    private fun timer(p: DayPicture, lang: Lang): Phrase? {
        val ends = p.timerEnds ?: return null
        return if (p.timers == 1) BriefStrings.dayTimer("time" to clock(ends, p, lang)) else BriefStrings.dayTimers(p.timers, "time" to clock(ends, p, lang))
    }

    private fun now(e: CalendarEvent, p: DayPicture, lang: Lang): Phrase {
        val end = Instant.ofEpochMilli(e.end).atZone(p.now.zone)
        return if (end.toLocalDate() != p.now.toLocalDate()) BriefStrings.dayNow("title" to title(e))
        else BriefStrings.dayNowUntil("title" to title(e), "time" to clock(end.toLocalTime(), lang))
    }

    private fun next(e: CalendarEvent, p: DayPicture, lang: Lang, withLater: Boolean): Phrase =
        if (withLater && p.later > 0) BriefStrings.dayNextMore(p.later, "title" to title(e), "time" to clock(e.begin, p, lang))
        else BriefStrings.dayNext("title" to title(e), "time" to clock(e.begin, p, lang))

    /** Tomorrow's first event once nothing timed is left today, or in the evening; in the evening, an empty tomorrow too. */
    private fun tomorrow(p: DayPicture, lang: Lang): Phrase? {
        if (!p.readable || !(p.doneForToday || p.evening)) return null
        val t = p.tomorrow ?: return if (p.evening) BriefStrings.dayTomorrowEmpty() else null
        return if (t.allDay) BriefStrings.dayTomorrowAllDay("title" to title(t))
        else BriefStrings.dayTomorrowAt("title" to title(t), "time" to clock(t.begin, p, lang))
    }
}

/**
 * The timers Robot set, for the day line: each one's end (ms). Android lets no app read the clock's timers, so this is
 * Robot's own record: added when a timer is set, emptied when "cancel the timer" reaches the clock, and ended ones dropped.
 * A timer stopped in the clock app itself stays until its end (the line says "ends at", which stays true of the time set).
 */
object TodayTimers {
    const val VERSION = 1
    /** More than this many at once is unlikely; the oldest go. */
    const val MAX = 10

    fun add(list: List<Long>, endsAt: Long, nowMs: Long): List<Long> = (running(list, nowMs) + endsAt).sorted().takeLast(MAX)

    fun running(list: List<Long>, nowMs: Long): List<Long> = list.filter { it > nowMs }

    fun encode(list: List<Long>): String = JSONObject().put("v", VERSION).put("ends", JSONArray().apply { list.forEach { put(it) } }).toString()

    /** Unreadable or from a newer Robot: none (the line then says no timer, never a wrong one). */
    fun decode(text: String?): List<Long> = try {
        val o = JSONObject(text ?: return emptyList())
        if (o.optInt("v", -1) != VERSION) return emptyList()
        val a = o.getJSONArray("ends")
        (0 until a.length()).map { a.getLong(it) }.take(MAX)
    } catch (_: Exception) {
        emptyList()
    }
}
