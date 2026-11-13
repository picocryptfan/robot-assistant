// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.web.Passage
import app.heyrobot.core.web.Passages
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * fix-wiki-founder-141-b (review-fix-wiki-founder-141, findings 1 and 4): the final "ee" rule in WebWords.stem joins
 * some words whose meanings differ (année/Anne, musée/Muses, committee/committed). On articles shaped like real ones,
 * the passage that answers the question must still lead the excerpt, ahead of one that only shares the joined word.
 */
class WikiStemJoinTest {
    /** [lead], then [filler] paragraphs naming [name], with [distractor] early and [fact] deep in the history. */
    private fun article(lead: String, name: String, fact: String, distractor: String, filler: List<String>): String {
        val body = (0 until 14).map { i -> "${filler[i % filler.size]} $name ${filler[(i + 1) % filler.size]} $name. " +
            "${filler[(i + 2) % filler.size]} $name ${filler[(i + 3) % filler.size]}." }
        return (listOf(lead) + body.take(3) + distractor + body.subList(3, 9) + fact + body.drop(9)).joinToString("\n\n")
    }

    private val fillerFr = listOf(
        "Le port de", "accueille chaque année des navires de croisière venus de partout, et les quais restaurés attirent les promeneurs",
        "Le climat de", "connaît des hivers longs et enneigés et des étés chauds et humides, avec des précipitations régulières",
        "L'économie de", "repose sur l'aérospatiale, le jeu vidéo, la recherche, les services financiers et le commerce de détail",
        "Les quartiers de", "comptent des rues commerçantes, des escaliers extérieurs, des ruelles vertes, des places et des parcs")
    private val fillerLouvre = listOf(
        "Les collections du", "rassemblent des peintures, des sculptures, des objets d'art et des antiquités venues de plusieurs civilisations",
        "Les salles du", "s'étendent sur plusieurs ailes du palais, autour de la cour Carrée et de la cour Napoléon",
        "La fréquentation du", "dépasse plusieurs millions de visiteurs par an, ce qui en fait l'un des musées les plus visités au monde",
        "Les restaurations du", "se poursuivent dans les galeries, les escaliers et les jardins des Tuileries voisins")
    private val fillerEn = listOf(
        "The members of", "are elected for terms of eight years and meet in session once a year to vote on the host cities",
        "The headquarters of", "stand in Lausanne, on the shore of Lake Geneva, where its museum and archives are open to visitors",
        "The revenue of", "comes mostly from the sale of broadcast rights and from partners who sponsor the games worldwide",
        "The commissions of", "advise on athletes, ethics, medicine, the environment and the sharing of revenue with sports")

    /**
     * The excerpt [Passages.select] makes of [text] under the copy's cap. WikiAnswer.answer itself takes none of these
     * three questions (its route and subject don't), but web search ranks its pages with the same Passages and stems.
     */
    private fun excerpt(question: String, title: String, text: String): List<Passage> {
        val passages = Passages.select(question, listOf(1 to WikiAnswer.text(FakeCopy.html(title, text))), WikiAnswer.TOKENS, WikiAnswer.TOKENS)
        assertTrue(question, passages.isNotEmpty())
        return passages
    }

    /** The answer passage (holding [fact]) is in the excerpt and outscores every passage after the lead. */
    private fun assertLeads(question: String, title: String, text: String, fact: String) {
        val passages = excerpt(question, title, text)
        val shown = passages.joinToString(" | ") { "${it.index}:${"%.2f".format(it.score)}:${it.text.take(40)}" }
        val best = passages.filter { it.index != 0 }.maxByOrNull { it.score }
        assertTrue(shown, best != null && fact in best.text)
    }

    @Test
    fun yearOfFoundingIsNotTakenOverBySainteAnne() {
        val fact = "1642"
        val text = article("Montréal est la plus grande ville du Québec et la deuxième du Canada. Elle occupe une île au confluent du " +
            "fleuve Saint-Laurent et de la rivière des Outaouais.", "Montréal",
            "Ville-Marie, qui deviendra Montréal, est fondée le 17 mai $fact par Paul de Chomedey de Maisonneuve et Jeanne Mance, au nom de " +
                "la Société Notre-Dame de Montréal, sur la pointe où la petite rivière Saint-Pierre se jette dans le fleuve.",
            "Au XIXe siècle, le quartier Sainte-Anne, autour de l'église Sainte-Anne, accueille les ouvriers irlandais du canal de Lachine; " +
                "la rue Sainte-Anne et le marché Sainte-Anne rappellent la paroisse, et la romancière Anne Hébert y situe une nouvelle.",
            fillerFr)
        assertLeads("En quelle année a été fondée Montréal ?", "Montréal", text, fact)
    }

    @Test
    fun openingOfTheLouvreIsNotTakenOverByTheMuses() {
        val fact = "10 août 1793"
        val text = article("Le musée du Louvre est un musée d'art et d'antiquités situé au centre de Paris, dans l'ancien palais royal du Louvre.",
            "Louvre", "Le musée est ouvert au public le $fact, sous le nom de Muséum central des arts de la République, dans la Grande Galerie " +
                "du Louvre, avec quelque 540 tableaux tirés des collections royales et des biens saisis aux émigrés et aux églises.",
            "Au plafond de la galerie d'Apollon, les neuf Muses entourent le dieu; les Muses de la poésie et de la musique y côtoient " +
                "les saisons, et un sarcophage romain des Muses est exposé dans les salles des antiquités grecques et romaines.",
            fillerLouvre)
        assertLeads("Quand le musée du Louvre a-t-il ouvert ?", "Musée du Louvre", text, fact)
    }

    @Test
    fun formingOfTheCommitteeIsNotTakenOverByCommitted() {
        val fact = "23 June 1894"
        val text = article("The International Olympic Committee (IOC) is a non-governmental sports organization based in Lausanne, Switzerland.",
            "the IOC", "The IOC was formed on $fact at a congress held at the Sorbonne in Paris, called by Pierre de Coubertin, with " +
                "Demetrios Vikelas as its first president and the first modern games set for Athens in 1896.",
            "After the Salt Lake City scandal, the committee committed to reforms in 1999: members who had committed bribery were expelled, " +
                "and the IOC committed itself to term limits and an ethics commission.",
            fillerEn)
        assertLeads("When was the International Olympic Committee formed?", "International Olympic Committee", text, fact)
    }
}
