// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.connect

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.skills.AttentionWhy
import app.heyrobot.core.skills.IfTest
import app.heyrobot.core.skills.OnFail
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillIf
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillMade
import app.heyrobot.core.skills.SkillOption
import app.heyrobot.core.skills.SkillParam
import app.heyrobot.core.skills.SkillRef
import app.heyrobot.core.skills.SkillTemplate
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.termux.ScriptLinks
import app.heyrobot.core.termux.TermuxScriptInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C9, plan §6.1 and §6.4: Add a skill and Add a step's sheet, the argument editor (a value, a question, an earlier step's
 * value, words with chips), Only if…, If it doesn't work, and the lines a skill's page computes (Effects, Risk, Needs a
 * model, Test's dry run: nothing sent, never a key). Every result passes SkillCheck, and links that fit leave no attention.
 */
class SkillEditorTest {
    private val ha = Connection("c-ha0001", ConnKind.HOME_ASSISTANT, "Home Assistant", ConnUrl.parse("https://home.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(
            ConnOp("light_off", "Turn off a light", OpSpec.HaCall("light", "turn_off"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("light.kitchen", "light.downstairs")))),
            ConnOp("lock", "Lock the door", OpSpec.HaCall("lock", "lock"), mapOf(ConnOp.ENTITY to OpParam(ParamType.CHOICE, listOf("lock.front_door"), default = "lock.front_door"))),
        ))
    private val ntfy = ConnSetup.create("c-nt0001", ConnKind.NTFY, "family ntfy", "https://ntfy.example.org", ntfyTitle = "Tell the family").first!!
    private val hook = Connection("c-wh0001", ConnKind.WEBHOOK, "n8n", ConnUrl.parse("https://n8n.example.org")!!, auth = ConnAuth.Bearer,
        ops = listOf(ConnOp(WebhookForm.OP_ID, "Log a run", OpSpec.Webhook(HttpMethod.POST, "/hook/{{room}}"), mapOf("room" to OpParam(ParamType.TEXT)))))
    private val osrm = Connection("c-rt0001", ConnKind.ROUTING, "OSRM", ConnUrl.parse("https://osrm.example.org")!!, routing = RoutingKind.OSRM)
    private val backup = TermuxScriptInfo("backup", paramLines = listOf("# param: folder choice photos|documents \"Which folder\"", "# param: days int 1..30"))
    private val broken = TermuxScriptInfo("broken", paramLines = listOf("# param: x float"))
    private val connections = listOf(ha, ntfy, hook, osrm)
    private val scripts = listOf(backup, broken)
    private val links = ConnLinks(connections, ScriptLinks(scripts))

    private fun offer(f: (StepOffer) -> Boolean) = SkillEditor.offers(connections, scripts).first(f)
    private fun op(id: String) = offer { it is StepOffer.Operation && it.op.id == id }
    private val travel get() = offer { it is StepOffer.Travel }
    private fun skill(vararg o: StepOffer): Skill {
        var s = SkillEditor.newSkill("s-abc123", "Heading home", SkillEditor.step(o.first(), "Hi."), 1000L, Lang.EN)
        for (x in o.drop(1)) s = SkillEditor.add(s, SkillEditor.step(x, "Hi."))!!
        return s
    }
    private fun slot(s: Skill, i: Int, name: String) = SkillEditor.slots(s, i, connections, scripts).first { it.name == name }

    @Test fun theSheetOffersEachSectionInOrderAndOnlyScriptsRobotCanRead() {
        val o = SkillEditor.offers(connections, scripts)
        assertEquals(listOf(OfferSection.CONNECTIONS, OfferSection.CONNECTIONS, OfferSection.CONNECTIONS, OfferSection.CONNECTIONS, OfferSection.TRAVEL,
            OfferSection.SCRIPTS, OfferSection.SAY), o.map { it.section })
        // One routing server: travel time uses it without naming it.
        assertNull((o.first { it is StepOffer.Travel } as StepOffer.Travel).conn)
        assertEquals(listOf("backup"), o.filterIsInstance<StepOffer.Script>().map { it.info.name })
        // Two routing servers: one offer each, named.
        val two = SkillEditor.offers(listOf(osrm, osrm.copy(id = "c-rt0002", name = "Valhalla", routing = RoutingKind.VALHALLA)), emptyList())
        assertEquals(listOf("c-rt0001", "c-rt0002"), two.filterIsInstance<StepOffer.Travel>().map { it.conn?.id })
        assertEquals(listOf(StepOffer.Say), SkillEditor.offers(emptyList(), emptyList()))
    }

    @Test fun addASkillMakesAValidSkillAtAskFirst() {
        val s = skill(op("light_off"))
        assertTrue(SkillCheck.problems(s).toString(), SkillCheck.valid(s))
        assertEquals(SkillLevel.ASK_FIRST, s.level)
        assertEquals(SkillMade.SETTINGS, s.provenance.made)
        assertEquals(listOf("heading home"), s.namesEn)
        assertEquals(emptyList<String>(), s.namesFr)
        // A choice starts at its first value; nothing needs attention.
        assertEquals("light.kitchen", s.steps[0].args[ConnOp.ENTITY])
        assertEquals(emptyList<Any>(), SkillCheck.attention(s, links))
        val fr = SkillEditor.newSkill("s-abc124", "Je rentre", SkillEditor.step(StepOffer.Say, "Salut."), 1L, Lang.FR)
        assertEquals(listOf("je rentre"), fr.namesFr)
        assertEquals(mapOf("text" to "Salut."), fr.steps[0].args)
    }

    @Test fun aStepStartsWithValuesItCantBeWrongAboutAndLeavesWordsToYou() {
        val sc = SkillEditor.step(offer { it is StepOffer.Script })
        assertEquals(mapOf<String, Any?>("script" to "backup", "folder" to "photos", "days" to 1L), sc.args)
        // ntfy's message has no default: the step needs attention until you fill it.
        val s = skill(op(ConnSetup.NTFY_OP))
        assertTrue(SkillCheck.valid(s))
        assertEquals(listOf(AttentionWhy.DOESNT_FIT), SkillCheck.attention(s, links).map { it.why })
        val filled = SkillEditor.setConstant(s, 0, slot(s, 0, "message"), "I'm home.")!!
        assertEquals(emptyList<Any>(), SkillCheck.attention(filled, links))
    }

    @Test fun aQuestionsLastUseChangedDropsTheQuestion() {
        val s = skill(offer { it is StepOffer.Script })
        val days = slot(s, 0, "days")
        val asked = SkillEditor.ask(s, 0, days, "How many days?", "7")!!
        assertEquals(1, asked.params.size)
        val fixed = SkillEditor.setConstant(asked, 0, days, "7")!!
        assertEquals(emptyList<Any>(), fixed.params)
        assertTrue(SkillCheck.valid(fixed))
        // A question another argument still names stays.
        val two = SkillEditor.ask(asked, 0, slot(s, 0, "folder"), "Which folder?", "documents")!!
        assertEquals(2, two.params.size)
        assertEquals(1, SkillEditor.setConstant(two, 0, days, "7")!!.params.size)
    }

    @Test fun aQuestionOnlyAnOnlyIfUsedGoesWhenTheConditionDoes() {
        val base = skill(op(ConnSetup.NTFY_OP), op("light_off"))
        val filled = SkillEditor.setConstant(base, 0, slot(base, 0, "message"), "Hi.")!!
        val choice = SkillParam("room", ParamType.CHOICE, "a", "Room?", "Room?", options = listOf(SkillOption("a", "A", "A"), SkillOption("b", "B", "B")))
        val c = SkillEditor.setCondition(filled.copy(params = listOf(choice)), 1, SkillIf(SkillRef("room"), IfTest.Is("a")), links)!!
        assertEquals(1, c.params.size)
        val always = SkillEditor.setCondition(c, 1, null, links)!!
        assertEquals(emptyList<Any>(), always.params)
        assertTrue(SkillCheck.valid(always))
    }

    @Test fun goOnCanGoBackToStopAfterTheStepStopsBeingARead() {
        val s = skill(op(ConnSetup.NTFY_OP))
        assertEquals(listOf(OnFail.STOP), SkillEditor.onFailWays(s, 0, links))
        val stuck = s.copy(steps = s.steps.map { it.copy(onFail = OnFail.GO_ON) })
        assertEquals(listOf(OnFail.STOP, OnFail.GO_ON), SkillEditor.onFailWays(stuck, 0, links))
        assertEquals(OnFail.STOP, SkillEditor.setOnFail(stuck, 0, OnFail.STOP, links)!!.steps[0].onFail)
    }

    @Test fun constantsAreTypedAndCheckedAgainstTheSlot() {
        val s = skill(offer { it is StepOffer.Script })
        val days = slot(s, 0, "days")
        assertEquals(7L, SkillEditor.setConstant(s, 0, days, "7")!!.steps[0].args["days"])
        assertNull(SkillEditor.setConstant(s, 0, days, "31"))
        assertNull(SkillEditor.setConstant(s, 0, days, "seven"))
        val folder = slot(s, 0, "folder")
        assertNull(SkillEditor.setConstant(s, 0, folder, "music"))
        assertEquals("documents", SkillEditor.setConstant(s, 0, folder, "documents")!!.steps[0].args["folder"])
        // Words never hold a template typed by hand, or a key.
        val n = skill(op(ConnSetup.NTFY_OP))
        val msg = slot(n, 0, "message")
        assertNull(SkillEditor.setConstant(n, 0, msg, "{{who}}"))
        assertNull(SkillEditor.setConstant(n, 0, msg, "ghp_" + "a1B2c3D4e5F6g7H8i9J0k1L2m3N4o5P6q7R8"))
    }

    @Test fun askEachTimeMakesAQuestionOfTheSlotsTypeAndAtMostThree() {
        var s = skill(offer { it is StepOffer.Script })
        s = SkillEditor.ask(s, 0, slot(s, 0, "folder"), "Which folder?", "documents")!!
        val q = s.params.single()
        assertEquals(ParamType.CHOICE, q.type)
        assertEquals(listOf("photos", "documents"), q.options.map { it.value })
        assertEquals("documents", q.default)
        assertEquals("{{folder}}", s.steps[0].args["folder"])
        s = SkillEditor.ask(s, 0, slot(s, 0, "days"), "How many days?", "7")!!
        assertEquals(1.0 to 30.0, s.params[1].min to s.params[1].max)
        assertTrue(SkillCheck.valid(s))
        assertEquals(emptyList<Any>(), SkillCheck.attention(s, links))
        // A default outside the limits falls back to the least.
        val t = SkillEditor.ask(skill(offer { it is StepOffer.Script }), 0, slot(s, 0, "days"), "Days?", "99")!!
        assertEquals("1", t.params.single().default)
        // A fourth question can't be made.
        var many = skill(op(ConnSetup.NTFY_OP), op(ConnSetup.NTFY_OP), op(ConnSetup.NTFY_OP), op(ConnSetup.NTFY_OP))
        for (i in 0..2) many = SkillEditor.ask(many, i, slot(many, i, "message"), "What?", "Hi")!!
        assertEquals(listOf("message", "message2", "message3"), many.params.map { it.name })
        assertNull(SkillEditor.ask(many, 3, slot(many, 3, "message"), "What?", "Hi"))
    }

    @Test fun anEarlierValueGoesInsideTheWordsAndItsStepGetsAnId() {
        val s = skill(travel, op(ConnSetup.NTFY_OP))
        val msg = slot(s, 1, "message")
        assertEquals(listOf(SkillRef("eta", "minutes"), SkillRef("eta", "km")), SkillEditor.chips(s, 1, links).map { it.first })
        assertNull(s.steps[0].id)
        val w = SkillEditor.setWords(s, 1, msg, "On my way home, about {{eta.minutes}} min.", links)!!
        assertEquals("On my way home, about {{eta.minutes}} min.", w.steps[1].args["message"])
        // The words alone don't name the step: picking the value does (whole, or as a condition).
        val picked = SkillEditor.pick(s, 1, msg, SkillRef("eta", "minutes"), links)!!
        assertEquals("eta", picked.steps[0].id)
        assertEquals(SkillTemplate.of(SkillRef("eta", "minutes")), picked.steps[1].args["message"])
        assertTrue(SkillCheck.valid(picked))
        assertEquals(emptyList<Any>(), SkillCheck.attention(picked, links))
        // Words naming something that isn't there, or that don't read, are refused.
        assertNull(SkillEditor.setWords(s, 1, msg, "About {{nope}} min.", links))
        assertNull(SkillEditor.setWords(s, 1, msg, "About {{eta.minutes min.", links))
    }

    @Test fun stepsWithoutIdsAreNamedTheSameWayEverywhere() {
        val s = skill(travel, travel, op(ConnSetup.NTFY_OP))
        assertEquals(listOf("eta.minutes", "eta.km", "eta2.minutes", "eta2.km"), SkillEditor.outputs(s, 2, links).map { it.first.toString() })
        val picked = SkillEditor.pick(s, 2, slot(s, 2, "message"), SkillRef("eta2", "minutes"), links)!!
        assertEquals(listOf(null, "eta2", null), picked.steps.map { it.id })
        assertTrue(SkillCheck.valid(picked))
        // Afterwards the list names them as before.
        assertEquals(listOf("eta.minutes", "eta.km", "eta2.minutes", "eta2.km"), SkillEditor.outputs(picked, 2, links).map { it.first.toString() })
    }

    @Test fun onlyIfTestsTypedValuesNeverWords() {
        var s = skill(travel, op(ConnSetup.NTFY_OP))
        s = SkillEditor.ask(s, 1, slot(s, 1, "message"), "What?", "Hi")!!
        val subjects = SkillEditor.subjects(s, 1, links)
        // The free-text question isn't offered; travel time's numbers are.
        assertEquals(listOf("eta.minutes", "eta.km"), subjects.map { it.ref.toString() })
        val minutes = subjects.first()
        assertEquals(listOf(IfTest.Set, IfTest.Unset), SkillEditor.tests(minutes))
        assertTrue(SkillEditor.numeric(minutes))
        val c = SkillEditor.setCondition(s, 1, SkillIf(minutes.ref, IfTest.Below(30.0)), links)!!
        assertEquals("eta", c.steps[0].id)
        assertTrue(SkillCheck.problems(c).toString(), SkillCheck.valid(c))
        assertEquals("Only if eta.minutes is less than 30", SkillEditor.condText(c.steps[1].cond!!).en())
        assertNull(SkillEditor.setCondition(c, 1, null, links)!!.steps[1].cond)
        // A yes_no question offers yes and no; a choice its values.
        var y = skill(offer { it is StepOffer.Script }, op(ConnSetup.NTFY_OP))
        y = SkillEditor.ask(y, 0, slot(y, 0, "folder"), "Which folder?", "photos")!!
        assertEquals(listOf(IfTest.Is("photos"), IfTest.Is("documents")), SkillEditor.tests(SkillEditor.subjects(y, 1, links).first { it.ref == SkillRef("folder") }))
    }

    @Test fun goOnOnlyAfterARead() {
        val s = skill(travel, op(ConnSetup.NTFY_OP))
        assertEquals(OnFail.GO_ON, SkillEditor.setOnFail(s, 0, OnFail.GO_ON, links)!!.steps[0].onFail)
        assertNull(SkillEditor.setOnFail(s, 1, OnFail.GO_ON, links))
        assertEquals(OnFail.STOP, SkillEditor.setOnFail(s, 1, OnFail.STOP, links)!!.steps[1].onFail)
    }

    @Test fun theRiskEffectsAndModelLines() {
        val lights = skill(op("light_off"))
        assertEquals("Simple: 1 step, changes your home", SkillEditor.risk(lights, links).en())
        assertEquals("Needs a model: no", SkillEditor.model(lights).en())
        val lock = skill(op("lock"))
        assertTrue(SkillEditor.risk(lock, links).en(), SkillEditor.risk(lock, links).en().startsWith("Risky: 1 step, locks, unlocks or opens"))
        var tell = skill(op(ConnSetup.NTFY_OP))
        tell = SkillEditor.ask(tell, 0, slot(tell, 0, "message"), "What should I say?", "I'm home.")!!
        assertEquals("Careful: 1 step, tells people and may use words Robot's model wrote", SkillEditor.risk(tell, links).en())
        assertEquals("Needs a model: yes, 1 question", SkillEditor.model(tell).en())
        val say = SkillEditor.newSkill("s-say123", "Hello", SkillEditor.step(StepOffer.Say, "Hello."), 1L, Lang.EN)
        assertEquals("Simple: 1 step", SkillEditor.risk(say, links).en())
        assertEquals(emptyList<Any>(), SkillEditor.chips(SkillEditor.effects(say, links)))
    }

    @Test fun theDryRunSendsNothingAndNeverShowsAKey() {
        var s = skill(op("light_off"), op(ConnSetup.NTFY_OP), op(WebhookForm.OP_ID), travel)
        s = SkillEditor.setConstant(s, 1, slot(s, 1, "message"), "I'm home.")!!
        s = SkillEditor.setConstant(s, 2, slot(s, 2, "room"), "kitchen")!!
        val masks = mapOf("c-nt0001" to mapOf(SecretPart.TOPIC to "…AB12"), "c-wh0001" to mapOf(SecretPart.TOKEN to "…9xYz"))
        val lines = SkillEditor.dryRun(s, connections, links) { masks[it].orEmpty() }.map { it.en() }
        assertEquals(4, lines.size)
        assertTrue(lines[0], lines[0].startsWith("Step 1: Request: POST https://home.example.org/api/services/light/turn_off"))
        assertTrue(lines[0], "light.kitchen" in lines[0])
        assertTrue(lines[1], "…AB12" in lines[1] && "I'm home." in lines[1])
        assertTrue(lines[2], "https://n8n.example.org/hook/kitchen" in lines[2] && "Authorization: your n8n token" in lines[2])
        assertTrue(lines[3], lines[3].startsWith("Step 4: Travel time to home · your OSRM"))
        // An earlier step's value isn't there before a run.
        val e = SkillEditor.setWords(skill(travel, op(ConnSetup.NTFY_OP)), 1, slot(skill(travel, op(ConnSetup.NTFY_OP)), 1, "message"), "About {{eta.minutes}} min.", links)!!
        assertEquals("Step 2: it uses an earlier step's answer, which only comes when the skill runs.", SkillEditor.dryRun(e, connections, links)[1].en())
        // A gone connection says so.
        assertEquals("Step 1: what it links to is gone.", SkillEditor.dryRun(skill(op("light_off")), emptyList(), ConnLinks(emptyList()))[0].en())
    }

    @Test fun aSkillHoldsAtMostEightSteps() {
        var s = skill(StepOffer.Say)
        repeat(7) { s = SkillEditor.add(s, SkillEditor.step(StepOffer.Say, "Hi."))!! }
        assertEquals(8, s.steps.size)
        assertNull(SkillEditor.add(s, SkillEditor.step(StepOffer.Say, "Hi.")))
        assertEquals(StepKind.SAY, s.steps.last().kind)
        assertNotNull(SkillEditor.slots(s, 0, connections, scripts).singleOrNull { it.name == "text" })
        assertFalse(SkillEditor.reads(s, 0, links))
    }
}
