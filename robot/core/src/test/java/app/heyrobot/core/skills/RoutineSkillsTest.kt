// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ReadyCard
import app.heyrobot.core.ReadyCards
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.RoutinePlan
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.RunOrigin
import app.heyrobot.core.StepGo
import app.heyrobot.core.ToolCalls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Skills 1.3 plan S3.2: routines as skills on the files, under both locks, from either process. */
class RoutineSkillsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tool(call: String, vararg args: Pair<String, Any?>): RoutineStep =
        RoutineStep.Tool((ToolCalls.parse(RawToolCall(call, mapOf(*args))) as ParsedCall.Ok).action)

    private val weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)

    private fun routine(id: String, name: String, vararg steps: RoutineStep) =
        Routine(id, name, RoutineTrigger.At(LocalTime.of(7, 30), weekdays), steps.toList(), created = 1760800000000, edited = 1760900000000)

    private val wake = routine("wake", "Wake up", RoutineStep.Brief, tool("open_app", "name" to "Organic Maps"), RoutineStep.Say("Drink some water"))
    private val night = routine("night", "Good night", tool("flashlight", "on" to false), RoutineStep.Reminders, RoutineStep.Messages)

    private fun dir(vararg routines: Routine): File = tmp.newFolder().also { d -> routines.forEach { assertTrue(RoutineStore(d).save(it)) } }

    private fun bytes(d: File) = listOf("routines.json", "skills.json").map { File(d, it).takeIf { f -> f.isFile }?.readText() }

    @Test fun theMigrationRunsOnceUnderBothLocksFromEitherProcess() {
        val d = dir(wake, night)
        // Main and :voice start together: each with its own stores, as two processes have.
        val pool = Executors.newFixedThreadPool(4)
        val go = CountDownLatch(1)
        val runs = (1..4).map { pool.submit<RoutineMigrated?> { go.await(); RoutineSkills.migrate(RoutineStore(d), SkillStore(d)) } }
        go.countDown()
        val out = runs.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(out.all { it != null })
        assertEquals(1, out.count { it!!.changed })
        val skills = SkillStore(d).skills()
        assertEquals(listOf(RoutineMigration.BRIEF_SKILL, "Wake up", "Good night"), skills.map { if (RoutineMigration.builtIn(it)) it.id else it.name })
        val routines = RoutineStore(d).routines()
        assertEquals(skills.drop(1).map { it.id }, routines.map { it.skill })
        // Each entry point's once: the first does nothing more, a second is skipped, and the files stay byte for byte.
        val before = bytes(d)
        assertFalse(RoutineSkills.once(d)!!.changed)
        assertNull(RoutineSkills.once(d))
        assertEquals(before, bytes(d))
        assertEquals(3, SkillStore(d).skills().size)
    }

    @Test fun aMigrationThatCantFinishChangesNothing() {
        val d = dir(wake)
        File(d, "skills.json").writeText("""{"v":99,"skills":[]}""")
        val before = bytes(d)
        assertNull(RoutineSkills.migrate(RoutineStore(d), SkillStore(d)))
        assertEquals(before, bytes(d))
        val e = dir(wake)
        File(e, "routines.json").writeText("{not json")
        assertNull(RoutineSkills.migrate(RoutineStore(e), SkillStore(e)))
        assertFalse(File(e, "skills.json").exists())
    }

    @Test fun aRoutineAndItsSkillKeepTheSameSteps() {
        val d = dir(wake, night)
        val rs = RoutineStore(d)
        val ss = SkillStore(d)
        RoutineSkills.migrate(rs, ss)
        val linked = rs.routines().first { it.id == "night" }
        // Settings › Routines: the routine's new steps go to its skill; an edit's own link is never taken.
        assertTrue(RoutineSkills.save(rs, ss, linked.copy(steps = linked.steps.take(2), skill = null)))
        val sk = ss.get(linked.skill!!)!!
        assertEquals(listOf(StepKind.FLASHLIGHT, StepKind.REMINDERS), sk.steps.map { it.kind })
        assertEquals(linked.skill, rs.get("night")!!.skill)
        // Settings › Skills: the skill's steps go back to the routine; one a routine can't run by itself isn't saved.
        assertTrue(RoutineSkills.saveSkill(rs, ss, sk.copy(steps = sk.steps.reversed())))
        assertEquals(listOf(RoutineStep.Reminders, linked.steps[0]), rs.get("night")!!.steps)
        val asks = SkillFixtures.headingHome()
        assertFalse(RoutineSkills.saveSkill(rs, ss, sk.copy(params = asks.params, steps = asks.steps)))
        assertEquals(2, ss.get(sk.id)!!.steps.size)
        // A routine's skill goes only with its routine; the brief's never.
        assertFalse(RoutineSkills.deleteSkill(rs, ss, sk.id))
        assertFalse(RoutineSkills.deleteSkill(rs, ss, RoutineMigration.BRIEF_SKILL))
        assertTrue(RoutineSkills.delete(rs, ss, "night"))
        assertNull(ss.get(sk.id))
        // A step skills don't have (the nearest place): the routine goes on alone, its skill is gone, and nothing is made again.
        val w = rs.get("wake")!!
        val nearest = RoutineStep.Tool(((ToolCalls.parse(RawToolCall("maps_search", mapOf("query" to "coffee"))) as ParsedCall.Ok).action
            as app.heyrobot.core.Action.MapsSearch).copy(nearest = true))
        assertTrue(RoutineSkills.save(rs, ss, w.copy(steps = w.steps + nearest)))
        assertNull(rs.get("wake")!!.skill)
        assertNull(ss.get(w.skill!!))
        assertFalse(RoutineSkills.migrate(rs, ss)!!.changed)
        // A new routine is linked as it is saved.
        assertTrue(RoutineSkills.save(rs, ss, routine("tea", "Tea", tool("set_timer", "minutes" to 4))))
        assertNotNull(rs.get("tea")!!.skill?.let { ss.get(it) })
    }

    @Test fun aTriggeredRunNeverGetsACardAndAnAskedOneDoes() {
        val d = dir(wake)
        RoutineSkills.migrate(RoutineStore(d), SkillStore(d))
        val r = RoutineStore(d).get("wake")!!
        val skills = SkillStore(d).skills().map { if (it.id == r.skill) it.copy(level = SkillLevel.ASK_FIRST) else it }
        assertEquals(SkillGo.Card(tapOnly = false), RoutineSkills.go(r, skills, RunOrigin.ASKED, messages = false))
        assertEquals(SkillGo.Card(tapOnly = true), RoutineSkills.go(r, skills, RunOrigin.ASKED, messages = true))
        assertNull(RoutineSkills.go(r, skills, RunOrigin.UNATTENDED, messages = false))
        assertNull(RoutineSkills.go(r, skills, RunOrigin.READY_TAP, messages = false))
        val off = skills.map { if (it.id == r.skill) it.copy(level = SkillLevel.OFF) else it }
        assertTrue(RoutineSkills.go(r, off, RunOrigin.ASKED, messages = false) is SkillGo.Refused)
        assertNull(RoutineSkills.go(r, off, RunOrigin.UNATTENDED, messages = false))
        // A routine with no skill runs as in 0.8, and a triggered one's steps get the same gate as before it had one.
        assertNull(RoutineSkills.go(r.copy(skill = null), skills, RunOrigin.ASKED, messages = false))
        val s = RobotSettings()
        val rs = RoutineSettings()
        assertEquals(RoutinePlan.plan(wake, RunOrigin.UNATTENDED, false, s, rs), RoutinePlan.plan(r, RunOrigin.UNATTENDED, false, s, rs))
    }

    /** The done-when's desk run: a 7:30 routine migrated, fired, and its app step waiting on a Ready card. */
    @Test fun aMigratedSevenThirtyRoutineFiresAndLeavesItsReadyCard() {
        val d = dir(wake)
        val store = RoutineStore(d)
        assertNotNull(RoutineSkills.once(d))
        val r = store.get("wake")!!
        val sk = SkillStore(d).get(r.skill!!)!!
        assertEquals(RoutineTrigger.At(LocalTime.of(7, 30), weekdays), r.trigger)
        assertEquals(r.steps, RoutineSkills.routineSteps(sk))
        val plan = RoutinePlan.plan(r, RunOrigin.UNATTENDED, false, RobotSettings(), RoutineSettings())
        val held = plan.filter { it.go == StepGo.READY || it.go == StepGo.CONFIRM }
        assertEquals(listOf(r.steps[1]), held.map { it.step })
        val today = LocalDate.of(2026, 11, 4)
        store.editReady { ReadyCards.add(it, ReadyCard(r.id, r.name, held[0].step, 1762252200000, today), today) }
        val card = store.ready().single()
        assertEquals(r.steps[1], card.step)
        // Its tap is a Ready card's, which the skill's card never stands in for.
        assertNull(RoutineSkills.go(r, listOf(sk.copy(level = SkillLevel.ASK_FIRST)), RunOrigin.READY_TAP, messages = false))
        assertTrue(RoutinePlan.runs(RoutineGate.decide(card.step, RunOrigin.READY_TAP, false, r, RobotSettings(), RoutineSettings())))
    }
}
