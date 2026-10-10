package app.heyrobot.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

// Pure (coroutines, no Android). One question and its answer, as the assistant panel runs it: the model's reply,
// the tool calls it asks for, the approval gate, the confirmation cards, the tool results going back. The panel
// (PanelController) supplies the ports: the model link, the actions and the view. Kept here so the approval flow,
// the limits and the "one turn at a time" rule are unit-tested.

object TurnRules {
    /** Actions in one answer at most; calls past it are shown as not done and the model is told why. */
    const val MAX_CALLS = 2
    /** Tool rounds (calls, results, the model again) in one answer at most. */
    const val MAX_ROUNDS = 3

    /**
     * Robot asks before any action in a conversation that carries screen text or a picture (this turn's or an
     * earlier one's: the live conversation still holds it). A page or a photo is content, never a command.
     */
    fun untrusted(attachments: List<Attachment>): Boolean = attachments.any { it != Attachment.NONE }
}

/** How a request to the model ended, as the turn sees it. */
sealed class ModelOutcome {
    /** [textCalls]: the conversation runs in text-call mode (no tool API on this TPU build); see [ToolCalls.wholeReplyCalls]. */
    data class Done(val reason: DoneReason, val textCalls: Boolean = false) : ModelOutcome()
    data class Calls(val calls: List<RawToolCall>) : ModelOutcome()
    data class Failed(val message: String) : ModelOutcome()
}

/** The model side of a turn. The reply's text reaches the view as it streams (the port's job). */
interface TurnModel {
    suspend fun ask(): ModelOutcome
    suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome
    /** Forget the live conversation; it is rebuilt from the history next time. */
    fun close()
}

/** An action made ready for the gate (open_app resolved to the installed app it will open), or why not. */
sealed class Prepared {
    /** [mustAsk]: show the card even where the action could just run (an app name that matched loosely). */
    data class Ready(val action: Action, val mustAsk: Boolean = false) : Prepared()
    data class Refused(val status: ActionStatus, val detail: String) : Prepared()
}

data class Executed(val status: ActionStatus, val detail: String, val launchedApp: Boolean = false)

interface TurnActions {
    /** Read when deciding and again after a confirmation (the phone may have locked meanwhile). */
    val locked: Boolean
    val settings: RobotSettings
    fun prepare(action: Action): Prepared
    fun run(action: Action): Executed
}

enum class TurnPhase { THINKING, CONFIRMING }

/** One action card in the reply. [action] is set for a card about a valid action (the panel shows an app's icon). */
data class Card(val id: String, val tool: ToolName?, val summary: String, val status: ActionStatus, val detail: String?, val action: Action? = null)

interface TurnView {
    fun phase(p: TurnPhase)
    /** Adds the card, or replaces the one with its id. */
    fun card(c: Card)
    /** The reply's text so far. */
    val text: String
    fun replaceText(text: String)
    /** The model's text after a tool round starts a new paragraph. */
    fun paragraphBreak()
    /** Reads out an action's outcome (text-call mode has no follow-up reply); only if replies are spoken. */
    fun say(sentence: String)
}

/** How the turn ended: [reason] when the model's reply ended (or was ended), [failure] when it failed. */
data class TurnResult(val reason: DoneReason?, val failure: String? = null, val launchedApp: Boolean = false)

/** The person's answers to confirmation cards. Thread-safe. */
class Confirmations {
    private val pending = LinkedHashMap<String, CompletableDeferred<Boolean>>()

    /** Waits for "Do it" (true) or "Cancel" (false). */
    suspend fun ask(id: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        synchronized(pending) { pending[id] = d }
        try {
            return d.await()
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    fun answer(id: String, yes: Boolean): Boolean = synchronized(pending) { pending[id] }?.complete(yes) ?: false

    fun declineAll() {
        synchronized(pending) { pending.values.toList() }.forEach { it.complete(false) }
    }

    val waiting: Boolean get() = synchronized(pending) { pending.isNotEmpty() }
}

/**
 * At most one turn at a time. [start] refuses while a turn runs or is still finishing (a confirmation card counts:
 * the turn is waiting for the person, not over); [stop] declines its cards and cancels it.
 */
class TurnSlot(private val scope: CoroutineScope) {
    val confirmations = Confirmations()
    private var job: Job? = null

    val running: Boolean get() = job?.isCompleted == false

    fun start(block: suspend () -> Unit): Boolean {
        if (running) return false
        job = scope.launch { block() }
        return true
    }

    fun stop() {
        confirmations.declineAll()
        job?.cancel()
    }

    suspend fun join() {
        job?.join()
    }
}

class TurnRunner(
    private val model: TurnModel,
    private val actions: TurnActions,
    private val view: TurnView,
    private val confirmations: Confirmations,
    /** The conversation carries screen text or a picture: every action needs a tap ([TurnRules.untrusted]). */
    private val untrusted: Boolean,
    private val newId: () -> String,
) {
    private var budget = TurnRules.MAX_CALLS

    private class Round(val responses: List<Pair<String, Map<String, Any?>>>, val launched: Boolean)

    suspend fun run(): TurnResult {
        var outcome = model.ask()
        var rounds = 0
        while (outcome is ModelOutcome.Calls && rounds < TurnRules.MAX_ROUNDS) {
            rounds++
            val round = runCalls(outcome.calls, speak = false)
            if (round.launched) {
                // Another app is opening: no follow-up reply.
                model.close()
                return TurnResult(DoneReason.END, launchedApp = true)
            }
            view.phase(TurnPhase.THINKING)
            view.paragraphBreak()
            outcome = model.answer(round.responses)
        }
        return when (outcome) {
            is ModelOutcome.Failed -> TurnResult(null, failure = outcome.message)
            is ModelOutcome.Calls -> {
                // Still asking for tools after the last round: end here.
                model.close()
                TurnResult(DoneReason.END)
            }
            is ModelOutcome.Done -> {
                // Only in text-call mode, and only a reply that is nothing but calls: prose that quotes a call never runs.
                val found = if (outcome.reason == DoneReason.END && outcome.textCalls) ToolCalls.wholeReplyCalls(view.text) else emptyList()
                if (found.isEmpty()) return TurnResult(outcome.reason)
                view.replaceText("")
                val round = runCalls(found.map { it.call }, speak = true)
                // No tool results in this mode: the conversation is rebuilt from the history next time.
                model.close()
                TurnResult(DoneReason.END, launchedApp = round.launched)
            }
        }
    }

    private suspend fun runCalls(calls: List<RawToolCall>, speak: Boolean): Round {
        val out = mutableListOf<Pair<String, Map<String, Any?>>>()
        for (raw in calls) {
            val parsed = ToolCalls.parse(raw)
            if (budget <= 0) {
                val why = "Robot does at most ${TurnRules.MAX_CALLS} actions per answer."
                val (tool, summary) = when (parsed) {
                    is ParsedCall.Ok -> parsed.action.tool to parsed.action.describe()
                    is ParsedCall.Invalid -> ToolName.of(parsed.tool) to "Couldn't do “${parsed.tool}”"
                }
                view.card(Card(newId(), tool, summary, ActionStatus.SKIPPED, why))
                out += raw.name to ActionStatus.SKIPPED.toolResponse(why)
                continue
            }
            budget--
            when (parsed) {
                is ParsedCall.Invalid -> {
                    view.card(Card(newId(), ToolName.of(parsed.tool), "Couldn't do “${parsed.tool}”", ActionStatus.INVALID, parsed.reason))
                    out += raw.name to ActionStatus.INVALID.toolResponse(parsed.reason)
                }
                is ParsedCall.Ok -> {
                    val (response, launched) = decideAndRun(parsed.action, speak)
                    out += response
                    // The panel is leaving for another app: nothing more is done in this answer.
                    if (launched) return Round(out, launched = true)
                }
            }
        }
        return Round(out, launched = false)
    }

    private suspend fun decideAndRun(asked: Action, speak: Boolean): Pair<Pair<String, Map<String, Any?>>, Boolean> {
        val id = newId()
        fun done(a: Action, status: ActionStatus, detail: String?, launched: Boolean = false): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            view.card(Card(id, a.tool, a.describe(), status, detail, a))
            return (a.tool.id to status.toolResponse(detail)) to launched
        }
        // The lock and the settings first: nothing about the phone (an installed app's name) is looked up for nothing.
        when (ApprovalGate.decide(asked.tool, actions.locked, actions.settings, untrusted)) {
            GateDecision.NEEDS_UNLOCK -> return done(asked, ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
            GateDecision.DISABLED -> return done(asked, ActionStatus.DISABLED, "${asked.tool.title} are turned off in Robot's settings.")
            else -> {}
        }
        val a = when (val p = actions.prepare(asked)) {
            is Prepared.Refused -> return done(asked, p.status, p.detail)
            is Prepared.Ready -> p
        }
        val decision = ApprovalGate.decide(a.action.tool, actions.locked, actions.settings, untrusted || a.mustAsk)
        val go = when (decision) {
            GateDecision.RUN -> true
            GateDecision.CONFIRM -> confirm(id, a.action)
            GateDecision.NEEDS_UNLOCK -> return done(a.action, ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
            GateDecision.DISABLED -> return done(a.action, ActionStatus.DISABLED, "${a.action.tool.title} are turned off in Robot's settings.")
        }
        if (!go) return done(a.action, ActionStatus.DECLINED, null)
        // The phone may have locked while the card waited.
        if (actions.locked && a.action.tool !in ApprovalGate.ALLOWED_WHEN_LOCKED) {
            return done(a.action, ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
        }
        val r = actions.run(a.action)
        val launched = r.launchedApp && r.status == ActionStatus.DONE
        if (speak && !launched) view.say(r.detail)
        return done(a.action, r.status, r.detail, launched)
    }

    /** Shows the card and waits; a turn stopped meanwhile leaves the card declined, never waiting. */
    private suspend fun confirm(id: String, a: Action): Boolean {
        view.card(Card(id, a.tool, a.describe(), ActionStatus.PENDING, null, a))
        view.phase(TurnPhase.CONFIRMING)
        val yes = try {
            confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, a.tool, a.describe(), ActionStatus.DECLINED, null, a))
            throw e
        }
        view.phase(TurnPhase.THINKING)
        return yes
    }
}
