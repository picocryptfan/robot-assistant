// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

/**
 * 1.0.2. Pure. With your own GGUF file, llama.cpp refuses a prompt in which a message other than Robot's system prompt
 * still holds the text of one of the model's control tokens (robot_llama.cpp's PREP_CONTROL_TEXT: the backstop for what
 * ControlText didn't break). The refusal stands. Before 1.0.2 the turn that held the text stayed in the conversation, so
 * every later question in it was refused too. Now LlamaEngine reads each message alone again to find which ones held it
 * and says so in the error's detail ([detail], [parse]): the new question, and past turns by their index in
 * Generate.history. The panel leaves those turns out of what the model is given from then on ([recover],
 * ChatTurn.leftOut) and says what happened ([text]).
 *
 * A failed question is never replayed anyway (its error reply isn't, and PromptFit.alternating drops a question with no
 * reply), so what kept failing was an earlier turn that had an answer: a helper's answer, messages read, a web answer,
 * a reply written while another model was loaded.
 */
object ControlRefusal {
    /** BrainMsg.Error.detail for this refusal (1.0.1 sent it alone); 1.0.2 adds what held the text after a colon. */
    const val DETAIL = "control"

    /** At most this many past turns are named in a detail. */
    const val MAX_NAMED = 64

    /** Your message (or what went with it) held the text. */
    const val QUESTION = "Robot didn't read your message: it holds text this model treats as a command. Try asking without that part."

    /** An earlier turn held it: it is left out from now on. */
    const val EARLIER = "Part of this conversation holds text this model treats as a command, so Robot leaves it out from now on. Ask again."

    /** Nothing found on its own (the check itself failed): the conversation can't go on with this model. */
    const val UNKNOWN = "This conversation holds text this model treats as a command, so Robot couldn't read it. Start a new conversation."

    /** Which messages held such a text: the new [question], and past turns by their index in Generate.history. */
    data class Found(val question: Boolean = false, val history: List<Int> = emptyList()) {
        val none: Boolean get() = !question && history.isEmpty()
    }

    /** The error's detail for [f]: "control" alone when nothing was found, else "control:q,2,5" ("q": the question). */
    fun detail(f: Found): String =
        if (f.none) DETAIL
        else DETAIL + ":" + (listOfNotNull("q".takeIf { f.question }) + f.history.take(MAX_NAMED).map(Int::toString)).joinToString(",")

    /** What a detail names; null when it isn't this refusal's. */
    fun parse(detail: String?): Found? {
        if (detail == DETAIL) return Found()
        if (detail == null || !detail.startsWith("$DETAIL:")) return null
        var question = false
        val history = sortedSetOf<Int>()
        for (p in detail.substring(DETAIL.length + 1).split(',')) {
            if (p == "q") question = true
            else p.toIntOrNull()?.takeIf { it >= 0 && history.size < MAX_NAMED }?.let { history += it }
        }
        return Found(question, history.toList())
    }

    /**
     * Each message read alone by [refused] (as llama.cpp reads it: the text sent, after ControlText): the new [question],
     * then every past turn's text in [history] (Generate.history's, in its order).
     */
    fun find(question: String, history: List<String>, refused: (String) -> Boolean): Found =
        Found(refused(question), history.indices.filter { refused(history[it]) })

    /** What the panel says for [f]. */
    fun text(f: Found): String = when {
        f.history.isNotEmpty() -> EARLIER
        f.question -> QUESTION
        else -> UNKNOWN
    }

    /**
     * [chat] with the turns [f] names left out (ChatTurn.leftOut). [sent]: the turns the refused request's history was
     * made from (Generate.history is their toPromptTurns, which skips some: an error reply, an empty one, one already left
     * out), so its indices count only the turns that became a message. A question left out takes its replies with it, so
     * none is replayed as the answer to another. The new question (the newest of your turns) is left out when it held the
     * text, and when nothing was found.
     */
    fun recover(chat: List<ChatTurn>, sent: List<ChatTurn>, f: Found): List<ChatTurn> {
        val messages = sent.filter { listOf(it).toPromptTurns().isNotEmpty() }
        val ids = f.history.mapNotNullTo(HashSet()) { messages.getOrNull(it)?.id }
        if (f.question || f.none) chat.lastOrNull { it.role == TurnRole.USER }?.let { ids += it.id }
        var inLeftOutQuestion = false
        return chat.map { t ->
            if (t.role == TurnRole.USER) inLeftOutQuestion = t.id in ids
            if (t.id in ids || (inLeftOutQuestion && t.role == TurnRole.ASSISTANT)) t.copy(leftOut = true) else t
        }
    }
}
