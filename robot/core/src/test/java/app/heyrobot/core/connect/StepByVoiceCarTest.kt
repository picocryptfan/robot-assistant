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

/** Review M3: in Car mode the refusal opens nothing on the phone and leaves nothing to open later. */
class StepByVoiceCarTest {
    @Test fun inCarModeTheRefusalOpensNothing() {
        for (w in ScreenOnly.entries) {
            val r = StepByVoice.refusal(w, "Lock the front door", "Home Assistant", driving = true)
            assertFalse(w.name, r.opens)
            assertEquals(SkillStrings.addPark("step" to "Lock the front door").en(), r.line.en())
            assertTrue(w.name, StepByVoice.refusal(w, "Lock the front door", "Home Assistant", driving = false).opens)
        }
        val said = { w: ScreenOnly -> StepByVoice.refusal(w, "X", "Home Assistant", driving = false).line.en() }
        assertEquals(SkillStrings.addSecurity("step" to "X").en(), said(ScreenOnly.SECURITY))
        assertEquals(SkillStrings.addRisky("step" to "X").en(), said(ScreenOnly.RISKY))
        assertEquals(SkillStrings.addCode("step" to "X", "name" to "Home Assistant").en(), said(ScreenOnly.CODE))
        assertEquals(SkillStrings.addFirst("step" to "X", "name" to "Home Assistant").en(), said(ScreenOnly.FIRST_USE))
    }

    /** Review Low 8: the refusal never says the page opened; the panel adds that only once it has launched (else why not). */
    @Test fun theRefusalNeverSaysThePageOpened() {
        for (w in ScreenOnly.entries) {
            val r = StepByVoice.refusal(w, "X", "Home Assistant", driving = false).line
            assertFalse(w.name, r.en().contains("opened"))
            assertFalse(w.name, r.render(app.heyrobot.core.i18n.Langs.of(app.heyrobot.core.i18n.Lang.FR)).contains("ouvert"))
        }
    }
}
