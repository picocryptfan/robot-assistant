// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.BriefLock
import app.heyrobot.core.RoutineSettings
import app.heyrobot.core.SpokenPart
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesLocked

/**
 * Pure. 1.1, Phone lock (docs/plans/phone-lock-design.md §6): during a lock for someone else, what the panel may do keeps
 * to the lock screen's defaults, because the rows that widen it need no code. Safety & privacy's lock-screen actions are
 * narrowed in VoiceHub.settings; these two are the others of that kind: Settings › Messages' "On the lock screen" reads as
 * Unlock first, and Memory's "Use on the lock screen" as off. The files keep what you chose: only the turns see the
 * narrowed value, and only while that lock is on. Memory's sealing key (MemoryVault) still follows the file.
 * 1.4 Lists (lists-plan.md §5): both of Settings › Lists' lock switches read as off, "Add to lists while locked" and "Hear
 * and tick on the lock screen", so nothing is added, heard or ticked ("Robot can't change your lists right now."); the
 * lists' key (ListsVault) still follows the file.
 */
object SomeoneElseLock {
    /**
     * F6: a routine during someone else's lock reads as on the lock screen, at "Time and weather" at most. Its own and the
     * routine's wait for the unlock don't hold its actions, since that unlock isn't yours; what would wait is dropped, not
     * kept, by the runner and the panel.
     */
    fun routines(rs: RoutineSettings, someoneElse: Boolean): RoutineSettings =
        if (someoneElse) rs.copy(briefLock = if (rs.briefLock == BriefLock.EVERYTHING) BriefLock.TIME_WEATHER else rs.briefLock, runLocked = true) else rs

    /** F6: what may be said during someone else's lock: nothing personal, whatever the routine's step. */
    fun spoken(parts: List<SpokenPart>, someoneElse: Boolean): List<SpokenPart> = if (someoneElse) parts.filter { !it.personal } else parts

    fun messages(s: MessageSettings, someoneElse: Boolean): MessageSettings =
        if (someoneElse && s.locked != MessagesLocked.OFF) s.copy(locked = MessagesLocked.OFF) else s

    fun memory(s: MemorySettings, someoneElse: Boolean): MemorySettings =
        if (someoneElse && s.onLockScreen) s.copy(onLockScreen = false) else s

    fun lists(s: ListsSettings, someoneElse: Boolean): ListsSettings =
        if (someoneElse && (s.addLocked || s.lockScreen)) s.copy(addLocked = false, lockScreen = false) else s
}
