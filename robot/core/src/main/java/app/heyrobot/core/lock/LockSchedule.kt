// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.RoutineSchedule
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime

// Pure (java.time). Which locks are on now and until when, worked out from stored facts on every window event, at every
// exact alarm and after every restart: the Time of day windows in locks.json, the actives in
// state.json (a lock started by voice, phase B's alarm and app locks), the occurrences skipped or ended early. A late
// alarm can only make a warning late, never a lock wrong. Rules for every lock: no lock lasts more than [CAP] from its
// start (a bug guard); overlapping locks leave open only what all of them allow; ending early ends every active lock.

/** One Time of day window of [lock]: from [start] to [end] (capped at [LockSchedule.CAP]). [date]: its start day. */
data class Occurrence(val lock: PhoneLock, val start: ZonedDateTime, val end: ZonedDateTime) {
    val date: LocalDate get() = start.toLocalDate()
}

/** The clocks :lock reads when it refreshes: the wall clock in the phone's zone, the elapsed-time clock, the boot count. */
data class Clocks(val now: ZonedDateTime, val elapsed: Long, val boot: Int?) {
    val nowMs: Long get() = now.toInstant().toEpochMilli()
}

/** A refresh's result: the new state, and the locks that came on and went off with it (for the record and the notices). */
data class Refreshed(val state: LockState, val started: List<ActiveLock>, val ended: List<ActiveLock>)

object LockSchedule {
    /** No lock lasts longer than this from its start, whatever its settings: the phone can never stay locked. */
    val CAP: Duration = Duration.ofHours(24)
    /** A lock started by voice lasts this long at most. */
    val VOICE_MAX: Duration = Duration.ofHours(12)
    /** The accessibility service asks for window events from this long before a lock starts (§0.5). */
    val WAKE_LEAD: Duration = Duration.ofMinutes(10)

    /** The locks that may come on: none while Phone lock is off or locks.json can't be read. */
    fun usable(read: LockFileRead?): LockFile? = read?.ok?.takeIf { it.settings.on }

    // ------------------------------------------------------------------------------------------------ Time of day

    /** The occurrence of [l] that starts on [date], or null ([date] isn't one of its days, or it isn't a Time of day lock). */
    fun occurrenceOn(l: PhoneLock, date: LocalDate, now: ZonedDateTime): Occurrence? {
        when (val r = l.rule) {
            is LockRule.Time -> {
                if (date.dayOfWeek !in r.days || r.window.from == r.window.to) return null
                val start = RoutineSchedule.at(date, r.window.from, now.zone)
                val endDate = if (r.window.from < r.window.to) date else date.plusDays(1)
                val end = minOf(RoutineSchedule.at(endDate, r.window.to, now.zone), start.plus(CAP))
                return if (end.isAfter(start)) Occurrence(l, start, end) else null
            }
            // Phase B: One app first at a time, from that time to its latest end (its time in the app ends it sooner).
            is LockRule.App -> {
                val at = r.start as? AppStart.At ?: return null
                if (r.mode != AppMode.FIRST || date.dayOfWeek !in at.days) return null
                val start = RoutineSchedule.at(date, at.time, now.zone)
                return Occurrence(l, start, start.plus(Duration.ofMinutes(r.latestMin.toLong()).coerceAtMost(CAP)))
            }
            is LockRule.Alarm -> return null
        }
    }

    /** Locks with a start time of their own: Time of day, and One app first at a time (phase B). */
    fun scheduled(r: LockRule): Boolean = r is LockRule.Time || (r is LockRule.App && r.mode == AppMode.FIRST && r.start is AppStart.At)

    /** The occurrence of [l] that [now] is in (today's, or one that started yesterday and runs past midnight). */
    fun occurrenceAt(l: PhoneLock, now: ZonedDateTime): Occurrence? {
        val today = now.toLocalDate()
        for (d in listOf(today, today.minusDays(1))) {
            val o = occurrenceOn(l, d, now) ?: continue
            if (!now.isBefore(o.start) && now.isBefore(o.end)) return o
        }
        return null
    }

    /** The first occurrence of [l] that starts after [after] and isn't skipped in [state], within 8 days. */
    fun nextOccurrence(l: PhoneLock, after: ZonedDateTime, state: LockState = LockState()): Occurrence? {
        var d = after.toLocalDate()
        val last = d.plusDays(8)
        while (!d.isAfter(last)) {
            val o = occurrenceOn(l, d, after)
            if (o != null && o.start.isAfter(after) && state.skip[l.id] != o.date) return o
            d = d.plusDays(1)
        }
        return null
    }

    private fun timeLocks(f: LockFile?): List<PhoneLock> = f?.locks.orEmpty().filter { it.on && scheduled(it.rule) }

    // ------------------------------------------------------------------------------------------------ active now

    /** Whether [a] is over at [c]: its elapsed-time end when it is from this boot, else its wall-clock end, and the cap. */
    fun over(a: ActiveLock, c: Clocks): Boolean {
        if (c.nowMs >= a.start + CAP.toMillis()) return true
        if (a.endElapsed != null && a.boot != null && a.boot == c.boot) return c.elapsed >= a.endElapsed
        return c.nowMs >= a.end
    }

    /** [a]'s end as a time on the wall clock now (an elapsed-time end is counted from now). */
    fun endOf(a: ActiveLock, c: Clocks): ZonedDateTime {
        val ms = if (a.endElapsed != null && a.boot != null && a.boot == c.boot) c.nowMs + (a.endElapsed - c.elapsed).coerceAtLeast(0) else a.end
        val capped = minOf(ms, a.start + CAP.toMillis())
        return Instant.ofEpochMilli(capped).atZone(c.now.zone)
    }

    /**
     * The state as it is at [c]: Time of day locks whose window is open (and not skipped) are on, with their window's end;
     * actives that are over, whose lock was deleted or turned off, or that started more than [CAP] ago are off; with
     * Phone lock off or locks.json unreadable ([file] null), every lock is off. A carried-over lock keeps its wait.
     */
    fun refresh(file: LockFile?, state: LockState, c: Clocks): Refreshed {
        val kept = mutableListOf<ActiveLock>()
        val ended = mutableListOf<ActiveLock>()
        val started = mutableListOf<ActiveLock>()
        val byId = state.active.associateBy { it.lock }
        val due = if (file == null) emptyMap() else timeLocks(file).mapNotNull { l ->
            occurrenceAt(l, c.now)?.takeIf { state.skip[l.id] != it.date }?.let { l.id to it }
        }.toMap()
        for (a in state.active) {
            val lock = if (a.voice) null else file?.lock(a.lock)
            val stillOn = when {
                file == null -> false
                a.voice -> !over(a, c)
                lock == null || !lock.on -> false
                scheduled(lock.rule) -> a.lock in due
                else -> !over(a, c)
            }
            if (!stillOn) { ended += a; continue }
            val o = due[a.lock]
            kept += if (o != null) a.copy(start = ms(o.start), end = ms(o.end), endElapsed = null, boot = null) else a
        }
        for ((id, o) in due) if (id !in byId) {
            val a = ActiveLock(id, ms(o.start), ms(o.end))
            kept += a
            started += a
        }
        val today = c.now.toLocalDate()
        val skip = state.skip.filterValues { !it.isBefore(today.minusDays(2)) }
        return Refreshed(state.copy(active = kept, skip = skip), started, ended)
    }

    /**
     * A lock started by voice (phase B's LockRouter sends its end): null when Phone lock is off, a lock for someone else is
     * on ([someoneElseOn]: only the code changes it), the end isn't after now, or it is more than [VOICE_MAX] away. Its
     * length is kept on the elapsed-time clock. A second one never ends the one on sooner: when that one already lasts
     * until [end] or later, the state stays as it is; otherwise its end moves to [end], and it keeps its start, its wait
     * and the [CAP] counted from that start.
     */
    fun startVoice(file: LockFile?, state: LockState, c: Clocks, end: ZonedDateTime): LockState? {
        if (file == null || someoneElseOn(file, state, c)) return null
        val len = Duration.between(c.now, end)
        if (len.isNegative || len.isZero || len > VOICE_MAX) return null
        val on = voiceOn(state, c)
        if (on != null && !endOf(on, c).isBefore(end)) return state
        val a = on?.copy(end = ms(end), endElapsed = c.elapsed + len.toMillis(), boot = c.boot)
            ?: ActiveLock(ActiveLock.VOICE, c.nowMs, ms(end), c.elapsed + len.toMillis(), c.boot)
        return state.copy(active = state.active.filterNot { it.voice } + a)
    }

    /** The lock by voice that is on at [c] (one that is over doesn't count), or null. */
    fun voiceOn(state: LockState, c: Clocks): ActiveLock? = state.active.firstOrNull { it.voice && !over(it, c) }

    /** When the lock by voice that is on ends, or null when none is on (the panel says so instead of a shorter card). */
    fun voiceEnd(state: LockState, c: Clocks): ZonedDateTime? = voiceOn(state, c)?.let { endOf(it, c) }

    /** A lock for someone else is on at [c]: then only the lock code ends or changes it, and no lock by voice starts. */
    fun someoneElseOn(file: LockFile?, state: LockState, c: Clocks): Boolean =
        file?.settings?.forWho == LockFor.SOMEONE_ELSE && state.active.any { !over(it, c) }

    /**
     * Every active lock ended early (§5.4): they go, and each Time of day occurrence among them is marked so the next
     * refresh doesn't put it back on.
     */
    fun endAll(state: LockState, c: Clocks): LockState {
        val skip = state.skip.toMutableMap()
        for (a in state.active) if (!a.voice) skip[a.lock] = Instant.ofEpochMilli(a.start).atZone(c.now.zone).toLocalDate()
        return state.copy(active = emptyList(), skip = skip)
    }

    /** [l]'s next occurrence skipped ("Skip this time" on its warning). Unchanged when it has none. */
    fun skipNext(l: PhoneLock, state: LockState, now: ZonedDateTime): LockState {
        val o = nextOccurrence(l, now, state) ?: return state
        return state.copy(skip = state.skip + (l.id to o.date))
    }

    // ------------------------------------------------------------------------------------------------ what's next

    /**
     * When the phone opens: the latest end among the active locks, carried on by any Time of day lock that starts before
     * then and ends later (overlaps chain). Null when no lock is on.
     */
    fun opensAt(file: LockFile?, state: LockState, c: Clocks): ZonedDateTime? {
        if (state.active.isEmpty()) return null
        var end = state.active.maxOf { endOf(it, c) }
        val cap = c.now.plus(CAP)
        repeat(LockLimits.MAX_LOCKS * 3) {
            val later = timeLocks(file).mapNotNull { l ->
                nextOccurrence(l, c.now.minusSeconds(1), state)?.takeIf { !it.start.isAfter(end) && it.end.isAfter(end) }
            }.maxOfOrNull { it.end } ?: return minOf(end, cap)
            end = later
        }
        return minOf(end, cap)
    }

    /** The next lock to come on (for "The next one is Bedtime, at 22:00"), or null. */
    fun nextLock(file: LockFile?, state: LockState, now: ZonedDateTime): Occurrence? =
        timeLocks(file).mapNotNull { nextOccurrence(it, now, state) }.minByOrNull { it.start.toInstant() }

    /** The lock whose warning is due at [now] (within [LockSettings.warnMin] before its start), or null (warnings off). */
    fun warning(file: LockFile?, state: LockState, now: ZonedDateTime): Occurrence? {
        val m = file?.settings?.warnMin ?: return null
        if (m <= 0) return null
        return timeLocks(file).mapNotNull { nextOccurrence(it, now, state) }
            .filter { !now.isBefore(it.start.minusMinutes(m.toLong())) && state.active.none { a -> a.lock == it.lock.id } }
            .minByOrNull { it.start.toInstant() }
    }

    /** The service wants window events: a lock is on, or one starts within [WAKE_LEAD]. */
    fun wantsEvents(file: LockFile?, state: LockState, c: Clocks): Boolean =
        state.active.isNotEmpty() || nextLock(file, state, c.now)?.let { !c.now.plus(WAKE_LEAD).isBefore(it.start) } == true

    /**
     * The one exact alarm (§8.1): the soonest of every lock's next warning, wake-up ([WAKE_LEAD] before) and start, every
     * active lock's end, the end of a wait and of its confirm window, and the edit session's end. Null: nothing to wait for.
     */
    fun nextAlarm(file: LockFile?, state: LockState, c: Clocks): ZonedDateTime? {
        val times = mutableListOf<ZonedDateTime>()
        val warn = file?.settings?.warnMin ?: 0
        for (l in timeLocks(file)) {
            val o = nextOccurrence(l, c.now, state) ?: continue
            times += o.start
            times += o.start.minus(WAKE_LEAD)
            if (warn > 0) times += o.start.minusMinutes(warn.toLong())
        }
        for (a in state.active) {
            times += endOf(a, c)
            val rule = EndEarlyRules.waitMinutes(file?.settings ?: LockSettings(), a)
            a.waitFrom?.let { w ->
                val ready = w + rule * 60_000L
                times += Instant.ofEpochMilli(ready).atZone(c.now.zone)
                times += Instant.ofEpochMilli(ready + EndEarlyRules.CONFIRM.toMillis()).atZone(c.now.zone)
            }
        }
        if (state.edit > 0) times += Instant.ofEpochMilli(state.edit).atZone(c.now.zone)
        return times.filter { it.isAfter(c.now) }.minByOrNull { it.toInstant() }
    }

    /**
     * What the active locks let through by name, besides the always-open set: only what all of them allow (§2,
     * overlaps). A lock started by voice allows no extras. [mapsApp]: the app that handles geo: on this phone, open for
     * the locks with Maps on (phase B). 1.2: never one of [LockGate.NEVER_OPEN], so the panel doesn't offer what the gate
     * covers, and One app first doesn't count time in it.
     */
    fun allowedPackages(file: LockFile?, state: LockState, mapsApp: String? = null): Set<String> {
        if (state.active.isEmpty()) return emptySet()
        return state.active.map { a ->
            val l = if (a.voice) null else file?.lock(a.lock)
            val app = (l?.rule as? LockRule.App)?.app
            l?.extras?.packages.orEmpty() + listOfNotNull(app, mapsApp?.takeIf { l?.extras?.maps == true })
        }.reduce { acc, s -> acc intersect s } - LockGate.NEVER_OPEN
    }

    /** Phase B: the People every active lock lets you call (none for a lock started by voice), by People id. */
    fun allowedPeople(file: LockFile?, state: LockState): Set<String> {
        if (state.active.isEmpty()) return emptySet()
        return state.active.map { a -> (if (a.voice) null else file?.lock(a.lock))?.extras?.people.orEmpty().toSet() }.reduce { acc, s -> acc intersect s }
    }

    /** Phase B: every active lock has Maps on (the panel may then show places and routes). */
    fun mapsAllowed(file: LockFile?, state: LockState): Boolean =
        state.active.isNotEmpty() && state.active.all { a -> !a.voice && file?.lock(a.lock)?.extras?.maps == true }

    // ------------------------------------------------------------------------------------------------ phase B: the alarm

    /** A missed alarm's lock starts at the first unlock within this long of the alarm going quiet (§2). */
    val MISSED_WAIT: Duration = Duration.ofHours(3)

    /** The locks that start on Clock's alarm: After the alarm, and One app first "when I turn off my alarm". */
    fun alarmLocks(f: LockFile?): List<PhoneLock> = f?.locks.orEmpty().filter { l ->
        l.on && (l.rule is LockRule.Alarm || (l.rule is LockRule.App && l.rule.mode == AppMode.FIRST && l.rule.start == AppStart.Alarm))
    }

    /** :lock listens to Clock's alarm only while one of these may start. */
    fun wantsClock(f: LockFile?): Boolean = alarmLocks(f).isNotEmpty()

    /** [l] starts for an alarm that first rang at [alarmAt]: within its window, on one of its days (One app first: 4:00–12:00, every day). */
    fun startsFor(l: PhoneLock, alarmAt: Long, zone: java.time.ZoneId): Boolean {
        val at = Instant.ofEpochMilli(alarmAt).atZone(zone)
        return when (val r = l.rule) {
            is LockRule.Alarm -> at.dayOfWeek in r.days && r.window.contains(at.toLocalTime())
            is LockRule.App -> r.start == AppStart.Alarm && app.heyrobot.core.TimeWindow.ALARM.contains(at.toLocalTime())
            is LockRule.Time -> false
        }
    }

    /** How long [l] lasts once its alarm starts it: After the alarm's length, One app first's latest end. */
    private fun alarmLength(l: PhoneLock): Duration = when (val r = l.rule) {
        is LockRule.Alarm -> Duration.ofMinutes(r.minutes.toLong())
        is LockRule.App -> Duration.ofMinutes(r.latestMin.toLong())
        is LockRule.Time -> Duration.ZERO
    }.coerceAtMost(CAP)

    /** The locks the alarm that rang at [alarmAt] starts now ([c]), each for its length on the elapsed-time clock. */
    fun startOnAlarm(file: LockFile?, state: LockState, c: Clocks, alarmAt: Long): Refreshed {
        val started = alarmLocks(file).filter { l -> startsFor(l, alarmAt, c.now.zone) && state.active.none { it.lock == l.id } }.map { l ->
            val len = alarmLength(l).toMillis()
            ActiveLock(l.id, c.nowMs, c.nowMs + len, c.elapsed + len, c.boot)
        }
        return Refreshed(state.copy(active = state.active + started), started, emptyList())
    }

    /**
     * Clock's alarm ended ([end], from AlarmWatch, [alarmAt] its first ring): turned off, its locks start now; silenced by
     * Clock (missed), they wait for the first unlock within [MISSED_WAIT]; a snooze starts nothing.
     */
    fun onAlarmEnd(file: LockFile?, state: LockState, c: Clocks, end: app.heyrobot.core.AlarmEnd, alarmAt: Long): Refreshed = when (end) {
        app.heyrobot.core.AlarmEnd.DISMISSED -> startOnAlarm(file, state.copy(missed = null), c, alarmAt)
        app.heyrobot.core.AlarmEnd.MISSED ->
            if (alarmLocks(file).any { startsFor(it, alarmAt, c.now.zone) }) Refreshed(state.copy(missed = MissedAlarm(alarmAt, c.nowMs)), emptyList(), emptyList())
            else Refreshed(state, emptyList(), emptyList())
        else -> Refreshed(state, emptyList(), emptyList())
    }

    /** The first unlock after a missed alarm: its locks start now, if it went quiet less than [MISSED_WAIT] ago. */
    fun onUnlock(file: LockFile?, state: LockState, c: Clocks): Refreshed {
        val m = state.missed ?: return Refreshed(state, emptyList(), emptyList())
        val cleared = state.copy(missed = null)
        if (c.nowMs < m.doneAt || c.nowMs - m.doneAt > MISSED_WAIT.toMillis()) return Refreshed(cleared, emptyList(), emptyList())
        return startOnAlarm(file, cleared, c, m.alarmAt)
    }

    // ------------------------------------------------------------------------------------------------ phase B: screen time

    /** The active One app first locks, with their rules. */
    fun appLocks(file: LockFile?, state: LockState): List<Pair<ActiveLock, LockRule.App>> = state.active.mapNotNull { a ->
        val r = (if (a.voice) null else file?.lock(a.lock))?.rule as? LockRule.App
        if (r != null && r.mode == AppMode.FIRST) a to r else null
    }

    /**
     * One app first's count (§2): [used] is each active app lock's time in its app since it started (ScreenTime.count),
     * by lock id. Each count is kept in usedMs; a lock whose time is met ends, and its occurrence is marked so the next
     * refresh doesn't put it back on. A count never goes down (usage events are kept only a few days).
     */
    fun countApps(file: LockFile?, state: LockState, used: Map<String, Long>, c: Clocks): Refreshed {
        val ended = mutableListOf<ActiveLock>()
        val rules = appLocks(file, state).associate { (a, r) -> a.lock to r }
        var skip = state.skip
        val kept = state.active.mapNotNull { a ->
            val r = rules[a.lock] ?: return@mapNotNull a
            val u = maxOf(a.usedMs, used[a.lock] ?: 0)
            val next = a.copy(usedMs = u)
            if (u >= r.minutes * 60_000L) {
                ended += next
                skip = skip + (a.lock to Instant.ofEpochMilli(a.start).atZone(c.now.zone).toLocalDate())
                null
            } else next
        }
        return Refreshed(state.copy(active = kept, skip = skip), emptyList(), ended)
    }

    /** The time still needed in its app by [a] ([r]'s minutes less its count), never below zero. */
    fun appLeftMs(a: ActiveLock, r: LockRule.App): Long = (r.minutes * 60_000L - a.usedMs).coerceAtLeast(0)

    /** Daily limit locks that are on and haven't come on today. */
    fun limitsArmed(file: LockFile?, state: LockState, now: ZonedDateTime): List<Pair<PhoneLock, LockRule.App>> = file?.locks.orEmpty().mapNotNull { l ->
        val r = l.rule as? LockRule.App
        if (l.on && r != null && r.mode == AppMode.LIMIT && state.skip[l.id] != now.toLocalDate() && state.active.none { it.lock == l.id }) l to r else null
    }

    /**
     * Daily limit (§2): once today's screen time ([today] ms, screen on and unlocked since 00:00) reaches a lock's amount,
     * it comes on until its next "until" time (5:00 by default), and is marked as done for today.
     */
    fun startLimits(file: LockFile?, state: LockState, c: Clocks, today: Long): Refreshed {
        val due = limitsArmed(file, state, c.now).filter { (_, r) -> today >= r.limitMin * 60_000L }
        if (due.isEmpty()) return Refreshed(state, emptyList(), emptyList())
        val started = due.map { (l, r) ->
            val d = c.now.toLocalDate()
            var until = RoutineSchedule.at(d, r.until, c.now.zone)
            if (!until.isAfter(c.now)) until = RoutineSchedule.at(d.plusDays(1), r.until, c.now.zone)
            ActiveLock(l.id, c.nowMs, minOf(ms(until), c.nowMs + CAP.toMillis()))
        }
        val skip = state.skip + due.associate { (l, _) -> l.id to c.now.toLocalDate() }
        return Refreshed(state.copy(active = state.active + started, skip = skip), started, emptyList())
    }

    /** How much screen time is left today before the first Daily limit comes on (null: none armed). */
    fun limitLeftMs(file: LockFile?, state: LockState, now: ZonedDateTime, today: Long): Long? =
        limitsArmed(file, state, now).minOfOrNull { (_, r) -> (r.limitMin * 60_000L - today).coerceAtLeast(0) }

    // ------------------------------------------------------------------------------------------------ phase B: the brief

    /**
     * The morning brief's "Your phone opens at 8:00" (§9.2), worked out from locks.json and the alarm that just ended
     * ([alarmJustEnded], its first ring), so a brief that starts on the same ALARM_DONE is right before :lock has written
     * anything. One app first locks end on time spent in their app, not at a time, so they don't count here. Null: no lock
     * opens within [BRIEF_AHEAD].
     */
    fun briefOpensAt(file: LockFile?, state: LockState, c: Clocks, alarmJustEnded: Long?): ZonedDateTime? {
        val st = if (alarmJustEnded != null) startOnAlarm(file, state, c, alarmJustEnded).state else state
        return timedOpensAt(file, st, c)?.takeIf { it.isAfter(c.now) && !it.isAfter(c.now.plus(BRIEF_AHEAD)) }
    }

    /**
     * [opensAt] without the One app first locks, which end on time spent in their app (the cover and the notice show their
     * progress instead, "8 of 20 minutes in Libby done"). Null when only those are on.
     */
    fun timedOpensAt(file: LockFile?, state: LockState, c: Clocks): ZonedDateTime? {
        val apps = appLocks(file, state).map { it.first.lock }.toSet()
        return opensAt(file, state.copy(active = state.active.filter { it.lock !in apps }), c)
    }

    val BRIEF_AHEAD: Duration = Duration.ofHours(12)

    private fun ms(z: ZonedDateTime): Long = z.toInstant().toEpochMilli()
}
