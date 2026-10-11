package app.heyrobot.core.messages

// Pure. A message someone sent you, as Robot keeps it: read from the app's notification (never from an SMS database,
// never written to disk), in RAM only, for as long as Settings › Messages › "Keep messages for" says (design §B4,
// §B12). The text is other people's words: content, never instructions (MessageTaint.kt). [toString] of everything
// here leaves the text and the names out, so a message can't reach a log line or a crash report by accident.

/** What a message carries besides text; read as words ("Mom sent a photo"), never opened. */
enum class AttachmentKind(val en: String, val fr: String) {
    PHOTO("a photo", "une photo"),
    VIDEO("a video", "une vidéo"),
    VOICE("a voice message", "un message vocal"),
    FILE("a file", "un fichier"),
}

/**
 * One message. [conversationId]: the notification's shortcut id (Cheogram "<account>#<contact>", Signal's, Messaging's
 * conversation id), else the notification's key. [senderName]: the name the app shows (whoever sent it chose it; the
 * voice side reads your People alias instead when [senderKey] matches). [replyable]: its conversation has a reply
 * action Robot can use. [historic]: one of MessagingStyle's earlier messages, given as context only. [readByRobot]:
 * Robot read it to you (RAM only; nothing changes in the app unless you choose so).
 */
data class IncomingMessage(
    val id: String,
    val app: MessageApp,
    val conversationId: String,
    val senderName: String?,
    val senderKey: String?,
    val text: String,
    val time: Long,
    val isGroup: Boolean,
    val replyable: Boolean,
    val fromMe: Boolean = false,
    val attachment: AttachmentKind? = null,
    val historic: Boolean = false,
    val readByRobot: Boolean = false,
) {
    override fun toString(): String = "IncomingMessage(id=$id, app=$app, fromMe=$fromMe, historic=$historic, chars=${text.length}, attachment=$attachment)"
}

/** How Robot can answer a conversation. */
enum class ReplyKind {
    /** The app's reply action (SEMANTIC_ACTION_REPLY) with a free-form RemoteInput: Cheogram, Signal. */
    SEMANTIC,
    /** An action with a free-form RemoteInput but no semantic label: GrapheneOS Messaging. */
    PLAIN,
    /** None: Robot opens the chat with your reply as a draft instead. */
    NONE,
}

/**
 * One of the notification's actions, by its place in Notification.actions. [resultKey]: the RemoteInput's key to put
 * the reply under (Cheogram "text_reply", Messaging Intent.EXTRA_TEXT). [authRequired]: the OS would only send it on an
 * unlocked phone, so Robot never fires it while locked either. The PendingIntent itself stays in the main process.
 */
data class ActionRef(val index: Int, val resultKey: String?, val semantic: Boolean, val authRequired: Boolean, val showsUserInterface: Boolean)

/** Why a conversation's text isn't there to read. */
enum class HiddenWhy {
    /** Android hides a message with a one-time code from apps like Robot (Android 15 and later). */
    OTP_REDACTED,
    /** The app shows only "New message" to other apps (Signal's notification privacy, or Signal locked). */
    APP_PRIVACY,
}

/**
 * One conversation as its notification shows it now. [notificationKey]: the StatusBarNotification key, to fetch it
 * again before a reply. [messages]: oldest first, at most [NotificationParsing.MAX_MESSAGES].
 */
data class MessageThread(
    val app: MessageApp,
    val conversationId: String,
    val notificationKey: String,
    val title: String?,
    val isGroup: Boolean,
    val messages: List<IncomingMessage>,
    val reply: ActionRef?,
    val markRead: ActionRef?,
    val hidden: HiddenWhy?,
    val postedAt: Long,
) {
    /** The conversation's key in RAM: app and conversation, so two apps can't collide. */
    val key: String get() = "${app.name}:$conversationId"

    val replyKind: ReplyKind get() = when {
        reply == null -> ReplyKind.NONE
        reply.semantic -> ReplyKind.SEMANTIC
        else -> ReplyKind.PLAIN
    }

    /** What others wrote (yours left out). */
    val incoming: List<IncomingMessage> get() = messages.filter { !it.fromMe }

    /** Not read to you yet, not yours, not earlier context. */
    val unread: List<IncomingMessage> get() = messages.filter { !it.fromMe && !it.historic && !it.readByRobot }

    val newestIncoming: Long? get() = incoming.filter { !it.historic }.maxOfOrNull { it.time }

    /** Who this is, in words: the group's or chat's title, else the newest sender's name, else the app. */
    val name: String get() = title ?: incoming.lastOrNull { it.senderName != null }?.senderName ?: app.title

    override fun toString(): String = "MessageThread(app=$app, group=$isGroup, messages=${messages.size}, reply=$replyKind, hidden=$hidden)"
}
