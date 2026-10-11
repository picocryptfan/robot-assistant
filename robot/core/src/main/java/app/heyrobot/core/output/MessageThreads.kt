package app.heyrobot.core.output

import app.heyrobot.core.messages.MessageBuffer
import app.heyrobot.core.messages.MessageReading
import app.heyrobot.core.messages.MessageThread
import app.heyrobot.core.messages.ReplyKind

// Pure. Where 0.7's messages (core messages) meet the hooks in Messages.kt: a [MessageThread] as the [MessageNotice] the
// headset and the car speak, and the RAM [MessageBuffer] as their [MessageFeed]. The notice's conversationKey is the
// thread's key, so ReplyChannel finds the thread again with MessageBuffer.thread; ReplyChannel itself stays the main
// process's (it owns the notification).

/**
 * The newest message [MessageReading] would read of this conversation as a [MessageNotice], or null when it holds no one
 * else's message and nothing hidden. [dndAllows]: its notification's Ranking.matchesInterruptionFilter.
 */
fun MessageThread.notice(dndAllows: Boolean = true): MessageNotice? {
    val m = MessageReading.toRead(this).lastOrNull()
    if (m == null && hidden == null) return null
    return MessageNotice(key, m?.senderName ?: name, m?.senderKey, m?.text.orEmpty(), app.title, replyable = replyKind != ReplyKind.NONE,
        group = if (isGroup) name else null, dndAllows = dndAllows, hidden = hidden != null)
}

/** [buffer] as the hands-free side reads it, at [now]; [dndAllows] by the conversation's key. */
class BufferFeed(private val buffer: MessageBuffer, private val now: () -> Long, private val dndAllows: (String) -> Boolean = { true }) : MessageFeed {
    override fun unread(): List<MessageNotice> = buffer.unread(now()).mapNotNull { it.notice(dndAllows(it.key)) }

    override fun current(conversationKey: String): MessageNotice? = buffer.thread(conversationKey)?.notice(dndAllows(conversationKey))
}
