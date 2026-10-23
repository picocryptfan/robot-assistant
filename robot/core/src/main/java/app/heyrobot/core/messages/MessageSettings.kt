// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.MessageStrings
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings

// Pure. Settings › Messages. The defaults are the design's answers to its open questions: replies are
// sent by Robot after a card, with "Open the chat; I press Send" as the switch back (1); after reading, the notification
// is left as it was (2); messages are kept in RAM only until their notification goes (3); nothing on the lock screen
// (4); and messages reach a helper only through a card listing what goes.
//
// Wired in 0.7: the app keeps these in their own file, messages.json ([MessagesFile]), so settings.json keeps its schema;
// People's "Send my replies to … without a card" is [MessagesFile.noCard] by People id. The voice log's events 190–199
// (VoiceEvent.MSG_*, codes in MessageLog.kt) record counts and codes only, never a name or a word of a message.

/** How long a message stays in RAM: never on disk, whatever this says. [text]: its name (core i18n); [title] its English. */
enum class KeepMessages(private val msg: Msg, val afterGoneMs: Long) {
    /** The default: while its notification is there (read in the app, dismissed or cleared: gone). */
    UNTIL_GONE(MessageStrings.keepUntilGone, 0),
    ONE_HOUR(MessageStrings.keepHour, 3_600_000),
    ONE_DAY(MessageStrings.keepDay, 86_400_000);

    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** How a reply goes out. */
enum class ReplyRoute(private val msg: Msg) {
    /** The default: through the app's own reply action (RemoteInput), after the card. */
    SEND_AFTER_CARD(MessageStrings.routeSend),
    /** Phase 2's rule: the chat opens with your reply as a draft and nothing is sent until you press Send there. */
    OPEN_CHAT(MessageStrings.routeOpen);

    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** What happens to a notification after Robot reads its messages to you. */
enum class AfterReading(private val msg: Msg) {
    /** The default: nothing changes; Robot only remembers, in RAM, that it read them. */
    LEAVE(MessageStrings.afterLeave),
    /** Dismiss the notification; the chat stays unread in the app. */
    CLEAR(MessageStrings.afterClear),
    /** Fire the app's mark-as-read: the sender may see you read it (Cheogram's displayed marker, Signal's receipts). */
    MARK_READ(MessageStrings.afterMarkRead);

    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

/** Messages on the lock screen. */
enum class MessagesLocked(private val msg: Msg) {
    /** The default: "Unlock first". */
    OFF(MessageStrings.lockedOff),
    WHO_MESSAGED(MessageStrings.lockedWho),
    READ_AND_REPLY(MessageStrings.lockedRead);

    val text: Phrase get() = msg()
    val title: String get() = text.en()
}

data class MessageSettings(
    /** "Read my messages": off until you turn it on. */
    val on: Boolean = false,
    val allowlist: MessageAllowlist = MessageAllowlist(),
    val keep: KeepMessages = KeepMessages.UNTIL_GONE,
    val replyRoute: ReplyRoute = ReplyRoute.SEND_AFTER_CARD,
    /** "Card before sending a reply": on. Off, a 1:1 reply you dictated goes after a 3-second "stop" window. */
    val replyCard: Boolean = true,
    /**
     * A spoken "yes" answers a reply card: off. A tap sends; a "yes" heard by the microphone might be someone else's,
     * or Robot's own voice reading the card, and a sent message can't be recalled.
     */
    val voiceYesSends: Boolean = false,
    val afterReading: AfterReading = AfterReading.LEAVE,
    val translate: Boolean = true,
    val offerTheirLanguage: Boolean = true,
    val locked: MessagesLocked = MessagesLocked.OFF,
    /** "Messages can go to trusted helpers without a card": off. */
    val helpersNoCard: Boolean = false,
    /**
     * "Announce new messages" (0.8, off): a new message is announced in private headphones (Settings › Headset) and in
     * the car while car mode runs (Settings › Car mode), never on the phone's speaker. Settings › Headset and Car mode say
     * who and how; this is the one switch that turns announcements on.
     */
    val announce: Boolean = false,
)

/** What "read my messages" does on a locked phone. */
enum class LockedMessages { READ, NAMES_ONLY, UNLOCK_FIRST }

object MessageAccess {
    val UNLOCK_FIRST: String get() = unlockFirstText().en()
    val UNLOCK_FIRST_FR: String get() = unlockFirstText().render(Langs.of(Lang.FR))
    val OFF: String get() = offText().en()
    val OFF_FR: String get() = offText().render(Langs.of(Lang.FR))

    /** These lines in words without a language yet (1.1). */
    fun unlockFirstText(): Phrase = MessageStrings.unlockFirst()
    fun offText(): Phrase = MessageStrings.off("page" to UiStrings.inSettings(UiStrings.messages))

    fun whenLocked(locked: Boolean, s: MessageSettings): LockedMessages = when {
        !locked -> LockedMessages.READ
        s.locked == MessagesLocked.READ_AND_REPLY -> LockedMessages.READ
        s.locked == MessagesLocked.WHO_MESSAGED -> LockedMessages.NAMES_ONLY
        else -> LockedMessages.UNLOCK_FIRST
    }

    /** Message text may go to a helper only through a card listing what goes, unless you allowed trusted helpers. */
    fun helperNeedsCard(s: MessageSettings, trusted: Boolean): Boolean = !(s.helpersNoCard && trusted)
}
