package app.heyrobot.sys

import app.heyrobot.core.memory.MemoryEdits
import app.heyrobot.core.memory.MemoryEnvelope
import app.heyrobot.core.memory.MemoryFile
import app.heyrobot.core.memory.MemoryItem
import app.heyrobot.core.memory.MemoryLoad
import app.heyrobot.core.memory.MemorySettings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * files/memory/memory.enc on disk (0.7, design §A5 and §A8), with a software AES-GCM key standing in for the Keystore's:
 * no plain text in the file, nothing written over a file that couldn't be read (locked, key gone, damaged, newer), a
 * Keystore that can't answer read as "not now", the lock screen's key and back, Start over and Delete all.
 */
class MemoryVaultTest {
    private val dir: File = Files.createTempDirectory("memory").toFile()

    private val keys = SoftMemoryKeys()
    private var lockScreen = false
    private val vault = FileMemoryVault(dir, keys, lockScreen = { lockScreen }, now = { 1_000L })
    private val julie = MemoryItem("m_fact00000001", "My sister Julie's birthday is May 3", createdAt = 1)
    private val door = MemoryItem("m_secr00000001", "The door code is 4512", createdAt = 2)

    private fun add(item: MemoryItem) = vault.update { MemoryEdits.add(it, item).file }

    @Test fun nothingYetThenSavedEncrypted() {
        assertEquals(MemoryLoad.Empty, vault.load())
        assertFalse(vault.file.exists())
        val r = add(julie)
        assertTrue(r is MemoryLoad.Loaded)
        assertEquals(listOf(julie.id), (vault.load() as MemoryLoad.Loaded).file.items.map { it.id })
        // No plain text in the file: neither the words nor the JSON's keys.
        val bytes = vault.file.readBytes()
        val text = String(bytes, Charsets.ISO_8859_1)
        for (w in listOf("sister", "Julie", "May 3", "items", "m_fact")) assertFalse(w, text.contains(w))
        assertEquals(MemoryKeyAliases.UNLOCKED, MemoryEnvelope.alias(bytes))
        assertEquals(MemoryKeyAliases.UNLOCKED, vault.alias())
        // Written whole, through a temporary file: only the file and its lock are left.
        assertEquals(setOf(MemoryEnvelope.FILE, FileMemoryVault.LOCK_FILE), dir.list()!!.toSet())
        // A secret is marked as one when it is saved.
        add(door)
        assertTrue((vault.load() as MemoryLoad.Loaded).file.items.single { it.id == door.id }.secret)
    }

    @Test fun lockedReadsAsLockedAndWritesNothing() {
        add(julie)
        val before = vault.file.readBytes()
        keys.locked = true
        assertEquals(MemoryLoad.Locked, vault.load())
        assertEquals(MemoryLoad.Locked, add(door))
        assertArrayEquals(before, vault.file.readBytes())
        // A Keystore that can't answer now is "not now" too, never "gone": nothing is written, nothing dropped.
        keys.locked = false
        keys.busy = true
        assertEquals(MemoryLoad.Locked, vault.load())
        assertEquals(MemoryLoad.Locked, add(door))
        assertFalse(vault.startOver())
        keys.busy = false
        assertArrayEquals(before, vault.file.readBytes())
        assertEquals(1, (vault.load() as MemoryLoad.Loaded).file.items.size)
    }

    @Test fun aKeyThatIsGoneOrADamagedFileIsNeverWrittenOver() {
        add(julie)
        val before = vault.file.readBytes()
        keys.delete(MemoryKeyAliases.UNLOCKED)
        assertEquals(MemoryLoad.KeyGone, vault.load())
        assertEquals(MemoryLoad.KeyGone, add(door))
        // No new key was made over the old file, and the file is as it was.
        assertNull(keys.keys[MemoryKeyAliases.UNLOCKED])
        assertArrayEquals(before, vault.file.readBytes())
        // Start over keeps it aside as memory.enc.bad, then memory starts empty.
        assertTrue(vault.startOver())
        assertArrayEquals(before, File(dir, FileMemoryVault.BAD).readBytes())
        assertEquals(MemoryLoad.Empty, vault.load())
        add(julie)
        // A damaged file: unreadable, kept, and only Start over moves it.
        vault.file.writeBytes(vault.file.readBytes().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() })
        val damaged = vault.file.readBytes()
        assertEquals(MemoryLoad.Unreadable, vault.load())
        assertEquals(MemoryLoad.Unreadable, add(door))
        assertArrayEquals(damaged, vault.file.readBytes())
        // Not memory's file at all.
        vault.file.writeText("hello")
        assertEquals(MemoryLoad.Unreadable, vault.load())
    }

    @Test fun aNewerFileIsLeftAlone() {
        add(julie)
        val b = vault.file.readBytes()
        b[4] = 9
        vault.file.writeBytes(b)
        assertEquals(MemoryLoad.TooNew(9), vault.load())
        assertEquals(MemoryLoad.TooNew(9), add(door))
        assertArrayEquals(b, vault.file.readBytes())
        assertFalse(vault.startOver())
    }

    @Test fun nothingChangedWritesNothing() {
        add(julie)
        val t = vault.file.lastModified()
        val stamp = vault.stamp()
        Thread.sleep(20)
        assertTrue(vault.update { it } is MemoryLoad.Loaded)
        assertEquals(t, vault.file.lastModified())
        assertEquals(stamp, vault.stamp())
        // Items that ended more than a day ago go with the next change.
        val ended = MemoryItem("m_fact00000009", "I parked on P3", createdAt = 1, expiresAt = -100_000_000L)
        vault.update { it.copy(items = it.items + ended) }
        assertEquals(listOf(julie.id), (vault.load() as MemoryLoad.Loaded).file.items.map { it.id })
    }

    @Test fun theLockScreensKeyAndBack() {
        add(julie)
        // On: sealed again with the lock screen's key, the unlock-only key deleted; it opens while "locked".
        assertTrue(vault.rekey(lockScreenKey = true) is MemoryLoad.Loaded)
        assertEquals(MemoryKeyAliases.LOCK_SCREEN, vault.alias())
        assertNull(keys.keys[MemoryKeyAliases.UNLOCKED])
        keys.locked = true
        assertTrue(vault.load() is MemoryLoad.Loaded)
        // A change while locked keeps the lock screen's key.
        assertTrue(add(door) is MemoryLoad.Loaded)
        assertEquals(MemoryKeyAliases.LOCK_SCREEN, vault.alias())
        // Off (from an unlocked phone): back to the unlock-only key, the lock screen's deleted.
        keys.locked = false
        assertTrue(vault.rekey(lockScreenKey = false) is MemoryLoad.Loaded)
        assertEquals(MemoryKeyAliases.UNLOCKED, vault.alias())
        assertNull(keys.keys[MemoryKeyAliases.LOCK_SCREEN])
        assertEquals(2, (vault.load() as MemoryLoad.Loaded).file.items.size)
        keys.locked = true
        assertEquals(MemoryLoad.Locked, vault.load())
        // Locked, the unlock-only file can't be re-keyed: nothing changes.
        assertEquals(MemoryLoad.Locked, vault.rekey(lockScreenKey = true))
        assertEquals(MemoryKeyAliases.UNLOCKED, vault.alias())
        // No file yet: a new one takes the key the switch says.
        keys.locked = false
        assertTrue(vault.deleteAll())
        lockScreen = true
        add(julie)
        assertEquals(MemoryKeyAliases.LOCK_SCREEN, vault.alias())
    }

    @Test fun deleteAllLeavesOnlyTheLock() {
        add(julie)
        File(dir, FileMemoryVault.BAD).writeText("x")
        File(dir, FileMemoryVault.INDEX).writeText("x")
        assertTrue(vault.deleteAll())
        assertEquals(setOf(FileMemoryVault.LOCK_FILE), dir.list()!!.toSet())
        assertEquals(0L, File(dir, FileMemoryVault.LOCK_FILE).length())
        assertEquals(MemoryLoad.Empty, vault.load())
        // The key stays (the design: Delete all is the files, not the key).
        assertTrue(keys.keys.containsKey(MemoryKeyAliases.UNLOCKED))
    }

    @Test fun twoVaultsOnOneFolderTakeTurns() {
        // The main app and the voice process each have their own vault on the same folder: every change is read-modify-write
        // under the lock, so none is lost.
        val other = FileMemoryVault(dir, keys, now = { 1_000L })
        val threads = (0 until 8).map { n ->
            Thread {
                val v = if (n % 2 == 0) vault else other
                v.update { MemoryEdits.add(it, MemoryItem("m_fact0000000$n", "Fact number $n about hockey", createdAt = n.toLong())).file }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertEquals(8, (vault.load() as MemoryLoad.Loaded).file.items.size)
    }

    @Test fun memorySettingsLiveBesideSettingsJsonAndTheVaultInFilesMemory() {
        val src = File(System.getProperty("robot.rootDir"), "app/src/main/java/app/heyrobot/sys/MemoryVault.kt").readText()
        assertTrue(src.contains("File(Paths.dir(ctx), MemorySettings.FILE)"))
        assertTrue(src.contains("File(ctx.filesDir, \"memory\")"))
        // The unlock-only key needs the phone unlocked; the lock screen's is the only one without.
        assertTrue(src.contains(".setUnlockedDeviceRequired(alias == MemoryKeyAliases.UNLOCKED)"))
        assertEquals("memory.json", MemorySettings.FILE)
        assertEquals(MemoryFile(), MemoryFile())
    }
}
