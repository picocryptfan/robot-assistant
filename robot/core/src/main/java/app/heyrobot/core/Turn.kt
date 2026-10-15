// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.messages.ActionOrigin
import app.heyrobot.core.messages.MessageQuarantine
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.messages.QuarantineVerdict
import app.heyrobot.core.web.WebOutcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
     * A helper's tool rounds in one answer at most ("plan my week and add these to my calendar"): each call behind a card,
     * and still at most [MAX_CALLS] calls in the answer. Server-side web search continuations (pause_turn) are not rounds.
     */
    const val HELPER_MAX_ROUNDS = 3

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
    fun untrusted(turns: List<ChatTurn>): Boolean =
        untrusted(turns.map { it.attachment }) || turns.any { t -> t.actions.any { it.content } } ||
            // A helper's answers (and one read off the Claude app's screen) are content Robot didn't write either; so are
            // the messages Robot read you (0.7), and what was said while the phone was locked (1.0, ChatTurn.locked).
            turns.any { it.fromHelper || it.readBack || it.messages || it.locked }
}

/** How a request to the model ended, as the turn sees it. */
sealed class ModelOutcome {
    /** [textCalls]: the conversation runs in text-call mode (the model's build refused the tool API); see [ToolCalls.wholeReplyCalls]. */
    data class Done(val reason: DoneReason, val textCalls: Boolean = false) : ModelOutcome()
    data class Calls(val calls: List<RawToolCall>) : ModelOutcome()
    data class Failed(val message: String) : ModelOutcome()
}

/** Robot's call to ask a helper (ask_claude): the turn ends there and EscalationPolicy decides what happens. */
private fun RawToolCall.asksHelper(): Boolean = ToolName.of(name) == ToolName.ASK_HELPER

/** The model side of a turn. The reply's text reaches the view as it streams (the port's job). */
interface TurnModel {
    suspend fun ask(): ModelOutcome
    suspend fun answer(results: List<Pair<String, Map<String, Any?>>>): ModelOutcome
    /** Forget the live conversation; it is rebuilt from the history next time. */
    fun close()

    /**
     * The turn ends with the last round's calls answered only in part: another app opened. [results]: the calls that ran
     * (in call order, the one that opened the app last); the rest never will.
     */
    fun abandon(results: List<Pair<String, Map<String, Any?>>>) = close()

    /** The turn was stopped during a round: [results] of the calls that ran before the stop. Nothing by default. */
    fun stopped(results: List<Pair<String, Map<String, Any?>>>) {}

    /**
     * 0.8: the calls are the app's own, made from your words before any model (a phrase the panel recognised, a routine's
     * step), so they may name a tool that is never described to the model ([ToolName.forModel] false: the call tool).
     * False for a model: its call to such a tool is refused as unknown, whatever its words or the screen's asked for.
     */
    val appCalls: Boolean get() = false
}

/**
 * One of the people or numbers a card offers: tapping it confirms [action], which goes to that one. [remember]: the
 * same action that also remembers the choice, taken instead when "Always use this for …" is ticked on the card.
 */
data class Choice(val label: String, val action: Action, val remember: Action? = null)

/**
 * An action made ready for the gate (open_app resolved to the installed app it will open, send_text to the number
 * it will go to), or why not.
 */
sealed class Prepared {
    /**
     * [why]: why the card may show even where the action could just run (an app name that matched loosely, a person not
     * surely the one named, a name learned from Cheogram, a file to delete); the gate asks the settings whether it does.
     * [choices]: several people or numbers fit; the card lists them and the person taps one. When the settings let it
     * run without a card, the first choice (the best) is taken. [more]: how many more fitted than the card lists.
     * [note]: the card's line while it waits (what its buttons do). [remember]: the words of the tick box that makes a
     * choice remembered ("Always use this for “mom”"), when the choices can be. [allowContacts]: the card also offers to
     * allow reading the contacts (the person asked for may be there).
     */
    data class Ready(val action: Action, val why: Set<AskWhy> = emptySet(), val choices: List<Choice> = emptyList(), val more: Int = 0,
        val note: String? = null, val remember: String? = null, val allowContacts: Boolean = false) : Prepared() {
        /** Some reason to show the card besides the level (the settings decide whether it does). */
        val mustAsk: Boolean get() = why.isNotEmpty()

        /** [why] with a list to choose from that names no reason of its own counted as a pick. */
        val reasons: Set<AskWhy> get() = if (choices.isNotEmpty() && why.isEmpty()) setOf(AskWhy.PICK) else why
    }
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
    /** What the model is told instead of [detail] (a fixed code, LookupFallback); null: [detail]. */
    val forModel: String? = null,
    /**
     * An app the card offers to open (its package; launch intent only), e.g. Cheogram when no contact matched; or a page
     * of Android's settings ([SettingsPage], 0.4.0), e.g. storage after phone_status.
     */
    val handOff: String? = null,
    /**
     * 0.9, web_search: what the search found (or why it failed). The model's turn ends with it: the panel answers from it
     * in a tool-free one-shot (or says why it failed in fixed words), and the main conversation never holds web text.
     */
    val web: WebOutcome? = null,
) {
    /** Content entered the conversation: every later action in it asks first (web results too: content, not personal). */
    val content: Boolean get() = !results.isNullOrEmpty() || web is WebOutcome.Found
}

interface TurnActions {
    /** Read when deciding and again after a confirmation (the phone may have locked meanwhile). */
    val locked: Boolean
    val settings: RobotSettings
    /** May read the contacts or the calendar: suspends, so the panel does that off its main thread. */
    suspend fun prepare(action: Action): Prepared
    /**
     * 0.9: the same, knowing whether the conversation holds content Robot didn't write ([untrusted]) and a helper asked
     * ([remote]): a Termux command's card depends on both (TermuxPolicy's rule 3).
     */
    suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared = prepare(action)
    suspend fun run(action: Action): Executed
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
    /** More people or numbers fitted than [choices] lists ("and 3 more"). */
    val more: Int = 0,
    /** A helper asked for it: what a read tool finds goes to the helper only after this card's tap. */
    val remote: Boolean = false,
    /** The buttons' own words when not an action's ("Send" and "Cancel", "Include" and "Start fresh"). */
    val confirm: String? = null,
    val cancel: String? = null,
    /** An app the card offers to open ([Executed.handOff]). */
    val handOff: String? = null,
    /** The tick box next to [choices] that remembers the one tapped ("Always use this for “mom”"); null: none. */
    val remember: String? = null,
    /** While it waits, the card also offers to allow reading the contacts ([Prepared.Ready.allowContacts]). */
    val allowContacts: Boolean = false,
    /**
     * Only a tap answers it, never a spoken or typed yes ([CardAnswer.tapFor]): a name learned from a Cheogram notification
     * (whoever sent it chose it), not being sure who or which, or going online the first time; who or what it really is
     * must be seen on the card. A no still declines it. Null: a yes may answer it.
     */
    val tapFor: AskWhy? = null,
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

/** No event for [gapMs] since the start or the last event: the other side has stopped answering (pure). */
class StallTimer(private val gapMs: Long, start: Long) {
    @Volatile private var last = start

    fun event(now: Long) { last = now }

    fun stalled(now: Long): Boolean = now - last >= gapMs
}

/**
 * How the turn ended: [reason] when the model's reply ended (or was ended), [failure] when it failed. [escalate]: the
 * local model called ask_claude; nothing else of that reply ran, and the panel brings a helper in (or offers to).
 */
data class TurnResult(val reason: DoneReason?, val failure: String? = null, val launchedApp: Boolean = false, val escalate: Boolean = false,
    /** 0.9: the model called web_search and it ran: its turn ended there, and the panel answers from this. */
    val web: WebOutcome? = null)

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

    /** The person tapped the [index]th choice of the card [id]; [remember]: with "Always use this for …" ticked. */
    fun choose(id: String, index: Int, remember: Boolean = false): Boolean =
        synchronized(pending) { pending[id] }?.complete(if (remember && index >= 0) index + REMEMBER else index) ?: false

    fun declineAll() {
        synchronized(pending) { pending.values.toList() }.forEach { it.complete(NO) }
    }

    companion object {
        const val YES = 0
        const val NO = -1
        /** Added to a choice's index when it is to be remembered. */
        const val REMEMBER = 1 shl 16
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
    /**
     * A helper is the model (the API route): every call shows a card (unless Settings › Safety & privacy says otherwise),
     * only [HelperTools.OFFERED] exist, and each input is checked against its schema before it is read. A helper never
     * calls ask_claude.
     */
    private val remote: Boolean = false,
    private val maxRounds: Int = if (remote) TurnRules.HELPER_MAX_ROUNDS else TurnRules.MAX_ROUNDS,
    /**
     * The actions the person asked for in their own words this turn ([ToolRouter.asked], or a fast path's): under "Safety
     * cards: When you didn't ask for it" content doesn't make these ask. Never screen text, a photo or a tool's results.
     */
    private val asked: Set<ToolName> = emptySet(),
    /** 0.6.0: the helper asking is trusted with personal content: what a read finds goes to it without "Share with …". */
    private val trusted: Boolean = false,
    /**
     * 0.6.0, [remote]: the tools this helper was given (none when its actions are off or its model can't use them). A
     * helper's reply is untrusted (a server can answer with any call at all), so a call to anything else is refused.
     */
    private val offered: Set<ToolName> = HelperTools.OFFERED.toSet(),
    /**
     * 0.7: the messages other people wrote that are in this conversation (core messages MessageQuarantine): every action
     * that writes or leaves the phone asks, and one that sends something to anyone but their sender needs a tap.
     */
    private val messages: MessageTaint = MessageTaint.NONE,
    /**
     * 0.7: whose words the calls are. The model's ([ActionOrigin.MODEL], the default), or a call the app made from your own
     * words ([ActionOrigin.USER_WORDS]: "remember that …" in a conversation that holds a message). With a message in the
     * conversation, a memory the model proposes is never kept, and yours is a card a tap answers (MessageQuarantine).
     */
    private val origin: ActionOrigin = ActionOrigin.MODEL,
    private val newId: () -> String,
) {
    private var budget = TurnRules.MAX_CALLS

    /** [spoken]: what the round's actions say (their cards' text), for when no reply can follow them. */
    private class Round(val responses: List<Pair<String, Map<String, Any?>>>, val launched: Boolean, val spoken: List<String> = emptyList())

    /** The round under way's spoken texts ([Round.spoken]). */
    private val spokenThisRound = mutableListOf<String>()

    /** The round under way's results so far: a stop meanwhile tells the model what ran ([TurnModel.stopped]). */
    private var inRound: MutableList<Pair<String, Map<String, Any?>>>? = null

    /** 0.9: a web search ran in this turn (core web WebOutcome): nothing else of the reply runs, and the turn ends. */
    private var web: WebOutcome? = null

    suspend fun run(): TurnResult {
        var outcome = model.ask()
        var rounds = 0
        while (outcome is ModelOutcome.Calls && rounds < maxRounds) {
            // The local model asks a helper: its turn ends here, nothing else it asked for runs.
            if (!remote && outcome.calls.any { it.asksHelper() }) return escalate()
            rounds++
            val round = try {
                runCalls(outcome.calls, speak = false)
            } catch (e: CancellationException) {
                model.stopped(inRound.orEmpty().toList())
                throw e
            }
            if (round.launched) {
                // Another app is opening: no follow-up reply. What ran (the launch included) is the model's to know.
                model.abandon(round.responses)
                return TurnResult(DoneReason.END, launchedApp = true)
            }
            // A web search ran: like ask_claude, the model's turn ends at the call, and the panel answers from what it found.
            web?.let {
                model.close()
                return TurnResult(DoneReason.END, web = it)
            }
            view.phase(TurnPhase.THINKING)
            view.paragraphBreak()
            val before = view.text.length
            outcome = model.answer(round.responses)
            // No room left for a reply after the results (CONTEXT_FULL): say what the cards say rather than nothing.
            if (outcome is ModelOutcome.Done && outcome.reason == DoneReason.CONTEXT_FULL && view.text.length <= before) round.spoken.forEach(view::say)
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
                if (found.any { it.call.asksHelper() }) return escalate()
                val round = runCalls(found.map { it.call }, speak = true)
                // No tool results in this mode: the conversation is rebuilt from the history next time.
                model.close()
                TurnResult(DoneReason.END, launchedApp = round.launched, web = web)
            }
        }
    }

    /**
     * The app route: the model's one call is the hand-off (ask_claude, Action.AskHelper), shown on its card
     * ("Open the ChatGPT app with: «…»") and run only after the tap; then Robot steps aside. No model reply follows.
     */
    suspend fun runHandOff(): TurnResult {
        val calls = (model.ask() as? ModelOutcome.Calls)?.calls.orEmpty()
        val round = runCalls(calls, speak = false)
        model.close()
        return TurnResult(DoneReason.END, launchedApp = round.launched)
    }

    private fun escalate(): TurnResult {
        model.close()
        return TurnResult(DoneReason.END, escalate = true)
    }

    /** A helper's call checked first: one of the tools offered to it, its input fitting the schema; then the usual reading. */
    private fun parse(raw: RawToolCall): ParsedCall {
        // A tool the model is never told about stays out of its reach (0.8's call tool: "call Mom" is the panel's, not the model's).
        if (!model.appCalls && ToolName.of(raw.name)?.forModel == false) return ParsedCall.Invalid(raw.name.take(40), "unknown tool")
        if (!remote) return ToolCalls.parse(raw)
        val tool = ToolName.of(raw.name)?.takeIf { it in HelperTools.OFFERED } ?: return ParsedCall.Invalid(raw.name.take(40), "unknown tool")
        if (tool !in offered) return ParsedCall.Invalid(tool.id, "this tool wasn't offered")
        HelperTools.check(ToolSpecs.of(tool), raw.args)?.let { return ParsedCall.Invalid(tool.id, it) }
        return ToolCalls.parse(raw)
    }

    private suspend fun runCalls(calls: List<RawToolCall>, speak: Boolean): Round {
        val out = mutableListOf<Pair<String, Map<String, Any?>>>()
        inRound = out
        spokenThisRound.clear()
        for (raw in calls) {
            val parsed = parse(raw)
            // After a web search, nothing else of this reply runs: the answer comes from the search (design A6.2).
            if (web != null) {
                val why = "Robot answers from the web search first."
                val (tool, summary) = when (parsed) {
                    is ParsedCall.Ok -> parsed.action.tool to parsed.action.describe()
                    is ParsedCall.Invalid -> ToolName.of(parsed.tool) to "Couldn't do “${parsed.tool}”"
                }
                view.card(Card(newId(), tool, summary, ActionStatus.SKIPPED, why, remote = remote))
                out += raw.name to ActionStatus.SKIPPED.toolResponse(why)
                continue
            }
            if (budget <= 0) {
                val why = "Robot does at most ${TurnRules.MAX_CALLS} actions per answer."
                val (tool, summary) = when (parsed) {
                    is ParsedCall.Ok -> parsed.action.tool to parsed.action.describe()
                    is ParsedCall.Invalid -> ToolName.of(parsed.tool) to "Couldn't do “${parsed.tool}”"
                }
                view.card(Card(newId(), tool, summary, ActionStatus.SKIPPED, why, remote = remote))
                out += raw.name to ActionStatus.SKIPPED.toolResponse(why)
                continue
            }
            budget--
            when (parsed) {
                is ParsedCall.Invalid -> {
                    view.card(Card(newId(), ToolName.of(parsed.tool), "Couldn't do “${parsed.tool}”", ActionStatus.INVALID, parsed.reason, remote = remote))
                    out += raw.name to ActionStatus.INVALID.toolResponse(parsed.reason)
                }
                is ParsedCall.Ok -> {
                    val (response, launched) = decideAndRun(parsed.action, speak)
                    out += response
                    // The panel is leaving for another app: nothing more is done in this answer.
                    if (launched) return Round(out, launched = true, spokenThisRound.toList())
                }
            }
        }
        return Round(out, launched = false, spokenThisRound.toList())
    }

    private suspend fun decideAndRun(asked: Action, speak: Boolean): Pair<Pair<String, Map<String, Any?>>, Boolean> {
        val id = newId()
        fun done(a: Action, status: ActionStatus, detail: String?, launched: Boolean = false, r: Executed? = null): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            view.card(Card(id, a.tool, a.describe(), status, detail, a, body = r?.body ?: a.body(), undo = r?.undo, content = r?.content == true, remote = remote,
                handOff = r?.handOff))
            return (a.tool.id to status.toolResponse(r?.forModel ?: detail, r?.results)) to launched
        }
        // 0.7: what a message's quarantine forbids outright (a memory the model proposes once a message is in the
        // conversation) gets no card and runs nothing.
        if (MessageQuarantine.verdict(asked.tool, origin, messages) == QuarantineVerdict.FORBIDDEN) {
            if (messages.active) VoiceLog.add(VoiceEvent.MSG_GUARD, app.heyrobot.core.messages.GuardCode.REFUSED)
            return done(asked, ActionStatus.SKIPPED, MessageQuarantine.forbidden(asked.tool))
        }
        // The lock and the settings first: nothing about the phone (an installed app's name) is looked up for nothing.
        when (ApprovalGate.decide(asked.tool, actions.locked, actions.settings, content = untrusted, asked = asked.tool in this.asked, remote = remote,
            online = asked.online, trusted = trusted, messages = messages.active, saidLocked = messages.saidLocked)) {
            GateDecision.NEEDS_UNLOCK -> return done(asked, ActionStatus.NEEDS_UNLOCK, ApprovalGate.unlockLine(asked.tool))
            GateDecision.DISABLED -> return done(asked, ActionStatus.DISABLED, "${asked.tool.title} are turned off in Robot's settings.")
            else -> {}
        }
        val a = when (val p = actions.prepare(asked, untrusted, remote)) {
            is Prepared.Refused -> return done(asked, p.status, p.detail)
            is Prepared.Ready -> p
        }
        // 1.0 (injection-termux-policy-unwired): a Termux call TermuxPolicy didn't prepare (no card, no key) never runs, and
        // never runs without its card: fail closed rather than trust the gate's Termux branch with no reasons.
        if (a.action.tool.termux && !TermuxCalls.prepared(a.action)) return done(a.action, ActionStatus.FAILED, TermuxCalls.UNPREPARED)
        // A message in the conversation: a tap for anything that leaves for someone else (the card says why).
        val fromMessage = MessageQuarantine.why(a.action, messages, origin)
        val reasons = a.reasons + fromMessage
        val decision = ApprovalGate.decide(a.action.tool, actions.locked, actions.settings, reasons, content = untrusted,
            asked = a.action.tool in this.asked, remote = remote, online = a.action.online, trusted = trusted, messages = messages.active,
            saidLocked = messages.saidLocked)
        if (messages.active && decision == GateDecision.CONFIRM) VoiceLog.add(VoiceEvent.MSG_GUARD,
            if (fromMessage.isEmpty()) app.heyrobot.core.messages.GuardCode.ASKED else app.heyrobot.core.messages.GuardCode.TAP_REQUIRED)
        val chosen = when (decision) {
            // No card for these choices (the settings say so): the first, the best match.
            GateDecision.RUN -> a.choices.firstOrNull()?.action ?: a.action
            GateDecision.CONFIRM -> confirm(id, a.action, a.choices, a.more, if (fromMessage.isNotEmpty()) MessageQuarantine.CARD_LINE else a.note,
                a.remember, a.allowContacts, tapFor = CardAnswer.tapFor(reasons))
            GateDecision.NEEDS_UNLOCK -> return done(a.action, ActionStatus.NEEDS_UNLOCK, ApprovalGate.unlockLine(a.action.tool))
            GateDecision.DISABLED -> return done(a.action, ActionStatus.DISABLED, "${a.action.tool.title} are turned off in Robot's settings.")
        } ?: return done(a.action, ActionStatus.DECLINED, null)
        // The phone may have locked while the card waited.
        if (ApprovalGate.lockedOut(chosen, actions.locked, actions.settings)) {
            return done(chosen, ActionStatus.NEEDS_UNLOCK, ApprovalGate.unlockLine(chosen.tool))
        }
        fun finish(r: Executed): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            // What a read tool found is content: from now on every action in this conversation asks first.
            if (r.content) untrusted = true
            if (r.web != null && r.status != ActionStatus.DECLINED) web = r.web
            val launched = r.launchedApp && r.status.succeeded
            if (speak && !launched && r.web == null) view.say(r.spoken ?: r.detail)
            if (!launched) spokenThisRound += r.spoken ?: r.detail
            return done(chosen, r.status, r.detail, launched, r)
        }
        // An online lookup (the weather, the nearest place for the maps, 0.4.0) changes nothing until it is found and may
        // wait seconds on the network: Stop ends it at once, and its card says it was stopped. So does a Termux run (0.9).
        if (chosen.online || chosen.stoppable) {
            val r = try {
                actions.run(chosen)
            } catch (e: CancellationException) {
                view.card(Card(id, chosen.tool, chosen.describe(), ActionStatus.DECLINED, "Stopped.", chosen, remote = remote))
                throw e
            }
            return finish(r)
        }
        // Once it runs it runs to the end, and its card says what really happened, even if Stop comes meanwhile: a saved
        // note keeps its Undo, an added event isn't recorded as cancelled.
        return withContext(NonCancellable) { finish(actions.run(chosen)) }
    }

    /**
     * Shows the card and waits: the action ("Do it"), the person or number tapped among [choices] (its remembering
     * action when "Always use this" was ticked), or null (Cancel). [note]: the card's line while it waits. A turn stopped
     * meanwhile leaves the card declined, never waiting.
     */
    private suspend fun confirm(id: String, a: Action, choices: List<Choice>, more: Int, note: String? = null, remember: String? = null,
        allowContacts: Boolean = false, tapFor: AskWhy? = null): Action? {
        view.card(Card(id, a.tool, a.describe(), ActionStatus.PENDING, note, a, body = a.body(), choices = choices, more = more, remote = remote,
            remember = remember, allowContacts = allowContacts, tapFor = tapFor))
        view.phase(TurnPhase.CONFIRMING)
        val pick = try {
            confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, a.tool, a.describe(), ActionStatus.DECLINED, null, a, body = a.body(), remote = remote))
            throw e
        }
        view.phase(TurnPhase.THINKING)
        return when {
            pick < 0 -> null
            choices.isEmpty() -> a
            pick >= Confirmations.REMEMBER -> choices.getOrNull(pick - Confirmations.REMEMBER)?.let { it.remember ?: it.action }
            else -> choices.getOrNull(pick)?.action
        }
    }
}
