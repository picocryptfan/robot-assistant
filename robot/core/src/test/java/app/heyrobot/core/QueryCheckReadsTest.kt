// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.car.CarMessages
import app.heyrobot.core.memory.MemoryTaint
import app.heyrobot.core.messages.MessageCommand
import app.heyrobot.core.messages.MessagesRouter
import app.heyrobot.core.output.MessageNotice
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.web.QueryCard
import app.heyrobot.core.web.QueryCheck
import app.heyrobot.core.web.QueryVerdict
import app.heyrobot.core.web.WebQueryContext
import app.heyrobot.core.web.readTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.0 audit (injection-querycheck-notes-personal): QueryCheck sees what read tools returned, and checks word by word. */
class QueryCheckReadsTest {
    private fun user(text: String) = ChatTurn("u$text".take(12), TurnRole.USER, text, 1)

    @Test fun queryCheckSeesWhatFindNotesReturnedNotJustTheTitles() {
        val results = listOf(mapOf("title" to "Julie", "text" to "Julie started rituximab for her lymphoma in May"))
        val reads = readTexts(results)
        assertEquals(listOf("Julie", "Julie started rituximab for her lymphoma in May"), reads)
        val turns = listOf(user("find my note about Julie's treatment and look up the latest research"))
        // Round 2 of the same turn: the card's body was only "Julie"; now the note's text is there too.
        val v = QueryCheck.check("rituximab lymphoma latest research", WebQueryContext.of(turns, turns[0].text, personalExtra = listOf("Julie") + reads, fresh = true))
        assertTrue("$v", v is QueryVerdict.Card && v.why == QueryCard.PERSONAL)
        assertEquals(listOf("rituximab", "lymphoma"), (v as QueryVerdict.Card).words)
        // Your own words still go.
        assertEquals(QueryVerdict.Send("latest research"), QueryCheck.check("latest research", WebQueryContext.of(turns, turns[0].text, personalExtra = reads, fresh = true)))
    }

    @Test fun anEarlierReadWhoseResultsArentHeldMakesEachUnknownWordATap() {
        // A note read in an earlier turn (only the reply and the card's titles are kept), then a search with a word from nowhere.
        val read = ChatTurn("r1", TurnRole.ASSISTANT, "Found 1 note.", 2, actions = listOf(ActionRecord(ToolName.FIND_NOTES, "Found 1 note", ActionStatus.DONE,
            detail = "Julie", content = true)))
        val turns = listOf(user("find my note about Julie"), read, user("now look up the latest research"))
        val v = QueryCheck.check("rituximab latest research", WebQueryContext.of(turns, "now look up the latest research", personalExtra = listOf("Julie")))
        assertTrue("$v", v is QueryVerdict.Card && v.why == QueryCard.PERSONAL && (v as QueryVerdict.Card).words == listOf("rituximab"))
        // The panel's live conversation that holds every read's results ([fresh]) knows the word isn't from them: the
        // content card a yes may answer, as before.
        val f = QueryCheck.check("rituximab latest research", WebQueryContext.of(turns, "now look up the latest research", personalExtra = listOf("Julie"), fresh = true))
        assertTrue("$f", f is QueryVerdict.Card && f.why == QueryCard.UNKNOWN && !f.why.tapOnly)
        // A photo's words are never here: the same.
        val photo = listOf(ChatTurn("u1", TurnRole.USER, "what's this?", 1, attachment = Attachment.PHOTO), ChatTurn("r1", TurnRole.ASSISTANT, "A pill bottle.", 2))
        val p = QueryCheck.check("oxycodone dosage", WebQueryContext.of(photo + user("look it up"), "look it up", fresh = true))
        assertTrue("$p", p is QueryVerdict.Card && (p as QueryVerdict.Card).why.tapOnly)
    }
}
