// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FilePack
import app.heyrobot.core.files.FileTags
import app.heyrobot.core.files.PackKind
import app.heyrobot.core.files.PackWords
import app.heyrobot.core.files.Provenance
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * files-1.4-plan.md §7.2 (F12): a tax pack copied into the folder you picked. Robot's own folder in it, each file as Share
 * sends it (a photo whose location can't be removed left out and noted), then the table naming each copy; nothing while
 * locked, and a lock part way stops it with what was copied counted.
 */
class FilesExportTest {
    @get:Rule val tmp = TemporaryFolder()
    private var n = 0

    private fun entry(type: FileType, date: String): FileEntry {
        val id = FileId.of("%032x".format(++n))!!
        return FileEntry(id, "Costco $date", FileKind.RECEIPT, FileState.FILED, Provenance(FileSource.SHARE, at = 1_000L * n, size = 3, sha256 = "a".repeat(64), type = type.mime),
            drawer = Drawers.TAXES, tags = listOf(FileTags.TAX), fields = mapOf("date" to FileField(date, FieldBy.READ), "total" to FileField("1.00", FieldBy.READ)))
    }

    /** A folder on disk standing in for the one you picked. */
    private inner class Place(val failing: Set<String> = emptySet()) : ExportPlace {
        var made: File? = null
        val removed = ArrayList<String>()
        override fun folder(name: String): ExportFolder {
            val d = File(tmp.root, name).apply { mkdirs() }
            made = d
            return object : ExportFolder {
                override fun create(name: String, mime: String): OutputStream {
                    val out = FileOutputStream(File(d, name))
                    // FX2: a copy that fails part way, as a folder's app may: some bytes written, then an error.
                    return if (name !in failing) out else object : java.io.FilterOutputStream(out) {
                        var n = 0
                        override fun write(b: Int) { if (++n > 2) throw java.io.IOException("cut"); out.write(b) }
                        override fun write(b: ByteArray, off: Int, len: Int) { for (i in off until off + len) write(b[i].toInt()) }
                    }
                }
                override fun remove(name: String) { File(d, name).delete(); removed += name }
            }
        }
    }

    private val words = PackWords(List(FilePack.COLUMNS) { "c$it" }, { it.name }, { it }, "check", "not copied")

    private fun src(locked: () -> Boolean = { false }, stripLocation: Boolean = true) = ExportSource(
        open = { id -> ByteArrayInputStream(id.hex.toByteArray()) }, type = { e -> FileType.entries.first { it.mime == e.from.type } },
        strip = { ParseResult.Failed(ParseFailure.UNAVAILABLE) }, locked = locked, stripLocation = stripLocation)

    @Test fun copiesEachFileThenTheTable() {
        val pdf = entry(FileType.PDF, "2025-03-01")
        val photo = entry(FileType.JPEG, "2025-04-01")
        val video = entry(FileType.MP4, "2025-05-01")
        val pack = FilePack.of(FileIndex(listOf(pdf, photo, video)), PackKind.TAX, 2025)
        val place = Place()
        val r = FilesExport.run(pack, place, src(), words, Langs.of(Lang.EN))
        // The PDF goes as it is; the photo's location couldn't be removed here (:files unavailable) and the video's can't be: left out.
        assertEquals(ExportResult.Done(1, 2, "Robot tax pack 2025"), r)
        val d = place.made!!
        assertEquals(setOf("Costco 2025-03-01.pdf", "Robot tax pack 2025.csv"), d.list()!!.toSet())
        assertEquals(pdf.id.hex, File(d, "Costco 2025-03-01.pdf").readText())
        val table = File(d, "Robot tax pack 2025.csv").readText()
        assertTrue(table, table.contains("Costco 2025-03-01.pdf,RECEIPT,2025-03-01"))
        assertTrue(table, table.contains("Costco 2025-04-01,RECEIPT,2025-04-01") && table.contains("not copied"))
    }

    /** FX2 (files-1.4-review.md finding 13): a copy that fails part way is deleted, never left half-written in your folder. */
    @Test fun aCopyCutPartWayLeavesNothing() {
        val a = entry(FileType.PDF, "2025-03-01")
        val b = entry(FileType.PDF, "2025-03-02")
        val pack = FilePack.of(FileIndex(listOf(a, b)), PackKind.TAX, 2025)
        val place = Place(failing = setOf("Costco 2025-03-01.pdf"))
        val r = FilesExport.run(pack, place, src(), words, Langs.of(Lang.EN))
        assertEquals(ExportResult.Done(1, 1, "Robot tax pack 2025"), r)
        assertEquals(listOf("Costco 2025-03-01.pdf"), place.removed)
        assertEquals(setOf("Costco 2025-03-02.pdf", "Robot tax pack 2025.csv"), place.made!!.list()!!.toSet())
        // The table too: a table cut part way goes.
        val cut = Place(failing = setOf("Robot tax pack 2025.csv"))
        assertEquals(ExportResult.Stopped(ExportStop.FAILED, 2), FilesExport.run(pack, cut, src(), words, Langs.of(Lang.EN)))
        assertEquals(listOf("Robot tax pack 2025.csv"), cut.removed)
        assertEquals(setOf("Costco 2025-03-01.pdf", "Costco 2025-03-02.pdf"), cut.made!!.list()!!.toSet())
    }

    @Test fun withLocationStrippingOffEveryFileGoesAsItIs() {
        val pack = FilePack.of(FileIndex(listOf(entry(FileType.JPEG, "2025-04-01"), entry(FileType.MP4, "2025-05-01"))), PackKind.TAX, 2025)
        assertEquals(ExportResult.Done(2, 0, "Robot tax pack 2025"), FilesExport.run(pack, Place(), src(stripLocation = false), words, Langs.of(Lang.EN)))
    }

    @Test fun nothingWhileLockedAndALockStopsIt() {
        val pack = FilePack.of(FileIndex(listOf(entry(FileType.PDF, "2025-03-01"), entry(FileType.PDF, "2025-04-01"))), PackKind.TAX, 2025)
        val none = Place()
        assertEquals(ExportResult.Stopped(ExportStop.LOCKED, 0), FilesExport.run(pack, none, src(locked = { true }), words, Langs.of(Lang.EN)))
        assertEquals(null, none.made)
        // Locked after the first file: it stays, the rest and the table don't go.
        var calls = 0
        val place = Place()
        val r = FilesExport.run(pack, place, src(locked = { ++calls > 3 }), words, Langs.of(Lang.EN))
        assertEquals(ExportResult.Stopped(ExportStop.LOCKED, 1), r)
        assertEquals(listOf("Costco 2025-03-01.pdf"), place.made!!.list()!!.toList())
    }

    @Test fun noFolderNoExport() {
        val pack = FilePack.of(FileIndex(listOf(entry(FileType.PDF, "2025-03-01"))), PackKind.TAX, 2025)
        assertEquals(ExportResult.Stopped(ExportStop.NO_FOLDER, 0), FilesExport.run(pack, { null }, src(), words, Langs.of(Lang.EN)))
    }
}
