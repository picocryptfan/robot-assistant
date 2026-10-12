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
     * - The Claude app hand-off always shows "Open Claude with: «…»". [remote], a helper asked for it (its input may come
     *   from web pages): a card with "Card when a helper asks Robot to do something" on; and a read tool's findings go to
     *   the helper only after a tap unless "Send personal content to helpers automatically" is on or the helper is
     *   trusted with personal content ([trusted]), and so does an action that looks something up online for it
     *   ([online]: the nearest place, found around where you are).
     * - "Ask first", or a reason the prepared action gives ([why]): not certain who or which ("Card when who-to isn't
     *   certain"), a name learned from Cheogram ("Card for names learned from Cheogram"), deleting files ("Card before
     *   deleting files"), a list to pick from (always).
     * - [content]: the conversation carries screen text, a picture, a read tool's findings or Claude's words (content Robot
     *   didn't write and the person didn't type). "Safety cards" decides: every action that writes or leaves asks
     *   (Always after Robot reads something), only one the person didn't ask for in their own words this turn ([asked]; When
     *   you didn't ask for it, the default), or none (Only when Robot isn't sure who). The read tools ([ToolName.reads]:
     *   lookup_contact, find_notes, read_calendar, phone_status) never ask for content: they only bring more to read; a
     *   read that goes online ([ToolName.online], the weather) does, since injected text could send a word or where you
     *   are to Open-Meteo.
     * - 0.7, [messages]: a message other people wrote is in the conversation (core messages MessageQuarantine): every
     *   action that writes or leaves the phone asks, whatever Safety cards says; one that would send something to anyone
     *   but that message's sender also carries [AskWhy.MESSAGE] (a tap). Not a switch.
     */
    fun decide(tool: ToolName, locked: Boolean, settings: RobotSettings, why: Set<AskWhy> = emptySet(), content: Boolean = false,
        asked: Boolean = false, remote: Boolean = false, online: Boolean = tool.online, trusted: Boolean = false, messages: Boolean = false): GateDecision {
        val level = settings.approval(tool)
        return when {
            level == Approval.OFF -> GateDecision.DISABLED
            locked && tool !in settings.lockedTools -> GateDecision.NEEDS_UNLOCK
            tool == ToolName.ASK_CLAUDE -> GateDecision.CONFIRM
            // 0.7: a memory the model proposes without your asking, or in a conversation with content, is a card, never silent.
            tool == ToolName.REMEMBER && (!asked || content) -> GateDecision.CONFIRM
            remote && settings.claudeActionCards -> GateDecision.CONFIRM
            // What a read tool finds for a helper goes to it: "Share with …" unless personal content may go by itself (for
            // every helper, or this one: [trusted], "Trusted with personal content"). So does what an online lookup finds (the
            // nearest pharmacy says where you are).
            remote && (tool.reads || online) && !settings.personalToHelpers && !trusted -> GateDecision.CONFIRM
            level == Approval.ASK -> GateDecision.CONFIRM
            asks(why, settings) -> GateDecision.CONFIRM
            messages && !(tool.reads && !online) -> GateDecision.CONFIRM
            content && !(tool.reads && !online) && contentAsks(settings.safetyCards, asked) -> GateDecision.CONFIRM
            else -> GateDecision.RUN
        }
    }

    /** One of [why] needs a card with these settings. */
    fun asks(why: Set<AskWhy>, s: RobotSettings): Boolean =
        AskWhy.PICK in why || AskWhy.ONLINE in why || AskWhy.MESSAGE in why || (AskWhy.WHO in why && s.whoCards) || (AskWhy.LEARNED in why && s.learnedCards) || (AskWhy.DELETE in why && s.deleteCards)

    /** Content in the conversation makes this action ask first, under [mode] ([asked]: the person asked for it this turn). */
    fun contentAsks(mode: SafetyCards, asked: Boolean): Boolean = when (mode) {
        SafetyCards.AFTER_READING -> true
        SafetyCards.UNASKED -> !asked
        SafetyCards.UNSURE_ONLY -> false
    }
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
