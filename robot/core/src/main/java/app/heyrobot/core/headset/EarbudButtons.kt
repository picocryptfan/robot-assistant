// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.headset

// Pure. Earbud buttons while Robot speaks (docs/plans/earbud-buttons-plan.md; the headset design's §3.7 rule 3 as the
// user changed it on 7 Nov 2026: Robot takes the media buttons only while she speaks, and gives them back when she stops).
//
// - Taken when her voice starts (with "Earbud buttons while Robot talks" on, HeadsetSettings.earbudButtons), given back
//   once it has been quiet for [EarbudButtons.QUIET_MS] (the gap between two parts of a brief is shorter), and at once
//   when anything stops it, on an error, and when the voice service shuts down. [EarbudButtons.tick] re-reads whether
//   the voice really plays, so a missed signal can't keep the buttons.
// - One tap stops her: it waits [EarbudButtons.DOUBLE_TAP_MS] for a second, and two taps skip to the next part. The
//   earbud's own "next" key skips; pause and stop keys stop at once. Earbuds that send no double tap still stop.
// - A long press never reaches her session: Android turns it into the voice button, which opens Robot as before.
//   One that does arrive (a repeat, or the long-press flag) is ignored.

object EarbudButtons {
    const val DOUBLE_TAP_MS = 400L
    const val QUIET_MS = 600L
    private const val KEY_STOP = 86

    enum class Action { STOP, SKIP }

    /** How an answer ended, other than going quiet: each gives the buttons back at once. */
    enum class End { STOPPED, ERROR, KILLED }

    /** [held]: Robot holds her session; [quietSince]: her voice went quiet then; [tapAt]: a tap waits for a second. */
    data class State(val held: Boolean = false, val quietSince: Long? = null, val tapAt: Long? = null)

    /** [take]: hold the session now; [giveBack]: release it now; [action]: what Robot does; [checkAt]: call [tick] then. */
    data class Step(val state: State, val take: Boolean = false, val giveBack: Boolean = false, val action: Action? = null, val checkAt: Long? = null)

    /** Robot's voice started ([on]) or went quiet, at [at]. [enabled]: the setting. */
    fun speaking(s: State, on: Boolean, at: Long, enabled: Boolean): Step = when {
        on && !enabled -> Step(State(), giveBack = s.held)
        on -> s.copy(held = true, quietSince = null).let { Step(it, take = !s.held, checkAt = next(it, at)) }
        !s.held -> Step(s)
        else -> (if (s.quietSince == null) s.copy(quietSince = at) else s).let { Step(it, checkAt = next(it, at)) }
    }

    fun ended(s: State, @Suppress("UNUSED_PARAMETER") why: End): Step = Step(State(), giveBack = s.held)

    /** A check at [at]; [speakingNow]: the voice really plays now. */
    fun tick(s: State, at: Long, speakingNow: Boolean): Step {
        if (!s.held) return Step(s)
        if (s.tapAt != null && at - s.tapAt >= DOUBLE_TAP_MS) return Step(State(), giveBack = true, action = Action.STOP)
        val st = if (speakingNow) s.copy(quietSince = null) else if (s.quietSince == null) s.copy(quietSince = at) else s
        val quiet = st.quietSince
        if (quiet != null && st.tapAt == null && at - quiet >= QUIET_MS) return Step(State(), giveBack = true)
        return Step(st, checkAt = next(st, at))
    }

    /** A media key, or a controller's play, pause, stop or next (as its key code). [down]: pressed; [long]: held. */
    fun key(s: State, keyCode: Int, down: Boolean, long: Boolean, at: Long): Step {
        if (!s.held || !down || long) return Step(s)
        return when (keyCode) {
            MediaKey.NEXT.keyCode -> s.copy(tapAt = null).let { Step(it, action = Action.SKIP, checkAt = next(it, at)) }
            MediaKey.PAUSE.keyCode, KEY_STOP -> Step(State(), giveBack = true, action = Action.STOP)
            MediaKey.PLAY_PAUSE.keyCode, MediaKey.HEADSETHOOK.keyCode, MediaKey.PLAY.keyCode ->
                if (s.tapAt != null && at - s.tapAt <= DOUBLE_TAP_MS) s.copy(tapAt = null).let { Step(it, action = Action.SKIP, checkAt = next(it, at)) }
                else s.copy(tapAt = at).let { Step(it, checkAt = next(it, at)) }
            else -> Step(s)
        }
    }

    private fun next(s: State, at: Long): Long =
        listOfNotNull(s.tapAt?.plus(DOUBLE_TAP_MS), s.quietSince?.plus(QUIET_MS), at + QUIET_MS).min()
}
