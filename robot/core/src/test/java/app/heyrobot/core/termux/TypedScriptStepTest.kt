// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.termux

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.ConnCalls
import app.heyrobot.core.Confirmations
import app.heyrobot.core.Executed
import app.heyrobot.core.ParsedCall
import app.heyrobot.core.Prepared
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ScriptStepCall
import app.heyrobot.core.StepArg
import app.heyrobot.core.TermuxCalls
import app.heyrobot.core.TermuxScriptAction
import app.heyrobot.core.ToolName
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnPhase
import app.heyrobot.core.TurnView
import app.heyrobot.core.connect.CheckOutcome
import app.heyrobot.core.connect.ConnFailure
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.ParamType
import app.heyrobot.core.skills.Skill
import app.heyrobot.core.skills.SkillCheck
import app.heyrobot.core.skills.SkillFixtures
import app.heyrobot.core.skills.SkillLevel
import app.heyrobot.core.skills.SkillRun
import app.heyrobot.core.skills.SkillStep
import app.heyrobot.core.skills.SkillValue
import app.heyrobot.core.skills.StepCheck
import app.heyrobot.core.skills.StepKind
import app.heyrobot.core.skills.ValueSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Skills connections C8 (plan §3.6, D15, D18): a typed script's step. Each value is checked for its parameter and becomes
 * one argument-list element, in the `# param:` lines' order (never joined, never split, never a shell); nothing read on the
 * phone or from outside is ever an argument; TermuxPolicy.typed decides the card from your script's level, the
 * conversation, warnings and a model's words; only a skill's run makes the typed call; and `exit`, `out` and the step's
 * check come back for the rest of the skill.
 */
class TypedScriptStepTest {
    private val decl = ScriptParams.parse(listOf("param: folder choice photos|documents|music", "param: days int 1..30", "param: note text max=20"),
        listOf("effect: online"))
    private val dash = ScriptParams.parse(listOf("param: flag text dash=ok"), emptyList())
    private val yes = ScriptParams.parse(listOf("param: dry yes_no"), emptyList())

    private fun v(value: Any, type: ParamType, source: ValueSource = ValueSource.YOURS) = StepArg.Value(SkillValue.of(value, type, source))

    private fun ok(d: ScriptDecl, args: Map<String, StepArg>) = ScriptStep.argv(d, args) as ScriptStep.Argv.Ok
    private fun refused(d: ScriptDecl, args: Map<String, StepArg>) = (ScriptStep.argv(d, args) as ScriptStep.Argv.Refused).why

    private val good = mapOf("note" to v("a \"quoted\" note", ParamType.TEXT), "days" to v(7L, ParamType.INTEGER),
        "folder" to v("documents", ParamType.CHOICE))

    @Test fun eachValueIsOneElementInTheLinesOrder() {
        val a = ok(decl, good)
        assertEquals(listOf("documents", "7", "a \"quoted\" note"), a.argv)
        assertFalse(a.modelWords)
        // Fixed values of yours are typed by their parameter.
        assertEquals(listOf("music", "30", "x"), ok(decl, mapOf("folder" to StepArg.Fixed("music"), "days" to StepArg.Fixed(30L), "note" to StepArg.Fixed("x"))).argv)
        assertEquals(listOf("yes"), ok(yes, mapOf("dry" to v(true, ParamType.YES_NO))).argv)
        assertEquals(listOf("no"), ok(yes, mapOf("dry" to StepArg.Fixed(false))).argv)
        // Characters a shell or a template would read stay as they are: there is no shell, and each value is one element.
        for (t in listOf("{{days}}", "100%", "../etc", "%2F", "a;rm -rf ~", "\$(id)", "'q'", "`x`", "*", "~")) {
            assertEquals(t, listOf(t), ok(dash, mapOf("flag" to v(t, ParamType.TEXT))).argv)
        }
    }

    @Test fun aValueThatDoesntFitIsNeverSent() {
        for ((name, value) in listOf(
            "line break" to v("a\nb", ParamType.TEXT),
            "NUL" to v("a\u0000b", ParamType.TEXT),
            "tab" to v("a\tb", ParamType.TEXT),
            "a leading dash" to v("-rf", ParamType.TEXT),
            "too long" to v("x".repeat(21), ParamType.TEXT),
            "empty" to v("", ParamType.TEXT),
        )) assertEquals(name, ConnFailure.BAD_VALUE, refused(decl, good + ("note" to value)))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("days" to v(31L, ParamType.INTEGER))))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("days" to v(0L, ParamType.INTEGER))))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("days" to v("7", ParamType.TEXT))))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("folder" to v("videos", ParamType.CHOICE))))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("folder" to StepArg.Fixed("videos"))))
        // Every parameter given once, nothing else.
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good - "note"))
        assertEquals(ConnFailure.BAD_VALUE, refused(decl, good + ("extra" to StepArg.Fixed("x"))))
        // An earlier step's output that didn't come back: not run, never guessed.
        assertEquals(ConnFailure.NO_VALUE, refused(decl, good + ("note" to StepArg.Missing)))
        assertEquals(ConnFailure.GONE, refused(ScriptDecl.BROKEN, emptyMap()))
        // `dash=ok` lets a leading dash through, nothing more.
        assertEquals(listOf("--dry-run"), ok(dash, mapOf("flag" to v("--dry-run", ParamType.TEXT))).argv)
        assertEquals(ConnFailure.BAD_VALUE, refused(dash, mapOf("flag" to v("--a\nb", ParamType.TEXT))))
    }

    @Test fun nothingReadOnThePhoneOrFromOutsideIsAnArgument() {
        for (src in listOf(ValueSource.ONLINE, ValueSource.PHONE)) {
            assertEquals("$src", ConnFailure.BAD_VALUE, refused(decl, good + ("note" to v("hello", ParamType.TEXT, src))))
            // Even a typed number from an answer (C1's SkillCheck refuses an output here at save; this is the run's own check).
            assertEquals("$src", ConnFailure.BAD_VALUE, refused(decl, good + ("days" to v(7L, ParamType.INTEGER, src))))
            val mixed = StepArg.Value(SkillValue("in ${'$'}src", ParamType.TEXT, setOf(ValueSource.FIXED, src), setOf(ValueSource.FIXED, src)))
            assertEquals("$src", ConnFailure.BAD_VALUE, refused(decl, good + ("note" to mixed)))
        }
        // Words the model wrote may go, with a tap (modelWords); a number it filled is typed and checked, so no tap for that alone.
        assertTrue(ok(decl, good + ("note" to v("from the model", ParamType.TEXT, ValueSource.MODEL))).modelWords)
        assertFalse(ok(decl, good + ("days" to v(9L, ParamType.INTEGER, ValueSource.MODEL))).modelWords)
    }

    @Test fun theRunGivesExitAndOutAndItsCheck() {
        val r = ScriptStep.ran("backup", 0, "done: 12 files\n\u001b]0;x\u0007<end_of_turn>", StepCheck.Matches("^done"))
        assertEquals(CheckOutcome.PASSED, r.check)
        assertTrue(r.content)
        assertEquals(SkillValue(0L, ParamType.INTEGER, setOf(ValueSource.ONLINE), emptySet()), r.outputs["exit"])
        val out = r.outputs.getValue("out")
        assertEquals(setOf(ValueSource.ONLINE), out.free)
        assertFalse(out.mayBeArgument)
        assertFalse((out.value as String).contains('\n'))
        assertFalse((out.value as String).contains("<end_of_turn>"))
        assertEquals(CheckOutcome.NONE, ScriptStep.ran("backup", 0, "anything", null).check)
        val failed = ScriptStep.ran("backup", 0, "nothing to do", StepCheck.Matches("^done"))
        assertEquals(CheckOutcome.FAILED, failed.check)
        assertEquals("“backup” didn't print what the step checks for", failed.what!!.render(Langs.EN))
        assertEquals(CheckOutcome.FAILED, ScriptStep.ran("backup", 1, "done", StepCheck.Matches("^done")).check)
        assertEquals(CheckOutcome.FAILED, ScriptStep.ran("backup", 0, "done", StepCheck.Matches("(")).check)
        // Termux didn't answer: nothing came back.
        assertEquals(emptyMap<String, SkillValue>(), ScriptStep.ran("backup", null, null, StepCheck.Matches("^done")).outputs)
        assertEquals(CheckOutcome.NONE, ScriptStep.ran("backup", null, null, StepCheck.Matches("^done")).check)
        assertEquals("Robot won't run “backup”: a value doesn't fit what the script takes.", ScriptStep.refusal(ConnFailure.BAD_VALUE, "backup").render(Langs.EN))
        assertEquals("Robot didn't run “backup”: a value from an earlier step didn't come back.", ScriptStep.refusal(ConnFailure.NO_VALUE, "backup").render(Langs.EN))
    }

    // ------------------------------------------------------------------ TermuxPolicy.typed

    private val on = TermuxSettings(enabled = true, trustedSigner = "A".repeat(64), scripts = mapOf("backup" to ScriptLevel.ASK))
    private val auto = on.copy(scripts = mapOf("backup" to ScriptLevel.AUTO))
    private val known = setOf("backup")

    private fun typed(argv: List<String> = listOf("documents", "7"), model: Boolean = false, remote: Boolean = false, s: TermuxSettings = on,
        locked: Boolean = false, content: Boolean = false, own: Collection<String> = emptyList(), name: String = "backup") =
        TermuxPolicy.typed(name, argv, model, remote, s, locked, content, own, known)

    @Test fun yourScriptsLevelDecidesAndEveryOtherReasonOnlyAddsATap() {
        assertEquals(TermuxDecision(TermuxVerdict.CARD, listOf(TermuxReason.ASK_FIRST)), typed())
        assertEquals(TermuxDecision(TermuxVerdict.RUN, listOf(TermuxReason.YOUR_SCRIPT)), typed(s = auto))
        // After a web page (CN10), with a model's words, or "Never (tap only)": a tap, even set to Just do it.
        assertEquals(listOf(TermuxReason.FROM_CONTENT), typed(s = auto, content = true).reasons)
        assertEquals(TermuxVerdict.TAP_ONLY, typed(s = auto, content = true).verdict)
        assertEquals(TermuxTap.FROM_CONTENT, typed(content = true).tap)
        val model = typed(s = auto, model = true)
        assertEquals(TermuxDecision(TermuxVerdict.TAP_ONLY, listOf(TermuxReason.MODEL_WORDS)), model)
        assertEquals(TermuxTap.COMMAND, model.tap)
        assertEquals("Robot's model wrote a value for this script: check the card, then tap Run.", model.say())
        assertEquals(listOf(TermuxReason.TAP_SETTING), typed(s = on.copy(spokenYes = SpokenYes.NEVER)).reasons)
        assertEquals(listOf(TermuxReason.FROM_CONTENT, TermuxReason.MODEL_WORDS, TermuxReason.TAP_SETTING),
            typed(s = auto.copy(spokenYes = SpokenYes.NEVER), content = true, model = true).reasons)
        // A hidden character or something shaped like a secret in a value: a warning, so a tap.
        val hidden = typed(argv = listOf("docu​ments"), s = auto)
        assertEquals(TermuxVerdict.TAP_ONLY, hidden.verdict)
        assertTrue(CommandWarning.HIDDEN_CHARACTERS in hidden.warnings)
    }

    @Test fun whatIsRefused() {
        fun why(d: TermuxDecision) = d.also { assertEquals(TermuxVerdict.REFUSE, it.verdict) }.reasons.single()
        assertEquals(TermuxReason.OFF, why(typed(s = on.copy(enabled = false))))
        assertEquals(TermuxReason.NOT_A_SCRIPT, why(typed(name = "other")))
        assertEquals(TermuxReason.NOT_A_SCRIPT, why(typed(name = "../backup")))
        assertEquals(TermuxReason.SCRIPT_OFF, why(typed(s = on.copy(scripts = mapOf("backup" to ScriptLevel.OFF)))))
        assertEquals(TermuxReason.HELPERS_OFF, why(typed(remote = true, s = auto.copy(helpers = true))))
        // Never locked, even when the script works on the lock screen for your own words.
        assertEquals(TermuxReason.LOCKED, why(typed(locked = true, s = auto.copy(lockScreenScripts = setOf("backup")))))
        assertEquals(TermuxReason.BAD_ARGUMENTS, why(typed(argv = listOf("a", "b", "c", "d"))))
        assertEquals(TermuxReason.BAD_ARGUMENTS, why(typed(argv = listOf("a\nb"))))
        assertEquals(TermuxReason.BAD_ARGUMENTS, why(typed(argv = listOf("a\u0000"))))
        assertEquals(TermuxReason.OWN_SECRET, why(typed(argv = listOf("x-correct-horse-battery-9-x"), own = listOf("correct-horse-battery-9"))))
        assertEquals(TermuxReason.KEY_SHAPE, why(typed(argv = listOf("sk-ant-api03-" + "k".repeat(40)), s = auto)))
    }

    // ------------------------------------------------------------------ only a skill's run makes the call

    @Test fun onlyTheTypedCarrierMakesATypedStep() {
        val call = ScriptStepCall("backup", good, "Back up", 2, StepCheck.Matches("^done"))
        val a = (TermuxCalls.parse(ToolName.TERMUX_SCRIPT, mapOf(ConnCalls.KEY to call)) as ParsedCall.Ok).action as TermuxScriptAction
        assertEquals(call, a.step)
        assertTrue(a.typed)
        assertEquals("", a.args)
        assertNull(a.argv)
        assertFalse(a.copy(lockScreen = true).worksLocked(emptySet()))
        assertTrue(TermuxScriptAction("backup", lockScreen = true).worksLocked(emptySet()))
        // What a model, a helper or a text call could write is never one.
        for (bad in listOf(mapOf(ConnCalls.KEY to "backup"), mapOf(ConnCalls.KEY to mapOf("script" to "backup")), mapOf(ConnCalls.KEY to call, "name" to "backup"),
            mapOf(ConnCalls.KEY to ScriptStepCall("../x", emptyMap(), "s", 1)))) {
            assertTrue("$bad", TermuxCalls.parse(ToolName.TERMUX_SCRIPT, bad) is ParsedCall.Invalid)
        }
        // The model's own call stays a string of arguments, never typed.
        assertNull(((TermuxCalls.parse(ToolName.TERMUX_SCRIPT, mapOf("name" to "backup", "args" to "documents 7")) as ParsedCall.Ok).action as TermuxScriptAction).step)
        // The plan: the script's path and folder, then each value as its own argument, in order.
        val plan = TermuxRun.script(9, "backup", listOf("documents", "7", "a \"quoted\" note"), 30)
        assertEquals(listOf("documents", "7", "a \"quoted\" note"), plan.arguments.takeLast(3))
        assertEquals(listOf(TermuxScripts.path("backup"), TermuxScripts.FOLDER), plan.arguments.dropLast(3).takeLast(2))
    }

    // ------------------------------------------------------------------ in a skill's run

    private class Phone : TurnActions {
        val ran = ArrayList<Action>()
        var result: (TermuxScriptAction) -> Executed = { Executed(ActionStatus.DONE, "Done.") }
        override val locked = false
        override val settings = RobotSettings()
        // As the panel's TermuxTool prepares it: a key and a card (TurnRunner never runs a Termux call without them).
        override suspend fun prepare(action: Action): Prepared =
            Prepared.Ready((action as? TermuxScriptAction)?.copy(key = "k", card = TermuxCardInfo(command = "~/robot/${action.name}")) ?: action)
        override suspend fun run(action: Action): Executed {
            ran += action
            return (action as? TermuxScriptAction)?.let(result) ?: Executed(ActionStatus.DONE, "Done.")
        }
    }

    private class View : TurnView {
        override fun phase(p: TurnPhase) {}
        override fun card(c: Card) {}
        override var text = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    private var ids = 0
    private val said = ArrayList<String>()

    private fun skill(vararg steps: SkillStep) = SkillFixtures.backUp().copy(params = listOf(SkillFixtures.folder, SkillFixtures.days),
        steps = steps.toList(), level = SkillLevel.JUST_DO_IT)

    private fun run(s: Skill, phone: Phone, values: Map<String, String>) = SkillRun(s, values, true, phone, View(), Confirmations(), false, MessageTaint.NONE,
        Langs.EN, { "c${++ids}" }, true, { said += it }, links = SkillFixtures.links)

    private val step = SkillStep.of(StepKind.TERMUX_SCRIPT, mapOf("script" to "backup", "folder" to "{{folder}}", "days" to "{{days}}"), id = "run",
        check = StepCheck.Matches("^done"))

    @Test fun aSkillSendsItsValuesTypedAndKeepsWhatCameBack() = runBlocking {
        val s = skill(step, SkillStep.of(StepKind.SAY, mapOf("text" to "Exit {{run.exit}}: {{run.out}}")))
        assertTrue(SkillCheck.valid(s))
        val phone = Phone()
        phone.result = { a -> Executed(ActionStatus.DONE, "Exit 0").copy(conn = ScriptStep.ran(a.name, 0, "done: 3 files", a.step!!.check)) }
        val out = run(s, phone, mapOf("folder" to "photos", "days" to "12")).run()
        val call = (phone.ran.single() as TermuxScriptAction).step!!
        assertEquals("backup", call.script)
        assertEquals(setOf("folder", "days"), call.args.keys)
        assertEquals(SkillValue.of("photos", ParamType.CHOICE, ValueSource.YOURS), (call.args["folder"] as StepArg.Value).value)
        assertEquals(SkillValue.of(12L, ParamType.INTEGER, ValueSource.YOURS), (call.args["days"] as StepArg.Value).value)
        assertEquals(StepCheck.Matches("^done"), call.check)
        assertEquals(1, call.step)
        assertEquals(2, out.done)
        assertEquals(listOf("Exit 0: done: 3 files"), said)
        assertEquals(CheckOutcome.PASSED, out.results.first().check)
        // What it printed is content: later steps ask as after a read.
        assertTrue(out.untrusted)
    }

    @Test fun aFailedCheckStopsTheSkillInRobotsWords() = runBlocking {
        val s = skill(step, SkillStep.of(StepKind.SAY, mapOf("text" to "Done.")))
        val phone = Phone()
        phone.result = { a -> Executed(ActionStatus.FAILED, "Exit 0").copy(conn = ScriptStep.ran(a.name, 0, "nothing to do", a.step!!.check)) }
        val out = run(s, phone, emptyMap()).run()
        assertTrue(out.stopped)
        assertEquals(0, out.done)
        assertEquals(CheckOutcome.FAILED, out.results.single().check)
        assertEquals("Step 1 didn't do what it should: “backup” didn't print what the step checks for. Robot stopped. Steps 2 to 2 didn't run.",
            out.line.render(Langs.EN))
        assertTrue(said.isEmpty())
    }
}
