// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import java.time.Duration

// Pure. Ending a lock early: Off (no way), After a wait (End early starts it; at its end, End the lock now
// works for [CONFIRM], then the wait starts over) or With the code. Locks for someone else always need the code. A lock
// you started by voice for yourself always gets the wait, 10 minutes, whatever End early says (open question 12's
// default), so a misheard sentence can't lock you out. With several locks on, the strictest rule among them decides,
// and ending ends every one of them.

/** What the cover shows for ending early, and what its button does. */
sealed class EarlyView {
    /** "This lock can't be ended early." No button. */
    data object Off : EarlyView()
    /** End early opens the code pad. */
    data object Code : EarlyView()
    /** End early starts the wait (some lock has none running yet). */
    data object CanStartWait : EarlyView()
    /** "You can end this lock at 7:32." [until]: wall-clock ms. */
    data class Waiting(val until: Long) : EarlyView()
    /** End the lock now works until [until]. */
    data class Ready(val until: Long) : EarlyView()
}

object EndEarlyRules {
    /** How long End the lock now works once the wait is over. */
    val CONFIRM: Duration = Duration.ofMinutes(5)
    /** A lock started by voice for yourself: its wait, whatever End early says. */
    const val VOICE_WAIT_MIN = 10

    /** The rule for [a] under [s]. */
    fun rule(s: LockSettings, a: ActiveLock): EndEarly = when {
        s.forWho == LockFor.SOMEONE_ELSE -> EndEarly.CODE
        a.voice -> EndEarly.WAIT
        else -> s.endEarly
    }

    /** The wait for [a], in minutes. */
    fun waitMinutes(s: LockSettings, a: ActiveLock): Int = if (a.voice && s.forWho == LockFor.ME) VOICE_WAIT_MIN else s.waitMin

    /** The strictest of [rules]: Off, then With the code, then After a wait. Null: none. */
    fun strictest(rules: Collection<EndEarly>): EndEarly? = when {
        rules.isEmpty() -> null
        EndEarly.OFF in rules -> EndEarly.OFF
        EndEarly.CODE in rules -> EndEarly.CODE
        else -> EndEarly.WAIT
    }

    /** The rule that applies to the active locks together. */
    fun rule(s: LockSettings, active: List<ActiveLock>): EndEarly? = strictest(active.map { rule(s, it) })

    /** One lock's wait at [now]: null when none is running or its confirm window passed (it starts over). */
    private fun phase(s: LockSettings, a: ActiveLock, now: Long): EarlyView? {
        val from = a.waitFrom ?: return null
        val ready = from + waitMinutes(s, a) * 60_000L
        return when {
            now < from -> null // The clock moved back past the wait's start: it starts over.
            now < ready -> EarlyView.Waiting(ready)
            now < ready + CONFIRM.toMillis() -> EarlyView.Ready(ready + CONFIRM.toMillis())
            else -> null
        }
    }

    /** What the cover shows now. */
    fun view(s: LockSettings, active: List<ActiveLock>, now: Long): EarlyView = when (rule(s, active)) {
        null, EndEarly.OFF -> EarlyView.Off
        EndEarly.CODE -> EarlyView.Code
        EndEarly.WAIT -> {
            val phases = active.map { phase(s, it, now) }
            when {
                phases.any { it == null } -> EarlyView.CanStartWait
                phases.any { it is EarlyView.Waiting } -> EarlyView.Waiting(phases.filterIsInstance<EarlyView.Waiting>().maxOf { it.until })
                else -> EarlyView.Ready(phases.filterIsInstance<EarlyView.Ready>().minOf { it.until })
            }
        }
    }

    /** End early under a wait: every active lock without a running wait starts one at [now]. */
    fun startWait(s: LockSettings, state: LockState, now: Long): LockState =
        state.copy(active = state.active.map { if (phase(s, it, now) == null) it.copy(waitFrom = now) else it })

    /** End the lock now may end the locks at [now] without the code. */
    fun mayEndNow(s: LockSettings, active: List<ActiveLock>, now: Long): Boolean = view(s, active, now) is EarlyView.Ready
}
