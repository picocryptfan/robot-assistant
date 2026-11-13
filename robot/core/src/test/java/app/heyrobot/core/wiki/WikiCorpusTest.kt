// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing corpora (routing/baseline.tsv and baseline-everyday.tsv: English and Québec French) against WikiRoute:
 * no phrase one of the app's routers takes goes to the copy (the routers run first, and this keeps it so if they miss).
 */
class WikiCorpusTest {
    private fun corpus(name: String): List<List<String>> =
        javaClass.getResourceAsStream("/routing/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split('\t') }

    @Test
    fun noRoutedPhraseGoesToTheCopy() {
        val rows = corpus("baseline.tsv") + corpus("baseline-everyday.tsv")
        val routed = rows.filter { c -> c.size > 2 && c.drop(1).dropLast(2).any { it != "-" } }.map { it[0] }.distinct()
        assertTrue(routed.size > 500)
        val wrong = routed.filter { WikiRoute.goes(it) }
        assertEquals(emptyList<String>(), wrong)
    }
}
