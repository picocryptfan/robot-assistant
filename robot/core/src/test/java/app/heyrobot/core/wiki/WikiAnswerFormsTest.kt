// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** fix-wiki-answers-forms: a lead that answers in another form of the question's word ("died" for "die") gives its card. */
class WikiAnswerFormsTest {
    private val now = ZonedDateTime.of(2026, 11, 13, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private fun copy(lang: String, vararg pages: Pair<String, String>) = FakeCopy(lang, pages = pages.toMap())
    private fun titles(q: String, c: WikiCopy) = WikiAnswer.answer(q, listOf(c), now)?.articles?.map { it.title }

    @Test
    fun diedAnswersWhenDidHeDie() {
        val c = copy("en", "Louis Riel" to "Louis Riel was a Canadian politician who died on 16 November 1885.")
        assertEquals(listOf("Louis Riel"), titles("When did Louis Riel die?", c))
    }

    @Test
    fun sankAnswersWhenDidItSink() {
        val c = copy("en", "Titanic" to "Titanic was a British ocean liner that sank on 15 April 1912 after striking an iceberg.")
        assertEquals(listOf("Titanic"), titles("When did the Titanic sink?", c))
    }

    @Test
    fun formedAnswersWhenWasItFormed() {
        val c = copy("en", "Canadian Confederation" to "Canadian Confederation was the process by which three colonies formed Canada in 1867.")
        assertEquals(listOf("Canadian Confederation"), titles("When did Canadian Confederation form?", c))
    }

    @Test
    fun foundIsNotFounded() {
        // "found" is find's past: a body found in Halifax says nothing about when Halifax was founded.
        assertNull(titles("When was Halifax founded?", copy("en", "Halifax" to "Halifax is a port city where the wreck of a ship was found in 1912.")))
        assertEquals(listOf("Halifax"), titles("When was Halifax founded?", copy("en", "Halifax" to "Halifax is a port city that was founded in 1749.")))
    }

    /** H1 (review-fix-wiki-answers-forms): a sentence's present tense states no event ("Cream forms on milk", "forms part of"). */
    @Test
    fun presentTenseIsNotTheEvent() {
        assertNull(titles("When did Cream form?", copy("en", "Cream" to "Cream is a dairy product. Cream forms on the top of milk left to stand.",
            "Cream (band)" to "Cream were a British rock supergroup.")))
        assertNull(titles("When did Heart form?", copy("en", "Heart (band)" to "Heart is an American rock band.",
            "Hearts (card game)" to "Hearts is a trick-taking card game that forms part of the Reversis family.")))
    }

    /** H2: an -ing noun states no event ("a replica building"); "the sinking of" does. */
    @Test
    fun ingNounIsNotTheEvent() {
        assertNull(titles("When was the Parthenon built?", copy("en", "Parthenon" to "The Parthenon is a former temple on the Athenian Acropolis.",
            "Parthenon (Nashville)" to "The Parthenon is a full-scale replica building of the original Parthenon in Athens.")))
        assertEquals(listOf("Sinking of the Titanic"), titles("When did the Titanic sink?",
            copy("en", "Sinking of the Titanic" to "The sinking of the Titanic occurred on 15 April 1912.")))
    }

    /** M1: a title that opens with a word of the subject names all of it: Prince Henry isn't Prince. */
    @Test
    fun aLongerTitleIsSomeoneElse() {
        val singer = "Prince (musician)" to "Prince Rogers Nelson was an American singer and songwriter."
        assertNull(titles("When did Prince die?", copy("en", singer,
            "Prince Henry" to "Prince Henry was the eldest son of King James. Prince Henry died of typhoid fever in 1612.")))
        assertNull(titles("When was Prince born?", copy("en", singer,
            "Prince Henry" to "Prince Henry was the eldest son of King James. Prince Henry was born in 1594.")))
    }

    /** L1: "dies" and "lies" have a base, and a sentence's "founded" never answers a question's "found" (find's). */
    @Test
    fun shortFormsAndFoundBothWays() {
        assertEquals(setOf("dies", "die"), WordForms.bases("dies"))
        assertEquals(setOf("lies", "lie"), WordForms.bases("lies"))
        org.junit.Assert.assertFalse(WordForms.same("founded", "found"))
    }

    private val creamBand = "Cream (band)" to "Cream were a British rock supergroup."

    /** N1: a plain word ("form", the noun) is not the past "formed" asked about, so the dairy and Buddhism pages stay out. */
    @Test
    fun aPlainWordIsNotThePastAskedAbout() {
        assertNull(titles("When was Cream formed?", copy("en", "Cream" to "Cream is a dairy product sold in liquid or whipped form.", creamBand)))
    }

    /** N1, a second page: "a form of release" on the Buddhism page. */
    @Test
    fun aPlainWordIsNotThePastAskedAboutNirvana() {
        assertNull(titles("When was Nirvana formed?", copy("en", "Nirvana" to "Nirvana is the highest state in Buddhism, a form of release from rebirth.",
            "Nirvana (band)" to "Nirvana was an American rock band.")))
    }

    /** N2: a present passive ("is formed") is a process, not the event asked about. */
    @Test
    fun aPresentPassiveIsNotTheEvent() {
        assertNull(titles("When did Cream form?", copy("en", "Cream" to "Cream is a dairy product. Cream is formed when the fat in milk rises to the top.", creamBand)))
    }

    /** N3: "the sinking of the Titanic" counts only on a page named for it, never on a film's. */
    @Test
    fun theIngOfCountsOnlyOnItsOwnPage() {
        assertNull(titles("When did the Titanic sink?", copy("en", "Titanic (1997 film)" to "Titanic is a 1997 American disaster film about the sinking of the Titanic in 1912.")))
    }
}

class WikiAnswerFormsFrenchTest {
    @Test
    fun frenchMatchesWordsAsBefore() {
        val page = WikiCopy.Page("Louis_Riel", "Louis Riel", "<p>Louis Riel was a Canadian politician who died on 16 November 1885.</p>")
        org.junit.Assert.assertTrue(WikiAnswer.answers(page, "Louis Riel die", "en"))
        org.junit.Assert.assertFalse(WikiAnswer.answers(page, "Louis Riel die", "fr"))
    }
}
