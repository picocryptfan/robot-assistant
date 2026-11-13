// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The copy's turn in the panel (PanelWiki): review-wikipedia-p4's and -b's Lows. */
class WikiPanelLowsTest {
    private val wiki = File("src/main/java/app/heyrobot/assistant/PanelWiki.kt").readText()

    /** L5 and review-b's :46: a stop is never taken for a failure, and the copies' JSON is read only for the copy's kind of question. */
    @Test fun aStopIsRethrownAndTheRouteComesFirst() {
        assertFalse("runCatching takes a CancellationException", "runCatching" in wiki)
        assertTrue("each catch (t: Throwable) after a rethrow",
            Regex("catch \\(t: Throwable\\)").findAll(wiki).count() == Regex("catch \\(e: CancellationException\\)").findAll(wiki).count())
        val route = wiki.indexOf("WikiTurn.routes(")
        assertTrue("the route before WikiStore.load", route in 0 until wiki.indexOf("WikiStore.load("))
    }

    /** review-b's :100: a stop, by tap or by cancellation, or ANSWER_MS keeps only words the passages hold (WikiTurn.kept), never unchecked ones. */
    @Test fun aStoppedTurnKeepsOnlyCheckedWords() {
        assertTrue("kept on Stopped", Regex("""Stopped -> true\.also \{[^}]*setText\(replyId, (end\?\.shown \?: )?WikiTurn\.kept\(""").containsMatchIn(wiki))
        val cancel = wiki.indexOf("catch (e: CancellationException)", wiki.indexOf("BrainOneShots.run("))
        assertTrue("kept on a cancellation", cancel > 0 && wiki.indexOf("WikiTurn.kept(", cancel) in cancel until wiki.indexOf("throw e", cancel))
    }
}
