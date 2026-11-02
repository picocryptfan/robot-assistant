// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import app.heyrobot.core.i18n.Lang
import java.text.Normalizer
import java.util.Locale

// Pure. The everyday set's questions about what Robot sent online (docs/plans/everyday-set-plan.md §5.6): "what went
// online today?", "did you go online?", "what did you send online?", « qu'est-ce qui est allé en ligne aujourd'hui »,
// « es-tu allé en ligne ». Only a question about what Robot did, in the past tense, and only when it is the whole of what
// was said (a few polite words around it allowed): "go online and check the weather", "search online for …", "is the
// internet down", "is Sam online" and "go offline" are never taken. The panel asks it after SumsRouter and before
// hands-free (§2.1, D1); the answer is built by code from the record (OnlineSummary), and only unlocked.

/** What [OnlineRouter] heard. */
sealed interface OnlineCommand {
    /** The language the question was heard in, for the spoken summary. */
    val lang: Lang
}

/** Which days a question asks about. */
enum class OnlineDay { TODAY, YESTERDAY, WEEK }

/** "What went online today?": [day]'s summary, in [lang]. */
data class OnlineAsk(override val lang: Lang, val day: OnlineDay) : OnlineCommand

object OnlineRouter {
    /** "Était" → "etait", "qu'est-ce" → "quest ce": no accents, apostrophes, case or punctuation. */
    fun plain(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "") // l10n: not text (words Robot hears)
        .lowercase(Locale.ROOT).replace("'", "").replace("’", "").replace(Regex("[^a-z0-9]+"), " ").trim() // l10n: not text (words Robot hears)

    /** Polite words before the question, any number of them. */
    private val LEAD = Regex("^(?:(?:hey|hi|ok|okay|dis|he) robot|robot|please|so|and|um|uh|tell me|can you tell me|could you tell me|show me|" + // l10n: not text (words Robot hears)
        "dis moi|dis donc|montre moi|peux tu me dire|pourrais tu me dire|est ce que tu peux me dire|alors|bon|coudonc|eille|heille|sil te plait|sil vous plait) ") // l10n: not text (words Robot hears)

    /** And after it. */
    private val TAIL = Regex(" (?:please|robot|thanks|thank you|sil te plait|sil vous plait|svp|stp|merci|la)$") // l10n: not text (words Robot hears)

    private const val WHO = "(?:you|robot)" // l10n: not text (words Robot hears)
    private val EN = listOf(
        "what (?:went|has gone|have gone|got sent) online", // l10n: not text (words Robot hears)
        "what (?:did|have) $WHO (?:send|sent) online", // l10n: not text (words Robot hears)
        "(?:did|have) $WHO (?:go|gone|been|went) online", // l10n: not text (words Robot hears)
        "what (?:connections|servers) (?:did|have) $WHO (?:make|made|connect to|connected to|use|used)", // l10n: not text (words Robot hears)
        "what (?:did|have) $WHO (?:connect|connected) to", // l10n: not text (words Robot hears)
        "where (?:did|have) $WHO (?:go|gone|been) online", // l10n: not text (words Robot hears)
    ).map { Regex("^$it(?: (today|yesterday|this week|in the last week|lately|recently))?$") } // l10n: not text (words Robot hears)

    private const val TU = "(?:tu|robot)" // l10n: not text (words Robot hears)
    private const val SUR = "(?:en ligne|sur internet|sur le web)" // l10n: not text (words Robot hears)
    private const val AS = "(?:as tu|tas tu|est ce que tu as|est ce que tas)" // l10n: not text (words Robot hears)
    private val FR = listOf(
        "(?:quest )?ce qui (?:est alle|est allee|est parti|a ete envoye) en ligne", // l10n: not text (words Robot hears)
        "cest quoi qui (?:est alle|est parti|a ete envoye) en ligne", // l10n: not text (words Robot hears)
        "(?:quest ce que|cest quoi que) tas envoye en ligne", // l10n: not text (words Robot hears)
        "$AS envoye (?:quelque chose|de quoi|des affaires) en ligne", // l10n: not text (words Robot hears)
        "$AS ete $SUR", // l10n: not text (words Robot hears)
        "$AS utilise internet", // l10n: not text (words Robot hears)
        "a quels serveurs (?:tes tu|est ce que tu tes|tu tes) connecte", // l10n: not text (words Robot hears)
        "quest ce que $TU (?:as|a) envoye en ligne", // l10n: not text (words Robot hears)
        "quas tu envoye en ligne", // l10n: not text (words Robot hears)
        "(?:es tu|tes tu|est ce que tu es|est ce que tes|robot est il|est ce que robot est) (?:alle|allee) $SUR", // l10n: not text (words Robot hears)
        "(?:tes tu|est ce que tu tes) connecte", // l10n: not text (words Robot hears)
        "a quoi (?:tes tu|est ce que tu tes|tu tes) connecte", // l10n: not text (words Robot hears)
    ).map { Regex("^$it(?: (aujourdhui|a matin|ce matin|a soir|ce soir|hier|hier soir|cette semaine|la semaine derniere|la semaine passee|ces derniers jours|dernierement|recemment))?$") } // l10n: not text (words Robot hears)

    fun route(text: String): OnlineCommand? {
        var t = plain(text)
        while (true) {
            val m = LEAD.find(t) ?: break
            t = t.substring(m.range.last + 1)
        }
        while (true) {
            val m = TAIL.find(t) ?: break
            t = t.substring(0, m.range.first)
        }
        if (t.isEmpty()) return null
        for (r in EN) r.matchEntire(t)?.let { return OnlineAsk(Lang.EN, day(it.groupValues[1])) }
        for (r in FR) r.matchEntire(t)?.let { return OnlineAsk(Lang.FR, day(it.groupValues[1])) }
        return null
    }

    private fun day(w: String): OnlineDay = when (w) {
        "yesterday", "hier", "hier soir" -> OnlineDay.YESTERDAY // l10n: not text (words Robot hears)
        "this week", "in the last week", "lately", "recently", "cette semaine", "la semaine derniere", "la semaine passee", "ces derniers jours", "dernierement", "recemment" -> OnlineDay.WEEK // l10n: not text (words Robot hears)
        else -> OnlineDay.TODAY
    }
}
