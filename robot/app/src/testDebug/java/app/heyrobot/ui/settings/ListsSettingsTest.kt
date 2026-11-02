// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsEnvelope
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.NewItem
import app.heyrobot.sys.ListsFollow
import app.heyrobot.sys.ListsKeyAliases
import app.heyrobot.sys.ListsVault
import app.heyrobot.sys.SoftListsKeys
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.security.SecureRandom

/**
 * Settings › Lists (lists-plan.md §4.5, §7.5; LS2) on a real vault with Lists' keys in software: what the lists take,
 * Delete all lists behind its dialog (refused when the phone locked meanwhile), the two lock switches changing the seal
 * and the journal before they're saved, the phone locked, the key gone with Restore and Start over, damaged and newer
 * lists; in English and Québec French, with goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ListsSettingsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })
    private val log = ActionLog()
    private val nb = ' '
    private val fine = ' '

    /** The page's store over a real vault. */
    private inner class TestStore : ListsStore {
        val keys = SoftListsKeys()
        var locked = false
        /** Locked from the next call on (the phone locking while a dialog shows). */
        var lockAtTap = false
        /** lists_settings.json is a newer Robot's. */
        var newer = false
        var settings = ListsSettings()
        val dir = File(tmp.root, "lists")
        val vault = ListsVault(dir, keys, settings = { settings }, someoneElse = { false }, locked = { locked })
        val events = ArrayList<String>()

        override fun load() = vault.load(firstUse = false)
        override fun settings() = settings
        override fun canSave() = !newer
        override fun save(change: (ListsSettings) -> ListsSettings): Boolean {
            settings = change(settings)
            events += "save"
            return true
        }
        /** What follow answers instead of the vault (null: the vault's). */
        var followAnswer: ListsFollow? = null
        override fun follow(s: ListsSettings): ListsFollow = (followAnswer ?: vault.follow(s)).also { events += "follow ${it::class.simpleName}" }
        override fun deleteAll() = vault.deleteAll().also { events += "deleteAll $it" }
        override fun startOver() = vault.startOver().also { events += "startOver $it" }
        override fun locked(): Boolean {
            if (lockAtTap) locked = true
            return locked
        }
        override fun changed() { events += "changed" }

        /** The first-use lists, and [items] on Shopping. */
        fun seed(vararg items: String) {
            val shop = (vault.load() as ListsLoad.Loaded).value.withRole(ListRole.SHOPPING)!!.id
            if (items.isNotEmpty()) vault.update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, shop, items.map { NewItem(it) }, who) { ListsId.new(SecureRandom()) } }
        }
        val listsFile get() = File(dir, ListsEnvelope.FILE)
    }

    private val store by lazy { TestStore() }

    private fun open(opened: String = "Storage") {
        rule.show { ListsSettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText(opened, substring = true).assertExists() }.isSuccess }
    }

    private fun says(text: String) = rule.waitUntil(5_000) { log.lastOrNull() == "say $text" }

    @Test fun whatTheListsTakeThenDeleteThemAll() {
        store.seed("oat milk", "eggs", "bread")
        open()
        rule.onNodeWithText("2 lists · 3 items").assertExists()
        rule.onNodeWithText("Lists you keep by voice, such as “add oat milk to the shopping list”.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_settings")
        rule.onRow("Delete all lists").assert(hasText("Every list and item")).performClick()
        rule.inDialog("Every list and item goes, with anything added while the phone was locked. This can't be undone.").assertExists()
        rule.shot("lists_settings_delete")
        rule.inDialog("Delete all lists").performClick()
        says("All lists deleted.")
        assertEquals(listOf("deleteAll true", "changed"), store.events)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("No lists yet").assertExists() }.isSuccess }
        rule.onNodeWithText("Delete all lists").assertDoesNotExist()
    }

    @Test fun nothingYetIsNoListsAndNothingIsMade() {
        open("No lists yet")
        rule.onNodeWithText("Delete all lists").assertDoesNotExist()
        assertFalse(store.listsFile.exists())
    }

    @Test fun cancelDeletesNothing() {
        store.seed("eggs")
        open()
        rule.onRow("Delete all lists").performClick()
        rule.inDialog("Cancel").performClick()
        assertTrue(store.events.isEmpty())
        rule.onNodeWithText("2 lists · 1 item").assertExists()
    }

    /** The phone locked while the dialog showed: nothing is deleted, and the page says why. */
    @Test fun deleteAllWaitsForTheUnlock() {
        store.seed("eggs")
        open()
        rule.onRow("Delete all lists").performClick()
        store.lockAtTap = true
        rule.inDialog("Delete all lists").performClick()
        says("Unlock your phone to change your lists.")
        assertTrue(store.events.isEmpty())
        store.locked = false
        store.lockAtTap = false
        assertEquals(1, (store.vault.load() as ListsLoad.Loaded).value.withRole(ListRole.SHOPPING)!!.live.size)
    }

    @Test fun theLockedPhoneIsNotNow() {
        store.seed("eggs", "bread")
        store.locked = true
        open("just now")
        rule.onNodeWithText("Robot couldn't open your lists just now. Try again in a moment.").assertExists()
        // Nothing about the lists, not even how many; nothing to delete.
        rule.onNodeWithText("2 lists · ", substring = true).assertDoesNotExist()
        rule.onNodeWithText("Delete all lists").assertDoesNotExist()
        rule.onNodeWithText("Start over").assertDoesNotExist()
        store.locked = false
        rule.onRow("Try again").performClick()
        rule.waitUntil(5_000) { runCatching { rule.onRow("Delete all lists").assertExists() }.isSuccess }
    }

    @Test fun theLockSwitchesChangeTheSealFirstThenAreSaved() {
        store.seed("eggs")
        open()
        rule.scrollToText("Hear and tick on the lock screen")
        rule.onRow("Hear and tick on the lock screen").assert(hasText("Anyone who can talk to your locked phone can hear your lists and tick items")).performClick()
        rule.waitUntil(5_000) { store.settings.lockScreen }
        assertEquals(listOf("follow Done", "save", "changed"), store.events)
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.alias(store.listsFile.readBytes()))
        rule.onRow("Add to lists while locked").assert(hasText("Items you add while the phone is locked wait, encrypted, until you unlock it")).performClick()
        rule.waitUntil(5_000) { !store.settings.addLocked }
        rule.onRow("Hear and tick on the lock screen").performClick()
        rule.waitUntil(5_000) { !store.settings.lockScreen }
        // Both off: back under robot_lists_v1, the journal and the lock screen's key gone.
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(store.listsFile.readBytes()))
        assertFalse(File(store.dir, ListsEnvelope.JOURNAL).exists())
        assertNull(store.keys.keys[ListsKeyAliases.LOCK_SCREEN])
        rule.onNodeWithText("2 lists · 1 item").assertExists()
    }

    @Test fun aSwitchWhoseSealCantChangeStaysAsItWas() {
        store.seed("eggs")
        open()
        store.keys.busy = true
        store.vault.drop()
        rule.scrollToText("Hear and tick on the lock screen")
        rule.onRow("Hear and tick on the lock screen").performClick()
        says("Robot couldn't change your lists' key. Nothing changed.")
        assertFalse(store.settings.lockScreen)
        assertEquals(listOf("follow Problem"), store.events)
    }

    @Test fun aNewerSettingsFileIsNeverWrittenOverAndTheSealStays() {
        store.seed("eggs")
        store.newer = true
        open()
        rule.scrollToText("Hear and tick on the lock screen")
        rule.onRow("Hear and tick on the lock screen").performClick()
        says("Robot couldn't save this. Nothing changed.")
        assertTrue(store.events.isEmpty())
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(store.listsFile.readBytes()))
    }

    @Test fun listsOffLeavesTheLockSwitchesAsTheyAre() {
        store.seed("eggs")
        open()
        rule.onRow("Use lists").performClick()
        rule.waitUntil(5_000) { !store.settings.on }
        rule.scrollToText("Add to lists while locked")
        rule.onRow("Add to lists while locked").assertIsNotEnabled()
        rule.onRow("Hear and tick on the lock screen").assertIsNotEnabled()
        assertEquals(listOf("save"), store.events)
    }

    @Test fun theKeyGoneOffersRestoreStartOverAndDelete() {
        store.seed("eggs", "bread")
        store.keys.keys.remove(ListsKeyAliases.UNLOCKED)
        store.vault.drop()
        open("key is gone")
        rule.onNodeWithText("Robot's lists key is gone, so your lists can't be read.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_settings_key_gone")
        rule.onRow("Restore from a backup").assert(hasText("In Back up and restore")).performClick()
        assertEquals("openPage ${Page.Settings(SettingsRoutes.BACKUP)}", log.last())
        rule.onRow("Delete all lists").assertExists()
        rule.onRow("Start over").assert(hasText("The file is kept aside as lists.enc.bad")).performClick()
        rule.inDialog("Robot keeps the lists it can't read aside, as lists.enc.bad, and starts again with Shopping and To-do.").assertExists()
        rule.inDialog("Start over").performClick()
        says("Your lists start again with Shopping and To-do.")
        assertTrue(File(store.dir, ListsVault.BAD).isFile)
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("2 lists · 0 items").assertExists() }.isSuccess }
    }

    @Test fun damagedListsStartOver() {
        store.seed("eggs")
        store.listsFile.writeBytes(store.listsFile.readBytes().also { it[it.size - 2] = (it[it.size - 2] + 1).toByte() })
        store.vault.drop()
        open("damaged")
        rule.onNodeWithText("Robot couldn't read your lists. The file is damaged.").assertExists()
        rule.onNodeWithText("Restore from a backup").assertDoesNotExist()
        rule.onRow("Start over").assertExists()
        rule.onRow("Delete all lists").assertExists()
    }

    @Test fun aNewerRobotsLists() {
        store.seed("eggs")
        store.listsFile.writeBytes(store.listsFile.readBytes().also { it[4] = 2 })
        store.vault.drop()
        open("newer Robot")
        rule.onNodeWithText("Your lists are from a newer Robot, so this one can't read or change them. Update Robot, or start over.").assertExists()
        rule.onRow("Start over").assertExists()
    }

    @Test fun theFooterSaysWhatStaysOnThePhone() {
        open("No lists yet")
        rule.scrollToText("Your lists are encrypted", substring = true)
        rule.onNodeWithText("Your lists are encrypted on this phone and never sent anywhere, except a list you share as text. History keeps what you say to Robot, " +
            "as it does for everything you ask, but not what Robot reads back from a list. A widget shows its list to your home-screen app.").assertExists()
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun inFrench() {
        store.seed("lait d’avoine", "œufs", "pain")
        open("Stockage")
        rule.onNodeWithText("2 listes · 3 éléments").assertExists()
        rule.onNodeWithText("Des listes que tu tiens à la voix, comme «${nb}ajoute du lait d’avoine à la liste d’épicerie${nb}».").assertExists()
        rule.onRow("Utiliser les listes").assertExists()
        rule.onRow("Supprimer toutes les listes").assert(hasText("Chaque liste et chaque élément"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_settings_fr")
        rule.scrollToText("Entendre et cocher sur l’écran de verrouillage")
        rule.onRow("Entendre et cocher sur l’écran de verrouillage")
            .assert(hasText("Quiconque peut parler à ton téléphone verrouillé peut entendre tes listes et cocher des éléments"))
        rule.onRow("Ajouter aux listes quand le téléphone est verrouillé")
            .assert(hasText("Les éléments que tu ajoutes quand le téléphone est verrouillé attendent, chiffrés, que tu le déverrouilles"))
        rule.scrollToText("Supprimer toutes les listes")
        rule.onRow("Supprimer toutes les listes").performClick()
        rule.inDialog("Supprimer toutes les listes${fine}?").assertExists()
        rule.inDialog("Annuler").performClick()
        assertTrue(store.events.isEmpty())
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun theKeyGoneInFrench() {
        store.seed("œufs")
        store.keys.keys.remove(ListsKeyAliases.UNLOCKED)
        store.vault.drop()
        open("La clé")
        rule.onNodeWithText("La clé des listes de Robot n’existe plus${nb}: tes listes ne peuvent donc pas être lues.").assertExists()
        rule.onRow("Restaurer à partir d’une sauvegarde").assert(hasText("Dans Sauvegarde et restauration"))
        rule.onRow("Recommencer").assert(hasText("Le fichier est mis de côté sous le nom lists.enc.bad"))
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("lists_settings_key_gone_fr")
    }

    @Test fun dark() {
        store.seed("oat milk", "eggs", "bread")
        rule.show(dark = true) { ListsSettingsScreen(store, log.actions, SnackbarHostState(), onBack = {}) }
        rule.waitUntil(5_000) { runCatching { rule.onNodeWithText("2 lists · 3 items").assertExists() }.isSuccess }
        rule.shot("lists_settings_dark")
    }

    /** An add said while locked that can't fit yet keeps "Add to lists while locked" on (ListsVault.follow's Waiting). */
    @Test fun aWaitingAddKeepsAddWhileLockedOn() {
        store.seed("eggs")
        store.followAnswer = ListsFollow.Waiting(1)
        open()
        rule.scrollToText("Add to lists while locked")
        rule.onRow("Add to lists while locked").performClick()
        says("Items you added while the phone was locked are waiting for room on a full list. Clear its done items, then try again.")
        assertTrue(store.settings.addLocked)
        assertEquals(listOf("follow Waiting"), store.events)
    }
}
