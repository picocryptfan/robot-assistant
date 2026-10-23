// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Settings › Skills' edits (plan §9): rev, from_content, the questions fixed at their default, the steps moved and removed. */
class SkillEditTest {
    private val home = SkillFixtures.headingHome()
    private val pasta = SkillFixtures.pasta()

    @Test fun revGoesUpOnlyWhenWhatYouSayOrWhatItDoesChanged() {
        assertEquals(3, SkillEdit.saved(home, home.copy(level = SkillLevel.OFF), 9L).rev)
        assertEquals(4, SkillEdit.saved(home, SkillEdit.rename(home, "Going home"), 9L).rev)
        assertEquals(4, SkillEdit.saved(home, SkillEdit.addName(home, "on my way", Lang.EN), 9L).rev)
        assertEquals(4, SkillEdit.saved(home, SkillEdit.move(home, 1, -1), 9L).rev)
        assertEquals(9L, SkillEdit.saved(home, home.copy(level = SkillLevel.OFF), 9L).provenance.edited)
    }

    @Test fun aSkillFromContentIsYoursOnceYouChangeItsSteps() {
        val c = SkillFixtures.headingHome(fromContent = true)
        assertEquals(listOf(SkillLevel.ASK_FIRST, SkillLevel.OFF), SkillEdit.levels(c))
        assertEquals(SkillLevel.entries, SkillEdit.levels(home))
        // Off (as someone else's restored skill arrives): it turns on at Ask first, then Just do it may be chosen.
        assertEquals(listOf(SkillLevel.ASK_FIRST, SkillLevel.OFF), SkillEdit.levels(home.copy(level = SkillLevel.OFF)))
        // A level or a name leaves it from content.
        assertTrue(SkillEdit.saved(c, c.copy(level = SkillLevel.OFF), 1L).provenance.fromContent)
        assertTrue(SkillEdit.saved(c, SkillEdit.rename(c, "Going home"), 1L).provenance.fromContent)
        // Just do it can't be saved while it is.
        assertEquals(SkillLevel.ASK_FIRST, SkillEdit.saved(c, c.copy(level = SkillLevel.JUST_DO_IT), 1L).level)
        // A step or a question changed, or "These steps are mine": yours.
        assertFalse(SkillEdit.saved(c, SkillEdit.move(c, 0, 1), 1L).provenance.fromContent)
        assertFalse(SkillEdit.saved(c, SkillEdit.question(c, "who", askEn = "Who?"), 1L).provenance.fromContent)
        val mine = SkillEdit.mine(c)
        assertFalse(mine.provenance.fromContent)
        assertEquals(c.rev, SkillEdit.saved(c, mine, 1L).rev)
    }

    @Test fun renamingKeepsTheSaidNameInStep() {
        val r = SkillEdit.rename(home, "Going home")
        assertEquals("Going home", r.name)
        assertEquals(listOf("going home", "on my way home"), r.namesEn)
        assertTrue(SkillCheck.valid(r))
        // Other names: added in their language, removed.
        assertEquals(listOf("je rentre", "je pars"), SkillEdit.addName(home, "Je pars", Lang.FR).namesFr)
        assertEquals(emptyList<String>(), SkillEdit.removeName(home, "je rentre", Lang.FR).namesFr)
    }

    @Test fun removingAQuestionFixesItsValueAtTheDefault() {
        val r = SkillEdit.removeQuestion(home, "who")
        assertEquals(emptyList<SkillParam>(), r.params)
        assertEquals("Sam", r.steps[0].args["to"])
        assertTrue(SkillCheck.valid(r))
        val p = SkillEdit.removeQuestion(pasta, "length")
        assertEquals(720L, p.steps[0].args["seconds"])
        assertTrue(SkillCheck.valid(p))
        val wake = SkillFixtures.simple("s-wake12", "Wake").copy(params = listOf(SkillParam("t", ParamType.TIME, "07:30", "What time?", "À quelle heure?")),
            steps = listOf(SkillStep.of(StepKind.SET_ALARM, mapOf("hour" to "{{t}}"))))
        val fixed = SkillEdit.removeQuestion(wake, "t")
        assertEquals(mapOf("hour" to 7L, "minute" to 30L), fixed.steps[0].args)
        assertTrue(SkillCheck.problems(fixed).toString(), SkillCheck.valid(fixed))
    }

    @Test fun stepsMoveAndGoButOneStays() {
        assertEquals(listOf(StepKind.MAPS_NAVIGATE, StepKind.SEND_TEXT), SkillEdit.move(home, 1, -1).steps.map { it.kind })
        assertEquals(home, SkillEdit.move(home, 0, -1))
        // Removing the text removes its question too.
        val r = SkillEdit.removeStep(home, 0)
        assertEquals(listOf(StepKind.MAPS_NAVIGATE), r.steps.map { it.kind })
        assertEquals(emptyList<SkillParam>(), r.params)
        assertTrue(SkillCheck.valid(r))
        assertEquals(r, SkillEdit.removeStep(r, 0))
    }

    @Test fun defaultsAsTyped() {
        assertEquals("480", SkillEdit.typedDefault(ParamType.DURATION, "8"))
        assertEquals("8", SkillEdit.editableDefault(ParamType.DURATION, "480"))
        assertNull(SkillEdit.typedDefault(ParamType.DURATION, "0"))
        assertEquals("06:30", SkillEdit.typedDefault(ParamType.TIME, "6:30"))
        assertNull(SkillEdit.typedDefault(ParamType.TIME, "25:00"))
        assertEquals("Marie", SkillEdit.typedDefault(ParamType.PERSON, " Marie "))
        assertNull(SkillEdit.typedDefault(ParamType.TEXT, "my password is Tr0ub4dor"))
    }

    @Test fun theRowsWords() {
        val now = ZonedDateTime.of(2026, 10, 19, 18, 0, 0, 0, ZoneId.of("America/Toronto"))
        val at = now.withHour(17).withMinute(32).toInstant().toEpochMilli()
        assertEquals("Ask first · today 17:32 · 2 of 2 done", SkillCards.row(home, SkillRunRecord(at, 2, 2), now).render(Langs.EN))
        assertEquals("Ask first · Never run", SkillCards.row(home, null, now).render(Langs.EN))
        assertEquals("Taught by voice on Sunday October 18", SkillCards.made(home.copy(provenance = home.provenance.copy(created = now.minusDays(1).toInstant().toEpochMilli(),
            edited = now.minusDays(1).toInstant().toEpochMilli())), now.zone).render(Langs.EN))
    }
}
