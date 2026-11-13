// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.ChatTurn
import app.heyrobot.core.ConversationCodec
import app.heyrobot.core.HistoryEnvelope
import app.heyrobot.core.SealedOpen
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.TurnRole
import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import java.io.File
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** History's keys for JVM tests: software keys by alias. [locked] fails the unlock-only key; [gone] as Android's invalidated key. */
class SoftHistoryKeys : MemoryKeys {
    val keys = HashMap<String, SecretKey>()
    var locked = false
    val gone = HashSet<String>()
    var created = 0
    var opens = 0
    /** Thrown by the key fetch itself, as AndroidKeyStoreSpi.engineGetKey does. */
    val refuse = HashMap<String, Throwable>()

    override fun cipher(alias: String, create: Boolean): MemoryCipher? {
        refuse[alias]?.let { throw it }
        val k = keys[alias] ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it; created++ } else return null
        return object : MemoryCipher {
            override val alias = alias
            private fun check() {
                if (alias in gone) throw MemoryKeyException(KeyProblem.GONE)
                if (locked && alias == HistoryKeyAliases.UNLOCKED) throw MemoryKeyException(KeyProblem.LOCKED)
            }
            override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
                check()
                val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)); c.updateAAD(aad)
                return iv + c.doFinal(plain)
            }
            override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
                opens++
                check()
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12)); c.updateAAD(aad)
                return try { c.doFinal(sealed, 12, sealed.size - 12) } catch (e: AEADBadTagException) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
            }
        }
    }

    override fun delete(alias: String) { keys.remove(alias); gone.remove(alias); refuse.remove(alias) }
}

class HistoryVaultTest {
    @get:Rule val tmp = TemporaryFolder()
    private val keys = SoftHistoryKeys()
    private var lockedNow = false
    private val dir by lazy { tmp.newFolder("conversations") }
    private fun vault(crashAt: String? = null) = HistoryVault(dir, keys, File(tmp.root, "history.lock"), { lockedNow }) { if (it == crashAt) throw IllegalStateException("cut at $it") }

    private fun conv(id: String, words: String, updated: Long) = StoredConversation(id, 1_000L, updated, listOf(ChatTurn("u", TurnRole.USER, words, 1_000L)))
    private fun plain(c: StoredConversation) = File(dir, c.id + ".json").apply { writeText(ConversationCodec.toJson(c)) }
    private fun names() = dir.list()!!.sorted()
    /** fix-seal-14-e 2: a copy kept aside, sealed as History is, opened back to its bytes. */
    private fun asideText(name: String) = String((HistoryEnvelope.frame.open(File(dir, name).readBytes(), { a -> keys.cipher(a, false) }, name) { it } as SealedOpen.Ok).value, Charsets.UTF_8)
    private fun noPlainWords(word: String) = dir.listFiles()!!.forEach { assertFalse(it.name, String(it.readBytes(), Charsets.ISO_8859_1).contains(word)) }

    /** seal-history-fix-c 3: a save already on its way in :voice never writes a deleted conversation back, one or all. */
    @Test fun aSaveOnItsWayNeverWritesADeletedConversationBack() {
        val v = vault()
        assertTrue(v.save(conv("c1", "the dentist on Tuesday", 2_000L)))
        v.delete("c1") // the main process
        v.forgetDeleted("c1")() // its broadcast
        assertTrue(v.save(conv("c1", "the dentist on Tuesday", 3_000L))) // the reply that ended as you deleted
        assertEquals(emptyList<String>(), names())
        assertTrue(v.save(conv("c2", "the plumber", 2_000L)))
        v.deleteAll()
        assertTrue(v.save(conv("c2", "the plumber", 3_000L))) // written back before the broadcast came
        v.forgetDeleted(null, now = 5_000L)()
        assertEquals(emptyList<String>(), names())
        assertTrue(v.save(conv("c2", "the plumber", 6_000L)))
        assertTrue(v.save(StoredConversation("c3", 6_000L, 6_000L, listOf(ChatTurn("u", TurnRole.USER, "a new one", 6_000L)))))
        assertEquals(listOf("c3.enc"), names())
    }

    /** fix-seal-14-e 2: a plain copy kept aside is sealed as History is; an older sealed copy of other bytes is never overwritten. */
    @Test fun aPlainCopyKeptAsideIsSealedAndNeverOverAnother() {
        val words = "the dentist on Tuesday, a turn a newer Robot wrote"
        File(dir, "c1.json.bad").writeText(words)
        File(dir, "c1.json.enc.bad").writeBytes(byteArrayOf(9, 9, 9))
        val v = vault()
        v.read()
        assertEquals(listOf("c1.json.enc.bad", "c1.json.enc.bad2"), names())
        noPlainWords("dentist")
        val back = HistoryEnvelope.frame.open(File(dir, "c1.json.enc.bad2").readBytes(), { a -> keys.cipher(a, false) }, "c1.json.enc.bad2") { it }
        assertEquals(words, String((back as SealedOpen.Ok).value, Charsets.UTF_8))
        assertEquals(1, v.aside())
        v.read()
        assertEquals(listOf("c1.json.enc.bad", "c1.json.enc.bad2"), names())
        v.deleteAside()
        assertEquals(emptyList<String>(), names())
    }

    @Test fun plainFileIsSealedReadBackAndRemoved() {
        val c = conv("c1", "the dentist on Tuesday", 2_000L)
        plain(c)
        val r = vault().read()
        assertEquals(listOf(ConversationCodec.toJson(c)), r.conversations.map(ConversationCodec::toJson))
        assertEquals(listOf("c1.enc"), names())
        noPlainWords("dentist")
        assertEquals(ConversationCodec.toJson(c), ConversationCodec.toJson(vault().load("c1")!!))
    }

    /** A crash at each step of the sealing leaves one whole copy, and the next pass ends with just the sealed one. */
    @Test fun aCutAtEachStepLeavesOneWholeCopy() {
        for (at in listOf("seal", "written", "verified", "tmp")) {
            dir.listFiles()!!.forEach { it.delete() }
            val c = conv("c1", "words $at", 2_000L)
            plain(c)
            if (at == "tmp") File(dir, ".c1.enc.tmp").writeBytes(byteArrayOf(1, 2, 3)) else vault(crashAt = at).read()
            val whole = ConversationCodec.fromJson(File(dir, "c1.json").readText()) != null ||
                vault().also { lockedNow = true }.read().conversations.isNotEmpty()
            lockedNow = false
            assertTrue(at, whole)
            val r = vault().read()
            assertEquals(at, listOf("words $at"), r.conversations.map { it.turns.single().text })
            assertEquals(at, listOf("c1.enc"), names())
        }
    }

    @Test fun theNewerCopyWinsWhenBothAreThere() {
        vault().save(conv("c1", "old", 2_000L))
        plain(conv("c1", "newer", 3_000L))
        assertEquals("newer", vault().read().conversations.single().turns.single().text)
        plain(conv("c1", "older", 1_500L))
        assertEquals("newer", vault().read().conversations.single().turns.single().text)
        assertEquals(listOf("c1.enc"), names())
    }

    @Test fun aSaveWhileLockedIsSealedUnderTheLockKeyAndMergedAtUnlock() {
        lockedNow = true
        val v = vault()
        assertTrue(v.save(conv("c1", "over the lock screen", 2_000L)))
        assertEquals(listOf("c1.locked.enc"), names())
        noPlainWords("lock screen")
        assertEquals(HistoryKeyAliases.LOCK_SCREEN, HistoryEnvelope.alias(File(dir, "c1.locked.enc").readBytes()))
        assertTrue(v.read().conversations.isEmpty())
        assertNull(v.load("c1"))
        lockedNow = false
        assertEquals("over the lock screen", v.read().conversations.single().turns.single().text)
        assertEquals(listOf("c1.enc"), names())
        assertEquals(HistoryKeyAliases.UNLOCKED, HistoryEnvelope.alias(File(dir, "c1.enc").readBytes()))
    }

    @Test fun theUnlockKeyNotUsableNowSendsTheSaveToTheJournal() {
        keys.locked = true
        assertTrue(vault().save(conv("c1", "hi", 2_000L)))
        assertEquals(listOf("c1.locked.enc"), names())
        keys.locked = false
        vault().open()
        assertEquals(listOf("c1.enc"), names())
    }

    @Test fun anOlderJournalNeverReplacesANewerConversation() {
        lockedNow = true
        vault().save(conv("c1", "locked", 2_000L))
        lockedNow = false
        File(dir, "c1.locked.enc").copyTo(File(tmp.root, "j"))
        vault().save(conv("c1", "later", 3_000L))
        File(tmp.root, "j").copyTo(File(dir, "c1.locked.enc"))
        assertEquals("later", vault().read().conversations.single().turns.single().text)
        assertEquals(listOf("c1.enc"), names())
    }

    @Test fun aKeyThatIsGoneKeepsItsFilesAsideAndANewKeyIsMade() {
        val v = vault()
        v.save(conv("c1", "first", 2_000L))
        keys.gone += HistoryKeyAliases.UNLOCKED
        val r = v.read()
        assertTrue(r.conversations.isEmpty())
        assertEquals(1, r.aside)
        assertTrue(File(dir, "c1.enc.bad").isFile)
        assertTrue(v.save(conv("c2", "second", 3_000L)))
        assertEquals(2, keys.created)
        val after = v.read()
        assertEquals(listOf("c2"), after.conversations.map { it.id })
        assertEquals(1, after.aside)
        assertTrue(File(dir, "c1.enc.bad").isFile)
    }

    // seal-history-fix 1: Android 15's engineGetKey turns KeyPermanentlyInvalidatedException into an
    // UnrecoverableKeyException with its message and no cause; any other failure keeps a KeyStoreException as the cause.
    @Test fun aKeyFetchThatSaysInvalidatedIsGoneNotNotNow() {
        assertEquals(KeyProblem.GONE, ListsVault.problemOf(java.security.UnrecoverableKeyException("User changed or deleted their auth credentials")))
        assertEquals(KeyProblem.LOCKED, ListsVault.problemOf(java.security.UnrecoverableKeyException("Failed to obtain information about key").apply { initCause(RuntimeException("keystore")) }))
        val v = vault()
        v.save(conv("c1", "first", 2_000L))
        keys.refuse[HistoryKeyAliases.UNLOCKED] = java.security.UnrecoverableKeyException("User changed or deleted their auth credentials")
        assertEquals(1, v.read().aside)
        assertTrue(v.save(conv("c2", "second", 3_000L)))
        assertEquals(listOf("c1.enc.bad", "c2.enc"), names())
        assertEquals(listOf("c2"), v.read().conversations.map { it.id })
    }

    @Test fun aJournalTheUnlockKeyCantTakeYetIsStillListed() {
        keys.locked = true
        val v = vault()
        assertTrue(v.save(conv("c1", "saved while the key said not now", 2_000L)))
        assertEquals(listOf("c1"), v.read().conversations.map { it.id })
        assertEquals("saved while the key said not now", v.load("c1")?.turns?.single()?.text)
        assertEquals(listOf("c1.locked.enc"), names())
    }

    // seal-history-fix 2: a backup never leaves one out, and a restore's plan never misses one it would delete or overwrite.
    @Test fun aConversationTheKeyCantOpenNowFailsBackupAndRestoreWithLocked() {
        vault().save(conv("c1", "a", 2_000L)); vault().save(conv("c2", "b", 3_000L))
        assertEquals(listOf("c2", "c1"), vault().whole().map { it.id })
        keys.locked = true
        val e = try { vault().whole(); null } catch (x: HistoryNotWhole) { x }
        assertEquals(2, e?.notNow)
        assertEquals(0, e?.tooNew)
    }

    // seal-history-fix-b 3: a pass that fails fails whole(), never a quiet empty History a backup would carry.
    @Test fun aFailedPassFailsWholeInsteadOfGivingNoConversations() {
        plain(conv("c1", "a", 2_000L))
        assertTrue(vault(crashAt = "seal").read().conversations.size <= 1)
        val e = try { vault(crashAt = "seal").whole(); null } catch (x: IllegalStateException) { x }
        assertEquals("cut at seal", e?.message)
        assertEquals(listOf("c1"), vault().whole().map { it.id })
    }

    // seal-history-fix-b 1: a newer Robot's file is counted as such, stays where it is, and Delete these reaches it.
    @Test fun aNewerRobotsFileIsCountedAsSuchAndDeleteTheseRemovesIt() {
        vault().save(conv("c1", "a", 2_000L)); vault().save(conv("c2", "b", 3_000L))
        val f = File(dir, "c1.enc")
        f.writeBytes(f.readBytes().also { it[4] = 99; it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) // a newer layout this one can't check
        val r = vault().read()
        assertEquals(listOf("c2"), r.conversations.map { it.id })
        assertEquals(1, r.tooNew)
        assertEquals(0, r.notNow)
        assertEquals(0, r.aside)
        val e = try { vault().whole(); null } catch (x: HistoryNotWhole) { x }
        assertEquals(1, e?.tooNew)
        assertEquals(listOf("c1.enc", "c2.enc"), names())
        vault().deleteAside()
        assertEquals(listOf("c2.enc"), names())
        assertEquals(listOf("c2"), vault().whole().map { it.id })
    }

    // seal-history-fix 3: what was said before the lock never opens under the lock-screen key.
    @Test fun aConversationStartedUnlockedJournalsOnlyItsLockScreenTurns() {
        val v = vault()
        val t1 = ChatTurn("u1", TurnRole.USER, "before the lock", 1_000L)
        val c = StoredConversation("c1", 1_000L, 2_000L, listOf(t1))
        v.save(c)
        lockedNow = true
        val t2 = ChatTurn("u2", TurnRole.USER, "after the lock", 3_000L)
        assertTrue(v.save(c.copy(updated = 3_000L, turns = listOf(t1, t2))))
        assertTrue(v.save(c.copy(updated = 3_500L, turns = listOf(t1, t2))))
        val j = HistoryEnvelope.open(File(dir, "c1.locked.enc").readBytes(), { a -> keys.cipher(a, false) }, "c1.locked.enc") as app.heyrobot.core.HistoryOpen.Ok
        assertEquals(listOf("after the lock"), j.conversation.turns.map { it.text })
        assertEquals(1, j.base)
        lockedNow = false
        assertEquals(listOf("before the lock", "after the lock"), vault().read().conversations.single().turns.map { it.text })
        assertEquals(listOf("c1.enc"), names())
    }

    // seal-history-fix-b 5: a merge cut after its write and before the journal goes never adds those turns twice.
    @Test fun aMergeCutBeforeTheJournalGoesAddsItsTurnsOnce() {
        val t1 = ChatTurn("u1", TurnRole.USER, "before the lock", 1_000L)
        val c = StoredConversation("c1", 1_000L, 2_000L, listOf(t1))
        val v = vault()
        v.save(c)
        lockedNow = true
        assertTrue(v.save(c.copy(updated = 3_000L, turns = listOf(t1, ChatTurn("u2", TurnRole.USER, "after the lock", 3_000L)))))
        lockedNow = false
        vault(crashAt = "merged").read()
        assertEquals(listOf("c1.enc", "c1.locked.enc"), names())
        assertEquals(listOf("before the lock", "after the lock"), vault().read().conversations.single().turns.map { it.text })
        assertEquals(listOf("c1.enc"), names())
        // An unlocked save cut before its journal went: the turn it had after the journal's stays, once.
        lockedNow = true
        val two = vault().read().conversations.single()
        val t3 = ChatTurn("u3", TurnRole.USER, "locked again", 4_000L)
        assertTrue(vault().save(two.copy(updated = 4_000L, turns = two.turns + t3)))
        lockedNow = false
        try { vault(crashAt = "saved").save(two.copy(updated = 5_000L, turns = two.turns + t3 + ChatTurn("u4", TurnRole.USER, "unlocked", 5_000L))) } catch (_: IllegalStateException) {}
        assertEquals(listOf("before the lock", "after the lock", "locked again", "unlocked"), vault().read().conversations.single().turns.map { it.text })
        // seal-history-fix-c 4: the same, with the locked turn changed after it was saved (its actions settled): once, as changed.
        lockedNow = true
        val three = vault().read().conversations.single()
        assertTrue(vault().save(three.copy(updated = 6_000L, turns = three.turns + ChatTurn("a5", TurnRole.ASSISTANT, "Setting it", 6_000L))))
        lockedNow = false
        try { vault(crashAt = "saved").save(three.copy(updated = 7_000L, turns = three.turns + ChatTurn("a5", TurnRole.ASSISTANT, "Set it", 6_000L))) } catch (_: IllegalStateException) {}
        assertEquals(listOf("before the lock", "after the lock", "locked again", "unlocked", "Set it"), vault().read().conversations.single().turns.map { it.text })
    }

    // seal-history-fix 4: the check after sealing fills the cache, so the first open costs one Keystore open per conversation.
    @Test fun aPlainFileIsOpenedOnceWhenSealed() {
        plain(conv("c1", "hi", 2_000L))
        val v = vault()
        assertEquals(1, v.read().conversations.size)
        assertEquals(1, keys.opens)
        v.read()
        assertEquals(1, keys.opens)
    }

    // seal-history-fix 5: the card counts conversations, not files, and its Delete these leaves every other conversation.
    @Test fun theCardCountsConversationsAndDeletesOnlyThoseKeptAside() {
        val v = vault()
        v.save(conv("c2", "keep", 2_000L))
        File(dir, "c1.enc.bad").writeText("x"); File(dir, "c1.locked.enc.bad").writeText("y")
        assertEquals(1, v.aside())
        v.deleteAside()
        assertEquals(listOf("c2.enc"), names())
        assertEquals(0, v.aside())
    }

    // seal-history-fix 6: nothing opened stays in memory past the screen going off.
    @Test fun dropForgetsWhatWasOpened() {
        val v = vault()
        v.save(conv("c1", "hi", 2_000L))
        v.read()
        val n = keys.opens
        v.read()
        assertEquals(n, keys.opens)
        v.drop()
        v.read()
        assertEquals(n + 1, keys.opens)
    }

    // seal-history-fix 7: a plain file that can't be parsed is counted on the card, never left plain and uncounted.
    // fix-seal-14-lows-b 2: a turn that doesn't parse is never dropped: the plain file is kept aside whole and counted.
    @Test fun aPlainFileWithATurnThatDoesntParseIsKeptAsideWholeNeverDropped() {
        val good = ConversationCodec.toJson(conv("c1", "kept words", 2_000L))
        val words = good.replaceFirst("\"turns\":[", "\"turns\":[{\"id\":\"n\",\"role\":\"A_NEWER_ROLE\",\"text\":\"newer words\"},")
        assertTrue(words != good)
        File(dir, "c1.json").writeText(words)
        val r = vault().read()
        assertEquals(listOf("kept words"), r.conversations.single().turns.map { it.text })
        assertEquals(1, r.aside)
        assertFalse(File(dir, "c1.json").exists())
        assertEquals(words, asideText("c1.json.enc.bad"))
    }

    @Test fun aSaveAfterAFailedMigrationNeverDropsATurnThatDoesntParse() {
        // fix-seal-14-c M2: the migration fails before sealing, the conversation opens from the plain file minus the turn,
        // then the voice process saves it: the plain file is kept aside whole, never deleted.
        val good = ConversationCodec.toJson(conv("c1", "kept words", 2_000L))
        val words = good.replaceFirst("\"turns\":[", "\"turns\":[{\"id\":\"n\",\"role\":\"A_NEWER_ROLE\",\"text\":\"newer words\"},")
        File(dir, "c1.json").writeText(words)
        val v = vault("seal")
        runCatching { v.read() }
        assertTrue(File(dir, "c1.json").exists())
        assertTrue(v.save(conv("c1", "kept words", 3_000L)))
        assertEquals(words, asideText("c1.json.enc.bad"))
        assertFalse(File(dir, "c1.json").exists())
    }

    @Test fun anUnparseablePlainFileIsKeptAsideAndCounted() {
        File(dir, "c1.json").writeText("{not json")
        assertEquals(1, vault().read().aside)
        assertEquals(listOf("c1.json.enc.bad"), names())
    }

    // seal-history-fix 8: with the clock set back, the older copy with more turns is kept.
    @Test fun aClockSetBackKeepsTheCopyWithMoreTurns() {
        vault().save(conv("c1", "unlocked", 2_000L))
        lockedNow = true
        vault().save(StoredConversation("c1", 1_000L, 1_500L, listOf(ChatTurn("u", TurnRole.USER, "unlocked", 1_000L), ChatTurn("u2", TurnRole.USER, "after the clock went back", 1_400L))))
        lockedNow = false
        assertEquals(2, vault().read().conversations.single().turns.size)
    }

    @Test fun anotherKeysAliasIsNeverAskedFor() {
        lockedNow = true
        vault().save(conv("c1", "x", 2_000L))
        lockedNow = false
        File(dir, "c1.locked.enc").renameTo(File(dir, "c1.enc"))
        val r = vault().read()
        assertTrue(r.conversations.isEmpty())
        assertEquals(1, r.aside)
    }

    @Test fun deleteAllLeavesNothingAndDropsBothKeys() {
        val v = vault()
        v.save(conv("c1", "a", 2_000L))
        lockedNow = true
        v.save(conv("c2", "b", 2_000L))
        lockedNow = false
        File(dir, "c3.enc.bad").writeBytes(byteArrayOf(9))
        plain(conv("c4", "d", 2_000L))
        v.deleteAll()
        assertEquals(emptyList<String>(), names())
        assertTrue(keys.keys.isEmpty())
    }

    @Test fun deleteOneTakesEveryCopy() {
        val v = vault()
        v.save(conv("c1", "a", 2_000L))
        lockedNow = true
        v.save(conv("c1", "b", 3_000L))
        v.delete("c1")
        lockedNow = false
        assertEquals(emptyList<String>(), names())
    }

    @Test fun aRestoredConversationIsStagedSealedAndMovedIn() {
        val v = vault()
        val c = conv("r1", "restored words", 2_000L)
        val staging = tmp.newFolder("staging")
        File(staging, "r1.enc").writeBytes(v.sealForRestore(c))
        noPlainWords("restored")
        assertFalse(String(File(staging, "r1.enc").readBytes(), Charsets.ISO_8859_1).contains("restored"))
        v.moveIn(staging)
        assertEquals(listOf("r1"), v.read().conversations.map { it.id })
        assertEquals(listOf("r1.enc", "r1.locked.enc", "r1.json"), v.namesOf("r1"))
    }

    /** Only History's store opens files/conversations: every other reader goes through it (ConversationStore, HistoryVault). */
    @Test fun nothingOutsideTheStoreOpensTheConversationsFolder() {
        val allowed = setOf("Stores.kt", "HistoryVault.kt")
        val bad = File("src/main/java").walkTopDown().filter { it.isFile && it.name.endsWith(".kt") && it.name !in allowed }
            .filter { f -> f.readText().let { it.contains("Paths.conversations") || Regex(""""conversations"\)""").containsMatchIn(it) } }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), bad)
    }
}
