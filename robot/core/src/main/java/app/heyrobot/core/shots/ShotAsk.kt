// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.shots

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.files.FilePrompt
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ShotStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// Pure. Asking about your screenshots (screenshots-plan.md, "What's new" 4; Privacy): the router's line (a question that
// names a screenshot, in English or French, not a request to take one), the one-shot that answers from the entries
// found (as text: secrets are tokens already, and the model is told never to guess them; built by core files FilePrompt,
// the notes after FileQuote's frame and in its quotes, since a screenshot holds anyone's words), and what History and the bug
// card keep: the placeholder only (question 4's default), and the turn marked as content Robot didn't write.

object ShotAsk {
    private val NAMES = Regex("""(?i)\b(screen ?shots?|screen captures?|captures? d'écran|captures? d’écran|copies? d'écran|copies? d’écran)\b""") // l10n: not text (a pattern)
    private val TAKE = Regex("""(?i)\b(take|grab|make|capture|prends|prendre|fais|faire)\b[^?]{0,12}\b(a |an |une )?(screen ?shot|capture d['’]écran)""") // l10n: not text (a pattern)

    /** Whether [question] asks about your screenshots (and isn't "take a screenshot"). */
    fun asks(question: String): Boolean = NAMES.containsMatchIn(question) && !TAKE.containsMatchIn(question)

    private val DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ROOT)

    /** The one-shot answering [question] from [hits] (FilePrompt.shots); null when nothing is left to ask or quote. */
    fun request(question: String, hits: List<ShotEntry>, zone: ZoneId, spoken: Boolean): BrainMsg.OneShot? =
        FilePrompt.shots(question, hits.map { "Screenshot from ${DAY.format(Instant.ofEpochMilli(it.at).atZone(zone))}: ${it.text}" }, spoken)?.request // l10n: not text (the model reads it)

    /** Nothing matched: part of the index won't open, or how many screenshots weren't read, or nothing found. */
    fun none(entries: Collection<ShotEntry>, unreadable: Int): Phrase {
        val skipped = entries.count { it.skipped }
        return when {
            unreadable > 0 -> ShotStrings.unreadable()
            skipped > 0 -> ShotStrings.skipped(skipped)
            else -> ShotStrings.nothing()
        }
    }

    /** The conversation as the index's gate reads it (fix-train-14, phone-train-14 screenshots 2): the index's own answers,
     *  kept only as their placeholder, are left out, so a second question about your screenshots still reaches the index
     *  instead of the model; anything else Robot didn't write still keeps it out (TurnRules.untrusted). */
    fun untrusted(turns: List<ChatTurn>): Boolean = app.heyrobot.core.TurnRules.untrusted(turns.filterNot(::own))

    private val placeholders by lazy {
        listOf(app.heyrobot.core.i18n.Lang.EN, app.heyrobot.core.i18n.Lang.FR).map { ShotStrings.kept().render(Langs(it, it, it)) }.toSet()
    }

    /** [t] is an answer from the index as [forHistory] keeps it, and nothing else. */
    private fun own(t: ChatTurn): Boolean = !t.locked && t.role == app.heyrobot.core.TurnRole.ASSISTANT && t.messages && t.kept != null && t.text == t.kept && t.kept in placeholders &&
        t.attachment == app.heyrobot.core.Attachment.NONE && t.actions.isEmpty() && !t.fromHelper && !t.readBack && !t.content

    /** The answer as History, the bug card and the live chat keep it: the placeholder only, marked as content. */
    fun forHistory(t: ChatTurn, langs: Langs): ChatTurn {
        val kept = ShotStrings.kept().render(langs)
        return t.copy(text = kept, messages = true, kept = kept)
    }
}
