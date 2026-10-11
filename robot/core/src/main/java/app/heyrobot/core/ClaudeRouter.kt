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

sealed class ClaudeCommand {
    /**
     * One question for Claude. [deep]: you asked for depth ("think hard about", "take your time"). [extra]: you asked
     * about the screen or a photo; it goes only after a tap on a card.
     */
    data class Ask(val question: String, val deep: Boolean = false, val extra: Attachment = Attachment.NONE) : ClaudeCommand()

    /**
     * Claude mode: the following turns go to Claude. [question]: said in the same breath ("go get Claude and ask …").
     * [misheard]: a word that only sounded like the name ("get cloud"): the conversation so far goes only after a tap.
     */
    data class Join(val question: String? = null, val misheard: Boolean = false) : ClaudeCommand()

    /** Leave Claude mode ("thanks Claude", "back to Robot"); outside Claude mode it is ordinary talk. */
    data object Leave : ClaudeCommand()

    /** "Read Claude's answer": the Claude app's last answer, from the corner swipe's screen data. */
    data object ReadAnswer : ClaudeCommand()
}

object ClaudeRouter {
    /** "Claude" and how Parakeet may hear it; the mishearings count only after a command verb ([seq]'s "*"). */
    val ALIASES = setOf("claude", "cloud", "clod", "clawed", "claud", "klode", "clyde")
    /** The name as written: the only form that may be addressed directly or used after a weaker verb ("switch to"). */
    private const val NAME = "claude"

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
    /** Skips the filler at the start: the index of the first word that counts. */
    private fun head(ws: List<Word>): Int {
        var i = 0
        while (true) {
            val f = FILLER.firstOrNull { f -> f.indices.all { k -> ws.getOrNull(i + k)?.w == f[k] } } ?: break
            i += f.size
        }
        return i
    }

    /** True if [ws] from [at] reads [p], where "*" is the name or a mishearing of it and "=" the name exactly. */
    private fun seq(ws: List<Word>, at: Int, vararg p: String): Boolean =
        p.indices.all { k ->
            ws.getOrNull(at + k)?.let {
                when (p[k]) {
                    "*" -> it.w in ALIASES
                    "=" -> it.w == NAME
                    else -> it.w == p[k]
                }
            } == true
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
        arrayOf("thats", "all", "*"), arrayOf("that", "is", "all", "*"), arrayOf("stop", "*"), arrayOf("back", "to", "robot"),
        arrayOf("go", "back", "to", "robot"), arrayOf("switch", "back", "to", "robot"),
        arrayOf("merci", "*"), arrayOf("au", "revoir", "*"), arrayOf("cest", "tout", "*"), arrayOf("retour", "a", "robot"),
    )

    /** The command at the start of [text], or null: ordinary talk for Robot. */
    fun route(text: String): ClaudeCommand? {
        val ws = words(text)
        val i = head(ws)
        if (i >= ws.size) return null

        // Leave first: "stop Claude" and "thanks Claude" are never questions.
        LEAVES.firstOrNull { seq(ws, i, *it) }?.let { return ClaudeCommand.Leave }

        // "read Claude's answer".
        if (ws.getOrNull(i)?.w == "read" && ws.getOrNull(i + 1)?.w?.let { it.endsWith("s") && it.dropLast(1) in ALIASES } == true &&
            ws.getOrNull(i + 2)?.w in setOf("answer", "reply", "response")) return ClaudeCommand.ReadAnswer

        // "ask Claude …", "demande à Claude …" (and "demande Claude", when the "à" was not heard).
        val askAt = when {
            seq(ws, i, "ask", "*") -> i + 2
            seq(ws, i, "demande", "a", "*") -> i + 3
            seq(ws, i, "demande", "*") -> i + 2
            else -> -1
        }
        if (askAt >= 0) {
            val next = ws.getOrNull(askAt)?.w
            // "ask Clyde if he's coming to dinner": a message for a person (Robot's to handle), not a question for Claude.
            if (next in setOf("if", "whether", "si") && ws.getOrNull(askAt + 1)?.w in PERSON) return null
            // After a word that only sounds like the name, "ask Clyde to pick up milk" or "… whether dinner is ready" is
            // a message for a person too: only the name itself carries a request like that.
            val misheard = ws[askAt - 1].w != NAME
            if (misheard && next in setOf("to", "if", "whether", "si", "de", "d")) return null
            return rest(text, ws, askAt, ASK_DROP)?.let(::ask) ?: ClaudeCommand.Join(misheard = misheard)
        }

        // "what does Claude think about …", "qu'est-ce que Claude pense de …".
        if (seq(ws, i, "what", "does", "=", "think") || seq(ws, i, "whats", "=", "think")) {
            val at = i + if (ws[i].w == "what") 4 else 3
            val r = rest(text, ws, at, setOf("about", "of")) ?: return ClaudeCommand.Join()
            return ask("What do you think about ${r.trimEnd('?', '.', ' ')}?")
        }
        if (seq(ws, i, "quest", "ce", "que", "=", "pense")) {
            val r = rest(text, ws, i + 5) ?: return ClaudeCommand.Join()
            return ask("Que penses-tu ${r.trimEnd('?', '.', ' ')} ?")
        }

        // "go get Claude", "va chercher Claude": nothing after the name but a polite word, or "and" with a question.
        for (p in JOINS) {
            if (!seq(ws, i, *p)) continue
            var k = i + p.size
            val nameEnd = ws[i + p.size - 1]
            // A word that only sounded like the name ("get cloud", "go get Clyde"): Claude mode alone, or with an
            // explicit "and ask …". "Go get Clyde and the kids from school" or "get Clyde, he's at the door" is about a
            // person.
            if (ws[i + p.indexOfFirst { it == "*" || it == "=" }].w != NAME) {
                while (k < ws.size && ws[k].w in BARE_TAIL) k++
                if (k >= ws.size) return ClaudeCommand.Join(misheard = true)
                val asks = (ws[k].w in setOf("and", "then", "et", "puis") && ws.getOrNull(k + 1)?.w in setOf("ask", "demande", "demandes")) ||
                    ws[k].w in setOf("ask", "demande")
                return if (asks) ClaudeCommand.Join(rest(text, ws, k, JOIN_LINK + ASK_DROP + "demandes"), misheard = true) else null
            }
            while (k < ws.size && ws[k].w in JOIN_TAIL) k++
            if (k >= ws.size) return ClaudeCommand.Join()
            // "talk to Claude about my day".
            if (ws[k].w in ABOUT && k + 1 < ws.size) return ClaudeCommand.Join("Let's talk about " + text.substring(ws[k + 1].start).trim().trimEnd('.', '!', ' ') + ".")
            // "get Claude, what's …" or "go get Claude and ask him …": the rest is the first question.
            if (nameEnd.punctAfter in setOf(',', ':', '—', '-') || ws[k].w in JOIN_LINK) {
                return ClaudeCommand.Join(rest(text, ws, k, JOIN_LINK + ASK_DROP))
            }
            // "get cloud storage prices": not a command.
            return null
        }

        // The name itself, addressed: "Claude, what's …", "hey Claude explain …", "Claude" alone. Only the name as
        // written, and only into a question or a request: "Claude can come over tomorrow", "Claude Monet painted …" and
        // "Claude please stop calling me" are about someone called Claude, and a mishearing never counts here.
        if (ws[i].w == NAME) {
            if (i + 1 >= ws.size) return ClaudeCommand.Join()
            if (addressed(ws.drop(i + 1), comma = ws[i].punctAfter in setOf(',', ':', '—'))) return rest(text, ws, i + 1)?.let(::ask) ?: ClaudeCommand.Join()
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

    private fun ask(q: String) = ClaudeCommand.Ask(q, deep(q), extra(q))

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
     * You turned Claude down: "not Claude", "no Claude", "don't ask Claude", "don't check with Claude", "without Claude",
     * "leave Claude out of it", "you tell me", "tell me yourself", "answer it yourself", "on your own", "pas Claude", "sans
     * Claude", "ne demande pas à Claude", "réponds toi-même". Anywhere in what you said, not only at the start ("tell me
     * about this screen. Not Claude"). Robot then answers itself and doesn't bring Claude in for the rest of the
     * conversation, until you ask for Claude again ("ask Claude …", "go get Claude").
     *
     * Parakeet's mishearings count after a refusal word too ("not cloud", "don't ask clod"), but only where nothing could
     * follow them but a pause or a polite word: "no cloud today?", "not cloud storage", "don't ask Clyde to pick up milk"
     * and "without cloud" are about the weather, storage or a person. Words about someone called Claude never count:
     * "is it not Claude's birthday", "Claude is not available", "it's not Claude, it's Sam", "whether or not Claude
     * comes", "don't tell Claude", "tell me about yourself", "can you set alarms on your own?".
     */
    fun declines(text: String): Boolean {
        val ws = words(text)
        if (ws.isEmpty()) return false
        val start = head(ws)
        return ws.indices.any { i -> contrast(text, ws, i) || verbFirst(text, ws, i) || yourself(text, ws, i, start) || french(text, ws, i) }
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

    /** The name (or a mishearing of it) at [k], and nothing after it but a pause or a word that can't go on about a person. */
    private fun nameEnds(text: String, ws: List<Word>, k: Int): Boolean {
        val w = ws.getOrNull(k)?.w ?: return false
        if (w !in ALIASES) return false
        return pauseAfter(text, ws, k) || ws[k + 1].w in (if (w == NAME) NOT_AFTER else NOT_AFTER_HEARD)
    }

    /** The name after a refusal verb ("don't ask Claude …"): anything may follow the name itself, but "if he …" is a person. */
    private fun nameAfterVerb(text: String, ws: List<Word>, k: Int): Boolean {
        val w = ws.getOrNull(k)?.w ?: return false
        if (w != NAME) return nameEnds(text, ws, k)
        return !(ws.getOrNull(k + 1)?.w in setOf("if", "whether", "si") && ws.getOrNull(k + 2)?.w in PERSON)
    }

    /** "not Claude", "no Claude", "without Claude", "pas Claude", "sans Claude", "not to Claude", "pas à Claude". */
    private fun contrast(text: String, ws: List<Word>, i: Int): Boolean {
        val neg = ws[i].w
        if (neg !in setOf("not", "no", "pas", "sans", "without") || ws[i].punctAfter != null) return false
        if (ws.getOrNull(i - 1)?.w in BE) return false
        var k = i + 1
        if (neg in setOf("not", "pas") && ws.getOrNull(k)?.w in setOf("to", "a", "au", "with", "avec")) k++
        // "without cloud" is about storage: only the name itself counts after "without" and "sans".
        if (neg in setOf("sans", "without") && ws.getOrNull(k)?.w != NAME) return false
        return nameEnds(text, ws, k)
    }

    private val REFUSE_FIRST: List<Array<String>> = listOf(arrayOf("dont"), arrayOf("do", "not"), arrayOf("never"), arrayOf("stop"),
        arrayOf("no", "need", "to"), arrayOf("without"))
    /** What you might not want done with Claude: "don't ask / check with / use / bring in / send it to Claude". */
    private val REFUSED = setOf("ask", "asking", "check", "checking", "use", "using", "call", "calling", "get", "getting", "bring", "bringing", "involve",
        "involving", "send", "sending", "go", "going", "consult", "consulting", "hand", "handing", "pass", "passing", "forward", "forwarding", "run",
        "running", "need", "want", "mind", "contact", "contacting", "wait", "waiting", "try", "trying")
    /** Between the verb and the name: "check with", "send it to", "hand it over to", "run this by". */
    private val CONNECT = setOf("with", "to", "in", "it", "this", "that", "over", "on", "through", "by", "past", "for", "the", "question", "one", "up", "at")

    /**
     * "don't ask Claude", "do not check with Claude", "never mind Claude", "stop asking Claude", "no need to ask Claude",
     * "without asking Claude", "no need for Claude", "leave Claude out of it", "keep Claude out of this", "forget Claude".
     */
    private fun verbFirst(text: String, ws: List<Word>, i: Int): Boolean {
        REFUSE_FIRST.firstOrNull { seq(ws, i, *it) }?.let { p ->
            val v = i + p.size
            if (ws.getOrNull(v)?.w !in REFUSED) return@let
            var k = v + 1
            while (k < ws.size && k - v <= 3 && ws[k].w in CONNECT) k++
            // "don't use the cloud" is about storage: after "the", only the name itself.
            if (ws.getOrNull(k - 1)?.w == "the" && ws.getOrNull(k)?.w != NAME) return@let
            if (nameAfterVerb(text, ws, k)) return true
        }
        if (seq(ws, i, "no", "need", "for")) return nameEnds(text, ws, i + 3)
        val w = ws[i].w
        // "leave Claude out of it", "keep cloud out of this", "leave out Claude", "leave Claude alone".
        if (w in setOf("leave", "keep")) {
            val k = ws.getOrNull(i + 1)?.takeIf { it.w == "out" }?.let { i + 2 } ?: (i + 1)
            if (ws.getOrNull(k)?.w !in ALIASES) return false
            return k == i + 2 || ws.getOrNull(k + 1)?.w == "out" || (ws[k].w == NAME && ws.getOrNull(k + 1)?.w == "alone")
        }
        // "forget Claude", "forget about Claude", "skip Claude".
        if (w in setOf("forget", "skip")) return nameEnds(text, ws, if (ws.getOrNull(i + 1)?.w == "about") i + 2 else i + 1)
        return false
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

    private val FR_VERBS = setOf("demande", "demandes", "demandez", "appelle", "appelles", "appelez", "consulte", "consultes", "consultez", "utilise",
        "utilises", "utilisez", "verifie", "verifies", "verifiez", "interroge", "interroges", "interrogez", "passe", "passes", "passez", "envoie", "envoies",
        "envoyez", "va", "vas", "allez")
    private val FR_INF = setOf("demander", "appeler", "consulter", "utiliser", "verifier", "interroger", "passer", "envoyer", "chercher", "solliciter")
    private val FR_CONNECT = setOf("a", "au", "avec", "de", "par", "ca", "cela", "le", "la", "chercher", "lui")
    private val FR_SELF = setOf("reponds", "repond", "repondez", "dis", "dites", "fais", "faites", "explique", "expliquez", "decris", "resume", "reflechis",
        "trouve", "lis")

    /**
     * "ne demande pas à Claude", "n'appelle pas Claude", "ne va pas chercher Claude", "ne pas demander à Claude", "sans
     * passer par Claude", "pas besoin de Claude", "laisse Claude en dehors", "toi-même", "réponds tout seul".
     */
    private fun french(text: String, ws: List<Word>, i: Int): Boolean {
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
            return nameAfterVerb(text, ws, k)
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
            if (w == "pas" || w == "inutile") return nameEnds(text, ws, inf)
        }
        // "laisse Claude en dehors (de ça)", "laisse Claude de côté", "laisse tomber Claude".
        if (w in setOf("laisse", "laissez", "laisser")) {
            if (ws.getOrNull(i + 1)?.w == "tomber") return nameEnds(text, ws, i + 2)
            if (ws.getOrNull(i + 1)?.w !in ALIASES) return false
            return seq(ws, i + 2, "en", "dehors") || seq(ws, i + 2, "de", "cote")
        }
        return false
    }

    private val YES = setOf("yes", "yeah", "yep", "yup", "sure", "please", "ok", "okay", "go", "ahead", "do", "it", "check", "oui",
        "daccord", "vas", "y", "please", "absolutely", "definitely", "go ahead", "with", "claude", "ask", "him")

    /** A short yes to an offer ("yes", "sure, go ahead", "oui"), never a question of its own. */
    fun isYes(text: String): Boolean {
        val ws = words(text).map { it.w }
        if (ws.isEmpty() || ws.size > 6) return false
        val first = ws.first()
        return first in setOf("yes", "yeah", "yep", "yup", "sure", "ok", "okay", "please", "oui", "daccord", "absolutely", "definitely", "go") &&
            ws.all { it in YES }
    }
}

/**
 * What answers a question before the local model is asked, in this order: Ask Claude's phrases (and Claude mode), then
 * texting without the model ("text mom I'm running late", [TextCommand]), then the model. A Claude command always wins,
 * so "ask Claude to text mom …" goes to Claude, never to the texting shortcut. "Thanks Claude" and "read Claude's
 * answer" are handled before this.
 */
object FastPath {
    enum class Way { CLAUDE, TEXT, MODEL }

    fun of(cmd: ClaudeCommand?, claudeMode: Boolean, attachment: Attachment): Way = when {
        cmd is ClaudeCommand.Ask || cmd is ClaudeCommand.Join || (cmd == null && claudeMode) -> Way.CLAUDE
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

    private val LOCAL = Regex("""\b(timer|alarm|wake me|remind|reminder|text|texto|sms|message|email|e mail|mail|courriel|note|notes|jot|calendar|calendrier|event|appointment|meeting|schedule|agenda|open|launch|flashlight|torch|lampe|navigate|directions|route to|map|maps|call|contact)\b""")
    /**
     * Everyday codes, news and scores, in any word order: "the wifi code", "the code for the front door", "news from
     * my sister", "the latest from Sarah", "my credit score", "the score of my exam". They are taken out before the
     * signals are looked for, so they never escalate (the rest of the question still counts).
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
    )
    private val CREATIVE = Regex("""\b(story|stories|poem|poems|joke|jokes|song|limerick|haiku|rhyme|riddle|fairy tale)\b""")
    private val SIGNALS: List<Pair<Signal, Regex>> = listOf(
        Signal.REASONING to Regex("""\bexplain (why|how)\b|\bcompare\b|\bstep by step\b|\bpros and cons\b|\bplan (a|my|an|the)\b|\bhelp me (plan|decide)\b|\bshould i\b.*\bor\b"""),
        Signal.WRITING to Regex("""^(please )?(write me|draft)\b|\bwrite me\b|\bwrite (a|an) (poem|story|essay|letter|speech|cover letter)\b"""),
        Signal.CODE to Regex("""\bcode\b|\bpython\b|\bjavascript\b|\bkotlin\b|\bregex\b|\bsql\b"""),
        Signal.MATH to Regex("""\bcalculate\b|\bsolve\b|\bintegral\b|\bderivative\b|\bequation\b|\bpercent(age)? of\b"""),
        Signal.CURRENT_INFO to Regex("""\blatest\b|\bnews\b|\btodays price\b|\bprice of\b|\bstock price\b|\bexchange rate\b|\bscore\b|\bweather\b|\bforecast\b|\bwho won\b"""),
    )

    /**
     * The strongest signal in [question], or null (Gemma answers). [localWeather]: the weather tool is on (0.4.0), so a
     * weather question ("what's the forecast", "cloud cover tomorrow") is Robot's, not current news for Claude; the
     * other signals in it still count ("explain why it rains").
     */
    fun signal(question: String, localWeather: Boolean = false): Signal? {
        val t = ClaudeRouter.plain(question).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (t.isEmpty() || LOCAL.containsMatchIn(t)) return null
        val rest = EVERYDAY.fold(t) { acc, r -> r.replace(acc, " ") }
        SIGNALS.firstOrNull { it.second.containsMatchIn(rest) }?.let { (signal, _) ->
            if (signal == Signal.CURRENT_INFO && localWeather && ToolGroup.WEATHER in ToolRouter.route(question)) return null
            // Stories, poems and jokes are Robot's own to write (0.4.0); letters, speeches and essays still go to Claude.
            if (signal == Signal.WRITING && CREATIVE.containsMatchIn(rest)) return null
            return signal
        }
        return if (t.split(' ').size > LONG_WORDS) Signal.LONG else null
    }
}

/**
 * Gemma gave up (escalation trigger c): "I don't know", "I'm not sure", "I can't browse", "as an AI", or nothing at all.
 * Only near the start or in a short reply, so a long answer that says "I'm not sure you meant X, but…" in passing is
 * not a hedge.
 */
object Hedge {
    private val PHRASES = Regex(
        """\bi (do not|dont) know\b|\bi am not sure\b|\bim not sure\b|\bi cant browse\b|\bi cannot browse\b|\bas an ai\b|""" +
            """\bi (cant|cannot|can not) check\b|\b(i )?(dont|do not) have (access to )?(the )?internet\b|\bno internet access\b|""" +
            """\bi (dont|do not) have (real time|current|up to date|recent) (information|data)\b|\bi (cant|cannot) (access|look up)\b|""" +
            """\bje ne sais pas\b|\bje ne suis pas sur\b""",
    )

    fun detect(reply: String): Boolean {
        val t = ClaudeRouter.plain(reply).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (t.isEmpty()) return true
        val short = t.split(' ').size <= 60
        val m = PHRASES.find(t) ?: return false
        return short || m.range.first < 120
    }
}
