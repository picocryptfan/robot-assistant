// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.assistant.ask.SharedItem
import app.heyrobot.assistant.read.PanelRead
import app.heyrobot.assistant.skills.PanelHelp
import app.heyrobot.assistant.skills.PanelSkills
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.core.Abort
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.LocalSummary
import app.heyrobot.core.SummaryBudget
import app.heyrobot.core.TurnRole
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.i18n.ConversationStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.i18n.WebStrings
import app.heyrobot.core.web.KeepSources
import app.heyrobot.core.web.PageContent
import app.heyrobot.core.web.PageGo
import app.heyrobot.core.web.PageHelp
import app.heyrobot.core.web.PageProblem
import app.heyrobot.core.web.PageSkip
import app.heyrobot.core.web.PageSumEnd
import app.heyrobot.core.web.PageSummaryAsk
import app.heyrobot.core.web.PageSummaryPlanner
import app.heyrobot.core.web.PageSummaryRouter
import app.heyrobot.core.web.UrlPolicy
import app.heyrobot.core.web.WebGate
import app.heyrobot.core.web.WebLimits
import app.heyrobot.core.web.WebUrl
import app.heyrobot.helpers.web.PageFetcher
import app.heyrobot.helpers.web.PageGet
import app.heyrobot.sys.WebStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

// "Summarize this page" (websearch-termux-design W4): the page read the way a web answer reads one (web search set up,
// pages read from this phone, core WebGate), its text summarized part by part on the phone's model (core
// PageSummaryPlanner, LocalSummary), the step on the reply, Stop ending the read and the model's step. Nothing goes
// anywhere but the page's own site, as with a web answer's page reads; locked, it asks you to unlock first.
internal object PanelPageSummary {
    /** The longest a page read may take: each hop's time cap, every redirect followed, and the reading. */
    private val READ_MS = (UrlPolicy.MAX_REDIRECTS + 1) * WebLimits.PAGE_MS + 4_000L

    // tests-batch-15: the summary's outside world (your web settings, the page's read, the phone's model), faked by
    // PanelPageSummaryTest. [model]: the model's context size, null when it isn't ready.
    internal var web: () -> app.heyrobot.core.web.WebSettings = { WebStore.load(VoiceHub.app) }
    internal var read: suspend (Abort, WebUrl, String) -> PageGet.Got? = { abort, url, lang -> OnlineRun.run(abort, READ_MS) { a -> PageGet.get(PageFetcher(), url, lang, a) } }
    internal var model: suspend () -> Int? = { BrainKeeper.ensureLoaded(VoiceHub.app)?.let { BrainKeeper.state.value.contextTokens } }
    internal var summarize: suspend (app.heyrobot.core.web.PagePlan, String, (LocalSummary.Progress) -> Unit) -> LocalSummary.Outcome = { plan, lang, progress ->
        val st = BrainKeeper.state.value
        LocalSummary(BrainOneShots, SummaryBudget.of(st.backend)).run(emptyList(), lang, st.contextTokens, onProgress = progress, plan = plan.plan,
            reduce = PageSummaryPlanner.reduce(lang))
    }

    /**
     * Your words, or a link shared with Robot with nothing typed or "summarize" (core PageSummaryRouter.route). With
     * something shared, only when it is a link (with its title) or your words hold one: a shared picture or passage goes
     * to the model as it did before (review M1). A shared link with nothing typed only when pages are read from this
     * phone ([pagesOn]); else it goes where it went before, with no new refusal (review M4).
     */
    fun route(q: String, shared: SharedItem?, pagesOn: () -> Boolean): PageSummaryAsk? {
        val text = PanelRead.sharedText(shared)
        if (shared != null && PageSummaryRouter.sharedLink(text) == null && PageSummaryRouter.link(q) == null) return null
        val ask = PageSummaryRouter.route(q, text) ?: return null
        return if (ask.fromWords || pagesOn()) ask else null
    }

    /** Web search set up and pages read from this phone (core WebGate.pagesFromPhone). */
    fun pagesOn(): Boolean = WebStore.load(VoiceHub.app).let { it.service != null && WebGate.pagesFromPhone(it) > 0 }

    /** The last web answer's first cited page, only when the newest reply in [turns] is that answer (review M3). */
    fun lastAnswer(turns: List<UiTurn>): String? = turns.lastOrNull { it.role == TurnRole.ASSISTANT }?.web?.cited?.firstOrNull()?.url

    /** Your last question went with the screen; else a link on it may be any link in a page's text (review M3). */
    fun screenBefore(turns: List<UiTurn>): Boolean = turns.lastOrNull { it.role == TurnRole.USER }?.attachment == Attachment.SCREEN

    /**
     * The page, in core PageSummaryRouter.pick's order: the link in your words, the one shared, the screen's, the last
     * web answer's; the screen and the answer only when the turn just before was about them, else null (ask which page).
     * [turns]: the conversation before this question.
     */
    fun pick(ask: PageSummaryAsk, shared: SharedItem?, screen: String?, turns: List<UiTurn>): PageSummaryRouter.Picked? =
        PageSummaryRouter.pick(ask, PanelRead.sharedText(shared), screen?.takeIf { screenBefore(turns) }, lastAnswer(turns))

    /** The summary's first line, so its History line too: the page's host, its address or neither, as Settings › Web search keeps a web answer's sources. */
    fun label(keep: KeepSources, url: WebUrl): Phrase = when (keep) {
        KeepSources.DOMAINS -> WebStrings.pageLabel("domain" to WebUrl.domain(url.toString()))
        KeepSources.LINKS -> WebStrings.pageLabel("domain" to url.toString())
        KeepSources.NONE -> WebStrings.pageLabelNone()
    }

    /**
     * W-18 (summarize-page-c): the phone's model gave no summary ([end]), so a helper you added may, after the card with
     * exactly what would go, to whom and its cost (PanelHelp, core SkillHelp.send); only a tap sends it. True when the
     * helper's summary came back. fix-batch-15 M2 (review-batch-15): never after a summary (its first-parts line ends the
     * turn), never hands-free or in car mode, so no card waits on you there; L4: "can try" says when only the start goes.
     */
    private suspend fun helper(replyId: String, host: PanelSkills.Host, end: PageSumEnd, domain: String, content: PageContent, lang: String, l: Langs): Boolean {
        if (!PageHelp.offers(end, handsFree = host.handsFree, driving = host.helpers?.driving != false)) return false
        val h = PanelHelp.offered(replyId, host) ?: return false
        val offer = PageHelp.offer(h.name, content.text)
        host.append(replyId, offer.render(l))
        if (host.speak) host.say(offer.render(l.voice))
        val text = PanelHelp.send(replyId, host, h, PageHelp.request(domain, content.text, lang), l)?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        host.append(replyId, listOf(WebStrings.pageHelpLabel("name" to h.name).render(l), text).joinToString("\n\n")) // l10n: not text (paragraph break)
        if (host.speak) host.say(PageSummaryPlanner.spoken(text))
        return true
    }

    /**
     * [markContent]: once the page's text is read, the reply is content Robot didn't write (every later action asks first).
     * [host]: the skills' host, for the helper's card (W-18).
     */
    suspend fun handle(replyId: String, ask: PageSummaryAsk, host: PanelSkills.Host, picked: PageSummaryRouter.Picked?, markContent: () -> Unit) {
        val l = host.langs(null, when (ask.lang) { "fr" -> Lang.FR; "en" -> Lang.EN; else -> null }) // l10n: not text (language codes)
        val lang = ask.lang.ifEmpty { if (l.say == Lang.FR) "fr" else "en" } // l10n: not text (language codes)
        val started = System.currentTimeMillis()
        var end = PageSumEnd.STOPPED
        var from = 0
        var parts = 0
        fun reply(p: Phrase) {
            host.text(replyId, p.render(l))
            if (host.speak) host.say(p.render(l.voice))
        }
        fun endWith(e: PageSumEnd, p: Phrase) {
            end = e
            reply(p)
        }
        val abort = Abort()
        try {
            if (host.locked) return endWith(PageSumEnd.LOCKED, WebStrings.pageUnlock())
            val s = withContext(Dispatchers.IO) { web() }
            if (s.service == null) return endWith(PageSumEnd.WEB_OFF, WebStrings.pageWebOff())
            if (WebGate.pagesFromPhone(s) <= 0) return endWith(PageSumEnd.NOT_FROM_PHONE,
                // review L4: DuckDuckGo Instant and Wikipedia only open no page, whatever the settings say.
                if (PageSummaryRouter.shortAnswersOnly(s.service)) WebStrings.pageShortAnswers() else WebStrings.pageNotFromPhone())
            if (picked == null) return endWith(PageSumEnd.NO_PAGE, WebStrings.pageWhich())
            from = picked.from.ordinal + 1
            val domain = WebUrl.domain(picked.url.toString())
            // fix-routing-1.4 (review-routing-1.4 M1): a link the screen's text chose waits for a tap on its card.
            when (PageSummaryRouter.go(picked.from, host.handsFree, CarModeController.on, host.onScreen)) {
                PageGo.LOOK -> return endWith(PageSumEnd.SCREEN_LOOK, PageSummaryRouter.look(CarModeController.on))
                PageGo.CARD -> {
                    val card = PageSummaryRouter.screenCard(host.ports(replyId).newId(), picked.url, l)
                    val tap = WebStrings.pageScreenTap("open" to UiStrings.open().render(l.voice)).render(l.voice)
                    if (!host.ask(replyId, card, tap)) {
                        host.show(replyId, card.copy(status = ActionStatus.DECLINED))
                        return endWith(PageSumEnd.SCREEN_NO, WebStrings.pageScreenNo("domain" to domain))
                    }
                    host.show(replyId, card.copy(status = ActionStatus.DONE))
                    if (host.locked) return endWith(PageSumEnd.LOCKED, WebStrings.pageUnlock())
                }
                PageGo.READ -> Unit
            }
            host.text(replyId, WebStrings.pageReading("domain" to domain).render(l))
            // On a thread of its own: Stop cancels the turn, and OnlineRun cancels the read under way (review M2).
            val got = read(abort, picked.url, lang) ?: PageGet.Got.Skipped(PageSkip.TOO_SLOW)
            val content = when (got) {
                is PageGet.Got.Skipped -> return endWith(PageSumEnd.NOT_READ, WebStrings.pageCouldnt("domain" to domain, "why" to got.why.label()))
                is PageGet.Got.Page -> got.content
            }
            when (PageSummaryPlanner.problem(content)) {
                PageProblem.NEEDS_JS -> return endWith(PageSumEnd.NOT_READ, WebStrings.pageCouldnt("domain" to domain, "why" to PageSkip.NEEDS_JS.label()))
                PageProblem.SIGN_IN -> return endWith(PageSumEnd.SIGN_IN, WebStrings.pageSignIn("domain" to domain))
                PageProblem.NOTHING -> return endWith(PageSumEnd.NO_TEXT, WebStrings.pageNothing("domain" to domain))
                null -> Unit
            }
            // The page's text goes to the model from here: the reply is content Robot didn't write.
            markContent()
            val tokens = model()
            if (tokens == null) {
                endWith(PageSumEnd.NO_MODEL, ConversationStrings.modelNotReady())
                if (helper(replyId, host, PageSumEnd.NO_MODEL, domain, content, lang, l)) end = PageSumEnd.HELPER
                return
            }
            val plan = PageSummaryPlanner.plan(content, lang, tokens)
            val r = summarize(plan, lang) { p ->
                val line = if (p.merging) WebStrings.pageMerging() else WebStrings.pagePart("n" to p.step + 1, "of" to plan.parts)
                host.text(replyId, line.render(l))
            }
            when (r) {
                is LocalSummary.Outcome.Failed -> {
                    endWith(if (!currentCoroutineContext().isActive) PageSumEnd.STOPPED else PageSumEnd.FAILED, r.whyText)
                    if (helper(replyId, host, end, domain, content, lang, l)) end = PageSumEnd.HELPER
                }
                is LocalSummary.Outcome.Done -> {
                    end = PageSumEnd.DONE
                    parts = plan.parts
                    val notes = listOfNotNull(
                        if (plan.cut) WebStrings.pageFirstParts("n" to plan.parts, "of" to plan.of) else null,
                        if (plan.otherLanguage) WebStrings.pageOtherLanguage() else null,
                    )
                    host.text(replyId, (listOf(label(s.keepSources, picked.url).render(l), r.text) + notes.map { it.render(l) }).joinToString("\n\n")) // l10n: not text (paragraph break)
                    if (host.speak) {
                        host.say(PageSummaryPlanner.spoken(r.text))
                        notes.forEach { host.say(it.render(l.voice)) }
                    }
                }
            }
        } finally {
            VoiceLog.add(VoiceEvent.PAGE_SUMMARY, end.code, from * 10 + parts, ((System.currentTimeMillis() - started) / 1000).toInt())
            host.finish(replyId)
        }
    }
}
