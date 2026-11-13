// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.web

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.TurnRole
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-wikipedia-p4-c (Low): an answer from the copy of Wikipedia is content to QueryCheck, word for word. */
class WebQueryContentTest {
    @Test fun theCopysWordsAreUntrusted() {
        val copy = "Louis Riel led the Red River Resistance."
        val ctx = WebQueryContext.of(listOf(ChatTurn("u", TurnRole.USER, "Who was Louis Riel?", 1), ChatTurn("a", TurnRole.ASSISTANT, copy, 2, content = true)),
            "search the web for more")
        assertTrue(ctx.untrusted.toString(), copy in ctx.untrusted)
    }
}
