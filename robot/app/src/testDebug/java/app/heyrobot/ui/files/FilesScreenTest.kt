// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.Intent
import android.net.Uri
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.files.Drawers
import app.heyrobot.core.files.FieldBy
import app.heyrobot.core.files.FileDeleteCode
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileException
import app.heyrobot.core.files.FileField
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileMoveCode
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FileProblem
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.FileViews
import app.heyrobot.core.files.FiledBy
import app.heyrobot.core.files.FilesSettings
import app.heyrobot.core.files.Filing
import app.heyrobot.core.files.FilingStep
import app.heyrobot.core.files.FilingWhy
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.NameWords
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.files.FileRuleCode
import app.heyrobot.core.files.Filer
import app.heyrobot.core.files.FilingRule
import app.heyrobot.core.files.FilingRules
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.memory.AddOutcome
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLimits
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.ParsedPixels
import app.heyrobot.sys.FilesOut
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.FilingMemory
import app.heyrobot.sys.KeystoreFileKeys
import app.heyrobot.sys.OutCopy
import app.heyrobot.sys.SoftFileKeystore
import app.heyrobot.ui.Page
import app.heyrobot.ui.settings.SettingsRoutes
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The Files tab (files-1.4-plan.md §6.2 to §6.5, Q1, Q2; F7) on a real vault with robot_files_v1 in software and :files
 * stood in for: Inbox with Robot's questions as chips and Ask me first's "File them", Recently filed's Undo, Move and its
 * Undo, a drawer with its months and totals, your drawers, a file's page (its pages as pixels, rename, a field, Check the
 * total, tags, people, where it came from), Delete with its 10-second Undo and the key destroyed after, Share and Open
 * with another app (the copy each takes, the location question), search, the lock gate (nothing shown, the reader ended,
 * copies taken back, a waiting delete done) and the background; goldens light, dark and at 200% text. Demo data only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FilesScreenTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })
    private val log = ActionLog()
    private val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()

    private companion object {
        /** 22 Oct 2026, 12:00 UTC. */
        const val NOW = 1_792_670_400_000L
        const val HOUR = 3_600_000L
    }

    /** The screen's port over a real vault; :files and the hand-off to another app stood in for, every call recorded. */
    private inner class TestPort : FilesPort {
        val keystore = SoftFileKeystore()
        var locked = false
        var settings = FilesSettings()
        val dir = File(tmp.root, "files")
        val vault = FilesVault(dir, KeystoreFileKeys(keystore), "main", space = { 100L shl 30 }, locked = { locked })
        val events = ArrayList<String>()
        val launched = ArrayList<Intent>()

        override fun locked() = locked
        /** FX1: travel mode as the phone's port shows it (the index without its hidden drawers); null: the whole index. */
        var travel: FilesSettings? = null
        private fun shown(l: IndexLoad): IndexLoad = travel?.let { t -> (l as? IndexLoad.Loaded)?.let { IndexLoad.Loaded(app.heyrobot.core.files.TravelMode.shown(it.index, t)) } } ?: l
        override fun load(): IndexLoad = shown(vault.load())
        override fun update(change: (FileIndex) -> FileIndex) = shown(vault.update(change))
        override fun delete(ids: Collection<FileId>): Boolean = ((vault.delete(ids)?.removed ?: 0) > 0).also { events += "delete ${ids.size} $it" }
        override fun settings() = settings
        override fun words() = NameWords("due")
        override fun people() = listOf("p-sam" to "Sam Tremblay", "p-lea" to "Léa Roy")
        override fun thumb(e: FileEntry): ParsedPixels? = if (e.thumb) paper(160, 200, receipt = e.kind == FileKind.RECEIPT) else null
        override fun page(e: FileEntry, page: Int) = ShownPage(paper(600, 800, receipt = true), 2).also { events += "page $page" }
        override fun type(e: FileEntry): FileType = FileOut.typeOf(e) ?: FileType.UNKNOWN
        override fun copy(e: FileEntry, copy: OutgoingCopy, name: String, mime: String, dangerous: Boolean): OutCopy {
            events += "copy $copy $name $mime"
            return OutCopy.Ready(e.id, Uri.parse("content://${FilesOut.AUTHORITY}/out/x/" + Uri.encode(name)), mime, copy == OutgoingCopy.STRIPPED, dangerous)
        }
        override fun handOut(c: OutCopy.Ready, open: Boolean, title: String): Intent {
            events += if (open) "open" else "share"
            return if (open) FilesOut.openIntent(ctx, c, title) else FilesOut.shareIntent(ctx, c, title)
        }
        override fun takeBack(id: FileId?) { events += if (id == null) "takeBack all" else "takeBack one" }
        override fun stopped() { events += "stopped" }
        override fun resumed() { events += "resumed" }
        override fun endReader() { events += "endReader" }
        override fun changed() { events += "changed" }
        override fun logMove(code: FileMoveCode, n: Int) { events += "move $code $n" }
        override fun logDelete(code: FileDeleteCode, n: Int) { events += "deleteLog $code $n" }

        /** F8: memory stood in for (sys/FilingMemory's own work is MemoryEdits'): off unless a test turns it on. */
        var memoryOn = false
        var memory = MemoryFile()
        override fun rules(): List<FilingRule> = if (!memoryOn) emptyList() else FilingRules.of(memory.items)
        override fun renameRules(old: String, new: String) { memory = FilingRules.renamed(memory, old, new) }
        override fun saveRule(rule: MemoryFiling): FilingMemory.Saved {
            if (!memoryOn) return FilingMemory.Saved.Off
            val r = MemoryEdits.addRule(memory, FilingRules.item(rule, Lang.EN, MemorySource.SETTINGS, NOW))
            memory = r.file
            return when (r.outcome) {
                AddOutcome.SAVED, AddOutcome.SAVED_PIN_FULL -> FilingMemory.Saved.Kept(r.item!!, r.replaced)
                AddOutcome.ALREADY_KEPT -> FilingMemory.Saved.Already(r.item!!)
                AddOutcome.FULL -> FilingMemory.Saved.Full
                AddOutcome.EMPTY -> FilingMemory.Saved.NotOpen
            }
        }
        override fun undoRule(saved: FilingMemory.Saved.Kept): Boolean { memory = MemoryEdits.undoRule(memory, saved.item.id, saved.replaced); return true }
        override fun logRule(code: FileRuleCode) { events += "rule $code" }

        /** Adds a file (its bytes sealed as the intake would) and gives its entry what [shape] says. */
        fun add(name: String, type: String = "application/pdf", bytes: Int = 4_000, shape: (FileEntry) -> FileEntry = { it }): FileId {
            val s = FileStore(dir, KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 })
            val e = s.add(ByteArrayInputStream(ByteArray(bytes) { (it * 7 + name.length).toByte() }), FileIntake(name, FileSource.SHARE, type = type))
            vault.update { idx -> idx.copy(entries = idx.entries.map { if (it.id == e.id) shape(it) else it }) }
            return e.id
        }

        fun entry(id: FileId): FileEntry? = (vault.load() as IndexLoad.Loaded).index.entry(id)

        /** F10: the calendar app's editor stood in for (an intent naming the title and the day; nothing is written). */
        override fun calendar(title: String, date: java.time.LocalDate): Intent = Intent("test.CALENDAR").putExtra("title", title).putExtra("day", date.toString())
            .also { events += "calendar" }
    }

    private val port by lazy { TestPort() }

    /** A pale page with dark lines, as a scanned receipt would look (demo pixels, no file of anyone's). */
    private fun paper(w: Int, h: Int, receipt: Boolean): ParsedPixels {
        val px = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val line = receipt && y % 40 in 18..22 && x in w / 8..(w * 7 / 8 - (y * 13 % (w / 3)))
            val v = if (line) 60 else 246
            val i = (y * w + x) * 4
            px[i] = v.toByte(); px[i + 1] = v.toByte(); px[i + 2] = (v - 6).toByte(); px[i + 3] = 255.toByte()
        }
        return ParsedPixels(w, h, px)
    }

    private fun filed(drawer: String, kind: FileKind, by: FiledBy, ago: Long, name: String, fields: Map<String, String> = emptyMap(), tags: List<String> = emptyList()): (FileEntry) -> FileEntry = { e ->
        e.copy(name = name, kind = kind, state = FileState.FILED, drawer = drawer, tags = tags, thumb = true,
            fields = fields.mapValues { FileField(it.value, FieldBy.READ) }, filing = Filing(FilingStep.FILED, wasName = e.name, wasTags = e.tags),
            from = e.from.copy(at = NOW - ago - HOUR, filedBy = by, filedAt = NOW - ago))
    }

    private fun asking(why: FilingWhy, kind: FileKind = FileKind.UNKNOWN, choices: List<FileKind> = emptyList(), fields: Map<String, String> = emptyMap(), ago: Long = HOUR): (FileEntry) -> FileEntry = { e ->
        e.copy(kind = kind, filing = Filing(if (why == FilingWhy.DANGEROUS || why == FilingWhy.OFF) FilingStep.HELD else FilingStep.ASK, why, choices = choices),
            fields = fields.mapValues { FileField(it.value, FieldBy.READ) }, from = e.from.copy(at = NOW - ago))
    }

    private val ids = HashMap<String, FileId>()

    /** The demo cabinet: Robot's questions, Ask me first's batch, a file it holds, one being read, files filed, your drawers. */
    private fun cabinet() {
        ids["kind"] = port.add("IMG_2041.jpg", "image/jpeg") { asking(FilingWhy.KIND, choices = listOf(FileKind.RECEIPT, FileKind.BILL), ago = 20 * 60_000L,
            fields = mapOf("merchant" to "Pharmaprix", "date" to "2026-10-21", "total" to "12.34", "currency" to "CAD"))(it).copy(thumb = true) }
        ids["total"] = port.add("Scan 2026-10-21 09.12") { asking(FilingWhy.TOTAL, FileKind.RECEIPT,
            fields = mapOf("merchant" to "Metro", "date" to "2026-10-20", "currency" to "CAD", "check" to "total"))(it) }
        ids["them1"] = port.add("receipt.pdf") { asking(FilingWhy.FILE_THEM, FileKind.RECEIPT, fields = mapOf("merchant" to "Jean Coutu", "date" to "2026-10-19",
            "total" to "23.45", "currency" to "CAD"))(it) }
        ids["them2"] = port.add("statement.pdf") { asking(FilingWhy.FILE_THEM, FileKind.BILL, fields = mapOf("merchant" to "Videotron", "date" to "2026-10-01",
            "total" to "95.00", "currency" to "CAD", "due" to "2026-10-28"))(it) }
        ids["apk"] = port.add("setup.apk", "application/zip") { asking(FilingWhy.DANGEROUS, FileKind.DANGEROUS)(it) }
        ids["reading"] = port.add("Shared text 2026-10-22 08.30", "text/plain") { it.copy(filing = Filing(FilingStep.NEW), from = it.from.copy(at = NOW - 5 * 60_000L)) }
        ids["costco"] = port.add("costco.pdf") { filed(Drawers.RECEIPTS, FileKind.RECEIPT, FiledBy.ROBOT, HOUR, "2026-10-18 Costco 84.12 CAD",
            mapOf("merchant" to "Costco", "date" to "2026-10-18", "total" to "84.12", "currency" to "CAD", "tax_gst" to "3.75", "tax_qst" to "7.48"),
            listOf("2026-10", "Costco"))(it) }
        ids["iga"] = port.add("iga.pdf") { filed(Drawers.RECEIPTS, FileKind.RECEIPT, FiledBy.ROBOT, 30 * HOUR, "2026-09-28 IGA 41.07 CAD",
            mapOf("merchant" to "IGA", "date" to "2026-09-28", "total" to "41.07", "currency" to "CAD"))(it) }
        ids["hydro"] = port.add("hydro.pdf") { filed(Drawers.BILLS, FileKind.BILL, FiledBy.ROBOT, 3 * HOUR, "2026-09 Hydro-Québec 142.00 CAD due 2026-10-17",
            mapOf("merchant" to "Hydro-Québec", "date" to "2026-09-30", "total" to "142.00", "currency" to "CAD", "due" to "2026-10-17"))(it) }
        ids["lease"] = port.add("lease.pdf") { filed(Drawers.DOCUMENTS, FileKind.DOCUMENT, FiledBy.YOU, 9 * 24 * HOUR, "Lease 2026-07-01",
            mapOf("date" to "2026-07-01"))(it) }
        ids["esso"] = port.add("esso.jpg", "image/jpeg") { filed("Car", FileKind.RECEIPT, FiledBy.RULE, 5 * HOUR, "2026-10-02 Esso 61.40 CAD",
            mapOf("merchant" to "Esso", "date" to "2026-10-02", "total" to "61.40", "currency" to "CAD"))(it) }
        port.vault.update { idx -> FileViews.addDrawer(idx, "House")!! }
    }

    /** The lifecycle the screen sees: resumed, until a test sends it to the background. */
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = registry
    }

    private val owner = Owner()
    private val snackbar = SnackbarHostState()

    /** Shows the tab; [first]: the text to wait for (by default, Files' home: its search field). */
    private fun open(dark: Boolean = false, undoMs: Long = UNDO_DELETE_MS, first: String? = null) {
        rule.show(dark = dark) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
                    FilesScreen(port, log.actions, snackbar, Modifier.padding(pad), now = { NOW }, undoMs = undoMs, launch = { port.launched += it; true })
                }
            }
        }
        if (first != null) waitFor(first) else home()
    }

    /** Files' home is showing (its search field). */
    private fun home() = rule.waitUntil(5_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }

    private fun waitFor(text: String, substring: Boolean = false) =
        rule.waitUntil(5_000) { rule.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }

    private fun says(text: String) = rule.waitUntil(5_000) { log.lastOrNull() == "say $text" }

    private fun openFile(title: String) {
        rule.scrollToText(title)
        rule.onRow(title).performClick()
        filePage()
    }

    /** A file's page (or a drawer's) is showing: its bar has Back, Files' home has none. */
    private fun filePage() = rule.waitUntil(5_000) { rule.onAllNodes(hasContentDescription("Back")).fetchSemanticsNodes().isNotEmpty() }

    /**
     * FX2 (files-1.4-review.md K5): a file's page and nothing else, its page drawn: its Name row (near the top, which no
     * drawer page has) and no "Opening your files…" left. After a drawer's page, [filePage] alone is already true (the drawer
     * has Back too), so a scroll right after it could run on the drawer, or while the page's picture changes its height.
     */
    private fun fileShown() = rule.waitUntil(5_000) {
        rule.onAllNodes(hasText("Name")).fetchSemanticsNodes().isNotEmpty() && rule.onAllNodes(hasText("Opening your files…")).fetchSemanticsNodes().isEmpty()
    }

    /** A drawer's row on the home page ("Receipts" with its count; a filed file's line may name its drawer too). */
    private fun openDrawer(name: String, wait: String = "All") {
        val row = hasText(name) and hasText(" file", substring = true) and hasClickAction()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(row)
        rule.onNode(row).performClick()
        waitFor(wait)
    }

    /** The text field of the dialog showing. */
    private fun dialogField() = rule.onNode(hasSetTextAction() and hasAnyAncestor(isDialog()))

    @Test fun homeShowsInboxRecentlyFiledAndDrawers() {
        cabinet()
        open()
        rule.onNodeWithText("Inbox · 5 need you").assertExists()
        rule.onNodeWithText("Is this a receipt or a bill?").assertExists()
        rule.onNodeWithText("Check the total: Robot couldn't be sure of it.").assertExists()
        rule.onNodeWithText("2 new files: 1 receipt and 1 bill. File them?").assertExists()
        rule.onNodeWithText("This could install or run something. Robot won't open it.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_home")
        rule.scrollToText("Recently filed")
        rule.scrollToText("Lease 2026-07-01")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("New drawer")
        for (d in listOf("Receipts", "Bills", "Documents", "Medical", "Taxes", "Trips and events", "Photos and media", "Conversations", "House", "Car"))
            rule.onRow(d).assertExists()
        rule.onNode(hasText("Car") and hasText("1 file")).assertExists()
        rule.onNode(hasText("House") and hasText("0 files")).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_home_drawers")
        // The Scan button opens scanning.
        rule.onNodeWithText("Scan").performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.FILES_SCAN)}", log.last())
    }

    /**
     * F13: the Files tab and a file's page with the menus in Québec French: Robot's questions, the drawers, a receipt's
     * details (« Commerçant », « 84,12 $ », « 18 octobre 2026 », the taxes by their French names) and its tags, in the
     * glossary's words (robot/l10n/glossary.tsv) with the OQLF's spaces; still 48 dp targets with the longer words.
     */
    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun inQuebecFrench() {
        val nb = '\u00A0'
        val fine = '\u202F'
        cabinet()
        open()
        rule.onNodeWithText("Boîte de réception · 5 ont besoin de toi").assertExists()
        rule.onNodeWithText("Est-ce un reçu ou une facture$fine?").assertExists()
        rule.onNodeWithText("Vérifie le total$nb: Robot n’a pas pu en être sûr.").assertExists()
        rule.onNodeWithText("2 nouveaux fichiers$nb: 1 reçu et 1 facture. Les classer$fine?").assertExists()
        rule.onNodeWithText("Ce fichier pourrait installer ou lancer quelque chose. Robot ne l’ouvrira pas.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Classés récemment")
        rule.scrollToText("Nouveau tiroir")
        for (d in listOf("Reçus", "Factures", "Documents", "Santé", "Impôts", "Voyages et événements", "Photos et médias", "Conversations", "House", "Car"))
            rule.onRow(d).assertExists()
        rule.onNode(hasText("Car") and hasText("1 fichier")).assertExists()
        rule.scrollToText("Dossier d’impôts")
        rule.onRow("Dossier d’impôts").assert(hasText("Les feuillets et documents fiscaux d’une année, avec un tableau de leurs détails"))
        rule.scrollToText("Rapport de dépenses")
        rule.onRow("Rapport de dépenses").assert(hasText("Les fichiers étiquetés affaires d’une année, avec un tableau de leurs détails"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // A receipt's page.
        rule.scrollToText("Classés récemment")
        rule.scrollToText("2026-10-18 Costco 84.12 CAD")
        rule.onRow("2026-10-18 Costco 84.12 CAD").performClick()
        waitFor("Page 1 sur 2", substring = true)
        rule.scrollToText("Commerçant")
        rule.onRow("Commerçant").assert(hasText("Costco · Lu par Robot"))
        rule.scrollToText("Montant total")
        rule.onRow("Montant total").assert(hasText("84,12$nb$ · Lu par Robot"))
        rule.onRow("Date").assert(hasText("18 octobre 2026 · Lu par Robot"))
        rule.scrollToText("Taxe (TPS)")
        rule.onNode(hasText("Taxe (TPS)") and hasText("3,75$nb$ · Lu par Robot")).assertExists()
        rule.onNode(hasText("Taxe (TVQ)") and hasText("7,48$nb$ · Lu par Robot")).assertExists()
        rule.scrollToText("Étiquettes")
        rule.scrollToText("Affaires")
        rule.scrollToText("Perso")
        rule.scrollToText("D’où il vient")
        rule.onNodeWithText("Partagé depuis une autre appli").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun homeDark() {
        cabinet()
        open(dark = true)
        rule.shot("files_home_dark")
    }

    @Test @Config(fontScale = 2f) fun homeAtTwiceTheTextSize() {
        cabinet()
        open()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_home_large")
    }

    @Test fun emptyCabinet() {
        open(first = "Nothing here yet. Share a file to Robot Files from any app, scan a receipt, or say “file this”.")
        rule.onNodeWithText("Nothing here yet. Share a file to Robot Files from any app, scan a receipt, or say “file this”.").assertExists()
        rule.shot("files_empty")
    }

    /**
     * F11: a file from someone else's backup waits in Inbox from "a backup that isn't yours"; the drawer it was in there is
     * offered, and your tap files it there (no rule is learned from their drawer).
     */
    @Test fun someoneElsesFileOffersItsDrawer() {
        port.memoryOn = true
        val theirs = port.add("their receipt.pdf") { e ->
            e.copy(kind = FileKind.RECEIPT, filing = Filing(FilingStep.ASK, FilingWhy.NOT_YOURS, suggest = "Boat"),
                fields = mapOf("merchant" to FileField("Esso", FieldBy.READ)), from = e.from.copy(source = FileSource.BACKUP, at = NOW - HOUR))
        }
        open()
        waitFor("From a backup that isn't yours: file it where you want it.")
        rule.onNodeWithText("File in Boat").performClick()
        rule.waitUntil(5_000) { port.entry(theirs)!!.state == FileState.FILED }
        assertEquals("Boat", port.entry(theirs)!!.drawer)
        assertEquals(FiledBy.YOU, port.entry(theirs)!!.from.filedBy)
        assertTrue("move ${FileMoveCode.MOVED} 1" in port.events)
        assertTrue(port.memory.items.isEmpty())
    }

    @Test fun answerChipsFileWhatRobotAsked() {
        cabinet()
        open()
        // "Is this a receipt or a bill?": Receipt, filed as Robot would file one, by you.
        rule.onNodeWithText("Receipt").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("kind"))!!.state == FileState.FILED }
        val kind = port.entry(ids.getValue("kind"))!!
        assertEquals(FileKind.RECEIPT, kind.kind)
        assertEquals(Drawers.RECEIPTS, kind.drawer)
        assertEquals("2026-10-21 Pharmaprix 12.34 CAD", kind.name)
        assertEquals(FiledBy.YOU, kind.from.filedBy)
        // "File them" files Ask me first's batch, as Robot would have.
        rule.onNodeWithText("File them").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("them1"))!!.state == FileState.FILED }
        assertEquals(Drawers.RECEIPTS, port.entry(ids.getValue("them1"))!!.drawer)
        assertEquals("2026-10-19 Jean Coutu 23.45 CAD", port.entry(ids.getValue("them1"))!!.name)
        assertEquals(Drawers.BILLS, port.entry(ids.getValue("them2"))!!.drawer)
        assertTrue("move ${FileMoveCode.FILE_THEM} 2" in port.events)
        assertTrue("changed" in port.events)
        // The dangerous file offers no chip but its page.
        rule.onNodeWithText("Inbox · 2 need you").assertExists()
    }

    /**
     * FX1 (files-1.4-review.md finding 6): travel mode hides your drawer "Lawyer". A new drawer by that name isn't made, and
     * what Robot says never tells that a hidden drawer has it: the same line as for any taken name while drawers are hidden.
     */
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun aHiddenDrawersNameIsAnsweredWithoutNamingIt() {
        cabinet()
        port.vault.update { i -> i.copy(extra = i.extra + (FileViews.DRAWERS_KEY to listOf("Lawyer"))) }
        port.travel = FilesSettings(travel = true, hidden = setOf("Lawyer"))
        open()
        rule.scrollToText("New drawer")
        rule.onRow("New drawer").performClick()
        dialogField().performTextInput("Reçus")
        rule.inDialog("Robot can't use that name for a drawer. Try another.").assertExists()
        dialogField().performTextReplacement("lawyer")
        rule.inDialog("Create").performClick()
        says("Robot can't use that name for a drawer. Try another.")
        assertEquals(listOf("Lawyer"), FileViews.yours((port.vault.load() as IndexLoad.Loaded).index).filter { it.equals("lawyer", ignoreCase = true) })
    }

    // At 411 dp Robolectric never settles once a text field in a dialog has the focus (PreferencesTest says so): the tests
    // that type into one run on its default 320 dp screen.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun checkTheTotalAnswersAndFiles() {
        cabinet()
        open()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check the total") and hasClickAction())
        rule.onNode(hasText("Check the total") and hasClickAction()).performClick()
        filePage()
        waitFor("Page 1 of 2")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Check the total") and hasClickAction())
        rule.onNodeWithText("Check the total: Robot couldn't be sure of it.").assertExists()
        rule.onNode(hasText("Check the total") and hasClickAction()).performClick()
        dialogField().performTextInput("abc")
        // Not an amount: Save stays off.
        rule.inDialog("Save").assertIsNotEnabled()
        dialogField().performTextReplacement("52.30")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("total"))!!.state == FileState.FILED }
        val e = port.entry(ids.getValue("total"))!!
        assertEquals("2026-10-20 Metro 52.30 CAD", e.name)
        assertEquals(FileField("52.30", FieldBy.YOU), e.fields["total"])
        assertNull(e.fields["check"])
    }

    @Test fun recentlyFiledUndoPutsItBackInInbox() {
        cabinet()
        open()
        rule.scrollToText("2026-10-18 Costco 84.12 CAD")
        // The newest filed is first: Costco (1 hour), with Undo; your own lease has none.
        rule.onAllNodes(hasText("Undo")).onFirst().performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.state == FileState.INBOX }
        val e = port.entry(ids.getValue("costco"))!!
        assertEquals("costco.pdf", e.name)
        assertEquals(FilingStep.KEPT, e.filing!!.step)
        says("Back in your Inbox.")
        assertTrue("move ${FileMoveCode.UNDO} 1" in port.events)
    }

    @Test fun moveAndItsUndo() {
        cabinet()
        open()
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.onRow("Drawer").performClick()
        rule.inDialog("Move to").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_move")
        rule.inDialog("Taxes").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.drawer == Drawers.TAXES }
        assertEquals(FiledBy.YOU, port.entry(ids.getValue("costco"))!!.from.filedBy)
        waitFor("Moved to Taxes.")
        rule.onNodeWithText("Undo").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.drawer == Drawers.RECEIPTS }
        assertEquals(FiledBy.ROBOT, port.entry(ids.getValue("costco"))!!.from.filedBy)
        says("Put back where it was.")
        assertTrue("move ${FileMoveCode.MOVED} 1" in port.events && "move ${FileMoveCode.PUT_BACK} 1" in port.events)
    }

    /**
     * F8 (§4.5, the 12 Oct decision): moving a Hydro bill to House saves "Hydro Québec bills go in House" at once and says so
     * once; its Undo drops the rule and leaves the bill in House. A file Robot then files follows it.
     */
    @Test fun aMoveTeachesARuleAndItsUndoKeepsTheMove() {
        cabinet()
        port.memoryOn = true
        open()
        openFile("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17")
        rule.onRow("Drawer").performClick()
        rule.inDialog("House").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("hydro"))!!.drawer == "House" }
        waitFor("Got it: from now on, Hydro Québec bills go in House.")
        rule.shot("files_rule_learned")
        val kept = port.memory.items.single()
        assertEquals(MemoryFiling(kind = "BILL", merchant = "Hydro Québec", drawer = "House", learned = true), kept.filing)
        assertEquals("Hydro Québec bills go in House.", kept.text)
        assertEquals(MemorySource.SETTINGS, kept.source)
        assertTrue("rule ${FileRuleCode.SAVED}" in port.events)
        // The next Hydro bill Robot files goes to House by your rule.
        val next = port.entry(ids.getValue("them2"))!!.copy(fields = mapOf("merchant" to FileField("Hydro-Québec", FieldBy.READ)))
        assertEquals("House", Filer.place(next, FileKind.BILL, Filer.valuesOf(next.fields), next.fields, port.rules(), NameWords("due")).drawer)
        rule.onNodeWithText("Undo").performClick()
        says("Rule dropped. The file stays where you put it.")
        assertTrue(port.memory.items.isEmpty())
        assertEquals("House", port.entry(ids.getValue("hydro"))!!.drawer)
        assertTrue("rule ${FileRuleCode.UNDONE}" in port.events)
    }

    /** Memory off: a move is only a move (and its Undo puts it back). Memory full: the move says so, and keeps its Undo. */
    @Test fun memoryOffOrFullKeepsNoRule() {
        cabinet()
        open()
        openFile("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17")
        rule.onRow("Drawer").performClick()
        rule.inDialog("House").performClick()
        waitFor("Moved to House.")
        assertTrue(port.memory.items.isEmpty())
        assertTrue("rule ${FileRuleCode.OFF}" in port.events)
        rule.runOnIdle { snackbar.currentSnackbarData?.dismiss() }
        port.memoryOn = true
        port.memory = MemoryFile((0 until MemoryLimits.MAX_ITEMS).map { MemoryItem("m_" + it.toString(36).padStart(9, '0') + "aaaaaa", "fact $it", createdAt = NOW) })
        rule.onRow("Drawer").performClick()
        rule.inDialog("Bills").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("hydro"))!!.drawer == Drawers.BILLS }
        waitFor("Moved to Bills.")
        rule.runOnIdle { snackbar.currentSnackbarData?.dismiss() }
        rule.onRow("Drawer").performClick()
        rule.inDialog("House").performClick()
        waitFor("Moved to House. Memory is full, so Robot can't keep this rule: delete some in Settings › Memory.")
        assertTrue("rule ${FileRuleCode.FULL}" in port.events)
    }

    /** "You tag two Hydro bills business": the second tag saves the rule, confirmed once with Undo. */
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun theSecondTagTeachesARule() {
        cabinet()
        ids["hydro2"] = port.add("hydro2.pdf") { filed(Drawers.BILLS, FileKind.BILL, FiledBy.ROBOT, 40 * HOUR, "2026-08 Hydro-Québec 128.00 CAD due 2026-09-17",
            mapOf("merchant" to "Hydro-Québec", "date" to "2026-08-31", "total" to "128.00", "currency" to "CAD"))(it).copy(tags = listOf("business")) }
        port.memoryOn = true
        open()
        openFile("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17")
        rule.scrollToText("Add a tag")
        rule.onNodeWithText("Add a tag").performClick()
        dialogField().performTextInput("business")
        rule.inDialog("Save").performClick()
        waitFor("Got it: from now on, Hydro Québec bills get the tag business.")
        assertEquals(listOf("business"), port.memory.items.single().filing!!.tags)
        rule.onNodeWithText("Undo").performClick()
        says("Rule dropped.")
        assertTrue(port.memory.items.isEmpty())
        assertTrue("business" in port.entry(ids.getValue("hydro"))!!.tags)
    }

    @Test fun aDrawerWithItsMonthsAndTotals() {
        cabinet()
        port.add("rona.pdf") { filed(Drawers.RECEIPTS, FileKind.RECEIPT, FiledBy.ROBOT, 50 * HOUR, "2026-10-05 Rona 18.99 CAD",
            mapOf("merchant" to "Rona", "date" to "2026-10-05", "total" to "18.99", "currency" to "CAD", "check" to "total"))(it) }
        open()
        openDrawer("Receipts")
        rule.onNodeWithText("$125.19 in 2 receipts · 1 total to check").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_drawer")
        rule.onNodeWithText("September 2026").performClick()
        waitFor("$41.07 in 1 receipt")
        rule.onNodeWithText("2026-10-18 Costco 84.12 CAD").assertDoesNotExist()
        rule.onNodeWithText("2026-09-28 IGA 41.07 CAD").assertExists()
    }

    @Test fun aDrawerDarkAndLarge() {
        cabinet()
        open(dark = true)
        openDrawer("Receipts")
        rule.shot("files_drawer_dark")
    }

    @Test @Config(fontScale = 2f) fun aDrawerAtTwiceTheTextSize() {
        cabinet()
        open()
        openDrawer("Receipts")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_drawer_large")
    }

    // At 411 dp Robolectric never settles once a text field in a dialog has the focus (PreferencesTest says so): the tests
    // that type into one run on its default 320 dp screen.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun yourDrawersNewRenameAndDelete() {
        cabinet()
        open()
        rule.scrollToText("New drawer")
        rule.onRow("New drawer").performClick()
        dialogField().performTextInput("Reçus")
        rule.inDialog("There's already a drawer with that name.").assertExists()
        dialogField().performTextReplacement("Garage")
        rule.inDialog("Create").performClick()
        rule.waitUntil(5_000) { "Garage" in FileViews.yours((port.load() as IndexLoad.Loaded).index) }
        openDrawer("Garage", wait = "No files in this drawer yet. Move a file here from its page.")
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Rename drawer").performClick()
        dialogField().performTextReplacement("Shed")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { "Shed" in FileViews.yours((port.load() as IndexLoad.Loaded).index) }
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete drawer").performClick()
        rule.waitUntil(5_000) { "Shed" !in FileViews.yours((port.load() as IndexLoad.Loaded).index) }
        // A drawer with files isn't deleted.
        openDrawer("Car", wait = "2026-10-02 Esso 61.40 CAD")
        rule.onNodeWithContentDescription("More options").performClick()
        rule.onNodeWithText("Delete drawer").performClick()
        says("Move its files to another drawer first.")
        assertTrue("Car" in FileViews.yours((port.load() as IndexLoad.Loaded).index))
    }

    /**
     * F10 (§6.4, §7.2): a file's dates coming up, from its fields, each with Add to my brief (kept in its entry, said in the
     * brief from its day) and Add to calendar (the calendar app's own editor: nothing written); none offered with Reminders
     * from files Off. A tax file's keeping time. The Bills drawer's subscriptions, found by code.
     */
    @Test fun aFilesDatesRemindersAndSubscriptions() {
        cabinet()
        val bell = port.add("bell.pdf") { filed(Drawers.BILLS, FileKind.BILL, FiledBy.ROBOT, 2 * HOUR, "2026-10 Bell 80.00 CAD due 2026-10-25",
            mapOf("merchant" to "Bell", "date" to "2026-10-05", "total" to "80.00", "currency" to "CAD", "due" to "2026-10-25"))(it) }
        for (m in 8..10) port.add("videotron$m.pdf") { filed(Drawers.BILLS, FileKind.BILL, FiledBy.ROBOT, 24 * HOUR * (12 - m), "2026-%02d Vidéotron 35.00 CAD".format(m),
            mapOf("merchant" to "Vidéotron", "date" to "2026-%02d-03".format(m), "total" to "35.00", "currency" to "CAD"))(it) }
        port.add("t4.pdf") { filed(Drawers.TAXES, FileKind.TAX_SLIP, FiledBy.ROBOT, 4 * HOUR, "2025 T4 Employer", mapOf("year" to "2025", "slip" to "T4"))(it) }
        open()
        openFile("2026-10 Bell 80.00 CAD due 2026-10-25")
        // Its pages drawn first, so the scroll below lands the same way every run (the golden).
        waitFor("Page 1 of 2", substring = true)
        rule.waitForIdle()
        rule.scrollToText("Reminders")
        rule.onNodeWithText("Due October 25, 2026: \$80.00").assertExists()
        rule.onNodeWithText("Add to my brief").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_file_reminders")
        // Add to calendar: the calendar app's editor, the title and the day; the file is unchanged.
        val before = port.entry(bell)
        rule.onNodeWithText("Add to calendar").performClick()
        rule.waitUntil(5_000) { "calendar" in port.events }
        assertEquals("Pay Bell bill", port.launched.last().getStringExtra("title"))
        assertEquals("2026-10-25", port.launched.last().getStringExtra("day"))
        assertEquals(before, port.entry(bell))
        // Add to my brief: kept in the file's entry, said from its day.
        rule.onNodeWithText("Add to my brief").performClick()
        rule.waitUntil(5_000) { port.entry(bell)!!.fields[app.heyrobot.core.files.FileRemind.FIELD] != null }
        val on = port.entry(bell)!!.fields[app.heyrobot.core.files.FileRemind.FIELD]!!.value
        assertEquals("due:2026-10-23", on)
        waitFor("In your brief on Friday October 23")
        rule.onNodeWithText("Remove").performClick()
        rule.waitUntil(5_000) { port.entry(bell)!!.fields[app.heyrobot.core.files.FileRemind.FIELD] == null }
        assertEquals(before!!.fields, port.entry(bell)!!.fields)
        // The Bills drawer: its subscriptions line.
        rule.onNodeWithContentDescription("Back").performClick()
        openDrawer("Bills")
        waitFor("1 subscription in your bills: \$35.00 a month.")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_drawer_bills")
        rule.onNodeWithContentDescription("Back").performClick()
        // A tax slip: its keeping time.
        openDrawer("Taxes", wait = "2025 T4 Employer")
        rule.onRow("2025 T4 Employer").performClick()
        fileShown()
        rule.waitForIdle()
        rule.scrollToText("Keep until", substring = true)
        // Waited for, as the page's other lines are: read at once, it was missed in full runs (passing alone).
        waitFor("Keep until the end of 2031: the CRA and Revenu Québec ask you to keep tax records for six years.")
    }

    /** F10: with Reminders from files Off, a file's dates show without an offer. */
    @Test fun noOfferWithRemindersOff() {
        port.settings = FilesSettings(reminders = app.heyrobot.core.files.FileReminders.OFF)
        port.add("bell.pdf") { filed(Drawers.BILLS, FileKind.BILL, FiledBy.ROBOT, 2 * HOUR, "2026-10 Bell 80.00 CAD due 2026-10-25",
            mapOf("merchant" to "Bell", "date" to "2026-10-05", "total" to "80.00", "currency" to "CAD", "due" to "2026-10-25"))(it) }
        open()
        openFile("2026-10 Bell 80.00 CAD due 2026-10-25")
        rule.scrollToText("Reminders")
        rule.onNodeWithText("Due October 25, 2026: \$80.00").assertExists()
        rule.onNodeWithText("Add to my brief").assertDoesNotExist()
        rule.onNodeWithText("Add to calendar").assertDoesNotExist()
    }

    @Test fun aFilesPage() {
        cabinet()
        open()
        openFile("2026-10-18 Costco 84.12 CAD")
        waitFor("Page 1 of 2", substring = true)
        rule.onNodeWithContentDescription("Page 1 of 2").assertExists()
        rule.onNodeWithText("Next page").performClick()
        rule.waitUntil(5_000) { "page 1" in port.events }
        rule.onNodeWithText("Previous page").performClick()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_file")
        rule.scrollToText("Where it came from")
        rule.onNodeWithText("Shared from another app").assertExists()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Filed") and hasText("By Robot, October 22, 2026"))
        rule.scrollToText("Delete")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_file_end")
    }

    @Test fun aFilesPageDark() {
        cabinet()
        open(dark = true)
        openFile("2026-10-18 Costco 84.12 CAD")
        waitFor("Page 1 of 2", substring = true)
        rule.shot("files_file_dark")
    }

    @Test @Config(fontScale = 2f) fun aFilesPageAtTwiceTheTextSize() {
        cabinet()
        open()
        openFile("2026-10-18 Costco 84.12 CAD")
        waitFor("Page 1 of 2", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_file_large")
    }

    // At 411 dp Robolectric never settles once a text field in a dialog has the focus (PreferencesTest says so): the tests
    // that type into one run on its default 320 dp screen.
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun renameTagAndLinkAPerson() {
        cabinet()
        open()
        openFile("2026-10-18 Costco 84.12 CAD")
        // Its page first (drawn by :files' stand-in), so the rows below stay where they are.
        waitFor("Page 1 of 2")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Name") and hasClickAction())
        rule.onRow("Name").performClick()
        dialogField().performTextReplacement("Groceries for the cottage")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.named }
        assertEquals("Groceries for the cottage", port.entry(ids.getValue("costco"))!!.name)
        waitFor("Groceries for the cottage · Named by you")
        rule.scrollToText("Add a tag")
        rule.onNodeWithText("Add a tag").performClick()
        // F12, deliberately: a tag you type (business and personal now have chips of their own, below).
        dialogField().performTextInput("Garage")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { "Garage" in port.entry(ids.getValue("costco"))!!.tags }
        // F12: Personal, then Business in its place (never both), then Business off again: one tap each.
        rule.onNodeWithText("Personal").performClick()
        rule.waitUntil(5_000) { "personal" in port.entry(ids.getValue("costco"))!!.tags }
        rule.onNodeWithText("Business").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.tags.let { "business" in it && "personal" !in it } }
        rule.onNodeWithText("Business").performClick()
        rule.waitUntil(5_000) { "business" !in port.entry(ids.getValue("costco"))!!.tags }
        rule.scrollToText("Link a person")
        rule.onNodeWithText("Link a person").performClick()
        rule.inDialog("Sam Tremblay").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.people == listOf("p-sam") }
        rule.scrollToText("Garage")
        rule.onNodeWithContentDescription("Remove Garage").performClick()
        rule.waitUntil(5_000) { "Garage" !in port.entry(ids.getValue("costco"))!!.tags }
        rule.scrollToText("Sam Tremblay")
        rule.onNodeWithContentDescription("Remove Sam Tremblay").performClick()
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.people.isEmpty() }
    }

    @Test fun deleteWithUndoThenTheKeyIsDestroyed() {
        cabinet()
        open(undoMs = 60_000)
        val costco = ids.getValue("costco")
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.scrollToText("Delete")
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete “2026-10-18 Costco 84.12 CAD”?").assertExists()
        rule.inDialog("It stays in backups you made before.").assertExists()
        rule.shot("files_delete")
        // Keep deletes nothing.
        rule.inDialog("Keep").performClick()
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete").performClick()
        // Gone from every list at once, with Undo; nothing destroyed yet.
        waitFor("File deleted.")
        home()
        rule.onNodeWithText("2026-10-18 Costco 84.12 CAD").assertDoesNotExist()
        assertTrue(port.entry(costco) != null)
        rule.onNodeWithText("Undo").performClick()
        says("The file is back.")
        assertTrue(port.events.none { it.startsWith("delete ") })
        assertTrue("deleteLog ${FileDeleteCode.UNDONE} 1" in port.events)
        rule.scrollToText("2026-10-18 Costco 84.12 CAD")
    }

    @Test fun deleteGoesThroughWhenItsUndoRunsOut() {
        cabinet()
        assertEquals(10_000L, UNDO_DELETE_MS)
        open(undoMs = 300)
        val costco = ids.getValue("costco")
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.scrollToText("Delete")
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete").performClick()
        rule.waitUntil(5_000) { "delete 1 true" in port.events }
        assertTrue("takeBack one" in port.events)
        assertTrue("deleteLog ${FileDeleteCode.DELETED} 1" in port.events)
        // Its key is destroyed: nothing of it can be opened, and it is out of the index.
        assertNull(port.entry(costco))
        assertEquals(FileProblem.MISSING, (runCatching { port.vault.open(costco) }.exceptionOrNull() as FileException).problem)
    }

    @Test fun shareAndOpenWithAnotherAppHandACopy() {
        cabinet()
        val photo = port.add("IMG_3002.HEIC", "image/heic") { filed(Drawers.PHOTOS, FileKind.PHOTO, FiledBy.ROBOT, 7 * HOUR, "IMG_3002.HEIC")(it) }
        val video = port.add("VID_0007.mp4", "video/mp4") { filed(Drawers.PHOTOS, FileKind.VIDEO, FiledBy.ROBOT, 8 * HOUR, "VID_0007.mp4")(it) }
        open()
        // A receipt: Share sends it as it is, through the chooser, with a read grant only.
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.scrollToText("Share")
        rule.onRow("Share").performClick()
        rule.waitUntil(5_000) { port.launched.size == 1 }
        assertTrue("copy ORIGINAL 2026-10-18 Costco 84.12 CAD.pdf application/pdf" in port.events)
        val chooser = port.launched[0]
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        @Suppress("DEPRECATION") val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, send.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
        // Open with another app asks first, then hands a copy and remembers that you opened it.
        rule.onRow("Open with another app").performClick()
        rule.inDialog("This gives the app a copy of the file. Robot deletes its copy when you come back, but the app may keep one.").assertExists()
        rule.inDialog("Cancel").performClick()
        assertEquals(1, port.launched.size)
        rule.onRow("Open with another app").performClick()
        rule.inDialog("Open").performClick()
        rule.waitUntil(5_000) { port.launched.size == 2 }
        assertEquals(Intent.ACTION_CHOOSER, port.launched[1].action)
        rule.waitUntil(5_000) { port.entry(ids.getValue("costco"))!!.from.opened }
        assertEquals(1, port.events.count { it == "share" })
        assertEquals(1, port.events.count { it == "open" })
        // The plan for the others: a video asks first, a photo goes without its metadata (both on screen below).
        assertTrue(FileOut.plan(port.entry(video)!!, FileType.MP4, port.settings.stripLocation) is app.heyrobot.core.files.OutPlan.AskFirst)
        assertEquals(app.heyrobot.core.files.OutPlan.Go(OutgoingCopy.STRIPPED, "IMG_3002.jpg", "image/jpeg"), FileOut.plan(port.entry(photo)!!, FileType.HEIC, true))
    }

    @Test fun aVideoAsksBeforeItGoesWithItsLocation() {
        port.add("VID_0007.mp4", "video/mp4") { filed(Drawers.PHOTOS, FileKind.VIDEO, FiledBy.ROBOT, 8 * HOUR, "VID_0007.mp4")(it) }
        port.add("IMG_3002.HEIC", "image/heic") { filed(Drawers.PHOTOS, FileKind.PHOTO, FiledBy.ROBOT, 9 * HOUR, "IMG_3002.HEIC")(it) }
        open()
        openFile("VID_0007.mp4")
        rule.scrollToText("Share")
        rule.onRow("Share").performClick()
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Send it with its location?")).fetchSemanticsNodes().isNotEmpty() }
        rule.inDialog("Send it with its location?").assertExists()
        rule.inDialog("Robot can't remove the location and other details from this file. The app gets it as you added it.").assertExists()
        rule.inDialog("Send it as it is").performClick()
        rule.waitUntil(5_000) { port.launched.size == 1 }
        assertTrue("copy ORIGINAL VID_0007.mp4 video/mp4" in port.events)
        // A photo goes stripped, with no question.
        rule.onNodeWithContentDescription("Back").performClick()
        home()
        openFile("IMG_3002.HEIC")
        rule.scrollToText("Share")
        rule.onRow("Share").performClick()
        rule.waitUntil(5_000) { port.launched.size == 2 }
        assertTrue("copy STRIPPED IMG_3002.jpg image/jpeg" in port.events)
    }

    @Test fun searchFindsInBothLanguages() {
        cabinet()
        open()
        rule.onNode(hasSetTextAction()).performTextInput("costco")
        waitFor("1 file found")
        rule.onNodeWithText("2026-10-18 Costco 84.12 CAD").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_search")
        rule.onNode(hasSetTextAction()).performTextReplacement("facture")
        waitFor("2026-09 Hydro-Québec 142.00 CAD due 2026-10-17")
        rule.onNode(hasSetTextAction()).performTextClearance()
        home()
    }

    @Test fun searchDark() {
        cabinet()
        open(dark = true)
        rule.onNode(hasSetTextAction()).performTextInput("costco")
        waitFor("1 file found")
        rule.shot("files_search_dark")
    }

    @Test @Config(fontScale = 2f) fun searchAtTwiceTheTextSize() {
        cabinet()
        open()
        rule.onNode(hasSetTextAction()).performTextInput("costco")
        waitFor("1 file found")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_search_large")
    }

    @Test fun lockedNothingIsShownAndWhatWaitsIsDone() {
        cabinet()
        open(undoMs = 60_000)
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.scrollToText("Delete")
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete").performClick()
        waitFor("File deleted.")
        // The phone locks (or a phone lock comes on) while the screen shows.
        port.locked = true
        waitFor("Unlock your phone to use your files.")
        rule.onNodeWithText("Drawers").assertDoesNotExist()
        rule.onNodeWithText("2026-10-02 Esso 61.40 CAD").assertDoesNotExist()
        rule.waitUntil(5_000) { "endReader" in port.events && "takeBack all" in port.events }
        // The delete you confirmed went through (its key destroyed), the Undo too late.
        rule.waitUntil(5_000) { port.events.any { it.startsWith("delete 1") } }
        rule.shot("files_locked")
        // Unlocked again: the files come back.
        port.locked = false
        home()
        assertNull(port.entry(ids.getValue("costco")))
    }

    @Test fun lockedAtOpenShowsNothing() {
        cabinet()
        port.locked = true
        open(first = "Unlock your phone to use your files.")
        rule.onNodeWithText("Inbox", substring = true).assertDoesNotExist()
    }

    @Test fun theBackgroundDropsTheScreenAndTakesCopiesBack() {
        cabinet()
        open(undoMs = 60_000)
        openFile("2026-10-18 Costco 84.12 CAD")
        rule.scrollToText("Delete")
        rule.onRow("Delete").performClick()
        rule.inDialog("Delete").performClick()
        waitFor("File deleted.")
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.waitUntil(5_000) { "stopped" in port.events && "endReader" in port.events && port.events.any { it.startsWith("delete 1") } }
        waitFor("Opening your files…")
        rule.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        rule.waitUntil(5_000) { "resumed" in port.events }
        home()
    }

    @Test fun aDamagedIndexOffersSettings() {
        port.add("a.pdf")
        // A list Robot can't read: nothing read, nothing written; Settings › Files says what to do (Start over).
        File(port.dir, "index.enc").writeBytes(ByteArray(10))
        port.vault.drop()
        open(first = "Robot couldn't read your files' list. Start over makes a new one from your files.")
        rule.onNodeWithText("Open Settings › Files").performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.FILES)}", log.last())
    }

    @Test fun theStackKeepsIdsOnly() {
        val id = FileId.of("0123456789abcdef0123456789abcdef")!!
        val saved = with(FilesView.Saver) { androidx.compose.runtime.saveable.SaverScope { true }.save(listOf(FilesView.Drawer(Drawers.RECEIPTS),
            FilesView.Drawer("Sam's medical"), FilesView.File(id))) }!!
        assertEquals("d:receipts\nf:0123456789abcdef0123456789abcdef", saved)
        assertFalse("Sam" in saved)
        assertEquals(listOf(FilesView.Drawer(Drawers.RECEIPTS), FilesView.File(id)), FilesView.Saver.restore(saved))
    }
}
