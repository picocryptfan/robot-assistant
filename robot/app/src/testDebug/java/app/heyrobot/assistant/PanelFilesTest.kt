// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.graphics.Bitmap
import android.graphics.Color
import app.heyrobot.core.Attachment
import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FiledBy
import app.heyrobot.core.files.Filing
import app.heyrobot.core.files.FilingOutcome
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.Placement
import app.heyrobot.core.files.FileThis
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FilesCommand
import app.heyrobot.core.files.FilingRules
import app.heyrobot.core.ActionStatus
import app.heyrobot.core.AskWhy
import app.heyrobot.core.CardAnswer
import app.heyrobot.core.memory.AddOutcome
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLimits
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.IntakeStep
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.KeystoreFileKeys
import app.heyrobot.sys.SoftFileKeystore
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * files-1.4-plan.md §3.3 (F5): "file this" in the panel. What it files (the attached photo as its own bytes, the attached
 * screen or Android's screenshot as a PNG, else the panel's last photo), with provenance PANEL; nothing when the panel holds
 * nothing, or the photo was too big; refused during a phone lock and while locked before anything is taken; a duplicate said
 * with its place and added only on "file it anyway"; the panel closing discards it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PanelFilesTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val ctx = RuntimeEnvironment.getApplication()
    private val vault by lazy { FilesVault(File(tmp.root, "files"), KeystoreFileKeys(SoftFileKeystore()), "voice", space = { 100L shl 30 }, locked = { false }) }
    private val intake by lazy { FilesIntake(vault) }
    private var gate: Phrase? = null
    private val en = Langs.EN
    private val fileThis = FileThis.Heard(FileThis.Ask.FILE, Lang.EN)
    private val anyway = FileThis.Heard(FileThis.Ask.ANYWAY, Lang.EN)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(5_000) { (it % 13).toByte() }

    @After fun clean() = PanelFiles.forget(ctx) {}

    /** What FilingWork.fileNow would have done with a file that just came in (none: it didn't finish in time). */
    private var filing: (FileId) -> FilingOutcome? = { null }

    private fun change(c: (FileIndex) -> FileIndex?): Boolean {
        var did = false
        vault.update { idx -> c(idx)?.also { did = true } ?: idx }
        return did
    }

    private fun reply(h: FileThis.Heard, w: PanelFiles.What, l: Langs = en) =
        PanelFiles.file(ctx, h, w, l, now = 1_792_000_000_000L, gate = { gate }, intakeOf = { intake }, fileNow = filing, change = ::change,
            index = { vault.load().current })

    private fun file(h: FileThis.Heard, w: PanelFiles.What) = reply(h, w).text.render(en)

    private val later = "It's in your Inbox. Robot will file it shortly."

    /** Files [id] as Robot would a Costco receipt (FilingRun's own change, as a placement it decided). */
    private fun asReceipt(id: FileId, kind: FileKind = FileKind.RECEIPT, drawer: String = Drawers.RECEIPTS): FilingOutcome {
        val p = Placement(kind, drawer, "2026-08-14 Costco 84.12 CAD", listOf("2026-08"), emptyMap(), FiledBy.ROBOT)
        var after: FileEntry? = null
        vault.update { idx -> idx.copy(entries = idx.entries.map { e -> if (e.id != id) e else e.copy(state = FileState.FILED, kind = kind, drawer = drawer, name = p.name,
            tags = p.tags, filing = Filing(FilingStep.FILED, wasName = e.name, wasTags = e.tags), from = e.from.copy(filedBy = FiledBy.ROBOT, filedAt = 1)).also { after = it } }) }
        return FilingOutcome.Filed(after!!, p)
    }

    private fun entries() = (vault.load() as? IndexLoad.Loaded)?.index?.entries.orEmpty()

    private fun shot(): Bitmap = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    @Test fun whatThePanelHolds() {
        val s = shot()
        assertNull(PanelFiles.what(Attachment.NONE, null).photo)
        assertTrue(PanelFiles.what(Attachment.NONE, s).shot === s)
        PanelFiles.picked(jpeg, tooBig = false)
        // The attached photo; the attached screen over the photo; with nothing attached, the panel's photo before the screen.
        assertArrayEquals(jpeg, PanelFiles.what(Attachment.PHOTO, s).photo)
        assertTrue(PanelFiles.what(Attachment.SCREEN, s).shot === s)
        assertArrayEquals(jpeg, PanelFiles.what(Attachment.NONE, s).photo)
        PanelFiles.picked(null, tooBig = true)
        assertTrue(PanelFiles.what(Attachment.PHOTO, s).tooBig)
        PanelFiles.forget(ctx) {}
        assertTrue(PanelFiles.what(Attachment.NONE, s).shot === s)
    }

    @Test fun aPhotoIsFiledAsItsOwnBytes() {
        assertEquals(later, file(fileThis, PanelFiles.What(null, jpeg, false)))
        val e = entries().single()
        assertEquals(FileSource.PANEL, e.from.source)
        assertEquals("image/jpeg", e.from.type)
        assertTrue(e.name, e.name.startsWith("Photo 2026-10-14 ") && e.name.endsWith(".jpg"))
        assertArrayEquals(jpeg, vault.open(e.id).use { it.readAll(100_000) })
    }

    @Test fun aScreenshotIsFiledAsAPng() {
        assertEquals(later, file(fileThis, PanelFiles.What(shot(), null, false)))
        val e = entries().single()
        assertEquals("image/png", e.from.type)
        assertTrue(e.name, e.name.startsWith("Screenshot 2026-10-14 ") && e.name.endsWith(".png"))
    }

    @Test fun nothingToFileATooBigPhotoAndNothingWaiting() {
        assertEquals(FilesStrings.nothingToFile().render(en), file(fileThis, PanelFiles.What(null, null, false)))
        assertEquals(FilesStrings.photoTooBig().render(en), file(fileThis, PanelFiles.What(null, null, true)))
        assertEquals(FilesStrings.nothingWaiting().render(en), file(anyway, PanelFiles.What(null, null, false)))
        assertEquals(emptyList<Any>(), entries())
    }

    @Test fun refusedDuringAPhoneLockOrWhileLockedBeforeAnythingIsTaken() {
        gate = LockStrings.closedNow()
        assertEquals(LockStrings.closedNow().render(en), file(fileThis, PanelFiles.What(null, jpeg, false)))
        gate = FilesStrings.locked()
        assertEquals("Unlock your phone to use your files.", file(fileThis, PanelFiles.What(null, jpeg, false)))
        assertTrue(!vault.exists())
    }

    @Test fun aDuplicateIsAddedOnlyWhenYouSayAnyway() {
        file(fileThis, PanelFiles.What(null, jpeg, false))
        val name = entries().single().name
        // FX2 (files-1.4-review.md finding 12), deliberately: History keeps the shown text, which no longer holds the file's
        // name (the sender's words); what Robot says names the file as Robot says one, never its name read raw.
        val dup = reply(fileThis, PanelFiles.What(null, jpeg, false))
        assertEquals("You already have this file in your Inbox. To add it again, say “file it anyway”.", dup.text.render(en))
        assertTrue(dup.spoken.render(en), dup.spoken.render(en).startsWith("You already have this file in your Inbox: "))
        assertFalse(dup.spoken.render(en).contains(name))
        assertEquals(1, entries().size)
        assertEquals(later, file(anyway, PanelFiles.What(null, null, false)))
        assertEquals(2, entries().size)
        // Closing the panel discards a duplicate still waiting.
        file(fileThis, PanelFiles.What(null, jpeg, false))
        var dropped = 0
        PanelFiles.forget(ctx) { intake.drop(it); dropped++ }
        Thread.sleep(200)
        assertEquals(1, dropped)
        assertEquals(FilesStrings.nothingWaiting().render(en), file(anyway, PanelFiles.What(null, null, false)))
        assertEquals(0, vault.recover()!!.adopted)
        assertEquals(2, entries().size)
    }

    @Test fun filedWithMoveAndUndo() {
        filing = { asReceipt(it) }
        val r = reply(fileThis, PanelFiles.What(null, jpeg, false))
        // The bubble (kept in History) names the drawer only; the name is on the card and in what Robot says.
        assertEquals("Filed in Receipts.", r.text.render(en))
        // FX2 (F13's note), deliberately: never "2026-08-14 Costco 84.12 CAD" read raw; with no merchant or day read, the drawer
        // alone (FileMoneyTest pins the spoken names).
        assertEquals("Filed in Receipts.", r.spoken.render(en))
        val card = r.card!!
        assertEquals("2026-08-14 Costco 84.12 CAD", card.summary)
        assertEquals("Filed in Receipts.", card.detail)
        assertNull(card.tool)
        assertTrue(card.files)
        assertTrue(PanelFiles.isUndo(card.undo))
        assertTrue(PanelFiles.isMove(card.handOff))
        assertEquals("Move", PanelFiles.label(card.handOff)!!.render(en))
        assertEquals("Déplacer", PanelFiles.label(card.handOff)!!.render(Langs.of(Lang.FR)))
        // Undo: back in Inbox with its old name, and Robot won't file it again by itself.
        assertEquals("Back in your Inbox.", PanelFiles.undo(ctx, card.undo!!, en, ::change))
        val e = entries().single()
        assertEquals(FileState.INBOX, e.state)
        assertTrue(e.name, e.name.startsWith("Photo "))
        assertEquals(FilingStep.KEPT, e.filing!!.step)
        // Only once.
        assertEquals("Robot couldn't undo that: the file changed since.", PanelFiles.undo(ctx, card.undo!!, en, ::change))
    }

    @Test fun moveAsksForADrawerAndTakesYourNextWords() {
        filing = { asReceipt(it) }
        val card = reply(fileThis, PanelFiles.What(null, jpeg, false)).card!!
        assertEquals("Where should it go? Say or type a drawer: Receipts, Bills, Documents, Medical, Taxes, Trips and events, Photos and media or Conversations.",
            PanelFiles.startMove(card.handOff!!)!!.render(en))
        val heard = (PanelFiles.route("put it in Taxes") as app.heyrobot.core.files.FilesCommand.File).heard
        assertEquals(FileThis.Ask.MOVE, heard.ask)
        val r = reply(heard, PanelFiles.What(null, null, false))
        assertEquals("Moved to Taxes.", r.text.render(en))
        assertEquals(Drawers.TAXES, entries().single().drawer)
        assertEquals(FiledBy.YOU, entries().single().from.filedBy)
        // Its Undo puts it back where it was.
        assertEquals("Put back where it was.", PanelFiles.undo(ctx, r.card!!.undo!!, en, ::change))
        assertEquals(Drawers.RECEIPTS, entries().single().drawer)
        // Words that name no drawer; and Move's question is asked once.
        PanelFiles.startMove(r.card!!.handOff!!)
        assertTrue(reply((PanelFiles.route("the weather") as app.heyrobot.core.files.FilesCommand.File).heard, PanelFiles.What(null, null, false)).text.render(en).startsWith("Robot didn't catch a drawer."))
        assertNull(PanelFiles.route("Taxes"))
        // French names, and your own drawers in use.
        assertEquals(Drawers.TAXES, PanelFiles.drawerIn("mets-le dans Impôts", null))
        assertEquals(Drawers.TRIPS, PanelFiles.drawerIn("voyages et événements", null))
        assertNull(PanelFiles.drawerIn("Car", null))
    }

    @Test fun inInboxWithRobotsQuestionAndNeverAMedicalNameAloud() {
        filing = { id -> FilingOutcome.Asked(entries().first { it.id == id }.copy(filing = Filing(FilingStep.ASK, FilingWhy.KIND, choices = listOf(FileKind.RECEIPT, FileKind.BILL))), FilingWhy.KIND) }
        assertEquals("It's in your Inbox. Is this a receipt or a bill?", file(fileThis, PanelFiles.What(null, jpeg, false)))
        filing = { id -> FilingOutcome.Waiting(FilingWhy.NO_VISION) }
        assertEquals("Il est dans ta boîte de réception. Pour lire des images, il faut Rapide ou Plus futé.",
            reply(fileThis, PanelFiles.What(null, jpeg + byteArrayOf(1), false), Langs.of(Lang.FR)).text.render(Langs.of(Lang.FR)))
        filing = { asReceipt(it, FileKind.MEDICAL, Drawers.MEDICAL) }
        val r = reply(fileThis, PanelFiles.What(null, jpeg + byteArrayOf(2), false))
        assertEquals("Filed in Medical.", r.spoken.render(en))
        // A dangerous file is never filed.
        filing = { error("never") }
        val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(100)
        assertEquals("It's in your Inbox. This could install or run something. Robot won't open it.", file(fileThis, PanelFiles.What(null, zip, false)))
    }

    // F8: Robot Files' other words.

    /** Memory stood in for, as sys/FilingMemory keeps rules (its work is MemoryEdits'); [on]: Settings › Memory's switch. */
    private var memory = MemoryFile()
    private var memoryOn = true
    private val save: (MemoryFiling, Lang) -> FilingMemory.Saved = { f, l ->
        if (!memoryOn) FilingMemory.Saved.Off else {
            val r = MemoryEdits.addRule(memory, FilingRules.item(f, l, MemorySource.SAID, 1_792_000_000_000L))
            memory = r.file
            when (r.outcome) {
                AddOutcome.SAVED, AddOutcome.SAVED_PIN_FULL -> FilingMemory.Saved.Kept(r.item!!, r.replaced)
                AddOutcome.ALREADY_KEPT -> FilingMemory.Saved.Already(r.item!!)
                AddOutcome.FULL -> FilingMemory.Saved.Full
                AddOutcome.EMPTY -> FilingMemory.Saved.NotOpen
            }
        }
    }
    private val unsave: (FilingMemory.Saved.Kept) -> Boolean = { memory = MemoryEdits.undoRule(memory, it.item.id, it.replaced); true }

    private fun say(q: String, l: Langs = en): PanelFiles.Reply {
        val c = PanelFiles.route(q) ?: error("not routed: $q")
        return PanelFiles.file(ctx, c, PanelFiles.What(null, null, false), l, now = 1_792_000_000_000L, gate = { gate }, intakeOf = { intake }, fileNow = filing,
            change = ::change, index = { vault.load().current }, save = save)
    }

    private var n = 0
    /** A filed receipt or bill with its merchant, as Robot would have filed it. */
    private fun filed(merchant: String, drawer: String, name: String, kind: FileKind = FileKind.RECEIPT): FileId {
        val step = intake.add(jpeg + byteArrayOf((++n).toByte(), 7)) { t -> FileIntake("$name.jpg", FileSource.SHARE, type = t.mime) } as IntakeStep.Added
        vault.update { idx -> idx.copy(entries = idx.entries.map { e -> if (e.id != step.entry.id) e else e.copy(state = FileState.FILED, kind = kind, drawer = drawer,
            name = name, fields = mapOf("merchant" to FileField(merchant, FieldBy.READ)), filing = Filing(FilingStep.FILED), from = e.from.copy(filedBy = FiledBy.ROBOT, filedAt = 1)) }) }
        PanelFiles.seen(vault.load().current)
        return step.entry.id
    }

    // ------------------------------------------------------------------ F10: money and dates

    private val today = java.time.Instant.ofEpochMilli(1_792_000_000_000L).atZone(java.time.ZoneId.systemDefault()).toLocalDate()

    /** A filed file of [kind] with [fields], as Robot would have filed it. */
    private fun filedWith(kind: FileKind, drawer: String, name: String, fields: Map<String, String>): FileId {
        val step = intake.add(jpeg + byteArrayOf((++n).toByte(), 9)) { t -> FileIntake("$name.jpg", FileSource.SHARE, type = t.mime) } as IntakeStep.Added
        vault.update { idx -> idx.copy(entries = idx.entries.map { e -> if (e.id != step.entry.id) e else e.copy(state = FileState.FILED, kind = kind, drawer = drawer,
            name = name, fields = fields.mapValues { FileField(it.value, FieldBy.READ) }, filing = Filing(FilingStep.FILED),
            from = e.from.copy(filedBy = FiledBy.ROBOT, filedAt = 1)) }) }
        return step.entry.id
    }

    private fun money(q: String, l: Langs = en, settings: app.heyrobot.core.files.FilesSettings = app.heyrobot.core.files.FilesSettings()): PanelFiles.Reply {
        val c = app.heyrobot.core.files.FilesRouter.route(q, today = today) ?: error("not routed: $q")
        return PanelFiles.file(ctx, c, PanelFiles.What(null, null, false), l, now = 1_792_000_000_000L, gate = { gate }, intakeOf = { intake }, fileNow = filing,
            change = ::change, index = { vault.load().current }, save = save, settings = { settings })
    }

    /** "How much did I spend on gas": code's sum in whole cents, per currency; a total to check left out and counted. */
    @Test fun spendingIsCodesSum() {
        filedWith(FileKind.RECEIPT, Drawers.RECEIPTS, "Esso", mapOf("merchant" to "Esso", "date" to today.minusDays(3).toString(), "total" to "61.40", "currency" to "CAD"))
        filedWith(FileKind.RECEIPT, Drawers.RECEIPTS, "Shell", mapOf("merchant" to "Shell", "date" to today.minusDays(2).toString(), "total" to "0.29", "currency" to "CAD"))
        filedWith(FileKind.RECEIPT, Drawers.RECEIPTS, "Ultramar", mapOf("merchant" to "Ultramar", "date" to today.minusDays(1).toString(), "total" to "99.00",
            "currency" to "CAD", "check" to "total"))
        filedWith(FileKind.RECEIPT, Drawers.RECEIPTS, "Costco", mapOf("merchant" to "Costco", "date" to today.minusDays(1).toString(), "total" to "84.12", "currency" to "CAD"))
        val r = money("how much did I spend on gas")
        assertEquals("You spent \$61.69 on gas, from 2 receipts. 1 total needs checking, so it's left out.", r.text.render(en))
        assertEquals("Tu as dépensé 61,69\u00A0\$ en essence, d’après 2 reçus. 1 total doit être vérifié, alors il est laissé de côté.",
            money("combien j'ai dépensé en essence", Langs.of(Lang.FR)).text.render(Langs.of(Lang.FR)))
        assertEquals("You spent \$84.12 at Costco, from 1 receipt.", money("how much did I spend at Costco").text.render(en))
        assertNull(r.card)
    }

    /**
     * "What bills are due soon": the reply's text names no merchant (History keeps it), what Robot says does; a card per date
     * offers Add to my brief (kept in the file's entry, never the calendar) and Add to calendar (add_event's card, in a turn of
     * its own: PanelController); none with Reminders from files Off.
     */
    @Test fun datesComingUpWithReminderCards() {
        val due = today.plusDays(3)
        val hydro = filedWith(FileKind.BILL, Drawers.BILLS, "Hydro bill", mapOf("merchant" to "Hydro-Québec", "date" to today.minusDays(20).toString(), "total" to "142.00",
            "currency" to "CAD", "due" to due.toString()))
        val r = money("what bills are due soon")
        val dueText = app.heyrobot.core.i18n.Fmt.date(due, Lang.EN)
        assertEquals("1 bill is due in the next 14 days. The soonest is $dueText.", r.text.render(en))
        assertEquals("1 bill is due in the next 14 days. Hydro-Québec. Due $dueText: \$142.00.", r.spoken.render(en))
        val card = r.card!!
        assertTrue(PanelFiles.isRemind(card.id))
        assertEquals(ActionStatus.PENDING, card.status)
        assertEquals("Hydro bill", card.summary)
        assertEquals("Due $dueText: \$142.00. Remind you in your morning brief, or add it to your calendar?", card.detail)
        assertEquals("Add to my brief", card.confirm)
        assertEquals("Not now", card.cancel)
        assertTrue(PanelFiles.isCalendar(card.handOff))
        assertEquals("Add to calendar", PanelFiles.label(card.handOff)!!.render(en))
        val before = entries().first { it.id == hydro }
        // Locked at the tap: the card waits.
        gate = FilesStrings.locked()
        assertEquals(ActionStatus.PENDING to "Unlock your phone to use your files.", PanelFiles.remindTapped(ctx, card.id, true, en, gate = { gate }, change = ::change,
            index = { vault.load().current }))
        gate = null
        val now = today.atTime(10, 0).atZone(java.time.ZoneId.systemDefault())
        val on = app.heyrobot.core.files.FileDates.remindOn(app.heyrobot.core.files.FileDates.of(before).single(), app.heyrobot.core.files.FileDates.nextBrief(now))!!
        assertEquals(ActionStatus.DONE to "In your brief on ${app.heyrobot.core.i18n.Fmt.day(on, Lang.EN)}.",
            PanelFiles.remindTapped(ctx, card.id, true, en, now = now, gate = { gate }, change = ::change, index = { vault.load().current }))
        val after = entries().first { it.id == hydro }
        assertEquals("due:$on", after.fields[app.heyrobot.core.files.FileRemind.FIELD]!!.value)
        assertEquals(FieldBy.YOU, after.fields[app.heyrobot.core.files.FileRemind.FIELD]!!.by)
        // Nothing else of the file changed.
        assertEquals(before.copy(fields = before.fields), after.copy(fields = after.fields - app.heyrobot.core.files.FileRemind.FIELD))
        assertEquals(ActionStatus.DONE to "It's already in your brief.", PanelFiles.remindTapped(ctx, card.id, true, en, now = now, gate = { gate }, change = ::change,
            index = { vault.load().current }))
        assertEquals(ActionStatus.DECLINED to "Not now", PanelFiles.remindTapped(ctx, card.id, false, en, gate = { error("never") }, change = { error("never") }))
        // Add to calendar: the file's date for add_event's card; the panel writes nothing here.
        val d = PanelFiles.calendar(ctx, card.handOff!!, today) { vault.load().current }!!
        assertEquals(due, d.date)
        assertEquals("Pay Hydro-Québec bill", app.heyrobot.core.files.FileMoneyText.eventTitle(d).render(en))
        // Reminders from files Off: the answer, no card.
        val off = money("what bills are due soon", settings = app.heyrobot.core.files.FilesSettings(reminders = app.heyrobot.core.files.FileReminders.OFF))
        assertNull(off.card)
        assertEquals(r.text.render(en), off.text.render(en))
        // In French.
        val fr = Langs.of(Lang.FR)
        assertEquals("1 facture est due dans les 14 prochains jours. La date la plus proche\u00A0: ${app.heyrobot.core.i18n.Fmt.date(due, Lang.FR)}.",
            money("quelles factures sont dues bientôt", fr).text.render(fr))
    }

    /** "What subscriptions do I have": the same merchant billed 3 months in a row, by code; listed, never acted on. */
    @Test fun subscriptionsListed() {
        for (m in 0L..2L) filedWith(FileKind.BILL, Drawers.BILLS, "Vidéotron $m", mapOf("merchant" to "Vidéotron", "date" to today.minusMonths(m).withDayOfMonth(5).toString(),
            "total" to "35.00", "currency" to "CAD"))
        val r = money("what subscriptions do I have")
        assertEquals("1 subscription in your bills: \$35.00 a month.", r.text.render(en))
        assertEquals("1 subscription in your bills: \$35.00 a month. Your subscriptions: Vidéotron: \$35.00 a month.", r.spoken.render(en))
        assertNull(r.card)
    }

    /** "Esso receipts go under Car": kept at once, said once with its words, a card with Undo; the drawer your words named, made. */
    @Test fun aRuleFromYourWords() {
        filed("Costco Wholesale", Drawers.RECEIPTS, "2026-10-18 Costco 84.12 CAD")
        val r = say("Esso receipts go under Car")
        assertEquals("Got it: Robot will file them that way from now on.", r.text.render(en))
        assertEquals("Got it: from now on, Esso receipts go in Car.", r.spoken.render(en))
        assertEquals("Esso receipts go in Car.", r.card!!.summary)
        assertEquals("A filing rule, in Settings › Memory", r.card!!.detail)
        assertEquals(MemoryFiling(kind = "RECEIPT", merchant = "Esso", drawer = "Car"), memory.items.single().filing)
        assertEquals(MemorySource.SAID, memory.items.single().source)
        assertEquals(listOf("Car"), FileViews.yours(vault.load().current!!))
        // Said again: already so. Undo: the rule and the drawer it made go.
        assertEquals("Robot already files them that way.", say("put Esso receipts in Car").text.render(en))
        assertEquals("Rule dropped.", PanelFiles.undo(ctx, r.card!!.undo!!, en, ::change, unsave))
        assertTrue(memory.items.isEmpty())
        assertTrue(FileViews.yours(vault.load().current!!).isEmpty())
        // In French, to a starter drawer by its French name.
        val fr = say("mets les reçus d'Esso dans Impôts", Langs.of(Lang.FR))
        assertEquals("Compris\u00A0: à partir de maintenant, les reçus d’Esso vont dans Impôts.", fr.spoken.render(Langs.of(Lang.FR)))
        assertEquals("Les reçus d’Esso vont dans Impôts.", memory.items.single().text)
        assertEquals("fr", memory.items.single().language)
        assertEquals(Drawers.TAXES, memory.items.single().filing!!.drawer)
    }

    @Test fun aRuleWithMemoryOffFullOrLocked() {
        memoryOn = false
        assertEquals("Memory is off, so Robot won't keep filing rules.", say("Esso receipts go under Car").text.render(en))
        assertTrue(FileViews.yours(vault.load().current!!).isEmpty())
        memoryOn = true
        memory = MemoryFile((0 until MemoryLimits.MAX_ITEMS).map { MemoryItem("m_" + it.toString(36).padStart(9, '0') + "aaaaaa", "fact $it", createdAt = 1) })
        assertEquals("Memory is full, so Robot can't keep this rule: delete some in Settings › Memory.", say("Esso receipts go under Car").text.render(en))
        gate = FilesStrings.locked()
        assertEquals("Unlock your phone to use your files.", say("Esso receipts go under Car").text.render(en))
        assertEquals("Unlock your phone to use your files.", say("find my Esso receipt").text.render(en))
        assertEquals("Unlock your phone to use your files.", say("delete the Esso receipt").text.render(en))
        assertEquals(MemoryLimits.MAX_ITEMS, memory.items.size)
    }

    /** A move by voice: one clear file to a drawer that exists, with Undo; several, and nothing moves. */
    @Test fun aMoveByYourWords() {
        val esso = filed("Esso", Drawers.RECEIPTS, "2026-10-02 Esso 61.40 CAD")
        filed("Costco Wholesale", Drawers.RECEIPTS, "2026-10-18 Costco 84.12 CAD")
        filed("Costco Wholesale", Drawers.RECEIPTS, "2026-09-03 Costco 12.00 CAD")
        val r = say("move the Esso receipt to Taxes")
        assertEquals("Moved to Taxes.", r.text.render(en))
        assertEquals(Drawers.TAXES, entries().first { it.id == esso }.drawer)
        // "Move it" means that one now.
        assertEquals("Moved to Bills.", say("move it to Bills").text.render(en))
        assertEquals(Drawers.BILLS, entries().first { it.id == esso }.drawer)
        // Two Costco receipts: nothing moves, and a card opens Files.
        val two = say("move the Costco receipt to Taxes")
        assertEquals("2 files match. Show them in Files to choose one.", two.text.render(en))
        assertTrue(PanelFiles.isOpen(two.card!!.handOff))
        assertEquals("Show in Files", PanelFiles.label(two.card!!.handOff)!!.render(en))
        assertEquals(2, entries().count { it.drawer == Drawers.RECEIPTS })
        // A drawer that doesn't exist isn't Robot Files' to move to.
        assertNull(PanelFiles.route("move the Esso receipt to the glovebox"))
    }

    @Test fun findAndInbox() {
        filed("Esso", Drawers.RECEIPTS, "2026-10-02 Esso 61.40 CAD")
        filed("Hydro-Québec", Drawers.BILLS, "2026-09 Hydro-Québec 142.00 CAD", FileKind.BILL)
        val r = say("find my Esso receipt")
        assertEquals("Found 1 file. It's in Receipts.", r.text.render(en))
        assertEquals("2026-10-02 Esso 61.40 CAD", r.card!!.summary)
        assertTrue(PanelFiles.isOpen(r.card!!.handOff))
        assertEquals("1 fichier trouvé. Il est dans Factures.", say("montre mes factures d'Hydro", Langs.of(Lang.FR)).text.render(Langs.of(Lang.FR)))
        assertEquals("Robot found no file for that.", say("where's my lease").text.render(en))
        assertEquals("Your Inbox is empty.", say("what's in my Inbox").text.render(en))
        intake.add(jpeg + byteArrayOf(99)) { t -> FileIntake("new.jpg", FileSource.SHARE, type = t.mime) }
        assertEquals("1 file is in your Inbox.", say("what's in my Inbox").text.render(en))
    }

    /** "Delete the Esso receipt": a card naming it, Delete and Keep, answered only by a tap; a spoken yes is told to tap. */
    @Test fun deleteIsACardOnlyATapAnswers() {
        val esso = filed("Esso", Drawers.RECEIPTS, "2026-10-02 Esso 61.40 CAD")
        val r = say("delete the Esso receipt")
        assertEquals("To delete it, tap Delete on the card. A spoken yes won't delete it.", r.text.render(en))
        val card = r.card!!
        assertTrue(PanelFiles.isDelete(card.id))
        assertEquals(ActionStatus.PENDING, card.status)
        assertEquals(AskWhy.DELETE, card.tapFor)
        assertEquals("Delete", card.confirm)
        assertEquals("Keep", card.cancel)
        assertEquals("Delete “2026-10-02 Esso 61.40 CAD”? It stays in backups you made before.", card.detail)
        // A spoken or typed yes never confirms it.
        for (spoken in listOf(true, false)) assertEquals(CardAnswer.Does.TAP, CardAnswer.reply("yes", card.waiting, voiceCards = true, voiceYesPersonal = true, spoken = spoken)!!.does)
        assertEquals(CardAnswer.Does.DECLINE, CardAnswer.reply("no", card.waiting, voiceCards = true, voiceYesPersonal = true, spoken = true)!!.does)
        // Keep.
        assertEquals(PanelFiles.Tapped(ActionStatus.DECLINED, "Kept."), PanelFiles.deleteTapped(ctx, card.id, yes = false, langs = en, gate = { gate }, delete = { error("never") }))
        assertTrue(entries().any { it.id == esso })
        // Locked at the tap: not now, the card waits.
        val again = say("delete the Esso receipt").card!!
        gate = FilesStrings.locked()
        assertEquals(PanelFiles.Tapped(ActionStatus.PENDING, "Unlock your phone to use your files."), PanelFiles.deleteTapped(ctx, again.id, true, en, gate = { gate }, delete = { error("never") }))
        gate = null
        // FX2 (files-1.4-review.md K1), deliberately: Delete now waits 10 seconds for its Undo (core PendingDeletes) before the
        // key is destroyed; meanwhile the file is out of the panel's answers. In time, Undo brings it back.
        val ended = java.util.concurrent.LinkedBlockingQueue<Boolean>()
        val t = PanelFiles.deleteTapped(ctx, again.id, true, en, gate = { gate }, delete = { id -> (vault.delete(listOf(id))?.removed ?: 0) > 0 }, ended = { ended += it })
        assertEquals(ActionStatus.DONE, t.status)
        assertEquals("Deleted.", t.line)
        assertTrue(PanelFiles.isUndo(t.undo))
        assertTrue(entries().any { it.id == esso })
        assertEquals("Robot found no file for that.", say("find my Esso receipt").text.render(en))
        assertEquals("The file is back.", PanelFiles.undo(ctx, t.undo!!, en))
        assertTrue(entries().any { it.id == esso })
        assertEquals(FilesStrings.notUndone().render(en), PanelFiles.undo(ctx, t.undo, en))
        assertTrue(ended.isEmpty())
        // Deleted again: the panel closing makes it go now, and its Undo is then too late.
        val third = say("delete the Esso receipt").card!!
        val t2 = PanelFiles.deleteTapped(ctx, third.id, true, en, gate = { gate }, delete = { id -> (vault.delete(listOf(id))?.removed ?: 0) > 0 }, ended = { ended += it })
        PanelFiles.forget(ctx, drop = {})
        assertEquals(true, ended.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(entries().none { it.id == esso })
        assertEquals(FilesStrings.notUndone().render(en), PanelFiles.undo(ctx, t2.undo!!, en))
        // Once only.
        assertEquals(PanelFiles.Tapped(ActionStatus.DECLINED, "Kept."), PanelFiles.deleteTapped(ctx, third.id, true, en, gate = { gate }, delete = { error("never") }))
    }

    @Test fun theWordsInBothLanguages() {
        assertEquals(app.heyrobot.core.files.FilesCommand.File(FileThis.Heard(FileThis.Ask.FILE, Lang.FR)), PanelFiles.route("Classe ça"))
        assertEquals(app.heyrobot.core.files.FilesCommand.File(FileThis.Heard(FileThis.Ask.FILE, Lang.EN)), PanelFiles.route("file this"))
        assertNull(PanelFiles.route("save this note"))
        assertEquals("Tu as déjà ce fichier dans ta boîte de réception\u00A0: une photo. Pour l’ajouter de nouveau, dis «\u00A0classe-le quand même\u00A0».",
            FilesStrings.duplicateSay("where" to app.heyrobot.core.i18n.Arg.Sub(FilesStrings.yourInbox()), "name" to app.heyrobot.core.i18n.Arg.Sub(FilesStrings.kind(FileKind.PHOTO)))
                .render(Langs.of(Lang.FR)))
    }
}
