// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.BrainMsg
import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import app.heyrobot.core.i18n.WebStrings
import app.heyrobot.core.skills.HelpJob
import app.heyrobot.core.DoneReason
import app.heyrobot.core.LocalSummary
import app.heyrobot.core.OneShotBudget
import app.heyrobot.core.OneShotResult
import app.heyrobot.core.OneShotTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageSummaryTest {
    private fun page(text: String, js: Boolean = false) =
        PageContent("A page", text.split("\n\n").map { PageBlock(PageBlock.Kind.PARAGRAPH, it) }, needsJavaScript = js, truncated = false)

    private val english = "The city council voted on Tuesday to extend the bike lane on Main Street by 2.4 km, and the work is to start in May. "

    @Test fun summarizeThisPageRoutesInEnglishAndFrench() {
        for (q in listOf("Summarize this page", "can you summarize this article for me?", "Hey robot, sum up that link", "what does this page say",
            "what's this article about?", "give me a summary of this web page", "TL;DR this article", "summarise the website please")) {
            assertEquals(q, "en", PageSummaryRouter.route(q)?.lang)
        }
        for (q in listOf("Résume cette page", "résume-moi cet article", "fais-moi un résumé de cette page", "Peux-tu résumer l’article ?",
            "qu'est-ce que dit cette page ?", "de quoi parle cet article", "donne-moi un résumé du lien")) {
            assertEquals(q, "fr", PageSummaryRouter.route(q)?.lang)
        }
    }

    @Test fun summaryTurnsMeantForSomethingElseNeverRouteHere() {
        for (q in listOf("summarize my notes", "summarize our chat", "summarize this conversation", "summarize the meeting", "summarize this",
            "résume notre conversation", "résume mes notes", "search the web for a summary of this page's topic", "summarize", "what does this word mean",
            "read this page", "résume")) {
            assertNull(q, PageSummaryRouter.route(q))
        }
    }

    @Test fun aLinkInTheWordsIsTheOneRead() {
        val a = PageSummaryRouter.route("summarize https://example.com/news/bikes.")
        assertEquals("https://example.com/news/bikes", a?.url)
        val b = PageSummaryRouter.route("résume cet article https://exemple.ca/a?b=1")
        assertEquals("https://exemple.ca/a?b=1", b?.url)
    }

    @Test fun thePageComesFromTheWordsThenSharedThenScreenThenLastAnswer() {
        val said = PageSummaryAsk("https://a.example/1", "en")
        val none = PageSummaryAsk(null, "en")
        assertEquals(PageSummaryRouter.From.SAID, PageSummaryRouter.pick(said, "https://b.example", "https://c.example", "https://d.example")?.from)
        assertEquals(PageSummaryRouter.From.SHARED, PageSummaryRouter.pick(none, "look https://b.example/x", "https://c.example", null)?.from)
        assertEquals(PageSummaryRouter.From.SCREEN, PageSummaryRouter.pick(none, "no link", "Menu\nhttps://c.example/story\nShare", "https://d.example")?.from)
        val last = PageSummaryRouter.pick(none, null, "nothing here", "https://d.example/read")
        assertEquals(PageSummaryRouter.From.LAST_ANSWER, last?.from)
        assertNull(PageSummaryRouter.pick(none, null, "nothing", null))
        // http goes as https, as a result's link does (PageRules refuses plain http).
        assertTrue(PageSummaryRouter.pick(PageSummaryAsk("http://e.example/p", "en"), null, null, null)!!.url.https)
    }

    @Test fun brokenPagesSayWhy() {
        assertEquals(PageProblem.NEEDS_JS, PageSummaryPlanner.problem(page(english.repeat(5), js = true)))
        assertEquals(PageProblem.NOTHING, PageSummaryPlanner.problem(page("Home\n\nAbout\n\nContact")))
        assertEquals(PageProblem.SIGN_IN, PageSummaryPlanner.problem(page("Council votes on bike lanes\n\nSubscribe to continue reading. Already a subscriber? Log in.")))
        assertEquals(PageProblem.SIGN_IN, PageSummaryPlanner.problem(page("Le conseil vote\n\nCet article est réservé aux abonnés. Déjà abonné ? Connectez-vous pour lire la suite.")))
        assertNull(PageSummaryPlanner.problem(page(english.repeat(5))))
        // A long article whose footer asks you to subscribe is still read.
        assertNull(PageSummaryPlanner.problem(page(english.repeat(40) + "\n\nSubscribe to continue reading our newsletter.")))
    }

    @Test fun aPageInAnotherLanguageIsNoted() {
        val german = "Der Stadtrat hat am Dienstag beschlossen, den Radweg in der Hauptstraße um zwei Kilometer zu verlängern. Die Arbeiten beginnen im Mai. "
        assertTrue(PageSummaryPlanner.otherLanguage(german.repeat(8)))
        assertFalse(PageSummaryPlanner.otherLanguage(english.repeat(8)))
        assertFalse(PageSummaryPlanner.otherLanguage("Le conseil municipal a voté mardi pour prolonger la piste cyclable de la rue Principale, et les travaux commencent en mai. ".repeat(8)))
    }

    @Test fun aLongPageIsCutAtTheCapAndSaysSo() {
        val long = page((1..400).joinToString("\n\n") { "Paragraph $it. $english" })
        val p = PageSummaryPlanner.plan(long, "en")
        assertEquals(PageSummaryPlanner.MAX_PARTS, p.parts)
        assertTrue(p.of > p.parts)
        assertTrue(p.cut)
        assertTrue(p.plan.map.all { it.system == PageSummaryPlanner.MAP })
        assertTrue(p.plan.map.first().user.startsWith("Part 1 of ${p.of}:"))
        val short = PageSummaryPlanner.plan(page(english.repeat(6)), "fr")
        assertEquals(1, short.parts)
        assertFalse(short.cut)
        assertEquals(PageSummaryPlanner.MAP_FR, short.plan.map.single().system)
    }

    @Test fun theMapReduceRunsOnThePagesOwnPrompts() = runBlocking {
        val seen = ArrayList<BrainMsg.OneShot>()
        val model = object : OneShotTransport {
            override suspend fun run(req: BrainMsg.OneShot, budget: OneShotBudget, onText: (String) -> Unit): OneShotResult {
                seen += req
                return OneShotResult.Done("- note ${seen.size}", DoneReason.END)
            }
        }
        val plan = PageSummaryPlanner.plan(page((1..150).joinToString("\n\n") { "Paragraph $it. $english" }), "en")
        assertTrue(plan.parts > 1)
        val r = LocalSummary(model, OneShotBudget(null, 1_000)).run(emptyList(), "en", 4_096, plan = plan.plan, reduce = PageSummaryPlanner.reduce("en"))
        assertTrue(r is LocalSummary.Outcome.Done)
        assertEquals(plan.parts + 1, seen.size)
        assertTrue(seen.dropLast(1).all { it.system == PageSummaryPlanner.MAP })
        assertEquals(PageSummaryPlanner.REDUCE, seen.last().system)
        assertNotNull((r as LocalSummary.Outcome.Done).text)
    }

    @Test fun theSpokenSummaryDropsBulletsAndMarks() {
        assertEquals("The council voted. **Bike** lanes grow. Work starts in May.".replace("**", ""),
            PageSummaryPlanner.spoken("The council voted.\n\n- **Bike** lanes grow\n* Work starts in May."))
    }

    @org.junit.Test fun sharedLinkRoutes() {
        org.junit.Assert.assertEquals("https://news.example.com/a", PageSummaryRouter.sharedLink("A headline\nhttps://news.example.com/a"))
        org.junit.Assert.assertNull(PageSummaryRouter.sharedLink("x".repeat(400) + " https://news.example.com/a"))
        org.junit.Assert.assertNull(PageSummaryRouter.sharedLink("https://a.example.com/1 https://b.example.com/2"))
        org.junit.Assert.assertFalse(PageSummaryRouter.route("", "https://news.example.com/a")!!.fromWords)
        org.junit.Assert.assertEquals("fr", PageSummaryRouter.route("Résume", "https://news.example.com/a")!!.lang)
        org.junit.Assert.assertEquals("en", PageSummaryRouter.route("summarize", "https://news.example.com/a")!!.lang)
        // L4: a bare "resume" is English as much as French: the turn's reply language.
        org.junit.Assert.assertEquals("", PageSummaryRouter.route("resume", "https://news.example.com/a")!!.lang)
        org.junit.Assert.assertNull(PageSummaryRouter.route("summarize"))
        org.junit.Assert.assertNull(PageSummaryRouter.route("", null))
        org.junit.Assert.assertNull(PageSummaryRouter.route("what's the weather", "https://news.example.com/a"))
    }

    /** summarize-page-c L3: a page summary's reply keeps the copy of Wikipedia's next answer untrusted, saved and read back too. */
    @Test fun aPageSummaryStaysContentForTheCopy() {
        val q = ChatTurn("q", TurnRole.USER, "summarize https://example.com/a", 1)
        val a = ChatTurn("a", TurnRole.ASSISTANT, "Robot's summary · example.com", 2, page = true)
        assertTrue(TurnRules.untrusted(listOf(q, a)))
        assertTrue(TurnRules.untrustedForCopy(listOf(q, a)))
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(StoredConversation("c", 1, 2, listOf(q, a))))!!.turns.last()
        assertTrue(back.page)
        assertTrue(back.content)
        assertTrue(TurnRules.untrustedForCopy(listOf(q, back)))
        assertFalse(TurnRules.untrustedForCopy(listOf(q, a.copy(page = false))))
    }

    /** summarize-page-c L4: DuckDuckGo Instant and Wikipedia only are told their service opens no page, not their settings. */
    @Test fun shortAnswerServicesSaySo() {
        assertTrue(PageSummaryRouter.shortAnswersOnly(SearchService.DDG_INSTANT))
        assertTrue(PageSummaryRouter.shortAnswersOnly(SearchService.WIKIPEDIA_ONLY))
        assertFalse(PageSummaryRouter.shortAnswersOnly(SearchService.BRAVE))
        assertFalse(PageSummaryRouter.shortAnswersOnly(null))
        assertFalse(WebStrings.pageShortAnswers().en().contains("settings"))
    }

    /** summarize-page-c W-18: offered only when the phone's model couldn't; the site goes, never the address; the text is capped. */
    @Test fun helperOfferSendsTheSiteNotTheAddress() {
        assertTrue(PageHelp.offers(PageSumEnd.NO_MODEL))
        assertTrue(PageHelp.offers(PageSumEnd.FAILED))
        // fix-batch-15 M2 (review-batch-15): a summary of the first parts no longer ends in a helper card (it was offered).
        assertFalse(PageHelp.offers(PageSumEnd.FAILED, handsFree = true))
        assertFalse(PageHelp.offers(PageSumEnd.NO_MODEL, driving = true))
        assertFalse(PageHelp.offers(PageSumEnd.DONE))
        assertFalse(PageHelp.offers(PageSumEnd.STOPPED))
        assertFalse(PageHelp.offers(PageSumEnd.NOT_READ))
        val r = PageHelp.request("news.example.com", "x".repeat(PageHelp.CHARS + 500), "fr")
        assertEquals(HelpJob.SUMMARY, r.job)
        assertTrue(r.words.startsWith("Page from news.example.com:"))
        assertTrue(r.words.endsWith("[…]"))
        assertTrue(r.chars < PageHelp.CHARS + 1_000)
        assertTrue(r.system.contains("French"))
        assertFalse(PageHelp.request("a.example", "hello", "en").words.contains("[…]"))
        assertTrue(WebStrings.pageHelpOffer("name" to "Jarvis").en().contains("Jarvis"))
    }

    // fix-batch-15 L4 (review-batch-15): over 40,000 characters the offer says only the start goes, never "whole page".
    @Test fun helperOfferSaysWhenOnlyTheStartGoes() {
        assertEquals(WebStrings.pageHelpOffer("name" to "Jarvis").en(), PageHelp.offer("Jarvis", "a short page").en())
        assertEquals(WebStrings.pageHelpStart("name" to "Jarvis").en(), PageHelp.offer("Jarvis", "x".repeat(PageHelp.CHARS + 1)).en())
    }

    // fix-batch-15 L5 (review-batch-15): a Chinese or Japanese character counts about a token on the cost line, not 1/3.5.
    @Test fun helperCostCountsWideScriptsAsAboutATokenEach() {
        assertEquals(10, app.heyrobot.core.HelperCost.charsOf("abcdefghij"))
        assertEquals(14, app.heyrobot.core.HelperCost.charsOf("中文网页"))
        assertEquals(14, app.heyrobot.core.HelperCost.charsOf("ひらがな"))
    }
}
