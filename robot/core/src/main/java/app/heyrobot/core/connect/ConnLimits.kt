// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.Skill

// Pure. Rate limits (connections plan D16, C2): a connection takes at most its requests a minute (30 unless you set
// another, 1 to 60) and 500 a day; a skill runs at most its `limits.per_hour` (12 unless it says, 1 to 60). Kept in
// skills_state.json (SkillStateFile's "limits"), :voice's own file: the times of recent calls and runs by id, nothing
// else (counts and times only; never backed up, logged or sent). Bounded: only the last day's calls (at most 500 a
// connection) and the last hour's runs (at most 60 a skill) are kept, for ids that still exist. A time after now (the
// clock went back) is dropped rather than counted, so a clock change never locks you out.

/** Recent call times by connection id, and recent run times by skill id (wall-clock ms, oldest first). */
data class LimitState(val calls: Map<String, List<Long>> = emptyMap(), val runs: Map<String, List<Long>> = emptyMap()) {
    val isEmpty: Boolean get() = calls.isEmpty() && runs.isEmpty()
}

object ConnLimits {
    const val MAX_PER_MINUTE = 60
    /** D16 (EST): a connection's requests a day. */
    const val PER_DAY = 500
    const val MAX_PER_HOUR = 60
    const val MINUTE_MS = 60_000L
    const val HOUR_MS = 3_600_000L
    const val DAY_MS = 86_400_000L
    /** At most this many ids of each kind are kept (more connections or skills than Robot holds). */
    const val MAX_IDS = 128

    /** Why [c] can't make a request at [now] (its minute's or its day's limit), or null when it can. */
    fun connection(s: LimitState, c: Connection, now: Long): ConnFailure? {
        val times = s.calls[c.id].orEmpty()
        if (within(times, now, MINUTE_MS) >= c.perMinute) return ConnFailure.LIMIT_MINUTE
        if (within(times, now, DAY_MS) >= PER_DAY) return ConnFailure.LIMIT_DAY
        return null
    }

    /** [s] with a request of connection [id] at [now]. */
    fun recordCall(s: LimitState, id: String, now: Long): LimitState =
        s.copy(calls = s.calls + (id to recent(s.calls[id].orEmpty() + now, now, DAY_MS, PER_DAY)))

    /** Skill [k] may run at [now]: fewer than its runs an hour in the last hour. */
    fun skill(s: LimitState, k: Skill, now: Long): Boolean = within(s.runs[k.id].orEmpty(), now, HOUR_MS) < k.runsPerHour

    /** [s] with a run of skill [id] at [now]. */
    fun recordRun(s: LimitState, id: String, now: Long): LimitState =
        s.copy(runs = s.runs + (id to recent(s.runs[id].orEmpty() + now, now, HOUR_MS, MAX_PER_HOUR)))

    /** [s] keeping only [connections]' and [skills]' recent times (a removed connection's or a deleted skill's go). */
    fun prune(s: LimitState, now: Long, connections: Set<String>, skills: Set<String>): LimitState = LimitState(
        s.calls.filterKeys { it in connections }.mapValues { recent(it.value, now, DAY_MS, PER_DAY) }.filterValues { it.isNotEmpty() },
        s.runs.filterKeys { it in skills }.mapValues { recent(it.value, now, HOUR_MS, MAX_PER_HOUR) }.filterValues { it.isNotEmpty() },
    )

    /** How many of [times] fall in the [window] ms up to [now] (none after now). */
    fun within(times: List<Long>, now: Long, window: Long): Int = times.count { it in (now - window + 1)..now }

    /** The [max] newest of [times] in the [window] up to [now], oldest first. */
    fun recent(times: List<Long>, now: Long, window: Long, max: Int): List<Long> =
        times.filter { it in (now - window + 1)..now }.sorted().takeLast(max)
}
