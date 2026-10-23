// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.net.Uri
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.memory.HelperMemory
import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryFiling
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryKind
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import app.heyrobot.core.memory.MemorySource
import app.heyrobot.core.memory.MemorySuggestion
import app.heyrobot.sys.FileMemoryVault
import app.heyrobot.sys.MemoryKeyAliases
import app.heyrobot.sys.SoftMemoryKeys
import app.heyrobot.ui.testing.ActionLog
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.inDialog
import app.heyrobot.ui.testing.onRow
import app.heyrobot.ui.testing.scrollToText
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Settings › Memory (0.7) on a real vault with a software key: the items pinned and not, search, an item's dialog (edit,
 * pin up to six, private, delete), suggestions with Keep and Not this, the switches and their defaults, the lock screen's
 * key changed before its switch moves, Export behind its warning, Delete all behind its confirm, and a file Robot can't
 * read with Start over.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class MemorySettingsTest {
    @get:Rule val rule = createComposeRule()
    private val log = ActionLog()
    /** Noon UTC, so the dates read the same in any time zone the tests run in. */
    private val now = LocalDate.of(2026, 10, 11).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
    private val day = 86_400_000L

    /** The page's store over a real vault in a temporary folder; what it was asked, as words. */
    /** [now]: the test's clock (the vault's day-after sweep would otherwise end "I parked on P3" by the real date). */
    private class TestStore(dir: File, now: () -> Long) : MemoryStore {
        val keys = SoftMemoryKeys()
        val vault = FileMemoryVault(dir, keys, now = now)
        @Volatile var settings = MemorySettings()
        /** Written from the store's coroutines while the test waits on it, so a list that is safe across threads. */
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        override fun settings() = settings
        override fun saveSettings(s: MemorySettings) { settings = s; events += "settings" }
        override fun load() = vault.load()
        override fun update(change: (MemoryFile) -> MemoryFile) = vault.update(change)
        override fun deleteAll() = vault.deleteAll().also { events += "deleteAll" }
        override fun startOver() = vault.startOver().also { events += "startOver" }
        override fun rekey(lockScreen: Boolean) = vault.rekey(lockScreen).also { events += "rekey $lockScreen" }
        override fun export(uri: Uri, text: String) = true
        override fun changed() { events += "changed" }
        override fun log(e: VoiceEvent, a: Int, b: Int, c: Int) { events += "log ${e.name} $a $b $c" }
        override fun locked() = false
        override fun readImport(uri: Uri): ByteArray? = null
        override fun deleteImport(uri: Uri) = false
        /** 1.0.2: the vault changed elsewhere (the panel, in the voice process). */
        val changes = MutableSharedFlow<Long>(extraBufferCapacity = 4)
        override fun changes(): Flow<Long> = changes
        fun items(): List<MemoryItem> = (vault.load() as? MemoryLoad.Loaded)?.file?.items.orEmpty()
        /** FX1: the drawers travel mode hides now (none until a test says). */
        @Volatile var hidden: Set<String> = emptySet()
        override fun hiddenDrawers(): (String) -> Boolean { val h = hidden; return { it in h } }
    }

    private val dir: File = Files.createTempDirectory("memory-page").toFile()
    private val store = TestStore(dir) { now }

    @After fun removeTemp() { dir.deleteRecursively() }

    private fun item(id: String, text: String, kind: MemoryKind = MemoryKind.FACT, pinned: Boolean = false, secret: Boolean = false,
        uses: Int = 0, daysAgo: Int = 1, expires: Long? = null) =
        MemoryItem(id, text, kind, secret = secret, pinned = pinned, source = MemorySource.SAID, createdAt = now - daysAgo * day, uses = uses, expiresAt = expires)

    private fun seed(suggestions: List<MemorySuggestion> = emptyList()) {
        store.vault.update {
            MemoryFile(listOf(
                item("m_pref00000001", "Call me Sam", MemoryKind.PREFERENCE, pinned = true, daysAgo = 30),
                item("m_pref00000002", "I prefer metric units", MemoryKind.PREFERENCE, pinned = true, daysAgo = 20),
                item("m_fact00000001", "My sister Julie's birthday is May 3", uses = 4, daysAgo = 10),
                item("m_fact00000002", "Léo has hockey on Tuesdays and Thursdays", daysAgo = 3),
                item("m_secr00000001", "The cottage door code is 4512", secret = true, daysAgo = 2),
                item("m_fact00000003", "I parked on P3", daysAgo = 0, expires = now + 2 * 3_600_000L),
            ), suggestions)
        }
    }

    private fun open() {
        rule.show { MemorySettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, now = { now }) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Use memory").assertExists() }.isSuccess && runCatching { rule.onNodeWithText("Opening…").assertDoesNotExist() }.isSuccess }
    }

    /**
     * 1.0.2, from the phone: two items forgotten from the panel still showed here until the page was opened again. The
     * list now follows the file while the page is open.
     */
    @Test fun theListFollowsWhatThePanelChanges() {
        seed()
        open()
        fun says(n: String) = rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Robot remembers $n.", substring = true).assertExists() }.isSuccess }
        says("6 things")
        // The panel forgets two items (the voice process writes the same vault).
        store.vault.update { f -> f.copy(items = f.items.filter { it.id != "m_fact00000002" && it.id != "m_fact00000003" }) }
        store.changes.tryEmit(1)
        says("4 things")
        rule.onNodeWithText("Léo has hockey on Tuesdays and Thursdays").assertDoesNotExist()
        // And remembers a new one.
        store.vault.update { f -> f.copy(items = f.items + item("m_fact00000009", "The recycling goes out on Mondays", daysAgo = 0)) }
        store.changes.tryEmit(2)
        says("5 things")
        rule.scrollToText("The recycling goes out on Mondays")
        // Reading the page again changes nothing and tells nobody.
        assertEquals(emptyList<String>(), store.events)
    }

    @Test fun theListPinnedFirstThenNewestWithEachItemsState() {
        seed()
        open()
        // The list comes in off the main thread: waited for, as in the dark test.
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Robot remembers 6 things.", substring = true).assertExists() }.isSuccess }
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.scrollToText("Pinned (2 of 6): in every answer")
        rule.onNodeWithText("Call me Sam").assert(hasText("Pinned · saved Sep 11"))
        rule.scrollToText("My sister Julie's birthday is May 3")
        rule.onNodeWithText("My sister Julie's birthday is May 3").assert(hasText("Saved Oct 1 · used in 4 answers"))
        rule.scrollToText("The cottage door code is 4512")
        rule.onNodeWithText("The cottage door code is 4512").assert(hasText("Private · saved Oct 9"))
        rule.scrollToText("I parked on P3")
        rule.onNodeWithText("I parked on P3").assert(hasText("Saved Oct 11 · until Oct 11"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // Reading the page changes nothing and tells nobody.
        assertEquals(emptyList<String>(), store.events)
    }

    @Test fun searchFindsAccentsAndCaseAside() {
        seed()
        open()
        rule.onNode(hasSetTextAction()).performTextInput("leo")
        rule.waitForIdle()
        rule.onNodeWithText("Léo has hockey on Tuesdays and Thursdays").assertExists()
        rule.onNodeWithText("Call me Sam").assertDoesNotExist()
        rule.onNodeWithContentDescription("Clear search").performClick()
        rule.scrollToText("Call me Sam")
    }

    // Robolectric settles with a text field in a dialog only on its default small screen (PreferencesTest).
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun anItemCanBeEditedPinnedMadePrivateOrDeleted() {
        seed()
        open()
        rule.scrollToText("Léo has hockey on Tuesdays and Thursdays")
        rule.onRow("Léo has hockey on Tuesdays and Thursdays").performClick()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNode(hasText("What Robot remembers") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
            .performTextReplacement("Léo has hockey on Tuesdays")
        rule.inDialog("Pinned").assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)).performClick()
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { store.items().any { it.text == "Léo has hockey on Tuesdays" } }
        val leo = store.items().single { it.id == "m_fact00000002" }
        assertEquals("Léo has hockey on Tuesdays", leo.text)
        assertTrue(leo.pinned)
        rule.waitUntil(5_000) { "changed" in store.events }
        // Private: never pinned.
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Léo has hockey on Tuesdays") }.isSuccess }
        rule.onRow("Léo has hockey on Tuesdays").performClick()
        rule.inDialog("Private").performClick()
        rule.inDialog("Pinned").assertIsOff()
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { store.items().single { it.id == "m_fact00000002" }.secret }
        assertFalse(store.items().single { it.id == "m_fact00000002" }.pinned)
        // Delete.
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("My sister Julie's birthday is May 3") }.isSuccess }
        rule.onRow("My sister Julie's birthday is May 3").performClick()
        rule.inDialog("Delete").performClick()
        rule.waitUntil(5_000) { store.items().none { it.id == "m_fact00000001" } }
        rule.waitUntil(5_000) { store.events.any { it == "log MEM_FORGET 1 4 0" } }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun sixPinnedAtMost() {
        store.vault.update { MemoryFile((1..7).map { item("m_pref0000000$it", "Preference number $it", MemoryKind.PREFERENCE, pinned = it <= 6) }) }
        open()
        rule.scrollToText("Preference number 7")
        rule.onRow("Preference number 7").performClick()
        rule.inDialog("Six are pinned: unpin one first", substring = true).assertExists()
        rule.inDialog("Pinned").assertIsNotEnabled()
        rule.inDialog("Cancel").performClick()
    }

    @Test fun suggestionsWaitForATap() {
        seed(listOf(MemorySuggestion("s_000000001aaaaaa", "Julie likes tulips", MemoryKind.FACT, now - day),
            MemorySuggestion("s_000000002bbbbbb", "Bob is vegetarian", MemoryKind.FACT, now - day)))
        open()
        rule.scrollToText("Suggestions (2)")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.onNodeWithContentDescription("Keep: Julie likes tulips").performClick()
        rule.waitUntil(5_000) { store.items().any { it.text == "Julie likes tulips" } }
        assertEquals(MemorySource.SUGGESTED, store.items().single { it.text == "Julie likes tulips" }.source)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithContentDescription("Not this: Bob is vegetarian").assertExists() }.isSuccess }
        rule.onNodeWithContentDescription("Not this: Bob is vegetarian").performClick()
        rule.waitUntil(5_000) { (store.vault.load() as MemoryLoad.Loaded).file.suggestions.isEmpty() }
        assertTrue(store.items().none { it.text == "Bob is vegetarian" })
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Nothing waiting.", substring = true) }.isSuccess }
    }

    @Test fun theSwitchesAndTheirDefaults() {
        seed()
        open()
        rule.onRow("Use memory").assertIsOn()
        rule.scrollToText("Use memory in answers")
        rule.onRow("Use memory in answers").assertIsOn()
        rule.onRow("Suggest things to remember").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "A quiet list here"))
        rule.onRow("Say private items aloud").assertIsOff()
        rule.scrollToText("Share memory with helpers")
        rule.onRow("Use memory on the lock screen").assertIsOff()
        rule.onRow("Share memory with helpers").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Only when I include it"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        // The lock screen: memory sealed again with the lock screen's key, then the switch moves.
        rule.onRow("Use memory on the lock screen").performClick()
        rule.waitUntil(5_000) { store.settings.onLockScreen }
        assertEquals(MemoryKeyAliases.LOCK_SCREEN, store.vault.alias())
        rule.waitUntil(5_000) { "rekey true" in store.events && store.events.any { it == "log MEM_KEY 1 1 0" } }
        // A key change that fails leaves the switch where it was.
        store.keys.busy = true
        rule.onRow("Use memory on the lock screen").performClick()
        rule.waitUntil(5_000) { log.any { it.startsWith("say ") } }
        assertTrue(store.settings.onLockScreen)
        store.keys.busy = false
        rule.scrollToText("Share memory with helpers")
        rule.onRow("Share memory with helpers").performClick()
        rule.inDialog("With helpers I trust").performClick()
        rule.waitUntil(5_000) { store.settings.helpers == HelperMemory.TRUSTED_HELPERS }
        assertEquals(HelperMemory.TRUSTED_HELPERS, store.settings.helpers)
        rule.scrollToText("Use memory")
        rule.onRow("Use memory").performClick()
        rule.waitUntil(5_000) { !store.settings.on }
        assertFalse(store.settings.on)
        rule.scrollToText("Use memory in answers")
        rule.onRow("Use memory in answers").assertIsNotEnabled()
    }

    @Test fun exportWarnsAndDeleteAllConfirms() {
        seed()
        open()
        rule.scrollToText("Export")
        rule.onRow("Export").performClick()
        rule.inDialog("isn't encrypted", substring = true).assertExists()
        rule.inDialog("Cancel").performClick()
        rule.onRow("Delete all").performClick()
        rule.inDialog("Cancel").performClick()
        assertEquals(6, store.items().size)
        rule.onRow("Delete all").performClick()
        rule.inDialog("Delete all").performClick()
        rule.waitUntil(5_000) { "say Memory deleted." in log }
        assertEquals(emptyList<MemoryItem>(), store.items())
        rule.waitUntil(5_000) { store.events.containsAll(listOf("deleteAll", "changed", "log MEM_FORGET 6 6 0")) }
        rule.waitUntil(5_000) { runCatching { rule.scrollToText("Robot remembers nothing yet.", substring = true) }.isSuccess }
    }

    @Test fun aFileRobotCantReadOffersStartOver() {
        seed()
        store.keys.delete(MemoryKeyAliases.UNLOCKED)
        open()
        rule.onNodeWithText("Robot's memory key is gone", substring = true).assertExists()
        rule.onRow("Start over").performClick()
        rule.inDialog("Start over").performClick()
        rule.waitUntil(5_000) { "say Memory is empty now." in log }
        rule.waitUntil(5_000) { "startOver" in store.events }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Robot remembers nothing yet.", substring = true).assertExists() }.isSuccess }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun addSomething() {
        open()
        rule.scrollToText("Add something")
        rule.onRow("Add something").performClick()
        rule.onNode(hasText("What to remember") and SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText)).performTextInput("Julie likes tulips")
        rule.inDialog("Save").performClick()
        rule.waitUntil(5_000) { store.items().isNotEmpty() }
        assertEquals(MemorySource.SETTINGS, store.items().single().source)
        assertTrue(MemoryEdits.sameText("Julie likes tulips", store.items().single().text))
    }

    /** FX3: Export leaves out the filing rules naming a drawer travel mode hides now, as the list does; the rest goes. */
    @Test fun exportLeavesOutRulesNamingAHiddenDrawer() {
        seedRules()
        val all = (store.vault.load() as app.heyrobot.core.memory.MemoryLoad.Loaded).file
        val out = exported(all, { it == "Car" })
        assertEquals(all.items.map { it.id } - "m_rule00000001", out.items.map { it.id })
        assertEquals(all.items, exported(all, { false }).items)
    }

    private fun seedRules() {
        store.vault.update {
            MemoryFile(listOf(
                item("m_fact00000001", "My sister Julie's birthday is May 3", uses = 4, daysAgo = 10),
                item("m_rule00000001", "Esso receipts go in Car.", daysAgo = 2).copy(filing = MemoryFiling(kind = "RECEIPT", merchant = "Esso", drawer = "Car")),
                item("m_rule00000002", "Hydro Québec bills get the tag business.", daysAgo = 1)
                    .copy(source = MemorySource.SETTINGS, filing = MemoryFiling(kind = "BILL", merchant = "Hydro Québec", tags = listOf("business"), learned = true)),
                item("m_rule00000003", "Costco receipts go in Taxes.", daysAgo = 1)
                    .copy(imported = app.heyrobot.core.memory.MemoryImported.CONTENT, filing = MemoryFiling(kind = "RECEIPT", merchant = "Costco", drawer = "taxes")),
            ))
        }
    }

    /**
     * 1.4 F8 (files-1.4-plan.md §4.5): a filing rule says so in the list; its dialog shows its words, whether Robot Files
     * follows it, and only Delete (its words are a template: no edit, no pin, no private switch). Deleting it deletes the rule.
     */
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun aFilingRule() {
        seedRules()
        open()
        rule.scrollToText("Esso receipts go in Car.")
        rule.onNodeWithText("Esso receipts go in Car.").assert(hasText("Filing rule · ", substring = true))
        rule.onNodeWithText("My sister Julie's birthday is May 3").assert(hasText("Saved", substring = true))
        rule.shot("memory_rules")
        rule.onRow("Esso receipts go in Car.").performClick()
        rule.inDialog("Robot Files follows it when it files. Delete it here and Robot stops.").assertExists()
        rule.onNodeWithText("Pinned").assertDoesNotExist()
        rule.onNodeWithText("Private").assertDoesNotExist()
        assertEquals(0, rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size)
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("memory_rule")
        rule.inDialog("Delete").performClick()
        rule.waitUntil(5_000) { store.items().none { it.id == "m_rule00000001" } }
        // An imported rule: shown, not followed.
        rule.scrollToText("Costco receipts go in Taxes.")
        rule.onRow("Costco receipts go in Taxes.").performClick()
        rule.inDialog("Robot doesn't follow it: it isn't from your own words.").assertExists()
    }

    /**
     * FX1 (files-1.4-review.md finding 6): while travel mode hides the drawer "Car", the rule naming it isn't listed or
     * counted (it stays in memory); the others are; shown again once it isn't hidden.
     */
    @Test fun aRuleNamingAHiddenDrawerIsOutOfSight() {
        seedRules()
        store.hidden = setOf("Car")
        open()
        rule.onNodeWithText("Robot remembers 3 things.", substring = true).assertExists()
        rule.scrollToText("Costco receipts go in Taxes.")
        rule.onNodeWithText("Esso receipts go in Car.").assertDoesNotExist()
        rule.onNodeWithText("Hydro Québec bills get the tag business.").assertExists()
        assertEquals(4, store.items().size)
        store.hidden = emptySet()
        store.changes.tryEmit(1)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Esso receipts go in Car.").assertExists() }.isSuccess }
    }

    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun aFilingRuleInFrench() {
        seedRules()
        rule.show { MemorySettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, now = { now }) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Utiliser la mémoire").assertExists() }.isSuccess }
        rule.scrollToText("Esso receipts go in Car.")
        rule.onNodeWithText("Esso receipts go in Car.").assert(hasText("Règle de classement · ", substring = true))
        rule.onRow("Esso receipts go in Car.").performClick()
        rule.inDialog("Les Fichiers de Robot la suivent pour classer. Supprime-la ici et Robot arrête.").assertExists()
        rule.shot("memory_rule_fr")
    }

    @Test fun light() {
        seed()
        open()
        rule.shot("memory_light")
    }

    @Test @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun itemDialog() {
        seed()
        open()
        rule.scrollToText("My sister Julie's birthday is May 3")
        rule.onRow("My sister Julie's birthday is May 3").performClick()
        rule.shot("memory_item")
    }

    /** 1.1: French menus (values-fr-rCA/strings_memory.xml and core's MemoryStrings): the page in Québec French, your words as they are. */
    @Config(qualifiers = "fr-rCA-w411dp-h891dp-xhdpi")
    @Test fun inFrench() {
        seed()
        rule.show { MemorySettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, now = { now }) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Robot retient 6 choses.", substring = true).assertExists() }.isSuccess }
        rule.onNodeWithText("Utiliser la mémoire").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("memory_fr")
        rule.scrollToText("Épinglées (2 sur 6)\u00A0: dans chaque réponse")
        rule.onNodeWithText("Call me Sam").assert(hasText("Épinglé · enregistré le 11 septembre"))
        rule.scrollToText("My sister Julie's birthday is May 3")
        rule.onNodeWithText("My sister Julie's birthday is May 3").assert(hasText("Enregistré le 1er octobre · utilisé dans 4 réponses"))
        rule.scrollToText("Utiliser la mémoire sur l’écran de verrouillage")
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
    }

    @Test fun dark() {
        seed()
        rule.show(dark = true) { MemorySettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}, now = { now }) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("Robot remembers 6 things.", substring = true).assertExists() }.isSuccess }
        rule.scrollToText("Use memory on the lock screen")
        rule.shot("memory_dark")
    }
}
