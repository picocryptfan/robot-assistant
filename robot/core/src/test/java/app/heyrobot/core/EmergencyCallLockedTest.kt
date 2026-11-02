// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.1.1 (a Pixel 8, 23 Oct 2026: "call 911" typed during a phone lock said "Unlock to do this"): a call to an emergency
 * number goes past the lock to prepare and runs, which opens the emergency dialer (CallPlacer); a call to anyone else is
 * still refused on a locked phone, and an emergency number prepare doesn't confirm is refused too. Nothing here dials:
 * run is a fake.
 */
class EmergencyCallLockedTest {
    private class Calls(private val to: String) : TurnModel {
        var answered = false
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(RawToolCall("call", mapOf("to" to to))))
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome { answered = true; return ModelOutcome.Done(DoneReason.END) }
        override fun close() {}
        override val appCalls: Boolean get() = true
    }

    /** [emergency]: what prepare's check of Android's list says about the number. */
    private class Actions(override val locked: Boolean, override val phoneLock: Boolean, private val emergency: Boolean,
        override val settings: RobotSettings = RobotSettings(), private val region: String? = null) : TurnActions {
        val ran = mutableListOf<Action>()
        val prepared = mutableListOf<Action>()
        override suspend fun emergencyNumber(to: String) = app.heyrobot.core.car.EmergencyNumbers.basic(to, region)
        override suspend fun prepare(action: Action): Prepared {
            prepared += action
            val c = action as Action.Call
            return Prepared.Ready(c.copy(number = c.to, emergency = emergency))
        }
        override suspend fun run(action: Action): Executed { ran += action; return Executed(ActionStatus.DONE, "Dialer open", launchedApp = true) }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c }
        override var text = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private fun turn(to: String, locked: Boolean, phoneLock: Boolean, emergency: Boolean, settings: RobotSettings = RobotSettings(),
        region: String? = null): Triple<Actions, View, Calls> = runBlocking {
        var n = 0
        val actions = Actions(locked, phoneLock, emergency, settings, region)
        val view = View()
        val model = Calls(to)
        TurnRunner(model, actions, view, Confirmations(), untrusted = false, asked = setOf(ToolName.CALL)) { "c${++n}" }.run()
        Triple(actions, view, model)
    }

    @Test fun anEmergencyNumberRunsDuringAPhoneLockAndOnTheLockScreen() {
        for ((phoneLock, label) in listOf(true to "phone lock", false to "lock screen")) {
            for (number in listOf("911", "112")) {
                val (actions, view, _) = turn(number, locked = true, phoneLock = phoneLock, emergency = true)
                assertEquals("$label $number", 1, actions.ran.size)
                val call = actions.ran.single() as Action.Call
                assertTrue("$label $number", call.emergency)
                assertEquals("$label $number", ActionStatus.DONE, view.cards.values.single().status)
            }
        }
    }

    // review-lock 1: only 112 and 911 got past the lock, wherever the phone was.
    @Test fun theNumbersOfWhereThePhoneIsRunLocked() {
        for ((region, numbers) in listOf("GB" to listOf("999"), "AU" to listOf("000"), "NZ" to listOf("111"), "DE" to listOf("110"), "FR" to listOf("15", "17", "18"))) {
            for (number in numbers) {
                val (actions, view, _) = turn(number, locked = true, phoneLock = true, emergency = true, region = region)
                assertEquals("$region $number", 1, actions.ran.size)
                assertTrue("$region $number", (actions.ran.single() as Action.Call).emergency)
                assertEquals("$region $number", ActionStatus.DONE, view.cards.values.single().status)
            }
        }
        // Somewhere else 999 is an ordinary number, and it never reaches prepare.
        val (a, v, _) = turn("999", locked = true, phoneLock = true, emergency = false, region = "CA")
        assertTrue(a.prepared.isEmpty())
        assertEquals(ActionStatus.NEEDS_UNLOCK, v.cards.values.single().status)
    }

    // review-lock 2: Call set to Off refused 911, locked or not.
    @Test fun callSetToOffStillOpensTheDialerForAnEmergencyNumber() {
        val off = RobotSettings(approvals = mapOf(ToolName.CALL to Approval.OFF))
        for (locked in listOf(false, true)) for ((number, region) in listOf("911" to null, "999" to "GB")) {
            val (actions, view, _) = turn(number, locked = locked, phoneLock = locked, emergency = true, settings = off, region = region)
            assertEquals("$locked $number", 1, actions.ran.size)
            assertTrue("$locked $number", (actions.ran.single() as Action.Call).emergency)
            assertEquals("$locked $number", ActionStatus.DONE, view.cards.values.single().status)
        }
    }

    @Test fun callSetToOffRefusesEveryoneElse() {
        val off = RobotSettings(approvals = mapOf(ToolName.CALL to Approval.OFF))
        val (a1, v1, _) = turn("5550100", locked = false, phoneLock = false, emergency = false, settings = off)
        assertTrue(a1.prepared.isEmpty())
        assertEquals(ActionStatus.DISABLED, v1.cards.values.single().status)
        // Android's list says no after all: refused after prepare, and nothing opens.
        val (a2, v2, _) = turn("911", locked = false, phoneLock = false, emergency = false, settings = off)
        assertTrue(a2.ran.isEmpty())
        assertEquals(ActionStatus.DISABLED, v2.cards.values.single().status)
        // A name holding an emergency number is a name.
        val (a3, v3, _) = turn("Room 911", locked = true, phoneLock = true, emergency = false, settings = off)
        assertTrue(a3.prepared.isEmpty())
        assertEquals(ActionStatus.DISABLED, v3.cards.values.single().status)
    }

    @Test fun anyoneElseIsStillRefusedLocked() {
        val (actions, view, _) = turn("Mom", locked = true, phoneLock = true, emergency = false)
        assertTrue(actions.prepared.isEmpty())
        assertTrue(actions.ran.isEmpty())
        assertEquals(ActionStatus.NEEDS_UNLOCK, view.cards.values.single().status)
        // A number prepare doesn't mark as an emergency (Android's list says no) is refused after prepare.
        val (a2, v2, _) = turn("911", locked = true, phoneLock = true, emergency = false)
        assertTrue(a2.ran.isEmpty())
        assertEquals(ActionStatus.NEEDS_UNLOCK, v2.cards.values.single().status)
        // An ordinary number on a locked phone never reaches prepare.
        val (a3, _, _) = turn("5550100", locked = true, phoneLock = false, emergency = false)
        assertTrue(a3.prepared.isEmpty())
    }
}
