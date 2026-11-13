// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.skills

import app.heyrobot.core.RoutineRouter
import app.heyrobot.core.TextCommand
import app.heyrobot.core.NoteDictation
import app.heyrobot.core.lists.ListsRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

/** Idea 6: the corpus's baseline, what the routers before MultiAsk did with each sentence, in PanelController's order. */
class MultiBaselineTest {
    data class Row(val said: String, val expect: String, val baseline: String)

    companion object {
        val NOW: ZonedDateTime = ZonedDateTime.of(2026, 11, 7, 14, 0, 0, 0, ZoneId.of("America/Halifax"))

        fun corpus(): List<Row> = MultiBaselineTest::class.java.getResource("/multi/corpus.tsv")!!.readText().lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t').let { c -> Row(c[0], c[1], c[2]) } }

        fun old(s: String): String = when {
            TextCommand.parse(s, typed = false) != null -> "messages"
            RoutineRouter.route(s, NOW) != null -> "routine"
            PlanRouter.route(s) != null -> "plan"
            ListsRouter.route(s) != null -> "lists"
            NoteDictation.direct(s) != null -> "note"
            else -> "model"
        }
    }

    @Test fun corpusHoldsFortyInBothLanguages() {
        val rows = corpus()
        assertTrue(rows.size >= 40)
        assertTrue(rows.count { Regex("""\b(mets|ajoute|texte|appelle|paie|règle|réveille|envoie|change mon|allume|c'est)\b""").containsMatchIn(it.said) } >= 15)
    }

    @Test fun baselineIsRecorded() {
        val rows = corpus()
        File("build/multi-baseline.tsv").writeText(rows.joinToString("\n") { it.said + "\t" + it.expect + "\t" + old(it.said) } + "\n")
        assertEquals(rows.map { it.said + " -> " + it.baseline }, rows.map { it.said + " -> " + old(it.said) })
    }
}
