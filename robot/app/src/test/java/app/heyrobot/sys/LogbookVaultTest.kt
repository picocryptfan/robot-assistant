// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.logbook.LogEntry
import app.heyrobot.core.logbook.LogbookDelete
import app.heyrobot.core.logbook.LogbookEnvelope
import app.heyrobot.core.logbook.LogbookLimits
import app.heyrobot.ui.settings.LogbookCsv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.math.BigDecimal
import java.time.ZoneOffset

/** Spoken log: the logbook sealed at rest under its own key, adds while locked journaled and merged at unlock. */
class LogbookVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val keys = SoftMemoryKeys()
    private var lockedNow = false
    private val dir by lazy { tmp.newFolder("logbook") }
    private fun vault() = LogbookVault(dir, keys, File(tmp.root, "logbook.lock")) { lockedNow }
    private fun e(id: String, at: Long = 1_000L, what: String = "took my pills") = LogEntry(id, at, what)

    @Test fun sealedUnderItsOwnKeyNeverPlain() {
        assertEquals(LogbookAdd.ADDED, vault().add(e("a"), whileLocked = true))
        val f = File(dir, "log.enc")
        assertEquals("robot_logbook_v1", LogbookEnvelope.frame.alias(f.readBytes()))
        assertFalse(String(f.readBytes(), Charsets.ISO_8859_1).contains("pills"))
        assertEquals(listOf(e("a")), (vault().read() as LogbookRead.Ok).entries)
    }

    @Test fun lockedAddsWaitInTheJournalAndMergeAtUnlock() {
        lockedNow = true
        assertEquals(LogbookAdd.ADDED_LOCKED, vault().add(e("b"), whileLocked = true))
        assertEquals("robot_logbook_lock_v1", LogbookEnvelope.frame.alias(File(dir, "b.locked.enc").readBytes()))
        assertEquals(LogbookRead.Locked, vault().read())
        assertEquals(LogbookAdd.NOT_WHILE_LOCKED, vault().add(e("c"), whileLocked = false))
        lockedNow = false
        assertEquals(listOf(e("b")), (vault().read() as LogbookRead.Ok).entries)
        assertFalse(File(dir, "b.locked.enc").exists())
    }

    @Test fun deleteUndoAndDeleteAll() {
        val v = vault()
        v.add(e("a", 1), true); v.add(e("b", 2), true)
        val gone = v.delete(setOf("a"))!!
        assertEquals(listOf(e("b", 2)), (v.read() as LogbookRead.Ok).entries)
        assertEquals(LogbookDelete.Back.DONE, v.restore(gone, System.currentTimeMillis()))
        assertEquals(listOf("a", "b"), (v.read() as LogbookRead.Ok).entries.map { it.id })
        assertTrue(v.deleteAll())
        assertEquals(emptyList<LogEntry>(), (v.read() as LogbookRead.Ok).entries)
    }

    @Test fun deleteAllTakesKeptAsideCopiesAndEndsEveryUndo() {
        val v = vault()
        v.add(e("a", 1), true); v.add(e("b", 2), true)
        val at = System.currentTimeMillis()
        val gone = v.delete(setOf("a"))!!
        File(dir, "log.enc.bad").writeText("sealed under a key that still exists")
        File(dir, ".log.enc.1.tmp").writeText("cut")
        assertTrue(v.deleteAll())
        assertEquals(emptyList<String>(), dir.list()!!.filter { it.contains(".bad") || it.endsWith(".tmp") || it.endsWith(".enc") })
        assertEquals(LogbookDelete.Back.GONE, v.restore(gone, at))
        assertEquals(emptyList<LogEntry>(), (v.read() as LogbookRead.Ok).entries)
    }

    @Test fun damageIsKeptAsideNeverDeleted() {
        File(dir, "log.enc").writeText("not sealed")
        assertEquals(emptyList<LogEntry>(), (vault().read() as LogbookRead.Ok).entries)
        assertTrue(File(dir, "log.enc.bad").exists())
    }

    private fun full(v: LogbookVault): List<LogEntry> {
        val all = (1..LogbookLimits.MAX_ENTRIES).map { e("e$it", it.toLong()) }
        assertTrue(v.fromBackup(all, replace = true))
        return all
    }

    @Test fun undoWithAFullLogNeverWritesPastTheCap() {
        val v = vault()
        full(v)
        val gone = v.delete(setOf("e5"))!!
        assertEquals(LogbookAdd.ADDED, v.add(e("new", 20_000), true))
        assertEquals(LogbookDelete.Back.NOT_NOW, v.restore(gone, System.currentTimeMillis()))
        val r = v.read() as LogbookRead.Ok
        assertEquals(LogbookLimits.MAX_ENTRIES, r.entries.size)
        assertTrue(r.entries.any { it.id == "new" })
        assertFalse(File(dir, "log.enc.bad").exists())
    }

    @Test fun lockedAddsPastTheCapWaitOrAreRefusedNeverDropped() {
        val v = vault()
        full(v)
        v.delete(setOf("e1"))
        lockedNow = true
        assertEquals(LogbookAdd.ADDED_LOCKED, v.add(e("y", 30_000), true))
        assertEquals(LogbookAdd.ADDED_LOCKED, v.add(e("z", 30_001), true))
        lockedNow = false
        val r = v.read() as LogbookRead.Ok
        assertEquals(LogbookLimits.MAX_ENTRIES, r.entries.size)
        assertEquals(1, r.waiting)
        assertTrue(File(dir, "z.locked.enc").exists())
        lockedNow = true
        assertEquals(LogbookAdd.FULL, v.add(e("w", 30_002), true))
        lockedNow = false
        v.delete(setOf("e2"))
        val ids = (v.read() as LogbookRead.Ok).entries.map { it.id }
        assertTrue(ids.containsAll(listOf("y", "z")))
        assertFalse(ids.contains("w"))
    }

    @Test fun anEntryTheReaderWouldRefuseIsNeverWritten() {
        val v = vault()
        v.add(e("a"), true)
        assertEquals(LogbookAdd.FAILED, v.add(e("old", -1_000), true))
        assertEquals(listOf(e("a")), (v.read() as LogbookRead.Ok).entries)
    }

    @Test fun exportNeverHandsASpreadsheetAFormula() {
        val csv = LogbookCsv.of(listOf(LogEntry("x", 0, "=HYPERLINK(\"a\")"), LogEntry("y", 1, "@sum")), ZoneOffset.UTC)
        assertTrue(csv, csv.contains("\"'=HYPERLINK(\"\"a\"\")\"") && csv.contains("\"'@sum\""))
    }

    @Test fun exportIsAPlainTable() {
        val csv = LogbookCsv.of(listOf(LogEntry("x", 0, "ran \"5\" km", BigDecimal(5), "km")), ZoneOffset.UTC)
        assertEquals("when,what,number,unit\r\n\"1970-01-01 00:00\",\"ran \"\"5\"\" km\",\"5\",\"km\"\r\n", csv)
    }
}
