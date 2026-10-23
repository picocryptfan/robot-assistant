// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.LogCode
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseStatus

// Pure. 1.4, Robot Files' voice-log codes (files-1.4-plan.md D16; job F6): the small stable numbers events 270 to 279
// carry. Codes and counts only: never a file's name, words, fields, merchant, query or tag. Never renumber an entry.

/** How a file came in (FILE_IN's a). */
enum class FileInCode(override val id: Int, override val title: String) : LogCode {
    SHARE(1, "shared"), SCAN(2, "scanned"), PANEL(3, "\"file this\""), TRANSCRIPT(4, "a transcript saved"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(s: FileSource): FileInCode = when (s) {
            FileSource.SCAN -> SCAN
            FileSource.PANEL -> PANEL
            FileSource.TRANSCRIPT -> TRANSCRIPT
            else -> SHARE
        }
    }
}

/** What :files said (FILE_PARSE's a). */
enum class FileParseCode(override val id: Int, override val title: String) : LogCode {
    OK(1, "read"), DANGEROUS(2, "dangerous, not read"), NOT_READ(3, "a type Robot doesn't read"), TOO_LARGE(4, "too large"), // l10n: not text (voice log codes)
    MALFORMED(5, "malformed"), FAILED(6, "couldn't be decoded"), ENCRYPTED(7, "protected by a password"), OTHER(8, "refused"), // l10n: not text (voice log codes)
    CRASHED(9, "the reader stopped"), TIMED_OUT(10, "the reader took too long"), BAD_ANSWER(11, "the reader's answer broke the rules"), // l10n: not text (voice log codes)
    UNAVAILABLE(12, "the reader couldn't start"), NOT_ISOLATED(13, "the reader wasn't isolated"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(s: ParseStatus?, f: ParseFailure?): FileParseCode = when {
            f == ParseFailure.CRASHED -> CRASHED
            f == ParseFailure.TIMED_OUT -> TIMED_OUT
            f == ParseFailure.MALFORMED -> BAD_ANSWER
            f == ParseFailure.UNAVAILABLE -> UNAVAILABLE
            f == ParseFailure.NOT_ISOLATED -> NOT_ISOLATED
            s == ParseStatus.OK -> OK
            s == ParseStatus.DANGEROUS -> DANGEROUS
            s == ParseStatus.NOT_READ -> NOT_READ
            s == ParseStatus.TOO_LARGE || s == ParseStatus.OUTPUT_TOO_LARGE -> TOO_LARGE
            s == ParseStatus.MALFORMED -> MALFORMED
            s == ParseStatus.FAILED -> FAILED
            s == ParseStatus.ENCRYPTED -> ENCRYPTED
            else -> OTHER
        }
    }
}

/** How the model's reading ended (FILE_READ's a). */
enum class FileReadCode(override val id: Int, override val title: String) : LogCode {
    READ(1, "read"), WAITS(2, "waits (not counted)"), COUNTED(3, "no answer (counted)"), STOPPED(4, "stopped"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(t: TryEnd): FileReadCode = when (t) {
            TryEnd.VERDICT -> READ
            TryEnd.WAIT -> WAITS
            TryEnd.COUNTS -> COUNTED
            TryEnd.STOP -> STOPPED
        }
    }
}

/** What filing came to (FILE_FILED's a). */
enum class FileFiledCode(override val id: Int, override val title: String) : LogCode {
    FILED(1, "filed by Robot"), BY_RULE(2, "filed by your rule"), ASKED(3, "waits for you"), HELD(4, "kept in Inbox"), // l10n: not text (voice log codes)
    WAITING(5, "waits"), LOCKED(6, "locked: not now"), SKIPPED(7, "not Robot's to file"), STOPPED(8, "the reader couldn't be used"), // l10n: not text (voice log codes)
    ;

    companion object {
        fun of(o: FilingOutcome): FileFiledCode = when (o) {
            is FilingOutcome.Filed -> if (o.placement.by == FiledBy.RULE) BY_RULE else FILED
            is FilingOutcome.Asked -> ASKED
            is FilingOutcome.Held -> HELD
            is FilingOutcome.Waiting -> WAITING
            FilingOutcome.Locked -> LOCKED
            FilingOutcome.Skipped -> SKIPPED
            FilingOutcome.Stop -> STOPPED
        }

        /** FILE_FILED's c: the reason ([FilingWhy]'s place, 1 up; 0 none). */
        fun why(o: FilingOutcome): Int = when (o) {
            is FilingOutcome.Asked -> o.why.ordinal + 1
            is FilingOutcome.Held -> o.why.ordinal + 1
            is FilingOutcome.Waiting -> (o.why?.ordinal ?: -1) + 1
            else -> 0
        }
    }
}

/** A change you made (FILE_MOVE's a). */
enum class FileMoveCode(override val id: Int, override val title: String) : LogCode {
    UNDO(1, "Undo: back in Inbox"), MOVED(2, "moved by you"), PUT_BACK(3, "a move undone"), FILE_THEM(4, "\"File them\""), // l10n: not text (voice log codes)
    ;
}

/** A delete you tapped (FILE_DELETE's a, F7): b = files. Codes and counts only. */
enum class FileDeleteCode(override val id: Int, override val title: String) : LogCode {
    DELETED(1, "deleted (its key destroyed)"), UNDONE(2, "Undo: kept"), NOT_NOW(3, "locked: not deleted"), // l10n: not text (voice log codes)
    CARD(4, "a delete card for your words"), // l10n: not text (voice log codes)
    ;
}

/** A filing rule (FILE_RULE's a, F8): b = 1 learned from your taps, 0 from your words. Codes only, never a rule's words. */
enum class FileRuleCode(override val id: Int, override val title: String) : LogCode {
    SAVED(1, "a rule saved"), ALREADY(2, "already a rule"), UNDONE(3, "a rule undone"), OFF(4, "Memory off: not kept"), // l10n: not text (voice log codes)
    FULL(5, "Memory full: not kept"), NOT_OPEN(6, "memory closed: not kept"), // l10n: not text (voice log codes)
    ;
}

/** Your words looking for files (FILE_FIND's a, F8; F9 adds find_files): b = files found. */
enum class FileFindCode(override val id: Int, override val title: String) : LogCode {
    FIND(1, "find, your words"), INBOX(2, "what's in Inbox"), MOVE(3, "move, your words"), // l10n: not text (voice log codes)
    // F9: the model's find_files (b: files matching) and read_file's answer (b = 1 answered, 0 not).
    TOOL(4, "find_files, the model's"), ANSWER(5, "read_file's answer"), // l10n: not text (voice log codes)
    // F10 (b: files counted, never an amount): spending, what's coming up, subscriptions, a reminder set from a card or on its
    // own, and add_event's card opened from a file's date.
    SPEND(6, "spending, your words"), DUE(7, "dates coming up, your words"), SUBSCRIPTIONS(8, "subscriptions, your words"), // l10n: not text (voice log codes)
    REMIND(9, "a brief reminder set"), CALENDAR(10, "add_event's card from a file"), // l10n: not text (voice log codes)
    // F12 (b: files in it): a tax pack's or an expense report's contents shown, your words or its page.
    PACK(11, "a tax pack or expense report shown"), // l10n: not text (voice log codes)
    ;
}

/** A copy that left the phone (FILE_OUT's a, F7): b = 1 without its metadata (stripped), c = 1 a file that could run something. */
enum class FileOutCode(override val id: Int, override val title: String) : LogCode {
    SHARED(1, "shared"), OPENED(2, "opened with another app"), REVOKED(3, "copies taken back"), // l10n: not text (voice log codes)
    // F12: a tax pack or an expense report exported to a folder you picked (b: files copied, c: files left out).
    EXPORTED(4, "exported to a folder you picked"), // l10n: not text (voice log codes)
    ;
}
