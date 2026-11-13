// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import app.heyrobot.core.TurnRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** review-wikipedia-p4-c: the copy's words stay content however its turn ends, and the copy's own turn reads its own rule. */
class WikiStoppedTest {
    private val root = File("src/main/java/app/heyrobot/assistant")

    /** Medium: stop, ANSWER_MS or a failure leave streamed words and no card; they are still the copy's. */
    @Test fun aStoppedCopyTurnIsStillContent() {
        assertTrue(wikiContent(UiTurn("r", TurnRole.ASSISTANT, "Louis Riel led", stopped = true, content = true)))
        assertFalse(wikiContent(UiTurn("r", TurnRole.ASSISTANT, "Hello")))
        val wiki = File(root, "PanelWiki.kt").readText()
        val mark = wiki.indexOf("replies.setContent(replyId, true)")
        assertTrue("marked before the one-shot streams", mark in 0 until wiki.indexOf("BrainOneShots.run("))
        assertTrue("unmarked when the model answers instead", Regex("""NotAsked -> false\.also \{[^}]*setContent\(replyId, false\)""").containsMatchIn(wiki))
        assertTrue(File(root, "PanelController.kt").readText().contains("content = wikiContent(ui)"))
    }

    /** Low: the copy's turn is untrusted by every rule but its own answers (TurnRules.untrustedForCopy), never the full one. */
    @Test fun theCopyIsAskedWithItsOwnRule() {
        val call = File(root, "PanelController.kt").readLines().single { "wiki.run(replyId" in it }
        assertTrue(call, call.trimEnd().endsWith("TurnRules.untrustedForCopy(chat)) &&"))
    }
}
