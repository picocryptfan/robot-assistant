// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

// Pure. 1.3, the phrases that start a hold (docs/plans/notify-hold-plan.md), ahead of the model (MessagesRouter): on text
// folded as the router folds it (lower case, no accents, no wake word, no final punctuation). Only notifications or apps
// held "till"/"for" a time, or you "off your phone" until then: "hold on", "hold the door", "put a hold on my library
// book", "hold my calls" and « retiens … » (Remember's) stay with the model.

object HoldPhrase {
    private const val NUM_EN = "one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve"
    private const val NUM_FR = "une|un|deux|trois|quatre|cinq|six|sept|huit|neuf|dix|onze|douze"
    private val WORDS_EN = NUM_EN.split('|')
    private val WORDS_FR = listOf("une", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze")

    private val HOLD_EN = Regex("^(?:hold|pause|snooze)(?: all)?(?: of)? (?:my |the )?(?:(.+?) )?(?:apps|app|notifications|notification) (till|until|til|'til|for) (.+)$")
    private val OFF_EN = Regex("^(?:i'm|im|i am|i'll be|ill be|i will be) (?:off|away from|staying off|taking a break from|off of) (?:my |the )?phone (till|until|til|'til|for) (.+)$")

    private val HOLD_FR = listOf(
        Regex("^(?:suspends|mets en attente|mets en pause|mets sur pause) (?:mes |les |toutes mes )?(?:notifications|notifs|applis|applications)(?: (?:de |d')(.+?))? (jusqu'a|pendant|pour) (.+)$"),
        Regex("^mets (?:mes |les |toutes mes )?(?:notifications|notifs|applis|applications)(?: (?:de |d')(.+?))? (?:en attente|en pause|sur pause) (jusqu'a|pendant|pour) (.+)$"),
    )
    private val OFF_FR = Regex("^(?:je suis|je serai|je reste|je vais etre|je lache|je laisse) (?:loin de |sans |hors de |pas sur )?(?:mon |le )?(?:telephone|cell|cellulaire) (jusqu'a|pendant|pour) (.+)$")

    fun english(f: String): HoldAsk? {
        HOLD_EN.matchEntire(f)?.let { m -> return ask(names(m.groupValues[1], Regex(",? and |, ")), m.groupValues[2] == "for", m.groupValues[3], ::clockEn, ::forEn) }
        OFF_EN.matchEntire(f)?.let { m -> return ask(emptyList(), m.groupValues[1] == "for", m.groupValues[2], ::clockEn, ::forEn) }
        return null
    }

    fun french(f: String): HoldAsk? {
        for (r in HOLD_FR) r.matchEntire(f)?.let { m -> return ask(names(m.groupValues[1], Regex(",? et (?:de |d')?|, (?:de |d')?")), m.groupValues[2] != "jusqu'a", m.groupValues[3], ::clockFr, ::forFr) } // l10n: not text (heard words)
        OFF_FR.matchEntire(f)?.let { m -> return ask(emptyList(), m.groupValues[1] != "jusqu'a", m.groupValues[2], ::clockFr, ::forFr) } // l10n: not text (heard words)
        return null
    }

    private fun ask(apps: List<String>?, isFor: Boolean, tail: String, clock: (String) -> HoldUntil?, dur: (String) -> HoldUntil?): HoldAsk? {
        val a = apps ?: return null
        val u = (if (isFor) dur(tail.trim()) else clock(tail.trim())) ?: return null
        return HoldAsk(a, u)
    }

    /** "shopping and news" to its names; "" (just "my apps"): every picked app. null: a word that names no app ("all of"). */
    private fun names(said: String, sep: Regex): List<String>? {
        val s = said.trim().removePrefix("my ").removePrefix("the ").trim()
        if (s.isEmpty() || s == "all" || s == "all my" || s == "toutes") return emptyList() // l10n: not text (heard words)
        val parts = s.split(sep).map { it.trim().removePrefix("my ").removePrefix("the ").trim() }.filter { it.isNotEmpty() }
        return parts.takeIf { it.isNotEmpty() && it.all { p -> p.length <= 30 } }
    }

    private fun numEn(t: String): Int? = t.toIntOrNull() ?: WORDS_EN.indexOf(t).takeIf { it >= 0 }?.plus(1)
    private fun numFr(t: String): Int? = t.toIntOrNull() ?: (if (t == "un") 1 else WORDS_FR.indexOf(t).takeIf { it >= 0 }?.plus(1))

    private val DIGITS_EN = Regex("^(\\d{1,2})(?:(?::| )(\\d{2}))?(?: ?o'?clock)?(?: ?(am|pm|a\\.m\\.?|p\\.m\\.?|a m|p m))?$")
    private val WORD_EN = Regex("^($NUM_EN)(?: (o'clock|oclock|fifteen|thirty|forty five|forty-five|oh five))?(?: (am|pm|in the morning|in the afternoon|in the evening|this afternoon|this evening|tonight))?$")

    fun clockEn(t: String): HoldUntil? {
        if (t == "noon" || t == "midday") return HoldUntil.At(12, 0)
        if (t == "midnight") return HoldUntil.At(0, 0)
        DIGITS_EN.matchEntire(t)?.let { m ->
            val pm = m.groupValues[3].takeIf { it.isNotEmpty() }?.startsWith("p")
            return at(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, pm)
        }
        WORD_EN.matchEntire(t)?.let { m ->
            val min = when (m.groupValues[2]) { "fifteen" -> 15; "thirty" -> 30; "forty five", "forty-five" -> 45; "oh five" -> 5; else -> 0 } // l10n: not text (heard words)
            val pm = when (m.groupValues[3]) { "" -> null; "am", "in the morning" -> false; else -> true } // l10n: not text (heard words)
            return at(numEn(m.groupValues[1])!!, min, pm)
        }
        return null
    }

    private val FOR_EN = Regex("^(an|a|\\d+|$NUM_EN) (hour|hours|minute|minutes|min|mins)$")

    fun forEn(t: String): HoldUntil? {
        if (t == "half an hour" || t == "a half hour") return HoldUntil.For(30) // l10n: not text (heard words)
        if (t == "an hour and a half" || t == "one and a half hours") return HoldUntil.For(90) // l10n: not text (heard words)
        val m = FOR_EN.matchEntire(t) ?: return null
        val n = if (m.groupValues[1] == "an" || m.groupValues[1] == "a") 1 else numEn(m.groupValues[1]) ?: return null
        return minutes(n, m.groupValues[2].startsWith("h"))
    }

    private val DIGITS_FR = Regex("^(\\d{1,2}) ?(?:h|heures?|:) ?(\\d{2})?$")
    private val WORD_FR = Regex("^($NUM_FR) heures?(?: (et quart|et demie|quinze|trente|quarante-cinq|quarante cinq))?(?: (du matin|de l'apres-midi|de l'apres midi|cet apres-midi|du soir|ce soir))?$")

    fun clockFr(t: String): HoldUntil? {
        val s = t.removePrefix("a ").trim()
        if (s == "midi") return HoldUntil.At(12, 0)
        if (s == "minuit") return HoldUntil.At(0, 0)
        DIGITS_FR.matchEntire(s)?.let { m -> return at(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull() ?: 0, null) }
        WORD_FR.matchEntire(s)?.let { m ->
            val min = when (m.groupValues[2]) { "et quart", "quinze" -> 15; "et demie", "trente" -> 30; "quarante-cinq", "quarante cinq" -> 45; else -> 0 } // l10n: not text (heard words)
            val pm = when (m.groupValues[3]) { "" -> null; "du matin" -> false; else -> true } // l10n: not text (heard words)
            return at(numFr(m.groupValues[1])!!, min, pm)
        }
        return null
    }

    private val FOR_FR = Regex("^(\\d+|$NUM_FR) (heure|heures|minute|minutes|min)$")

    fun forFr(t: String): HoldUntil? {
        if (t == "une demi-heure" || t == "une demi heure") return HoldUntil.For(30) // l10n: not text (heard words)
        if (t == "une heure et demie") return HoldUntil.For(90) // l10n: not text (heard words)
        val m = FOR_FR.matchEntire(t) ?: return null
        val n = numFr(m.groupValues[1]) ?: return null
        return minutes(n, m.groupValues[2].startsWith("h"))
    }

    private fun minutes(n: Int, hours: Boolean): HoldUntil? = (if (hours) n * 60 else n).takeIf { it in 1..24 * 60 }?.let { HoldUntil.For(it) }

    /** A clock time: 0 to 23 h (13 and up: no am or pm), 0 to 59 min. */
    private fun at(h: Int, m: Int, pm: Boolean?): HoldUntil? = when {
        h !in 0..23 || m !in 0..59 -> null
        h > 12 && pm != null -> null
        h == 0 -> HoldUntil.At(0, m)
        else -> HoldUntil.At(h, m, if (h > 12) null else pm)
    }
}
