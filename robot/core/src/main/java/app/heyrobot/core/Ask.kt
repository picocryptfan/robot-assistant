package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperWho
import java.util.Locale
import kotlin.math.roundToLong

// Pure. Asking a helper (docs/ask-claude.md; every helper since 0.6.0): how hard a helper thinks and how long it may
// answer, the tokens a request used, the cost line, the plain-language failures, a helper's voice, which route a question
// takes (the helper's app or its API) and when Robot may bring a helper in by itself. Nothing here touches the network:
// the clients live in the :helpers module, which only the :voice process loads. Anthropic's models and prices, and the
// Claude helper as 0.3 to 0.5 had it, are in Anthropic.kt.

/** `output_config.effort`. Always sent explicitly: the API default differs between models. */
enum class HelperEffort(val wire: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

/** The effort setting: automatic, or one level for every question (a setting overrides both automatic rules). */
enum class HelperEffortSetting(val title: String, val level: HelperEffort?) {
    AUTO("Automatic", null),
    LOW("Low", HelperEffort.LOW),
    MEDIUM("Medium", HelperEffort.MEDIUM),
    HIGH("High", HelperEffort.HIGH),
}

/** How hard a helper thinks and how long it may answer, for one question. */
data class HelperPlan(val effort: HelperEffort, val maxTokens: Long) {
    companion object {
        /** A spoken answer: short, and thinking counts towards it too (never lowball it). */
        const val VOICE_TOKENS = 4_000L
        /** Typed, asked for depth, or high effort (thinking would use up a short limit). */
        const val DEEP_TOKENS = 16_000L

        /**
         * Automatic: low for a voice question, medium for a typed one, high when the user asked for depth ("think hard
         * about", "take your time"). A fixed setting wins over all three. High effort always gets the long limit: its
         * thinking alone can use up a short one and leave no answer.
         */
        fun of(setting: HelperEffortSetting, voice: Boolean, deep: Boolean): HelperPlan {
            val effort = setting.level ?: when {
                deep -> HelperEffort.HIGH
                voice -> HelperEffort.LOW
                else -> HelperEffort.MEDIUM
            }
            return HelperPlan(effort, if (voice && !deep && effort != HelperEffort.HIGH) VOICE_TOKENS else DEEP_TOKENS)
        }
    }

    /**
     * This question's plan in a helper's conversation whose first question had [first]: the effort stays the same
     * for the whole conversation (a change would restart the prompt cache); the length still follows this question.
     */
    fun keeping(first: HelperPlan): HelperPlan =
        HelperPlan(first.effort, if (first.effort == HelperEffort.HIGH) DEEP_TOKENS else maxTokens)
}

/** What one request used, from its `usage` (numbers only). */
data class TokenUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val searches: Long = 0) {
    val prompt: Long get() = input + cacheRead + cacheWrite
}

/** Every helper's cost line and spend, as Robot writes them. */
object CostText {
    /** Millionths of a dollar, for the budget file (whole numbers). */
    fun micros(dollars: Double): Long = (dollars * 1_000_000).roundToLong().coerceAtLeast(0)

    /** "≈ 1¢", "< 1¢", "≈ 12¢", "≈ \$1.40": the line under a helper's reply. */
    fun line(dollars: Double): String {
        val cents = dollars * 100
        return when {
            cents < 0.5 -> "< 1¢"
            cents < 99.5 -> "≈ ${cents.roundToLong()}¢"
            else -> String.format(Locale.US, "≈ $%.2f", dollars)
        }
    }

    /** "\$0.42" for today's spend in Settings. */
    fun dollarsText(micros: Long): String = String.format(Locale.US, "$%.2f", micros / 1_000_000.0)
}

/**
 * Why a helper's request failed, as Robot says it (every helper since 0.6.0). Mapped from the Anthropic SDK's typed
 * exceptions (most specific first) in the :helpers module, or from an HTTP status and the error body's type and
 * code for the other wire formats; the words are fixed here so they are tested once. [text] is the Claude helper's, word for word as
 * before; [say] puts in another helper's name. [retry]: worth one more try (overloaded or 5xx).
 */
enum class HelperFailure(val retry: Boolean = false) {
    KEY_REFUSED,
    NO_CREDITS,
    NOT_ALLOWED,
    MODEL_UNAVAILABLE,
    RATE_LIMITED,
    OVERLOADED(retry = true),
    SERVER(retry = true),
    BAD_REQUEST,
    TOO_LONG,
    NO_INTERNET,
    NO_NETWORK_PERMISSION,
    /** 60 seconds without anything from the helper (a thinking block in progress counts as something). */
    TIMEOUT,
    /** Robot's copy of the conversation with the helper no longer made a request the API takes: the conversation starts over. */
    RESET,
    NO_KEY,
    UNKNOWN,
    /** 0.6.0, a server on your network: the connection was refused or timed out. */
    UNREACHABLE,
    /** 0.6.0: an http:// address that isn't on your home network (core LanRule): nothing was sent. */
    NOT_LOCAL,
    /** 0.6.0: the server's certificate isn't one Robot trusts (self-signed, or not for that name). */
    CERTIFICATE,
    /** 0.6.0: your server's address names no single place yet (a bare IP: Test connection finds the port). */
    NO_ADDRESS;

    /** The Claude helper's words (as 0.3 to 0.5 said them). */
    val text: String get() = say(HelperWho.CLAUDE)

    /** The words for helper [w]. */
    fun say(w: HelperWho): String {
        val n = w.name
        return when (this) {
            KEY_REFUSED -> "Your $n key was refused. Check it in Robot's settings."
            NO_CREDITS -> "Your ${w.company} account is out of API credits." + (w.console?.let { " Add credits at $it." } ?: "")
            NOT_ALLOWED -> if (w.home) "$n refused that request. Check the server's settings." else "Your $n key isn't allowed to do that. Check your ${w.company} account."
            MODEL_UNAVAILABLE -> if (w.home) "$n doesn't have that model any more. Pick another one in Robot's settings, under Helpers."
                else "That $n model isn't available to your key. Pick another one in Robot's settings."
            RATE_LIMITED -> "$n is busy, try again in a minute."
            OVERLOADED -> "$n is overloaded right now. Try again in a minute."
            SERVER -> "$n isn't answering right now. Try again in a minute."
            BAD_REQUEST -> "$n couldn't take that request."
            TOO_LONG -> "That was too long for $n."
            NO_INTERNET -> "No internet. If you're online, check that Robot's Network permission is on (Settings › Apps › Robot › Permissions)."
            NO_NETWORK_PERMISSION -> "Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings."
            TIMEOUT -> "$n took too long to answer."
            RESET -> "Robot lost track of the conversation with $n, so $n mode has ended. Ask again to start fresh."
            NO_KEY -> "Add your $n API key in Robot's settings first" + (w.app?.let { ", or choose the $it app there" } ?: "") + "."
            UNKNOWN -> "Something went wrong with $n."
            UNREACHABLE -> if (w.home) "$n isn't answering. Is the computer on, and is the phone on the same network?" else "$n can't be reached right now."
            NOT_LOCAL -> "That address isn't on your home network, so Robot won't send to it without https."
            CERTIFICATE -> "$n's certificate isn't one Robot can trust. Use https with a real certificate (Tailscale's HTTPS gives one), or http on your home network."
            NO_ADDRESS -> "Robot doesn't know where $n is yet. Open its page in Robot's settings, under Helpers, and tap Test connection."
        }
    }

    companion object {
        /**
         * From an HTTP status and the API's error type (`authentication_error`, `overloaded_error`, ...). A stream that
         * failed after it opened carries status 200 and the type only. Null [status] and [type]: no answer arrived.
         * [code]: the error's code where the API gives one (OpenAI says "out of credit" with a 429 and a code), read
         * before the status so it is never taken for "busy".
         */
        fun of(status: Int?, type: String?, code: String? = null): HelperFailure = when {
            code in CREDIT_CODES || type == "insufficient_quota" || (code != null && code.endsWith("_spend_limit_exceeded")) -> NO_CREDITS
            type == "authentication_error" || status == 401 || code == "invalid_api_key" -> KEY_REFUSED
            type == "billing_error" || status == 402 -> NO_CREDITS
            type == "permission_error" || status == 403 -> NOT_ALLOWED
            type == "not_found_error" || status == 404 -> MODEL_UNAVAILABLE
            type == "request_too_large" || status == 413 || code == "context_length_exceeded" -> TOO_LONG
            type == "rate_limit_error" || status == 429 || code == "rate_limit_exceeded" -> RATE_LIMITED
            type == "overloaded_error" || status == 529 || code == "server_is_overloaded" || status == 498 -> OVERLOADED
            type == "api_error" || type == "timeout_error" || code == "server_error" || (status != null && status >= 500) -> SERVER
            type == "invalid_request_error" || status == 400 || status == 422 -> BAD_REQUEST
            else -> UNKNOWN
        }

        /** OpenAI's codes for an account out of credit (they come with a 429). */
        private val CREDIT_CODES = setOf("credit_balance_exhausted", "insufficient_quota", "organization_usage_limit_exceeded", "billing_hard_limit_reached")
    }
}

/**
 * A helper's voice: another voice of the same model as Robot's, so you hear who is talking without a second model
 * load. Kokoro speaks the chosen [KOKORO] voice; Kitten its other voice of the same pair; the phone's voice reads
 * the helper at a lower pitch (no speaker id).
 */
object HelperVoices {
    /** The Kokoro v1.0 English voices docs/voice-plan.md lists, with their speaker ids. */
    val KOKORO: List<Pair<String, Int>> = listOf(
        "am_michael" to 16, "am_fenrir" to 14, "am_puck" to 18, "bf_emma" to 21, "af_bella" to 2, "af_heart" to 3,
    )
    const val DEFAULT = "am_michael"
    /** The phone's own voice: a helper's sentences at this pitch (Robot's at 1.0). */
    const val SYSTEM_PITCH = 0.82f

    fun title(name: String): String = when (name) {
        "am_michael" -> "Michael (American, male)"
        "am_fenrir" -> "Fenrir (American, male)"
        "am_puck" -> "Puck (American, male)"
        "bf_emma" -> "Emma (British, female)"
        "af_bella" -> "Bella (American, female)"
        "af_heart" -> "Heart (American, female)"
        else -> name
    }

    fun of(name: String?): String = KOKORO.firstOrNull { it.first == name }?.first ?: DEFAULT

    /**
     * The helper's voice ([voice]) for one [sentence]: its speaker id and its language. A French sentence gets Kokoro's
     * French voice (Siwis) when Robot speaks with Kokoro (or, if Robot's own voice is Siwis, the helper's voice reading
     * French); everything else the helper's own voice in English. Null speaker: the phone's voice at a lower pitch.
     */
    fun forSentence(robot: VoiceChoice, voice: String?, sentence: String): Pair<Int?, String?> {
        val french = robot.bundle == VoiceBundle.KOKORO && SpeechLang.french(sentence)
        return when {
            !french -> speaker(robot, voice) to null
            robot != VoiceChoice.SIWIS -> VoiceChoice.SIWIS.speaker to "fr"
            else -> speaker(robot, voice) to "fr"
        }
    }

    /**
     * The speaker id a helper's sentences use in its [voice] with Robot's [robot] voice, or null for the phone's voice.
     * Never Robot's own speaker: if you picked Robot's voice for a helper, it gets the default instead.
     */
    fun speaker(robot: VoiceChoice, voice: String?): Int? = when (robot.bundle) {
        VoiceBundle.KOKORO -> KOKORO.first { it.first == of(voice) }.second.let { id -> if (id != robot.speaker) id else KOKORO.first { it.second != robot.speaker }.second }
        // Kitten nano 0.8: pairs of voices (2*k male, 2*k+1 female); a helper speaks the other one of Robot's pair.
        VoiceBundle.KITTEN -> robot.speaker xor 1
        else -> null
    }
}

/**
 * A cheap check of a sentence's language, to read a helper's French in a French voice: common French words, elisions
 * ("l'", "qu'") and accents against common English words. Pure.
 */
object SpeechLang {
    private val FRENCH = setOf("le", "la", "les", "des", "du", "de", "un", "une", "est", "et", "que", "qui", "pas", "pour", "avec", "dans",
        "sur", "je", "tu", "il", "elle", "nous", "vous", "ils", "elles", "ce", "cette", "ces", "mais", "ou", "tres", "aussi", "sont", "etre",
        "plus", "au", "aux", "en", "son", "sa", "ses", "leur", "mon", "ma", "mes", "votre", "notre", "peut", "fait", "comme", "bien", "y",
        "suis", "bonjour", "merci", "oui", "voici", "voila", "tout", "rien", "aujourdhui")
    private val ENGLISH = setOf("the", "is", "and", "of", "to", "in", "that", "it", "you", "for", "with", "are", "this", "be", "was", "at",
        "by", "or", "not", "but", "from", "they", "we", "have", "has", "your", "my", "what", "which", "can", "will", "would", "about", "there")
    private val ELISION = Regex("""(?i)\b(l|d|qu|c|j|n|s|m|t)['’][a-zàâäéèêëîïôöùûüç]""")
    private val ACCENT = Regex("[àâéèêëîïôûùüç]")

    fun french(sentence: String): Boolean {
        val t = sentence.lowercase(java.util.Locale.ROOT)
        val words = HelperRouter.plain(t).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val fr = words.count { it in FRENCH } + ELISION.findAll(t).count() + ACCENT.findAll(t).count() / 2
        val en = words.count { it in ENGLISH }
        return fr >= 2 && fr > en * 2
    }
}

/** Which way a question to a helper goes. The titles are the Claude helper's (HelperSettings.routeTitle words the others'). */
enum class AskRoute(val title: String, val detail: String) {
    APP("Claude app", "Opens the Claude app with your question; uses your claude.ai plan. Robot doesn't hear the answer."),
    API("Claude API", "Robot asks Claude itself and speaks the answer. Needs an API key with credits."),
}

/** When Robot may bring a helper in without being asked (docs/ask-claude.md "When Robot needs help"; every helper since 0.6.0). */
enum class Escalation(val title: String, val detail: String) {
    OFF("Off", "Robot never suggests a helper."),
    ASK("Ask me first", "Robot offers to check with the helper and waits for a yes or a tap."),
    AUTO("Automatic", "Robot checks with the helper by itself for hard questions (only through its API, at a price it knows)."),
}

/** Why the hand-over tool (ask_claude) is left out of a question for the local model (logged as this code only, never the words). */
enum class HelperLeftOut(override val id: Int, override val title: String) : LogCode {
    OFF(1, "Ask a helper or “When Robot needs help” is off"),
    NOWHERE(2, "no API key and no app to open"),
    DECLINED(3, "you turned the helper down in this conversation"),
    SCREEN(4, "a question about the screen: Robot answers it first"),
    PHOTO(5, "a question about a photo: Robot answers it first"),
}

/**
 * Whether the local model is told about the hand-over tool (ask_claude) for one question: the tool and its one line in the system prompt go
 * together, or neither does (docs/ask-claude.md "When Robot needs help"). Pure.
 */
object AskHelperTool {
    /**
     * For the Claude helper, as before 0.6.0: null when ask_claude is described, otherwise why not, first reason first:
     * Ask Claude off or escalation Off ([ClaudeSettings.localTool]); nowhere for the question to go ([reachable]: the API
     * with a key, or the Claude app installed); you said "not Claude" in this conversation ([declined]); or the question is
     * about the screen or a photo ([attachment], see [picture]): Robot answers those itself first, and "ask Claude about
     * this screen" still goes to Claude.
     */
    fun leftOut(s: RobotSettings, keySaved: Boolean, reachable: Boolean, declined: Boolean, attachment: Attachment): HelperLeftOut? = when {
        !ClaudeSettings.localTool(s, keySaved) -> HelperLeftOut.OFF
        !reachable -> HelperLeftOut.NOWHERE
        declined -> HelperLeftOut.DECLINED
        attachment == Attachment.SCREEN -> HelperLeftOut.SCREEN
        attachment == Attachment.PHOTO -> HelperLeftOut.PHOTO
        else -> null
    }

    fun offered(s: RobotSettings, keySaved: Boolean, reachable: Boolean, declined: Boolean, attachment: Attachment): Boolean =
        leftOut(s, keySaved, reachable, declined, attachment) == null

    /** The same for any helper (0.6.0): [localTool] is HelperSettings.localTool for the helper Robot checks with. */
    fun leftOut(localTool: Boolean, reachable: Boolean, declined: Boolean, attachment: Attachment): HelperLeftOut? = when {
        !localTool -> HelperLeftOut.OFF
        !reachable -> HelperLeftOut.NOWHERE
        declined -> HelperLeftOut.DECLINED
        attachment == Attachment.SCREEN -> HelperLeftOut.SCREEN
        attachment == Attachment.PHOTO -> HelperLeftOut.PHOTO
        else -> null
    }

    /**
     * The screen or photo a question to the local model is about: its own, else the newest one earlier in the
     * conversation ([history]). A follow-up ("and the second one?") is about it too, and the live conversation still
     * holds that screen's text and picture: describing ask_claude again would change the system prompt, and the
     * conversation would be rebuilt from the history, where screens and pictures are not resent.
     */
    fun picture(attachment: Attachment, history: List<ChatTurn>): Attachment =
        if (attachment != Attachment.NONE) attachment
        else history.lastOrNull { it.role == TurnRole.USER && it.attachment != Attachment.NONE }?.attachment ?: Attachment.NONE
}

/**
 * Personal content in a conversation (docs/ask-claude.md "What Claude receives", for every helper). From the first turn
 * that carries one, the conversation is personal: Robot checking with a helper by itself sends only the question, and a
 * helper joining it sees what came before only after a tap on Include. [label] completes "It contains …".
 */
enum class Personal(val label: String) {
    SCREEN("screen text"),
    PHOTO("a photo"),
    CALENDAR("your calendar"),
    NOTES("your notes"),
    /** What a lookup found: the phone's contacts and Robot's People (names learned from notifications included). */
    CONTACTS("your contacts"),
    /** 0.4.0: a weather lookup or the nearest place found online, which say where you are (or where your Home for weather is). */
    LOCATION("your location"),
    /** 0.4.0: phone_status, your phone's storage, battery and memory and its biggest apps. */
    PHONE("your phone's status"),
    /** Claude's answer read off the Claude app's screen. */
    CLAUDE_APP("text from the Claude app"),
    /** Another read tool's findings (none today besides the three above). */
    READ("what Robot read on your phone"),
    /**
     * 0.5.0: a transcript from conversation mode, other people's words included. It reaches a helper only through the
     * "Summarize with a stronger AI" card, never through an escalation, a helper's conversation or "include the conversation".
     */
    CONVERSATION("a recorded conversation"),
    /**
     * 0.7: what Robot remembers about you (core memory/), given to the local model with a turn or saved in it. It reaches a
     * helper only through a card, even a trusted one (HelperTrust), and a secret item never does.
     */
    MEMORY("what Robot remembers about you"),
    /**
     * 0.7: messages you received, other people's words. Like a screen or a photo, never sent to a helper on trust alone
     * or by "Send personal content to helpers automatically": only through a card that lists them (core helpers HelperTrust).
     */
    MESSAGES("messages you received"),
    /** 0.9: what a Termux command or script printed. */
    TERMUX("your Termux output"),
    ;

    companion object {
        fun of(a: Attachment): Personal? = when (a) {
            Attachment.SCREEN -> SCREEN
            Attachment.PHOTO -> PHOTO
            Attachment.NONE -> null
        }

        /** What a read tool's findings were; null for public web text (0.9: web search is content, not personal). */
        fun of(tool: ToolName): Personal? = when (tool) {
            ToolName.WEB_SEARCH -> null
            ToolName.READ_CALENDAR -> CALENDAR
            ToolName.FIND_NOTES -> NOTES
            ToolName.LOOKUP_CONTACT, ToolName.CALL -> CONTACTS
            // The weather, and (0.4.0) the nearest place found online: both say where you are.
            ToolName.WEATHER, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> LOCATION
            ToolName.PHONE_STATUS, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE -> PHONE
            ToolName.TERMUX_SCRIPT, ToolName.TERMUX_COMMAND -> TERMUX
            else -> READ
        }

        /** "screen text, a photo and your calendar", in the enum's order. */
        fun list(p: Set<Personal>): String {
            val l = entries.filter { it in p }.map { it.label }
            return when (l.size) {
                0 -> ""
                1 -> l[0]
                else -> l.dropLast(1).joinToString(", ") + " and " + l.last()
            }
        }
    }
}

enum class EscalationDecision {
    /** Robot doesn't bring a helper in. */
    NONE,
    /** Ask the helper now, without a tap (API key, Automatic, nothing personal, within budget, allowed here). */
    AUTO,
    /** Offer it: a card under the reply ("Want me to check with Jarvis?" or "Open the ChatGPT app with: «…»"), a tap or a yes. */
    OFFER,
    /** The phone is locked and the helper isn't allowed on the lock screen. */
    NEEDS_UNLOCK,
}

/** Why Robot thought of a helper (logged as this enum only, never the words). */
enum class EscalationTrigger { PRE_ROUTER, LOCAL_TOOL, HEDGE }

/** The escalation policy: pure, so its whole matrix is tested (EscalationTest). */
object EscalationPolicy {
    /**
     * [personal]: what would be sent includes this question's screen text or picture: always asks first, with a card
     * listing it (earlier personal turns are never sent by an escalation, so they don't need one). [budgetReached]:
     * Automatic drops to Ask first for the rest of the day. [alreadyEscalated]: at most one escalation per turn.
     * [fromHelper]: a helper's answer is never re-escalated. [declined]: you turned the helper down in this conversation
     * ("not Claude"), so Robot neither asks it nor offers to. This overload decides for the Claude helper, as before 0.6.0.
     */
    fun decide(
        s: RobotSettings,
        keySaved: Boolean,
        personal: Boolean,
        locked: Boolean,
        budgetReached: Boolean,
        alreadyEscalated: Boolean = false,
        fromHelper: Boolean = false,
        declined: Boolean = false,
    ): EscalationDecision = decide(s, s.claude, keySaved, personal, locked, budgetReached, alreadyEscalated, fromHelper, declined)

    /**
     * The same for any helper (0.6.0): [target] is the helper Robot would check with ([HelperSettings.target]; null: none),
     * [keySaved] its key. Automatic also needs [priceKnown] (a reply whose cost Robot can't tell is never asked for by
     * itself) and, for current information, a helper that can search the web ([needsSearch] and [HelperConfig.searching]):
     * otherwise Robot offers. A helper with neither an API Robot can use nor an app to open: nothing.
     */
    fun decide(
        s: RobotSettings,
        target: HelperConfig?,
        keySaved: Boolean,
        personal: Boolean,
        locked: Boolean,
        budgetReached: Boolean,
        alreadyEscalated: Boolean = false,
        fromHelper: Boolean = false,
        declined: Boolean = false,
        priceKnown: Boolean = target?.price?.known ?: false,
        needsSearch: Boolean = false,
    ): EscalationDecision {
        if (fromHelper || alreadyEscalated || declined || target == null || !target.on) return EscalationDecision.NONE
        val mode = HelperSettings.escalation(s, target, keySaved)
        if (mode == Escalation.OFF) return EscalationDecision.NONE
        val api = HelperSettings.apiReady(target, keySaved)
        if (!api && target.presetOf.app == null) return EscalationDecision.NONE
        // The app needs the phone unlocked, always; the API only when the helper isn't allowed on the lock screen.
        if (locked && (!api || !target.locked)) return EscalationDecision.NEEDS_UNLOCK
        val auto = api && mode == Escalation.AUTO && !personal && !budgetReached && priceKnown && (!needsSearch || target.searching)
        return if (auto) EscalationDecision.AUTO else EscalationDecision.OFFER
    }

    /**
     * The local model handed the question over (ask_claude, [handedOver]) without a word or an action ([said] false), and
     * the helper won't answer right now ([decision] isn't AUTO: an offer, the lock screen, or no helper at all): Robot runs
     * the model once more on the same question without ask_claude for its own short answer, and any offer goes under
     * it. Never an answer of nothing but "I'd rather check that with Jarvis.".
     */
    fun ownAnswerFirst(decision: EscalationDecision, handedOver: Boolean, said: Boolean): Boolean =
        handedOver && !said && decision != EscalationDecision.AUTO
}

/** At most one escalation per turn, and never one from a helper's answer (the "no loops" rule). */
class EscalationGuard {
    var used = false; private set

    /** True the first time only; false for a helper's answer. */
    fun take(fromHelper: Boolean): Boolean {
        if (fromHelper || used) return false
        used = true
        return true
    }
}
