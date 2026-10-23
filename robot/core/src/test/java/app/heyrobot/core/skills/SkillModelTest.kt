// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainModel
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.TurnStrings
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase B (plan §8, §11): SkillCalls, SkillFill, SkillRisk, SkillStats, and a model's skill call through a real TurnRunner. */
class SkillModelTest {
    private val home = SkillFixtures.headingHome()
    private val pasta = SkillFixtures.pasta()

    /** SK13's skill: a text to Sam whose message is a question, at Just do it. */
    private val team = Skill("s-team01", 1, "Team reminder", listOf("team reminder"), emptyList(),
        listOf(SkillParam("message", ParamType.TEXT, "Standup at 9", "What should it say?", "Ça dit quoi?")),
        listOf(SkillStep.of(StepKind.SEND_TEXT, mapOf("to" to "Sam", "message" to "{{message}}"))), SkillLevel.JUST_DO_IT,
        SkillProvenance(SkillMade.VOICE, false, 1L, 1L))

    private fun menu(vararg skills: Skill, now: List<Skill> = skills.toList()): SkillMenu =
        SkillMenu(SkillSpecs.describe(skills.toList(), skills.toList())) { id -> now.firstOrNull { it.id == id } }

    // ------------------------------------------------------------------ SkillCalls

    @Test fun callsAreReadAgainstWhatWasDescribed() {
        val m = menu(home, pasta)
        val ok = SkillCalls.parse(RawToolCall("skill_heading_home", mapOf("who" to "Alex")), m) as SkillCall.Ok
        assertEquals(home.id, ok.skill.id)
        assertEquals(mapOf("who" to "Alex"), ok.values)
        // A bare number is read as its text; an empty value as none.
        assertEquals(mapOf("length" to "8"), (SkillCalls.parse(RawToolCall("skill_pasta", mapOf("length" to 8)), m) as SkillCall.Ok).values)
        assertEquals(emptyMap<String, String>(), (SkillCalls.parse(RawToolCall("skill_pasta", mapOf("length" to " ")), m) as SkillCall.Ok).values)
        assertTrue(SkillCalls.isSkill("skill_pasta") && !SkillCalls.isSkill("set_timer"))
    }

    @Test fun refusedNeverRepaired() {
        val m = menu(home)
        fun code(raw: RawToolCall, mm: SkillMenu = m, remote: Boolean = false) = (SkillCalls.parse(raw, mm, remote) as SkillCall.Refused).code
        assertEquals(SkillWarnCode.UNKNOWN, code(RawToolCall("skill_pasta", emptyMap())))
        assertEquals(SkillWarnCode.BAD_ARGS, code(RawToolCall("skill_heading_home", mapOf("whom" to "Alex"))))
        assertEquals(SkillWarnCode.BAD_ARGS, code(RawToolCall("skill_heading_home", mapOf("who" to listOf("Alex")))))
        assertEquals(SkillWarnCode.BAD_ARGS, code(RawToolCall("skill_heading_home", mapOf("who" to true))))
        // Changed since it was described: a new rev, deleted, or turned off.
        assertEquals(SkillWarnCode.CHANGED, code(RawToolCall("skill_heading_home", emptyMap()), menu(home, now = listOf(home.copy(rev = 4)))))
        assertEquals(SkillWarnCode.CHANGED, code(RawToolCall("skill_heading_home", emptyMap()), menu(home, now = emptyList())))
        assertEquals(SkillWarnCode.CHANGED, code(RawToolCall("skill_heading_home", emptyMap()), menu(home, now = listOf(home.copy(level = SkillLevel.OFF)))))
        assertEquals("This skill changed. Ask again.", (SkillCalls.parse(RawToolCall("skill_heading_home", emptyMap()), menu(home, now = emptyList())) as SkillCall.Refused).why.en())
        // A helper is never offered skills.
        assertEquals(SkillWarnCode.NOT_OFFERED, code(RawToolCall("skill_heading_home", emptyMap()), remote = true))
    }

    // ------------------------------------------------------------------ SkillFill

    @Test fun yoursOrTheModels() {
        val people = listOf(listOf("Alex Martin", "Alex", "my brother"), listOf("Sam Lee", "Sam"))
        val f = SkillFills.of(home, mapOf("who" to "Alex Martin"), "tell my brother I'm heading home", people) as SkillFill.Ok
        assertEquals(mapOf("who" to FillSource.YOURS), f.sources)
        assertEquals("Alex Martin", f.values["who"])
        val g = SkillFills.of(home, mapOf("who" to "Sam"), "I'm going home now", people) as SkillFill.Ok
        assertEquals(FillSource.MODEL, g.sources["who"])
        assertEquals(setOf("who"), g.written)
        // The text of SK13, every word yours: still a model fill (D16), marked yours.
        val t = SkillFills.of(team, mapOf("message" to "the standup moved to 10"), "team reminder, the standup moved to 10") as SkillFill.Ok
        assertTrue(t.fromModel)
        assertEquals(FillSource.YOURS, t.sources["message"])
        // Nothing from the call: the defaults, or what the router read.
        val d = SkillFills.of(pasta, emptyMap(), "pasta", heard = mapOf("length" to "480")) as SkillFill.Ok
        assertFalse(d.fromModel)
        assertEquals("480", d.values["length"])
    }

    @Test fun numbersInBothLanguages() {
        val en = SkillFills.of(pasta, mapOf("length" to "8 minutes"), "pasta for eight minutes") as SkillFill.Ok
        assertEquals("480", en.values["length"])
        assertEquals(FillSource.YOURS, en.sources["length"])
        val fr = SkillFills.of(pasta, mapOf("length" to "8 minutes"), "les pâtes pour 8 minutes") as SkillFill.Ok
        assertEquals(FillSource.YOURS, fr.sources["length"])
        val bare = SkillFills.of(pasta, mapOf("length" to "9"), "start the pasta") as SkillFill.Ok
        assertEquals("540", bare.values["length"])
        assertEquals(FillSource.MODEL, bare.sources["length"])
        assertEquals("19:30", SkillFills.read(ParamType.TIME, "7:30 pm"))
        assertEquals("07:00", SkillFills.read(ParamType.TIME, "7:00"))
    }

    @Test fun valuesThatCantBeUsedAreRefused() {
        assertTrue(SkillFills.of(pasta, mapOf("length" to "forever"), "pasta") is SkillFill.Refused)
        assertTrue(SkillFills.of(pasta, mapOf("length" to "30 hours"), "pasta") is SkillFill.Refused)
        assertNull(SkillFills.read(ParamType.TIME, "tomorrow-ish"))
        assertNull(SkillFills.read(ParamType.TEXT, "x".repeat(2_001)))
        // A key or password is never a value.
        assertNull(SkillFills.read(ParamType.TEXT, "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }

    // ------------------------------------------------------------------ SkillRisk

    @Test fun pointsAndTiers() {
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(home, emptySet()))
        // SK13: a text the model filled (+2) to someone (+3): Careful.
        assertEquals(5, SkillRisk.points(team, setOf("message")))
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(team, setOf("message")))
        // Heading home with who filled: +1, and a text step +3.
        assertEquals(4, SkillRisk.points(home, setOf("who")))
        // Pasta's length (+1) and its text step (+3).
        assertEquals(SkillTier.CAREFUL, SkillRisk.tier(pasta, setOf("length")))
        val timer = SkillFixtures.simple("s-timer1", "Tea").copy(params = listOf(SkillParam("t", ParamType.DURATION, "60", "How long?", "Combien?")),
            steps = listOf(SkillStep.of(StepKind.SET_TIMER, mapOf("seconds" to "{{t}}"))))
        assertEquals(SkillTier.SIMPLE, SkillRisk.tier(timer, setOf("t")))
        // Two texts the model wrote to someone it named: Risky.
        val late = SkillLibrary.skills.getValue("late")
        assertEquals(SkillTier.RISKY, SkillRisk.tier(late, setOf("who", "message")))
    }

    @Test fun theRule() {
        fun go(s: Skill, named: Boolean = true, filled: Boolean = true, tier: SkillTier, trusted: Boolean = false, locked: Boolean = false,
            phoneLock: Boolean = false, messages: Boolean = false) = SkillRisk.decide(s, named, filled, tier, trusted, locked, phoneLock, messages)
        val ask = team.copy(level = SkillLevel.ASK_FIRST)
        val just = team
        // Ask first: a card, tap only when Risky.
        for (t in SkillTier.entries) assertEquals(ModelGo.Card(tapOnly = t == SkillTier.RISKY, didYouMean = false), go(ask, tier = t))
        // Just do it.
        assertEquals(ModelGo.Run(), go(just, tier = SkillTier.SIMPLE))
        assertEquals(ModelGo.Card(tapOnly = false, didYouMean = false), go(just, tier = SkillTier.CAREFUL))
        assertEquals(ModelGo.Run(trusted = true), go(just, tier = SkillTier.CAREFUL, trusted = true))
        assertEquals(ModelGo.Card(tapOnly = true, didYouMean = false), go(just, tier = SkillTier.RISKY, trusted = true))
        // D18: a pick without your words naming it always asks, whatever the level; Risky, content or a message: tap only.
        assertEquals(ModelGo.Card(tapOnly = false, didYouMean = true), go(just, named = false, tier = SkillTier.SIMPLE, trusted = true))
        assertEquals(ModelGo.Card(tapOnly = false, didYouMean = true), go(just, named = false, filled = false, tier = SkillTier.SIMPLE))
        assertEquals(ModelGo.Card(tapOnly = true, didYouMean = true), go(just, named = false, tier = SkillTier.RISKY))
        assertEquals(ModelGo.Card(tapOnly = true, didYouMean = true), go(just, named = false, tier = SkillTier.SIMPLE, messages = true))
        // Nothing filled: phase A's policy.
        assertEquals(ModelGo.Run(), go(just, filled = false, tier = SkillTier.SIMPLE))
        assertEquals(ModelGo.Card(tapOnly = false, didYouMean = false), go(ask, filled = false, tier = SkillTier.SIMPLE))
        assertEquals(ModelGo.Card(tapOnly = true, didYouMean = false), go(ask, filled = false, tier = SkillTier.SIMPLE, messages = true))
        // Off never runs; a locked phone runs nothing.
        assertTrue(go(just.copy(level = SkillLevel.OFF), tier = SkillTier.SIMPLE) is ModelGo.Refused)
        assertEquals(TurnStrings.unlockThenAsk().en(), (go(just, tier = SkillTier.SIMPLE, locked = true) as ModelGo.Refused).why.en())
        assertEquals(LockStrings.closedNow().en(), (go(just, tier = SkillTier.SIMPLE, locked = true, phoneLock = true) as ModelGo.Refused).why.en())
    }

    // ------------------------------------------------------------------ SkillStats

    @Test fun nineOfTheLastTen() {
        var st = SkillState()
        repeat(9) { st = SkillStats.record(st, team, FillModel.FAST, right = true) }
        assertFalse("10 samples at least", SkillStats.trusted(st, team, FillModel.FAST))
        st = SkillStats.record(st, team, FillModel.FAST, right = false)
        assertTrue(SkillStats.trusted(st, team, FillModel.FAST))
        // A ring of 10: the wrong one stays until 10 more come; another wrong makes 8 of 10.
        st = SkillStats.record(st, team, FillModel.FAST, right = false)
        assertFalse(SkillStats.trusted(st, team, FillModel.FAST))
        assertEquals(10, SkillStats.samples(st, team, FillModel.FAST).size)
        // Per model.
        assertFalse(SkillStats.trusted(st, team, FillModel.SMARTER))
        // A level change keeps them (rev is the same); a new rev starts over.
        assertEquals(10, SkillStats.samples(st, team.copy(level = SkillLevel.ASK_FIRST), FillModel.FAST).size)
        assertEquals(0, SkillStats.samples(st, team.copy(rev = 2), FillModel.FAST).size)
        val st2 = SkillStats.record(st, team.copy(rev = 2), FillModel.FAST, right = true)
        assertEquals(listOf(true), SkillStats.samples(st2, team.copy(rev = 2), FillModel.FAST))
        // Settings' line after 5.
        assertEquals(listOf("On Fast: 8 of the last 10 right"), SkillStats.lines(st, team).map { it.en() })
        assertEquals(emptyList<String>(), SkillStats.lines(st2, team.copy(rev = 2)).map { it.en() })
        // After a restore, nothing.
        assertEquals(0, SkillStats.samples(SkillStats.cleared(st), team, FillModel.FAST).size)
    }

    @Test fun samplesSurviveTheFileAndOnlyCounts() {
        var st = SkillState(lastRun = mapOf(team.id to SkillRunRecord(5L, 1, 1)))
        st = SkillStats.record(st, team, FillModel.FAST, true)
        st = SkillStats.record(st, team, FillModel.OWN, false)
        val text = SkillStateFile.encode(st)
        assertEquals(st, SkillStateFile.decode(text))
        assertFalse(text.contains("Team") || text.contains("Sam") || text.contains("message"))
        // Damage drops only what can't be read.
        assertEquals(st.lastRun, SkillStateFile.decode(text.replace("\"1\"", "\"x\"")).lastRun)
        assertEquals(FillModel.FAST, FillModel.of(BrainModel.GENERAL))
        assertEquals(FillModel.OWN, FillModel.of(BrainModel.OWN))
        // A deleted skill's samples go with its last run.
        val kept = SkillStateFile.record(st, "s-other1", SkillRunRecord(6L, 1, 1), setOf("s-other1"))
        assertTrue(team.id !in kept.fills)
    }

    // ------------------------------------------------------------------ TurnRunner

    private class Phone(val approval: Approval = Approval.ASK, val ran: (SkillCall.Ok, Boolean) -> SkillRan) : TurnActions {
        val log = mutableListOf<String>()
        override val locked = false
        override val settings = RobotSettings(approvals = ToolName.entries.associateWith { approval })
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed { log += "run ${action.tool.id}"; return Executed(ActionStatus.DONE, "Done.") }
        override suspend fun runSkill(call: SkillCall.Ok, untrusted: Boolean): SkillRan { log += "skill ${call.skill.id} $untrusted"; return ran(call, untrusted) }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private class Model(val calls: List<RawToolCall>) : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        var abandoned = false
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(calls)
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { answers += results; return ModelOutcome.Done(DoneReason.END) }
        override fun close() {}
        override fun abandon(results: List<Pair<String, Map<String, Any?>>>) { abandoned = true }
    }

    private var ids = 0

    private fun turn(model: Model, phone: Phone, view: View, m: SkillMenu, untrusted: Boolean = false, remote: Boolean = false) =
        TurnRunner(model, phone, view, Confirmations(), untrusted, remote = remote, langs = Langs.EN, newId = { "t${++ids}" }, skills = m)

    @Test(timeout = 20_000) fun aSkillCallRunsThroughThePortAndTheModelHearsEachStep() = runBlocking {
        val out = SkillOutcome(listOf(StepResult(0, StepKind.SEND_TEXT, ActionStatus.DRAFT_OPENED, null, false, false),
            StepResult(1, StepKind.MAPS_NAVIGATE, ActionStatus.DONE, null, false, false)), 2, 2, null, false, SkillStrings0.line)
        val phone = Phone { _, _ -> SkillRan(SkillRan.statusOf(out), SkillRan.forModel(out), asked = true) }
        val model = Model(listOf(RawToolCall("skill_heading_home", mapOf("who" to "Alex"))))
        turn(model, phone, View(), menu(home)).run()
        assertEquals(listOf("skill ${home.id} false"), phone.log)
        val r = model.answers.single().single()
        assertEquals("skill_heading_home", r.first)
        assertEquals("draft_opened_not_sent", r.second["status"])
        assertEquals(listOf(mapOf("step" to 1, "status" to "draft_opened_not_sent"), mapOf("step" to 2, "status" to "done")), r.second["steps"])
    }

    @Test(timeout = 20_000) fun aStepsContentMakesTheNextCallAsk() = runBlocking {
        // The skill read a note (content); the model's next call in the same answer, a text, now shows its card.
        // Every action just runs; the timer alone would run without a card.
        val alone = Phone(Approval.AUTO) { _, _ -> SkillRan(ActionStatus.DONE, ActionStatus.DONE.toolResponse("1 of 1 steps done")) }
        turn(Model(listOf(RawToolCall("skill_heading_home", emptyMap()), RawToolCall("set_timer", mapOf("minutes" to 5)))), alone, View(), menu(home)).run()
        assertTrue(alone.log.contains("run set_timer"))
        // The skill read a note (content): the model's next call in the same answer now shows its card.
        val phone = Phone(Approval.AUTO) { _, _ -> SkillRan(ActionStatus.DONE, ActionStatus.DONE.toolResponse("1 of 1 steps done"), content = true) }
        val model = Model(listOf(RawToolCall("skill_heading_home", emptyMap()), RawToolCall("set_timer", mapOf("minutes" to 5))))
        val view = View()
        val conf = Confirmations()
        val runner = TurnRunner(model, phone, view, conf, false, langs = Langs.EN, newId = { "t${++ids}" }, skills = menu(home))
        val job = async { runner.run() }
        withTimeout(5_000) { while (view.cards.values.none { it.status == ActionStatus.PENDING }) delay(10) }
        conf.declineAll()
        job.await()
        assertTrue(phone.log.none { it == "run set_timer" })
    }

    @Test(timeout = 20_000) fun refusalsAndTheBudget() = runBlocking {
        val phone = Phone { _, _ -> SkillRan(ActionStatus.DONE, ActionStatus.DONE.toolResponse("ok")) }
        val model = Model(listOf(RawToolCall("skill_pasta", emptyMap()), RawToolCall("skill_heading_home", emptyMap()),
            RawToolCall("skill_heading_home", emptyMap())))
        val view = View()
        turn(model, phone, view, menu(home)).run()
        // The unknown one is refused (and counts), one runs, the third is over the limit of 2.
        assertEquals(listOf("skill ${home.id} false"), phone.log)
        assertEquals(listOf(ActionStatus.INVALID, ActionStatus.DONE, ActionStatus.SKIPPED).map { it.toolResponse(null)["status"] },
            model.answers.single().map { it.second["status"] })
        assertTrue(view.cards.values.first().summary == "A skill")
        // A helper's skill call is refused as not offered, and the port never runs.
        val p2 = Phone { _, _ -> error("never") }
        turn(Model(listOf(RawToolCall("skill_heading_home", emptyMap()))), p2, View(), menu(home), remote = true).run()
        assertTrue(p2.log.isEmpty())
        // No menu (the default port, or nothing described): unknown.
        val p3 = Phone { _, _ -> error("never") }
        turn(Model(listOf(RawToolCall("skill_heading_home", emptyMap()))), p3, View(), SkillMenu.NONE).run()
        assertTrue(p3.log.isEmpty())
    }

    @Test(timeout = 20_000) fun didYouMeanNoEndsTheTurnForAnotherAnswer() = runBlocking {
        val phone = Phone { _, _ -> SkillRan(ActionStatus.DECLINED, ActionStatus.DECLINED.toolResponse(null), withoutSkills = true) }
        val model = Model(listOf(RawToolCall("skill_heading_home", emptyMap()), RawToolCall("set_timer", mapOf("minutes" to 5))))
        val result = turn(model, phone, View(), menu(home)).run()
        assertTrue(result.withoutSkills)
        assertTrue(model.abandoned)
        assertTrue(model.answers.isEmpty())
        assertTrue(phone.log.none { it.startsWith("run") })
    }

    @Test fun theDefaultPortRefuses() = runBlocking {
        val plain = object : TurnActions {
            override val locked = false
            override val settings = RobotSettings()
            override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
            override suspend fun run(action: Action): Executed = Executed(ActionStatus.DONE, "Done.")
        }
        val c = SkillCalls.parse(RawToolCall("skill_heading_home", emptyMap()), menu(home)) as SkillCall.Ok
        assertEquals(ActionStatus.SKIPPED, plain.runSkill(c, false).status)
    }

    private object SkillStrings0 {
        val line = app.heyrobot.core.i18n.SkillStrings.done("name" to "Heading home", "done" to "2", "total" to "2")
    }
}
