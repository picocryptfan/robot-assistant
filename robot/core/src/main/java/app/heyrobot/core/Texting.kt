// SPDX-License-Identifier: GPL-3.0-only
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
     * the answer itself goes on to the approval gate, which handles a locked phone. [wakePhrases]: yours (a wake
     * phrase before "cancel" is not part of it).
     */
    fun route(input: String, pendingName: String?, locked: Boolean, typed: Boolean, wakePhrases: List<String> = emptyList()): Route {
        val parsed = parse(input, emptyList(), typed) != null
        val shaped = parsed || looksLikeText(input)
        return when {
            pendingName != null && !shaped -> if (isCancel(input, wakePhrases)) Route.CANCEL else Route.ANSWER
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

    /** How an answer cancelled the text: a cancel word ("cancel", "never mind", "annule"), or a bare "no" or "stop". */
    enum class Cancel { EXPLICIT, BARE }

    /**
     * Cancel words, in plain words (HelperRouter.plain: no case, accents or apostrophes, so "don't" is "dont" and
     * "j'ai changé d'avis" is "jai change davis"). Filler may come around them and several may come together ("Nah,
     * forget it", "Never mind, cancel", "Wait, cancel that text").
     */
    private val CANCEL = words(
        "cancel", "never mind", "nevermind", "forget", "forget it", "forget about it", "forget about", "scratch that", "abort", "skip",
        "dont send", "dont text", "changed my mind", "i changed my mind", "ive changed my mind", "i change my mind",
        "annule", "annuler", "annulez", "laisse faire", "laissez faire", "laisse tomber", "laissez tomber", "laisse ca", "oublie", "oubliez",
        "jai change davis", "change davis",
    )
    /**
     * "No", "stop" and the like: a cancel only as the whole answer, punctuation aside ("No.", "Non merci !"). With anything
     * around them they are a reply worth sending ("Sorry, no.", "No, thanks.", "Please stop.").
     */
    private val BARE = setOf(
        "no", "nope", "nah", "non", "stop", "dont", "dont bother", "nothing", "no thanks", "no thank you",
        "rien", "non merci", "arrete", "arretez",
    )
    /** Allowed in front of or between cancel words: the wake phrase (heard whole while Robot waits), "no,", "wait"… */
    private val CANCEL_LEAD = words(
        "hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "he robot", "dis robot", "robot",
        "oh", "ah", "um", "uh", "euh", "hmm", "hm", "well", "ok", "okay", "please", "sorry", "oops", "actually", "just", "wait", "hold on",
        "on second thought", "no", "nope", "nah", "stop", "en fait", "finalement", "desole", "attends", "attendez", "non", "arrete", "arretez",
    )
    /** ... and after them: "that", "it", "ça", "the text", "that message", "please", "merci"… */
    private val CANCEL_TAIL = words(
        "please", "thanks", "thank you", "merci", "sil te plait", "sil vous plait", "that", "it", "this", "ca", "then", "alors", "for now",
        "text", "the text", "that text", "this text", "my text", "the text message", "that text message", "message", "the message",
        "that message", "le texto", "le texte", "le message", "ce texto", "ce message", "robot",
    )

    private fun words(vararg phrases: String): List<List<String>> = phrases.map { it.split(' ') }

    /** The phrase of [list] that starts [w] at [i], longest first; null if none does. */
    private fun at(w: List<String>, i: Int, list: List<List<String>>): List<String>? =
        list.filter { p -> i + p.size <= w.size && p.indices.all { w[i + it] == p[it] } }.maxByOrNull { it.size }

    /**
     * Whether [input] cancels the text Robot asked about, and how. Case, accents and punctuation don't count ("Cancel?",
     * "Annule ça !"), nor a leading wake phrase ([wakePhrases]: yours; "hey robot" and "robot" always). A cancel word may
     * have filler around it ("No, cancel it please", "Wait, cancel that text", "Nah, forget it"); everything must be
     * used up, so "cancel dinner tonight" and "no problem, see you soon" are messages. A bare "no" or "stop" counts only
     * as the whole answer.
     */
    fun cancelKind(input: String, wakePhrases: List<String> = emptyList()): Cancel? {
        val plain = HelperRouter.plain(stripWake(input, wakePhrases, loose = true))
        if (plain.trim { !it.isLetterOrDigit() }.replace(Regex("\\s+"), " ") in BARE) return Cancel.BARE
        val w = plain.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        // Used up from the front, a phrase at a time; at least one must be a cancel word.
        var i = 0
        var cancelled = false
        while (i < w.size) {
            val c = at(w, i, CANCEL)
            val f = listOfNotNull(at(w, i, CANCEL_LEAD), at(w, i, CANCEL_TAIL)).maxByOrNull { it.size }
            when {
                c != null && c.size >= (f?.size ?: 0) -> { cancelled = true; i += c.size }
                f != null -> i += f.size
                else -> return null
            }
        }
        return if (cancelled) Cancel.EXPLICIT else null
    }

    fun isCancel(input: String, wakePhrases: List<String> = emptyList()): Boolean = cancelKind(input, wakePhrases) != null

    /**
     * Robot's reply to a cancel: "Okay, no text to Mom." After a bare "no", also how to send it anyway, since it may
     * have been the message ("To send “No thanks”, say “text Mom no thanks”.").
     */
    fun cancelReply(name: String, answer: String, kind: Cancel, wakePhrases: List<String> = emptyList()): String {
        val n = display(name)
        val said = stripWake(answer, wakePhrases, loose = true).trim { !it.isLetterOrDigit() }
        if (kind == Cancel.EXPLICIT || said.isEmpty()) return "Okay, no text to $n."
        return "Okay, no text to $n. To send “${said.replaceFirstChar { it.titlecase(Locale.ROOT) }}”, say “text $n ${said.lowercase(Locale.ROOT)}”."
    }

    /** Wake phrases Robot strips without your list: "hey robot" and its kin, and "robot" alone. */
    private val DEFAULT_WAKE = listOf("hey robot", "hi robot", "ok robot", "okay robot", "hello robot", "hé robot", "dis robot", "robot")

    /** A word and where it is: letters and digits, with the apostrophes inside it ("I'm"). */
    private class Tok(val w: String, val start: Int, val end: Int)

    private fun tokens(s: String): List<Tok> {
        val out = mutableListOf<Tok>()
        var i = 0
        while (i < s.length) {
            if (!s[i].isLetterOrDigit()) { i++; continue }
            val start = i
            while (i < s.length && (s[i].isLetterOrDigit() || (s[i] in "'’" && i + 1 < s.length && s[i + 1].isLetterOrDigit()))) i++
            out += Tok(HelperRouter.plain(s.substring(start, i)), start, i)
        }
        return out
    }

    /** An opening quote or bracket: it belongs to the message after a wake phrase ("Hey robot, “I'm late”"). */
    private fun opens(c: Char): Boolean = c == '"' || Character.getType(c).let { it == Character.START_PUNCTUATION.toInt() || it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() }

    /**
     * The spoken answer to "what should it say?" without the wake phrases at its start: the wake word is paused while
     * Robot listens, so "hey robot, I'm running late" comes back whole. [phrases]: your wake phrases ("hey robot" and
     * "robot" always count), matched without case or accents ("Hé robot"), as often as they repeat. A single word
     * ("robot") counts only with punctuation after it ("Robot, I'm late"), so "Robot vacuum is fixed" stays, unless
     * [loose] (for telling a cancel: "Computer cancel"). Nothing else goes: "Okay, see you at 5" is the text.
     */
    fun stripWake(answer: String, phrases: List<String> = emptyList(), loose: Boolean = false): String {
        val all = (phrases + DEFAULT_WAKE).map { p -> WakePhrases.words(p).map(HelperRouter::plain).filter { it.isNotEmpty() } }
            .filter { it.isNotEmpty() }.distinct().sortedByDescending { it.size }
        var s = answer.trim()
        while (true) {
            val t = tokens(s)
            val p = all.firstOrNull { p -> t.size >= p.size && p.indices.all { t[it].w == p[it] } } ?: return s
            val end = t[p.size - 1].end
            val next = t.getOrNull(p.size)?.start ?: s.length
            val gap = s.substring(end, next)
            if (next < s.length && p.size == 1 && !loose && gap.isBlank()) return s
            val cut = gap.indexOfFirst(::opens)
            s = s.substring(if (cut >= 0) end + cut else next).trim()
        }
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
