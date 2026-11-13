// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.ConnCalls
import app.heyrobot.core.ConnStepCall
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.HandOffCall
import app.heyrobot.core.LogCode
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.Prepared
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ScriptStepCall
import app.heyrobot.core.StepArg
import app.heyrobot.core.StepCode
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.connect.CheckOutcome
import app.heyrobot.core.connect.SkillCheckCode
import app.heyrobot.core.i18n.ConnStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.SkillStrings
import app.heyrobot.core.messages.MessageTaint

// Pure (coroutines). How a skill's steps run (plan §7.4 to §7.7, D5 to D7): one at a time, each through the same path as
// a typed command (a TurnRunner over the step's own call: the message quarantine, the approval gate with its level, the
// lock, content and messages, the tool's prepare, its card, the lock again after the card, then the action), so a skill
// never skips a card a step would show. Content one step brings in makes every later step ask as it would after a read.
// The run stops at the first step that didn't happen; when an app opens with steps left, the rest waits for a tap on a
// notification (the continuation). Robot's line comes from the real statuses, never from a model: a draft is "opened".
//
// Skills connections (plan §5.1, §5.5; C4): a step whose condition (`if`) doesn't hold is skipped and counts as done; a
// step's declared outputs are kept for later steps' templates and conditions (an output that didn't come back is unset:
// a condition sees it unset, a connection step needing it isn't sent); a failed check stops the run, and so does a step
// that didn't work, unless it reads and says `on_fail: go_on`. Every value goes to its step with where it came from
// (ValueSource): your words, the model's, fixed in the skill, or read from an answer; a connection's runner decides its
// card from them, and a step of Robot's own that uses a value from an answer always takes a tap (CONNECTION_TAP).

/**
 * One step as its card ended. [why]: its card's line, in the reply language (why it didn't happen). [check]: its check
 * (C4); [what]: a failed check's words. [wentOn]: it didn't work and the skill went on (`on_fail: go_on` after a read).
 */
data class StepResult(val index: Int, val kind: StepKind, val status: ActionStatus, val why: String?, val launched: Boolean, val content: Boolean,
    val check: CheckOutcome = CheckOutcome.NONE, val what: Phrase? = null, val wentOn: Boolean = false) {
    val happened: Boolean get() = status.succeeded
}

/**
 * How a run (or one part of it, after a continuation) ended. [continueAt]: the step to go on with after the app that
 * opened (null: nothing waits). [untrusted]: the conversation's taint once the run is over. [line]: what Robot says.
 */
data class SkillOutcome(val results: List<StepResult>, val done: Int, val total: Int, val continueAt: Int?, val untrusted: Boolean, val line: Phrase,
    val noNotifications: Boolean = false,
    /** C4: the outputs that came back, by step id: a continuation's later steps read them. */
    val outputs: Map<String, Map<String, SkillValue>> = emptyMap()) {
    val launched: Boolean get() = results.any { it.launched }
    val stopped: Boolean get() = results.any { !it.happened && !it.wentOn } || noNotifications
}

/** The voice log's codes for a skill run (SKILL_RUN's a). Stable numbers. */
enum class SkillRunCode(override val id: Int, override val title: String) : LogCode {
    DONE(1, "ran"), STOPPED(2, "stopped at a step"), CONTINUES(3, "waits for a tap after an app opened"), CARD_NO(4, "its card was cancelled"),
    OFF(5, "off"), LOCKED(6, "phone locked"), NO_NOTIFICATIONS(7, "stopped: notifications off"), EXPIRED(8, "continuation expired"),
    CONTINUED(9, "continued after a tap"),
    /** Connections plan §5.2: a step's connection, app or script is gone (SkillCheck.attention). */
    NEEDS_ATTENTION(10, "needs attention"),
}

/** The voice log's codes for a skill made or changed (SKILL_EDIT's a). Stable numbers. */
enum class SkillEditCode(override val id: Int, override val title: String) : LogCode {
    TAUGHT(1, "taught by voice"), SAVED_THAT(2, "saved from what Robot did"), NOT_SAVED(3, "not saved"), DELETED(4, "deleted"), KEPT(5, "kept"),
    CHANGED(6, "changed in Settings"), MADE(7, "made in Settings"), DRAFTED(8, "drafted by Robot's model"),
}

/** A step's call: the app's own, from your words naming the skill (never a model's). */
private class StepCall(private val call: RawToolCall) : TurnModel {
    override val appCalls: Boolean get() = true
    override suspend fun ask(): ModelOutcome = ModelOutcome.Calls(listOf(call))
    override suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome = ModelOutcome.Done(DoneReason.END)
    override fun close() {}
}

/**
 * Runs [skill] with [values] (its questions' values; defaults for the rest). [named]: your words named the skill (its
 * steps count as asked for). [untrusted]: the conversation's content, TurnRules.untrusted(chat) (D5). [canWait]: Robot's
 * notifications are on, so steps after an app opens can wait for a tap (D7). [say]: a `say` step's sentence, shown and
 * spoken. [done]: steps that happened before this part (a continuation). [sources]: where each question's value came from
 * (C4; a question your words gave is yours, one left at its default fixed, unless said here); [outputs]: what earlier steps
 * brought back (a continuation's); [links]: your connections (which of its steps only read).
 */
class SkillRun(
    private val skill: Skill,
    private val values: Map<String, String>,
    private val named: Boolean,
    private val actions: TurnActions,
    private val view: TurnView,
    private val confirmations: Confirmations,
    untrusted: Boolean,
    private val messages: MessageTaint,
    private val langs: Langs,
    private val newId: () -> String,
    private val canWait: Boolean,
    private val say: (String) -> Unit,
    private val done: Int = 0,
    private val sources: Map<String, ValueSource> = emptyMap(),
    outputs: Map<String, Map<String, SkillValue>> = emptyMap(),
    /** Your connections: whether a connection's step only reads (go on is honoured only after a read). */
    private val links: SkillLinks = SkillLinks.NONE,
    /** auto-open-plan §1: the apps (package names) this skill's save card let its open step open without asking. */
    private val opens: Set<String> = emptySet(),
    /** S3.2: how a routine's own steps run here (the brief, your reminders, the messages count); none: they fail. */
    private val routineSteps: RoutineSteps? = null,
) {
    private var taint = SkillPolicy.untrusted(skill, untrusted)

    /** The outputs that came back so far, by step id. */
    private val outputs = LinkedHashMap(outputs)

    /** Where question [name]'s value came from. */
    private fun source(name: String): ValueSource = sources[name] ?: if (values.containsKey(name)) ValueSource.YOURS else ValueSource.FIXED

    /** [r]'s value as a step takes it, with its sources; null when it isn't set (an output that didn't come back). */
    private fun valueOf(r: SkillRef): SkillValue? = if (r.output == null) skill.param(r.name)?.let { p -> SkillValue.of(p, values[p.name] ?: p.default, source(p.name)) }
        else outputs[r.name]?.get(r.output)

    /** [r]'s value, typed (a condition's, and 1.1's arguments): null when it isn't set. */
    private fun typed(r: SkillRef): Any? = if (r.output == null) skill.typed(r.name, values) else outputs[r.name]?.get(r.output)?.value

    private fun outcome(out: List<StepResult>, done: Int, total: Int, continueAt: Int?, line: Phrase, noNotifications: Boolean = false) =
        SkillOutcome(out, done, total, continueAt, taint, line, noNotifications, outputs.toMap())

    suspend fun run(from: Int = 0): SkillOutcome {
        val out = mutableListOf<StepResult>()
        val total = skill.steps.size
        for (i in from until total) {
            val step = skill.steps[i]
            val last = i == total - 1
            // Connections plan §5.1: a step whose condition doesn't hold is skipped and counts as done. Its value is a
            // question's (typed) or an earlier step's output (unset when it didn't come back).
            if (step.cond != null && !step.cond.holds(::typed)) {
                out += StepResult(i, step.kind, ActionStatus.DONE, null, launched = false, content = false)
                continue
            }
            // D7: with nothing to tap afterwards, the run stops before an app opens and leaves steps behind.
            if (!last && !canWait && mayOpenApp(step.kind)) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.NO_NOTIFICATIONS.id, done + out.count { it.happened }, total)
                return outcome(out, done + out.count { it.happened }, total, null, SkillStrings.noNotifications("n" to (i + 2).toString()),
                    noNotifications = true)
            }
            val r0 = step(i, step)
            // C4: a step that reads and says "go on" doesn't stop the skill when it doesn't work (never when you said no).
            val r = if (!r0.happened && goesOn(step, r0)) r0.copy(wentOn = true) else r0
            out += r
            VoiceLog.add(VoiceEvent.SKILL_STEP, code(step.kind).id, if (r.happened) 1 else 0, if (r.launched) 1 else 0)
            if (r.check != CheckOutcome.NONE) VoiceLog.add(VoiceEvent.SKILL_CHECK, (when {
                r.check == CheckOutcome.PASSED -> SkillCheckCode.PASSED
                r.wentOn -> SkillCheckCode.WENT_ON
                else -> SkillCheckCode.FAILED
            }).id, i + 1)
            val doneNow = done + out.count { it.happened }
            if (!r.happened && !r.wentOn) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.STOPPED.id, doneNow, total)
                return outcome(out, doneNow, total, null, line(i, r, total))
            }
            if (r.launched && !last) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.CONTINUES.id, doneNow, total)
                return outcome(out, doneNow, total, i + 1, SkillStrings.done("name" to skill.name, "done" to doneNow.toString(), "total" to total.toString()))
            }
        }
        val doneNow = done + out.count { it.happened }
        VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.DONE.id, doneNow, total)
        return outcome(out, doneNow, total, null, SkillStrings.done("name" to skill.name, "done" to doneNow.toString(), "total" to total.toString()))
    }

    /** `on_fail: go_on` (allowed only after a read, SkillCheck): a step that didn't work goes on; a no, a lock or Off never does. */
    private fun goesOn(step: SkillStep, r: StepResult): Boolean = step.onFail == OnFail.GO_ON && (r.status == ActionStatus.FAILED || r.status == ActionStatus.NOT_FOUND) &&
        // Checked again here, beside SkillCheck.attention: a step that changes something always stops the skill when it fails.
        (step.kind.reads || (step.kind == StepKind.CONN && step.conn != null && step.op != null && links.op(step.conn, step.op)?.read == true))

    /** Robot's line for a run that stopped at step [i] (from 0) with [r]. */
    private fun line(i: Int, r: StepResult, total: Int): Phrase {
        // C4, plan §3: a failed check says what the step should have done; Robot never says "done" for it.
        if (r.check == CheckOutcome.FAILED) {
            val what = r.what ?: ConnStrings.checkWhatAny()
            return if (i < total - 1) ConnStrings.checkFailed("n" to (i + 1).toString(), "what" to what, "next" to (i + 2).toString(), "last" to total.toString())
            else ConnStrings.checkFailedLast("n" to (i + 1).toString(), "what" to what)
        }
        val why = (r.why?.trim()?.trimEnd('.', '!', '?')?.takeIf { it.isNotEmpty() } ?: r.status.text.render(langs))
        val args = arrayOf("name" to skill.name, "n" to (i + 1).toString(), "why" to why)
        return if (i < total - 1) SkillStrings.stopped(*args) else SkillStrings.stoppedLast(*args)
    }

    private suspend fun step(i: Int, step: SkillStep): StepResult {
        if (step.kind == StepKind.SAY) {
            // `say`: Robot's words of yours (its templates filled, an earlier step's output among them), shown and spoken.
            say((skill.withValues(step, values, ::typed)["text"] as? String).orEmpty())
            return StepResult(i, step.kind, ActionStatus.DONE, null, launched = false, content = false)
        }
        if (step.kind.routineOnly) {
            // S3.2: a routine's own step runs by the routine's code (its gate and lock rules), never as a tool call. What it
            // said (your calendar, your reminders, who wrote) is content for every later step.
            val st = RoutineMigration.routineStep(skill, step)?.let { routineSteps?.run(it) } ?: ActionStatus.FAILED
            if (st.succeeded) taint = true
            return StepResult(i, step.kind, st, null, launched = false, content = st.succeeded)
        }
        val tool = step.kind.runsAs ?: return StepResult(i, step.kind, ActionStatus.FAILED, null, launched = false, content = false)
        var ran: Executed? = null
        var card: Card? = null
        // C4: a step of Robot's own that uses a value an earlier step read from an answer takes a tap (never a spoken yes).
        val readValue = step.kind.tool != null && step.refs.any { it.output != null }
        val a = object : TurnActions by actions {
            override suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared {
                val p = actions.prepare(action, untrusted, remote)
                return if (readValue && p is Prepared.Ready) p.copy(why = p.why + AskWhy.CONNECTION_TAP, noteText = p.noteText ?: ConnStrings.noteValue(),
                    note = p.note ?: ConnStrings.noteValue().en()) else p
            }
            override suspend fun run(action: Action): Executed = actions.run(action).also { ran = it }
        }
        val v = object : TurnView by view {
            override fun card(c: Card) {
                card = c
                view.card(c)
            }
        }
        val raw = call(i, step, tool)
        val result = TurnRunner(StepCall(raw), a, v, confirmations, taint, asked = SkillPolicy.asked(tool, named), messages = messages, langs = langs,
            opens = SkillPolicy.opens(skill, opens), ticksOnly = true, newId = newId).run()
        val e = ran
        // D5: what a step brought in is content for every later step.
        if (e?.content == true) taint = true
        // C4: its declared outputs, for later steps (an output that didn't come back stays unset).
        val conn = e?.conn
        if (e != null && conn != null && step.id != null && e.status.succeeded) outputs[step.id] = conn.outputs
        val status = e?.status ?: card?.status?.takeIf { it != ActionStatus.PENDING } ?: ActionStatus.FAILED
        return StepResult(i, step.kind, status, card?.detail ?: result.failure, launched = result.launchedApp || (e?.launchedApp == true && status.succeeded),
            content = e?.content == true, check = conn?.check ?: CheckOutcome.NONE, what = conn?.what)
    }

    /**
     * Step [i]'s call to [tool]: Robot's own with its arguments filled (1.1); a connection's, a hand-off's or a typed
     * script's as its typed carrier (ConnCalls.KEY), each argument with where its value came from.
     */
    private fun call(i: Int, step: SkillStep, tool: app.heyrobot.core.ToolName): RawToolCall = when (step.kind) {
        StepKind.CONN -> RawToolCall(tool.id, mapOf(ConnCalls.KEY to ConnStepCall(step.kind, step.conn, step.op, args(step.args), skill.name, i + 1,
            step.check as? StepCheck.Equals)))
        StepKind.TRAVEL_TIME -> RawToolCall(tool.id, mapOf(ConnCalls.KEY to ConnStepCall(step.kind, step.args["conn"] as? String, null,
            args(step.args - "conn"), skill.name, i + 1)))
        StepKind.APP -> RawToolCall(tool.id, mapOf(ConnCalls.KEY to HandOffCall(step.conn!!, args(step.args), skill.name, i + 1)))
        // C8: a typed script's values, each with its source, never joined into a string of arguments.
        StepKind.TERMUX_SCRIPT -> RawToolCall(tool.id, mapOf(ConnCalls.KEY to ScriptStepCall((step.args["script"] as? String).orEmpty(),
            args(step.args - "script"), skill.name, i + 1, step.check as? StepCheck.Matches)))
        else -> RawToolCall(tool.id, skill.withValues(step, values, ::typed))
    }

    /** A linked step's arguments: constants as written, templates as values with their sources, missing outputs as missing. */
    private fun args(a: Map<String, Any?>): Map<String, StepArg> = a.mapNotNull { (k, v) ->
        val whole = SkillTemplate.ref(v)
        k to when {
            whole != null -> valueOf(whole)?.let { StepArg.Value(it) } ?: StepArg.Missing
            SkillTemplate.looksLike(v) -> SkillValue.inText(v as String, ::valueOf)?.let { StepArg.Value(it) } ?: StepArg.Missing
            v == null -> return@mapNotNull null
            else -> StepArg.Fixed(v)
        }
    }.toMap()

    companion object {
        /** A step that may open another app (a draft, the maps, an app, the calendar's editor): the panel leaves for it. */
        fun mayOpenApp(kind: StepKind): Boolean = kind in setOf(StepKind.OPEN_APP, StepKind.MAPS_SEARCH, StepKind.MAPS_NAVIGATE, StepKind.SEND_TEXT,
            StepKind.DRAFT_EMAIL, StepKind.ADD_EVENT, StepKind.APP)

        fun code(kind: StepKind): StepCode = when (kind) {
            StepKind.CONN -> StepCode.CONNECTION
            StepKind.APP -> StepCode.HAND_OFF
            StepKind.TRAVEL_TIME -> StepCode.TRAVEL_TIME
            StepKind.TERMUX_SCRIPT -> StepCode.TYPED_SCRIPT
            else -> kind.tool?.let { StepCode.of(it) } ?: StepCode.SAY
        }
    }
}
