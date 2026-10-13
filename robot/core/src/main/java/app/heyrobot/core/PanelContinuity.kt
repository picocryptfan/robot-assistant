package app.heyrobot.core

/**
 * What a panel show does with the conversation already in memory (1.0 audit: ipc-2, physical-locked-conversation-carryover).
 * The lock-screen rule comes first and has no exception, not even the camera's return: on the lock screen the panel shows
 * only a conversation that began there and has never been used unlocked, and only within [stale]'s five minutes.
 *
 * The other way round (1.0): anyone can talk to a locked phone, so a conversation begun on the lock screen never steers
 * your unlocked one with its turns as if you had said them. The first unlocked show after it starts a new conversation,
 * unless that show is the continuation it asked for itself: its last reply asked you to unlock ("Unlock first", "Unlock to
 * do this"; [unlockAsked]) at most [UNLOCK_WINDOW_MS] before. Then, and when you unlock with the panel still open and ask
 * there ([quotesNow]), its turns stay, marked as said while the phone was locked (ChatTurn.locked): shown under "Said
 * while the phone was locked", replayed to the model as quoted information ([Quoted.LOCKED]), and the conversation is
 * content (TurnRules.untrusted), where any action you didn't ask for in your own words needs its card (ApprovalGate).
 */
object PanelContinuity {
    /** An "Unlock first" reply waits this long for you to unlock and open Robot again. */
    const val UNLOCK_WINDOW_MS = 2 * 60_000L

    /** [CONTINUE_QUOTED]: continue, with the turns said while the phone was locked quoted (ChatTurn.locked). */
    enum class Open { LOAD, NEW_LOCKED, NEW, CONTINUE, CONTINUE_QUOTED }

    /**
     * [startedLocked]: the conversation began on the lock screen and nothing has been asked in it unlocked since
     * ([stillLocked]). [returning]: the camera or photo picker the panel opened is handing a picture back. [load]: Robot's
     * app asked to continue a saved conversation. [unlockAsked]: its last reply asked you to unlock, within the window
     * ([unlockAsked] of its turns).
     */
    fun open(locked: Boolean, startedLocked: Boolean, stale: Boolean, empty: Boolean, load: Boolean, returning: Boolean,
        unlockAsked: Boolean = false): Open = when {
        // Never show an earlier conversation on the lock screen.
        locked && (!startedLocked || stale) -> Open.NEW_LOCKED
        // A picture the panel itself asked for, unlocked in the meantime: the camera only opens unlocked.
        returning -> if (!locked && startedLocked) Open.CONTINUE_QUOTED else Open.CONTINUE
        load && !locked -> Open.LOAD
        stale || empty -> Open.NEW
        // Never your unlocked conversation with the lock screen's turns in it, unless they asked for this unlock.
        !locked && startedLocked -> if (unlockAsked) Open.CONTINUE_QUOTED else Open.NEW
        else -> Open.CONTINUE
    }

    /**
     * Whether the conversation still counts as begun on the lock screen once it is shown or used with [lockedNow]: a
     * conversation you continued unlocked (your calendar, a contact, a memory answer) never goes back to it.
     */
    fun stillLocked(startedLocked: Boolean, lockedNow: Boolean): Boolean = startedLocked && lockedNow

    /** A question asked unlocked in the panel that is still open since the lock screen: the turns before it are quoted. */
    fun quotesNow(startedLocked: Boolean, lockedNow: Boolean, empty: Boolean): Boolean = startedLocked && !lockedNow && !empty

    /** The conversation's last turn is a reply that asked you to unlock ([asksUnlock]), at most [UNLOCK_WINDOW_MS] before [now]. */
    fun unlockAsked(turns: List<ChatTurn>, now: Long): Boolean {
        val last = turns.lastOrNull() ?: return false
        return last.role == TurnRole.ASSISTANT && asksUnlock(last) && now - last.time in 0..UNLOCK_WINDOW_MS
    }

    /**
     * A reply that asked you to unlock: an action the lock refused ("Unlock to do this"), or a sentence of it that begins
     * with "Unlock" or « Déverrouille » ("Unlock first: …", "Unlock your phone, then ask again.", "Unlock to hear your day.").
     */
    fun asksUnlock(reply: ChatTurn): Boolean =
        reply.actions.any { it.status == ActionStatus.NEEDS_UNLOCK } || UNLOCK.containsMatchIn(reply.text)

    private val UNLOCK = Regex("""(^|[.!?:…]\s+|\n\s*)(Unlock|Déverrouille)\b""")

    /** [turns] marked as said while the phone was locked (ChatTurn.locked). */
    fun quoted(turns: List<ChatTurn>): List<ChatTurn> = turns.map { if (it.locked) it else it.copy(locked = true) }

    /**
     * What History keeps of a conversation's [turns]: one begun on the lock screen and not yet used unlocked
     * ([startedLocked]) is saved marked as said while the phone was locked, so continuing it from History later (its
     * Continue) quotes those turns as the panel's own continuation does, never as your words. The panel's conversation in
     * memory stays as it is while the phone is locked.
     */
    fun stored(turns: List<ChatTurn>, startedLocked: Boolean): List<ChatTurn> = if (startedLocked) quoted(turns) else turns
}
