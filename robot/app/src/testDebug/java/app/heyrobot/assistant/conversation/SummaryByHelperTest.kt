// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.conversation

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.AppShareCode
import app.heyrobot.core.ConsentCard
import app.heyrobot.core.HelperListener
import app.heyrobot.core.HelperResult
import app.heyrobot.core.HelperRoute
import app.heyrobot.core.SessionStatus
import app.heyrobot.core.TranscriptEvent
import app.heyrobot.core.TranscriptFold
import app.heyrobot.core.TranscriptMeta
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.Presets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 1.0.0: "Summarize with a stronger AI" by the helper you pick: its own id on the card (a job for one helper never runs
 * on another), its company, model and price on the card, its own room for a transcript; or a share to its app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SummaryByHelperTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val zone = ZoneOffset.UTC
    private val now = ZonedDateTime.of(2026, 10, 17, 12, 0, 0, 0, zone)
    private val start = now.minusHours(1).toInstant().toEpochMilli()
    private val meta = TranscriptMeta("20261017-1100-7f3a", "Lunch", start, start + 41 * 60_000L, SessionStatus.ENDED)
    private val t = TranscriptFold().apply {
        apply(TranscriptEvent.Line(0, 10.0, 14.0, "S1", "en", "So Marie, are we still on for Friday?"))
        apply(TranscriptEvent.Line(1, 70.0, 75.0, "S2", "fr", "Oui, vendredi à 18 h chez Julien."))
    }.result()
    private val gpt = HelperConfig.of(Presets.OPENAI, "hgpt", "am_puck")
    private val mistral = HelperConfig.of(Presets.MISTRAL, "hmis", "bf_emma")

    @Test fun eachHelperHasItsOwnCard() {
        val g = ApiHelper(ctx, gpt, 35_000)
        assertEquals("api_hgpt", g.id)
        assertEquals(HelperRoute.API, g.route)
        // OpenAI's published price for the model chosen, from the text's length (no peak hours, no dated change for it).
        assertEquals("Goes to: OpenAI (GPT-6.1 Sol), over the internet, with your API key. ≈ 5¢.", g.disclosure.line)
        assertEquals(ApiHelper.OTHER_INPUT_TOKENS, g.maxInputTokens())
        // Claude keeps its id and its room.
        val c = ApiHelper(ctx, HelperConfig.claude(), 35_000)
        assertEquals(ApiHelper.CLAUDE_JOB_ID, c.id)
        assertEquals(ApiHelper.MAX_INPUT_TOKENS, c.maxInputTokens())
        // A price Robot doesn't know: said on the card. Over budget: said too.
        assertTrue(ApiHelper(ctx, mistral, 35_000).disclosure.line.contains("Cost unknown"))
        assertTrue(ApiHelper(ctx, gpt, 35_000, budgetUsed = true).disclosure.line.endsWith("Today's budget for helpers is used up: this adds to it."))
    }

    @Test fun aJobForOneHelperNeverRunsOnAnother() = runBlocking {
        val g = ApiHelper(ctx, gpt, 1_000)
        val forClaude = ConsentCard.build(ApiHelper.CLAUDE_JOB_ID, "Claude", g.disclosure, meta, t, now, "CA", "en", zone).approve(System.currentTimeMillis())
        val r = g.summarize(forClaude, object : HelperListener {})
        assertEquals(HelperResult.Failed("Nothing was sent: Robot needs your OK on the card."), r)
    }

    @Test fun aHelpersAppGetsAShare() {
        val pkg = "com.openai.chatgpt"
        val pm = shadowOf(ctx.packageManager)
        pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg } })
        val share = ComponentName(pkg, "$pkg.Share")
        pm.addActivityIfNotPresent(share)
        pm.addIntentFilterForActivity(share, IntentFilter(Intent.ACTION_SEND).apply { addDataType("text/plain"); addCategory(Intent.CATEGORY_DEFAULT) })
        val a = AppShareHelper(ctx, gpt)
        assertEquals("app_hgpt", a.id)
        assertEquals("Goes to: the ChatGPT app, with your own account there, when you press Send there.", a.disclosure.line)
        val card = ConsentCard.build(a.id, "the ChatGPT app", a.disclosure, meta, t, now, "CA", "en", zone)
        assertEquals("Send this conversation to the ChatGPT app?", card.title)
        val (i, how) = a.intent(card.approve(System.currentTimeMillis()))!!
        assertEquals(AppShareCode.SHARED, how)
        assertEquals(pkg, i.`package`)
        assertEquals(card.appText, i.getStringExtra(Intent.EXTRA_TEXT))
        // Another helper's card is never shared with this app; an app that isn't installed gets nothing.
        val other = ConsentCard.build(AppShareHelper.CLAUDE_JOB_ID, "the Claude app", a.disclosure, meta, t, now, "CA", "en", zone)
        assertNull(a.intent(other.approve(System.currentTimeMillis())))
        val grok = AppShareHelper(ctx, HelperConfig.of(Presets.XAI, "hgrk", "am_fenrir"))
        assertNull(grok.intent(ConsentCard.build(grok.id, "the Grok app", grok.disclosure, meta, t, now, "CA", "en", zone).approve(System.currentTimeMillis())))
    }
}
