// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

/**
 * Phone lock as the processes other than :lock see it, from state.json and locks.json (app PhoneLockNow). A lock counts only
 * while Phone lock is on: turning it off ends every lock, before :lock writes. A file that can't be read is never taken for
 * "no lock" (F22).
 *
 * [exact]: both files were read, so this holds until either changes. [state] null: state.json can't be read. Then a lock may
 * be on: [on] answers yes, though only for [CAP][LockSchedule.CAP] from that file's last write, since no lock it could hold
 * outlasts that, and :lock heals the file at its next refresh. Locks.json unreadable with state.json read: state.json's locks
 * count (:lock holds them), for someone else as far as anyone can tell, and leave nothing open.
 */
class LockView private constructor(val exact: Boolean, val state: LockState?, private val active: List<ActiveLock>, private val heldUntil: Long,
    val someoneElse: Boolean, val apps: Set<String>, val people: Set<String>, val maps: Boolean, val file: LockFile?) {

    /** A lock is on at [c]: decided as :lock decides ([LockSchedule.over]), so the panel and the cover never disagree when the wall clock moves (F23). */
    fun on(c: Clocks): Boolean = c.nowMs < heldUntil || active.any { !LockSchedule.over(it, c) }

    /** Car mode your remembered car started, during your own lock: nothing is covered then (LockGate's carOwnLock, as :lock works it out). */
    fun carOwn(nowMs: Long): Boolean = state?.carOn(nowMs) == true && file?.settings?.forWho == LockFor.ME

    companion object {
        /** [state] null: state.json can't be read. [stateStamp]: when it last changed. */
        fun of(state: LockState?, locks: LockFileRead, stateStamp: Long): LockView {
            val f = locks.ok
            return when {
                f != null && !f.settings.on -> LockView(true, state ?: LockState(), emptyList(), 0, false, emptySet(), emptySet(), false, null)
                state == null -> unknown(stateStamp)
                f == null -> LockView(false, state, state.active, 0, true, emptySet(), emptySet(), false, null)
                else -> LockView(true, state, state.active, 0, f.settings.forWho == LockFor.SOMEONE_ELSE, LockSchedule.allowedPackages(f, state, state.mapsApp),
                    LockSchedule.allowedPeople(f, state), LockSchedule.mapsAllowed(f, state), f)
            }
        }

        /** Nothing could be read. */
        fun unknown(stateStamp: Long) = LockView(false, null, emptyList(), if (stateStamp > 0) stateStamp + LockSchedule.CAP.toMillis() else 0, true,
            emptySet(), emptySet(), false, null)
    }
}
