// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.files.FilesRouter
import app.heyrobot.core.music.MusicContext
import app.heyrobot.core.music.MusicRouter
import app.heyrobot.core.online.OnlineRouter
import app.heyrobot.core.sums.SumsRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The logbook's corpus (routing/corpus-logbook.txt: a phrase, a tab, the kind it routes as or "-"): without LogbookRouter
 * every row with a kind goes to memory and the model. The routers before it (Files, music, sums, What went online) take none
 * of them, and LogbookRouter takes none of the shared corpus's phrases, with a log that holds runs, pills and an oil change.
 */
class LogbookRoutingCorpusTest {
    private val now = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, ZoneId.of("America/Halifax"))
    private val heard = LogbookHeard.of(true, listOf(LogEntry("a", 1L, "ran 5 km", BigDecimal(5), "km"), LogEntry("b", 2L, "took my pills"),
        LogEntry("c", 3L, "oil changed at 84,000 km", BigDecimal(84000), "km"), LogEntry("d", 4L, "pris mes pilules")))
    private fun rows() = javaClass.getResourceAsStream("/routing/corpus-logbook.txt")!!.bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        .map { it.split('\t').let { p -> p[0] to p[1] } }

    @Test fun everyRowRoutesAsWritten() {
        val wrong = rows().filter { (t, k) -> (LogbookRouter.route(t, heard, now)?.kind?.name ?: "-") != k }
        assertEquals(emptyList<Pair<String, String>>(), wrong)
    }

    @Test fun noEarlierRouterTakesALogPhrase() {
        val taken = rows().filter { it.second != "-" }.map { it.first }.filter { t ->
            FilesRouter.route(t) != null || MusicRouter.route(t, MusicContext(appPicked = true)) != null || SumsRouter.route(t, now) != null || OnlineRouter.route(t) != null
        }
        assertEquals(emptyList<String>(), taken)
    }

    @Test fun theSharedCorpusIsNotTheLogs() {
        val files = listOf("corpus.txt", "corpus-f10.txt", "corpus-f12.txt", "corpus-f13.txt", "corpus-fx2.txt", "corpus-e1.txt", "corpus-e2.txt", "corpus-lists.txt", "corpus-phonefix.txt")
        for (f in files) for (t in javaClass.getResourceAsStream("/routing/$f")!!.bufferedReader().readLines().filter { it.isNotBlank() }) {
            assertNull("$f: $t", LogbookRouter.route(t, heard, now))
        }
    }
}
