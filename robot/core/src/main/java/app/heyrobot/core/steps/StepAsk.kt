// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.steps

import app.heyrobot.core.read.ReadRouter
import app.heyrobot.core.sums.SumsRouter

// "How much butter again?" while the steps are open: the page's own line that names most of what you asked about, as
// the page wrote it. Nothing is made up: no line names it, no answer (the panel goes on as before).
object StepAsk {
    private val SKIP = setOf(
        "how", "much", "many", "what", "whats", "which", "when", "where", "who", "why", "long", "do", "does", "did", "i", "you", // l10n: not text (words Robot hears)
        "we", "it", "is", "are", "was", "the", "a", "an", "of", "to", "for", "in", "on", "at", "with", "and", "or", "again", // l10n: not text (words Robot hears)
        "need", "use", "put", "add", "say", "said", "tell", "me", "about", "there", "that", "this", "my", "your", "be", "s", // l10n: not text (words Robot hears)
        "combien", "quoi", "quel", "quelle", "quels", "quelles", "quand", "ou", "qui", "pourquoi", "comment", "de", "du", "des", // l10n: not text (words Robot hears)
        "le", "la", "les", "l", "un", "une", "d", "j", "je", "tu", "il", "on", "est", "c", "ce", "ca", "qu", "que", "en", "faut", // l10n: not text (words Robot hears)
        "mettre", "met", "dit", "dis", "moi", "encore", "deja", "y", "a", "avec", "pour", "dans", "et", "ai", "besoin", "temps", // l10n: not text (words Robot hears)
        "so", "ok", "okay", "then", "now", "alors", "donc", "puis", "wheres", // l10n: not text (words Robot hears; fillers, since every other word must be on the page)
    )

    private val AMOUNT = listOf("how much", "how many", "how long", "what temperature", "combien", "quelle temperature") // l10n: not text (words Robot hears)

    // A question about the page: an amount, a time, a temperature (« combien », « quelle température »). Anything else
    // ("what time is it", "set a timer…") goes where it would without the steps.
    private val ASKS = listOf("how much", "how many", "how long", "how hot", "how big", "what temperature", "what temp", // l10n: not text (words Robot hears)
        "what size", "what kind of", "which", "do i need", "do we need", "combien", "pendant combien", "quelle temperature", // l10n: not text (words Robot hears)
        "quelle taille", "quel genre de", "faut il", "est ce qu il faut", "il faut combien", // l10n: not text (words Robot hears)
        "where is", "where s", "wheres", "ou est") // l10n: not text (words Robot hears; "where is the reset button", only when a line answers; "where's" folds to "where s")

    private val QWORDS = setOf("what", "whats", "how", "which", "quel", "quelle", "quels", "quelles", "combien") // l10n: not text (words Robot hears)
    private val TOPIC = setOf("temperature", "temp", "hot", "chaud", "size", "taille") // l10n: not text (words Robot hears)
    private val TEMP = setOf("temperature", "temp", "hot", "chaud", "chaude", "degree", "degre") // l10n: not text (words Robot hears)
    private val TIME_ASK = listOf("how long", "how much time", "how many minutes", "combien de temps", "pendant combien") // l10n: not text (words Robot hears)
    private val TIME = setOf("time", "minute", "min", "hour", "heure", "second", "seconde", "duree") // l10n: not text (words Robot hears)
    private val TIMER = setOf("timer", "minuteur", "minuterie", "alarm", "alarme") // l10n: not text (words Robot hears)
    private val DEGREE = Regex("""\d\s*°|\d\s*(?:degrees?|degrés?|degres?)\b|\b\d{3}\s*[fc]\b""", RegexOption.IGNORE_CASE) // l10n: not text (words on a page)

    /** [q] is shaped like a question for the page, anywhere in the sentence ("so how much butter", « quelle est la
     *  température du four »). The steps take it only when a line answers it (StepRouter.during). */
    fun asks(q: String): Boolean {
        val t = " ${ReadRouter.fold(q)} "
        if (ASKS.any { t.contains(" $it ") }) return true
        val w = t.trim().split(' ')
        return w.any { it in QWORDS } && w.any { it in TOPIC }
    }

    /** Goes where it would without the steps even when the page has a line for it: Sums ("how many grams in a cup") and
     *  the timer ("how long is left on my timer"). */
    fun elsewhere(q: String): Boolean = words(q).any { it in TIMER } || SumsRouter.route(q) != null

    private fun tidy(l: String) = l.replace(Regex("""\s+"""), " ").removePrefix("- ").removePrefix("• ")

    private fun stem(w: String) = if (w.length > 3 && w != "temps") w.removeSuffix("s") else w // « temps » is time, not "temp"

    private fun words(s: String): Set<String> = ReadRouter.fold(s).split(' ').filter { it.isNotEmpty() }.map(::stem).toSet()

    /** The best line of [page] for [question], or null. A bare "How long?" (« combien de temps ») reads [shown], the step
     *  on screen, when it names a time, else the page's first line that does; "What temperature?" finds the degrees. */
    fun answer(page: String, question: String, shown: String? = null): String? {
        val asked = words(question)
        // SKIP is checked before the plural goes ("does", "has" stay small words).
        val want = ReadRouter.fold(question).split(' ').filter { it.isNotEmpty() && it !in SKIP }.map(::stem).toSet() - SKIP
        val q = " ${ReadRouter.fold(question)} "
        val time = TIME_ASK.any { q.contains(" $it ") }
        val temp = asked.any { it in TEMP }
        if (want.isEmpty()) {
            if (!time) return null
            shown?.takeIf { StepTime.seconds(it) != null }?.let { return tidy(it.trim()) }
            return page.lines().map { it.trim() }.firstOrNull { it.isNotEmpty() && StepTime.seconds(it) != null }?.let(::tidy)
        }
        // Every word asked about is on the page, a temperature word on a line with degrees, a time word on a line with a
        // duration; else the question isn't about the page ("how long until Christmas", "the temperature outside").
        val lines = page.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val onPage = lines.flatMap { words(it) }.toSet()
        val deg = lines.any { DEGREE.containsMatchIn(it) }
        val dur = lines.any { StepTime.seconds(it) != null }
        if (!want.all { it in onPage || (deg && it in TEMP) || (dur && it in TIME) }) return null
        // "How much", "how long", « combien »: a line with a number in it is the better answer (the butter, not the title).
        val amount = AMOUNT.any { q.contains(" $it ") }
        var best: String? = null
        var score = 0
        for (l in lines) {
            val w = words(l)
            val d = DEGREE.containsMatchIn(l)
            val t = StepTime.seconds(l) != null
            val hits = want.count { it in w || (d && it in TEMP) || (t && it in TIME) } + if (temp && d) 1 else 0
            if (hits == 0) continue
            val n = hits * 2 + (if (amount && l.any(Char::isDigit)) 1 else 0) + if (time && StepTime.seconds(l) != null) 1 else 0
            if (n > score) { score = n; best = l }
        }
        return best?.let(::tidy)
    }
}
