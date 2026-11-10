// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

/**
 * Pure. fix-covered-partial M1: the line a refused Yes leaves on the car screen's card, for that card only. It goes with any
 * answer, when another card (or none) comes, and after [ms]; it never shows with another card's words.
 */
class CarCoveredLine(private val ms: Long) {
    private var card: String? = null
    private var res = 0
    private var until = 0L

    /** Yes on [card] was refused at [now]; [res] is the line's string. */
    fun refused(card: String?, res: Int, now: Long) {
        this.card = card
        this.res = res
        until = now + ms
    }

    fun answered() {
        card = null
    }

    /** The line for the card shown now ([card]), or null; another card clears it for good. */
    fun line(card: String?, now: Long): Int? {
        if (card == null || card != this.card) {
            this.card = null
            return null
        }
        return res.takeIf { now < until }
    }
}
