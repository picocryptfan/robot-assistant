// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import androidx.test.core.app.ApplicationProvider
import app.heyrobot.assistant.skills.PanelSkills
import app.heyrobot.assistant.skills.SkillHelpers
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.LocalSummary
import app.heyrobot.core.OneShotTransport
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.helpers.HelperConfig
import app.heyrobot.core.helpers.HelperSpend
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.messages.MessageTaint
import app.heyrobot.core.skills.DraftSource
import app.heyrobot.core.skills.HelpReply
import app.heyrobot.core.skills.HelpRequest
import app.heyrobot.core.web.PageBlock
import app.heyrobot.core.web.PageContent
import app.heyrobot.core.web.PageSummaryAsk
import app.heyrobot.core.web.PageSummaryRouter
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebSettings
import app.heyrobot.core.web.WebUrl
import app.heyrobot.helpers.web.PageGet
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * fix-batch-15 M2 (review-batch-15), driven rather than read: the helper's card comes only when the phone's model gave no
 * summary (not ready, or it failed), never after a summary, hands-free, in car mode or after a stop. The helper's path is
 * seen at its first step, PanelHelp.offered asking the helpers what was spent today (this fake's [Helpers.asked]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PageSummaryWiringTest {
    private class Helpers(override val driving: Boolean = false) : SkillHelpers {
        var asked = 0
        override fun keySaved(h: HelperConfig) = false
        override suspend fun spend(): HelperSpend { asked++; throw IllegalStateException("no helper in this test") }
        override suspend fun untilParked() = Unit
        override suspend fun ask(h: HelperConfig, r: HelpRequest): HelpReply = error("unused")
    }

    private class Host(val hs: Helpers, override val handsFree: Boolean = false) : PanelSkills.Host {
        val texts = ArrayList<String>()
        override val speak = false
        override val locked = false
        override fun text(replyId: String, text: String) { texts += text }
        override fun append(replyId: String, text: String) { texts += text }
        override fun say(sentence: String, personal: Boolean) = Unit
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?) = false
        override fun show(replyId: String, card: Card) = Unit
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() = Unit
        override fun langs(said: String?, router: Lang?): Langs = Langs.of(Lang.EN)
        override fun ports(replyId: String): PanelSkills.SkillPorts = error("unused: the helpers' spend fails first")
        override val untrusted = false
        override val phoneLock = false
        override val messages = MessageTaint()
        override val oneShots: OneShotTransport get() = error("unused")
        override val source: DraftSource? = null
        override val helpers: SkillHelpers get() = hs
    }

    private val saved = listOf(PanelPageSummary.web, PanelPageSummary.read, PanelPageSummary.model, PanelPageSummary.summarize)
    private val url = WebUrl.parse("https://news.example.com/2026/ferries")!!
    private val page = PageContent("Ferries", List(12) { PageBlock(PageBlock.Kind.PARAGRAPH,
        "The morning ferry leaves the north wharf at nine and the evening one at six, all through the summer season.") }, false, false)

    @Before fun fakes() {
        VoiceHub.init(ApplicationProvider.getApplicationContext())
        PanelPageSummary.web = { WebSettings(on = true, service = SearchService.SEARXNG_HOME) }
        PanelPageSummary.read = { _, _, _ -> PageGet.Got.Page(page) }
        PanelPageSummary.model = { 4096 }
        PanelPageSummary.summarize = { _, _, _ -> LocalSummary.Outcome.Done("Two ferries a day.", 1) }
    }

    @Suppress("UNCHECKED_CAST")
    @After fun restore() {
        PanelPageSummary.web = saved[0] as () -> WebSettings
        PanelPageSummary.read = saved[1] as suspend (app.heyrobot.core.Abort, WebUrl, String) -> PageGet.Got?
        PanelPageSummary.model = saved[2] as suspend () -> Int?
        PanelPageSummary.summarize = saved[3] as suspend (app.heyrobot.core.web.PagePlan, String, (LocalSummary.Progress) -> Unit) -> LocalSummary.Outcome
    }

    private fun summarize(host: Host): Host {
        runBlocking {
            launch { PanelPageSummary.handle("reply", PageSummaryAsk(url.toString(), "en"), host, PageSummaryRouter.Picked(url, PageSummaryRouter.From.SAID)) {} }.join()
        }
        return host
    }

    @Test fun noHelperAfterThePhonesSummary() {
        val h = summarize(Host(Helpers()))
        assertTrue(h.texts.toString(), h.texts.last().contains("Two ferries a day."))
        assertEquals(0, h.hs.asked)
    }

    @Test fun theHelperWhenTheModelIsNotReady() {
        PanelPageSummary.model = { null }
        assertEquals(1, summarize(Host(Helpers())).hs.asked)
    }

    @Test fun theHelperWhenTheSummaryFailed() {
        PanelPageSummary.summarize = { _, _, _ -> LocalSummary.Outcome.Failed("the model stopped", 0) }
        assertEquals(1, summarize(Host(Helpers())).hs.asked)
    }

    @Test fun noHelperHandsFreeOrInCarMode() {
        PanelPageSummary.model = { null }
        assertEquals(0, summarize(Host(Helpers(), handsFree = true)).hs.asked)
        assertEquals(0, summarize(Host(Helpers(driving = true))).hs.asked)
    }

    @Test fun noHelperAfterAStop() {
        PanelPageSummary.summarize = { _, _, _ -> currentCoroutineContext().cancel(); LocalSummary.Outcome.Failed("stopped", 0) }
        assertEquals(0, summarize(Host(Helpers())).hs.asked)
    }
}
