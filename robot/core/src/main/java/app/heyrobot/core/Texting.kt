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
            val msg = message(m.groupValues[2])
            if (describes(msg)) return null
            return TextRequest(name, msg)
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
        val number = NUMBER.matches(first)
        val (k, known) = when {
            number -> 1 to true
            !NAME_WORD.matches(first) || first.lowercase(Locale.ROOT) in NOT_NAMES -> return null
            else -> nameBoundary(raw, contacts, typed)
        }
        val name = raw.take(k).joinToString(" ") { bare(it) }
        // Punctuation right after the name ("Mom: hi", "Mom, hi") belongs to neither.
        val msg = message(raw.drop(k).joinToString(" "))
        if (needMessage && msg == null) return null
        // "write a message to my landlord about the heater" says what to write, not the text itself: the model's job.
        if (describes(msg)) return null
        // No message and nobody known by that name ("text recognition"): not a text, unless it's a family word.
        if (msg == null && !known && first.lowercase(Locale.ROOT) !in KIN) return null
        return TextRequest(name, msg)
    }

    /** A "message" that describes the text rather than being it ("about the heater"). */
    private fun describes(msg: String?): Boolean = msg != null && Regex("^(?:about|regarding|re:?)\\b", RegexOption.IGNORE_CASE).containsMatchIn(msg)

    /**
     * How many words the name takes: the longest of 1 to 3 that matches a contact's name or nickname exactly or as its
     * start (ContactMatcher), else 1, or 2 when a typed second word is capitalised and no message word ("Text Sarah
     * Connor I'm late"). A family word ("mom") is a name of its own.
     */
    fun nameLength(words: List<String>, contacts: List<String>, typed: Boolean): Int = nameBoundary(words, contacts, typed).first

    /**
     * [nameLength] and whether the name is a contact's. Two or three words must be the contact's whole words ("Dan
     * Can…" is not "Dan Cannon"), and a message word ("Can", "I'm") never continues a name; one word may be the start
     * of a name ("Alex" for "Alexandra Roy").
     */
    fun nameBoundary(words: List<String>, contacts: List<String>, typed: Boolean): Pair<Int, Boolean> {
        val w = words.map(::bare)
        val maxK = minOf(MAX_NAME_WORDS, w.size)
        for (k in maxK downTo 1) {
            val cand = w.take(k)
            if (cand.any { !NAME_WORD.matches(it) || it.lowercase(Locale.ROOT) in NOT_NAMES }) continue
            if (k >= 2 && cand.drop(1).any { it.lowercase(Locale.ROOT) in STARTERS }) continue
            val q = cand.joinToString(" ")
            val qw = ContactMatcher.words(q)
            val hit = contacts.any { c ->
                if (k == 1) ContactMatcher.kind(q, c).let { it == NameMatch.EXACT || it == NameMatch.PREFIX }
                else ContactMatcher.words(c).let { cw -> cw.size >= qw.size && cw.subList(0, qw.size) == qw }
            }
            if (hit) return k to true
        }
        if (w[0].lowercase(Locale.ROOT) in KIN) return 1 to false
        if (typed && w.size >= 2) {
            val second = w[1]
            val surname = second.firstOrNull()?.isUpperCase() == true && NAME_WORD.matches(second) &&
                second.lowercase(Locale.ROOT) !in STARTERS && second.lowercase(Locale.ROOT) !in NOT_NAMES && second.length > 1
            if (surname) return 2 to false
        }
        return 1 to false
    }

    /** Where a question goes, decided before any contact is read (pure). */
    enum class Route {
        /** Not a text: the model answers. */
        MODEL,
        /** A text, but the phone is locked: say so; no contact is read and nothing is asked. */
        UNLOCK,
        /** The answer to "what should the text say?": it is the message. */
        ANSWER,
        /** "cancel" while Robot waits for the message. */
        CANCEL,
        /** A text: read the contacts' names for where the name ends, then parse again. */
        PARSE,
    }

    /**
     * [pendingName]: Robot asked what the text to this person should say. A new text request replaces that question;
     * the answer itself goes on to the approval gate, which handles a locked phone.
     */
    fun route(input: String, pendingName: String?, locked: Boolean, typed: Boolean): Route {
        val parsed = parse(input, emptyList(), typed) != null
        val shaped = parsed || looksLikeText(input)
        return when {
            pendingName != null && !shaped -> if (isCancel(input)) Route.CANCEL else Route.ANSWER
            !shaped -> Route.MODEL
            // Locked: only a sure text gets "unlock"; one that needs the contacts to tell ("text Bob") goes to the model,
            // whose send_text meets the gate's own lock rule.
            locked -> if (parsed) Route.UNLOCK else Route.MODEL
            else -> Route.PARSE
        }
    }

    /**
     * Shaped like a text even when the parse without contacts says no (a two-word contact name with no message, which
     * only the contacts can tell apart from "text recognition").
     */
    private fun looksLikeText(input: String): Boolean {
        val s = POLITE.replace(input.trim(), "").trim()
        val m = TEXT.find(s) ?: SEND_TO.find(s) ?: return false
        val first = m.groupValues[1].trim().split(Regex("\\s+")).first().let(::bare)
        return NAME_WORD.matches(first) && first.lowercase(Locale.ROOT) !in NOT_NAMES
    }

    /** The message: without a leading "that", "saying" or ":", and without the quotes around it; null if empty. */
    fun message(raw: String): String? {
        var m = LEADING.replace(raw.trim(), "").trim()
        val pairs = listOf('"' to '"', '“' to '”', '\'' to '\'', '‘' to '’')
        for ((a, b) in pairs) if (m.length >= 2 && m.first() == a && m.last() == b) m = m.substring(1, m.length - 1).trim()
        return m.ifEmpty { null }
    }

    /**
     * What "cancel" can be, in plain words (ClaudeRouter.plain: no case, accents or apostrophes, so "don't" is "dont"
     * and "oublie ça" is "oublie ca"). Always the whole answer, filler aside: "cancel dinner tonight" is a message.
     */
    private val CANCEL = setOf(
        "cancel", "never mind", "nevermind", "forget it", "forget about it", "scratch that", "nah", "no", "nope", "stop", "dont",
        "dont send", "dont send it", "dont bother", "nothing", "abort", "skip it", "no thanks", "no thank you",
        "annule", "annuler", "annulez", "laisse faire", "laisse tomber", "laisse ca", "oublie", "oublie ca", "non",
        "non merci", "arrete", "arrete ca", "rien",
    )
    /** Allowed in front of a cancel word: the wake phrase (heard whole while Robot waits), "no,", "please", "okay"… */
    private val CANCEL_LEAD: List<List<String>> = listOf(
        listOf("hey", "robot"), listOf("hi", "robot"), listOf("ok", "robot"), listOf("okay", "robot"), listOf("he", "robot"),
        listOf("dis", "robot"), listOf("robot"), listOf("oh"), listOf("ah"), listOf("um"), listOf("uh"), listOf("euh"), listOf("well"),
        listOf("ok"), listOf("okay"), listOf("no"), listOf("non"), listOf("please"), listOf("sorry"), listOf("actually"),
        listOf("en", "fait"), listOf("finalement"), listOf("desole"), listOf("just"),
    )
    /** ... and after it: "that", "it", "ça", "the text", "please", "merci"… */
    private val CANCEL_TAIL: List<List<String>> = listOf(
        listOf("please"), listOf("thanks"), listOf("thank", "you"), listOf("merci"), listOf("sil", "te", "plait"),
        listOf("sil", "vous", "plait"), listOf("that"), listOf("it"), listOf("this"), listOf("ca"), listOf("then"), listOf("alors"),
        listOf("the", "text"), listOf("the", "message"), listOf("le", "texto"), listOf("le", "texte"), listOf("le", "message"),
        listOf("for", "now"), listOf("robot"),
    )

    /** [w] without one [filler] at its front (or back); null if none is there or nothing would be left. */
    private fun strip(w: List<String>, filler: List<List<String>>, front: Boolean): List<String>? {
        val f = filler.firstOrNull { f -> w.size > f.size && (if (front) w.subList(0, f.size) else w.subList(w.size - f.size, w.size)) == f } ?: return null
        return if (front) w.drop(f.size) else w.dropLast(f.size)
    }

    /**
     * "cancel", "never mind", "annule": you changed your mind while Robot asked what the text should say. Case, accents
     * and punctuation don't count ("Cancel?", "Annule ça !"), nor filler around the words ("Hey robot, cancel.", "No,
     * cancel it please", "Oublie ça, merci"). The whole answer must be one of them: "no problem, see you soon" is a message.
     */
    fun isCancel(input: String): Boolean {
        var w = ClaudeRouter.plain(input).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        // Each lead taken off, then each tail: "no" and "non" are cancel words before they are filler.
        while (w.isNotEmpty()) {
            var t: List<String>? = w
            while (t != null) {
                if (t.joinToString(" ") in CANCEL) return true
                t = strip(t, CANCEL_TAIL, front = false)
            }
            w = strip(w, CANCEL_LEAD, front = true) ?: break
        }
        return false
    }

    /** A wake phrase heard at the start of a spoken answer ("Hey Robot, I'm running late"): never part of the text. */
    private val DEFAULT_WAKE = listOf("hey robot", "hi robot", "ok robot", "okay robot", "hé robot", "dis robot")

    /**
     * The spoken answer to "what should it say?" without a leading wake phrase: the wake word is paused while Robot
     * listens, so "hey robot, I'm running late" comes back whole. [phrases]: your wake phrases. A single word ("robot")
     * counts only with punctuation after it ("Robot, I'm late"), so "Robot vacuum is fixed" stays. Nothing else goes:
     * "Okay, see you at 5" is the text.
     */
    fun stripWake(answer: String, phrases: List<String> = emptyList()): String {
        val s = answer.trim()
        val all = (phrases.map(WakePhrases::clean) + DEFAULT_WAKE + "robot").filter { it.isNotBlank() }.distinct().sortedByDescending { it.length }
        for (p in all) {
            // Letters as they are, an apostrophe either way; the words may be split by commas ("Hey, robot").
            val words = p.split(' ').joinToString("[\\s,]+") { w -> w.map { c -> if (c == '\'') "['’]" else Regex.escape(c.toString()) }.joinToString("") }
            val after = if (p.contains(' ')) "\\s*[,.!?:;—–-]+\\s*|\\s+|$" else "\\s*[,.!?:;—–-]+\\s*|$"
            val m = Regex("^$words(?:$after)", RegexOption.IGNORE_CASE).find(s) ?: continue
            return s.substring(m.range.last + 1).trim()
        }
        return s
    }

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
