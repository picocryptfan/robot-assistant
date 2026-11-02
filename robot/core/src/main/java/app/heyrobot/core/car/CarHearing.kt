// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.car

import app.heyrobot.core.ListenPause
import app.heyrobot.core.WakeSensitivity

/** Settings › Car mode › "Hear me in the car". */
enum class CarHearMe { NORMAL, MORE }

/**
 * What car mode changes about how you're heard, while it's on (pure; docs/pm/handoff-car-mode-drive-fixes.md items 5 and
 * 6). Road noise sits under your voice: with [CarHearMe.MORE] the wake phrase is at High unless you chose Low, and the
 * question's speech detector takes a softer word for speech. The pause is at least Long, and at least what you chose for the car.
 */
object CarHearing {
    /** The speech detectors everywhere else. */
    const val SPEECH_THRESHOLD = 0.5f

    /**
     * A question in the car with [CarHearMe.MORE], from the desk set (docs/ops/reports/listen-wait.md): fewer cut-offs there and
     * none that never end. Outside the car it held more questions open in light noise, and the wake gate gained nothing, so
     * both stay at [SPEECH_THRESHOLD].
     */
    const val CAR_SPEECH_THRESHOLD = 0.35f

    /** Settings › Car mode › "Wait before answering": never shorter than Long. */
    val PAUSE_CHOICES = listOf(ListenPause.LONG, ListenPause.VERY_LONG)

    fun more(carOn: Boolean, car: CarSettings): Boolean = carOn && car.hearMe == CarHearMe.MORE

    fun speechThreshold(more: Boolean): Float = if (more) CAR_SPEECH_THRESHOLD else SPEECH_THRESHOLD

    fun sensitivity(chosen: WakeSensitivity, more: Boolean): WakeSensitivity =
        if (more && chosen == WakeSensitivity.MEDIUM) WakeSensitivity.HIGH else chosen

    fun pauseMs(yours: ListenPause, carOn: Boolean, car: CarSettings): Int =
        if (carOn) maxOf(yours.ms, car.waitPause.ms, ListenPause.LONG.ms) else yours.ms
}
