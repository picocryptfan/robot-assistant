package app.heyrobot.core

import java.util.Locale

// Pure. Texting without the model (0.2.3). The small model's choice of tool is unreliable: "text mom" made it look Mom
// up (lookup_contact, which has no Cheogram fallback) or answer in words. A request that is plainly a text is read
// here instead and becomes send_text directly, through the same preparation, card, approval gate and lock-screen
// rule as the model's call. Narrow on purpose: "tell me a joke", "message me later" or "what's the text on this page"
// are left to the model.

/** "text NAME MESSAGE" and its kin, read by [TextCommand.parse]. [message] null: none given (Robot asks for it). */
data class TextRequest(val name: String, val message: String?)

object TextCommand {
    /** Words for family and close people: a whole name by themselves ("text mom"), never the start of a longer one. */
    val KIN = setOf(
        "mom", "mum", "mommy", "mummy", "mama", "mother", "ma", "dad", "daddy", "papa", "pa", "father", "grandma", "granny", "nana",
        "grandpa", "granddad", "grandad", "sis", "sister", "bro", "brother", "wife", "husband", "hubby", "son", "daughter", "aunt",
        "auntie", "uncle", "cousin", "boss", "babe", "honey", "sweetheart",
    )

    /** Never a name: who the request is about ("me", "him"), small words, and what "text" can mean otherwise. */
    private val NOT_NAMES = setOf(
        "me", "myself", "us", "you", "yourself", "him", "her", "them", "it", "this", "that", "these", "those", "the", "a", "an", "on",
        "in", "at", "for", "of", "about", "from", "with", "to", "is", "are", "was", "be", "what", "how", "when", "why", "who", "where",
        "which", "everyone", "everybody", "someone", "somebody", "anyone", "anybody", "nobody", "all", "here", "there", "later",
        "now", "today", "tomorrow", "again", "back", "please", "something", "nothing", "and", "or", "if", "my", "your", "our",
        "jokes", "joke", "story", "stories", "time", "truth", "difference", "more", "box", "field", "file", "message", "messages",
        "text", "texts", "size", "font", "editor",
    )

    /** A capitalised second word that starts a message, not a surname ("Text Sarah Hi there"). */
    private val STARTERS = setOf(
        "i", "i'm", "i'll", "i've", "i'd", "hi", "hey", "hello", "can", "could", "are", "do", "did", "does", "where", "what", "when",
        "call", "please", "don't", "we", "we're", "it's", "is", "will", "thanks", "thank", "sorry", "ok", "okay", "yes", "no", "love",
        "happy", "good", "see", "just", "let's", "running", "on", "the", "dinner", "lunch", "be", "have", "how", "why", "you", "your",
        "my", "meet", "pick", "bring", "remember", "don't", "come", "going", "got", "need", "want",
    )

    private const val MAX_NAME_WORDS = 3
    private val POLITE = Regex("^(?:(?:hey robot|robot|ok|okay|please|pls)[,!.]?\\s+)?(?:(?:can|could|would|will) you\\s+)?(?:please\\s+)?", RegexOption.IGNORE_CASE)
    private val SEND_TO = Regex("^(?:send|write)\\s+(?:a\\s+|an\\s+)?(?:text|txt|sms|message|msg)(?:\\s+message)?\\s+to\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val SEND_NAME = Regex("^(?:send|write)\\s+(.+?)\\s+(?:a|an)\\s+(?:text|txt|sms|message|msg)\\b[,:]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val LET_KNOW = Regex("^let\\s+(.+?)\\s+know\\b[,:]?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val TEXT = Regex("^(?:text|txt|sms|message|msg)\\s+(?:to\\s+)?(.+)$", RegexOption.IGNORE_CASE)
    private val TELL = Regex("^tell\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val LEADING = Regex("^(?:(?:that|saying|says|to say|reading)\\b[,:]?\\s*|[:,\\-–]\\s*)+", RegexOption.IGNORE_CASE)
    private val NUMBER = Regex("^\\+?[0-9][0-9().\\-]{5,}$")
    private val NAME_WORD = Regex("^[\\p{L}][\\p{L}'’.\\-]*$")

    /**
     * Reads [input] as a text to someone, or null. [contacts]: the phone's contact names and nicknames, for where the
     * name ends ("text Mary Ann hi" with a Mary Ann in the contacts). [typed]: the person typed it (a capital letter
     * then means a surname; speech to text capitalises everything it likes).
     */
    fun parse(input: String, contacts: List<String> = emptyList(), typed: Boolean = true): TextRequest? {
        val s = POLITE.replace(input.trim(), "").trim()
        if (s.isEmpty()) return null
        SEND_TO.find(s)?.let { return split(it.groupValues[1], contacts, typed, needMessage = false) }
        SEND_NAME.find(s)?.let { m ->
            val name = cleanName(m.groupValues[1]) ?: return null
            return TextRequest(name, message(m.groupValues[2]))
        }
        LET_KNOW.find(s)?.let { m ->
            val name = cleanName(m.groupValues[1]) ?: return null
            val msg = message(m.groupValues[2]) ?: return null
            return TextRequest(name, msg)
        }
        TEXT.find(s)?.let { return split(it.groupValues[1], contacts, typed, needMessage = false) }
        TELL.find(s)?.let { return split(it.groupValues[1], contacts, typed, needMessage = true) }
        return null
    }

    /** A name given whole ("let Sarah Connor know"): up to three name-like words, none of them a non-name. */
    private fun cleanName(raw: String): String? {
        val w = raw.trim().split(Regex("\\s+")).map(::bare).filter { it.isNotEmpty() }.dropWhile { it.lowercase(Locale.ROOT) == "my" }
        if (w.isEmpty() || w.size > MAX_NAME_WORDS) return null
        if (w.size == 1 && NUMBER.matches(w[0])) return w[0]
        if (w.any { !NAME_WORD.matches(it) || it.lowercase(Locale.ROOT) in NOT_NAMES }) return null
        return w.joinToString(" ")
    }

    /** A word without the punctuation speech to text or typing puts around it ("Mom," → "Mom"). */
    private fun bare(w: String): String = w.trim().trimEnd(',', '.', ':', ';', '!', '?').trim('"', '“', '”')

    /** The name at the start of [rest] and the message after it. */
    private fun split(rest: String, contacts: List<String>, typed: Boolean, needMessage: Boolean): TextRequest? {
        val raw = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.toMutableList()
        // "text my mom": whose, not who.
        if (raw.size > 1 && raw[0].lowercase(Locale.ROOT) == "my") raw.removeAt(0)
        if (raw.isEmpty()) return null
        val first = bare(raw[0])
        val k: Int = when {
            NUMBER.matches(first) -> 1
            !NAME_WORD.matches(first) || first.lowercase(Locale.ROOT) in NOT_NAMES -> return null
            else -> nameLength(raw, contacts, typed)
        }
        val name = raw.take(k).joinToString(" ") { bare(it) }
        // Punctuation right after the name ("Mom: hi", "Mom, hi") belongs to neither.
        val msg = message(raw.drop(k).joinToString(" "))
        if (needMessage && msg == null) return null
        return TextRequest(name, msg)
    }

    /**
     * How many words the name takes: the longest of 1 to 3 that matches a contact's name or nickname exactly or as its
     * start (ContactMatcher), else 1, or 2 when a typed second word is capitalised and no message word ("Text Sarah
     * Connor I'm late"). A family word ("mom") is a name of its own.
     */
    fun nameLength(words: List<String>, contacts: List<String>, typed: Boolean): Int {
        val w = words.map(::bare)
        val maxK = minOf(MAX_NAME_WORDS, w.size)
        for (k in maxK downTo 1) {
            val cand = w.take(k)
            if (cand.any { !NAME_WORD.matches(it) || it.lowercase(Locale.ROOT) in NOT_NAMES }) continue
            val q = cand.joinToString(" ")
            if (contacts.any { c -> ContactMatcher.kind(q, c).let { it == NameMatch.EXACT || it == NameMatch.PREFIX } }) return k
        }
        if (w[0].lowercase(Locale.ROOT) in KIN) return 1
        if (typed && w.size >= 2) {
            val second = w[1]
            val surname = second.firstOrNull()?.isUpperCase() == true && NAME_WORD.matches(second) &&
                second.lowercase(Locale.ROOT) !in STARTERS && second.lowercase(Locale.ROOT) !in NOT_NAMES && second.length > 1
            if (surname) return 2
        }
        return 1
    }

    /** The message: without a leading "that", "saying" or ":", and without the quotes around it; null if empty. */
    fun message(raw: String): String? {
        var m = LEADING.replace(raw.trim(), "").trim()
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’')
        for ((a, b) in pairs) if (m.length >= 2 && m.first() == a && m.last() == b) m = m.substring(1, m.length - 1).trim()
        return m.ifEmpty { null }
    }

    /** "cancel", "never mind": the person changed their mind while Robot asked what the text should say. */
    fun isCancel(input: String): Boolean =
        input.trim().trimEnd('.', '!').lowercase(Locale.ROOT).replace('’', '\'') in setOf(
            "cancel", "never mind", "nevermind", "forget it", "no", "nothing", "stop", "don't", "dont", "cancel it", "no thanks", "nope",
        )

    /** "What should the text to Mom say?" (the name as asked, capitalised). */
    fun askMessage(name: String): String = "What should the text to ${display(name)} say?"

    fun display(name: String): String = name.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.ROOT) } }
}

/**
 * lookup_contact found no one (0.2.3): with Cheogram as the texting app the person may be in its own roster, which
 * Robot can't read. The model gets a fixed code saying so (and the system prompt says to call send_text with the name
 * anyway); the card gets an "Open Cheogram" button.
 */
object LookupFallback {
    const val CODE_MAY_BE_IN_CHEOGRAM = "not_in_phone_contacts_may_be_in_cheogram"

    /** [detail] for the card, [forModel] for the tool response, [openCheogram]: the card offers to open Cheogram. */
    data class NotFound(val detail: String, val forModel: String, val openCheogram: Boolean)

    fun notFound(name: String, textApp: TextApp, cheogramInstalled: Boolean): NotFound =
        if (textApp == TextApp.CHEOGRAM && cheogramInstalled) {
            NotFound("No contact matches “$name” on the phone. They may be in Cheogram's own list.", CODE_MAY_BE_IN_CHEOGRAM, true)
        } else {
            val d = "No contact matches “$name”."
            NotFound(d, d, false)
        }
}
