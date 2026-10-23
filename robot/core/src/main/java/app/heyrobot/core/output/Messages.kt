// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.output

import app.heyrobot.core.HelperRouter
import app.heyrobot.core.LogCode
import app.heyrobot.core.TextCommand

// Pure. The hooks headset and car mode need from 0.7's messages (announcements, messages in car mode): announcing a new message, reading it after a yes, and replying through the notification's own Reply action
// after a spoken read-back. 0.7 (app.heyrobot.core.messages: MessageThread, ReplyPlanner) supplies
// them: a MessageThread maps onto a [MessageNotice] (its key, its newest incoming message's sender and text, its title,
// replyKind != NONE, isGroup, hidden != null), its notification listener in the main process implements
// [ReplyChannel] over the internal command path (it owns the StatusBarNotification and its PendingIntent, checks the
// conversation is still there and that Robot's :voice asked), and People decides [SenderFilter]. Until 0.7 nothing
// implements them, and announcements, reading and replies stay off.
//
// Hands-free replies differ from the panel's on purpose: in the car or a headset there is no card to tap, so the reply
// is read back word for word and sent after a spoken "send" or "yes" ("Send a reply after a spoken yes (car, headset)",
// Safety & privacy, on; off: every reply goes to the screen's card). What the Reply action receives is exactly
// the read-back string (car ReplyFlow), never a model's draft. Groups, apps without a Reply action and replies the
// model wrote are never sent by voice.

/**
 * One conversation's newest message, as spoken. [conversationKey]: 0.7's MessageThread.key. [sender]: the name to say
 * (your People alias when the key matches, else the app's). [group]: the group's title, or null for a 1:1 chat.
 * [replyable]: its notification has a Reply action Robot can fill. [dndAllows]: Ranking.matchesInterruptionFilter.
 * [hidden]: the app shows only "New message" (a one-time code Android hid, an app's own privacy). [toString] leaves the
 * names and the text out.
 */
data class MessageNotice(
    val conversationKey: String,
    val sender: String,
    val senderKey: String?,
    val text: String,
    val app: String,
    val replyable: Boolean,
    val group: String? = null,
    val dndAllows: Boolean = true,
    val hidden: Boolean = false,
) {
    val isGroup: Boolean get() = group != null

    override fun toString(): String = "MessageNotice(app=$app, replyable=$replyable, group=$isGroup, hidden=$hidden, chars=${text.length})" // l10n: not text (toString, for the logs)
}

/** 0.7's messages, as the hands-free side reads them. */
interface MessageFeed {
    /** Conversations with messages not read to you yet, newest first. */
    fun unread(): List<MessageNotice>

    /** The conversation as its notification shows it now, or null when it went away. */
    fun current(conversationKey: String): MessageNotice?
}

/** "Announce from: People you choose": whether [senderKey] is one of them (People decides; null: unknown sender). */
fun interface SenderFilter {
    fun allowed(senderKey: String?): Boolean
}

/** The main process sends [text] through the conversation's own Reply action (RemoteInput), then marks it read if set. */
fun interface ReplyChannel {
    fun reply(conversationKey: String, text: String): ReplyResult
}

/** What came of a reply. Also the voice log's CAR_REPLY b. */
enum class ReplyResult(override val id: Int, override val title: String) : LogCode {
    /** Handed to the app ("Handed to Cheogram"): never "delivered", which Robot can't know. */
    HANDED_OVER(1, "handed to the app"),
    NO_REPLY_ACTION(2, "no reply action"),
    GONE(3, "the notification went away"),
    LOCKED(4, "the app's reply needs an unlocked phone"),
    FAILED(5, "didn't work"),
    /** 0.8: a newer message came after the read-back; nothing sent. */
    NEW_MESSAGES(6, "a newer message came"),
}

/** The words of a reply without what introduces them ("tell her", "reply that", "dis-lui que", "réponds-lui que"). */
object ReplyWords {
    private val LEADS: List<List<String>> = listOf(
        "reply to her that", "reply to him that", "reply to them that", "reply to her", "reply to him", "reply to them", "reply that", "reply with", "reply",
        "respond that", "respond", "answer that", "answer", "tell her that", "tell him that", "tell them that", "tell her", "tell him", "tell them",
        "say that", "say", "text back that", "text back", "write back that", "write back",
        "reponds lui que", "reponds leur que", "reponds lui", "reponds leur", "reponds que", "reponds", "repond lui que", "repond que",
        "dis lui que", "dis leur que", "dis lui", "dis leur", "dis que", "ecris lui que", "ecris lui",
    ).map { it.split(' ') }

    /** [dictated] without its lead-in words and the wake phrase, trimmed, its own words otherwise untouched. */
    fun body(dictated: String): String {
        val s = TextCommand.stripWake(dictated).trim()
        val end = leadEnd(s) ?: return s
        return s.substring(end).trimStart(' ', ',', ':', '-', '—').trim()
    }

    /** [said] begins with a reply's lead-in ("tell her …", "dis-lui que …"), the wake phrase aside. */
    fun hasLead(said: String): Boolean = leadEnd(TextCommand.stripWake(said).trim()) != null

    /** Where the lead-in of [s] ends, or null without one. */
    private fun leadEnd(s: String): Int? {
        // Words with where they are in [s], to cut the lead off the original text.
        val words = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*").findAll(s).map { HelperRouter.plain(it.value) to it.range }.toList()
        val split = words.flatMap { (w, r) -> w.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.map { it to r } }
        val lead = LEADS.firstOrNull { l -> l.size <= split.size && l.indices.all { split[it].first == l[it] } } ?: return null
        return split[lead.size - 1].second.last + 1
    }
}
