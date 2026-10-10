package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. What the owner's words mean for Claude, decided by code and never by the local model (docs/ask-claude.md "How
// it is triggered"). The router runs on the transcript or the typed text before anything reaches Gemma. Matching is
// anchored at the start of the utterance, with only filler words ("hey robot", "please", "can you", "okay") in front,
// so "what's the weather like on Claude's birthday" stays local. Parakeet's mishearings of "Claude" ("cloud", "clod",
// "clawed", "claud", "klode", "clyde") count only in command position: "ask cloud about X" is a command, "what's the
// cloud cover tomorrow" is not.

sealed class ClaudeCommand {
    /**
     * One question for Claude. [deep]: the owner asked for depth ("think hard about", "take your time"). [extra]: the
     * owner asked about the screen or a photo; it goes only after a tap on a card.
     */
    data class Ask(val question: String, val deep: Boolean = false, val extra: Attachment = Attachment.NONE) : ClaudeCommand()

    /** Claude mode: the following turns go to Claude. [question]: said in the same breath ("go get Claude and ask …"). */
    data class Join(val question: String? = null) : ClaudeCommand()

    /** Leave Claude mode ("thanks Claude", "back to Robot"); outside Claude mode it is ordinary talk. */
    data object Leave : ClaudeCommand()

    /** "Read Claude's answer": the Claude app's last answer, from the corner swipe's screen data. */
    data object ReadAnswer : ClaudeCommand()
}

object ClaudeRouter {
    /** "Claude" and how Parakeet may hear it; accepted in command position only. */
    val ALIASES = setOf("claude", "cloud", "clod", "clawed", "claud", "klode", "clyde")

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

    /** Filler allowed in front of a command, English and French. "hey"/"hi"/"ok" before the name make it a vocative. */
    private val FILLER: List<List<String>> = listOf(
        listOf("hey", "robot"), listOf("hi", "robot"), listOf("ok", "robot"), listOf("okay", "robot"), listOf("hello", "robot"),
        listOf("he", "robot"), listOf("dis", "robot"), listOf("robot"),
        listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"), listOf("will", "you"),
        listOf("okay"), listOf("ok"), listOf("so"), listOf("um"), listOf("uh"), listOf("hey"), listOf("hi"), listOf("and"),
        listOf("sil", "te", "plait"), listOf("sil", "vous", "plait"), listOf("peux", "tu"), listOf("pourrais", "tu"),
        listOf("est", "ce", "que", "tu", "peux"), listOf("dac"), listOf("daccord"),
    )
    private val VOCATIVE_LEAD = setOf("hey", "hi", "ok", "okay", "he")

    private class Head(val at: Int, val vocative: Boolean)

    /** Skips the filler at the start; [Head.vocative]: the last filler word was "hey", "hi" or "ok". */
    private fun head(ws: List<Word>): Head {
        var i = 0
        var vocative = false
        while (true) {
            val f = FILLER.firstOrNull { f -> f.indices.all { k -> ws.getOrNull(i + k)?.w == f[k] } } ?: break
            // "hey" and "ok" alone are filler only before the name or another filler; "hey robot" is always filler.
            i += f.size
            vocative = f.size == 1 && f[0] in VOCATIVE_LEAD
        }
        return Head(i, vocative)
    }

    private fun alias(w: Word?): Boolean = w != null && w.w in ALIASES

    /** True if [ws] from [at] reads [p], where "*" is the name (an alias). */
    private fun seq(ws: List<Word>, at: Int, vararg p: String): Boolean =
        p.indices.all { k -> ws.getOrNull(at + k)?.let { if (p[k] == "*") it.w in ALIASES else it.w == p[k] } == true }

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

    private val JOINS: List<Array<String>> = listOf(
        arrayOf("go", "get", "*"), arrayOf("go", "and", "get", "*"), arrayOf("bring", "in", "*"), arrayOf("bring", "*", "in"), arrayOf("bring", "*"),
        arrayOf("get", "*"), arrayOf("let", "me", "talk", "to", "*"), arrayOf("lets", "talk", "to", "*"), arrayOf("switch", "to", "*"),
        arrayOf("i", "want", "to", "talk", "to", "*"), arrayOf("talk", "to", "*"), arrayOf("put", "*", "on"),
        arrayOf("va", "chercher", "*"), arrayOf("passe", "moi", "*"), arrayOf("je", "veux", "parler", "a", "*"), arrayOf("parle", "a", "*"),
    )
    private val LEAVES: List<Array<String>> = listOf(
        arrayOf("thanks", "*"), arrayOf("thank", "you", "*"), arrayOf("thx", "*"), arrayOf("bye", "*"), arrayOf("bye", "bye", "*"), arrayOf("goodbye", "*"),
        arrayOf("thats", "all", "*"), arrayOf("that", "is", "all", "*"), arrayOf("stop", "*"), arrayOf("back", "to", "robot"),
        arrayOf("go", "back", "to", "robot"), arrayOf("switch", "back", "to", "robot"),
        arrayOf("merci", "*"), arrayOf("au", "revoir", "*"), arrayOf("cest", "tout", "*"), arrayOf("retour", "a", "robot"),
    )

    /** The command at the start of [text], or null: ordinary talk for Robot. */
    fun route(text: String): ClaudeCommand? {
        val ws = words(text)
        val h = head(ws)
        val i = h.at
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
        if (askAt >= 0) return rest(text, ws, askAt, ASK_DROP)?.let(::ask) ?: ClaudeCommand.Join()

        // "what does Claude think about …", "qu'est-ce que Claude pense de …".
        if (seq(ws, i, "what", "does", "*", "think") || seq(ws, i, "whats", "*", "think")) {
            val at = i + if (ws[i].w == "what") 4 else 3
            val r = rest(text, ws, at, setOf("about", "of")) ?: return ClaudeCommand.Join()
            return ask("What do you think about ${r.trimEnd('?', '.', ' ')}?")
        }
        if (seq(ws, i, "quest", "ce", "que", "*", "pense")) {
            val r = rest(text, ws, i + 5) ?: return ClaudeCommand.Join()
            return ask("Que penses-tu ${r.trimEnd('?', '.', ' ')} ?")
        }

        // "go get Claude", "va chercher Claude": nothing after the name but a polite word, or "and" with a question.
        for (p in JOINS) {
            if (!seq(ws, i, *p)) continue
            var k = i + p.size
            while (k < ws.size && ws[k].w in JOIN_TAIL) k++
            if (k >= ws.size) return ClaudeCommand.Join()
            val nameEnd = ws[i + p.size - 1]
            // "get Claude, what's …" or "go get Claude and ask him …": the rest is the first question.
            if (nameEnd.punctAfter in setOf(',', ':', '—', '-') || ws[k].w in JOIN_LINK) {
                return ClaudeCommand.Join(rest(text, ws, k, JOIN_LINK + ASK_DROP))
            }
            // "get cloud storage prices": not a command.
            return null
        }

        // The name itself, as a vocative: "Claude, …", "hey Claude …". An alias needs the comma or the "hey"; the real
        // name may also go straight into a question ("Claude what is …"), never into a statement ("Claude Monet …").
        if (alias(ws[i])) {
            val w = ws[i]
            val questionNext = w.w == "claude" && ws.getOrNull(i + 1)?.w in QUESTION_WORDS
            if (w.punctAfter in setOf(',', ':', '—') || h.vocative || questionNext) {
                return rest(text, ws, i + 1)?.let(::ask) ?: ClaudeCommand.Join()
            }
        }
        return null
    }

    private val QUESTION_WORDS = setOf("what", "whats", "why", "how", "who", "whos", "when", "where", "which", "can", "could",
        "tell", "explain", "write", "give", "help", "please", "pourquoi", "comment", "quest", "dis", "peux", "explique")

    private fun ask(q: String) = ClaudeCommand.Ask(q, deep(q), extra(q))

    private val DEEP = listOf("think hard", "think carefully", "think it through", "take your time", "in depth", "in detail",
        "reflechis bien", "prends ton temps", "en detail")

    /** The owner asked for depth: high effort and the longer answer limit. */
    fun deep(text: String): Boolean = plain(text).let { t -> DEEP.any { t.contains(it) } }

    private val SCREEN = Regex("""\b(on|of|about) (my|the|this) screen\b|\bthis screen\b|\bmon ecran\b|\bcet ecran\b""")
    private val PHOTO = Regex("""\b(this|the|my) (photo|picture|image|pic)\b|\bcette (photo|image)\b""")

    /** "about what's on my screen", "about this photo": what the owner wants to share (only after a tap). */
    fun extra(text: String): Attachment {
        val t = plain(text)
        return when {
            SCREEN.containsMatchIn(t) -> Attachment.SCREEN
            PHOTO.containsMatchIn(t) -> Attachment.PHOTO
            else -> Attachment.NONE
        }
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
 * Strong signals that a question needs Claude, checked before Gemma spends a prefill (escalation trigger a). Kept
 * conservative: a question with a local action in it (a timer, an alarm, a text, a note, the calendar, an app, the maps,
 * the flashlight) never escalates; Robot handles it.
 */
object PreRouter {
    enum class Signal { REASONING, WRITING, CODE, MATH, CURRENT_INFO, LONG }

    /** More words than this: a long question goes to Claude. */
    const val LONG_WORDS = 40

    private val LOCAL = Regex("""\b(timer|alarm|wake me|remind|reminder|text|texto|sms|message|email|e mail|mail|courriel|note|notes|jot|calendar|calendrier|event|appointment|meeting|schedule|agenda|open|launch|flashlight|torch|lampe|navigate|directions|route to|map|maps|call|contact)\b""")
    private val SIGNALS: List<Pair<Signal, Regex>> = listOf(
        Signal.REASONING to Regex("""\bexplain (why|how)\b|\bcompare\b|\bstep by step\b|\bpros and cons\b|\bplan (a|my|an|the)\b|\bhelp me (plan|decide)\b|\bshould i\b.*\bor\b"""),
        Signal.WRITING to Regex("""^(please )?(write me|draft)\b|\bwrite me\b|\bwrite (a|an) (poem|story|essay|letter|speech|cover letter)\b"""),
        Signal.CODE to Regex("""(?<!area |postal |zip |dress |promo )\bcode\b|\bpython\b|\bjavascript\b|\bkotlin\b|\bregex\b|\bsql\b"""),
        Signal.MATH to Regex("""\bcalculate\b|\bsolve\b|\bintegral\b|\bderivative\b|\bequation\b|\bpercent(age)? of\b"""),
        Signal.CURRENT_INFO to Regex("""\blatest\b|\bnews\b|\btodays price\b|\bprice of\b|\bstock price\b|\bexchange rate\b|\bscore\b|\bweather\b|\bforecast\b|\bwho won\b"""),
    )

    /** The strongest signal in [question], or null (Gemma answers). */
    fun signal(question: String): Signal? {
        val t = ClaudeRouter.plain(question).replace(Regex("[^a-z0-9]+"), " ").trim()
        if (t.isEmpty() || LOCAL.containsMatchIn(t)) return null
        SIGNALS.firstOrNull { it.second.containsMatchIn(t) }?.let { return it.first }
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
