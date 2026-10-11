package app.heyrobot.core.messages

import org.json.JSONArray
import org.json.JSONObject

// Pure (org.json). How the voice process asks the main process about your messages (design §B4): MessagesBridge, a
// bound service in the main process (not exported, Robot's signature permission), over a Messenger. The notification
// listener, the RAM buffer and every PendingIntent stay in the main process; message text crosses Binder in RAM only,
// as these JSON strings, and is never written anywhere. A reply names a conversation by its key and carries your words:
// the main process takes the recipient from that conversation's own notification, read again just before it fires
// ([ReplySend]), so nothing the voice process (or a model) says can send it anywhere else.

object MessagesProtocol {
    /** The bridge, bound by class name from the voice process. */
    const val SERVICE_CLASS = "app.heyrobot.people.MessagesBridge"

    /** The conversations held now, newest first, and [MessagesStatus]. */
    const val REQ_LIST = 1
    /** Robot read these messages to you (RAM only). */
    const val REQ_MARK_ROBOT = 2
    /** Send a reply through the conversation's reply action ([ReplyRequest]). */
    const val REQ_REPLY = 3
    /** Fire the conversation's mark-as-read action. */
    const val REQ_MARK_READ = 4
    /** Dismiss the conversation's notification. */
    const val REQ_CLEAR = 5
    /** Every answer. */
    const val ANSWER = 100

    /** The Bundle key of a request's or an answer's JSON. */
    const val KEY = "json"

    const val CONNECT_MS = 3_000L
    const val ANSWER_MS = 10_000L
}

/**
 * The listener's side: Settings › Messages › "Read my messages" [on], notification access granted ([access]) and the
 * listener [connected], the phone [locked] (the main process's own view, which decides what it hands over).
 */
data class MessagesStatus(val on: Boolean = false, val access: Boolean = false, val connected: Boolean = false, val locked: Boolean = false, val apps: Int = 0)

/** A reply to send: to the conversation [key] (MessageThread.key), [text] as on the card, made when [newestSeen] was the newest message. */
data class ReplyRequest(val key: String, val text: String, val newestSeen: Long?) {
    override fun toString(): String = "ReplyRequest(chars=${text.length})"
}

/** How a reply (or a mark) went, in the main process. */
enum class ReplyOutcome(val end: ReplyEnd?) {
    SENT(ReplyEnd.SENT),
    /** A message came after the card's: the card shows it ("Mom wrote again"), nothing sent. */
    NEW_MESSAGES(ReplyEnd.NEW_MESSAGES),
    /** The notification or its reply action has gone: the chat can open with the reply instead. */
    GONE(ReplyEnd.GONE),
    /** Not the same conversation any more: never sent. */
    CHANGED(ReplyEnd.MISMATCH),
    LOCKED(ReplyEnd.LOCKED),
    AUTH_REQUIRED(ReplyEnd.LOCKED),
    /** "Read my messages" is off. */
    OFF(null),
    /** "How replies go" is "Open the chat; I press Send": the main process never sends then. */
    OPEN_CHAT(null),
    /** The app's PendingIntent was cancelled (its notification updated or went). */
    CANCELED_PI(ReplyEnd.CANCELED_PI),
    FAILED(ReplyEnd.FAILED),
}

/** A reply's answer; [thread] is the conversation as it is now for [ReplyOutcome.NEW_MESSAGES]. */
data class ReplyAnswer(val outcome: ReplyOutcome, val thread: MessageThread? = null)

/** What the main process does with a reply, decided by code. */
sealed class ReplyCheck {
    /** Fire [target]'s action with [text]. */
    data class Fire(val target: ReplyTarget, val text: String) : ReplyCheck() {
        override fun toString(): String = "Fire(app=${target.app}, chars=${text.length})"
    }
    data class Stop(val answer: ReplyAnswer) : ReplyCheck()
}

object ReplySend {
    /**
     * The main process's check before firing a reply: [held] is the conversation as the RAM buffer holds it (its key
     * came from the voice process; nothing else did), [fresh] the same notification read again now (null: gone). The
     * settings and the lock are the main process's own. The recipient is [held]'s notification's, never anything sent.
     */
    fun check(held: MessageThread?, fresh: MessageThread?, req: ReplyRequest, s: MessageSettings, locked: Boolean): ReplyCheck {
        val t = held ?: return stop(ReplyOutcome.GONE)
        return when (val p = ReplyPlanner.plan(t, req.text, ReplySource.DICTATED, s, locked)) {
            is ReplyPlan.Refused -> stop(when (p.why) {
                RefuseWhy.OFF -> ReplyOutcome.OFF
                RefuseWhy.LOCKED -> ReplyOutcome.LOCKED
                RefuseWhy.AUTH_REQUIRED -> ReplyOutcome.AUTH_REQUIRED
                else -> ReplyOutcome.FAILED
            })
            is ReplyPlan.HandOff -> stop(if (p.why == HandOffWhy.ROUTE_OPEN_CHAT) ReplyOutcome.OPEN_CHAT else ReplyOutcome.GONE)
            is ReplyPlan.Send -> when (val r = ReplyGuard.recheck(p.copy(newestSeen = req.newestSeen), fresh)) {
                is Recheck.Ready -> if (locked && r.target.action.authRequired) stop(ReplyOutcome.AUTH_REQUIRED) else ReplyCheck.Fire(r.target, p.text)
                is Recheck.NewMessages -> ReplyCheck.Stop(ReplyAnswer(ReplyOutcome.NEW_MESSAGES, r.thread))
                Recheck.Gone -> stop(ReplyOutcome.GONE)
                Recheck.Changed -> stop(ReplyOutcome.CHANGED)
            }
        }
    }

    private fun stop(o: ReplyOutcome) = ReplyCheck.Stop(ReplyAnswer(o))
}

/** The JSON both sides of the bridge read and write (RAM only). Damaged input reads as nothing, never as something else. */
object MessageWire {
    fun status(s: MessagesStatus): JSONObject = JSONObject().put("on", s.on).put("access", s.access).put("connected", s.connected)
        .put("locked", s.locked).put("apps", s.apps)

    fun status(o: JSONObject?): MessagesStatus = if (o == null) MessagesStatus() else
        MessagesStatus(o.optBoolean("on"), o.optBoolean("access"), o.optBoolean("connected"), o.optBoolean("locked"), o.optInt("apps"))

    fun list(status: MessagesStatus, threads: List<MessageThread>): String =
        JSONObject().put("status", status(status)).put("threads", JSONArray().apply { threads.forEach { put(thread(it)) } }).toString()

    /** The status and the conversations of a [list] answer; damaged: off and none. */
    fun list(text: String?): Pair<MessagesStatus, List<MessageThread>> = try {
        val o = JSONObject(text ?: "")
        val arr = o.optJSONArray("threads") ?: JSONArray()
        status(o.optJSONObject("status")) to (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(::thread) }
    } catch (_: Exception) {
        MessagesStatus() to emptyList()
    }

    fun request(r: ReplyRequest): String = JSONObject().put("key", r.key).put("text", r.text).apply { r.newestSeen?.let { put("seen", it) } }.toString()

    fun request(text: String?): ReplyRequest? = try {
        val o = JSONObject(text ?: "")
        val key = o.getString("key")
        ReplyRequest(key, o.getString("text"), if (o.has("seen")) o.getLong("seen") else null)
    } catch (_: Exception) {
        null
    }

    fun answer(a: ReplyAnswer): String = JSONObject().put("outcome", a.outcome.name).apply { a.thread?.let { put("thread", thread(it)) } }.toString()

    fun answer(text: String?): ReplyAnswer = try {
        val o = JSONObject(text ?: "")
        ReplyAnswer(ReplyOutcome.valueOf(o.getString("outcome")), o.optJSONObject("thread")?.let(::thread))
    } catch (_: Exception) {
        ReplyAnswer(ReplyOutcome.FAILED)
    }

    fun key(key: String): String = JSONObject().put("key", key).toString()

    fun keyOf(text: String?): String? = try { JSONObject(text ?: "").getString("key").takeIf { it.isNotEmpty() } } catch (_: Exception) { null }

    fun ids(ids: Collection<String>): String = JSONObject().put("ids", JSONArray(ids.toList())).toString()

    fun ids(text: String?): List<String> = try {
        val a = JSONObject(text ?: "").getJSONArray("ids")
        (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotEmpty() } }
    } catch (_: Exception) {
        emptyList()
    }

    fun ok(ok: Boolean): String = JSONObject().put("ok", ok).toString()

    fun ok(text: String?): Boolean = try { JSONObject(text ?: "").optBoolean("ok") } catch (_: Exception) { false }

    private fun ref(a: ActionRef): JSONObject = JSONObject().put("i", a.index).put("semantic", a.semantic).put("auth", a.authRequired).put("ui", a.showsUserInterface)
        .apply { a.resultKey?.let { put("key", it) } }

    private fun ref(o: JSONObject?): ActionRef? = o?.let {
        ActionRef(it.optInt("i", -1).takeIf { i -> i >= 0 } ?: return null, it.optString("key").takeIf { k -> k.isNotEmpty() }, it.optBoolean("semantic"),
            it.optBoolean("auth"), it.optBoolean("ui"))
    }

    fun thread(t: MessageThread): JSONObject = JSONObject().apply {
        put("app", t.app.name)
        put("conv", t.conversationId)
        put("nkey", t.notificationKey)
        t.title?.let { put("title", it) }
        put("group", t.isGroup)
        put("posted", t.postedAt)
        t.hidden?.let { put("hidden", it.name) }
        t.reply?.let { put("reply", ref(it)) }
        t.markRead?.let { put("mark", ref(it)) }
        put("messages", JSONArray().apply {
            for (m in t.messages) put(JSONObject().apply {
                put("id", m.id)
                m.senderName?.let { put("name", it) }
                m.senderKey?.let { put("from", it) }
                put("text", m.text)
                put("time", m.time)
                if (m.fromMe) put("me", true)
                m.attachment?.let { put("att", it.name) }
                if (m.historic) put("old", true)
                if (m.readByRobot) put("read", true)
            })
        })
    }

    fun thread(o: JSONObject): MessageThread? = try {
        val app = MessageApp.valueOf(o.getString("app"))
        val conv = o.getString("conv")
        val group = o.optBoolean("group")
        val reply = ref(o.optJSONObject("reply"))
        val arr = o.optJSONArray("messages") ?: JSONArray()
        val msgs = (0 until arr.length()).mapNotNull { i ->
            val m = arr.optJSONObject(i) ?: return@mapNotNull null
            IncomingMessage(
                id = m.getString("id"), app = app, conversationId = conv, senderName = m.optString("name").takeIf { it.isNotEmpty() },
                senderKey = m.optString("from").takeIf { it.isNotEmpty() }, text = m.optString("text"), time = m.optLong("time"), isGroup = group,
                replyable = reply != null, fromMe = m.optBoolean("me"),
                attachment = m.optString("att").takeIf { it.isNotEmpty() }?.let { a -> AttachmentKind.entries.firstOrNull { it.name == a } },
                historic = m.optBoolean("old"), readByRobot = m.optBoolean("read"),
            )
        }.take(NotificationParsing.MAX_MESSAGES)
        MessageThread(app, conv, o.getString("nkey"), o.optString("title").takeIf { it.isNotEmpty() }, group, msgs, reply, ref(o.optJSONObject("mark")),
            o.optString("hidden").takeIf { it.isNotEmpty() }?.let { h -> HiddenWhy.entries.firstOrNull { it.name == h } }, o.optLong("posted"))
    } catch (_: Exception) {
        null
    }
}
