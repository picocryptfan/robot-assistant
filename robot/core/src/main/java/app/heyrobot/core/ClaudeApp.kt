package app.heyrobot.core

// Pure. The Claude app route (docs/ask-claude.md "The Claude app route"): Robot opens the official Claude Android app with
// the question, the way it opens Organic Maps, and steps aside; your claude.ai plan answers it there. Robot only
// prefills: you press Send. It never drives, types into or reads the app in the background (Anthropic's Consumer
// Terms forbid automated access to the apps); the only read-back is your own corner swipe ([ClaudeAppAnswer]).
// The app is closed source and its intent filters are unverified, so the hand-off is resolved on the phone at run time.

object ClaudeAppLink {
    const val PACKAGE = "com.anthropic.claude"
    /** At most this many characters of the question go in the link. */
    const val MAX_CHARS = 2_000

    /** a, b, c, d in the order Robot tries them; the first that resolves to the Claude app is used. */
    enum class Way {
        /** VIEW `claude://claude.ai/new?q=…` (documented for Claude Desktop; unverified on Android). */
        APP_LINK,
        /** VIEW `https://claude.ai/new?q=…` with the package set: only with verified app links for that path. */
        WEB_LINK,
        /** SEND text/plain with EXTRA_TEXT to the package. */
        SEND,
        /** Its launch intent only; the question goes on the clipboard and Robot says "Paste your question in Claude". */
        LAUNCH,
    }

    fun appLink(q: String): String = "claude://claude.ai/new?q=" + encode(cap(q))

    fun webLink(q: String): String = "https://claude.ai/new?q=" + encode(cap(q))

    /**
     * The way to hand off, or null when the app isn't installed (NO_APP, "Install Claude"). [resolves] says whether a
     * way resolves to the app's package (resolveActivity / queryIntentActivities with setPackage, on the phone).
     */
    fun choose(installed: Boolean, resolves: (Way) -> Boolean): Way? = AppHandOff.choose(PACKAGE, installed, resolves)

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

    /**
     * At most [max] characters, cut after the last sentence that fits when there is one in the second half, otherwise
     * on a space, never inside an emoji.
     */
    fun cap(s: String, max: Int = MAX_CHARS): String {
        val t = s.trim()
        if (t.length <= max) return t
        val head = ToolCalls.safeTake(t, max)
        val sentence = Regex("""[.!?…](?=\s)""").findAll(head).lastOrNull()?.range?.last
        if (sentence != null && sentence >= max / 2) return head.substring(0, sentence + 1)
        val space = head.lastIndexOf(' ')
        return if (space >= max / 2) head.substring(0, space).trimEnd() else head
    }

    /**
     * "Go get Claude" with no question: one to three sentences from the conversation's own words (never written by the
     * local model): what the user last asked and the start of Robot's answer. Null for an empty conversation (the app
     * opens without text). In a personal conversation (screen text, a photo, what Robot read, a read-back) Robot's
     * answer is not quoted: only the user's own question goes on the card.
     */
    fun summary(turns: List<ChatTurn>): String? {
        val asked = turns.lastOrNull { it.role == TurnRole.USER && it.text.isNotBlank() && ClaudeRouter.route(it.text) == null } ?: return null
        val q = oneLine(asked.text).let { ToolCalls.safeTake(it, 400).trimEnd() + if (it.length > 400) "…" else "" }
        val reply = turns.drop(turns.indexOf(asked) + 1).firstOrNull { it.role == TurnRole.ASSISTANT && !it.error && it.text.isNotBlank() }
        val said = reply?.takeIf { ClaudeContext.personal(turns).isEmpty() }?.let { firstSentence(it.text) }
        return buildString {
            append("I asked my phone's assistant: “").append(q.trimEnd('.', '?', '!')).append("”.")
            if (said != null) append(" It said: “").append(said.trimEnd('.')).append("”.")
            append(" Can you help with that?")
        }
    }

    private fun firstSentence(s: String): String {
        val t = oneLine(Markdown.plain(s))
        val end = Regex("""[.!?](?=\s|$)""").find(t)?.range?.last
        val first = if (end != null) t.substring(0, end + 1) else t
        return if (first.length <= 300) first else ToolCalls.safeTake(first, 300).trimEnd() + "…"
    }

    private fun oneLine(s: String) = s.replace(Regex("\\s+"), " ").trim()
}

/**
 * 1.0.0: the hand-off to any helper's app (the Claude app's route, for every company with an Android app). Robot opens
 * the app with the question and steps aside; you press Send there. The Claude app keeps its links first (a, b); every
 * app is tried with a share of the text (SEND text/plain to its package), else just opened. The question always goes on
 * the clipboard too, so it can be pasted when an app ignores the share. Never automation, never reading the app.
 */
object AppHandOff {
    /** The ways tried for [pkg], in order; [Way.LAUNCH] last. */
    fun ways(pkg: String): List<ClaudeAppLink.Way> =
        if (pkg == ClaudeAppLink.PACKAGE) listOf(ClaudeAppLink.Way.APP_LINK, ClaudeAppLink.Way.WEB_LINK, ClaudeAppLink.Way.SEND, ClaudeAppLink.Way.LAUNCH)
        else listOf(ClaudeAppLink.Way.SEND, ClaudeAppLink.Way.LAUNCH)

    /** The first way that resolves to [pkg], or null when it isn't installed. */
    fun choose(pkg: String, installed: Boolean, resolves: (ClaudeAppLink.Way) -> Boolean): ClaudeAppLink.Way? {
        if (!installed) return null
        val w = ways(pkg)
        return w.dropLast(1).firstOrNull(resolves) ?: w.last().takeIf(resolves)
    }

    /** The card's line while it waits: what goes, to which app, and that you send it ([app]: "ChatGPT"). */
    fun note(app: String, text: String): String =
        if (text.isBlank()) "Robot opens the $app app. Nothing goes to $app from Robot."
        else "Robot opens the $app app with this text and copies it. Nothing is sent until you press Send in $app."

    /** What happened, for the card and the voice: [way] was used, [text] went with it (empty: the app only). */
    fun done(app: String, way: ClaudeAppLink.Way, text: String): String = when {
        text.isEmpty() -> "Opened $app."
        way == ClaudeAppLink.Way.LAUNCH -> "Opened $app. Paste your question in $app: it's on the clipboard."
        else -> "Opened $app with your question: press Send there if it isn't sent. It's on the clipboard too."
    }

    /** No helper on opens an app, or its app isn't installed. */
    fun install(app: String?): String = if (app == null) "No helper opens an app. Set one up in Robot's settings, under Helpers." else "Install $app."
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

    private fun norm(s: String) = ClaudeRouter.plain(s).replace(Regex("[^a-z0-9]+"), " ").trim()
}
