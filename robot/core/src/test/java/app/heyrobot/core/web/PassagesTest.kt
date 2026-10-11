package app.heyrobot.core.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** BM25 passage choice (design A6.1 Passages, A8): order on fixtures, the lead bonus, per-page and total caps, stemming. */
class PassagesTest {
    private fun page(vararg blocks: PageBlock) = PageContent(null, blocks.toList(), needsJavaScript = false, truncated = false)

    private fun p(text: String) = PageBlock(PageBlock.Kind.PARAGRAPH, text)

    private val breville = page(
        PageBlock(PageBlock.Kind.HEADING, "How to descale a Breville Barista Express", 1),
        p("Scale builds up inside every espresso machine over time, and hard water makes it worse."),
        PageBlock(PageBlock.Kind.HEADING, "Steps", 2),
        PageBlock(PageBlock.Kind.ITEM, "Fill the Breville tank with descaling solution."),
        PageBlock(PageBlock.Kind.ITEM, "Press and hold the 1 CUP and 2 CUP buttons to start the descale cycle on the Barista Express."),
        PageBlock(PageBlock.Kind.HEADING, "About us", 2),
        p("Coffee Notes has reviewed grinders and kettles since 2009. Subscribe for weekly deals."),
    )
    private val generic = page(
        p("Our espresso machine buying guide covers twenty machines at every price, from small single boilers to dual boiler machines " +
            "that cost as much as a used car."),
        p("Kettles, grinders and scales round out a good home coffee bar, and a good grinder matters more than any other part of it " +
            "for the taste in the cup."),
    )

    @Test fun passagesFollowHeadingsAndKeepListMarks() {
        val ps = Passages.split(1, page(
            PageBlock(PageBlock.Kind.HEADING, "Steps", 2),
            PageBlock(PageBlock.Kind.ITEM, "Empty the tray."),
            PageBlock(PageBlock.Kind.ITEM, "Fill the tank."),
            PageBlock(PageBlock.Kind.HEADING, "Notes", 2),
            p("Rinse after.[2] Twice is better [citation needed] , really."),
        ))
        assertEquals(listOf("• Empty the tray. • Fill the tank.", "Rinse after. Twice is better, really."), ps.map { it.text })
        assertEquals(listOf("Steps", "Notes"), ps.map { it.heading })
        assertEquals(listOf(0, 1), ps.map { it.index })
        assertTrue(ps.all { it.source == 1 })
    }

    @Test fun longBlocksAreCutAtSentences() {
        val sentence = "The descale cycle pushes the solution through the group head and the steam wand in turn. "
        val ps = Passages.split(2, page(p(sentence.repeat(20))))
        assertTrue(ps.size > 1)
        assertTrue(ps.all { it.text.split(' ').size <= Passages.MAX_WORDS })
        assertTrue(ps.all { it.text.endsWith("in turn.") })
        // One sentence longer than a passage is cut at words.
        val run = Passages.split(2, page(p("word ".repeat(400))))
        assertEquals(listOf(160, 160, 80), run.map { it.text.split(' ').size })
    }

    @Test fun bm25RanksTheAnsweringPassageFirst() {
        val ranked = Passages.rank("how do I descale a Breville Barista Express", Passages.split(1, breville) + Passages.split(2, generic))
            .sortedByDescending { it.score }
        assertTrue(ranked.first().text, ranked.first().text.contains("descale cycle on the Barista Express"))
        assertTrue(ranked.last().score < ranked.first().score)
        val about = ranked.first { it.heading == "About us" }
        assertEquals(0.0, about.score, 1e-9)
    }

    @Test fun shortParagraphsJoinTheNextLongOnesStandAlone() {
        val ps = Passages.split(1, page(p("Short intro."), p("Another short one."), p("word ".repeat(30).trim()), p("Tail.")))
        assertEquals(listOf("Short intro. Another short one. " + "word ".repeat(30).trim(), "Tail."), ps.map { it.text })
    }

    @Test fun shorterPassagesWinAtEqualTermCounts() {
        val short = Passage(1, 1, "Descale the machine monthly.")
        val long = Passage(2, 1, "Descale the machine monthly, and between times wipe the outside, empty the tray, wash the portafilter, change the filter and " +
            "check the gasket for wear, because a worn gasket leaks and lets the pressure drop when you pull a shot of espresso.")
        val ranked = Passages.rank("descale", listOf(short, long, Passage(3, 1, "Nothing about it here.")))
        assertTrue(ranked[0].score > ranked[1].score)
        assertEquals(0.0, ranked[2].score, 1e-9)
    }

    @Test fun theLeadPassageGetsABonus() {
        val r = Passages.rank("descaling machine", listOf(Passage(1, 0, "Descaling the machine is easy."), Passage(1, 1, "Descaling the machine is easy.")))
        assertTrue(r[0].score > r[1].score)
        // A lead that matches nothing still beats other passages that match nothing.
        val none = Passages.rank("zebra", listOf(Passage(1, 0, "Alpha."), Passage(1, 1, "Beta.")))
        assertTrue(none[0].score > 0 && none[1].score == 0.0)
    }

    @Test fun headingWordsCountHalf() {
        val r = Passages.rank("descale", listOf(Passage(1, 1, "Fill the tank.", heading = "Descale"), Passage(2, 1, "Descale: fill the tank.")))
        assertTrue(r[1].score > r[0].score)
        assertTrue(r[0].score > 0)
    }

    @Test fun frenchAndEnglishLightStemming() {
        assertEquals("cheval", WebWords.stem("chevaux"))
        assertEquals("bateau", WebWords.stem("bateaux"))
        assertEquals("station", WebWords.stem("stations"))
        assertEquals("city", WebWords.stem("cities"))
        assertEquals("class", WebWords.stem("classes"))
        assertEquals("bus", WebWords.stem("bus"))
        assertEquals(WebWords.stem("descale"), WebWords.stem("descaling"))
        assertEquals(WebWords.stem("machine"), WebWords.stem("machines"))
        assertEquals(listOf("cheval", "quebec"), WebWords.terms("Les chevaux du Québec"))
        val r = Passages.rank("les chevaux du Québec", listOf(Passage(1, 0, "Intro sans rapport."), Passage(1, 1, "Le cheval canadien vient du Québec."),
            Passage(1, 2, "Autre chose.")))
        assertTrue(r[1].score > r[0].score)
        assertEquals(0.0, r[2].score, 1e-9)
    }

    @Test fun selectionKeepsTheCapsAndPageOrder() {
        val sentence = "Descale the Breville Barista Express with the descale cycle and fresh solution every few months. "
        val big = page(*(1..12).map { p(sentence.repeat(6)) }.toTypedArray())
        val chosen = Passages.select("descale Breville Barista Express", listOf(1 to big, 2 to big, 3 to big))
        val perPage = chosen.groupBy { it.source }.mapValues { (_, l) -> l.sumOf { it.tokens + Passages.OVERHEAD } }
        assertTrue(perPage.toString(), perPage.values.all { it <= Passages.PAGE_TOKENS })
        assertTrue(chosen.sumOf { it.tokens + Passages.OVERHEAD } <= Passages.TOTAL_TOKENS)
        assertEquals(chosen.sortedWith(compareBy({ it.source }, { it.index })), chosen)
        assertEquals(setOf(1, 2, 3), chosen.map { it.source }.toSet())
        // Pages keep the order they were given in, not their numbers.
        val swapped = Passages.select("descale", listOf(3 to page(p("Descale three.")), 1 to page(p("Descale one."))))
        assertEquals(listOf(3, 1), swapped.map { it.source })
    }

    @Test fun irrelevantPassagesStayOut() {
        val chosen = Passages.select("descale Breville", listOf(1 to breville, 2 to generic))
        assertFalse(chosen.any { it.text.contains("Subscribe") })
        // The generic page's second paragraph matches nothing and isn't a lead.
        assertFalse(chosen.any { it.text.startsWith("Kettles") })
        assertTrue(chosen.first().text, chosen.any { it.text.contains("descale cycle on the Barista Express") })
    }
}
