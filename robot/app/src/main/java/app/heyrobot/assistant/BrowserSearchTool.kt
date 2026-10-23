// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import app.heyrobot.R
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.BrowserSearchAction
import app.heyrobot.core.Executed
import app.heyrobot.core.Prepared
import app.heyrobot.core.RLog
import app.heyrobot.core.RawToolCall
import app.heyrobot.core.ToolName
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.web.BrowserGate
import app.heyrobot.core.web.QueryContext
import app.heyrobot.core.web.WebConsent
import app.heyrobot.core.web.WebLogLine
import app.heyrobot.core.web.WebRoute
import app.heyrobot.sys.WebAnswers
import app.heyrobot.sys.WebStore

// VOICE PROCESS. 1.0: browser_search's glue (core web BrowserSearch.kt): ToolExecutor's prepare and run for it, and the
// call the panel makes from your own words. Robot hands the engine's own search address to your browser (ACTION_VIEW,
// CATEGORY_BROWSABLE) and stops there: it sends nothing itself and never reads what comes back, so nothing here touches
// the network (ProcessIsolationTest). The voice log gets the engine's code and whose words they were, never the words.

object BrowserSearchTool {
    /** browser_search made ready: the engine (named, said, or Settings › Web search's default) and its card when the model wrote the words. */
    fun prepare(ctx: Context, a: BrowserSearchAction, said: String?, query: QueryContext): Prepared =
        BrowserGate.prepare(a, said, query, WebStore.load(ctx).browserEngine) { e -> ctx.getString(R.string.browser_card_note, e.title) }

    /** The intent that opens [url] in your browser: a link, as tapping it would open it. */
    fun intent(url: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Opens [a]'s search with [launch] (the panel's: it starts the activity, or says it couldn't). */
    fun open(ctx: Context, a: BrowserSearchAction, launch: (Intent) -> Boolean): Executed {
        val s = WebStore.load(ctx)
        val engine = a.engine ?: s.browserEngine
        val opened = try { launch(intent(engine.url(a.query))) } catch (_: Exception) { false }
        if (!opened) return Executed(ActionStatus.NO_APP, ToolStrings.noBrowser())
        val line = WebLogLine.browser(engine, a.own, fallback = !WebConsent.on(s, WebAnswers.load(ctx)))
        VoiceLog.add(VoiceEvent.BROWSER_SEARCH, line.a, line.b, line.c)
        RLog.i("browser_search", "engine" to engine.code, "by" to line.b, "fallback" to line.c)
        // The model reads the English; the card says it in the reply language (1.1).
        return Executed(ActionStatus.DONE, ToolStrings.browserOpened("engine" to engine.title), launchedApp = true)
    }

    /**
     * The call your own explicit phrase makes ([route]): browser_search for a search in your browser, and for "search the
     * web for …" while Robot's own web search is off (Google for "google …", else your default); null: Robot's own search.
     */
    fun directCall(ctx: Context, route: WebRoute): RawToolCall? = when (route) {
        is WebRoute.Browser -> call(route.query, route.engine?.id)
        is WebRoute.Search -> if (WebConsent.on(WebStore.load(ctx), WebAnswers.load(ctx))) null
            else call(route.query, if (route.google) app.heyrobot.core.web.BrowserEngine.GOOGLE.id else null)
    }

    private fun call(query: String, engine: String?): RawToolCall =
        RawToolCall(ToolName.BROWSER_SEARCH.id, buildMap { put("query", query); engine?.let { put("engine", it) } })
}
