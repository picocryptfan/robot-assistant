// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.BrainMsg
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.OneShotFold
import app.heyrobot.core.OneShotKind
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.OneShotRules
import app.heyrobot.core.Prepared
import app.heyrobot.core.Priority
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolMode
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.messages.MessageTaint
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Skills 1.3, plans (design §9; skills-1.3-plan.md S3.7, M4 without the helper): the router, the strict read, the run. */
class PlanTest {
    private val on = SkillPlans.PLANNABLE.toSet()
    private fun step(use: String, vararg a: Pair<String, Any?>) = mapOf("use" to use, "args" to mapOf(*a))
    private fun call(vararg steps: Map<String, Any?>) = RawToolCall(SkillPlans.TOOL, mapOf("steps" to steps.toList()))
    private fun read(vararg steps: Map<String, Any?>, cap: Int = 5, tools: Set<ToolName> = on) =
        SkillPlans.read(listOf(call(*steps)), "", tools, cap, Lang.EN, 0L, fromContent = false)
    private fun ok(r: PlanRead) = (r as PlanRead.Ok).plan
    private val weather = step("weather")
    private val timer = step("set_timer", "minutes" to 10)

    // ---------------------------------------------------------------- the router

    @Test fun twoActionsMakeAPlanAndOneDoesnt() {
        assertEquals(Lang.EN, PlanRouter.route("what's the weather and set a ten minute timer")?.lang)
        assertNotNull(PlanRouter.route("set a timer for 10 minutes, then tell me the weather"))
        assertNotNull(PlanRouter.route("first check the weather, after that set an alarm for 7"))
        assertEquals(Lang.FR, PlanRouter.route("mets un minuteur de 10 minutes pis donne-moi la météo")?.lang)
        assertEquals(Lang.FR, PlanRouter.route("quelle est la météo, ensuite mets un minuteur de 5 minutes")?.lang)
        assertNull(PlanRouter.route("set a timer for 10 minutes"))
        assertNull(PlanRouter.route("what's the weather and is it windy"))
        assertNull(PlanRouter.route("tell me a joke and then another one"))
        assertNull(PlanRouter.route("depuis quand il pleut"))
    }

    @Test fun planThisIsAPlanWhateverItSays() {
        val a = PlanRouter.route("plan this: set a timer for 10 minutes")!!
        assertTrue(a.explicit)
        assertEquals("set a timer for 10 minutes", a.words)
        assertEquals(Lang.FR, PlanRouter.route("planifie : mets un minuteur")?.lang)
    }

    // ---------------------------------------------------------------- the strict read

    @Test fun aGoodPlanIsReadWithItsReadsFirstAndItsTier() {
        val p = ok(read(weather, timer))
        assertEquals(listOf(StepKind.WEATHER, StepKind.SET_TIMER), p.steps.map { it.kind })
        assertEquals(1, p.readsFirst)
        assertEquals(600L, p.steps[1].args["seconds"])
        // 0 past two, 1 value, no effects, +2 for a plan and +1 per step: Careful.
        assertEquals(5, p.points)
        assertEquals(SkillTier.CAREFUL, p.tier)
        assertEquals(SkillMade.DRAFT_DEVICE, SkillPlans.draft(p, "s-abcdef", Lang.EN, 0L).made)
    }

    @Test fun anythingOffShapeIsRefusedWhole() {
        assertEquals(PlanRead.Refused(PlanRefusal.TOO_MANY), read(weather, timer, timer, timer, timer, timer))
        assertEquals(PlanRead.Refused(PlanRefusal.TOO_MANY), read(weather, timer, timer, timer, cap = 3))
        assertEquals(PlanRead.Refused(PlanRefusal.NOT_ON), read(weather, step("make_plan")))
        assertEquals(PlanRead.Refused(PlanRefusal.NOT_ON), read(weather, step("call", "to" to "Sam")))
        assertEquals(PlanRead.Refused(PlanRefusal.NOT_ON), read(weather, timer, tools = on - ToolName.SET_TIMER))
        assertEquals(PlanRead.Refused(PlanRefusal.TEMPLATE), read(weather, step("add_note", "text" to "{{s1.reply}}")))
        assertEquals(PlanRead.Refused(PlanRefusal.BAD_ARGS), read(weather, step("set_alarm", "hour" to 31)))
        assertEquals(PlanRead.Refused(PlanRefusal.APP_NOT_LAST), read(step("open_app", "name" to "Signal"), timer))
        assertEquals(PlanRead.Refused(PlanRefusal.BAD_SHAPE), read(mapOf("use" to "weather", "args" to emptyMap<String, Any?>(), "then" to "x")))
        assertEquals(PlanRead.Refused(PlanRefusal.BAD_SHAPE),
            SkillPlans.read(listOf(call(weather), RawToolCall("set_timer", mapOf("minutes" to 1))), "", on, 5, Lang.EN, 0L, false))
        assertEquals(PlanRead.Refused(PlanRefusal.NO_PLAN), SkillPlans.read(emptyList(), "Sure! Here you go.", on, 5, Lang.EN, 0L, false))
    }

    @Test fun aModelWithoutToolsWritesThePlanAsText() {
        val text = "Here: {\"steps\": [{\"use\": \"weather\", \"args\": {}}, {\"use\": \"set_timer\", \"args\": {\"minutes\": 10}}]} done"
        assertEquals(2, ok(SkillPlans.read(emptyList(), text, on, 5, Lang.EN, 0L, false)).steps.size)
    }

    @Test fun thePlanOneShotHasMakePlanOnlyAndItsCallComesBack() {
        val req = BrainMsg.OneShot(OneShotKind.PLAN, Priority.PANEL, SkillPlans.system(listOf(ToolName.WEATHER, ToolName.SET_TIMER), 5), "w", 64)
        val c = OneShotRules.withPlanTool(OneShotRules.conversation(req), req, ToolMode.TOOLS_CONSTRAINED)
        assertEquals(SkillPlans.TOOL_JSON, c.forced)
        assertTrue(c.constrained)
        assertEquals(emptyList<ToolName>(), c.tools)
        assertEquals(ToolMode.CHAT_ONLY, OneShotRules.withPlanTool(OneShotRules.conversation(req), req, ToolMode.TEXT_CALLS).mode)
        val other = req.copy(kind = OneShotKind.TRANSLATE)
        assertNull(OneShotRules.withPlanTool(OneShotRules.conversation(other), other, ToolMode.TOOLS).forced)
        assertTrue("weather(place: string" in req.system || "- weather(" in req.system)
        val fold = OneShotFold(0)
        fold.event(BrainMsg.ToolCallsMsg(listOf(call(weather))), 1)
        fold.event(BrainMsg.Done(DoneReason.TOOL_CALLS, 1, 1, 1.0, false), 2)
        assertEquals(listOf(call(weather)), (fold.result as OneShotResult.Done).calls)
    }

    @Test fun theSettingsFileStaysV1UntilAPlanRowChanges() {
        assertEquals("{\"v\":1,\"modelPicks\":true}", SkillSettingsCodec.encode(SkillSettings()))
        val s = SkillSettings(planApprove = PlanApprove.STEP_BY_STEP, planCap = 4)
        assertEquals(s, SkillSettingsCodec.decode(SkillSettingsCodec.encode(s)).settings)
        assertEquals(SkillSettingsFile.Damaged, SkillSettingsCodec.decode("{\"v\":1,\"planCap\":4}"))
        assertEquals(SkillSettingsFile.Damaged, SkillSettingsCodec.decode("{\"v\":2,\"planCap\":9}"))
        assertEquals(SkillSettingsFile.Damaged, SkillSettingsCodec.decode("{\"v\":2,\"planApprove\":\"none\"}"))
        assertEquals(SkillSettingsFile.TooNew(3), SkillSettingsCodec.decode("{\"v\":3}"))
    }

    // ---------------------------------------------------------------- running it (M4 without the helper)

    private class Phone(var settings0: RobotSettings) : TurnActions {
        val log = mutableListOf<String>()
        var result: (Action) -> Executed = { if (it.tool == ToolName.WEATHER) Executed(ActionStatus.DONE, "Sunny, 12 degrees.") else Executed(ActionStatus.DONE, "Done.") }
        override val locked get() = false
        override val settings get() = settings0
        override suspend fun prepare(action: Action): Prepared { log += "prepare ${action.tool.id}"; return Prepared.Ready(action) }
        override suspend fun run(action: Action): Executed { log += "run ${action.tool.id}"; return result(action) }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c; if (c.status == ActionStatus.PENDING) pending.trySend(c.id) }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private var ids = 0
    private val said = mutableListOf<String>()
    /** Reads just run; the timer and notes at Ask first (a card for the level only). */
    private val askFirst = RobotSettings(approvals = ToolName.entries.associateWith { if (it == ToolName.SET_TIMER || it == ToolName.ADD_NOTE) Approval.ASK else Approval.AUTO })

    private fun planRun(p: SkillPlan, phone: Phone, view: View, conf: Confirmations, approve: PlanApprove = PlanApprove.ALL_AT_ONCE,
        asked: Set<ToolName> = setOf(ToolName.WEATHER, ToolName.SET_TIMER, ToolName.ADD_NOTE)) =
        PlanRun(p, asked, phone, view, conf, MessageTaint.NONE, Langs.EN, { "c${++ids}" }, { said += it }, approve)

    @Test fun theWeatherIsSaidAtOnceAndDoTheRestSetsTheTimer() = runBlocking {
        val p = ok(read(weather, timer))
        val phone = Phone(askFirst)
        val view = View()
        val run = planRun(p, phone, view, Confirmations())
        run.reads(untrusted = false)
        assertEquals(listOf("Sunny, 12 degrees."), said)
        assertEquals(listOf("prepare weather", "run weather"), phone.log)
        assertTrue(run.lines(SkillStrings.planModel()).first().en().endsWith("· done"))
        // Do the rest: the timer's Ask first card is answered by the plan card's tap; it never shows waiting.
        val out = run.rest(stepByStep = false, untrusted = false)
        assertEquals(listOf("prepare weather", "run weather", "prepare set_timer", "run set_timer"), phone.log)
        assertFalse(view.cards.values.any { it.status == ActionStatus.PENDING })
        assertEquals(2, out.done)
        assertTrue(run.finished)
        assertTrue(run.line().en().startsWith("Plan done: 2 of 2 steps."))
    }

    @Test fun stepTwoFailingStopsThePlanAndSaysTheTimerDidntRun() = runBlocking {
        val p = ok(read(weather, step("add_note", "text" to "buy milk"), timer))
        val phone = Phone(askFirst)
        phone.result = { if (it.tool == ToolName.ADD_NOTE) Executed(ActionStatus.FAILED, "Notes couldn't be saved.") else Executed(ActionStatus.DONE, "Sunny.") }
        val run = planRun(p, phone, View(), Confirmations())
        run.reads(untrusted = false)
        run.rest(stepByStep = false, untrusted = false)
        assertFalse("run set_timer" in phone.log)
        assertTrue(run.stopped)
        val line = run.line().en()
        assertTrue(line, line.startsWith("The plan stopped at step 2: "))
        assertTrue(line, "Didn't run: " in line && "imer" in line)
        assertTrue(run.lines(SkillStrings.planModel())[2].en().endsWith("· didn't run"))
    }

    @Test fun stepByStepAndUnaskedStepsShowTheirOwnCards() = runBlocking {
        val p = ok(read(weather, timer))
        for ((approve, asked) in listOf(PlanApprove.STEP_BY_STEP to setOf(ToolName.WEATHER, ToolName.SET_TIMER), PlanApprove.ALL_AT_ONCE to setOf(ToolName.WEATHER))) {
            val phone = Phone(askFirst)
            val view = View()
            val conf = Confirmations()
            val run = planRun(p, phone, view, conf, approve, asked)
            if (asked.size == 2) run.reads(untrusted = false)
            val job = async { run.rest(stepByStep = false, untrusted = false) }
            val id = withTimeout(5_000) { view.pending.receive() }
            assertEquals(ToolName.SET_TIMER, view.cards.getValue(id).tool)
            conf.answer(id, false)
            job.await()
            assertFalse("run set_timer" in phone.log)
            assertTrue(run.line().en().startsWith("The plan stopped at step 2"))
        }
    }

    /** Q6 (4 Nov 2026): messages still get their own card. A text to an exact contact has no reason but the level, yet Do the rest leaves it. */
    @Test fun doTheRestLeavesAMessageItsOwnCard() = runBlocking {
        val p = ok(read(weather, timer, step("send_text", "to" to "Sam", "message" to "home soon"))) // the draft opens an app: last
        val phone = Phone(RobotSettings(approvals = ToolName.entries.associateWith {
            if (it == ToolName.SET_TIMER || it == ToolName.SEND_TEXT) Approval.ASK else Approval.AUTO }))
        val view = View()
        val conf = Confirmations()
        val run = planRun(p, phone, view, conf, asked = setOf(ToolName.WEATHER, ToolName.SEND_TEXT, ToolName.SET_TIMER))
        run.reads(untrusted = false)
        val job = async { run.rest(stepByStep = false, untrusted = false) }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(ToolName.SEND_TEXT, view.cards.getValue(id).tool)
        assertTrue("run set_timer" in phone.log)
        assertFalse("run send_text" in phone.log)
        conf.answer(id, true)
        job.await()
        // The timer before it was still answered by Do the rest; the text ran only on its own card's yes.
        assertEquals(listOf("run weather", "run set_timer", "run send_text"), phone.log.filter { it.startsWith("run") })
        assertTrue(run.finished)
    }

    @Test fun theCallsPastTheLimitBecomeAPlan() {
        val p = ok(SkillPlans.check(listOf(RawToolCall("set_timer", mapOf("minutes" to 5)), RawToolCall("weather", emptyMap())), on, 5, Lang.EN, 0L, false))
        assertEquals(0, p.readsFirst)
    }
}
