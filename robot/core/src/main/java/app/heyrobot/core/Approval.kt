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
    /** Lock-screen rules (spec item 1): only questions, timers and alarms while the keyguard is locked. */
    val ALLOWED_WHEN_LOCKED: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM)

    fun decide(tool: ToolName, locked: Boolean, settings: RobotSettings): GateDecision {
        val level = settings.approval(tool)
        return when {
            level == Approval.OFF -> GateDecision.DISABLED
            locked && tool !in ALLOWED_WHEN_LOCKED -> GateDecision.NEEDS_UNLOCK
            level == Approval.AUTO -> GateDecision.RUN
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
    INVALID("Couldn't understand the request");

    /** The tool response sent back to the model ([detail] is a short, fixed-vocabulary explanation). */
    fun toolResponse(detail: String?): Map<String, Any?> = buildMap {
        put("status", when (this@ActionStatus) {
            PENDING -> "pending"
            DONE -> "done"
            DECLINED -> "cancelled_by_user"
            NEEDS_UNLOCK -> "phone_locked_user_must_unlock"
            DISABLED -> "turned_off_in_settings"
            FAILED -> "failed"
            INVALID -> "invalid_arguments"
        })
        if (!detail.isNullOrBlank()) put("detail", detail)
    }
}
