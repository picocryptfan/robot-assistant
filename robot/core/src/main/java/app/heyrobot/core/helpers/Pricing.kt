package app.heyrobot.core.helpers

import app.heyrobot.core.AnthropicCost
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.CostText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.roundToLong
import org.json.JSONObject

// Pure. What a helper's reply costs (docs/ask-claude.md "Cost", 0.6.0 for every helper). Prices are never guessed: a
// model's price comes, in this order, from what the provider reported for the request itself (OpenRouter's usage.cost),
// the prices you entered for the helper, or the shipped table ([PriceTable], each entry with the page and the day it was
// read). Anything else is "cost unknown", and Robot then never asks that helper by itself (Automatic needs a known price).
// A home server is free unless you enter a price (electricity, a rented GPU).

/** US dollars per million tokens. [cacheRead]: a cache hit; [cacheWrite]: tokens written to the cache. */
data class Rates(val input: Double, val output: Double, val cacheRead: Double = input, val cacheWrite: Double = input) {
    fun toJson(): JSONObject = JSONObject().put("input", input).put("output", output).put("cacheRead", cacheRead).put("cacheWrite", cacheWrite)

    /** All four are numbers a person could have meant (not negative, not absurd). */
    val sane: Boolean get() = listOf(input, output, cacheRead, cacheWrite).all { it.isFinite() && it >= 0 && it <= MAX }

    companion object {
        /** No real model costs more than this per million tokens; a typo of an extra zero or two still fits. */
        const val MAX = 10_000.0

        fun fromJson(o: JSONObject?): Rates? {
            o ?: return null
            val input = o.optDouble("input", Double.NaN)
            val output = o.optDouble("output", Double.NaN)
            if (input.isNaN() || output.isNaN()) return null
            val r = Rates(input, output, o.optDouble("cacheRead", input), o.optDouble("cacheWrite", input))
            return r.takeIf { it.sane }
        }
    }
}

/** Hours of the week in UTC: [days], from [fromHour] (inclusive) to [toHour] (exclusive), 0 to 24. */
data class UtcWindow(val days: Set<DayOfWeek>, val fromHour: Int, val toHour: Int) {
    fun contains(t: Instant): Boolean {
        val u = t.atZone(ZoneOffset.UTC)
        return u.dayOfWeek in days && u.hour >= fromHour && u.hour < toHour
    }

    companion object {
        val WEEKDAYS = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY)
    }
}

/**
 * One model's published prices. [rates] apply unless: the prompt (every input token, cached or not) is at least
 * [longFrom] tokens ([long] then prices every token of the request, as OpenAI, xAI, Google and Anthropic bill it); the
 * request starts in one of [peakHours] ([peak]); or the day (UTC) is after [until], when [then] takes over (a dated price
 * change, such as a promotion ending). [perSearch]: dollars per web search (null: the provider's search price isn't
 * known, so a reply that searched has an unknown cost). [asOf] and [source]: when and where the prices were read.
 */
data class PriceCard(
    val rates: Rates,
    val long: Rates? = null,
    val longFrom: Long = Long.MAX_VALUE,
    val peak: Rates? = null,
    val peakHours: List<UtcWindow> = emptyList(),
    val until: LocalDate? = null,
    val then: PriceCard? = null,
    val perSearch: Double? = null,
    val asOf: String = "",
    val source: String = "",
) {
    /** The card in force on [at]'s day (UTC). */
    fun current(at: Instant): PriceCard {
        val day = at.atZone(ZoneOffset.UTC).toLocalDate()
        return if (until != null && then != null && day.isAfter(until)) then.current(at) else this
    }

    /** The rates for one request whose prompt had [promptTokens], started at [at]. */
    fun rates(promptTokens: Long, at: Instant): Rates {
        val c = current(at)
        if (c.long != null && promptTokens >= c.longFrom) return c.long
        if (c.peak != null && c.peakHours.any { it.contains(at) }) return c.peak
        return c.rates
    }

    /** "Prices as of 11 Oct 2026 from platform.openai.com". */
    val line: String get() = listOfNotNull(asOf.takeIf { it.isNotEmpty() }?.let { "as of $it" }, source.takeIf { it.isNotEmpty() }?.let { "from $it" }).joinToString(" ")
}

/**
 * What one request used, normalized for every wire format: [input] is the uncached input only (the Anthropic way:
 * OpenAI's prompt_tokens include the cached ones, the wire mappers take them out), [cacheRead] cache hits, [cacheWrite]
 * tokens written to a cache, [searches] web searches. [reported]: what the provider said the request cost, in dollars
 * (OpenRouter's usage.cost); it wins over any estimate.
 */
data class HelperUsage(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
    val searches: Long = 0,
    val reported: Double? = null,
) {
    val prompt: Long get() = input + cacheRead + cacheWrite

    operator fun plus(o: HelperUsage) = HelperUsage(input + o.input, output + o.output, cacheRead + o.cacheRead, cacheWrite + o.cacheWrite,
        searches + o.searches, if (reported == null && o.reported == null) null else (reported ?: 0.0) + (o.reported ?: 0.0))
}

/** Where a helper's price comes from, as Settings shows it and the cost line uses it. */
sealed class HelperPrice {
    /** A home server without a price you entered: nothing is counted. */
    data object Free : HelperPrice()

    /** Your prices for this helper's model ([HelperConfig.prices]). */
    data class Yours(val rates: Rates) : HelperPrice()

    /** The shipped table ([PriceTable]). */
    data class Published(val card: PriceCard) : HelperPrice()

    /** The provider reports each request's cost (OpenRouter); [card] for an estimate when a request ends without it. */
    data class Reported(val card: PriceCard? = null) : HelperPrice()

    /** Nothing Robot can trust: the cost line says "cost unknown", and Automatic is off for this helper. */
    data object Unknown : HelperPrice()

    /** Robot can tell what a reply costs (Automatic escalation needs it). */
    val known: Boolean get() = this !is Unknown
}

object Pricing {
    /**
     * The price of one request in dollars, or null when it can't be known: [price] is unknown, or the request searched the
     * web and the search price isn't known. A reported cost always wins.
     */
    fun dollars(price: HelperPrice, u: HelperUsage, at: Instant): Double? {
        u.reported?.let { return it.coerceAtLeast(0.0) }
        return when (price) {
            HelperPrice.Free -> 0.0
            is HelperPrice.Yours -> tokens(price.rates, u)
            is HelperPrice.Published -> card(price.card, u, at)
            is HelperPrice.Reported -> price.card?.let { card(it, u, at) }
            HelperPrice.Unknown -> null
        }
    }

    private fun card(c: PriceCard, u: HelperUsage, at: Instant): Double? {
        val searches = if (u.searches == 0L) 0.0 else c.current(at).perSearch?.let { it * u.searches } ?: return null
        return tokens(c.rates(u.prompt, at), u) + searches
    }

    fun tokens(r: Rates, u: HelperUsage): Double =
        (u.input * r.input + u.output * r.output + u.cacheRead * r.cacheRead + u.cacheWrite * r.cacheWrite) / 1_000_000.0

    /** Millionths of a dollar, for the spend file (whole numbers, never negative). */
    fun micros(dollars: Double): Long = (dollars * 1_000_000).roundToLong().coerceAtLeast(0)

    /** "≈ 1¢" for a known cost, "cost unknown" for one that isn't, nothing for free (a home server). */
    fun line(dollars: Double?, free: Boolean = false): String? = when {
        dollars == null -> "cost unknown"
        free && dollars == 0.0 -> null
        else -> CostText.line(dollars)
    }
}

/**
 * The prices Robot ships, each read from the provider's own pricing page on the day given (never guessed). A model that
 * isn't here has no price until you enter one. Sources, read 2026-10-11:
 * - Anthropic: https://platform.claude.com/docs/en/about-claude/pricing.md ([AnthropicModel] holds them; web search $10 per 1,000).
 * - OpenAI: https://developers.openai.com/api/docs/pricing.md (Standard; long context over 272K input tokens; web search $10 per 1,000 calls).
 * - Google: https://ai.google.dev/gemini-api/docs/pricing (paid tier; Gemini 3.8 Flash's price through 31 Dec 2026 and from 1 Jan 2027).
 * - DeepSeek: https://api-docs.deepseek.com/quick_start/pricing (off-peak; peak is double, 01:00-04:00 and 06:00-10:00 UTC Monday to
 *   Friday. Chinese public holidays are off-peak there too; Robot can't tell them, so it counts those hours as peak).
 * - Groq: https://console.groq.com/docs/models.md and each model's page (browser search's price isn't published there, so Robot
 *   doesn't offer it).
 * - xAI: https://docs.x.ai/developers/pricing.md (from 200k prompt tokens the higher row; web search $5 per 1,000).
 * - Mistral's per-model prices weren't readable (the page renders them in the browser), so none ship. OpenRouter reports
 *   each request's cost itself.
 */
object PriceTable {
    const val AS_OF = "11 Oct 2026"

    private const val OPENAI = "developers.openai.com/api/docs/pricing"
    private const val GOOGLE = "ai.google.dev/gemini-api/docs/pricing"
    private const val DEEPSEEK = "api-docs.deepseek.com/quick_start/pricing"
    private const val GROQ = "console.groq.com/docs/models"
    private const val XAI = "docs.x.ai/developers/pricing"
    private const val ANTHROPIC = "platform.claude.com/docs/en/about-claude/pricing"

    /** OpenAI bills a prompt over 272K input tokens on its long-context rates. */
    private const val OPENAI_LONG = 272_001L
    private val DEEPSEEK_PEAK = listOf(UtcWindow(UtcWindow.WEEKDAYS, 1, 4), UtcWindow(UtcWindow.WEEKDAYS, 6, 10))

    private fun openai(base: Rates, long: Rates) = PriceCard(base, long, OPENAI_LONG, perSearch = 0.01, asOf = AS_OF, source = OPENAI)
    private fun deepseek(offPeak: Rates) = PriceCard(offPeak, peak = Rates(offPeak.input * 2, offPeak.output * 2, offPeak.cacheRead * 2, offPeak.cacheWrite * 2),
        peakHours = DEEPSEEK_PEAK, asOf = AS_OF, source = DEEPSEEK)

    /** By model id. Cache writes: OpenAI's own column; elsewhere the provider prices no write, so it is input. */
    val SHIPPED: Map<String, PriceCard> = buildMap {
        for (m in AnthropicModel.entries) put(m.id, claude(m))
        put("gpt-6-astra", openai(Rates(10.0, 50.0, 1.00, 12.50), Rates(20.0, 75.0, 2.00, 25.0)))
        put("gpt-6.1-sol", openai(Rates(2.0, 10.0, 0.10, 2.50), Rates(4.0, 15.0, 0.20, 5.0)))
        put("gpt-6-luna", openai(Rates(0.10, 0.50, 0.01, 0.125), Rates(0.20, 0.75, 0.02, 0.25)))
        // Google's paid tier: the promotional price through 31 December 2026, then double.
        put("gemini-3.8-flash", PriceCard(Rates(0.75, 3.75, 0.075, 0.75), until = LocalDate.of(2026, 12, 31),
            then = PriceCard(Rates(1.50, 7.50, 0.15, 1.50), asOf = AS_OF, source = GOOGLE), asOf = AS_OF, source = GOOGLE))
        put("gemini-3.5-flash-lite", PriceCard(Rates(0.30, 2.50, 0.03, 0.30), asOf = AS_OF, source = GOOGLE))
        put("gemini-3.1-pro-preview", PriceCard(Rates(2.0, 12.0, 0.20, 2.0), Rates(4.0, 18.0, 0.40, 4.0), 200_001, asOf = AS_OF, source = GOOGLE))
        put("deepseek-flash", deepseek(Rates(0.15, 0.60, 0.003, 0.15)))
        put("deepseek-v4-pro", deepseek(Rates(0.66, 1.98, 0.022, 0.66)))
        put("openai/gpt-oss-120b", PriceCard(Rates(0.15, 0.60, 0.075, 0.15), asOf = AS_OF, source = GROQ))
        put("openai/gpt-oss-20b", PriceCard(Rates(0.075, 0.30, 0.037, 0.075), asOf = AS_OF, source = GROQ))
        // No cached-input price on its page: a cache hit is counted at the full input price (never less than billed).
        put("qwen/qwen3.8-27b", PriceCard(Rates(0.80, 4.00, 0.80, 0.80), asOf = AS_OF, source = GROQ))
        put("grok-4.7", PriceCard(Rates(2.0, 6.0, 0.50, 2.0), Rates(4.0, 12.0, 1.00, 4.0), 200_000, perSearch = 0.005, asOf = AS_OF, source = XAI))
    }

    /** Claude's three (and Fable's) prices as a card, from [AnthropicModel] (the one place they live). */
    fun claude(m: AnthropicModel): PriceCard {
        val p = m.prices(0)
        val l = m.prices(Long.MAX_VALUE)
        val long = if (l != p) Rates(l.input, l.output, l.cacheRead, l.cacheWrite) else null
        return PriceCard(Rates(p.input, p.output, p.cacheRead, p.cacheWrite), long, if (long != null) AnthropicModel.LONG_PROMPT + 1 else Long.MAX_VALUE,
            perSearch = AnthropicCost.SEARCH_DOLLARS, asOf = AS_OF, source = ANTHROPIC)
    }

    fun of(model: String): PriceCard? = SHIPPED[model]
}

/**
 * Today's spend on helpers (the :voice process writes it after each reply; Settings shows it): the total and each
 * helper's part, in millionths of a dollar. A new day starts from zero. [reached]: the daily budget for all helpers, or
 * one helper's own cap.
 */
data class HelperSpend(val day: String, val total: Long = 0, val by: Map<String, Long> = emptyMap()) {
    fun on(today: LocalDate): HelperSpend = if (day == today.toString()) this else HelperSpend(today.toString())

    fun add(today: LocalDate, helper: String, dollars: Double): HelperSpend {
        val d = on(today)
        val m = Pricing.micros(dollars)
        return d.copy(total = d.total + m, by = d.by + (helper to (d.by[helper] ?: 0L) + m))
    }

    fun of(today: LocalDate, helper: String): Long = on(today).by[helper] ?: 0L

    /** All helpers' budget ([budgetCents]; negative: none) or [helper]'s own cap ([capCents], null: none) is used up. */
    fun reached(today: LocalDate, budgetCents: Int, helper: String? = null, capCents: Int? = null): Boolean {
        val d = on(today)
        if (budgetCents >= 0 && d.total >= budgetCents * 10_000L) return true
        return helper != null && capCents != null && capCents >= 0 && (d.by[helper] ?: 0L) >= capCents * 10_000L
    }

    fun toJson(): String = JSONObject().put("day", day).put("totalMicros", total).put("byHelper", JSONObject().apply { for ((k, v) in by) put(k, v) }).toString()

    companion object {
        /**
         * helpers_spend.json; without it, the 0.4/0.5 claude_spend.json ([legacy]) as helper "claude"'s spend. A damaged
         * file reads as nothing spent.
         */
        fun fromJson(text: String?, legacy: String? = null): HelperSpend = try {
            if (text == null) fromLegacy(legacy) else {
                val o = JSONObject(text)
                val by = o.optJSONObject("byHelper")?.let { b -> b.keys().asSequence().associateWith { b.optLong(it).coerceAtLeast(0) } }.orEmpty()
                HelperSpend(o.optString("day"), o.optLong("totalMicros").coerceAtLeast(0), by)
            }
        } catch (_: Exception) { HelperSpend("") }

        private fun fromLegacy(legacy: String?): HelperSpend {
            val c = app.heyrobot.core.ClaudeSpend.fromJson(legacy)
            return if (c.micros > 0) HelperSpend(c.day, c.micros, mapOf(HelperConfig.CLAUDE_ID to c.micros)) else HelperSpend(c.day)
        }
    }
}
