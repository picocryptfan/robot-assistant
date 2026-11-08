// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.BriefRoutine
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.ToolCalls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** Skills 1.3 plan S3.1, design §14.4: 3 routines and the morning brief become 4 linked skills; a second run changes nothing. */
class RoutineMigrationTest {
    private fun tool(call: String, vararg args: Pair<String, Any?>): RoutineStep =
        RoutineStep.Tool((ToolCalls.parse(RawToolCall(call, mapOf(*args))) as ParsedCall.Ok).action)

    private fun routine(id: String, name: String, vararg steps: RoutineStep) =
        Routine(id, name, RoutineTrigger.At(LocalTime.of(7, 30), setOf(java.time.DayOfWeek.MONDAY)), steps.toList(), created = 1760800000000, edited = 1760900000000)

    private val wake = routine("wake", "Wake up", RoutineStep.Brief, tool("weather"), RoutineStep.Say("Drink some water"))
    private val night = routine("night", "Good night", tool("set_alarm", "hour" to 6, "minute" to 30), tool("flashlight", "on" to false),
        RoutineStep.Reminders, RoutineStep.Messages)
    private val work = routine("work", "Work", tool("open_app", "name" to "Organic Maps"), tool("set_timer", "minutes" to 25))
    private val home = SkillFixtures.headingHome()

    @Test fun threeRoutinesAndTheBriefBecomeFourLinkedSkills() {
        val m = RoutineMigration.migrate(listOf(wake, night, work), listOf(home))
        assertEquals(0, m.refused)
        assertTrue(m.changed)
        assertEquals(5, m.skills.size)
        assertEquals(home, m.skills[0])
        val made = m.skills.drop(1)
        assertTrue(made.all { it.provenance.made == SkillMade.ROUTINE && it.level == SkillLevel.JUST_DO_IT && SkillCheck.valid(it) })
        assertEquals(listOf(BriefRoutine.NAME, "Wake up", "Good night", "Work"), made.map { it.name })
        assertTrue(made.all { it.namesEn.isEmpty() && it.namesFr.isEmpty() && it.params.isEmpty() && !it.provenance.fromContent })
        // The brief: built in, one step.
        val brief = made[0]
        assertEquals(RoutineMigration.BRIEF_SKILL, brief.id)
        assertTrue(RoutineMigration.builtIn(brief))
        assertEquals(listOf(SkillStep.of(StepKind.BRIEF, emptyMap())), brief.steps)
        assertEquals(m.skills.map { it.id }.distinct(), m.skills.map { it.id })
        // Each routine links to its skill, which holds its steps and dates; triggers and the rest stay the routine's.
        assertEquals(mapOf(BriefRoutine.ID to brief.id, "wake" to made[1].id, "night" to made[2].id, "work" to made[3].id), m.links)
        assertEquals(listOf(wake, night, work), m.routines.map { it.copy(skill = null) })
        assertEquals(listOf(StepKind.BRIEF, StepKind.WEATHER, StepKind.SAY), made[1].steps.map { it.kind })
        assertEquals(mapOf("hour" to 6L, "minute" to 30L), made[2].steps[0].args)
        assertEquals(mapOf("seconds" to 1500L), made[3].steps[1].args)
        assertEquals(SkillProvenance(SkillMade.ROUTINE, false, 1760800000000, 1760900000000), made[1].provenance)
        // Category, as every skill's: computed from the steps.
        assertEquals(setOf(SkillEffect.READS_ONLINE, SkillEffect.READS_PHONE), SkillEffects.of(made[1], SkillLinks.NONE))
        // The skills file they make is v3 and reads back whole.
        val text = SkillCodec.encode(m.skills)
        assertEquals(SkillFile.Ok(m.skills), SkillCodec.decode(text))
    }

    @Test fun aSecondRunChangesNothing() {
        val m = RoutineMigration.migrate(listOf(wake, night, work), listOf(home))
        val again = RoutineMigration.migrate(m.routines, m.skills)
        assertFalse(again.changed)
        assertEquals(m.skills, again.skills)
        assertEquals(m.routines, again.routines)
        assertEquals(m.links, again.links)
        // From the files, too.
        val skills = (SkillCodec.decode(SkillCodec.encode(m.skills)) as SkillFile.Ok).skills
        val routines = (app.heyrobot.core.RoutineCodec.decode(app.heyrobot.core.RoutineCodec.encode(m.routines)) as app.heyrobot.core.RoutineFile.Ok).routines
        assertFalse(RoutineMigration.migrate(routines, skills).changed)
        // Ids come from the routine's id: another process makes the same file.
        assertEquals(m, RoutineMigration.migrate(listOf(wake, night, work), listOf(home)))
    }

    @Test fun aClashKeepsTheRoutinesNameAndTheSkillGetsANumber() {
        val mine = SkillFixtures.simple("s-mine01", "Work")
        val m = RoutineMigration.migrate(listOf(work), listOf(mine))
        val s = m.skills.single { it.id == m.links["work"] }
        assertEquals("Work 2", s.name)
        assertTrue(s.namesEn.isEmpty() && s.namesFr.isEmpty())
        assertEquals("Work", m.routines.single().name)
        assertEquals(mine, m.skills[0])
        assertEquals(SkillFile.Ok(m.skills), SkillCodec.decode(SkillCodec.encode(m.skills)))
        // The brief too, when one of yours is called that.
        val b = RoutineMigration.migrate(emptyList(), listOf(SkillFixtures.simple("s-mine02", "Morning brief")))
        assertEquals("Morning brief 2", b.skills.single { RoutineMigration.builtIn(it) }.name)
        assertFalse(RoutineMigration.migrate(m.routines, m.skills).changed)
    }

    @Test fun aRoutineThatCantBeASkillStaysAsItWasAndIsCounted() {
        val call = routine("call", "Ask", RoutineStep.Tool(Action.AskHelper("news")), RoutineStep.Brief)
        val nearest = routine("near", "Gas", RoutineStep.Tool(Action.MapsSearch("gas station", nearest = true)))
        val template = routine("tpl", "Odd", RoutineStep.Say("Hi {{who}}"))
        val long = routine("long", "A routine name that is far too long for a skill name", RoutineStep.Brief)
        val m = RoutineMigration.migrate(listOf(call, wake, nearest, template, long), emptyList())
        assertEquals(4, m.refused)
        assertEquals(listOf(call, wake.copy(skill = m.links["wake"]), nearest, template, long), m.routines)
        assertEquals(2, m.skills.size)
        for (id in listOf("call", "near", "tpl", "long")) assertNull(m.links[id])
        val again = RoutineMigration.migrate(m.routines, m.skills)
        assertEquals(4, again.refused)
        assertFalse(again.changed)
    }

    @Test fun aFullSkillsFileLeavesTheRoutineUnlinked() {
        val full = (1..SkillCheck.MAX_SKILLS - 1).map { SkillFixtures.simple("s-f%05d".format(it), "Skill $it") }
        val m = RoutineMigration.migrate(listOf(wake), full)
        assertEquals(1, m.refused)
        assertNull(m.routines.single().skill)
        assertEquals(SkillCheck.MAX_SKILLS, m.skills.size)
    }
}
