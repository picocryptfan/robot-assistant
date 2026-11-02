// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.sums

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.HelperTools
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.ToolSpecs
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * calculate (everyday-set-plan.md §4.4, D8): its expression grammar and limits, the three text-call shapes, the turn that
 * ends with code's reply (the model's own words before the call never stay), its group's words, and units never a dose's.
 */
class CalculateToolTest {
    private val en = Langs(Lang.EN, Lang.EN, Lang.EN)

    @Test fun aLeadingMinusTakesTheWholePower() {
        assertEquals("-3^2 = -9", calc("-3^2"))
        assertEquals("(-3)^2 = 9", calc("(-3)^2"))
        assertTrue(calc("-2^2+1").endsWith("= -3"))
        assertTrue(calc("1+-2^2").endsWith("= -3"))
    }

    private fun calc(e: String, from: String? = null, to: String? = null): String =
        Calculate.run(CalcAsk(e, from, to)).text.render(en)

    @Test fun theExpressionGrammar() {
        assertEquals("3 × 45 × 1.13 = 152.55", calc("3*45*1.13"))
        assertEquals("3 times 45 times 1.13 is 152.55.", Calculate.run(CalcAsk("3 * 45 * 1.13")).text.render(en.voice))
        assertEquals("3 × $45.00 × 1.13 = $152.55", calc("3 × $45 × 1.13"))
        assertEquals("(3 × 45) × 1.13 = 152.55", calc("(3*45)*1.13"))
        assertEquals("$64.50 + 13% = about $72.89", calc("64.50$ + 13%"))
        assertEquals("18% × $64.50 = $11.61", calc("18% of $64.50"))
        assertEquals("2^10 = 1,024", calc("2**10"))
        assertEquals("√2 = about 1.414214", calc("√2"))
        assertEquals("√(144) = 12", calc("sqrt(144)"))
        assertEquals("1,500 ÷ 12 = 125", calc("1,500 / 12"))
        assertEquals("64.5 × 2 = 129", calc("64,50 * 2"))
        assertEquals("-5 + 3 = -2", calc("-5 + 3"))
        assertEquals("You can't divide by zero.", calc("5/0"))
        assertEquals("That number is too big for Robot to say.", calc("10^30"))
        assertEquals("Robot works out whole powers only, up to 999.", calc("9^9^9"))
        for (bad in listOf("", "3 apples * 2", "2 +", "((2)", "rm -rf /", "x", "2^^3", "1".repeat(30), "(".repeat(20) + "1" + ")".repeat(20), "2" + "+2".repeat(80)))
            assertEquals(bad, SumResult.UNREADABLE, Calculate.run(CalcAsk(bad)).result)
    }

    @Test fun conversionsAndNoDose() {
        assertEquals("5 miles is about 8.05 km.", calc("5", "miles", "km"))
        assertEquals("350 °F is about 177 °C.", calc("350", "°F", "Celsius"))
        assertEquals("1 L is 4 cups of 250 mL. In US measure, about 4.2 US cups.", calc("1", "litre", "cups"))
        // No milligrams, micrograms, IU or cc: Sums doesn't know them, so a dose is never converted by code.
        for (u in listOf("mg", "mcg", "µg", "IU", "cc", "milligrams", "tablets"))
            assertEquals(u, SumResult.UNKNOWN_UNIT, Calculate.run(CalcAsk("500", u, "g")).result)
        assertEquals(SumResult.NO_DENSITY, Calculate.run(CalcAsk("2", "cups", "grams")).result)
    }

    @Test fun theCallReadsInEveryShape() {
        val ok = ToolCalls.parse(RawToolCall("calculate", mapOf("expression" to "(3*45)*1.13"))) as ParsedCall.Ok
        assertEquals(Action.Calculate(CalcAsk("(3*45)*1.13")), ok.action)
        assertEquals(Action.Calculate(CalcAsk("152", "miles", "km")), (ToolCalls.parse(RawToolCall("calculate", mapOf("expression" to 152, "from" to "miles", "to" to "km"))) as ParsedCall.Ok).action)
        assertTrue(ToolCalls.parse(RawToolCall("calculate", mapOf("from" to "km"))) is ParsedCall.Invalid)
        // Gemma's, JSON and Python-style, with parentheses and a percent sign.
        for (text in listOf("call:calculate{expression:<escape>(3*45)*1.13 + 13%<escape>}", "{\"name\": \"calculate\", \"arguments\": {\"expression\": \"(3*45)*1.13 + 13%\"}}",
            "```tool_code\ncalculate(expression=\"(3*45)*1.13 + 13%\")\n```")) {
            val calls = ToolCalls.wholeReplyCalls(text)
            assertEquals(text, 1, calls.size)
            assertEquals(text, "(3*45)*1.13 + 13%", calls.single().call.args["expression"])
        }
        // Its arguments never reach a log.
        assertFalse(ok.action.toString().contains("45") || ok.action.toString().contains("1.13"))
    }

    @Test fun describedForNumberWordsOnlyAndNeverForADose() {
        val spec = ToolSpecs.of(ToolName.CALCULATE)
        assertEquals(listOf("expression", "from", "to"), spec.params.map { it.name })
        assertTrue(ToolName.CALCULATE.internal && ToolName.CALCULATE.group == ToolGroup.SUMS)
        assertFalse(ToolName.CALCULATE in HelperTools.OFFERED)
        for (q in listOf("I have 3 kids, the trip is $45 each plus 13% HST, how much is that?", "what's 12 times 7", "what's 18% of 64.50", "convert 5 miles to km",
            "combien font 12 fois 7", "dix-huit pour cent de 80", "twelve times seven", "350 / 7"))
            assertTrue(q, ToolGroup.SUMS in ToolRouter.route(q))
        for (q in listOf("how much ibuprofen for a 30 kg child", "how many mg in 2 tablets of 200 mg", "take 2 pills times 3 a day", "500mg times 2",
            "tide times Tadoussac", "2026-10-14 17.46", "call 514-555-0101", "dentist on 10/11", "why is the sky blue", "set a timer for 5 minutes"))
            assertFalse(q, ToolGroup.SUMS in ToolRouter.route(q))
        assertTrue(ToolName.CALCULATE in ToolRouter.tools(setOf(ToolGroup.CORE, ToolGroup.SUMS), RobotSettings()))
        assertFalse(ToolName.CALCULATE in ToolRouter.tools(setOf(ToolGroup.CORE), RobotSettings()))
        assertFalse(ApprovalGate.lockedOut(ToolName.CALCULATE, locked = true, settings = RobotSettings()))
    }

    // ---------------------------------------------------------------- the turn

    private class Model(private val first: ModelOutcome) : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        var closed = 0
        override suspend fun ask() = first
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { answers += results; return ModelOutcome.Done(DoneReason.END) }
        override fun close() { closed++ }
    }

    private class Actions(override val locked: Boolean = false, val run: (Action) -> Executed) : TurnActions {
        val ran = mutableListOf<Action>()
        override val settings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed { ran += action; return run.invoke(action) }
    }

    private class View(override var text: String = "") : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val said = mutableListOf<String>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c }
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) { said += sentence }
    }

    private var ids = 0
    private val sum = RawToolCall("calculate", mapOf("expression" to "3*45*1.13"))
    private val textBob = RawToolCall("send_text", mapOf("to" to "Bob", "message" to "152.54"))

    /** As PanelSums.calculate makes it (the app's side). */
    private fun run(a: Action): Executed = if (a is Action.Calculate) Calculate.run(a.ask).text.let { Executed(ActionStatus.DONE, it, calc = it) } else Executed(ActionStatus.DONE, "done")

    @Test fun calculateEndsTheTurnWithCodesReply() = runBlocking {
        // The model wrote a guess before its call: the turn ends with code's reply, nothing else of it runs, nothing is said here
        // (the panel shows and says code's reply in place of the model's words).
        for (locked in listOf(false, true)) {
            val model = Model(ModelOutcome.Calls(listOf(sum, textBob)))
            val actions = Actions(locked = locked, run = ::run)
            val view = View("That's about 152.54 dollars.")
            val r = TurnRunner(model, actions, view, Confirmations(), untrusted = false) { "c${++ids}" }.run()
            assertNotNull(r.calc)
            assertEquals("3 × 45 × 1.13 = 152.55", r.calc!!.render(Langs.EN))
            assertNull(r.web)
            assertNull(r.file)
            assertEquals(listOf<Action>(Action.Calculate(CalcAsk("3*45*1.13"))), actions.ran)
            assertEquals(listOf(ActionStatus.DONE, ActionStatus.SKIPPED), view.cards.values.map { it.status })
            assertEquals("Not done: Robot answered with its sum first.", view.cards.values.last().detail)
            assertTrue(model.answers.isEmpty())
            assertEquals(1, model.closed)
            assertTrue(view.said.isEmpty())
            // Not content: a sum Robot worked out is Robot's.
            assertFalse(view.cards.values.first().content)
            assertFalse(run(Action.Calculate(CalcAsk("1+1"))).content)
        }
        // Text-call mode: a reply that is only the call, as text.
        val view = View("call:calculate{expression:<escape>3*45*1.13<escape>}")
        val model = Model(ModelOutcome.Done(DoneReason.END, textCalls = true))
        val r = TurnRunner(model, Actions(run = ::run), view, Confirmations(), untrusted = false) { "t${++ids}" }.run()
        assertEquals("3 × 45 × 1.13 = 152.55", r.calc!!.render(Langs.EN))
        assertEquals("", view.text)
    }
}
