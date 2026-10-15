// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

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
        saidLocked: Boolean = false): GateDecision {
        val level = settings.approval(tool)
        return when {
            // 0.9: TermuxPolicy decided when the action was prepared (Settings › Termux, the lock, rules 2 to 5): only a
            // Termux reason ([AskWhy.TERMUX], COMMAND, FROM_CONTENT) shows the card; none means it runs (your script set to
            // Just do it, or an allowlisted read).
            tool.termux -> if (asks(why, settings)) GateDecision.CONFIRM else GateDecision.RUN
            level == Approval.OFF -> GateDecision.DISABLED
            // 0.7: nothing is saved to or removed from memory on the lock screen, whatever "Works on the lock screen" says
            // ("Use memory on the lock screen" lets it be read there, never changed).
            locked && memoryWrite(tool) -> GateDecision.NEEDS_UNLOCK
            locked && tool !in settings.lockedTools -> GateDecision.NEEDS_UNLOCK
            tool == ToolName.ASK_HELPER -> GateDecision.CONFIRM
            // 0.7: a memory the model proposes without your asking, or in a conversation with content, is a card, never silent.
            tool == ToolName.REMEMBER && (!asked || content) -> GateDecision.CONFIRM
            remote && settings.helperActionCards -> GateDecision.CONFIRM
            // What a read tool finds for a helper goes to it: "Share with …" unless personal content may go by itself (for
            // every helper, or this one: [trusted], "Trusted with personal content"). So does what an online lookup finds (the
            // nearest pharmacy says where you are).
            remote && (tool.reads || online) && !settings.personalToHelpers && !trusted -> GateDecision.CONFIRM
            level == Approval.ASK -> GateDecision.CONFIRM
            asks(why, settings) -> GateDecision.CONFIRM
            messages && !(tool.reads && !online) -> GateDecision.CONFIRM
            saidLocked && !asked && !(tool.reads && !online) -> GateDecision.CONFIRM
            content && !(tool.reads && !online) && contentAsks(settings.safetyCards, asked, writes(tool)) -> GateDecision.CONFIRM
            else -> GateDecision.RUN
        }
    }

    /** remember and forget change memory: never on the lock screen. */
    fun memoryWrite(tool: ToolName): Boolean = tool == ToolName.REMEMBER || tool == ToolName.FORGET

    /** [tool] can't run with the phone [locked] under [settings] (checked again after a card, the phone may have locked). */
    fun lockedOut(tool: ToolName, locked: Boolean, settings: RobotSettings): Boolean = locked && (memoryWrite(tool) || tool !in settings.lockedTools)

    /** The same for an action that decides for itself what works locked (0.9: a Termux script you allowed there, never a command). */
    fun lockedOut(a: Action, locked: Boolean, settings: RobotSettings): Boolean = locked && (memoryWrite(a.tool) || !a.worksLocked(settings.lockedTools))

    /** What an action refused for the lock says: memory's own line for remember and forget. */
    fun unlockLine(tool: ToolName): String = when (tool) {
        ToolName.REMEMBER -> app.heyrobot.core.memory.MemoryAccess.UNLOCK_TO_SAVE
        ToolName.FORGET -> app.heyrobot.core.memory.MemoryAccess.UNLOCK_TO_CHANGE
        else -> "Unlock your phone, then ask again."
    }

    /** One of [why] needs a card with these settings. */
    fun asks(why: Set<AskWhy>, s: RobotSettings): Boolean =
        AskWhy.PICK in why || AskWhy.ONLINE in why || AskWhy.MESSAGE in why || AskWhy.SEARCH in why || AskWhy.SEARCH_PERSONAL in why || (AskWhy.WHO in why && s.whoCards) || (AskWhy.LEARNED in why && s.learnedCards) || (AskWhy.DELETE in why && s.deleteCards) ||
            AskWhy.TERMUX in why || AskWhy.COMMAND in why || AskWhy.FROM_CONTENT in why

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

/** What happened to one action; also what the model is told about it. */
enum class ActionStatus(val label: String) {
    PENDING("Waiting for you"),
    DONE("Done"),
    /**
     * Handed to another app as a draft (Cheogram, the SMS app, Proton Mail, the calendar app's editor): nothing is
     * sent or saved until the person does it there, so the model is never told "done".
     */
    DRAFT_OPENED("Draft opened"),
    DECLINED("Cancelled"),
    NEEDS_UNLOCK("Unlock to do this"),
    DISABLED("Turned off in settings"),
    FAILED("Didn't work"),
    INVALID("Couldn't understand the request"),
    /** Over the limit of actions in one answer ([TurnRules.MAX_CALLS]): not run. */
    SKIPPED("Not done"),
    /** Robot needs a permission (contacts, calendar) the person grants in the main app. */
    NEEDS_PERMISSION("Needs your permission"),
    /** No contact (or note) matches what was asked. */
    NOT_FOUND("Not found"),
    /** The app it hands over to isn't installed ("Install Cheogram", "No email app"). */
    NO_APP("App not installed"),
    /** The person tapped Undo on the card (a note Robot had just saved). */
    UNDONE("Undone");

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
