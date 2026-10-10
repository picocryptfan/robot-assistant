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
     * Lock-screen rules (spec item 1): only questions, timers and alarms while the keyguard is locked. Every phase 2
     * tool (texts, email, contacts, notes, calendar) needs the phone unlocked.
     */
    val ALLOWED_WHEN_LOCKED: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM)

    /**
     * [mustAsk]: "Just do it" becomes "Ask first" for this action. Set when the conversation carries screen text
     * or a picture, or a read tool's findings (content Robot did not write and the person did not type: a page, a
     * photo, a note or a calendar invite must never trigger an action without a tap), for an app name that matched
     * only loosely, and when the card offers a choice of people or numbers.
     */
    fun decide(tool: ToolName, locked: Boolean, settings: RobotSettings, mustAsk: Boolean = false): GateDecision {
        val level = settings.approval(tool)
        return when {
            level == Approval.OFF -> GateDecision.DISABLED
            locked && tool !in ALLOWED_WHEN_LOCKED -> GateDecision.NEEDS_UNLOCK
            level == Approval.AUTO && !mustAsk -> GateDecision.RUN
            else -> GateDecision.CONFIRM
        }
    }
}

/** What happened to one action; also what the model is told about it. */
enum class ActionStatus(val label: String) {
    PENDING("Waiting for you"),
    DONE("Done"),
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
}
