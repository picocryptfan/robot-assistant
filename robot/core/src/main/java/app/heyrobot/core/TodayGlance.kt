// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import java.time.Instant
import java.time.LocalTime

// Pure. Today outside the app (home-design.md §6.2, H9): the home-screen widget and the lock screen summary. The app
// (ui/today/TodayWidget.kt) reads the phone's state for these rules, draws what they say and posts the summary.
//
// The widget, as the lists' widget does (WidgetRules):
//   - Today off: a line saying how to turn it on;
//   - a phone lock (Robot's own), for you or someone else: "Robot is locked";
//   - a lock-screen host, the screen off or the phone locked: the summary when "Day summary on the lock screen" is on,
//     else "Unlock to see your day";
//   - otherwise the day line, as "how's my day" says it.
// The lock screen summary (a silent notice, off until you turn it on): counts and times only, "3 events today, next at
// 2:00.", never a title, a reminder's words, a list's name or a note. Nothing under a phone lock or with Today off.

/** Where and how Today's widget or summary is drawn now, as the app read it. */
data class GlanceNow(
    /** Today is your home page (HomeSettings.today). */
    val today: Boolean,
    /** "Day summary on the lock screen" (HomeSettings.lockSummary). */
    val lockSummary: Boolean,
    /** The screen is on (PowerManager.isInteractive). */
    val interactive: Boolean,
    /** The phone is locked (the lock screen, or the device locked). */
    val locked: Boolean,
    /** A phone lock (Robot's own) is on, for you or someone else. */
    val phoneLock: Boolean,
    /** That phone lock is for someone else. */
    val someoneElse: Boolean,
    /** The widget's host is the lock screen (a keyguard category). */
    val keyguardHost: Boolean = false,
)

/** What turning the lock screen summary on does: set it, ask for notifications, or say they're off. */
enum class LockSwitch { SET, ASK, REFUSED }

/** What the widget says instead of the day. */
enum class GlanceLine { OFF, ROBOT_LOCKED, UNLOCK }

/** What the widget draws. */
sealed class GlanceView {
    data class Line(val line: GlanceLine) : GlanceView()
    /** Counts and times only ([TodayGlance.summary]). */
    data class Summary(val text: Phrase) : GlanceView()
    /** The day line, titles and all: the phone is unlocked and on. */
    data class Day(val text: Phrase) : GlanceView()
}

object TodayGlance {
    /** Never redrawn sooner than this after a change (a burst of events starting together is one redraw). */
    const val MIN_WAIT_MS = 60_000L

    /** What the widget draws; [day] is read only when the answer needs it. */
    fun widget(n: GlanceNow, lang: Lang, day: () -> DayInput): GlanceView = when {
        !n.today -> GlanceView.Line(GlanceLine.OFF)
        n.someoneElse || n.phoneLock -> GlanceView.Line(GlanceLine.ROBOT_LOCKED)
        n.keyguardHost || n.locked || !n.interactive ->
            if (n.lockSummary) GlanceView.Summary(summary(Today.picture(asLocked(day())), lang)) else GlanceView.Line(GlanceLine.UNLOCK)
        // today-fix-c 1: the home-screen app draws it, so a private event is "a private event" and its time (design §6.3).
        else -> GlanceView.Day(Today.line(Today.picture(hidden(day(), lang)), lang))
    }

    /**
     * The lock screen summary's words, or null: no notice (it is off, Today is off, or a phone lock is on). today-fix-c 5:
     * always as if the phone were locked, since it stays on the lock screen after a lock delay with nothing redrawn.
     */
    fun notice(n: GlanceNow, lang: Lang, day: () -> DayInput): Phrase? =
        if (!n.today || !n.lockSummary || n.phoneLock || n.someoneElse) null else summary(Today.picture(asLocked(day())), lang)

    /** The day as the phone locked reads it: no private event counted or timed, only that they wait (any in the file, as locked). */
    fun asLocked(i: DayInput): DayInput {
        val private = i.events.orEmpty().any { it.kind == EventKind.PRIVATE }
        return i.copy(events = i.events?.filter { it.kind != EventKind.PRIVATE }, privateLocked = i.privateLocked || private)
    }

    /** The day with each private event's title "a private event" and no place, as History keeps it. */
    fun hidden(i: DayInput, lang: Lang): DayInput {
        val name = BriefStrings.privateEvent().render(Langs.of(lang))
        return i.copy(events = i.events?.map { if (it.kind == EventKind.PRIVATE) it.copy(title = name, location = null) else it })
    }

    /** today-fix-c 3: turning the summary on with Robot's notifications allowed sets it; else Android is asked, or it can't be. */
    fun lockSwitch(want: Boolean, canNotify: Boolean, granted: Boolean): LockSwitch = when {
        !want || canNotify -> LockSwitch.SET
        !granted -> LockSwitch.ASK
        else -> LockSwitch.REFUSED
    }

    /** today-fix-c 10: the widget's smallest height when Android doesn't say (its minHeight). */
    const val MIN_HEIGHT_DP = 110

    /**
     * today-fix-c 10: the day's lines that fit a widget [heightDp] tall (0: not known) at [fontScale], so the last one ends
     * "…" instead of being cut mid-line: the layout's padding (20dp), its title (18sp, at least 40dp) and 4dp above.
     */
    fun lines(heightDp: Int, fontScale: Float): Int {
        val h = if (heightDp > 0) heightDp else MIN_HEIGHT_DP
        val scale = if (fontScale.isFinite() && fontScale > 0f) fontScale.coerceIn(0.5f, 3f) else 1f
        val title = maxOf(40f, 18f * scale * LINE)
        return ((h - 24 - title) / (15f * scale * LINE)).toInt().coerceIn(1, 12)
    }

    /** A line of text is about this many times its size tall. */
    private const val LINE = 1.2f

    /** Today's events not yet over: under way, all-day, and still to come. */
    fun left(p: DayPicture): Int = (if (p.current != null) 1 else 0) + p.allDay.size + (if (p.next != null) 1 + p.later else 0)

    /** The summary: counts and times only, never anything you wrote or a title. */
    fun summary(p: DayPicture, lang: Lang): Phrase {
        val parts = mutableListOf<Phrase>()
        val n = left(p)
        when {
            p.next != null -> parts += BriefStrings.glanceNext(n, "time" to clock(p.next.begin, p, lang))
            n > 0 -> parts += BriefStrings.glanceEvents(n)
        }
        if (p.privateLocked) parts += BriefStrings.glancePrivate()
        else if (!p.readable) parts += BriefStrings.calendarUnreadable()
        else if (n == 0) {
            parts += BriefStrings.dayNothingElse()
            p.tomorrow?.let { t -> parts += if (t.allDay) BriefStrings.glanceTomorrowAllDay() else BriefStrings.glanceTomorrowAt("time" to clock(t.begin, p, lang)) }
        }
        p.timerEnds?.let { parts += if (p.timers == 1) BriefStrings.dayTimer("time" to clock(it, p, lang)) else BriefStrings.dayTimers(p.timers, "time" to clock(it, p, lang)) }
        if (p.reminders.isNotEmpty()) parts += BriefStrings.glanceReminders(p.reminders.size)
        if (p.todo > 0) parts += BriefStrings.dayTodo(p.todo)
        return Phrase.Join(parts)
    }

    /**
     * When the summary next changes (ms): an event under way ends, the next one starts, a timer ends, or the day turns. At
     * least [MIN_WAIT_MS] from now.
     */
    fun nextChange(p: DayPicture): Long {
        val now = p.now.toInstant().toEpochMilli()
        val midnight = p.now.toLocalDate().plusDays(1).atStartOfDay(p.now.zone).toInstant().toEpochMilli()
        val soonest = listOfNotNull(p.current?.end, p.next?.begin, p.timerEnds, midnight).filter { it > now }.minOrNull() ?: midnight
        return maxOf(soonest, now + MIN_WAIT_MS)
    }

    private fun clock(ms: Long, p: DayPicture, lang: Lang): String {
        val t: LocalTime = Instant.ofEpochMilli(ms).atZone(p.now.zone).toLocalTime()
        return if (lang == Lang.EN) BriefClock.en(t) else BriefClock.fr(t)
    }
}

/**
 * H9: "Read my day after my morning alarm" on Today's settings. It is the brief, run after the alarm (one brief and one
 * list of parts, home-design §4): on means the brief is on and starts when you turn off an alarm in its hours. Off until
 * you turn it on, as the brief is.
 */
object TodayAlarm {
    fun on(rs: RoutineSettings): Boolean = rs.briefEnabled && rs.briefTrigger is RoutineTrigger.AlarmDismissed

    /**
     * On: the brief on, after the alarm; the trigger it had while on (a time, say) kept. Off (today-fix-c 2): that trigger
     * back, or "only when asked" when there was none; the brief itself stays on.
     */
    fun set(rs: RoutineSettings, on: Boolean): RoutineSettings = when {
        on -> rs.copy(briefEnabled = true, briefTrigger = RoutineTrigger.AlarmDismissed(rs.alarmWindow),
            briefTriggerBefore = if (rs.briefEnabled && rs.briefTrigger !is RoutineTrigger.AlarmDismissed) rs.briefTrigger else rs.briefTriggerBefore.takeIf { on(rs) })
        on(rs) -> rs.copy(briefTrigger = rs.briefTriggerBefore ?: RoutineTrigger.Asked, briefTriggerBefore = null)
        else -> rs
    }
}
