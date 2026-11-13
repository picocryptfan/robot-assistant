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

/** Review M1: the yes adds the step to the skill as saved at that moment, re-checked with the connections as they are then. */
class StepByVoiceYesTest {
    private val ha = allowed(Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(haOp("porch_on", "Porch light on", "light", "turn_on", "light.porch"))))
    private val home: Skill = SkillEditor.newSkill("s-abc123", "Heading home", SkillEditor.step(StepOffer.Say, "On my way."), 1000L, Lang.EN)
    private val step = SkillEditor.step(StepOffer.Operation(ha, ha.ops[0]))
    private val snapshot = SkillEditor.add(home, step)!!
    private val i = snapshot.steps.size - 1
    // A touch edit made while the card waited: another step.
    private val edited = SkillEditor.add(home, SkillEditor.step(StepOffer.Say, "Almost there."))!!

    private fun not(r: OnYes): String = (r as OnYes.Not).line.en()

    @Test fun theYesAddsToTheSkillAsSavedNow() {
        val r = StepByVoice.onYes(edited, snapshot, i, "Porch light on", listOf(ha)) as OnYes.Save
        assertEquals(edited.steps + step, r.skill.steps)
    }

    @Test fun nothingIsSavedWhenTheSkillOrTheConnectionWentAway() {
        assertEquals(SkillStrings.addGone("name" to "Heading home").en(), not(StepByVoice.onYes(null, snapshot, i, "Porch light on", listOf(ha))))
        val gone = SkillStrings.addConnGone("step" to "Porch light on").en()
        assertEquals(gone, not(StepByVoice.onYes(edited, snapshot, i, "Porch light on", emptyList())))
        assertEquals(gone, not(StepByVoice.onYes(edited, snapshot, i, "Porch light on", listOf(ha.copy(ops = emptyList())))))
    }

    @Test fun aStepThatNowNeedsTheScreenIsNotSaved() {
        val marked = ha.copy(ops = listOf(ha.ops[0].copy(marks = setOf(SkillEffect.SECURITY))))
        assertEquals(SkillStrings.addNowScreen("step" to "Porch light on").en(), not(StepByVoice.onYes(edited, snapshot, i, "Porch light on", listOf(marked))))
        val full = (1 until SkillCheck.MAX_STEPS).fold(home) { s, n -> SkillEditor.add(s, SkillEditor.step(StepOffer.Say, "Step $n."))!! }
        assertEquals(SkillStrings.addFull("name" to "Heading home", "max" to SkillCheck.MAX_STEPS.toString()).en(),
            not(StepByVoice.onYes(full, snapshot, i, "Porch light on", listOf(ha))))
    }
}
