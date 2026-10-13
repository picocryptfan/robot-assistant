package app.heyrobot.core.helpers

import app.heyrobot.core.AnthropicCost
import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class PricingTest {
    private val monday9 = Instant.parse("2026-10-12T09:30:00Z")
    private val monday11 = Instant.parse("2026-10-12T11:00:00Z")

    private fun shipped(id: String) = HelperPrice.Published(PriceTable.of(id)!!)

    @Test fun claudesPricesAreTheModelsOwn() {
        // The Claude table stays in one place (AnthropicModel); the card is made from it, with web search at a cent.
        for (m in AnthropicModel.entries) {
            val u = HelperUsage(input = 1_000, output = 2_000, cacheRead = 3_000, cacheWrite = 4_000, searches = 1)
            val c = AnthropicCost.dollars(m, TokenUsage(1_000, 2_000, 3_000, 4_000, 1))
            assertEquals(m.id, c, Pricing.dollars(shipped(m.id), u, monday9)!!, 1e-12)
        }
        // As published: Sonnet 5.5's cache reads at $0.10, Opus 5.5's at $0.20, Fable 5.1 $10 / $50 with cache reads at $0.25.
        assertEquals(0.10, PriceTable.of("claude-sonnet-5-5")!!.rates.cacheRead, 0.0)
        assertEquals(0.20, PriceTable.of("claude-opus-5-5")!!.rates.cacheRead, 0.0)
        assertEquals(Rates(10.0, 50.0, 0.25, 12.50), PriceTable.of("claude-fable-5-1")!!.rates)
        // Haiku 5.5 over 100,000 prompt tokens: the higher card for every token.
        assertEquals(0.50, PriceTable.of("claude-haiku-5-5")!!.rates(100_001, monday9).input, 0.0)
        assertEquals(0.10, PriceTable.of("claude-haiku-5-5")!!.rates(100_000, monday9).input, 0.0)
    }

    @Test fun openAiLongContextOver272K() {
        val card = PriceTable.of("gpt-6.1-sol")!!
        assertEquals(2.0, card.rates(272_000, monday9).input, 0.0)
        assertEquals(4.0, card.rates(272_001, monday9).input, 0.0)
        assertEquals(15.0, card.rates(300_000, monday9).output, 0.0)
        // Cached input counts toward the prompt: 100k uncached and 200k cached is 300k, so the long rates.
        val u = HelperUsage(input = 100_000, output = 1_000, cacheRead = 200_000)
        assertEquals((100_000 * 4.0 + 1_000 * 15.0 + 200_000 * 0.20) / 1e6, Pricing.dollars(shipped("gpt-6.1-sol"), u, monday9)!!, 1e-12)
        // A search at $10 per 1,000.
        assertEquals(0.01, Pricing.dollars(shipped("gpt-6-luna"), HelperUsage(searches = 1), monday9)!!, 1e-12)
    }

    @Test fun xaiLongContextFrom200k() {
        val card = PriceTable.of("grok-4.7")!!
        assertEquals(2.0, card.rates(199_999, monday9).input, 0.0)
        assertEquals(4.0, card.rates(200_000, monday9).input, 0.0)
        assertEquals(0.005, Pricing.dollars(shipped("grok-4.7"), HelperUsage(searches = 1), monday9)!!, 1e-12)
    }

    @Test fun geminiPromotionEndsAtMidnightUtc() {
        val card = PriceTable.of("gemini-3.8-flash")!!
        assertEquals(0.75, card.rates(10, Instant.parse("2026-12-31T23:59:59Z")).input, 0.0)
        assertEquals(1.50, card.rates(10, Instant.parse("2027-01-01T00:00:00Z")).input, 0.0)
        assertEquals(7.50, card.rates(10, Instant.parse("2027-06-01T12:00:00Z")).output, 0.0)
        assertEquals(LocalDate.of(2026, 12, 31), card.until)
        // Google search grounding isn't used through this connection: no search price, so a search would be unknown.
        assertNull(Pricing.dollars(shipped("gemini-3.8-flash"), HelperUsage(searches = 1), card.until!!.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)))
    }

    @Test fun deepSeekPeakHoursOnWeekdays() {
        val card = PriceTable.of("deepseek-flash")!!
        // Peak: 01:00-04:00 and 06:00-10:00 UTC, Monday to Friday; double the off-peak price.
        assertEquals(0.30, card.rates(0, monday9).input, 1e-12)
        assertEquals(0.15, card.rates(0, monday11).input, 1e-12)
        assertEquals(0.30, card.rates(0, Instant.parse("2026-10-12T01:00:00Z")).input, 1e-12)
        assertEquals(0.15, card.rates(0, Instant.parse("2026-10-12T04:00:00Z")).input, 1e-12)
        assertEquals(0.15, card.rates(0, Instant.parse("2026-10-12T05:59:59Z")).input, 1e-12)
        assertEquals(0.30, card.rates(0, Instant.parse("2026-10-12T06:00:00Z")).input, 1e-12)
        assertEquals(0.15, card.rates(0, Instant.parse("2026-10-12T10:00:00Z")).input, 1e-12)
        // Weekends are off-peak all day.
        assertEquals(0.15, card.rates(0, Instant.parse("2026-10-10T09:00:00Z")).input, 1e-12)
        assertEquals(0.15, card.rates(0, Instant.parse("2026-10-11T02:00:00Z")).input, 1e-12)
        assertEquals(0.006, card.rates(0, monday9).cacheRead, 1e-12)
        assertEquals(1.20, card.rates(0, monday9).output, 1e-12)
        assertEquals(3.96, PriceTable.of("deepseek-v4-pro")!!.rates(0, monday9).output, 1e-12)
    }

    @Test fun reportedCostWinsYoursNextUnknownSaysSo() {
        val u = HelperUsage(input = 1_000, output = 1_000, reported = 0.0123)
        assertEquals(0.0123, Pricing.dollars(HelperPrice.Unknown, u, monday9)!!, 0.0)
        assertEquals(0.0123, Pricing.dollars(shipped("gpt-6-astra"), u, monday9)!!, 0.0)
        assertNull(Pricing.dollars(HelperPrice.Unknown, u.copy(reported = null), monday9))
        assertEquals(0.0, Pricing.dollars(HelperPrice.Free, u.copy(reported = null), monday9)!!, 0.0)
        assertEquals((1_000 * 1.0 + 1_000 * 3.0) / 1e6, Pricing.dollars(HelperPrice.Yours(Rates(1.0, 3.0)), u.copy(reported = null), monday9)!!, 1e-12)
        assertEquals("cost unknown", Pricing.line(null))
        assertNull(Pricing.line(0.0, free = true))
        assertEquals("< 1¢", Pricing.line(0.001))
        assertFalse(HelperPrice.Unknown.known)
        assertTrue(HelperPrice.Free.known && HelperPrice.Reported().known)
    }

    @Test fun whereAHelpersPriceComesFrom() {
        val p = Presets
        fun h(preset: Preset, model: String, home: String? = null) = HelperConfig("h1", preset.id, "X", model, baseUrl = home)
        assertEquals(HelperPrice.Published(PriceTable.of("gpt-6.1-sol")!!), h(p.OPENAI, "gpt-6.1-sol").price)
        // Mistral's prices aren't shipped: unknown until you enter them, and then only for the model you entered them for.
        assertEquals(HelperPrice.Unknown, h(p.MISTRAL, "mistral-medium-3-5").price)
        val yours = h(p.MISTRAL, "mistral-medium-3-5").copy(prices = Rates(0.4, 2.0), pricesFor = "mistral-medium-3-5")
        assertEquals(HelperPrice.Yours(Rates(0.4, 2.0)), yours.price)
        assertEquals(HelperPrice.Unknown, yours.copy(model = "mistral-large-4-0").price)
        // OpenRouter reports each answer's cost; a home server is free unless you enter a price.
        assertTrue(h(p.OPENROUTER, "any/model").price is HelperPrice.Reported)
        assertEquals(HelperPrice.Free, h(p.HOME, "qwen3.8:27b", "http://192.168.1.20:11434/v1").price)
        assertEquals(HelperPrice.Yours(Rates(0.01, 0.01)), h(p.HOME, "m", "http://10.0.0.2:8080/v1").copy(prices = Rates(0.01, 0.01)).price)
        assertEquals(HelperPrice.Unknown, h(p.OPENAI, "gpt-9-unknown").price)
    }

    @Test fun ratesFromJsonRefuseNonsense() {
        assertEquals(Rates(1.0, 2.0, 1.0, 1.0), Rates.fromJson(org.json.JSONObject("""{"input":1,"output":2}""")))
        assertNull(Rates.fromJson(org.json.JSONObject("""{"input":-1,"output":2}""")))
        assertNull(Rates.fromJson(org.json.JSONObject("""{"input":"x","output":2}""")))
        assertNull(Rates.fromJson(org.json.JSONObject("""{"input":1}""")))
        assertNull(Rates.fromJson(org.json.JSONObject("""{"input":1e9,"output":1}""")))
    }

    @Test fun spendIsPerHelperAndInAll() {
        val today = LocalDate.of(2026, 10, 11)
        var s = HelperSpend("")
        s = s.add(today, "claude", 0.42).add(today, "h1", 0.10).add(today, "claude", 0.08)
        assertEquals(600_000L, s.total)
        assertEquals(500_000L, s.of(today, "claude"))
        assertEquals(100_000L, s.of(today, "h1"))
        // A new day starts from zero.
        assertEquals(0L, s.on(today.plusDays(1)).total)
        // The budget for all helpers, or one helper's own cap.
        assertTrue(s.reached(today, 60))
        assertFalse(s.reached(today, 61))
        assertTrue(s.reached(today, 100, "h1", capCents = 10))
        assertFalse(s.reached(today, 100, "h1", capCents = 11))
        assertFalse(s.reached(today, 100, "h1", capCents = null))
        assertEquals(s, HelperSpend.fromJson(s.toJson()))
        // 0.4/0.5's claude_spend.json reads as Claude's spend; a damaged file as nothing.
        val legacy = HelperSpend.fromJson(null, """{"day":"2026-10-11","micros":250000}""")
        assertEquals(HelperSpend("2026-10-11", 250_000, mapOf("claude" to 250_000L)), legacy)
        assertEquals(0L, HelperSpend.fromJson("{oops").total)
        assertEquals(0L, HelperSpend.fromJson(null, null).total)
    }
}
