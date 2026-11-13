// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.read

import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.messages.MessagesRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Read it to me's words (read-it-to-me-plan.md §4): routing/corpus-read.txt's rows, each read in its reading state, and
 * every phrase of the other routing corpora, none of which ReadRouter may take when nothing is being read.
 */
class ReadWordsTest {
    private fun lines(name: String): List<String> =
        javaClass.getResourceAsStream("/routing/$name")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }

    private val rows = lines("corpus-read.txt").map { it.split('\t') }

    private fun state(s: String) = ReadState.valueOf(s.uppercase())

    private fun said(t: String, s: ReadState) = ReadRouter.route(t, s)?.toString() ?: "-"

    @Test fun everyRowOfTheCorpusRoutesAsWritten() {
        val wrong = rows.filter { (s, t, want) -> said(t, state(s)) != want }.map { (s, t, want) -> "$s\t$t\twant $want, got ${said(t, state(s))}" }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
        assertTrue(rows.size >= 50)
    }

    @Test fun noOtherCorpusPhraseIsTaken() {
        val others = javaClass.getResource("/routing")!!.let { java.io.File(it.toURI()) }.listFiles { f -> f.name.startsWith("corpus") && f.name.endsWith(".txt") && f.name != "corpus-read.txt" }!!
        assertTrue(others.size >= 8)
        val taken = others.flatMap { f -> lines(f.name).map { it.substringBefore('\t') }.filter { ReadRouter.route(it) != null }.map { "${f.name}: $it -> ${ReadRouter.route(it)}" } }
        assertEquals(taken.joinToString("\n"), 0, taken.size)
    }

    @Test fun theRoutersBeforeItTakeNoneOfItsPhrases() {
        val ours = rows.filter { it[2] != "-" && it[0] == "none" }.map { it[1] }
        val clash = ours.filter { MessagesRouter.route(it) != null || FilesRouter.route(it) != null }
        assertEquals(clash.joinToString("\n") { "$it: ${MessagesRouter.route(it)} ${FilesRouter.route(it)}" }, 0, clash.size)
    }
}
