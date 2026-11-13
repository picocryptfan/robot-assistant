// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.logbook

import app.heyrobot.assistant.routines.PanelRoutines
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.Card
import app.heyrobot.core.Executed
import app.heyrobot.core.RoutineStep
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.logbook.LogEntry
import app.heyrobot.core.logbook.LogbookDelete
import app.heyrobot.core.logbook.LogbookResult
import app.heyrobot.sys.LogbookRead
import app.heyrobot.sys.LogbookVault
import app.heyrobot.sys.SoftMemoryKeys
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.ZonedDateTime

/** spoken-log-3: "delete the last log entry" asks on a card naming it, deletes on yes, and Undo brings it back. */
class PanelLogbookDeleteTest {
    @get:Rule val tmp = TemporaryFolder()

    private class Fake(val yes: Boolean) : PanelRoutines.Host {
        val said = ArrayList<String>()
        val asked = ArrayList<Card>()
        val shown = ArrayList<Card>()
        override val speak = true
        override val locked = false
        override fun text(replyId: String, text: String) { said += text }
        override fun append(replyId: String, text: String) = Unit
        override fun say(sentence: String, personal: Boolean) { said += sentence }
        override suspend fun ask(replyId: String, card: Card, tapOnly: String?): Boolean { asked += card; return yes }
        override fun show(replyId: String, card: Card) { shown += card }
        override suspend fun tool(replyId: String, step: RoutineStep.Tool): Executed? = null
        override fun finish(replyId: String) = Unit
        override fun quoted(replyId: String) = Unit
        override suspend fun leave() = Unit
    }

    private val now = ZonedDateTime.now()
    private val old = LogEntry("a", now.minusHours(3).toInstant().toEpochMilli(), "took my pills", added = 1_000)
    private val last = LogEntry("b", now.minusHours(1).toInstant().toEpochMilli(), "walked the dog", added = 2_000)

    private fun vault(): LogbookVault = LogbookVault(File(tmp.root, "logbook"), SoftMemoryKeys(), File(tmp.root, "logbook.lock")) { false }.also {
        it.add(old, false); it.add(last, false)
    }

    private fun ids(v: LogbookVault) = (v.read() as LogbookRead.Ok).entries.map { it.id }.sorted()

    @Test fun yesDeletesTheNewestAndUndoBringsItBack() {
        val v = vault()
        val h = Fake(yes = true)
        assertEquals(LogbookResult.DELETED, runBlocking { PanelLogbook.deleteLast(v, "r", h, Langs.EN, now) })
        assertTrue(h.asked.single().summary, h.asked.single().summary.contains("walked the dog"))
        assertTrue(h.said.first().contains("walked the dog")) // named aloud before a spoken yes
        assertEquals(listOf("a"), ids(v))
        val key = h.shown.last().undo!!
        assertTrue(LogbookDelete.isUndo(key))
        assertEquals("The entry is back in your logbook.", PanelLogbook.undoDelete(v, key, Langs.EN).line)
        assertEquals(listOf("a", "b"), ids(v))
        assertEquals(last, (v.read() as LogbookRead.Ok).entries.first { it.id == "b" })
        assertEquals("Robot couldn't put the entry back.", PanelLogbook.undoDelete(v, key, Langs.EN).line)
    }

    @Test fun aFailedUndoKeepsItsButton() {
        var locked = false
        val v = LogbookVault(File(tmp.root, "logbook"), SoftMemoryKeys(), File(tmp.root, "logbook.lock")) { locked }.also { it.add(old, false); it.add(last, false) }
        val h = Fake(yes = true)
        runBlocking { PanelLogbook.deleteLast(v, "r", h, Langs.EN, now) }
        val key = h.shown.last().undo!!
        locked = true
        val r = PanelLogbook.undoDelete(v, key, Langs.EN)
        assertEquals("Robot couldn't put the entry back.", r.line)
        assertEquals(key, r.again)
        locked = false
        val r2 = PanelLogbook.undoDelete(v, key, Langs.EN)
        assertEquals("The entry is back in your logbook.", r2.line)
        assertNull(r2.again)
        assertEquals(listOf("a", "b"), ids(v))
    }

    @Test fun noKeepsIt() {
        val v = vault()
        val h = Fake(yes = false)
        assertEquals(LogbookResult.KEPT, runBlocking { PanelLogbook.deleteLast(v, "r", h, Langs.EN, now) })
        assertEquals(listOf("a", "b"), ids(v))
        assertEquals(ActionStatus.DECLINED, h.shown.single().status)
        assertNull(h.shown.single().undo)
    }
}
