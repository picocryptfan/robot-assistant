// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.car

import app.heyrobot.assistant.UiAction

// Pure. A card in a turn with no panel, the car screen up (fix-car-long-card): what the car screen's pane shows and Robot
// reads out, and when a spoken yes may approve it. A yes approves only what Robot has read out whole; what it can't read
// out well takes a tap on the car screen, and nothing is ever approved by itself or on a timer.
object CarCardVoice {
    /** About 60 seconds of speech: some 150 words a minute, 6 characters a word with its space. EST. */
    const val SAY_MAX = 900

    /** The card's words as the pane shows them and Robot reads them: what it does, the reason, the words it sends, a command, the choices. */
    fun text(a: UiAction): String = (listOf(a.summary, a.detail, a.body, a.termux?.command) + a.choices)
        .filterNot { it.isNullOrBlank() }.joinToString("\n")

    /** Over about a minute aloud. */
    fun tooLong(text: String): Boolean = text.length > SAY_MAX

    /** A picture to send, a Termux command, a card that already needs a tap, or one too long to read out: a tap on the car screen only. */
    fun tapOnly(a: UiAction): Boolean = a.picture != null || a.termux != null || a.tapFor != null || tooLong(text(a))

    enum class Yes { COUNTS, NOT_YET, TAP }

    /**
     * fix-car-long-card-b: a reply card's voice yes in a car quiet turn sends [sends] only when Robot read out exactly those
     * words in full ([readOut]; null: Speak replies off, held back or cut off), the car screen is trusted, and they aren't
     * over about a minute aloud. Otherwise a tap.
     */
    fun replyYes(trusted: Boolean, readOut: String?, sends: String): Boolean = trusted && readOut == sends && !tooLong(sends)

    /**
     * A spoken yes to a card: it counts once Robot has read it out whole ([readWhole]); while Robot still reads it
     * ([reading]) it approves nothing; a [tapOnly] card, one never read out, or any while the phone isn't [trusted]
     * (nothing private said beyond 1.3.0) takes a tap.
     */
    fun yes(tapOnly: Boolean, trusted: Boolean, reading: Boolean, readWhole: Boolean): Yes = when {
        tapOnly || !trusted -> Yes.TAP
        readWhole -> Yes.COUNTS
        reading -> Yes.NOT_YET
        else -> Yes.TAP
    }
}
