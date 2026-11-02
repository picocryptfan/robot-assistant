// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.ConnCalls
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScriptStepCall
import app.heyrobot.core.StepArg
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.ValueSource
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxScriptInfo
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.core.termux.TermuxSource
import app.heyrobot.core.termux.TermuxStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Skills connections C8, end to end in the panel's path: a skill's typed script step (the carrier SkillRun makes) goes
 * through TurnRunner, TermuxTool.prepareTurn (TermuxPolicy.typed, ScriptStep.argv) and ApprovalGate, and what runs carries
 * the argument list itself, one element per value. Test Bench CN10's desk half: the card shows both values; after a web
 * page the same request needs a tap. Only what Android says about Termux is faked (TermuxTool.Facts), as TermuxGateTest does.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TypedScriptGateTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val signer = "A".repeat(64)

    private val backup = TermuxScriptInfo("backup", "Backs up", paramLines = listOf("param: folder choice photos|documents", "param: days int 1..30"),
        effectLines = listOf("effect: online"))

    private class Facts(var s: TermuxSettings, var scripts: List<TermuxScriptInfo>, var locked: Boolean = false, val signer: String) : TermuxTool.Facts {
        override fun settings(ctx: Context) = s
        override fun check(ctx: Context, s: TermuxSettings) = TermuxCheck(TermuxStatus.READY, TermuxSource.F_DROID, signer, "0.118.3")
        override fun granted(ctx: Context) = true
        override fun status(ctx: Context) = TermuxSetupStatus(testAt = 1, testOk = true, timeout = true, scripts = scripts)
        override fun locked(ctx: Context) = locked
    }

    private val ask = TermuxSettings(enabled = true, trustedSigner = signer, scripts = mapOf("backup" to ScriptLevel.ASK))
    private val facts = Facts(ask, listOf(backup), signer = signer)

    private inner class Actions : TurnActions {
        val ran = ArrayList<Action>()
        override val locked: Boolean get() = facts.locked
        override val settings = RobotSettings()
        override suspend fun prepare(action: Action): Prepared = Prepared.Ready(action)
        override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared =
            TermuxTool.prepareTurn(ctx, action, untrusted, remote, said = null) { prepare(it) }
        override suspend fun run(action: Action): Executed { ran += action; return Executed(ActionStatus.DONE, "Ran.") }
    }

    private class View : TurnView {
        val cards = LinkedHashMap<String, Card>()
        val pending = Channel<String>(Channel.UNLIMITED)
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) { cards[c.id] = c; if (c.status == ActionStatus.PENDING) pending.trySend(c.id) }
        override var text = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    /** A skill's step call, as SkillRun makes it (its own TurnModel: the app's call, never a model's). */
    private fun step(call: ScriptStepCall) = object : TurnModel {
        override val appCalls: Boolean get() = true
        override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(RawToolCall(ToolName.TERMUX_SCRIPT.id, mapOf(ConnCalls.KEY to call))))
        override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
        override fun close() {}
    }

    private fun v(value: Any, type: ParamType, source: ValueSource = ValueSource.YOURS) = StepArg.Value(SkillValue.of(value, type, source))

    private fun call(folder: StepArg = v("documents", ParamType.CHOICE), days: StepArg = v(7L, ParamType.INTEGER)) =
        ScriptStepCall("backup", mapOf("days" to days, "folder" to folder), "Back up", 1)

    private var ids = 0
    private fun runner(c: ScriptStepCall, actions: Actions, view: View, conf: Confirmations, untrusted: Boolean = false) =
        TurnRunner(step(c), actions, view, conf, untrusted, asked = setOf(ToolName.TERMUX_SCRIPT)) { "a${++ids}" }

    @Before fun setUp() { TermuxTool.facts = facts }
    @After fun tearDown() { TermuxTool.facts = TermuxTool.PhoneFacts }

    @Test fun theCardShowsBothValuesAndWhatRunsIsTheArgumentList() = runBlocking {
        val actions = Actions()
        val view = View()
        val conf = Confirmations()
        val job = async { runner(call(), actions, view, conf).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        val card = view.cards.getValue(id)
        // Your script set to Ask first: a card a spoken yes may answer, saying Robot can't see what the script does.
        assertNull(card.tapFor)
        assertEquals(ConnStrings.scriptUnseen().en(), card.detail)
        val info = TermuxTool.cardOf(card.action)!!
        assertEquals("~/robot/backup documents 7", info.command)
        assertTrue(actions.ran.isEmpty())
        conf.answer(id, true)
        job.await()
        val ran = actions.ran.single() as TermuxScriptAction
        // In the order of its `# param:` lines, whatever order the step gave them in; never a string to split.
        assertEquals(listOf("documents", "7"), ran.argv)
        assertEquals("", ran.args)
        assertTrue(ran.key.isNotBlank())
    }

    @Test fun afterAWebPageTheSameRequestNeedsATap() = runBlocking {
        val actions = Actions()
        val view = View()
        val conf = Confirmations()
        val job = async { runner(call(), actions, view, conf, untrusted = true).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(AskWhy.FROM_CONTENT, view.cards.getValue(id).tapFor)
        conf.answer(id, false)
        job.await()
        assertTrue(actions.ran.isEmpty())
    }

    @Test fun justDoItRunsWithoutACardUnlessTheModelWroteAValue() = runBlocking {
        facts.s = ask.copy(scripts = mapOf("backup" to ScriptLevel.AUTO))
        val actions = Actions()
        runner(call(), actions, View(), Confirmations()).run()
        assertEquals(listOf("documents", "7"), (actions.ran.single() as TermuxScriptAction).argv)
        // A text parameter the model filled: a tap, even set to Just do it.
        facts.scripts = listOf(backup.copy(paramLines = listOf("param: folder choice photos|documents", "param: days int 1..30", "param: note text")))
        val view = View()
        val conf = Confirmations()
        val later = Actions()
        val job = async { runner(ScriptStepCall("backup", call().args + ("note" to v("tidy up", ParamType.TEXT, ValueSource.MODEL)), "Back up", 1), later, view, conf).run() }
        val id = withTimeout(5_000) { view.pending.receive() }
        assertEquals(AskWhy.COMMAND, view.cards.getValue(id).tapFor)
        assertEquals(ConnStrings.scriptModelWords("run" to app.heyrobot.core.termux.TermuxReason.RUN).en(), view.cards.getValue(id).detail)
        conf.answer(id, true)
        job.await()
        assertEquals(listOf("documents", "7", "tidy up"), (later.ran.single() as TermuxScriptAction).argv)
    }

    @Test fun whatIsRefusedNeverRuns() = runBlocking {
        fun prepared(c: ScriptStepCall, untrusted: Boolean = false) = TermuxTool.prepare(ctx, TermuxScriptAction("backup", step = c), untrusted, remote = false, said = null)
        // A value outside the script's limits, or not one of its choices.
        val wide = prepared(call(days = v(31L, ParamType.INTEGER))) as Prepared.Refused
        assertEquals(ActionStatus.INVALID, wide.status)
        assertEquals("Robot won't run “backup”: a value doesn't fit what the script takes.", wide.detail)
        assertTrue(prepared(call(folder = StepArg.Fixed("music"))) is Prepared.Refused)
        // Something Robot read on the phone or from outside is never an argument.
        assertTrue(prepared(call(folder = v("documents", ParamType.CHOICE, ValueSource.ONLINE))) is Prepared.Refused)
        assertEquals(ActionStatus.INVALID, (prepared(call(days = StepArg.Missing)) as Prepared.Refused).status)
        // Locked: never, even for a script that works on the lock screen.
        facts.s = ask.copy(lockScreenScripts = setOf("backup"))
        facts.locked = true
        assertEquals(ActionStatus.NEEDS_UNLOCK, (prepared(call()) as Prepared.Refused).status)
        facts.locked = false
        // A `# param:` line Robot can't read: no typed script by that name.
        facts.scripts = listOf(backup.copy(paramLines = listOf("param: folder choice photos|documents", "param: days int 30..1")))
        assertEquals(ActionStatus.NOT_FOUND, (prepared(call()) as Prepared.Refused).status)
        facts.scripts = listOf(backup)
        facts.s = ask.copy(scripts = mapOf("backup" to ScriptLevel.OFF))
        assertEquals(ActionStatus.DISABLED, (prepared(call()) as Prepared.Refused).status)
    }
}
