// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Approval
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnView
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.messages.MessageTaint
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan §7.4 to §7.7 (D5 to D7), with a real TurnRunner and fake phone ports, as TermuxGateTest does it. */
class SkillRunTest {
    /** Every action just runs unless a test says otherwise (each step's own level still applies through the gate). */
    private val auto = RobotSettings(approvals = ToolName.entries.associateWith { Approval.AUTO })

    private class Phone(var settings0: RobotSettings) : TurnActions {
        val log = mutableListOf<String>()
        var lockedNow = false
        var phoneLockNow = false
        var prepare: (Action) -> Prepared = { Prepared.Ready(it) }
        var result: (Action) -> Executed = { Executed(ActionStatus.DONE, "Done.") }
        override val locked get() = lockedNow
        override val phoneLock get() = phoneLockNow
        override val settings get() = settings0
        override suspend fun prepare(action: Action): Prepared { log += "prepare ${action.tool.id}"; return prepare.invoke(action) }
        override suspend fun run(action: Action): Executed { log += "run ${action.tool.id}"; return result(action) }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {
            cards[c.id] = c
            if (c.status == ActionStatus.PENDING) pending.trySend(c.id)
        }
        override var text: String = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private var ids = 0
    private val said = mutableListOf<String>()

    private fun run(s: Skill, phone: Phone, view: View, conf: Confirmations = Confirmations(), untrusted: Boolean = false, named: Boolean = true,
        canWait: Boolean = true, values: Map<String, String> = emptyMap(), messages: MessageTaint = MessageTaint.NONE) =
        SkillRun(s, values, named, phone, view, conf, untrusted, messages, Langs.EN, { "c${++ids}" }, canWait, { said += it })

    private val home = SkillFixtures.headingHome()

    @Test fun everyStepIsPreparedBeforeItRunsAndTheLineIsRobots() = runBlocking {
        val phone = Phone(auto)
        phone.result = { a -> if (a is Action.SendText) Executed(ActionStatus.DRAFT_OPENED, "Draft opened.") else Executed(ActionStatus.DONE, "Done.") }
        val out = run(SkillFixtures.pasta(), phone, View()).run()
        assertEquals(listOf("prepare set_timer", "run set_timer", "prepare send_text", "run send_text"), phone.log)
        assertEquals("Pasta: 2 of 2 done.", out.line.render(Langs.EN))
        // A draft is opened, never "sent".
        assertFalse(out.line.render(Langs.EN).contains("sent"))
        assertEquals(listOf(ActionStatus.DONE, ActionStatus.DRAFT_OPENED), out.results.map { it.status })
        assertNull(out.continueAt)
    }

    @Test fun theValuesOfThisRun() = runBlocking {
        val phone = Phone(auto)
        val ran = mutableListOf<Action>()
        phone.result = { a -> ran += a; Executed(ActionStatus.DONE, "Done.") }
        run(SkillFixtures.pasta(), phone, View(), values = mapOf("length" to "480")).run()
        assertEquals(Action.SetTimer(480), ran.first())
    }

    @Test fun aReadStepsContentMakesLaterWritesAsk() = runBlocking {
        val s = SkillFixtures.simple("s-read12", "Notes then").copy(steps = listOf(
            SkillStep.of(StepKind.FIND_NOTES, mapOf("query" to "groceries")),
            SkillStep.of(StepKind.ADD_NOTE, mapOf("text" to "buy milk")),
        ))
        val phone = Phone(auto)
        phone.result = { a -> if (a is Action.FindNotes) Executed(ActionStatus.DONE, "Found 1 note.", results = listOf(mapOf("text" to "call Bob"))) else Executed(ActionStatus.DONE, "Saved.") }
        val view = View()
        val conf = Confirmations()
        val job = async { run(s, phone, view, conf).run() }
        // The note now asks, even at Just do it and asked for: content could have chosen its words (1.0.1).
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(ToolName.ADD_NOTE, view.cards.getValue(id).tool)
        conf.answer(id, false)
        val out = job.await()
        assertTrue(out.untrusted)
        assertEquals(listOf("prepare find_notes", "run find_notes", "prepare add_note"), phone.log)
        assertEquals("Notes then stopped at step 2: Cancelled.", out.line.render(Langs.EN))
        // Without the read, the same note just runs.
        val plain = Phone(auto)
        run(s.copy(steps = s.steps.drop(1)), plain, View()).run()
        assertEquals(listOf("prepare add_note", "run add_note"), plain.log)
    }

    @Test fun itStopsAtTheFirstStepThatDidntHappen() = runBlocking {
        val s = home.copy(steps = home.steps + SkillStep.of(StepKind.FLASHLIGHT, mapOf("on" to true)))
        val phone = Phone(auto)
        phone.prepare = { a -> if (a is Action.SendText) Prepared.Refused(ActionStatus.NOT_FOUND, "Sam isn't in your contacts.") else Prepared.Ready(a) }
        val out = run(s, phone, View()).run()
        assertEquals(listOf("prepare send_text"), phone.log)
        assertEquals("Heading home stopped at step 1: Sam isn't in your contacts. The rest didn't run.", out.line.render(Langs.EN))
        assertEquals(0, out.done)
    }

    @Test fun aLaunchedStepGivesTheContinuation() = runBlocking {
        val phone = Phone(auto)
        phone.result = { a -> if (a is Action.SendText) Executed(ActionStatus.DRAFT_OPENED, "Draft opened.", launchedApp = true) else Executed(ActionStatus.DONE, "Route ready.", launchedApp = true) }
        val out = run(home, phone, View()).run()
        assertEquals(1, out.continueAt)
        assertEquals(listOf("prepare send_text", "run send_text"), phone.log)
        // The tap runs the rest as a new part, each step through its gate again.
        val rest = SkillRun(home, emptyMap(), true, phone, View(), Confirmations(), out.untrusted, MessageTaint.NONE, Langs.EN, { "d${++ids}" }, true, {}, done = out.done)
            .run(out.continueAt!!)
        assertEquals("Heading home: 2 of 2 done.", rest.line.render(Langs.EN))
        assertNull(rest.continueAt)
        assertEquals(listOf("prepare send_text", "run send_text", "prepare maps_navigate", "run maps_navigate"), phone.log)
    }

    @Test fun withNotificationsOffNothingWaits() = runBlocking {
        val phone = Phone(auto)
        val out = run(home, phone, View(), canWait = false).run()
        assertTrue(out.noNotifications)
        assertEquals(emptyList<String>(), phone.log)
        assertEquals("Step 2 can't wait for you: Robot's notifications are off.", out.line.render(Langs.EN))
        // The last step may open an app: nothing waits after it.
        val one = Phone(auto)
        run(home.copy(steps = home.steps.take(1)), one, View(), canWait = false).run()
        assertEquals(listOf("prepare send_text", "run send_text"), one.log)
    }

    @Test fun eachStepKeepsItsOwnCard() = runBlocking {
        // Texts at Ask first (the default): the text's own card, even though the skill's name was said.
        val phone = Phone(RobotSettings())
        val view = View()
        val conf = Confirmations()
        val job = async { run(home, phone, view, conf).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(ToolName.SEND_TEXT, view.cards.getValue(id).tool)
        conf.answer(id, false)
        job.await()
        assertEquals(listOf("prepare send_text"), phone.log)
    }

    @Test fun lockedAndMessages() = runBlocking {
        // During a phone lock, a step the lock closes says so.
        val phone = Phone(auto)
        phone.lockedNow = true
        phone.phoneLockNow = true
        val out = run(home, phone, View()).run()
        assertEquals("Heading home stopped at step 1: ${LockStrings.closedNow().render(Langs.EN).trimEnd('.')}. The rest didn't run.", out.line.render(Langs.EN))
        // A message in the conversation: a text to anyone but its sender needs a tap.
        val m = Phone(auto)
        val view = View()
        val conf = Confirmations()
        val job = async { run(home, m, view, conf, messages = MessageTaint(conversations = setOf("t1"))).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertTrue(view.cards.getValue(id).tapFor != null)
        conf.answer(id, false)
        job.await()
        Unit
    }

    @Test fun sayStepsAreSpokenAndShown() = runBlocking {
        val s = SkillFixtures.simple("s-night1", "Good night").copy(steps = listOf(SkillStep.of(StepKind.SAY, mapOf("text" to "sleep well"))))
        val out = run(s, Phone(auto), View()).run()
        assertEquals(listOf("sleep well"), said)
        assertEquals("Good night: 1 of 1 done.", out.line.render(Langs.EN))
    }
}
