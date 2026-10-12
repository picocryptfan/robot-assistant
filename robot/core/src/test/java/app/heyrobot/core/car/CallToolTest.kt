package app.heyrobot.core.car

import app.heyrobot.core.Action
import app.heyrobot.core.Approval
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.RoutineGate
import app.heyrobot.core.StepClass
import app.heyrobot.core.StepCode
import app.heyrobot.core.ToolCalls
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.ToolRouter
import app.heyrobot.core.output.SpokenLang
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.8's call tool and the call flow's emergency rule (design §4.5 flow 2, §4.9 rule 6). */
class CallToolTest {
    @Test fun theModelsCallParsesAndAsksFirst() {
        val ok = ToolCalls.parse(RawToolCall("call", mapOf("to" to "Mom"))) as ParsedCall.Ok
        assertEquals(Action.Call("Mom"), ok.action)
        assertEquals("Call **Mom**", ok.action.describe())
        assertTrue(ToolCalls.parse(RawToolCall("call", emptyMap())) !is ParsedCall.Ok)
        assertEquals(ToolGroup.PEOPLE, ToolName.CALL.group)
        assertEquals(Approval.ASK, RobotSettings().approval(ToolName.CALL))
        // Never described to the model (the prompt doesn't grow): "call Mom" is recognised before it. A routine never
        // phones anyone. Locked, it waits for the unlock (car mode's trusted session is CarGate's, on top).
        assertFalse(ToolName.CALL.forModel)
        assertTrue(ToolName.CALL !in ToolRouter.tools(ToolGroup.entries.toSet(), RobotSettings()))
        assertTrue(ToolName.entries.filter { it != ToolName.CALL }.all { it.forModel })
        assertEquals(app.heyrobot.core.output.HandsFreeCommand.Call("Mom"), app.heyrobot.core.output.HandsFreeRouter.route("call Mom", carMode = true))
        assertTrue(ToolName.CALL !in app.heyrobot.core.LockScreen.DEFAULT)
        assertEquals(app.heyrobot.core.GateDecision.NEEDS_UNLOCK, app.heyrobot.core.ApprovalGate.decide(ToolName.CALL, locked = true, settings = RobotSettings()))
        assertEquals(StepClass.NEVER, RoutineGate.classOf(ToolName.CALL))
        // 0.7.0 shipped remember and forget as 28 and 29: the call is 30.
        assertEquals(30, StepCode.of(ToolName.CALL).id)
    }

    @Test fun aResolvedCallSaysWhoAndWhichNumber() {
        val c = Action.Call("mom", "+15145550101", "Martine Martin", "Mobile")
        assertEquals("Call **Martine Martin** (Mobile, +15145550101)", c.describe())
        assertEquals("Open the dialer", c.confirmLabel)
        assertEquals("Call", c.copy(direct = true).confirmLabel)
        // An emergency number never says Call: the dialer opens and you tap Call there.
        assertEquals("Open the dialer", c.copy(direct = true, emergency = true).confirmLabel)
        assertTrue(c.copy(learned = true).describe().contains("seen in Cheogram"))
    }

    /**
     * Emergency numbers are tested with a number that isn't one: the classifier is a seam, so a test never dials or names
     * a real emergency number as if it called it. Whatever the classifier marks goes to the dialer, never a direct call,
     * locked or not, with the Phone permission and the switch on.
     */
    @Test fun aNumberTheClassifierMarksOnlyEverOpensTheDialer() {
        val fake = { n: String -> n == "5550199" }
        val rules = CallRules(CallSetting.CALL_AFTER_YES, hasCallPermission = true)
        assertEquals(CallWay.CALL, rules.way)
        for (locked in listOf(false, true)) for (trusted in listOf(false, true)) {
            val p = CallTarget.plan("555 0199", emptyList(), rules, locked, trusted, SpokenLang.EN, fake)
            assertTrue(p.toString(), p is CallPlan.Emergency)
            p as CallPlan.Emergency
            assertEquals("5550199", p.number)
            assertTrue(p.say.startsWith("I can't place emergency calls."))
            val (_, fx) = CallFlow.start(p, "555 0199", SpokenLang.EN)
            assertEquals(FlowEffect.Place("5550199", CallWay.DIAL), fx.first())
            assertFalse(fx.any { it is FlowEffect.Place && it.way == CallWay.CALL })
        }
        // A person whose number the classifier marks: the dialer too.
        val p = CallTarget.plan("Mom", listOf(PhoneCandidate("Mom", "555-0199")), rules, false, false, SpokenLang.FR, fake)
        assertTrue(p is CallPlan.Emergency)
        assertTrue((p as CallPlan.Emergency).say.startsWith("Je ne peux pas"))
        // Any other number keeps the read-back and the yes.
        assertTrue(CallTarget.plan("555 0100", emptyList(), rules, false, false, SpokenLang.EN, fake) is CallPlan.Confirm)
    }
}
