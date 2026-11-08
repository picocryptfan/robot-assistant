// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.Routine
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.RoutineStore
import app.heyrobot.core.RoutineTrigger
import app.heyrobot.core.skills.RoutineSkills
import app.heyrobot.core.skills.SkillStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

/** Skills 1.3 plan S3.2: routines become skills on the first start, in main and :voice, whichever is first, once. */
class RoutineSkillsStartTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root: File = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile

    @Test fun itRunsInTheTwoProcessesThatWriteRoutinesAndSkills() {
        assertTrue(RoutineSkillsStart.runsIn("app.heyrobot.assistant"))
        assertTrue(RoutineSkillsStart.runsIn("app.heyrobot.assistant:voice"))
        for (p in listOf("brain", "mt", "link", "lock", "voiceless")) assertFalse(p, RoutineSkillsStart.runsIn("app.heyrobot.assistant:$p"))
        // Every process's first code (RobotApp) starts it with its own name, off the main thread.
        val app = File(root, "app/src/main/java/app/heyrobot/RobotApp.kt").readText()
        assertTrue(app.contains("app.heyrobot.sys.RoutineSkillsStart.start(this, p)"))
        val sys = File(root, "app/src/main/java/app/heyrobot/sys/Routines.kt").readText()
        assertTrue(sys.contains("Thread({ run(app) }, \"routine-skills\").start()"))
    }

    @Test fun mainAndVoiceStartingTogetherMakeOneSetOfSkills() {
        val dir = tmp.newFolder("robot")
        val r = Routine("tea", "Tea", RoutineTrigger.At(LocalTime.of(7, 30), setOf(java.time.DayOfWeek.MONDAY)),
            listOf(RoutineStep.Say("Kettle on")), created = 1, edited = 1)
        assertTrue(RoutineStore(dir).save(r))
        // Two processes are two class loaders' worth of state: here, each one's first run is its own migrate.
        val first = listOf(Thread { RoutineSkills.migrate(RoutineStore(dir), SkillStore(dir)) }, Thread { RoutineSkills.migrate(RoutineStore(dir), SkillStore(dir)) })
        first.forEach { it.start() }
        first.forEach { it.join(10_000) }
        assertEquals(2, SkillStore(dir).skills().size)
        val once = RoutineSkills.once(dir)
        assertFalse(once!!.changed)
        assertNull(RoutineSkills.once(dir))
        assertEquals(SkillStore(dir).skills().single { it.id != "s-mbrief" }.id, RoutineStore(dir).get("tea")!!.skill)
    }
}
