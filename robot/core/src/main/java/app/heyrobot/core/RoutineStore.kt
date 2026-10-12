package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Pure (java.io, org.json). Where routines live (design §2.8, 0.8): files/robot/ next to settings.json, one small file
// each, all written atomically under one lock both app processes take (the PeopleStore pattern), because two processes
// write them: the main app's Settings › Routines and the :voice process (a routine saved or deleted by voice, Ready
// cards, brief reminders, the run ledger). settings.json is left alone: the routine settings have their own file.
//
//   routines.json          the routines (RoutineCodec, schema 1)
//   routine_settings.json  Settings › Routines and the morning brief ([RoutineSettingsCodec])
//   ready.json             steps waiting for you (ReadyCards)
//   reminders.json         brief reminders (BriefReminders)
//   state.json             the run ledger, AlarmWatch's state, the last run of each routine ([RoutineStateFile])
//
// A routines file that can't be read is never written over: changes are refused and nothing runs until Settings ›
// Routines' "Start over" keeps it aside as routines.json.bad. One from a newer Robot is left as it is. Routines are
// personal: nothing here is logged.

class RoutineStore(private val dir: File) {
    private val routinesFile get() = File(dir, ROUTINES)
    private val settingsFile get() = File(dir, SETTINGS)
    private val readyFile get() = File(dir, READY)
    private val remindersFile get() = File(dir, REMINDERS)
    private val stateFile get() = File(dir, STATE)

    /** What routines.json holds now: no file is no routines. */
    fun file(): RoutineFile {
        val f = routinesFile
        if (!f.exists()) return RoutineFile.Ok(emptyList())
        val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { return RoutineFile.Damaged }
        return RoutineCodec.decode(text)
    }

    /** Your routines (none while the file can't be read or is from a newer Robot: nothing runs then). */
    fun routines(): List<Routine> = (file() as? RoutineFile.Ok)?.routines.orEmpty()

    /** Your routines and the built-in morning brief ([BriefRoutine]), as the triggers and the scheduler see them. */
    fun all(): List<Routine> = routines().filter { it.id != BriefRoutine.ID } + BriefRoutine.of(settings())

    fun get(id: String): Routine? = all().firstOrNull { it.id == id }

    /**
     * Reads, changes and writes routines.json under the lock. Null when it couldn't: the file is damaged or newer (never
     * written over), or the write failed. A file with routines that couldn't be read is kept aside as routines.json.bad
     * before the first write, so nothing you saved is lost without a copy.
     */
    fun edit(change: (List<Routine>) -> List<Routine>): List<Routine>? = try {
        locked {
            when (val now = file()) {
                is RoutineFile.Ok -> {
                    val next = change(now.routines).filter { it.id != BriefRoutine.ID }.take(RoutineCheck.MAX_ROUTINES)
                    if (next != now.routines) {
                        if (now.dropped > 0) keepAside()
                        writeAtomic(routinesFile, RoutineCodec.encode(next))
                    }
                    next
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Adds [r], or replaces the routine with its id. False when it can't be saved ([RoutineCheck]) or written. */
    fun save(r: Routine): Boolean {
        if (!RoutineCheck.valid(r) || r.id == BriefRoutine.ID) return false
        val saved = edit { list ->
            if (list.none { it.id == r.id } && list.size >= RoutineCheck.MAX_ROUTINES) list
            else if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else list + r
        } ?: return false
        return saved.any { it.id == r.id }
    }

    fun delete(id: String): Boolean = edit { list -> list.filter { it.id != id } } != null

    /** A routine switched on or off (the brief's switch is its setting, [RoutineSettings.briefEnabled]). */
    fun setEnabled(id: String, on: Boolean, nowMs: Long): Boolean = if (id == BriefRoutine.ID) {
        editSettings { it.copy(briefEnabled = on) } != null
    } else {
        edit { list -> list.map { if (it.id == id) it.copy(enabled = on, edited = nowMs) else it } } != null
    }

    /** Starts over after a damaged file: kept aside as routines.json.bad, and Robot has no routines. */
    fun reset(): Boolean = try {
        locked {
            if (file() is RoutineFile.Damaged) {
                val bad = File(dir, "$ROUTINES.bad")
                bad.delete()
                routinesFile.renameTo(bad)
            } else true
        }
    } catch (_: Exception) {
        false
    }

    private fun keepAside() {
        val bad = File(dir, "$ROUTINES.bad")
        if (!bad.exists()) routinesFile.copyTo(bad)
    }

    // ------------------------------------------------------------------ the other files

    fun settings(): RoutineSettings = RoutineSettingsCodec.decode(read(settingsFile))

    fun editSettings(change: (RoutineSettings) -> RoutineSettings): RoutineSettings? = update(::settings, change) { writeAtomic(settingsFile, RoutineSettingsCodec.encode(it)) }

    fun ready(): List<ReadyCard> = ReadyCards.decode(read(readyFile))

    fun editReady(change: (List<ReadyCard>) -> List<ReadyCard>): List<ReadyCard>? = update(::ready, change) { writeAtomic(readyFile, ReadyCards.encode(it)) }

    fun reminders(): List<BriefReminder> = BriefReminders.decode(read(remindersFile))

    fun editReminders(change: (List<BriefReminder>) -> List<BriefReminder>): List<BriefReminder>? =
        update(::reminders, change) { writeAtomic(remindersFile, BriefReminders.encode(it)) }

    fun state(): RoutineState = RoutineStateFile.decode(read(stateFile))

    fun editState(change: (RoutineState) -> RoutineState): RoutineState? = update(::state, change) { writeAtomic(stateFile, RoutineStateFile.encode(it)) }

    /** Everything Routines keeps, deleted (Settings › Routines › Delete all routines); the files are gone, not emptied. */
    fun deleteAll(): Boolean = try {
        locked { listOf(ROUTINES, READY, REMINDERS, STATE).forEach { File(dir, it).delete() }; true }
    } catch (_: Exception) {
        false
    }

    private fun <T> update(read: () -> T, change: (T) -> T, write: (T) -> Unit): T? = try {
        locked {
            val now = read()
            val next = change(now)
            if (next != now) write(next)
            next
        }
    } catch (_: Exception) {
        null
    }

    private fun read(f: File): String? = try { if (f.isFile) f.readText(Charsets.UTF_8) else null } catch (_: Exception) { null }

    private fun <T> locked(block: () -> T): T = synchronized(LOCK) {
        dir.mkdirs()
        FileChannel.open(File(dir, LOCK_FILE).toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { ch ->
            val l = ch.lock()
            try { block() } finally { l.release() }
        }
    }

    companion object {
        const val ROUTINES = "routines.json"
        const val SETTINGS = "routine_settings.json"
        const val READY = "ready.json"
        const val REMINDERS = "reminders.json"
        const val STATE = "state.json"
        const val LOCK_FILE = "routines.lock"
        private val LOCK = Any()
    }
}

/**
 * routine_settings.json, schema 1. A missing file, field or value that can't be read is its default ([RoutineSettings]):
 * routines may run locked and go online by the services their cards listed, the brief is off.
 */
object RoutineSettingsCodec {
    const val VERSION = 1
    /** "Clock silences after": the choices Settings offers, in minutes (Clock's own choices). */
    val SILENCE_CHOICES = listOf(1, 5, 10, 15, 20, 25, 30)

    fun encode(s: RoutineSettings): String = JSONObject().apply {
        put("v", VERSION)
        put("run_locked", s.runLocked)
        put("online", s.online)
        put("clock_silence_minutes", s.clockSilenceMinutes)
        put("alarm_window", JSONObject().put("from", s.alarmWindow.from.toString()).put("to", s.alarmWindow.to.toString()))
        put("brief", JSONObject().apply {
            put("enabled", s.briefEnabled)
            put("trigger", RoutineCodec.trigger(s.briefTrigger))
            put("items", JSONArray().apply { for (i in s.briefItems) put(i.id) })
            put("lock", s.briefLock.id)
            put("finish_on_unlock", s.finishOnUnlock)
            s.briefLang?.let { put("lang", it.id) }
            put("prepare", s.briefPrepare)
            put("output", s.briefOutput.id)
            put("online", s.briefOnline)
        })
    }.toString()

    fun decode(json: String?): RoutineSettings {
        val d = RoutineSettings()
        val o = try { JSONObject(json ?: return d) } catch (_: Exception) { return d }
        val b = o.optJSONObject("brief") ?: JSONObject()
        val window = o.optJSONObject("alarm_window")?.let { w ->
            val from = RoutineCodec.time(RoutineCodec.str(w, "from"))
            val to = RoutineCodec.time(RoutineCodec.str(w, "to"))
            if (from != null && to != null && from != to) TimeWindow(from, to) else null
        } ?: d.alarmWindow
        val trigger = b.optJSONObject("trigger")?.let(RoutineCodec::trigger)?.takeIf(::briefTriggerOk) ?: d.briefTrigger
        val items = b.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { BriefItem.of(a.opt(it) as? String) }.distinct() } ?: d.briefItems
        return RoutineSettings(
            runLocked = RoutineCodec.bool(o, "run_locked") ?: d.runLocked,
            online = RoutineCodec.bool(o, "online") ?: d.online,
            briefEnabled = RoutineCodec.bool(b, "enabled") ?: d.briefEnabled,
            briefItems = items,
            briefLock = if (b.has("lock")) BriefLock.of(RoutineCodec.str(b, "lock")) else d.briefLock,
            finishOnUnlock = RoutineCodec.bool(b, "finish_on_unlock") ?: d.finishOnUnlock,
            briefLang = RoutineCodec.str(b, "lang")?.let { l -> BriefLang.entries.firstOrNull { it.id == l } },
            briefPrepare = RoutineCodec.bool(b, "prepare") ?: d.briefPrepare,
            clockSilenceMinutes = (o.opt("clock_silence_minutes") as? Number)?.toInt()?.takeIf { it in 1..60 } ?: d.clockSilenceMinutes,
            alarmWindow = window,
            briefTrigger = trigger,
            briefOutput = RoutineOutput.of(RoutineCodec.str(b, "output")) ?: d.briefOutput,
            briefOnline = RoutineCodec.bool(b, "online") ?: d.briefOnline,
        )
    }

    /** The brief runs at a time, after an alarm, at the first unlock, or only when asked: not on a device or charging. */
    fun briefTriggerOk(t: RoutineTrigger): Boolean = when (t) {
        is RoutineTrigger.At -> t.time.second == 0 && t.time.nano == 0 && (t.days.isNotEmpty() != (t.once != null))
        is RoutineTrigger.AlarmDismissed, is RoutineTrigger.Unlock, RoutineTrigger.Asked -> true
        else -> false
    }
}
