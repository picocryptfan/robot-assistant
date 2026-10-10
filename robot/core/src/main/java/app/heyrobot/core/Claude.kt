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
 * The three models the owner can pick. Exact model ID strings, never with a date suffix. [fallback]: the request carries
 * the server-side refusal fallback (`fallbacks: "default"`); Claude Haiku 5.5 has none, and a fallback list there is a 400.
 */
enum class ClaudeModel(val id: String, val title: String, val detail: String, private val prices: ClaudePrices, private val longPrices: ClaudePrices?, val fallback: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "The most capable: \$4 per million tokens in, \$20 out.", ClaudePrices(4.0, 20.0, 0.20, 5.0), null, true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable: \$2 in, \$10 out.", ClaudePrices(2.0, 10.0, 0.20, 2.5), null, true),
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
        /** Typed, or the owner asked for depth. */
        const val DEEP_TOKENS = 16_000L

        /**
         * Automatic: low for a voice question, medium for a typed one, high when the owner asked for depth ("think hard
         * about", "take your time"). A fixed setting wins over all three.
         */
        fun of(setting: ClaudeEffortSetting, voice: Boolean, deep: Boolean): ClaudePlan = ClaudePlan(
            effort = setting.level ?: when {
                deep -> ClaudeEffort.HIGH
                voice -> ClaudeEffort.LOW
                else -> ClaudeEffort.MEDIUM
            },
            maxTokens = if (voice && !deep) VOICE_TOKENS else DEEP_TOKENS,
        )
    }
}

/** What one request used, from its `usage` (numbers only). */
data class ClaudeUsage(val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0, val searches: Long = 0) {
    val prompt: Long get() = input + cacheRead + cacheWrite
}

object ClaudeCost {
    /** Web search: \$10 per 1,000 searches. */
    const val SEARCH_DOLLARS = 0.01

    /** The estimated price of one request, in dollars. */
    fun dollars(model: ClaudeModel, u: ClaudeUsage): Double {
        val p = model.prices(u.prompt)
        return (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite) / 1_000_000.0 +
            u.searches * SEARCH_DOLLARS
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
    NO_INTERNET("No internet."),
    NO_NETWORK_PERMISSION("Robot isn't allowed to use the network. Turn on Network for Robot in Android's app settings."),
    TIMEOUT("Claude took too long to start answering."),
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
    const val SWIPE_TO_READ = "Open the Claude app, then swipe up from the corner and tap “Read Claude's answer”."
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
     * The speaker id Claude's sentences use with Robot's [robot] voice, or null for the phone's voice. Never Robot's
     * own speaker: if the owner picked Robot's voice for Claude, Claude gets the default instead.
     */
    fun speaker(robot: VoiceChoice, claude: String?): Int? = when (robot.bundle) {
        VoiceBundle.KOKORO -> KOKORO.first { it.first == of(claude) }.second.let { id -> if (id != robot.speaker) id else KOKORO.first { it.second != robot.speaker }.second }
        // Kitten nano 0.8: pairs of voices (2*k male, 2*k+1 female); Claude speaks the other one of Robot's pair.
        VoiceBundle.KITTEN -> robot.speaker xor 1
        else -> null
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

/** The owner's Ask Claude settings with the defaults that depend on whether an API key is saved. Pure. */
object ClaudeSettings {
    /** Ask Claude on or off: Off until a key is saved, unless the owner chose. */
    fun enabled(s: RobotSettings, keySaved: Boolean): Boolean = s.claudeEnabled ?: keySaved

    /** "Claude app" without a key, "Claude API" once a key is saved, unless the owner chose. */
    fun route(s: RobotSettings, keySaved: Boolean): ClaudeRoute = s.claudeRoute ?: if (keySaved) ClaudeRoute.API else ClaudeRoute.APP

    /** Automatic once a key is saved, Ask me first before; never Automatic without a key (the app can't escalate by itself). */
    fun escalation(s: RobotSettings, keySaved: Boolean): Escalation {
        val chosen = s.claudeHelp ?: if (keySaved) Escalation.AUTO else Escalation.ASK
        return if (chosen == Escalation.AUTO && !(keySaved && route(s, keySaved) == ClaudeRoute.API)) Escalation.ASK else chosen
    }

    /** Robot may describe ask_claude to the local model: Ask Claude is on and escalation isn't Off. */
    fun localTool(s: RobotSettings, keySaved: Boolean): Boolean = enabled(s, keySaved) && escalation(s, keySaved) != Escalation.OFF
}

/** What the turn that would go to Claude carries besides plain conversation text. */
enum class Personal(val label: String) {
    SCREEN("Your screen's text"),
    PHOTO("The photo"),
    READ("What Robot read from your contacts, calendar or notes"),
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
     * [personal]: the turn carries screen text, a picture or a read tool's findings: always asks first, with a card
     * listing what would be sent. [budgetReached]: Automatic drops to Ask first for the rest of the day.
     * [alreadyEscalated]: at most one escalation per turn. [fromClaude]: a Claude answer is never re-escalated.
     */
    fun decide(
        s: RobotSettings,
        keySaved: Boolean,
        personal: Boolean,
        locked: Boolean,
        budgetReached: Boolean,
        alreadyEscalated: Boolean = false,
        fromClaude: Boolean = false,
    ): EscalationDecision {
        if (fromClaude || alreadyEscalated || !ClaudeSettings.enabled(s, keySaved)) return EscalationDecision.NONE
        val mode = ClaudeSettings.escalation(s, keySaved)
        if (mode == Escalation.OFF) return EscalationDecision.NONE
        val api = keySaved && ClaudeSettings.route(s, keySaved) == ClaudeRoute.API
        // The app needs the phone unlocked, always; the API only when Claude isn't allowed on the lock screen.
        if (locked && (!api || !s.claudeLocked)) return EscalationDecision.NEEDS_UNLOCK
        return if (api && mode == Escalation.AUTO && !personal && !budgetReached) EscalationDecision.AUTO else EscalationDecision.OFFER
    }
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
