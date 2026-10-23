// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Phrase
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Web search's words in Québec French (1.1, the l10n design's WP6): the first search's card, why a search failed, what
 * Robot says while it searches, the answer card and History's line, Settings' choices. The English of each is the tests
 * beside this one; here the French, with the OQLF's spaces (U+00A0 before ":" and inside « », U+202F before "?").
 */
class WebFrenchTest {
    private val fr = Langs.of(Lang.FR)
    private fun Phrase.fr() = render(fr)

    @Test fun theFirstSearchsCard() {
        val c = WebConsent.card(WebAsk.FIRST_TIME, SearchService.SEARXNG_HOME, "qui a gagné la coupe Stanley", pages = 3)!!
        assertEquals("Chercher « qui a gagné la coupe Stanley » sur le web", c.titleText.fr())
        assertEquals("Robot enverrait ces mots à ton SearXNG. Il pourrait aussi ouvrir jusqu’à 3 pages de résultats depuis ce téléphone.", c.noteText.fr())
        assertEquals(listOf("Toujours autoriser", "Juste cette fois", "Non"), c.buttonTexts.map { it.fr() })
        assertEquals("Ne plus demander", c.rememberText!!.fr())
        assertEquals("Robot enverrait ces mots à Kagi.", WebConsent.noteText(SearchService.KAGI, 0).fr())
        assertEquals("Robot enverrait ces mots à ton SearXNG et Wikipédia. Il pourrait aussi ouvrir jusqu’à 1 page de résultats depuis ce téléphone.",
            WebConsent.noteText(SearchService.SEARXNG_HOME, 1, listOf(WebAlso.WIKIPEDIA)).fr())
        // Wikipedia too, for a who, what or where question while Encyclopedia lookups are on (the default).
        val wiki = "Une question qui commence par «\u00A0qui\u00A0», «\u00A0quel\u00A0», «\u00A0quelle\u00A0», «\u00A0qu’est-ce\u00A0» ou «\u00A0où\u00A0» va aussi à Wikipédia."
        assertEquals("Quand tu le demandes, Robot envoie tes mots de recherche à DuckDuckGo. $wiki Rien d’autre ne quitte le téléphone.",
            WebSetup.consentText(SearchService.DDG_INSTANT, WebSettings()).fr())
        assertEquals("Quand tu le demandes, Robot envoie tes mots de recherche à Kagi. $wiki Pour répondre, il peut ouvrir jusqu’à 5 des pages " +
            "de résultats depuis ce téléphone, comme un navigateur sans témoins\u00A0: ces sites voient ton adresse IP. Rien d’autre ne quitte le téléphone.",
            WebSetup.consentText(SearchService.KAGI, WebSettings(pages = 5)).fr())
        assertEquals("Quand tu le demandes, Robot envoie tes mots de recherche à Kagi. Une question sur des livres va aussi à Open Library. " +
            "Pour répondre, il peut ouvrir 1 page de résultats depuis ce téléphone, comme un navigateur sans témoins\u00A0: ce site voit ton adresse IP. " +
            "Rien d’autre ne quitte le téléphone.",
            WebSetup.consentText(SearchService.KAGI, WebSettings(pages = 1, encyclopedia = false, books = true)).fr())
    }

    @Test fun whyASearchFailed() {
        assertEquals("Ton SearXNG ne répond pas.", WebOutcome.Failed(SearchService.SEARXNG_HOME, WebFail.NOT_ANSWERING).sentence.fr())
        assertEquals("Ton SearXNG a répondu 403 : ajoute json sous search: formats: dans settings.yml",
            WebFail.JSON_OFF.textPhrase(SearchService.SEARXNG_HOME).fr())
        assertEquals("La clé pour un SearXNG public manque", WebFail.KEY_MISSING.textPhrase(SearchService.SEARXNG_PUBLIC).fr())
        assertEquals("Le service de recherche est occupé en ce moment", WebFail.BUSY.textPhrase(null).fr())
        assertEquals("Aucun résultat.", WebFail.NO_RESULTS.sentence(SearchService.BRAVE).fr())
        assertEquals("Cette recherche semblait contenir des renseignements personnels, alors Robot ne l’a pas envoyée.",
            WebFail.QUERY_REFUSED.sentence(null).fr())
        assertEquals("La recherche web est désactivée : active-la dans Réglages › Recherche web.", WebGate.OFF_TURN_ON.fr())
        assertEquals("C’est la limite de recherches web pour aujourd’hui (100). Tu peux l’augmenter dans Réglages › Recherche web.",
            WebGate.dailyCap(100).fr())
        // The model reads the English, whatever the card shows.
        assertEquals("That's today's limit of web searches (100). Settings › Web search can raise it.", WebGate.dailyCap(100).en())
    }

    @Test fun theGatesCardsSayItInFrenchAndTheModelReadsEnglish() {
        val mine = QueryContext(said = listOf("score du Canadien hier"))
        val off = WebGate.prepare(app.heyrobot.core.Action.WebSearch("qui a gagné la coupe Stanley"), WebSettings(), null, mine, 0) as app.heyrobot.core.Prepared.Refused
        assertEquals("La recherche web est désactivée\u00A0: active-la dans Réglages › Recherche web.", off.text!!.fr())
        assertEquals("Web search is off: turn it on in Robot's Settings › Web search.", off.detail)
        val on = WebSettings(on = true, service = SearchService.KAGI, ask = WebAsk.FIRST_TIME)
        val card = WebGate.prepare(app.heyrobot.core.Action.WebSearch("score du Canadien hier"), on, null, mine, 0) as app.heyrobot.core.Prepared.Ready
        assertEquals(listOf("Toujours autoriser", "Juste cette fois", "Non"), card.choices.map { it.text!!.fr() })
        assertEquals("Robot enverrait ces mots à Kagi. Il pourrait aussi ouvrir jusqu’à 3 pages de résultats depuis ce téléphone.", card.noteText!!.fr())
        assertEquals("Ne plus demander", card.rememberText!!.fr())
    }

    @Test fun whatRobotSaysWhileItSearches() {
        assertEquals("Je cherche sur le web…", WebStatus.searchingText.fr())
        assertEquals("Je lis 3 pages…", WebStatus.readingText(3).fr())
        assertEquals("Je lis 1 page…", WebStatus.readingText(1).fr())
        assertEquals("Je lis les pages 2/3…", WebStatus.readingText(3, done = 2).fr())
        // Aloud, nothing the voice would read oddly.
        assertEquals("Je cherche sur le web…", WebStatus.searchingText.render(fr.voice))
    }

    @Test fun theAnswerCardAndHistorysLine() {
        val sources = listOf(
            WebSource(1, "Panthers win", "https://www.nhl.com/news/panthers", SearchProvider.SEARXNG),
            WebSource(2, "Final - Wikipedia", "https://en.wikipedia.org/wiki/2025_Stanley_Cup_Final", SearchProvider.SEARXNG),
        )
        val cited = Citations.finish("The Panthers won [1], in six games [2].", sources)
        val card = AnswerCard.of(SearchService.SEARXNG_HOME, "qui a gagné", cited, pagesRead = 2, skipped = listOf("tsn.ca" to PageSkip.NOT_PUBLIC),
            showCost = true, also = listOf(WebAlso.WIKIPEDIA))
        assertEquals("Recherche web · ton SearXNG", card.headerText.fr())
        assertEquals("≈ gratuit", card.costText!!.fr())
        assertEquals("Envoyé à ton SearXNG et Wikipédia : « qui a gagné »", card.sentText.fr())
        assertEquals("2 pages lues depuis ce téléphone", card.readText!!.fr())
        assertEquals(listOf("tsn.ca : pas une adresse publique"), card.skippedTexts.map { it.fr() })
        assertEquals(listOf("Tiré de l’article « Final » de Wikipédia, CC BY-SA 4.0"), card.creditTexts.map { it.fr() })
        assertEquals("Recherche web (ton SearXNG et Wikipédia) · nhl.com, en.wikipedia.org", card.savedText(KeepSources.DOMAINS).fr())
        assertEquals("1 page lue par Brave", AnswerCard.of(SearchService.BRAVE, "q", cited, pagesRead = 1, throughService = true).readText!!.fr())
        assertEquals("≈ 0,5 ¢", SearchService.BRAVE.costText.fr())
    }

    @Test fun settingsChoicesAndTest() {
        assertEquals(listOf("Demander la première fois", "Toujours demander", "Ne jamais demander"), WebAsk.entries.map { it.label().fr() })
        assertEquals(listOf("Élevé", "Moyen", "Faible"), PrivacyLevel.entries.map { it.label().fr() })
        assertEquals("Un SearXNG public", SearchService.SEARXNG_PUBLIC.titleText.fr())
        assertEquals("JSON activé · 0 résultat", SearxngWire.testLineText(SearchAnswer.Results(emptyList())).fr())
        assertEquals("Colle la clé.", WebKeyFormat.problemText("")!!.fr())
        assertEquals("Cette recherche utilise des mots que tu n’as pas dits, dans une conversation avec du contenu que Robot n’a pas écrit.",
            QueryCard.UNKNOWN.noteText.fr())
    }
}
