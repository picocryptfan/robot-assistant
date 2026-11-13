// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.web.Passage
import app.heyrobot.core.web.Passages
import app.heyrobot.core.web.WebWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * fix-wiki-founder-141 (phone-1.4.0-rc6 row 37): « Qui a fondé Québec ? » found Québec (ville), but its founding
 * sentence ("fondée", feminine) shared no term with the question's "fondé", so BM25 kept the passages that say
 * "Québec" most and the model answered that Québec is a capital.
 */
class WikiFounderTest {
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("America/Halifax"))

    /** A long article: [lead], then [filler] paragraphs that name [name] often, then [fact] deep in its history. */
    private fun article(lead: String, name: String, fact: String, filler: List<String>): String {
        val body = (0 until 14).map { i -> "${filler[i % filler.size]} $name ${filler[(i + 1) % filler.size]} $name, $name et $name. " +
            "${filler[(i + 2) % filler.size]} $name ${filler[(i + 3) % filler.size]}." }
        return (listOf(lead) + body.take(9) + fact + body.drop(9)).joinToString("\n\n")
    }

    private val fillerFr = listOf(
        "Le port de", "accueille chaque été des navires de croisière venus de partout, et les quais restaurés attirent les promeneurs",
        "Le climat de", "connaît des hivers longs et enneigés et des étés chauds et humides, avec des précipitations régulières",
        "L'économie de", "repose sur l'administration publique, le tourisme, la défense, les services financiers et l'assurance",
        "Les quartiers de", "comptent des rues étroites, des maisons de pierre, des escaliers, des places et des jardins publics")
    private val fillerEn = listOf(
        "The port of", "welcomes cruise ships from around the world every summer, and its restored quays draw walkers",
        "The climate of", "brings long snowy winters and warm humid summers, with steady rain and snow through the year",
        "The economy of", "rests on public administration, tourism, defence, financial services and insurance companies",
        "The neighbourhoods of", "hold narrow streets, stone houses, stairways, squares and public gardens along the river")

    private val villeFr = article("Québec est la capitale nationale de la province de Québec, au Canada. Elle est située sur la rive nord du fleuve Saint-Laurent.",
        "Québec", "La ville a été fondée le 3 juillet 1608 par l'explorateur Samuel de Champlain, sur le site de l'ancien village iroquoien de " +
            "Stadaconé, au pied du cap Diamant, là où le fleuve se rétrécit, ce qui lui donne son nom algonquien, et elle devient vite le cœur de la Nouvelle-France.",
        fillerFr)
    private val provinceFr = article("Le Québec est une province du Canada, la plus grande par sa superficie. Sa capitale est la ville de Québec.",
        "Québec", "Le territoire compte de nombreux lacs et rivières, des forêts boréales immenses, des montagnes anciennes usées par les glaciers, " +
            "des terres agricoles fertiles le long du fleuve et une toundra au nord où vivent les Inuits depuis des millénaires sans interruption.",
        fillerFr)
    private val cityEn = article("Quebec City is the capital city of the Canadian province of Quebec. It lies on the north shore of the Saint Lawrence River.",
        "Quebec City", "The city was founded on 3 July 1608 by the explorer Samuel de Champlain, on the site of the former Iroquoian village of " +
            "Stadacona, at the foot of Cape Diamond where the river narrows, which gives it its Algonquin name, and it soon became the heart of New France.",
        fillerEn)

    private val provinceEn = article("Quebec is a province of Canada, the largest by area. Its capital is Quebec City.",
        "Quebec", "The territory holds many lakes and rivers, vast boreal forests, old mountains worn down by glaciers, fertile farmland " +
            "along the river and a tundra in the north where the Inuit have lived for thousands of years without a break.",
        fillerEn)

    private fun excerpt(question: String, copy: FakeCopy): List<Passage> {
        val found = WikiAnswer.answer(question, listOf(copy), now)
        assertNotNull(question, found)
        found!!
        assertTrue(question, found.passages.sumOf { it.tokens + Passages.OVERHEAD } <= WikiAnswer.TOKENS)
        return found.passages
    }

    @Test
    fun frenchFounderSentenceDeepInTheArticleReachesTheModel() {
        val copy = FakeCopy("fr", pages = mapOf("Québec (ville)" to villeFr, "Québec" to provinceFr))
        val passages = excerpt("Qui a fondé Québec ?", copy)
        assertTrue(passages.joinToString(" | ") { it.text.take(60) }, passages.any { "Champlain" in it.text })
    }

    @Test
    fun englishFounderSentenceDeepInTheArticleReachesTheModel() {
        val passages = excerpt("Who founded Quebec City?", FakeCopy("en", pages = mapOf("Quebec City" to cityEn)))
        assertTrue(passages.joinToString(" | ") { it.text.take(60) }, passages.any { "Champlain" in it.text })
    }

    /**
     * fix-wiki-fact-test-141 (phone-wiki-141 row 37's English twin): "Who founded Quebec?" asks only "Quebec", so the title
     * index gives the province and the city; the city's founding sentence still reaches the model, as on the Pixel 8.
     */
    @Test
    fun englishFounderOfQuebecGetsTheCityAndItsFounder() {
        val copy = FakeCopy("en", pages = mapOf("Quebec" to provinceEn, "Quebec City" to cityEn))
        val found = WikiAnswer.answer("Who founded Quebec?", listOf(copy), now)!!
        assertTrue(found.byTitle)
        assertEquals(setOf("Quebec", "Quebec City"), found.articles.map { it.title }.toSet())
        assertTrue(found.passages.joinToString(" | ") { it.text.take(60) }, found.passages.any { "Champlain" in it.text })
    }

    @Test
    fun frenchPastParticiplesShareOneStem() {
        listOf(listOf("fondé", "fondée", "fondés", "fondées", "fonde"), listOf("inventé", "inventée", "inventées"), listOf("découvert", "découverte"))
            .forEach { forms -> assertEquals(forms.toString(), 1, forms.map { WebWords.stem(WebWords.fold(it)) }.toSet().size) }
        // English keeps its stems: "founded" and "founder" as before.
        assertEquals("found", WebWords.stem("founded"))
        assertEquals(WebWords.stem("free"), "fre")
    }

    @Test
    fun aFactInTheLeadStillLeadsTheExcerpt() {
        val lead = "Québec est la capitale du Québec, fondée par Samuel de Champlain en 1608, sur la rive nord du fleuve Saint-Laurent, au pied du cap Diamant."
        val copy = FakeCopy("fr", pages = mapOf("Québec (ville)" to (listOf(lead) + fillerFr.chunked(2).map { it.joinToString(" Québec ") + "." }).joinToString("\n\n")))
        val passages = excerpt("Qui a fondé Québec ?", copy)
        assertEquals(0, passages.first().index)
        assertTrue("Champlain" in passages.first().text)
    }
}
