package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. What your words mean for Claude, decided by code and never by the local model (docs/ask-claude.md "How it is
// triggered"). The router runs on the transcript or the typed text before anything reaches Gemma. Matching is anchored
// at the start of the utterance, with only filler words ("hey robot", "please", "can you", "okay") in front, so "what's
// the weather like on Claude's birthday" stays local. Parakeet's mishearings of "Claude" ("cloud", "clod", "clawed",
// "claud", "klode", "clyde") count only after an explicit command verb: "ask cloud about X" and "get cloud" are
// commands, "what's the cloud cover tomorrow" and "Clyde, pass the salt" are not. The name on its own leads into a
// question for Claude only when it is exactly "Claude" and what follows is a question or a request ("Claude, what's
// …", "Claude explain …"), never a sentence about someone called Claude ("Claude can come over tomorrow").
//
// 0.6.0: the same rules for every helper, by its name ([HelperName]): a name may be several words ("chat gpt", "deep
// seek"), and a "*" slot takes the longest name that fits. Curated mishearings ship for Claude only; any other name of
// five letters or more (or two syllables) also matches a word that sounds like it ([Phonetic]), but only where a
// mishearing may count (after a command verb), so it is marked misheard and its conversation asks before anything earlier goes.

sealed class HelperCommand {
    /**
     * One question for Claude. [deep]: you asked for depth ("think hard about", "take your time"). [extra]: you asked
     * about the screen or a photo; it goes only after a tap on a card.
     */
    data class Ask(val question: String, val deep: Boolean = false, val extra: Attachment = Attachment.NONE, val helper: String = HelperName.CLAUDE_ID) : HelperCommand()

    /**
     * Claude mode: the following turns go to Claude. [question]: said in the same breath ("go get Claude and ask …").
     * [misheard]: a word that only sounded like the name ("get cloud"): the conversation so far goes only after a tap.
     */
    data class Join(val question: String? = null, val misheard: Boolean = false, val helper: String = HelperName.CLAUDE_ID) : HelperCommand()

    /** Leave Claude mode, or any helper's ("thanks Claude", "back to Robot"); outside it, ordinary talk. */
    data object Leave : HelperCommand()

    /** "Read Claude's answer": the Claude app's last answer, from the corner swipe's screen data. */
    data object ReadAnswer : HelperCommand()
}

/**
 * A helper's name as the router hears it: [forms] mean it exactly (its name, other ways speech recognition writes it,
 * your own aliases), each a list of plain words; [misheard] count only after a command verb (Claude's curated list);
 * [phonetic]: a word that sounds like the name counts there too (any name without a curated list, see [Phonetic]).
 * [app]: "read X's answer" reads its app (the Claude app).
 */
data class HelperName(val id: String, val forms: List<List<String>>, val misheard: List<List<String>> = emptyList(), val phonetic: Boolean = false,
    val app: Boolean = false) {
    companion object {
        const val CLAUDE_ID = "claude"

        /** Claude as 0.3 to 0.5 heard it. */
        val CLAUDE = HelperName(CLAUDE_ID, listOf(listOf("claude")), listOf("cloud", "clod", "clawed", "claud", "klode", "clyde").map { listOf(it) }, app = true)

        /**
         * 1.0.0, "Ask a helper": no name, the helper Robot checks with ([app.heyrobot.core.helpers.HelperSettings.target]),
         * which the panel picks when the command arrives. Never a helper's id (ids are letters, digits and "_").
         */
        const val ANY_ID = "*"

        /**
         * "the helper", "a helper", "my helper", « l'assistant », « un assistant »: heard after "ask" and « demande (à) »
         * only ([HelperRouter.route]), and in a refusal ("don't ask the helper"), never on their own or after "go get".
         */
        val ANY = HelperName(ANY_ID, listOf(listOf("the", "helper"), listOf("a", "helper"), listOf("my", "helper"), listOf("lassistant"),
            listOf("l", "assistant"), listOf("un", "assistant")))

        /**
         * 1.0.0: how you thank or stop the helper "ask the helper" brought in: "thanks helper", "thank you, the helper", "stop my
         * helper", « merci l'assistant », « merci assistant ». Heard in a leave only ([HelperRouter.route]), so "helper" alone
         * still names nobody anywhere else.
         */
        val ANY_LEAVE = HelperName(ANY_ID, listOf(listOf("helper"), listOf("the", "helper"), listOf("my", "helper"), listOf("lassistant"),
            listOf("l", "assistant"), listOf("assistant")))

        /** A word sequence in the router's plain form ("ChatGPT" → [chatgpt], "Deep Seek" → [deep, seek]). */
        fun words(s: String): List<String> = HelperRouter.plain(s).split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }

        /** How [h] is heard: its name, the preset's spellings and mishearings when it keeps the preset's name, and your aliases. */
        fun of(h: app.heyrobot.core.helpers.HelperConfig): HelperName {
            val p = h.presetOf
            val default = words(h.name) == words(p.defaultName)
            val forms = (listOf(words(h.name)) + (if (default) p.spoken.map(::words) else emptyList()) + h.aliases.map(::words)).filter { it.isNotEmpty() }.distinct()
            val misheard = if (default) p.misheard.map(::words).filter { it.isNotEmpty() } else emptyList()
            return HelperName(h.id, forms, misheard, phonetic = misheard.isEmpty() && forms.any { it.size == 1 && Phonetic.eligible(it[0]) },
                app = default && p.app == ClaudeAppLink.PACKAGE)
        }

        /** Every helper in settings, on or off (off ones are recognised so Robot can say they are off). */
        fun all(s: RobotSettings): List<HelperName> = s.helpers.map(::of)
    }
}

/** Where a name was found in what was said: whose, where it ends, and whether it was the name itself ([exact]). */
class HeardName(val id: String, val end: Int, val exact: Boolean)

object HelperRouter {
    /** "Claude" and how Parakeet may hear it; the mishearings count only after a command verb ([seq]'s "*"). */
    val ALIASES = setOf("claude", "cloud", "clod", "clawed", "claud", "klode", "clyde")

    private val CLAUDE = listOf(HelperName.CLAUDE)

    /** A word of the utterance: its plain form (no accents, apostrophes or case) and where it sits in the original. */
    private class Word(val w: String, val start: Int, val end: Int, val punctAfter: Char?)

    private fun words(text: String): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit()) { i++; continue }
            val start = i
            // A word keeps its inner apostrophes ("what's", "qu'est" → whats, quest).
            while (i < text.length && (text[i].isLetterOrDigit() || (text[i] in "'’" && i + 1 < text.length && text[i + 1].isLetterOrDigit()))) i++
            var j = i
            while (j < text.length && text[j] == ' ') j++
            val punct = text.getOrNull(j)?.takeIf { it in ",:;!?.—-" }
            out += Word(plain(text.substring(start, i)), start, i, punct)
        }
        return out
    }

    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace("'", "").replace("’", "")

    /** Filler allowed in front of a command, English and French. */
    private val FILLER: List<List<String>> = listOf(
        listOf("hey", "robot"), listOf("hi", "robot"), listOf("ok", "robot"), listOf("okay", "robot"), listOf("hello", "robot"),
        listOf("he", "robot"), listOf("dis", "robot"), listOf("robot"),
        listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("will", "you"),
        listOf("okay"), listOf("ok"), listOf("so"), listOf("um"), listOf("uh"), listOf("hey"), listOf("hi"), listOf("and"),
        listOf("sil", "te", "plait"), listOf("sil", "vous", "plait"), listOf("peux", "tu"), listOf("pourrais", "tu"),
        listOf("est", "ce", "que", "tu", "peux"), listOf("dac"), listOf("daccord"),
    )

    /** The filler words, which no helper may be called (core HelperNames). */
    val FILLER_WORDS: Set<String> = FILLER.flatten().toSet()

    /** Skips the filler at the start: the index of the first word that counts. */
    private fun head(ws: List<Word>): Int {
        var i = 0
        while (true) {
            val f = FILLER.firstOrNull { f -> f.indices.all { k -> ws.getOrNull(i + k)?.w == f[k] } } ?: break
            i += f.size
        }
        return i
    }

    /** True if [ws] from [at] reads [p], word for word (no name slots). */
    private fun seq(ws: List<Word>, at: Int, vararg p: String): Boolean = p.indices.all { k -> ws.getOrNull(at + k)?.w == p[k] }

    private fun form(ws: List<Word>, at: Int, f: List<String>): Boolean = f.isNotEmpty() && f.indices.all { k -> ws.getOrNull(at + k)?.w == f[k] }

    /**
     * A helper's name at [at]: the longest of its exact forms first; unless [exactOnly], then a curated mishearing, then a
     * word that sounds like a name without one ([Phonetic]).
     */
    private fun nameAt(ws: List<Word>, at: Int, names: List<HelperName>, exactOnly: Boolean): HeardName? {
        fun longest(pick: (HelperName) -> List<List<String>>): HeardName? {
            var best: HeardName? = null
            for (n in names) for (f in pick(n)) if (form(ws, at, f) && (best == null || at + f.size > best.end)) best = HeardName(n.id, at + f.size, false)
            return best
        }
        longest { it.forms }?.let { return HeardName(it.id, it.end, exact = true) }
        if (exactOnly) return null
        longest { it.misheard }?.let { return it }
        val w = ws.getOrNull(at)?.w ?: return null
        for (n in names) if (n.phonetic) for (f in n.forms) if (f.size == 1 && Phonetic.close(w, f[0])) return HeardName(n.id, at + 1, false)
        return null
    }

    /**
     * [p] at [at], where "*" is a helper's name or a word that may be a mishearing of one, and "=" a name exactly (the
     * slot takes a whole name, the longest that fits): where it ends and whose name it was.
     */
    private fun seqName(ws: List<Word>, at: Int, p: Array<String>, names: List<HelperName>): Pair<Int, HeardName>? {
        var k = at
        var m: HeardName? = null
        for (e in p) {
            if (e == "*" || e == "=") {
                val n = nameAt(ws, k, names, exactOnly = e == "=") ?: return null
                m = n
                k = n.end
            } else {
                if (ws.getOrNull(k)?.w != e) return null
                k++
            }
        }
        return if (m == null) null else k to m
    }

    /** The original text from word [at] on, without leading connectors ([drop]) and punctuation; null if empty. */
    private fun rest(text: String, ws: List<Word>, at: Int, drop: Set<String> = emptySet()): String? {
        var i = at
        while (i < ws.size && ws[i].w in drop) i++
        if (i >= ws.size) return null
        return text.substring(ws[i].start).trim().trimEnd(',', ';', ':').trim().takeIf { it.isNotEmpty() }
    }

    private val ASK_DROP = setOf("to", "de", "d")
    private val JOIN_TAIL = setOf("please", "in", "now", "back", "here", "sil", "te", "vous", "plait", "maintenant", "on", "for", "me")
    private val JOIN_LINK = setOf("and", "et", "then", "ask", "him", "her", "it", "lui", "demande", "puis")
    /** After a mishearing only polite words may follow a bare "get cloud" ("get Clyde in here" is about a person). */
    private val BARE_TAIL = setOf("please", "now", "sil", "te", "vous", "plait", "maintenant")

    /** Claude mode. A mishearing ("*") counts after "get" and "va chercher" only; the rest need the name ("="). */
    private val JOINS: List<Array<String>> = listOf(
        arrayOf("go", "get", "*"), arrayOf("go", "and", "get", "*"), arrayOf("get", "*"), arrayOf("va", "chercher", "*"),
        arrayOf("bring", "in", "="), arrayOf("bring", "=", "in"), arrayOf("bring", "="),
        arrayOf("let", "me", "talk", "to", "="), arrayOf("lets", "talk", "to", "="), arrayOf("switch", "to", "="),
        arrayOf("i", "want", "to", "talk", "to", "="), arrayOf("talk", "to", "="), arrayOf("put", "=", "on"),
        arrayOf("passe", "moi", "="), arrayOf("je", "veux", "parler", "a", "="), arrayOf("parle", "a", "="),
    )
    /** "talk to Claude about …": the subject opens the conversation. */
    private val ABOUT = setOf("about", "de", "sur")
    private val LEAVES: List<Array<String>> = listOf(
        arrayOf("thanks", "*"), arrayOf("thank", "you", "*"), arrayOf("thx", "*"), arrayOf("bye", "*"), arrayOf("bye", "bye", "*"), arrayOf("goodbye", "*"),
        arrayOf("thats", "all", "*"), arrayOf("that", "is", "all", "*"), arrayOf("stop", "*"),
        arrayOf("merci", "*"), arrayOf("au", "revoir", "*"), arrayOf("cest", "tout", "*"),
    )
    private val BACK: List<Array<String>> = listOf(
        arrayOf("back", "to", "robot"), arrayOf("go", "back", "to", "robot"), arrayOf("switch", "back", "to", "robot"), arrayOf("retour", "a", "robot"),
    )
    private val ASKS: List<Array<String>> = listOf(arrayOf("ask", "*"), arrayOf("demande", "a", "*"), arrayOf("demande", "*"))
    /** After a request to "my helper", these words are a message for a person ("ask my helper to pick up milk"). */
    private val FOR_A_PERSON = setOf("to", "if", "whether", "si", "de", "d")

    /** The command at the start of [text] for one of [names] (Claude alone by default), or null: ordinary talk for Robot. */
    fun route(text: String, names: List<HelperName> = CLAUDE): HelperCommand? {
        val ws = words(text)
        val i = head(ws)
        if (i >= ws.size) return null

        // Leave first: "stop Claude" and "thanks Claude" are never questions. 1.0.0: nor "thanks helper", « merci l'assistant ».
        if (LEAVES.any { seqName(ws, i, it, names + HelperName.ANY_LEAVE) != null } || BACK.any { seq(ws, i, *it) }) return HelperCommand.Leave

        // "read Claude's answer" (an app Robot reads back).
        if (ws.getOrNull(i)?.w == "read" && ws.getOrNull(i + 2)?.w in setOf("answer", "reply", "response")) {
            val w = ws.getOrNull(i + 1)?.w
            if (w != null && w.endsWith("s") && names.any { n -> n.app && (n.forms + n.misheard).any { it == listOf(w.dropLast(1)) } }) return HelperCommand.ReadAnswer
        }

        // "ask Claude …", "demande à Claude …" (and "demande Claude", when the "à" was not heard). 1.0.0: "ask the helper …",
        // "ask a helper …", "ask my helper …", « demande à l'assistant … », « demande à un assistant … » ([HelperName.ANY]),
        // after the helpers' own names, so a helper you named wins a tie.
        ASKS.firstNotNullOfOrNull { seqName(ws, i, it, names + HelperName.ANY) }?.let { (askAt, m) ->
            val next = ws.getOrNull(askAt)?.w
            // "ask Clyde if he's coming to dinner": a message for a person (Robot's to handle), not a question for Claude.
            if (next in setOf("if", "whether", "si") && ws.getOrNull(askAt + 1)?.w in PERSON) return null
            // After a word that only sounds like the name, "ask Clyde to pick up milk" or "… whether dinner is ready" is
            // a message for a person too: only the name itself carries a request like that.
            if (!m.exact && next in FOR_A_PERSON) return null
            // "My helper" may be a person who helps at home: "ask my helper to pick up the kids" is a message for them.
            if (m.id == HelperName.ANY_ID && ws[askAt - 2].w == "my" && next in FOR_A_PERSON) return null
            // "Ask the helper" alone joins like a mishearing: no helper was named, so the conversation so far goes only after a
            // tap, even to a helper you trust (HelperTrust.joinWithoutCard).
            return rest(text, ws, askAt, ASK_DROP)?.let { ask(it, m.id) } ?: HelperCommand.Join(misheard = !m.exact || m.id == HelperName.ANY_ID, helper = m.id)
        }

        // "what does Claude think about …", "qu'est-ce que Claude pense de …".
        (seqName(ws, i, arrayOf("what", "does", "=", "think"), names) ?: seqName(ws, i, arrayOf("whats", "=", "think"), names))?.let { (at, m) ->
            val r = rest(text, ws, at, setOf("about", "of")) ?: return HelperCommand.Join(helper = m.id)
            return ask("What do you think about ${r.trimEnd('?', '.', ' ')}?", m.id)
        }
        seqName(ws, i, arrayOf("quest", "ce", "que", "=", "pense"), names)?.let { (at, m) ->
            val r = rest(text, ws, at) ?: return HelperCommand.Join(helper = m.id)
            return ask("Que penses-tu ${r.trimEnd('?', '.', ' ')} ?", m.id)
        }

        // "go get Claude", "va chercher Claude": nothing after the name but a polite word, or "and" with a question.
        for (p in JOINS) {
            val (end, m) = seqName(ws, i, p, names) ?: continue
            var k = end
            val nameEnd = ws[end - 1]
            // A word that only sounded like the name ("get cloud", "go get Clyde"): Claude mode alone, or with an
            // explicit "and ask …". "Go get Clyde and the kids from school" or "get Clyde, he's at the door" is about a
            // person.
            if (!m.exact) {
                while (k < ws.size && ws[k].w in BARE_TAIL) k++
                if (k >= ws.size) return HelperCommand.Join(misheard = true, helper = m.id)
                val asks = (ws[k].w in setOf("and", "then", "et", "puis") && ws.getOrNull(k + 1)?.w in setOf("ask", "demande", "demandes")) ||
                    ws[k].w in setOf("ask", "demande")
                return if (asks) HelperCommand.Join(rest(text, ws, k, JOIN_LINK + ASK_DROP + "demandes"), misheard = true, helper = m.id) else null
            }
            while (k < ws.size && ws[k].w in JOIN_TAIL) k++
            if (k >= ws.size) return HelperCommand.Join(helper = m.id)
            // "talk to Claude about my day".
            if (ws[k].w in ABOUT && k + 1 < ws.size) return HelperCommand.Join("Let's talk about " + text.substring(ws[k + 1].start).trim().trimEnd('.', '!', ' ') + ".", helper = m.id)
            // "get Claude, what's …" or "go get Claude and ask him …": the rest is the first question.
            if (nameEnd.punctAfter in setOf(',', ':', '—', '-') || ws[k].w in JOIN_LINK) {
                return HelperCommand.Join(rest(text, ws, k, JOIN_LINK + ASK_DROP), helper = m.id)
            }
            // "get cloud storage prices": not a command.
            return null
        }

        // The name itself, addressed: "Claude, what's …", "hey Claude explain …", "Claude" alone. Only the name as
        // written, and only into a question or a request: "Claude can come over tomorrow", "Claude Monet painted …" and
        // "Claude please stop calling me" are about someone called Claude, and a mishearing never counts here.
        nameAt(ws, i, names, exactOnly = true)?.let { m ->
            if (m.end >= ws.size) return HelperCommand.Join(helper = m.id)
            if (addressed(ws.drop(m.end), comma = ws[m.end - 1].punctAfter in setOf(',', ':', '—'))) return rest(text, ws, m.end)?.let { ask(it, m.id) } ?: HelperCommand.Join(helper = m.id)
        }
        return null
    }

    /** "he", "she", "they" right after "ask … if": the question is for a person ("il" is also "it" in French). */
    private val PERSON = setOf("he", "hes", "she", "shes", "they", "theyre", "theyll", "hell", "shell", "hed", "shed", "elle", "elles")

    private val WH = setOf("what", "whats", "why", "how", "hows", "who", "whos", "whose", "when", "where", "wheres", "which",
        "pourquoi", "comment", "quest", "quel", "quelle", "quels", "quelles", "combien", "qui", "quand")
    /** Requests that stand on their own: "Claude, explain …". */
    private val REQUEST = setOf("explain", "summarize", "summarise", "translate", "describe", "define", "compare", "recommend", "suggest",
        "explique", "resume", "traduis", "decris", "compare", "definis")
    /** Requests that need "me" or "us" after them: "tell me", "help me", "write me". */
    private val TO_ME = setOf("tell", "help", "write", "give", "show", "teach", "dis", "aide", "donne", "ecris", "montre")
    /**
     * Questions that start with a verb: for Claude only with "you" after it ("Claude, can you …"), or when the name is
     * set off by a comma ("Claude, is it going to rain?"). "Claude has a fever, what should I do?" and "Claude is
     * coming over tonight?" are about someone, question mark or not.
     */
    private val AUX = setOf("can", "could", "would", "will", "do", "does", "did", "is", "are", "should", "have", "has", "peux", "pourrais", "est", "es")

    /** What follows the name is a question or a request for Claude ([comma]: the name is set off by a comma). */
    private fun addressed(r: List<Word>, comma: Boolean): Boolean {
        val w0 = r.firstOrNull()?.w ?: return false
        val w1 = r.getOrNull(1)?.w
        return when {
            w0 in WH || w0 in REQUEST -> true
            w0 in TO_ME -> w1 in setOf("me", "us", "moi", "nous") || (w0 == "write" && w1 in setOf("a", "an"))
            w0 in AUX -> comma || w1 in setOf("you", "tu", "vous")
            // "Claude, please explain …" (but "Claude please stop calling me" is about someone).
            w0 == "please" -> addressed(r.drop(1), comma)
            else -> false
        }
    }

    private fun ask(q: String, helper: String) = HelperCommand.Ask(q, deep(q), extra(q), helper)

    private val DEEP = listOf("think hard", "think carefully", "think it through", "take your time", "in depth", "in detail",
        "reflechis bien", "prends ton temps", "en detail")

    /** You asked for depth: high effort and the longer answer limit. */
    fun deep(text: String): Boolean = plain(text).let { t -> DEEP.any { t.contains(it) } }

    private val SCREEN = Regex("""\b(on|of|about) (my|the|this) screen\b|\bthis screen\b|\bmon ecran\b|\bcet ecran\b""")
    private val PHOTO = Regex("""\b(this|the|my) (photo|picture|image|pic)\b|\bcette (photo|image)\b""")

    /** "about what's on my screen", "about this photo": what you want to share (only after a tap). */
    fun extra(text: String): Attachment {
        val t = plain(text)
        return when {
            SCREEN.containsMatchIn(t) -> Attachment.SCREEN
            PHOTO.containsMatchIn(t) -> Attachment.PHOTO
            else -> Attachment.NONE
        }
    }

    // ------------------------------------------------------------------ "not Claude"

    /**
     * You turned Claude down (any helper, by its name, since 0.6.0): "not Claude", "no Claude", "don't ask Claude",
     * "don't check with Claude", "without Claude", "leave Claude out of it", "you tell me", "tell me yourself", "answer it
     * yourself", "on your own", "pas Claude", "sans Claude", "ne demande pas à Claude", "réponds toi-même". Anywhere in
     * what you said, not only at the start ("tell me about this screen. Not Claude"). Robot then answers itself and
     * doesn't bring a helper in for the rest of the conversation, until you ask for one again ("ask Claude …", "go get
     * Claude").
     *
     * Parakeet's mishearings count after a refusal word too ("not cloud", "don't ask clod"), but only where nothing could
     * follow them but a pause or a polite word: "no cloud today?", "not cloud storage", "don't ask Clyde to pick up milk"
     * and "without cloud" are about the weather, storage or a person. Words about someone called Claude never count:
     * "is it not Claude's birthday", "Claude is not available", "it's not Claude, it's Sam", "whether or not Claude
     * comes", "don't tell Claude", "tell me about yourself", "can you set alarms on your own?".
     */
    fun declines(text: String, names: List<HelperName> = CLAUDE): Boolean {
        val ws = words(text)
        if (ws.isEmpty()) return false
        val start = head(ws)
        val d = Declines(text, ws, names + HelperName.ANY)
        return ws.indices.any { i -> d.contrast(i) || d.verbFirst(i) || yourself(text, ws, i, start) || d.french(i) }
    }

    /** Words after "not Claude" that keep it a refusal: "not Claude please", "not Claude this time", "pas Claude, toi". */
    private val NOT_AFTER = setOf("please", "pls", "thanks", "thank", "thx", "this", "that", "for", "just", "you", "yourself", "i", "im", "robot", "ok",
        "okay", "again", "now", "today", "here", "either", "but", "and", "so", "then", "only", "needed", "necessary", "required", "involved", "though",
        "too", "anymore", "stp", "svp", "sil", "merci", "toi", "cette", "pour", "juste", "je", "mais", "et", "maintenant", "ici", "non", "ca", "cela")
    /** After a word that only sounds like the name, fewer: "no cloud today?" is about the weather. */
    private val NOT_AFTER_HEARD = setOf("please", "pls", "thanks", "thank", "thx", "just", "you", "yourself", "i", "im", "robot", "but", "stp", "svp",
        "sil", "merci", "toi", "juste", "je", "mais")
    /** "it's not Claude", "there's no Claude", "c'est pas Claude": who someone is, never a refusal. */
    private val BE = setOf("is", "are", "am", "was", "were", "be", "been", "being", "im", "youre", "its", "thats", "hes", "shes", "theyre", "theres",
        "there", "isnt", "wasnt", "whos", "whats", "est", "cest", "nest", "suis", "es", "sont", "etait", "a", "ya")
    /** Pauses that end a refusal ("not Claude, you"); a hyphen joined to the word ("cloud-based") is not one. */
    private const val PAUSES = ",:;!?.—-"

    /** Word [k] ends a clause: the last word, or a pause right after it. */
    private fun pauseAfter(text: String, ws: List<Word>, k: Int): Boolean {
        if (k >= ws.size - 1) return true
        val p = ws[k].punctAfter ?: return false
        return p in PAUSES && !(p == '-' && text.getOrNull(ws[k].end) == '-')
    }

    private val REFUSE_FIRST: List<Array<String>> = listOf(arrayOf("dont"), arrayOf("do", "not"), arrayOf("never"), arrayOf("stop"),
        arrayOf("no", "need", "to"), arrayOf("without"))
    /** What you might not want done with Claude: "don't ask / check with / use / bring in / send it to Claude". */
    private val REFUSED = setOf("ask", "asking", "check", "checking", "use", "using", "call", "calling", "get", "getting", "bring", "bringing", "involve",
        "involving", "send", "sending", "go", "going", "consult", "consulting", "hand", "handing", "pass", "passing", "forward", "forwarding", "run",
        "running", "need", "want", "mind", "contact", "contacting", "wait", "waiting", "try", "trying")
    /** Between the verb and the name: "check with", "send it to", "hand it over to", "run this by". */
    private val CONNECT = setOf("with", "to", "in", "it", "this", "that", "over", "on", "through", "by", "past", "for", "the", "question", "one", "up", "at")

    private val FR_VERBS = setOf("demande", "demandes", "demandez", "appelle", "appelles", "appelez", "consulte", "consultes", "consultez", "utilise",
        "utilises", "utilisez", "verifie", "verifies", "verifiez", "interroge", "interroges", "interrogez", "passe", "passes", "passez", "envoie", "envoies",
        "envoyez", "va", "vas", "allez")
    private val FR_INF = setOf("demander", "appeler", "consulter", "utiliser", "verifier", "interroger", "passer", "envoyer", "chercher", "solliciter")
    private val FR_CONNECT = setOf("a", "au", "avec", "de", "par", "ca", "cela", "le", "la", "chercher", "lui")
    private val FR_SELF = setOf("reponds", "repond", "repondez", "dis", "dites", "fais", "faites", "explique", "expliquez", "decris", "resume", "reflechis",
        "trouve", "lis")

    /** The refusal rules over one utterance, for the helpers' [names]. */
    private class Declines(val text: String, val ws: List<Word>, val names: List<HelperName>) {
        fun name(k: Int, exactOnly: Boolean = false): HeardName? = nameAt(ws, k, names, exactOnly)

        /** A name (or a mishearing of one) at [k], and nothing after it but a pause or a word that can't go on about a person. */
        fun nameEnds(k: Int): Boolean {
            val m = name(k) ?: return false
            val last = m.end - 1
            return pauseAfter(text, ws, last) || ws[last + 1].w in (if (m.exact) NOT_AFTER else NOT_AFTER_HEARD)
        }

        /** A name after a refusal verb ("don't ask Claude …"): anything may follow the name itself, but "if he …" is a person. */
        fun nameAfterVerb(k: Int): Boolean {
            val m = name(k) ?: return false
            if (!m.exact) return nameEnds(k)
            return !(ws.getOrNull(m.end)?.w in setOf("if", "whether", "si") && ws.getOrNull(m.end + 1)?.w in PERSON)
        }

        /** "not Claude", "no Claude", "without Claude", "pas Claude", "sans Claude", "not to Claude", "pas à Claude". */
        fun contrast(i: Int): Boolean {
            val neg = ws[i].w
            if (neg !in setOf("not", "no", "pas", "sans", "without") || ws[i].punctAfter != null) return false
            if (ws.getOrNull(i - 1)?.w in BE) return false
            var k = i + 1
            if (neg in setOf("not", "pas") && ws.getOrNull(k)?.w in setOf("to", "a", "au", "with", "avec")) k++
            // "without cloud" is about storage: only the name itself counts after "without" and "sans".
            if (neg in setOf("sans", "without") && name(k, exactOnly = true) == null) return false
            return nameEnds(k)
        }

        /**
         * "don't ask Claude", "do not check with Claude", "never mind Claude", "stop asking Claude", "no need to ask Claude",
         * "without asking Claude", "no need for Claude", "leave Claude out of it", "keep Claude out of this", "forget Claude".
         */
        fun verbFirst(i: Int): Boolean {
            REFUSE_FIRST.firstOrNull { seq(ws, i, *it) }?.let { p ->
                val v = i + p.size
                if (ws.getOrNull(v)?.w !in REFUSED) return@let
                // "don't ask the helper": a name that starts with a connecting word ([HelperName.ANY]).
                if (nameAfterVerb(v + 1)) return true
                var k = v + 1
                while (k < ws.size && k - v <= 3 && ws[k].w in CONNECT) k++
                // "don't use the cloud" is about storage: after "the", only the name itself.
                if (ws.getOrNull(k - 1)?.w == "the" && name(k, exactOnly = true) == null) return@let
                if (nameAfterVerb(k)) return true
            }
            if (seq(ws, i, "no", "need", "for")) return nameEnds(i + 3)
            val w = ws[i].w
            // "leave Claude out of it", "keep cloud out of this", "leave out Claude", "leave Claude alone".
            if (w in setOf("leave", "keep")) {
                val k = ws.getOrNull(i + 1)?.takeIf { it.w == "out" }?.let { i + 2 } ?: (i + 1)
                val m = name(k) ?: return false
                return k == i + 2 || ws.getOrNull(m.end)?.w == "out" || (m.exact && ws.getOrNull(m.end)?.w == "alone")
            }
            // "forget Claude", "forget about Claude", "skip Claude".
            if (w in setOf("forget", "skip")) return nameEnds(if (ws.getOrNull(i + 1)?.w == "about") i + 2 else i + 1)
            return false
        }

        /**
         * "ne demande pas à Claude", "n'appelle pas Claude", "ne va pas chercher Claude", "ne pas demander à Claude", "sans
         * passer par Claude", "pas besoin de Claude", "laisse Claude en dehors", "toi-même", "réponds tout seul".
         */
        fun french(i: Int): Boolean {
            val w = ws[i].w
            // "toi-même", "vous-même": you, yourself.
            if (w in setOf("toi", "vous") && ws.getOrNull(i + 1)?.w == "meme") return true
            // "réponds tout seul".
            if (w in FR_SELF) {
                for (k in i + 1..minOf(i + 4, ws.size - 2)) if (ws[k].w == "tout" && ws[k + 1].w == "seul" || ws[k].w == "toute" && ws[k + 1].w == "seule") return true
            }
            fun connectedName(from: Int): Boolean {
                var k = from
                while (k < ws.size && k - from < 3 && ws[k].w in FR_CONNECT) k++
                return nameAfterVerb(k)
            }
            // "(ne) demande pas à Claude", "n'appelle pas Claude" (the "n'" joins the verb), "ne va pas chercher Claude". Not
            // "demande pas, Claude" or "ça va pas, Claude ?", which speak to someone.
            val verb = if (w in FR_VERBS) w else w.drop(1).takeIf { w.startsWith("n") && it in FR_VERBS }
            if (verb != null && ws.getOrNull(i + 1)?.w == "pas" && ws[i + 1].punctAfter == null) {
                if (verb in setOf("va", "vas", "allez") && ws.getOrNull(i + 2)?.w != "chercher") return false
                return connectedName(i + 2)
            }
            // "ne pas demander à Claude", "sans demander à Claude", "pas besoin de demander à Claude".
            val inf = when {
                seq(ws, i, "ne", "pas") -> i + 2
                w == "sans" -> i + 1
                seq(ws, i, "pas", "besoin", "de") -> i + 3
                seq(ws, i, "inutile", "de") -> i + 2
                else -> -1
            }
            if (inf >= 0) {
                if (ws.getOrNull(inf)?.w in FR_INF) return connectedName(inf + 1)
                // "pas besoin de Claude".
                if (w == "pas" || w == "inutile") return nameEnds(inf)
            }
            // "laisse Claude en dehors (de ça)", "laisse Claude de côté", "laisse tomber Claude".
            if (w in setOf("laisse", "laissez", "laisser")) {
                if (ws.getOrNull(i + 1)?.w == "tomber") return nameEnds(i + 2)
                val m = name(i + 1) ?: return false
                return seq(ws, m.end, "en", "dehors") || seq(ws, m.end, "de", "cote")
            }
            return false
        }
    }

    /** "tell me yourself": an answer the way you want it from Robot. Not after "don't" ("don't answer yourself, ask Claude"). */
    private val SELF = setOf("tell", "answer", "do", "work", "figure", "explain", "describe", "say", "read", "decide", "think", "check", "look", "summarise",
        "summarize", "handle", "solve", "reply", "respond", "find")
    /** Between the verb and "yourself": "answer it yourself", "figure it out yourself", "do it by yourself", "think for yourself". */
    private val SELF_MID = setOf("me", "us", "it", "this", "that", "out", "the", "answer", "question", "one", "for", "up", "screen", "at", "by", "on")
    private val NEGATED = setOf("dont", "not", "never", "didnt", "cant", "cannot", "wont", "shouldnt")
    /** Words that may open a clause before "you tell me": "no, you tell me", "so you tell me". */
    private val OPENERS = setOf("no", "nope", "nah", "just", "so", "then", "please", "and", "but", "well", "ok", "okay", "now")

    /** "you tell me", "tell me yourself", "answer it yourself", "on your own", "by yourself", "I'm asking you". */
    private fun yourself(text: String, ws: List<Word>, i: Int, start: Int): Boolean {
        fun clauseStart(k: Int) = k == start || (k > 0 && (ws[k - 1].punctAfter != null || ws[k - 1].w in OPENERS))
        // "you tell me", "you answer", "you decide": you, not someone else, at the start of a clause ("can you tell me" is a request).
        if (ws[i].w == "you" && ws[i].punctAfter == null && clauseStart(i)) {
            val v = ws.getOrNull(i + 1)?.w
            if (v == "tell" && ws.getOrNull(i + 2)?.w in setOf("me", "us")) return true
            if (v in setOf("answer", "explain", "decide", "figure")) return true
            if (v == "do" && ws.getOrNull(i + 2)?.w in setOf("it", "this", "that")) return true
        }
        // "tell me yourself", "answer it yourself".
        if (ws[i].w == "yourself") return selfVerbBefore(ws, i)
        // "on your own", "by yourself": after an answering verb or on their own, and nothing after them but a pause.
        val phrase = when {
            seq(ws, i, "on", "your", "own") -> 3
            seq(ws, i, "by", "yourself") -> 2
            else -> 0
        }
        if (phrase > 0) {
            val last = i + phrase - 1
            val ends = pauseAfter(text, ws, last) || ws[last + 1].w in NOT_AFTER
            return ends && (clauseStart(i) || selfVerbBefore(ws, i))
        }
        // "I'm asking you", "I asked you" (and nothing more about it).
        val asking = when {
            seq(ws, i, "im", "asking", "you") -> i + 2
            seq(ws, i, "i", "am", "asking", "you") -> i + 3
            seq(ws, i, "i", "asked", "you") -> i + 2
            else -> -1
        }
        return asking >= 0 && (pauseAfter(text, ws, asking) || ws[asking + 1].w in NOT_AFTER)
    }

    /** An answering verb ([SELF]) right before [k], past at most four small words ([SELF_MID]), and not negated. */
    private fun selfVerbBefore(ws: List<Word>, k: Int): Boolean {
        var j = k - 1
        while (j >= 0 && k - j <= 4 && ws[j].w in SELF_MID && ws[j].w !in SELF) j--
        if (j < 0 || ws[j].w !in SELF) return false
        val before = ws.getOrNull(j - 1)?.w
        // "don't answer yourself, ask Claude"; "did you do it yourself?" asks about something done.
        return before !in NEGATED && !seq(ws, j - 2, "do", "not") && !(before == "you" && ws.getOrNull(j - 2)?.w in setOf("did", "do", "does", "have", "had"))
    }

    private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "please", "ok", "okay", "go", "ahead", "do", "it", "check", "oui",
        "daccord", "vas", "y", "please", "absolutely", "definitely", "go ahead", "with", "claude", "ask", "him")

    /** A short yes to an offer ("yes", "sure, go ahead", "oui"), never a question of its own ([names]: "yes, check with Jarvis"). */
    fun isYes(text: String, names: List<HelperName> = CLAUDE): Boolean {
        val ws = words(text).map { it.w }
        if (ws.isEmpty() || ws.size > 6) return false
        val first = ws.first()
        val named = (names + HelperName.ANY).flatMap { n -> n.forms.flatten() }.toSet()
        return first in setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "oui", "daccord", "absolutely", "definitely", "go") &&
            ws.all { it in YES || it in named }
    }
}

/**
 * A rough English sound key for a helper's name (0.6.0), so a word Parakeet writes for a name it doesn't know ("Jervis"
 * for "Jarvis") still counts after a command verb. Voiced and unvoiced pairs share a letter (b/p, d/t, g/k, v/f, z/s),
 * vowels other than a first one are dropped, doubles collapse. Two words are close when their keys start alike and differ
 * by one edit at most. Pure; only for names of five letters or two syllables (short words collide too easily).
 */
object Phonetic {
    fun eligible(name: String): Boolean = name.length >= 5 || Regex("[aeiouy]+").findAll(name).count() >= 2

    fun key(word: String): String {
        val w = word.lowercase(Locale.ROOT).filter { it in 'a'..'z' }
        if (w.isEmpty()) return ""
        val s = when {
            w.startsWith("kn") || w.startsWith("gn") || w.startsWith("pn") || w.startsWith("wr") -> w.substring(1)
            else -> w
        }
        val out = StringBuilder()
        var i = 0
        fun vowel(c: Char?) = c != null && c in "aeiouy"
        while (i < s.length) {
            val c = s[i]
            val n = s.getOrNull(i + 1)
            val code: String = when {
                vowel(c) -> if (i == 0) "A" else ""
                c == 'p' && n == 'h' -> { i++; "F" }
                c == 's' && n == 'h' -> { i++; "X" }
                c == 'c' && n == 'h' -> { i++; "X" }
                c == 't' && n == 'h' -> { i++; "0" }
                c == 'c' && n == 'k' -> { i++; "K" }
                c == 'c' -> if (n != null && n in "eiy") "S" else "K"
                c == 'g' -> if (n != null && n in "eiy") "J" else "K"
                c == 'q' || c == 'k' -> "K"
                c == 'x' -> "KS"
                c == 'z' || c == 's' -> "S"
                c == 'v' || c == 'f' -> "F"
                c == 'b' || c == 'p' -> "P"
                c == 'd' || c == 't' -> "T"
                c == 'j' -> "J"
                c == 'w' || c == 'h' -> if (vowel(n) && (i == 0 || !vowel(s[i - 1]))) c.uppercaseChar().toString() else ""
                else -> c.uppercaseChar().toString()
            }
            for (ch in code) if (out.isEmpty() || out.last() != ch) out.append(ch)
            i++
        }
        return out.toString()
    }

    /** [heard] may be [name] misheard: not the same word, both long enough, keys that start alike within one edit. */
    fun close(heard: String, name: String): Boolean {
        if (heard == name || heard.length < 4 || !eligible(name)) return false
        val a = key(heard)
        val b = key(name)
        if (a.isEmpty() || b.isEmpty() || a[0] != b[0]) return false
        return distance(a, b) <= 1
    }

    /** Two names a helper's can't share: the same key ("Groq" and "Grok"). */
    fun same(a: String, b: String): Boolean = a == b || (key(a).isNotEmpty() && key(a) == key(b))

    fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val t = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return d[b.length]
    }
}

/**
 * What answers a question before the local model is asked, in this order: Ask Claude's phrases (and Claude mode), then
 * texting without the model ("text mom I'm running late", [TextCommand]), then the model. A Claude command always wins,
 * so "ask Claude to text mom …" goes to Claude, never to the texting shortcut. "Thanks Claude" and "read Claude's
 * answer" are handled before this.
 */
object FastPath {
    enum class Way { HELPER, TEXT, MODEL }

    fun of(cmd: HelperCommand?, inHelperMode: Boolean, attachment: Attachment): Way = when {
        cmd is HelperCommand.Ask || cmd is HelperCommand.Join || (cmd == null && inHelperMode) -> Way.HELPER
        cmd == null && attachment == Attachment.NONE -> Way.TEXT
        else -> Way.MODEL
    }
}

/**
 * Strong signals that a question needs Claude, checked before Gemma spends a prefill (escalation trigger a). Kept
 * conservative: a question with a local action in it (a timer, an alarm, a text, a note, the calendar, an app, the maps,
 * the flashlight) never escalates; Robot handles it.
 */
object PreRouter {
    enum class Signal { REASONING, WRITING, CODE, MATH, CURRENT_INFO, LONG }

    /** More words than this: a long question goes to Claude. */
    const val LONG_WORDS = 40

    /** Local actions in English and (0.4.6) French: "mets une alarme", "rappelle-moi", "ouvre la météo". */
    private val LOCAL = Regex("""\b(timer|alarm|wake me|remind|reminder|text|texto|sms|message|email|e mail|mail|courriel|note|notes|jot|calendar|calendrier|event|appointment|meeting|schedule|agenda|open|launch|flashlight|torch|lampe|navigate|directions|route to|map|maps|call|contact|minuteur|alarme|reveil|reveille moi|rappel|rappelle moi|evenement|rendez vous|ouvre|ouvrir|itineraire|appelle|appeler)\b""")
    /**
     * Everyday codes, news and scores, in any word order: "the wifi code", "the code for the front door", "news from
     * my sister", "the latest from Sarah", "my credit score", "the score of my exam", and in French "des nouvelles de ma
     * sœur", "ma cote de crédit". They are taken out before the signals are looked for, so they never escalate (the rest
     * of the question still counts).
     */
    private val EVERYDAY: List<Regex> = listOf(
        Regex("""\b(area|postal|zip|dress|promo|discount|coupon|qr|bar|country|door|gate|wifi|wi fi|access|entry|pin|lock|garage|security|building|alarm|locker|safe|parking|router|network) code\b"""),
        Regex("""\bcodes? (for|to|of|on) (the |my |our |your |a )?(front |back |side |main |garage |building |apartment )?(wifi|wi fi|door|gate|garage|building|lock|locker|safe|alarm|apartment|house|router|network|entry|parking|bike lock|mailbox|elevator)\b"""),
        Regex("""\b(news|latest) (from|with|on|about) (my|our|your|his|her|their) \w+"""),
        Regex("""\bnews from \w+"""),
        Regex("""\blatest (from|with) \w+( \w+)?$"""),
        Regex("""\b(credit|fico|test|exam|sleep|health|my|our|his|her|their|your) scores?\b"""),
        Regex("""\bscores? (of|on|for|in|from) (my|our|his|her|their|your)\b( \w+)?"""),
        Regex("""\bscores? (of|on|for|in|from) (the |a )?(exam|test|quiz|midterm|final|essay|assignment|homework|credit check|sat|act)\b"""),
        Regex("""\b(des )?nouvelles (de|d|du) (ma|mon|mes|notre|nos|ta|ton|tes|sa|son|ses|leur|leurs|votre|vos) \w+"""),
        Regex("""\bdes nouvelles (de|d) \w+"""),
        Regex("""\b(ma|mon|mes|sa|son|notre|ta|ton|leur|votre) (cote|score|pointage|resultat)s?\b"""),
        Regex("""\bcote de credit\b"""),
    )
    private val CREATIVE = Regex("""\b(story|stories|poem|poems|joke|jokes|song|limerick|haiku|rhyme|riddle|fairy tale)\b""")
    private val SIGNALS: List<Pair<Signal, Regex>> = listOf(
        Signal.REASONING to Regex("""\bexplain (why|how)\b|\bcompare\b|\bstep by step\b|\bpros and cons\b|\bplan (a|my|an|the)\b|\bhelp me (plan|decide)\b|\bshould i\b.*\bor\b"""),
        Signal.WRITING to Regex("""^(please )?(write me|draft)\b|\bwrite me\b|\bwrite (a|an) (poem|story|essay|letter|speech|cover letter)\b"""),
        Signal.CODE to Regex("""\bcode\b|\bpython\b|\bjavascript\b|\bkotlin\b|\bregex\b|\bsql\b"""),
        Signal.MATH to Regex("""\bcalculate\b|\bsolve\b|\bintegral\b|\bderivative\b|\bequation\b|\bpercent(age)? of\b"""),
        // What changes from day to day, which Robot's model can't know (0.4.6: more of it, and in French): the news and
        // the latest, prices and rates, scores and who won, who holds an office now, the weather.
        Signal.CURRENT_INFO to Regex(listOf(
            """\blatest\b""", """\bnews\b""", """\bheadlines?\b""", """\btodays price\b""", """\bprice of\b""", """\bstock (price|market)\b""",
            """\bexchange rate\b""", """\bscore\b""", """\bweather\b""", """\bforecast\b""", """\bwho won\b""", """\belection results?\b""",
            """\bupdates? (on|about)\b""", """\bstill alive\b""",
            """\b(current|recent) (events|affairs|situation|president|prime minister|premier|ceo|leader|champions?|record|prices?|rates?|scores?|standings|rankings?|polls?)\b""",
            """\b(who is|whos) (the )?(current |new )?(president|prime minister|premier|chancellor|mayor|ceo|pope|king|queen|governor)\b""",
            """\b(what happened|whats happening|what is happening) (today|yesterday|this week|last night|in|with|at)\b""",
            // French, without accents (HelperRouter.plain): "les dernières nouvelles sur Artemis", "l'actualité", "le prix
            // de l'essence", "le taux de change", "qui a gagné", "le pointage", "la météo", "qui est le premier ministre".
            """\bdernieres nouvelles\b""", """\bnouvelles (sur|de|du|des|concernant|a propos|dans)\b""",
            """\bquelles sont les nouvelles\b""", """\bl?actualites?\b""", """\bl?actu\b""", """\bmanchettes?\b""", """\ble dernier (sur|de)\b""",
            """\bprix (de|du|des|dun|dune)\b""", """\btaux de change\b""", """\bbourse\b""", """\bqui a gagne\b""", """\bpointage\b""", """\bmeteo\b""",
            """\bqui est (le|la) (nouveau |nouvelle )?(president|presidente|premier ministre|premiere ministre|pdg|maire|mairesse|chef)\b""",
            """\bquest ce qui se passe (a|au|aux|en|dans|avec)\b""",
        ).joinToString("|")),
    )

    /** The words that make a question current information only because of the weather (0.4.0; "météo", 0.4.6). */
    private val WEATHER_WORDS = Regex("""\b(weather|forecast|meteo)\b""")

    /**
     * The strongest signal in [question], or null (Gemma answers). [localWeather]: the weather tool is on and may run (0.4.0;
     * PanelController: not on the lock screen), so "weather" and "forecast" no longer count as current information; every
     * other word and signal still does ("the price of a winter coat", "explain why it rains", a long question).
     * [localWeb] (0.9): web search is on with "Current information: Robot searches first", so current information stays
     * with Robot as the weather did.
     */
    fun signal(question: String, localWeather: Boolean = false, localWeb: Boolean = false): Signal? {
        val t = HelperRouter.plain(question).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (t.isEmpty() || LOCAL.containsMatchIn(t)) return null
        val rest = EVERYDAY.fold(t) { acc, r -> r.replace(acc, " ") }.let { if (localWeather) WEATHER_WORDS.replace(it, " ") else it }
        SIGNALS.firstOrNull { it.second.containsMatchIn(rest) }?.let { (signal, _) ->
            // Stories, poems and jokes are Robot's own to write (0.4.0); letters, speeches and essays still go to Claude.
            if (signal == Signal.WRITING && CREATIVE.containsMatchIn(rest)) return null
            if (signal == Signal.CURRENT_INFO && localWeb) return null
            return signal
        }
        return if (t.split(' ').size > LONG_WORDS) Signal.LONG else null
    }
}

/**
 * Gemma gave up (escalation trigger c): "I don't know", "I'm not sure", "I can't browse", "as an AI", a refusal ("I
 * can't help with that", "I'm unable to provide…", "je ne peux pas faire ça"), or nothing at all. Only near the start or
 * in a short reply, so a long answer that says "I'm not sure you meant X, but…" in passing is not a hedge. A reply that
 * says its facts may be out of date ("I don't have access to real-time information", "my knowledge cutoff", "for the
 * latest information, check…", "je n'ai pas accès à Internet") counts wherever it says so (0.4.6): Claude, with web
 * search, can answer that from today's sources.
 */
object Hedge {
    /** Words that say Robot's model can't or won't answer: only near the start or in a short reply. */
    private val PHRASES = Regex(listOf(
        """\bi (do not|dont) know\b""", """\b(i am|im) not (sure|certain)\b""", """\bas an? (ai|artificial intelligence|language model|large language model|ai model|ai assistant|ai language model)\b""",
        // "I can't" with anything after it but the idioms that aren't a refusal ("I can't wait to…").
        """\bi (cant|cannot|can not)\b(?! (wait|believe|stress|thank|imagine|emphasize|recommend|say enough|get enough|help but))""",
        """\b(i am|im) (unable|not able|not allowed|not permitted) to\b""", """\bi (wont|will not) be able to\b""",
        """\bi (dont|do not) have (the )?(ability|capability|means|capacity|tools|any way|a way) to\b""",
        """\bi (dont|do not) have (any |enough |specific |reliable |current |up to date |recent |real time |live )?(access|internet|web|online|information|data|details|knowledge|info)\b""",
        """\b(no|without) (internet|web|online|real time) access\b""", """\b(beyond|outside( of)?) my (capabilities|abilities|knowledge|scope)\b""",
        // French, without accents or apostrophes (HelperRouter.plain): "je ne sais pas", "je sais pas", "je ne suis pas sûr",
        // "je ne peux pas", "je peux pas" (Québec), "je ne suis pas en mesure", "chu pas capable", "je n'ai pas accès",
        // "j'ai pas accès", "en tant qu'IA".
        """\bje (ne )?sais pas\b""", """\b(chais|jsais|chsais) pas\b""", """\bje (ne )?suis pas (sur|sure|certain|certaine)\b""",
        """\bje (ne )?(peux|peut|pourrai|pourrais|pourrait) pas\b""", """\bje (ne )?suis pas (capable|en mesure)\b""", """\bchu pas capable\b""",
        """\bje suis incapable\b""", """\b(je nai|jai) pas (acces|access|la capacite|la possibilite|les moyens|dinformations?|de donnees|linformation|internet)\b""",
        """\b(pas|aucun|sans) (dacces|acces) (a|aux|au)\b""", """\ben tant (quia|quintelligence|que (ia|modele|assistant|intelligence))\b""",
    ).joinToString("|"))

    /** Words that say the answer may be out of date: anywhere in the reply. */
    private val STALE = Regex(listOf(
        """\b(dont|do not|cant|cannot|can not|unable to|not able to|no|without)\b[a-z ]{0,30}\breal time (information|data|updates|news|access|results|info|events)\b""",
        """\b(dont|do not|cant|cannot|can not|unable to|not able to)\b[a-z ]{0,20}\b(browse|search|access) the (internet|web)\b""",
        """\b(knowledge|training) (cutoff|cut off)\b""", """\bas of my (last|latest|most recent) (knowledge )?(update|training)\b""",
        """\bmy (knowledge|training data|training|information)\b[a-z ]{0,20}\b(goes up to|go up to|extends to|is limited to|only goes|ends in|stops in|out of date|outdated|not up to date)\b""",
        """\bfor the (latest|most recent|most up to date|current|up to date) (information|news|updates|details|info)\b""",
        """\b(reliable|reputable|trusted) news (source|sources|outlet|outlets|site|sites|website|websites)\b""",
        // French: "pas accès aux informations en temps réel", "mes connaissances s'arrêtent en 2024", "pour les dernières nouvelles".
        """\b(pas|aucun|sans|ne peux|peux pas)\b[a-z ]{0,40}\b(informations?|donnees|nouvelles|actualites|mises a jour) en temps reel\b""", """\bmes connaissances (sarretent|se limitent|datent|ne sont pas a jour|ne vont)\b""",
        """\bdate limite de mes connaissances\b""", """\bpour (les|des) (dernieres|plus recentes) (informations|nouvelles)\b""",
        """\b(je nai|jai) pas acces (a|aux|au) (internet|linternet|web|nouvelles|actualites|informations)\b""",
    ).joinToString("|"))

    /** Words in a hedge about going online or about today's events ([needsSearch]): "the internet", "current events". */
    private val ONLINE = Regex(listOf(
        """\b(internet|linternet|web|online|en ligne)\b""",
        """\b(current|recent|up to date|latest|live|todays) (events|affairs|information|info|news|data|details)\b""",
        """\b(informations?|nouvelles|donnees|evenements) (actuelles|actuels|recentes|recents|a jour|du jour)\b""", """\bl?actualites?\b""",
    ).joinToString("|"))

    private fun words(reply: String): String = HelperRouter.plain(reply).replace(Regex("[^a-z0-9]+"), " ").trim()

    fun detect(reply: String): Boolean {
        val t = words(reply)
        if (t.isEmpty()) return true
        if (STALE.containsMatchIn(t)) return true
        val short = t.split(' ').size <= 60
        val m = PHRASES.find(t) ?: return false
        return short || m.range.first < 120
    }

    /**
     * A reply [detect] found to hedge says the answer needs today's sources: its facts may be out of date, it can't go
     * online, it has no current information (0.6.0, with 0.4.6's hedges). Like the pre-router's current information, the
     * question then goes to a helper by itself only if that helper can search the web (EscalationPolicy's needsSearch);
     * otherwise Robot offers. Claude, which searches, is asked as in 0.4.6. An empty reply or a plain refusal ("I can't
     * help with that") needs no search.
     */
    fun needsSearch(reply: String): Boolean {
        val t = words(reply)
        return t.isNotEmpty() && (STALE.containsMatchIn(t) || ONLINE.containsMatchIn(t))
    }
}
