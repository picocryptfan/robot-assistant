// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillCommand
import app.heyrobot.core.skills.SkillRouter
import app.heyrobot.core.skills.StepKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Add to <skill>: <what a connection does>" (1.4): the router's command, the operation it names, the values it asks for, security. */
class StepByVoiceTest {
    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(
            ConnOp("porch_on", "Porch light on", OpSpec.HaCall("light", "turn_on"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch"), default = "light.porch"))),
            ConnOp("porch_off", "Porch light off", OpSpec.HaCall("light", "turn_off"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch"), default = "light.porch"))),
            ConnOp("lock", "Lock the front door", OpSpec.HaCall("lock", "lock"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("lock.front_door"), default = "lock.front_door"))),
            ConnOp("garage", "Open the garage", OpSpec.HaCall("cover", "open_cover"), marks = setOf(app.heyrobot.core.skills.SkillEffect.SECURITY)),
        ))
    private val hook = Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(ConnOp(WebhookForm.OP_ID, "Log a run", OpSpec.Webhook(HttpMethod.POST, "/hook/{{room}}"), mapOf("room" to OpParam(ParamType.TEXT)))))
    private val connections = listOf(ha, hook)
    private val home: Skill = SkillEditor.newSkill("s-abc123", "Heading home", SkillEditor.step(StepOffer.Say, "On my way."), 1000L, Lang.EN)

    private fun one(what: String) = StepByVoice.find(what, connections) as StepByVoice.Found.One

    @Test fun routesAddToASkillOfYoursInEnglishAndFrench() {
        val en = SkillRouter.route("add to heading home: turn on the porch light", listOf(home)) as SkillCommand.AddStep
        assertEquals("s-abc123", en.skill?.id)
        assertEquals("turn on the porch light", en.what)
        assertEquals(Lang.EN, en.lang)
        // Speech to text gives no colon: the name is one of yours.
        assertEquals("log a run", (SkillRouter.route("hey robot add a step to heading home log a run", listOf(home)) as SkillCommand.AddStep).what)
        val fr = SkillRouter.route("ajoute à heading home : allume la lumière du porche", listOf(home)) as SkillCommand.AddStep
        assertEquals("s-abc123", fr.skill?.id)
        assertEquals(Lang.FR, fr.lang)
        // "skill" said, none called that: Robot says so.
        val none = SkillRouter.route("add to the skill bedtime: porch light off", listOf(home)) as SkillCommand.AddStep
        assertNull(none.skill)
    }

    @Test fun aListOfTheSameNameGetsItsItemsUnlessSkillIsSaid() {
        val groceries = SkillEditor.newSkill("s-gro001", "Groceries", SkillEditor.step(StepOffer.Say, "Done."), 1000L, Lang.EN)
        val lists = listOf("Groceries")
        assertNull(SkillRouter.route("add to groceries: milk", listOf(groceries), lists = lists))
        assertNull(SkillRouter.route("add to groceries milk", listOf(groceries), lists = lists))
        assertNull(SkillRouter.route("ajoute à groceries : du lait", listOf(groceries), lists = lists))
        assertEquals("s-gro001", (SkillRouter.route("add to the skill groceries: milk", listOf(groceries), lists = lists) as SkillCommand.AddStep).skill?.id)
        assertEquals("s-gro001", (SkillRouter.route("ajoute à la compétence groceries : du lait", listOf(groceries), lists = lists) as SkillCommand.AddStep).skill?.id)
        // No list of that name: the skill, as before.
        assertEquals("s-gro001", (SkillRouter.route("add to groceries: milk", listOf(groceries)) as SkillCommand.AddStep).skill?.id)
    }

    @Test fun wordsThatFitNoChoiceEndTheWait() {
        val lights = ha.copy(ops = listOf(ConnOp("lights_on", "Lights on", OpSpec.HaCall("light", "turn_on"),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch", "light.kitchen_ceiling"))))))
        val op = lights.ops.single()
        val slot = StepByVoice.missing(SkillEditor.step(StepOffer.Operation(lights, op)), lights, op).single()
        assertTrue(StepByVoice.fits(slot, "the kitchen ceiling"))
        assertFalse(StepByVoice.fits(slot, "what's the weather tomorrow"))
        // A value by its name: any words.
        val log = one("log a run")
        assertTrue(StepByVoice.fits(StepByVoice.missing(SkillEditor.step(StepOffer.Operation(log.conn, log.op)), log.conn, log.op).single(), "what's the weather"))
    }

    @Test fun listNamesCountOnlyWhileListsIsOn() {
        val shop = SkillEditor.newSkill("s-sho001", "Shopping", SkillEditor.step(StepOffer.Say, "Done."), 1000L, Lang.EN)
        val off = app.heyrobot.core.lists.ListRole.forSkills(listOf("Costco"), listsOn = false)
        assertEquals("s-sho001", (SkillRouter.route("add to shopping: porch light on", listOf(shop), lists = off) as SkillCommand.AddStep).skill?.id)
        assertNull(SkillRouter.route("add to shopping: milk", listOf(shop), lists = app.heyrobot.core.lists.ListRole.forSkills(emptyList(), listsOn = true)))
    }

    @Test fun leavesOtherAddsAlone() {
        assertNull(SkillRouter.route("add to my shopping list eggs", listOf(home)))
        assertNull(SkillRouter.route("add milk to the grocery list", listOf(home)))
        assertNull(SkillRouter.route("add to heading home", listOf(home)))
    }

    @Test fun matchesTheOperationYourWordsName() {
        assertEquals("porch_on", one("turn on the porch light").op.id)
        assertEquals("porch_off", one("porch light off").op.id)
        assertEquals(WebhookForm.OP_ID, one("log a run").op.id)
        assertTrue(StepByVoice.find("water the plants", connections) is StepByVoice.Found.None)
        // The same title on two connections: Robot names them and asks again.
        val cabin = ha.copy(id = "c-ha0002", name = "Cabin")
        assertTrue(StepByVoice.find("turn on the porch light", listOf(ha, cabin)) is StepByVoice.Found.Several)
    }

    @Test fun onlyNameWordsOutsideTheTitleBreakATie() {
        // "Porch" is in the title: it names no connection there, so Robot asks.
        val porch = ha.copy(id = "c-ha0002", name = "Porch")
        assertTrue(StepByVoice.find("turn on the porch light", listOf(porch, ha)) is StepByVoice.Found.Several)
        val h = StepByVoice.find("porch light on the home assistant", listOf(porch, ha)) as StepByVoice.Found.One
        assertEquals("c-ha0001", h.conn.id)
        // The say-back names the connection whenever another one has the same title.
        assertEquals("Porch light on (Home Assistant)", h.shown)
        assertEquals("Porch light on", one("turn on the porch light").shown)
    }

    @Test fun aConnectionsNameTellsTheSameTitleApart() {
        val cabin = ha.copy(id = "c-ha0002", name = "Cabin")
        assertEquals("c-ha0002", (StepByVoice.find("turn on the porch light at the cabin", listOf(ha, cabin)) as StepByVoice.Found.One).conn.id)
        assertEquals("c-ha0001", (StepByVoice.find("porch light on the home assistant", listOf(ha, cabin)) as StepByVoice.Found.One).conn.id)
        // Not said: Robot lists each with its connection, and asks for it.
        val both = StepByVoice.find("turn on the porch light", listOf(ha, cabin)) as StepByVoice.Found.Several
        assertEquals(listOf("Porch light on (Home Assistant)", "Porch light on (Cabin)"), both.titles)
        assertTrue(SkillStrings.addSeveral("list" to "x").en().contains("connection's name"))
        assertTrue(SkillStrings.addSeveral("list" to "x").render(app.heyrobot.core.i18n.Langs(Lang.FR, Lang.FR, Lang.FR)).contains("connexion"))
    }

    @Test fun severalPickedEntitiesAreAskedBeforeTheYes() {
        val lights = ha.copy(ops = listOf(ConnOp("lights_on", "Lights on", OpSpec.HaCall("light", "turn_on"),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch", "light.kitchen_ceiling"))))))
        val f = StepByVoice.find("turn the lights on", listOf(lights)) as StepByVoice.Found.One
        val step = SkillEditor.step(StepOffer.Operation(f.conn, f.op))
        // The form's first entity is a guess: asked as a missing value, never saved unasked.
        val slot = StepByVoice.missing(step, f.conn, f.op).single()
        assertEquals(ConnOp.ENTITY, slot.name)
        assertEquals("Which one for “Lights on”: porch, kitchen ceiling?", StepByVoice.ask(slot, "Lights on").en())
        assertEquals("light.kitchen_ceiling", StepByVoice.which(slot, "the kitchen ceiling"))
        assertEquals("light.porch", StepByVoice.which(slot, "light.porch"))
        // Nothing fits: the words as said, which the form refuses (Robot asks again).
        assertEquals("the garage", StepByVoice.which(slot, "the garage"))
        val added = SkillEditor.add(home, step)!!
        assertNull(SkillEditor.setConstant(added, added.steps.size - 1, slot, StepByVoice.which(slot, "the garage")))
        val filled = SkillEditor.setConstant(added, added.steps.size - 1, slot, StepByVoice.which(slot, "kitchen ceiling"))!!
        assertEquals("light.kitchen_ceiling", filled.steps.last().args[ConnOp.ENTITY])
        // The say-back names the entity.
        assertEquals("Lights on (kitchen ceiling)", StepByVoice.named("Lights on", filled.steps.last()))
        // One picked: its default, nothing asked, the title alone.
        val porch = one("turn on the porch light")
        val p = SkillEditor.step(StepOffer.Operation(porch.conn, porch.op))
        assertTrue(StepByVoice.missing(p, porch.conn, porch.op).isEmpty())
        assertEquals("Porch light on", StepByVoice.named("Porch light on", p))
    }

    @Test fun aNameInsideTheTitleNamesItsConnectionWhenSaidAgain() {
        // Review-c Low 1: "Porch" said once is the title's; said once more, it names the connection.
        val porch = ha.copy(id = "c-ha0002", name = "Porch")
        assertEquals("c-ha0002", (StepByVoice.find("porch light on the porch", listOf(porch, ha)) as? StepByVoice.Found.One)?.conn?.id)
        assertTrue(StepByVoice.find("turn on the porch light", listOf(porch, ha)) is StepByVoice.Found.Several)
    }

    @Test fun aPartialAnswerPicksTheOneOptionItFits() {
        val lights = ha.copy(ops = listOf(ConnOp("lights_on", "Lights on", OpSpec.HaCall("light", "turn_on"),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch", "light.kitchen_ceiling", "light.kitchen_counter"))))))
        val op = lights.ops.single()
        val step = SkillEditor.step(StepOffer.Operation(lights, op))
        val slot = StepByVoice.missing(step, lights, op).single()
        // Review-c Low 2: part of one option picks it; part of several asks again; words that fit none end the wait.
        assertEquals("light.kitchen_ceiling", StepByVoice.which(slot, "ceiling"))
        assertTrue(StepByVoice.fits(slot, "kitchen"))
        assertEquals("kitchen", StepByVoice.which(slot, "kitchen"))
        assertFalse(StepByVoice.fits(slot, "what's the weather tomorrow"))
        assertEquals(StepByVoice.ask(slot, "Lights on").en(), StepByVoice.again(slot, "Lights on").en())
        assertEquals("Not added: “Lights on”.", SkillStrings.addLeft("step" to "Lights on").en())
    }

    @Test fun theSayBackHasOneParenthesis() {
        val lights = ha.copy(ops = listOf(ConnOp("lights_on", "Lights on", OpSpec.HaCall("light", "turn_on"),
            mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.porch", "light.kitchen_ceiling", "light.kitchen_counter"))))))
        val op = lights.ops.single()
        val step = SkillEditor.step(StepOffer.Operation(lights, op))
        val slot = StepByVoice.missing(step, lights, op).single()
        val added = SkillEditor.add(home, step)!!
        val filled = SkillEditor.setConstant(added, added.steps.size - 1, slot, "light.kitchen_ceiling")!!
        // Review-c Low 4: the connection and the entity in one parenthesis.
        assertEquals("Lights on (Home Assistant, kitchen ceiling)", StepByVoice.named("Lights on (Home Assistant)", filled.steps.last()))
    }

    @Test fun aSkillNamedAsAListsOwnNameLeavesTheAddToLists() {
        // Review-c Low 3: Lists' resolver reads "groceries" as Shopping, so Lists takes it; "skill" reaches the skill.
        val groceries = SkillEditor.newSkill("s-gro002", "Groceries", SkillEditor.step(StepOffer.Say, "Done."), 1000L, Lang.EN)
        val on = app.heyrobot.core.lists.ListRole.forSkills(emptyList(), listsOn = true)
        assertNull(SkillRouter.route("add to groceries: milk", listOf(groceries), lists = on))
        assertNull(SkillRouter.route("add to groceries milk", listOf(groceries), lists = on))
        assertEquals("s-gro002", (SkillRouter.route("add to the skill groceries: milk", listOf(groceries), lists = on) as SkillCommand.AddStep).skill?.id)
    }

    private fun haOps(vararg ops: Pair<String, String>) = ha.copy(id = "c-ha0003", ops = ops.map { (id, title) ->
        ConnOp(id, title, OpSpec.HaCall("light", "turn_on"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.x"), default = "light.x"))) })

    @Test fun neverPicksTheOppositeOfWhatYouSaid() {
        val none = { what: String, c: Connection -> assertTrue(what, StepByVoice.find(what, listOf(c)) is StepByVoice.Found.None) }
        none("close the living room blinds", haOps("b" to "Open the living room blinds"))
        none("turn off the porch light", haOps("p" to "Porch light on"))
        none("turn on the porch light", haOps("p" to "Porch light off"))
        none("unlock the front door", haOps("l" to "Lock the front door"))
        none("lower the blinds", haOps("b" to "Raise the blinds"))
        none("ferme les stores du salon", haOps("b" to "Ouvre les stores du salon"))
        none("éteins la lumière du porche", haOps("p" to "Allume la lumière du porche"))
        none("déverrouille la porte", haOps("l" to "Verrouille la porte"))
        // A partial match is never a guess: Robot says none fits.
        none("porch light", haOps("p" to "Porch light off"))
        // Two that fit as well: a question, never a pick.
        assertTrue(StepByVoice.find("porch light", listOf(haOps("a" to "Porch light", "b" to "Light porch"))) is StepByVoice.Found.Several)
        // The words that do fit still find it.
        assertEquals("b", (StepByVoice.find("open the living room blinds", listOf(haOps("b" to "Open the living room blinds", "c" to "Close the living room blinds"))) as StepByVoice.Found.One).op.id)
        assertEquals("p", (StepByVoice.find("allume la lumière du porche", listOf(haOps("p" to "Allume la lumière du porche", "q" to "Éteins la lumière du porche"))) as StepByVoice.Found.One).op.id)
    }

    @Test fun asksForTheValuesTheTouchFormLeavesForYou() {
        val log = one("log a run")
        val step = SkillEditor.step(StepOffer.Operation(log.conn, log.op))
        assertEquals(listOf("room"), StepByVoice.missing(step, log.conn, log.op).map { it.name })
        val porch = one("turn on the porch light")
        assertTrue(StepByVoice.missing(SkillEditor.step(StepOffer.Operation(porch.conn, porch.op)), porch.conn, porch.op).isEmpty())
        val added = SkillEditor.add(home, step)!!
        val slot = StepByVoice.missing(step, log.conn, log.op).single()
        val filled = SkillEditor.setConstant(added, added.steps.size - 1, slot, "kitchen")!!
        assertTrue(SkillCheck.valid(filled))
        assertEquals(StepKind.CONN, filled.steps.last().kind)
    }

    @Test fun aSecurityOperationIsNeverAddedByVoice() {
        for (w in listOf("lock the front door", "open the garage")) {
            val f = one(w)
            assertTrue(w, StepByVoice.security(home, SkillEditor.step(StepOffer.Operation(f.conn, f.op)), connections))
        }
        val p = one("turn on the porch light")
        assertFalse(StepByVoice.security(home, SkillEditor.step(StepOffer.Operation(p.conn, p.op)), connections))
    }
}
