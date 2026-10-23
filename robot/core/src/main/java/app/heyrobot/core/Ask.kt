// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.UiLabel
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.i18n.HelperStrings
import app.heyrobot.core.i18n.AndroidPath
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
enum class HelperEffortSetting(private val titleMsg: Msg, val level: HelperEffort?) {
    AUTO(HelperStrings.effortAuto, null),
    LOW(HelperStrings.effortLow, HelperEffort.LOW),
    MEDIUM(HelperStrings.effortMedium, HelperEffort.MEDIUM),
    HIGH(HelperStrings.effortHigh, HelperEffort.HIGH);

    /** Its name, as words without a language yet (1.1). */
    val titleText: Phrase get() = titleMsg()
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
    NO_ADDRESS,
    /** 1.0.1: a key is saved for an http:// address: Robot never sends a key over http (core LanRule), so nothing was sent. */
    KEY_OVER_HTTP;

    /** The Claude helper's words (as 0.3 to 0.5 said them). */
    val text: String get() = say(HelperWho.CLAUDE)

    /** The words for helper [w], in English. */
    fun say(w: HelperWho): String = sayText(w).en()

    /** [say] as words without a language yet (1.1: the panel says them in the turn's reply language). */
    fun sayText(w: HelperWho): Phrase {
        val n = "name" to w.name
        return when (this) {
            KEY_REFUSED -> HelperStrings.keyRefused(n)
            NO_CREDITS -> w.console?.let { HelperStrings.noCreditsAt("company" to w.companyText, "console" to it) } ?: HelperStrings.noCredits("company" to w.companyText)
            NOT_ALLOWED -> if (w.home) HelperStrings.serverRefused(n) else HelperStrings.notAllowed(n, "company" to w.companyText)
            MODEL_UNAVAILABLE -> if (w.home) HelperStrings.serverModelGone(n) else HelperStrings.modelUnavailable(n)
            RATE_LIMITED -> HelperStrings.rateLimited(n)
            OVERLOADED -> HelperStrings.overloaded(n)
            SERVER -> HelperStrings.serverError(n)
            BAD_REQUEST -> HelperStrings.badRequest(n)
            TOO_LONG -> HelperStrings.tooLong(n)
            NO_INTERNET -> HelperStrings.noInternet("path" to AndroidPath.APP_PERMISSIONS)
            NO_NETWORK_PERMISSION -> HelperStrings.noNetworkPermission()
            TIMEOUT -> HelperStrings.timeout(n)
            RESET -> HelperStrings.reset(n)
            NO_KEY -> w.app?.let { HelperStrings.noKeyOrApp(n, "appName" to it) } ?: HelperStrings.noKey(n)
            UNKNOWN -> HelperStrings.unknown(n)
            UNREACHABLE -> if (w.home) HelperStrings.homeUnreachable(n) else HelperStrings.unreachable(n)
            NOT_LOCAL -> HelperStrings.notLocal()
            CERTIFICATE -> HelperStrings.certificate(n)
            NO_ADDRESS -> HelperStrings.noAddress(n, "button" to UiLabel(UiStrings.testConnection))
            KEY_OVER_HTTP -> HelperStrings.keyOverHttp(n)
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

    /** A voice's name and what it sounds like ("Emma (British, female)"), as words without a language yet (1.1: Settings shows them in the menus' language); an unknown one by its id. */
    fun titleText(name: String): Phrase = when (name) {
        "am_michael" -> HelperStrings.voiceMichael()
        "am_fenrir" -> HelperStrings.voiceFenrir()
        "am_puck" -> HelperStrings.voicePuck()
        "bf_emma" -> HelperStrings.voiceEmma()
        "af_bella" -> HelperStrings.voiceBella()
        "af_heart" -> HelperStrings.voiceHeart()
        else -> Phrase.plain(name)
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

/** Which way a question to Claude goes. */
enum class AskRoute(private val titleMsg: Msg, private val detailMsg: Msg) {
    APP(HelperStrings.routeApp, HelperStrings.routeAppDetail),
    API(HelperStrings.routeApi, HelperStrings.routeApiDetail);

    /** Its name and what it does, as words without a language yet (1.1: Settings shows them in the menus' language). */
    val titleText: Phrase get() = titleMsg()
    val detailText: Phrase get() = detailMsg()
}

/** When Robot may bring a helper in without being asked (docs/ask-claude.md "When Robot needs help"; every helper since 0.6.0). */
enum class Escalation(private val titleMsg: Msg, private val labelMsg: Msg, private val detailMsg: Msg) {
    OFF(HelperStrings.escalationOff, HelperStrings.escalationOff, HelperStrings.escalationOffDetail),
    ASK(HelperStrings.escalationAsk, HelperStrings.escalationAskFirst, HelperStrings.escalationAskDetail),
    AUTO(HelperStrings.escalationAuto, HelperStrings.escalationAuto, HelperStrings.escalationAutoDetail);

    /** Its name as you'd say it ("Ask me first") and what it does, as words without a language yet (1.1). */
    val titleText: Phrase get() = titleMsg()
    val detailText: Phrase get() = detailMsg()

    /** Its name in Settings' words ("Ask first"), as the app's escalationText writes it. */
    val labelText: Phrase get() = labelMsg()
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
 * Whether the local model is told about the hand-over tool (ask_claude) for one question: the tool and its one line in
 * the system prompt go together, or neither does (docs/ask-claude.md "When Robot needs help"). Pure.
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
enum class Personal(val msg: Msg) {
    SCREEN(HelperStrings.personalScreen),
    PHOTO(HelperStrings.personalPhoto),
    CALENDAR(HelperStrings.personalCalendar),
    NOTES(HelperStrings.personalNotes),
    /** What a lookup found: the phone's contacts and Robot's People (names learned from notifications included). */
    CONTACTS(HelperStrings.personalContacts),
    /** 0.4.0: a weather lookup or the nearest place found online, which say where you are (or where your Home for weather is). */
    LOCATION(HelperStrings.personalLocation),
    /** 0.4.0: phone_status, your phone's storage, battery and memory and its biggest apps. */
    PHONE(HelperStrings.personalPhone),
    /** Claude's answer read off the Claude app's screen. */
    CLAUDE_APP(HelperStrings.personalClaudeApp),
    /** Another read tool's findings (none today besides the three above). */
    READ(HelperStrings.personalRead),
    /**
     * 0.5.0: a transcript from conversation mode, other people's words included. It reaches a helper only through the
     * "Summarize with a stronger AI" card, never through an escalation, a helper's conversation or "include the conversation".
     */
    CONVERSATION(HelperStrings.personalConversation),
    /**
     * 0.7: what Robot remembers about you (core memory/), given to the local model with a turn or saved in it. It reaches a
     * helper only through a card, even a trusted one (HelperTrust), and a secret item never does.
     */
    MEMORY(HelperStrings.personalMemory),
    /**
     * 0.7: messages you received, other people's words. Like a screen or a photo, never sent to a helper on trust alone
     * or by "Send personal content to helpers automatically": only through a card that lists them (core helpers HelperTrust).
     */
    MESSAGES(HelperStrings.personalMessages),
    /** 0.9: what a Termux command or script printed. */
    TERMUX(HelperStrings.personalTermux),
    /**
     * 1.4, Robot Files (files-1.4-plan.md §5.2): what find_files found or read_file answered from a file. Like messages, it
     * reaches a helper only through a card a tap answers: never by "Send personal content to helpers automatically", trust
     * or a spoken yes (core helpers HelperTrust). Medical files and IDs never reach one at all (core files FilesForHelper).
     */
    FILES(HelperStrings.personalFiles),
    ;

    /** What [this] is, in English ("your calendar"); [msg] the words without a language yet (1.1). */
    val label: String get() = msg().en()

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
            ToolName.FIND_FILES, ToolName.READ_FILE -> FILES
            else -> READ
        }

        /** [list] as words without a language yet: « le texte de l'écran, une photo et ton agenda ». */
        fun listText(p: Set<Personal>): Phrase = Phrase.ListOf(entries.filter { it in p }.map { it.msg() })

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
     * The local model handed the question over (ask_claude, [handedOver]) without a word or an action ([said] false): Robot
     * runs the model once more on the same question without ask_claude for its own short answer, with what it has on the
     * phone (Memory's notes, its tools). With an offer, the lock screen or no helper, any offer goes under it: never an
     * answer of nothing but "I'd rather check that with Jarvis.". 1.1, with Automatic too (the user, 19 Oct: "own notes
     * first then helper"): the helper is asked only when that answer is missing or unsure ([afterOwnAnswer]).
     */
    fun ownAnswerFirst(handedOver: Boolean, said: Boolean): Boolean = handedOver && !said

    /**
     * Robot's own answer ([ownAnswerFirst]) is in: [answered] it said or did something, [hedged] its words don't know, can't
     * or won't ([ownAnswerHedged]). With Automatic ([decision] AUTO) the helper is asked only when that answer is missing or
     * hedged; an answer that stands ends the turn (NONE). Every other decision is as it was.
     */
    fun afterOwnAnswer(decision: EscalationDecision, answered: Boolean, hedged: Boolean): EscalationDecision =
        if (decision == EscalationDecision.AUTO && answered && !hedged) EscalationDecision.NONE else decision

    /** Robot's own answer, its [text] and whether it [acted] (a card): hedged as the HEDGE trigger counts a reply ([Hedge.detect]). */
    fun ownAnswerHedged(text: String, acted: Boolean): Boolean = !acted && Hedge.detect(text)
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
