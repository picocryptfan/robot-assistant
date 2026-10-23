// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.Manifest
import android.content.Context
import android.os.SystemClock
import app.heyrobot.BuildConfig
import app.heyrobot.core.Abort
import app.heyrobot.core.Action
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ContextWindow
import app.heyrobot.core.Executed
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SecretRead
import app.heyrobot.core.TokenEstimate
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.toPromptTurns
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.Citations
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebConsentAnswer
import app.heyrobot.core.web.WebConsentChoice
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebAlso
import app.heyrobot.core.web.WebGate
import app.heyrobot.core.web.WebLogNumbers
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebPrices
import app.heyrobot.core.web.WebPrompt
import app.heyrobot.core.web.WebRouter
import app.heyrobot.core.web.request
import app.heyrobot.helpers.web.PageFetcher
import app.heyrobot.helpers.web.SearchClient
import app.heyrobot.helpers.web.WebSearch
import app.heyrobot.helpers.web.WebStep
import app.heyrobot.helpers.web.WebTransport
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.HelperSpendStore
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebCounts
import app.heyrobot.sys.WebKeyFiles
import app.heyrobot.sys.WebStore
import java.time.ZonedDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// VOICE PROCESS. The web_search tool's glue (0.9, design A6.1 WebTool): ToolExecutor's prepare and run for it. Before
// anything leaves the phone, core WebGate (web search on, today's cap, the query's check, Ask before searching); then the
// :helpers module's WebSearch with the key decrypted here (WebKeys), and what it found goes back as the action's result
// for the panel's one-shot answer. The first search card's lasting answer, today's count and a paid search's cost are
// written here. The voice log and logcat get codes and numbers only: never the query, a URL, a domain or a title.

/** The search services' keys, decrypted in this process only, just before a search or Test. Background thread. */
object WebKeys {
    fun read(ctx: Context, service: SearchService): SecretRead = if (service.keyId == null) SecretRead.Absent else WebKeyFiles.store(service).read(ctx)

    fun save(ctx: Context, service: SearchService, key: String, masked: String): Boolean = WebKeyFiles.store(service).save(ctx, key, masked)

    fun clearClipboard(ctx: Context, service: SearchService, key: String) = WebKeyFiles.store(service).clearClipboard(ctx, key)
}

class WebTool(
    private val ctx: Context,
    private val settings: RobotSettings,
    private val abort: Abort,
    /** What you typed or said this turn (never screen text): the depth words and the passages' ranking come from it. */
    private val said: String?,
    private val context: () -> QueryContext,
    /** What the search is doing, and the question's language, for the panel's status and Robot's voice. */
    private val step: (WebStep, String) -> Unit,
) {
    fun prepare(a: Action.WebSearch): Prepared {
        if (!Assistant.granted(ctx, Manifest.permission.INTERNET)) return Prepared.Refused(ActionStatus.FAILED, WebFail.NO_NETWORK_PERMISSION.sentence(null))
        return WebGate.prepare(a, WebStore.load(ctx), WebAnswers.load(ctx), context(), WebCounts.today(ctx), settings.weatherHome?.name,
            question = said?.takeIf { it.isNotBlank() } ?: a.query)
    }

    fun run(a: Action.WebSearch): Executed {
        val s = WebStore.load(ctx)
        // The first search's card: "Allow always" and "No" with "Don't ask again" last (they count while newer than Settings).
        WebConsentAnswer.of(a.consent ?: WebConsentChoice.ONCE, System.currentTimeMillis())?.let { WebAnswers.save(ctx, it) }
        if (!WebGate.searches(a.consent)) return Executed(ActionStatus.DECLINED, WebGate.NOT_SEARCHED)
        val service = s.service ?: return failed(null, WebFail.OFF)
        val key = if (!service.needsKey) null else when (val r = WebKeys.read(ctx, service)) {
            is SecretRead.Present -> r.value
            else -> return failed(service, WebFail.KEY_MISSING)
        }
        val question = said?.takeIf { it.isNotBlank() } ?: a.query
        val lang = WebRouter.lang(question)
        val t0 = SystemClock.elapsedRealtime()
        var searched = -1L
        val search = WebSearch(SearchClient(s, key, BuildConfig.VERSION_NAME, WebTransport(), abort), PageFetcher(), s)
        val outcome = search.run(a.query, question, lang, a.depth, abort) { st ->
            if (st is WebStep.Reading && searched < 0) searched = SystemClock.elapsedRealtime()
            step(st, lang)
        }
        val now = SystemClock.elapsedRealtime()
        if (searched < 0) searched = now
        return when (outcome) {
            is WebOutcome.Found -> {
                WebCounts.add(ctx)
                cost(service, outcome)
                val ms = (searched - t0).toInt()
                VoiceLog.add(VoiceEvent.WEB_SEARCH, service.code, outcome.results.size, ms)
                RLog.i("web_search", "svc" to service.code, "n" to outcome.results.size, "ms" to ms)
                if (outcome.pagesRead > 0 || outcome.skipped.isNotEmpty()) {
                    val skips = outcome.skipped.map { it.second }
                    VoiceLog.add(VoiceEvent.WEB_PAGES, outcome.pagesRead, WebLogNumbers.skipped(skips), (now - searched).toInt())
                    RLog.i("web_pages", "ok" to outcome.pagesRead, "skipped" to skips.size, "ms" to (now - searched), "why" to (skips.firstOrNull() ?: PageSkip.FAILED).code)
                }
                // 1.0 (network-1): everyone the words went to (core WebAlso), as the answer card and History say too.
                Executed(ActionStatus.DONE, ToolStrings.sentTo("who" to WebAlso.to(service, outcome.also), "query" to outcome.query), web = outcome)
            }
            is WebOutcome.Failed -> {
                // Asked, and answered with a failure: it counts toward today's cap (a key's credit may have gone).
                if (outcome.why in COUNTED) WebCounts.add(ctx)
                failed(service, outcome.why)
            }
        }
    }

    private fun failed(service: SearchService?, why: WebFail): Executed {
        if (why != WebFail.STOPPED) {
            VoiceLog.add(VoiceEvent.WEB_FAILED, why.id, service?.code ?: 0)
            RLog.i("web_failed", "why" to why.id, "svc" to (service?.code ?: 0))
        }
        val o = WebOutcome.Failed(service, why)
        return Executed(ActionStatus.FAILED, o.sentence, web = o)
    }

    /** A paid search's estimated price joins the helpers' one daily budget (0.6.0 helpers_spend.json), under the service's key id. */
    private fun cost(service: SearchService, o: WebOutcome.Found) {
        val dollars = WebPrices.dollars(service, if (o.throughService) o.pagesRead else 0)
        if (dollars > 0) try { HelperSpendStore.add(ctx, service.keyId ?: return, dollars) } catch (t: Throwable) { RLog.w("web_cost", t) }
    }

    companion object {
        /** Failures after the service was asked: they count toward today's searches. */
        private val COUNTED = setOf(WebFail.NO_RESULTS, WebFail.SERVICE, WebFail.BUSY, WebFail.OUT_OF_CREDIT, WebFail.KEY_REFUSED, WebFail.JSON_OFF)
    }
}

/**
 * The panel's answer from what a search found: its results and passages in a tool-free one-shot (core WebPrompt: at most
 * 2,890 tokens in, 350 out spoken or 700 typed, smaller for a short-context model), streamed to [onText] ([onRestart] when
 * a request that gave way starts over); then the citations checked and renumbered, and the answer card (sources, what was
 * sent, credits) with the search's saved line (the domains). Logs numbers only.
 */
object WebReply {
    sealed class Result {
        /** [text] with its marks renumbered; [complete] false when the answer was cut at its time. */
        class Answered(val text: String, val complete: Boolean, val card: AnswerCard, val saved: Phrase) : Result()
        object Cancelled : Result()
        object Failed : Result()
    }

    /** A web answer's whole time on the model once it starts (a CPU model writes 350 tokens in about a minute). */
    private const val ANSWER_MS = 180_000L
    val NO_ANSWER: String get() = noAnswer.en()

    /** [NO_ANSWER] in words without a language yet (1.1: Robot says it in the reply language). */
    val noAnswer: app.heyrobot.core.i18n.Phrase get() = ToolStrings.noWebAnswer()

    suspend fun answer(ctx: Context, question: String, history: List<ChatTurn>, found: WebOutcome.Found, spoken: Boolean, showCost: Boolean,
        onRestart: () -> Unit, onText: (String) -> Unit): Result {
        val web = withContext(Dispatchers.IO) { WebStore.load(ctx) }
        val context = BrainKeeper.state.value.contextTokens.takeIf { it > 0 } ?: ContextWindow.MAX_TOKENS
        val one = WebPrompt.build(question, found.lang, ZonedDateTime.now(), history.toPromptTurns(), found.results, found.passages, spoken,
            WebPrompt.budget(context, spoken))
        val t0 = SystemClock.elapsedRealtime()
        var first = -1L
        var streamed = 0
        val r = BrainOneShots.run(one.request(), OneShotBudget(startWithinMs = null, totalMs = ANSWER_MS)) { soFar ->
            // A request that gave way starts over: what was shown goes.
            if (soFar.length < streamed) {
                onRestart()
                streamed = 0
            }
            if (soFar.length > streamed) {
                if (first < 0) first = SystemClock.elapsedRealtime() - t0
                val delta = soFar.substring(streamed)
                streamed = soFar.length
                onText(delta)
            }
        }
        val (text, complete) = when (r) {
            is OneShotResult.Done -> r.text to true
            is OneShotResult.TimedOut -> r.partial to false
            OneShotResult.Cancelled -> return Result.Cancelled
            else -> return Result.Failed
        }
        if (text.isBlank()) return Result.Failed
        val cited = Citations.finish(text, one.sources)
        val card = AnswerCard.of(found.service, found.query, cited, found.pagesRead, found.throughService, found.skipped,
            showCost = showCost, poweredByBrave = web.poweredByBrave, also = found.also)
        val out = TokenEstimate.text(text)
        VoiceLog.add(VoiceEvent.WEB_ANSWER, one.inputTokens / 10, out, first.coerceAtLeast(0).toInt())
        RLog.i("web_answer", "in" to one.inputTokens, "out" to out, "first_ms" to first)
        return Result.Answered(cited.text, complete, card, card.savedText(web.keepSources))
    }
}
