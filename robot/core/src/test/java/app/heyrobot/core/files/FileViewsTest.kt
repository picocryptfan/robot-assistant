// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

/**
 * files-1.4-plan.md §6.2 to §6.5 (F7): what the Files screens show and what your taps change. Inbox's "need you" and
 * "being read", Recently filed and its 7-day Undo, drawers with counts (starter first, then yours, empty ones kept in the
 * index's "drawers" key and refused when they take a starter drawer's name in either language), a drawer newest first
 * with its months and totals by currency (a total to check left out and counted), rename ("named by you"), fields you set
 * (checked; answering "Check the total" files the receipt), tags, people, hidden files left out everywhere, BM25 search in
 * both languages, and the name and type a copy handed to another app gets.
 */
class FileViewsTest {
    private val zone = ZoneOffset.UTC
    private val words = NameWords("due")
    private var n = 0

    private fun id(): FileId = FileId.of("%032x".format(++n))!!

    private fun file(name: String, kind: FileKind = FileKind.RECEIPT, state: FileState = FileState.FILED, drawer: String? = Drawers.RECEIPTS,
        fields: Map<String, String> = emptyMap(), filing: Filing? = Filing(FilingStep.FILED), at: Long = 1_000L * n, filedAt: Long? = at + 10,
        by: FiledBy? = FiledBy.ROBOT, text: String = "", type: String? = "application/pdf", size: Long = 100): FileEntry {
        val i = id()
        return FileEntry(i, name, kind, state, Provenance(FileSource.SHARE, at = at, size = size, sha256 = "a".repeat(64), type = type,
            filedBy = if (state == FileState.FILED) by else null, filedAt = if (state == FileState.FILED) filedAt else null),
            drawer = if (state == FileState.FILED) drawer else null, fields = fields.mapValues { FileField(it.value, FieldBy.READ) }, filing = filing, text = text)
    }

    private fun inbox(name: String, filing: Filing?, kind: FileKind = FileKind.UNKNOWN) = file(name, kind, FileState.INBOX, null, filing = filing)

    @Test fun homeSplitsInboxAndListsRecentAndDrawers() {
        val ask = inbox("a", Filing(FilingStep.ASK, FilingWhy.KIND, choices = listOf(FileKind.RECEIPT, FileKind.BILL)))
        val them = inbox("b", Filing(FilingStep.ASK, FilingWhy.FILE_THEM), FileKind.RECEIPT)
        val held = inbox("c", Filing(FilingStep.HELD, FilingWhy.OFF), FileKind.PHOTO)
        val danger = inbox("d", Filing(FilingStep.HELD, FilingWhy.DANGEROUS), FileKind.DANGEROUS)
        val reading = inbox("e", Filing(FilingStep.WAIT, FilingWhy.NO_VISION))
        val fresh = inbox("f", null)
        val filed = (1..12).map { file("r$it", fields = mapOf("total" to "1.00", "currency" to "CAD")) }
        val car = file("esso", drawer = "Car")
        val idx = FileIndex(listOf(ask, them, held, danger, reading, fresh) + filed + car)
        val h = FileViews.home(idx)
        assertEquals(setOf(ask, them, held, danger), h.needYou.toSet())
        assertEquals(setOf(reading, fresh), h.reading.toSet())
        assertEquals(listOf(them), h.fileThem)
        assertEquals(6, h.inbox)
        // The last 10 filed, the newest first.
        assertEquals(10, h.recent.size)
        assertEquals(car, h.recent.first())
        assertEquals(Drawers.STARTER + "Car", h.drawers.map { it.id })
        assertEquals(12, h.drawers.first { it.id == Drawers.RECEIPTS }.count)
        assertEquals(1, h.drawers.first { it.id == "Car" }.count)
        assertFalse(h.empty)
        assertTrue(FileViews.home(FileIndex()).empty)
        // A file just deleted (its Undo running) is in no list and no count.
        val gone = FileViews.home(idx, hidden = setOf(car.id, ask.id))
        assertEquals(0, gone.drawers.first { it.id == "Car" }.count)
        assertFalse(ask in gone.needYou)
        assertEquals(17, gone.total)
    }

    @Test fun undoOnlyOnWhatRobotFiledAndForSevenDays() {
        val robot = file("a", filedAt = 1_000)
        val rule = file("b", filedAt = 1_000, by = FiledBy.RULE)
        val you = file("c", filedAt = 1_000, by = FiledBy.YOU)
        assertTrue(FileViews.canUndo(robot, 1_000 + FileViews.UNDO_MS))
        assertTrue(FileViews.canUndo(rule, 2_000))
        assertFalse(FileViews.canUndo(robot, 1_001 + FileViews.UNDO_MS))
        assertFalse(FileViews.canUndo(you, 2_000))
        assertFalse(FileViews.canUndo(inbox("d", null), 2_000))
    }

    @Test fun aDrawerNewestFirstWithItsMonthsAndTotals() {
        val aug = file("aug", fields = mapOf("date" to "2026-08-14", "total" to "84.12", "currency" to "CAD"))
        val sep1 = file("sep1", fields = mapOf("date" to "2026-09-02", "total" to "10.00", "currency" to "CAD"))
        val sep2 = file("sep2", fields = mapOf("date" to "2026-09-20", "total" to "5.50", "currency" to "USD"))
        val check = file("check", fields = mapOf("date" to "2026-09-21", "total" to "99.00", "currency" to "CAD", "check" to "total"))
        val other = file("bill", FileKind.BILL, drawer = Drawers.BILLS)
        val idx = FileIndex(listOf(aug, sep1, sep2, check, other))
        val files = FileViews.inDrawer(idx, Drawers.RECEIPTS, zone = zone)
        assertEquals(listOf(check, sep2, sep1, aug), files)
        assertEquals(listOf(2026 to 9, 2026 to 8), FileViews.months(files, zone))
        assertEquals(listOf(check, sep2, sep1), FileViews.inMonth(files, 2026, 9, zone))
        val t = FileViews.totals(FileViews.inMonth(files, 2026, 9, zone))
        assertEquals(mapOf<String?, Pair<Long, Int>>("CAD" to (1_000L to 1), "USD" to (550L to 1)), t.byCurrency)
        assertEquals(1, t.check)
        assertEquals("$84.12", Fmt.money(8412, "CAD", Lang.EN))
        assertEquals("84,12 $", Fmt.money(8412, "CAD", Lang.FR))
        assertEquals("1,234.50 USD", Fmt.money(123450, "USD", Lang.EN))
        assertEquals("August 14, 2026", Fmt.date(java.time.LocalDate.of(2026, 8, 14), Lang.EN))
        assertEquals("1er août 2026", Fmt.date(java.time.LocalDate.of(2026, 8, 1), Lang.FR))
    }

    @Test fun renameAndFieldsYouSet() {
        val r = file("2026-08-14 Costco 84.12 CAD", fields = mapOf("total" to "84.12", "check" to "total"))
        var idx = FileIndex(listOf(r))
        idx = FileViews.rename(idx, r.id, "  Groceries‮ for Sam  ")!!
        assertEquals("Groceries for Sam", idx.entry(r.id)!!.name)
        assertTrue(idx.entry(r.id)!!.named)
        assertNull(FileViews.rename(idx, r.id, " \u0007 "))
        // An amount you set answers "Check the total"; one that isn't an amount is refused.
        assertNull(FileViews.setField(idx, r.id, "total", "lots"))
        idx = FileViews.setField(idx, r.id, "total", "84,21 $")!!
        assertEquals(FileField("84.21", FieldBy.YOU), idx.entry(r.id)!!.fields["total"])
        assertNull(idx.entry(r.id)!!.fields["check"])
        assertNull(FileViews.setField(idx, r.id, "date", "14/08/2026"))
        idx = FileViews.setField(idx, r.id, "date", "2026-08-14")!!
        assertEquals("2026-08-14", idx.entry(r.id)!!.fields["date"]!!.value)
        // Empty removes it; a field Files doesn't let you set is refused.
        idx = FileViews.setField(idx, r.id, "date", "")!!
        assertNull(idx.entry(r.id)!!.fields["date"])
        assertNull(FileViews.setField(idx, r.id, "check", "none"))
        assertEquals("CAD", FileViews.cleanField("currency", "cad"))
        assertEquals("1234", FileViews.cleanField("account", "card 4500 1234"))
        assertNull(FileViews.cleanField("account", "12"))
    }

    @Test fun yourAnswerToCheckTheTotalFilesIt() {
        val asked = FileEntry(id(), "IMG_1.jpg", FileKind.RECEIPT, FileState.INBOX, Provenance(FileSource.SHARE, 1, 10, "b".repeat(64), type = "image/jpeg"),
            fields = mapOf("merchant" to FileField("Costco", FieldBy.READ), "date" to FileField("2026-08-14", FieldBy.READ),
                "currency" to FileField("CAD", FieldBy.READ), "check" to FileField("total", FieldBy.READ)),
            filing = Filing(FilingStep.ASK, FilingWhy.TOTAL))
        val idx = FileIndex(listOf(asked))
        assertNull(FileViews.answerTotal(idx, asked.id, "nope", FilesSettings(), emptyList(), words, 5))
        val e = FileViews.answerTotal(idx, asked.id, "84.12", FilesSettings(), emptyList(), words, 5)!!.entry(asked.id)!!
        assertEquals(FileState.FILED, e.state)
        assertEquals(Drawers.RECEIPTS, e.drawer)
        assertEquals("2026-08-14 Costco 84.12 CAD", e.name)
        assertEquals(FiledBy.YOU, e.from.filedBy)
        assertEquals(FieldBy.YOU, e.fields["total"]!!.by)
        // Only a file asking that.
        assertNull(FileViews.answerTotal(FileIndex(listOf(file("x"))), FileIndex(listOf(file("y"))).entries[0].id, "1.00", FilesSettings(), emptyList(), words, 5))
    }

    @Test fun tagsAndPeople() {
        val r = file("a")
        var idx = FileIndex(listOf(r))
        idx = FileViews.addTag(idx, r.id, " business ")!!
        assertEquals(listOf("business"), idx.entry(r.id)!!.tags)
        assertNull(FileViews.addTag(idx, r.id, "business"))
        assertNull(FileViews.addTag(idx, r.id, "  "))
        idx = FileViews.removeTag(idx, r.id, "business")!!
        assertTrue(idx.entry(r.id)!!.tags.isEmpty())
        idx = FileViews.addPerson(idx, r.id, "p-1")!!
        assertEquals(listOf("p-1"), idx.entry(r.id)!!.people)
        assertNull(FileViews.addPerson(idx, r.id, "not an id!"))
        idx = FileViews.removePerson(idx, r.id, "p-1")!!
        assertTrue(idx.entry(r.id)!!.people.isEmpty())
        idx = FileViews.opened(idx, r.id)!!
        assertTrue(idx.entry(r.id)!!.from.opened)
        assertNull(FileViews.opened(idx, r.id))
    }

    @Test fun yourDrawers() {
        val inCar = file("esso", drawer = "Car")
        var idx = FileIndex(listOf(inCar))
        assertEquals(listOf("Car"), FileViews.yours(idx))
        // A starter drawer's id or name, in English or French, and a name you already have, are taken.
        for (n in listOf("receipts", "Receipts", "Reçus", "Impôts", "car", "  ")) assertNull(n, FileViews.addDrawer(idx, n))
        assertEquals(FileViews.DrawerProblem.TAKEN, FileViews.drawerProblem(idx, "reçus"))
        assertEquals(FileViews.DrawerProblem.EMPTY, FileViews.drawerProblem(idx, " "))
        idx = FileViews.addDrawer(idx, "House")!!
        assertEquals(listOf("House", "Car"), FileViews.yours(idx))
        assertEquals(listOf("House"), idx.extra[FileViews.DRAWERS_KEY])
        // The index keeps the list sealed with it, through its codec.
        val back = (FileIndexCodec.decode(FileIndexCodec.encode(idx)) as IndexParse.Ok).index
        assertEquals(listOf("House", "Car"), FileViews.yours(back))
        // Renaming moves its files; removing only an empty one.
        idx = FileViews.renameDrawer(idx, "Car", "Auto")!!
        assertEquals("Auto", idx.entry(inCar.id)!!.drawer)
        assertEquals(listOf("House", "Auto"), FileViews.yours(idx))
        assertNull(FileViews.renameDrawer(idx, Drawers.RECEIPTS, "Tickets"))
        assertNull(FileViews.renameDrawer(idx, "House", "auto"))
        assertNull(FileViews.removeDrawer(idx, "Auto"))
        idx = FileViews.removeDrawer(idx, "House")!!
        assertEquals(listOf("Auto"), FileViews.yours(idx))
        assertNull(FileViews.removeDrawer(idx, Drawers.BILLS))
    }

    @Test fun searchInBothLanguages() {
        val costco = file("2026-08-14 Costco 84.12 CAD", fields = mapOf("merchant" to "Costco", "total" to "84.12"), text = "Costco Wholesale\nTotal 84.12")
        val hydro = file("2026-09 Hydro-Québec 142.00 CAD", FileKind.BILL, drawer = Drawers.BILLS, fields = mapOf("merchant" to "Hydro-Québec"))
        val lease = file("Lease 2026-07-01", FileKind.DOCUMENT, drawer = Drawers.DOCUMENTS, text = "Residential lease")
        val idx = FileIndex(listOf(costco, hydro, lease))
        assertEquals(costco, FileViews.search(idx, "costco").first())
        assertEquals(hydro, FileViews.search(idx, "hydro quebec").first())
        // The drawer and the kind in both languages.
        assertEquals(hydro, FileViews.search(idx, "facture").first())
        assertEquals(costco, FileViews.search(idx, "reçu").first())
        assertEquals(lease, FileViews.search(idx, "lease").first())
        assertTrue(FileViews.search(idx, "  ").isEmpty())
        assertTrue(FileViews.search(idx, "costco", hidden = setOf(costco.id)).isEmpty())
    }

    @Test fun aCopyHandedOutIsNamedAndTypedByItsBytes() {
        val photo = file("IMG_0001.HEIC", FileKind.PHOTO, type = "image/heic")
        // Strip location on: a photo goes re-encoded as a JPEG; off: as it is.
        assertEquals(OutPlan.Go(OutgoingCopy.STRIPPED, "IMG_0001.jpg", "image/jpeg"), FileOut.plan(photo, FileType.HEIC, stripLocation = true))
        assertEquals(OutPlan.Go(OutgoingCopy.ORIGINAL, "IMG_0001.heic", "image/heic"), FileOut.plan(photo, FileType.HEIC, stripLocation = false))
        val png = file("screen.png", FileKind.PHOTO, type = "image/png")
        assertEquals(OutPlan.Go(OutgoingCopy.STRIPPED, "screen.png", "image/png"), FileOut.plan(png, FileType.PNG, true))
        // A video, or an image too big for :files, can't lose its location: ask first.
        assertEquals(OutPlan.AskFirst("clip.mp4", "video/mp4"), FileOut.plan(file("clip", FileKind.VIDEO, type = "video/mp4"), FileType.MP4, true))
        val big = file("big.jpg", FileKind.PHOTO, type = "image/jpeg", size = 65L * 1024 * 1024)
        assertTrue(FileOut.plan(big, FileType.JPEG, true) is OutPlan.AskFirst)
        // Documents go as they are, under Robot's name and the type's extension.
        assertEquals(OutPlan.Go(OutgoingCopy.ORIGINAL, "2026-08-14 Costco 84.12 CAD.pdf", "application/pdf"),
            FileOut.plan(file("2026-08-14 Costco 84.12 CAD"), FileType.PDF, true))
        // A file that could run something: no installer's type, its name kept.
        assertEquals(OutPlan.Go(OutgoingCopy.ORIGINAL, "app.apk", "application/octet-stream"), FileOut.plan(file("app.apk", FileKind.DANGEROUS, type = "application/zip"), FileType.ZIP, true))
        // Characters no file name may hold, a name made only of dots, a long one.
        assertEquals("a_b_c_.pdf", FileOut.name("a/b\\c:", FileType.PDF))
        assertEquals("file.txt", FileOut.name("...", FileType.TEXT))
        assertEquals("Costco 84.12.pdf", FileOut.name("Costco 84.12", FileType.PDF))
        assertEquals("scan.pdf", FileOut.name("scan.PDF", FileType.PDF))
        assertEquals(124, FileOut.name("x".repeat(300), FileType.PDF).length)
        assertNotNull(FileOut.typeOf(photo))
    }
}
