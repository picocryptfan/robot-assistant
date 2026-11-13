// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** review-wikipedia-p4's Lows: L1, the copy's line stays when the model names the month; L2, next, last and recent are fresh. */
class WikiLowsTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val copy = FakeCopy("en", pages = mapOf("Louis Riel" to "Louis Riel was a Métis leader born in 1844.\n\nHe led two resistance movements."))

    @Test fun theCopysLineStaysWhenTheModelNamesTheMonth() {
        val f = WikiAnswer.answer("Who was Louis Riel?", listOf(copy), now, spoken = false)!!
        assertEquals("As of September 2026, Louis Riel was a Métis leader. From Wikipedia on this phone, September 2026.",
            WikiAnswer.reply(f, "As of September 2026, Louis Riel was a Métis leader."))
        assertEquals("Louis Riel was a Métis leader. From Wikipedia on this phone, September 2026.",
            WikiAnswer.reply(f, "Louis Riel was a Métis leader. From Wikipedia on this phone, September 2026."))
    }

    @Test fun nextLastAndRecentAreFresh() {
        for (q in listOf("When is the next solar eclipse?", "What are the upcoming Olympic Games?", "Who was the last person on the Moon?",
            "What was the most recent eclipse?", "Quel est le prochain lancement de fusée?", "Qui était le dernier roi de France?", "Quelle est la plus récente comète?"))
            assertEquals(q, WikiRoute.Verdict.FRESH, WikiRoute.check(q))
    }
}
