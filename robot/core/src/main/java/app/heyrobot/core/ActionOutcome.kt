// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TurnStrings
import java.text.Normalizer
import java.util.Locale

// Pure. 1.0.2: what Robot says about an action that didn't happen. On a 1.0.1 phone the model answered "I have saved that
// as a note" after you tapped Cancel, and "I drafted an email to Bob" when no contact matched: its follow-up after the
// tool result is free text, and a small model can claim what the card denies. So the model is told plainly that nothing
// was done (forModel), an action you asked for that didn't happen gets Robot's own line from its real status instead of
// a follow-up (TurnRunner), and a reply that still claims success when nothing was done loses that sentence (claims).

/**
 * One action's card as it ended, for the lines Robot writes itself. [asked]: you asked for that kind of action this turn.
 * [detail] and [spoken] are English (what the model reads); 1.1: [text] and [spokenText] are the same words without a
 * language yet, used when their English matches, so Robot's line is in the reply language.
 */
data class Outcome(val tool: ToolName?, val status: ActionStatus, val detail: String?, val asked: Boolean = false, val content: Boolean = false,
    val spoken: String? = null, val text: Phrase? = null, val spokenText: Phrase? = null) {
    /** It writes or leaves the phone (not a read tool), whatever happened to it; an unknown tool counts as one. */
    val acts: Boolean get() = tool?.reads != true

    /** An action that didn't happen and that the model can't put right by calling again (a bad argument it can). */
    val notDone: Boolean get() = acts && !status.succeeded && status != ActionStatus.PENDING && status != ActionStatus.INVALID
}

object ActionOutcome {
    // 1.1: Robot's own lines are TurnStrings' (English and French); these are their English, for the tests.
    /** The line after a card you cancelled. */
    val CANCELLED: String get() = TurnStrings.outcomeCancelled().en()
    /** The line when an action didn't happen and its card has no words of its own. */
    val NOT_DONE: String get() = TurnStrings.outcomeNotDone().en()

    /** The line when the model named no one to text, email or call (ToolCalls.NO_RECIPIENT). */
    val NO_ONE: String get() = TurnStrings.outcomeNoOne().en()

    /** What the model is told beside the status of an action that didn't happen (Gemma reads "the user"). */
    const val FOR_MODEL = "Not done: nothing was saved, sent, opened or changed. Tell the user it was not done; never say it was done."

    /**
     * The tool response with [FOR_MODEL] added when [o] is an action that didn't happen. Not for a call with bad arguments
     * ([Outcome.notDone]): the model may call again with them put right, and its reason says what was missing.
     */
    fun forModel(response: Map<String, Any?>, o: Outcome): Map<String, Any?> = if (o.notDone) response + ("outcome" to FOR_MODEL) else response

    /**
     * Robot's own words for the round when an action you asked for didn't happen ([Outcome.notDone] and [Outcome.asked]) and
     * nothing in it brought content to answer from: the model's follow-up isn't asked for. Not for a helper (TurnRunner).
     */
    fun settles(round: List<Outcome>): Boolean = round.any { it.notDone && it.asked } && round.none { it.content }

    /** 1.1: the actions whose confirmation Robot writes itself from the card, never the model (docs/pm/handoff-dated-reminders.md point 1). */
    val CONFIRMED: Set<ToolName> = setOf(ToolName.SET_TIMER, ToolName.SET_ALARM, ToolName.ADD_EVENT, ToolName.ADD_NOTE)

    /**
     * 1.1: every action of [round] is a timer, an alarm, an event or a note that was done, and nothing brought content: Robot's
     * own line from each card is the reply ("Alarm set for 18:00."), and the model writes no follow-up that could name another
     * day or time.
     */
    fun confirms(round: List<Outcome>): Boolean =
        round.isNotEmpty() && round.none { it.content } && round.all { it.tool in CONFIRMED && it.status == ActionStatus.DONE }

    /** One line per card, from its real status, without repeats; [say] puts words in a language (1.1; English by default). */
    fun lines(round: List<Outcome>, say: (Phrase) -> String = Phrase::en): List<String> = round.map { line(it, say) }.distinct()

    fun line(o: Outcome, say: (Phrase) -> String = Phrase::en): String {
        fun words(en: String?, p: Phrase?) = (p?.takeIf { it.en() == en }?.let(say) ?: en)?.trim()?.takeIf { it.isNotEmpty() }?.let(::sentence)
        val d = words(o.detail, o.text)
        return when {
            o.status.succeeded -> words(o.spoken, o.spokenText) ?: d ?: say(TurnStrings.outcomeDone())
            o.status == ActionStatus.DECLINED -> d ?: say(TurnStrings.outcomeCancelled())
            o.status == ActionStatus.INVALID -> say(if (o.detail == ToolCalls.NO_RECIPIENT) TurnStrings.outcomeNoOne() else TurnStrings.outcomeNotDone())
            else -> d ?: say(TurnStrings.outcomeNotDone())
        }
    }

    private fun sentence(s: String) = if (s.last() in ".!?…”»)") s else "$s."

    // ------------------------------------------------------------------ a claim the card denies

    /** Words that say an action happened (English and French, accents stripped), in a past or done form. */
    private const val DONE_VERBS = "saved|added|created|drafted|sent|scheduled|opened|noted|written|wrote|stored|booked|started|deleted|removed|" + // l10n: not text (the words a claim is matched on)
        "emailed|texted|messaged|called|jotted|remembered|composed|prepared|set|made|put|turned|launched|placed|" +
        "enregistree?s?|ajoutee?s?|creee?s?|redigee?s?|envoyee?s?|notee?s?|ouverte?s?|programmee?s?|reglee?s?|preparee?s?|sauvegardee?s?|ecrite?s?|mise?s?|lancee?s?|" +
        // 1.1: the French for the rest of the English (started, deleted, removed, called, texted, composed, booked, remembered,
        // placed, turned on or off), as Robot's French replies say them in the tu form.
        "demarree?s?|supprimee?s?|effacee?s?|retiree?s?|appelee?s?|telephone|textee?s?|composee?s?|planifiee?s?|reservee?s?|memorisee?s?|retenue?s?|" +
        "placee?s?|allumee?s?|eteinte?s?|activee?s?|desactivee?s?"

    /** 1.1: the infinitives after "je viens de" (French for "I just …"). */
    private const val DONE_INFINITIVES = "enregistrer|ajouter|creer|rediger|envoyer|noter|ouvrir|programmer|regler|preparer|sauvegarder|ecrire|mettre|" +
        "lancer|demarrer|supprimer|effacer|retirer|appeler|telephoner|texter|composer|planifier|reserver|memoriser|placer|allumer|eteindre|activer|desactiver"

    /** The narrower ones for "it's …" ("it's set to approximate" describes a setting, "it's saved" claims a note). */
    private const val KEPT_VERBS = "saved|added|created|drafted|scheduled|noted|stored|booked|enregistree?|ajoutee?|creee?|notee?|sauvegardee?" // l10n: not text (the words a claim is matched on)

    /**
     * A claim, never a description of what Robot read: "I" or "we" did it ("I have saved", "I've gone ahead and drafted",
     * "j'ai bien enregistré", 1.1: "je viens de t'envoyer"), "c'est noté", or one of the actions' own things is done ("your
     * note is saved", "the timer is set"); "Wi-Fi is turned on" or "your order was sent" are not.
     */
    private val CLAIM = Regex(
        """\b(?:i|i've|ive|we|we've|weve|j'ai|jai|je|nous)(?:\s+(?:have|has|had|just|now|also|already|successfully|gone|ahead|and|went|been|then|""" +
            """ai|l'ai|l'avons|avons|bien|deja|aussi|maintenant|donc|vous|te|t'ai|vous l'ai|te l'ai|me|lui|leur|lui l'ai)){0,4}\s+(?:$DONE_VERBS)\b|""" +
            """\b(?:je|nous)\s+(?:viens|venons)\s+(?:de\s+|d')(?:(?:te|vous|le|la|les|lui|leur)\s+|t'|l')*(?:$DONE_INFINITIVES)\b|""" +
            """\bc'est\s+(?:bien\s+)?(?:note|fait|enregistre|sauvegarde|ajoute|cree|programme|regle|envoye|redige)e?s?\b|""" +
            """\b(?:it's|it is|it has been|that's|that is|that has been)\s+(?:now\s+|all\s+|been\s+)?(?:$KEPT_VERBS)\b|""" +
            """\b(?:note|notes|email|e mail|draft|message|text|event|alarm|timer|reminder|list|appointment|courriel|brouillon|texto|evenement|""" +
            """alarme|minuterie|rappel|liste|rendez-vous)\s+(?:is|are|was|were|has been|have been|est|a ete|sont|ont ete)\s+(?:now\s+|all\s+|bien\s+)?(?:$DONE_VERBS)\b""")

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
