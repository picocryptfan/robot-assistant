package app.heyrobot.core.helpers

import app.heyrobot.core.ClaudeRoute
import app.heyrobot.core.ClaudeVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.Personal
import app.heyrobot.core.RobotSettings

// Pure. The rules every helper follows (0.6.0): when it is on, which way a question goes (its app or its API), who Robot
// checks with when it needs help, what may go to a helper without a tap, and the words Robot says about it. Claude's
// rules from 0.3 to 0.5 are these rules with the Claude helper (EscalationTest and ClaudeContextTest still hold).

/** Who a helper is, for the words Robot says: its name, its company and where its key comes from. */
data class HelperWho(val name: String, val company: String, val console: String?, val app: String?, val home: Boolean) {
    companion object {
        val CLAUDE = HelperWho("Claude", "Anthropic", "console.anthropic.com", "Claude", false)

        fun of(h: HelperConfig): HelperWho {
            val p = h.presetOf
            return HelperWho(h.name, p.company, p.keyWhere?.substringBefore(" ›"), if (p.app != null) p.defaultName else null, p.home)
        }
    }
}

object HelperSettings {
    /**
     * The company's app or its API: the app until a key is saved, the API once one is, unless you chose. A helper without
     * an app always goes through its API (a home server needs no key).
     */
    fun route(h: HelperConfig, keySaved: Boolean): ClaudeRoute =
        if (h.presetOf.app == null) ClaudeRoute.API else h.route ?: if (keySaved) ClaudeRoute.API else ClaudeRoute.APP

    /** Robot can ask [h] itself: on, the API route, and a key when it needs one. */
    fun apiReady(h: HelperConfig, keySaved: Boolean): Boolean =
        h.on && route(h, keySaved) == ClaudeRoute.API && (keySaved || !h.presetOf.keyRequired)

    /**
     * "When Robot needs help" for [h]: as chosen, else Automatic once Robot can ask it itself and Ask me first before;
     * never Automatic without that (an app can't escalate by itself).
     */
    fun escalation(s: RobotSettings, h: HelperConfig, keySaved: Boolean): Escalation {
        val ready = apiReady(h, keySaved)
        val chosen = s.help ?: if (ready) Escalation.AUTO else Escalation.ASK
        return if (chosen == Escalation.AUTO && !ready) Escalation.ASK else chosen
    }

    /**
     * The helper Robot checks with when it needs help: the one you chose ("Ask:"), when it is still there and on; else the
     * first one Robot can ask itself ([keySaved] per helper); else the first on with an app to open (Claude's, without a
     * key). Null: none.
     */
    fun target(s: RobotSettings, keySaved: (HelperConfig) -> Boolean): HelperConfig? {
        s.helper(s.helpDefault)?.takeIf { it.on }?.let { return it }
        return s.helpers.firstOrNull { apiReady(it, keySaved(it)) } ?: s.helpers.firstOrNull { it.on && it.presetOf.app != null }
    }

    /** The first Kokoro voice that neither Robot ([robotSpeaker]) nor another helper uses, for a new helper. */
    fun freeVoice(s: RobotSettings, robotSpeaker: Int?): String {
        val used = s.helpers.map { it.voice }.toSet()
        return ClaudeVoices.KOKORO.firstOrNull { (name, id) -> name !in used && id != robotSpeaker }?.first
            ?: ClaudeVoices.KOKORO.firstOrNull { it.second != robotSpeaker }?.first ?: ClaudeVoices.DEFAULT
    }
}

/**
 * What may go to a helper without a tap (docs/ask-claude.md "What Claude receives", for every helper). Two switches
 * decide, and both are off by default:
 * - Settings › Safety & privacy › "Send personal content to helpers automatically" ([RobotSettings.personalToHelpers]),
 *   for every helper: Robot checking by itself may include the conversation's personal content and this question's
 *   screen or photo, and what Robot reads for a helper goes without "Share with …". A helper joining still asks first.
 * - The helper's own "Trusted with personal content" ([HelperConfig.trust]), for that helper only: the conversation's
 *   personal content (what Robot read from your calendar, notes, contacts, your location, your phone's status) may go
 *   when Robot checks with it, when it joins, on a spoken yes, and what Robot reads for it goes without "Share with …".
 *   A screen or a photo still needs a tap every time: each capture is a new disclosure.
 */
object HelperTrust {
    /** Robot checking with [h] by itself may carry the last turns of a personal conversation. */
    fun conversation(s: RobotSettings, h: HelperConfig?): Boolean = s.personalToHelpers || h?.trust == true

    /** This question's screen text and picture may go with an escalation without a tap (never by trust alone). */
    fun attachments(s: RobotSettings): Boolean = s.personalToHelpers

    /** What a read tool finds for [h] (or an online lookup around where you are) goes without "Share with …". */
    fun reads(s: RobotSettings, h: HelperConfig?): Boolean = s.personalToHelpers || h?.trust == true

    /**
     * [h] joining a conversation that holds [personal] skips the Include card: only a trusted helper, only when it was
     * called by its name (not a word that only sounded like it), and never when the conversation had a screen or a photo.
     */
    fun joinWithoutCard(h: HelperConfig?, personal: Set<Personal>, misheard: Boolean): Boolean =
        h?.trust == true && !misheard && Personal.SCREEN !in personal && Personal.PHOTO !in personal

    /** A spoken or typed yes may send [personal] to [h] ("Voice yes can send personal content", or a trusted helper without a picture). */
    fun voiceYes(s: RobotSettings, h: HelperConfig?, personal: Set<Personal>): Boolean =
        personal.isEmpty() || s.voiceYesPersonal || (h?.trust == true && Personal.SCREEN !in personal && Personal.PHOTO !in personal)
}

/**
 * What Robot says about a helper, in its name ([ClaudeWords] is these with Claude: what 0.3 to 0.5 said, except that the
 * budget is now every helper's and the settings live under Helpers).
 */
class HelperWords(w: HelperWho) {
    private val n = w.name
    val declined = "$n declined that one."
    val thinking = "$n is thinking…"
    val checking = "Checking with $n…"
    val searched = "$n searched the web"
    val off = "$n is off. Turn it on in Robot's settings, under Helpers."
    val locked = "Unlock your phone to ask $n, or allow $n on the lock screen in Robot's settings."
    val joined = "$n is here. Go ahead."
    val notHere = "$n isn't in this conversation."
    val budget = "Today's budget for helpers is used up, so Robot will ask before checking with $n again today."
    val cutEmpty = "$n ran out of room before it could answer. Ask again, or ask for a shorter answer."
    val keyUnreadable = "Robot couldn't read your saved $n key, so it was removed. Add it again in Robot's settings, under Helpers."
    val offer = "Want me to check with $n?"
    val offerAsWell = "Want $n's take as well?"
    val rather = "I'd rather check that with $n."
    /** A home server's model is loading (no answer for a few seconds). */
    val loading = "$n is loading its model…"
    val unlockToCheck = "Unlock your phone to check with $n."
    val nothingSent = "Okay, nothing was sent to $n."

    fun tapToInclude(p: Set<Personal>): String = "Tap Send to include ${Personal.list(p)}."
}
