// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.lists.ItemBy
import app.heyrobot.core.lists.ItemHow
import app.heyrobot.core.lists.ItemOutcome
import app.heyrobot.core.lists.JournalRefusal
import app.heyrobot.core.lists.ListRole
import app.heyrobot.core.lists.ListsEdit
import app.heyrobot.core.lists.ListsEdits
import app.heyrobot.core.lists.ListsEnvelope
import app.heyrobot.core.lists.ListsFile
import app.heyrobot.core.lists.ListsId
import app.heyrobot.core.lists.ListsJournal
import app.heyrobot.core.lists.ListsLimits
import app.heyrobot.core.lists.ListsList
import app.heyrobot.core.lists.ListsLoad
import app.heyrobot.core.lists.ListsRefusal
import app.heyrobot.core.lists.ListsSettings
import app.heyrobot.core.lists.NewItem
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryKeyException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import javax.crypto.AEADBadTagException

/**
 * files/lists on disk (lists-plan.md §4, §5; LS2) with software AES-GCM keys standing in for the Keystore's: the first-use
 * lists sealed under robot_lists_v1, nothing read or changed while locked but the journal and (with the lock-screen switch)
 * an add, a tick or an untick; the journal merged at the next open, twice changing nothing; the switches' re-key and
 * keys; the key gone, damaged and newer states never written over, Start over keeping the file aside; Delete all; a
 * Keystore that can't answer read as "not now"; and only Lists' own aliases ever asked for.
 */
class ListsVaultTest {
    private val dir: File = Files.createTempDirectory("lists").toFile()

    @After fun removeTemp() { dir.deleteRecursively() }

    private val keys = SoftListsKeys()
    private var settings = ListsSettings()
    private var someoneElse = false
    private var phoneLocked = false
    private var clock = 1_000_000L
    private val vault = vault()

    private fun vault() = ListsVault(dir, keys, settings = { settings }, someoneElse = { someoneElse }, locked = { phoneLocked }, now = { clock })

    private val listsFile get() = File(dir, ListsEnvelope.FILE)
    private val journalFile get() = File(dir, ListsEnvelope.JOURNAL)

    /** The phone locked: the vault's gate and the unlock-only key both. */
    private fun lock(on: Boolean = true) {
        phoneLocked = on
        keys.locked = on
    }

    private fun loaded(l: ListsLoad<ListsFile> = vault.load()): ListsFile = (l as? ListsLoad.Loaded)?.value ?: throw AssertionError("not loaded: $l")

    private fun shopping(f: ListsFile = loaded()): ListsList = f.withRole(ListRole.SHOPPING)!!

    private fun add(list: ListsId, vararg words: String): ListsEdit.Done =
        vault.update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, list, words.map { NewItem(it) }, who) { ListsId.new(SecureRandom()) } }.done!!

    @Test fun theFirstUseListsAreSealedUnderTheUnlockKeyAndKeepTheirIds() {
        // Nothing until Lists is used: Settings › Lists only counts.
        assertEquals(ListsLoad.Empty, vault.load(firstUse = false))
        assertFalse(listsFile.exists())
        val first = loaded()
        assertEquals(listOf(ListRole.SHOPPING, ListRole.TODO), first.lists.map { it.role })
        assertEquals(first, loaded())
        assertEquals(first, loaded(vault().load()))
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        // The journal knows them from the start (an add said while locked finds Shopping), under the lock screen's key.
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.journalAlias(journalFile.readBytes()))
        val names = (vault.lockedLists() as ListsLoad.Loaded).value
        assertEquals(first.lists.map { it.id }, names.lists.map { it.id })
        // No plain text on disk: neither the words nor the JSON's keys.
        add(shopping().id, "oat milk")
        for (f in listOf(listsFile, journalFile)) {
            val text = String(f.readBytes(), Charsets.ISO_8859_1)
            for (w in listOf("oat milk", "shopping", "\"items\"", "\"lists\"")) assertFalse("${f.name}: $w", text.contains(w))
        }
        // Only Lists' own aliases were ever asked for.
        assertEquals(setOf(ListsKeyAliases.UNLOCKED, ListsKeyAliases.LOCK_SCREEN), keys.asked.toSet())
    }

    @Test fun listsOffMakesNoFirstUseListsAndNoFile() {
        settings = ListsSettings(on = false)
        assertEquals(ListsLoad.Empty, vault.load())
        assertFalse(listsFile.exists())
        // An explicit change still makes them (a screen or Settings used Lists).
        assertTrue(vault.update(ItemHow.TYPED) { f, _ -> ListsEdit.Done(f, f.lists[0].id, changed = false) }.load is ListsLoad.Loaded)
        assertTrue(listsFile.exists())
    }

    @Test fun lockedNothingIsReadOrChangedAndTheRamCopyIsNotUsed() {
        val f = loaded()
        val listsBefore = listsFile.readBytes()
        val journalBefore = journalFile.readBytes()
        lock()
        assertEquals(ListsLoad.Locked, vault.load())
        assertEquals(ListsLoad.Locked, vault.update(ItemHow.TYPED) { x, who -> ListsEdits.add(x, shopping(f).id, listOf(NewItem("eggs")), who) { ListsId.new(SecureRandom()) } }.load)
        assertEquals(ListsLoad.Locked, vault.lockedEdit(LockedEdit.Tick(shopping(f).id, shopping(f).id), ItemHow.VOICE).load)
        assertEquals(ListsFollow.Problem(ListsLoad.Locked), vault.follow(ListsSettings(lockScreen = true)))
        assertFalse(vault.deleteAll())
        assertFalse(vault.startOver())
        assertArrayEquals(listsBefore, listsFile.readBytes())
        assertArrayEquals(journalBefore, journalFile.readBytes())
        // The gate alone, the key still working (a phone lock leaves the device unlocked): still nothing, not even from RAM.
        keys.locked = false
        assertEquals(ListsLoad.Locked, vault.load())
        assertEquals(0, keys.created - 2)
    }

    @Test fun anAddSaidWhileLockedWaitsInTheJournalAndIsMergedOnce() {
        val shop = shopping()
        lock()
        val r = vault.addLocked(shop.id, listOf(NewItem("oat milk", "2 L")))
        assertTrue(r is LockedAdd.Ok)
        // Never read to answer: the lists stay closed while locked.
        assertEquals(ListsLoad.Locked, vault.load())
        lock(false)
        clock += 5_000
        val item = shopping().live.single()
        assertEquals((r as LockedAdd.Ok).ids.single(), item.id)
        assertEquals(ItemBy.LOCKED, item.by)
        assertEquals("2 L", item.qty)
        assertEquals(0, (vault.lockedLists() as ListsLoad.Loaded).value.waiting)
        // Again: nothing more.
        assertEquals(1, shopping(loaded(vault().load())).live.size)
    }

    @Test fun aCrashBetweenWritingTheListsAndEmptyingTheJournalLosesAndDoublesNothing() {
        val shop = shopping()
        lock()
        assertTrue(vault.addLocked(shop.id, listOf(NewItem("eggs"), NewItem("bread"))) is LockedAdd.Ok)
        val waiting = journalFile.readBytes()
        lock(false)
        assertEquals(2, shopping().live.size)
        // The journal as it was before it was emptied, as if the process died between the two writes.
        journalFile.writeBytes(waiting)
        assertEquals(2, shopping(loaded(vault().load())).live.size)
        assertEquals(0, (vault.lockedLists() as ListsLoad.Loaded).value.waiting)
    }

    @Test fun locklessAddsAreRefusedWithTheirReason() {
        val shop = shopping()
        lock()
        assertEquals(LockedAdd.No(JournalRefusal.NO_LIST), vault.addLocked(ListsId.new(SecureRandom()), listOf(NewItem("eggs"))))
        assertEquals(LockedAdd.No(JournalRefusal.SECRET), vault.addLocked(shop.id, listOf(NewItem("my PIN is 4821"))))
        repeat(ListsLimits.MAX_JOURNAL_ADDS) { assertTrue(vault.addLocked(shop.id, listOf(NewItem("thing $it"))) is LockedAdd.Ok) }
        assertEquals(LockedAdd.No(JournalRefusal.FULL), vault.addLocked(shop.id, listOf(NewItem("one more"))))
        // A phone lock for someone else, or the switch off: nothing at all.
        someoneElse = true
        assertEquals(LockedAdd.Off, vault.addLocked(shop.id, listOf(NewItem("eggs"))))
        assertEquals(ListsLoad.Locked, vault.lockedLists())
        someoneElse = false
        settings = ListsSettings(addLocked = false)
        assertEquals(LockedAdd.Off, vault.addLocked(shop.id, listOf(NewItem("eggs"))))
        settings = ListsSettings(on = false)
        assertEquals(LockedAdd.Off, vault.addLocked(shop.id, listOf(NewItem("eggs"))))
    }

    @Test fun noJournalYetKnowsNoListAndMakesNoKeyWhileLocked() {
        lock()
        assertEquals(LockedAdd.No(JournalRefusal.NO_LIST), vault.addLocked(ListsId.new(SecureRandom()), listOf(NewItem("eggs"))))
        assertEquals(ListsLoad.Empty, vault.lockedLists())
        assertEquals(0, keys.created)
        assertFalse(listsFile.exists())
    }

    @Test fun theLockScreenSwitchReKeysAndAllowsOnlyAnAddATickOrAnUntickWhileLocked() {
        val eggs = add(shopping().id, "eggs").items.single().item
        assertTrue(vault.follow(ListsSettings(lockScreen = true)) is ListsFollow.Done)
        settings = ListsSettings(lockScreen = true)
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.alias(listsFile.readBytes()))
        // The unlock-only key goes once nothing is sealed under it.
        assertNull(keys.keys[ListsKeyAliases.UNLOCKED])
        lock()
        val shop = shopping()
        val ticked = vault.lockedEdit(LockedEdit.Tick(shop.id, eggs), ItemHow.VOICE)
        assertEquals(ItemOutcome.TICKED, ticked.done!!.items.single().outcome)
        assertEquals(ItemBy.LOCKED, shopping().item(eggs)!!.hist.last().by)
        val added = vault.lockedEdit(LockedEdit.Add(shop.id, listOf(NewItem("bread"))), ItemHow.VOICE).done!!
        assertEquals(ItemBy.LOCKED, shopping().item(added.items.single().item)!!.by)
        assertTrue(vault.lockedEdit(LockedEdit.Untick(shop.id, eggs), ItemHow.VOICE).done!!.changed)
        // Anything else waits for the unlock, and nothing new is made while locked.
        assertEquals(ListsLoad.Locked, vault.update(ItemHow.VOICE) { f, who -> ListsEdits.clearDone(f, shop.id, who) }.load)
        assertEquals(ListsFollow.Problem(ListsLoad.Locked), vault.follow(ListsSettings()))
        // A phone lock for someone else: the switch reads as off.
        someoneElse = true
        assertEquals(ListsLoad.Locked, vault.load())
        assertEquals(ListsLoad.Locked, vault.lockedEdit(LockedEdit.Tick(shop.id, eggs), ItemHow.VOICE).load)
        someoneElse = false
        lock(false)
        // Off again: back under robot_lists_v1; the lock screen's key stays for the journal (Add to lists while locked on).
        assertTrue(vault.follow(ListsSettings()) is ListsFollow.Done)
        settings = ListsSettings()
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        assertNotNull(keys.keys[ListsKeyAliases.LOCK_SCREEN])
        assertEquals(2, shopping().live.size)
        // Both off: the journal goes, then the lock screen's key.
        assertTrue(vault.follow(ListsSettings(addLocked = false)) is ListsFollow.Done)
        settings = ListsSettings(addLocked = false)
        assertFalse(journalFile.exists())
        assertNull(keys.keys[ListsKeyAliases.LOCK_SCREEN])
        assertNotNull(keys.keys[ListsKeyAliases.UNLOCKED])
        assertEquals(2, shopping().live.size)
    }

    @Test fun turningAddWhileLockedOffMergesFirstAndWaitsWhileAnAddCantFit() {
        val shop = shopping()
        lock()
        assertTrue(vault.addLocked(shop.id, listOf(NewItem("milk"))) is LockedAdd.Ok)
        lock(false)
        // Merged first, then the journal and the key go.
        assertTrue(vault.follow(ListsSettings(addLocked = false)) is ListsFollow.Done)
        settings = ListsSettings(addLocked = false)
        assertEquals(listOf("milk"), shopping().live.map { it.text })
        assertFalse(journalFile.exists())
        assertNull(keys.keys[ListsKeyAliases.LOCK_SCREEN])
        // Again on, then an add that can't fit: Shopping full.
        assertTrue(vault.follow(ListsSettings()) is ListsFollow.Done)
        settings = ListsSettings()
        vault.update(ItemHow.TYPED) { f, who ->
            ListsEdit.Done(f.with(f.withRole(ListRole.SHOPPING)!!.let { l -> l.copy(items = l.items + (1 until ListsLimits.MAX_ITEMS).map { n ->
                app.heyrobot.core.lists.ListItem(ListsId.new(SecureRandom()), "thing $n", pos = n * 1000L, at = who.at, upd = who.at, by = ItemBy.YOU, how = ItemHow.TYPED) }) }), shop.id)
        }
        lock()
        assertTrue(vault.addLocked(shop.id, listOf(NewItem("one more"))) is LockedAdd.Ok)
        lock(false)
        assertEquals(ListsLimits.MAX_ITEMS, shopping().items.size)
        assertEquals(ListsFollow.Waiting(1), vault.follow(ListsSettings(addLocked = false)))
        assertTrue(journalFile.exists())
        assertNotNull(keys.keys[ListsKeyAliases.LOCK_SCREEN])
    }

    @Test fun aGoneKeyIsKeyGoneNeverWrittenOverAndStartOverKeepsTheFileAside() {
        add(shopping().id, "eggs")
        val before = listsFile.readBytes()
        keys.invalid += ListsKeyAliases.UNLOCKED
        val v = vault()
        assertEquals(ListsLoad.KeyGone, v.load())
        assertEquals(ListsLoad.KeyGone, v.update(ItemHow.TYPED) { f, _ -> ListsEdit.Done(f, f.lists[0].id) }.load)
        assertArrayEquals(before, listsFile.readBytes())
        assertTrue(keys.deleted.isEmpty())
        // Start over: kept aside as lists.enc.bad; the next open makes the first-use lists under a new key (the gone one replaced).
        assertTrue(v.startOver())
        assertArrayEquals(before, File(dir, ListsVault.BAD).readBytes())
        val fresh = loaded(v.load())
        assertEquals(0, shopping(fresh).live.size)
        assertEquals(listOf(ListsKeyAliases.UNLOCKED), keys.deleted)
        assertFalse(ListsKeyAliases.UNLOCKED in keys.invalid)
        // Start over again later: the older copy stays, the new one goes beside it.
        listsFile.writeBytes(listsFile.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        assertEquals(ListsLoad.Unreadable, vault().load())
        assertTrue(vault().startOver())
        assertArrayEquals(before, File(dir, ListsVault.BAD).readBytes())
        assertTrue(File(dir, ListsVault.BAD + "2").exists())
    }

    /**
     * LS10 (L5, §4.5): a copy kept aside keeps its key. lists.enc reads as a newer Robot's, so Start over keeps it as
     * lists.enc.bad, still sealed under robot_lists_v1; turning "Hear and tick on the lock screen" on moves the lists off that
     * key but doesn't delete it, and the copy still opens with it. The same for the journal kept aside and the lock screen's
     * key. Delete all lists removes the copies; then a switch deletes the key again.
     */
    @Test fun aCopyKeptAsideKeepsItsKey() {
        add(shopping().id, "eggs")
        listsFile.writeBytes(listsFile.readBytes().also { it[4] = 2 })
        assertEquals(ListsLoad.TooNew(2), vault().load())
        assertTrue(vault.startOver())
        assertTrue(File(dir, ListsVault.JOURNAL_BAD).exists())
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        assertTrue(vault.follow(ListsSettings(lockScreen = true)) is ListsFollow.Done)
        settings = ListsSettings(lockScreen = true)
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.alias(listsFile.readBytes()))
        assertNotNull(keys.keys[ListsKeyAliases.UNLOCKED])
        val bad = File(dir, ListsVault.BAD).readBytes().also { it[4] = 1 }
        assertEquals(listOf("eggs"), shopping(loaded(ListsEnvelope.open(bad) { keys.cipher(it, create = false) })).live.map { it.text })
        // Both switches off: the journal kept aside keeps the lock screen's key too.
        assertTrue(vault.follow(ListsSettings(addLocked = false)) is ListsFollow.Done)
        settings = ListsSettings(addLocked = false)
        assertNotNull(keys.keys[ListsKeyAliases.LOCK_SCREEN])
        assertTrue(keys.deleted.isEmpty())
        // Delete all lists: the copies go, and the next switch deletes the key the lists moved off.
        assertTrue(vault.deleteAll())
        assertFalse(File(dir, ListsVault.BAD).exists() || File(dir, ListsVault.JOURNAL_BAD).exists())
        assertTrue(vault.follow(ListsSettings(addLocked = false, lockScreen = true)) is ListsFollow.Done)
        assertNull(keys.keys[ListsKeyAliases.UNLOCKED])
    }

    /**
     * LS10 (L6): a merged journal is emptied whatever "Add to lists while locked" reads (a damaged lists_settings.json reads
     * both lock switches off), and an add already in lists.enc changes nothing: lists.enc isn't written again at every open.
     */
    @Test fun aMergedJournalIsEmptiedAndAMergeTwiceWritesNothing() {
        var writes = 0
        fun counted() = ListsVault(dir, keys, settings = { settings }, someoneElse = { someoneElse }, locked = { phoneLocked }, now = { clock }, changed = { writes++ })
        val shop = shopping(loaded(counted().load()))
        lock()
        assertTrue(vault.addLocked(shop.id, listOf(NewItem("oat milk"), NewItem("eggs"))) is LockedAdd.Ok)
        val waiting = journalFile.readBytes()
        lock(false)
        settings = app.heyrobot.core.lists.ListsSettingsCodec.decode("{").settings
        assertEquals(ListsSettings(addLocked = false, lockScreen = false), settings)
        assertEquals(2, shopping(loaded(counted().load())).live.size)
        assertEquals(0, journalNow().waiting)
        // Another open (a process with no RAM copy): nothing merged again, nothing written.
        val bytes = listsFile.readBytes()
        val n = writes
        assertEquals(2, shopping(loaded(counted().load())).live.size)
        assertArrayEquals(bytes, listsFile.readBytes())
        assertEquals(n, writes)
        // The journal as it was before it was emptied (a crash between the two writes): its adds are there already, so
        // lists.enc stays as it is and the journal is emptied.
        journalFile.writeBytes(waiting)
        assertEquals(2, shopping(loaded(counted().load())).live.size)
        assertArrayEquals(bytes, listsFile.readBytes())
        assertEquals(n, writes)
        assertEquals(0, journalNow().waiting)
    }

    private fun journalNow() = (ListsEnvelope.openJournal(journalFile.readBytes()) { keys.cipher(it, create = false) } as ListsLoad.Loaded).value

    /**
     * LS10 (L7): follow() is all or nothing. Turning "Hear and tick on the lock screen" on with "Add to lists while locked"
     * (the journal to write), the journal's seal fails "not now": Problem, and lists.enc is still under robot_lists_v1, that
     * key still there. Once the Keystore answers, the same switch goes through.
     */
    @Test fun followIsAllOrNothing() {
        add(shopping().id, "eggs")
        assertTrue(vault.follow(ListsSettings(addLocked = false)) is ListsFollow.Done)
        settings = ListsSettings(addLocked = false)
        assertFalse(journalFile.exists())
        val before = listsFile.readBytes()
        keys.failSeal = { alias, aad -> alias == ListsKeyAliases.LOCK_SCREEN && String(aad, Charsets.UTF_8).endsWith(ListsEnvelope.JOURNAL) }
        assertTrue(vault.follow(ListsSettings(addLocked = true, lockScreen = true)) is ListsFollow.Problem)
        assertEquals(ListsKeyAliases.UNLOCKED, ListsEnvelope.alias(listsFile.readBytes()))
        assertArrayEquals(before, listsFile.readBytes())
        assertNotNull(keys.keys[ListsKeyAliases.UNLOCKED])
        assertFalse(journalFile.exists())
        assertEquals(listOf("eggs"), shopping(loaded(vault().load())).live.map { it.text })
        keys.failSeal = { _, _ -> false }
        assertTrue(vault.follow(ListsSettings(addLocked = true, lockScreen = true)) is ListsFollow.Done)
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.alias(listsFile.readBytes()))
        assertTrue(journalFile.exists())
        assertNull(keys.keys[ListsKeyAliases.UNLOCKED])
    }

    @Test fun aMissingKeyIsKeyGone() {
        loaded()
        keys.keys.remove(ListsKeyAliases.UNLOCKED)
        assertEquals(ListsLoad.KeyGone, vault().load())
        // Delete all lists: an empty file under a new key.
        assertTrue(vault().deleteAll())
        assertEquals(emptyList<ListsList>(), loaded(vault().load()).lists)
    }

    @Test fun damagedAndNewerAreNeverWrittenOverAndStartOverIsOnlyForThem() {
        loaded()
        assertFalse(vault.startOver())
        val good = listsFile.readBytes()
        val damaged = good.copyOf().also { it[it.size - 3] = (it[it.size - 3] + 1).toByte() }
        listsFile.writeBytes(damaged)
        assertEquals(ListsLoad.Unreadable, vault.load())
        assertEquals(ListsLoad.Unreadable, vault.update(ItemHow.TYPED) { f, _ -> ListsEdit.Done(f, f.lists[0].id) }.load)
        assertTrue(vault.follow(ListsSettings(lockScreen = true)) is ListsFollow.Problem)
        assertArrayEquals(damaged, listsFile.readBytes())
        val newer = good.copyOf().also { it[4] = 2 }
        listsFile.writeBytes(newer)
        assertEquals(ListsLoad.TooNew(2), vault.load())
        assertEquals(ListsLoad.TooNew(2), vault.update(ItemHow.TYPED) { f, _ -> ListsEdit.Done(f, f.lists[0].id) }.load)
        assertArrayEquals(newer, listsFile.readBytes())
        assertTrue(vault.startOver())
        assertArrayEquals(newer, File(dir, ListsVault.BAD).readBytes())
    }

    @Test fun onlyListsOwnAliasesAreEverAskedFor() {
        // A lists.enc whose header names memory's key: damaged data, and memory's key is never asked for.
        val other = SoftListsKeys().cipher("robot_memory_v1", create = true)!!
        listsFile.parentFile.mkdirs()
        listsFile.writeBytes(ListsEnvelope.seal(ListsFile(), other))
        assertEquals(ListsLoad.Unreadable, vault.load())
        assertFalse("robot_memory_v1" in keys.asked)
        // A journal sealed under the unlock-only key: not a journal (kept aside), and never opened with it.
        listsFile.delete()
        val k = SoftListsKeys().cipher(ListsKeyAliases.UNLOCKED, create = true)!!
        journalFile.writeBytes(ListsEnvelope.sealJournal(ListsJournal.snapshot(ListsFile()), k))
        loaded()
        assertTrue(File(dir, ListsVault.JOURNAL_BAD).exists())
        assertEquals(ListsKeyAliases.LOCK_SCREEN, ListsEnvelope.journalAlias(journalFile.readBytes()))
    }

    @Test fun aDamagedJournalIsKeptAsideAndANewOneStarts() {
        loaded()
        journalFile.writeBytes(byteArrayOf(1, 2, 3))
        val f = loaded(vault().load())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(dir, ListsVault.JOURNAL_BAD).readBytes())
        assertEquals(f.lists.map { it.id }, (vault.lockedLists() as ListsLoad.Loaded).value.lists.map { it.id })
    }

    @Test fun aJournalWhoseKeyIsGoneIsKeptAsideAndItsKeyReplaced() {
        loaded()
        keys.invalid += ListsKeyAliases.LOCK_SCREEN
        val f = loaded(vault().load())
        assertTrue(File(dir, ListsVault.JOURNAL_BAD).exists())
        assertEquals(listOf(ListsKeyAliases.LOCK_SCREEN), keys.deleted)
        assertEquals(f.lists.map { it.id }, (vault.lockedLists() as ListsLoad.Loaded).value.lists.map { it.id })
    }

    @Test fun deleteAllLeavesAnEmptyFileSoTheFirstUseListsDontComeBack() {
        add(shopping().id, "eggs")
        File(dir, ListsVault.BAD).writeBytes(byteArrayOf(9))
        File(dir, ListsVault.JOURNAL_BAD).writeBytes(byteArrayOf(9))
        assertTrue(vault.deleteAll())
        assertEquals(emptyList<ListsList>(), loaded().lists)
        assertEquals(emptyList<ListsList>(), loaded(vault().load()).lists)
        assertEquals(setOf(ListsEnvelope.FILE, ListsEnvelope.JOURNAL, ListsVault.LOCK_FILE), dir.list()!!.toSet())
        // The keys stay.
        assertTrue(keys.deleted.isEmpty())
    }

    @Test fun aKeystoreThatCantAnswerIsNotNowAndChangesNothing() {
        val shop = shopping().id
        add(shop, "eggs")
        val before = listsFile.readBytes()
        keys.busy = true
        val v = vault()
        assertEquals(ListsLoad.Locked, v.load())
        assertEquals(ListsLoad.Locked, v.update(ItemHow.TYPED) { f, _ -> ListsEdit.Done(f, f.lists[0].id) }.load)
        assertTrue(v.follow(ListsSettings(lockScreen = true)) is ListsFollow.Problem)
        assertEquals(LockedAdd.Failed, v.addLocked(shop, listOf(NewItem("milk"))))
        assertFalse(v.startOver())
        assertArrayEquals(before, listsFile.readBytes())
        assertTrue(keys.deleted.isEmpty())
        assertFalse(File(dir, ListsVault.BAD).exists())
        assertFalse(File(dir, ListsVault.JOURNAL_BAD).exists())
    }

    @Test fun anUnlockedChangeIsYoursOrContentsNeverLockedOrABackups() {
        val shop = shopping().id
        val r = vault.update(ItemHow.CARD, ItemBy.CONTENT) { f, who -> ListsEdits.add(f, shop, listOf(NewItem("flour")), who) { ListsId.new(SecureRandom()) } }
        assertEquals(ItemBy.CONTENT, shopping().item(r.done!!.items.single().item)!!.by)
        for (by in listOf(ItemBy.LOCKED, ItemBy.BACKUP, ItemBy.PEER, ItemBy.UNKNOWN))
            assertTrue(by.name, runCatching { vault.update(ItemHow.CARD, by) { f, _ -> ListsEdit.Done(f, shop) } }.isFailure)
    }

    @Test fun aRefusedChangeStillKeepsTheMerge() {
        val shop = shopping()
        lock()
        vault.addLocked(shop.id, listOf(NewItem("rice")))
        lock(false)
        val r = vault.update(ItemHow.TYPED) { _, _ -> ListsEdit.No(ListsRefusal.NO_LIST) }
        assertEquals(ListsEdit.No(ListsRefusal.NO_LIST), r.edit)
        assertEquals(listOf("rice"), shopping(loaded(vault().load())).live.map { it.text })
    }

    @Test fun everyWritePurgesOldTombstonesAndKeepsTheJournalsNamesInStep() {
        val shop = shopping()
        val egg = add(shop.id, "eggs").items.single().item
        vault.update(ItemHow.TYPED) { f, who -> ListsEdits.remove(f, shop.id, listOf(egg), who) }
        assertNotNull(shopping().item(egg))
        clock += ListsLimits.TOMBSTONE_MS + 1
        add(shop.id, "bread")
        assertNull(shopping().item(egg))
        // A new list: the journal knows it at once.
        vault.update(ItemHow.TYPED) { f, who -> ListsEdits.make(f, "Packing", null, who) { ListsId.new(SecureRandom()) } }
        val j = (vault.lockedLists() as ListsLoad.Loaded).value
        assertNotNull(ListsJournal.named(j, "packing"))
    }

    @Test fun anotherProcessesChangeIsSeenAndTwoVaultsTakeTurns() {
        val shop = shopping()
        val other = vault()
        assertEquals(0, shopping(loaded(other.load())).live.size)
        add(shop.id, "eggs")
        assertEquals(1, shopping(loaded(other.load())).live.size)
        val threads = (0 until 8).map { n ->
            Thread { (if (n % 2 == 0) vault else other).update(ItemHow.TYPED) { f, who -> ListsEdits.add(f, shop.id, listOf(NewItem("item $n")), who) { ListsId.new(SecureRandom()) } } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(9, shopping().live.size)
    }

    @Test fun theRamCopyGoesWhenDropped() {
        loaded()
        val asked = keys.asked.size
        loaded()
        assertEquals(asked, keys.asked.size)
        vault.drop()
        loaded()
        assertTrue(keys.asked.size > asked)
    }

    @Test fun keysProblemsFromTheKeystoreAreReadFromTheirCauses() {
        assertEquals(KeyProblem.BAD_DATA, ListsVault.problemOf(RuntimeException(AEADBadTagException())))
        assertEquals(KeyProblem.GONE, ListsVault.problemOf(IllegalStateException(MemoryKeyException(KeyProblem.GONE))))
        assertEquals(KeyProblem.LOCKED, ListsVault.problemOf(IllegalStateException("busy")))
    }


    // F51: lists.enc there but unreadable now is "not now": never first-use lists over it.
    @Test fun aFailedReadOfTheListsWritesNoFirstUseListsOverThem() {
        val first = loaded(vault.load())
        val before = listsFile.readBytes().toList()
        listsFile.setReadable(false)
        try {
            org.junit.Assume.assumeFalse(listsFile.canRead())
            assertEquals(ListsLoad.Locked, vault().load())
            assertEquals(ListsLoad.Locked, vault.load())
        } finally {
            listsFile.setReadable(true)
        }
        assertEquals(before, listsFile.readBytes().toList())
        assertEquals(first, loaded(vault().load()))
    }
}
