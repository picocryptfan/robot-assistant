package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.web.OpenLibraryWire
import app.heyrobot.core.web.WebAlso
import app.heyrobot.core.web.PageContent
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.PageText
import app.heyrobot.core.web.Passages
import app.heyrobot.core.web.ReadThrough
import app.heyrobot.core.web.SearchAnswer
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.SearchWire
import app.heyrobot.core.web.WebFail
import app.heyrobot.core.web.WebGate
import app.heyrobot.core.web.WebOutcome
import app.heyrobot.core.web.WebPlan
import app.heyrobot.core.web.WebSettings

// VOICE PROCESS ONLY, :helpers module (0.9 web search, design A6.2). One search, from the query to what the answer is made
// from: the service's results (with Wikipedia's intros and Open Library's books when those are on and the question asks
// for them), then, when the question wants more than snippets (or Settings › Read result pages says Always), up to 3 of
// the results' own pages read from the phone (PageFetcher, PageReader) or the page text the service gave itself, and the
// passages that best answer the question within Gemma's budget (core Passages). The answer itself is the panel's one-shot.

/** What a search is doing, for the panel's status line and Robot's voice. */
sealed class WebStep {
    data object Searching : WebStep()
    /** Reading [pages] pages; [done] back so far. */
    data class Reading(val pages: Int, val done: Int) : WebStep()
}

class WebSearch(
    private val client: SearchClient,
    private val fetcher: PageFetcher,
    private val settings: WebSettings,
    /** Which results' pages to read (core WebPlan's rules: only the results' own https URLs, public, one a site); tests pick a local server's. */
    private val pick: (List<app.heyrobot.core.web.SearchResult>, Int) -> WebPlan.Picks = WebPlan::pickPages,
) {
    /**
     * Searches for [query] (as it goes) to answer [question] (your words, for the depth words and the passages' ranking)
     * in [lang]; [depth] the model's "quick" or "read". [step] says what it is doing; [abort] ends it (Stop, the deadline).
     */
    fun run(query: String, question: String, lang: String, depth: String?, abort: Abort, step: (WebStep) -> Unit = {}): WebOutcome {
        val service = settings.service ?: return WebOutcome.Failed(null, WebFail.OFF)
        step(WebStep.Searching)
        val wantPages = WebPlan.readPages(settings.readPages, depth, question.ifBlank { query })
        val pagesFromPhone = WebGate.pagesFromPhone(settings)
        val through = wantPages && settings.readThrough == ReadThrough.SERVICE && (service == SearchService.BRAVE || service == SearchService.KAGI)
        val answer = client.search(query, lang, readThrough = through, pages = if (through) settings.pages else 0)
        if (abort.cancelled) return WebOutcome.Failed(service, if (abort.expired) WebFail.TIMEOUT else WebFail.STOPPED)
        var results = when (answer) {
            is SearchAnswer.Failed -> return WebOutcome.Failed(service, answer.why)
            is SearchAnswer.Results -> answer.results
        }
        val answers = (answer as SearchAnswer.Results).answers
        // Wikipedia's intros first for a who/what/where question (Encyclopedia lookups), Open Library's books for a book question.
        // The same decisions as the consent card's (core WebAlso); who was actually sent the words goes in the "Sent to" line.
        val also = ArrayList<String>()
        if (WebAlso.wikipedia(settings, service, question)) {
            // Null: Wikipedia may not be asked (no contact for its User-Agent), so it got nothing.
            client.wikipedia(query, lang)?.let { wiki -> also += WebAlso.WIKIPEDIA; results = SearchWire.distinct(wiki + results) }
        }
        if (WebAlso.books(settings, question.ifBlank { query })) {
            also += WebAlso.OPEN_LIBRARY
            (client.books(query) as? SearchAnswer.Results)?.results?.let { books -> results = SearchWire.distinct(results.take(WebPlanLimits.WITH_BOOKS) + books) }
        }
        if (results.isEmpty()) return WebOutcome.Failed(service, WebFail.NO_RESULTS)
        if (abort.cancelled) return WebOutcome.Failed(service, if (abort.expired) WebFail.TIMEOUT else WebFail.STOPPED)

        // Page text the service (or Wikipedia) supplied: read like a page, never fetched.
        val pages = ArrayList<Pair<Int, PageContent>>()
        results.forEachIndexed { i, r -> if (r.extra.isNotEmpty()) pages += (i + 1) to PageText.plain(r.extra.joinToString("\n\n")) }
        val supplied = pages.size
        val skipped = ArrayList<Pair<String, PageSkip>>()
        var read = 0
        if (wantPages && pagesFromPhone > 0) {
            val picks = pick(results, pagesFromPhone)
            picks.skipped.forEach { (r, why) -> skipped += r.domain to why }
            if (picks.pages.isNotEmpty()) {
                step(WebStep.Reading(picks.pages.size, 0))
                for (res in fetcher.fetchAll(picks.pages, lang, abort) { done -> step(WebStep.Reading(picks.pages.size, done)) }) {
                    when (res) {
                        is PageFetcher.Result.Skipped -> skipped += res.pick.result.domain to res.why
                        is PageFetcher.Result.Read -> {
                            val content = try { PageReader.read(res.page) } catch (_: Throwable) { null }
                            when {
                                content == null -> skipped += res.pick.result.domain to PageSkip.FAILED
                                content.needsJavaScript -> skipped += res.pick.result.domain to PageSkip.NEEDS_JS
                                else -> {
                                    pages += res.pick.source to content
                                    read++
                                }
                            }
                        }
                    }
                }
            }
        }
        if (abort.cancelled && !abort.expired) return WebOutcome.Failed(service, WebFail.STOPPED)
        val passages = if (pages.isEmpty()) emptyList() else Passages.select(question.ifBlank { query }, pages)
        return WebOutcome.Found(service, query, lang, results, passages, pagesRead = if (through) supplied else read, throughService = through,
            skipped = skipped.distinct(), answers = answers, also = also)
    }

    /** Results kept when books join them. */
    private object WebPlanLimits {
        const val WITH_BOOKS = 5
    }

    companion object {
        /** A search's overall deadline: the request, then the pages (6 s each, 10 s in all), then a margin. */
        const val DEADLINE_MS = app.heyrobot.core.web.WebLimits.SEARCH_MS + app.heyrobot.core.web.WebLimits.PAGES_MS + 2_000L
    }
}
