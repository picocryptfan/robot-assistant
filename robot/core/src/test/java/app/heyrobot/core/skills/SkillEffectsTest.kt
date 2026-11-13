// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills connections plan D12, §3, §7.1 (design §4.2): effects are computed from the steps, each kind's own; monotone
 * (adding a step never removes one); marks and a script's `# effect:` lines only add; Home Assistant's from the domains of
 * the service and of every entity a step may send, "all" only where it's allowed.
 */
class SkillEffectsTest {
    private val links = SkillFixtures.links
    private val E = SkillEffect.entries

    @Test fun eachKindsEffects() {
        val home = SkillFixtures.headingHome()
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE, SkillEffect.OPENS_APP), SkillEffects.of(home, SkillLinks.NONE))
        assertEquals(setOf(SkillEffect.CHANGES_PHONE, SkillEffect.MESSAGES_SOMEONE), SkillEffects.of(SkillFixtures.pasta(), SkillLinks.NONE))
        assertEquals(setOf(SkillEffect.READS_ONLINE, SkillEffect.MESSAGES_SOMEONE, SkillEffect.OPENS_APP), SkillEffects.of(SkillFixtures.headingHomeV2(), SkillLinks.NONE))
        // Good night: a light (home) and the front door's lock (security).
        assertEquals(setOf(SkillEffect.CHANGES_HOME, SkillEffect.SECURITY), SkillEffects.of(SkillFixtures.goodNight(), links))
        // Back up: the calendar, the script (code, online by its `# effect:`), ntfy (tells people), the thermostat, Signal (a SEND).
        assertEquals(setOf(SkillEffect.READS_PHONE, SkillEffect.RUNS_CODE, SkillEffect.WRITES_ONLINE, SkillEffect.MESSAGES_SOMEONE, SkillEffect.CHANGES_HOME,
            SkillEffect.OPENS_APP), SkillEffects.of(SkillFixtures.backUp(), links))
        // With nothing linked, what each kind always does.
        assertEquals(setOf(SkillEffect.READS_PHONE, SkillEffect.RUNS_CODE, SkillEffect.OPENS_APP), SkillEffects.of(SkillFixtures.backUp(), SkillLinks.NONE))
        // A text question the model filled.
        val team = SkillLibrary.skills.getValue("team")
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), SkillEffects.of(team, SkillLinks.NONE))
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE, SkillEffect.MODEL_TEXT), SkillEffects.of(team, SkillLinks.NONE, given = setOf("message")))
        // Every 1.1 kind has its effect, and only `say` none.
        for (k in StepKind.entries.filter { it.tool != null }) assertTrue(k.id, SkillEffects.step(home, SkillStep.of(k, emptyMap()), SkillLinks.NONE).isNotEmpty())
        assertEquals(emptySet<SkillEffect>(), SkillEffects.step(home, SkillStep.of(StepKind.SAY, mapOf("text" to "Hi.")), SkillLinks.NONE))
    }

    @Test fun monotoneAddingAStepNeverRemovesAnEffect() {
        val all = SkillFixtures.goodNight().steps + SkillFixtures.backUp().steps + SkillFixtures.headingHomeV2().steps
        val base = SkillFixtures.backUp().copy(params = SkillFixtures.backUp().params + SkillFixtures.room + SkillFixtures.lockUp + SkillFixtures.level)
        var acc = base.copy(steps = emptyList())
        var before = emptySet<SkillEffect>()
        for (st in all + StepKind.entries.filter { it.tool != null }.map { SkillStep.of(it, emptyMap()) }) {
            acc = acc.copy(steps = acc.steps + st)
            val now = SkillEffects.of(acc, links)
            assertTrue(st.use, now.containsAll(before))
            before = now
        }
    }

    @Test fun marksAndDeclaredEffectsOnlyAdd() {
        val publish = SkillStep.of(StepKind.CONN, mapOf("message" to "hi"), conn = SkillFixtures.NTFY, op = "publish")
        val s = SkillFixtures.pasta().copy(steps = listOf(publish))
        for (marks in listOf(setOf(SkillEffect.MONEY), setOf(SkillEffect.SECURITY), setOf(SkillEffect.MESSAGES_SOMEONE), E.toSet())) {
            val marked = object : SkillLinks by links {
                override fun op(conn: String, op: String) = links.op(conn, op)?.copy(marks = marks)
            }
            val with = SkillEffects.of(s, marked)
            assertTrue(with.containsAll(SkillEffects.of(s, links)))
            assertTrue(with.containsAll(marks))
        }
        assertEquals(setOf(SkillEffect.RUNS_CODE), SkillEffects.script(emptyList()))
        assertEquals(setOf(SkillEffect.RUNS_CODE, SkillEffect.WRITES_ONLINE, SkillEffect.DELETES, SkillEffect.MESSAGES_SOMEONE, SkillEffect.MONEY),
            SkillEffects.script(listOf("online", " Deletes", "sends", "money", "harmless", "none")))
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), SkillEffects.ntfy(onlyMyDevices = false))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE), SkillEffects.ntfy(onlyMyDevices = true))
        assertEquals(setOf(SkillEffect.OPENS_APP), SkillEffects.handOff("VIEW", "https"))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), SkillEffects.handOff("SENDTO", null))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), SkillEffects.handOff("VIEW", "smsto"))
        assertEquals(setOf(SkillEffect.OPENS_APP, SkillEffect.MESSAGES_SOMEONE), SkillEffects.handOff("view", "MAILTO"))
    }

    @Test fun webhooksByMethodAndMoneyAsAHint() {
        assertEquals(setOf(SkillEffect.READS_ONLINE), SkillEffects.webhook("GET", "/api/status"))
        assertEquals(setOf(SkillEffect.READS_ONLINE), SkillEffects.webhook("head", "/"))
        for (m in listOf("POST", "PUT", "PATCH")) assertEquals(setOf(SkillEffect.WRITES_ONLINE), SkillEffects.webhook(m, "/webhook/abc"))
        assertEquals(setOf(SkillEffect.DELETES), SkillEffects.webhook("DELETE", "/items/1"))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.DELETES), SkillEffects.webhook("PURGE", "/"))
        for (p in listOf("/api/checkout", "/orders/new", "/Payment", "/wallet/top-up", "/commande", "/acheter-maintenant", "/buy?x=1"))
            assertTrue(p, SkillEffect.MONEY in SkillEffects.webhook("POST", p))
        for (p in listOf("/display", "/border", "/api/lights", "/repay-later-not"))
            assertFalse(p, SkillEffect.MONEY in SkillEffects.webhook("POST", p))
    }

    @Test fun homeAssistantDomainsUnionsAndAll() {
        fun ha(domain: String, vararg entities: String, classes: Map<String, String> = emptyMap()) =
            SkillEffects.homeAssistant(HaCall(domain, "turn_on", deviceClasses = classes), entities.toList())
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("light", "light.kitchen"))
        for (d in listOf("lock", "alarm_control_panel", "siren", "camera", "valve")) assertEquals(d, setOf(SkillEffect.SECURITY), ha(d, "$d.front"))
        for (d in listOf("script", "automation", "shell_command", "python_script", "hassio", "scene", "group", "rest_command", "pyscript"))
            assertEquals(d, setOf(SkillEffect.RUNS_CODE), ha(d))
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), ha("notify", "notify.family"))
        // The union over the service's domain and every entity's: turning "off" a lock is security.
        assertEquals(setOf(SkillEffect.CHANGES_HOME, SkillEffect.SECURITY), ha("homeassistant", "light.kitchen", "lock.front_door"))
        assertEquals(setOf(SkillEffect.CHANGES_HOME, SkillEffect.RUNS_CODE), ha("homeassistant", "script.good_night"))
        // Covers: a blind or a shade changes your home; a garage, a gate, a door, none or one Robot doesn't know is security.
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("cover", "cover.living", classes = mapOf("cover.living" to "blind")))
        for (c in listOf("garage", "gate", "door", "trapdoor")) assertEquals(c, setOf(SkillEffect.SECURITY), ha("cover", "cover.x", classes = mapOf("cover.x" to c)))
        assertEquals(setOf(SkillEffect.SECURITY), ha("cover", "cover.x"))
        assertEquals(setOf(SkillEffect.SECURITY), ha("cover"))
        assertEquals(setOf(SkillEffect.CHANGES_HOME, SkillEffect.SECURITY), ha("cover", "cover.living", "cover.garage",
            classes = mapOf("cover.living" to "shade", "cover.garage" to "garage")))
        // "all": the service's own domain, only outside security and never for homeassistant's services.
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("light", "all"))
        assertTrue(SkillEffects.haAll("light"))
        for (d in listOf("lock", "cover", "homeassistant", "alarm_control_panel")) {
            assertFalse(d, SkillEffects.haAll(d))
            assertTrue(d, SkillEffect.SECURITY in ha(d, "all"))
        }
        // An entity Robot can't tell is security; one without a domain too.
        assertEquals(setOf(SkillEffect.SECURITY), SkillEffects.homeAssistant(HaCall("light", "turn_on"), null))
        assertTrue(SkillEffect.SECURITY in ha("light", "kitchen"))
    }

    /**
     * C12's M2: a scene or a group can reach a lock or an alarm Robot doesn't see (Home Assistant applies a scene's states
     * and expands a group to its members), so they count as code Robot can't see into: no spoken yes, and the card says so.
     */
    @Test fun scenesAndGroupsAreCode() {
        fun ha(domain: String, service: String, vararg entities: String) = SkillEffects.homeAssistant(HaCall(domain, service), entities.toList())
        assertEquals(setOf(SkillEffect.RUNS_CODE), ha("scene", "turn_on", "scene.leaving"))
        assertTrue(SkillEffect.RUNS_CODE in ha("homeassistant", "turn_on", "group.front"))
        assertTrue(SkillEffect.RUNS_CODE in ha("homeassistant", "turn_off", "light.kitchen", "group.front"))
        // HTTP calls and code from Home Assistant itself.
        for (d in listOf("rest_command", "pyscript")) assertEquals(d, setOf(SkillEffect.RUNS_CODE), ha(d, "x"))
        // A light is still a change at home, which a spoken yes may answer.
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("light", "turn_on", "light.x"))
        for (e in listOf(ha("scene", "turn_on", "scene.leaving"), ha("homeassistant", "turn_on", "group.front")))
            assertFalse(e.toString(), e.all { it in app.heyrobot.core.connect.ConnStep.SPOKEN_YES })
    }

    /**
     * review-batch-14-rest's M1: a domain Robot doesn't know fails closed. A button may open a garage, a switch drive a
     * door's relay: no spoken yes for them (D14), as for code Robot can't see; the plain domains keep theirs.
     */
    @Test fun anUnknownDomainNeedsATap() {
        fun ha(domain: String, service: String, vararg entities: String) = SkillEffects.homeAssistant(HaCall(domain, service), entities.toList())
        val spoken = app.heyrobot.core.connect.ConnStep.SPOKEN_YES
        for ((d, sv) in listOf("button" to "press", "switch" to "turn_on", "remote" to "send_command", "select" to "select_option",
            "update" to "install", "input_boolean" to "turn_on", "input_button" to "press", "made_up" to "do_it",
            "number" to "set_value", "input_number" to "set_value")) {
            val e = ha(d, sv, "$d.garage")
            assertEquals(d, setOf(SkillEffect.RUNS_CODE), e)
            assertFalse(d, e.all { it in spoken })
            assertFalse(d, SkillEffects.haAll(d))
        }
        // homeassistant.turn_on takes each entity's domain: a switch in it needs the tap too.
        assertFalse(ha("homeassistant", "turn_on", "light.kitchen", "switch.garage").all { it in spoken })
        // homeassistant's other services (restart, stop) are code.
        assertTrue(SkillEffect.RUNS_CODE in ha("homeassistant", "restart"))
        for (d in listOf("light", "fan", "climate", "humidifier", "media_player", "vacuum", "water_heater"))
            assertEquals(d, setOf(SkillEffect.CHANGES_HOME), ha(d, "turn_on", "$d.x"))
        // review-batch-14-rest-b's Medium: a number may be a lock's relock delay or an alarm's entry delay; a tap.
        for (d in listOf("number", "input_number")) assertEquals(d, setOf(SkillEffect.RUNS_CODE), ha(d, "set_value", "$d.relock_delay"))
        // review-batch-14-rest-b's Low: a send_command is code in any domain, a vacuum's too (a map reset, say).
        val vacuum = ha("vacuum", "send_command", "vacuum.downstairs")
        assertTrue(SkillEffect.RUNS_CODE in vacuum)
        assertFalse(vacuum.all { it in spoken })
        assertTrue(ha("homeassistant", "toggle", "light.kitchen").all { it in spoken })
    }

    /** M2: a service that removes, deletes, purges or clears is DELETES (Risky, a tap), never a spoken yes (D14). */
    @Test fun aServiceThatRemovesDeletes() {
        fun ha(domain: String, service: String, vararg entities: String) = SkillEffects.homeAssistant(HaCall(domain, service), entities.toList())
        for ((d, sv) in listOf("todo" to "remove_item", "todo" to "remove_completed_items", "shopping_list" to "remove_item",
            "shopping_list" to "clear_completed_items", "recorder" to "purge", "media_player" to "clear_playlist", "made_up" to "delete_all"))
            assertTrue("$d.$sv", SkillEffect.DELETES in ha(d, sv, "todo.groceries"))
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("todo", "add_item", "todo.groceries"))
        assertEquals(setOf(SkillEffect.CHANGES_HOME), ha("shopping_list", "add_item"))
        assertFalse(SkillEffect.DELETES in ha("light", "turn_off", "light.x"))
    }

    /** C12's M2: `entity_id: all` never runs every script, automation or scene you have, nor tells everyone. */
    @Test fun allNeverRunsEveryScript() {
        for (d in listOf("script", "automation", "scene", "notify", "group", "shell_command", "rest_command", "pyscript", "python_script", "hassio"))
            assertFalse(d, SkillEffects.haAll(d))
        for (d in listOf("light", "fan", "media_player")) assertTrue(d, SkillEffects.haAll(d))
        // A switch may be a pump, a heater or a garage door: "all" is never allowed there, and it counts as risky.
        assertFalse(SkillEffects.haAll("switch"))
        val allSwitches = SkillEffects.homeAssistant(HaCall("switch", "turn_off"), listOf("all"))
        assertTrue(allSwitches.toString(), allSwitches.any { it in SkillEffect.RISKY })
        assertFalse(allSwitches.toString(), allSwitches.all { it in app.heyrobot.core.connect.ConnStep.SPOKEN_YES })
        val all = app.heyrobot.core.connect.ConnFixtures.homeAssistant("c-ha0001", app.heyrobot.core.connect.ConnOp("all", "Run every script",
            app.heyrobot.core.connect.OpSpec.HaCall("script", "turn_on"), mapOf("entity" to app.heyrobot.core.connect.ConnFixtures.entity("all"))))
        assertFalse(app.heyrobot.core.connect.ConnCheck.valid(all))
        assertTrue(app.heyrobot.core.connect.ConnCheck.valid(app.heyrobot.core.connect.ConnFixtures.homeAssistant("c-ha0001", app.heyrobot.core.connect.ConnFixtures.allLightsOff)))
    }

    @Test fun aStepsEntitiesAreItsValueAChoicesValuesOrTheSlots() {
        val night = SkillFixtures.goodNight()
        val op = links.op(SkillFixtures.HA, "light_dim")!!
        assertEquals(listOf("light.downstairs", "light.kitchen"), SkillEffects.entities(night, night.steps[0], op, op.ha!!))
        val fixed = night.steps[0].copy(args = mapOf("entity" to "light.a, light.b", "brightness" to 5L))
        assertEquals(listOf("light.a", "light.b"), SkillEffects.entities(night, fixed, op, op.ha!!))
        val none = night.steps[0].copy(args = mapOf("brightness" to 5L))
        assertEquals(listOf("light.downstairs", "light.kitchen", "light.porch"), SkillEffects.entities(night, none, op, op.ha!!))
        // From a text question or a step's output: unknown, so security.
        val what = night.copy(params = night.params + SkillParam("what", ParamType.TEXT, "light.x", "Which?", "Lequel?"))
        assertEquals(null, SkillEffects.entities(what, night.steps[0].copy(args = mapOf("entity" to "{{what}}")), op, op.ha!!))
        assertEquals(setOf(SkillEffect.SECURITY), SkillEffects.step(what, night.steps[0].copy(args = mapOf("entity" to "{{what}}")), links))
        // A choice that includes a lock makes the step security, whichever value runs.
        val withLock = night.copy(params = listOf(SkillFixtures.room.copy(options = SkillFixtures.room.options + SkillOption("lock.back", "Back door", "Porte arrière"))) +
            night.params.drop(1))
        assertTrue(SkillEffect.SECURITY in SkillEffects.step(withLock, night.steps[0], links))
    }
}
