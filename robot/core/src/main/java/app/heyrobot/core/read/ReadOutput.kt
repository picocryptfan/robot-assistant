// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.RoutineRoute
import app.heyrobot.core.output.GuardSettings
import app.heyrobot.core.output.OutputContext
import app.heyrobot.core.output.OutputDecision
import app.heyrobot.core.output.OutputGuard
import app.heyrobot.core.output.PhoneState
import app.heyrobot.core.output.SessionKind
import app.heyrobot.core.output.Trust

// Pure. Where a reading may play (docs/plans/read-it-to-me-plan.md §5): every piece through OutputGuard as personal, by
// where Robot's voice goes now, and never onto the phone's speaker when the reading started elsewhere (a headset gone, a
// car left). The voice process's reading session (app ReadingVoice) asks [mayPlay] before each piece.

/** How Android's audio focus changed, for the reading (AudioManager's AUDIOFOCUS_* values). */
enum class ReadFocus { LOST, LOST_SHORT, GAINED, OTHER }

object ReadOutput {
    /** The OutputGuard context for [route]: the car while car mode has one, the phone itself, else a headset or speaker. */
    fun context(route: RoutineRoute, phone: PhoneState): OutputContext = when {
        route.car != null -> OutputContext(SessionKind.CAR, route.car, Trust.NOT_PRIVATE, phone, route.car, carTrusted = route.carTrusted)
        route.onPhone -> OutputContext(SessionKind.PHONE, null, Trust.NOT_PRIVATE, phone)
        else -> OutputContext(SessionKind.HEADSET, route.routed, route.trust, phone, route.routed)
    }

    /**
     * May [piece] play now? [startedOnPhone]: the reading began on the phone's own speaker. One that began in a headset or
     * the car never falls back to the phone; any decision but Speak (held, asked about) stops it with its place kept.
     */
    fun mayPlay(piece: ReadPiece, route: RoutineRoute, phone: PhoneState, startedOnPhone: Boolean, s: GuardSettings): Boolean {
        if (!startedOnPhone && route.onPhone && route.car == null) return false
        return OutputGuard.decide(ReadGate.utterance(piece), context(route, phone), s) is OutputDecision.Speak
    }

    /** AudioManager's focus change as the reading sees it: a ducking loss pauses too (speech isn't heard under a duck). */
    fun focus(change: Int): ReadFocus = when (change) {
        FOCUS_LOSS -> ReadFocus.LOST
        FOCUS_LOSS_TRANSIENT, FOCUS_LOSS_DUCK -> ReadFocus.LOST_SHORT
        FOCUS_GAIN -> ReadFocus.GAINED
        else -> ReadFocus.OTHER
    }

    // AudioManager's values (android.media.AudioManager), kept here so this stays pure.
    const val FOCUS_GAIN = 1
    const val FOCUS_LOSS = -1
    const val FOCUS_LOSS_TRANSIENT = -2
    const val FOCUS_LOSS_DUCK = -3
}
