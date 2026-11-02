// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import app.heyrobot.core.RLog
import app.heyrobot.core.readOrNull
import app.heyrobot.core.writeAtomic
import java.io.File
import java.io.IOException

/**
 * state.json as one process knows it (app LockStore holds one, and takes the file lock around [edit]). Only :lock edits.
 * A read that fails is never taken for "no locks" (F22): the state this process last read or wrote stands in for it, and
 * a process that knows none writes nothing ([Unreadable]). Text that can't be understood won't get better: with no state to
 * go by it is kept aside as state.json.bad and :lock starts over, so Time of day locks come back from locks.json at once.
 */
class LockStateFile(val file: File, private val mapsApp: () -> String? = { null }) {
    /** state.json is there, can't be read now, and this process has no state to go by: nothing was written. */
    class Unreadable : IOException("state.json can't be read") // l10n: not text (a log's error code)

    /** A state :lock worked out but couldn't write (storage full). It is newer than the file; every edit writes it again. */
    @Volatile private var unsaved: LockState? = null

    /** The newest state this process read or wrote. */
    @Volatile private var known: LockState? = null

    val unsavedState: Boolean get() = unsaved != null

    /** What the file says, as any process sees it. Null: it's there and can't be read. */
    fun stateOrNull(): LockState? = unsaved ?: if (!file.exists()) LockState() else LockCodec.decodeState(readOrNull(file))

    /** [stateOrNull], or the last this process knew, or for a state nobody can read, one that asks for the code rather than lets you through. */
    fun state(): LockState = stateOrNull() ?: known ?: LockState(codeSet = true)

    /**
     * Reads, changes and writes. When only the write fails (storage full), the new state is still returned and kept as
     * [unsaved], so locks still start, end and come back in :lock. Throws [Unreadable] rather than work from an empty state.
     */
    fun edit(change: (LockState) -> LockState): LockState {
        val wasUnsaved = unsaved
        var mustWrite = wasUnsaved != null
        val now = when {
            wasUnsaved != null -> wasUnsaved
            !file.exists() -> LockState()
            else -> {
                val text = readOrNull(file)
                LockCodec.decodeState(text) ?: run {
                    mustWrite = true
                    known ?: if (text == null) throw Unreadable() else startOver()
                }
            }
        }
        val next = LockSchedule.mapsKept(now, change(now), mapsApp)
        if (next != now || mustWrite) {
            try {
                writeAtomic(file, LockCodec.encodeState(next))
                unsaved = null
            } catch (e: IOException) {
                RLog.w("lock_state_write", e)
                unsaved = next
            }
        }
        known = next
        return next
    }

    /** The text can't be understood and nothing here says what it was: kept aside. The code pad puts codeSet right when it opens. */
    private fun startOver(): LockState {
        RLog.w("lock_state_damaged", Unreadable())
        file.renameTo(File(file.parentFile, file.name + ".bad"))
        return LockState(codeSet = true)
    }
}
