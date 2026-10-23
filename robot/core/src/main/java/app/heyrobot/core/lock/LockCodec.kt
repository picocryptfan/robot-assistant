// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.AlarmPhase
import app.heyrobot.core.AlarmWatchState
import app.heyrobot.core.SeenAlarm
import app.heyrobot.core.StrictJson
import app.heyrobot.core.TimeWindow
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

// Pure (org.json to write, StrictJson to read). locks.json and state.json, schema 1: strict JSON, a "v"
// field, and unknown fields refused. A locks.json that can't be read whole is Damaged: every lock then counts as off
// (Settings says so, and keeps the file aside as locks.json.bad before writing a new one), because a lock read in part
// would be a rule you never saved. A state.json that can't be read is an empty state.

/** What locks.json holds. */
sealed class LockFileRead {
    data class Ok(val file: LockFile) : LockFileRead()
    data class TooNew(val version: Int) : LockFileRead()
    data object Damaged : LockFileRead()

    /** The file when it could be read, else null (every lock off). */
    val ok: LockFile? get() = (this as? Ok)?.file
}

object LockCodec {
    const val VERSION = 1
    const val STATE_VERSION = 1
    /** locks.json is small: ten locks. Anything bigger wasn't written by Robot. */
    const val MAX_BYTES = 64 * 1024
    const val MAX_STATE_BYTES = 256 * 1024

    // ------------------------------------------------------------------------------------------------ locks.json

    fun encode(f: LockFile): String {
        val s = f.settings
        return JSONObject().put("v", VERSION).put("on", s.on).put("for", s.forWho.id).put("endEarly", s.endEarly.id).put("waitMin", s.waitMin)
            .put("warnMin", s.warnMin).put("showLeft", s.showLeft)
            .put("locks", JSONArray().apply { for (l in f.locks) put(lock(l)) }).toString()
    }

    private fun lock(l: PhoneLock): JSONObject = JSONObject().put("id", l.id).put("name", l.name).put("on", l.on).put("kind", l.rule.kind).apply {
        when (val r = l.rule) {
            is LockRule.Time -> { put("from", hm(r.window.from)); put("to", hm(r.window.to)); put("days", days(r.days)) }
            is LockRule.Alarm -> { put("window", JSONArray().put(hm(r.window.from)).put(hm(r.window.to))); put("minutes", r.minutes); put("days", days(r.days)) }
            is LockRule.App -> {
                put("mode", r.mode.id)
                put("app", r.app)
                when (r.mode) {
                    AppMode.FIRST -> {
                        put("start", when (val st = r.start) {
                            is AppStart.At -> JSONObject().put("at", hm(st.time)).put("days", days(st.days))
                            AppStart.Alarm -> JSONObject().put("alarm", true)
                        })
                        put("minutes", r.minutes)
                        put("latestMin", r.latestMin)
                    }
                    AppMode.LIMIT -> { put("limitMin", r.limitMin); put("until", hm(r.until)) }
                }
            }
        }
        put("extras", JSONObject().put("people", JSONArray(l.extras.people)).put("maps", l.extras.maps).put("music", l.extras.music ?: JSONObject.NULL)
            .put("apps", JSONArray(l.extras.apps)))
    }

    fun decode(text: String?): LockFileRead {
        if (text == null || text.length > MAX_BYTES) return LockFileRead.Damaged
        val root = (StrictJson.parse(text)?.value as? Map<*, *>) ?: return LockFileRead.Damaged
        return try {
            val o = Obj(root)
            val v = o.int("v")
            if (v > VERSION) return LockFileRead.TooNew(v)
            if (v < 1) return LockFileRead.Damaged
            o.only("v", "on", "for", "endEarly", "waitMin", "warnMin", "showLeft", "locks")
            val settings = LockSettings(
                on = o.bool("on"),
                forWho = LockFor.of(o.str("for")) ?: bad(),
                endEarly = EndEarly.of(o.str("endEarly")) ?: bad(),
                waitMin = o.int("waitMin"),
                warnMin = o.int("warnMin"),
                showLeft = o.bool("showLeft"),
            )
            if (LockLimits.problem(settings) != null) bad()
            val locks = o.list("locks").map { lock(Obj(it as? Map<*, *> ?: bad())) }
            if (locks.size > LockLimits.MAX_LOCKS || locks.map { it.id }.toSet().size != locks.size) bad()
            LockFileRead.Ok(LockFile(settings, locks))
        } catch (_: Bad) {
            LockFileRead.Damaged
        }
    }

    private fun lock(o: Obj): PhoneLock {
        val kind = o.str("kind")
        val common = arrayOf("id", "name", "on", "kind", "extras")
        val rule = when (kind) {
            LockRule.KIND_TIME -> {
                o.only(*common, "from", "to", "days")
                LockRule.Time(TimeWindow(time(o.str("from")), time(o.str("to"))), days(o.list("days")))
            }
            LockRule.KIND_ALARM -> {
                o.only(*common, "window", "minutes", "days")
                val w = o.list("window").map { it as? String ?: bad() }
                if (w.size != 2) bad()
                LockRule.Alarm(TimeWindow(time(w[0]), time(w[1])), o.int("minutes"), days(o.list("days")))
            }
            LockRule.KIND_APP -> when (AppMode.of(o.str("mode"))) {
                AppMode.FIRST -> {
                    o.only(*common, "mode", "app", "start", "minutes", "latestMin")
                    val st = Obj(o.map("start"))
                    val start = if (st.has("alarm")) {
                        st.only("alarm")
                        if (!st.bool("alarm")) bad()
                        AppStart.Alarm
                    } else {
                        st.only("at", "days")
                        AppStart.At(time(st.str("at")), days(st.list("days")))
                    }
                    LockRule.App(AppMode.FIRST, o.str("app"), start, minutes = o.int("minutes"), latestMin = o.int("latestMin"))
                }
                AppMode.LIMIT -> {
                    o.only(*common, "mode", "app", "limitMin", "until")
                    LockRule.App(AppMode.LIMIT, o.str("app"), limitMin = o.int("limitMin"), until = time(o.str("until")))
                }
                null -> bad()
            }
            else -> bad()
        }
        val e = Obj(o.map("extras"))
        e.only("people", "maps", "music", "apps")
        val extras = LockExtras(
            people = e.list("people").map { it as? String ?: bad() },
            maps = e.bool("maps"),
            music = e.strOrNull("music"),
            apps = e.list("apps").map { it as? String ?: bad() },
        )
        val l = PhoneLock(o.str("id"), o.str("name"), rule, o.bool("on"), extras)
        if (LockLimits.problem(l) != null) bad()
        return l
    }

    // ------------------------------------------------------------------------------------------------ state.json

    fun encodeState(s: LockState): String = JSONObject().put("v", STATE_VERSION)
        .put("active", JSONArray().apply {
            for (a in s.active) put(JSONObject().put("lock", a.lock).put("start", a.start).put("end", a.end)
                .put("endElapsed", a.endElapsed ?: JSONObject.NULL).put("boot", a.boot ?: JSONObject.NULL).put("usedMs", a.usedMs)
                .put("early", a.waitFrom?.let { JSONObject().put("waitFrom", it) } ?: JSONObject.NULL))
        })
        .put("skip", JSONObject().apply { for ((k, d) in s.skip) put(k, d.toString()) })
        .put("edit", s.edit)
        .put("codeSet", s.codeSet)
        .put("code", JSONObject().put("fails", s.code.fails).put("nextTry", s.code.nextTry))
        .put("alarm", JSONObject().put("phase", s.alarm.phase.name.lowercase()).put("ringing", s.alarm.ringingSince).put("first", s.alarm.firstAlert)
            .put("snoozed", s.alarm.snoozedAt)
            .put("seen", JSONArray().apply { for (a in s.seen) put(JSONObject().put("t", a.triggerAt).put("clock", a.fromClock).put("at", a.seenAt)) })
            .put("missed", s.missed?.let { JSONObject().put("at", it.alarmAt).put("done", it.doneAt) } ?: JSONObject.NULL))
        .put("last", JSONObject().put("alive", s.last.alive).put("shutdown", s.last.shutdown))
        .put("car", s.car)
        .put("record", JSONArray().apply {
            for (r in s.record) put(JSONObject().put("t", r.t).put("e", r.e.id).apply {
                r.lock?.let { put("lock", it) }
                r.n?.let { put("n", it) }
            })
        }).toString()

    /** state.json as written; null when there is none or it can't be read (the caller starts from an empty state). */
    fun decodeState(text: String?): LockState? {
        if (text == null || text.length > MAX_STATE_BYTES) return null
        val root = (StrictJson.parse(text)?.value as? Map<*, *>) ?: return null
        return try {
            val o = Obj(root)
            if (o.int("v") != STATE_VERSION) return null
            // Phase B added "car"; a state written before it has none.
            o.only("v", "active", "skip", "edit", "codeSet", "code", "alarm", "last", "record", "car")
            val active = o.list("active").map { x ->
                val a = Obj(x as? Map<*, *> ?: bad())
                a.only("lock", "start", "end", "endElapsed", "boot", "usedMs", "early")
                val id = a.str("lock")
                if (id != ActiveLock.VOICE && !LockLimits.validId(id)) bad()
                val early = a.mapOrNull("early")?.let { Obj(it) }
                early?.only("waitFrom")
                ActiveLock(id, a.long("start"), a.long("end"), a.longOrNull("endElapsed"), a.longOrNull("boot")?.toInt(), a.long("usedMs"), early?.long("waitFrom"))
            }
            val skip = o.map("skip").entries.associate { (k, v) ->
                val id = k as? String ?: bad()
                if (!LockLimits.validId(id)) bad()
                id to date(v as? String ?: bad())
            }
            val c = Obj(o.map("code"))
            c.only("fails", "nextTry")
            val al = Obj(o.map("alarm"))
            // Phase B added "seen" and "missed"; a state written before them has neither.
            al.only("phase", "ringing", "first", "snoozed", "seen", "missed")
            val seen = if (!al.has("seen")) emptyList() else al.list("seen").map { x ->
                val a = Obj(x as? Map<*, *> ?: bad())
                a.only("t", "clock", "at")
                SeenAlarm(a.long("t"), a.bool("clock"), a.long("at"))
            }.takeLast(app.heyrobot.core.AlarmCheck.KEEP)
            val missed = if (!al.has("missed")) null else al.mapOrNull("missed")?.let { Obj(it) }?.let { m ->
                m.only("at", "done")
                MissedAlarm(m.long("at"), m.long("done"))
            }
            val phase = AlarmPhase.entries.firstOrNull { it.name.lowercase() == al.str("phase") } ?: bad()
            val last = Obj(o.map("last"))
            last.only("alive", "shutdown")
            val record = o.list("record").mapNotNull { x ->
                val r = Obj(x as? Map<*, *> ?: bad())
                r.only("t", "e", "lock", "n")
                // A line from a newer Robot is left out, never the whole state.
                val e = RecordEvent.of(r.str("e")) ?: return@mapNotNull null
                RecordLine(r.long("t"), e, r.opt("lock")?.let { it as? String ?: bad() }, r.opt("n")?.let { (it as? Long ?: bad()).toInt() })
            }
            LockState(active, skip, o.long("edit"), o.bool("codeSet"), CodeTries(c.int("fails"), c.long("nextTry")),
                AlarmWatchState(phase, al.long("ringing"), al.long("first"), al.long("snoozed")), seen = seen, missed = missed, last = LastSeen(last.long("alive"), last.long("shutdown")), record = record,
                car = if (o.has("car")) o.long("car") else 0)
        } catch (_: Bad) {
            null
        }
    }

    // ------------------------------------------------------------------------------------------------ pieces

    private val DAY_IDS = listOf("MON", "TUE", "WED", "THU", "FRI", "SAT", "SUN") // l10n: not text (file keys)

    /** "22:00": hours and minutes, as the file and the editor write them. */
    fun hm(t: LocalTime): String = "%02d:%02d".format(t.hour, t.minute)

    private fun days(d: Set<DayOfWeek>): JSONArray = JSONArray().apply { for (x in d.sortedBy { it.value }) put(DAY_IDS[x.value - 1]) }

    private fun days(a: List<Any?>): Set<DayOfWeek> {
        val out = a.map { v -> DAY_IDS.indexOf(v as? String ?: bad()).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) } ?: bad() }
        if (out.toSet().size != out.size) bad()
        return out.toSet()
    }

    private fun time(s: String): LocalTime {
        if (!Regex("([01][0-9]|2[0-3]):[0-5][0-9]").matches(s)) bad()
        return LocalTime.parse(s)
    }

    private fun date(s: String): LocalDate = try { LocalDate.parse(s) } catch (_: DateTimeParseException) { bad() }

    private class Bad : Exception() {
        override fun fillInStackTrace(): Throwable = this
    }

    private fun bad(): Nothing = throw Bad()

    /** A JSON object read strictly: each value of the type asked for, and no key that isn't named in [only]. */
    private class Obj(val m: Map<*, *>) {
        fun has(k: String) = m.containsKey(k)
        fun opt(k: String): Any? = m[k]
        fun only(vararg keys: String) { if (m.keys.any { it !in keys }) bad() }
        fun str(k: String): String = m[k] as? String ?: bad()
        fun strOrNull(k: String): String? = if (!m.containsKey(k)) bad() else m[k]?.let { it as? String ?: bad() }
        fun bool(k: String): Boolean = m[k] as? Boolean ?: bad()
        fun long(k: String): Long = m[k] as? Long ?: bad()
        fun longOrNull(k: String): Long? = if (!m.containsKey(k)) bad() else m[k]?.let { it as? Long ?: bad() }
        fun int(k: String): Int = long(k).also { if (it !in Int.MIN_VALUE..Int.MAX_VALUE) bad() }.toInt()
        fun list(k: String): List<Any?> = m[k] as? List<*> ?: bad()
        fun map(k: String): Map<*, *> = m[k] as? Map<*, *> ?: bad()
        fun mapOrNull(k: String): Map<*, *>? = if (!m.containsKey(k)) bad() else m[k]?.let { it as? Map<*, *> ?: bad() }
    }
}
