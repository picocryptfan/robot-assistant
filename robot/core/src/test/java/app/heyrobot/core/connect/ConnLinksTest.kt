// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.skills.AttentionWhy
import app.heyrobot.core.skills.IfTest
import app.heyrobot.core.skills.OnFail
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillAttention
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillEffect
import app.heyrobot.core.skills.SkillEffects
import app.heyrobot.core.skills.SkillIf
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillOption
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.skills.SkillProvenance
import app.heyrobot.core.skills.SkillRef
import app.heyrobot.core.skills.SkillRisk
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.SkillTier
import app.heyrobot.core.skills.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1's seam filled (plan §5.2): your connections as SkillCheck.attention, SkillEffects and SkillRisk see them, through the
 * real checks. A lock step is Risky; "turn off all lights" Simple; homeassistant.turn_off on a lock is security; ntfy
 * with the model's words Careful (5 points); a webhook's DELETE Risky; a removed connection "needs attention".
 */
class ConnLinksTest {
    private val ha = ConnFixtures.homeAssistant("c-ha0002", ConnFixtures.lightOff, ConnFixtures.dim, ConnFixtures.lockDoor, ConnFixtures.allLightsOff,
        ConnFixtures.homeOffLock, ConnFixtures.blinds, ConnFixtures.garage, ConnFixtures.readLight, ConnFixtures.readTemp, ConnFixtures.script)
    private val deleter = ConnFixtures.webhook("c-del001").copy(ops = listOf(ConnOp("remove", "Remove an item",
        OpSpec.Webhook(HttpMethod.DELETE, "/items/old"))))
    private val getter = ConnFixtures.webhook("c-get001").copy(ops = listOf(ConnOp("look", "Look it up", OpSpec.Webhook(HttpMethod.GET, "/status"),
        outputs = mapOf("note" to OpOutput("/note", ParamType.TEXT), "busy" to OpOutput("/busy", ParamType.YES_NO)))))
    private val pay = ConnFixtures.webhook("c-pay001").copy(ops = listOf(ConnOp("go", "Order more", OpSpec.Webhook(HttpMethod.POST, "/api/checkout"))))
    private val all = listOf(ha, ConnFixtures.ntfy(), ConnFixtures.webhook(), ConnFixtures.discord(), deleter, getter, pay, ConnFixtures.routing(), ConnFixtures.handOff())
    private val links = ConnLinks(all)

    private fun skill(name: String, vararg steps: SkillStep, params: List<SkillParam> = emptyList(), id: String = "s-" + name.lowercase().filter { it.isLetter() }.take(6).padEnd(6, 'x')) =
        Skill(id, 1, name, listOf(name.lowercase()), emptyList(), params, steps.toList(), SkillLevel.ASK_FIRST, SkillProvenance(SkillMade.SETTINGS, false, 1L, 1L))

    private fun conn(c: String, op: String, args: Map<String, Any?> = emptyMap(), id: String? = null, onFail: OnFail = OnFail.STOP, cond: SkillIf? = null) =
        SkillStep.of(StepKind.CONN, args, id = id, conn = c, op = op, onFail = onFail, cond = cond)

    private fun valid(s: Skill) = assertEquals(s.name, emptyList<Any>(), SkillCheck.problems(s))

    @Test fun aLockIsRisky() {
        val s = skill("Lock up", conn("c-ha0002", "lock_door"))
        valid(s)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(setOf(SkillEffect.SECURITY), SkillEffects.of(s, links))
        assertEquals(SkillTier.RISKY, SkillRisk.tier(s, emptySet(), links))
    }

    @Test fun allLightsOffIsSimple() {
        val s = skill("Lights out", conn("c-ha0002", "all_off"))
        valid(s)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(setOf(SkillEffect.CHANGES_HOME), SkillEffects.of(s, links))
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(s, emptySet(), links))
    }

    @Test fun homeassistantTurnOffOnALockIsSecurity() {
        val s = skill("Off", conn("c-ha0002", "ha_off"))
        // The union of the service's domain (it changes something at home) and the entity's (a lock).
        assertEquals(setOf(SkillEffect.CHANGES_HOME, SkillEffect.SECURITY), SkillEffects.of(s, links))
        assertEquals(SkillTier.RISKY, SkillRisk.tier(s, emptySet(), links))
    }

    @Test fun coversByTheirClass() {
        assertEquals(setOf(SkillEffect.CHANGES_HOME), SkillEffects.of(skill("Blinds", conn("c-ha0002", "blinds")), links))
        assertEquals(setOf(SkillEffect.SECURITY), SkillEffects.of(skill("Garage", conn("c-ha0002", "garage")), links))
        assertEquals(setOf(SkillEffect.RUNS_CODE), SkillEffects.of(skill("Good night", conn("c-ha0002", "good_night")), links))
        assertEquals(setOf(SkillEffect.READS_ONLINE), SkillEffects.of(skill("Is it on", conn("c-ha0002", "light_state")), links))
    }

    /** A question picks the room: the entities it may send are its choices, each one the operation's. */
    @Test fun aChoiceOfEntities() {
        val room = SkillParam("room", ParamType.CHOICE, "light.downstairs", "Which room?", "Quelle pièce?",
            options = listOf(SkillOption("light.downstairs", "Downstairs", "En bas"), SkillOption("light.kitchen", "Kitchen", "Cuisine")))
        val s = skill("Room off", conn("c-ha0002", "light_off", mapOf("entity" to "{{room}}")), params = listOf(room))
        valid(s)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(setOf(SkillEffect.CHANGES_HOME), SkillEffects.of(s, links))
        // A choice the operation doesn't take doesn't fit.
        val far = room.copy(options = room.options + SkillOption("lock.front_door", "Front door", "Porte"))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(skill("Room off", conn("c-ha0002", "light_off",
            mapOf("entity" to "{{room}}")), params = listOf(far)), links))
        // A typed number into the brightness; a word into it doesn't fit.
        val level = SkillParam("level", ParamType.INTEGER, "20", "How bright?", "Quelle intensité?", min = 0.0, max = 100.0)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(skill("Dim", conn("c-ha0002", "dim", mapOf("level" to "{{level}}")), params = listOf(level)), links))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(skill("Dim", conn("c-ha0002", "dim", mapOf("level" to "bright"))), links))
    }

    @Test fun ntfyWithTheModelsWordsIsCareful() {
        val msg = SkillParam("message", ParamType.TEXT, "I'm home", "What should it say?", "Qu'est-ce que ça dit?")
        val s = skill("Tell the family", conn("c-ntfy01", "tell_family", mapOf("message" to "{{message}}")), params = listOf(msg))
        valid(s)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(5, SkillRisk.points(s, setOf("message"), links))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(s, setOf("message"), links))
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE, SkillEffect.MODEL_TEXT), SkillEffects.of(s, links, setOf("message")))
        // The message is required: a step that leaves it out doesn't fit; the priority has a default.
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(skill("Tell", conn("c-ntfy01", "tell_family")), links))
    }

    @Test fun aWebhooksMethodAndMarks() {
        assertEquals(SkillTier.RISKY, SkillRisk.tier(skill("Clean up", conn("c-del001", "remove")), emptySet(), links))
        assertEquals(setOf(SkillEffect.DELETES), SkillEffects.of(skill("Clean up", conn("c-del001", "remove")), links))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.MONEY), SkillEffects.of(skill("Order", conn("c-pay001", "go")), links))
        assertEquals(setOf(SkillEffect.WRITES_ONLINE, SkillEffect.MESSAGES_SOMEONE),
            SkillEffects.of(skill("Post", conn("c-disc01", "post", mapOf("text" to "Hello"))), links))
        // Money from a secret address's path: saved as a mark when you paste it.
        assertEquals(setOf(SkillEffect.MONEY), WebhookPreset.addressMarks("https://shop.example.org/hooks/checkout/AbC123?x=1"))
        assertEquals(emptySet<SkillEffect>(), WebhookPreset.addressMarks("https://discord.example.com/api/webhooks/1/AbC"))
    }

    /** "Go on" only after a read; a reply's free text counts against a later step; a condition only on a typed output. */
    @Test fun readsOutputsAndConditions() {
        val s = skill("Status", conn("c-get001", "look", id = "st", onFail = OnFail.GO_ON),
            SkillStep.of(StepKind.SAY, mapOf("text" to "It says {{st.note}}")),
            conn("c-ha0002", "light_off", mapOf("entity" to "light.kitchen"), cond = SkillIf(SkillRef("st", "busy"), IfTest.Yes(false))))
        valid(s)
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(1, SkillRisk.replyText(s, links))
        // A write never goes on after failing.
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(skill("Go", conn("c-ha0002", "lock_door", onFail = OnFail.GO_ON)), links))
        // A condition on free text from a reply doesn't fit.
        val onText = skill("Status", conn("c-get001", "look", id = "st", onFail = OnFail.GO_ON),
            conn("c-ha0002", "light_off", mapOf("entity" to "light.kitchen"), cond = SkillIf(SkillRef("st", "note"), IfTest.Set)))
        assertEquals(listOf(SkillAttention(1, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(onText, links))
    }

    @Test fun aRemovedConnectionNeedsAttention() {
        val s = skill("Lock up", conn("c-ha0002", "lock_door"))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.CONNECTION_GONE)), SkillCheck.attention(s, ConnLinks(all - ha)))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.CONNECTION_GONE)), SkillCheck.attention(skill("Gone", conn("c-ha0002", "nothing")), links))
        // A connection of another kind under that id isn't an operation's.
        assertEquals(listOf(SkillAttention(0, AttentionWhy.CONNECTION_GONE)), SkillCheck.attention(skill("Route", conn("c-osrm01", "x")), links))
        // The skill keeps its tier's effects computed from nothing: it doesn't run (SkillRisk.decide refuses it).
        assertTrue(SkillCheck.attention(s, SkillLinksNone).isNotEmpty())
    }

    @Test fun travelTime() {
        val s = skill("Eta", SkillStep.of(StepKind.TRAVEL_TIME, mapOf("to" to "home"), id = "eta"))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(ConnFixtures.routing(), links.routingFor(null))
        // With two, the step names one.
        val two = ConnLinks(all + ConnFixtures.routing("c-osrm02"))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.NO_ROUTING)), SkillCheck.attention(s, two))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(skill("Eta", SkillStep.of(StepKind.TRAVEL_TIME, mapOf("to" to "home", "conn" to "c-osrm02"), id = "eta")), two))
        assertFalse(ConnLinks(emptyList()).routing(null))
        assertFalse(links.routing("c-ha0002"))
    }

    @Test fun handOffsComeFromTheirConnections() {
        // C7 fills them: a saved hand-off (MAIN, no slots) links; a gone one needs attention.
        val s = skill("Open", SkillStep.of(StepKind.APP, emptyMap(), conn = "c-app001"))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(s, links))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.APP_GONE)), SkillCheck.attention(skill("Open", SkillStep.of(StepKind.APP, emptyMap(), conn = "c-app009")), links))
        // A slot without a default must be filled; one that isn't the hand-off's doesn't fit.
        val share = ConnLinks(listOf(ConnFixtures.signal()))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)), SkillCheck.attention(skill("Share", SkillStep.of(StepKind.APP, emptyMap(), conn = "c-app001")), share))
        assertEquals(listOf(SkillAttention(0, AttentionWhy.DOESNT_FIT)),
            SkillCheck.attention(skill("Share", SkillStep.of(StepKind.APP, mapOf("text" to "hi", "other" to "x"), conn = "c-app001")), share))
        assertEquals(emptyList<SkillAttention>(), SkillCheck.attention(skill("Share", SkillStep.of(StepKind.APP, mapOf("text" to "On my way."), conn = "c-app001")), share))
    }

    /**
     * A trap for C5, not changed here: C1's rule reads a zigbee-style entity id ("sensor.0x00158d0001a2b3c4_temperature")
     * as a key, so such an entity can't be a skill's fixed value or a question's choice today.
     */
    @Test fun zigbeeStyleEntitiesReadAsKeysToC1sRule() {
        assertTrue(SkillCheck.secret("sensor.0x00158d0001a2b3c4_temperature"))
        assertFalse(SkillCheck.secret("sensor.living_room_temperature"))
        // connections.json itself doesn't run its entities through it (they come from Home Assistant's own list).
        assertTrue(ConnCheck.valid(ha.copy(ops = listOf(ConnFixtures.readTemp.copy(params = mapOf("entity" to ConnFixtures.entity("sensor.0x00158d0001a2b3c4_temperature")))))))
    }

    @Test fun linkedOpShape() {
        val op = links.op("c-ntfy01", "tell_family")!!
        assertEquals(setOf("message", "urgency"), op.params.keys)
        assertEquals(setOf("message"), op.required)
        assertFalse(op.read)
        assertEquals(null, op.ha)
        val h = links.op("c-ha0002", "blinds")!!
        assertEquals("cover", h.ha!!.domain)
        assertEquals("entity", h.ha!!.entityArg)
        assertEquals(mapOf("cover.living_room" to "blind"), h.ha!!.deviceClasses)
        assertTrue(links.op("c-get001", "look")!!.read)
        assertTrue(links.op("c-ha0002", "light_state")!!.read)
        assertEquals(setOf(SkillEffect.MESSAGES_SOMEONE), links.op("c-disc01", "post")!!.marks)
    }

    private val SkillLinksNone = app.heyrobot.core.skills.SkillLinks.NONE
}
