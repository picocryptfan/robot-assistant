// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.Locale

// Pure. 1.1 (docs/pm/handoff-dated-reminders.md points 2 and 3): an alarm is set only for a time you said, on the day it
// will ring. On a 1.1 phone "Remind me to turn off [a subscription] on the twentieth of November" set an alarm for 18:00
// that evening: the model made the time up, and Android's set-alarm intent has no date (it rings the next time the clock
// shows that hour). So set_alarm is checked against your own words this turn before it runs.

/**
 * What your own words said this turn ([words], never screen text or a tool's results), read at [now]: the days and clock
 * times in them (WhenParser.find). Null where nothing is checked (a routine's step, a skill, a helper's turn).
 */
class TurnWords(val words: String, val now: ZonedDateTime, private val lang: Lang) {
    /** The days and clock times in [words], read once, and only when an alarm is to be checked. */
    val mentions: List<Mention> by lazy { WhenParser.find(words, now, lang) }

    companion object {
        fun of(words: String, now: ZonedDateTime, lang: Lang): TurnWords = TurnWords(words, now, lang)
    }
}

/** How a set_alarm call fits your words. */
sealed class AlarmFit {
    /** A time you said, on the day it rings. */
    data object Ok : AlarmFit()

    /** No clock time in your words: Robot asks "What time?". */
    data object NoTime : AlarmFit()

    /** A time you said, but not the call's ([said]): back to the model as a bad call. */
    data class OtherTime(val said: Mention) : AlarmFit()

    /**
     * Your words name [day], and the alarm would ring on another: it is never set; the dated reminder is offered instead,
     * at [time] that day (null: all day, with its alert at 9:00).
     */
    data class OtherDay(val day: LocalDate, val time: LocalTime?) : AlarmFit()
}

object AlarmWords {
    /** What the model is told when your words gave no time (Gemma reads "the user", as ActionOutcome.FOR_MODEL). */
    const val NO_TIME = "Not set: the user said no clock time. Ask them what time; never guess one." // l10n: not text (what the model reads)

    /** What the model is told when the call's time isn't one you said. */
    const val OTHER_TIME = "Not set: hour and minute must be the time the user said" // l10n: not text (what the model reads)

    /** What the model is told when the alarm was offered as a calendar reminder instead. */
    const val OFFERED = "Not set as an alarm: an alarm can't ring on another day, so a calendar event with an alert was offered instead." // l10n: not text (what the model reads)

    /**
     * 1.1 (phone, 22 Oct: « Ajoute un film ce soir de 22 h à 1 h » set a 1-minute timer): [heard] asks for an event (core
     * ToolRouter.asksEvent) or names a time range (« de 22 h à 1 h », "from 10 pm to 1 am"). Then a timer or an alarm your
     * words don't name never runs without its card (Turn).
     */
    fun event(heard: TurnWords): Boolean =
        ToolRouter.asksEvent(heard.words) || heard.mentions.any { it.clock && !it.relative && it.span.end != null }

    /** The day an alarm for [h]:[m] rings, set at [now]: today if that time is still to come, else tomorrow. */
    fun rings(h: Int, m: Int, now: ZonedDateTime): LocalDate =
        if (LocalTime.of(h, m).isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)

    /**
     * [a] against [heard]. A day your words name comes first: when it isn't the day the alarm would ring ("on the 20th",
     * "next Friday", "tomorrow at 7" said at 6 a.m.), [AlarmFit.OtherDay]. Then a clock time: none is [AlarmFit.NoTime]; the
     * call must match one you said ("7" is 07:00 or 19:00; "7 pm" only 19:00; "in 20 minutes" the minute it falls on).
     */
    fun check(a: Action.SetAlarm, heard: TurnWords): AlarmFit {
        val clocks = heard.mentions.filter { it.clock || it.relative }
        val matches = clocks.filter { fits(it, a, heard.now) }
        val ring = rings(a.hour, a.minute, heard.now)
        heard.mentions.firstOrNull { it.dated }?.let { d ->
            val day = d.span.start.toLocalDate()
            if (day != ring) {
                val time = if (matches.isNotEmpty()) LocalTime.of(a.hour, a.minute) else clocks.firstOrNull { it.clock }?.time
                return AlarmFit.OtherDay(day, time)
            }
        }
        return when {
            clocks.isEmpty() -> AlarmFit.NoTime
            matches.isNotEmpty() -> AlarmFit.Ok
            else -> AlarmFit.OtherTime(clocks.first())
        }
    }

    private fun fits(m: Mention, a: Action.SetAlarm, now: ZonedDateTime): Boolean {
        if (m.relative) {
            val ring = ZonedDateTime.of(rings(a.hour, a.minute, now), LocalTime.of(a.hour, a.minute), now.zone)
            return Duration.between(m.span.start, ring).abs() <= Duration.ofMinutes(2)
        }
        return m.sameTime(a.hour, a.minute)
    }

    /**
     * The dated reminder offered for [fit] instead of the alarm: an event with an alert on [OtherDay.day], at its time or
     * all day, titled [title]. Its words are ISO (WhenParser reads them exactly).
     */
    fun reminder(fit: AlarmFit.OtherDay, title: String): Action.AddEvent {
        val whenText = fit.time?.let { "${fit.day}T" + String.format(Locale.ROOT, "%02d:%02d", it.hour, it.minute) } ?: fit.day.toString() // l10n: not text (ISO for the parser)
        return Action.AddEvent(title, whenText, alert = true)
    }
}
