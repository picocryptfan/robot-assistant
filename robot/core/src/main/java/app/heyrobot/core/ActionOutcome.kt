// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.text.Normalizer
import java.util.Locale

// Pure. 1.0.2: what Robot says about an action that didn't happen. On a 1.0.1 phone the model answered "I have saved that
// as a note" after you tapped Cancel, and "I drafted an email to Bob" when no contact matched: its follow-up after the
// tool result is free text, and a small model can claim what the card denies. So the model is told plainly that nothing
// was done (forModel), an action you asked for that didn't happen gets Robot's own line from its real status instead of
// a follow-up (TurnRunner), and a reply that still claims success when nothing was done loses that sentence (claims).

/** One action's card as it ended, for the lines Robot writes itself. [asked]: you asked for that kind of action this turn. */
data class Outcome(val tool: ToolName?, val status: ActionStatus, val detail: String?, val asked: Boolean = false, val content: Boolean = false,
    val spoken: String? = null) {
    /** It writes or leaves the phone (not a read tool), whatever happened to it; an unknown tool counts as one. */
    val acts: Boolean get() = tool?.reads != true

    /** An action that didn't happen and that the model can't put right by calling again (a bad argument it can). */
    val notDone: Boolean get() = acts && !status.succeeded && status != ActionStatus.PENDING && status != ActionStatus.INVALID
}

object ActionOutcome {
    /** The line after a card you cancelled. */
    const val CANCELLED = "You cancelled it, so nothing was done."
    /** The line when an action didn't happen and its card has no words of its own. */
    const val NOT_DONE = "Nothing was done."

    /** The line when the model named no one to text, email or call (ToolCalls.NO_RECIPIENT). */
    const val NO_ONE = "Nothing was done: say who it's for."

    /** What the model is told beside the status of an action that didn't happen (Gemma reads "the user"). */
    const val FOR_MODEL = "Not done: nothing was saved, sent, opened or changed. Tell the user it was not done; never say it was done."

    /** The tool response with [FOR_MODEL] added when [o] is an action that didn't happen (its bad arguments included). */
    fun forModel(response: Map<String, Any?>, o: Outcome): Map<String, Any?> =
        if (o.acts && !o.status.succeeded && o.status != ActionStatus.PENDING) response + ("outcome" to FOR_MODEL) else response

    /**
     * Robot's own words for the round when an action you asked for didn't happen ([Outcome.notDone] and [Outcome.asked]) and
     * nothing in it brought content to answer from: the model's follow-up isn't asked for. Not for a helper (TurnRunner).
     */
    fun settles(round: List<Outcome>): Boolean = round.any { it.notDone && it.asked } && round.none { it.content }

    /** One line per card, from its real status, without repeats. */
    fun lines(round: List<Outcome>): List<String> = round.map(::line).distinct()

    fun line(o: Outcome): String {
        val d = o.detail?.trim()?.takeIf { it.isNotEmpty() }?.let(::sentence)
        return when {
            o.status.succeeded -> o.spoken?.trim()?.takeIf { it.isNotEmpty() }?.let(::sentence) ?: d ?: "Done."
            o.status == ActionStatus.DECLINED -> d ?: CANCELLED
            o.status == ActionStatus.INVALID -> if (o.detail == ToolCalls.NO_RECIPIENT) NO_ONE else NOT_DONE
            else -> d ?: NOT_DONE
        }
    }

    private fun sentence(s: String) = if (s.last() in ".!?…”»)") s else "$s."

    // ------------------------------------------------------------------ a claim the card denies

    /** Words that say an action happened (English and French, accents stripped), in a past or done form. */
    private const val DONE_VERBS = "saved|added|created|drafted|sent|scheduled|opened|noted|written|wrote|stored|booked|started|deleted|removed|" +
        "emailed|texted|messaged|called|jotted|remembered|composed|prepared|set|made|put|turned|launched|placed|" +
        "enregistree?s?|ajoutee?s?|creee?s?|redigee?s?|envoyee?s?|notee?s?|ouverte?s?|programmee?s?|reglee?s?|preparee?s?|sauvegardee?s?|ecrite?s?|mise?s?|lancee?s?"

    /** Who did it, or a form of "to be" or "to have" before the verb, a few words before it at most ("I have now saved"). */
    private val CLAIM = Regex(
        """\b(?:i|ive|i've|we|we've|weve|it's|its|it|has|have|had|was|were|is|are|been|j'ai|jai|c'est|cest|est|sont|ete|a)\b(?:\s+[\p{L}']+){0,3}?\s+(?:$DONE_VERBS)\b""")

    /** A sentence that says it wasn't done (or asks) is never a claim. */
    private val NEGATED = Regex("""\b(?:not|no|never|nothing|n't|cannot|couldn't|didn't|wasn't|haven't|hasn't|won't|unable|cancel\w*|would|should|could|shall|want|ne|n'|pas|rien|aucun\w*|annule\w*|veux|voulez)\b|n't\b|\?""")

    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
        .replace('’', '\'')

    /** One sentence says an action was done ("I have saved that as a note", "I drafted an email to Bob"). */
    fun claimsSentence(s: String): Boolean {
        val p = plain(s)
        return CLAIM.containsMatchIn(p) && !NEGATED.containsMatchIn(p)
    }

    /** [text] says somewhere that an action was done. */
    fun claims(text: String): Boolean = sentences(text).any(::claimsSentence)

    /** [text] without the sentences that say an action was done; the rest as it was. */
    fun withoutClaims(text: String): String =
        text.split("\n").joinToString("\n") { line -> sentences(line).filterNot(::claimsSentence).joinToString(" ") }
            .replace(Regex("\n{3,}"), "\n\n").trim()

    private fun sentences(text: String): List<String> = text.split(Regex("""(?<=[.!?…])\s+|\n+""")).filter { it.isNotBlank() }
}
