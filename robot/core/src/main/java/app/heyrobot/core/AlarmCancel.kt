package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * fix-everyday-c ("cancel my alarm", Pixel 8, 5 Nov 2026: a card that only offered to open the clock app): what Robot does
 * with AlarmClock's ACTION_DISMISS_ALARM. Android shows Robot one alarm only, the next (AlarmManager's next alarm clock), so
 * with no time said Robot asks first about that one, since any other could be meant; a time you said goes to the clock as
 * is. "All my alarms", or no alarm Robot can see, opens your clock's alarms instead.
 */
object AlarmCancel {
    sealed class Plan {
        /** No time said: a card about the next alarm, the only one Robot sees. */
        data class Ask(val time: LocalTime) : Plan()
        /** The time you said; [next]: it is the next alarm, so Robot can check it went. */
        data class Dismiss(val time: LocalTime, val next: Boolean) : Plan()
        /** Your clock's alarms page; [none]: Robot sees no alarm set. */
        data class Open(val none: Boolean) : Plan()
    }

    fun plan(cmd: RoutineCommand.CantCancel, next: LocalTime?): Plan {
        if (cmd.all) return Plan.Open(none = next == null)
        val h = cmd.hour ?: return if (next != null) Plan.Ask(next) else Plan.Open(none = true)
        val m = cmd.minute ?: 0
        if (next != null && next.minute == m && next.hour == h) return Plan.Dismiss(next, true)
        // review-1.2.4 finding 2: "cancel my 7 alarm" (no a.m. or p.m.) at 10:00 matched 19:00, the next, and turned it off
        // unasked while tomorrow's 07:00 may have been meant: the card asks about the one Robot sees.
        if (next != null && next.minute == m && !cmd.exact && next.hour % 12 == h % 12) return Plan.Ask(next)
        return Plan.Dismiss(LocalTime.of(h, m), false)
    }

    /** fix-cancel-alarm, "what alarms do I have": [next], the next alarm Android shows Robot, in words; null, none seen. */
    fun nextLine(next: ZonedDateTime?, now: ZonedDateTime): Phrase {
        next ?: return ToolStrings.alarmNoneSeen()
        val time = EventText.clock(next.hour, next.minute)
        val day = next.withZoneSameInstant(now.zone).toLocalDate()
        val today = now.toLocalDate()
        return when (day) {
            today -> ToolStrings.alarmNextToday("time" to time)
            today.plusDays(1) -> ToolStrings.alarmNextTomorrow("time" to time)
            else -> ToolStrings.alarmNextDay("time" to time, "day" to EventText.dayText(day, today.year))
        }
    }

    /** fix-alarm-moved (phone-train-14-b): the card reads done only once the read-back shows the alarm went. */
    fun cardStatus(gone: Boolean): ActionStatus = if (gone) ActionStatus.DONE else ActionStatus.FAILED

    /**
     * fix-alarm-moved: the lines after [why] once Robot opened your clock's alarms ([opened]) or couldn't. [still], the alarm
     * your clock left on: you finish there, so the line says so.
     */
    fun openLines(why: Phrase, opened: Boolean, still: LocalTime? = null): List<Phrase> = listOf(why, when {
        still == null -> if (opened) ToolStrings.alarmsOpened() else ToolStrings.alarmsInClock()
        opened -> ToolStrings.alarmFinishThere("time" to EventText.clock(still.hour, still.minute))
        else -> ToolStrings.alarmFinishInClock()
    })

    /**
     * fix-batch-15 L2 (review-batch-15): the dismiss read back [steps] times [stepMs] apart, then once more after [lastMs]
     * (a slow clock); true once the next alarm moved on from [before].
     */
    inline fun readBack(before: Long, steps: Int = 8, stepMs: Long = 250, lastMs: Long = 1000, pause: (Long) -> Unit, read: () -> Long?): Boolean {
        for (i in 0 until steps) { pause(stepMs); if (gone(before, read())) return true }
        pause(lastMs)
        return gone(before, read())
    }

    /** After the dismiss: the next alarm Android shows moved on, or none is left, so the one asked about went. */
    fun gone(before: Long, after: Long?): Boolean = after != before
}
