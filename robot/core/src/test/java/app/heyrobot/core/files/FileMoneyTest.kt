// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.ApprovalGate
import app.heyrobot.core.Approval
import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.GateDecision
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SafetyCards
import app.heyrobot.core.ToolName
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.PhraseWire
import app.heyrobot.core.i18n.FilesStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * files-1.4-plan.md §7 (F10): spending answered by code (whole cents, per currency, totals to check left out and counted),
 * dates from fields only, reminders kept in the file's entry, subscriptions found by code, near-duplicates and clean-up
 * suggestions that never change anything, and the phrases that ask for them in English and Québec French.
 */
class FileMoneyTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 22)
    private val en = Langs.of(Lang.EN)
    private val fr = Langs.of(Lang.FR)
    private val nb = ' '
    private var n = 0

    private fun file(kind: FileKind, fields: Map<String, String>, state: FileState = FileState.FILED, text: String = "", name: String = "",
        sha: String? = null, named: Boolean = false, tags: List<String> = emptyList(), damaged: Boolean = false, filing: Filing? = null,
        filedAt: Long? = null, drawer: String? = Drawers.of(kind, tags)): FileEntry {
        val i = FileId.of("%032x".format(++n))!!
        return FileEntry(i, name, kind, state, Provenance(FileSource.SHARE, at = 1_000L * n, size = 100, sha256 = sha ?: "%064x".format(n), filedBy = if (filedAt != null) FiledBy.ROBOT else null,
            filedAt = filedAt), drawer = if (state == FileState.FILED) drawer else null, fields = fields.mapValues { FileField(it.value, FieldBy.READ) },
            text = text, named = named, tags = tags, damaged = damaged, filing = filing)
    }

    private fun receipt(merchant: String, date: String, total: String, currency: String? = "CAD", vararg more: Pair<String, String>) =
        file(FileKind.RECEIPT, buildMap { put("merchant", merchant); put("date", date); put("total", total); currency?.let { put("currency", it) }; putAll(more) })

    private fun bill(merchant: String, date: String, total: String, due: String? = null, vararg more: Pair<String, String>) =
        file(FileKind.BILL, buildMap { put("merchant", merchant); put("date", date); put("total", total); put("currency", "CAD"); due?.let { put("due", it) }; putAll(more) })

    // ------------------------------------------------------------------ spending

    @Test fun spendingIsCodesSumInWholeCentsPerCurrency() {
        // 0.29 is 28.999999999999996 as a Double: three of them truncated to cents would give 86. Robot's sum is 87, exactly.
        val a = receipt("Esso", "2026-09-03", "0.29")
        val b = receipt("Shell", "2026-09-10", "0.29")
        val c = receipt("Ultramar", "2026-09-20", "0.29")
        val us = receipt("Shell", "2026-09-21", "45.00", "USD")
        val none = receipt("Irving", "2026-09-22", "10.15", null)
        val idx = FileIndex(listOf(a, b, c, us, none))
        val q = SpendQuery(category = "fuel", from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30))
        val r = FileSpending.sum(idx, q, zone = zone)
        assertEquals(mapOf("USD" to 4500L, null to 1015L, "CAD" to 87L), r.byCurrency)
        assertEquals(listOf("USD", null, "CAD"), r.byCurrency.keys.toList())
        assertEquals(5, r.files)
        assertEquals(0, r.check)
        // 1.15 is 114.99999999999999 cents as a Double.
        assertEquals(115L, FileSpending.sum(FileIndex(listOf(receipt("Esso", "2026-09-03", "1.15"))), SpendQuery(category = "fuel"), zone = zone).byCurrency["CAD"])
        val words = FileMoneyText.spending(q, r, today)
        assertEquals("You spent 45.00${nb}USD, 10.15 and \$0.87 on gas in September 2026, from 5 receipts.", words.render(en))
    }

    @Test fun totalsThatNeedCheckingAreLeftOutAndCounted() {
        val ok = receipt("Esso", "2026-09-03", "61.07")
        val check = receipt("Esso", "2026-09-04", "99.00", "CAD", "check" to "total")
        val held = file(FileKind.RECEIPT, mapOf("merchant" to "Esso", "date" to "2026-09-05", "total" to "12.00"), state = FileState.INBOX,
            filing = Filing(FilingStep.ASK, FilingWhy.TOTAL))
        val asking = file(FileKind.RECEIPT, mapOf("merchant" to "Esso", "date" to "2026-09-06", "total" to "13.00"), state = FileState.INBOX,
            filing = Filing(FilingStep.ASK, FilingWhy.FILE_THEM))
        val noTotal = file(FileKind.RECEIPT, mapOf("merchant" to "Esso", "date" to "2026-09-07"))
        val broken = file(FileKind.RECEIPT, mapOf("merchant" to "Esso", "date" to "2026-09-08", "total" to "5.00"), damaged = true)
        val idx = FileIndex(listOf(ok, check, held, asking, noTotal, broken))
        val q = SpendQuery(category = "fuel", from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30))
        val r = FileSpending.sum(idx, q, zone = zone)
        assertEquals(mapOf("CAD" to 6107L), r.byCurrency)
        assertEquals(1, r.files)
        // The flagged total, the one held in Inbox for its total, and the one without a total; never Ask me first's, never a damaged file.
        assertEquals(3, r.check)
        assertEquals("You spent \$61.07 on gas in September 2026, from 1 receipt. 3 totals need checking, so they're left out.", FileMoneyText.spending(q, r, today).render(en))
        assertEquals("Tu as dépensé 61,07$nb\$ en essence en septembre 2026, d’après 1 reçu. 3 totaux doivent être vérifiés, alors ils sont laissés de côté.",
            FileMoneyText.spending(q, r, today).render(fr))
    }

    @Test fun categoriesMerchantsAndKinds() {
        val esso = receipt("Esso", "2026-08-03", "40.00")
        val costco = receipt("Costco", "2026-08-14", "84.12")
        val costcoGas = receipt("Costco", "2026-08-15", "30.00", "CAD", "category" to "fuel")
        val hydro = bill("Hydro-Québec", "2026-08-20", "142.00")
        val idx = FileIndex(listOf(esso, costco, costcoGas, hydro))
        val aug = SpendQuery(from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 8, 31))
        assertEquals(mapOf("CAD" to 7000L), FileSpending.sum(idx, aug.copy(category = "fuel"), zone = zone).byCurrency)
        assertEquals(mapOf("CAD" to 11412L), FileSpending.sum(idx, aug.copy(merchant = "costco"), zone = zone).byCurrency)
        // Receipts only, unless the words say bills or the spending is on utilities.
        assertEquals(mapOf("CAD" to 15412L), FileSpending.sum(idx, aug, zone = zone).byCurrency)
        assertEquals(mapOf("CAD" to 14200L), FileSpending.sum(idx, aug.copy(category = "utilities"), zone = zone).byCurrency)
        assertEquals(mapOf("CAD" to 14200L), FileSpending.sum(idx, aug.copy(bills = true), zone = zone).byCurrency)
        val none = FileSpending.sum(idx, aug.copy(merchant = "vacation"), zone = zone)
        assertEquals(0, none.files)
        assertEquals("Robot found no receipts for that.", FileMoneyText.spending(aug, none, today).render(en))
    }

    @Test fun readingTheWordsAfterTheQuestion() {
        val aug = LocalDate.of(2026, 8, 1) to LocalDate.of(2026, 8, 31)
        fun r(s: String, fold: Boolean = true) = FileSpending.parse(if (fold) FilesRouter.fold(s) else s, s, today)!!
        assertEquals(SpendQuery(category = "fuel", from = aug.first, to = aug.second), r("on gas in August"))
        assertEquals(SpendQuery(category = "fuel", from = aug.first, to = aug.second), r("en essence en août"))
        assertEquals(SpendQuery(category = "groceries", from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30)), r("en épicerie le mois passé"))
        assertEquals(SpendQuery(merchant = "Costco", from = LocalDate.of(2026, 10, 1), to = today), r("at Costco this month"))
        assertEquals(SpendQuery(merchant = "Costco", from = LocalDate.of(2026, 10, 1), to = today), r("chez Costco ce mois-ci"))
        assertEquals(SpendQuery(merchant = "Canadian Tire", from = LocalDate.of(2026, 1, 1), to = LocalDate.of(2026, 12, 31)), r("at Canadian Tire in 2026"))
        assertEquals(SpendQuery(category = "restaurants", from = LocalDate.of(2026, 1, 1), to = today), r("on restaurants this year"))
        assertEquals(SpendQuery(category = "utilities"), r("for hydro"))
        assertEquals(SpendQuery(from = LocalDate.of(2026, 9, 1), to = LocalDate.of(2026, 9, 30)), r("last month"))
        assertEquals(SpendQuery(merchant = "my vacation".substringAfter(' ')), r("on my vacation"))
        // A sentence, not a question about spending.
        assertNull(FileSpending.parse(FilesRouter.fold("on the thing my brother told me about at work"), "on the thing my brother told me about at work", today))
    }

    // ------------------------------------------------------------------ dates and reminders

    @Test fun datesComeFromFieldsOnly() {
        // The text holds a due date, a return date and an expiry; the fields hold none: no date at all.
        val bare = file(FileKind.BILL, mapOf("merchant" to "Hydro-Québec", "total" to "142.00"), text = "Due date: 2026-10-24\nReturn by 2026-10-25\nExpiry 2026-11-01")
        assertEquals(emptyList<FileDate>(), FileDates.of(bare))
        assertEquals(emptyList<FileDate>(), FileDates.upcoming(FileIndex(listOf(bare)), today))
        // Each date belongs to its kinds: a receipt's due field isn't a bill's due date; a receipt's expiry isn't an ID's.
        val odd = receipt("Esso", "2026-10-01", "40.00", "CAD", "due" to "2026-10-24", "expiry" to "2026-10-30")
        assertEquals(emptyList<FileDate>(), FileDates.of(odd))
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-24")
        val drill = receipt("Canadian Tire", "2026-10-10", "129.99", "CAD", "return_by" to "2026-11-09", "warranty" to "2027-10-10")
        val passport = file(FileKind.ID, mapOf("expiry" to "2027-01-10"), name = "Passport")
        val lease = file(FileKind.DOCUMENT, mapOf("date" to "2026-07-01", "expiry" to "2027-06-30"))
        val inbox = file(FileKind.BILL, mapOf("merchant" to "Bell", "due" to "2026-10-25"), state = FileState.INBOX)
        val past = bill("Vidéotron", "2026-09-01", "35.00", "2026-10-01")
        val idx = FileIndex(listOf(hydro, drill, passport, lease, inbox, past))
        val up = FileDates.upcoming(idx, today)
        // Each kind's own window: due 14 days, return 14 (the drill's return is 18 days off), warranty 30, expiry 90.
        assertEquals(listOf(hydro.id to DateKind.DUE, passport.id to DateKind.EXPIRY), up.map { it.entry.id to it.kind })
        assertEquals(listOf(drill.id to DateKind.RETURN), FileDates.upcoming(idx, today, setOf(DateKind.RETURN), to = LocalDate.of(2026, 11, 30)).map { it.entry.id to it.kind })
    }

    @Test fun remindersLiveInTheEntryAndAreSaidFromTheirDay() {
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-25")
        val idx = FileIndex(listOf(hydro))
        val d = FileDates.of(hydro).single()
        // Due in 3 days, Wednesday morning: "Add to my brief" puts it in tomorrow's brief (2 days ahead of its date).
        val now = ZonedDateTime.of(2026, 10, 22, 10, 0, 0, 0, zone)
        val next = FileDates.nextBrief(now)
        assertEquals(LocalDate.of(2026, 10, 23), next)
        assertEquals(LocalDate.of(2026, 10, 23), FileDates.remindOn(d, next))
        val set = FileRemind.set(idx, d, LocalDate.of(2026, 10, 23))!!
        assertEquals("due:2026-10-23", set.entries.single().fields[FileRemind.FIELD]!!.value)
        assertEquals(FieldBy.YOU, set.entries.single().fields[FileRemind.FIELD]!!.by)
        // Set once: a second tap changes nothing.
        assertNull(FileRemind.set(set, d, LocalDate.of(2026, 10, 24)))
        assertEquals(emptyList<FileDate>(), FileRemind.due(set, today))
        assertEquals(listOf(DateKind.DUE), FileRemind.due(set, LocalDate.of(2026, 10, 23)).map { it.kind })
        assertEquals(listOf(DateKind.DUE), FileRemind.due(set, LocalDate.of(2026, 10, 25)).map { it.kind })
        // After its date: gone from the brief.
        assertEquals(emptyList<FileDate>(), FileRemind.due(set, LocalDate.of(2026, 10, 26)))
        assertEquals(idx.entries.single().fields, FileRemind.remove(set, hydro.id, DateKind.DUE)!!.entries.single().fields)
        // A far date waits for its lead; a date already gone gets none.
        val passport = file(FileKind.ID, mapOf("expiry" to "2027-01-10"))
        assertEquals(LocalDate.of(2026, 12, 11), FileDates.remindOn(FileDates.of(passport).single(), next))
        assertNull(FileDates.remindOn(FileDate(hydro, DateKind.DUE, LocalDate.of(2026, 10, 20)), next))
    }

    @Test fun addToMyBriefOnItsOwnSetsBriefRemindersOnly() {
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-30")
        val later = bill("Bell", "2026-10-01", "80.00", "2026-12-30")
        val idx = FileIndex(listOf(hydro, later))
        val now = ZonedDateTime.of(2026, 10, 22, 10, 0, 0, 0, zone)
        val out = FileRemind.autoAdd(idx, listOf(hydro.id, later.id), now)
        assertEquals("due:2026-10-28", out.entry(hydro.id)!!.fields[FileRemind.FIELD]!!.value)
        assertEquals(FieldBy.READ, out.entry(hydro.id)!!.fields[FileRemind.FIELD]!!.by)
        // Beyond its window: nothing yet.
        assertNull(out.entry(later.id)!!.fields[FileRemind.FIELD])
        // Nothing else of either file changed.
        assertEquals(idx.entry(hydro.id)!!.fields, out.entry(hydro.id)!!.fields - FileRemind.FIELD)
    }

    @Test fun theBriefsLinesNameNoMerchantOnScreenAndNoPrivateFileAloud() {
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-24")
        val passport = file(FileKind.ID, mapOf("expiry" to "2026-11-15", "merchant" to "Passport Canada"))
        val drill = receipt("Canadian Tire", "2026-10-10", "129.99", "CAD", "return_by" to "2026-10-22")
        var idx = FileIndex(listOf(hydro, passport, drill, receipt("Costco", "2026-10-21", "84.12").let {
            it.copy(from = it.from.copy(filedBy = FiledBy.ROBOT, filedAt = LocalDate.of(2026, 10, 21).atStartOfDay(zone).toInstant().toEpochMilli() + 3_600_000))
        }))
        for (d in FileDates.upcoming(idx, today, to = today.plusYears(1))) idx = FileRemind.set(idx, d, today)!!
        val b = FileBrief.of(idx, today, zone)
        assertEquals(listOf(FileKind.RECEIPT), b.filed)
        val lines = FileMoneyText.brief(b, today)
        assertEquals(listOf("Robot filed 1 thing yesterday: 1 receipt.", "The return window for a purchase ends today.", "A bill is due Saturday October 24: \$142.00.",
            "An ID in Robot Files expires November 15, 2026."), lines.map { it.shown.render(en) })
        assertEquals(listOf("Robot filed 1 thing yesterday: 1 receipt.", "The return window for your Canadian Tire purchase ends today.",
            "Your Hydro-Québec bill is due Saturday October 24: \$142.00.", "An ID in Robot Files expires November 15, 2026."), lines.map { it.said.render(en) })
        assertEquals("Ta facture d’Hydro-Québec est due le samedi 24 octobre$nb: 142,00$nb\$.", lines[2].said.render(fr))
        assertFalse(lines.any { it.said.render(en).contains("Passport") })
    }

    @Test fun calendarWritesAlwaysTakeACardOnlyATapAnswers() {
        // Reminders from files go to add_event with FROM_CONTENT: a card even with new events set to run on their own and
        // Safety cards at "Only when Robot isn't sure who", and a spoken yes never answers it.
        val loosest = RobotSettings().let { s -> s.copy(approvals = s.approvals + (ToolName.ADD_EVENT to Approval.AUTO), safetyCards = SafetyCards.UNSURE_ONLY) }
        assertEquals(GateDecision.RUN, ApprovalGate.decide(ToolName.ADD_EVENT, false, loosest, asked = true))
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_EVENT, false, loosest, setOf(AskWhy.FROM_CONTENT), asked = true))
        assertEquals(AskWhy.FROM_CONTENT, CardAnswer.tapFor(setOf(AskWhy.FROM_CONTENT)))
        // With 1.1's alert ("alert": true, AskWhy.REMINDER, which a yes alone may answer) the file's reason still wins: a tap only.
        assertEquals(GateDecision.CONFIRM, ApprovalGate.decide(ToolName.ADD_EVENT, false, loosest, setOf(AskWhy.REMINDER, AskWhy.FROM_CONTENT), asked = true))
        assertEquals(AskWhy.FROM_CONTENT, CardAnswer.tapFor(setOf(AskWhy.REMINDER, AskWhy.FROM_CONTENT)))
        // Its title names a merchant only from a file that isn't private.
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-24")
        assertEquals("Pay Hydro-Québec bill", FileMoneyText.eventTitle(FileDates.of(hydro).single()).render(en))
        assertEquals("Payer la facture d’Hydro-Québec", FileMoneyText.eventTitle(FileDates.of(hydro).single()).render(fr))
        val id = file(FileKind.ID, mapOf("expiry" to "2027-01-10", "merchant" to "Passport Canada"))
        assertEquals("ID expires", FileMoneyText.eventTitle(FileDates.of(id).single()).render(en))
    }

    // ------------------------------------------------------------------ subscriptions

    @Test fun subscriptionsAreTheSameMerchantThreeMonthsInARow() {
        val vid = (7..10).map { m -> bill("Vidéotron", "2026-%02d-05".format(m), "35.00") }
        val netflix = (8..10).map { m -> bill("NETFLIX", "2026-%02d-12".format(m), "12.99") }
        val twice = (9..10).map { m -> bill("Bell", "2026-%02d-01".format(m), "80.00") }
        val gap = listOf(6, 8, 10).map { m -> bill("Rogers", "2026-%02d-01".format(m), "50.00") }
        val ended = (3..6).map { m -> bill("Fido", "2026-%02d-01".format(m), "40.00") }
        val receipts = (8..10).map { m -> receipt("Tim Hortons", "2026-%02d-03".format(m), "4.50") }
        val idx = FileIndex(vid + netflix + twice + gap + ended + receipts)
        val subs = FileSubscriptions.find(idx, today, zone = zone)
        assertEquals(listOf("Vidéotron" to 4, "NETFLIX" to 3), subs.map { it.merchant to it.months })
        assertEquals(YearMonth.of(2026, 10), subs.first().last)
        assertEquals(mapOf("CAD" to 4799L), FileSubscriptions.monthly(subs))
        val said = FileMoneyText.subscriptions(subs)
        assertEquals("2 subscriptions in your bills: \$47.99 a month.", said.text.render(en))
        assertEquals("2 subscriptions in your bills: \$47.99 a month. Your subscriptions: Vidéotron: \$35.00 a month and NETFLIX: \$12.99 a month.", said.spoken.render(en))
        assertEquals("2 abonnements dans tes factures$nb: 47,99$nb\$ par mois.", said.text.render(fr))
        assertEquals("Robot found no subscriptions in your bills: a merchant has to bill you 3 months in a row.", FileMoneyText.subscriptions(emptyList()).text.render(en))
    }

    // ------------------------------------------------------------------ clean-up

    @Test fun cleanupSuggestsAndNeverChangesAnything() {
        val first = receipt("Costco", "2026-08-14", "84.12")
        val copy = first.copy(id = FileId.of("%032x".format(++n))!!, from = first.from.copy(at = first.from.at + 5))
        val scan = file(FileKind.RECEIPT, mapOf("merchant" to "COSTCO", "date" to "2026-08-14", "total" to "84.12", "currency" to "CAD"), sha = "b".repeat(64))
        val namedScan = file(FileKind.RECEIPT, mapOf("merchant" to "Costco", "date" to "2026-08-14", "total" to "84.12", "currency" to "CAD"), sha = "c".repeat(64),
            named = true, name = "Costco, my copy")
        val ticket = file(FileKind.TICKET, mapOf("date" to "2026-09-01"))
        val futureTicket = file(FileKind.TICKET, mapOf("date" to "2026-12-01"))
        val returned = receipt("Canadian Tire", "2026-08-01", "129.99", "CAD", "return_by" to "2026-08-31")
        val warranty = receipt("Best Buy", "2026-08-01", "499.99", "CAD", "return_by" to "2026-08-31", "warranty" to "2027-08-01")
        val oldBill = bill("Bell", "2024-01-05", "80.00")
        val oldTax = file(FileKind.BILL, mapOf("merchant" to "Bell", "date" to "2023-01-05", "total" to "80.00"), tags = listOf(Drawers.TAX_TAG))
        val slip = file(FileKind.TAX_SLIP, mapOf("year" to "2019", "slip" to "T4"))
        val broken = file(FileKind.TICKET, mapOf("date" to "2026-01-01"), damaged = true)
        val inbox = file(FileKind.TICKET, mapOf("date" to "2026-01-01"), state = FileState.INBOX)
        val idx = FileIndex(listOf(first, copy, scan, namedScan, ticket, futureTicket, returned, warranty, oldBill, oldTax, slip, broken, inbox))
        val before = FileIndexCodec.encode(idx).toList()
        val s = FileCleanup.suggest(idx, today, zone = zone)
        assertEquals(listOf(copy.id to CleanupWhy.DUPLICATE, first.id to CleanupWhy.NEAR_DUPLICATE, scan.id to CleanupWhy.NEAR_DUPLICATE,
            ticket.id to CleanupWhy.PAST_TICKET, returned.id to CleanupWhy.RETURN_CLOSED, oldBill.id to CleanupWhy.OLD_BILL), s.map { it.entry.id to it.why })
        // The copy that stays is named: one you named; else the first in.
        assertEquals(first.id, s.first().of)
        assertEquals(namedScan.id, s[1].of)
        // Suggestions only: the index is byte for byte the same.
        assertEquals(before, FileIndexCodec.encode(idx).toList())
        // No delete anywhere in the clean-up's code: nothing in FileCleanup returns an index.
        assertTrue(FileCleanup::class.java.declaredMethods.none { it.returnType == FileIndex::class.java })
        // Tax files: kept six years from the end of their year.
        assertEquals(2025, FileCleanup.keepUntil(slip))
        assertEquals("Keep until the end of 2025: the CRA and Revenu Québec ask you to keep tax records for six years.", FileMoneyText.keepUntil(slip)!!.render(en))
        assertNull(FileCleanup.keepUntil(oldBill))
        assertEquals("Its return window closed on August 31, 2026, and no warranty is running.", FileMoneyText.why(s[4]).render(en))
    }

    // ------------------------------------------------------------------ the phrases

    @Test fun f10sPhrasesInBothLanguages() {
        fun r(s: String) = FilesRouter.route(s, today = today)
        val spend = r("how much did I spend on gas in August") as FilesCommand.Spend
        assertEquals(SpendQuery(category = "fuel", from = LocalDate.of(2026, 8, 1), to = LocalDate.of(2026, 8, 31)), spend.query)
        assertEquals(Lang.EN, spend.lang)
        val depense = r("combien j'ai dépensé en essence en août") as FilesCommand.Spend
        assertEquals(spend.query, depense.query)
        assertEquals(Lang.FR, depense.lang)
        assertEquals(SpendQuery(merchant = "Costco", from = LocalDate.of(2026, 10, 1), to = today), (r("How much have I spent at Costco this month?") as FilesCommand.Spend).query)
        assertEquals(SpendQuery(merchant = "Costco", from = LocalDate.of(2026, 10, 1), to = today), (r("combien ai-je dépensé chez Costco ce mois-ci") as FilesCommand.Spend).query)
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.THIS_WEEK, "", Lang.EN), r("what bills are due this week"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.THIS_WEEK, "", Lang.FR), r("quelles factures sont dues cette semaine"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.SOON, "", Lang.EN), r("which bills are due soon"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.THIS_MONTH, "", Lang.FR), r("quelles factures je dois payer ce mois-ci"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.DUE), Span.ANY, "hydro", Lang.EN), r("when is my Hydro bill due"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.EXPIRY), Span.SOON, "", Lang.EN), r("what documents are expiring soon"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.EXPIRY), Span.SOON, "", Lang.FR), r("quels documents expirent bientôt"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.EXPIRY), Span.ANY, "passport", Lang.EN), r("when does my passport expire"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.EXPIRY), Span.ANY, "passeport", Lang.FR), r("quand expire mon passeport"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.RETURN), Span.SOON, "", Lang.EN), r("what can I still return"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.RETURN), Span.SOON, "", Lang.FR), r("qu'est-ce que je peux encore retourner"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.WARRANTY), Span.SOON, "", Lang.EN), r("what's still under warranty"))
        assertEquals(FilesCommand.Upcoming(setOf(DateKind.WARRANTY), Span.SOON, "", Lang.FR), r("qu'est-ce qui est encore sous garantie"))
        assertEquals(FilesCommand.Subscriptions(Lang.EN), r("what subscriptions do I have"))
        assertEquals(FilesCommand.Subscriptions(Lang.FR), r("quels sont mes abonnements"))
        // Misfires: prices (the web's), the calendar, routines' reminders, a subscription to cancel, a fridge.
        for (t in listOf("how much does gas cost in Montreal", "how much is a passport renewal", "what's due on my calendar this week",
            "remind me to pay the hydro bill on Friday", "cancel my Netflix subscription", "when does the pharmacy close", "how much money do I have",
            "what's expiring in my fridge", "how much did the plumber charge", "my taxes are due soon", "how much do I pay for internet",
            "combien coûte l'essence à Montréal", "rappelle-moi de payer la facture d'Hydro vendredi", "annule mon abonnement Netflix",
            "qu'est-ce qui expire dans mon frigo", "when does my subscription expire")) assertNull(t, r(t))
    }

    @Test fun answersToWhatsComingUp() {
        val hydro = bill("Hydro-Québec", "2026-09-20", "142.00", "2026-10-24")
        val bell = bill("Bell", "2026-10-01", "80.00", "2026-10-26")
        val idx = FileIndex(listOf(hydro, bell))
        val found = FileDates.upcoming(idx, today, setOf(DateKind.DUE), FileMoneyText.until(Span.THIS_WEEK, today))
        assertEquals(listOf(hydro.id), found.map { it.entry.id })
        val said = FileMoneyText.upcoming(DateKind.DUE, Span.THIS_WEEK, found)
        assertEquals("1 bill is due this week. The soonest is October 24, 2026.", said.text.render(en))
        assertEquals("1 bill is due this week. Hydro-Québec. Due October 24, 2026: \$142.00.", said.spoken.render(en))
        assertEquals("1 facture est due cette semaine. La date la plus proche$nb: 24 octobre 2026.", said.text.render(fr))
        assertEquals("No bills are due today.", FileMoneyText.upcoming(DateKind.DUE, Span.TODAY, emptyList()).text.render(en))
        assertEquals("2 bills are due in the next 14 days. The soonest is October 24, 2026.",
            FileMoneyText.upcoming(DateKind.DUE, Span.SOON, FileDates.upcoming(idx, today, setOf(DateKind.DUE))).text.render(en))
    }

    @Test fun moneyAndDatesCrossProcesses() {
        val p = app.heyrobot.core.i18n.FilesStrings.dateAmount("date" to app.heyrobot.core.i18n.FilesStrings.dateDue("date" to Arg.Date(LocalDate.of(2026, 10, 17))),
            "amount" to Arg.Money(14200, "CAD"))
        val back = PhraseWire.decode(PhraseWire.encode(p))
        assertNotNull(back)
        assertEquals(p.render(fr), back!!.render(fr))
        val m = app.heyrobot.core.i18n.FilesStrings.inMonth("month" to Arg.Month(2026, 9))
        assertEquals("en septembre 2026", PhraseWire.decode(PhraseWire.encode(m))!!.render(fr))
    }

    /**
     * FX2 (F13's note): a file as Robot says it aloud, never its name read raw ("2026-08-14 Costco 84.12 CAD", "IMG_2034.jpg");
     * a private file by its kind; the name you gave it as you gave it. « de » elided before a vowel or a mute h only.
     */
    @Test fun aFileAsRobotSaysIt() {
        val costco = receipt("Costco", "2026-08-14", "84.12").copy(name = "2026-08-14 Costco 84.12 CAD")
        assertEquals("a receipt from Costco on August 14, 2026", FileMoneyText.said(costco).render(en))
        assertEquals("un reçu de Costco du 14 août 2026", FileMoneyText.said(costco).render(fr))
        assertEquals("un reçu d’Esso du 1er août 2026", FileMoneyText.said(receipt("Esso", "2026-08-01", "61.40")).render(fr))
        val depot = file(FileKind.BILL, mapOf("merchant" to "Home Depot"), name = "2026-09 Home Depot 42.00 CAD")
        assertEquals("a bill from Home Depot", FileMoneyText.said(depot).render(en))
        assertEquals("une facture de Home Depot", FileMoneyText.said(depot).render(fr))
        assertEquals("une facture d’Hydro-Québec", FileMoneyText.said(file(FileKind.BILL, mapOf("merchant" to "Hydro-Québec"))).render(fr))
        val photo = file(FileKind.PHOTO, emptyMap(), name = "IMG_2034.jpg")
        assertEquals("a photo", FileMoneyText.said(photo).render(en))
        assertEquals(null, FileMoneyText.more(photo))
        assertEquals("a receipt from August 14, 2026", FileMoneyText.said(file(FileKind.RECEIPT, mapOf("date" to "2026-08-14"))).render(en))
        val yours = file(FileKind.DOCUMENT, emptyMap(), name = "Lease for the cottage", named = true)
        assertEquals("Lease for the cottage", FileMoneyText.said(yours).render(en))
        val medical = file(FileKind.MEDICAL, mapOf("merchant" to "Clinique Saint-Louis", "date" to "2026-08-14"), name = "Clinique Saint-Louis 2026-08-14", named = true)
        assertEquals(FilesStrings.kind(FileKind.MEDICAL).render(en), FileMoneyText.said(medical).render(en))
        assertEquals(null, FileMoneyText.more(medical))
        // who(): a merchant, else your name for it, else nothing (the caller says its kind), never Robot's or the sender's name.
        assertEquals(null, FileMoneyText.who(file(FileKind.BILL, mapOf("due" to "2026-10-25"), name = "2026-10 Bell 80.00 CAD due 2026-10-25")))
        assertEquals("Lease for the cottage", FileMoneyText.who(yours))
        // One elision rule for both: no « d' » before an aspirated h.
        for (m in listOf("Esso", "IKEA", "Hydro-Québec", "Hydro Ottawa", "Hôtel-Dieu", "Yves Rocher", "Uniprix")) assertTrue(m, FilesStrings.elides(m))
        for (m in listOf("Home Depot", "H&M", "Hyundai", "Honda", "Harvey's", "Costco", "Bell")) assertFalse(m, FilesStrings.elides(m))
        val rule = FilingRules.text(app.heyrobot.core.memory.MemoryFiling(kind = "RECEIPT", merchant = "Home Depot", drawer = "Maison"), Lang.FR)
        assertTrue(rule, rule.contains("de Home Depot"))
        val hydro = FilingRules.text(app.heyrobot.core.memory.MemoryFiling(kind = "BILL", merchant = "Hydro-Québec", drawer = "Maison"), Lang.FR)
        assertTrue(hydro, hydro.contains("d’Hydro-Québec") || hydro.contains("d'Hydro-Québec"))
    }

    /** FX2 (files-1.4-review.md R4): pounds said as « livres sterling », and amounts in several currencies added with « plus » aloud. */
    @Test fun poundsAndSeveralCurrenciesAloudInFrench() {
        val frVoice = fr.voice
        assertEquals("84 livres sterling et 12", app.heyrobot.core.i18n.Fmt.money(8412, "GBP", Lang.FR, app.heyrobot.core.i18n.Medium.VOICE))
        assertEquals("1 livre sterling", app.heyrobot.core.i18n.Fmt.money(100, "GBP", Lang.FR, app.heyrobot.core.i18n.Medium.VOICE))
        assertEquals("84,12${nb}GBP", app.heyrobot.core.i18n.Fmt.money(8412, "GBP", Lang.FR))
        val two = FileMoneyText.amounts(linkedMapOf("CAD" to 31240L, "USD" to 4550L))
        assertEquals("312 dollars et 40, plus 45 dollars américains et 50", two.render(frVoice))
        assertEquals("312,40${nb}\$ et 45,50${nb}USD", two.render(fr))
        assertEquals("\$312.40 and 45.50\u00A0USD", two.render(en))
        assertEquals("312 dollars et 40", FileMoneyText.amounts(linkedMapOf("CAD" to 31240L)).render(frVoice))
        // On the wire between processes (PhraseWire), the sum stays a sum.
        assertEquals(two.render(frVoice), PhraseWire.decode(PhraseWire.encode(two))!!.render(frVoice))
    }
}
