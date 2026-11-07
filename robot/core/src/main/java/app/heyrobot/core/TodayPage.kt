// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

// Today (home-design H2): what the Today page shows, from the day's picture (Today, H1) and the counts the app reads. Pure:
// the page draws a TodayView and holds no rules of its own. Short by design: a few rows per part, the rest a tap away.

/** A list on the page: its id, its name in the screen's language, and how many things are left on it. */
data class ListGlance(val id: String, val name: String, val left: Int)

/** The pinned note (or your newest): its id, title and first lines. [pinned]: you chose it. */
data class NoteGlance(val id: String, val title: String, val lines: List<String>, val pinned: Boolean)

/**
 * What the page is made of. [week]: this week's events (Monday to Sunday, and tomorrow when it is next week), null when
 * the calendar can't be read. [looseEnds]: Files' dates due (its brief part); [fromRobots]: requests from other Robots
 * waiting (Robot to Robot's brief part). Both 0 when that part is off or the phone is locked.
 */
data class TodayInput(
    val day: DayPicture,
    val week: List<CalendarEvent>?,
    val lists: List<ListGlance> = emptyList(),
    val looseEnds: Int = 0,
    val fromRobots: Int = 0,
    val note: NoteGlance? = null,
    /** H6: the weather line, null while it is off (HomeSettings.weather). */
    val weather: WeatherGlance? = null,
)

/** A day of the strip: its date, whether it is today, and how many events it has ([dots]: at most [TodayPage.MARKS]). */
data class WeekDay(val date: LocalDate, val today: Boolean, val count: Int) {
    val dots: Int get() = count.coerceAtMost(TodayPage.MARKS)
}

/**
 * The page. [week]: the strip, empty when the calendar can't be read. [rows]: today's events from now on (all-day first,
 * then the one under way, then the next ones), at most [TodayPage.ROWS]; [more] past them. [next]: the one in bold.
 */
data class TodayView(
    val day: DayPicture,
    val week: List<WeekDay>,
    val rows: List<CalendarEvent>,
    val more: Int,
    val next: CalendarEvent?,
    val lists: List<ListGlance>,
    val reminders: List<String>,
    val moreReminders: Int,
    val looseEnds: Int,
    val fromRobots: Int,
    val note: NoteGlance?,
    /** H6: the weather line, null while it is off. */
    val weather: WeatherGlance? = null,
) {
    val readable: Boolean get() = day.readable
}

object TodayPage {
    const val ROWS = 4
    const val LISTS = 2
    const val REMINDERS = 2
    const val NOTE_LINES = 2
    const val MARKS = 3
    /** A quick add to the note is cut here, as a list item is (ListsLimits.MAX_TEXT is 120; a note line may say more). */
    const val QUICK_ADD = 500

    fun view(i: TodayInput): TodayView {
        val p = i.day
        val zone = p.now.zone
        val today = p.now.toLocalDate()
        val ms = p.now.toInstant().toEpochMilli()
        val events = i.week
        val week = if (events == null) emptyList() else days(today).map { d -> WeekDay(d, d == today, marks(events, d, zone)) }
        val timed = if (events == null) emptyList() else BriefComposer.todays(events, p.now).filter { !it.allDay && it.end > ms }.sortedBy { it.begin }
        val all = p.allDay + timed
        val reminders = p.reminders.filter { it.isNotBlank() }
        return TodayView(p, week, all.take(ROWS), (all.size - ROWS).coerceAtLeast(0), p.current ?: p.next, i.lists.filter { it.left > 0 }.take(LISTS),
            reminders.take(REMINDERS), (reminders.size - REMINDERS).coerceAtLeast(0), i.looseEnds.coerceAtLeast(0), i.fromRobots.coerceAtLeast(0), i.note, i.weather)
    }

    /** This week, Monday first (as the design's strip; a setting can come with H4 if people want Sunday). */
    fun days(today: LocalDate): List<LocalDate> {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        return (0L until 7L).map { monday.plusDays(it) }
    }

    /** The range [TodaySource] reads: this week, and tomorrow when today is Sunday (H1's evening line needs it). */
    fun range(today: LocalDate, zone: ZoneId): DayRange {
        val days = days(today)
        val end = maxOf(days.last(), today.plusDays(1)).plusDays(1)
        return DayRange(days.first().atStartOfDay(zone), end.atStartOfDay(zone))
    }

    fun marks(events: List<CalendarEvent>, d: LocalDate, zone: ZoneId): Int =
        CalendarFormat.inRange(events, DayRange(d.atStartOfDay(zone), d.plusDays(1).atStartOfDay(zone))).size

    /** An event's time on the screen: "2:30" in English, "14 h 30" in French (the glossary's form). */
    fun time(ms: Long, zone: ZoneId, lang: Lang): String {
        val t = Instant.ofEpochMilli(ms).atZone(zone).toLocalTime()
        return if (lang == Lang.FR) (if (t.minute == 0) "${t.hour} h" else "${t.hour} h ${"%02d".format(t.minute)}")
        else "${if (t.hour % 12 == 0) 12 else t.hour % 12}:${"%02d".format(t.minute)}"
    }

    /** A title as the page shows it: cleaned and cut as the brief's are; empty means untitled. */
    fun title(e: CalendarEvent): String = BriefText.clean(e.title, CalendarFormat.TITLE)

    /** The note's glance: its first [NOTE_LINES] lines that say something, after the title. */
    fun note(n: Note, pinned: Boolean): NoteGlance {
        val lines = n.text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        if (n.title.isBlank() && lines.isNotEmpty()) lines.removeAt(0)
        return NoteGlance(n.id, n.displayTitle, lines.take(NOTE_LINES).map { NoteSearch.cut(it, 120) }, pinned)
    }

    /** The note after a quick add: [line] on a line of its own at the end, cut at [QUICK_ADD]; null when there is nothing to add. */
    fun added(n: Note, line: String, now: Long): Note? {
        val t = line.trim().take(QUICK_ADD)
        if (t.isEmpty()) return null
        val text = if (n.text.isBlank()) t else n.text.trimEnd() + "\n" + t
        return n.copy(text = text, updated = now)
    }

    /** The note on the page: the one you pinned while it exists, else your newest. */
    fun pick(notes: List<Note>, pinned: String?): Pair<Note, Boolean>? =
        notes.firstOrNull { it.id == pinned }?.let { it to true } ?: notes.maxByOrNull { it.updated }?.let { it to false }
}

/** The one offer of Today on the getting-started home: not yet made, taken, or turned down for good. */
enum class TodayOffer { OPEN, TAKEN, DECLINED }

/**
 * Today's own settings (home-design §3), in their own file, never settings.json. [today]: Today is your home page.
 * [offer]: where the one offer stands. [note]: the pinned note's id, null for your newest.
 */
data class HomeSettings(val today: Boolean = false, val offer: TodayOffer = TodayOffer.OPEN, val note: String? = null,
    /** H4: the parts' order (TodayOrder), as you put them by voice or drag. */
    val order: List<TodayPart> = TodayPart.DEFAULT,
    /** H9: "Day summary on the lock screen", counts and times only (TodayGlance); off until you turn it on. */
    val lockSummary: Boolean = false,
    /** H6: the weather line on the page, off until you turn it on; Robot fetches its forecast only when you tap. */
    val weather: Boolean = false,
    /** H7: your news feeds, none to start, and how they arrive (core news NewsSettings). */
    val news: app.heyrobot.core.news.NewsSettings = app.heyrobot.core.news.NewsSettings()) {
    fun toJson(): String = JSONObject().put("v", VERSION).put("today", today).put("offer", offer.name.lowercase())
        .apply { if (note != null) put("note", note) }.put("order", org.json.JSONArray(order.map { it.key })).put("lock_summary", lockSummary).put("weather", weather).put("news", news.toJson()).toString()

    /** H4: these settings with [part] moved as said. */
    fun with(part: TodayPart, move: TodayMove): HomeSettings = copy(order = TodayOrder.moved(order, part, move))

    /** Turn on: Today is home, and the offer is answered. */
    fun turnedOn(): HomeSettings = copy(today = true, offer = TodayOffer.TAKEN)

    /**
     * H5, a restore: [file]'s order, pinned note, weather line and Today on or off over these settings (RestoreSafety.home
     * guards the weather line first). Today restored on answers the offer; a declined offer stays declined; otherwise this
     * phone's offer stands. today-fix-b: a merge, so an older backup made while Today was off never turns it off here (and
     * only your own backup is restored: BackupCategory.ONLY_YOURS).
     */
    fun restored(file: HomeSettings): HomeSettings = copy(today = today || file.today, note = file.note, order = file.order, lockSummary = file.lockSummary, weather = file.weather, news = file.news,
        offer = if (file.today) TodayOffer.TAKEN else if (file.offer == TodayOffer.DECLINED) TodayOffer.DECLINED else offer)

    /** Not now is final; Settings › Display stays the way in. */
    fun declined(): HomeSettings = copy(offer = TodayOffer.DECLINED)

    /**
     * The offer shows on the getting-started home once Robot is [ready] and the setup guide isn't under way, until it is
     * answered. Turning Today on in Settings answers it too.
     */
    fun offerShows(ready: Boolean, guideUnderWay: Boolean): Boolean = !today && offer == TodayOffer.OPEN && ready && !guideUnderWay

    companion object {
        const val FILE = "home_settings.json"
        const val VERSION = 1

        /** H5, the backup: the file's own version (0 without one), or null when it isn't a JSON object. */
        fun version(text: String): Int? = try { JSONObject(text).optInt("v", 0) } catch (_: Exception) { null }

        /** A file this version can't read (newer, damaged) is the defaults: the getting-started home, as before. */
        fun fromJson(text: String?): HomeSettings = try {
            if (text.isNullOrBlank()) HomeSettings() else {
                val o = JSONObject(text)
                if (o.optInt("v", 0) != VERSION) HomeSettings()
                else HomeSettings(o.optBoolean("today", false),
                    TodayOffer.entries.firstOrNull { it.name.lowercase() == o.optString("offer") } ?: TodayOffer.OPEN,
                    o.optString("note").takeIf { o.has("note") && it.isNotBlank() && it.length <= 64 },
                    TodayOrder.of(o.optJSONArray("order")?.let { a -> (0 until minOf(a.length(), 16)).map { a.optString(it) } } ?: emptyList()),
                    o.optBoolean("lock_summary", false), o.optBoolean("weather", false),
                    app.heyrobot.core.news.NewsSettings.of(o.optJSONObject("news")))
            }
        } catch (_: Exception) {
            HomeSettings()
        }
    }
}
