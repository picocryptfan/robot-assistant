// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.parse.FileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * files-1.4-plan.md §4.1, §4.4, §4.5, §11's FileRulesTest and FilerTest (F6): the rules settle what they can from types
 * and words; the model's kind is taken only when the words don't point elsewhere; kind to drawer by a fixed table and your
 * rules (the most recent wins, a tainted one never); names by template from checked values only; tags; levels; unclear
 * files and dangerous ones never filed; the index keeps Robot's filing state.
 */
class FilerTest {
    private val words = NameWords("due")
    private val entry = FileEntry(FileId.of("0123456789abcdef0123456789abcdef")!!, "scan.pdf", FileKind.UNKNOWN, FileState.INBOX,
        Provenance(FileSource.SCAN, at = 1, size = 10, sha256 = "a".repeat(64), type = "application/pdf"))
    private val receipt = FileFieldValues(merchant = "Costco", date = LocalDate.of(2026, 8, 14), total = 8412, currency = "CAD")

    // ------------------------------------------------------------------ rules

    @Test fun typesSettleWhatTheyCan() {
        assertEquals(FileKind.DANGEROUS, FileRules.kindOf(FileType.ZIP, FileSource.SHARE, "Receipt Total 1.00").kind)
        assertEquals(FileKind.VIDEO, FileRules.kindOf(FileType.MP4, FileSource.SHARE, null).kind)
        assertEquals(FileKind.PHOTO, FileRules.kindOf(FileType.GIF, FileSource.SHARE, null).kind)
        assertEquals(FileKind.TRANSCRIPT, FileRules.kindOf(FileType.TEXT, FileSource.TRANSCRIPT, "Receipt Total TPS").kind)
        assertEquals(FileKind.NOTE, FileRules.kindOf(FileType.TEXT, FileSource.SHARE, "https://example.org").kind)
        assertNull(FileRules.kindOf(FileType.UNKNOWN, FileSource.SHARE, null).kind)
        assertFalse(FileRules.kindOf(FileType.UNKNOWN, FileSource.SHARE, null).read)
        assertFalse(FileRules.kindOf(FileType.MP4, FileSource.SHARE, null).read)
    }

    @Test fun wordsSettleKindsInBothLanguages() {
        fun kind(t: String) = FileRules.kindOf(FileType.PDF, FileSource.SHARE, t).let { if (it.settled) it.kind else null }
        assertEquals(FileKind.RECEIPT, kind("Reçu\nSous-total 10,00\nTPS 0,50\nTotal 10,50\nMerci"))
        assertEquals(FileKind.RECEIPT, kind("Sales receipt\nSubtotal 10.00\nGST 0.50\nTotal 10.50"))
        assertEquals(FileKind.BILL, kind("Facture\nNuméro de compte 123\nDate d'échéance 2026-10-17\nMontant dû 142,00"))
        assertEquals(FileKind.BILL, kind("Invoice\nAccount number 123\nDue date 2026-10-17\nAmount due 142.00"))
        assertEquals(FileKind.TAX_SLIP, kind("T4\nStatement of Remuneration Paid\nEmployment income"))
        assertEquals(FileKind.TAX_SLIP, kind("Relevé 1\nÉtat de la rémunération payée\nRevenus d'emploi"))
        assertEquals(FileKind.TICKET, kind("Boarding pass\nGate 12\nSeat 14C\nFlight AC 123"))
        assertEquals(FileKind.DOCUMENT, kind("Bail de logement\nLes parties\nSignature"))
        // A few words aren't enough, and two kinds close together aren't settled.
        assertNull(kind("Total 10.00"))
        assertNull(kind("Facture\nTotal 10.00"))
    }

    @Test fun theModelsKindOnlyWhenTheWordsDontPointElsewhere() {
        val vote = FileRules.kindOf(FileType.PDF, FileSource.SHARE, "Kind: medical\nSubtotal 20.00\nTotal 21.00\nGST 1.00")
        assertFalse(FileRules.agrees(FileKind.MEDICAL, vote))
        assertTrue(FileRules.agrees(FileKind.RECEIPT, vote))
        assertTrue(FileRules.agrees(FileKind.DOCUMENT, FileRules.kindOf(FileType.PDF, FileSource.SHARE, "Some words")))
    }

    @Test fun yourRulesTheMostRecentFirstNeverTainted() {
        val rules = listOf(
            FilingRule(merchant = "Esso", drawer = "Car", at = 1),
            FilingRule(merchant = "esso", drawer = "Gas", at = 2),
            FilingRule(merchant = "Esso", drawer = "Evil", at = 3, tainted = true),
            FilingRule(kind = FileKind.BILL, words = "Hydro", drawer = "House", at = 4),
        )
        assertEquals("Gas", FileRules.yours(rules, FileKind.RECEIPT, "ESSO", null)?.drawer)
        assertNull(FileRules.yours(rules, FileKind.RECEIPT, "Shell", null))
        assertEquals("House", FileRules.yours(rules, FileKind.BILL, null, "Hydro-Québec bill")?.drawer)
        assertNull(FileRules.yours(rules, FileKind.RECEIPT, null, "Hydro-Québec"))
        // A rule that matches nothing or does nothing isn't one.
        assertFalse(FilingRule(drawer = "Car").usable)
        assertFalse(FilingRule(merchant = "Esso").usable)
    }

    // ------------------------------------------------------------------ the decision

    private fun decide(kind: FileKind?, v: FileFieldValues = receipt, s: FilesSettings = FilesSettings(), rules: List<FilingRule> = emptyList(), yours: Boolean = false) =
        Filer.decide(entry, kind, v, s, rules, yours, words)

    @Test fun kindToDrawerByTheTable() {
        val drawers = mapOf(FileKind.RECEIPT to Drawers.RECEIPTS, FileKind.BILL to Drawers.BILLS, FileKind.DOCUMENT to Drawers.DOCUMENTS,
            FileKind.ID to Drawers.DOCUMENTS, FileKind.TAX_SLIP to Drawers.TAXES, FileKind.TICKET to Drawers.TRIPS, FileKind.MEDICAL to Drawers.MEDICAL,
            FileKind.PHOTO to Drawers.PHOTOS, FileKind.VIDEO to Drawers.PHOTOS, FileKind.NOTE to Drawers.DOCUMENTS, FileKind.TRANSCRIPT to Drawers.CONVERSATIONS,
            FileKind.OTHER to Drawers.DOCUMENTS)
        for ((k, d) in drawers) assertEquals(k.name, d, (decide(k) as FilingDecision.File).placement.drawer)
        assertNull(Drawers.of(FileKind.DANGEROUS))
        assertNull(Drawers.of(FileKind.UNKNOWN))
        assertEquals(Drawers.TAXES, Drawers.of(FileKind.RECEIPT, listOf(Drawers.TAX_TAG)))
    }

    @Test fun namesByTemplateFromCheckedValues() {
        fun name(k: FileKind, v: FileFieldValues, first: Boolean = false) = Filer.name(k, v, first, words)
        assertEquals("2026-08-14 Costco 84.12 CAD", name(FileKind.RECEIPT, receipt))
        assertEquals("Costco 2026-08-14 84.12 CAD", name(FileKind.RECEIPT, receipt, first = true))
        assertEquals("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17", name(FileKind.BILL,
            FileFieldValues(merchant = "Hydro-Québec", date = LocalDate.of(2026, 9, 30), total = 14200, currency = "CAD", due = LocalDate.of(2026, 10, 17))))
        assertEquals("2026-09 Hydro-Québec 142.00 CAD échéance 2026-10-17", Filer.name(FileKind.BILL,
            FileFieldValues(merchant = "Hydro-Québec", date = LocalDate.of(2026, 9, 30), total = 14200, currency = "CAD", due = LocalDate.of(2026, 10, 17)), false, NameWords("échéance")))
        assertEquals("2025 T4 Acme", name(FileKind.TAX_SLIP, FileFieldValues(merchant = "Acme", slip = "T4", year = 2025)))
        assertEquals("Lease 2026-07-01", name(FileKind.DOCUMENT, FileFieldValues(title = "Lease", date = LocalDate.of(2026, 7, 1))))
        // Fewer than two parts: the file keeps its name; photos keep theirs.
        assertNull(name(FileKind.RECEIPT, FileFieldValues(total = 100)))
        assertNull(name(FileKind.PHOTO, receipt))
        // A name you gave is kept for good.
        val p = Filer.place(entry.copy(name = "My receipt", named = true), FileKind.RECEIPT, receipt, words = words)
        assertEquals("My receipt", p.name)
    }

    @Test fun tagsAndFields() {
        val p = (decide(FileKind.RECEIPT) as FilingDecision.File).placement
        assertEquals(listOf("2026-08", "Costco"), p.tags)
        assertEquals(setOf("merchant", "date", "total", "currency"), p.fields.keys)
        assertTrue(p.fields.values.all { it.by == FieldBy.READ })
        assertEquals(listOf(Drawers.TAX_TAG), (decide(FileKind.TAX_SLIP, FileFieldValues(slip = "T4", year = 2025)) as FilingDecision.File).placement.tags)
        // Your rule's drawer and tags; filed by the rule.
        val r = (decide(FileKind.RECEIPT, rules = listOf(FilingRule(merchant = "Costco", drawer = "Groceries", tags = listOf("business")))) as FilingDecision.File).placement
        assertEquals("Groceries", r.drawer)
        assertTrue("business" in r.tags)
        assertEquals(FiledBy.RULE, r.by)
    }

    @Test fun unclearFilesStayInInbox() {
        val unknown = decide(null) as FilingDecision.Ask
        assertEquals(FilingWhy.KIND, unknown.why)
        assertEquals(Filer.DEFAULT_CHOICES, unknown.choices)
        val two = Filer.decide(entry, null, receipt, FilesSettings(), emptyList(), false, words, choices = listOf(FileKind.BILL, FileKind.RECEIPT)) as FilingDecision.Ask
        assertEquals(listOf(FileKind.BILL, FileKind.RECEIPT, FileKind.DOCUMENT, FileKind.PHOTO), two.choices)
        assertEquals(FilingWhy.TOTAL, (decide(FileKind.RECEIPT, receipt.copy(totalCheck = true)) as FilingDecision.Ask).why)
        assertEquals(FilingWhy.TOTAL, (decide(FileKind.BILL, receipt.copy(total = null)) as FilingDecision.Ask).why)
        // Even "file this" doesn't file an unclear one.
        assertTrue(decide(FileKind.RECEIPT, receipt.copy(totalCheck = true), yours = true) is FilingDecision.Ask)
        // Dangerous is never filed.
        assertTrue(decide(FileKind.DANGEROUS, yours = true) is FilingDecision.Ask)
    }

    @Test fun levels() {
        assertTrue(decide(FileKind.RECEIPT, s = FilesSettings(receipts = FileLevel.ASK)).let { it is FilingDecision.Ask && it.why == FilingWhy.FILE_THEM && it.placement != null })
        assertTrue(decide(FileKind.RECEIPT, s = FilesSettings(receipts = FileLevel.ASK), yours = true) is FilingDecision.File)
        assertTrue(decide(FileKind.RECEIPT, s = FilesSettings(receipts = FileLevel.OFF), yours = true) is FilingDecision.Held)
        assertTrue(decide(FileKind.PHOTO, s = FilesSettings(receipts = FileLevel.OFF)) is FilingDecision.File)
        assertTrue(decide(FileKind.MEDICAL, s = FilesSettings(documents = FileLevel.OFF)) is FilingDecision.Held)
        assertEquals(FileLevel.OFF, Filer.level(FileKind.DANGEROUS, FilesSettings()))
    }

    // ------------------------------------------------------------------ the index keeps the filing state

    @Test fun theFilingStateRoundTripsAndABadOneIsReadAsNone() {
        val f = Filing(FilingStep.FILED, FilingWhy.TOTAL, 2, listOf(FileKind.RECEIPT, FileKind.BILL), "scan.pdf", listOf("2026-08"))
        val e = entry.copy(filing = f)
        val back = (FileIndexCodec.decode(FileIndexCodec.encode(FileIndex(listOf(e)))) as IndexParse.Ok).index.entries.single()
        assertEquals(f, back.filing)
        assertEquals(e, back)
        // A newer Robot's step and keys are kept as they were.
        val newer = String(FileIndexCodec.encode(FileIndex(listOf(entry)))).replace("\"from\":", "\"filing\":{\"step\":\"SNOOZED\",\"tries\":1,\"later\":true},\"from\":")
        val read = (FileIndexCodec.decode(newer.toByteArray()) as IndexParse.Ok).index.entries.single()
        assertEquals(FilingStep.UNKNOWN, read.filing!!.step)
        assertTrue(String(FileIndexCodec.encode(FileIndex(listOf(read)))).contains("\"filing\":{\"step\":\"SNOOZED\",\"tries\":1,\"later\":true}"))
        // A broken one is no filing state (the file is tried again from the start), never a dropped entry.
        for (bad in listOf("\"x\"", "{\"step\":3}", "{\"step\":\"NEW\",\"tries\":-1}", "{\"step\":\"NEW\",\"tries\":1000}", "{\"step\":\"NEW\",\"was\":7}")) {
            val j = String(FileIndexCodec.encode(FileIndex(listOf(entry)))).replace("\"from\":", "\"filing\":$bad,\"from\":")
            val p = FileIndexCodec.decode(j.toByteArray()) as IndexParse.Ok
            assertEquals(bad, 0, p.dropped)
            assertNull(bad, p.index.entries.single().filing)
        }
        assertFalse(f.toString().contains("scan"))
    }
}
