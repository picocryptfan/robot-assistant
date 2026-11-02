// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lists

import app.heyrobot.core.LogCode

// Pure. Robot Lists' voice-log codes (lists-plan.md §8.4, D14; job LS3): the small stable numbers events 280 to 289 carry.
// Codes and counts only: never a list's name or an item's words. Never renumber an entry.

/** What an add did (LIST_ADD's a): b = items in it, c = 1 when said while the phone was locked. */
enum class ListAddCode(override val id: Int, override val title: String) : LogCode {
    ADDED(1, "added"), ALREADY(2, "already there"), PUT_BACK(3, "ticked ones put back"), QTY(4, "a quantity changed"), // l10n: not text (voice log codes)
    NEW_LIST(5, "added to a new list"), REFUSED(6, "refused"), NO_LIST(7, "no such list"), JOURNAL(8, "waiting for the unlock"), // l10n: not text (voice log codes)
    ;
}

/** A read or a check (LIST_READ's a): b = open items read, or lists named. */
enum class ListReadCode(override val id: Int, override val title: String) : LogCode {
    READ(1, "a list read"), CHECK(2, "an item checked"), MY_LISTS(3, "your lists named"), NO_LIST(4, "no such list"), // l10n: not text (voice log codes)
    ;
}

/** A tick or a put back by voice (LIST_TICK's a): b = items. */
enum class ListTickCode(override val id: Int, override val title: String) : LogCode {
    TICKED(1, "ticked"), PUT_BACK(2, "put back"), ALREADY(3, "already so"), NOT_FOUND(4, "not on the list"), WHICH(5, "asked which"), // l10n: not text (voice log codes)
    ;
}

/** A change to a whole list, or an Undo (LIST_EDIT's a): b = items. */
enum class ListEditCode(override val id: Int, override val title: String) : LogCode {
    CLEARED(1, "ticked items cleared"), EMPTY_CARD(2, "an Empty card shown"), EMPTIED(3, "emptied, by a tap"), KEPT(4, "Keep tapped"), // l10n: not text (voice log codes)
    UNDONE(5, "undone"), NOT_UNDONE(6, "couldn't be undone"), // l10n: not text (voice log codes)
    ;
}

/** A list made by voice (LIST_MAKE's a). */
enum class ListMakeCode(override val id: Int, override val title: String) : LogCode {
    MADE(1, "made"), EXISTS(2, "already there"), REFUSED(3, "refused"), // l10n: not text (voice log codes)
    ;
}

/** What the lock rules of §5 said (LIST_LOCKED's a): b = items. */
enum class ListLockedCode(override val id: Int, override val title: String) : LogCode {
    UNLOCK_TO_HEAR(1, "unlock to hear"), UNLOCK_TO_CHANGE(2, "unlock to change"), SOMEONE_ELSE(3, "a phone lock for someone else"), // l10n: not text (voice log codes)
    LOCK_SCREEN(4, "on the lock screen, with Hear and tick on"), NOT_NOW(5, "the lists couldn't be opened"), // l10n: not text (voice log codes)
    ;
}

/** LS4: the model's split of a run of your words (LIST_SPLIT's a): b = items it gave. */
enum class ListSplitCode(override val id: Int, override val title: String) : LogCode {
    SPLIT(1, "split into items"), ONE(2, "one item, the model said"), REFUSED(3, "refused: not your words"), // l10n: not text (voice log codes)
    NO_ANSWER(4, "no answer: no model, busy or too slow"), // l10n: not text (voice log codes)
    ;
}
