// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.TextTarget
import app.heyrobot.core.TextVia
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Teaching (plan §6, D8, D9) and "save that" (D12, D13): the save card's draft. */
class SkillTeachTest {
    private fun teach(name: String, steps: String, lang: Lang = Lang.EN, content: Boolean = false) =
        SkillTeach.teach(name, steps, lang, "s-new123", 5_000L, content)

    @Test fun stepsAndTheirSlots() {
        val d = teach("heading home", "text Sam on my way home, then open the route home")
        assertEquals(listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "on my way home")),
            SkillStep.of(StepKind.MAPS_NAVIGATE, mapOf("destination" to "home"))), d.steps)
        assertEquals(listOf(Slot(0, "to", ParamType.PERSON, "Sam"), Slot(0, "message", ParamType.TEXT, "on my way home"),
            Slot(1, "destination", ParamType.PLACE, "home")), d.slots)
        val s = d.skill()
        assertEquals("Heading home", s.name)
        assertEquals(listOf("heading home"), s.namesEn)
        assertEquals(SkillLevel.ASK_FIRST, s.level)
        assertEquals(SkillMade.VOICE, s.provenance.made)
        assertTrue(SkillCheck.problems(s).toString(), SkillCheck.valid(s))
        assertTrue(d.yesSaves)
    }

    @Test fun aSlotAskedEachTimeBecomesAQuestion() {
        val d = teach("pasta", "set a timer for 12 minutes, then text Marie pasta's on")
        val length = d.slots.first { it.type == ParamType.DURATION }
        assertEquals("720", length.value)
        val asked = d.toggle(length)
        val s = asked.skill()
        assertEquals(listOf(SkillParam("length", ParamType.DURATION, "720", "How long?", "Combien de temps ?")), s.params)
        assertEquals("{{length}}", s.steps[0].args["seconds"])
        assertTrue(SkillCheck.problems(s).toString(), SkillCheck.valid(s))
        assertEquals(Action.SetTimer(480), SkillCheck.action(s, s.steps[0], mapOf("length" to "480")))
        // Another tap makes it fixed again; at most 3 asked.
        assertEquals(d, asked.toggle(length))
        val all = d.slots.fold(d) { x, slot -> x.toggle(slot) }
        assertEquals(3, all.asked.size)
        // The card's chip.
        assertEquals("12 minutes · asked each time", length.chip(true).render(Langs.EN))
    }

    @Test fun anAlarmsTimeIsOneQuestion() {
        val d = teach("wake up", "set an alarm for 7 am")
        val slot = d.slots.single()
        assertEquals(Slot(0, "hour", ParamType.TIME, "07:00"), slot)
        val s = d.toggle(slot).skill()
        assertEquals(mapOf("hour" to "{{time}}"), s.steps[0].args)
        assertTrue(SkillCheck.valid(s))
    }

    @Test fun unparsedAndRefusedParts() {
        val d = teach("cozy", "turn on the flashlight, then make it cozy, then ask Claude for a poem, then read my messages")
        assertEquals(listOf(StepKind.FLASHLIGHT), d.steps.map { it.kind })
        assertEquals(listOf("make it cozy"), d.unparsed)
        assertEquals(2, d.cantDo.size)
        val card = SkillCards.save(d, null)
        val lines = card.lines.map { it.render(Langs.EN) }
        assertTrue(lines.toString(), "Robot didn't understand: “make it cozy”" in lines)
        assertTrue(lines.toString(), lines.any { it.startsWith("Skills can't do this") })
    }

    @Test fun inFrench() {
        val d = teach("je rentre", "texte à Sam que je m'en viens, pis ouvre l'itinéraire vers la maison", Lang.FR)
        val s = d.skill()
        assertEquals("Je rentre", s.name)
        assertEquals(emptyList<String>(), s.namesEn)
        assertEquals(listOf("je rentre"), s.namesFr)
        assertEquals(listOf(StepKind.SEND_TEXT, StepKind.MAPS_NAVIGATE), s.steps.map { it.kind })
    }

    @Test fun aNameWithNoSteps() {
        // D8: the next sentence is the steps only when one parses.
        assertTrue(SkillTeach.anyStep("text Sam on my way home, then open the route home"))
        assertFalse(SkillTeach.anyStep("hello there"))
        val empty = teach("heading home", "hello there")
        assertTrue(empty.steps.isEmpty())
        assertFalse(empty.yesSaves)
        assertEquals(listOf(SkillStrings.noSteps()), empty.problems)
    }

    @Test fun madeFromContentSavesByTapOnly() {
        val d = teach("heading home", "text Sam on my way home", content = true)
        assertFalse(d.yesSaves)
        assertTrue(d.skill().provenance.fromContent)
        // The card offers no Just do it.
        val card = SkillCards.save(d, null)
        assertFalse(card.levelChip)
        assertEquals(SkillStrings.tapToSave("save" to app.heyrobot.core.i18n.UiLabel(app.heyrobot.core.i18n.UiStrings.save)), card.footer)
        assertEquals(SkillLevel.ASK_FIRST, d.copy(level = SkillLevel.JUST_DO_IT).skill().level)
    }

    @Test fun saveThatFromTheConversationsActions() {
        val marie = TextTarget("Marie", "+15145550101", "+1 514-555-0101", "Messages", TextVia.SMS)
        val actions = listOf(
            RecentAction(Action.SetTimer(720), ActionStatus.DONE),
            RecentAction(Action.SendText("Marie", "pasta's on", marie), ActionStatus.DRAFT_OPENED),
            RecentAction(Action.Weather(null, null), ActionStatus.FAILED),
            RecentAction(Action.OpenApp("Camera"), ActionStatus.DONE, helper = true),
            RecentAction(Action.Remember("Sam likes tea"), ActionStatus.DONE),
        )
        val d = SkillFromActions.draft(actions, null, Lang.EN, "s-save12", 9L, fromContent = false)!!
        // The number the card resolved is never saved: Marie is found again when the skill runs.
        assertEquals(listOf(SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to 720L)), SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Marie", "message" to "pasta's on"))), d.steps)
        assertEquals(listOf(SkillStrings.leftOutHelper(), SkillStrings.leftOutFailed()), d.leftOut)
        assertEquals(1, d.cantDo.size)
        assertEquals("Timer and text", d.skill().name)
        assertEquals(SkillMade.DEMO, d.skill().provenance.made)
        assertEquals("Timer and text 2", SkillFromActions.defaultName(d.steps, Lang.EN, setOf("Timer and text")))
        assertEquals("Minuterie et texto", SkillFromActions.defaultName(d.steps, Lang.FR))
        // A name given: kept. Content: from_content.
        val named = SkillFromActions.draft(actions, "Pasta", Lang.EN, "s-save12", 9L, fromContent = true)!!
        assertEquals("Pasta", named.skill().name)
        assertTrue(named.skill().provenance.fromContent)
        // At most 8, the last ones.
        val many = (1..10).map { RecentAction(Action.SetTimer(it * 60L), ActionStatus.DONE) }
        val eight = SkillFromActions.draft(many, "Timers", Lang.EN, "s-save12", 9L, false)!!
        assertEquals(8, eight.steps.size)
        assertEquals(180L, eight.steps.first().args["seconds"])
        // Nothing that happened: nothing to save.
        assertNull(SkillFromActions.draft(listOf(RecentAction(Action.SetTimer(60), ActionStatus.DECLINED)), null, Lang.EN, "s-save12", 9L, false))
    }
}
