// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.text.Normalizer

// Pure. named-timers: the phrases for Robot's own timers, English and French, read before the model and only while the
// timers are on (Settings › Routines). Setting needs a timer word and one length; asking, cancelling and stopping need a
// timer word or the name of a timer Robot keeps, so "how long is the drive" or "stop the music" are never taken.

sealed class TimerCommand {
    abstract val french: Boolean
    /** [name] as said, accents kept ("" unnamed). */
    data class Set(val name: String, val seconds: Long, override val french: Boolean) : TimerCommand()
    /** [name]: "" for every timer. */
    data class Left(val name: String, override val french: Boolean) : TimerCommand()
    /** [name]: "" for "the timer", [ALL] for every one. */
    data class Cancel(val name: String, override val french: Boolean) : TimerCommand()
    /** While one rings: [name] "" for every ringing one. */
    data class Stop(val name: String, override val french: Boolean) : TimerCommand()

    companion object { const val ALL = "*" }
}

object OwnTimerPhrases {
    private class Said(val shown: List<String>, val w: List<String>)

    private fun said(text: String): Said {
        val shown = text.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }
        return Said(shown, shown.map { Normalizer.normalize(it, Normalizer.Form.NFD).replace(Regex("""\p{M}+"""), "") })
    }

    private val NOUNS_EN = setOf("timer", "timers") // l10n: not text (the words these phrases are found by, to the end of the file)
    private val NOUNS_FR = setOf("minuterie", "minuteries", "minuteur", "minuteurs")
    private val FR_WORDS = NOUNS_FR + setOf("arrete", "annule", "combien", "reste", "ca", "suffit", "pour", "les", "des", "mets", "regle", "secondes", "heure", "heures")

    private val NUM = mapOf("a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "thirteen" to 13, "fourteen" to 14, "fifteen" to 15,
        "sixteen" to 16, "seventeen" to 17, "eighteen" to 18, "nineteen" to 19, "twenty" to 20, "thirty" to 30, "forty" to 40, "fifty" to 50,
        "sixty" to 60, "ninety" to 90, "un" to 1, "une" to 1, "deux" to 2, "trois" to 3, "quatre" to 4, "cinq" to 5, "sept" to 7, "huit" to 8,
        "neuf" to 9, "dix" to 10, "onze" to 11, "douze" to 12, "treize" to 13, "quatorze" to 14, "quinze" to 15, "seize" to 16, "vingt" to 20,
        "trente" to 30, "quarante" to 40, "cinquante" to 50, "soixante" to 60)
    private val TENS = setOf(20, 30, 40, 50, 60, 10)
    private val UNIT = mapOf("second" to 1, "seconds" to 1, "sec" to 1, "secs" to 1, "seconde" to 1, "secondes" to 1, "minute" to 60,
        "minutes" to 60, "min" to 60, "mins" to 60, "mn" to 60, "hour" to 3600, "hours" to 3600, "hr" to 3600, "hrs" to 3600, "heure" to 3600,
        "heures" to 3600, "h" to 3600)

    /** A number at [i] ("9", "nine", "twenty five", « vingt et un », « dix sept »), and the index after it. */
    private fun number(w: List<String>, i: Int): Pair<Int, Int>? {
        val a = w.getOrNull(i) ?: return null
        val n = a.toIntOrNull()?.takeIf { it in 0..999 } ?: NUM[a] ?: return null
        if (n in TENS) {
            val j = if (w.getOrNull(i + 1) == "et" || w.getOrNull(i + 1) == "and") i + 2 else i + 1
            val u = NUM[w.getOrNull(j)]
            if (u != null && u in 1..9 && w.getOrNull(j + 1) in UNIT) return n + u to j + 1
        }
        return n to i + 1
    }

    /** A length at [i] ("9 minutes", "an hour and a half", "1 h 30", « une demi-heure »), in seconds, and the index after it. */
    fun duration(w: List<String>, i: Int): Pair<Long, Int>? {
        if (w.getOrNull(i) == "half" && w.getOrNull(i + 1) in setOf("an", "a") && w.getOrNull(i + 2) == "hour") return 1800L to i + 3
        val demi = if (w.getOrNull(i) in setOf("une", "un")) i + 1 else i
        if (w.getOrNull(demi) == "demi" && w.getOrNull(demi + 1) == "heure") return 1800L to demi + 2
        var total = 0L
        var at = i
        var last = 0
        while (true) {
            val (n, j) = number(w, at) ?: break
            val unit = UNIT[w.getOrNull(j)] ?: run {
                if (last == 3600 && w.getOrNull(at)?.toIntOrNull() != null) { total += n * 60L; at = j }
                null
            } ?: break
            total += n.toLong() * unit
            last = unit
            at = j + 1
            if (w.getOrNull(at) in setOf("and", "et") && w.getOrNull(at + 1) in setOf("a", "une", "demie", "demi") && w.getOrNull(at + 2) in setOf("half", "demie", "demi", null)) {
                total += unit / 2
                at += if (w.getOrNull(at + 1) in setOf("demie", "demi")) 2 else 3
                break
            }
            if (w.getOrNull(at) in setOf("and", "et") && number(w, at + 1)?.let { (_, k) -> w.getOrNull(k) in UNIT } == true) at += 1
        }
        return if (total > 0 && at > i) total to at else null
    }

    private val LEAD = setOf("set", "start", "make", "put", "create", "give", "me", "please", "robot", "hey", "can", "could", "would", "you",
        "mets", "met", "regle", "lance", "part", "demarre", "programme", "fais", "moi", "peux", "pourrais", "tu", "s", "il", "te", "plait", "new", "nouvelle")
    private val CONNECT = setOf("a", "an", "the", "my", "for", "called", "named", "of", "on", "in", "une", "un", "la", "le", "les", "l", "de",
        "des", "du", "d", "pour", "appelee", "appele", "nommee", "nomme", "dans", "ma", "mon", "to")
    private val DENY = setOf("and", "then", "et", "puis", "remind", "reminder", "rappel", "rappelle", "alarm", "alarme", "wake", "reveille", "cancel",
        "annule", "how", "combien", "left", "reste", "every", "chaque", "tomorrow", "demain", "at", "text", "call", "message", "if", "when", "quand", "si")

    private fun set(s: Said): TimerCommand.Set? {
        val w = s.w
        val nouns = w.indices.filter { w[it] in NOUNS_EN || w[it] in NOUNS_FR }
        if (nouns.size != 1) return null
        val starts = w.indices.mapNotNull { i -> duration(w, i)?.let { i to it } }
        if (starts.isEmpty()) return null
        val (d0, d) = starts.first()
        val (seconds, d1) = d
        if (starts.any { it.first >= d1 }) return null
        val used = (d0 until d1).toSet() + nouns
        var lead = 0
        while (lead < w.size && lead !in used && w[lead] in LEAD + CONNECT) lead++
        val rest = w.indices.filter { it >= lead && it !in used && w[it] !in CONNECT && !(w[it] in LEAD && it < nouns[0] && it < d0) }
        // "5 minute timer": the unit as an adjective is the length's.
        if (rest.any { w[it] in DENY || w[it] in UNIT }) return null
        if (rest.size > 4 || (rest.isNotEmpty() && rest.last() - rest.first() + 1 != rest.size)) return null
        val french = w[nouns[0]] in NOUNS_FR
        return TimerCommand.Set(rest.joinToString(" ") { s.shown[it] }, seconds, french)
    }

    private val LEFT_LEADS = listOf(listOf("how", "long"), listOf("how", "much", "time"), listOf("how", "much", "longer"), listOf("how", "many", "minutes"),
        listOf("time", "left"), listOf("il", "reste", "combien"), listOf("combien", "de", "temps"), listOf("combien", "reste"), listOf("combien", "il", "reste"),
        listOf("combien", "de", "minutes"), listOf("reste", "combien"))
    private val LEFT_FILL = setOf("is", "are", "left", "on", "the", "my", "for", "until", "till", "there", "remaining", "still", "to", "go", "done", "ready",
        "be", "will", "it", "of", "before", "with", "s", "t", "il", "reste", "a", "au", "aux", "pour", "sur", "la", "le", "les", "l", "de", "des", "du",
        "d", "ma", "mon", "mes", "temps", "encore", "avant", "que", "qu", "soit", "soient", "pret", "prete", "prets", "pretes", "est", "sont", "y", "en",
        "ce", "minutes", "please", "robot", "time", "longer", "much", "long", "how", "combien", "my", "your", "what", "s")
    private val CANCEL_LEADS = listOf(listOf("cancel"), listOf("delete"), listOf("remove"), listOf("clear"), listOf("stop"), listOf("turn", "off"),
        listOf("switch", "off"), listOf("end"), listOf("annule"), listOf("annuler"), listOf("arrete"), listOf("arreter"), listOf("supprime"),
        listOf("enleve"), listOf("efface"), listOf("eteins"), listOf("stoppe"))
    private val CANCEL_FILL = setOf("the", "my", "a", "for", "on", "of", "it", "please", "robot", "le", "la", "les", "l", "de", "des", "du", "d", "ma", "mon",
        "mes", "pour", "sur", "ca", "off", "now", "maintenant", "tes", "your")
    private val ALL = setOf("all", "every", "toutes", "tous", "tout")

    private fun after(w: List<String>, leads: List<List<String>>): Int {
        var i = 0
        while (i < w.size && w[i] in setOf("hey", "robot", "please", "ok", "okay", "can", "could", "you", "tu", "peux")) i++
        return leads.firstOrNull { l -> w.drop(i).take(l.size) == l }?.let { i + it.size } ?: -1
    }

    private fun matches(name: String, names: List<String>): Boolean {
        val k = OwnTimers.key(name)
        return k.isNotEmpty() && names.any { n -> OwnTimers.key(n).let { it.isNotEmpty() && (it == k || it.split(' ').containsAll(k.split(' ')) || k.split(' ').containsAll(it.split(' '))) } }
    }

    private fun french(s: Said): Boolean = s.w.any { it in FR_WORDS }

    private fun left(s: Said, names: List<String>): TimerCommand.Left? {
        val a = after(s.w, LEFT_LEADS).takeIf { it >= 0 } ?: return null
        val noun = s.w.any { it in NOUNS_EN || it in NOUNS_FR }
        val rest = (a until s.w.size).filter { s.w[it] !in LEFT_FILL && s.w[it] !in NOUNS_EN && s.w[it] !in NOUNS_FR }
        val name = rest.joinToString(" ") { s.shown[it] }
        return when {
            name.isEmpty() -> if (noun || s.w.any { it == "left" || it == "reste" || it == "remaining" }) TimerCommand.Left("", french(s)) else null
            matches(name, names) -> TimerCommand.Left(name, french(s))
            else -> null
        }
    }

    private fun cancel(s: Said, names: List<String>): TimerCommand.Cancel? {
        val a = after(s.w, CANCEL_LEADS).takeIf { it >= 0 } ?: return null
        val noun = s.w.any { it in NOUNS_EN || it in NOUNS_FR }
        val rest = (a until s.w.size).filter { s.w[it] !in CANCEL_FILL && s.w[it] !in NOUNS_EN && s.w[it] !in NOUNS_FR }
        if (rest.isNotEmpty() && rest.all { s.w[it] in ALL }) return if (noun) TimerCommand.Cancel(TimerCommand.ALL, french(s)) else null
        val name = rest.joinToString(" ") { s.shown[it] }
        return when {
            name.isEmpty() -> if (noun) TimerCommand.Cancel("", french(s)) else null
            matches(name, names) || (noun && rest.size <= 4) -> TimerCommand.Cancel(name, french(s))
            else -> null
        }
    }

    private val STOP = setOf("stop", "stop it", "stop that", "stop the timer", "stop timer", "stop the alarm", "stop ringing", "stop the ringing",
        "dismiss", "dismiss it", "ok stop", "okay stop", "ok", "okay", "enough", "quiet", "silence", "shut up", "turn it off", "that s enough",
        "arrete", "arrete ca", "arrete la", "arrete de sonner", "arrete l alarme", "stop ca", "c est bon", "ca suffit", "tais toi", "ok arrete")
    private val STOP_FR = setOf("arrete", "ca", "c", "suffit", "tais", "sonner", "alarme")

    private fun stop(s: Said, ringing: List<String>): TimerCommand.Stop? {
        val w = s.w.dropWhile { it == "hey" || it == "robot" }.filter { it != "please" && it != "robot" }
        val fr = french(s) || w.any { it in STOP_FR }
        if (w.joinToString(" ") in STOP) return TimerCommand.Stop("", fr)
        val c = cancel(Said(s.shown, s.w), ringing) ?: return null
        return when {
            c.name == "" || c.name == TimerCommand.ALL -> TimerCommand.Stop("", fr)
            matches(c.name, ringing) -> TimerCommand.Stop(c.name, fr)
            else -> null
        }
    }

    /**
     * What [text] asks of Robot's own timers, or null. [names]: the timers Robot keeps (running or ringing; "" unnamed),
     * [ringing]: those ringing now.
     */
    fun route(text: String, names: List<String>, ringing: List<String>): TimerCommand? {
        val s = said(text)
        if (s.w.isEmpty() || s.w.size > 20) return null
        if (ringing.isNotEmpty()) stop(s, ringing)?.let { return it }
        set(s)?.let { return it }
        if (names.isEmpty()) return null
        return left(s, names) ?: cancel(s, names)
    }
}
