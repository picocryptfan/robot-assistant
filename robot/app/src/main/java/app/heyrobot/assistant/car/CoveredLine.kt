// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.R
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings

/**
 * Pure. fix-covered-partial-b: what Robot says, and the car card shows, when car mode's Yes was refused for another app's
 * window over the screen. [VOICE]: a spoken yes counts there (car mode's own gate); [NO_ONLY]: it needs a tap, and a no
 * still works; [TAP]: nothing listens after the line, so it needs a tap, with no "say no".
 */
enum class CoveredLine(val res: Int) {
    VOICE(R.string.car_covered_voice), NO_ONLY(R.string.car_covered_move), TAP(R.string.car_covered_tap);

    val phrase: Phrase get() = when (this) {
        VOICE -> TurnStrings.coveredSay()
        NO_ONLY -> TurnStrings.coveredMove()
        TAP -> TurnStrings.coveredTap()
    }

    companion object {
        fun of(hears: Boolean, voiceYes: Boolean): CoveredLine = if (!hears) TAP else if (voiceYes) VOICE else NO_ONLY

        /** A reply card: PanelMessages.answerCard's spoken-yes gate with the car screen up (fix-car-long-card-b), nothing looser. */
        fun reply(hears: Boolean, trusted: Boolean, voiceCards: Boolean, voiceYesCounts: Boolean, readOut: String?, sends: String): CoveredLine =
            of(hears, voiceYesCounts && voiceCards && CarCardVoice.replyYes(trusted, readOut, sends))
    }
}
