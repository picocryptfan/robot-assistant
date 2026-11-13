// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.TurnRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Medium (review-wikipedia-p4-b): an answer from the copy is saved to History as content, so a conversation continued from it still asks first. */
class WikiHistoryTest {
    @Test fun anAnswerFromTheCopyIsSavedAndLoadedAsContent() {
        val c = StoredConversation("c", 1, 2, listOf(ChatTurn("q", TurnRole.USER, "Who was Louis Riel?", 0),
            ChatTurn("a", TurnRole.ASSISTANT, "Louis Riel was a Métis leader.", 1, content = true)))
        val back = ConversationCodec.fromJson(ConversationCodec.toJson(c))!!
        assertEquals(listOf(false, true), back.turns.map { it.content })
        assertTrue(TurnRules.untrusted(back.turns))
    }
}
