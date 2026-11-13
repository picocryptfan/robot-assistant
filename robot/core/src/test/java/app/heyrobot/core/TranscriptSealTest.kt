// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.memory.KeyProblem
import app.heyrobot.core.memory.MemoryCipher
import app.heyrobot.core.memory.MemoryKeyException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.Timeout
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** seal-transcripts: transcripts sealed as History is (an upgrade, a locked read, a dropped key), with a soft Keystore. */
class TranscriptSealTest {
    @get:Rule val tmp = TemporaryFolder()
    @get:Rule val timeout: Timeout = Timeout.seconds(30)

    /** The Keystore as the phone's: the unlock key refuses while locked; a key in [gone] was invalidated by Android. */
    class SoftKeystore {
        val keys = HashMap<String, SecretKey>()
        var locked = false
        val gone = HashSet<String>()
        val deleted = ArrayList<String>()
        /** keystore2 throwing something that isn't a key's own verdict (KeyStore.load, getKey). */
        var busy = false
        /** Aliases whose getKey fails whatever the phone's state (a Keystore fault, fix-seal-transcripts-b). */
        val faulty = HashSet<String>()

        fun cipher(alias: String, create: Boolean): MemoryCipher? {
            if (busy) throw IllegalStateException("keystore2 busy")
            if (alias in faulty) throw IllegalStateException("getKey failed")
            if (create && locked && alias == TranscriptKeyAliases.UNLOCKED && alias !in keys) throw MemoryKeyException(KeyProblem.LOCKED)
            val k = keys[alias] ?: if (create) KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { keys[alias] = it } else return null
            return object : MemoryCipher {
                override val alias = alias
                private fun check() {
                    if (alias in gone) throw MemoryKeyException(KeyProblem.GONE)
                    if (locked && alias == TranscriptKeyAliases.UNLOCKED) throw MemoryKeyException(KeyProblem.LOCKED)
                }
                override fun seal(plain: ByteArray, aad: ByteArray): ByteArray {
                    check()
                    val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
                    return iv + Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, k, GCMParameterSpec(128, iv)); updateAAD(aad) }.doFinal(plain)
                }
                override fun open(sealed: ByteArray, aad: ByteArray): ByteArray {
                    check()
                    return try {
                        Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, sealed, 0, 12)); updateAAD(aad) }.doFinal(sealed, 12, sealed.size - 12)
                    } catch (e: Exception) { throw MemoryKeyException(KeyProblem.BAD_DATA, e) }
                }
            }
        }

        fun delete(alias: String) { keys.remove(alias); gone.remove(alias); deleted += alias }
    }

    private val ks = SoftKeystore()
    private fun root() = File(tmp.root, "transcripts")
    private fun sealed(): TranscriptStore = TranscriptStore(root(), TranscriptSeal(root(), ks::cipher, ks::delete) { ks.locked })

    private fun record(s: TranscriptStore, id: String, vararg words: String): TranscriptWriter {
        val w = TranscriptWriter.start(s, id, 1_000L, ConvMode.TRANSCRIBE, LangPair.DEFAULT)!!
        words.forEachIndexed { n, t -> assertNotNull(w.line(2_000L + n) { i -> TranscriptEvent.Line(i, n.toDouble(), n + 0.5, "S1", "en", t) }) }
        return w
    }

    private fun textFiles(id: String) = File(root(), id).listFiles { f -> f.isFile && f.name.endsWith(".json") || f.name == TranscriptFiles.LOG }.orEmpty().toList()

    private fun assertNoPlainWords(id: String, vararg words: String) {
        for (f in textFiles(id)) {
            val t = f.readText()
            for (w in words) assertFalse("${f.name} holds plain words", t.contains(w))
            t.split('\n').filter { it.isNotEmpty() }.forEach { assertTrue("${f.name}: a plain line", it.startsWith(TranscriptSeal.MARK)) }
        }
    }

    private fun aliases(id: String): Set<String?> {
        val s = TranscriptSeal(root(), ks::cipher, ks::delete) { ks.locked }
        return textFiles(id).flatMap { it.readText().split('\n').filter(String::isNotEmpty) }.map { s.aliasOf(it) }.toSet()
    }

    @Test fun upgrade_with_saved_transcripts() {
        val id = "20261111-0900-a1b2"
        val plain = TranscriptStore(root())
        record(plain, id, "Bonjour Marie", "On se voit demain").close(9_000L)
        plain.rename(id, "Lunch with Marie")
        val before = plain.load(id)!!
        assertTrue(File(root(), "$id/${TranscriptFiles.META}").readText().contains("Lunch with Marie"))

        val s = sealed()
        assertEquals(before.lines.map { it.text }, s.load(id)!!.lines.map { it.text }) // an older Robot's plain files still read
        assertEquals(listOf(id), s.list().map { it.id }) // the list settles them: sealed, read back, then the plain gone
        assertNoPlainWords(id, "Bonjour Marie", "On se voit demain", "Lunch with Marie")
        assertEquals(setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(id))
        val again = sealed()
        assertEquals(before.lines.map { it.text }, again.load(id)!!.lines.map { it.text })
        assertEquals("Lunch with Marie", again.meta(id)!!.title)
        assertTrue(File(root(), id).listFiles()!!.none { it.name.endsWith(".tmp") })
    }

    @Test fun locked_read() {
        // Started unlocked, then the phone locked: what the unlock key sealed waits for an unlock, never read as empty.
        val a = "20261111-1000-0001"
        val s = sealed()
        record(s, a, "Room 4 at noon")
        s.beat(SessionHeartbeat(a, ConvMode.TRANSCRIBE, 1_000L, 2_000L, 2_000L, 1))
        ks.locked = true
        val voice = sealed() // the :voice process after a crash, the phone still locked
        assertNull(voice.load(a))
        assertNull(voice.recover(serviceRunning = false, stoppedByUser = false))
        assertNotNull("the heartbeat is kept for the next start", voice.heartbeat())

        // A session begun on the lock screen: sealed under the lock-screen key, which reads (and resumes) while locked.
        val b = "20261111-1001-0002"
        record(voice, b, "Call the plumber").close(5_000L)
        assertNoPlainWords(b, "Call the plumber")
        assertEquals(setOf<String?>(TranscriptKeyAliases.LOCK_SCREEN), aliases(b))
        assertEquals(listOf("Call the plumber"), sealed().load(b)!!.lines.map { it.text })
        assertNotNull(TranscriptWriter.resume(sealed(), b, 9_000L))

        // Unlocked: the next list re-seals the lock screen's records under the unlock key, and the first one reads again.
        ks.locked = false
        val main = sealed()
        assertEquals(setOf(a, b), main.list().map { it.id }.toSet())
        assertEquals(setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(b))
        assertEquals(listOf("Room 4 at noon"), main.load(a)!!.lines.map { it.text })
        assertTrue(main.load(b)!!.lines.any { it.text == "Call the plumber" })
    }

    @Test fun dropped_key_keeps_the_transcript_aside() {
        val id = "20261111-1100-0003"
        val s = sealed()
        record(s, id, "Pick up at six").close(5_000L)
        s.list()
        ks.gone += TranscriptKeyAliases.UNLOCKED // the screen lock removed: Android drops the unlock-only key
        val after = sealed()
        assertEquals(emptyList<String>(), after.list().map { it.id })
        assertEquals(1, after.asideCount())
        val kept = File(root(), "$id.bad")
        assertTrue("kept aside, never deleted", kept.isDirectory && File(kept, TranscriptFiles.LOG).length() > 0)
        assertTrue(TranscriptKeyAliases.UNLOCKED in ks.deleted)
        // A new key is made: a new transcript saves and reads.
        val next = "20261111-1101-0004"
        record(after, next, "New one").close(6_000L)
        assertEquals(listOf("New one"), sealed().load(next)!!.lines.map { it.text })
        sealed().deleteAll() // Delete all reaches the kept-aside one too
        assertEquals(0, sealed().asideCount())
    }

    @Test fun a_record_moved_to_another_transcript_never_opens() {
        val s = sealed()
        record(s, "20261111-1200-0005", "Secret plan").close(5_000L)
        record(s, "20261111-1201-0006", "Other").close(5_000L)
        val from = File(root(), "20261111-1200-0005/${TranscriptFiles.LOG}").readText().split('\n').first { it.isNotEmpty() }
        File(root(), "20261111-1201-0006/${TranscriptFiles.LOG}").appendText(from + "\n")
        assertFalse(sealed().load("20261111-1201-0006")!!.lines.any { it.text == "Secret plan" })
    }

    // ------------------------------------------------------------------ fix-seal-transcripts

    @Test fun a_keystore_error_never_drops_the_key_or_the_transcript() {
        val id = "20261111-1300-0007"
        record(sealed(), id, "Gate code 4411").close(5_000L)
        sealed().list()
        val key = File(root(), ".${TranscriptKeyAliases.UNLOCKED}.key")
        assertTrue(key.isFile)
        ks.busy = true
        val s = sealed()
        s.list()
        assertNull("not now", s.load(id))
        assertEquals(0, s.asideCount())
        assertTrue(File(root(), id).isDirectory && key.isFile)
        assertFalse(TranscriptKeyAliases.UNLOCKED in ks.deleted)
        ks.busy = false
        assertEquals(listOf("Gate code 4411"), s.load(id)!!.lines.map { it.text })
    }

    /** The first review's 2, its key-file branch: a damaged key file goes aside with its folder; the Keystore key stays. */
    @Test fun a_damaged_key_file_goes_aside_and_the_keystore_key_stays() {
        val id = "20261111-1600-0010"
        record(sealed(), id, "Locker 5521").close(5_000L)
        sealed().list()
        val key = File(root(), ".${TranscriptKeyAliases.UNLOCKED}.key")
        key.writeBytes(key.readBytes().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() })
        val s = sealed()
        s.list()
        assertEquals(1, s.asideCount())
        assertTrue(File(root(), key.name + ".bad").isFile)
        assertFalse("the Keystore key is kept", TranscriptKeyAliases.UNLOCKED in ks.deleted)
        assertTrue(TranscriptKeyAliases.UNLOCKED in ks.keys)
    }

    /** The first review's 7: a key file the other process replaced is opened again, never the copy in RAM. */
    @Test fun a_key_file_the_other_process_replaced_is_opened_again() {
        val main = TranscriptSeal(root(), ks::cipher, ks::delete) { ks.locked }
        val voice = TranscriptSeal(root(), ks::cipher, ks::delete) { ks.locked }
        val name = "20261111-1700-0011/${TranscriptFiles.META}"
        assertEquals("one", main.open(name, main.seal(name, "one"))) // main's data key now in RAM
        val key = File(root(), ".${TranscriptKeyAliases.UNLOCKED}.key")
        val before = key.lastModified()
        voice.replace(TranscriptKeyAliases.UNLOCKED)
        val two = voice.seal(name, "two") // a new key file
        key.setLastModified(before + 10_000) // a new stamp whatever the file system's grain
        assertEquals("two", main.open(name, two))
    }

    /** fix-seal-transcripts-b, Low 4: a line saved while the unlock key is lost makes the key again, once, and is saved. */
    @Test fun a_lost_key_is_made_again_when_sealing() {
        val id = "20261111-1800-0012"
        record(sealed(), id, "First")
        ks.gone += TranscriptKeyAliases.UNLOCKED
        assertNull("saved under a new key", sealed().append(id, TranscriptEvent.Line(5, 9.0, 9.5, "S1", "en", "Second")))
        assertTrue(TranscriptKeyAliases.UNLOCKED in ks.deleted)
    }

    /** fix-seal-transcripts-b, Medium 1: a Keystore fault on an unlocked phone is logged once, skips its folder, and appends say KEY. */
    @Test fun a_keystore_fault_on_an_unlocked_phone_is_logged_and_settle_goes_on() {
        val a = "20261111-1900-0013"
        val b = "20261111-1900-0014"
        ks.locked = true
        record(sealed(), a, "Bike lock 3030").close(5_000L) // under the lock screen's key
        ks.locked = false
        record(TranscriptStore(root(), null), b, "Plain words").close(5_000L) // an older Robot's plain files
        val log = ArrayList<String>()
        RLog.sink = { log += it }
        try {
            ks.faulty += TranscriptKeyAliases.LOCK_SCREEN
            val s = sealed()
            s.list()
            assertEquals("settle went on past the faulty folder", setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(b))
            assertEquals(0, s.asideCount())
            assertEquals(1, log.count { "transcripts_key_fault" in it })
            assertFalse(log.any { "Bike" in it || "Plain" in it })
            ks.faulty += TranscriptKeyAliases.UNLOCKED
            assertEquals(IoCode.KEY, sealed().append(b, TranscriptEvent.Line(9, 9.0, 9.5, "S1", "en", "More")))
            ks.faulty.clear()
            assertEquals(listOf("Bike lock 3030"), sealed().also { it.list() }.load(a)!!.lines.map { it.text })
        } finally { RLog.sink = null }
    }

    /** fix-seal-transcripts-b, Medium 2: a damaged sealed snapshot is kept aside by a backup's read; the others still read. */
    @Test fun a_damaged_snapshot_is_kept_aside_by_a_backup_and_fails_no_other() {
        val bad = "20261111-2000-0015"
        val good = "20261111-2000-0016"
        record(sealed(), bad, "Safe 1234").close(5_000L)
        record(sealed(), good, "Still here").close(5_000L)
        val s = sealed()
        s.list()
        val snap = File(root(), "$bad/${TranscriptFiles.SNAPSHOT}")
        val t = snap.readText().trimEnd()
        val i = t.length * 3 / 4
        snap.writeText(t.substring(0, i) + (if (t[i] == 'A') 'B' else 'A') + t.substring(i + 1))
        val names = listOf(TranscriptFiles.META, TranscriptFiles.SNAPSHOT, TranscriptFiles.LOG)
        assertNull(s.backupFolder(File(root(), bad), names))
        assertEquals(1, s.asideCount())
        assertFalse(File(root(), bad).exists())
        assertEquals(names.toSet(), s.backupFolder(File(root(), good), names)!!.keys)
    }

    @Test fun the_unlock_key_is_not_used_from_ram_while_locked() {
        val id = "20261111-1400-0008"
        val s = sealed()
        record(s, id, "Door code 9182").close(5_000L)
        assertNotNull(s.load(id)) // its data key now in RAM
        ks.locked = true // the phone locked, the screen not off yet (the lock delay, Smart Lock)
        assertNull("never from RAM while locked", s.load(id))
        s.seal!!.drop() // the screen went off
        assertNull(s.load(id))
        ks.locked = false
        assertEquals(listOf("Door code 9182"), s.load(id)!!.lines.map { it.text })
    }

    @Test fun a_lost_unlock_key_seals_again_what_still_opens() {
        val old = "20261111-1500-0009"
        record(sealed(), old, "Old one").close(5_000L)
        File(root(), old).deleteRecursively() // the unlock key's file stays
        ks.locked = true
        val b = "20261111-1501-0010"
        record(sealed(), b, "Buy milk").close(6_000L) // the lock screen's key
        ks.locked = false
        val c = "20261111-1502-0011"
        record(TranscriptStore(root()), c, "Plain words").close(7_000L) // an older Robot's
        ks.gone += TranscriptKeyAliases.UNLOCKED
        val after = sealed()
        assertEquals(setOf(b, c), after.list().map { it.id }.toSet())
        assertEquals(0, after.asideCount())
        assertTrue(TranscriptKeyAliases.UNLOCKED in ks.deleted)
        assertEquals(setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(b))
        assertNoPlainWords(c, "Plain words")
        assertEquals(listOf("Buy milk"), sealed().load(b)!!.lines.map { it.text })
        assertEquals(listOf("Plain words"), sealed().load(c)!!.lines.map { it.text })
    }

    @Test fun backup_then_restore_while_locked() {
        val id = "20261111-1600-0012"
        val s = sealed()
        record(s, id, "Pharmacy at four", "Then the bank").close(5_000L)
        s.list()
        val d = File(root(), id)
        // The backup's copy is plain (the bundle has its own seal); a restore stages it, then seals it where it is.
        val staging = File(tmp.root, "staging")
        for (f in d.listFiles { f -> f.isFile && !f.name.startsWith(".") }!!) File(staging, "$id/${f.name}").apply { parentFile!!.mkdirs() }.writeBytes(s.backupBytes(d, f.name)!!)
        assertTrue(File(staging, "$id/${TranscriptFiles.LOG}").readText().contains("Pharmacy at four"))
        d.deleteRecursively()
        ks.locked = true // the restore finishes on a locked phone: sealed under the lock screen's key
        assertTrue(s.sealStaged(staging))
        assertTrue(File(staging, id).renameTo(d))
        assertNoPlainWords(id, "Pharmacy at four", "Then the bank")
        assertEquals(setOf<String?>(TranscriptKeyAliases.LOCK_SCREEN), aliases(id))
        assertEquals(listOf("Pharmacy at four", "Then the bank"), sealed().load(id)!!.lines.map { it.text })
        ks.locked = false
        sealed().list()
        assertEquals(setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(id))
        assertEquals(listOf("Pharmacy at four", "Then the bank"), sealed().load(id)!!.lines.map { it.text })
    }

    /** fix-seal-transcripts-c, Low 2: a re-sealed copy that doesn't read back sets nothing aside; the next list seals it. */
    @Test fun a_failed_read_back_sets_nothing_aside() {
        val id = "20261111-1700-0021"
        val s = sealed()
        ks.locked = true
        record(s, id, "Gate code 4411").close(5_000L)
        ks.locked = false
        s.readBack = { f -> if (f.name.contains(TranscriptFiles.LOG)) readOrNull(f) else "" } // meta.json and the snapshot: an empty read
        s.list()
        assertEquals(0, s.asideCount())
        assertTrue(File(root(), id).isDirectory)
        s.readBack = ::readOrNull
        s.list()
        assertEquals(setOf<String?>(TranscriptKeyAliases.UNLOCKED), aliases(id))
        assertEquals(listOf("Gate code 4411"), sealed().load(id)!!.lines.map { it.text })
    }

    /** A sealed line as a newer Robot's frame would be: its version byte raised, and not this Robot's with it put back. */
    private fun newer(line: String): String {
        val b = java.util.Base64.getDecoder().decode(line.trimEnd().removePrefix(TranscriptSeal.MARK))
        b[4] = 2
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        return TranscriptSeal.MARK + java.util.Base64.getEncoder().encodeToString(b)
    }

    /** fix-seal-transcripts-c, Low 3: a newer Robot's meta.json is left as it is, unread, never set aside, and said so. */
    @Test fun a_newer_robots_record_is_left_as_it_is() {
        val id = "20261111-1700-0022"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        val meta = File(root(), "$id/${TranscriptFiles.META}")
        val written = newer(meta.readText())
        meta.writeText(written)
        val seal = TranscriptSeal(root(), ks::cipher, ks::delete) { ks.locked }
        try { seal.open("$id/${TranscriptFiles.META}", written); fail("opened") } catch (e: MemoryKeyException) { assertTrue(e.toString(), e is TranscriptTooNew) }
        sealed().list()
        assertEquals(0, s.asideCount())
        assertEquals(written, meta.readText())
        assertNull(s.backupFolder(File(root(), id), listOf(TranscriptFiles.META, TranscriptFiles.SNAPSHOT)))
        assertEquals(0, s.asideCount())
    }

    /** fix-seal-transcripts-c, Low 4: the phone unlocked in the middle of a read is not a Keystore fault. */
    @Test fun an_unlock_mid_read_is_not_a_fault() {
        val id = "20261111-1700-0023"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        val line = File(root(), "$id/${TranscriptFiles.META}").readText()
        var asks = 0
        val seal = TranscriptSeal(root(), ks::cipher, ks::delete) { asks++ == 0 } // locked when the read began, unlocked after
        try { seal.reading { seal.open("$id/${TranscriptFiles.META}", line) }; fail("opened") } catch (e: MemoryKeyException) {
            assertEquals(KeyProblem.LOCKED, e.problem)
            assertFalse(e.toString(), e is TranscriptKeyFault)
        }
    }

    /** fix-seal-transcripts-c, Low 6: a restore's staged folders are sealed under the cross-process lock. */
    @Test fun staged_folders_are_sealed_under_the_lock() {
        val id = "20261111-1700-0024"
        val staging = File(tmp.root, "staging")
        record(TranscriptStore(staging), id, "Pharmacy at four").close(5_000L) // a backup's plain copy, staged
        val lock = File(root(), ".seal.lock")
        var held: Boolean? = null
        val s = TranscriptStore(root(), TranscriptSeal(root(), { a, create ->
            if (create && held == null) held = try {
                java.io.RandomAccessFile(lock, "rw").use { it.channel.tryLock()?.release() }; false
            } catch (_: java.nio.channels.OverlappingFileLockException) { true }
            ks.cipher(a, create)
        }, ks::delete) { ks.locked })
        assertTrue(s.sealStaged(staging))
        assertEquals(true, held)
    }

    /** fix-seal-transcripts-c, Low 5: a transcript that won't open on an unlocked phone says why; a locked phone's says nothing. */
    @Test fun a_transcript_that_wont_open_says_why() {
        val id = "20261111-1700-0025"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        assertNull(s.unopened(id))
        val snap = File(root(), "$id/${TranscriptFiles.SNAPSHOT}")
        val was = snap.readText()
        snap.writeText(newer(was))
        assertEquals(TranscriptStore.Unopened.NEWER, s.unopened(id))
        snap.writeText(was)
        ks.faulty += TranscriptKeyAliases.UNLOCKED
        assertEquals(TranscriptStore.Unopened.FAILED, sealed().unopened(id))
        ks.faulty.clear()
        ks.locked = true
        assertNull(sealed().unopened(id))
    }

    /** fix-seal-transcripts-d, Low 1: a newer Robot's transcript is counted (TranscriptStore.newer) and a Replace restore keeps it. */
    @Test fun a_newer_robots_transcript_is_counted_and_kept_by_replace() {
        val id = "20261111-1700-0026"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        assertTrue(s.newer().isEmpty())
        val meta = File(root(), "$id/${TranscriptFiles.META}")
        meta.writeText(newer(meta.readText()))
        assertEquals(setOf(id), sealed().newer())
        val plan = app.heyrobot.core.backup.RestoreMerge.transcripts(setOf(id, "20261111-1700-0027"), setOf(id, "20261111-1700-0028"),
            app.heyrobot.core.backup.RestoreMode.REPLACE, sealed().newer())
        assertEquals(setOf("20261111-1700-0027"), plan.delete)
        assertEquals(setOf("20261111-1700-0028"), plan.write)
        assertEquals(1, plan.keptHere)
    }

    /** fix-seal-transcripts-d, Low 2: a damaged snapshot says it is damaged, never "restart the phone" (FAILED). */
    @Test fun a_damaged_transcript_says_it_is_damaged() {
        val id = "20261111-1700-0029"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        val snap = File(root(), "$id/${TranscriptFiles.SNAPSHOT}")
        val b = java.util.Base64.getDecoder().decode(snap.readText().trimEnd().removePrefix(TranscriptSeal.MARK))
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        snap.writeText(TranscriptSeal.MARK + java.util.Base64.getEncoder().encodeToString(b))
        assertEquals(TranscriptStore.Unopened.DAMAGED, sealed().unopened(id))
    }

    /** fix-seal-transcripts-d, Low 3: a newer Robot's key file is a newer record on an unlocked phone, never a Keystore fault. */
    @Test fun a_newer_key_file_is_not_a_fault() {
        val id = "20261111-1700-0030"
        val s = sealed()
        record(s, id, "Gate code 4411").close(5_000L)
        s.list()
        val key = File(root(), ".${TranscriptKeyAliases.UNLOCKED}.key")
        val b = key.readBytes()
        b[4] = (b[4] + 1).toByte()
        b[b.size - 1] = (b[b.size - 1].toInt() xor 1).toByte()
        key.writeBytes(b)
        val again = sealed()
        assertNull(again.backupFolder(File(root(), id), listOf(TranscriptFiles.META, TranscriptFiles.SNAPSHOT)))
        assertEquals(TranscriptStore.Unopened.NEWER, again.unopened(id))
        assertEquals(setOf(id), again.newer())
        assertEquals(0, again.asideCount())
    }
}
