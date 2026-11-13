// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * fix-wiki-pick-141: the hits the starters on the Pixel 8 really give (resources/wiki/recorded-hits.tsv, from python-libzim
 * on the pinned files), for the questions rc6 got wrong and the ones it got right. Each fake article is the one sentence
 * recorded for it: the one that answers the subject, or the closest when none does.
 */
class WikiRecordedHitsTest {
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("America/Halifax"))

    private class Row(val id: String, val question: String, val subject: String) {
        val titles = mutableListOf<List<String>>()
        val full = mutableListOf<List<String>>()
    }

    private val rows: List<Row> = run {
        val out = mutableListOf<Row>()
        javaClass.getResourceAsStream("/wiki/recorded-hits.tsv")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }.forEach { l ->
            val f = l.split('\t')
            when (f[0]) {
                "Q" -> out += Row(f[1], f[2], f[3])
                "t" -> out.last().titles += f.drop(1)
                "f" -> out.last().full += f.drop(1)
            }
        }
        out
    }

    /** One row's copy: hit path, hit title, target path, target title, sentence. Counts the articles read. */
    private class Recorded(override val lang: String, val row: Row) : WikiCopy {
        override val date: String? = if (lang == "fr") "2026-10-01" else "2026-09-01"
        var reads = 0
        private val said = row.full.filter { it[4].isNotEmpty() }.associate { it[2] to it[4] }
        private val to = (row.titles + row.full).associate { it[0] to (it[2] to it[3]) }
        override fun titles(query: String, max: Int) = row.titles.take(max).map { WikiCopy.Hit(it[0], it[1]) }
        override fun search(query: String, max: Int) = row.full.take(max).map { WikiCopy.Hit(it[0], it[1]) }
        override fun article(path: String): WikiCopy.Page? {
            reads++
            val (p, t) = to[path] ?: return null
            val text = (said[p] ?: "$t is an article.").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            return WikiCopy.Page(p, t, "<p>$text</p>")
        }
    }

    // What each question must give: its first article, or none (the model answers alone). 37's subject is only « Québec »
    // (WikiRoute keeps no verb), so the title index gives the city, as on rc6: see the report's Next.
    private val want = mapOf(
        "W1" to "Jupiter", "W2" to null, "W3h" to "Mercury (element)", "W4" to "Halifax, Nova Scotia", "W5" to "Louis Riel",
        "31" to "Louis Riel", "32" to "Photosynthèse", "33" to "Chicoutimi", "34" to "René Lévesque", "35" to "Révolution tranquille",
        "36" to "Poutine (plat)", "37" to "Québec (ville)", "38" to "Sirop d'érable", "39" to "Maurice Richard",
        "41" to "Bataille des Plaines d'Abraham", "42" to "Trou noir", "43" to "Céline Dion", "44" to "Fleuve Saint-Laurent",
        "45" to "Nouvelle-France", "46" to "Jacques Cartier", "x54" to "Ottawa", "x55" to null, "x56" to null, "x57" to "Mercure (planète)")

    @Test fun `each recorded question gets the right article or none`() {
        assertEquals(want.keys, rows.map { it.id }.toSet())
        val got = rows.associate { r ->
            val lang = WikiRoute.lang(r.question)
            assertEquals(r.id, r.subject, WikiRoute.subject(r.question, lang))
            val found = WikiAnswer.answer(r.question, listOf(Recorded(lang, r)), now)
            // A full-text guess is one article at most.
            if (found != null && !found.byTitle) assertEquals(r.id, 1, found.articles.size)
            r.id to found?.articles?.firstOrNull()?.title
        }
        assertEquals(want, got)
    }

    @Test fun `each article is read once`() {
        val r = rows.first { it.id == "W4" }
        val copy = Recorded("en", r)
        val found = WikiAnswer.answer(r.question, listOf(copy), now)!!
        assertEquals(listOf("Halifax, Nova Scotia", "Halifax, West Yorkshire"), found.articles.map { it.title })
        assertEquals(r.titles.map { it[1] }.distinct().count { WikiAnswer.fits(it, r.subject) }, copy.reads)
    }

    /** A copy whose full-text index gives [hits] (title to the article's one sentence), best first; no title fits. */
    private fun full(lang: String, vararg hits: Pair<String, String>) = Recorded(lang, Row("m", "", "").apply {
        hits.forEach { (t, s) -> full += listOf(t.replace(' ', '_'), t, t.replace(' ', '_'), t, s) }
    })

    private fun got(q: String, vararg hits: Pair<String, String>) =
        WikiAnswer.answer(q, listOf(full(WikiRoute.lang(q), *hits)), now)?.articles?.map { it.title }

    /** M1 (review-fix-wiki-pick-141): a sentence about something else (Titan in Saturn) is never kept, even ranked first. */
    @Test fun `a hit whose sentence is about something else is never picked`() {
        fun moved(id: String, up: String, above: String): String? {
            val r = rows.first { it.id == id }
            val i = r.full.indexOfFirst { it[1] == up }
            val j = r.full.indexOfFirst { it[1] == above }
            assert(i > j && j >= 0)
            val re = Row(r.id, r.question, r.subject).also { it.titles += r.titles; it.full += r.full.toMutableList().apply { add(j, removeAt(i)) } }
            return WikiAnswer.answer(r.question, listOf(Recorded(WikiRoute.lang(r.question), re)), now)?.articles?.firstOrNull()?.title
        }
        assertEquals("Jupiter", moved("W1", "Saturn", "Jupiter"))
        assertEquals("Mercure (planète)", moved("x57", "Planète naine", "Mercure (planète)"))
    }

    /**
     * M2 (review-fix-wiki-pick-141): a name with a fact gets a card only from a page whose lead says it. High
     * (review-fix-wiki-pick-141-c): a page that only names the subject gives none, so a namesake never gets the card.
     */
    @Test fun `a name with a fact gets no card from a page that only names it`() {
        assertEquals(null, got("When did Louis Riel die?",
            "North-West Rebellion" to "The North-West Rebellion was fought in 1885 by the Métis under Louis Riel.",
            "Louis Riel" to "Louis Riel (22 October 1844 – 16 November 1885) was a Canadian politician."))
        // A title with a qualifier is one of several (the film), and a title that leaves more than itself unsaid is a first name.
        // M3 (review-fix-wiki-pick-141-b), changed by fix-wiki-answers-forms-c: with English word forms "sinking" in "the sinking
        // of" is sink's, and the page states the fact ("occurred on 15 April 1912"): its card; the film ranked first is still refused.
        assertEquals(listOf("Sinking of the Titanic"), got("When did the Titanic sink?",
            "Titanic (1997 film)" to "Titanic is a 1997 American epic romance film.",
            "Sinking of the Titanic" to "The sinking of the Titanic occurred on 15 April 1912.",
            "RMS Titanic" to "RMS Titanic was a British ocean liner that sank in 1912."))
        assertEquals(null, got("Qu'est-ce que Jacques Cartier a découvert ?",
            "Nouvelle-France" to "La Nouvelle-France est explorée par Jacques Cartier en 1534.",
            "Jacques" to "Jacques est un prénom.",
            "Jacques Cartier" to "Jacques Cartier est un navigateur français."))
        assertEquals(null, got("Which planet is largest?", "Planet" to "A planet is a large body in orbit around a star."))
        // fix-wiki-answers-forms-c: each band's page states the fact ("formed in London in 1966"), so with word forms it's the
        // card in either order; the dairy page says "forms" (a present tense, no event): none. No Oasis page states a split: none.
        val cream = arrayOf("Cream (band)" to "Cream were a British rock supergroup formed in London in 1966.",
            "Cream" to "Cream is a dairy product composed of the higher-fat layer skimmed from the top of milk.")
        assertEquals(listOf("Cream (band)"), got("When did Cream form?", *cream))
        assertEquals(listOf("Cream (band)"), got("When did Cream form?", *cream.reversedArray()))
        assertEquals(null, got("When did Cream form?", "Cream" to "Cream is a dairy product. Cream forms on the top of milk left to stand.",
            "Cream (band)" to "Cream were a British rock supergroup."))
        assertEquals(listOf("Heart (band)"), got("When did Heart form?", "Heart (band)" to "Heart is an American rock band formed in 1967.",
            "Heart" to "The heart is a muscular organ found in humans and other animals."))
        assertEquals(listOf("Nirvana (band)"), got("When did Nirvana form?", "Nirvana (band)" to "Nirvana was an American rock band formed in 1987.",
            "Nirvana" to "Nirvana is the extinguishing of the passions, the goal of Buddhist practice."))
        assertEquals(null, got("When did Oasis split?", "Oasis (band)" to "Oasis were an English rock band formed in 1991.",
            "Oasis" to "In ecology, an oasis is a fertile area of a desert or semi-desert environment."))
    }

    /**
     * H1 (review-fix-wiki-pick-141-b): a page that answers wins at any rank, and a page that only names the subject is never a
     * kind of thing ("A prince is") or a list of pages ("may refer to", « peut désigner »), in either order.
     */
    @Test fun `a name with a fact never gets a common word or a list page`() {
        val prince = arrayOf("Prince (musician)" to "Prince Rogers Nelson (June 7, 1958 – April 21, 2016) was an American musician.",
            "Death of Prince" to "On April 21, 2016, Prince was found dead at his Paisley Park home.",
            "Prince" to "A prince is a male ruler or member of a monarch's family.")
        assertEquals(null, got("When did Prince die?", *prince))
        assertEquals(null, got("When did Prince die?", *prince.reversedArray()))
        val halifax = arrayOf("Halifax, Nova Scotia" to "Halifax is the capital and largest municipality of Nova Scotia.",
            "Halifax" to "Halifax may refer to:")
        assertEquals(null, got("When was Halifax founded?", *halifax))
        assertEquals(null, got("When was Halifax founded?", *halifax.reversedArray()))
        assertEquals(null, got("When did Mercury die?", "Freddie Mercury" to "Freddie Mercury was a British singer.",
            "Mercury" to "Mercury commonly refers to:"))
        assertEquals(null, got("Qu'est-ce que Mercure a découvert ?", "Mercure" to "Mercure peut désigner :"))
        val champlain = arrayOf("Mort" to "La mort est l'arrêt définitif des fonctions vitales.",
            "Samuel de Champlain" to "Samuel de Champlain, né vers 1574 et mort le 25 décembre 1635 à Québec, est un navigateur.")
        assertEquals(listOf("Samuel de Champlain"), got("Quand est mort Champlain ?", *champlain))
        assertEquals(listOf("Samuel de Champlain"), got("Quand est mort Champlain ?", *champlain.reversedArray()))
    }

    /** L4 (review-fix-wiki-pick-141-b): only the lead says what a page is; a body sentence that starts with the title doesn't. */
    @Test fun `only the lead is read for what a page is`() {
        // Over PageText's 300 characters, so the article is read as HTML with its headings (a shorter stub has no sections).
        val body = "<p>Saturn is the sixth planet from the Sun and the second largest in the Solar System, after Jupiter. It is a gas " +
            "giant with an average radius of about nine times that of Earth. It has an eighth the average density of Earth, but " +
            "is over 95 times more massive.</p><h2>Moons</h2><p>Saturn's largest moon, Titan, is larger than the planet Mercury.</p>"
        assertEquals(false, WikiAnswer.answers(WikiCopy.Page("Saturn", "Saturn", body), "largest moon Titan", "en"))
        assertEquals(true, WikiAnswer.answers(WikiCopy.Page("Saturn", "Saturn", body.replace("<h2>Moons</h2>", "")), "largest moon Titan", "en"))
    }

    /** L3 (review-fix-wiki-pick-141): most, least, more, « plus », « moins » are superlatives too. */
    @Test fun `most, least, more, plus and moins are superlatives`() {
        assertEquals(null, got("Which planet has the most moons?", "Planet" to "A planet is a large body in orbit around a star.",
            "Jupiter" to "Jupiter has the second most moons of any planet."))
        assertEquals(null, got("Which planet is the least dense?", "Uranus" to "Uranus is the second least dense planet."))
        assertEquals(null, got("Which planet has more moons?", "Planet" to "A planet is a large body in orbit around a star."))
        assertEquals(null, got("Quelle planète est la plus chaude ?", "Planète" to "Une planète est un corps céleste en orbite autour du Soleil."))
        assertEquals(null, got("Quelle planète est la moins dense ?", "Planète" to "Une planète est un corps céleste en orbite autour du Soleil."))
        assertEquals(listOf("Saturn"), got("Which planet has the most moons?", "Saturn" to "Saturn has the most moons of any planet."))
    }

    @Test fun `a disambiguation page is never picked`() {
        val r = Row("d", "Who was Mercury?", "Mercury")
        r.titles += listOf("Mercury_(disambiguation)", "Mercury (disambiguation)", "Mercury_(disambiguation)", "Mercury (disambiguation)")
        r.titles += listOf("Mercury_(planet)", "Mercury (planet)", "Mercury_(planet)", "Mercury (planet)")
        assertEquals(listOf("Mercury (planet)"), WikiAnswer.find("Mercury", Recorded("en", r)).first.map { it.title })
        val fr = Row("h", "Qui était Mercure?", "Mercure")
        fr.full += listOf("Mercure_(homonymie)", "Mercure (homonymie)", "Mercure_(homonymie)", "Mercure (homonymie)", "Mercure est un nom.")
        assertEquals(emptyList<WikiCopy.Hit>(), WikiAnswer.find("Mercure", Recorded("fr", fr)).first)
    }
}
