// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.connect.StepByVoice.ScreenOnly
import app.heyrobot.core.connect.StepByVoice.OnYes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun allowed(c: Connection) = c.copy(answers = mapOf(ConnConsent.origin(c)!! to ConsentAnswer.ALLOW))
private fun haOp(id: String, title: String, domain: String, service: String, entity: String) =
    ConnOp(id, title, OpSpec.HaCall(domain, service), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf(entity), default = entity)))

/** Review M2 (D14): what a spoken yes never approves is added on the screen only, like a lock, a door or an alarm. */
class StepByVoiceRiskTest {
    private val ha = allowed(Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(
            haOp("porch_on", "Porch light on", "light", "turn_on", "light.porch"),
            haOp("lock", "Lock the front door", "lock", "lock", "lock.front_door"),
            haOp("scene", "Evening scene", "scene", "turn_on", "scene.evening"),
            haOp("script", "Bedtime script", "script", "turn_on", "script.bedtime"),
            haOp("group", "Downstairs on", "homeassistant", "turn_on", "group.downstairs"),
        )))
    private val hook = allowed(Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(ConnOp("del", "Clear the list", OpSpec.Webhook(HttpMethod.DELETE, "/items")),
            ConnOp("pay", "Pay the bill", OpSpec.Webhook(HttpMethod.POST, "/bill")).copy(marks = setOf(SkillEffect.MONEY)))))
    private val home: Skill = SkillEditor.newSkill("s-abc123", "Heading home", SkillEditor.step(StepOffer.Say, "On my way."), 1000L, Lang.EN)

    private fun why(c: Connection, op: String) = StepByVoice.screenOnly(home, SkillEditor.step(StepOffer.Operation(c, c.ops.first { it.id == op })), listOf(c))

    @Test fun whatASpokenYesNeverApprovesIsAddedOnTheScreenOnly() {
        assertNull(why(ha, "porch_on"))
        assertEquals(ScreenOnly.SECURITY, why(ha, "lock"))
        for (op in listOf("scene", "script", "group")) assertEquals(op, ScreenOnly.CODE, why(ha, op))
        assertEquals(ScreenOnly.RISKY, why(hook, "del"))
        assertEquals(ScreenOnly.RISKY, why(hook, "pay"))
        // A connection you haven't allowed yet: its first-use card is never a spoken yes.
        assertEquals(ScreenOnly.FIRST_USE, why(ha.copy(answers = emptyMap()), "porch_on"))
    }

    @Test fun aSecurityEntityPickedAtWhichOneIsRefusedThere() {
        val covers = allowed(ha.copy(ops = listOf(ConnOp("covers", "Covers open", OpSpec.HaCall("cover", "open_cover", classes = mapOf("cover.blinds" to "blind", "cover.garage" to "garage")),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("cover.blinds", "cover.garage")))))))
        val op = covers.ops.single()
        val step = SkillEditor.step(StepOffer.Operation(covers, op))
        // Nothing picked yet: the question comes ("Which one…").
        assertNull(StepByVoice.screenOnly(home, step, listOf(covers)))
        val added = SkillEditor.add(home, step)!!
        val i = added.steps.size - 1
        val slot = StepByVoice.missing(step, covers, op).single()
        val garage = StepByVoice.value(added, i, slot, "the garage", listOf(covers)) as? StepByVoice.Value.Refused
        assertEquals(ScreenOnly.SECURITY, garage?.why)
        assertEquals("Home Assistant", garage?.conn)
        assertEquals("cover.blinds", (StepByVoice.value(added, i, slot, "blinds", listOf(covers)) as StepByVoice.Value.Filled).skill.steps[i].args[ConnOp.ENTITY])
        assertTrue(StepByVoice.value(added, i, slot, "the attic", listOf(covers)) is StepByVoice.Value.Bad)
    }

    @Test fun onlyWhatASpokenYesMayRunIsAddedByVoice() {
        val post = allowed(Connection("c-wh0002", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!, auth = ConnAuth.Bearer,
            ops = listOf(ConnOp("log", "Log a run", OpSpec.Webhook(HttpMethod.POST, "/hook")))))
        // Run time wants a tap for these (ConnStep's spokenYes), so they are added on the screen.
        assertEquals(ScreenOnly.TAP, why(post, "log"))
        assertEquals(ScreenOnly.TAP, why(ha.copy(spokenYes = false), "porch_on"))
        assertNull(why(ha, "porch_on"))
        assertEquals("A spoken yes can't approve “Log a run”, so add it on the screen.", StepByVoice.refusal(ScreenOnly.TAP, "Log a run", "n8n", driving = false).line.en())
    }
}
