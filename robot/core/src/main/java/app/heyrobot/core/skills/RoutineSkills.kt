// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.RunOrigin
import java.io.File
import java.util.concurrent.ConcurrentHashMap

// Pure (java.io). Routines as skills in the app (skills 1.3 plan S3.2, design §14.4). Every change that touches a routine
// and its skill takes routines.lock, then skills.lock (always in that order, in either process), so the two files never
// disagree for the other process to read:
//
// - [migrate]: RoutineMigration on what is on disk, once per process start (main and :voice both call it). The skills go
//   first; when the routines can't be written after them, the skills file is put back as it was, so a failed migration
//   changes nothing. A damaged or newer file on either side, or routines that couldn't all be read: nothing is done.
// - [save] and [delete] (Settings › Routines, by voice): a linked routine's skill gets the routine's steps (and goes with
//   it); one with a step skills don't have goes on alone, and its skill is deleted. A new routine is linked at once.
// - [saveSkill] and [deleteSkill] (Settings › Skills, by voice): a routine's skill gives the routine its steps back; one a
//   routine couldn't run by itself (a question, an earlier step's output, a step routines don't have) isn't saved, and a
//   routine's skill is deleted with its routine, never alone.
//
// So the two always hold the same steps, and a routine runs its own as before: a triggered run goes through RoutineGate
// step by step and never gets the skill's card; an asked one gets the skill's rule first ([go]).

/** A routine's own step in a skill's run (the brief, your reminders, the messages count), run by the routine's code. */
fun interface RoutineSteps {
    suspend fun run(step: RoutineStep): ActionStatus
}

object RoutineSkills {
    private val done = ConcurrentHashMap.newKeySet<String>()

    /** [migrate] once in this process for [dir] (files/robot): what it did, or null when it already ran or couldn't. */
    fun once(dir: File): RoutineMigrated? {
        val key = dir.absolutePath
        if (key in done) return null
        return migrate(RoutineStore(dir), SkillStore(dir))?.also { done += key }
    }

    /** RoutineMigration on the files, under both locks. Null when nothing could be done (see above). */
    fun migrate(routines: RoutineStore, skills: SkillStore): RoutineMigrated? = locked(routines, skills) {
        val rf = routines.file() as? RoutineFile.Ok ?: return@locked null
        val sf = skills.file() as? SkillFile.Ok ?: return@locked null
        if (rf.dropped > 0 || sf.dropped > 0) return@locked null
        val m = RoutineMigration.migrate(rf.routines, sf.skills)
        if (!m.changed) return@locked m
        val before = skills.text()
        if (m.skills != sf.skills && skills.edit { m.skills } == null) return@locked null
        try {
            if (m.routines != rf.routines) routines.write(m.routines)
        } catch (e: Exception) {
            skills.restore(before)
            return@locked null
        }
        m
    }

    /** Saves [r] and its skill's steps (see above). False when it couldn't: then nothing changed. */
    fun save(routines: RoutineStore, skills: SkillStore, r: Routine): Boolean = both(routines, skills) {
        // The link is the file's: an edit never brings one of its own.
        val link = routines.routines().firstOrNull { it.id == r.id }?.skill
        val sk = link?.let { skills.get(it) }
        if (sk == null) return@both routines.save(r.copy(skill = null)).also { if (it) migrate(routines, skills) }
        val steps = r.steps.map { RoutineMigration.step(it) }
        if (steps.any { it == null }) {
            // It no longer fits a skill: the routine goes on alone, as one that couldn't be migrated, and its skill goes.
            return@both routines.save(r.copy(skill = null)).also { if (it && sk.provenance.made == SkillMade.ROUTINE) skills.delete(sk.id) }
        }
        val next = sk.copy(steps = steps.filterNotNull(), provenance = sk.provenance.copy(edited = maxOf(sk.provenance.edited, r.edited)))
        if (next != sk && !skills.save(next)) return@both false
        routines.save(r.copy(skill = link)).also { if (!it && next != sk) skills.save(sk) }
    }

    /** Deletes routine [id] and its skill. */
    fun delete(routines: RoutineStore, skills: SkillStore, id: String): Boolean = both(routines, skills) {
        val link = routines.routines().firstOrNull { it.id == id }?.skill
        routines.delete(id).also { ok -> if (ok && link != null && skills.get(link)?.provenance?.made == SkillMade.ROUTINE) skills.delete(link) }
    }

    /** The routine [s] starts by itself as, if any. */
    fun routineOf(s: Skill, routines: List<Routine>): Routine? = routines.firstOrNull { it.skill == s.id }

    /** [s]'s steps as a routine runs them, or null when a routine couldn't run one of them by itself. */
    fun routineSteps(s: Skill): List<RoutineStep>? = s.steps.map { RoutineMigration.routineStep(s, it) ?: return null }.takeIf { s.params.isEmpty() }

    /**
     * Saves [s]; a routine's skill gives the routine its steps. False when it couldn't, or when [s] starts by itself as a
     * routine and the routine couldn't run its steps ([routineSteps] null; SkillStrings.routineCantRun), or [s] is the
     * built-in brief with other steps.
     */
    fun saveSkill(routines: RoutineStore, skills: SkillStore, s: Skill): Boolean = both(routines, skills) {
        if (RoutineMigration.builtIn(s) && skills.get(s.id)?.steps?.let { it != s.steps } == true) return@both false
        val r = routineOf(s, routines.routines()) ?: return@both skills.save(s)
        val steps = routineSteps(s) ?: return@both false
        val old = skills.get(s.id)
        if (!skills.save(s)) return@both false
        if (steps == r.steps) return@both true
        routines.save(r.copy(steps = steps)).also { if (!it) old?.let { o -> skills.save(o) } }
    }

    /** Deletes skill [id], unless it is the built-in brief's or starts by itself as a routine (SkillStrings.routinesSkill). */
    fun deleteSkill(routines: RoutineStore, skills: SkillStore, id: String): Boolean = both(routines, skills) {
        if (id == RoutineMigration.BRIEF_SKILL || routines.routines().any { it.skill == id }) false else skills.delete(id)
    }

    /**
     * Whether an asked run of [r] may go on, by its skill's own rule (SkillPolicy: off, Ask first's card, a step that needs
     * attention), before each step's gate as today. Null, so no skill card ever: a triggered run ([RunOrigin.UNATTENDED]) or
     * a Ready card's tap, or a routine with no skill. The lock isn't the skill's rule here: RoutineGate keeps a routine's
     * lock rules step by step, as in 0.8 (an asked brief on a locked phone, for one).
     */
    fun go(r: Routine, skills: List<Skill>, origin: RunOrigin, messages: Boolean, links: SkillLinks = SkillLinks.NONE): SkillGo? {
        if (origin != RunOrigin.ASKED) return null
        val s = r.skill?.let { id -> skills.firstOrNull { it.id == id } } ?: return null
        return SkillPolicy.decide(s, locked = false, phoneLock = false, messages = messages, links = links)
    }

    private inline fun <T> locked(routines: RoutineStore, skills: SkillStore, crossinline block: () -> T?): T? = try {
        routines.underLock { skills.underLock { block() } }
    } catch (_: Exception) {
        null
    }

    private inline fun both(routines: RoutineStore, skills: SkillStore, crossinline block: () -> Boolean): Boolean = try {
        routines.underLock { skills.underLock { block() } }
    } catch (_: Exception) {
        false
    }
}
