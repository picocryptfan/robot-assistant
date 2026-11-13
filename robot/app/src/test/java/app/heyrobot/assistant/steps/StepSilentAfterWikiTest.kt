// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * review-batch-14-rest's M3: with steps open, "how many people live in Halifax" keeps its Wikipedia answer, so the page's
 * "The page doesn't say" (StepVoice.silent) runs after wiki.run in PanelController's last routes, never before.
 */
class StepSilentAfterWikiTest {
    @Test fun wikipediaRunsBeforeThePageSaysItDoesNotSay() {
        val s = File("src/main/java/app/heyrobot/assistant/PanelController.kt").readText()
        val wiki = s.indexOf("!wiki.run(replyId, q, history")
        val silent = s.indexOf("StepVoice.silent(q,")
        assertTrue("wiki $wiki, silent $silent", wiki >= 0 && silent > wiki)
    }
}
