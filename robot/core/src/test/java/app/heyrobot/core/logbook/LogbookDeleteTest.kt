// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.logbook

import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** spoken-log-3: the voice delete's newest entry, its card's words and the Undo that puts it back whole. */
class LogbookDeleteTest {
    private class Fake(var all: List<LogEntry>) : LogbookDelete.Store {
        var fail = false
        override fun delete(ids: Set<String>): List<LogEntry>? = if (fail) null else all.filter { it.id in ids }.also { all = all.filter { it.id !in ids } }
        override fun restore(back: List<LogEntry>, deletedAt: Long) = if (fail) LogbookDelete.Back.NOT_NOW else { all = (all + back).sortedBy { it.at }; LogbookDelete.Back.DONE }
    }

    private val now = ZonedDateTime.of(2026, 11, 6, 15, 0, 0, 0, ZoneOffset.UTC)
    private val ran = LogEntry("b", now.minusHours(2).toInstant().toEpochMilli(), "ran", BigDecimal(5), "km", added = 2_000)
    private val pills = LogEntry("a", now.minusHours(1).toInstant().toEpochMilli(), "took my pills", added = 1_000)

    @Test fun theLastIsTheNewestAdded() {
        assertEquals("b", LogbookDelete.last(listOf(pills, ran))?.id)
        assertNull(LogbookDelete.last(emptyList()))
    }

    @Test fun undoPutsItBackWhole() {
        val s = Fake(listOf(ran, pills))
        val u = LogbookDelete.Undos()
        val key = u.delete(s, ran)!!
        assertTrue(LogbookDelete.isUndo(key))
        assertEquals(listOf("a"), s.all.map { it.id })
        assertTrue(u.restore(s, key))
        assertEquals(ran, s.all.first { it.id == "b" })
        assertFalse(u.restore(s, key)) // once only
    }

    @Test fun nothingDeletedHasNoUndo() {
        val s = Fake(listOf(pills))
        val u = LogbookDelete.Undos()
        assertNull(u.delete(s, ran)) // already gone
        s.fail = true
        assertNull(u.delete(s, pills))
        assertFalse(u.restore(s, LogbookDelete.UNDO + "a"))
        assertFalse(LogbookDelete.isUndo("note-delete:a"))
    }

    @Test fun theCardNamesTheEntryAndItsTime() {
        val en = Logbook.deleteAsk(pills, now).render(Langs.EN)
        assertTrue(en, en.startsWith("Delete the log entry took my pills, today at ") && en.endsWith("?"))
        val fr = Logbook.deleteAsk(pills, now).render(Langs(Lang.FR, Lang.FR, Lang.FR))
        assertTrue(fr, fr.startsWith("Effacer l’inscription took my pills, aujourd’hui à 14"))
    }

    @Test fun theFrenchPhraseRoutes() {
        val h = LogbookHeard(on = true, empty = false, stems = emptySet(), units = emptySet())
        val c = LogbookRouter.route("Efface la dernière inscription du carnet.", h, now)
        assertTrue(c is LogbookCommand.DeleteLast)
        assertEquals(Lang.FR, c!!.lang)
    }
}
