// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.link

import app.heyrobot.core.LogCode

// Pure. 1.5, Robot to Robot's voice-log codes (robot-to-robot-plan.md D13; job L7): VoiceEvent.LINK_* (ids 300 to 319), written by
// :voice and the main app, codes and counts only: never a name, a pairing, a request's words, a time or an address (the
// marker test, VoiceLogTest). :link keeps its own codes-only log (LinkLog).

/** What you sent (LINK_OUT's a). */
enum class LinkOutCode(override val id: Int, override val title: String) : LogCode {
    FREE_BUSY(1, "free/busy asked"), PLAN(2, "a plan proposed"), CANCEL(3, "a plan cancelled"), // l10n: not text (voice log codes)
    REFUSED(4, "refused (locked or off)"), NOT_READ(5, "their Robot can't read it"), FAILED(6, "couldn't queue"), // l10n: not text (voice log codes)
    ETA(7, "an ETA told"), REMINDER(8, "a reminder offered"), QUESTION(9, "a question asked"), ANSWER(10, "a question answered"), // l10n: not text (voice log codes)
    CONTACT(11, "a contact card offered"), // l10n: not text (voice log codes)
    ;
}

/** Your answer to a card (LINK_CARD's a; b is the request's type code). */
enum class LinkCardCode(override val id: Int, override val title: String) : LogCode {
    YES(1, "yes"), DECLINE(2, "declined"), SECOND(3, "yes at its second time"), THIRD(4, "yes at its third time"), // l10n: not text (voice log codes)
    TURN_ON(5, "proposal turned on"), NOT_NOW(6, "proposal: not now"), FAILED(7, "couldn't be answered"), // l10n: not text (voice log codes)
    ;
}

/** Refused because the phone was locked or under a phone lock (LINK_LOCKED's a). */
enum class LinkLockedCode(override val id: Int, override val title: String) : LogCode {
    ASK(1, "a request, before its card"), SEND(2, "a request, at Send"), CARD(3, "a card's answer"), // l10n: not text (voice log codes)
    ;
}

/** A pairing's news (LINK_PAIR's a). */
enum class LinkPairCode(override val id: Int, override val title: String) : LogCode {
    PAIRED(1, "paired"), UNPAIRED(2, "unpaired"), MOVED(3, "their Robot moved"), // l10n: not text (voice log codes)
    ;
}

/** A pairing's mode (LINK_MODE's a). */
enum class LinkModeCode(override val id: Int, override val title: String) : LogCode {
    RAISED(1, "raised to Anonymous"), LOWER_ASKED(2, "asked to switch to Private"), LOWERED(3, "switched to Private"), // l10n: not text (voice log codes)
    ;
}

/** A limit reached (LINK_LIMIT's a). */
enum class LinkLimitCode(override val id: Int, override val title: String) : LogCode {
    BUSY(1, "too many requests: paused"), GAVE_UP(2, "couldn't reach their Robot"), // l10n: not text (voice log codes)
    ;
}

/** What came in (LINK_IN's a; b how many). */
enum class LinkInCode(override val id: Int, override val title: String) : LogCode {
    CARDS(1, "cards waiting"), REPLIES(2, "answers to your requests"), // l10n: not text (voice log codes)
    HANDLED(3, "handled on its own, said"), TRANSLATED(4, "their words translated"), // l10n: not text (voice log codes)
    ;
}
