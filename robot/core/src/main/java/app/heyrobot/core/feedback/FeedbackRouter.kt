package app.heyrobot.core.feedback

import java.text.Normalizer
import java.util.Locale

// Pure (0.7). The panel's feedback phrases, decided by code before anything reaches the model, like ConversationRouter:
// "report a bug", "send feedback", "I have an idea", "feature request", and in Québec French « signaler un bogue » (or
// « bug »), « j'ai une idée », « envoyer un commentaire ». They open Settings › About › Send feedback with the kind chosen,
// and what you said after the phrase ("I have an idea: read my calendar in the brief") is the start of your text. A phrase
// counts only at the start of what was said, with only filler before it ("hey robot", "please", "can you", "I want to"); a
// question about something else stays a question: "how do I report a bug in Excel?", "can you report a bug in Excel?".
// The panel never sends anything itself: the page opens and you choose.

/** What the panel was asked: Send feedback with [kind], and [text] said after the phrase (null: nothing). */
data class FeedbackCommand(val kind: FeedbackKind, val text: String?)

object FeedbackRouter {
    /** Words allowed before a phrase (English and French), as their plain forms ([plain]). */
    private val FILLER: List<List<String>> = listOf(
        listOf("hey", "robot"), listOf("hi", "robot"), listOf("ok", "robot"), listOf("okay", "robot"), listOf("hello", "robot"), listOf("he", "robot"),
        listOf("dis", "robot"), listOf("robot"), listOf("please"), listOf("can", "you"), listOf("could", "you"), listOf("would", "you"),
        listOf("will", "you"), listOf("okay"), listOf("ok"), listOf("so"), listOf("um"), listOf("uh"), listOf("hey"), listOf("hi"), listOf("and"),
        listOf("now"), listOf("i", "want", "to"), listOf("i", "wanna"), listOf("i", "would", "like", "to"), listOf("id", "like", "to"),
        listOf("i", "need", "to"), listOf("let", "me"), listOf("sil", "te", "plait"), listOf("sil", "vous", "plait"), listOf("peux", "tu"),
        listOf("pourrais", "tu"), listOf("pouvez", "vous"), listOf("est", "ce", "que", "tu", "peux"), listOf("est", "ce", "que", "je", "peux"),
        listOf("je", "veux"), listOf("je", "voudrais"), listOf("jaimerais"), listOf("je", "dois"), listOf("dac"), listOf("daccord"),
        listOf("alors"), listOf("bon"),
    )

    /** Words that may follow a phrase without being your text. */
    private val TAIL = setOf("please", "now", "thanks", "thank", "you", "robot", "ok", "okay", "merci", "svp", "sil", "te", "vous", "plait", "maintenant")

    /** Linking words dropped from the start of your text ("I have an idea that …"). */
    private val LINKS = setOf("that", "que", "qu")

    /** A pattern: one set of words per place, "a|b" for either, "?a|b" for an optional place. */
    private class Place(val words: Set<String>, val optional: Boolean)

    private fun p(vararg parts: String): List<Place> = parts.map { Place(it.removePrefix("?").split('|').toSet(), it.startsWith("?")) }

    private val BUG = listOf(
        p("report|file|send|submit|log|make|open|write", "?a|an|the|this", "bug|bogue", "?report"),
        p("report", "?a|an|this", "problem|issue|crash"),
        p("bug", "report"),
        p("signaler|signale|signalez|rapporter|rapporte|rapportez", "?un|le|ce", "bogue|bug|probleme|plantage"),
        p("faire|fais|faites|envoyer|envoie|envoyez", "un", "rapport", "de", "bogue|bug"),
        p("rapport", "de", "bogue|bug"),
    )
    private val IDEA = listOf(
        p("i", "have|got", "an", "idea"),
        p("ive", "got", "an", "idea"),
        p("heres", "an", "idea"),
        p("?i", "?have|got", "a", "feature", "request"),
        p("feature", "request"),
        p("request|suggest", "a", "feature"),
        p("make|send|submit", "a", "feature", "request"),
        p("jai|jaurais", "une", "idee|suggestion"),
        p("voici", "une", "idee"),
        p("?une", "demande", "de", "fonctionnalite"),
        p("suggerer|suggere|proposer|propose", "une", "fonctionnalite|idee"),
    )
    private val OTHER = listOf(
        p("send|give|leave|submit|share", "?some|my|a|the", "feedback"),
        p("envoyer|envoie|envoyez|laisser|laisse|laissez|donner|donne|donnez", "un|des|mon|mes", "commentaire|commentaires|avis"),
    )

    /** "Était" → "etait", "j'ai" → "jai": no accents, apostrophes or case. */
    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT).replace("'", "").replace("’", "")

    private class Word(val w: String, val start: Int, val end: Int)

    private fun words(text: String): List<Word> {
        val out = mutableListOf<Word>()
        var i = 0
        while (i < text.length) {
            if (!text[i].isLetterOrDigit()) { i++; continue }
            val start = i
            // A word keeps its inner apostrophes ("j'ai", "here's").
            while (i < text.length && (text[i].isLetterOrDigit() || (text[i] in "'’" && i + 1 < text.length && text[i + 1].isLetterOrDigit()))) i++
            out += Word(plain(text.substring(start, i)), start, i)
        }
        return out
    }

    private fun head(ws: List<Word>): Int {
        var i = 0
        while (true) {
            val f = FILLER.firstOrNull { f -> f.indices.all { k -> ws.getOrNull(i + k)?.w == f[k] } } ?: break
            i += f.size
        }
        return i
    }

    /** Where [pattern] ends when it matches at [at] (optional places taken when they fit), or -1. */
    private fun end(ws: List<Word>, at: Int, pattern: List<Place>): Int {
        fun go(i: Int, k: Int): Int {
            if (k == pattern.size) return i
            val place = pattern[k]
            if (i < ws.size && ws[i].w in place.words) go(i + 1, k + 1).let { if (it >= 0) return it }
            return if (place.optional) go(i, k + 1) else -1
        }
        return go(at, 0)
    }

    /** The feedback phrase at the start of [text], or null (anything else is for Robot to answer). */
    fun route(text: String): FeedbackCommand? {
        val ws = words(text)
        val i = head(ws)
        if (i >= ws.size) return null
        for ((kind, list) in listOf(FeedbackKind.BUG to BUG, FeedbackKind.IDEA to IDEA, FeedbackKind.OTHER to OTHER)) {
            for (pattern in list) {
                val e = end(ws, i, pattern)
                if (e < 0) continue
                val rest = ws.drop(e)
                if (rest.all { it.w in TAIL }) return FeedbackCommand(kind, null)
                // Words after it in a question: a question about that ("can you report a bug in Excel?").
                if (text.trimEnd().endsWith("?")) return null
                val first = rest.dropWhile { it.w in LINKS }.firstOrNull() ?: return FeedbackCommand(kind, null)
                val said = text.substring(first.start).trim().take(FeedbackPrefill.MAX_TEXT)
                return FeedbackCommand(kind, said.replaceFirstChar { it.titlecase(Locale.ROOT) })
            }
        }
        return null
    }
}
