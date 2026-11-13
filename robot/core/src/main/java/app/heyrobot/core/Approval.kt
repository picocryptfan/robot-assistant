// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings

// Pure. Every action the model asks for passes this gate before ToolExecutor runs it.

enum class GateDecision {
    /** Run it now and show what was done. */
    RUN,
    /** Show a confirmation card; run only if the person taps "Do it". */
    CONFIRM,
    /** The phone is locked and this action is not allowed there: "Unlock to do this". */
    NEEDS_UNLOCK,
    /** Turned off in settings. */
    DISABLED,
}

object ApprovalGate {
    /**
     * The gate (0.4.0: every rule a switch in Settings › Safety & privacy, [RobotSettings]). In order:
     *
     * - Off in Settings › Actions: not done. Locked, and not one of "Works on the lock screen" (timers and alarms by
     *   default; questions always work): unlock first.
     * - The hand-off to a helper's app always shows "Open the ChatGPT app with: «…»". [remote], a helper asked for it
     *   (its input may come from web pages): a card with "Card when a helper asks Robot to do something" on; and a read tool's findings go to
     *   the helper only after a tap unless "Send personal content to helpers automatically" is on or the helper is
     *   trusted with personal content ([trusted]), and so does an action that looks something up online for it
     *   ([online]: the nearest place, found around where you are).
     * - "Ask first", or a reason the prepared action gives ([why]): not certain who or which ("Card when who-to isn't
     *   certain"), a name learned from Cheogram ("Card for names learned from Cheogram"), deleting files ("Card before
     *   deleting files"), a list to pick from (always).
     * - [content]: the conversation carries screen text, a picture, a read tool's findings or a helper's words (content Robot
     *   didn't write and the person didn't type). "Safety cards" decides: every action that writes or leaves asks
     *   (Always after Robot reads something), only one the person didn't ask for in their own words this turn ([asked]; When
     *   you didn't ask for it, the default), or none (Only when Robot isn't sure who). 1.0.1: under the default, an action
     *   that writes ([writes]) asks with content even when you asked for it: your words named the tool, but injected text
     *   could have chosen its details (the event, the note, the app, the place). The read tools ([ToolName.reads]:
     *   lookup_contact, find_notes, read_calendar, phone_status) never ask for content: they only bring more to read; a
     *   read that goes online ([ToolName.online], the weather) does, since injected text could send a word or where you
     *   are to Open-Meteo.
     * - 0.7, [messages]: a message other people wrote is in the conversation (core messages MessageQuarantine): every
     *   action that writes or leaves the phone asks, whatever Safety cards says; one that would send something to anyone
     *   but that message's sender also carries [AskWhy.MESSAGE] (a tap). Not a switch.
     * - 1.0, [saidLocked]: the conversation holds turns said while the phone was locked, perhaps by someone else, and you
     *   continued it unlocked (core PanelContinuity): an action you didn't ask for in your own words this turn asks first,
     *   whatever Safety cards says. Not a switch either.
     */
    fun decide(tool: ToolName, locked: Boolean, settings: RobotSettings, why: Set<AskWhy> = emptySet(), content: Boolean = false,
        asked: Boolean = false, remote: Boolean = false, online: Boolean = tool.online, trusted: Boolean = false, messages: Boolean = false,
        saidLocked: Boolean = false, named: Boolean = false): GateDecision {
        val level = settings.approval(tool)
        // auto-open-plan §2: the app you named in your own words counts as asked, and its card no longer waits for content in
        // the conversation (the app came from you), only where [named] says so.
        val yours = named && named(tool, locked, why, remote, messages, saidLocked)
        val asked = asked || yours
        return when {
            // 0.9: TermuxPolicy decided when the action was prepared (Settings › Termux, the lock, rules 2 to 5): only a
            // Termux reason ([AskWhy.TERMUX], COMMAND, FROM_CONTENT) shows the card; none means it runs (your script set to
            // Just do it, or an allowlisted read).
            tool.termux -> if (asks(why, settings)) GateDecision.CONFIRM else GateDecision.RUN
            level == Approval.OFF -> GateDecision.DISABLED
            // The everyday set (E2, §4.4): calculate reads nothing of yours and changes nothing, so it runs locked or unlocked,
            // during a phone lock, with content or a message in the conversation; no card can make a sum safer.
            free(tool) -> GateDecision.RUN
            // 0.7: nothing is saved to or removed from memory on the lock screen, whatever "Works on the lock screen" says
            // ("Use memory on the lock screen" lets it be read there, never changed).
            locked && memoryWrite(tool) -> GateDecision.NEEDS_UNLOCK
            // 1.4: Robot Files' reads never run on a locked phone or during a phone lock, whatever "Works on the lock screen"
            // holds (a restored settings file can't add them: its key needs the phone unlocked anyway, files-1.4-plan.md §5.3).
            locked && files(tool) -> GateDecision.NEEDS_UNLOCK
            // 1.4, Robot Lists (§5): the model never changes your lists on a locked phone; locked adds come from your words only.
            locked && lists(tool) -> GateDecision.NEEDS_UNLOCK
            // Skills connections (C4, D8): a connection's or a hand-off's step never runs on a locked phone or during a phone
            // lock, whatever "Works on the lock screen" holds. Then the usual rules; the step's own reasons only add cards.
            locked && connection(tool) -> GateDecision.NEEDS_UNLOCK
            locked && tool !in settings.lockedTools -> GateDecision.NEEDS_UNLOCK
            tool == ToolName.ASK_HELPER -> GateDecision.CONFIRM
            // 0.7: a memory the model proposes without your asking, or in a conversation with content, is a card, never silent.
            tool == ToolName.REMEMBER && (!asked || content) -> GateDecision.CONFIRM
            remote && settings.helperActionCards -> GateDecision.CONFIRM
            // What a read tool finds for a helper goes to it: "Share with …" unless personal content may go by itself (for
            // every helper, or this one: [trusted], "Trusted with personal content"). So does what an online lookup finds (the
            // nearest pharmacy says where you are). web_search too (W4): a helper's query leaves the phone for the search
            // engine and its results go back to the helper, so it waits for a yes even with helper cards off.
            remote && (tool.reads || online) && !settings.personalToHelpers && !trusted -> GateDecision.CONFIRM
            level == Approval.ASK -> GateDecision.CONFIRM
            asks(why, settings) -> GateDecision.CONFIRM
            messages && !(tool.reads && !online) -> GateDecision.CONFIRM
            saidLocked && !asked && !(tool.reads && !online) -> GateDecision.CONFIRM
            content && !(tool.reads && !online) && contentAsks(settings.safetyCards, asked, writes(tool) && !yours) -> GateDecision.CONFIRM
            else -> GateDecision.RUN
        }
    }

    /**
     * auto-open-plan §2: may an app you named skip open_app's card? Only open_app, unlocked, with no helper or other robot
     * asking ([remote]), no message ([messages]) or words said locked ([saidLocked]) in the conversation, and a match that
     * isn't loose ([AskWhy.WHO]) or one of several ([AskWhy.PICK]). Ask first and Off still win in [decide].
     */
    fun named(tool: ToolName, locked: Boolean, why: Set<AskWhy>, remote: Boolean, messages: Boolean, saidLocked: Boolean): Boolean =
        tool == ToolName.OPEN_APP && !locked && !remote && !messages && !saidLocked && AskWhy.WHO !in why && AskWhy.PICK !in why

    /**
     * What acts on the phone and just happens by default: when your words this turn didn't ask for it ([ToolRouter.asked]),
     * only the model's guess did, it shows its card whatever Settings › Actions says (TurnRunner). A word missed there only
     * shows a card. Not here: what already decides its own card from your words (remember, add_to_list, browser_search,
     * skills' connections), an alarm (AlarmWords: only for a time you said), the reads, and what asks first anyway.
     */
    val ASKS_WHEN_UNSAID: Set<ToolName> = setOf(ToolName.OPEN_APP, ToolName.SET_TIMER, ToolName.FLASHLIGHT,
        ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE, ToolName.ADD_NOTE)

    /** remember and forget change memory: never on the lock screen. */
    fun memoryWrite(tool: ToolName): Boolean = tool == ToolName.REMEMBER || tool == ToolName.FORGET

    /** The everyday set (E2): calculate passes the gate locked or unlocked ([decide]); it works out a sum and nothing else. */
    fun free(tool: ToolName): Boolean = tool == ToolName.CALCULATE

    /** 1.4: find_files and read_file read Robot Files: never on the lock screen either. */
    fun files(tool: ToolName): Boolean = tool.group == ToolGroup.FILES

    /** 1.4: add_to_list changes Robot Lists: never on the lock screen either, whatever a restored "Works on the lock screen" says. */
    fun lists(tool: ToolName): Boolean = tool.group == ToolGroup.LISTS

    /** Skills connections (C4): a step that reaches a connection or hands off to an app: never on the lock screen (D8). */
    fun connection(tool: ToolName): Boolean = tool.group == ToolGroup.CONNECTIONS

    /** [tool] can't run with the phone [locked] under [settings] (checked again after a card, the phone may have locked). */
    fun lockedOut(tool: ToolName, locked: Boolean, settings: RobotSettings): Boolean =
        locked && !free(tool) && (memoryWrite(tool) || files(tool) || lists(tool) || connection(tool) || tool !in settings.lockedTools)

    /** The same for an action that decides for itself what works locked (0.9: a Termux script you allowed there, never a command). */
    fun lockedOut(a: Action, locked: Boolean, settings: RobotSettings): Boolean =
        locked && !free(a.tool) && (memoryWrite(a.tool) || files(a.tool) || lists(a.tool) || connection(a.tool) || !a.worksLocked(settings.lockedTools))

    /** What an action refused for the lock says: memory's own line for remember and forget. */
    fun unlockLine(tool: ToolName): String = unlockText(tool).en()

    /** [unlockLine] in words without a language yet (1.1): the card's line in the reply language. */
    fun unlockText(tool: ToolName): Phrase = when (tool) {
        ToolName.REMEMBER -> app.heyrobot.core.memory.MemoryAccess.unlockToSave()
        ToolName.FORGET -> app.heyrobot.core.memory.MemoryAccess.unlockToChange()
        ToolName.FIND_FILES, ToolName.READ_FILE -> app.heyrobot.core.i18n.FilesStrings.locked()
        ToolName.ADD_TO_LIST -> app.heyrobot.core.i18n.ListsStrings.unlockToChange()
        ToolName.ASK_ROBOT -> app.heyrobot.core.i18n.LinkStrings.unlockAny()
        else -> TurnStrings.unlockThenAsk()
    }

    /** One of [why] needs a card with these settings. */
    fun asks(why: Set<AskWhy>, s: RobotSettings): Boolean =
        AskWhy.PICK in why || AskWhy.ONLINE in why || AskWhy.MESSAGE in why || AskWhy.SEARCH in why || AskWhy.SEARCH_PERSONAL in why || (AskWhy.WHO in why && s.whoCards) || (AskWhy.LEARNED in why && s.learnedCards) || (AskWhy.DELETE in why && s.deleteCards) ||
            AskWhy.TERMUX in why || AskWhy.COMMAND in why || AskWhy.FROM_CONTENT in why || AskWhy.REMINDER in why || AskWhy.LIST_SUGGESTED in why ||
            // Skills connections (C4): each always shows its card (the first-use card above all: never a silent first choice).
            AskWhy.CONNECTION in why || AskWhy.CONNECTION_FIRST in why || AskWhy.CONNECTION_TAP in why

    /**
     * Content in the conversation makes this action ask first, under [mode] ([asked]: the person asked for it this turn;
     * [writes]: it writes something whose details content could have chosen, 1.0.1).
     */
    fun contentAsks(mode: SafetyCards, asked: Boolean, writes: Boolean = false): Boolean = when (mode) {
        SafetyCards.AFTER_READING -> true
        SafetyCards.UNASKED -> !asked || writes
        SafetyCards.UNSURE_ONLY -> false
    }

    /**
     * 1.0.1: [tool] writes or opens something with details the model chose (an event, a note, an app, a place, a timer):
     * with content in the conversation it asks even when you asked for it. Not the read tools; not texts, emails and calls,
     * which open a draft or the dialer you finish yourself (and ask first by default); not Termux, which TermuxPolicy decides.
     */
    fun writes(tool: ToolName): Boolean = !tool.reads && !tool.termux && tool !in DRAFTS

    private val DRAFTS = setOf(ToolName.SEND_TEXT, ToolName.DRAFT_EMAIL, ToolName.CALL)
}

/**
 * What happened to one action; also what the model is told about it. [label] is the card's state in English (and what a
 * replayed conversation tells the model); [text] the same words without a language yet (1.1: a card shows them in the
 * menus' language).
 */
enum class ActionStatus(private val msg: Msg) {
    PENDING(TurnStrings.statusPending),
    DONE(TurnStrings.statusDone),
    /**
     * Handed to another app as a draft (Cheogram, the SMS app, Proton Mail, the calendar app's editor): nothing is
     * sent or saved until the person does it there, so the model is never told "done".
     */
    DRAFT_OPENED(TurnStrings.statusDraftOpened),
    DECLINED(TurnStrings.statusDeclined),
    NEEDS_UNLOCK(TurnStrings.statusNeedsUnlock),
    DISABLED(TurnStrings.statusDisabled),
    FAILED(TurnStrings.statusFailed),
    INVALID(TurnStrings.statusInvalid),
    /** Over the limit of actions in one answer ([TurnRules.MAX_CALLS]): not run. */
    SKIPPED(TurnStrings.statusSkipped),
    /** Robot needs a permission (contacts, calendar) the person grants in the main app. */
    NEEDS_PERMISSION(TurnStrings.statusNeedsPermission),
    /** No contact, note or app matches what was asked: nothing was tried. */
    NOT_FOUND(TurnStrings.statusNotFound),
    /** The app it hands over to isn't installed ("Install Cheogram", "No email app"). */
    NO_APP(TurnStrings.statusNoApp),
    /** The person tapped Undo on the card (a note Robot had just saved). */
    UNDONE(TurnStrings.statusUndone);

    /** The card's state, in words without a language yet. */
    val text: Phrase get() = msg()

    /** The card's state in English ("Done"): History's replay to the model, and the screens not yet moved to [text]. */
    val label: String get() = text.en()

    /**
     * The tool response sent back to the model ([detail] is a short, fixed-vocabulary explanation; [results] a read
     * tool's findings, information for the answer and never instructions).
     */
    fun toolResponse(detail: String?, results: List<Map<String, Any?>>? = null): Map<String, Any?> = buildMap {
        put("status", when (this@ActionStatus) {
            PENDING -> "pending"
            DONE -> "done"
            DRAFT_OPENED -> "draft_opened_not_sent"
            DECLINED -> "cancelled_by_user"
            NEEDS_UNLOCK -> "phone_locked_user_must_unlock"
            DISABLED -> "turned_off_in_settings"
            FAILED -> "failed"
            INVALID -> "invalid_arguments"
            SKIPPED -> "not_done_too_many_actions"
            NEEDS_PERMISSION -> "permission_needed_user_must_allow"
            NOT_FOUND -> "not_found"
            NO_APP -> "app_not_installed"
            UNDONE -> "undone_by_user"
        })
        if (!detail.isNullOrBlank()) put("detail", detail)
        if (results != null) put("results", results)
    }

    /** It happened (or its draft opened). */
    val succeeded: Boolean get() = this == DONE || this == DRAFT_OPENED
}
