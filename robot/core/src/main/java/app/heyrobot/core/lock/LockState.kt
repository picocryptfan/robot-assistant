// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.AlarmWatchState
import app.heyrobot.core.SeenAlarm
import java.time.LocalDate

// Pure. state.json: what :lock knows about locks that are on, and only :lock writes it. Never in a backup.
// The main process reads it (LockStore.readState) to show "On now, opens at 8:00", whether a code is set ([codeSet]),
// and whether an edit session is open ([edit]); :voice reads it for the panel's lock rules.

/**
 * A lock that is on. [lock]: its id in locks.json, or [VOICE] for one started by voice (it lives only here). [start],
 * [end]: wall-clock ms. [endElapsed]: the end on the elapsed-time clock, believed only while [boot] is this boot's
 * count (a length isn't stretched by changing the clock); null for a Time of day lock, whose end follows the wall clock.
 * [usedMs]: One app first's screen time so far (phase B). [waitFrom]: when End early's wait started, or null.
 */
data class ActiveLock(
    val lock: String,
    val start: Long,
    val end: Long,
    val endElapsed: Long? = null,
    val boot: Int? = null,
    val usedMs: Long = 0,
    val waitFrom: Long? = null,
) {
    val voice: Boolean get() = lock == VOICE

    companion object {
        const val VOICE = "voice"
    }
}

/** The lock code's wrong tries: how many in a row, and the wall-clock time the next try is allowed (LockCodeRules). */
data class CodeTries(val fails: Int = 0, val nextTry: Long = 0)

/** What the record says (§3, "What happened"). Never a code, never an app's name: ids and numbers only. */
enum class RecordEvent(val id: String) {
    STARTED("start"),
    ENDED("end"),
    ENDED_CODE("end_code"),
    ENDED_WAIT("end_wait"),
    /** Phase B: One app first's time in its app was met ([RecordLine.n]: the minutes). */
    ENDED_APP("end_app"),
    SKIPPED("skip"),
    ACCESS_OFF("access_off"),
    ACCESS_ON("access_on"),
    GAP("gap"),
    RESTARTED("restarted"),
    CAR("car"),
    WRONG_CODES("wrong_codes"),
    KEY_LOST("key_lost"),
    COULD_NOT_START("not_started"),
    /** A lock by voice made longer by a second one ([RecordLine.n]: the minutes from then to its new end). */
    LONGER("longer");

    companion object {
        fun of(id: String?): RecordEvent? = entries.firstOrNull { it.id == id }
    }
}

/** One line of the record: when, what, which lock (an id or [ActiveLock.VOICE]), and a number (minutes, tries). */
data class RecordLine(val t: Long, val e: RecordEvent, val lock: String? = null, val n: Int? = null)

/**
 * Phase B: an alarm Clock silenced while an After the alarm lock waited for it (§2): the lock starts at the first unlock
 * within [LockSchedule.MISSED_WAIT] of [doneAt]. [alarmAt]: the alarm's first ring, which picks the locks (window, day).
 */
data class MissedAlarm(val alarmAt: Long, val doneAt: Long)

/** When :lock was last seen alive during a lock, and when the phone was last shut down (a restart's gap, §3). */
data class LastSeen(val alive: Long = 0, val shutdown: Long = 0)

data class LockState(
    val active: List<ActiveLock> = emptyList(),
    /** Time of day occurrences that won't come on: the lock's id → the date its occurrence starts (skipped, or ended early). */
    val skip: Map<String, LocalDate> = emptyMap(),
    /** The edit session's end (wall-clock ms; 0: none): Settings › Phone lock may change things without the code until then. */
    val edit: Long = 0,
    /** A lock code is set (only :lock reads code.json; this is how the other processes know). */
    val codeSet: Boolean = false,
    val code: CodeTries = CodeTries(),
    val alarm: AlarmWatchState = AlarmWatchState(),
    /** Phase B: Clock's next alarms as :lock read them before each ring (AlarmCheck), so a spoofed ALARM_ALERT is ignored. */
    val seen: List<SeenAlarm> = emptyList(),
    /** Phase B: a missed alarm waiting for the unlock, or null. */
    val missed: MissedAlarm? = null,
    /**
     * Phase B (§9.3): since when Robot's car mode, started by your remembered car, is on (wall-clock ms; 0: off). Kept here
     * so a restart of :lock while you drive never puts the cover over a map.
     */
    val car: Long = 0,
    val last: LastSeen = LastSeen(),
    val record: List<RecordLine> = emptyList(),
) {
    fun editOpen(now: Long): Boolean = now < edit

    /** The car's car mode is on at [now] (for 12 hours at most, should its end never come). */
    fun carOn(now: Long): Boolean = car in 1..now && now - car < CAR_MAX_MS

    /** With [line] added, and lines older than 30 days or beyond the newest [RECORD_MAX] left out. */
    fun recording(vararg line: RecordLine): LockState {
        if (line.isEmpty()) return this
        val newest = (record + line).maxOf { it.t }
        return copy(record = (record + line).filter { it.t > newest - RECORD_DAYS_MS }.takeLast(RECORD_MAX))
    }

    companion object {
        const val RECORD_MAX = 200
        const val CAR_MAX_MS = 12L * 3600 * 1000
        const val RECORD_DAYS_MS = 30L * 24 * 3600 * 1000
    }
}
