// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.web

import app.heyrobot.core.Abort
import app.heyrobot.core.web.PageContent
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.SearchProvider
import app.heyrobot.core.web.SearchResult
import app.heyrobot.core.web.WebPlan
import app.heyrobot.core.web.WebUrl
import okhttp3.Call

// One page read for "Summarize this page" (W4): the same PageFetcher (public addresses only, https, the size and time
// caps, no cookies or Referer) and PageReader (Readability, hidden text dropped) as a web answer's page reads.
object PageGet {
    sealed class Got {
        class Page(val content: PageContent) : Got()
        class Skipped(val why: PageSkip) : Got()
    }

    /** [url]'s text, or why it wasn't read. [abort] (Stop) cancels the request under way. */
    fun get(fetcher: PageFetcher, url: WebUrl, lang: String?, abort: Abort): Got {
        val pick = WebPlan.Pick(1, SearchResult(url.host, url.toString(), "", SearchProvider.SEARXNG), url)
        val calls = java.util.Collections.synchronizedList(ArrayList<Call>())
        val off = abort.onCancel { synchronized(calls) { calls.forEach { it.cancel() } } }
        try {
            return when (val r = fetcher.fetch(pick, lang, calls, abort)) {
                is PageFetcher.Result.Skipped -> Got.Skipped(r.why)
                is PageFetcher.Result.Read -> {
                    val content = try { PageReader.read(r.page) } catch (_: Throwable) { null }
                    if (content == null) Got.Skipped(PageSkip.FAILED) else Got.Page(content)
                }
            }
        } finally {
            off()
        }
    }
}
