// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.i18n.TurnStrings
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
    /** 1.1: how much of a card's body Robot reads for the days and times it shows (ReplyFacts). */
    const val CARD_WORDS = 600

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
 * [text]: 1.1, [label] as words without a language yet (a button: the turn shows it in the menus' language); null:
 * [label] as it is (a name, a number).
 */
data class Choice(val label: String, val action: Action, val remember: Action? = null, val text: Phrase? = null) {
    constructor(text: Phrase, action: Action, remember: Action? = null) : this(text.en(), action, remember, text)
}

/** 1.1: [en]'s words in [l] while they are still [en] (a copy that changed the English keeps the new English as it is). */
internal fun Phrase?.orEn(en: String, l: Langs): String = this?.takeIf { it.en() == en }?.render(l) ?: en

/** The same for an optional text. */
@JvmName("orEnOptional")
internal fun Phrase?.orEn(en: String?, l: Langs): String? = if (en == null) null else orEn(en, l)

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
        val note: String? = null, val remember: String? = null, val allowContacts: Boolean = false,
        /** 1.1: [note] and [remember] as words without a language yet (the card's line in the reply language, the tick box in the menus'). */
        val noteText: Phrase? = null, val rememberText: Phrase? = null) : Prepared() {
        /** Some reason to show the card besides the level (the settings decide whether it does). */
        val mustAsk: Boolean get() = why.isNotEmpty()

        /** [why] with a list to choose from that names no reason of its own counted as a pick. */
        val reasons: Set<AskWhy> get() = if (choices.isNotEmpty() && why.isEmpty()) setOf(AskWhy.PICK) else why
    }
    /** [text]: 1.1, [detail] as words without a language yet (the card shows them in the reply language; the model reads [detail]). */
    data class Refused(val status: ActionStatus, val detail: String, val text: Phrase? = null) : Prepared() {
        constructor(status: ActionStatus, text: Phrase) : this(status, text.en(), text)
    }
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
    /**
     * 1.4, read_file (Robot Files, F9): the file and the question. The model's turn ends with it, as at a web search: the panel
     * answers in a tool-free FILE_ANSWER one-shot over the file's text, and the main conversation never holds the file's text.
     */
    val file: app.heyrobot.core.files.FileAsk? = null,
    /**
     * 1.1: [detail], [body] and [spoken] as words without a language yet: the card shows them in the turn's reply language
     * and the voice says them so, while the model reads [detail] (or [forModel]), always English. Null: the text as it is.
     */
    val text: Phrase? = null,
    val bodyText: Phrase? = null,
    val spokenText: Phrase? = null,
) {
    /** [text] gives [detail] (its English) and the card's words in the reply language; [body] and [spoken] the same. */
    constructor(status: ActionStatus, text: Phrase, launchedApp: Boolean = false, results: List<Map<String, Any?>>? = null, body: Phrase? = null,
        spoken: Phrase? = null, undo: String? = null, forModel: String? = null, handOff: String? = null, web: WebOutcome? = null,
        file: app.heyrobot.core.files.FileAsk? = null) :
        this(status, text.en(), launchedApp, results, body?.en(), spoken?.en(), undo, forModel, handOff, web, file, text, body, spoken)

    /**
     * Content entered the conversation: every later action in it asks first (web results too: content, not personal; 1.4, a
     * file read_file answers from, whose answer is the file's words).
     */
    val content: Boolean get() = !results.isNullOrEmpty() || web is WebOutcome.Found || file != null
}

interface TurnActions {
    /** Read when deciding and again after a confirmation (the phone may have locked meanwhile). */
    val locked: Boolean
    /** 1.1: a phone lock is on, during which [locked] is true with the screen unlocked: what it refuses says the lock closes it. */
    val phoneLock: Boolean get() = false
    val settings: RobotSettings
    /** May read the contacts or the calendar: suspends, so the panel does that off its main thread. */
    suspend fun prepare(action: Action): Prepared
    /**
     * 0.9: the same, knowing whether the conversation holds content Robot didn't write ([untrusted]) and a helper asked
     * ([remote]): a Termux command's card depends on both (TermuxPolicy's rule 3).
     */
    suspend fun prepare(action: Action, untrusted: Boolean, remote: Boolean): Prepared = prepare(action)
    suspend fun run(action: Action): Executed

    /**
     * 1.1, Skills phase B (plan §8): one of your skills the model called ([call], read by SkillCalls), through the same path
     * as a skill you named (its card when the rule wants one, then each step through its own gate and card). [untrusted]:
     * the conversation holds content. Refuses by default; the panel's port runs it.
     */
    suspend fun runSkill(call: app.heyrobot.core.skills.SkillCall.Ok, untrusted: Boolean): app.heyrobot.core.skills.SkillRan =
        app.heyrobot.core.skills.SkillRan.NOT_OFFERED
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
    /**
     * 1.1 (Skills' save card): small buttons that change what the card would do ("12 minutes · fixed"); a tap on the
     * [i]th answers the card with [CHIP] + i, and the card is shown again. Its Do it and Cancel stay.
     */
    val chips: List<String> = emptyList(),
) {
    companion object {
        /** A chip's answer is this plus its index (below Confirmations.REMEMBER). */
        const val CHIP = 1 shl 12
    }
}

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
    val web: WebOutcome? = null,
    /** 1.4: the model called read_file and it was found: its turn ended there, and the panel answers from the file (FILE_ANSWER). */
    val file: app.heyrobot.core.files.FileAsk? = null,
    /** 1.1, Skills phase B: "Did you mean" answered no (D18): the model answers again, without skills, for this turn. */
    val withoutSkills: Boolean = false)

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
    /**
     * 1.1: the turn's languages (core i18n): what the cards and the voice say in the reply
     * language ([Langs.say], the panel decides it with ReplyLang), the buttons in the menus' ([Langs.ui]). What the model
     * is told stays English. English everywhere by default.
     */
    private val langs: Langs = Langs.EN,
    /** 1.1, Skills phase B: your skills described to the model this turn (a `skill_*` call is read against them). */
    private val skills: app.heyrobot.core.skills.SkillMenu = app.heyrobot.core.skills.SkillMenu.NONE,
    /**
     * 1.1: what your own words said this turn (core AlarmWords): set_alarm runs only for a time you said, on the day it
     * rings. Null: not checked (the panel's own calls, a routine's step, a skill, a helper).
     */
    private val heard: TurnWords? = null,
    /** The time the cards' days are read at (an alarm's ring day, ReplyFacts); [heard]'s when there is one. */
    private val clock: () -> java.time.ZonedDateTime = { java.time.ZonedDateTime.now() },
    private val newId: () -> String,
) {
    private fun now(): java.time.ZonedDateTime = heard?.now ?: clock()

    private var budget = TurnRules.MAX_CALLS

    /** 1.1: the actions that happened in this turn and their cards' words ([cardWords]), for [scrubbed]. */
    private val doneCards = mutableListOf<Pair<Action, String>>()

    /**
     * 1.1: [a]'s card in words, English and the reply language: its summary, the start of its body (a long note's or
     * message's days and times past [TurnRules.CARD_WORDS] characters aren't read) and its line.
     */
    private fun cardWords(a: Action, body: String?, detail: String?): String =
        listOfNotNull(a.summary().en(), a.summary().render(langs), (body ?: a.body())?.take(TurnRules.CARD_WORDS), detail).joinToString("\n")

    /** A button's or a tick box's words: the menus' language. */
    private val ui: Langs get() = Langs(langs.ui, langs.ui, langs.system, pseudo = langs.pseudo)

    /** What the voice says: its own form in French; English as the card has it (1.0's words). */
    private fun spoken(p: Phrase): String = if (langs.say == Lang.EN) p.render(langs) else p.render(langs.voice)

    /** What text-call mode says of a result: its spoken words, else its card's line. */
    private fun said(r: Executed): String =
        r.spoken?.let { s -> r.spokenText?.takeIf { it.en() == s }?.let(::spoken) ?: s } ?: r.text?.takeIf { it.en() == r.detail }?.let(::spoken) ?: r.detail

    /** A card for a call that doesn't run ([why]), and what the model is told. */
    private fun notRun(parsed: ParsedCall, status: ActionStatus, why: Phrase, raw: RawToolCall): Pair<String, Map<String, Any?>> {
        val (tool, summary) = when (parsed) {
            is ParsedCall.Ok -> parsed.action.tool to parsed.action.summary()
            is ParsedCall.Invalid -> ToolName.of(parsed.tool) to TurnStrings.couldntDo("tool" to parsed.tool)
        }
        view.card(Card(newId(), tool, summary.render(langs), status, why.render(langs), remote = remote))
        record(Outcome(tool, status, why.en(), text = why))
        return raw.name to status.toolResponse(why.en())
    }

    /**
     * [spoken]: what the round's actions say (their cards' text), for when no reply can follow them. [outcomes]: how each
     * card ended (1.0.2, ActionOutcome).
     */
    private class Round(val responses: List<Pair<String, Map<String, Any?>>>, val launched: Boolean, val spoken: List<String> = emptyList(),
        val outcomes: List<Outcome> = emptyList())

    /** 1.0.2: every card of this turn as it ended, and the round under way's ([Round.outcomes]). */
    private val outcomes = mutableListOf<Outcome>()
    private val outcomesThisRound = mutableListOf<Outcome>()

    /** The round under way's spoken texts ([Round.spoken]). */
    private val spokenThisRound = mutableListOf<String>()

    /** The round under way's results so far: a stop meanwhile tells the model what ran ([TurnModel.stopped]). */
    private var inRound: MutableList<Pair<String, Map<String, Any?>>>? = null

    /** 0.9: a web search ran in this turn (core web WebOutcome): nothing else of the reply runs, and the turn ends. */
    private var web: WebOutcome? = null

    /** 1.4: read_file found its file in this turn: nothing else of the reply runs, and the turn ends (as [web]). */
    private var file: app.heyrobot.core.files.FileAsk? = null

    /** A web search or read_file ended the model's turn: what else it asked for is skipped, in its words. */
    private val answersFirst: Phrase? get() = when {
        web != null -> TurnStrings.webFirst()
        file != null -> TurnStrings.fileFirst()
        else -> null
    }

    /** 1.1, Skills phase B: a skill's turn ends here ([SkillRan.endTurn]), or "Did you mean" was answered no ([SkillRan.withoutSkills]). */
    private var skillEnd: app.heyrobot.core.skills.SkillRan? = null

    /** 1.1: Robot's own question that ends the turn after this round ("What time should the alarm ring?", AlarmWords). */
    private var askBack: Phrase? = null

    /** 1.1: actions Robot offered in place of one asked for (a dated reminder for an alarm on another day): as asked. */
    private val offeredInstead = mutableSetOf<ToolName>()

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
            // 1.4: read_file the same: the panel answers from the file, in a one-shot that has no tools.
            file?.let {
                model.close()
                return TurnResult(DoneReason.END, file = it)
            }
            // A skill's card ended the turn: "Did you mean" answered no (the panel asks again without skills), or a value
            // being changed (your next words). The model writes nothing after it.
            skillEnd?.let {
                model.abandon(round.responses)
                return TurnResult(DoneReason.END, withoutSkills = it.withoutSkills)
            }
            // 1.1: an alarm without a time you said: Robot asks, and the model writes nothing that could guess one.
            askBack?.let {
                model.abandon(round.responses)
                ask(it)
                return TurnResult(DoneReason.END)
            }
            // 1.0.2: an action you asked for didn't happen (you cancelled it, no contact matched): Robot says so from its card,
            // and the model, told what happened, writes no follow-up that could claim it was done. A helper still answers.
            // 1.1: a timer, an alarm, an event or a note that was done: Robot's line from its card is the reply, never a
            // follow-up that could name another day or time (ActionOutcome.confirms).
            if (!remote && !model.appCalls && (ActionOutcome.settles(round.outcomes) || ActionOutcome.confirms(round.outcomes))) {
                model.abandon(round.responses)
                writeOutcome(round.outcomes)
                return TurnResult(DoneReason.END)
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
                if (found.isEmpty()) {
                    keepHonest()
                    keepTrue()
                    return TurnResult(outcome.reason)
                }
                view.replaceText("")
                if (found.any { it.call.asksHelper() }) return escalate()
                val round = runCalls(found.map { it.call }, speak = true)
                askBack?.let(::ask)
                // No tool results in this mode: the conversation is rebuilt from the history next time.
                model.close()
                TurnResult(DoneReason.END, launchedApp = round.launched, web = web, file = file, withoutSkills = skillEnd?.withoutSkills == true)
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

    /**
     * 1.0.2: Robot's own lines for [round] after the reply so far, without any sentence of it that claims an action was done
     * when none of this turn's was; said too when replies are spoken.
     */
    private fun writeOutcome(round: List<Outcome>) {
        // 1.1: shown in the reply language, said in the voice's own form.
        val lines = ActionOutcome.lines(round) { it.render(langs) }
        // 1.1: and without one that names a day, time or length no card that happened shows (ReplyFacts).
        val before = if (outcomes.any { it.acts && it.status.succeeded }) scrubbed() ?: view.text else ActionOutcome.withoutClaims(view.text)
        view.replaceText(listOf(before.trimEnd(), lines.joinToString(" ")).filter { it.isNotBlank() }.joinToString("\n\n"))
        ActionOutcome.lines(round, ::spoken).forEach(view::say)
    }

    /**
     * 1.0.2, the backstop: the model answered after actions of which none happened (a card you cancelled that you hadn't asked
     * for in your words, a call it got wrong) and its reply still says one was done. That sentence goes, and Robot's line
     * for each card says what really happened (said too: the claim may have been read out already). The local model only.
     */
    private fun keepHonest() {
        if (remote || model.appCalls) return
        val acts = outcomes.filter { it.acts }
        if (acts.isEmpty() || acts.any { it.status.succeeded || it.status == ActionStatus.PENDING }) return
        if (!ActionOutcome.claims(view.text)) return
        writeOutcome(acts)
    }

    /** 1.1: Robot's own question after the reply so far (without a sentence claiming an action when none happened); said too. */
    private fun ask(q: Phrase) {
        val before = if (outcomes.any { it.acts && it.status.succeeded }) view.text else ActionOutcome.withoutClaims(view.text)
        view.replaceText(listOf(before.trimEnd(), q.render(langs)).filter { it.isNotBlank() }.joinToString("\n\n"))
        view.say(spoken(q))
    }

    /** 1.1: the reply so far without its sentences about an action that name what no card that happened shows; null: none. */
    private fun scrubbed(): String? {
        if (doneCards.isEmpty() || !ReplyFacts.speaksOfAction(view.text)) return null
        val shown = doneCards.map { (a, words) -> ReplyFacts.shown(a, now(), words, langs.say) }
        return ReplyFacts.scrub(view.text, shown, now(), langs.say)
    }

    /**
     * 1.1, point 1's backstop: after actions that happened, a sentence of the reply that speaks of one and names a day, time
     * or length its card doesn't show goes, and Robot's line for each card that happened follows (said too). The local model only.
     */
    private fun keepTrue() {
        if (remote || model.appCalls) return
        val done = outcomes.filter { it.acts && it.status.succeeded }
        val kept = scrubbed() ?: return
        val lines = ActionOutcome.lines(done) { it.render(langs) }
        view.replaceText(listOf(kept.trimEnd(), lines.joinToString(" ")).filter { it.isNotBlank() }.joinToString("\n\n"))
        ActionOutcome.lines(done, ::spoken).forEach(view::say)
    }

    private fun escalate(): TurnResult {
        model.close()
        return TurnResult(DoneReason.END, escalate = true)
    }

    /** A helper's call checked first: one of the tools offered to it, its input fitting the schema; then the usual reading. */
    private fun parse(raw: RawToolCall): ParsedCall {
        // A tool the model is never told about stays out of its reach (0.8's call tool: "call Mom" is the panel's, not the model's).
        if (!model.appCalls && ToolName.of(raw.name)?.forModel == false) return ParsedCall.Invalid(raw.name.take(40), ToolStrings.unknownTool())
        if (!remote) return ToolCalls.parse(raw)
        val tool = ToolName.of(raw.name)?.takeIf { it in HelperTools.OFFERED } ?: return ParsedCall.Invalid(raw.name.take(40), ToolStrings.unknownTool())
        if (tool !in offered) return ParsedCall.Invalid(tool.id, ToolStrings.notOffered())
        HelperTools.check(ToolSpecs.of(tool), raw.args)?.let { return ParsedCall.Invalid(tool.id, it) }
        return ToolCalls.parse(raw)
    }

    private suspend fun runCalls(calls: List<RawToolCall>, speak: Boolean): Round {
        val out = mutableListOf<Pair<String, Map<String, Any?>>>()
        inRound = out
        spokenThisRound.clear()
        outcomesThisRound.clear()
        for (raw in calls) {
            // 1.1, Skills phase B: a skill's card ended the turn ("Did you mean" answered no, a value being changed):
            // nothing else of this reply runs.
            if (skillEnd != null) break
            // A skill's call is read by SkillCalls, never by ToolCalls.
            if (app.heyrobot.core.skills.SkillCalls.isSkill(raw.name)) {
                val (response, launched) = skillCall(raw) ?: continue
                out += response
                if (launched) return Round(out, launched = true, spokenThisRound.toList(), outcomesThisRound.toList())
                continue
            }
            val parsed = parse(raw)
            // After a web search, nothing else of this reply runs: the answer comes from the search (design A6.2). 1.4: nor after
            // read_file, whose answer comes from the file.
            answersFirst?.let {
                out += notRun(parsed, ActionStatus.SKIPPED, it, raw)
                continue
            }
            if (budget <= 0) {
                out += notRun(parsed, ActionStatus.SKIPPED, TurnStrings.maxCalls(TurnRules.MAX_CALLS), raw)
                continue
            }
            budget--
            when (parsed) {
                is ParsedCall.Invalid -> {
                    // The card in the reply language; the model reads the reason as it was written, English.
                    view.card(Card(newId(), ToolName.of(parsed.tool), TurnStrings.couldntDo("tool" to parsed.tool).render(langs), ActionStatus.INVALID,
                        parsed.why.orEn(parsed.reason, langs), remote = remote))
                    val o = Outcome(ToolName.of(parsed.tool), ActionStatus.INVALID, parsed.reason, text = parsed.why)
                    out += raw.name to ActionOutcome.forModel(ActionStatus.INVALID.toolResponse(parsed.reason), o)
                    record(o)
                }
                is ParsedCall.Ok -> {
                    val (response, launched) = decideAndRun(parsed.action, speak)
                    out += response
                    // The panel is leaving for another app: nothing more is done in this answer.
                    if (launched) return Round(out, launched = true, spokenThisRound.toList(), outcomesThisRound.toList())
                }
            }
        }
        return Round(out, launched = false, spokenThisRound.toList(), outcomesThisRound.toList())
    }

    /**
     * A model's skill call (plan §8): refused when it wasn't described, changed since, or its arguments aren't the skill's;
     * else run by the panel's port. It counts as one of [TurnRules.MAX_CALLS], and its outcome has no ToolName (it acts);
     * each step records its own card. Null: nothing for the model (a skill already ended the turn).
     */
    private suspend fun skillCall(raw: RawToolCall): Pair<Pair<String, Map<String, Any?>>, Boolean>? {
        val sk = app.heyrobot.core.skills.SkillCalls
        fun refused(status: ActionStatus, why: Phrase, name: String?): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            val summary = name?.let { app.heyrobot.core.i18n.SkillStrings.callNamed("name" to it) } ?: app.heyrobot.core.i18n.SkillStrings.callAny()
            view.card(Card(newId(), null, summary.render(langs), status, why.render(langs), remote = remote))
            val o = Outcome(null, status, why.en(), text = why)
            record(o)
            return (raw.name to ActionOutcome.forModel(status.toolResponse(why.en()), o)) to false
        }
        answersFirst?.let { return refused(ActionStatus.SKIPPED, it, null) }
        if (budget <= 0) return refused(ActionStatus.SKIPPED, TurnStrings.maxCalls(TurnRules.MAX_CALLS), null)
        budget--
        return when (val c = sk.parse(raw, skills, remote)) {
            is app.heyrobot.core.skills.SkillCall.Refused -> {
                VoiceLog.add(VoiceEvent.SKILL_WARN, c.code.id, 0, 0)
                refused(c.status, c.why, c.skill?.name)
            }
            is app.heyrobot.core.skills.SkillCall.Ok -> {
                val r = actions.runSkill(c, untrusted)
                // What a step brought in is content for the rest of this answer (D5).
                if (r.content) untrusted = true
                if (r.endTurn || r.withoutSkills) skillEnd = r
                val o = Outcome(null, r.status, r.text?.en() ?: r.status.label, asked = r.asked, content = r.content, text = r.text)
                record(o)
                (raw.name to ActionOutcome.forModel(r.forModel, o)) to r.launched
            }
        }
    }

    private fun record(o: Outcome) {
        outcomesThisRound += o
        outcomes += o
    }

    /** What a refusal for the lock says: during a phone lock, that the lock closes it (unlocking wouldn't help, §6). */
    private fun unlockText(t: ToolName): Phrase = if (actions.phoneLock) app.heyrobot.core.i18n.LockStrings.closedNow() else ApprovalGate.unlockText(t)

    private suspend fun decideAndRun(asked: Action, speak: Boolean): Pair<Pair<String, Map<String, Any?>>, Boolean> {
        val id = newId()
        /** The card as it ended ([detail]: its line, in the reply language) and what the model is told ([model], English). */
        fun done(a: Action, status: ActionStatus, detail: String?, launched: Boolean = false, r: Executed? = null, model: String? = detail,
            text: Phrase? = null): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            val body = if (r?.body != null) r.bodyText.orEn(r.body, langs) else a.bodyText().orEn(a.body(), langs)
            view.card(Card(id, a.tool, a.summary().render(langs), status, detail, a, body = body, undo = r?.undo, content = r?.content == true, remote = remote,
                handOff = r?.handOff))
            // 1.0.2: an action that didn't happen is said so in plain words too, beside its status code. [text]: its line's
            // words without a language yet (1.1), for Robot's own line in the reply language.
            val o = Outcome(a.tool, status, model, asked = a.tool in this.asked || a.tool in offeredInstead, content = r?.content == true, spoken = r?.spoken, text = text,
                spokenText = r?.spokenText)
            record(o)
            // 1.1: what its card shows, read only if the reply speaks of an action (ReplyFacts, [scrubbed]).
            if (status.succeeded && !a.tool.reads) doneCards += a to cardWords(a, body, detail)
            return (a.tool.id to ActionOutcome.forModel(status.toolResponse(r?.forModel ?: model, r?.results), o)) to launched
        }
        fun done(a: Action, status: ActionStatus, why: Phrase): Pair<Pair<String, Map<String, Any?>>, Boolean> = done(a, status, why.render(langs), model = why.en(), text = why)
        // 0.7: what a message's quarantine forbids outright (a memory the model proposes once a message is in the
        // conversation) gets no card and runs nothing.
        if (MessageQuarantine.verdict(asked.tool, origin, messages) == QuarantineVerdict.FORBIDDEN) {
            if (messages.active) VoiceLog.add(VoiceEvent.MSG_GUARD, app.heyrobot.core.messages.GuardCode.REFUSED)
            return done(asked, ActionStatus.SKIPPED, MessageQuarantine.forbiddenText(asked.tool))
        }
        // 1.1: an alarm only for a time you said, on the day it rings (core AlarmWords); a day it won't ring on is offered
        // as a dated reminder instead, on add_event's card.
        if (asked is Action.SetAlarm && heard != null && !remote && !model.appCalls) when (val fit = AlarmWords.check(asked, heard)) {
            AlarmFit.Ok -> {}
            AlarmFit.NoTime -> {
                askBack = TurnStrings.alarmWhatTime()
                return done(asked, ActionStatus.INVALID, ToolStrings.alarmNoTime().render(langs), model = AlarmWords.NO_TIME, text = ToolStrings.alarmNoTime())
            }
            is AlarmFit.OtherTime -> {
                val why = ToolStrings.alarmOtherTime("time" to EventText.clock(asked.hour, asked.minute))
                return done(asked, ActionStatus.INVALID, why.render(langs), model = AlarmWords.OTHER_TIME + " (" + fit.said.said + ").", text = why)
            }
            is AlarmFit.OtherDay -> {
                offeredInstead += ToolName.ADD_EVENT
                val (response, launched) = decideAndRun(AlarmWords.reminder(fit, ToolStrings.reminderTitle().render(langs)), speak)
                return (asked.tool.id to (response.second + ("alarm" to AlarmWords.OFFERED))) to launched
            }
        }
        // 1.1 (phone, 22 Oct: « Ajoute un film ce soir de 22 h à 1 h » set a 1-minute timer): a timer or an alarm your words
        // didn't name, when they ask for an event or name a time range (core AlarmWords.event), shows its card whatever the
        // settings say.
        val unnamed = (asked is Action.SetTimer || asked is Action.SetAlarm) && asked.tool !in this.asked && heard != null && !remote &&
            !model.appCalls && AlarmWords.event(heard)
        // 1.1.1 (a Pixel 8, 23 Oct: "call 911" during a phone lock said "Unlock to do this"): a call to a number that is an
        // emergency one everywhere goes past the lock to prepare, which checks it against Android's own list; only a call
        // prepare marks as an emergency then runs locked, and it only opens the emergency dialer (CallPlacer).
        val emergencyAsked = asked is Action.Call && app.heyrobot.core.car.EmergencyNumbers.basic(asked.to, null)
        // The lock and the settings first: nothing about the phone (an installed app's name) is looked up for nothing.
        when (ApprovalGate.decide(asked.tool, actions.locked, actions.settings, content = untrusted, asked = asked.tool in this.asked, remote = remote,
            online = asked.online, trusted = trusted, messages = messages.active, saidLocked = messages.saidLocked)) {
            GateDecision.NEEDS_UNLOCK -> if (!emergencyAsked) return done(asked, ActionStatus.NEEDS_UNLOCK, unlockText(asked.tool))
            GateDecision.DISABLED -> return done(asked, ActionStatus.DISABLED, TurnStrings.turnedOff("what" to asked.tool.titleText))
            else -> {}
        }
        val a = when (val p = actions.prepare(asked, untrusted, remote)) {
            is Prepared.Refused -> return done(asked, p.status, p.text.orEn(p.detail, langs), model = p.detail, text = p.text)
            is Prepared.Ready -> p
        }
        val emergency = emergencyAsked && (a.action as? Action.Call)?.emergency == true
        // 1.0 (injection-termux-policy-unwired): a Termux call TermuxPolicy didn't prepare (no card, no key) never runs, and
        // never runs without its card: fail closed rather than trust the gate's Termux branch with no reasons.
        if (a.action.tool.termux && !TermuxCalls.prepared(a.action)) return done(a.action, ActionStatus.FAILED, TermuxCalls.unprepared)
        // A message in the conversation: a tap for anything that leaves for someone else (the card says why).
        val fromMessage = MessageQuarantine.why(a.action, messages, origin)
        val reasons = a.reasons + fromMessage
        val decision = ApprovalGate.decide(a.action.tool, actions.locked, actions.settings, reasons, content = untrusted,
            asked = a.action.tool in this.asked, remote = remote, online = a.action.online, trusted = trusted, messages = messages.active,
            saidLocked = messages.saidLocked).let { if (unnamed && it == GateDecision.RUN) GateDecision.CONFIRM else it }
            .let { if (emergency && it == GateDecision.NEEDS_UNLOCK) GateDecision.RUN else it }
        if (messages.active && decision == GateDecision.CONFIRM) VoiceLog.add(VoiceEvent.MSG_GUARD,
            if (fromMessage.isEmpty()) app.heyrobot.core.messages.GuardCode.ASKED else app.heyrobot.core.messages.GuardCode.TAP_REQUIRED)
        val chosen = when (decision) {
            // No card for these choices (the settings say so): the first, the best match.
            GateDecision.RUN -> a.choices.firstOrNull()?.action ?: a.action
            GateDecision.CONFIRM -> confirm(id, a.action, a.choices, a.more, if (fromMessage.isNotEmpty()) MessageQuarantine.cardLineText().render(langs) else a.noteText.orEn(a.note, langs),
                a.rememberText.orEn(a.remember, ui), a.allowContacts, tapFor = CardAnswer.tapFor(reasons))
            GateDecision.NEEDS_UNLOCK -> return done(a.action, ActionStatus.NEEDS_UNLOCK, unlockText(a.action.tool))
            GateDecision.DISABLED -> return done(a.action, ActionStatus.DISABLED, TurnStrings.turnedOff("what" to a.action.tool.titleText))
        } ?: return done(a.action, ActionStatus.DECLINED, null)
        // The phone may have locked while the card waited.
        if (!emergency && ApprovalGate.lockedOut(chosen, actions.locked, actions.settings)) {
            return done(chosen, ActionStatus.NEEDS_UNLOCK, unlockText(chosen.tool))
        }
        fun finish(r: Executed): Pair<Pair<String, Map<String, Any?>>, Boolean> {
            // What a read tool found is content: from now on every action in this conversation asks first.
            if (r.content) untrusted = true
            if (r.web != null && r.status != ActionStatus.DECLINED) web = r.web
            if (r.file != null && r.status == ActionStatus.DONE) file = r.file
            val launched = r.launchedApp && r.status.succeeded
            if (speak && !launched && r.web == null && r.file == null) view.say(said(r))
            if (!launched) spokenThisRound += said(r)
            return done(chosen, r.status, r.text.orEn(r.detail, langs), launched, r, model = r.detail, text = r.text)
        }
        // An online lookup (the weather, the nearest place for the maps, 0.4.0) changes nothing until it is found and may
        // wait seconds on the network: Stop ends it at once, and its card says it was stopped. So does a Termux run (0.9).
        if (chosen.online || chosen.stoppable) {
            val r = try {
                actions.run(chosen)
            } catch (e: CancellationException) {
                view.card(Card(id, chosen.tool, chosen.summary().render(langs), ActionStatus.DECLINED, TurnStrings.stopped().render(langs), chosen, remote = remote))
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
        val summary = a.summary().render(langs)
        val body = a.bodyText().orEn(a.body(), langs)
        // The buttons are in the menus' language; a helper's read keeps the panel's own "Share with …" (null here).
        val button = if (remote && a.tool.reads) null else a.confirmText?.render(ui)
        val shown = choices.map { c -> c.text?.let { c.copy(label = it.orEn(c.label, ui)) } ?: c }
        view.card(Card(id, a.tool, summary, ActionStatus.PENDING, note, a, body = body, choices = shown, more = more, remote = remote,
            confirm = button, remember = remember, allowContacts = allowContacts, tapFor = tapFor))
        view.phase(TurnPhase.CONFIRMING)
        val pick = try {
            confirmations.ask(id)
        } catch (e: CancellationException) {
            view.card(Card(id, a.tool, summary, ActionStatus.DECLINED, null, a, body = body, remote = remote))
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
