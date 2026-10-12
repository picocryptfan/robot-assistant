package app.heyrobot.core

/**
 * What a panel show does with the conversation already in memory (1.0 audit: ipc-2, physical-locked-conversation-carryover).
 * The lock-screen rule comes first and has no exception, not even the camera's return: on the lock screen the panel shows
 * only a conversation that began there and has never been used unlocked, and only within [stale]'s five minutes.
 */
object PanelContinuity {
    enum class Open { LOAD, NEW_LOCKED, NEW, CONTINUE }

    /**
     * [startedLocked]: the conversation began on the lock screen and nothing has been asked in it unlocked since
     * ([stillLocked]). [returning]: the camera or photo picker the panel opened is handing a picture back. [load]: Robot's
     * app asked to continue a saved conversation.
     */
    fun open(locked: Boolean, startedLocked: Boolean, stale: Boolean, empty: Boolean, load: Boolean, returning: Boolean): Open = when {
        // Never show an earlier conversation on the lock screen.
        locked && (!startedLocked || stale) -> Open.NEW_LOCKED
        returning -> Open.CONTINUE
        load && !locked -> Open.LOAD
        stale || empty -> Open.NEW
        else -> Open.CONTINUE
    }

    /**
     * Whether the conversation still counts as begun on the lock screen once it is shown or used with [lockedNow]: a
     * conversation the phone's owner continued unlocked (their calendar, a contact, a memory answer) never goes back to it.
     */
    fun stillLocked(startedLocked: Boolean, lockedNow: Boolean): Boolean = startedLocked && lockedNow
}
