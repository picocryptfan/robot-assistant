package app.heyrobot.core

import org.json.JSONArray
import org.json.JSONObject

// Pure. Conversation history in app-private storage (one JSON file per conversation). Pictures and
// screen text are never stored: a turn records only that one was attached.

enum class Attachment { NONE, PHOTO, SCREEN }

enum class TurnRole { USER, ASSISTANT }

/**
 * An action in a reply, as it ended up. [content]: a read tool brought content Robot didn't write into the
 * conversation (its findings themselves are not stored), so every later action in it asks first.
 */
data class ActionRecord(val tool: ToolName, val summary: String, val status: ActionStatus, val detail: String? = null, val content: Boolean = false)

data class ChatTurn(
    val id: String,
    val role: TurnRole,
    val text: String,
    val time: Long,
    val attachment: Attachment = Attachment.NONE,
    val actions: List<ActionRecord> = emptyList(),
    /** The reply was stopped before it finished. */
    val stopped: Boolean = false,
    /** The reply failed; [text] holds the plain-language error. */
    val error: Boolean = false,
)

data class StoredConversation(val id: String, val created: Long, val updated: Long, val turns: List<ChatTurn>) {
    val title: String get() = titleOf(turns)
    val preview: String get() = turns.lastOrNull { it.role == TurnRole.ASSISTANT && !it.error }?.text?.let(::oneLine)?.take(120) ?: ""

    companion object {
        fun titleOf(turns: List<ChatTurn>): String {
            val first = turns.firstOrNull { it.role == TurnRole.USER } ?: return "New conversation"
            val t = oneLine(first.text)
            val base = t.ifEmpty { if (first.attachment == Attachment.PHOTO) "Photo" else if (first.attachment == Attachment.SCREEN) "Screen" else "Conversation" }
            return if (base.length <= 60) base else base.take(57).trimEnd() + "…"
        }

        private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
    }
}

/** History as the model sees it when a conversation is rebuilt: text only, actions summarised. */
fun List<ChatTurn>.toPromptTurns(): List<PromptTurn> = mapNotNull { t ->
    when (t.role) {
        TurnRole.USER -> PromptTurn(PromptRole.USER, Prompt.replayText(t.text, t.attachment))
        TurnRole.ASSISTANT -> {
            if (t.error) return@mapNotNull null
            val acts = t.actions.joinToString(" ") { "(${Markdown.plain(it.summary)}: ${it.status.label.lowercase()}.)" }
            val text = listOf(acts, t.text).filter { it.isNotBlank() }.joinToString(" ").trim()
            if (text.isEmpty()) null else PromptTurn(PromptRole.MODEL, text)
        }
    }
}

object ConversationCodec {
    const val VERSION = 1

    fun toJson(c: StoredConversation): String = JSONObject().apply {
        put("v", VERSION)
        put("id", c.id)
        put("created", c.created)
        put("updated", c.updated)
        put("turns", JSONArray().apply { c.turns.forEach { put(turn(it)) } })
    }.toString()

    fun fromJson(text: String): StoredConversation? = try {
        val o = JSONObject(text)
        val arr = o.optJSONArray("turns") ?: JSONArray()
        StoredConversation(
            id = o.getString("id"),
            created = o.optLong("created"),
            updated = o.optLong("updated"),
            turns = (0 until arr.length()).mapNotNull { turn(arr.optJSONObject(it) ?: return@mapNotNull null) },
        )
    } catch (_: Exception) { null }

    private fun turn(t: ChatTurn) = JSONObject().apply {
        put("id", t.id)
        put("role", t.role.name)
        put("text", t.text)
        put("time", t.time)
        if (t.attachment != Attachment.NONE) put("attachment", t.attachment.name)
        if (t.stopped) put("stopped", true)
        if (t.error) put("error", true)
        if (t.actions.isNotEmpty()) put("actions", JSONArray().apply {
            t.actions.forEach { a ->
                put(JSONObject().put("tool", a.tool.id).put("summary", a.summary).put("status", a.status.name).apply {
                    a.detail?.let { put("detail", it) }
                    if (a.content) put("content", true)
                })
            }
        })
    }

    private fun turn(o: JSONObject): ChatTurn? {
        val role = enumValues<TurnRole>().firstOrNull { it.name == o.optString("role") } ?: return null
        val acts = o.optJSONArray("actions")
        return ChatTurn(
            id = o.optString("id"),
            role = role,
            text = o.optString("text"),
            time = o.optLong("time"),
            attachment = enumOr(o.optString("attachment"), Attachment.NONE),
            stopped = o.optBoolean("stopped", false),
            error = o.optBoolean("error", false),
            actions = if (acts == null) emptyList() else (0 until acts.length()).mapNotNull { i ->
                val a = acts.optJSONObject(i) ?: return@mapNotNull null
                val tool = ToolName.of(a.optString("tool")) ?: return@mapNotNull null
                // A stored card can't be waiting any more (0.1.0 could save one that was): it was not done.
                val status = enumOr(a.optString("status"), ActionStatus.DONE).let { if (it == ActionStatus.PENDING) ActionStatus.DECLINED else it }
                ActionRecord(tool, a.optString("summary"), status, a.optString("detail").takeIf { it.isNotEmpty() }, a.optBoolean("content", false))
            },
        )
    }
}
