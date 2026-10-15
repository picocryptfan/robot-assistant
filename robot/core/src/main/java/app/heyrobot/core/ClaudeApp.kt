// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

// Pure. The Claude app route (docs/ask-claude.md "The Claude app route"): Robot opens the official Claude Android app with
// the question, the way it opens Organic Maps, and steps aside; your claude.ai plan answers it there. Robot only
// prefills: you press Send. It never drives, types into or reads the app in the background (Anthropic's Consumer
// Terms forbid automated access to the apps); the only read-back is your own corner swipe ([ClaudeAppAnswer]).
// The app is closed source and its intent filters are unverified, so the hand-off is resolved on the phone at run time.

object ClaudeAppLink {
    const val PACKAGE = "com.anthropic.claude"

    fun appLink(q: String): String = "claude://claude.ai/new?q=" + encode(AppHandOff.cap(q))

    fun webLink(q: String): String = "https://claude.ai/new?q=" + encode(AppHandOff.cap(q))

    /**
     * The way to hand off, or null when the app isn't installed (NO_APP, "Install Claude"). [resolves] says whether a
     * way resolves to the app's package (resolveActivity / queryIntentActivities with setPackage, on the phone).
     */
    fun choose(installed: Boolean, resolves: (AppHandOff.Way) -> Boolean): AppHandOff.Way? = AppHandOff.choose(PACKAGE, installed, resolves)

    /**
     * RFC 3986 percent-encoding of the UTF-8 bytes: everything but the unreserved `A-Z a-z 0-9 - . _ ~` is encoded, a
     * space as %20 (never +), so `& ? # % +`, emoji and line breaks arrive as written.
     */
    fun encode(s: String): String {
        val sb = StringBuilder()
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            if (c in 'A'.code..'Z'.code || c in 'a'.code..'z'.code || c in '0'.code..'9'.code || c == '-'.code || c == '.'.code || c == '_'.code || c == '~'.code) {
                sb.append(c.toChar())
            } else {
                sb.append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
            }
        }
        return sb.toString()
    }

    private const val HEX = "0123456789ABCDEF"
}

/**
 * "Read Claude's answer": the last assistant message in the Claude app, from the text of the screen your corner swipe
 * handed to Robot (AssistStructure, in tree order). No accessibility service and nothing in the background: you opened
 * the panel over the app, and only then. Heuristics, tested on synthetic node lists: the text after your last message
 * (the question Robot handed off, when Robot knows it), otherwise the longest recent block with the paragraphs around
 * it. Null when nothing usable is found ("Ask about this screen" is offered instead).
 */
object ClaudeAppAnswer {
    /** An answer shorter than this is not worth reading out (a button, a title). */
    const val MIN_CHARS = 20
    /** Only the end of the screen counts as "recent". */
    private const val RECENT = 24

    /**
     * The app's own words around a conversation: buttons, hints, disclaimers, the model picker ("Opus 5.5", "Sonnet 5.5
     * · Extended"), never an answer. A model name inside a sentence is answer text and stays.
     */
    private val CHROME = Regex(
        """^(copy|retry|share|edit|like|dislike|good response|bad response|read aloud|stop|send|new chat|chats|projects|artifacts|settings|""" +
            """reply to claude|message claude|talk to claude|write a message|claude can make mistakes.*|claude is ai and can make mistakes.*|""" +
            """please double.check responses.*|(claude )?(opus|sonnet|haiku) \d+(\.\d+)?( ?[·•-] ?\w+)?|claude|menu|back|more options|attach|voice|""" +
            """\d{1,2}:\d{2}( ?[ap]m)?|today|yesterday|thinking|searching.*|research)$""",
        RegexOption.IGNORE_CASE,
    )

    private fun chrome(s: String) = s.length < 4 || CHROME.matches(s.trim())

    /** One answer paragraph: prose, not a label. */
    private fun prose(s: String) = s.length >= 40 || (s.length >= MIN_CHARS && s.trimEnd().lastOrNull() in setOf('.', '!', '?', ':', '…'))

    fun extract(pieces: List<String>, asked: String? = null): String? {
        val lines = pieces.map { it.replace(Regex("[ \\t]+"), " ").trim() }.filter { it.isNotEmpty() && !chrome(it) }
        if (lines.isEmpty()) return null
        // 1. After your last message: the question Robot handed off, as the app shows it. Found with nothing after
        //    it: Claude hasn't answered yet (or Send wasn't pressed), so there is no answer to read.
        val key = asked?.let(::norm)?.takeIf { it.length >= 8 }?.take(80)
        if (key != null) {
            val at = lines.indexOfLast { val n = norm(it); n.contains(key) || (n.length >= 8 && key.contains(n)) }
            if (at >= 0) return join(lines.drop(at + 1).filter(::prose))
        }
        val recent = lines.takeLast(RECENT)
        // 2. After the last short question line (most likely your message): the prose that follows it.
        val q = recent.indexOfLast(::looksAsked)
        if (q >= 0) join(recent.drop(q + 1).takeWhile(::prose))?.let { return it }
        // 3. The longest recent block that isn't a question, with the prose paragraphs around it (one answer, several nodes).
        val best = recent.indices.filter { !looksAsked(recent[it]) }.maxByOrNull { recent[it].length } ?: return null
        if (!prose(recent[best])) return null
        var from = best
        while (from > 0 && prose(recent[from - 1]) && !looksAsked(recent[from - 1])) from--
        var to = best
        while (to < recent.lastIndex && prose(recent[to + 1])) to++
        return join(recent.subList(from, to + 1))
    }

    /** A short question line just above an answer is your message, not part of the answer. */
    private fun looksAsked(s: String) = s.length < 160 && s.trimEnd().endsWith("?")

    private fun join(ps: List<String>): String? = ps.joinToString("\n\n").trim().takeIf { it.length >= MIN_CHARS }

    private fun norm(s: String) = HelperRouter.plain(s).replace(Regex("[^a-z0-9]+"), " ").trim()
}
