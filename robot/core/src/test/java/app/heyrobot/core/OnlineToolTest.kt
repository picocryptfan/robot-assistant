package app.heyrobot.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An online tool (the weather) ends on Stop; actions that write still run to the end (0.4.0 review). */
class OnlineToolTest {
    private class Model(private val calls: List<RawToolCall>) : TurnModel {
        var stoppedWith: List<Pair<String, Map<String, Any?>>>? = null
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(calls)
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
        override fun close() {}
        override fun stopped(results: List<Pair<String, Map<String, Any?>>>) { stoppedWith = results }
    }

    private class Actions(private val run: suspend (Action) -> Executed) : TurnActions {
        val started = CompletableDeferred<Action>()
        override val locked = false
        override val settings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun run(action: Action): Executed {
            started.complete(action)
            return run.invoke(action)
        }
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

    private var ids = 0

    @Test fun stopEndsAWeatherLookupAtOnce() = runBlocking {
        val actions = Actions { awaitCancellation() }
        val view = View()
        val model = Model(listOf(RawToolCall("weather", mapOf("place" to "Paris"))))
        val turn = async { TurnRunner(model, actions, view, Confirmations(), untrusted = false) { "c${++ids}" }.run() }
        withTimeout(5_000) { actions.started.await() }
        // A lookup stuck on the network: Stop still ends the turn right away.
        withTimeout(2_000) { turn.cancelAndJoin() }
        val card = view.cards.values.single()
        assertEquals(ActionStatus.DECLINED, card.status)
        assertEquals("Stopped.", card.detail)
        assertEquals(ToolName.WEATHER, card.tool)
    }

    @Test fun aNoteStillRunsToTheEndAfterStop() = runBlocking {
        val actions = Actions { delay(300); Executed(ActionStatus.DONE, "Saved to Robot Notes.") }
        val view = View()
        val model = Model(listOf(RawToolCall("add_note", mapOf("text" to "milk"))))
        val turn = async { TurnRunner(model, actions, view, Confirmations(), untrusted = false) { "c${++ids}" }.run() }
        withTimeout(5_000) { actions.started.await() }
        turn.cancelAndJoin()
        assertEquals(ActionStatus.DONE, view.cards.values.single().status)
    }

    @Test fun abortRunsItsHooksOnceAndKnowsItsDeadline() {
        var now = 1_000L
        val a = Abort(15_000) { now }
        assertEquals(15_000, a.remainingMs())
        var disconnects = 0
        val forget = a.onCancel { disconnects++ }
        val other = a.onCancel { disconnects += 10 }
        forget()
        now += 16_000
        assertTrue(a.expired)
        assertEquals(0, a.remainingMs())
        a.cancel()
        a.cancel()
        assertEquals(10, disconnects)
        assertTrue(a.cancelled)
        // A hook added after the cancel runs at once.
        a.onCancel { disconnects++ }
        assertEquals(11, disconnects)
        other()
        // No deadline: never expired.
        val none = Abort()
        assertFalse(none.expired)
        assertTrue(none.remainingMs() > 1_000_000_000)
    }
}
