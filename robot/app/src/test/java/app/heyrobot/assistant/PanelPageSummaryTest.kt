// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.Source
import app.heyrobot.assistant.ask.SharedItem
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Attachment
import app.heyrobot.core.Card
import app.heyrobot.core.TurnRole
import app.heyrobot.core.ask.SharedKind
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.web.AnswerCard
import app.heyrobot.core.web.CardSource
import app.heyrobot.core.web.KeepSources
import app.heyrobot.core.web.PageSummaryAsk
import app.heyrobot.core.web.PageSummaryRouter.From
import app.heyrobot.core.web.SearchService
import app.heyrobot.core.web.WebUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// "Summarize this page" in the panel: which page is read (the link in your words, the one shared, the screen's, the last
// web answer's source), a shared link with nothing typed, and the History line as Settings › Web search keeps sources.
class PanelPageSummaryTest {
    private fun shared(text: String) = SharedItem(SharedKind.TEXT, text = text)
    private fun answer(url: String) = UiTurn("a", TurnRole.ASSISTANT, "",
        web = AnswerCard(SearchService.values().first(), "q", listOf(CardSource(1, "t", WebUrl.domain(url), url)), emptyList()))
    private val ask = PageSummaryAsk(null, "en")
    private val screen = "Read more at https://screen.example.org/a"
    private val turns = listOf(answer("https://answer.example.net/b"))
    private val photo = SharedItem(SharedKind.PHOTO, jpeg = ByteArray(1))
    private val screenTurns = listOf(UiTurn("u", TurnRole.USER, "what's this?", Attachment.SCREEN), UiTurn("r", TurnRole.ASSISTANT, "An article."))
    private val on = { true }

    @Test fun wordsFirst() {
        val p = PanelPageSummary.pick(PageSummaryAsk("https://said.example.com/x", "en"), shared("https://shared.example.com/y"), screen, turns)!!
        assertEquals(From.SAID, p.from)
        assertEquals("said.example.com", p.url.host)
    }

    @Test fun sharedBeforeScreenAndAnswer() {
        val p = PanelPageSummary.pick(ask, shared("A title https://shared.example.com/y"), screen, turns)!!
        assertEquals(From.SHARED, p.from)
        assertEquals("shared.example.com", p.url.host)
    }

    /** H1: a shared photo has its picture. M3: the screen only when your last question went with it. */
    @Test fun screenOnlyAfterAScreenQuestion() {
        assertEquals(From.SCREEN, PanelPageSummary.pick(ask, photo, screen, screenTurns)!!.from)
        assertEquals(From.SCREEN, PanelPageSummary.pick(ask, null, screen, screenTurns)!!.from)
        // The newest reply is the web answer: its page, not some link on the screen.
        assertEquals(From.LAST_ANSWER, PanelPageSummary.pick(ask, null, screen, turns)!!.from)
        // Nothing before: ask which page rather than read the first link on the screen; so after a screen without one.
        assertNull(PanelPageSummary.pick(ask, null, screen, emptyList()))
        assertNull(PanelPageSummary.pick(ask, null, "no link here", screenTurns))
    }

    /** M3: the last web answer's page only while it is the newest reply. */
    @Test fun lastAnswerOnlyWhenNewest() {
        val later = turns + UiTurn("u2", TurnRole.USER, "thanks") + UiTurn("r2", TurnRole.ASSISTANT, "You're welcome.")
        assertNull(PanelPageSummary.pick(ask, null, null, later))
    }

    @Test fun lastAnswerThenNothing() {
        val p = PanelPageSummary.pick(ask, null, "no link here", turns)!!
        assertEquals(From.LAST_ANSWER, p.from)
        assertEquals("answer.example.net", p.url.host)
        assertNull(PanelPageSummary.pick(ask, null, null, emptyList()))
    }

    @Test fun sharedLinkWithNothingTyped() {
        val a = PanelPageSummary.route("", shared("https://news.example.com/story"), on)!!
        assertFalse(a.fromWords)
        assertEquals("en", PanelPageSummary.route("summarize", shared("A headline\nhttps://news.example.com/story"), on)!!.lang)
        assertNull(PanelPageSummary.route("", photo, on))
        assertNull(PanelPageSummary.route("", shared("word ".repeat(100) + "https://news.example.com/story"), on))
        assertNull(PanelPageSummary.route("summarize", null, on))
    }

    /** M4: pages not read from this phone, a shared link with nothing typed goes where it went before; your words still route. */
    @Test fun sharedLinkWithoutPagesGoesAsBefore() {
        assertNull(PanelPageSummary.route("", shared("https://news.example.com/story")) { false })
        assertTrue(PanelPageSummary.route("summarize this page", shared("https://news.example.com/story")) { false }!!.fromWords)
    }

    /** M1: a shared picture or passage goes to the model as shared, never swapped for another page. */
    @Test fun sharedPictureOrPassageNotSwapped() {
        assertNull(PanelPageSummary.route("summarize this article", photo, on))
        assertNull(PanelPageSummary.route("summarize this article", shared("word ".repeat(100) + "https://other.example.com/x"), on))
        assertNull(PanelPageSummary.route("summarize this page", shared("Notes https://a.example.com/1 https://b.example.com/2"), on))
        val link = shared("A title https://shared.example.com/y")
        assertEquals(From.SHARED, PanelPageSummary.pick(PanelPageSummary.route("summarize this article", link, on)!!, link, screen, turns)!!.from)
        assertEquals(From.SAID, PanelPageSummary.pick(PanelPageSummary.route("summarize https://said.example.com/x", photo, on)!!, photo, screen, turns)!!.from)
    }

    /** M2: Stop ends the page read at once: it runs through OnlineRun, which cancels it, not after the blocking read returns. */
    @Test fun stopCancelsTheRead() {
        val src = java.io.File("src/main/java/app/heyrobot/assistant/PanelPageSummary.kt").readText()
        assertTrue(src.contains("OnlineRun.run(abort, READ_MS) { a -> PageGet.get("))
        assertFalse(src.contains("invokeOnCompletion"))
    }

    @Test fun historyLineFollowsKeepSources() {
        val u = WebUrl.parse("https://news.example.com/2026/story")!!
        assertTrue(PanelPageSummary.label(KeepSources.DOMAINS, u).render(Langs.EN).endsWith("news.example.com"))
        assertTrue(PanelPageSummary.label(KeepSources.LINKS, u).render(Langs.EN).endsWith("https://news.example.com/2026/story"))
        assertFalse(PanelPageSummary.label(KeepSources.NONE, u).render(Langs.EN).contains("example"))
    }

    /** fix-routing-1.4-b (review-fix-routing-1.4 L1): handle() itself with a fake Host. A screen's link is read only after
     *  a tap on its card; Cancel reads nothing and says so; hands-free off screen, no card and no read; L2: a wake-word
     *  turn with the panel on screen gets the card. */
    @Test fun aScreenLinkWaitsForItsCardBeforeTheRead() {
        val web = PanelPageSummary.web
        val read = PanelPageSummary.read
        var reads = 0
        PanelPageSummary.web = { app.heyrobot.core.web.WebSettings(service = SearchService.SEARXNG_HOME) }
        PanelPageSummary.read = { _, _, _ -> reads++; app.heyrobot.helpers.web.PageGet.Got.Skipped(app.heyrobot.core.web.PageSkip.TOO_SLOW) }
        try {
            assertTrue(app.heyrobot.core.web.WebGate.pagesFromPhone(PanelPageSummary.web()) > 0)
            val picked = PanelPageSummary.pick(ask, null, screen, screenTurns)!!
            assertEquals(From.SCREEN, picked.from)
            fun run(h: CardHost): CardHost { kotlinx.coroutines.runBlocking { PanelPageSummary.handle("r", ask, h, picked) {} }; return h }
            val no = run(CardHost(yes = false, handsFree = false, onScreen = true))
            assertEquals(1, no.asked.size)
            assertEquals(0, reads)
            assertEquals(ActionStatus.DECLINED, no.shown.single().status)
            assertTrue(no.lines.last(), no.lines.last().contains("screen.example.org"))
            val yes = run(CardHost(yes = true, handsFree = false, onScreen = true))
            assertEquals(1, yes.asked.size)
            assertEquals(1, reads)
            assertEquals(ActionStatus.DONE, yes.shown.single().status)
            val away = run(CardHost(yes = true, handsFree = true, onScreen = false))
            assertTrue(away.asked.isEmpty())
            assertEquals(1, reads)
            val woke = run(CardHost(yes = true, handsFree = true, onScreen = true))
            assertEquals(1, woke.asked.size)
            assertEquals(2, reads)
        } finally {
            PanelPageSummary.web = web
            PanelPageSummary.read = read
        }
    }

    private class CardHost(val yes: Boolean, override val handsFree: Boolean, override val onScreen: Boolean) : app.heyrobot.assistant.skills.PanelSkills.Host {
        val asked = mutableListOf<Card>()
        val shown = mutableListOf<Card>()
        val lines = mutableListOf<String>()
        override val locked = false
        override val speak = false
        override fun langs(said: String?, router: app.heyrobot.core.i18n.Lang?): Langs = Langs.EN
        override fun text(replyId: String, text: String) { lines += text }
        override fun append(replyId: String, text: String) { lines += text }
        override fun say(sentence: String, personal: Boolean) {}
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean { asked += card; return yes }
        override suspend fun pick(replyId: String, card: Card, tapOnly: String?): Int = error("no pick here")
        override fun show(replyId: String, card: Card) { shown += card }
        override suspend fun tool(replyId: String, step: app.heyrobot.core.RoutineStep.Tool): app.heyrobot.core.Executed? = null
        override fun finish(replyId: String) {}
        override fun quoted(replyId: String) {}
        override suspend fun leave() {}
        override fun ports(replyId: String) = app.heyrobot.assistant.skills.PanelSkills.SkillPorts(NoActions, NoView,
            app.heyrobot.core.Confirmations(), app.heyrobot.core.messages.MessageTaint.NONE, Langs.EN) { "c1" }
        override val untrusted = false
        override val phoneLock = false
        override val messages = app.heyrobot.core.messages.MessageTaint.NONE
        override val oneShots: app.heyrobot.core.OneShotTransport get() = error("no model in this test")
        override val source: app.heyrobot.core.skills.DraftSource? = null
    }

    private object NoActions : app.heyrobot.core.TurnActions {
        override val settings = app.heyrobot.core.RobotSettings()
        override val locked = false
        override suspend fun prepare(action: app.heyrobot.core.Action): app.heyrobot.core.Prepared = error("no action here")
        override suspend fun run(action: app.heyrobot.core.Action): app.heyrobot.core.Executed = error("no action here")
    }

    private object NoView : app.heyrobot.core.TurnView {
        override fun phase(p: app.heyrobot.core.TurnPhase) {}
        override fun card(c: Card) {}
        override var text = ""
        override fun replaceText(text: String) { this.text = text }
        override fun paragraphBreak() {}
        override fun say(sentence: String) {}
    }

    /** summarize-page-c L3: the reply is marked a page (ChatTurn.page), not the copy's content; W-18: the skills' host carries the helper card. */
    @Test fun pageFlagAndHelperCard() {
        val ctl = java.io.File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
        assertTrue(ctl.contains("PanelPageSummary.handle(replyId, pageAsk, skillHost, pagePick) { _state.update { s -> s.copy(turns = s.turns.map { if (it.id == replyId) it.copy(page = true) else it }) } }"))
        assertTrue(ctl.contains("page = ui?.page == true"))
        assertFalse(UiTurn("x", TurnRole.ASSISTANT, "").page)
        val src = java.io.File("src/main/java/app/heyrobot/assistant/PanelPageSummary.kt").readText()
        assertTrue(src.contains("PanelHelp.send(replyId, host, h, PageHelp.request(domain, content.text, lang), l)"))
        assertTrue(src.contains("PanelHelp.offered(replyId, host) ?: return false"))
        assertFalse(src.contains("val app = "))
    }

    /** car-park-test-1.4 (review-fix-batch-19-b L1): Car mode keeps "once you're parked" however often the screen link is
     *  asked for. Robot has no parked signal, so the panel hands the router Car mode itself, unchanged, in both calls: a
     *  value worked out from it (asked again, a time, a count) would let a second ask lift the wait. */
    @Test fun carModeReachesTheScreenLinkRouterUnchanged() {
        val root = System.getProperty("robot.rootDir")?.let(::File) ?: File(System.getProperty("user.dir") ?: ".").absoluteFile.parentFile
        val f = File(root, "app/src/main/java/app/heyrobot/assistant/PanelPageSummary.kt")
        val code = Source.strip(f.readText(), keepStrings = false)
        fun args(call: String): List<List<String>> {
            val out = mutableListOf<List<String>>()
            var at = code.indexOf(call)
            while (at >= 0) {
                var i = at + call.length; var depth = 1; val parts = mutableListOf<String>(); val cur = StringBuilder()
                while (depth > 0) {
                    val c = code[i++]
                    when {
                        c == '(' || c == '[' || c == '{' -> { depth++; cur.append(c) }
                        c == ')' || c == ']' || c == '}' -> { depth--; if (depth > 0) cur.append(c) }
                        c == ',' && depth == 1 -> { parts += cur.toString(); cur.clear() }
                        else -> cur.append(c)
                    }
                }
                parts += cur.toString()
                out += parts.map { it.replace(Regex("\\s+"), "") }
                at = code.indexOf(call, i)
            }
            return out
        }
        val go = args("PageSummaryRouter.go(")
        val look = args("PageSummaryRouter.look(")
        assertEquals(1, go.size)
        assertEquals(1, look.size)
        assertEquals("go(from, handsFree, driving, onScreen)", "CarModeController.on", go.single()[2])
        assertEquals("look(driving)", listOf("CarModeController.on"), look.single())
    }
}
