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
    private class Actions(override val locked: Boolean, override val phoneLock: Boolean, private val emergency: Boolean) : TurnActions {
        val ran = mutableListOf<Action>()
        val prepared = mutableListOf<Action>()
        override val settings = RobotSettings()
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

    private fun turn(to: String, locked: Boolean, phoneLock: Boolean, emergency: Boolean): Triple<Actions, View, Calls> = runBlocking {
        var n = 0
        val actions = Actions(locked, phoneLock, emergency)
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
