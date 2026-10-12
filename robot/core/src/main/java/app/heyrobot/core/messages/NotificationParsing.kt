package app.heyrobot.core.messages

import app.heyrobot.core.ToolCalls
import java.text.Normalizer
import java.util.Locale

// Pure. A messaging notification read into a [MessageThread] (design §B2–§B4). The main process's notification listener
// copies what Android gives (MessagingStyle's messages and people, the actions and their RemoteInputs) into the plain
// snapshot classes below, field for field, and everything that decides runs here, so it is tested on the JVM with
// recorded snapshots. Missing pieces are normal (an app's privacy setting, a redacted code, a quiet-hours channel
// without a shortcut, a damaged bundle): the parser takes what is there and never throws.

/** Notification.Action.SEMANTIC_ACTION_* values (API 28+). */
object SemanticAction {
    const val NONE = 0
    const val REPLY = 1
    const val MARK_AS_READ = 2
    const val MARK_AS_UNREAD = 3
    const val DELETE = 4
    const val ARCHIVE = 5
    const val MUTE = 6
    const val UNMUTE = 7
    const val THUMBS_UP = 8
    const val THUMBS_DOWN = 9
    const val CALL = 10
}

/** android.app.Person: [name] as shown, [key] the app's id for them (Cheogram: the bare JID), [uri] a contact link. */
data class PersonSnapshot(val name: String?, val key: String? = null, val uri: String? = null) {
    override fun toString(): String = "PersonSnapshot(named=${name != null}, keyed=${key != null})"
}

/** MessagingStyle.Message: text, timestamp, sender (null: the user), and the data MIME type of an attachment. */
data class MessageSnapshot(val text: String?, val timestamp: Long, val sender: PersonSnapshot?, val dataMimeType: String? = null) {
    override fun toString(): String = "MessageSnapshot(chars=${text?.length}, time=$timestamp, mime=$dataMimeType)"
}

/** RemoteInput: [resultKey] the reply goes under; [allowFreeFormInput] false means canned choices only. */
data class RemoteInputSnapshot(val resultKey: String?, val label: String? = null, val allowFreeFormInput: Boolean = true, val choices: List<String> = emptyList())

/**
 * Notification.Action. [showsUserInterface] is Android's default (true) unless the app said otherwise. [contextual]:
 * an action the system added (smart replies), never Robot's to fire.
 */
data class ActionSnapshot(
    val title: String?,
    val semanticAction: Int = SemanticAction.NONE,
    val remoteInputs: List<RemoteInputSnapshot> = emptyList(),
    val showsUserInterface: Boolean = true,
    val contextual: Boolean = false,
    val authenticationRequired: Boolean = false,
)

/**
 * One posted notification. [key]: StatusBarNotification.getKey(). [template]: EXTRA_TEMPLATE
 * ("android.app.Notification$MessagingStyle"). [messages], [historicMessages]: EXTRA_MESSAGES and
 * EXTRA_HISTORIC_MESSAGES (an entry Android couldn't read is null). [actions]: Notification.actions in order;
 * [wearActions]: WearableExtender's, copied only so tests can show they are never used (Cheogram's marks the chat read).
 */
data class NotificationSnapshot(
    val packageName: String?,
    val key: String?,
    val postTime: Long,
    val category: String? = null,
    val template: String? = null,
    val shortcutId: String? = null,
    val channelId: String? = null,
    val title: String? = null,
    val text: String? = null,
    val conversationTitle: String? = null,
    val isGroupConversation: Boolean? = null,
    val user: PersonSnapshot? = null,
    val messages: List<MessageSnapshot?> = emptyList(),
    val historicMessages: List<MessageSnapshot?> = emptyList(),
    val actions: List<ActionSnapshot?> = emptyList(),
    val wearActions: List<ActionSnapshot?> = emptyList(),
    val groupSummary: Boolean = false,
    val ongoing: Boolean = false,
) {
    override fun toString(): String = "NotificationSnapshot(package=$packageName, messages=${messages.size}, actions=${actions.size})"
}

/** Why a notification was left alone. */
enum class IgnoreWhy {
    /** Not an app you turned on in Settings › Messages: dropped at the first line, nothing read. */
    NOT_ALLOWED,
    NO_KEY,
    /** A group summary: its children carry the messages. */
    SUMMARY,
    ONGOING,
    /** Neither MessagingStyle nor CATEGORY_MESSAGE: a download, a call, a sync. */
    NOT_A_CONVERSATION,
    /** A group chat with "Include group chats" off. */
    GROUP_OFF,
    /** Nothing readable in it. */
    NOTHING_TO_READ,
}

sealed class Parsed {
    data class Thread(val thread: MessageThread) : Parsed()
    data class Ignored(val why: IgnoreWhy) : Parsed()
}

object NotificationParsing {
    const val CATEGORY_MESSAGE = "msg"
    /** MessagingStyle.MAXIMUM_RETAINED_MESSAGES. */
    const val MAX_MESSAGES = 25
    /** Characters kept of one message (a summary cuts again, at 600). */
    const val MAX_TEXT = 4_000
    const val MAX_NAME = 80
    /** Distinct people writing in a conversation (you included) that make it a group, whatever the app says. */
    const val GROUP_SENDERS = 3

    /**
     * What apps show instead of the text: Signal's notification privacy ("New message"), and Android's redaction of a
     * message with a one-time code. The redaction's wording on GrapheneOS is not checked yet (phone gate 7.0e).
     */
    private val PRIVACY = setOf("new message", "new messages", "nouveau message", "nouveaux messages", "you have a new message",
        "vous avez un nouveau message")
    private val REDACTED = setOf("sensitive notification content hidden", "contenu de notification sensible masque",
        "contenu sensible masque")

    private val MARK_READ_TITLE = Regex("^(?:mark (?:as )?read|marquer (?:comme )?lus?|lu)$")

    /** [n] as a conversation, or why it was left alone. Only an allowlisted app's notification is read at all. */
    fun parse(n: NotificationSnapshot, allow: MessageAllowlist): Parsed {
        val app = allow.allows(n.packageName) ?: return Parsed.Ignored(IgnoreWhy.NOT_ALLOWED)
        val key = n.key?.trim()?.takeIf { it.isNotEmpty() } ?: return Parsed.Ignored(IgnoreWhy.NO_KEY)
        if (n.groupSummary) return Parsed.Ignored(IgnoreWhy.SUMMARY)
        if (n.ongoing) return Parsed.Ignored(IgnoreWhy.ONGOING)
        val styled = n.template?.endsWith("MessagingStyle") == true || n.messages.any { it != null }
        if (!styled && n.category != CATEGORY_MESSAGE) return Parsed.Ignored(IgnoreWhy.NOT_A_CONVERSATION)

        val conversationId = n.shortcutId?.trim()?.takeIf { it.isNotEmpty() } ?: key
        var raw = n.messages.filterNotNull().map { it to false } + n.historicMessages.filterNotNull().map { it to true }
        // CATEGORY_MESSAGE without MessagingStyle (or with none left): the notification's own title and text.
        if (raw.isEmpty() && n.text != null) raw = listOf(MessageSnapshot(n.text, n.postTime, PersonSnapshot(n.title)) to false)

        val senders = raw.mapNotNull { (m, _) -> if (isMe(m.sender, n.user)) null else m.sender?.key ?: m.sender?.name }.toSet()
        val mine = raw.any { (m, _) -> isMe(m.sender, n.user) }
        // What the app says (or a titled conversation with two others), and, whatever it says, three people or more
        // writing in it (you included): a reply to a group always needs a tap.
        val group = (n.isGroupConversation ?: (n.conversationTitle != null && senders.size > 1)) || senders.size + (if (mine) 1 else 0) >= GROUP_SENDERS
        if (group && !allow.groups) return Parsed.Ignored(IgnoreWhy.GROUP_OFF)

        val reply = replyAction(n.actions)
        var hidden: HiddenWhy? = null
        val seen = HashSet<String>()
        val messages = ArrayList<IncomingMessage>()
        for ((m, historic) in raw) {
            val me = isMe(m.sender, n.user)
            var text = cleanText(m.text)
            val attachment = attachment(m.dataMimeType)
            val folded = fold(text)
            if (!me && folded in REDACTED) { hidden = HiddenWhy.OTP_REDACTED; text = "" }
            else if (!me && attachment == null && folded.trimEnd('.', '!') in PRIVACY) { hidden = hidden ?: HiddenWhy.APP_PRIVACY; text = "" }
            else if (text.isEmpty() && attachment == null) continue
            val time = m.timestamp.takeIf { it > 0 } ?: n.postTime
            val senderName = if (me) null else cleanName(m.sender?.name)
            val senderKey = if (me) null else m.sender?.key?.trim()?.takeIf { it.isNotEmpty() }
            val id = messageId(app, conversationId, time, senderKey ?: senderName ?: if (me) "me" else "?", text, attachment)
            if (!seen.add(id)) continue
            messages += IncomingMessage(id, app, conversationId, senderName, senderKey, text, time, group, reply != null, me, attachment, historic)
        }
        if (messages.isEmpty() && hidden == null) return Parsed.Ignored(IgnoreWhy.NOTHING_TO_READ)
        // Oldest first; the newest MAX_MESSAGES, a current message before an earlier-context one at the same time.
        val kept = messages.sortedWith(compareBy<IncomingMessage> { it.time }.thenBy { !it.historic }).takeLast(MAX_MESSAGES)
        val title = cleanName(n.conversationTitle)
            ?: if (!group) kept.lastOrNull { !it.fromMe && it.senderName != null }?.senderName ?: cleanName(n.title) else cleanName(n.title)
        return Parsed.Thread(MessageThread(app, conversationId, key, title, group, kept, reply, markReadAction(n.actions), hidden, n.postTime))
    }

    /** Sender null, or the same person as MessagingStyle's user (by key, else by name when neither has a key): you. */
    fun isMe(sender: PersonSnapshot?, user: PersonSnapshot?): Boolean = when {
        sender == null -> true
        user == null -> false
        sender.key != null && user.key != null -> sender.key == user.key
        sender.key == null && user.key == null -> sender.name != null && sender.name == user.name
        else -> false
    }

    /**
     * The action Robot replies with, in order: SEMANTIC_ACTION_REPLY with a free-form RemoteInput and no screen of its
     * own (Cheogram, Signal); the same that opens a screen; an unlabelled action with a free-form RemoteInput
     * (GrapheneOS Messaging's actions have no semantic labels), preferring one with no screen. Never a contextual
     * (system) action, never one labelled for something else, never a WearableExtender action.
     */
    fun replyAction(actions: List<ActionSnapshot?>): ActionRef? {
        var best: ActionRef? = null
        var bestRank = Int.MAX_VALUE
        for ((i, a) in actions.withIndex()) {
            if (a == null || a.contextual) continue
            if (a.semanticAction != SemanticAction.REPLY && a.semanticAction != SemanticAction.NONE) continue
            val input = a.remoteInputs.firstOrNull { it.allowFreeFormInput && !it.resultKey.isNullOrBlank() } ?: continue
            val semantic = a.semanticAction == SemanticAction.REPLY
            val rank = (if (semantic) 0 else 2) + (if (a.showsUserInterface) 1 else 0)
            if (rank < bestRank) {
                bestRank = rank
                best = ActionRef(i, input.resultKey, semantic, a.authenticationRequired, a.showsUserInterface)
            }
        }
        return best
    }

    /** SEMANTIC_ACTION_MARK_AS_READ, else an unlabelled action titled "Mark as read" / "Marquer comme lu" (Messaging). */
    fun markReadAction(actions: List<ActionSnapshot?>): ActionRef? {
        val usable = actions.withIndex().filter { (_, a) -> a != null && !a.contextual && a.remoteInputs.isEmpty() }
        usable.firstOrNull { (_, a) -> a!!.semanticAction == SemanticAction.MARK_AS_READ }?.let { (i, a) ->
            return ActionRef(i, null, true, a!!.authenticationRequired, a.showsUserInterface)
        }
        usable.firstOrNull { (_, a) -> a!!.semanticAction == SemanticAction.NONE && MARK_READ_TITLE.matches(fold(a.title.orEmpty()).trim()) }?.let { (i, a) ->
            return ActionRef(i, null, false, a!!.authenticationRequired, a.showsUserInterface)
        }
        return null
    }

    /** A MIME type in words: image, video, audio, anything else a file. */
    fun attachment(mime: String?): AttachmentKind? {
        val m = mime?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return when {
            m.startsWith("image/") -> AttachmentKind.PHOTO
            m.startsWith("video/") -> AttachmentKind.VIDEO
            m.startsWith("audio/") -> AttachmentKind.VOICE
            else -> AttachmentKind.FILE
        }
    }

    /** Invisible and direction-changing characters a message could hide words with. */
    private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\u00AD]")
    private val CONTROL = Regex("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F-\\u009F]")

    /** A message's text: invisible and control characters out, at most two line breaks in a row, [MAX_TEXT] characters. */
    fun cleanText(s: String?): String {
        if (s == null) return ""
        val t = s.replace("\r\n", "\n").replace('\r', '\n').replace(INVISIBLE, "").replace(CONTROL, "")
            .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        return ToolCalls.safeTake(t, MAX_TEXT).trim()
    }

    /** A name on one line, at most [MAX_NAME] characters; null when nothing is left. */
    fun cleanName(s: String?): String? =
        ToolCalls.safeTake(cleanText(s).replace(Regex("\\s+"), " "), MAX_NAME).trim().takeIf { it.isNotEmpty() }

    /**
     * A message's identity: app, conversation, time, sender and a hash of the text, so a re-post isn't counted twice.
     * 16 hex digits (FNV-1a, 64 bits); no text in it.
     */
    fun messageId(app: MessageApp, conversationId: String, time: Long, sender: String, text: String, attachment: AttachmentKind?): String {
        var h = -0x340d631b7bdddcdbL
        for (c in "${app.name}\u0000$conversationId\u0000$time\u0000$sender\u0000$text\u0000${attachment?.name}") {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return java.lang.Long.toHexString(h).padStart(16, '0')
    }

    private fun fold(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
}
