package app.heyrobot.core

import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSettings
import app.heyrobot.core.helpers.HelperWho
import app.heyrobot.core.helpers.HelperWords
import java.time.LocalDate

// Pure. The Anthropic API (docs/ask-claude.md): Claude's models and their published prices, a request's price as the API
// bills it, and the shape of an Anthropic API key. The client is in the :helpers module (app.heyrobot.helpers.anthropic),
// which only the :voice process loads.

/** Prices in US dollars per million tokens, as published (docs/ask-claude.md "Cost line"). */
data class AnthropicPrices(val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

/**
 * The models you can pick. Exact model ID strings, never with a date suffix. [fallback]: the request carries the
 * server-side refusal fallback (`fallbacks: "default"`); Claude Haiku 5.5 has none, and a fallback list there is a 400.
 * Prices as published (https://platform.claude.com/docs/en/about-claude/pricing.md, read 2026-10-11): cache reads are
 * 0.05x input on Opus 5.5 and Sonnet 5.5, 0.025x on Fable 5.1, 0.1x on Haiku 5.5; 5-minute cache writes 1.25x.
 */
enum class AnthropicModel(val id: String, val title: String, val detail: String, private val prices: AnthropicPrices, private val longPrices: AnthropicPrices?, val fallback: Boolean) {
    OPUS("claude-opus-5-5", "Claude Opus 5.5", "Recommended for most questions: \$4 per million tokens in, \$20 out.", AnthropicPrices(4.0, 20.0, 0.20, 5.0), null, true),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", "Fast and capable: \$2 in, \$10 out.", AnthropicPrices(2.0, 10.0, 0.10, 2.5), null, true),
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5", "The fastest and cheapest: \$0.10 in, \$0.50 out.", AnthropicPrices(0.10, 0.50, 0.01, 0.125), AnthropicPrices(0.50, 2.50, 0.05, 0.625), false),
    /** 0.6.0: for the hardest questions; slower, and its thinking is always on (as on Opus 5.5). */
    FABLE("claude-fable-5-1", "Claude Fable 5.1", "For the hardest questions, slower: \$10 in, \$50 out.", AnthropicPrices(10.0, 50.0, 0.25, 12.50), null, true);

    /** The rate card for a request whose prompt (all input, cached or not) had [promptTokens]. */
    fun prices(promptTokens: Long): AnthropicPrices = if (longPrices != null && promptTokens > LONG_PROMPT) longPrices else prices

    companion object {
        val DEFAULT = OPUS
        /** Claude Haiku 5.5 bills prompts over this many tokens on its higher rate card. */
        const val LONG_PROMPT = 100_000L

        fun of(id: String?): AnthropicModel = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** One attempt within a request (`usage.iterations`): the model that ran it, if the API said, and its tokens. */
data class AnthropicAttempt(val model: String?, val usage: TokenUsage)

object AnthropicCost {
    /** Web search: \$10 per 1,000 searches. */
    const val SEARCH_DOLLARS = 0.01

    /**
     * The server-side fallback's models (Claude Opus 4.8 and Claude Opus 5, chosen by the API): \$5 in, \$25 out, cache
     * reads \$0.50, writes \$6.25. Any model that isn't one of the three Robot asks for is priced like this.
     */
    val FALLBACK_PRICES = AnthropicPrices(5.0, 25.0, 0.50, 6.25)

    /** The estimated price of one request, in dollars, at [model]'s rates. */
    fun dollars(model: AnthropicModel, u: TokenUsage): Double = tokens(model.prices(u.prompt), u) + u.searches * SEARCH_DOLLARS

    private fun tokens(p: AnthropicPrices, u: TokenUsage): Double =
        (u.input * p.input + u.output * p.output + u.cacheRead * p.cacheRead + u.cacheWrite * p.cacheWrite) / 1_000_000.0

    /** The rates of the model that ran a request: one of Robot's three by its ID, any other at the fallback's. */
    fun prices(served: String?, requested: AnthropicModel, promptTokens: Long): AnthropicPrices {
        if (served.isNullOrEmpty()) return requested.prices(promptTokens)
        return AnthropicModel.entries.firstOrNull { served == it.id || served.startsWith(it.id + "-") }?.prices(promptTokens) ?: FALLBACK_PRICES
    }

    /**
     * A request's price as the API bills it: each attempt in [attempts] (`usage.iterations`, the per-attempt record when
     * a fallback ran) at the rates of the model that ran it, otherwise [total] at the rates of [served] (the model the
     * response names, which sticky fallback routing can change). Searches at a cent each.
     */
    fun request(requested: AnthropicModel, served: String?, total: TokenUsage, attempts: List<AnthropicAttempt> = emptyList()): Double =
        (if (attempts.isEmpty()) tokens(prices(served, requested, total.prompt), total)
        else attempts.sumOf { tokens(prices(it.model ?: served, requested, it.usage.prompt), it.usage) }) + total.searches * SEARCH_DOLLARS

    /** The tokens a request used in all: the attempts' sum when there are attempts, otherwise [total]. */
    fun used(total: TokenUsage, attempts: List<AnthropicAttempt>): TokenUsage = if (attempts.isEmpty()) total else
        attempts.fold(TokenUsage(searches = total.searches)) { a, t ->
            a.copy(input = a.input + t.usage.input, output = a.output + t.usage.output, cacheRead = a.cacheRead + t.usage.cacheRead,
                cacheWrite = a.cacheWrite + t.usage.cacheWrite)
        }
}

/** The API key's shape, checked before it is saved (pure; the key itself is handled in the :voice process only). */
object AnthropicKeyFormat {
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

// The Claude helper as Robot 0.3 to 0.5 had it (the Claude preset since 0.6.0): its words, its settings, and the format
// of those versions' budget file, claude_spend.json, which Robot still reads and writes (sys HelperSpendStore).

/** Claude's spend for one day in claude_spend.json, the 0.4/0.5 format (stored name and keys kept; HelperSpend since 0.6.0). Pure. */
data class ClaudeSpend(val day: String, val micros: Long) {
    /** The spend for [today]: a new day starts from zero. */
    fun on(today: LocalDate): ClaudeSpend = if (day == today.toString()) this else ClaudeSpend(today.toString(), 0)

    fun add(today: LocalDate, dollars: Double): ClaudeSpend = on(today).let { it.copy(micros = it.micros + CostText.micros(dollars)) }

    fun reached(today: LocalDate, budgetCents: Int): Boolean = budgetCents >= 0 && on(today).micros >= budgetCents * 10_000L

    fun toJson(): String = org.json.JSONObject().put("day", day).put("micros", micros).toString()

    companion object {
        fun fromJson(text: String?): ClaudeSpend = try {
            val o = org.json.JSONObject(text ?: return ClaudeSpend("", 0))
            ClaudeSpend(o.optString("day"), o.optLong("micros").coerceAtLeast(0))
        } catch (_: Exception) { ClaudeSpend("", 0) }
    }
}

/** Robot's own words about Claude (each helper's are [HelperWords]). */
object ClaudeWords {
    private val W = HelperWords(HelperWho.CLAUDE)
    val DECLINED = W.declined
    val THINKING = W.thinking
    val CHECKING = W.checking
    val SEARCHED = W.searched
    val OFF = W.off
    val LOCKED = W.locked
    val JOINED = W.joined
    const val LEFT = "Back to Robot."
    val NOT_HERE = W.notHere
    val BUDGET = W.budget
    /** A reply cut at its length limit before it said anything (its thinking used the room). */
    val CUT_EMPTY = W.cutEmpty
    val KEY_UNREADABLE = W.keyUnreadable
    /** The saved key couldn't be read just now (a passing Keystore error): it is kept. */
    val KEY_UNAVAILABLE = W.keyUnavailable
    const val SWIPE_TO_READ = "Open the Claude app, then swipe up from the corner and tap “Read Claude's answer”."
    /** The offer under a reply: Robot didn't answer (a hedge, or the pre-router) ... */
    val OFFER = W.offer
    /** ... or under Robot's own answer, after the local model first handed the question over. */
    val OFFER_AS_WELL = W.offerAsWell
    /** The local model handed over and, asked again, still gave no answer of its own: the offer stands alone. */
    val RATHER = W.rather

    /** A spoken yes to an offer that would send personal content [p]: only a tap sends it. */
    fun tapToInclude(p: Set<Personal>): String = W.tapToInclude(p)
}

/**
 * The Ask Claude settings, with the defaults that depend on whether an API key is saved: since 0.6.0 the Claude helper's
 * ([RobotSettings.claude]) under [HelperSettings], the same rules for every helper. Pure.
 */
object ClaudeSettings {
    private fun claude(s: RobotSettings) = s.claude ?: HelperConfig.claude().copy(enabled = false)

    /**
     * Ask Claude on or off: on unless you turned it off (or removed Claude), with or without a key (without one,
     * questions go to the Claude app). [keySaved] is kept for callers that pass it; it doesn't change the answer.
     */
    @Suppress("UNUSED_PARAMETER")
    fun enabled(s: RobotSettings, keySaved: Boolean = false): Boolean = s.claude?.on ?: false

    /** "Claude app" without a key, "Claude API" once a key is saved, unless you chose. */
    fun route(s: RobotSettings, keySaved: Boolean): AskRoute = HelperSettings.route(claude(s), keySaved)

    /** Automatic once a key is saved, Ask me first before; never Automatic without a key (the app can't escalate by itself). */
    fun escalation(s: RobotSettings, keySaved: Boolean): Escalation = HelperSettings.escalation(s, claude(s), keySaved)

    /** Robot may describe ask_claude to the local model: Ask Claude is on and escalation isn't Off. */
    fun localTool(s: RobotSettings, keySaved: Boolean): Boolean = enabled(s, keySaved) && escalation(s, keySaved) != Escalation.OFF
}
