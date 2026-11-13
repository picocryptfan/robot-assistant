// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.messages

import app.heyrobot.core.HelperRouter
import java.util.Locale

/**
 * A text's draft reworded by voice while its card waits (idea 5, row 3; docs/plans/tidy-reword-plan.md): "warmer, and
 * say I'm bringing dessert". The on-phone model writes the new draft; [check] keeps its facts. Sending stays behind
 * the new card's own Send.
 */
object DraftReword {
    /** Style words: "warmer", "a bit shorter", "more formal", "make it friendlier", « plus chaleureux », « plus court ». */
    private val STYLE = Regex(
        "^(?:(?:can you |could you |please )?(?:make it|make that|make the text|keep it|rends le|rends-le|fais le|fais-le|rends ca|fais ca)\\s+)?" +
            "(?:a (?:bit|little) |a little bit |bit |much |way |un peu |beaucoup )?(?:more |less |plus |moins )?" +
            "(?:warmer|warm|friendlier|friendly|nicer|nice|kinder|kind|sweeter|softer|shorter|short|longer|politer|polite|formal|casual|" +
            "professional|funnier|funny|happier|gentler|simpler|clearer|" +
            "chaleureux|chaleureuse|gentil|gentille|court|courte|long|longue|poli|polie|formel|formelle|amical|amicale|decontracte|" +
            "simple|clair|claire|drole|doux|douce)\\b",
    )
    /** What to add: "say I'm bringing dessert", "add that…", « dis que… », « ajoute… ». */
    private val ADD = Regex("^(?:and |also |then |et |puis )?(?:also |aussi )?(?:say|add|mention|tell (?:him|her|them)|dis|ajoute|mentionne|precise|reword|rephrase|rewrite|reformule)\\b")

    /** [text], said or typed while a text's card waits, asks for a reword: the request as said; null if it doesn't. */
    fun request(text: String): String? {
        val t = text.trim().trimEnd('.', '!')
        val p = HelperRouter.plain(t).lowercase(Locale.ROOT).replace("'", " ").replace("’", " ").replace(Regex("\\s+"), " ")
        return t.takeIf { it.isNotEmpty() && (STYLE.containsMatchIn(p) || ADD.containsMatchIn(p)) }
    }

    /** The model's instructions (l10n: not text: a model prompt). */
    const val SYSTEM = "You rewrite a text message before it is sent. Do what the request asks and nothing else. " + // l10n: not text (model prompt)
        "Write in the draft's language, as the sender, to the person named. Keep every number, time, day and name the draft has, " + // l10n: not text (model prompt)
        "unless the request changes it. Add no fact the request does not give. Answer with the new message only: no quotes, no notes." // l10n: not text (model prompt)

    /** The model's input. */
    fun input(to: String, draft: String, request: String): String =
        "To: $to\nDraft: $draft\nRequest: $request" // l10n: not text (model prompt)

    /** The model's answer as a message: its first paragraph, without a lead-in or quotes; null if there's nothing. */
    fun clean(out: String?): String? {
        var t = out?.trim() ?: return null
        t = t.replace(Regex("^(?:here(?:'s| is)[^:\\n]*|voici[^:\\n]*|new message|message|draft|nouveau message)\\s*:\\s*", RegexOption.IGNORE_CASE), "")
        t = t.split(Regex("\\n\\s*\\n")).first().trim()
        for ((a, b) in listOf('"' to '"', '“' to '”', '«' to '»')) if (t.length >= 2 && t.first() == a && t.last() == b) t = t.substring(1, t.length - 1).trim()
        return t.takeIf { it.isNotEmpty() && it.length <= MAX_CHARS }
    }

    const val MAX_CHARS = 600

    /** A rewrite's facts: [ok] false when it dropped or invented a number, day or name your request didn't give. */
    data class Checked(val ok: Boolean, val marks: List<DraftMark>)

    /**
     * [after] keeps [before]'s facts. A number, day or name it drops is fine only when your [request] gives one of the
     * same kind (a change you asked for); one it adds must come from your request (or be [to], the person). What it
     * adds is marked, as the old one it replaced when there was exactly one.
     */
    fun check(before: String, after: String, request: String, to: String, names: Collection<String> = emptyList()): Checked {
        val all = names + to
        val b = DraftTidy.facts(before, all)
        val a = DraftTidy.facts(after, all)
        val r = DraftTidy.facts(request, all)
        val them = to.split(' ').map(DraftTidy::key).toSet()
        val marks = ArrayList<DraftMark>()
        for (kind in DraftTidy.Kind.entries) {
            val bb = b[kind].orEmpty()
            val aa = a[kind].orEmpty()
            val rr = r[kind].orEmpty()
            val added = aa - bb - them
            val dropped = bb - aa
            if ((added - rr).isNotEmpty()) return Checked(false, emptyList())
            if (dropped.isNotEmpty() && rr.isEmpty()) return Checked(false, emptyList())
            for (x in added) marks += DraftMark(shown(after, x), dropped.singleOrNull()?.takeIf { added.size == 1 }?.let { shown(before, it) })
        }
        return Checked(true, marks)
    }

    /** How [text] writes the fact [key] ("15", "fifteen", "Sarah"), for the card. */
    private fun shown(text: String, key: String): String =
        text.split(Regex("\\s+")).firstOrNull { w -> DraftTidy.facts(w)[DraftTidy.Kind.NUMBER]?.contains(key) == true || DraftTidy.key(w) == key }
            ?.trim('"', '“', '”', '«', '»', '(', ')', ',', '.', ';', ':', '!', '?') ?: key
}
