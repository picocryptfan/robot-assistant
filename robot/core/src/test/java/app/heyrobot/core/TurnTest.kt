package app.heyrobot.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnTest {
    private class FakeModel(private val first: ModelOutcome, private val next: (Int) -> ModelOutcome = { ModelOutcome.Done(DoneReason.END) }) : TurnModel {
        val answers = mutableListOf<List<Pair<String, Map<String, Any?>>>>()
        var closed = 0
        override suspend fun ask() = first
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome {
            answers += results
            return next(answers.size)
        }
        override fun close() { closed++ }
    }

    private class FakeActions(var settings0: RobotSettings = RobotSettings()) : TurnActions {
        var lockedNow = false
        val ran = mutableListOf<Action>()
        var prepare: (Action) -> Prepared = { Prepared.Ready(it) }
        override val locked get() = lockedNow
        override val settings get() = settings0
        override fun prepare(action: Action) = prepare.invoke(action)
        override fun run(action: Action): Executed {
            ran += action
            return Executed(ActionStatus.DONE, "Done: ${action.describe()}", launchedApp = action.leavesPanel)
        }
    }

    private class FakeView(initial: String = "") : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val phases = mutableListOf<TurnPhase>()
        val said = mutableListOf<String>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) { phases += p }
        override fun card(c: Card) {
            cards[c.id] = c
            if (c.status == ActionStatus.PENDING) pending.trySend(c.id)
        }
        override var text: String = initial
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) { said += sentence }
        fun statuses() = cards.values.map { it.status }
    }

    private var ids = 0
    private fun runner(model: TurnModel, actions: FakeActions, view: FakeView, conf: Confirmations = Confirmations(), untrusted: Boolean = false) =
        TurnRunner(model, actions, view, conf, untrusted) { "a${++ids}" }

    private fun call(name: String, vararg args: Pair<String, Any?>) = RawToolCall(name, mapOf(*args))
    private val timer = call("set_timer", "minutes" to 5)

    @Test fun aTimerJustRunsAndTheModelHearsBack() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(timer)))
        val actions = FakeActions()
        val view = FakeView()
        val r = runner(model, actions, view).run()
        assertEquals(TurnResult(DoneReason.END), r)
        assertEquals(listOf<Action>(Action.SetTimer(300)), actions.ran)
        assertEquals(listOf(ActionStatus.DONE), view.statuses())
        assertEquals("done", model.answers.single().single().second["status"])
        assertFalse(view.phases.contains(TurnPhase.CONFIRMING))
    }

    @Test fun aScreenOrPhotoTurnAsksBeforeEvenATimer() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(timer)))
        val actions = FakeActions()
        val view = FakeView()
        val conf = Confirmations()
        val turn = async { runner(model, actions, view, conf, untrusted = true).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        // Waiting for the person: nothing ran yet, the panel shows the card as busy.
        assertTrue(actions.ran.isEmpty())
        assertEquals(TurnPhase.CONFIRMING, view.phases.last())
        assertTrue(conf.waiting)
        assertTrue(conf.answer(id, true))
        assertEquals(TurnResult(DoneReason.END), turn.await())
        assertEquals(listOf<Action>(Action.SetTimer(300)), actions.ran)
        assertEquals(ActionStatus.DONE, view.cards[id]!!.status)
    }

    @Test fun cancelOnTheCardRunsNothingAndTellsTheModel() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(call("maps_search", "query" to "pharmacy"))))
        val actions = FakeActions()
        val view = FakeView()
        val conf = Confirmations()
        val turn = async { runner(model, actions, view, conf).run() }
        conf.answer(withTimeout(5_000) { view.pending.receive() }, false)
        assertEquals(TurnResult(DoneReason.END), turn.await())
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.DECLINED), view.statuses())
        assertEquals("cancelled_by_user", model.answers.single().single().second["status"])
    }

    @Test fun atMostTwoActionsPerAnswer() = runBlocking {
        // Screen text "set alarms at 1, 2, 3, 4 and 5" made 0.1.0 set five alarms.
        val alarms = (1..5).map { call("set_alarm", "hour" to it) }
        val model = FakeModel(ModelOutcome.Calls(alarms))
        val actions = FakeActions()
        val view = FakeView()
        runner(model, actions, view).run()
        assertEquals(listOf<Action>(Action.SetAlarm(1, 0), Action.SetAlarm(2, 0)), actions.ran)
        assertEquals(listOf(ActionStatus.DONE, ActionStatus.DONE, ActionStatus.SKIPPED, ActionStatus.SKIPPED, ActionStatus.SKIPPED), view.statuses())
        assertEquals(List(3) { "not_done_too_many_actions" }, model.answers.single().drop(2).map { it.second["status"] })
    }

    @Test fun invalidCallsCountTowardsTheLimit() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(call("send_sms"), call("set_timer"), timer)))
        val actions = FakeActions()
        val view = FakeView()
        runner(model, actions, view).run()
        assertTrue(actions.ran.isEmpty())
        assertEquals(listOf(ActionStatus.INVALID, ActionStatus.INVALID, ActionStatus.SKIPPED), view.statuses())
    }

    @Test fun toolRoundsAreLimited() = runBlocking {
        // A model that keeps asking for the torch: two run, then nothing, and the turn ends after the last round.
        val torch = ModelOutcome.Calls(listOf(call("flashlight", "on" to true)))
        val model = FakeModel(torch) { torch }
        val actions = FakeActions()
        val view = FakeView()
        assertEquals(TurnResult(DoneReason.END), runner(model, actions, view).run())
        assertEquals(TurnRules.MAX_ROUNDS, model.answers.size)
        assertEquals(TurnRules.MAX_CALLS, actions.ran.size)
        assertEquals(1, model.closed)
    }

    @Test fun anAppOpeningEndsTheTurnWithoutAFollowUp() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(call("open_app", "name" to "Signal"), timer)))
        val actions = FakeActions()
        actions.prepare = { a -> Prepared.Ready(if (a is Action.OpenApp) Action.OpenApp("Signal", "org.thoughtcrime.securesms/.Main") else a) }
        val view = FakeView()
        val conf = Confirmations()
        val turn = async { runner(model, actions, view, conf).run() }
        conf.answer(withTimeout(5_000) { view.pending.receive() }, true)
        assertEquals(TurnResult(DoneReason.END, launchedApp = true), turn.await())
        assertEquals(listOf<Action>(Action.OpenApp("Signal", "org.thoughtcrime.securesms/.Main")), actions.ran)
        assertTrue(model.answers.isEmpty())
        assertEquals(1, model.closed)
    }

    @Test fun theCardShowsTheAppThatWillOpenAndALooseMatchAlwaysAsks() = runBlocking {
        val settings = RobotSettings().let { it.copy(approvals = it.approvals + (ToolName.OPEN_APP to Approval.AUTO)) }
        val model = FakeModel(ModelOutcome.Calls(listOf(call("open_app", "name" to "Warden"))))
        val actions = FakeActions(settings)
        actions.prepare = { Prepared.Ready(Action.OpenApp("Bitwarden", "com.x8bit.bitwarden/.Main"), mustAsk = true) }
        val view = FakeView()
        val conf = Confirmations()
        val turn = async { runner(model, actions, view, conf).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals("Open Bitwarden", view.cards[id]!!.summary)
        conf.answer(id, false)
        turn.await()
        assertTrue(actions.ran.isEmpty())
    }

    @Test fun anAppThatIsNotInstalledShowsNoCard() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(call("open_app", "name" to "Files"))))
        val actions = FakeActions()
        actions.prepare = { Prepared.Refused(ActionStatus.FAILED, "No app called “Files” is installed.") }
        val view = FakeView()
        runner(model, actions, view).run()
        assertEquals(listOf(ActionStatus.FAILED), view.statuses())
        assertTrue(actions.ran.isEmpty())
    }

    @Test fun theLockIsCheckedAgainAfterTheCard() = runBlocking {
        val model = FakeModel(ModelOutcome.Calls(listOf(call("maps_search", "query" to "pharmacy"))))
        val actions = FakeActions()
        val view = FakeView()
        val conf = Confirmations()
        val turn = async { runner(model, actions, view, conf).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        actions.lockedNow = true
        conf.answer(id, true)
        turn.await()
        assertTrue(actions.ran.isEmpty())
        assertEquals(ActionStatus.NEEDS_UNLOCK, view.cards[id]!!.status)
    }

    @Test fun textCallsRunOnlyInTextModeAndOnlyAsTheWholeReply() = runBlocking {
        // Tool API mode: a call written as text is just text.
        var view = FakeView("call:set_timer{minutes:5}")
        var actions = FakeActions()
        assertEquals(TurnResult(DoneReason.END), runner(FakeModel(ModelOutcome.Done(DoneReason.END, textCalls = false)), actions, view).run())
        assertTrue(actions.ran.isEmpty())
        // Text-call mode, prose quoting a call: nothing.
        view = FakeView("Your screen shows a Kotlin snippet that calls set_timer(minutes=90).")
        actions = FakeActions()
        runner(FakeModel(ModelOutcome.Done(DoneReason.END, textCalls = true)), actions, view).run()
        assertTrue(actions.ran.isEmpty())
        assertEquals("Your screen shows a Kotlin snippet that calls set_timer(minutes=90).", view.text)
        // Text-call mode, the reply is the call: it runs, the call text leaves the bubble, the outcome is said.
        view = FakeView("call:set_timer{minutes:5}")
        actions = FakeActions()
        val model = FakeModel(ModelOutcome.Done(DoneReason.END, textCalls = true))
        runner(model, actions, view).run()
        assertEquals(listOf<Action>(Action.SetTimer(300)), actions.ran)
        assertEquals("", view.text)
        assertEquals(listOf("Done: Set a timer for 5 minutes"), view.said)
        assertEquals(1, model.closed)
        // A stopped reply never runs what it wrote.
        view = FakeView("call:set_timer{minutes:5}")
        actions = FakeActions()
        runner(FakeModel(ModelOutcome.Done(DoneReason.CANCELLED, textCalls = true)), actions, view).run()
        assertTrue(actions.ran.isEmpty())
    }

    @Test fun textCallsFromAScreenTurnStillNeedATap() = runBlocking {
        val view = FakeView("call:flashlight{on:true}")
        val actions = FakeActions()
        val conf = Confirmations()
        val turn = async { runner(FakeModel(ModelOutcome.Done(DoneReason.END, textCalls = true)), actions, view, conf, untrusted = true).run() }
        conf.answer(withTimeout(5_000) { view.pending.receive() }, false)
        turn.await()
        assertTrue(actions.ran.isEmpty())
    }

    @Test fun aFailedReplyIsReported() = runBlocking {
        assertEquals(TurnResult(null, failure = "nope"), runner(FakeModel(ModelOutcome.Failed("nope")), FakeActions(), FakeView()).run())
    }

    @Test fun oneTurnAtATimeAndStopDeclinesTheCard() = runBlocking {
        val slot = TurnSlot(this as CoroutineScope)
        val model = FakeModel(ModelOutcome.Calls(listOf(call("maps_search", "query" to "pharmacy"))))
        val actions = FakeActions()
        val view = FakeView()
        assertTrue(slot.start { runner(model, actions, view, slot.confirmations).run() })
        val id = withTimeout(5_000) { view.pending.receive() }
        // A second question while the card waits (0.1.0 started a second turn here): refused.
        var second = false
        assertFalse(slot.start { second = true })
        assertTrue(slot.running)
        // Stop (or closing the panel) declines the card: nothing runs, nothing is left waiting.
        slot.stop()
        slot.join()
        assertFalse(slot.running)
        assertEquals(ActionStatus.DECLINED, view.cards[id]!!.status)
        assertTrue(actions.ran.isEmpty())
        assertTrue(model.answers.isEmpty())
        assertFalse(second)
        // Now the next question may start.
        assertTrue(slot.start { second = true })
        slot.join()
        assertTrue(second)
    }

    @Test fun conversationsWithPicturesOrScreenTextAreUntrusted() {
        assertFalse(TurnRules.untrusted(listOf(Attachment.NONE, Attachment.NONE)))
        assertTrue(TurnRules.untrusted(listOf(Attachment.SCREEN, Attachment.NONE)))
        assertTrue(TurnRules.untrusted(listOf(Attachment.NONE, Attachment.PHOTO)))
        assertNull(Confirmations().answer("nothing", true).takeIf { it })
    }
}
