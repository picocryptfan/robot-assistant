package app.heyrobot.core.messages

// Pure. Settings › Messages (design §B11). The defaults are the design's answers to its open questions: replies are
// sent by Robot after a card, with "Open the chat; I press Send" as the switch back (1); after reading, the notification
// is left as it was (2); messages are kept in RAM only until their notification goes (3); nothing on the lock screen
// (4); and messages reach a helper only through a card listing what goes.
//
// Wired in 0.7: the app keeps these in their own file, messages.json ([MessagesFile]), so settings.json keeps its schema;
// People's "Send my replies to … without a card" is [MessagesFile.noCard] by People id. The voice log's events 190–199
// (VoiceEvent.MSG_*, codes in MessageLog.kt) record counts and codes only, never a name or a word of a message.

/** How long a message stays in RAM: never on disk, whatever this says. */
enum class KeepMessages(val title: String, val afterGoneMs: Long) {
    /** The default: while its notification is there (read in the app, dismissed or cleared: gone). */
    UNTIL_GONE("Until the notification goes away", 0),
    ONE_HOUR("1 hour", 3_600_000),
    ONE_DAY("24 hours", 86_400_000),
}

/** How a reply goes out. */
enum class ReplyRoute(val title: String) {
    /** The default: through the app's own reply action (RemoteInput), after the card. */
    SEND_AFTER_CARD("Robot sends after you confirm"),
    /** Phase 2's rule: the chat opens with your reply as a draft and nothing is sent until you press Send there. */
    OPEN_CHAT("Open the chat with my reply; I press Send"),
}

/** What happens to a notification after Robot reads its messages to you. */
enum class AfterReading(val title: String) {
    /** The default: nothing changes; Robot only remembers, in RAM, that it read them. */
    LEAVE("Leave it"),
    /** Dismiss the notification; the chat stays unread in the app. */
    CLEAR("Clear the notification"),
    /** Fire the app's mark-as-read: the sender may see you read it (Cheogram's displayed marker, Signal's receipts). */
    MARK_READ("Mark as read"),
}

/** Messages on the lock screen. */
enum class MessagesLocked(val title: String) {
    /** The default: "Unlock first". */
    OFF("Off"),
    WHO_MESSAGED("Who messaged"),
    READ_AND_REPLY("Read and reply"),
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
    const val UNLOCK_FIRST = "Unlock first: your messages stay private while the phone is locked."
    const val UNLOCK_FIRST_FR = "Déverrouille d'abord : tes messages restent privés quand le téléphone est verrouillé."
    const val OFF = "Reading messages is off. Turn it on in Settings › Messages."
    const val OFF_FR = "La lecture des messages est désactivée. Active-la dans Réglages › Messages."

    fun whenLocked(locked: Boolean, s: MessageSettings): LockedMessages = when {
        !locked -> LockedMessages.READ
        s.locked == MessagesLocked.READ_AND_REPLY -> LockedMessages.READ
        s.locked == MessagesLocked.WHO_MESSAGED -> LockedMessages.NAMES_ONLY
        else -> LockedMessages.UNLOCK_FIRST
    }

    /** Message text may go to a helper only through a card listing what goes, unless you allowed trusted helpers. */
    fun helperNeedsCard(s: MessageSettings, trusted: Boolean): Boolean = !(s.helpersNoCard && trusted)
}
