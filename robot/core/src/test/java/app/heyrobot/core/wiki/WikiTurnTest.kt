// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * P4: typed questions through the panel's turn with the copy (WikiTurn.run, PanelWiki's only way to it), a fake model
 * that answers from the one-shot's first article. Facts are answered with the article named and credited; personal,
 * fresh and command questions never open the copy nor reach the model.
 */
class WikiTurnTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun facts(lang: String) = WikiP0.rows.filter { it.kind == "fact" && it.lang == lang && it.subject != null }.take(10)
    private fun copy(lang: String, rows: List<WikiP0.Row>) = FakeCopy(lang, pages = rows.associate {
        it.subject!! to "${it.subject} is the subject of this article.\n\nIt has a long history in Canada."
    })

    private var models = 0
    private fun ask(q: String, copy: FakeCopy, copies: Int = 1, attached: Boolean = false, helper: Boolean = false, untrusted: Boolean = false) = runBlocking {
        var opened = 0
        WikiTurn.run(q, copies, { opened++; listOf(copy) }, now, emptyList(), spoken = false, attached = attached, helperMode = helper,
            untrusted = untrusted) { shot -> models++; "${shot.sources.first().title} [1] is well known." }.also { if (it == WikiTurn.Result.NotAsked && copy.asked == 0) assertTrue(opened <= 1) }
    }

    private fun answered(lang: String) {
        val rows = facts(lang)
        assertEquals(10, rows.size)
        val copy = copy(lang, rows)
        for (r in rows) {
            val a = ask(r.question, copy)
            assertTrue("${r.question}: $a", a is WikiTurn.Result.Answered)
            val reply = (a as WikiTurn.Result.Answered).reply
            assertTrue("${r.question}: ${reply.articles}", r.subject in reply.articles)
            // P4b: the card names the article with its link on Wikipedia, the licence with its link and the copy's month, in
            // the question's language; the reply's words carry no credit lines; the copy's month is said; no [n] mark is left.
            val card = reply.card
            assertTrue("${r.question}: ${card.articles}", r.subject in card.articles.map { it.title })
            for (art in card.articles) assertEquals(app.heyrobot.core.web.WikipediaWire.articleUrl(lang, art.title), art.url)
            assertEquals(card.articles.map { it.url } + app.heyrobot.core.web.WikipediaWire.LICENCE_URL, card.links)
            assertEquals(if (lang == "fr") "Selon Wikipédia sur ce téléphone, septembre 2026" else "From Wikipedia on this phone, September 2026", card.header)
            assertEquals(if (lang == "fr") "Texte sous licence CC BY-SA 4.0" else "Text under CC BY-SA 4.0", card.licence)
            assertEquals(reply.spoken, reply.shown)
            assertTrue(reply.shown, "CC BY-SA" !in reply.shown)
            assertTrue(reply.spoken, reply.spoken.contains(if (lang == "fr") "septembre 2026" else "September 2026") && "[1]" !in reply.shown)
        }
    }

    @Test fun tenEnglishFactsAreAnsweredWithTheArticleNamed() = answered("en")

    @Test fun tenFrenchFactsAreAnsweredWithTheArticleNamed() = answered("fr")

    @Test fun personalFreshAndCommandQuestionsNeverReachTheCopy() {
        val copy = copy("en", facts("en"))
        val personal = listOf("What is my wife's birthday?", "Who is our dentist?", "Where did I leave my keys?", "Qui est mon médecin?", "Quelle est notre adresse?")
        val fresh = listOf("What's the latest on the election?", "Who won the Stanley Cup?", "Qui est le premier ministre actuel du Canada?")
        val commands = listOf("Set an alarm for 7", "Remind me to call Bob", "Envoie un texto à Marie", "Search the web for Louis Riel")
        for (q in personal + fresh + commands) assertEquals(q, WikiTurn.Result.NotAsked, ask(q, copy))
        assertEquals(0, copy.asked)
        assertEquals(0, models)
    }

    @Test fun noCopyAnAttachmentAHelperOrScreenTextAsBefore() {
        val rows = facts("en")
        val copy = copy("en", rows)
        val q = rows.first().question
        assertEquals(WikiTurn.Result.NotAsked, ask(q, copy, copies = 0))
        assertEquals(WikiTurn.Result.NotAsked, ask(q, copy, attached = true))
        assertEquals(WikiTurn.Result.NotAsked, ask(q, copy, helper = true))
        assertEquals(WikiTurn.Result.NotAsked, ask(q, copy, untrusted = true))
        assertEquals(0, copy.asked + models)
        // Nothing in the copy: the model alone, as before.
        assertEquals(WikiTurn.Result.NotAsked, ask("Who was Zygmunt Qwertyuiop?", copy))
        assertEquals(0, models)
    }

    /** M2 (review-wikipedia-p4): the copy found the article, so no words from the model say its own sentences, never "nothing on". */
    @Test fun noWordsFromTheModelSayTheArticlesOwnSentences() {
        val rows = facts("en")
        val found = WikiAnswer.answer(rows.first().question, listOf(copy("en", rows)), now)!!
        val r = WikiTurn.reply(found, " ")
        assertEquals("${found.articles.first().title} is the subject of this article. It has a long history in Canada. From Wikipedia on this phone, September 2026.", r.spoken)
        assertEquals(r.spoken, r.shown)
        assertEquals(found.articles.map { it.title }, r.card.articles.map { it.title })
    }
}
