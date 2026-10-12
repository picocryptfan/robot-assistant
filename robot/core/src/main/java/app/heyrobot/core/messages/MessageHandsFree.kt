package app.heyrobot.core.messages

import app.heyrobot.core.AppUnread
import app.heyrobot.core.Person
import app.heyrobot.core.UnreadCount
import org.json.JSONArray
import org.json.JSONObject

// Pure. Where 0.7's messages meet 0.8's routines, headset and car mode: the brief's unread count (counts by app only,
// never who or what), which posted notification is a new message worth announcing, who "People you choose" lets
// through, and whether a reply may be sent after a spoken yes at all (0.7's reply rules: a reply that needs a tap still
// needs one, so in the car it waits for parking instead).

object MessageUnread {
    /**
     * The brief's count: the messages Robot hasn't read to you in [threads] (what the main process holds), by app, most
     * first, only from the apps Settings › Messages allows. Null when Settings › Messages is off: the brief says nothing.
     */
    fun count(threads: List<MessageThread>, s: MessageSettings): UnreadCount? {
        if (!s.on) return null
        val by = threads.filter { it.app in s.allowlist.apps }
            .groupBy { it.app }
            .mapValues { (_, ts) -> ts.sumOf { t -> t.unread.size.takeIf { it > 0 } ?: if (t.hidden != null && t.incoming.any { !it.readByRobot }) 1 else 0 } }
            .filterValues { it > 0 }
        val apps = by.entries.sortedWith(compareByDescending<Map.Entry<MessageApp, Int>> { it.value }.thenBy { it.key.ordinal }).map { AppUnread(it.key.title, it.value) }
        return UnreadCount(apps.sumOf { it.count }, apps)
    }

    /** The count as it crosses the bridge (numbers and app names only); null: nothing to say. */
    fun toJson(u: UnreadCount?): String = JSONObject().apply {
        if (u != null) put("total", u.total).put("apps", JSONArray().apply { u.apps.forEach { put(JSONObject().put("app", it.app).put("n", it.count)) } })
    }.toString()

    fun fromJson(text: String?): UnreadCount? = try {
        val o = JSONObject(text ?: "")
        if (!o.has("total")) null else {
            val a = o.optJSONArray("apps") ?: JSONArray()
            val apps = (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { AppUnread(it.optString("app"), it.optInt("n")) } }
                .filter { it.app.isNotBlank() && it.count > 0 }
            UnreadCount(o.optInt("total").coerceAtLeast(0), apps)
        }
    } catch (_: Exception) {
        null
    }
}

object MessageArrival {
    /**
     * [after], just posted, brings a message [before] (the same conversation as Robot held it a moment ago, or null)
     * didn't have: a newer incoming message, not yours, not earlier context, not read to you yet. An update of the same
     * notification (a reaction, your own reply, a re-post) is not new.
     */
    fun isNew(before: MessageThread?, after: MessageThread): Boolean {
        val newest = after.messages.filter { !it.fromMe && !it.historic && !it.readByRobot }.maxOfOrNull { it.time } ?: return false
        val had = before?.messages?.filter { !it.fromMe && !it.historic }?.maxOfOrNull { it.time } ?: return true
        return newest > had
    }
}

object AnnounceSenders {
    /**
     * "Announce from: People you choose": the newest message of [t] is from a confirmed person in [people] whose id is in
     * [chosen] (People › a person › "Announce messages from …"), by the same rules as the name Robot reads (core
     * MessagePeople.personFor: their address, or their name where the app gives none).
     */
    fun chosen(t: MessageThread, people: List<Person>, chosen: Set<String>): Boolean {
        if (chosen.isEmpty()) return false
        val m = t.incoming.lastOrNull { !it.historic } ?: return false
        return MessagePeople.personFor(m.senderKey, m.senderName, people)?.id?.let { it in chosen } == true
    }
}

/** Whether a reply to a conversation may go after a spoken yes, by 0.7's rules. */
enum class VoiceReply {
    /** A spoken read-back and "send" or "yes" may send it ("Answer reply cards by voice", or a person you chose for no card). */
    BY_VOICE,
    /** Only a tap sends it (the default card, a group, a locked phone): in the car it waits for parking. */
    TAP_ONLY,
    /** Robot sends nothing itself ("Open the chat; I press Send", or no Reply action): it waits for parking too. */
    OPEN_CHAT,
    /** Not now (Settings › Messages off, or locked without "Read and reply"). */
    REFUSED,
}

object HandsFreeReplies {
    /**
     * How a reply you dictate to [t] could go, by core ReplyPlanner exactly as the panel's card would plan it for your own
     * words in a conversation you chose (Robot announced or read it to you): [noCard] is People's "without a card" for its
     * sender (MessagePeople.withoutCard). The words themselves don't change the answer, so a placeholder is planned.
     */
    fun of(t: MessageThread, s: MessageSettings, locked: Boolean, noCard: Boolean): VoiceReply =
        when (val p = ReplyPlanner.plan(t, "…", ReplySource.DICTATED, s, locked, noCard, sure = true)) {
            is ReplyPlan.Send -> if (p.confirm == Confirm.TAP) VoiceReply.TAP_ONLY else VoiceReply.BY_VOICE
            is ReplyPlan.HandOff -> VoiceReply.OPEN_CHAT
            is ReplyPlan.Refused -> VoiceReply.REFUSED
        }
}
