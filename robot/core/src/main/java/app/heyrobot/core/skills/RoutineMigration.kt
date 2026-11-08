// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineStep
import java.math.BigInteger
import java.security.MessageDigest

// Pure. Routines as skills (skills 1.3 plan S3.1, design §14.4): your routines (routines.json v1) and your skills in,
// skills and the routines' links out. Each routine becomes a skill made `routine`, with the routine's name and steps
// (RoutineCodec's arguments, as SkillDraft.step takes them), level Just do it (a routine never asked beyond each step's own
// gate); its category is computed from its steps (SkillEffects), as every skill's is. The morning brief becomes a built-in
// one-step skill ([BRIEF_SKILL]) that can't be deleted. Triggers, conditions, output, lock and consent stay the routine's:
// a triggered run still goes through RoutineGate, step by step, with the same class ([routineStep] gives each one back).
//
// Fail closed: a routine whose skill would fail SkillCheck (a step skills don't have, the nearest place, a name too long, a
// sentence that looks like a template or a secret), or that doesn't fit in the skills file, stays unlinked as it was and is
// counted. A name already one of your skills' (SkillCodec reads no two skills with the same name) gets " 2", " 3", …: the
// routine keeps its own name, and the skill's spoken names stay empty, as every migrated skill's do (its name is enough).
// Running it again changes nothing: a linked routine is left alone, and an unlinked one whose skill is still there (1.2,
// after a downgrade, wrote routines.json without the links and left the v3 skills file alone) is linked to it again by
// the id it gave it, with the routine's name and steps as 1.2 left them; a routine-made skill no routine links is Off.
// Skill ids come from the routine's id, so both processes reach the same file.

/** What [RoutineMigration.migrate] made: [skills] to write, [routines] with their links, [refused] routines left unlinked. */
data class RoutineMigrated(val skills: List<Skill>, val routines: List<Routine>, val refused: Int, val changed: Boolean) {
    /** Routine id to skill id, the morning brief's included. */
    val links: Map<String, String> get() = routines.mapNotNull { r -> r.skill?.let { r.id to it } }.toMap() + (BriefRoutine.ID to RoutineMigration.BRIEF_SKILL)
}

object RoutineMigration {
    /** The morning brief's skill: built in, one step, never deleted (design §14.4 step 3). */
    const val BRIEF_SKILL = "s-mbrief" // l10n: not text (a skill id in the skills file)

    /** [s] is the built-in brief's skill: Settings never deletes it. */
    fun builtIn(s: Skill): Boolean = s.id == BRIEF_SKILL

    fun migrate(routines: List<Routine>, skills: List<Skill>): RoutineMigrated {
        val out = skills.toMutableList()
        val linked = HashSet<String>()
        var refused = 0
        if (out.none { it.id == BRIEF_SKILL }) brief(out)?.let { out += it }
        linked += BRIEF_SKILL
        val next = routines.filter { it.id != BriefRoutine.ID }.map { r ->
            r.skill?.takeIf { id -> out.any { it.id == id } }?.let { linked += it; return@map r }
            val steps = r.steps.map { step(it) }
            if (steps.any { it == null }) { refused++; return@map r.copy(skill = null) }
            val st = steps.filterNotNull()
            // 1.2 drops the links whenever it saves routines.json, after an edit there too: the skill made from this
            // routine's id is still its own, and takes the routine's name and steps as 1.2 left them (never a "Name 2").
            own(r.id, out, linked)?.let { i ->
                val s = relinked(r, st, out, i) ?: run { refused++; return@map r.copy(skill = null) }
                out[i] = s
                linked += s.id
                return@map r.copy(skill = s.id)
            }
            val again = out.firstOrNull { it.id !in linked && it.provenance.made == SkillMade.ROUTINE && it.steps == st && base(it.name) == base(r.name.trim()) }
            val s = again ?: make(r, st, out)?.also { out += it }
            if (s == null) { refused++; r.copy(skill = null) } else { linked += s.id; r.copy(skill = s.id) }
        }
        // A skill made from a routine that is gone (deleted under 1.2, or no longer a skill's): Off, never deleted, so it
        // never runs as a routine you no longer have and nothing is lost.
        for (i in out.indices) out[i].let { s ->
            if (s.provenance.made == SkillMade.ROUTINE && s.id !in linked && s.level != SkillLevel.OFF) out[i] = s.copy(level = SkillLevel.OFF)
        }
        return RoutineMigrated(out, next, refused, changed = out != skills || next != routines.filter { it.id != BriefRoutine.ID })
    }

    /** [r]'s step as a skill's, or null when skills have no step for it (a tool they never use, the nearest place). */
    fun step(r: RoutineStep): SkillStep? = when (r) {
        RoutineStep.Brief -> SkillStep.of(StepKind.BRIEF, emptyMap())
        RoutineStep.Reminders -> SkillStep.of(StepKind.REMINDERS, emptyMap())
        RoutineStep.Messages -> SkillStep.of(StepKind.MESSAGES, emptyMap())
        is RoutineStep.Say -> SkillStep.of(StepKind.SAY, mapOf("text" to r.text))
        is RoutineStep.Tool -> if (nearest(r.action)) null else SkillDraft.step(r.action)
    }

    /** A migrated skill's step as the routine runs it (RoutineGate), or null when it isn't one a routine has. */
    fun routineStep(s: Skill, st: SkillStep): RoutineStep? = when {
        st.kind == StepKind.BRIEF -> RoutineStep.Brief
        st.kind == StepKind.REMINDERS -> RoutineStep.Reminders
        st.kind == StepKind.MESSAGES -> RoutineStep.Messages
        st.kind == StepKind.SAY -> (st.args["text"] as? String)?.takeIf { st.refs.isEmpty() }?.let { RoutineStep.Say(it) }
        st.kind.tool != null && st.refs.isEmpty() -> SkillCheck.action(s, st)?.let { RoutineStep.Tool(it) }
        else -> null
    }

    private fun brief(out: List<Skill>): Skill? {
        val s = Skill(BRIEF_SKILL, 1, free(BriefRoutine.NAME, out) ?: return null, emptyList(), emptyList(), emptyList(),
            listOf(SkillStep.of(StepKind.BRIEF, emptyMap())), SkillLevel.JUST_DO_IT, SkillProvenance(SkillMade.ROUTINE, false, 0, 0))
        return s.takeIf { out.size < SkillCheck.MAX_SKILLS && SkillCheck.valid(it) }
    }

    private fun make(r: Routine, steps: List<SkillStep>, out: List<Skill>): Skill? {
        if (out.size >= SkillCheck.MAX_SKILLS) return null
        val name = free(r.name.trim(), out) ?: return null
        val s = Skill(id(r.id, out), 1, name, emptyList(), emptyList(), emptyList(), steps, SkillLevel.JUST_DO_IT,
            SkillProvenance(SkillMade.ROUTINE, false, r.created, r.edited))
        return s.takeIf { SkillCheck.valid(it) }
    }

    /** [name], or with " 2", " 3", … when another skill answers to it; null when none fits. */
    private fun free(name: String, out: List<Skill>): String? {
        val taken = out.flatMap { it.keys }.toSet()
        if (SkillKey.of(name) !in taken) return name
        for (n in 2..MAX_SUFFIX) {
            val tail = " $n"
            val c = name.take(SkillCheck.MAX_NAME - tail.length).trimEnd() + tail
            if (SkillKey.of(c) !in taken) return c
        }
        return null
    }

    /** A name's key without the " 2" [free] may have added. */
    private fun base(name: String): String = SkillKey.of(name.replace(SUFFIX, ""))

    /** A skill id from the routine's id: the same in either process; the next one when it's taken. */
    private fun id(routine: String, out: List<Skill>): String {
        val used = out.map { it.id }.toSet() + BRIEF_SKILL
        var n = 0
        while (true) {
            val id = hashed(routine, n++)
            if (id !in used && SkillCheck.validId(id)) return id
        }
    }

    private fun hashed(routine: String, n: Int): String {
        val h = MessageDigest.getInstance("SHA-256").digest("$routine/$n".toByteArray(Charsets.UTF_8))
        return "s-" + BigInteger(1, h).toString(36).padStart(6, '0').take(6)
    }

    /**
     * The index in [out] of the routine-made skill [id] gave routine [routine], not linked yet: the ids it tried in turn,
     * until one that no skill has (where [make] would have put it). Null when there is none.
     */
    private fun own(routine: String, out: List<Skill>, linked: Set<String>): Int? {
        for (n in 0 until MAX_IDS) {
            val id = hashed(routine, n)
            if (id == BRIEF_SKILL || !SkillCheck.validId(id)) continue
            val i = out.indexOfFirst { it.id == id }
            if (i < 0) return null
            if (out[i].provenance.made == SkillMade.ROUTINE && id !in linked) return i
        }
        return null
    }

    /** Skill [i] with routine [r]'s name (" 2" when another skill has it) and [steps], its level kept; null when it fails SkillCheck. */
    private fun relinked(r: Routine, steps: List<SkillStep>, out: List<Skill>, i: Int): Skill? {
        val s = out[i]
        val name = if (base(s.name) == base(r.name.trim())) s.name else free(r.name.trim(), out.filterIndexed { j, _ -> j != i }) ?: return null
        val next = s.copy(name = name, steps = steps, provenance = s.provenance.copy(edited = maxOf(s.provenance.edited, r.edited)))
        return next.takeIf { it == s || SkillCheck.valid(it) }
    }

    private fun nearest(a: Action): Boolean = (a as? Action.MapsSearch)?.nearest == true || (a as? Action.MapsNavigate)?.nearest == true

    private const val MAX_SUFFIX = 99
    private const val MAX_IDS = 64
    private val SUFFIX = Regex(" [0-9]{1,2}$")
}
