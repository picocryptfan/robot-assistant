// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.HelperName
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

/** D15: a skill's names against every router before the model, in every form that names a skill, and against your own names. */
class SkillNamesTest {
    private val now = ZonedDateTime.parse("2026-10-19T10:00:00-04:00[America/Toronto]")
    private val jarvis = HelperName("h-jarvis", listOf(listOf("jarvis")))
    private val ctx = NameContext(
        skills = listOf(SkillFixtures.headingHome(), SkillFixtures.pasta()),
        routines = listOf("Evening routine", "Leaving work"),
        locks = listOf("Bedtime"),
        helpers = listOf(HelperName.CLAUDE, jarvis),
        helperNames = listOf("Claude", "Jarvis"),
        wake = listOf("hey robot", "computer"),
        scripts = listOf("backup", "sync photos"),
    )

    private fun why(name: String, selfId: String? = null) = SkillNames.check(name, selfId, ctx, now)?.render(Langs.EN)

    @Test fun ordinaryNamesPass() {
        for (n in listOf("Team reminder", "Good night", "Lights", "Gym bag", "je pars", "Coffee break", "Bedtime story"))
            assertNull(n, why(n))
        // A skill's own names are its own.
        assertNull(why("Heading home", "s-k3f9q2"))
        assertNull(why("on my way home", "s-k3f9q2"))
    }

    /** The Test Bench's names (SK1 to SK13) are free on a phone with no skills yet; SK11's marker is no secret. */
    @Test fun theTestBenchsNames() {
        val empty = ctx.copy(skills = emptyList())
        for (n in listOf("Heading home", "Pasta", "je rentre", "Team reminder", "zq9marker", "on my way home"))
            assertNull(n, SkillNames.check(n, null, empty, now)?.render(Langs.EN))
        val marker = SkillFixtures.simple("s-zq9mrk", "zq9marker").copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "zq9marker2"))))
        assertTrue(SkillCheck.problems(marker).toString(), SkillCheck.valid(marker))
        val text = SkillFixtures.simple("s-zq9txt", "zq9marker").copy(steps = listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "zq9marker2"))))
        assertTrue(SkillCheck.problems(text).toString(), SkillCheck.valid(text))
    }

    @Test fun yourOtherNames() {
        assertEquals(SkillStrings.nameSkill("name" to "Heading home").render(Langs.EN), why("Heading home"))
        assertEquals(SkillStrings.nameSkill("name" to "Je rentre").render(Langs.EN), why("Je rentre"))
        assertEquals(SkillStrings.nameRoutine("name" to "Leaving work").render(Langs.EN), why("Leaving work"))
        assertEquals(SkillStrings.nameRoutine("name" to "evening").render(Langs.EN), why("evening"))
        assertEquals(SkillStrings.nameLock("name" to "Bedtime").render(Langs.EN), why("Bedtime"))
        assertEquals(SkillStrings.nameHelper("name" to "Jarvis").render(Langs.EN), why("Jarvis"))
        assertEquals(SkillStrings.nameHelper("name" to "Claude").render(Langs.EN), why("Claude"))
        assertEquals(SkillStrings.nameScript("name" to "Sync photos").render(Langs.EN), why("Sync photos"))
        assertEquals(SkillStrings.nameWake("name" to "Computer").render(Langs.EN), why("Computer"))
        assertEquals(SkillStrings.nameWake("name" to "hey robot lights").render(Langs.EN), why("hey robot lights"))
        assertEquals(SkillStrings.nameCommand("word" to "Stop").render(Langs.EN), why("Stop"))
        assertEquals(SkillStrings.nameCommand("word" to "thanks").render(Langs.EN), why("thanks"))
    }

    @Test fun robotsOwnPhrasesInEveryForm() {
        val taken = mapOf(
            "car mode" to SkillStrings.whatHandsFree, "read my messages" to SkillStrings.whatMessages, "lock the phone for 10 minutes" to SkillStrings.whatLock,
            "end the lock" to SkillStrings.whatLock, "my evening routine" to SkillStrings.whatRoutines, "remember that sam likes tea" to SkillStrings.whatMemory,
            "ask jarvis the time" to SkillStrings.whatHelpers, "free up memory" to SkillStrings.whatFreeUp, "report a bug" to SkillStrings.whatFeedback,
            "transcribe this conversation" to SkillStrings.whatConversation, "search the web for cats" to SkillStrings.whatWeb, "brief me" to SkillStrings.whatRoutines,
        )
        for ((name, what) in taken) {
            val got = why(name)
            assertNotNull(name, got)
            assertTrue("$name: $got", got == SkillStrings.nameRobot("name" to name, "what" to what()).render(Langs.EN) ||
                got == SkillStrings.nameRoutine("name" to name).render(Langs.EN))
        }
        // "start car mode" is hands-free's: a skill called "car mode" is refused.
        assertNotNull(SkillNames.claimedBy("start car mode", ctx, now))
    }

    @Test fun theForms() {
        val f = SkillNames.forms("pasta")
        for (x in listOf("pasta", "run pasta", "do pasta", "start pasta", "lance pasta", "fais pasta", "part pasta", "pasta for 5 minutes", "pasta saying hi",
                "what does pasta do", "delete the skill pasta", "efface la competence pasta")) assertTrue(x, x in f)
    }

    @Test fun rulesOfANameAndOtherNames() {
        assertEquals(SkillStrings.nameRules().render(Langs.EN), why(""))
        assertEquals(SkillStrings.nameRules().render(Langs.EN), why("x".repeat(41)))
        assertEquals(SkillStrings.otherNameRules().render(Langs.EN), SkillNames.check("one two three four five six", null, ctx, now, other = true)?.render(Langs.EN))
        assertNull(SkillNames.check("on my way", null, ctx, now, other = true))
    }

    @Test fun whatRunsFirstIsNotedOnThePage() {
        val s = SkillFixtures.simple("s-later1", "Leaving work")
        assertEquals(listOf(SkillStrings.shadowsRoutine()), SkillNames.shadows(s, ctx))
        assertEquals(listOf(SkillStrings.shadowsScript()), SkillNames.shadows(SkillFixtures.simple("s-later2", "Backup"), ctx))
        assertEquals(listOf(SkillStrings.shadowsLock()), SkillNames.shadows(SkillFixtures.simple("s-later3", "Bedtime"), ctx))
        assertEquals(emptyList<Any>(), SkillNames.shadows(SkillFixtures.pasta(), ctx))
    }

    @Test fun aOneWordNameIsCommon() {
        assertTrue(SkillNames.common("Lights"))
        assertTrue(!SkillNames.common("Heading home"))
    }
}
