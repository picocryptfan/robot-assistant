// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

// Pure. "When I turn off my alarm": GrapheneOS Clock (com.android.deskclock) broadcasts
// ALARM_ALERT when an alarm starts ringing and ALARM_DONE when it stops for any reason (dismissed, snoozed, silenced
// after its auto-silence time), with no permission. A runtime receiver in :voice will hear them (they are implicit and
// not exempt, so a manifest receiver would not); 2 s after DONE it reads AlarmManager.getNextAlarmClock(), and this
// state machine decides from what was observed: a snoozed instance stays Clock's "next alarm" within the hour, a missed
// alarm rang for its whole auto-silence time, anything else was dismissed. The state survives a :voice restart
// (state.json, [AlarmWatch.encode]).

/**
 * 1.2 (every-android-phone design, Step 6): only GrapheneOS Clock's broadcasts are known, so on a phone without it
 * ([clock] false: another maker's Clock, whose broadcasts are UNVERIFIED and not guessed) a start after an alarm never
 * comes. Where you pick one, Robot says so plainly instead of failing silently.
 */
object ClockNeed {
    /** [trigger] waits for an alarm that this phone's Clock won't announce. */
    fun unmet(trigger: RoutineTrigger, clock: Boolean): Boolean = !clock && trigger is RoutineTrigger.AlarmDismissed

    /** One of [routines] that is on, or the brief while it is on ([settings]), waits for such an alarm. */
    fun unmet(routines: List<Routine>, settings: RoutineSettings, clock: Boolean): Boolean =
        (routines + BriefRoutine.of(settings)).any { it.enabled && unmet(it.trigger, clock) }
}

/** How an alarm ended, for the routines and the voice log (141 ALARM_END, a). */
enum class AlarmEnd(override val id: Int, override val title: String) : LogCode {
    DISMISSED(1, "dismissed"), SNOOZED(2, "snoozed"), MISSED(3, "missed (silenced by Clock)"), UNKNOWN(4, "unknown (no alarm was ringing)"),
}

/**
 * What AlarmManager.getNextAlarmClock() said 2 s after ALARM_DONE: when it fires, and whether Clock set it (its show
 * intent's creator package is com.android.deskclock).
 */
data class NextAlarm(val triggerAt: Long, val fromClock: Boolean)

/** 0.8 review: Clock's next alarm as Robot read it (AlarmManager.getNextAlarmClock) at [seenAt]. */
data class SeenAlarm(val triggerAt: Long, val fromClock: Boolean, val seenAt: Long)

/**
 * 0.8 review: Clock's ALARM_ALERT comes with no permission, so any app can send it. One counts only when Clock's own next
 * alarm (its show intent's creator is the Clock app Robot detected), read before the alert and kept here, was due within
 * [WITHIN_MS] of it; anything else is logged and ignored, so it never starts a routine nor uses up its once-a-day run (an
 * ALARM_DONE counts only after a counted ALERT: AlarmWatch).
 */
object AlarmCheck {
    const val WITHIN_MS = 2 * 60_000L
    /** Next alarms kept (a snooze adds one, the next day's another). */
    const val KEEP = 8
    /** Kept this long after they were due. */
    const val KEEP_MS = 24 * 3_600_000L

    /** [seen] with [next] (read at [now]) added, the old ones gone. */
    fun remember(seen: List<SeenAlarm>, next: NextAlarm?, now: Long): List<SeenAlarm> {
        val kept = seen.filter { now - it.triggerAt <= KEEP_MS }
        if (next == null || kept.any { it.triggerAt == next.triggerAt && it.fromClock == next.fromClock }) return kept.takeLast(KEEP)
        return (kept + SeenAlarm(next.triggerAt, next.fromClock, now)).takeLast(KEEP)
    }

    /** An ALARM_ALERT at [alertAt] is Clock's: a Clock alarm Robot saw before it was due within [WITHIN_MS] of it. */
    fun verified(seen: List<SeenAlarm>, alertAt: Long): Boolean =
        seen.any { it.fromClock && it.seenAt <= alertAt && kotlin.math.abs(it.triggerAt - alertAt) <= WITHIN_MS }

    /** [signal] may go to AlarmWatch: an ALERT only when [verified]; a DONE always (it counts only after a counted ALERT). */
    fun admits(seen: List<SeenAlarm>, signal: AlarmSignal): Boolean = signal !is AlarmSignal.Alert || verified(seen, signal.at)
}

/** What :voice observed, with the wall clock in ms. */
sealed class AlarmSignal {
    abstract val at: Long

    /** ALARM_ALERT: an alarm started ringing. */
    data class Alert(override val at: Long) : AlarmSignal()

    /** ALARM_DONE, with the next alarm read [AlarmWatch.READ_DELAY_MS] later (null: none set). */
    data class Done(override val at: Long, val next: NextAlarm?) : AlarmSignal()
}

enum class AlarmPhase { IDLE, RINGING, SNOOZED }

/**
 * [ringingSince]: the current ALERT. [firstAlert]: the first ALERT of this alarm (before any snooze), the alarm's time for
 * the trigger's hours. [snoozedAt]: the DONE that snoozed it.
 */
data class AlarmWatchState(val phase: AlarmPhase = AlarmPhase.IDLE, val ringingSince: Long = 0, val firstAlert: Long = 0, val snoozedAt: Long = 0)

/**
 * What a signal did: the new [state]; [end] when an alarm ended (with [alarmAt], its first ALERT); [prepare] when one
 * starts ringing ("Get ready while the alarm rings": load the voice and fetch the weather, kept 15 min).
 */
data class AlarmStep(val state: AlarmWatchState, val end: AlarmEnd? = null, val alarmAt: Long? = null, val prepare: Boolean = false)

object AlarmWatch {
    /** :voice reads getNextAlarmClock() this long after ALARM_DONE (Clock publishes the snoozed instance first). */
    const val READ_DELAY_MS = 2_000L
    /** A Clock alarm due within this long after DONE is the snoozed one (Clock's snooze is 10 min by default). */
    const val SNOOZE_WINDOW_MS = 60 * 60_000L
    /** An alarm that rang this close to its full auto-silence time was missed, not turned off. */
    const val SILENCE_SLACK_MS = 30_000L
    /** "Get ready" work done on ALERT is kept this long. */
    const val PREPARE_KEEP_MS = 15 * 60_000L
    /** Clock's auto-silence default, and Robot's setting "Clock silences after" (not readable from Clock). */
    const val DEFAULT_SILENCE_MINUTES = 10

    /**
     * How an alarm that rang from [alertAt] and stopped at [doneAt] ended, given the next alarm read just after: rang
     * (almost) its whole [silenceMinutes] → [AlarmEnd.MISSED]; Clock's next alarm within [SNOOZE_WINDOW_MS] of now →
     * [AlarmEnd.SNOOZED] (a real second alarm within the hour reads as a snooze too: the brief then waits for that one);
     * otherwise [AlarmEnd.DISMISSED].
     */
    fun classify(alertAt: Long, doneAt: Long, next: NextAlarm?, silenceMinutes: Int = DEFAULT_SILENCE_MINUTES): AlarmEnd = when {
        doneAt - alertAt >= silenceMinutes * 60_000L - SILENCE_SLACK_MS -> AlarmEnd.MISSED
        next != null && next.fromClock && next.triggerAt >= doneAt && next.triggerAt - doneAt <= SNOOZE_WINDOW_MS -> AlarmEnd.SNOOZED
        else -> AlarmEnd.DISMISSED
    }

    /**
     * The state after [signal]. ALERT starts ringing (a second ALERT while one rings means Clock replaced it: the first was
     * missed); an ALERT within the hour after a snooze continues the same alarm. DONE while ringing is classified
     * ([classify]); DONE with nothing ringing is ignored ([AlarmEnd.UNKNOWN]: another app can send it, and a routine needs
     * ALERT then DONE). Stale state (restored after a long :voice restart) is dropped first ([expire]).
     */
    fun on(state: AlarmWatchState, signal: AlarmSignal, silenceMinutes: Int = DEFAULT_SILENCE_MINUTES): AlarmStep {
        val s = expire(state, signal.at, silenceMinutes)
        return when (signal) {
            is AlarmSignal.Alert -> when (s.phase) {
                AlarmPhase.IDLE -> AlarmStep(AlarmWatchState(AlarmPhase.RINGING, signal.at, signal.at), prepare = true)
                AlarmPhase.SNOOZED -> AlarmStep(AlarmWatchState(AlarmPhase.RINGING, signal.at, s.firstAlert), prepare = true)
                AlarmPhase.RINGING -> AlarmStep(AlarmWatchState(AlarmPhase.RINGING, signal.at, signal.at), AlarmEnd.MISSED, s.firstAlert, prepare = true)
            }
            is AlarmSignal.Done -> when (s.phase) {
                AlarmPhase.RINGING -> when (val end = classify(s.ringingSince, signal.at, signal.next, silenceMinutes)) {
                    AlarmEnd.SNOOZED -> AlarmStep(s.copy(phase = AlarmPhase.SNOOZED, snoozedAt = signal.at), end, s.firstAlert)
                    else -> AlarmStep(AlarmWatchState(), end, s.firstAlert)
                }
                else -> AlarmStep(s, AlarmEnd.UNKNOWN)
            }
        }
    }

    /**
     * 1.0 audit (ipc-4): whether an ALARM_DONE at [now] can mean anything, read before Robot schedules the 2 s read for it:
     * only while an alarm rings ([on] ignores DONE otherwise). Any app can send DONE, so one with nothing ringing is dropped
     * at once, unlogged and with no work scheduled.
     */
    fun awaitsDone(state: AlarmWatchState, now: Long, silenceMinutes: Int = DEFAULT_SILENCE_MINUTES): Boolean =
        expire(state, now, silenceMinutes).phase == AlarmPhase.RINGING

    /**
     * [state] as of [now]: a snooze with no ALERT for over [SNOOZE_WINDOW_MS] is over (dismissed from the notification,
     * which sends nothing: the "first unlock after the alarm" fallback covers it), and ringing far past the auto-silence
     * time means DONE was lost.
     */
    fun expire(state: AlarmWatchState, now: Long, silenceMinutes: Int = DEFAULT_SILENCE_MINUTES): AlarmWatchState = when {
        state.phase == AlarmPhase.SNOOZED && now - state.snoozedAt > SNOOZE_WINDOW_MS -> AlarmWatchState()
        state.phase == AlarmPhase.RINGING && now - state.ringingSince > silenceMinutes * 60_000L + SNOOZE_WINDOW_MS -> AlarmWatchState()
        now < state.ringingSince -> AlarmWatchState()
        else -> state
    }

    /** The alarm that rang at [alarmAt] is within [window] in [zone] (default 4:00–12:00). */
    fun inWindow(alarmAt: Long, zone: ZoneId, window: TimeWindow): Boolean = window.contains(Instant.ofEpochMilli(alarmAt).atZone(zone).toLocalTime())

    fun encode(s: AlarmWatchState): JSONObject = JSONObject().put("phase", s.phase.name.lowercase()).put("ringing", s.ringingSince)
        .put("first", s.firstAlert).put("snoozed", s.snoozedAt)

    /** The saved state, or idle when it can't be read. */
    fun decode(o: JSONObject?): AlarmWatchState = try {
        val phase = AlarmPhase.entries.first { it.name.lowercase() == RoutineCodec.str(o!!, "phase") }
        AlarmWatchState(phase, RoutineCodec.long(o!!, "ringing")!!, RoutineCodec.long(o, "first")!!, RoutineCodec.long(o, "snoozed")!!)
    } catch (_: Exception) { AlarmWatchState() }
}
