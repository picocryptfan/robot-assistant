package app.heyrobot.core.messages

// Pure. The messaging apps Robot may read and answer (design §B3, §B11), and what each one's notification actions do,
// as read in their source (Cheogram master 2f223af7, Signal-Android 92f5ccd3, GrapheneOS Messaging e34a84fd). Molly and
// Google Messages weren't checked: their reply behaviour is marked unverified, and a card says so.

/** One app; [packages] are its Android package names (Cheogram has a Play build). */
enum class MessageApp(
    val title: String,
    val packages: List<String>,
    /** Its reply and mark-read actions were read in source. */
    val verified: Boolean,
    /** An emoji-only reply becomes a reaction (Cheogram, XEP-0444). */
    val emojiReplyIsReaction: Boolean = false,
    /** Replying from the notification marks the chat read and sends read receipts (Signal). */
    val replyMarksRead: Boolean = false,
    /** Mark as read tells the sender (Cheogram's XEP-0333 "displayed", on by default; Signal's receipts if on). */
    val markReadTellsSender: Boolean = false,
    /** Each reply is an SMS or MMS (carrier charges apply). */
    val sms: Boolean = false,
) {
    CHEOGRAM("Cheogram", listOf("com.cheogram.android", "com.cheogram.android.playstore"), verified = true,
        emojiReplyIsReaction = true, markReadTellsSender = true),
    SIGNAL("Signal", listOf("org.thoughtcrime.securesms"), verified = true, replyMarksRead = true, markReadTellsSender = true),
    /** Signal's code; the package name is the one Molly publishes (not checked on the phone yet). */
    MOLLY("Molly", listOf("im.molly.app"), verified = false, replyMarksRead = true, markReadTellsSender = true),
    MESSAGING("Messaging (SMS)", listOf("com.android.messaging"), verified = true, sms = true),
    GOOGLE_MESSAGES("Google Messages", listOf("com.google.android.apps.messaging"), verified = false, sms = true);

    companion object {
        fun of(packageName: String?): MessageApp? = entries.firstOrNull { packageName != null && packageName in it.packages }
    }
}

/**
 * Settings › Messages › Apps: each app a switch, all off by default. [groups]: "Include group chats" (on; a reply to a
 * group always needs a tap). Every other package is dropped at the first line of the notification callback.
 */
data class MessageAllowlist(val apps: Set<MessageApp> = emptySet(), val groups: Boolean = true) {
    /** The app [packageName] belongs to when it is allowed; null for everything else. */
    fun allows(packageName: String?): MessageApp? = MessageApp.of(packageName)?.takeIf { it in apps }

    fun with(app: MessageApp, on: Boolean): MessageAllowlist = copy(apps = if (on) apps + app else apps - app)

    companion object {
        /** The setup screen pre-ticks only your texting app (Cheogram) and asks you to confirm. */
        fun atSetup(textingApp: MessageApp?): MessageAllowlist = MessageAllowlist(setOfNotNull(textingApp))
    }
}
