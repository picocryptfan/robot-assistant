// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.memory.MemoryFiling
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * files-1.4-plan.md §4.4 and §4.5 (F12): the tags `business` and `personal`. Robot adds them only from your taps or your
 * rules; a file is one or the other; their words in either language are stored as the code; the second file of a merchant
 * you tag teaches a rule; a rule's words name them in its language.
 */
class FileExtrasTest {
    private var n = 0
    private val words = NameWords("due")

    private fun id(): FileId = FileId.of("%032x".format(++n))!!

    private fun file(merchant: String? = "Hydro", kind: FileKind = FileKind.BILL, tags: List<String> = emptyList(), drawer: String = Drawers.BILLS,
        fields: Map<String, String> = emptyMap()): FileEntry =
        FileEntry(id(), "f$n", kind, FileState.FILED, Provenance(FileSource.SHARE, at = 1_000L * n, size = 10, sha256 = "a".repeat(64)),
            drawer = drawer, tags = tags, fields = (fields + listOfNotNull(merchant?.let { "merchant" to it })).mapValues { FileField(it.value, FieldBy.READ) })

    @Test fun typedWordsBecomeTheCode() {
        assertEquals(FileTags.BUSINESS, FileTags.of("Business"))
        assertEquals(FileTags.BUSINESS, FileTags.of(" affaires "))
        assertEquals(FileTags.PERSONAL, FileTags.of("Perso"))
        assertEquals(FileTags.PERSONAL, FileTags.of("personnel"))
        assertEquals(FileTags.TAX, FileTags.of("Impôts"))
        assertEquals("Garage", FileTags.of("Garage"))
        assertNull(FileTags.of("   "))
    }

    @Test fun businessAndPersonalNeverBoth() {
        val f = file(tags = listOf("2026-09", FileTags.PERSONAL))
        val idx = FileIndex(listOf(f))
        val b = FileViews.addTag(idx, f.id, "affaires")!!.entry(f.id)!!
        assertEquals(listOf("2026-09", FileTags.BUSINESS), b.tags)
        // Already there: nothing to change.
        assertNull(FileViews.addTag(FileIndex(listOf(b)), b.id, "business"))
        assertEquals(listOf("a", FileTags.BUSINESS), FileTags.exclusive(listOf("a", FileTags.BUSINESS, FileTags.PERSONAL)))
        assertEquals(listOf("a", FileTags.PERSONAL), FileTags.exclusive(listOf("a", FileTags.BUSINESS, FileTags.PERSONAL), wins = FileTags.PERSONAL))
        assertEquals(listOf("a", "b"), FileTags.exclusive(listOf("a", "b")))
    }

    @Test fun robotNeverTagsBusinessOrPersonalOnItsOwn() {
        val e = file(merchant = null, kind = FileKind.RECEIPT, drawer = Drawers.RECEIPTS).copy(state = FileState.INBOX, drawer = null)
        val v = FileFieldValues(merchant = "Costco", total = 8412, currency = "CAD", date = java.time.LocalDate.of(2026, 8, 14))
        val p = Filer.place(e, FileKind.RECEIPT, v, words = words)
        assertFalse(FileTags.BUSINESS in p.tags || FileTags.PERSONAL in p.tags)
        // Your rule says business: it wins over a personal tag the file had.
        val rule = FilingRule(kind = FileKind.RECEIPT, merchant = "Costco", words = null, drawer = null, tags = listOf(FileTags.BUSINESS), nameFirst = null, at = 1)
        val p2 = Filer.place(e.copy(tags = listOf(FileTags.PERSONAL)), FileKind.RECEIPT, v, rules = listOf(rule), words = words)
        assertTrue(FileTags.BUSINESS in p2.tags)
        assertFalse(FileTags.PERSONAL in p2.tags)
        assertEquals(FiledBy.RULE, p2.by)
    }

    @Test fun theSecondTaggedFileOfAMerchantTeachesARule() {
        val a = file(tags = listOf(FileTags.BUSINESS))
        val b = file()
        val idx = FileViews.addTag(FileIndex(listOf(a, b)), b.id, "Business")!!
        val rule = FilingRules.fromTag(idx, b.id, FileTags.BUSINESS, emptyList())
        assertNotNull(rule)
        assertEquals(listOf(FileTags.BUSINESS), rule!!.tags)
        assertEquals("Hydro", rule.merchant)
        // The first one teaches nothing.
        val esso = file(merchant = "Esso")
        val only = FileViews.addTag(FileIndex(listOf(esso)), esso.id, "personal")!!
        assertNull(FilingRules.fromTag(only, esso.id, FileTags.PERSONAL, emptyList()))
    }

    @Test fun aRuleNamesTheTagInItsLanguage() {
        val f = MemoryFiling(kind = FileKind.BILL.name, merchant = "Hydro", tags = listOf(FileTags.BUSINESS), learned = true)
        assertEquals("Hydro bills get the tag business.", FilingRules.text(f, Lang.EN))
        assertTrue(FilingRules.text(f, Lang.FR), FilingRules.text(f, Lang.FR).contains("affaires"))
        assertEquals("perso", FilesStrings.tag(FileTags.PERSONAL).render(Langs.of(Lang.FR)))
        assertEquals("Garage", FilesStrings.tag("Garage").render(Langs.of(Lang.FR)))
    }

    @Test fun searchFindsKnownTagsInBothLanguages() {
        val a = file(tags = listOf(FileTags.BUSINESS))
        val b = file(merchant = "Bell")
        val idx = FileIndex(listOf(a, b))
        assertEquals(listOf(a.id), FileViews.search(idx, "affaires").map { it.id })
        assertEquals(listOf(a.id), FileViews.search(idx, "business").map { it.id })
    }

    // The tax pack and the expense report (§7.2).

    private val utc = java.time.ZoneOffset.UTC

    private fun dated(kind: FileKind, date: String?, tags: List<String> = emptyList(), drawer: String = Drawers.RECEIPTS, fields: Map<String, String> = emptyMap(),
        damaged: Boolean = false): FileEntry =
        file(merchant = "Costco", kind = kind, tags = tags, drawer = drawer, fields = fields + listOfNotNull(date?.let { "date" to it })).copy(damaged = damaged)

    @Test fun whatGoesInATaxPack() {
        val slip = dated(FileKind.TAX_SLIP, null, drawer = Drawers.TAXES, fields = mapOf("year" to "2025", "slip" to "T4"))
        val tagged = dated(FileKind.RECEIPT, "2025-03-02", tags = listOf(FileTags.TAX), drawer = Drawers.TAXES)
        val moved = dated(FileKind.RECEIPT, "2025-05-01", drawer = Drawers.TAXES)
        val other = dated(FileKind.RECEIPT, "2025-06-01")
        val nextYear = dated(FileKind.RECEIPT, "2026-01-10", tags = listOf(FileTags.TAX))
        val danger = dated(FileKind.DANGEROUS, "2025-01-01", tags = listOf(FileTags.TAX))
        val broken = dated(FileKind.RECEIPT, "2025-02-01", tags = listOf(FileTags.TAX), damaged = true)
        val idx = FileIndex(listOf(slip, tagged, moved, other, nextYear, danger, broken))
        val p = FilePack.of(idx, PackKind.TAX, 2025, utc)
        // A slip by its tax year (it came in during 2026's first months in real life), receipts by their own date, oldest first.
        assertEquals(setOf(slip.id, tagged.id, moved.id), p.files.map { it.id }.toSet())
        assertEquals(1, p.damaged)
        assertEquals(listOf(2026, 2025), FilePack.years(idx, PackKind.TAX, utc))
        assertEquals(2025, FilePack.defaultYear(java.time.LocalDate.of(2026, 4, 1)))
        // The expense report: business files only.
        val biz = dated(FileKind.BILL, "2025-07-01", tags = listOf(FileTags.BUSINESS))
        val e = FilePack.of(FileIndex(listOf(biz, other, tagged)), PackKind.EXPENSES, 2025, utc)
        assertEquals(listOf(biz.id), e.files.map { it.id })
    }

    @Test fun theTableIsCodesNumbersAndNoFormulas() {
        val a = dated(FileKind.RECEIPT, "2025-03-02", tags = listOf(FileTags.TAX), fields = mapOf("total" to "84.12", "currency" to "CAD", "tax_gst" to "3.66",
            "tax_qst" to "7.30")).let { it.copy(fields = it.fields + ("merchant" to FileField("=HYPERLINK(\"x\")", FieldBy.READ))) }
        val b = dated(FileKind.RECEIPT, "2025-04-02", tags = listOf(FileTags.TAX), fields = mapOf("total" to "12.00", "check" to "total"))
        val slip = dated(FileKind.TAX_SLIP, null, drawer = Drawers.TAXES, fields = mapOf("year" to "2025", "slip" to "RL-1, case A"))
        val p = FilePack.of(FileIndex(listOf(a, b, slip)), PackKind.TAX, 2025, utc)
        val words = PackWords(FilesStrings.csvColumns().map { it.render(Langs.of(Lang.FR)) }, { it.name }, { FilesStrings.drawer(it).render(Langs.of(Lang.FR)) },
            "Vérifie le total", "Pas copié")
        val csv = FilePack.csv(p, mapOf(a.id to "a.pdf", b.id to null, slip.id to "s.pdf"), words, utc)
        assertTrue(csv.startsWith("\uFEFF"))
        val rows = csv.removePrefix("\uFEFF").split("\r\n").filter { it.isNotEmpty() }
        assertEquals("Fichier,Genre,Date,Commerçant,Total,Devise,TPS,TVH,TVP,TVQ,Feuillet,Année d’imposition,Tiroir,Note", rows[0])
        assertEquals(4, rows.size)
        // A merchant that looks like a formula starts with an apostrophe; amounts stay plain numbers.
        val ra = rows.first { it.startsWith("a.pdf,") }
        val rb = rows.first { it.startsWith(b.name + ",") }
        val rs = rows.first { it.startsWith("s.pdf,") }
        assertTrue(ra, ra.startsWith("a.pdf,RECEIPT,2025-03-02,\"'=HYPERLINK(\"\"x\"\")\",84.12,CAD,3.66,,,7.30,"))
        // A total to check stays empty and says so; a file not copied keeps its own name and says so.
        assertTrue(rb, rb.contains(",RECEIPT,2025-04-02,Costco,,,") && rb.endsWith(",Reçus,\"Vérifie le total; Pas copié\""))
        assertTrue(rs, rs.contains("\"RL-1, case A\",2025,Impôts,"))
        assertEquals("'-5", FilePack.text("-5"))
        assertEquals("Costco", FilePack.text("Costco"))
    }

    @Test fun uniqueNamesAndFolders() {
        assertEquals(listOf("Costco.pdf", "costco (2).pdf", "Costco (3).pdf", "x"), FilePack.unique(listOf("Costco.pdf", "costco.pdf", "Costco.pdf", "x")))
        assertEquals("Robot tax pack 2026", FilePack.folder(PackKind.TAX, 2026, Langs.of(Lang.EN)))
        assertEquals("Rapport de dépenses 2026 de Robot", FilePack.folder(PackKind.EXPENSES, 2026, Langs.of(Lang.FR)))
    }

    @Test fun packPhrasesInBothLanguages() {
        val today = java.time.LocalDate.of(2026, 10, 22)
        fun r(s: String) = FilesRouter.route(s, today = today) as? FilesCommand.Pack
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2026, Lang.EN), r("make my 2026 tax pack"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2025, Lang.EN), r("Make my tax pack for 2025."))
        assertEquals(FilesCommand.Pack(PackKind.TAX, null, Lang.EN), r("make my tax pack"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2025, Lang.EN), r("prepare my tax pack for last year"))
        assertEquals(FilesCommand.Pack(PackKind.EXPENSES, 2026, Lang.EN), r("make my expense report for this year"))
        assertEquals(FilesCommand.Pack(PackKind.EXPENSES, 2025, Lang.EN), r("prepare my business expense report for 2025"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2026, Lang.FR), r("fais mon dossier d'impôts 2026"))
        assertEquals(FilesCommand.Pack(PackKind.TAX, 2025, Lang.FR), r("Fais mon dossier d’impôts de l’an dernier"))
        assertEquals(FilesCommand.Pack(PackKind.EXPENSES, 2026, Lang.FR), r("prépare mon rapport de dépenses pour cette année"))
        for (no in listOf("how do I file my taxes", "make an expense", "when is the tax deadline", "fais mes impôts", "prépare un rapport", "make my bed"))
            assertNull(no, FilesRouter.route(no, today = today))
    }

    // Travel mode: hidden drawers (§1, §6.6).

    @get:org.junit.Rule val tmp = org.junit.rules.TemporaryFolder()

    private val travel = FilesSettings(travel = true, hidden = setOf(Drawers.MEDICAL, "Lawyer"))

    private fun cabinet(): FileIndex {
        val med = dated(FileKind.MEDICAL, "2025-03-01", drawer = Drawers.MEDICAL)
        val law = dated(FileKind.DOCUMENT, "2025-04-01", tags = listOf(FileTags.TAX), drawer = "Lawyer")
        val rec = dated(FileKind.RECEIPT, "2025-05-01", tags = listOf(FileTags.TAX), drawer = Drawers.TAXES)
        val inbox = dated(FileKind.UNKNOWN, null).copy(state = FileState.INBOX, drawer = null, filing = Filing(FilingStep.ASK, FilingWhy.NOT_YOURS, suggest = "Lawyer"))
        return FileIndex(listOf(med, law, rec, inbox), mapOf(FileViews.DRAWERS_KEY to listOf("Lawyer", "Car")))
    }

    @Test fun hiddenDrawersAppearNowhere() {
        val idx = cabinet()
        val v = TravelMode.shown(idx, travel)
        assertEquals(2, v.entries.size)
        assertEquals(setOf(Drawers.MEDICAL, "Lawyer"), v.outOfSight)
        // Not among the drawers, their counts, the search, a find, the tax pack, the brief; no suggestion names one.
        val drawers = FileViews.drawers(v).map { it.id }
        assertFalse(Drawers.MEDICAL in drawers || "Lawyer" in drawers)
        assertTrue("Car" in drawers)
        assertEquals(2, FileViews.home(v).total)
        assertEquals(4, FileViews.search(idx, "Costco").size)
        assertEquals(setOf(Drawers.TAXES, null), FileViews.search(v, "Costco").map { it.drawer }.toSet())
        assertNull(v.entries.single { it.state == FileState.INBOX }.filing!!.suggest)
        assertEquals(1, FilePack.of(v, PackKind.TAX, 2025, utc).files.size)
        assertEquals(2, FilePack.of(idx, PackKind.TAX, 2025, utc).files.size)
        // Your words can't name them: no move to, no rule's drawer found there.
        assertNull(FilesRouter.drawer("medical", FileViews.yours(v), v.outOfSight))
        assertNull(FilesRouter.drawer("Lawyer", listOf("Lawyer"), v.outOfSight))
        assertNull(FilesRouter.route("move the Costco receipt to Medical", out = v.outOfSight))
        assertNotNull(FilesRouter.route("move the Costco receipt to Medical"))
        // Off, or shown with your PIN: everything, the same index.
        assertTrue(TravelMode.shown(idx, travel.copy(travel = false)) === idx)
        assertTrue(TravelMode.shown(idx, travel, revealed = true) === idx)
        assertTrue(TravelMode.active(travel))
        assertFalse(TravelMode.active(travel.copy(hidden = emptySet())))
    }

    @Test fun aViewIsNeverWrittenBack() {
        val store = FileStore(tmp.newFolder("files"), FakeFileKeys(), "main", space = { 1L shl 40 })
        store.add(java.io.ByteArrayInputStream(ByteArray(10) { 1 }), FileIntake("a.pdf", FileSource.SHARE))
        val id = store.load().current!!.entries.single().id
        assertTrue(store.update { i -> i.copy(entries = i.entries.map { it.copy(state = FileState.FILED, drawer = Drawers.MEDICAL) }) } is IndexLoad.Loaded)
        // A change that hands back a travel-mode view (its files gone, or its marker kept) is refused whole.
        try {
            store.update { i -> TravelMode.shown(i, travel) }
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {}
        try {
            store.update { i -> i.copy(outOfSight = setOf("Car")) }
            org.junit.Assert.fail()
        } catch (_: IllegalArgumentException) {}
        assertEquals(Drawers.MEDICAL, store.load().current!!.entry(id)!!.drawer)
    }

    @Test fun hidingAndRenaming() {
        val s = FilesSettings()
        val h = TravelMode.hide(s, "Lawyer")!!
        assertEquals(setOf("Lawyer"), h.hidden)
        assertNull(TravelMode.hide(h, "Lawyer"))
        assertEquals(setOf("Legal"), TravelMode.renamed(h, "Lawyer", "Legal").hidden)
        assertEquals(h, TravelMode.renamed(h, "Car", "Auto"))
    }

    @Test fun travelModeInItsFile() {
        // Written only when set: a file without travel mode reads as before.
        val plain = FilesSettingsCodec.encode(FilesSettings())
        assertFalse(plain.contains("travel") || plain.contains("hidden"))
        val back = FilesSettingsCodec.decode(FilesSettingsCodec.encode(travel))
        assertEquals(travel, (back as FilesSettingsFile.Ok).read)
        assertEquals(FilesSettings(), FilesSettingsCodec.decode("{\"v\":1}").settings)
        for (bad in listOf("{\"v\":1,\"travel\":\"yes\"}", "{\"v\":1,\"hidden\":\"Car\"}", "{\"v\":1,\"hidden\":[1]}", "{\"v\":1,\"hidden\":[\"\"]}"))
            assertTrue(bad, FilesSettingsCodec.decode(bad) is FilesSettingsFile.Damaged)
    }
}
