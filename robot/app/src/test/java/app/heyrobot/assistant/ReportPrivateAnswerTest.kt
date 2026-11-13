package app.heyrobot.assistant

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.TurnRole
import app.heyrobot.core.feedback.ReportTurn
import app.heyrobot.core.feedback.ReportTurnRef
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.Flat
import app.heyrobot.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * report-private: a reply answered from a private file (attached, in Files, or read by point and read) reaches the bug card
 * only as History's placeholder, on every way a turn gets there: the newest turn, a chosen one, and the card recreated from
 * its sealed reference (ReportTurnRef, resolved against History's turns).
 */
class ReportPrivateAnswerTest {
    private val secret = "Your glucose was 5.2."
    private val fr = Langs(Lang.FR, Lang.FR, Lang.FR)

    /** The panel's conversation as finishReply leaves it: the reply through PanelFileAnswer.forHistory. */
    private fun chat(id: String, langs: Langs): List<ChatTurn> {
        PanelFileAnswer.markPrivate(id)
        val q = ChatTurn("q-$id", TurnRole.USER, "What was my glucose?", 1)
        return listOf(q, PanelFileAnswer.forHistory(ChatTurn(id, TurnRole.ASSISTANT, secret, 2), langs))
    }

    private fun assertPlaceholder(t: ReportTurn?, langs: Langs) {
        assertEquals(FilesStrings.privateAnswer().render(langs), t?.answer)
        assertFalse(t.toString().contains("5.2"))
    }

    @Test fun everyWayATurnReachesTheCardTakesThePlaceholder() {
        for ((id, langs) in listOf("p-en" to Langs.EN, "p-fr" to fr)) {
            val chat = chat(id, langs)
            val newest = ReportTurn.from(chat, null, "en", "en", 10)
            assertPlaceholder(newest, langs)
            assertPlaceholder(ReportTurn.of(chat, null, "en", "en", 10), langs)
            assertPlaceholder(ReportTurn.of(chat, id, null, null, 10), langs)
            val ref = ReportTurnRef.of(newest!!.copy(conv = "c1"), ReportTurnRef.newToken())
            assertFalse(ref.toString().contains("5.2"))
            assertPlaceholder(ref.resolve(chat), langs)
        }
        assertNotEquals(FilesStrings.privateAnswer().en(), FilesStrings.privateAnswer().render(fr))
    }

    @Test fun thePanelBuildsItsReportTurnsFromTheConversationHistoryKeeps() {
        val f = File("src/main/java/app/heyrobot/assistant/PanelController.kt")
        assertTrue("run from the :app module directory", f.isFile)
        val code = Source.strip(f.readText(), keepStrings = false)
        val panel = Flat(code)
        // The card's turn comes from `chat` (placeholders in), never the live list (_state.turns, the words as shown).
        assertTrue(panel.has("ReportTurn.from(chat, replyId"))
        assertTrue(panel.has("ReportTurn.of(chat, replyId"))
        assertFalse(Regex("ReportTurn\\.(of|from)\\((?!chat)").containsMatchIn(panel.text))
        // A finished reply joins `chat` only through PanelFileAnswer.forHistory; a failed one carries Robot's own message.
        val adds = Regex("chat \\+= [^\\n]*").findAll(code).map { it.value }.filter { "TurnRole.ASSISTANT" in it }.toList()
        assertEquals(2, adds.size)
        assertTrue(adds.any { "PanelFileAnswer.forHistory(MemoryPanel.forHistory(ChatTurn(replyId" in it })
        assertTrue(adds.any { "ChatTurn(replyId, TurnRole.ASSISTANT, message" in it && "error = true" in it })
    }
}
