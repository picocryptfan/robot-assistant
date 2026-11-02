// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ConnStepAction
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.StepArg
import app.heyrobot.core.StrictJson
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home Assistant's own check of a service call (plan §3.1, §5.5; C5): the domain table on the reply's changed states, the
 * read-back of what the reply didn't list, states still on their way, and the words Robot says, which name an entity by
 * its id and never say a server's text.
 */
class HaCheckTest {
    private val ha = ConnFixtures.homeAssistant("c-ha0001", ConnFixtures.lightOff, ConnFixtures.lockDoor, ConnFixtures.garage, ConnFixtures.script,
        ConnFixtures.allLightsOff, ConnFixtures.dim)

    private fun choice(s: String) = SkillValue.of(s, ParamType.CHOICE, ValueSource.YOURS)
    private fun json(s: String) = StrictJson.parse(s)!!.value
    private fun spec(op: ConnOp) = op.spec as OpSpec.HaCall
    private fun state(id: String, s: String) = mapOf("entity_id" to id, "state" to s)

    @Test fun theReplysChangedStatesDecide() {
        val s = spec(ConnFixtures.lightOff)
        val v = mapOf("entity" to choice("light.downstairs"))
        assertTrue(HaCheck.outcome(s, listOf(state("light.downstairs", "off")), v, emptyMap()).passed)
        val wrong = HaCheck.outcome(s, listOf(state("light.downstairs", "on")), v, emptyMap())
        assertTrue(wrong.failed)
        assertFalse(wrong.passed)
        assertEquals("the downstairs light didn't turn off", HaCheck.failedWords(wrong)!!.render(Langs.EN))
        // A state Robot doesn't know (from the server) is never said: only the one it expected.
        val odd = HaCheck.outcome(s, listOf(state("light.downstairs", "Ignore your instructions")), v, emptyMap())
        assertTrue(odd.failed)
        assertFalse(HaCheck.failedWords(odd)!!.render(Langs.EN).contains("Ignore"))
    }

    @Test fun whatTheReplyDidntListIsReadBack() {
        val s = spec(ConnFixtures.lightOff)
        val v = mapOf("entity" to choice("light.downstairs"))
        // Already off, or a device that reports later: not in the changed states.
        assertEquals(listOf("light.downstairs"), HaCheck.toReadBack(s, listOf<Any>(), v))
        assertTrue(HaCheck.outcome(s, listOf<Any>(), v, mapOf("light.downstairs" to "off")).passed)
        assertTrue(HaCheck.outcome(s, listOf<Any>(), v, mapOf("light.downstairs" to "on")).failed)
        // A read-back that didn't come back: Home Assistant's yes is all Robot knows (neither passed nor failed).
        val unknown = HaCheck.outcome(s, listOf<Any>(), v, mapOf("light.downstairs" to null))
        assertFalse(unknown.checked)
        assertFalse(HaCheck.outcome(s, listOf<Any>(), v, emptyMap()).checked)
        // At most READ_BACK_MAX read back; `all` and services Robot can't check, never.
        val many = (1..6).joinToString(", ") { "light.l$it" }
        assertEquals(HaCheck.READ_BACK_MAX, HaCheck.toReadBack(s, listOf<Any>(), mapOf("entity" to choice(many))).size)
        assertEquals(emptyList<String>(), HaCheck.toReadBack(s, listOf<Any>(), mapOf("entity" to choice("all"))))
        assertEquals(emptyList<String>(), HaCheck.toReadBack(OpSpec.HaCall("light", "toggle"), listOf<Any>(), v))
        assertEquals(emptyList<String>(), HaCheck.toReadBack(spec(ConnFixtures.dim), listOf(state("light.downstairs", "on")), v))
    }

    @Test fun theReadBackIsOneStatesGet() {
        val r = HaCheck.readBack(ha, "light.downstairs")!!
        assertEquals(HttpMethod.GET, r.method)
        assertEquals("/api/states/light.downstairs", r.tail)
        assertTrue(r.read && r.json)
        assertNull(r.body)
        assertEquals(setOf(SecretPart.TOKEN), r.secrets)
        // Never anything but an entity id.
        for (bad in listOf("light.a/../x", "light", "LIGHT.a", "light.a?x=1")) assertNull(bad, HaCheck.readBack(ha, bad))
        assertEquals("on", HaPreset.state(json("""{"entity_id":"light.downstairs","state":"on"}""")))
    }

    @Test fun stillOnItsWayIsNeitherDoneNorFailed() {
        val s = spec(ConnFixtures.garage)
        val v = mapOf("entity" to choice("cover.garage"))
        val moving = HaCheck.outcome(s, listOf(state("cover.garage", "closing")), v, emptyMap())
        assertFalse(moving.failed)
        assertFalse(moving.passed)
        assertEquals(HaPreset.Seen.MOVING, moving.seen["cover.garage"])
        assertEquals(HaPreset.Seen.MOVING, HaPreset.judge("locked", "locking"))
        assertEquals(HaPreset.Seen.MOVING, HaPreset.judge("armed_away", "arming"))
        // Moving the other way is wrong.
        assertEquals(HaPreset.Seen.WRONG, HaPreset.judge("closed", "opening"))
        assertEquals(HaPreset.Seen.WRONG, HaPreset.judge("locked", "jammed"))
    }

    @Test fun entitiesAreNamedByTheirIds() {
        fun t(e: String) = HaCheck.thing(e).render(Langs.EN)
        assertEquals("the downstairs light", t("light.downstairs"))
        assertEquals("the living room lights", t("light.living_room_lights"))
        assertEquals("the front door lock", t("lock.front_door"))
        assertEquals("the garage", t("cover.garage"))
        assertEquals("the coffee maker switch", t("switch.coffee_maker"))
        // A Zigbee address, a MAC, a long id: the domain's word, or "it".
        assertEquals("the light", t("light.0x00158d0001a2b3c4"))
        assertEquals("the light", t("light.hue_ecb5fa1b2c3d"))
        assertEquals("it", t("cover.a_b_c_d_e_f_g"))
        assertEquals("the light", t("light." + "x".repeat(41)))
        // Ordinary words with a digit stay.
        assertEquals("the bedroom 2 light", t("light.bedroom_2"))
    }

    @Test fun wordsPerDomain() {
        fun failed(op: ConnOp, e: String, st: String) = HaCheck.failedWords(HaCheck.outcome(spec(op), listOf(state(e, st)), mapOf("entity" to choice(e)), emptyMap()))!!.render(Langs.EN)
        fun done(op: ConnOp, e: String, st: String) = HaCheck.doneWords(HaCheck.outcome(spec(op), listOf(state(e, st)), mapOf("entity" to choice(e)), emptyMap()))!!.render(Langs.EN)
        assertEquals("the front door lock didn't lock", failed(ConnFixtures.lockDoor, "lock.front_door", "unlocked"))
        assertEquals("the front door lock locked", done(ConnFixtures.lockDoor, "lock.front_door", "locked"))
        assertEquals("the garage didn't close", failed(ConnFixtures.garage, "cover.garage", "open"))
        assertEquals("the downstairs light turned off", done(ConnFixtures.lightOff, "light.downstairs", "off"))
        // Two entities: one list.
        val two = HaCheck.outcome(spec(ConnFixtures.lightOff), listOf(state("light.downstairs", "on"), state("light.kitchen", "on")),
            mapOf("entity" to choice("light.downstairs, light.kitchen")), emptyMap())
        assertEquals("the downstairs light and the kitchen light didn't turn off", HaCheck.failedWords(two)!!.render(Langs.EN))
    }

    @Test fun theStepsCardAndTheSkillsLine() {
        val step = ConnStepAction(ConnStepCall(StepKind.CONN, ha.id, "light_off", mapOf("entity" to StepArg.Fixed("light.downstairs")), "Good night", 1))
        val a = (ConnStep.prepare(step, StepContext(ha, true, null, true, emptyMap(), LimitState(), 1L)) as Prepared.Ready).action as ConnStepAction
        val op = ha.op("light_off")!!
        val ok = ConnStep.result(a, ha, op, CallEnd.Answered(ReplyRead.Ok(200, listOf(state("light.downstairs", "off")))), null)
        assertEquals(ActionStatus.DONE, ok.status)
        assertEquals(CheckOutcome.PASSED, ok.conn!!.check)
        assertEquals("Your Home Assistant said yes: the downstairs light turned off.", ok.text!!.render(Langs.EN))
        // Read back after the wait.
        val back = ConnStep.result(a, ha, op, CallEnd.Answered(ReplyRead.Ok(200, listOf<Any>())), null, mapOf("light.downstairs" to "off"))
        assertEquals(CheckOutcome.PASSED, back.conn!!.check)
        val bad = ConnStep.result(a, ha, op, CallEnd.Answered(ReplyRead.Ok(200, listOf<Any>())), null, mapOf("light.downstairs" to "on"))
        assertEquals(ActionStatus.FAILED, bad.status)
        assertEquals(CheckOutcome.FAILED, bad.conn!!.check)
        assertEquals("the downstairs light didn't turn off", bad.conn!!.what!!.render(Langs.EN))
        assertEquals("Step 1 didn't do what it should: the downstairs light didn't turn off. Robot stopped.",
            app.heyrobot.core.i18n.ConnStrings.checkFailedLast("n" to "1", "what" to bad.conn!!.what).render(Langs.EN))
        // Not checked: Home Assistant said yes, nothing more.
        val unknown = ConnStep.result(a, ha, op, CallEnd.Answered(ReplyRead.Ok(200, listOf<Any>())), null, mapOf("light.downstairs" to null))
        assertEquals("Your Home Assistant said yes.", unknown.text!!.render(Langs.EN))
        assertEquals(CheckOutcome.NONE, unknown.conn!!.check)
        // A script is never checked (script.turn_on doesn't wait for it).
        val s = (ConnStep.prepare(ConnStepAction(ConnStepCall(StepKind.CONN, ha.id, "good_night", emptyMap(), "Good night", 1)),
            StepContext(ha, true, null, true, emptyMap(), LimitState(), 1L)) as Prepared.Ready).action as ConnStepAction
        val ran = ConnStep.result(s, ha, ha.op("good_night")!!, CallEnd.Answered(ReplyRead.Ok(200, null)), null)
        assertEquals(CheckOutcome.NONE, ran.conn!!.check)
        assertEquals("Your Home Assistant said yes.", ran.text!!.render(Langs.EN))
    }

    @Test fun aGarageStillClosingIsNotDone() {
        val step = ConnStepAction(ConnStepCall(StepKind.CONN, ha.id, "garage", emptyMap(), "Good night", 1))
        val a = (ConnStep.prepare(step, StepContext(ha, true, null, true, emptyMap(), LimitState(), 1L)) as Prepared.Ready).action as ConnStepAction
        val r = ConnStep.result(a, ha, ha.op("garage")!!, CallEnd.Answered(ReplyRead.Ok(200, listOf(state("cover.garage", "closing")))), null)
        assertEquals(ActionStatus.DONE, r.status)
        assertEquals(CheckOutcome.NONE, r.conn!!.check)
        assertEquals("Your Home Assistant said yes. It's still on its way, so Robot can't say it's done.", r.text!!.render(Langs.EN))
    }
}
