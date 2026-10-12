package app.heyrobot.core

import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// Pure (java.time). When routines fire (design §2.1, §2.7): one exact allow-while-idle alarm for the soonest next time
// over all routines (never setAlarmClock, which would become Android's "next alarm" and confuse AlarmWatch), rescheduled
// after every edit, BOOT_COMPLETED, TIME_SET and TIMEZONE_CHANGED; what a late alarm does after Doze or a reboot; and
// a ledger so each trigger runs a routine once. The AlarmManager calls and the receivers are wired in :voice later.

/** A routine due at [slot]: the local date and time it is for (what the alarm's TriggerEvent.Time carries). */
data class Due(val routine: Routine, val slot: LocalDateTime)

/** The next alarm to set: when, and the routines due then (several may share a minute). */
data class NextFire(val at: ZonedDateTime, val due: List<Due>) {
    val routines: List<Routine> get() = due.map { it.routine }
}

/** What a time trigger that comes late does (Doze, the phone off or not yet unlocked after a reboot). Also a voice log code (149). */
enum class MissedRun(override val id: Int, override val title: String) : LogCode {
    /** Within [RoutineSchedule.GRACE] of its time: run as usual. */
    RUN(1, "ran a little late"),
    /** Later the same day: no speech by surprise; a Ready card "Your “Morning” routine didn't run at 7:00 · Run now". */
    OFFER(2, "offered on a Ready card"),
    /** Older: nothing. */
    DROP(3, "too late: dropped"),
}

object RoutineSchedule {
    /**
     * A time trigger this late still runs. Allow-while-idle alarms fire at most once per nine minutes per app in Doze
     * (PUBLISHED), so a few minutes late is normal.
     */
    val GRACE: Duration = Duration.ofMinutes(10)
    /** Missed by more than [GRACE] but less than this, the same day: [MissedRun.OFFER]. */
    val OFFER_LIMIT: Duration = Duration.ofHours(3)

    /**
     * [date] at [time] in [zone], as a clock on the wall shows it: a time skipped by a spring-forward change fires when the
     * clocks jump (2:30 on 2027-03-14 in Toronto is 3:00), and a time that happens twice at a fall-back change is the
     * first of the two (1:30 on 2026-11-01 is 1:30 EDT).
     */
    fun at(date: LocalDate, time: LocalTime, zone: ZoneId): ZonedDateTime {
        val local = LocalDateTime.of(date, time)
        val rules = zone.rules
        if (rules.getValidOffsets(local).isEmpty()) {
            val gap = rules.getTransition(local)
            return ZonedDateTime.ofInstant(gap.instant, zone)
        }
        return ZonedDateTime.ofLocal(local, zone, null).withEarlierOffsetAtOverlap()
    }

    /** [t] fires on [date]: one of its days, or its one date. */
    fun firesOn(t: RoutineTrigger.At, date: LocalDate): Boolean = if (t.once != null) date == t.once else date.dayOfWeek in t.days

    /**
     * The first time strictly after [after] that [t] fires, in [after]'s zone (the zone the phone is in now: after a time
     * zone change, 7:00 is the new place's 7:00). Null: never again (a one-off date has passed, or no days).
     */
    fun next(t: RoutineTrigger.At, after: ZonedDateTime): ZonedDateTime? {
        val start = after.toLocalDate()
        val last = t.once ?: start.plusDays(8)
        var d = start
        while (!d.isAfter(last)) {
            if (firesOn(t, d)) {
                val z = at(d, t.time, after.zone)
                if (z.isAfter(after)) return z
            }
            d = d.plusDays(1)
        }
        return null
    }

    /** The one alarm to set: the soonest next time over the enabled routines with a time trigger, or null. */
    fun soonest(routines: List<Routine>, after: ZonedDateTime): NextFire? {
        val times = routines.filter { it.enabled }.mapNotNull { r ->
            val t = r.trigger as? RoutineTrigger.At ?: return@mapNotNull null
            next(t, after)?.let { Triple(r, it, slot(t, it)) }
        }
        val first = times.minOfOrNull { it.second.toInstant() } ?: return null
        val due = times.filter { it.second.toInstant() == first }
        return NextFire(due.first().second, due.map { Due(it.first, it.third) })
    }

    /** The slot (local date and the trigger's own time) of a fire time [at] that [next] or [lastMissed] gave for [t]. */
    fun slot(t: RoutineTrigger.At, at: ZonedDateTime): LocalDateTime = LocalDateTime.of(at.toLocalDate(), t.time)

    /**
     * The latest time [t] was due in (since, now], or null: after a reboot or a long Doze only the last missed time counts,
     * never a run for each one. Looks back at most 8 days.
     */
    fun lastMissed(t: RoutineTrigger.At, since: ZonedDateTime, now: ZonedDateTime): ZonedDateTime? {
        val from = maxOf(since, now.minusDays(8))
        var d = now.toLocalDate()
        while (!d.isBefore(from.toLocalDate().minusDays(1))) {
            if (firesOn(t, d)) {
                val z = at(d, t.time, now.zone)
                if (z.isAfter(from) && !z.isAfter(now)) return z
            }
            d = d.minusDays(1)
        }
        return null
    }

    /** What to do about a time trigger due at [scheduled] when it can run at [now]. */
    fun missed(scheduled: ZonedDateTime, now: ZonedDateTime): MissedRun {
        val late = Duration.between(scheduled, now)
        return when {
            late <= GRACE -> MissedRun.RUN
            late <= OFFER_LIMIT && now.toLocalDate() == scheduled.withZoneSameInstant(now.zone).toLocalDate() -> MissedRun.OFFER
            else -> MissedRun.DROP
        }
    }
}

// ---------------------------------------------------------------------------------------------- triggers and the ledger

/** Something :voice observed that may start routines. */
sealed class TriggerEvent {
    /** The exact alarm for [slot] (the local date and time it was set for, RoutineSchedule.next) went off. */
    data class Time(val slot: LocalDateTime) : TriggerEvent()
    /** AlarmWatch decided how an alarm that first rang at [alarmAt] ended. */
    data class Alarm(val end: AlarmEnd, val alarmAt: Long) : TriggerEvent()
    /** USER_PRESENT. */
    data object Unlocked : TriggerEvent()
    /** A remembered device ([deviceId]) of [type] connected or disconnected. */
    data class Device(val type: DeviceKind, val deviceId: String, val connected: Boolean) : TriggerEvent()
    /** POWER_CONNECTED or POWER_DISCONNECTED. */
    data class Power(val charging: Boolean) : TriggerEvent()
}

/**
 * A routine to run for one trigger: [key] names that occurrence (the ledger runs it once); [waitUnlock]: say nothing
 * now and run at the first unlock (a missed alarm's brief).
 */
data class Fire(val routine: Routine, val key: String, val waitUnlock: Boolean = false)

data class Skip(val routine: Routine, val why: SkipWhy)

data class Fired(val fire: List<Fire>, val skipped: List<Skip>)

/** The last run of each routine: which occurrence, when, and the day it was in. Kept in `routines/state.json`. */
data class LastRun(val key: String, val at: Long, val day: LocalDate)

data class RunLedger(val last: Map<String, LastRun> = emptyMap()) {
    fun record(routineId: String, key: String, now: ZonedDateTime): RunLedger =
        RunLedger(last + (routineId to LastRun(key, now.toInstant().toEpochMilli(), now.toLocalDate())))

    /** Without routines that no longer exist. */
    fun keep(ids: Set<String>): RunLedger = RunLedger(last.filterKeys { it in ids })
}

object RoutineTriggers {
    /**
     * The routines [event] starts at [now], and those it would start but doesn't (switched off, the same occurrence
     * already ran, already ran today with "at most once a day", outside their hours or days, an alarm outside the
     * trigger's hours). A dismissed alarm runs at once; a missed one runs at the first unlock. Snoozed and unknown endings
     * start nothing.
     */
    fun fired(routines: List<Routine>, event: TriggerEvent, now: ZonedDateTime, ledger: RunLedger): Fired {
        val fire = mutableListOf<Fire>()
        val skipped = mutableListOf<Skip>()
        for (r in routines) {
            val match = match(r.trigger, event, now) ?: continue
            if (match.outsideWindow) { skipped += Skip(r, SkipWhy.OUTSIDE_ALARM_WINDOW); continue }
            val why = skip(r, match.key, now, ledger)
            if (why != null) skipped += Skip(r, why) else fire += Fire(r, match.key, match.waitUnlock)
        }
        return Fired(fire, skipped)
    }

    private class Match(val key: String, val waitUnlock: Boolean = false, val outsideWindow: Boolean = false)

    private fun match(t: RoutineTrigger, e: TriggerEvent, now: ZonedDateTime): Match? = when {
        t is RoutineTrigger.At && e is TriggerEvent.Time ->
            if (e.slot.toLocalTime() == t.time && RoutineSchedule.firesOn(t, e.slot.toLocalDate())) Match(timeKey(e.slot)) else null
        t is RoutineTrigger.AlarmDismissed && e is TriggerEvent.Alarm && (e.end == AlarmEnd.DISMISSED || e.end == AlarmEnd.MISSED) ->
            Match("a:${e.alarmAt}", waitUnlock = e.end == AlarmEnd.MISSED, outsideWindow = !AlarmWatch.inWindow(e.alarmAt, now.zone, t.window))
        t is RoutineTrigger.Unlock && e is TriggerEvent.Unlocked ->
            if (now.toLocalTime() >= t.after) Match("u:${now.toLocalDate()}") else null
        t is RoutineTrigger.Device && e is TriggerEvent.Device ->
            if (t.type == e.type && t.connect == e.connected && (t.deviceId == null || t.deviceId == e.deviceId)) Match("d:${minute(now)}") else null
        t is RoutineTrigger.Charging && e is TriggerEvent.Power ->
            if (t.start == e.charging) Match("c:${minute(now)}") else null
        else -> null
    }

    /**
     * Why [r] doesn't run for occurrence [key] at [now], or null. A time routine's occurrence is its local date and
     * time, so the second 1:30 of a fall-back night, or a catch-up after a reboot for a time that already ran, is the
     * same occurrence. Other triggers also respect the routine's conditions.
     */
    fun skip(r: Routine, key: String, now: ZonedDateTime, ledger: RunLedger): SkipWhy? {
        if (!r.enabled) return SkipWhy.OFF
        val last = ledger.last[r.id]
        if (last?.key == key) return SkipWhy.DUPLICATE
        if (r.trigger is RoutineTrigger.At) return null
        val c = r.conditions
        if (c.between != null && !c.between.contains(now.toLocalTime())) return SkipWhy.QUIET_HOURS
        if (c.days != null && now.dayOfWeek !in c.days) return SkipWhy.QUIET_HOURS
        if (c.oncePerDay && last?.day == now.toLocalDate()) return SkipWhy.ALREADY_RAN
        return null
    }

    /** "t:2026-11-01T01:30": a time trigger's occurrence. */
    fun timeKey(slot: LocalDateTime): String = "t:${slot.toLocalDate()}T${slot.toLocalTime()}"

    private fun minute(now: ZonedDateTime): Long = now.toInstant().toEpochMilli() / 60_000L
}

/** `routines/state.json`: the run ledger and AlarmWatch's state, so both survive a :voice restart. */
object RoutineStateCodec {
    const val VERSION = 1

    fun encode(ledger: RunLedger, alarm: AlarmWatchState): String = JSONObject().put("v", VERSION).put("alarm", AlarmWatch.encode(alarm))
        .put("last", JSONObject().apply {
            for ((id, l) in ledger.last) put(id, JSONObject().put("key", l.key).put("at", l.at).put("day", l.day.toString()))
        }).toString()

    /** What can be read: an unreadable entry is left out (the routine may then run once more), a damaged file is empty. */
    fun decode(json: String?): Pair<RunLedger, AlarmWatchState> = try {
        val o = JSONObject(json ?: return RunLedger() to AlarmWatchState())
        if ((o.opt("v") as? Number)?.toInt() != VERSION) return RunLedger() to AlarmWatchState()
        val last = o.optJSONObject("last")
        val map = buildMap {
            if (last != null) for (id in last.keys()) {
                val l = last.optJSONObject(id) ?: continue
                val key = RoutineCodec.str(l, "key") ?: continue
                val at = RoutineCodec.long(l, "at") ?: continue
                val day = RoutineCodec.date(RoutineCodec.str(l, "day")) ?: continue
                if (RoutineCheck.validId(id)) put(id, LastRun(key, at, day))
            }
        }
        RunLedger(map) to AlarmWatch.decode(o.optJSONObject("alarm"))
    } catch (_: Exception) { RunLedger() to AlarmWatchState() }
}
