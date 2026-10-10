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

    /**
     * The same for a whole conversation: a picture or screen text, or a read tool (a note, a calendar invite, a
     * contact) that brought content Robot didn't write into it. Every later action asks first.
     */
    @JvmName("untrustedTurns")
    fun untrusted(turns: List<ChatTurn>): Boolean = untrusted(turns.map { it.attachment }) || turns.any { t -> t.actions.any { it.content } }
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

/** One of the people or numbers a card offers: tapping it confirms [action], which goes to that one. */
data class Choice(val label: String, val action: Action)

/**
 * An action made ready for the gate (open_app resolved to the installed app it will open, send_text to the number
 * it will go to), or why not.
 */
sealed class Prepared {
    /**
     * [mustAsk]: show the card even where the action could just run (an app name that matched loosely). [choices]:
     * several people or numbers fit; the card lists them and the person taps one (so it always asks).
     */
    data class Ready(val action: Action, val mustAsk: Boolean = false, val choices: List<Choice> = emptyList()) : Prepared()
    data class Refused(val status: ActionStatus, val detail: String) : Prepared()
}

data class Executed(
    val status: ActionStatus,
    val detail: String,
    val launchedApp: Boolean = false,
    /** A read tool's findings for the model (lookup_contact, find_notes, read_calendar): content Robot didn't write. */
    val results: List<Map<String, Any?>>? = null,
    /** What the card shows under the summary afterwards (the events found, the numbers). */
    val body: String? = null,
    /** What text-call mode reads out (it has no follow-up answer); null: [detail]. */
    val spoken: String? = null,
    /** A key for the card's Undo (a note Robot just saved); null: nothing to undo. */
    val undo: String? = null,
) {
    /** Content entered the conversation: every later action in it asks first. */
    val content: Boolean get() = !results.isNullOrEmpty()
}

interface TurnActions {
    /** Read when deciding and again after a confirmation (the phone may have locked meanwhile). */
    val locked: Boolean
    val settings: RobotSettings
    fun prepare(action: Action): Prepared
    fun run(action: Action): Executed
}

enum class TurnPhase { THINKING, CONFIRMING }

/**
 * One action card in the reply. [action] is set for a card about a valid action (the panel shows an app's icon and
 * the action's button). [body]: the full message, email or note, or what a read tool found. [choices]: the people or
 * numbers to tap while it waits. [undo]: the key of the card's Undo. [content]: a read tool brought content in.
 */
data class Card(
    val id: String,
    val tool: ToolName?,
    val summary: String,
    val status: ActionStatus,
    val detail: String?,
    val action: Action? = null,
    val body: String? = null,
    val choices: List<Choice> = emptyList(),
    val undo: String? = null,
    val content: Boolean = false,
)

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
    private val pending = LinkedHashMap<String, CompletableDeferred<Int>>()

    /** Waits for "Do it" ([YES]), a choice (its index) or "Cancel" ([NO]). */
    suspend fun ask(id: String): Int {
        val d = CompletableDeferred<Int>()
        synchronized(pending) { pending[id] = d }
        try {
            return d.await()
        } finally {
            synchronized(pending) { pending.remove(id) }
        }
    }

    fun answer(id: String, yes: Boolean): Boolean = choose(id, if (yes) YES else NO)

    /** The person tapped the [index]th choice of the card [id]. */
    fun choose(id: String, index: Int): Boolean = synchronized(pending) { pending[id] }?.complete(index) ?: false

    fun declineAll() {
        synchronized(pending) { pending.values.toList() }.forEach { it.complete(NO) }
    }

    companion object {
        const val YES = 0
        const val NO = -1
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
    /**
     * The conversation carries screen text, a picture or a read tool's findings: every action needs a tap
     * ([TurnRules.untrusted]). Becomes true during the turn once a read tool returns content.
     */
    private var untrusted: Boolean,
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
        fun done(a: Action, status: ActionStatus, detail: String?, launched: Boolean = false, r: Executed? = null): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            view.card(Card(id, a.tool, a.describe(), status, detail, a, body = r?.body ?: a.body(), undo = r?.undo, content = r?.content == true))
            return (a.tool.id to status.toolResponse(detail, r?.results)) to launched
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
        val decision = ApprovalGate.decide(a.action.tool, actions.locked, actions.settings, untrusted || a.mustAsk || a.choices.isNotEmpty())
        val chosen = when (decision) {
            GateDecision.RUN -> a.action
            GateDecision.CONFIRM -> confirm(id, a.action, a.choices)
            GateDecision.NEEDS_UNLOCK -> return done(a.action, ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
            GateDecision.DISABLED -> return done(a.action, ActionStatus.DISABLED, "${a.action.tool.title} are turned off in Robot's settings.")
        } ?: return done(a.action, ActionStatus.DECLINED, null)
        // The phone may have locked while the card waited.
        if (actions.locked && chosen.tool !in ApprovalGate.ALLOWED_WHEN_LOCKED) {
            return done(chosen, ActionStatus.NEEDS_UNLOCK, "Unlock your phone, then ask again.")
        }
        val r = actions.run(chosen)
        // What a read tool found is content: from now on every action in this conversation asks first.
        if (r.content) untrusted = true
        val launched = r.launchedApp && r.status == ActionStatus.DONE
        if (speak && !launched) view.say(r.spoken ?: r.detail)
        return done(chosen, r.status, r.detail, launched, r)
    }

    /**
     * Shows the card and waits: the action ("Do it"), the person or number tapped among [choices], or null (Cancel). A
     * turn stopped meanwhile leaves the card declined, never waiting.
     */
    private suspend fun confirm(id: String, a: Action, choices: List<Choice>): Action? {
        view.card(Card(id, a.tool, a.describe(), ActionStatus.PENDING, null, a, body = a.body(), choices = choices))
        view.phase(TurnPhase.CONFIRMING)
        val pick = try {
            confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, a.tool, a.describe(), ActionStatus.DECLINED, null, a, body = a.body()))
            throw e
        }
        view.phase(TurnPhase.THINKING)
        return when {
            pick < 0 -> null
            choices.isEmpty() -> a
            else -> choices.getOrNull(pick)?.action
        }
    }
}
