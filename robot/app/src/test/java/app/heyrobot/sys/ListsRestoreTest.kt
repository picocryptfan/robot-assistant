// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.backup.RestoreMerge
import app.heyrobot.core.backup.RestoreMode
import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ListFrom
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsEnvelope
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsMergeResult
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.ListsWho
import app.heyrobot.core.lists.NewItem
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom

/**
 * lists-plan.md §6 (LS7): a restore's lists through the vault, with software keys standing in for the Keystore's. Sealed
 * again under this phone's key (never the old phone's), Add through ListsMerge and Replace, someone else's apart, the
 * journal's waiting adds kept, lists whose key is gone kept aside, damaged or newer never written over, nothing while
 * locked, and the restored lock switches followed by the seal afterwards.
 */
class ListsRestoreTest {
    private val dir: File = Files.createTempDirectory("lists-restore").toFile()

    @After fun removeTemp() { dir.deleteRecursively() }

    private val keys = SoftListsKeys()
    private var settings = ListsSettings()
    private var phoneLocked = false
    private var writes = 0
    private val random = SecureRandom()
    private val ids: () -> ListsId = { ListsId.new(random) }
    private val roleName: (ListRole) -> String = { if (it == ListRole.SHOPPING) "Shopping" else "To-do" }

    private fun vault() = ListsVault(dir, keys, settings = { settings }, someoneElse = { false }, locked = { phoneLocked }, now = { T }, changed = { writes++ })
    private val vault = vault()
    private val listsFile get() = File(dir, ListsEnvelope.FILE)

    private fun loaded(l: ListsLoad<ListsFile> = vault().load(firstUse = false)): ListsFile = (l as? ListsLoad.Loaded)?.value ?: throw AssertionError("not loaded: $l")

    /** The old phone's lists, as a backup carries them: Shopping with two items, and Camping. */
    private fun backup(): ListsFile {
        val who = ListsWho(ItemBy.YOU, ItemHow.VOICE, T - 1_000)
        var f = ListsEdits.firstUse(T - 2_000, ids)
        f = (ListsEdits.add(f, f.withRole(ListRole.SHOPPING)!!.id, listOf(NewItem("oat milk"), NewItem("eggs")), who, ids) as ListsEdit.Done).file
        val camping = ListsEdits.make(f, "Camping", null, who, ids) as ListsEdit.Done
        f = (ListsEdits.add(camping.file, camping.list, listOf(NewItem("tent")), who, ids) as ListsEdit.Done).file
        return f
    }

    private fun restore(file: ListsFile, mode: RestoreMode, mine: Boolean = true) =
        vault.restore { have, ids -> RestoreMerge.lists(have, file, mode, mine, ids, roleName) }

    @Test fun yourListsAreAddedAndSealedUnderThisPhonesKey() {
        // This phone: its own Shopping with apples.
        val here = loaded(vault.load())
        vault.update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, here.withRole(ListRole.SHOPPING)!!.id, listOf(NewItem("apples")), who, ids) }
        writes = 0
        val b = backup()
        val r = restore(b, RestoreMode.ADD)
        assertTrue(r.toString(), r is ListsRestore.Done)
        assertTrue(writes > 0)
        // Sealed under this phone's own key, read by a fresh vault (another process).
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        val f = loaded()
        assertEquals(listOf("apples", "oat milk", "eggs"), f.withRole(ListRole.SHOPPING)!!.live.map { it.text })
        // Restoring the same backup again changes nothing.
        val before = loaded()
        restore(b, RestoreMode.ADD)
        assertEquals(before, loaded())
    }

    @Test fun replaceTakesTheBackupsAndSomeoneElsesStayApart() {
        loaded(vault.load())
        val b = backup()
        assertTrue(restore(b, RestoreMode.REPLACE) is ListsRestore.Done)
        assertEquals(b.lists.map { it.id }, loaded().lists.map { it.id })
        // Someone else's: theirs beside yours, marked, no role, every item by "backup".
        assertTrue(restore(backup(), RestoreMode.ADD, mine = false) is ListsRestore.Done)
        val f = loaded()
        val theirs = f.lists.filter { it.from == ListFrom.BACKUP }
        assertEquals(b.lists.size, theirs.size)
        assertTrue(theirs.all { it.role == null && it.items.all { i -> i.by == ItemBy.BACKUP } })
        assertEquals(b.withRole(ListRole.SHOPPING)!!.id, f.withRole(ListRole.SHOPPING)!!.id)
    }

    @Test fun anAddSaidWhileLockedIsThisPhonesAndAddKeepsIt() {
        val b = backup()
        // Your own lists here are the backup's (the same ids); an add said while locked waits for Shopping.
        assertTrue(restore(b, RestoreMode.REPLACE) is ListsRestore.Done)
        fun sayLocked(word: String) {
            phoneLocked = true
            keys.locked = true
            assertTrue(vault.addLocked(b.withRole(ListRole.SHOPPING)!!.id, listOf(NewItem(word))) is LockedAdd.Ok)
            phoneLocked = false
            keys.locked = false
        }
        sayLocked("bread")
        // Add: merged into this phone's lists first, then the backup's: kept, by "locked".
        assertTrue(restore(b, RestoreMode.ADD) is ListsRestore.Done)
        val shopping = loaded().withRole(ListRole.SHOPPING)!!
        assertEquals(listOf("oat milk", "eggs", "bread"), shopping.live.map { it.text })
        assertEquals(ItemBy.LOCKED, shopping.live.last().by)
        // Replace: this phone's lists go first, a waiting add with them (it is merged before, never after).
        sayLocked("jam")
        assertTrue(restore(b, RestoreMode.REPLACE) is ListsRestore.Done)
        assertEquals(listOf("oat milk", "eggs"), loaded().withRole(ListRole.SHOPPING)!!.live.map { it.text })
    }

    @Test fun nothingWhileLockedAndDamagedOrNewerNeverWrittenOver() {
        loaded(vault.load())
        val good = listsFile.readBytes()
        phoneLocked = true
        assertEquals(ListsRestore.Problem(ListsLoad.Locked), restore(backup(), RestoreMode.REPLACE))
        assertArrayEquals(good, listsFile.readBytes())
        phoneLocked = false
        val damaged = good.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        listsFile.writeBytes(damaged)
        assertEquals(ListsRestore.Problem(ListsLoad.Unreadable), restore(backup(), RestoreMode.REPLACE))
        assertArrayEquals(damaged, listsFile.readBytes())
        val newer = good.copyOf().also { it[4] = 2 }
        listsFile.writeBytes(newer)
        assertEquals(ListsRestore.Problem(ListsLoad.TooNew(2)), restore(backup(), RestoreMode.REPLACE))
        assertArrayEquals(newer, listsFile.readBytes())
        assertTrue(!File(dir, ListsVault.BAD).exists())
    }

    @Test fun listsWhoseKeyIsGoneAreKeptAsideAndTheBackupsComeBackUnderANewKey() {
        loaded(vault.load())
        val before = listsFile.readBytes()
        keys.invalid += ListsKeyAliases.UNLOCKED
        val b = backup()
        assertTrue(vault().restore { have, ids -> RestoreMerge.lists(have, b, RestoreMode.REPLACE, true, ids, roleName) } is ListsRestore.Done)
        assertArrayEquals(before, File(dir, ListsVault.BAD).readBytes())
        assertEquals(listOf(ListsKeyAliases.UNLOCKED), keys.deleted)
        assertEquals(b.lists.map { it.id }, loaded().lists.map { it.id })
    }

    @Test fun overTwoMegabytesNothingChanges() {
        loaded(vault.load())
        val good = listsFile.readBytes()
        assertEquals(ListsRestore.TooBig, vault.restore { _, _ -> ListsMergeResult.TooBig })
        assertArrayEquals(good, listsFile.readBytes())
    }

    @Test fun theRestoredLockSwitchesAreFollowedBySealAndJournal() {
        assertTrue(restore(backup(), RestoreMode.FRESH) is ListsRestore.Done)
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        val restored = ListsSettings(lockScreen = true)
        assertTrue(vault.follow(restored) is ListsFollow.Done)
        settings = restored
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.alias(listsFile.readBytes()))
        assertEquals(backup().lists.size, loaded().lists.size)
    }

    private companion object {
        const val T = 1_761_500_000_000L
    }
}
