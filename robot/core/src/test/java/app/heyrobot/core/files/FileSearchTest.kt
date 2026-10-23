// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * files-1.4-plan.md §6.5 and §5.1 (F9), with F0a's findings (files-1.4-measurements.md §7): BM25 alone misses the other
 * language, the words receipts don't print ("gas", "hydro", "groceries") and amounts. FileSearch adds kind and category
 * words in both languages from Robot's own fields, and reads amounts, days, kinds and drawers out of the words as filters.
 */
class FileSearchTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 22)
    private var n = 0

    private fun file(name: String, kind: FileKind, drawer: String?, fields: Map<String, String>, text: String = "", damaged: Boolean = false): FileEntry {
        val i = FileId.of("%032x".format(++n))!!
        return FileEntry(i, name, kind, FileState.FILED, Provenance(FileSource.SHARE, at = 1_000L * n, size = 100, sha256 = "a".repeat(64)),
            drawer = drawer, fields = fields.mapValues { FileField(it.value, FieldBy.READ) }, text = text, damaged = damaged)
    }

    // English and French receipts and bills, as F0a's set: the words they print, not the words you say.
    private val costco = file("2026-08-14 Costco 84.12 CAD", FileKind.RECEIPT, Drawers.RECEIPTS,
        mapOf("merchant" to "Costco", "date" to "2026-08-14", "total" to "84.12", "currency" to "CAD", "category" to "groceries"),
        "COSTCO WHOLESALE\nKIRKLAND 24 PK\nSUBTOTAL 79.12\nTOTAL 84.12\nKeep this receipt")
    private val costcoOld = file("2025-08-02 Costco 23.40 CAD", FileKind.RECEIPT, Drawers.RECEIPTS,
        mapOf("merchant" to "Costco", "date" to "2025-08-02", "total" to "23.40", "currency" to "CAD", "category" to "groceries"), "COSTCO\nTOTAL 23.40")
    private val esso = file("2026-09-03 Esso 61.07 CAD", FileKind.RECEIPT, "Car",
        mapOf("merchant" to "Esso", "date" to "2026-09-03", "total" to "61.07", "currency" to "CAD"), "ESSO\nORDINAIRE 38.2 L\nTOTAL 61,07 \$\nTPS TVQ")
    private val hydro = file("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17", FileKind.BILL, Drawers.BILLS,
        mapOf("merchant" to "Hydro-Québec", "date" to "2026-09-20", "total" to "142.00", "currency" to "CAD", "due" to "2026-10-17", "category" to "utilities"),
        "HYDRO-QUÉBEC\nMontant dû 142,00 \$\nkWh 1 204")
    private val jeanCoutu = file("2026-07-30 Jean Coutu 12.99 CAD", FileKind.RECEIPT, Drawers.RECEIPTS,
        mapOf("merchant" to "Jean Coutu", "date" to "2026-07-30", "total" to "12.99", "currency" to "CAD", "category" to "pharmacy"),
        "JEAN COUTU\nÉCRAN SOLAIRE\nTOTAL 12,99 \$\nConservez ce reçu")
    private val lease = file("Lease 2026-07-01", FileKind.DOCUMENT, Drawers.DOCUMENTS, mapOf("date" to "2026-07-01"), "RESIDENTIAL LEASE")
    private val check = file("2026-09-12 Metro", FileKind.RECEIPT, Drawers.RECEIPTS,
        mapOf("merchant" to "Metro", "date" to "2026-09-12", "total" to "150.00", "currency" to "CAD", "check" to "total"))
    private val broken = file("2026-09-30 Costco", FileKind.RECEIPT, Drawers.RECEIPTS, mapOf("merchant" to "Costco", "date" to "2026-09-30"), damaged = true)
    private val idx = FileIndex(listOf(costco, costcoOld, esso, hydro, jeanCoutu, lease, check, broken))

    private fun find(query: String, whenText: String? = null, drawer: String? = null): List<FileEntry> =
        FileSearch.find(idx, FileSearch.parse(query, whenText, drawer, yours = listOf("Car"), today = today), zone = zone)

    @Test fun theSameFileInBothLanguages() {
        // §6.5's pair, by voice (FilesRouter, then FileFind over the same search text) and through FileSearch's own reading.
        val en = FilesRouter.route("find my Costco receipt from August") as FilesCommand.Find
        val fr = FilesRouter.route("trouve mon reçu Costco d'août") as FilesCommand.Find
        assertEquals(FileFind.find(idx, en.query, zone = zone), FileFind.find(idx, fr.query, zone = zone))
        assertTrue(costco in FileFind.find(idx, en.query, zone = zone))
        assertEquals(listOf(costco), find("Costco receipt from August"))
        assertEquals(listOf(costco), find("mon reçu Costco d'août"))
        assertEquals(listOf(costco), find("Costco receipt", "August"))
        assertEquals(listOf(costco), find("reçu Costco", "août"))
    }

    @Test fun theOtherLanguageAndWordsReceiptsDontPrint() {
        // A French receipt found by "receipt"; an English one by « reçu »: kind words in both languages, from the kind.
        assertTrue(jeanCoutu in find("receipt"))
        assertTrue(costco in find("reçus"))
        // "gas" and « essence »: the Esso receipt says ORDINAIRE; its merchant is fuel in Robot's own table.
        assertEquals(esso, find("gas").first())
        assertEquals(esso, find("essence").first())
        assertEquals(esso, find("gas receipt").first())
        // "hydro bill", « électricité », "electricity": from the bill's category, never its text.
        assertEquals(hydro, find("hydro bill").first())
        assertEquals(hydro, find("électricité").first())
        assertEquals(hydro, find("electricity").first())
        // "groceries" and « épicerie »: Costco's category, and Metro's by Robot's merchant table (no category field).
        assertEquals(setOf(costco, costcoOld, check), find("groceries").toSet())
        assertEquals(setOf(costco, costcoOld, check), find("épicerie").toSet())
        assertEquals(jeanCoutu, find("pharmacy").first())
        assertEquals(jeanCoutu, find("sunscreen écran solaire").first())
    }

    @Test fun categoryWordsComeOnlyFromRobotsFields() {
        // A file whose text says "gas" in passing isn't a fuel receipt; one whose merchant Robot's table knows is.
        val words = FileSearch.words(esso)
        assertTrue(words.contains("essence") && words.contains("receipt") && words.contains("reçu"))
        val plain = file("note", FileKind.NOTE, Drawers.DOCUMENTS, emptyMap(), "gas")
        assertFalse(FileSearch.words(plain).contains("fuel"))
        assertEquals("", FileSearch.words(file("x", FileKind.UNKNOWN, null, emptyMap())))
    }

    @Test fun amountsAreSearchedThroughFields() {
        assertEquals(setOf(hydro), find("over 100 $").toSet())
        assertEquals(setOf(hydro), find("plus de 100 $").toSet())
        assertEquals(setOf(hydro), find("bills more than 100").toSet())
        assertEquals(listOf(costco), find("84.12"))
        assertEquals(listOf(costco), find("84,12 $"))
        assertEquals(listOf(costco), find("\$84.12"))
        assertEquals(setOf(esso, costco), find("receipts between 50 and 90").toSet())
        assertEquals(setOf(esso, costco), find("reçus entre 50 et 90 $").toSet())
        assertEquals(setOf(jeanCoutu), find("under 20 dollars from july").toSet())
        assertEquals(setOf(jeanCoutu, costcoOld), find("moins de 25 $").toSet())
        // A total that needs checking is never matched by an amount.
        assertFalse(check in find("over 100"))
        // A year is not an amount, nor a lone number.
        val q = FileSearch.parse("receipts 2025", today = today)
        assertNull(q.amount)
        assertEquals(LocalDate.of(2025, 1, 1), q.from)
        assertNull(FileSearch.parse("3 receipts", today = today).amount)
    }

    @Test fun daysAreSearchedThroughDates() {
        // A month alone is the last one begun: August 2026 on 22 Oct 2026; December is 2025's.
        assertEquals(LocalDate.of(2026, 8, 1), FileSearch.parse("August", today = today).from)
        assertEquals(LocalDate.of(2025, 12, 31), FileSearch.parse("receipts from december", today = today).to)
        assertEquals(LocalDate.of(2025, 8, 1), FileSearch.parse("août 2025", today = today).from)
        assertEquals(listOf(costcoOld), find("Costco", "August 2025"))
        assertEquals(listOf(costcoOld), find("Costco", "last year"))
        assertEquals(listOf(costcoOld), find("Costco", "l'an dernier"))
        assertEquals(listOf(costcoOld), find("Costco receipts in 2025"))
        assertEquals(setOf(esso, hydro), find("", "last month").toSet() - check)
        assertEquals(setOf(esso, hydro), find("le mois dernier").toSet() - check)
        assertEquals(LocalDate.of(2026, 10, 1), FileSearch.parse("this month", today = today).from)
        assertEquals(LocalDate.of(2026, 10, 12), FileSearch.parse("last week", today = today).from)
        // "may" is a month only with a year or a word before it.
        assertNull(FileSearch.parse("may I see the lease", today = today).from)
        assertEquals(LocalDate.of(2026, 5, 1), FileSearch.parse("receipts from may", today = today).from)
        // Never a date in the future: "October" on 22 Oct is this October.
        assertEquals(LocalDate.of(2026, 10, 1), FileSearch.parse("october", today = today).from)
    }

    @Test fun kindsAndDrawersAreFilters() {
        // "receipts" keeps only receipts; "bills" only bills; « factures » the same.
        assertTrue(find("receipts").all { it.kind == FileKind.RECEIPT })
        assertEquals(listOf(hydro), find("factures"))
        // A word narrower than its kind is still searched: "lease" is a document called Lease.
        assertEquals(listOf(lease), find("lease"))
        // The drawer given apart: a starter drawer in either language, or yours; one that doesn't exist is words.
        assertEquals(listOf(esso), find("receipt", drawer = "Car"))
        assertEquals(setOf(costco, costcoOld, jeanCoutu, check), find("receipt", drawer = "Receipts").toSet())
        assertEquals(setOf(costco, costcoOld, jeanCoutu, check), find("", drawer = "Reçus").toSet())
        assertEquals(listOf(esso), find("receipts in Car"))
        assertTrue(find("", drawer = "Glovebox").isEmpty())
    }

    @Test fun nothingSaidFindsAllAndDamagedFilesOnlyOnScreen() {
        val all = FileSearch.find(idx, FileSearch.parse("", today = today), zone = zone)
        assertEquals(idx.entries.size - 1, all.size)
        assertFalse(broken in all)
        assertEquals(hydro, all.first())
        assertTrue(broken in FileSearch.find(idx, FileSearch.parse("Costco", today = today), zone = zone, damaged = true))
        assertFalse(broken in find("Costco"))
        assertTrue(FileSearch.parse("  ").empty)
    }

    @Test fun totalsAreCodesOverEveryMatch() {
        // find_files' count and totals come from all the matches, not the best three, and leave out a total to check.
        val all = FileSearch.all(idx, FileSearch.parse("receipts", today = today), zone = zone)
        assertEquals(5, all.size)
        val t = FileViews.totals(all)
        assertEquals((8412L + 2340 + 6107 + 1299) to 4, t.byCurrency["CAD"])
        assertEquals(1, t.check)
    }

    @Test fun theScreensSearchUsesTheSameReading() {
        assertEquals(hydro, FileViews.search(idx, "over 100 $", today = today).first())
        assertEquals(esso, FileViews.search(idx, "essence", today = today).first())
        assertTrue(FileViews.search(idx, "  ", today = today).isEmpty())
    }
}
