package app.heyrobot.core

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToLong

// Pure. Ask Claude (docs/ask-claude.md): the models and their published prices, how hard Claude thinks and how long it
// may answer, the cost line and the daily budget, the plain-language failures, Claude's voice, which route a question
// takes (the Claude app or the API) and when Robot may bring Claude in by itself. Nothing here touches the network:
// the API client lives in the :claude module, which only the :voice process loads.

/** Prices in US dollars per million tokens, as published (docs/ask-claude.md "Cost line"). */
data class ClaudePrices(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

/**
 * The three models you can pick. Exact model ID strings, never with a date suffix. [fallback]: the request carries
 * the server-side refusal fallback (`fallbacks: "default"`); Claude Haiku 5.5 has none, and a fallback list there is a 400.
 */
enum class ClaudeModel(val id: String, val title: String, val detail: String, private val prices: ClaudePrices, private val longPrices: ClaudePrices?, val fallback: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "The most capable: \$4 per million tokens in, \$20 out.", ClaudePrices(4.0, 20.0, 0.20, 5.0), null, true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable: \$2 in, \$10 out.", ClaudePrices(2.0, 10.0, 0.10, 2.5), null, true),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", "The fastest and cheapest: \$0.10 in, \$0.50 out.", ClaudePrices(0.10, 0.50, 0.01, 0.125), ClaudePrices(0.50, 2.50, 0.05, 0.625), false);

    /** The rate card for a request whose prompt (all input, cached or not) had [promptTokens]. */
    fun prices(promptTokens: Long): ClaudePrices = if (longPrices != null && promptTokens > LONG_PROMPT) longPrices else prices

    companion object {
        val DEFAULT = OPUS
        /** Claude Haiku 5.5 bills prompts over this many tokens on its higher rate card. */
        const val LONG_PROMPT = 100_000L

        fun of(id: String?): ClaudeModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** `output_config.effort`. Always sent explicitly: the API default differs between models. */
enum class ClaudeEffort(val wire: String) { LOW("low"), MEDIUM("medium"), HIGH("high") }

/** The effort setting: automatic, or one level for every question (a setting overrides both automatic rules). */
enum class ClaudeEffortSetting(val title: String, val level: ClaudeEffort?) {
    AUTO("Automatic", null),
    LOW("Low", ClaudeEffort.LOW),
    MEDIUM("Medium", ClaudeEffort.MEDIUM),
    HIGH("High", ClaudeEffort.HIGH),
}

/** How hard Claude thinks and how long it may answer, for one question. */
data class ClaudePlan(val effort: ClaudeEffort, val maxTokens: Long) {
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
        fun of(setting: ClaudeEffortSetting, voice: Boolean, deep: Boolean): ClaudePlan {
            val effort = setting.level ?: when {
                deep -> ClaudeEffort.HIGH
                voice -> ClaudeEffort.LOW
                else -> ClaudeEffort.MEDIUM
            }
            return ClaudePlan(effort, if (voice && !deep && effort != ClaudeEffort.HIGH) VOICE_TOKENS else DEEP_TOKENS)
        }
    }

    /**
     * This question's plan in a Claude-mode conversation whose first question had [first]: the effort stays the same
     * for the whole conversation (a change would restart the prompt cache); the length still follows this question.
     */
    fun keeping(first: ClaudePlan): ClaudePlan =
        ClaudePlan(first.effort, if (first.effort == ClaudeEffort.HIGH) DEEP_TOKENS else maxTokens)
}

/** What one request used, from its `usage` (numbers only). */
data class ClaudeUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val searches: Long = 0) {
    val prompt: Long get() = input + cacheRead + cacheWrite
}

/** One attempt within a request (`usage.iterations`): the model that ran it, if the API said, and its tokens. */
data class ClaudeAttempt(val model: String?, val usage: ClaudeUsage)

object ClaudeCost {
    /** Web search: \$10 per 1,000 searches. */
    const val SEARCH_DOLLARS = 0.01

    /**
     * The server-side fallback's models (Claude Opus 4.8 and Claude Opus 5, chosen by the API): \$5 in, \$25 out, cache
     * reads \$0.50, writes \$6.25. Any model that isn't one of the three Robot asks for is priced like this.
     */
    val FALLBACK_PRICES = ClaudePrices(5.0, 25.0, 0.50, 6.25)

    /** The estimated price of one request, in dollars, at [model]'s rates. */
    fun dollars(model: ClaudeModel, u: ClaudeUsage): Double = tokens(model.prices(u.prompt), u) + u.searches * SEARCH_DOLLARS

    private fun tokens(p: ClaudePrices, u: ClaudeUsage): Double =
        (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite) / 1_000_000.0

    /** The rates of the model that ran a request: one of Robot's three by its ID, any other at the fallback's. */
    fun prices(served: String?, requested: ClaudeModel, promptTokens: Long): ClaudePrices {
        if (served.isNullOrEmpty()) return requested.prices(promptTokens)
        return ClaudeModel.entries.firstOrNull { served == it.id || served.startsWith(it.id + "-") }?.prices(promptTokens) ?: FALLBACK_PRICES
    }

    /**
     * A request's price as the API bills it: each attempt in [attempts] (`usage.iterations`, the per-attempt record when
     * a fallback ran) at the rates of the model that ran it, otherwise [total] at the rates of [served] (the model the
     * response names, which sticky fallback routing can change). Searches at a cent each.
     */
    fun request(requested: ClaudeModel, served: String?, total: ClaudeUsage, attempts: List<ClaudeAttempt> = emptyList()): Double =
        (if (attempts.isEmpty()) tokens(prices(served, requested, total.prompt), total)
        else attempts.sumOf { tokens(prices(it.model ?: served, requested, it.usage.prompt), it.usage) }) + total.searches * SEARCH_DOLLARS

    /** The tokens a request used in all: the attempts' sum when there are attempts, otherwise [total]. */
    fun used(total: ClaudeUsage, attempts: List<ClaudeAttempt>): ClaudeUsage = if (attempts.isEmpty()) total else
        attempts.fold(ClaudeUsage(searches = total.searches)) { a, t ->
            a.copy(input = a.input + t.usage.input, output = a.output + t.usage.output, cacheRead = a.cacheRead + t.usage.cacheRead,
                cacheWrite = a.cacheWrite + t.usage.cacheWrite)
        }

    /** Millionths of a dollar, for the budget file (whole numbers). */
    fun micros(dollars: Double): Long = (dollars * 1_000_000).roundToLong().coerceAtLeast(0)

    /** "≈ 1¢", "< 1¢", "≈ 12¢", "≈ \$1.40": the line under a Claude reply. */
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

/** Today's spend on Claude (the :voice process writes it after each reply; Settings shows it). Pure. */
data class ClaudeSpend(val day: String, val micros: Long) {
    /** The spend for [today]: a new day starts from zero. */
    fun on(today: LocalDate): ClaudeSpend = if (day == today.toString()) this else ClaudeSpend(today.toString(), 0)

    fun add(today: LocalDate, dollars: Double): ClaudeSpend = on(today).let { it.copy(micros = it.micros + ClaudeCost.micros(dollars)) }

    fun reached(today: LocalDate, budgetCents: Int): Boolean = budgetCents >= 0 && on(today).micros >= budgetCents * 10_000L

    fun toJson(): String = org.json.JSONObject().put("day", day).put("micros", micros).toString()

    companion object {
        fun fromJson(text: String?): ClaudeSpend = try {
            val o = org.json.JSONObject(text ?: return ClaudeSpend("", 0))
            ClaudeSpend(o.optString("day"), o.optLong("micros").coerceAtLeast(0))
        } catch (_: Exception) { ClaudeSpend("", 0) }
    }
}

/**
 * Why a Claude request failed, as Robot says it. Mapped from the SDK's typed exceptions (most specific first) in the
 * :claude module; the words are fixed here so they are tested once. [retry]: worth one more try (overloaded or 5xx).
 */
enum class ClaudeFailure(val text: String, val retry: Boolean = false) {
    KEY_REFUSED("Your Claude key was refused. Check it in Robot's settings."),
    NO_CREDITS("Your Anthropic account is out of API credits. Add credits at console.anthropic.com."),
    NOT_ALLOWED("Your Claude key isn't allowed to do that. Check your Anthropic account."),
    MODEL_UNAVAILABLE("That Claude model isn't available to your key. Pick another one in Robot's settings."),
    RATE_LIMITED("Claude is busy, try again in a minute."),
    OVERLOADED("Claude is overloaded right now. Try again in a minute.", retry = true),
    SERVER("Claude isn't answering right now. Try again in a minute.", retry = true),
    BAD_REQUEST("Claude couldn't take that request."),
    TOO_LONG("That was too long for Claude."),
    NO_INTERNET("No internet. If you're online, check that Robot's Network permission is on (Settings › Apps › Robot › Permissions)."),
    NO_NETWORK_PERMISSION("Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings."),
    /** 60 seconds without anything from Claude (a thinking block in progress counts as something). */
    TIMEOUT("Claude took too long to answer."),
    /** Robot's copy of the conversation with Claude no longer made a request the API takes: Claude mode starts over. */
    RESET("Robot lost track of the conversation with Claude, so Claude mode has ended. Ask again to start fresh."),
    NO_KEY("Add your Claude API key in Robot's settings first, or choose the Claude app there."),
    UNKNOWN("Something went wrong with Claude.");

    companion object {
        /**
         * From an HTTP status and the API's error type (`authentication_error`, `overloaded_error`, ...). A stream that
         * failed after it opened carries status 200 and the type only. Null [status] and [type]: no answer arrived.
         */
        fun of(status: Int?, type: String?): ClaudeFailure = when {
            type == "authentication_error" || status == 401 -> KEY_REFUSED
            type == "billing_error" || status == 402 -> NO_CREDITS
            type == "permission_error" || status == 403 -> NOT_ALLOWED
            type == "not_found_error" || status == 404 -> MODEL_UNAVAILABLE
            type == "request_too_large" || status == 413 -> TOO_LONG
            type == "rate_limit_error" || status == 429 -> RATE_LIMITED
            type == "overloaded_error" || status == 529 -> OVERLOADED
            type == "api_error" || type == "timeout_error" || (status != null && status >= 500) -> SERVER
            type == "invalid_request_error" || status == 400 || status == 422 -> BAD_REQUEST
            else -> UNKNOWN
        }
    }
}

/** Robot's own words when Claude stopped with `stop_reason == "refusal"`. */
object ClaudeWords {
    const val DECLINED = "Claude declined that one."
    const val THINKING = "Claude is thinking…"
    const val CHECKING = "Checking with Claude…"
    const val SEARCHED = "Claude searched the web"
    const val OFF = "Ask Claude is off. Turn it on in Robot's settings, under Ask Claude."
    const val LOCKED = "Unlock your phone to ask Claude, or allow Claude on the lock screen in Robot's settings."
    const val JOINED = "Claude is here. Go ahead."
    const val LEFT = "Back to Robot."
    const val NOT_HERE = "Claude isn't in this conversation."
    const val BUDGET = "Today's Claude budget is used up, so Robot will ask before checking with Claude again today."
    /** A reply cut at its length limit before it said anything (its thinking used the room). */
    const val CUT_EMPTY = "Claude ran out of room before it could answer. Ask again, or ask for a shorter answer."
    const val KEY_UNREADABLE = "Robot couldn't read your saved Claude key, so it was removed. Add it again in Robot's settings, under Ask Claude."
    const val SWIPE_TO_READ = "Open the Claude app, then swipe up from the corner and tap “Read Claude's answer”."
    /** The offer under a reply: Robot didn't answer (a hedge, or the pre-router) ... */
    const val OFFER = "Want me to check with Claude?"
    /** ... or under Robot's own answer, after the local model first handed the question over. */
    const val OFFER_AS_WELL = "Want Claude's take as well?"
    /** The local model handed over and, asked again, still gave no answer of its own: the offer stands alone. */
    const val RATHER = "I'd rather check that with Claude."

    /** A spoken yes to an offer that would send personal content [p]: only a tap sends it. */
    fun tapToInclude(p: Set<Personal>): String = "Tap Send to include ${Personal.list(p)}."
}

/**
 * Claude's voice: another voice of the same model as Robot's, so you hear who is talking without a second model
 * load. Kokoro speaks the chosen [KOKORO] voice; Kitten its other voice of the same pair; the phone's voice reads
 * Claude at a lower pitch (no speaker id).
 */
object ClaudeVoices {
    /** The Kokoro v1.0 English voices docs/voice-plan.md lists, with their speaker ids. */
    val KOKORO: List<Pair<String, Int>> = listOf(
        "am_michael" to 16, "am_fenrir" to 14, "am_puck" to 18, "bf_emma" to 21, "af_bella" to 2, "af_heart" to 3,
    )
    const val DEFAULT = "am_michael"
    /** The phone's own voice: Claude's sentences at this pitch (Robot's at 1.0). */
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
     * Claude's voice for one [sentence]: its speaker id and its language. A French sentence gets Kokoro's French voice
     * (Siwis) when Robot speaks with Kokoro (or, if Robot's own voice is Siwis, Claude's voice reading French);
     * everything else Claude's own voice in English. Null speaker: the phone's voice at a lower pitch.
     */
    fun forSentence(robot: VoiceChoice, claude: String?, sentence: String): Pair<Int?, String?> {
        val french = robot.bundle == VoiceBundle.KOKORO && SpeechLang.french(sentence)
        return when {
            !french -> speaker(robot, claude) to null
            robot != VoiceChoice.SIWIS -> VoiceChoice.SIWIS.speaker to "fr"
            else -> speaker(robot, claude) to "fr"
        }
    }

    /**
     * The speaker id Claude's sentences use with Robot's [robot] voice, or null for the phone's voice. Never Robot's
     * own speaker: if you picked Robot's voice for Claude, Claude gets the default instead.
     */
    fun speaker(robot: VoiceChoice, claude: String?): Int? = when (robot.bundle) {
        VoiceBundle.KOKORO -> KOKORO.first { it.first == of(claude) }.second.let { id -> if (id != robot.speaker) id else KOKORO.first { it.second != robot.speaker }.second }
        // Kitten nano 0.8: pairs of voices (2*k male, 2*k+1 female); Claude speaks the other one of Robot's pair.
        VoiceBundle.KITTEN -> robot.speaker xor 1
        else -> null
    }
}

/** The API key's shape, checked before it is saved (pure; the key itself is handled in the :voice process only). */
object ClaudeKeyFormat {
    /**
     * An Anthropic API key: "sk-ant-", then ASCII letters, digits, "-" and "_" only. Anything else (an accented letter,
     * a Cyrillic look-alike, full-width digits) is refused before saving: HTTP headers can't carry it, and the HTTP
     * client's error for a bad header character quotes the whole value.
     */
    fun plausible(k: String): Boolean = k.startsWith("sk-ant-") && k.length in 40..400 &&
        k.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }

    /** "sk-ant-…AB12": shown in Settings. */
    fun mask(k: String): String = "sk-ant-…" + k.takeLast(4)
}

/**
 * A cheap check of a sentence's language, to read Claude's French in a French voice: common French words, elisions
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
        val words = ClaudeRouter.plain(t).split(Regex("[^a-z]+")).filter { it.isNotEmpty() }
        val fr = words.count { it in FRENCH } + ELISION.findAll(t).count() + ACCENT.findAll(t).count() / 2
        val en = words.count { it in ENGLISH }
        return fr >= 2 && fr > en * 2
    }
}

/** Which way a question to Claude goes. */
enum class ClaudeRoute(val title: String, val detail: String) {
    APP("Claude app", "Opens the Claude app with your question; uses your claude.ai plan. Robot doesn't hear the answer."),
    API("Claude API", "Robot asks Claude itself and speaks the answer. Needs an API key with credits."),
}

/** When Robot may bring Claude in without being asked (docs/ask-claude.md "When Robot needs help"). */
enum class Escalation(val title: String, val detail: String) {
    OFF("Off", "Robot never suggests Claude."),
    ASK("Ask me first", "Robot offers “Want me to check with Claude?” and waits for a yes or a tap."),
    AUTO("Automatic", "Robot checks with Claude by itself for hard questions (API key only)."),
}

/** The Ask Claude settings, with the defaults that depend on whether an API key is saved. Pure. */
object ClaudeSettings {
    /**
     * Ask Claude on or off: on unless you turned it off, with or without a key (without one, questions go to the
     * Claude app). [keySaved] is kept for callers that pass it; it no longer changes the answer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun enabled(s: RobotSettings, keySaved: Boolean = false): Boolean = s.claudeEnabled ?: true

    /** "Claude app" without a key, "Claude API" once a key is saved, unless you chose. */
    fun route(s: RobotSettings, keySaved: Boolean): ClaudeRoute = s.claudeRoute ?: if (keySaved) ClaudeRoute.API else ClaudeRoute.APP

    /** Automatic once a key is saved, Ask me first before; never Automatic without a key (the app can't escalate by itself). */
    fun escalation(s: RobotSettings, keySaved: Boolean): Escalation {
        val chosen = s.claudeHelp ?: if (keySaved) Escalation.AUTO else Escalation.ASK
        return if (chosen == Escalation.AUTO && !(keySaved && route(s, keySaved) == ClaudeRoute.API)) Escalation.ASK else chosen
    }

    /** Robot may describe ask_claude to the local model: Ask Claude is on and escalation isn't Off. */
    fun localTool(s: RobotSettings, keySaved: Boolean): Boolean = enabled(s, keySaved) && escalation(s, keySaved) != Escalation.OFF
}

/** Why ask_claude is left out of a question for the local model (logged as this code only, never the words). */
enum class ClaudeLeftOut(override val id: Int, override val title: String) : LogCode {
    OFF(1, "Ask Claude or “When Robot needs help” is off"),
    NOWHERE(2, "no API key and no Claude app"),
    DECLINED(3, "you said not Claude in this conversation"),
    SCREEN(4, "a question about the screen: Robot answers it first"),
    PHOTO(5, "a question about a photo: Robot answers it first"),
}

/**
 * Whether the local model is told about ask_claude for one question: the tool and its one line in the system prompt go
 * together, or neither does (docs/ask-claude.md "When Robot needs help"). Pure.
 */
object AskClaudeTool {
    /**
     * Null when ask_claude is described, otherwise why not, first reason first: Ask Claude off or escalation Off
     * ([ClaudeSettings.localTool]); nowhere for the question to go ([reachable]: the API with a key, or the Claude app
     * installed); you said "not Claude" in this conversation ([declined]); or the question is about the screen or a photo
     * ([attachment], see [picture]): Robot answers those itself first, and "ask Claude about this screen" still goes to
     * Claude.
     */
    fun leftOut(s: RobotSettings, keySaved: Boolean, reachable: Boolean, declined: Boolean, attachment: Attachment): ClaudeLeftOut? = when {
        !ClaudeSettings.localTool(s, keySaved) -> ClaudeLeftOut.OFF
        !reachable -> ClaudeLeftOut.NOWHERE
        declined -> ClaudeLeftOut.DECLINED
        attachment == Attachment.SCREEN -> ClaudeLeftOut.SCREEN
        attachment == Attachment.PHOTO -> ClaudeLeftOut.PHOTO
        else -> null
    }

    fun offered(s: RobotSettings, keySaved: Boolean, reachable: Boolean, declined: Boolean, attachment: Attachment): Boolean =
        leftOut(s, keySaved, reachable, declined, attachment) == null

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
 * Personal content in a conversation (docs/ask-claude.md "What Claude receives"). From the first turn that carries one,
 * the conversation is personal: Robot checking with Claude by itself sends only the question, and Claude joining it
 * sees what came before only after a tap on Include. [label] completes "It contains …".
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
     * 0.5.0: a transcript from conversation mode, other people's words included. It reaches Claude only through the
     * "Summarize with a stronger AI" card, never through an escalation, Claude mode or "include the conversation".
     */
    CONVERSATION("a recorded conversation"),
    ;

    companion object {
        fun of(a: Attachment): Personal? = when (a) {
            Attachment.SCREEN -> SCREEN
            Attachment.PHOTO -> PHOTO
            Attachment.NONE -> null
        }

        /** What a read tool's findings were. */
        fun of(tool: ToolName): Personal = when (tool) {
            ToolName.READ_CALENDAR -> CALENDAR
            ToolName.FIND_NOTES -> NOTES
            ToolName.LOOKUP_CONTACT -> CONTACTS
            // The weather, and (0.4.0) the nearest place found online: both say where you are.
            ToolName.WEATHER, ToolName.MAPS_SEARCH, ToolName.MAPS_NAVIGATE -> LOCATION
            ToolName.PHONE_STATUS, ToolName.FREE_MEMORY, ToolName.FREE_STORAGE -> PHONE
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
    /** Robot doesn't bring Claude in. */
    NONE,
    /** Ask Claude now, without a tap (API key, Automatic, nothing personal, within budget, allowed here). */
    AUTO,
    /** Offer it: a card under the reply ("Want me to check with Claude?" or "Open Claude with: «…»"), a tap or a yes. */
    OFFER,
    /** The phone is locked and Claude isn't allowed on the lock screen. */
    NEEDS_UNLOCK,
}

/** Why Robot thought of Claude (logged as this enum only, never the words). */
enum class EscalationTrigger { PRE_ROUTER, LOCAL_TOOL, HEDGE }

/** The escalation policy: pure, so its whole matrix is tested (EscalationTest). */
object EscalationPolicy {
    /**
     * [personal]: what would be sent includes this question's screen text or picture: always asks first, with a card
     * listing it (earlier personal turns are never sent by an escalation, so they don't need one). [budgetReached]:
     * Automatic drops to Ask first for the rest of the day. [alreadyEscalated]: at most one escalation per turn.
     * [fromClaude]: a Claude answer is never re-escalated. [declined]: you said "not Claude" in this conversation, so
     * Robot neither asks Claude nor offers to.
     */
    fun decide(
        s: RobotSettings,
        keySaved: Boolean,
        personal: Boolean,
        locked: Boolean,
        budgetReached: Boolean,
        alreadyEscalated: Boolean = false,
        fromClaude: Boolean = false,
        declined: Boolean = false,
    ): EscalationDecision {
        if (fromClaude || alreadyEscalated || declined || !ClaudeSettings.enabled(s, keySaved)) return EscalationDecision.NONE
        val mode = ClaudeSettings.escalation(s, keySaved)
        if (mode == Escalation.OFF) return EscalationDecision.NONE
        val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
        // The app needs the phone unlocked, always; the API only when Claude isn't allowed on the lock screen.
        if (locked && (!api || !s.claudeLocked)) return EscalationDecision.NEEDS_UNLOCK
        return if (api && mode == Escalation.AUTO && !personal && !budgetReached) EscalationDecision.AUTO else EscalationDecision.OFFER
    }

    /**
     * The local model handed the question over (ask_claude, [handedOver]) without a word or an action ([said] false), and
     * Claude won't answer right now ([decision] isn't AUTO: an offer, the lock screen, or no Claude at all): Robot runs
     * the model once more on the same question without ask_claude for its own short answer, and any offer goes under
     * it. Never an answer of nothing but "I'd rather check that with Claude.".
     */
    fun ownAnswerFirst(decision: EscalationDecision, handedOver: Boolean, said: Boolean): Boolean =
        handedOver && !said && decision != EscalationDecision.AUTO
}

/** At most one escalation per turn, and never one from a Claude answer (the "no loops" rule). */
class EscalationGuard {
    var used = false; private set

    /** True the first time only; false for a Claude answer. */
    fun take(fromClaude: Boolean): Boolean {
        if (fromClaude || used) return false
        used = true
        return true
    }
}
