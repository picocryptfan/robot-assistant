// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.BriefStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Phrase
import java.time.ZonedDateTime

// Pure. 1.1.2: with no model to ask (none downloaded, or it couldn't load), "what time is it" and "set a timer for
// 10 minutes" went unanswered. Both are Robot's own to answer from your words: the time from the phone's clock, the
// timer as set_timer's call with the length you said (StepPhrases' timer phrases, as a routine's step reads them).

object ClockAsk {
    sealed class Answer {
        /** What time it is, in the brief's words ("It's 7:02, Thursday October 29."). */
        data class Say(val text: Phrase, val lang: Lang) : Answer()

        /** set_timer with the length you said; it runs as the model's call would (its approval, the lock, its card). */
        data class Call(val call: RawToolCall) : Answer()
    }

    private val LEAD = listOf("hey robot", "hi robot", "ok robot", "okay robot", "dis robot", "robot", "hey", "ok", "okay", "please",
        "can you", "could you", "peux tu", "pourrais tu", "est ce que tu peux", "sil te plait", "sil vous plait", "alors", "euh", "um")
    private val TAIL = listOf("please", "right now", "now", "sil te plait", "sil vous plait", "svp", "robot", "maintenant", "la")

    private val TIME_EN = setOf("what time is it", "whats the time", "what is the time", "tell me the time",
        "do you have the time", "what time it is", "time", "the time", "what time")
    private val TIME_FR = setOf("quelle heure est il", "quelle heure il est", "quelle heure quil est", "il est quelle heure",
        "cest quelle heure", "y est quelle heure", "dis moi lheure", "donne moi lheure", "lheure", "quelle heure", "tas lheure",
        "tu as lheure", "as tu lheure")

    /** Your words, lower case, without accents, apostrophes ("cest", StepPhrases.plain) or punctuation, without a greeting or a "please" around them. */
    internal fun plain(text: String): String {
        var s = " " + StepPhrases.plain(text.lowercase()).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
        var cut = true
        while (cut) {
            cut = false
            LEAD.firstOrNull { s.startsWith(" $it ") }?.let { s = s.substring(it.length + 1); cut = true }
            TAIL.firstOrNull { s.endsWith(" $it ") && s.length > it.length + 2 }?.let { s = s.substring(0, s.length - it.length - 1); cut = true }
        }
        return s.trim()
    }

    /** What [text] asks, read at [now], when it is only the time or a timer; null for anything else (the model's, if any). */
    fun route(text: String, now: ZonedDateTime): Answer? {
        val p = plain(text)
        if (p.isEmpty()) return null
        val fr = p in TIME_FR
        if (fr || p in TIME_EN) {
            val lang = if (fr) Lang.FR else Lang.EN
            val t = now.toLocalTime()
            return Answer.Say(BriefStrings.timeAndDate("time" to if (fr) BriefClock.fr(t) else BriefClock.nowEn(t), "date" to now.toLocalDate()), lang)
        }
        val acts = StepPhrases.steps(p)
        if (acts.unparsed.isNotEmpty() || acts.refused.isNotEmpty()) return null
        val timer = ((acts.steps.singleOrNull() as? RoutineStep.Tool)?.action as? Action.SetTimer) ?: return null
        if (timer.seconds <= 0) return null
        return Answer.Call(RawToolCall(ToolName.SET_TIMER.id, mapOf("seconds" to timer.seconds)))
    }
}
