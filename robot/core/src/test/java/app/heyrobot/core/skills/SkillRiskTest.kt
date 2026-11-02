// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills connections plan D11, §5.4 (design §5.2): each step's points by its effects (+3 tells someone, writes online or
 * runs code, +1 changes your home), +2 per text value and +1 per other value the model filled, +1 per step past 2, +2 per
 * use of free text a step brought back; Simple 0 to 2, Careful 3 to 5, Risky 6 or more; deletes, security or money make a
 * skill Risky whatever its points. 1.1's skills score as they did.
 */
class SkillRiskTest {
    private val links = SkillFixtures.links

    private fun skill(params: List<SkillParam>, vararg steps: SkillStep) = SkillFixtures.pasta().copy(params = params, steps = steps.toList())

    private val room = SkillFixtures.room
    private fun dim(entity: Any = "{{room}}") = SkillStep.of(StepKind.CONN, mapOf("entity" to entity, "brightness" to 20L), conn = SkillFixtures.HA, op = "light_dim")

    @Test fun the11SkillsScoreAsBefore() {
        val team = SkillLibrary.skills.getValue("team")
        assertEquals(5, SkillRisk.points(team, setOf("message"), SkillLinks.NONE))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(team, setOf("message"), SkillLinks.NONE))
        assertEquals(0, SkillRisk.points(team, emptySet(), SkillLinks.NONE))
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(team, emptySet(), SkillLinks.NONE))
        val pasta = SkillFixtures.pasta()
        assertEquals(4, SkillRisk.points(pasta, setOf("length"), SkillLinks.NONE))
        for (s in SkillLibrary.skills.values) for (g in listOf(emptySet(), s.params.map { it.name }.toSet())) {
            val sends = s.steps.count { it.kind == StepKind.SEND_TEXT || it.kind == StepKind.DRAFT_EMAIL } * 3
            val old = if (g.isEmpty()) 0 else maxOf(0, s.steps.size - 2) + s.params.sumOf { if (it.type == ParamType.TEXT) 2L else 1L }.toInt() + sends
            assertEquals(s.name, old, SkillRisk.points(s, g, links))
        }
    }

    @Test fun d11sPointsAndTiers() {
        // One model-filled choice in a one-step home change: 1 + 1 = 2, Simple.
        val one = skill(listOf(room), dim())
        assertEquals(2, SkillRisk.points(one, setOf("room"), links))
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(one, setOf("room"), links))
        // A second value makes it Careful (3), and so does a third step.
        val two = skill(listOf(room, SkillFixtures.level), dim().copy(args = mapOf("entity" to "{{room}}", "brightness" to "{{level}}")))
        assertEquals(3, SkillRisk.points(two, setOf("room", "level"), links))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(two, setOf("room", "level"), links))
        val three = skill(listOf(room), dim(), dim("light.porch"), dim("light.porch"))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(three, setOf("room"), links))
        // "Tell the family <message>" through ntfy, the message filled by the model: +3 + 2 = 5, Careful.
        val text = SkillParam("message", ParamType.TEXT, "I'm home", "What should it say?", "Ça dit quoi?")
        val ntfy = skill(listOf(text), SkillStep.of(StepKind.CONN, mapOf("message" to "{{message}}"), conn = SkillFixtures.NTFY, op = "publish"))
        assertEquals(5, SkillRisk.points(ntfy, setOf("message"), links))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(ntfy, setOf("message"), links))
        // rc4, CN8: its card takes a yes (it only starts the skill; ConnStepTest: the step's own card is tap only), unless content or a message is in play.
        val asks = ntfy.copy(level = SkillLevel.ASK_FIRST)
        assertEquals(SkillGo.Card(tapOnly = false), SkillPolicy.decide(asks, locked = false, phoneLock = false, messages = false, links = links))
        assertEquals(SkillGo.Card(tapOnly = true), SkillPolicy.decide(asks, locked = false, phoneLock = false, messages = true, links = links))
        // A script with one model-filled number: +3 + 1 = 4, plus its `# effect: online` (+3) here.
        val days = SkillFixtures.days
        val script = skill(listOf(days, SkillFixtures.folder), SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf("script" to "backup", "folder" to "{{folder}}", "days" to "{{days}}")))
        assertEquals(7, SkillRisk.points(script, setOf("days"), links))
        val plain = object : SkillLinks by links {
            override fun script(name: String) = links.script(name)?.copy(effects = SkillEffects.script(emptyList()))
        }
        assertEquals(4, SkillRisk.points(script, setOf("days"), plain))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(script, setOf("days"), plain))
        // Nothing for the model to do: Simple, whatever the steps.
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(ntfy, emptySet(), links))
    }

    @Test fun deletesSecurityOrMoneyAreRiskyWhateverThePoints() {
        // "Lock the front door": Risky, filled or not (a "Did you mean" for it is tap only).
        val lock = skill(emptyList(), SkillStep.of(StepKind.CONN, mapOf("entity" to "lock.front_door"), conn = SkillFixtures.HA, op = "lock"))
        assertEquals(SkillTier.RISKY, SkillRisk.tier(lock, emptySet(), links))
        assertEquals(ModelGo.Card(tapOnly = true, didYouMean = true), SkillRisk.decide(lock, named = false, filled = false, tier = SkillRisk.tier(lock, emptySet(), links),
            trusted = true, locked = false, phoneLock = false, messages = false, links = links))
        for (mark in SkillEffect.RISKY) {
            val marked = object : SkillLinks by links {
                override fun op(conn: String, op: String) = links.op(conn, op)?.copy(marks = setOf(mark))
            }
            val s = skill(listOf(room), dim())
            assertEquals(mark.name, SkillTier.RISKY, SkillRisk.tier(s, setOf("room"), marked))
        }
    }

    @Test fun freeTextFromAReplyInALaterStepScoresTwo() {
        val read = SkillStep.of(StepKind.CONN, mapOf("entity" to "light.kitchen"), id = "k", conn = SkillFixtures.HA, op = "state")
        val say = SkillStep.of(StepKind.SAY, mapOf("text" to "It's {{k.name}}, {{k.state}}."))
        val s = skill(listOf(room), read, say, dim())
        assertEquals(1, SkillRisk.replyText(s, links))
        // 1 (third step) + 1 (choice) + 1 (home) + 2 (the reply's name in words) = 5.
        assertEquals(5, SkillRisk.points(s, setOf("room"), links))
        val typedOnly = skill(listOf(room), read, SkillStep.of(StepKind.SAY, mapOf("text" to "It's {{k.state}}.")), dim())
        assertEquals(0, SkillRisk.replyText(typedOnly, links))
        // A script's words count too.
        val back = SkillFixtures.backUp()
        assertEquals(0, SkillRisk.replyText(back, links))
        val words = back.copy(steps = back.steps.mapIndexed { i, st -> if (i == 2) st.copy(args = mapOf("message" to "Backup said: {{run.out}}")) else st })
        assertEquals(1, SkillRisk.replyText(words, links))
        assertTrue(SkillRisk.points(words, setOf("folder"), links) == SkillRisk.points(back, setOf("folder"), links) + 2)
    }
}
