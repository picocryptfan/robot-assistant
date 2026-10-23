// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.Confirmations
import app.heyrobot.core.DoneReason
import app.heyrobot.core.Executed
import app.heyrobot.core.LogCode
import app.heyrobot.core.ModelOutcome
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.StepCode
import app.heyrobot.core.TurnActions
import app.heyrobot.core.TurnModel
import app.heyrobot.core.TurnRunner
import app.heyrobot.core.TurnView
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
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

/** One step as its card ended. [why]: its card's line, in the reply language (why it didn't happen). */
data class StepResult(val index: Int, val kind: StepKind, val status: ActionStatus, val why: String?, val launched: Boolean, val content: Boolean) {
    val happened: Boolean get() = status.succeeded
}

/**
 * How a run (or one part of it, after a continuation) ended. [continueAt]: the step to go on with after the app that
 * opened (null: nothing waits). [untrusted]: the conversation's taint once the run is over. [line]: what Robot says.
 */
data class SkillOutcome(val results: List<StepResult>, val done: Int, val total: Int, val continueAt: Int?, val untrusted: Boolean, val line: Phrase,
    val noNotifications: Boolean = false) {
    val launched: Boolean get() = results.any { it.launched }
    val stopped: Boolean get() = results.any { !it.happened } || noNotifications
}

/** The voice log's codes for a skill run (SKILL_RUN's a). Stable numbers. */
enum class SkillRunCode(override val id: Int, override val title: String) : LogCode {
    DONE(1, "ran"), STOPPED(2, "stopped at a step"), CONTINUES(3, "waits for a tap after an app opened"), CARD_NO(4, "its card was cancelled"),
    OFF(5, "off"), LOCKED(6, "phone locked"), NO_NOTIFICATIONS(7, "stopped: notifications off"), EXPIRED(8, "continuation expired"),
    CONTINUED(9, "continued after a tap"),
}

/** The voice log's codes for a skill made or changed (SKILL_EDIT's a). Stable numbers. */
enum class SkillEditCode(override val id: Int, override val title: String) : LogCode {
    TAUGHT(1, "taught by voice"), SAVED_THAT(2, "saved from what Robot did"), NOT_SAVED(3, "not saved"), DELETED(4, "deleted"), KEPT(5, "kept"),
    CHANGED(6, "changed in Settings"), MADE(7, "made in Settings"),
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
 * spoken. [done]: steps that happened before this part (a continuation).
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
) {
    private var taint = SkillPolicy.untrusted(skill, untrusted)

    suspend fun run(from: Int = 0): SkillOutcome {
        val out = mutableListOf<StepResult>()
        val total = skill.steps.size
        for (i in from until total) {
            val step = skill.steps[i]
            val last = i == total - 1
            // D7: with nothing to tap afterwards, the run stops before an app opens and leaves steps behind.
            if (!last && !canWait && mayOpenApp(step.kind)) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.NO_NOTIFICATIONS.id, done + out.count { it.happened }, total)
                return SkillOutcome(out, done + out.count { it.happened }, total, null, taint, SkillStrings.noNotifications("n" to (i + 2).toString()),
                    noNotifications = true)
            }
            val r = step(i, step)
            out += r
            VoiceLog.add(VoiceEvent.SKILL_STEP, code(step.kind).id, if (r.happened) 1 else 0, if (r.launched) 1 else 0)
            val doneNow = done + out.count { it.happened }
            if (!r.happened) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.STOPPED.id, doneNow, total)
                return SkillOutcome(out, doneNow, total, null, taint, line(i, r, total))
            }
            if (r.launched && !last) {
                VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.CONTINUES.id, doneNow, total)
                return SkillOutcome(out, doneNow, total, i + 1, taint, SkillStrings.done("name" to skill.name, "done" to doneNow.toString(), "total" to total.toString()))
            }
        }
        val doneNow = done + out.count { it.happened }
        VoiceLog.add(VoiceEvent.SKILL_RUN, SkillRunCode.DONE.id, doneNow, total)
        return SkillOutcome(out, doneNow, total, null, taint, SkillStrings.done("name" to skill.name, "done" to doneNow.toString(), "total" to total.toString()))
    }

    /** Robot's line for a run that stopped at step [i] (from 0) with [r]. */
    private fun line(i: Int, r: StepResult, total: Int): Phrase {
        val why = (r.why?.trim()?.trimEnd('.', '!', '?')?.takeIf { it.isNotEmpty() } ?: r.status.text.render(langs))
        val args = arrayOf("name" to skill.name, "n" to (i + 1).toString(), "why" to why)
        return if (i < total - 1) SkillStrings.stopped(*args) else SkillStrings.stoppedLast(*args)
    }

    private suspend fun step(i: Int, step: SkillStep): StepResult {
        val tool = step.kind.tool
        if (tool == null) {
            // `say`: Robot's words of yours, shown and spoken; nothing changes on the phone.
            say((step.args["text"] as? String).orEmpty())
            return StepResult(i, step.kind, ActionStatus.DONE, null, launched = false, content = false)
        }
        var ran: Executed? = null
        var card: Card? = null
        val a = object : TurnActions by actions {
            override suspend fun run(action: Action): Executed = actions.run(action).also { ran = it }
        }
        val v = object : TurnView by view {
            override fun card(c: Card) {
                card = c
                view.card(c)
            }
        }
        val raw = RawToolCall(tool.id, skill.withValues(step, values))
        val result = TurnRunner(StepCall(raw), a, v, confirmations, taint, asked = SkillPolicy.asked(tool, named), messages = messages, langs = langs,
            newId = newId).run()
        val e = ran
        // D5: what a step brought in is content for every later step.
        if (e?.content == true) taint = true
        val status = e?.status ?: card?.status?.takeIf { it != ActionStatus.PENDING } ?: ActionStatus.FAILED
        return StepResult(i, step.kind, status, card?.detail ?: result.failure, launched = result.launchedApp || (e?.launchedApp == true && status.succeeded),
            content = e?.content == true)
    }

    companion object {
        /** A step that may open another app (a draft, the maps, an app, the calendar's editor): the panel leaves for it. */
        fun mayOpenApp(kind: StepKind): Boolean = kind in setOf(StepKind.OPEN_APP, StepKind.MAPS_SEARCH, StepKind.MAPS_NAVIGATE, StepKind.SEND_TEXT,
            StepKind.DRAFT_EMAIL, StepKind.ADD_EVENT)

        fun code(kind: StepKind): StepCode = kind.tool?.let { StepCode.of(it) } ?: StepCode.SAY
    }
}
