// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** M1 (review-wikipedia-p4): a year, number or name the passages don't hold is never said under "From Wikipedia on this phone". */
class WikiGroundTest {
    private val now = ZonedDateTime.of(2026, 11, 7, 10, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val copy = FakeCopy("en", pages = mapOf("Louis Riel" to "Louis Riel was a Métis leader born in 1844 in the Red River Settlement.\n\nHe led two resistance movements against the Canadian government."))
    private fun found() = WikiAnswer.answer("Who was Louis Riel?", listOf(copy), now, spoken = false)!!

    @Test fun aYearNumberOrNameNotInThePassagesIsNeverSaid() {
        val f = found()
        for (text in listOf("Louis Riel [1] was born in 1850.", "Louis Riel [1] was a leader in Manitoba, close to John A. Macdonald.", "Louis Riel led 3 resistance movements [1].")) {
            val said = WikiAnswer.reply(f, text)
            assertFalse(said, "1850" in said || "Macdonald" in said || "Manitoba" in said || "3 resistance" in said)
            assertTrue(said, "born in 1844" in said)
            assertTrue(said, said.endsWith("From Wikipedia on this phone, September 2026."))
        }
    }

    @Test fun aReplyThePassagesHoldIsKept() {
        assertEquals("Louis Riel was a Métis leader born in 1844. He led two resistance movements. From Wikipedia on this phone, September 2026.",
            WikiAnswer.reply(found(), "Louis Riel [1] was a Métis leader born in 1844. He led two resistance movements."))
    }
}
