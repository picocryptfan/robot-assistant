// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.memory

import app.heyrobot.core.ScreenText
import app.heyrobot.core.TokenEstimate

// Pure. Things Robot may offer to remember, built only from your own words: never Robot's answers,
// screen text, a photo, a web or helper answer, a message or a read tool's findings, so nothing anyone else wrote can
// plant a memory. After a conversation (the panel closes, or 10 minutes without a turn, with at least two of your
// turns), the local model gets your words alone in a one-shot request with no tools, and may list two lasting facts.
// Its lines pass the guards below; what survives waits in Settings › Memory › Suggestions (a quiet list, the default:
// never spoken, no pop-up), and nothing is saved without your tap.

/** Who wrote a turn's text. Only [USER_SAID] and [USER_TYPED] are your own words. */
enum class TurnSource {
    USER_SAID,
    USER_TYPED,
    ROBOT,
    SCREEN,
    PHOTO,
    WEB,
    MESSAGE,
    TOOL_RESULT,
    HELPER,
    TRANSCRIPT;

    val own: Boolean get() = this == USER_SAID || this == USER_TYPED
}

/** One turn of a finished conversation, as the suggestion job sees it. */
class SpokenTurn(val text: String, val source: TurnSource) {
    override fun toString(): String = "SpokenTurn(source=$source, chars=${text.length})" // l10n: not text (toString for logs and tests)
}

/** The one-shot request for the local model: no tools, ever ([tools] is always empty). */
class SuggestionRequest(val system: String, val user: String) {
    val tools: List<String> get() = emptyList()
}

object Suggestions {
    /** Suggestions kept from one conversation at most. */
    const val MAX = 2
    const val MAX_CHARS = 200
    /** Your words given to the model at most (estimated tokens), the newest kept. */
    const val INPUT_TOKENS = 800
    const val MIN_USER_TURNS = 2
    /** Minutes without a turn after which a conversation counts as finished. */
    const val IDLE_MINUTES = 10

    /** Fixed (byte-stable), English: the model lists facts "as the user would say them". */
    const val PROMPT = "Below is what the user said to their assistant. List at most 2 lasting facts about the user or people they " +
        "know that would help in future conversations (birthdays, family, preferences, routines), written as the user would " +
        "say them, one per line. Not one-time tasks, not passwords or codes, not health or money details unless stated as " +
        "something to remember. If nothing, write NONE."

    /**
     * Time to ask: suggestions on, at least [MIN_USER_TURNS] of your turns, Robot idle, not on battery saver, and the
     * conversation finished (panel closed or [IDLE_MINUTES] quiet).
     */
    fun due(enabled: Boolean, userTurns: Int, finished: Boolean, idle: Boolean, batterySaver: Boolean): Boolean =
        enabled && userTurns >= MIN_USER_TURNS && finished && idle && !batterySaver

    /**
     * Your own words from [turns], one turn per line, newest kept within [INPUT_TOKENS]; turns that hold a secret are
     * left out (a suggestion from them would be dropped anyway). Null when fewer than [MIN_USER_TURNS] remain.
     */
    fun userWords(turns: List<SpokenTurn>): String? {
        // 0.7 with messages: a conversation that held a message gives no suggestion at all, not even from your own words
        // in it (they may repeat or answer the message's): nothing from such a conversation becomes memory by itself.
        if (turns.any { it.source == TurnSource.MESSAGE }) return null
        val own = turns.filter { it.source.own }
            .map { ScreenText.sanitize(it.text).replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotEmpty() && !SecretDetector.isSecret(it) }
        if (own.size < MIN_USER_TURNS) return null
        val kept = ArrayDeque<String>()
        var tokens = 0
        for (t in own.asReversed()) {
            val c = TokenEstimate.text(t) + 1
            if (tokens + c > INPUT_TOKENS) break
            tokens += c
            kept.addFirst(t)
        }
        return if (kept.size < MIN_USER_TURNS) null else kept.joinToString("\n")
    }

    /**
     * A panel conversation's turns as the suggestion job sees them: your questions as your words, Robot's answers as
     * Robot's, a helper's (or the Claude app's) as a helper's, and every turn that holds a message (ChatTurn.messages: read,
     * summarized or translated, or an answer after one) as [TurnSource.MESSAGE], which makes [userWords] give nothing.
     */
    fun turnsOf(chat: List<app.heyrobot.core.ChatTurn>): List<SpokenTurn> = chat.map { t ->
        SpokenTurn(t.text, when {
            t.messages -> TurnSource.MESSAGE
            t.role == app.heyrobot.core.TurnRole.USER -> TurnSource.USER_TYPED
            t.fromHelper || t.readBack -> TurnSource.HELPER
            else -> TurnSource.ROBOT
        })
    }

    /** The request, or null when there aren't enough of your own words. */
    fun request(turns: List<SpokenTurn>): SuggestionRequest? = userWords(turns)?.let { SuggestionRequest(PROMPT, it) }

    /**
     * The suggestions to keep from the model's [output], given [userWords] (what [userWords] returned): at most [MAX],
     * each grounded in your words ([grounded]), not secret, not a question, not over [MAX_CHARS], not a note-like task
     * ("remember to …"), and not like something already kept, already suggested, or a "Don't suggest" example
     * ([similar]: cosine ≥ 0.90 with the embedding model; by default most of the same words).
     */
    fun accept(output: String, userWords: String, file: MemoryFile, now: Long,
        similar: (String, String) -> Boolean = ::sameWords, newId: () -> String = { MemoryIds.newId(now, prefix = 's') }): List<MemorySuggestion> {
        val known = file.items.map { it.text } + file.suggestions.map { it.text } + file.negatives
        val out = ArrayList<MemorySuggestion>()
        for (raw in output.lines()) {
            if (out.size >= MAX) break
            val line = clean(raw) ?: continue
            if (line.length > MAX_CHARS || '?' in line) continue
            if (SecretDetector.isSecret(line) || MemoryCommands.staysNote(line)) continue
            if (!grounded(line, userWords)) continue
            if (known.any { similar(it, line) } || out.any { similar(it.text, line) }) continue
            out += MemorySuggestion(newId(), line, if (MemoryCommands.prefers(line)) MemoryKind.PREFERENCE else MemoryKind.FACT, now)
        }
        return out
    }

    /** A model line without its bullet, number or quotes; null for "NONE" and blank lines. */
    fun clean(raw: String): String? {
        val t = ScreenText.sanitize(raw).trim()
            .replace(Regex("^(?:[-*•·]|\\d+[.)])\\s*"), "")
            .trim().trim('"', '“', '”', '«', '»').trim()
        if (t.isEmpty() || Regex("^(?:none|nothing|aucun|aucune|rien)\\W*$", RegexOption.IGNORE_CASE).matches(t)) return null
        return MemoryEdits.clean(t)
    }

    /**
     * Every name and number in [line] is in your words, and so are at least 60% of its search terms: the model may
     * rephrase what you said ("I" for "me"), but not add a person, a date or a fact you never said.
     */
    fun grounded(line: String, userWords: String): Boolean {
        val said = MemoryText.tokens(userWords).toSet()
        val terms = MemoryText.tokens(line)
        if (terms.isEmpty()) return false
        if (HybridRetriever.strongTokens(line).any { it !in said }) return false
        // The first word is capitalised whatever it is, so strongTokens skips it: it too must be yours ("Bob owes you…").
        val first = Regex("[\\p{L}\\p{N}][\\p{L}\\p{N}'’]*").find(line)?.value
        if (first != null && MemoryText.tokens(first).any { it !in said }) return false
        val digits = Regex("\\d+").findAll(line).map { it.value }.toSet()
        if (digits.any { d -> Regex("(?<!\\d)$d(?!\\d)").find(userWords) == null }) return false
        return terms.count { it in said } * 10 >= terms.size * 6
    }

    /** Most of the same search terms (Jaccard ≥ 0.8), or the same text. */
    fun sameWords(a: String, b: String): Boolean {
        if (MemoryEdits.sameText(a, b)) return true
        val x = MemoryText.tokens(a).toSet()
        val y = MemoryText.tokens(b).toSet()
        if (x.isEmpty() || y.isEmpty()) return false
        return (x intersect y).size * 10 >= (x union y).size * 8
    }
}
