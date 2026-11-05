package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

/**
 * 1.2.3 (fix-123-c, bug 4): pure, one per model round, after [ClockEcho]. Told to answer general knowledge, Gemma still
 * opens such an answer with a disclaimer: "I do not have real-time population data, but according to recent estimates,
 * Portugal has a population of around 10.4 million", « Je n'ai pas accès aux données démographiques en temps réel, mais
 * je peux te dire que ... 10,4 millions ». That opening clause (I have no real-time data, internet or up-to-date access,
 * up to its "but" or « mais », or the end of its sentence) is removed before the reply is shown or spoken, and what
 * follows starts with a capital. Kept as written: a reply with nothing after the clause, a clause that names something
 * live (the weather, news, prices, scores), and every reply to a question about one ([live]), so Robot still says it
 * can't check those. Like [ClockEcho], the start is held back only while it could still be such a clause, at most
 * [hold] characters; a reply that starts any other way passes at once.
 */
class LiveHedge(question: String, private val hold: Int = HOLD) {
    private val held = StringBuilder()
    private var checking = !live(question)
    private var capNext = false

    /** Streamed text: what to show and speak now (possibly nothing yet). */
    fun feed(chunk: String): String {
        if (!checking) return cap(chunk)
        held.append(chunk)
        val m = HEDGE.matcher(held)
        if (m.lookingAt()) {
            if (m.end() < held.length) return release(cut(m.end()))
            if (held.length < hold) return ""
        } else if (m.hitEnd() && held.length < hold) return ""
        return release(0)
    }

    /** The round ended: whatever is still held back, without the clause if something follows it. */
    fun flush(): String {
        if (!checking) return ""
        val m = HEDGE.matcher(held)
        return release(if (m.lookingAt() && held.substring(m.end()).isNotBlank()) cut(m.end()) else 0)
    }

    /** Where the kept text starts: after the clause, unless it names something live. */
    private fun cut(end: Int): Int = if (TOPIC.containsMatchIn(plain(held.substring(0, end)))) 0 else end

    private fun release(from: Int): String {
        val out = held.substring(from)
        held.setLength(0)
        checking = false
        if (from > 0) capNext = true
        return cap(out)
    }

    private fun cap(s: String): String {
        if (!capNext) return s
        val i = s.indexOfFirst { !it.isWhitespace() }
        if (i < 0) return s
        capNext = false
        return s.substring(0, i) + s.substring(i, i + 1).uppercase(Locale.ROOT) + s.substring(i + 1)
    }

    companion object {
        const val HOLD = 160
        private const val NEG_EN = """I(?:\s+do\s+not|\s+don['’]t|\s+cannot|\s+can['’]t|\s+am\s+(?:unable|not\s+able)\s+to|['’]m\s+(?:unable|not\s+able)\s+to)\s+(?:have|access|check|provide|browse|look\s+up|get)(?!\p{L})"""
        private const val NEG_FR = """je\s+(?:n['’]aie?\s+(?:pas|aucun)|ne\s+peux\s+pas|ne\s+dispose\s+pas)(?!\p{L})"""
        /** What the model says it lacks: real-time, up-to-date or online information. */
        private const val NOW = """(?<!\p{L})(?:real[- ]?time|live|up[- ]to[- ]date|current|latest|internet|the\s+web|online|temps\s+réel|à\s+jour|actuel\p{L}*|récent\p{L}*|en\s+ligne|en\s+direct)(?!\p{L})"""
        /** Within the clause: anything but a sentence's end or its "but". */
        private const val CLAUSE = """(?:(?!\s(?:but|mais)\s)[^.!?\n])*?"""
        private const val SEP = """(?:,?\s+(?:but|mais|however|cependant)\s*,?\s+|[.;:]\s+|\s+[—–-]\s+)"""
        private val HEDGE = Regex(
            """\s*(?:(?:As\s+an\s+AI[^,.!?\n]{0,30},\s+)?(?:$NEG_EN|$NEG_FR)$CLAUSE$NOW$CLAUSE$SEP""" +
                """|(?:While|Although|Though|Bien\s+que|Même\s+si)\s+(?:$NEG_EN|$NEG_FR)$CLAUSE$NOW$CLAUSE,\s+)""",
            RegexOption.IGNORE_CASE,
        ).toPattern()

        /** Something only a live source knows (accents and case aside). */
        private const val TOPICS = """weather|forecast|temperature|rain|snow|news|headlines?|prices?|costs?|stocks?|scores?|games?|match|traffic|exchange\s+rate|bitcoin|meteo|previsions?|nouvelles|actualites|prix|couts?|bourse|pointage|resultats?|circulation|taux\s+de\s+change"""
        private val TOPIC = Regex("""\b(?:$TOPICS)\b""")
        private val LIVE_ASK = Regex("""\b(?:$TOPICS|right\s+now|today|tonight|currently|aujourd'hui|maintenant|ce\s+soir|en\s+ce\s+moment)\b""")

        private fun plain(s: String): String =
            Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").replace('’', '\'').lowercase(Locale.ROOT)

        /** A question about something live: its reply is never changed. */
        fun live(question: String): Boolean = LIVE_ASK.containsMatchIn(plain(question))
    }
}
