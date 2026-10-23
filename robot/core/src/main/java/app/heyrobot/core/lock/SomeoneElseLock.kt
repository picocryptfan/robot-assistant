// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.messages.MessageSettings
import app.heyrobot.core.messages.MessagesLocked

/**
 * Pure. 1.1, Phone lock (docs/plans/phone-lock-design.md §6): during a lock for someone else, what the panel may do keeps
 * to the lock screen's defaults, because the rows that widen it need no code. Safety & privacy's lock-screen actions are
 * narrowed in VoiceHub.settings; these two are the others of that kind: Settings › Messages' "On the lock screen" reads as
 * Unlock first, and Memory's "Use on the lock screen" as off. The files keep what you chose: only the turns see the
 * narrowed value, and only while that lock is on. Memory's sealing key (MemoryVault) still follows the file.
 */
object SomeoneElseLock {
    fun messages(s: MessageSettings, someoneElse: Boolean): MessageSettings =
        if (someoneElse && s.locked != MessagesLocked.OFF) s.copy(locked = MessagesLocked.OFF) else s

    fun memory(s: MemorySettings, someoneElse: Boolean): MemorySettings =
        if (someoneElse && s.onLockScreen) s.copy(onLockScreen = false) else s
}
