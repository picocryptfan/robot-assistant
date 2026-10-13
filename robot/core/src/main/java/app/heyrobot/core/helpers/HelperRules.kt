package app.heyrobot.core.helpers

import app.heyrobot.core.AskRoute
import app.heyrobot.core.HelperVoices
import app.heyrobot.core.Escalation
import app.heyrobot.core.Personal
import app.heyrobot.core.RobotSettings

// Pure. The rules every helper follows (0.6.0): when it is on, which way a question goes (its app or its API), who Robot
// checks with when it needs help, what may go to a helper without a tap, and the words Robot says about it. Claude's
// rules from 0.3 to 0.5 are these rules with the Claude helper (EscalationTest and HelperContextTest still hold).

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
    fun route(h: HelperConfig, keySaved: Boolean): AskRoute =
        if (h.presetOf.app == null) AskRoute.API else h.route ?: if (keySaved) AskRoute.API else AskRoute.APP

    /** Robot can ask [h] itself: on, the API route, and a key when it needs one. */
    fun apiReady(h: HelperConfig, keySaved: Boolean): Boolean =
        h.on && route(h, keySaved) == AskRoute.API && (keySaved || !h.presetOf.keyRequired)

    /**
     * "When Robot needs help" for [h]: as chosen, else Automatic once Robot can ask it itself and Ask me first before;
     * never Automatic without that (an app can't escalate by itself).
     */
    fun escalation(s: RobotSettings, h: HelperConfig, keySaved: Boolean): Escalation {
        val ready = apiReady(h, keySaved)
        val chosen = s.help ?: if (ready) Escalation.AUTO else Escalation.ASK
        return if (chosen == Escalation.AUTO && !ready) Escalation.ASK else chosen
    }

    /** Robot may describe ask_claude to the local model for the helper it checks with ([target]; null: none, so no tool). */
    fun localTool(s: RobotSettings, target: HelperConfig?, keySaved: Boolean): Boolean =
        target != null && target.on && escalation(s, target, keySaved) != Escalation.OFF

    /**
     * The helper Robot checks with when it needs help: the one you chose ("Ask:"), when it is still there and on; else the
     * first one Robot can ask itself ([keySaved] per helper); else the first on with an app to open (Claude's, without a
     * key). Null: none.
     */
    fun target(s: RobotSettings, keySaved: (HelperConfig) -> Boolean): HelperConfig? {
        s.helper(s.helpDefault)?.takeIf { it.on }?.let { return it }
        return s.helpers.firstOrNull { apiReady(it, keySaved(it)) } ?: s.helpers.firstOrNull { it.on && it.presetOf.app != null }
    }

    /**
     * 1.0.0, "ask the helper …" (no name said): a helper that can take the question now. The one you chose ("Ask:") when it
     * can; else the first Robot can ask itself; else the first on whose app is installed ([appInstalled], by package).
     * Null: none can, and Robot says what's missing rather than show a card that only says to install an app.
     */
    fun askTarget(s: RobotSettings, keySaved: (HelperConfig) -> Boolean, appInstalled: (String) -> Boolean): HelperConfig? {
        fun usable(h: HelperConfig): Boolean {
            val key = keySaved(h)
            return apiReady(h, key) || (h.on && route(h, key) == AskRoute.APP && h.presetOf.app?.let(appInstalled) == true)
        }
        s.helper(s.helpDefault)?.takeIf(::usable)?.let { return it }
        return s.helpers.firstOrNull { apiReady(it, keySaved(it)) } ?: s.helpers.firstOrNull(::usable)
    }

    /**
     * 1.0.0: the helper whose app a hand-off opens (ask_claude, an offer's tap, a routine's step). [id]: the helper it was
     * meant for, which must be on and have an app; without one, the helper Robot checks with when it opens an app, else
     * the first on that does. Null: no helper on opens an app, and nothing opens.
     */
    fun appHelper(s: RobotSettings, id: String?, keySaved: (HelperConfig) -> Boolean): HelperConfig? {
        if (id != null) return s.helper(id)?.takeIf { it.on && it.presetOf.app != null }
        target(s, keySaved)?.takeIf { it.presetOf.app != null && route(it, keySaved(it)) == AskRoute.APP }?.let { return it }
        return s.helpers.firstOrNull { it.on && it.presetOf.app != null && route(it, keySaved(it)) == AskRoute.APP }
            ?: s.helpers.firstOrNull { it.on && it.presetOf.app != null }
    }

    /**
     * 1.0.0: the helpers that can summarize a transcript: on, with their API (a key saved, or none needed). An app can't
     * return a summary to Robot, so a helper without a key isn't one. In your order, the one you chose ("Ask:") first.
     */
    fun summarizers(s: RobotSettings, keySaved: (HelperConfig) -> Boolean): List<HelperConfig> {
        val ok = s.helpers.filter { it.on && (keySaved(it) || !it.presetOf.keyRequired) }
        val chosen = ok.firstOrNull { it.id == s.helpDefault }
        return listOfNotNull(chosen) + ok.filter { it !== chosen }
    }

    /** "Use" on a helper's page: its app or its API, in its own name ([AskRoute]'s words for Claude, as before). */
    fun routeTitle(p: Preset, r: AskRoute): String = if (p.kind == HelperKind.ANTHROPIC) r.title else when (r) {
        AskRoute.APP -> "${p.defaultName} app"
        AskRoute.API -> "${p.company} API"
    }

    fun routeDetail(p: Preset, r: AskRoute): String = if (p.kind == HelperKind.ANTHROPIC) r.detail else when (r) {
        AskRoute.APP -> "Opens the ${p.defaultName} app with your question; you press Send there, with your own account. Robot doesn't hear the answer."
        AskRoute.API -> "Robot asks ${p.defaultName} itself and speaks the answer. Needs an API key with credits."
    }

    /** The first Kokoro voice that neither Robot ([robotSpeaker]) nor another helper uses, for a new helper. */
    fun freeVoice(s: RobotSettings, robotSpeaker: Int?): String {
        val used = s.helpers.map { it.voice }.toSet()
        return HelperVoices.KOKORO.firstOrNull { (name, id) -> name !in used && id != robotSpeaker }?.first
            ?: HelperVoices.KOKORO.firstOrNull { it.second != robotSpeaker }?.first ?: HelperVoices.DEFAULT
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
    /**
     * A screen or a photo is in [personal]: each capture is a new disclosure, never sent on trust alone. So are messages
     * you received (0.7): other people's words.
     */
    private fun captured(personal: Set<Personal>): Boolean = Personal.SCREEN in personal || Personal.PHOTO in personal || Personal.MESSAGES in personal

    /**
     * 0.7: what Robot remembers about you is in [personal] and may go to [h] only through a card: neither "Send personal
     * content to helpers automatically" nor trust alone sends it, unless Settings › Memory › "Share memory with helpers" is
     * "With helpers I trust" ([memoryToTrusted]) and [h] is one.
     */
    private fun memoryHeld(h: HelperConfig?, personal: Set<Personal>, memoryToTrusted: Boolean): Boolean =
        Personal.MEMORY in personal && !(memoryToTrusted && h?.trust == true)

    /**
     * Robot checking with [h] by itself (or an offer's payload) may carry the last turns of a conversation that holds
     * [personal]: with "Send personal content to helpers automatically", or to a trusted helper when no screen or photo
     * is in it (the last turns may be Robot's words about the screen; joining and a spoken yes follow the same rule).
     * 0.7: never with messages you received in it (neither switch sends them), nor with what Robot remembers ([memoryHeld]).
     */
    fun conversation(s: RobotSettings, h: HelperConfig?, personal: Set<Personal>, memoryToTrusted: Boolean = false): Boolean =
        !memoryHeld(h, personal, memoryToTrusted) &&
            ((s.personalToHelpers && Personal.MESSAGES !in personal) || (h?.trust == true && !captured(personal)))

    /** This question's screen text and picture may go with an escalation without a tap (never by trust alone). */
    fun attachments(s: RobotSettings): Boolean = s.personalToHelpers

    /** What a read tool finds for [h] (or an online lookup around where you are) goes without "Share with …". */
    fun reads(s: RobotSettings, h: HelperConfig?): Boolean = s.personalToHelpers || h?.trust == true

    /**
     * [h] joining a conversation that holds [personal] skips the Include card: only a trusted helper, only when it was
     * called by its name (not a word that only sounded like it), and never when the conversation had a screen or a photo.
     */
    fun joinWithoutCard(h: HelperConfig?, personal: Set<Personal>, misheard: Boolean, memoryToTrusted: Boolean = false): Boolean =
        h?.trust == true && !misheard && !captured(personal) && !memoryHeld(h, personal, memoryToTrusted)

    /** A spoken or typed yes may send [personal] to [h] ("Voice yes can send personal content", or a trusted helper without a picture). */
    fun voiceYes(s: RobotSettings, h: HelperConfig?, personal: Set<Personal>): Boolean =
        personal.isEmpty() || s.voiceYesPersonal || (h?.trust == true && !captured(personal))
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
    /** The saved key couldn't be read just now (a passing Keystore error): it is kept, and the next question reads it again. */
    val keyUnavailable = "Robot couldn't read your saved $n key just now. It's still saved, so try again in a moment."
    val offer = "Want me to check with $n?"
    val offerAsWell = "Want $n's take as well?"
    val rather = "I'd rather check that with $n."
    /** A home server's model is loading (no answer for a few seconds). */
    val loading = "$n is loading its model…"
    val unlockToCheck = "Unlock your phone to check with $n."
    val nothingSent = "Okay, nothing was sent to $n."
    /** "Ask the helper …" when no helper can take it yet ([HelperSettings.askTarget]): what [n], the one you'd get, still needs. */
    val notReady = "$n can't answer yet. Add its API key in Robot's settings, under Helpers" + (w.app?.let { ", or install the $it app" } ?: "") + "."

    fun tapToInclude(p: Set<Personal>): String = "Tap Send to include ${Personal.list(p)}."
}

/** A helper's API key's shape, checked before it is saved (pure; the key itself is handled in the :voice process only). */
object HelperKeyFormat {
    /**
     * Claude's: "sk-ant-" and ASCII letters, digits, "-" and "_" (as since 0.3.0). Any other: printable ASCII without
     * spaces, 8 to 512 characters (an HTTP header can't carry anything else, and the HTTP client's error for a bad header
     * character quotes the whole value). The usual start of a company's keys is only a warning ([warning]).
     */
    fun plausible(p: Preset, k: String): Boolean =
        if (p.kind == HelperKind.ANTHROPIC) app.heyrobot.core.AnthropicKeyFormat.plausible(k) else k.length in 8..512 && k.all { it.code in 0x21..0x7E }

    /** A note when [k] doesn't start the way [p]'s keys usually do (never a refusal: a wrong guess must not block a key). */
    fun warning(p: Preset, k: String): String? =
        if (p.keyPrefixes.isEmpty() || p.keyPrefixes.any { k.startsWith(it) }) null
        else "${p.company} keys usually start with ${p.keyPrefixes.first()}. Robot will try this one anyway."

    /** "sk-ant-…AB12", "gsk_…AB12", "…AB12": the usual start when it has one, and the last four characters. */
    fun mask(p: Preset, k: String): String {
        val start = p.keyPrefixes.firstOrNull { k.startsWith(it) }.orEmpty()
        return start + "…" + k.takeLast(4)
    }

    /** What the key window says about [p]'s key. */
    fun refusal(p: Preset): String = if (p.kind == HelperKind.ANTHROPIC) "That doesn't look like an Anthropic API key (sk-ant- followed by letters, digits, - and _)."
        else "That doesn't look like an API key: it should be 8 to 512 letters, digits and symbols, with no spaces."
}
