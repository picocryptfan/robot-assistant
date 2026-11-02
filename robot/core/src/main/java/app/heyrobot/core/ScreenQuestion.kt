// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import java.text.Normalizer

/**
 * "What's on my screen?" and its plain variants, as the whole of what you said: the screen question, never an action.
 * The panel attaches the screen (or says why it can't) and the model answers without tools (31 Oct: on a phone, the model
 * read the Home tab's title and called open_app). Longer sentences aren't this: "open Signal and read my screen" goes on
 * as usual, and so does "ask Jarvis about my screen", which a helper gets only after a tap.
 */
object ScreenQuestion {
    private const val OPENING = "(?:(?:hey |ok |okay )?robot |please |can you |could you |would you |tell me |show me |peux tu |pourrais tu |dis moi |montre moi |s il te plait )*"
    private const val CLOSING = "(?: to me| for me| right now| now| please| maintenant| s il te plait)*"
    private const val MY = "(?:my|the|this)"
    private const val MON = "(?:mon|l|cet)"
    private val ASKS = listOf(
        "(?:what s|whats|what is|what do you see|what can you see|what have i got|what do i have) on (?:$MY )?screen",
        "what (?:s|is) (?:this|that) screen(?: about)?",
        "what does $MY screen say", // l10n: not text (words Robot hears)
        "(?:read|describe|summarize|summarise|explain|look at|see) $MY screen",
        "what am i looking at",
        "(?:qu est ce qu il y a|qu y a t il|ce qu il y a|c est quoi|qu est ce qui est(?: affiche)?|ce qui est(?: affiche)?|que vois tu|qu est ce que tu vois|ce que tu vois) (?:sur|a) $MON ecran",
        "(?:lis|decris|resume|explique|regarde|vois tu) $MON ecran",
        "qu est ce que je regarde",
    ).map { Regex("$OPENING(?:$it)$CLOSING") }

    private val MARKS = Regex("\\p{M}+")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

    private fun plain(text: String): String =
        MARKS.replace(Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD), "").replace(NOT_WORD, " ").trim()

    /** [text], all of it, asks what's on the screen. */
    fun asks(text: String): Boolean = plain(text).let { t -> ASKS.any { it.matches(t) } }

    /** This turn's question is the screen question, with the screen attached (or nothing typed beside its chip). */
    fun answerOnly(question: String, attachment: Attachment): Boolean =
        attachment == Attachment.SCREEN && (asks(question) || question == Prompt.defaultQuestion(Attachment.SCREEN, false))
}
