// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.writeAtomic
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

// Pure (java.io, org.json). Where skills live (plan §3): files/robot/, beside routines.json, written whole and atomically
// under skills.lock, which both app processes take, because two write them: the main app's Settings › Skills and the
// :voice process (a skill taught, saved or deleted by voice). RoutineStore's pattern.
//
//   skills.json        the skills (SkillCodec, v1)
//   skill_settings.json  phase B's setting (SkillSettings): main writes it
//   skills_state.json  :voice's own: each skill's last run, and phase B's fill samples (SkillStats) (counts and times
//                      only; never backed up, logged or sent)
//
// A skills file that can't be read is never written over: changes are refused and nothing runs until Settings › Skills'
// "Start over" keeps it aside as skills.json.bad. One from a newer Robot is left as it is. A write after a read that
// dropped skills first keeps the file as skills.json.bad.

class SkillStore(private val dir: File) {
    private val skillsFile get() = File(dir, SKILLS)
    private val stateFile get() = File(dir, STATE)
    private val settingsFile get() = File(dir, SETTINGS)

    /** What skills.json holds now: no file is no skills. */
    fun file(): SkillFile {
        val f = skillsFile
        if (!f.exists()) return SkillFile.Ok(emptyList())
        val text = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { return SkillFile.Damaged }
        return SkillCodec.decode(text)
    }

    /** Your skills (none while the file can't be read or is from a newer Robot: nothing runs then). */
    fun skills(): List<Skill> = (file() as? SkillFile.Ok)?.skills.orEmpty()

    fun get(id: String): Skill? = skills().firstOrNull { it.id == id }

    /**
     * Reads, changes and writes skills.json under the lock. Null when it couldn't: the file is damaged or newer (never
     * written over), the result can't be written (a skill [SkillCheck] refuses, over 100 skills or 512 KiB), or the write
     * failed.
     */
    fun edit(change: (List<Skill>) -> List<Skill>): List<Skill>? = try {
        locked {
            when (val now = file()) {
                is SkillFile.Ok -> {
                    val next = change(now.skills)
                    if (next != now.skills) {
                        val text = SkillCodec.encode(next)
                        val back = SkillCodec.decode(text)
                        // Written only when every skill reads back as it is: nothing is saved that the next read would drop.
                        if (next.size > SkillCheck.MAX_SKILLS || back !is SkillFile.Ok || back.dropped > 0 || back.skills.size != next.size ||
                            SkillCodec.encode(back.skills) != text) return@locked null
                        if (now.dropped > 0) keepAside()
                        writeAtomic(skillsFile, text)
                    }
                    next
                }
                else -> null
            }
        }
    } catch (_: Exception) {
        null
    }

    /** Adds [s], or replaces the skill with its id. False when it can't be saved or written. */
    fun save(s: Skill): Boolean {
        if (!SkillCheck.valid(s)) return false
        val saved = edit { list ->
            if (list.any { it.id == s.id }) list.map { if (it.id == s.id) s else it }
            else if (list.size >= SkillCheck.MAX_SKILLS) list
            else list + s
        } ?: return false
        return s in saved
    }

    fun delete(id: String): Boolean = edit { list -> list.filter { it.id != id } } != null

    /** Starts over after a damaged file: kept aside as skills.json.bad, and Robot has no skills. */
    fun reset(): Boolean = try {
        locked {
            if (file() is SkillFile.Damaged) {
                val bad = File(dir, "$SKILLS.bad")
                bad.delete()
                skillsFile.renameTo(bad)
            } else true
        }
    } catch (_: Exception) {
        false
    }

    private fun keepAside() {
        val bad = File(dir, "$SKILLS.bad")
        if (!bad.exists()) skillsFile.copyTo(bad)
    }

    // ------------------------------------------------------------------ skill_settings.json (phase B, main)

    fun settingsFile(): SkillSettingsFile = SkillSettingsCodec.decode(read(settingsFile))

    /** What applies now: the defaults while the file can't be read. */
    fun settings(): SkillSettings = settingsFile().settings

    /** Writes [s] under the lock; never over a file from a newer Robot. False when it couldn't. */
    fun saveSettings(s: SkillSettings): Boolean = try {
        locked {
            if (settingsFile() is SkillSettingsFile.TooNew) false
            else { writeAtomic(settingsFile, SkillSettingsCodec.encode(s)); true }
        }
    } catch (_: Exception) {
        false
    }

    // ------------------------------------------------------------------ the last runs (:voice)

    fun state(): SkillState = SkillStateFile.decode(read(stateFile))

    fun editState(change: (SkillState) -> SkillState): SkillState? = try {
        locked {
            val now = state()
            val next = change(now)
            if (next != now) writeAtomic(stateFile, SkillStateFile.encode(next))
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
        const val SKILLS = "skills.json"
        const val STATE = "skills_state.json"
        const val SETTINGS = "skill_settings.json"
        const val LOCK_FILE = "skills.lock"
        private val LOCK = Any()
    }
}

/** A skill's last run: when ([at], wall clock ms), how many steps happened, out of how many. */
data class SkillRunRecord(val at: Long, val done: Int, val total: Int)

/** skills_state.json: the last run of each skill, and its fill samples (phase B, SkillStats), by id. Counts and times only. */
data class SkillState(val lastRun: Map<String, SkillRunRecord> = emptyMap(), val fills: Map<String, FillSamples> = emptyMap())

/** skills_state.json, v1. Lenient: what can't be read is left out (it is only what Settings shows). */
object SkillStateFile {
    const val VERSION = 1

    fun encode(s: SkillState): String = JSONObject().put("v", VERSION).put("runs", JSONObject().apply {
        for ((id, r) in s.lastRun) put(id, JSONObject().put("at", r.at).put("done", r.done).put("total", r.total))
    }).apply {
        // Phase B: "1101…" per model, oldest first (1: right).
        if (s.fills.isNotEmpty()) put("fills", JSONObject().apply {
            for ((id, f) in s.fills) put(id, JSONObject().put("rev", f.rev).put("models", JSONObject().apply {
                for ((m, ring) in f.byModel) put(m.id, ring.joinToString("") { if (it) "1" else "0" })
            }))
        })
    }.toString()

    fun decode(json: String?): SkillState {
        val o = try { JSONObject(json ?: return SkillState()) } catch (_: Exception) { return SkillState() }
        return SkillState(runs(o.optJSONObject("runs")), fills(o.optJSONObject("fills")))
    }

    private fun fills(o: JSONObject?): Map<String, FillSamples> {
        o ?: return emptyMap()
        val out = LinkedHashMap<String, FillSamples>()
        for (id in o.keys()) {
            if (!SkillCheck.validId(id)) continue
            val f = o.optJSONObject(id) ?: continue
            val rev = (f.opt("rev") as? Number)?.toInt()?.takeIf { it >= 1 } ?: continue
            val models = f.optJSONObject("models") ?: continue
            val by = LinkedHashMap<FillModel, List<Boolean>>()
            for (m in models.keys()) {
                val model = FillModel.of(m) ?: continue
                val ring = models.opt(m) as? String ?: continue
                if (ring.isEmpty() || ring.length > SkillStats.RING || ring.any { it != '0' && it != '1' }) continue
                by[model] = ring.map { it == '1' }
            }
            out[id] = FillSamples(rev, by)
        }
        return out
    }

    private fun runs(runs: JSONObject?): Map<String, SkillRunRecord> {
        runs ?: return emptyMap()
        val out = LinkedHashMap<String, SkillRunRecord>()
        for (id in runs.keys()) {
            if (!SkillCheck.validId(id)) continue
            val r = runs.optJSONObject(id) ?: continue
            val at = (r.opt("at") as? Number)?.toLong() ?: continue
            val done = (r.opt("done") as? Number)?.toInt() ?: continue
            val total = (r.opt("total") as? Number)?.toInt() ?: continue
            if (done in 0..total && total in 1..SkillCheck.MAX_STEPS) out[id] = SkillRunRecord(at, done, total)
        }
        return out
    }

    /** [s] with [id]'s run recorded, and only the runs of [keep] (a deleted skill's run goes). */
    fun record(s: SkillState, id: String, r: SkillRunRecord, keep: Set<String>): SkillState =
        s.copy(lastRun = (s.lastRun + (id to r)).filterKeys { it in keep || it == id }, fills = s.fills.filterKeys { it in keep || it == id })
}
