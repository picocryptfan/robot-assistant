// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.files.FileUsage
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.Recovery
import app.heyrobot.core.i18n.Fmt
import app.heyrobot.core.i18n.Lang
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.KeystoreFileKeys
import app.heyrobot.sys.SoftFileKeystore
import app.heyrobot.ui.Page
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
 * Settings › Files (files-1.4-plan.md §2.4, §6.6; F2's rows) on a real vault with robot_files_v1 in software: what the files
 * take, Delete them all behind its dialog (and refused when the phone locked meanwhile), the phone locked, the key gone
 * with Restore and Delete them all, a list Robot can't read with Start over, a newer Robot's; in English and Québec French,
 * with goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FilesSettingsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })
    private val log = ActionLog()
    private val nb = '\u00A0'
    private val fine = '\u202F'

    /** The page's store over a real vault; [newer] stands in for a newer Robot's index. */
    private inner class TestStore : FilesStore {
        val keystore = SoftFileKeystore()
        var locked = false
        /** Locked from the next call on (the phone locking while a dialog shows). */
        var lockAtTap = false
        var newer = false
        val dir = File(tmp.root, "files")
        val vault = FilesVault(dir, KeystoreFileKeys(keystore), "main", space = { 100L shl 30 }, locked = { locked })
        val events = ArrayList<String>()
        // F12: as the phone's store, the index as travel mode shows it.
        override fun load(): IndexLoad = if (newer) IndexLoad.TooNew(2) else vault.load().let { l ->
            if (l is IndexLoad.Loaded) IndexLoad.Loaded(app.heyrobot.core.files.TravelMode.shown(l.index, settings, shown)) else l }
        /** F12: a screen lock on this phone; hidden drawers shown with the PIN. */
        var lock = true
        var shown = false
        override fun secure() = lock
        override fun revealed() = shown
        override fun reveal() { shown = true; events += "reveal" }
        override fun hideAgain() { shown = false }
        /** FX1: what the PIN-only prompt answers (null: it never answers), and the line each confirm showed. */
        var pinAnswer: app.heyrobot.sys.PinAnswer? = app.heyrobot.sys.PinAnswer.NO_SCREEN
        val asked = ArrayList<String>()
        override fun confirm(host: android.content.Context, title: String, text: String, done: (app.heyrobot.sys.PinAnswer) -> Unit) {
            asked += "$title / $text"
            pinAnswer?.let(done)
        }
        override fun usage() = vault.usage()
        override fun deleteAll() = vault.deleteAll().also { events += "deleteAll $it" }
        override fun startOver(): Recovery? = vault.startOver().also { events += "startOver" }
        override fun locked(): Boolean {
            if (lockAtTap) locked = true
            return locked
        }
        override fun changed() { events += "changed" }
        /** F8: memory stood in for (null: the store's default, no line). */
        var rules: Int? = null
        var memoryOn = true
        override fun rules(): Int? = rules
        override fun memoryOn(): Boolean = memoryOn
        /** F10: Settings › Files' values as saved, and every delete asked for. */
        var settings = app.heyrobot.core.files.FilesSettings()
        override fun settings() = settings
        // FX3: as the phone's store, the change applied to the settings as they are when saved.
        override fun save(pin: Boolean, change: (app.heyrobot.core.files.FilesSettings) -> app.heyrobot.core.files.FilesSettings): Boolean {
            settings = change(settings); events += if (pin) "save pin" else "save"; return true }
        override fun delete(id: app.heyrobot.core.files.FileId): Boolean = ((vault.delete(listOf(id))?.removed ?: 0) > 0).also { events += "delete $it" }

        fun add(vararg sizes: Int) {
            val s = FileStore(dir, KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 })
            for ((i, n) in sizes.withIndex()) s.add(ByteArrayInputStream(ByteArray(n) { it.toByte() }), FileIntake("file$i.pdf", FileSource.SHARE))
        }
    }

    private val store by lazy { TestStore() }

    private fun open(opened: String = "Storage", undoMs: Long = app.heyrobot.core.files.PendingDeletes.UNDO_MS) {
        rule.show { FilesSettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, undoMs = undoMs) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(opened, substring = true).assertExists() }.isSuccess }
    }

    private fun says(text: String) = rule.waitUntil(5_000) { log.lastOrNull() == "say $text" }

    /**
     * F10 (§6.6, §7.4): Reminders from files, Show in morning brief and Clean-up suggestions, saved as you change them; the
     * suggestions listed with why, each deleted only from its own dialog's Delete (Keep keeps it, and a lock at the tap stops it).
     */
    @Test fun remindersBriefAndCleanUp() {
        store.add(10, 20, 30)
        val ids = (store.vault.load() as IndexLoad.Loaded).index.entries.map { it.id }
        store.vault.update { idx -> idx.copy(entries = idx.entries.mapIndexed { i, e ->
            val f = mapOf("date" to if (i == 0) "2026-01-15" else "2027-06-01").mapValues { app.heyrobot.core.files.FileField(it.value, app.heyrobot.core.files.FieldBy.READ) }
            e.copy(state = app.heyrobot.core.files.FileState.FILED, kind = app.heyrobot.core.files.FileKind.TICKET, drawer = app.heyrobot.core.files.Drawers.TRIPS,
                name = if (i == 0) "Train to Québec 2026-01-15" else "Flight to Halifax $i", fields = f)
        }) }
        open()
        rule.onRow("Reminders from files").assert(hasStateDescription("Ask me first · Adding to your calendar always takes a card."))
        rule.onRow("Show in morning brief").assert(hasText("What Robot filed and your reminders, only while the phone is unlocked."))
        rule.onRow("Clean-up suggestions").assert(hasText("Files that look no longer needed. Deleting always asks."))
        rule.onRow("Show in morning brief").performClick()
        rule.waitUntil(5_000) { !store.settings.brief }
        rule.onRow("Reminders from files").performClick()
        rule.inDialog("Add to my brief on its own").performClick()
        rule.waitUntil(5_000) { store.settings.reminders == app.heyrobot.core.files.FileReminders.BRIEF }
        rule.scrollToText("Files that could go")
        rule.onRow("Train to Québec 2026-01-15").assert(hasText("A ticket for a date that has passed."))
        rule.onNodeWithText("Flight to Halifax 1").assertDoesNotExist()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_cleanup")
        // Keep: nothing goes.
        rule.onRow("Train to Québec 2026-01-15").performClick()
        rule.inDialog("Delete “Train to Québec 2026-01-15”?").assertExists()
        rule.inDialog("Keep").performClick()
        assertTrue(store.events.none { it.startsWith("delete") })
        // Locked at the tap: not deleted.
        rule.onRow("Train to Québec 2026-01-15").performClick()
        store.lockAtTap = true
        rule.inDialog("Delete").performClick()
        says("Unlock your phone to use your files.")
        assertTrue(store.events.none { it.startsWith("delete") })
        // The page now reads as locked (nothing about the files): unlocked, Try again shows them again.
        store.lockAtTap = false
        rule.waitUntil(5_000) { runCatching { rule.onRow("Try again").assertExists() }.isSuccess }
        rule.onNodeWithText("Train to Québec 2026-01-15").assertDoesNotExist()
        store.locked = false
        rule.onRow("Try again").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onRow("Train to Québec 2026-01-15").assertExists() }.isSuccess }
        rule.onRow("Train to Québec 2026-01-15").performClick()
        rule.inDialog("Delete").performClick()
        // FX2 (files-1.4-review.md K1), deliberately: "File deleted." with Undo for 10 seconds, the suggestion gone at once and
        // nothing deleted yet; Undo brings it back.
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("File deleted.").assertExists() }.isSuccess }
        rule.onNodeWithText("Train to Québec 2026-01-15").assertDoesNotExist()
        assertTrue(store.events.none { it.startsWith("delete") })
        rule.onNodeWithText("Undo").performClick()
        says("The file is back.")
        rule.waitUntil(5_000) { runCatching { rule.onRow("Train to Québec 2026-01-15").assertExists() }.isSuccess }
        assertTrue(store.events.none { it.startsWith("delete") })
        // Its Undo runs out: deleted.
        rule.onRow("Train to Québec 2026-01-15").performClick()
        rule.inDialog("Delete").performClick()
        rule.waitUntil(15_000) { store.events.any { it.startsWith("delete") } }
        assertEquals(listOf("delete true"), store.events.filter { it.startsWith("delete") })
        assertEquals(ids.drop(1), (store.vault.load() as IndexLoad.Loaded).index.entries.map { it.id })
        rule.onNodeWithText("Train to Québec 2026-01-15").assertDoesNotExist()
    }

    /**
     * FX2 (files-1.4-review.md finding 4): §6.6's decided rows, which production code reads but no row could change until
     * now: Robot Files on or off, each kind's filing level, reading text in images, stripping location; each saved as you
     * change it, with travel mode as it was.
     */
    @Test fun robotFilesFilingLevelsTextAndLocation() {
        store.add(10)
        store.settings = store.settings.copy(travel = true, hidden = setOf(app.heyrobot.core.files.Drawers.MEDICAL))
        open()
        rule.scrollToText("Robot Files")
        rule.onRow("Robot Files").assert(hasText("Off, Robot takes no new files. The files you have stay as they are."))
        rule.onRow("Robot Files").performClick()
        rule.waitUntil(5_000) { !store.settings.on }
        rule.scrollToText("Strip location from photos I send")
        rule.onRow("Documents").assert(hasStateDescription("File it on its own · IDs, tax slips, tickets and medical files too"))
        rule.onRow("Other files").assert(hasStateDescription("File it on its own · Notes, transcripts and everything else"))
        rule.onRow("Bills").performClick()
        rule.inDialog("Ask me first").performClick()
        rule.waitUntil(5_000) { store.settings.bills == app.heyrobot.core.files.FileLevel.ASK }
        rule.onRow("Photos and videos").performClick()
        rule.inDialog("Off: it stays in your Inbox, unread").performClick()
        rule.waitUntil(5_000) { store.settings.photos == app.heyrobot.core.files.FileLevel.OFF }
        rule.onRow("Read text in images").assert(hasText("With Robot's own model, on this phone. Off, a photo is filed as a photo and Robot asks what a scan is."))
        rule.onRow("Read text in images").performClick()
        rule.waitUntil(5_000) { !store.settings.readImageText }
        rule.onRow("Strip location from photos I send").performClick()
        rule.waitUntil(5_000) { !store.settings.stripLocation }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // Each saved over the settings as they were: travel mode and its hidden drawer stay.
        assertTrue(store.settings.travel)
        assertEquals(setOf(app.heyrobot.core.files.Drawers.MEDICAL), store.settings.hidden)
        assertEquals(app.heyrobot.core.files.FileLevel.AUTO, store.settings.receipts)
    }

    /** FX3: a row saves only its own change, over the settings as they are then (a restore's finish wrote others meanwhile). */
    @Test fun aRowKeepsWhatARestoreWroteMeanwhile() {
        store.add(10)
        open()
        store.settings = store.settings.copy(receipts = app.heyrobot.core.files.FileLevel.ASK, cleanup = false)
        rule.scrollToText("Robot Files")
        rule.onRow("Robot Files").performClick()
        rule.waitUntil(5_000) { !store.settings.on }
        assertEquals(app.heyrobot.core.files.FileLevel.ASK, store.settings.receipts)
        assertFalse(store.settings.cleanup)
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun robotFilesFilingLevelsTextAndLocationInFrench() {
        store.add(10)
        open("Stockage")
        rule.scrollToText("Fichiers de Robot")
        rule.onRow("Fichiers de Robot").assert(hasText("Désactivés, Robot ne prend aucun nouveau fichier. Ceux que tu as restent comme ils sont."))
        rule.scrollToText("Retirer la localisation des photos que j’envoie")
        rule.onRow("Reçus").assert(hasStateDescription("Classer sans demander"))
        rule.onRow("Factures").performClick()
        rule.inDialog("Désactivé${nb}: il reste dans ta boîte de réception, sans être lu").assertExists()
        rule.inDialog("Demander d’abord").performClick()
        rule.waitUntil(5_000) { store.settings.bills == app.heyrobot.core.files.FileLevel.ASK }
        rule.onRow("Lire le texte dans les images").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_levels_fr")
    }

    /**
     * F12 (§6.6): travel mode. Hiding a drawer needs nothing; with travel mode on, the hidden drawer leaves the list (its files
     * leave every count) and "Show hidden drawers" asks for the PIN; without a screen lock travel mode can't be turned on, and a
     * lock removed later never keeps it on.
     */
    @Test fun travelModeHidesDrawersUntilThePin() {
        store.add(10, 20)
        store.vault.update { idx -> idx.copy(entries = idx.entries.mapIndexed { i, e ->
            e.copy(state = app.heyrobot.core.files.FileState.FILED, drawer = if (i == 0) app.heyrobot.core.files.Drawers.MEDICAL else app.heyrobot.core.files.Drawers.RECEIPTS)
        }) }
        open()
        rule.scrollToText("Hidden drawers")
        rule.scrollToText("Medical")
        rule.onRow("Medical").performClick()
        rule.waitUntil(5_000) { store.settings.hidden == setOf(app.heyrobot.core.files.Drawers.MEDICAL) }
        // No screen lock: not on.
        store.lock = false
        rule.scrollToText("Travel mode")
        rule.onRow("Travel mode").performClick()
        says("Set a screen lock first: travel mode needs your PIN to show hidden drawers again.")
        assertFalse(store.settings.travel)
        store.lock = true
        rule.onRow("Travel mode").performClick()
        rule.waitUntil(5_000) { store.settings.travel }
        // Its files leave the count.
        rule.scrollToText("Storage")
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("1 file", substring = true).assertExists() }.isSuccess }
        rule.scrollToText("Show hidden drawers")
        rule.onNodeWithText("Medical").assertDoesNotExist()
        rule.scrollToText("Other drawers may be hidden. Show them to change them.")
        // A screen lock, but Android's confirm screen didn't come: nothing is shown, and travel mode stays on.
        rule.scrollToText("Show hidden drawers")
        rule.onRow("Show hidden drawers").performClick()
        says("Robot couldn't open the screen that asks for your PIN. Nothing changed.")
        rule.scrollToText("Travel mode")
        rule.onRow("Travel mode").performClick()
        says("Robot couldn't open the screen that asks for your PIN. Nothing changed.")
        assertTrue(store.settings.travel)
        // The lock removed since: turning travel mode off needs nothing more.
        store.lock = false
        rule.onRow("Travel mode").performClick()
        rule.waitUntil(5_000) { !store.settings.travel }
        assertEquals(setOf(app.heyrobot.core.files.Drawers.MEDICAL), store.settings.hidden)
        assertEquals("save pin", store.events.last())
    }

    /**
     * FX1 (files-1.4-review.md finding 1): Show hidden drawers, Unhide and Off each ask the store's one confirm (the phone's
     * PIN, pattern or password only, sys/DevicePin) with their own line, and change something only on its yes; a cancel says
     * so and changes nothing.
     */
    @Test fun travelModesConfirmsTakeThePinOnly() {
        store.add(10, 20)
        store.vault.update { idx -> idx.copy(entries = idx.entries.mapIndexed { i, e ->
            e.copy(state = app.heyrobot.core.files.FileState.FILED, drawer = if (i == 0) app.heyrobot.core.files.Drawers.MEDICAL else app.heyrobot.core.files.Drawers.RECEIPTS)
        }) }
        store.settings = app.heyrobot.core.files.FilesSettings(travel = true, hidden = setOf(app.heyrobot.core.files.Drawers.MEDICAL))
        open()
        // Cancelled: nothing shown.
        store.pinAnswer = app.heyrobot.sys.PinAnswer.NOT_CONFIRMED
        rule.scrollToText("Show hidden drawers")
        rule.onRow("Show hidden drawers").performClick()
        says("Not confirmed: nothing changed.")
        assertFalse(store.shown)
        assertEquals("Confirm it's you / Show your hidden drawers", store.asked.last())
        // Your PIN: shown, then listed with its switch on.
        store.pinAnswer = app.heyrobot.sys.PinAnswer.CONFIRMED
        rule.onRow("Show hidden drawers").performClick()
        says("Hidden drawers show in Files for 10 minutes, until you leave Robot or the screen goes off.")
        assertTrue(store.shown)
        rule.scrollToText("Medical")
        // Unhiding it while travel mode is on asks again; a yes saves it as confirmed.
        store.pinAnswer = null
        rule.onRow("Medical").performClick()
        assertEquals("Confirm it's you / Stop hiding a drawer", store.asked.last())
        assertEquals(setOf(app.heyrobot.core.files.Drawers.MEDICAL), store.settings.hidden)
        store.pinAnswer = app.heyrobot.sys.PinAnswer.CONFIRMED
        rule.onRow("Medical").performClick()
        rule.waitUntil(5_000) { store.settings.hidden.isEmpty() }
        assertEquals("save pin", store.events.last())
        assertTrue(store.settings.travel)
        // Off: its own line, saved as confirmed.
        store.settings = store.settings.copy(hidden = setOf(app.heyrobot.core.files.Drawers.RECEIPTS))
        rule.scrollToText("Travel mode")
        rule.onRow("Travel mode").performClick()
        rule.waitUntil(5_000) { !store.settings.travel }
        assertEquals("Confirm it's you / Turn travel mode off and show your hidden drawers", store.asked.last())
        assertEquals("save pin", store.events.last())
        assertEquals(5, store.asked.size)
    }

    @Test fun nothingYet() {
        open()
        rule.onNodeWithText("No files yet").assertExists()
        rule.onNodeWithText("Delete them all").assertDoesNotExist()
        rule.scrollToText("Backup reminder")
        rule.onRow("Backup reminder").assert(hasText("Back up and restore can remind you every month")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.BACKUP)}", log.last())
        rule.scrollToText("Your files never show on the lock screen", substring = true)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    /** F8 (§6.6): your filing rules live in memory: their count and a link to Settings › Memory, or why Robot keeps none. */
    @Test fun filingRulesLineAndLink() {
        store.add(120_000)
        store.rules = 4
        open()
        rule.scrollToText("Filing rules")
        rule.onRow("Filing rules").assert(hasText("4 filing rules, in Settings › Memory")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.MEMORY)}", log.last())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_rules")
    }

    @Test fun memoryOffKeepsNoRules() {
        store.add(120_000)
        store.memoryOn = false
        open()
        rule.scrollToText("Filing rules")
        rule.onRow("Filing rules").assert(hasText("Memory is off, so Robot won't keep filing rules."))
        rule.shot("files_settings_rules_off")
        // Locked: nothing about rules either.
    }

    @Test fun whatTheFilesTakeThenDeleteThemAll() {
        store.add(120_000, 2_400_000, 40)
        open()
        rule.onNodeWithText("3 files · 2.5 MB").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings")
        rule.onRow("Delete them all").assert(hasText("Every file in Robot Files, and the key that opens them")).performClick()
        rule.inDialog("Robot deletes every file in Robot Files and the key that opens them. Copies stay in backups you made before. This can't be undone.").assertExists()
        rule.shot("files_settings_delete")
        rule.inDialog("Delete them all").performClick()
        says("Your files are deleted.")
        assertEquals(listOf("deleteAll true", "changed"), store.events)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("No files yet").assertExists() }.isSuccess }
        assertEquals(FileUsage(0, 0), store.usage())
        assertEquals(listOf(KeystoreFileKeys.ALIAS), store.keystore.deleted)
    }

    @Test fun cancelDeletesNothing() {
        store.add(10)
        open()
        rule.onRow("Delete them all").performClick()
        rule.inDialog("Cancel").performClick()
        assertTrue(store.events.isEmpty())
        assertEquals(1, store.usage().files)
    }

    /** The phone locked while the dialog showed: nothing is deleted, and the page says why. */
    @Test fun deleteThemAllWaitsForTheUnlock() {
        store.add(10)
        open()
        rule.onRow("Delete them all").performClick()
        store.lockAtTap = true
        rule.inDialog("Delete them all").performClick()
        says("Unlock your phone to use your files.")
        assertTrue(store.events.isEmpty())
        assertEquals(1, store.usage().files)
    }

    @Test fun theLockedPhoneIsNotNow() {
        store.add(10, 20)
        store.locked = true
        open("Unlock your phone")
        rule.onNodeWithText("Unlock your phone to use your files.").assertExists()
        // Nothing about the files, not even how many (a phone lock leaves the screen to someone else); nothing to delete.
        rule.onNodeWithText("2 files · ", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Storage").assertDoesNotExist()
        rule.onNodeWithText("Delete them all").assertDoesNotExist()
        rule.onNodeWithText("Start over").assertDoesNotExist()
        store.locked = false
        rule.onRow("Try again").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onRow("Delete them all").assertExists() }.isSuccess }
    }

    @Test fun theKeyGoneKeepsEveryFileAndOffersRestoreOrDelete() {
        store.add(10, 20)
        store.keystore.keys.clear()
        open("key is gone")
        rule.onNodeWithText("Robot's files key is gone, so your files can't be read. Robot keeps them until you choose: restore them from a backup, or delete them.")
            .assertExists()
        rule.onNodeWithText("2 files · ", substring = true).assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_key_gone")
        rule.onRow("Restore from a backup").assert(hasText("In Back up and restore")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.BACKUP)}", log.last())
        rule.onRow("Delete them all").performClick()
        rule.inDialog("Delete them all").performClick()
        says("Your files are deleted.")
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("No files yet").assertExists() }.isSuccess }
        assertFalse(store.keystore.contains(KeystoreFileKeys.ALIAS))
    }

    @Test fun aListRobotCantReadStartsOver() {
        store.add(10, 20)
        File(store.dir, FileStore.INDEX).writeBytes(ByteArray(300) { 7 })
        open("couldn't read")
        rule.onNodeWithText("Robot couldn't read your files' list. Start over makes a new one from your files.").assertExists()
        rule.onRow("Start over").assert(hasText("Your files come back in Inbox, without their names")).performClick()
        rule.inDialog("Robot keeps the list it couldn't read aside as index.enc.bad and makes a new one from your files. They come back in Inbox, without their names.")
            .assertExists()
        rule.inDialog("Start over").performClick()
        says("Robot made a new list: 2 files are in Inbox.")
        assertTrue(File(store.dir, FileStore.BAD).isFile)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("2 files · ", substring = true).assertExists() }.isSuccess }
        rule.onRow("Delete them all").assertExists()
    }

    @Test fun aNewerRobotsList() {
        store.add(10)
        store.newer = true
        open("newer Robot")
        rule.onNodeWithText("Your files' list is from a newer Robot, so this one can't read or change it. Update Robot, or start over.").assertExists()
        rule.onRow("Start over").assertExists()
        rule.onRow("Delete them all").assertExists()
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun inFrench() {
        store.add(120_000, 2_400_000, 40)
        open("Stockage")
        rule.onNodeWithText("3 fichiers · 2,5${nb}Mo").assertExists()
        rule.onNodeWithText("Robot garde tes fichiers sur ce téléphone, chacun chiffré avec une clé qui ne fonctionne que quand le téléphone est déverrouillé.").assertExists()
        rule.onRow("Tout supprimer").assert(hasText("Chaque fichier des Fichiers de Robot, et la clé qui les ouvre"))
        // F10: the reminders, brief and clean-up rows (§6.6), in French.
        rule.onRow("Rappels tirés des fichiers").assert(hasStateDescription("Demander d’abord · Ajouter à ton agenda passe toujours par une carte."))
        rule.onRow("Afficher dans le résumé du matin").assert(hasText("Ce que Robot a classé et tes rappels, seulement quand le téléphone est déverrouillé."))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_fr")
        rule.scrollToText("Rappel de sauvegarde")
        rule.onRow("Rappel de sauvegarde").assert(hasText("Sauvegarde et restauration peut te le rappeler chaque mois"))
        // FX2: the filing rows sit between them now.
        rule.scrollToText("Tout supprimer")
        rule.onRow("Tout supprimer").performClick()
        rule.inDialog("Supprimer tous tes fichiers${fine}?").assertExists()
        rule.inDialog("Annuler").performClick()
        assertEquals(Fmt.bytes(2_520_040, Lang.FR), "2,5${nb}Mo")
        // F12-F13: travel mode and the hidden drawers, in the glossary's words.
        rule.scrollToText("Mode voyage")
        rule.onNodeWithText("Les tiroirs masqués et leurs fichiers restent hors de vue partout jusqu’à ce que tu confirmes avec ton NIP.").assertExists()
        rule.scrollToText("Tiroirs masqués")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun theKeyGoneInFrench() {
        store.add(10, 20)
        store.keystore.keys.clear()
        open("La clé")
        rule.onNodeWithText("La clé des fichiers de Robot n’existe plus${nb}: tes fichiers ne peuvent donc pas être lus. Robot les garde jusqu’à ce que tu " +
            "choisisses${nb}: les restaurer à partir d’une sauvegarde, ou les supprimer.").assertExists()
        rule.onRow("Restaurer à partir d’une sauvegarde").assert(hasText("Dans Sauvegarde et restauration"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_settings_key_gone_fr")
    }

    @Test fun dark() {
        store.add(120_000, 2_400_000, 40)
        rule.show(dark = true) { FilesSettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("3 files · 2.5 MB").assertExists() }.isSuccess }
        rule.shot("files_settings_dark")
    }
}
