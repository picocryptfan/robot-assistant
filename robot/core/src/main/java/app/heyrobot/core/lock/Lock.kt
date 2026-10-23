// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.TimeWindow
import java.time.DayOfWeek
import java.time.LocalTime

// Pure. Phone lock (1.1, docs/plans/phone-lock-design.md §2, §7): a lock is a rule with a start and an end; while one is
// on, every app that isn't allowed is covered by Robot's cover screen (the :lock process). This file is the model of
// locks.json: Settings › Phone lock's own settings and the locks. The state of a lock that is on (when it started, the
// wait, the code's tries, the record) is LockState, in state.json, which only :lock writes.

/** Who the locks are for (Settings › Phone lock › Who the locks are for). Someone else needs a lock code. */
enum class LockFor(val id: String) {
    ME("me"), SOMEONE_ELSE("someone_else");

    companion object {
        fun of(id: String?): LockFor? = entries.firstOrNull { it.id == id }
    }
}

/** Settings › Phone lock › End early (§5.4). Locks for someone else always need the code, whatever this says. */
enum class EndEarly(val id: String) {
    OFF("off"), WAIT("wait"), CODE("code");

    companion object {
        fun of(id: String?): EndEarly? = entries.firstOrNull { it.id == id }
    }
}

/** One App first's two choices (phase B builds them; the file can hold them already). */
enum class AppMode(val id: String) {
    FIRST("first"), LIMIT("limit");

    companion object {
        fun of(id: String?): AppMode? = entries.firstOrNull { it.id == id }
    }
}

/** What stays open besides the always-open set (§6): switches per lock, all off. Apps are package names, People ids. */
data class LockExtras(
    val people: List<String> = emptyList(),
    val maps: Boolean = false,
    val music: String? = null,
    val apps: List<String> = emptyList(),
) {
    /** The packages this lock lets through by name (the maps app is resolved on the phone, in :lock). */
    val packages: Set<String> get() = (apps + listOfNotNull(music)).toSet()

    companion object {
        val NONE = LockExtras()
    }
}

/** When a lock starts and ends (§2). */
sealed class LockRule {
    /**
     * Time of day: from [window]'s start to its end on [days] (start days: 22:00 to 7:00 on Sunday is Sunday night into
     * Monday morning). The ends follow the wall clock in the phone's zone of the moment, as time routines do.
     */
    data class Time(val window: TimeWindow, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) : LockRule()

    /** After the alarm (phase B): [minutes] after you turn off a Clock alarm that rang within [window] on [days]. */
    data class Alarm(val window: TimeWindow = TimeWindow.ALARM, val minutes: Int = 60, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet()) : LockRule()

    /**
     * One app first (phase B). [FIRST][AppMode.FIRST]: from [start] until [minutes] in [app], at the latest [latestMin]
     * after the start. [LIMIT][AppMode.LIMIT]: once today's screen time reaches [limitMin], only [app] until [until].
     */
    data class App(
        val mode: AppMode,
        val app: String,
        val start: AppStart = AppStart.At(LocalTime.of(16, 0), WEEKDAYS),
        val minutes: Int = 20,
        val latestMin: Int = 180,
        val limitMin: Int = 120,
        val until: LocalTime = LocalTime.of(5, 0),
    ) : LockRule()

    val kind: String get() = when (this) {
        is Time -> KIND_TIME
        is Alarm -> KIND_ALARM
        is App -> KIND_APP
    }

    companion object {
        const val KIND_TIME = "time"
        const val KIND_ALARM = "alarm"
        const val KIND_APP = "app"
        val WEEKDAYS: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/** When a One app first lock starts: a time on days, or when you turn off your alarm. */
sealed class AppStart {
    data class At(val time: LocalTime, val days: Set<DayOfWeek>) : AppStart()
    data object Alarm : AppStart()
}

/** One lock. [id]: "lk_" and a few letters or digits, made when it is added. */
data class PhoneLock(
    val id: String,
    val name: String,
    val rule: LockRule,
    val on: Boolean = true,
    val extras: LockExtras = LockExtras.NONE,
)

/** Settings › Phone lock's own rows (§10), with their defaults. */
data class LockSettings(
    val on: Boolean = false,
    val forWho: LockFor = LockFor.ME,
    val endEarly: EndEarly = EndEarly.OFF,
    val waitMin: Int = 10,
    val warnMin: Int = 5,
    val showLeft: Boolean = true,
) {
    /** End early as it applies: always With the code for someone else. */
    val effectiveEndEarly: EndEarly get() = if (forWho == LockFor.SOMEONE_ELSE) EndEarly.CODE else endEarly
}

/** locks.json: the settings and the locks. */
data class LockFile(val settings: LockSettings = LockSettings(), val locks: List<PhoneLock> = emptyList()) {
    fun lock(id: String): PhoneLock? = locks.firstOrNull { it.id == id }
}

/** The limits a lock is checked against before it is saved or read (§2, §6). */
object LockLimits {
    const val MAX_LOCKS = 10
    const val MAX_NAME = 40
    const val MAX_APPS = 5
    const val MAX_PEOPLE = 20
    const val MAX_PACKAGE = 255
    val WAIT_CHOICES = listOf(5, 10, 15, 30)
    val WARN_CHOICES = listOf(0, 1, 5, 10, 15)
    val ALARM_MINUTES = listOf(15, 30, 45, 60, 90, 120, 180)
    val APP_MINUTES = 5..120
    val LATEST_MINUTES = 30..720
    val LIMIT_MINUTES = 15..720
    private val ID = Regex("lk_[a-z0-9]{4,16}")
    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+")
    private val PERSON = Regex("[A-Za-z0-9_-]{1,64}")

    fun validId(id: String): Boolean = ID.matches(id)
    fun validPackage(p: String): Boolean = p.length <= MAX_PACKAGE && PACKAGE.matches(p)
    fun validPerson(id: String): Boolean = PERSON.matches(id)

    /** The name as saved: trimmed, one line, at most [MAX_NAME] characters; null when it can't be one. */
    fun name(raw: String): String? = raw.trim().takeIf { it.isNotEmpty() && it.length <= MAX_NAME && it.none { c -> c < ' ' || c == ' ' || c == ' ' } }

    /** Why [l] can't be saved, or null. */
    fun problem(l: PhoneLock): LockProblem? {
        if (!validId(l.id)) return LockProblem.ID
        if (name(l.name) != l.name) return LockProblem.NAME
        val e = l.extras
        if (e.apps.size > MAX_APPS || e.apps.toSet().size != e.apps.size || e.apps.any { !validPackage(it) }) return LockProblem.APPS
        if (e.music != null && !validPackage(e.music)) return LockProblem.APPS
        if (e.people.size > MAX_PEOPLE || e.people.any { !PERSON.matches(it) }) return LockProblem.PEOPLE
        return when (val r = l.rule) {
            is LockRule.Time -> when {
                r.window.from == r.window.to -> LockProblem.SAME_TIMES
                r.days.isEmpty() -> LockProblem.NO_DAYS
                !whole(r.window.from) || !whole(r.window.to) -> LockProblem.TIME
                else -> null
            }
            is LockRule.Alarm -> when {
                r.window.from == r.window.to -> LockProblem.SAME_TIMES
                r.days.isEmpty() -> LockProblem.NO_DAYS
                r.minutes !in ALARM_MINUTES -> LockProblem.LENGTH
                else -> null
            }
            is LockRule.App -> when {
                !validPackage(r.app) -> LockProblem.APPS
                r.mode == AppMode.FIRST && (r.minutes !in APP_MINUTES || r.latestMin !in LATEST_MINUTES || r.latestMin < r.minutes) -> LockProblem.LENGTH
                r.mode == AppMode.FIRST && r.start is AppStart.At && r.start.days.isEmpty() -> LockProblem.NO_DAYS
                r.mode == AppMode.LIMIT && r.limitMin !in LIMIT_MINUTES -> LockProblem.LENGTH
                else -> null
            }
        }
    }

    fun problem(s: LockSettings): LockProblem? = when {
        s.waitMin !in WAIT_CHOICES -> LockProblem.LENGTH
        s.warnMin !in WARN_CHOICES -> LockProblem.LENGTH
        else -> null
    }

    private fun whole(t: LocalTime) = t.second == 0 && t.nano == 0
}

/** What keeps a lock from being saved. */
enum class LockProblem { ID, NAME, APPS, PEOPLE, SAME_TIMES, NO_DAYS, TIME, LENGTH }
