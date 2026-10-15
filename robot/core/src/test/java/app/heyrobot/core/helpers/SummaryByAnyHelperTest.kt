// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.helpers

import app.heyrobot.core.AnthropicModel
import app.heyrobot.core.Disclosure
import app.heyrobot.core.HelperCost
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SummaryCost
import app.heyrobot.core.SummaryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// 1.0.0: "Summarize with a stronger AI" with whichever helper you pick, through its own API, its own model and price.
class SummaryByAnyHelperTest {
    private val gpt = HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")
    private val mistral = HelperConfig.of(Presets.MISTRAL, "hmis", "bf_emma")
    private val home = HelperConfig.of(Presets.HOME, "hsrv", "af_bella", "Jarvis", "http://192.168.1.20:11434/v1").copy(model = "qwen3.8:27b")
    private val grok = HelperConfig.of(Presets.XAI, "hgrk", "am_fenrir")
    /** A Saturday at noon UTC: no peak hours, no dated price change. */
    private val at = Instant.parse("2026-10-17T12:00:00Z")

    @Test fun onlyHelpersWithTheirApiCanSummarize() {
        val s = RobotSettings(helpers = listOf(HelperConfig.claude(), gpt, grok, home, mistral.copy(enabled = false)))
        fun keys(vararg ids: String): (HelperConfig) -> Boolean = { it.id in ids }
        // No key anywhere: only your server (it needs none). An app can't send a summary back.
        assertEquals(listOf("hsrv"), HelperSettings.summarizers(s, keys()).map { it.id })
        // Keys saved: each, in your order; one turned off never.
        assertEquals(listOf("claude", "hgpt", "hsrv"), HelperSettings.summarizers(s, keys("claude", "hgpt", "hmis")).map { it.id })
        // The one you chose under "Ask:" first.
        assertEquals(listOf("hgpt", "claude", "hsrv"), HelperSettings.summarizers(s.copy(helpDefault = "hgpt"), keys("claude", "hgpt")).map { it.id })
        // A choice that can't summarize doesn't change the order.
        assertEquals(listOf("claude", "hsrv"), HelperSettings.summarizers(s.copy(helpDefault = "hgrk"), keys("claude")).map { it.id })
        // None: the summary screen offers to add one.
        assertEquals(emptyList<HelperConfig>(), HelperSettings.summarizers(RobotSettings(helpers = listOf(gpt, grok)), keys()))
    }

    @Test fun theCardsCostLineIsTheHelpersOwn() {
        val chars = 35_000
        val tokens = HelperCost.tokens(chars)
        // OpenAI's published price for the model chosen: 10,200 tokens in at $2, 2,500 out at $10 per million.
        val d = SummaryCost.estimate(gpt, chars, at)!!
        assertEquals(tokens * 2.0 / 1e6 + HelperCost.OUTPUT_TOKENS * 10.0 / 1e6, d, 1e-9)
        assertEquals("≈ 5¢", SummaryCost.line(gpt, chars, at))
        // Another model of the same helper: its own price.
        val luna = gpt.copy(model = "gpt-6-luna")
        assertTrue(SummaryCost.estimate(luna, chars, at)!! < d)
        // Claude as before (its prices from AnthropicModel).
        assertEquals(HelperCost.estimate(AnthropicModel.DEFAULT, chars), SummaryCost.estimate(HelperConfig.claude(), chars, at)!!, 1e-9)
        // Prices you entered win.
        val yours = mistral.copy(prices = Rates(1.0, 3.0), pricesFor = mistral.model)
        assertEquals(tokens * 1.0 / 1e6 + HelperCost.OUTPUT_TOKENS * 3.0 / 1e6, SummaryCost.estimate(yours, chars, at)!!, 1e-9)
        // A price Robot doesn't know: said, never guessed.
        assertNull(SummaryCost.estimate(mistral, chars, at))
        assertEquals(SummaryCost.UNKNOWN, SummaryCost.line(mistral, chars, at))
        // Your own server: free, no cost line.
        assertEquals(0.0, SummaryCost.estimate(home, chars, at)!!, 0.0)
        assertNull(SummaryCost.line(home, chars, at))
    }

    @Test fun theCardSaysWhereItGoesAndWhatItCosts() {
        assertEquals("Goes to: OpenAI (GPT-6.1 Sol), over the internet, with your API key. ≈ 5¢.",
            Disclosure.api(gpt, SummaryCost.line(gpt, 35_000, at), budgetUsed = false).line)
        assertEquals("Goes to: Mistral AI (Mistral Medium 3.5), over the internet, with your API key. Cost unknown: Robot has no price for this model " +
            "(you can enter it on the helper's page).", Disclosure.api(mistral, SummaryCost.line(mistral, 35_000, at), budgetUsed = false).line)
        assertEquals("Goes to: your own server (qwen3.8:27b), over your network.", Disclosure.api(home, SummaryCost.line(home, 35_000, at), false).line)
        // Over today's budget: the card says so; as an ask you make yourself, Send still sends.
        assertEquals("Goes to: xAI (Grok 4.7), over the internet, with your API key. ≈ 4¢. Today's budget for helpers is used up: this adds to it.",
            Disclosure.api(grok, SummaryCost.line(grok, 35_000, at), budgetUsed = true).line)
        // Claude's card as before.
        assertEquals(Disclosure.claudeApi(AnthropicModel.DEFAULT, 0.123).line, Disclosure("Anthropic", AnthropicModel.DEFAULT.title,
            "over the internet, with your API key", cost = "≈ 12¢").line)
        // A helper's app.
        assertEquals("Goes to: the ChatGPT app, with your own account there, when you press Send there.", Disclosure.app("ChatGPT").line)
        assertEquals("Goes to: the Claude app, with your claude.ai account, when you press Send there.", Disclosure.claudeApp().line)
    }

    @Test fun theBudgetCountsEachHelperAndAll() {
        val today = LocalDate.of(2026, 10, 17)
        // A summary by ChatGPT counts against its own cap and the budget for all helpers.
        var spend = HelperSpend(today.toString())
        spend = spend.add(today, "hgpt", 0.40)
        assertFalse(spend.reached(today, 100, "hgpt", gpt.budgetCents))
        assertTrue(spend.reached(today, 100, "hgpt", 40))
        assertFalse(spend.reached(today, 100, "claude", 40))
        spend = spend.add(today, "hgpt", 0.60)
        assertTrue(spend.reached(today, 100, "hgpt", null))
        assertEquals(1_000_000L, spend.of(today, "hgpt"))
        // Tomorrow starts from zero.
        assertFalse(spend.reached(today.plusDays(1), 100, "hgpt", 40))
    }

    @Test fun theSummaryIsLabelledWithItsHelper() {
        val sent = Instant.parse("2026-10-11T14:52:00Z").toEpochMilli()
        val r = SummaryRecord("gpt-6.1-sol", "gpt-6.1-sol", SummaryRecord.VIA_API, sent, "Overview…", consent = true, sentAt = sent, helperName = "ChatGPT",
            company = "OpenAI")
        assertEquals("ChatGPT's summary — sent to OpenAI with your OK on 11 Oct, 14:52.", r.label(ZoneOffset.UTC))
        assertEquals(r, SummaryRecord.fromJson(r.toJson()))
        // One saved before 1.0.0 is Claude's.
        val old = """{"by":"claude-opus-5-5","model":"claude-opus-5-5","via":"api","createdAt":$sent,"consent":true,"sentAt":$sent,"chunks":0,"text":"x"}"""
        assertEquals("Claude's summary — sent to Anthropic with your OK on 11 Oct, 14:52.", SummaryRecord.fromJson(old)!!.label(ZoneOffset.UTC))
    }
}
