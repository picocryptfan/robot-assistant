// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** review-wikipedia-p4-b (Low, PanelWiki): a stopped copy turn keeps only the words its passages hold. */
class WikiKeptTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val riel = "Louis Riel was a Canadian politician and a political leader of the Métis people. He was born in 1844 in the Red River Colony."
    private val found = WikiAnswer.answer("Who was Louis Riel?", listOf(FakeCopy("en", pages = mapOf("Louis Riel" to riel))), now)!!

    @Test fun aStoppedTurnKeepsOnlyWholeCheckedSentences() {
        assertEquals("Louis Riel was a Métis leader.", WikiTurn.kept(found, "Louis Riel was a Métis leader [1]. He was born in 18"))
        assertEquals("Louis Riel was born in 1844. He led the Métis people.", WikiTurn.kept(found, "Louis Riel was born in 1844. He led the Métis people."))
        for (partial in listOf("Louis Riel was born in 1850.", "Louis Riel was born in Ottawa. He", "Louis Ri", "", "Louis Riel was born in 1844 in Ottawa."))
            assertEquals(partial, "", WikiTurn.kept(found, partial))
        assertEquals("", WikiTurn.kept(null, "Louis Riel was a Métis leader."))
    }
}
