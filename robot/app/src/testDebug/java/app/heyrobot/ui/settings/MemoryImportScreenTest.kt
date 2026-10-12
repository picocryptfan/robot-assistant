package app.heyrobot.ui.settings

import android.app.Activity
import android.content.ContextWrapper
import android.net.Uri
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import app.heyrobot.core.RLog
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.memory.MemImportCode
import app.heyrobot.core.memory.MemoryCodec
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryImported
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.sys.FileMemoryVault
import app.heyrobot.sys.SoftMemoryKeys
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Settings › Memory › Import (1.0) on a real vault with a software key, the file picker answered by a registry of the
 * test's own: the row, refused on the lock screen and offered to turn Memory on first; the preview (counts, duplicates and
 * secrets skipped, private items masked until tapped, the tick box off, FLAG_SECURE, light, dark and 200 % font goldens);
 * Import writing content by default and yours when ticked, the file's own content marks kept either way; "Added N
 * memories." with the offer to delete the file; the Imported label and This is mine; and no item's words in any log.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MemoryImportScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val log = ActionLog()
    private val now = LocalDate.of(2026, 10, 11).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = 86_400_000L
    private val picked = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2Frobot-memory.json")

    private class TestStore(dir: File, now: () -> Long) : MemoryStore {
        val vault = FileMemoryVault(dir, SoftMemoryKeys(), now = now)
        var settings = MemorySettings()
        var locked = false
        var file: ByteArray? = null
        val deleted = ArrayList<Uri>()
        /** Written from the page's IO coroutines, read by the test. */
        val events: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())
        override fun settings() = settings
        override fun saveSettings(s: MemorySettings) { settings = s; events += "settings" }
        override fun load() = vault.load()
        override fun update(change: (MemoryFile) -> MemoryFile) = vault.update(change)
        override fun deleteAll() = vault.deleteAll()
        override fun startOver() = vault.startOver()
        override fun rekey(lockScreen: Boolean) = vault.rekey(lockScreen)
        override fun export(uri: Uri, text: String) = true
        override fun changed() { events += "changed" }
        // As the phone's: the voice log's file, codes and counts.
        override fun log(e: VoiceEvent, a: Int, b: Int, c: Int) { events += "log ${e.name} $a $b $c"; VoiceLog.add(e, a, b, c) }
        override fun locked() = locked
        override fun readImport(uri: Uri) = file
        override fun deleteImport(uri: Uri) = true.also { deleted += uri }
        fun items(): List<MemoryItem> = (vault.load() as? MemoryLoad.Loaded)?.file?.items.orEmpty()
    }

    private val store = TestStore(Files.createTempDirectory("memory-import").toFile()) { now }

    /** What the picker was asked to open (its MIME types), answered with [picked]. */
    private val launched = ArrayList<List<String>>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            @Suppress("UNCHECKED_CAST")
            launched += (input as Array<String>).toList()
            dispatchResult(requestCode, picked)
        }
    }
    private val owner = object : ActivityResultRegistryOwner { override val activityResultRegistry = registry }
    private var activity: Activity? = null

    @After fun tearDown() {
        RLog.sink = null
        VoiceLog.resetForTests()
    }

    /** 0.9's and 1.0's Export: MemoryCodec's JSON, indented by Android's org.json. */
    private fun export(items: List<MemoryItem>): ByteArray = JSONObject(MemoryCodec.toJson(MemoryFile(items))).toString(2).toByteArray()

    private fun item(id: String, text: String, kind: MemoryKind = MemoryKind.FACT, pinned: Boolean = false, secret: Boolean = false,
        source: MemorySource = MemorySource.SAID, label: String? = null, daysAgo: Int = 30) =
        MemoryItem(id, text, kind, secret = secret, pinned = pinned, source = source, sourceLabel = label, createdAt = now - daysAgo * day)

    /** A 0.9 export: two of yours, a door code, a private item, one 0.9 kept from a message, and one memory already has. */
    private val zeroNine = listOf(
        item("m_00mfqe0w0ab12cd", "My sister Julie's birthday is May 3", daysAgo = 400),
        item("m_00mfqe0w0ef34gh", "Call me Sam", MemoryKind.PREFERENCE, pinned = true, source = MemorySource.TYPED, daysAgo = 300),
        item("m_00mfqe0w0ij56kl", "The cottage door code is 4512", secret = true, source = MemorySource.SETTINGS, daysAgo = 200),
        item("m_00mfqe0w0uv12wx", "My therapist is Dr. Martin", secret = true, source = MemorySource.SETTINGS, daysAgo = 100),
        item("m_00mfqe0w0op12qr", "Bob's flight lands Friday at six", source = MemorySource.CONTENT, label = "a message from Bob", daysAgo = 20),
        item("m_00mfqe0w0yz34ab", "Léo has hockey on Tuesdays", daysAgo = 10),
    )

    private fun seed() {
        store.vault.update { MemoryFile(listOf(item("m_have00000001", "Léo has hockey on Tuesdays.", daysAgo = 2))) }
    }

    private fun open(dark: Boolean = false) {
        rule.show(dark = dark) {
            activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MemorySettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, now = { now })
            }
        }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Use memory").assertExists() }.isSuccess && runCatching { rule.onNodeWithText("Opening…").assertDoesNotExist() }.isSuccess }
    }

    /** Settings › Memory › Import, the file picked: the preview, once it has read the file. */
    private fun preview() {
        rule.scrollToText("Import")
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Import memories").assertExists() }.isSuccess }
    }

    private fun secure(): Boolean = (activity!!.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0

    @Test fun thePreviewCountsMasksAndIsSecure() {
        seed()
        store.file = export(zeroNine)
        open()
        preview()
        // Android's picker, for JSON and any file (some apps save .json as text/plain or octet-stream).
        assertEquals(listOf(listOf("application/json", "*/*")), launched)
        assertTrue(secure())
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("In this file") and hasText("6")).assertExists()
        rule.onNode(hasText("Already in Memory, skipped") and hasText("1")).assertExists()
        rule.onNode(hasText("Hold a password, a PIN, a code or a card number, skipped") and hasText("1")).assertExists()
        rule.onNode(hasText("To add") and hasText("4") and hasText("1 private", substring = true)).assertExists()
        // The tick box, off.
        rule.onRow("These are my own memories, from my own export").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).assertIsOff()
        // The items to add, a private one masked until tapped; the secret never shows.
        rule.scrollToText("My sister Julie's birthday is May 3")
        rule.scrollToText("Bob's flight lands Friday at six")
        rule.onNode(hasText("Bob's flight lands Friday at six") and hasText("From something Robot read: stays so", substring = true)).assertExists()
        rule.scrollToText("Private: tap to show")
        rule.onNodeWithText("My therapist is Dr. Martin").assertDoesNotExist()
        rule.onRow("Private: tap to show").performClick()
        rule.onNodeWithText("My therapist is Dr. Martin").assertExists()
        rule.onRow("My therapist is Dr. Martin").performClick()
        rule.onNodeWithText("My therapist is Dr. Martin").assertDoesNotExist()
        rule.onNodeWithText("4512", substring = true).assertDoesNotExist()
        // Nothing written by looking.
        assertEquals(1, store.items().size)
        // Cancel: back to Memory, nothing written; the page is still secure.
        rule.onRow("Cancel").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Import memories").assertDoesNotExist() }.isSuccess }
        assertEquals(1, store.items().size)
        assertTrue(secure())
        assertFalse(store.events.any { it.startsWith("log MEM_IMPORT") })
    }

    @Test fun importsAsContentByDefault() {
        seed()
        store.file = export(zeroNine)
        open()
        preview()
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { runCatching { rule.inDialog("Added 4 memories.").assertExists() }.isSuccess }
        val added = store.items().filter { it.id != "m_have00000001" }
        assertEquals(listOf("My sister Julie's birthday is May 3", "Call me Sam", "My therapist is Dr. Martin", "Bob's flight lands Friday at six"), added.map { it.text })
        assertTrue(added.all { it.imported == MemoryImported.CONTENT && it.tainted })
        // Ids are Robot's own; dates, private and pinned come along.
        assertTrue(added.none { it.id in zeroNine.map { z -> z.id } })
        assertEquals(now - 400 * day, added[0].createdAt)
        assertTrue(added[1].pinned)
        assertTrue(added[2].secret)
        assertTrue("changed" in store.events)
        rule.waitUntil(5_000) { "log MEM_IMPORT 4 ${MemImportCode.CONTENT.id} 2" in store.events }
        // The file isn't encrypted: Robot offers to delete it.
        rule.inDialog("isn't encrypted", substring = true).assertExists()
        rule.inDialog("Delete the file").performClick()
        rule.waitUntil(5_000) { "say The file is deleted." in log }
        assertEquals(listOf(picked), store.deleted)
        // The Imported label in Memory's list.
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("My sister Julie's birthday is May 3") }.isSuccess }
        rule.onNode(hasText("My sister Julie's birthday is May 3") and hasText("Imported")).assertExists()
        rule.onNode(hasText("Léo has hockey on Tuesdays.") and hasText("Imported")).assertDoesNotExist()
    }

    @Test fun theTickImportsThemAsYoursButAFilesContentMarkStays() {
        store.file = export(zeroNine)
        open()
        preview()
        rule.onRow("These are my own memories, from my own export").performClick()
        rule.onRow("These are my own memories, from my own export").assertIsOn()
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { runCatching { rule.inDialog("Added 5 memories.").assertExists() }.isSuccess }
        val byText = store.items().associateBy { it.text }
        for (t in listOf("My sister Julie's birthday is May 3", "Call me Sam", "My therapist is Dr. Martin", "Léo has hockey on Tuesdays")) {
            assertEquals(t, MemoryImported.YOURS, byText.getValue(t).imported)
            assertFalse(t, byText.getValue(t).tainted)
        }
        assertTrue(byText.getValue("Bob's flight lands Friday at six").tainted)
        rule.waitUntil(5_000) { "log MEM_IMPORT 5 ${MemImportCode.YOURS.id} 1" in store.events }
        rule.inDialog("Not now").performClick()
        assertEquals(emptyList<Uri>(), store.deleted)
    }

    // Robolectric settles with a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun thisIsMineClearsTheLabelAndTheImportsMark() {
        store.vault.update { MemoryFile(listOf(
            item("m_imported0001", "Julie likes tulips").copy(imported = MemoryImported.CONTENT),
            item("m_imported0002", "Bob's flight lands Friday at six", source = MemorySource.CONTENT, label = "a message from Bob").copy(imported = MemoryImported.CONTENT),
        )) }
        open()
        rule.scrollToText("Julie likes tulips")
        rule.onRow("Julie likes tulips").performClick()
        rule.inDialog("Imported: counts as something Robot read until you say it's yours").assertExists()
        rule.inDialog("Robot treats it as your own words", substring = true).assertExists()
        rule.inDialog("This is mine").performClick()
        rule.waitUntil(5_000) { store.items().first { it.id == "m_imported0001" }.imported == null }
        assertFalse(store.items().first { it.id == "m_imported0001" }.tainted)
        rule.waitUntil(5_000) { "say Marked as yours." in log }
        // Kept from something Robot read: the label goes, the content mark stays.
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Bob's flight lands Friday at six") }.isSuccess }
        rule.onRow("Bob's flight lands Friday at six").performClick()
        rule.inDialog("it still counts as something Robot read", substring = true).assertExists()
        rule.inDialog("This is mine").performClick()
        rule.waitUntil(5_000) { store.items().first { it.id == "m_imported0002" }.imported == null }
        assertTrue(store.items().first { it.id == "m_imported0002" }.tainted)
    }

    @Test fun neverOnTheLockScreen() {
        store.file = export(zeroNine)
        store.locked = true
        open()
        rule.scrollToText("Import")
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { "say Unlock first: memory is imported only on an unlocked phone." in log }
        assertEquals(emptyList<List<String>>(), launched)
        rule.waitUntil(5_000) { "log MEM_IMPORT 0 ${MemImportCode.LOCKED.id} 0" in store.events }
        // Locked while the preview showed: Import refuses, nothing is written.
        store.locked = false
        preview()
        store.locked = true
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { log.count { it == "say Unlock first: memory is imported only on an unlocked phone." } == 2 }
        assertEquals(emptyList<MemoryItem>(), store.items())
        rule.onNodeWithText("Import memories").assertExists()
    }

    @Test fun withMemoryOffItOffersToTurnItOnFirst() {
        store.settings = MemorySettings(on = false)
        store.file = export(zeroNine)
        open()
        rule.scrollToText("Import")
        rule.onRow("Import").performClick()
        rule.inDialog("Memory is off").assertExists()
        rule.inDialog("Cancel").performClick()
        assertEquals(emptyList<List<String>>(), launched)
        assertFalse(store.settings.on)
        rule.onRow("Import").performClick()
        rule.inDialog("Turn on").performClick()
        assertTrue(store.settings.on)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Import memories").assertExists() }.isSuccess }
        assertEquals(1, launched.size)
    }

    @Test fun aWrongFileIsRefusedWithAReason() {
        open()
        for ((bytes, said) in listOf(
            "BEGIN:VCARD\nVERSION:3.0\nFN:Julie\nEND:VCARD\n".toByteArray() to "This isn't a Robot memory export.",
            "{\"v\":1,\"items\":[{\"text\":\"cut short\"}".toByteArray() to "This isn't a Robot memory export.",
            "{\"v\":2,\"items\":[]}".toByteArray() to "A newer version of Robot made this export. Update Robot, then import it.",
            ByteArray(app.heyrobot.core.memory.MemoryImport.MAX_BYTES + 1) to "This file is over 5 MB, so it isn't a Robot memory export.",
            "{\"v\":1,\"items\":[]}".toByteArray() to "This export holds no memories.",
            null to "That file couldn't be read.",
        )) {
            store.file = bytes
            rule.scrollToText("Import")
            rule.onRow("Import").performClick()
            rule.waitForIdle()
            rule.waitUntil(5_000) { "say $said" in log }
            rule.onNodeWithText("Import memories").assertDoesNotExist()
            log.clear()
        }
        assertEquals(emptyList<MemoryItem>(), store.items())
    }

    @Test fun noItemsWordsReachALog() {
        val marker = "zq9marker"
        val logged = ArrayList<String>()
        RLog.sink = { logged += it }
        ShadowLog.clear()
        val voiceLog = File(Files.createTempDirectory("voicelog").toFile(), "voice_log.txt")
        VoiceLog.resetForTests()
        VoiceLog.init(voiceLog)
        store.file = export(listOf(
            item("m_00mfqe0w0ab12cd", "$marker likes tulips"),
            item("m_00mfqe0w0ij56kl", "The $marker door code is 4512", secret = true),
            item("m_00mfqe0w0uv12wx", "$marker sees Dr. Martin", secret = true),
            item("m_00mfqe0w0op12qr", "$marker lands Friday", source = MemorySource.CONTENT, label = "a message from $marker"),
        ))
        open()
        preview()
        rule.scrollToText("Private: tap to show")
        rule.onRow("Private: tap to show").performClick()
        rule.onRow("These are my own memories, from my own export").performClick()
        rule.onRow("Import").performClick()
        rule.waitUntil(5_000) { runCatching { rule.inDialog("Added 3 memories.").assertExists() }.isSuccess }
        rule.inDialog("Delete the file").performClick()
        rule.waitUntil(5_000) { "say The file is deleted." in log }
        // A damaged file with the marker in it, refused.
        store.file = "{\"v\":1,\"items\":[{\"text\":\"$marker\"}".toByteArray()
        rule.scrollToText("Import")
        rule.onRow("Import").performClick()
        rule.waitForIdle()
        rule.waitUntil(5_000) { "say This isn't a Robot memory export." in log }
        rule.waitUntil(5_000) { store.events.count { it.startsWith("log MEM_IMPORT") } == 2 }
        VoiceLog.flush()
        val entries = VoiceLog.entries()
        assertTrue(entries.any { it.event == VoiceEvent.MEM_IMPORT && it.a == 3 })
        val texts = logged + ShadowLog.getLogs().map { "${it.tag} ${it.msg} ${it.throwable}" } + voiceLog.readText() +
            VoiceLogCodec.encode(entries) + entries.map { VoiceLogText.describe(it) } + store.events
        assertEquals(emptyList<String>(), texts.filter { it.contains(marker) })
        // And on disk, memory is encrypted as ever.
        assertNull(store.vault.file.readBytes().toString(Charsets.ISO_8859_1).takeIf { it.contains(marker) })
    }

    @Test fun light() {
        seed()
        store.file = export(zeroNine)
        open()
        preview()
        rule.shot("memory_import_light")
    }

    @Test fun dark() {
        seed()
        store.file = export(zeroNine)
        open(dark = true)
        preview()
        rule.shot("memory_import_dark")
    }

    @Test @Config(fontScale = 2f)
    fun font200() {
        seed()
        store.file = export(zeroNine)
        open()
        preview()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("memory_import_font200")
    }

    @Test fun theImportButtonWaitsWhenThereIsNothingToAdd() {
        store.vault.update { MemoryFile(zeroNine.filter { !it.secret }) }
        store.file = export(zeroNine.filter { !it.secret })
        open()
        preview()
        rule.onNodeWithText("Nothing to add.").assertExists()
        rule.onRow("Import").assertIsNotEnabled()
    }
}
