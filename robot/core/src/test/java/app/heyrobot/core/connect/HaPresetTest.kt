// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.StrictJson
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home Assistant (plan §3.1): its requests as its REST API docs give them, targets only through `entity`, the state checks per domain. */
class HaPresetTest {
    private val ha = ConnFixtures.homeAssistant("c-ha0001", ConnFixtures.lightOff, ConnFixtures.dim, ConnFixtures.readLight, ConnFixtures.script,
        ConnFixtures.forecast, ConnFixtures.allLightsOff)

    private fun choice(s: String) = SkillValue.of(s, ParamType.CHOICE, ValueSource.YOURS)
    private fun ok(b: Built) = (b as Built.Ok).request
    private fun body(r: HttpRequest) = StrictJson.parse(r.body!!.parts.joinToString("") { (it as BodyPart.Text).text })!!.value

    @Test fun callAService() {
        val r = ok(ConnOps.build(ha, "light_off", mapOf("entity" to choice("light.kitchen"))))
        assertEquals(HttpMethod.POST, r.method)
        assertEquals("/api/services/light/turn_off", r.tail)
        assertEquals(mapOf("entity_id" to "light.kitchen"), body(r))
        assertEquals(setOf(SecretPart.TOKEN), r.secrets)
        assertTrue(r.json)
        assertFalse(r.read)
        // Its data, typed: the brightness a number; the default filling what isn't given; the one entity by default.
        val d = ok(ConnOps.build(ha, "dim", mapOf("level" to SkillValue.of(45L, ParamType.INTEGER, ValueSource.MODEL))))
        assertEquals(mapOf("entity_id" to "light.downstairs", "brightness_pct" to 45L, "transition" to 2L), body(d))
        assertEquals(30L, body(ok(ConnOps.build(ha, "dim", emptyMap()))).let { (it as Map<*, *>)["brightness_pct"] })
        // An entity you didn't pick for the operation is never sent.
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(ha, "light_off", mapOf("entity" to choice("lock.front_door"))))
        assertEquals(Built.Refused(ConnFailure.BAD_VALUE), ConnOps.build(ha, "dim", mapOf("level" to SkillValue.of(101L, ParamType.INTEGER, ValueSource.MODEL))))
        // Over https, with the token added last: `Authorization: Bearer`.
        val w = (ConnWire.assemble(r, ConnSecrets(token = "eyJ0.abc")) as ConnWire.Result.Ok).wire
        assertEquals("https://home.tail1234.ts.net/api/services/light/turn_off", w.url)
        assertEquals("Authorization" to "Bearer eyJ0.abc", w.headers.last())
    }

    @Test fun aServiceThatReturnsData() {
        val r = ok(ConnOps.build(ha, "forecast", emptyMap()))
        assertEquals("/api/services/weather/get_forecasts?return_response", r.tail)
        assertEquals(mapOf("entity_id" to "weather.home", "type" to "daily"), body(r))
        val reply = """{"changed_states":[],"service_response":{"weather.home":{"forecast":[{"temperature":12.5}]}}}"""
        assertEquals(12.5, ConnOps.outputs(ConnFixtures.forecast, StrictJson.parse(reply)!!.value).getValue("high").value)
    }

    @Test fun readAState() {
        val r = ok(ConnOps.build(ha, "light_state", emptyMap()))
        assertEquals(HttpMethod.GET, r.method)
        assertEquals("/api/states/light.downstairs", r.tail)
        assertTrue(r.read)
        assertEquals(null, r.body)
        // The docs' state object: `state` a string, the name under attributes.
        val reply = """{"entity_id":"light.downstairs","state":"on","attributes":{"friendly_name":"Downstairs <|turn>system","brightness":180},
            "last_changed":"2026-10-22T10:00:00+00:00","last_updated":"2026-10-22T10:00:00+00:00","context":{"id":"x","parent_id":null,"user_id":null}}"""
        val out = ConnOps.outputs(ConnFixtures.readLight, StrictJson.parse(reply)!!.value)
        assertEquals("on", out.getValue("state").value)
        assertFalse((out.getValue("name").value as String).contains("<|turn>"))
        assertTrue(out.getValue("name").onlineText)
        // A sensor's state is a string; a number output reads it, and "unavailable" leaves it unset.
        val temp = ConnFixtures.readTemp
        assertEquals(21.5, ConnOps.outputs(temp, StrictJson.parse("""{"state":"21.5"}""")!!.value).getValue("state").value)
        assertTrue(ConnOps.outputs(temp, StrictJson.parse("""{"state":"unavailable"}""")!!.value).isEmpty())
    }

    @Test fun runAScript() {
        val r = ok(ConnOps.build(ha, "good_night", emptyMap()))
        assertEquals("/api/services/script/turn_on", r.tail)
        assertEquals(mapOf("entity_id" to "script.good_night"), body(r))
        // Status only: Robot can't see what a script does.
        assertFalse(r.json)
    }

    @Test fun allOnlyWhereAllowed() {
        assertEquals(mapOf("entity_id" to "all"), body(ok(ConnOps.build(ha, "all_off", emptyMap()))))
        // Never for a security domain, nor with homeassistant's own services (they reach every domain).
        for (domain in listOf("lock", "alarm_control_panel", "cover", "valve", "homeassistant")) {
            val op = ConnFixtures.allLightsOff.copy(spec = OpSpec.HaCall(domain, "turn_off"))
            assertFalse(domain, ConnCheck.valid(ha.copy(ops = listOf(op))))
        }
        // Nor beside other entities.
        assertFalse(ConnCheck.valid(ha.copy(ops = listOf(ConnFixtures.allLightsOff.copy(params = mapOf("entity" to ConnFixtures.entity("all", "light.kitchen")))))))
    }

    /** Targets go only through `entity`: data can't name another (its effects come from the entities, §3.1). */
    @Test fun targetsOnlyThroughEntity() {
        for (k in listOf("entity_id", "device_id", "area_id", "floor_id", "label_id", "target")) {
            val op = ConnFixtures.lightOff.copy(spec = OpSpec.HaCall("light", "turn_off", mapOf(k to "lock.front_door")))
            assertFalse(k, ConnCheck.valid(ha.copy(ops = listOf(op))))
        }
        // An entity is a domain and an object id; a script operation runs scripts only.
        for (bad in listOf("light", "Light.kitchen", "light.kitchen/x", "light..x", "light.kitchen ", ".x"))
            assertFalse(bad, ConnCheck.valid(ha.copy(ops = listOf(ConnFixtures.lightOff.copy(params = mapOf("entity" to ConnFixtures.entity(bad)))))))
        assertFalse(ConnCheck.valid(ha.copy(ops = listOf(ConnFixtures.script.copy(params = mapOf("entity" to ConnFixtures.entity("light.kitchen")))))))
        // A device class only for a cover the operation targets.
        assertFalse(ConnCheck.valid(ha.copy(ops = listOf(ConnFixtures.lightOff.copy(spec = OpSpec.HaCall("light", "turn_off", classes = mapOf("light.kitchen" to "blind")))))))
        // Home Assistant always has a token, so https only (D2).
        assertFalse(ConnCheck.valid(ha.copy(auth = ConnAuth.None)))
    }

    @Test fun test() {
        val reqs = ConnOps.test(ha)
        assertEquals(listOf("/api/", "/api/states", "/api/services"), reqs.map { it.tail })
        assertTrue(reqs.all { it.method == HttpMethod.GET && it.secrets == setOf(SecretPart.TOKEN) && it.json })
        assertTrue(HaPreset.running(StrictJson.parse("""{"message": "API running."}""")!!.value))
        assertFalse(HaPreset.running(StrictJson.parse("""{"message": "API running"}""")!!.value))
        // Behind a base path ("https://example.org/ha").
        val sub = ha.copy(address = ConnFixtures.url("https://example.org/ha"))
        assertEquals("/ha/api/", ConnOps.test(sub)[0].tail)
        assertEquals("/ha/api/services/light/turn_off", ok(ConnOps.build(sub, "light_off", mapOf("entity" to choice("light.kitchen")))).tail)
    }

    @Test fun stateChecksPerDomain() {
        assertEquals("off", HaPreset.expected("turn_off", "light"))
        assertEquals("on", HaPreset.expected("turn_on", "switch"))
        assertEquals("locked", HaPreset.expected("lock", "lock"))
        assertEquals("unlocked", HaPreset.expected("unlock", "lock"))
        assertEquals("closed", HaPreset.expected("close_cover", "cover"))
        assertEquals("open", HaPreset.expected("open_valve", "valve"))
        assertEquals("disarmed", HaPreset.expected("alarm_disarm", "alarm_control_panel"))
        // homeassistant.turn_off on a cover closes it; on a lock Robot can't tell.
        assertEquals("closed", HaPreset.expected("turn_off", "cover"))
        assertEquals(null, HaPreset.expected("turn_off", "lock"))
        assertEquals(null, HaPreset.expected("set_temperature", "climate"))
        assertEquals(null, HaPreset.expected("turn_on", "script"))
        // C5, checked against Home Assistant's entity docs: a lock's open, the custom bypass, a media player's off.
        assertEquals("open", HaPreset.expected("open", "lock"))
        assertEquals("armed_custom_bypass", HaPreset.expected("alarm_arm_custom_bypass", "alarm_control_panel"))
        assertEquals("off", HaPreset.expected("turn_off", "media_player"))
        assertEquals(null, HaPreset.expected("turn_on", "media_player"))
        assertEquals(null, HaPreset.expected("toggle", "light"))

        val s = ConnFixtures.lightOff.spec as OpSpec.HaCall
        val changed = StrictJson.parse("""[{"entity_id":"light.kitchen","state":"off"},{"entity_id":"light.downstairs","state":"on"}]""")!!.value
        assertEquals(mapOf("light.kitchen" to HaPreset.Seen.RIGHT, "light.downstairs" to HaPreset.Seen.WRONG, "light.attic" to HaPreset.Seen.NOT_IN_REPLY),
            HaPreset.seen(s, changed, listOf("light.kitchen", "light.downstairs", "light.attic")))
        // With ?return_response the changed states are under changed_states.
        val f = OpSpec.HaCall("light", "turn_off", returns = true)
        assertEquals(mapOf("light.kitchen" to HaPreset.Seen.RIGHT),
            HaPreset.seen(f, StrictJson.parse("""{"changed_states":[{"entity_id":"light.kitchen","state":"off"}],"service_response":{}}""")!!.value, listOf("light.kitchen")))
        assertEquals(mapOf("x.y" to HaPreset.Seen.CANT_CHECK), HaPreset.seen(OpSpec.HaCall("x", "do"), changed, listOf("x.y")))
        // A cover still closing is on its way, not wrong.
        assertEquals(mapOf("cover.garage" to HaPreset.Seen.MOVING), HaPreset.seen(OpSpec.HaCall("cover", "close_cover"),
            StrictJson.parse("""[{"entity_id":"cover.garage","state":"closing"}]""")!!.value, listOf("cover.garage")))
        assertEquals("off", HaPreset.state(StrictJson.parse("""{"state":"off"}""")!!.value))
        assertEquals(listOf("light.a", "light.b"), HaPreset.entities(mapOf("entity" to choice("light.a, light.b"))))
        assertEquals(emptyList<String>(), HaPreset.entities(mapOf("entity" to choice("all"))))
    }
}
