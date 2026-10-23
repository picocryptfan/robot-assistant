// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.files

import app.heyrobot.core.seal.SealStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * files-1.4-plan.md §2.2 to §2.7 and §11's FileStoreTest: the store on a real folder (under build/, not /tmp, which is
 * memory on the developer PC) with robot_files_v1 faked in software. Round trips from 0 B to the 200 MiB cap, streamed;
 * paths from ids only; an add and a delete cut at every step and finished by the next start; the index never written over
 * when it can't be read; Start over; thumbnails; caps; low space; a locked, gone and wrong key; two processes at once;
 * nothing of a file on disk in the clear; deleting destroys the key before the bytes.
 */
class FileStoreTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private class Crash : Error()

    private val c = SealStream.CHUNK
    private var free = 100L * FileLimits.GIB
    private lateinit var dir: File
    private val keys = FakeFileKeys()

    private fun store(process: String = "voice", k: FileKeys = keys): FileStore {
        if (!::dir.isInitialized) dir = tmp.newFolder("files")
        return FileStore(dir, k, process, space = { free })
    }

    @After fun noInflight() = FileStore.forgetInflight()

    private fun data(n: Int, seed: Int = n) = Random(seed).nextBytes(n)
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private fun intake(name: String = "receipt.pdf") = FileIntake(name, FileSource.SHARE, app = "org.example.mail", type = "application/pdf")
    private fun FileStore.addBytes(b: ByteArray, name: String = "receipt.pdf"): FileEntry = add(ByteArrayInputStream(b), intake(name), b.size.toLong())
    private fun problem(block: () -> Unit): FileProblem? = try {
        block()
        null
    } catch (x: FileException) {
        x.problem
    }
    private fun loaded(s: FileStore) = (s.load() as IndexLoad.Loaded).index
    private fun files(d: File = dir): List<String> = d.walkTopDown().filter { it.isFile }.map { it.relativeTo(d).path }.sorted().toList()
    private fun blob(id: FileId) = File(dir, "blobs/${id.hex}.rf")

    @Test fun roundTripsFromNothingUp() {
        val s = store()
        for (n in listOf(0, 1, c - 1, c, c + 1, 3 * c + 7, 5 * 1024 * 1024)) {
            val d = data(n)
            val e = s.addBytes(d)
            assertArrayEquals("$n bytes", d, s.readAll(e.id, FileLimits.MAX_FILE))
            assertEquals(n.toLong(), e.from.size)
            assertEquals(sha(d), e.from.sha256)
            assertEquals(FileSeal.sealedSize(n.toLong()), blob(e.id).length())
            assertEquals(FileKind.UNKNOWN, e.kind)
            assertEquals(FileState.INBOX, e.state)
        }
        assertEquals(7, loaded(s).entries.size)
        // Nothing left in incoming/, nothing but sealed files, the lock and the index.
        assertTrue(files().all { it == "files.lock" || it == "index.enc" || Regex("blobs/[0-9a-f]{32}\\.rf").matches(it) })
    }

    /** The cap, streamed: 200 MiB in and out without the file ever in memory; one byte more is refused and leaves nothing. */
    @Test fun theCapIsStreamedAndOneByteMoreIsRefused() {
        val s = store()
        val rt = Runtime.getRuntime()
        fun used(): Long {
            System.gc()
            return rt.totalMemory() - rt.freeMemory()
        }
        val before = used()
        var peak = 0L
        var reads = 0
        val e = s.add(Pattern(FileLimits.MAX_FILE) { if (++reads % 32 == 0) peak = maxOf(peak, used() - before) }, intake("video.mp4"))
        assertEquals(FileLimits.MAX_FILE, e.from.size)
        val md = MessageDigest.getInstance("SHA-256")
        val p = Pattern(FileLimits.MAX_FILE) {}
        val want = ByteArray(1 shl 20)
        val got = ByteArray(1 shl 20)
        s.open(e.id).use { r ->
            var total = 0L
            while (true) {
                val k = r.readNBytes(got, 0, got.size)
                if (k <= 0) break
                assertEquals(k, p.readNBytes(want, 0, k))
                assertTrue(want.copyOf(k).contentEquals(got.copyOf(k)))
                md.update(got, 0, k)
                total += k
                if (total % (32 * FileLimits.MIB) == 0L) peak = maxOf(peak, used() - before)
            }
            assertEquals(FileLimits.MAX_FILE, total)
        }
        assertEquals(e.from.sha256, md.digest().joinToString("") { "%02x".format(it) })
        assertTrue("heap grew by $peak bytes", peak < 32 * FileLimits.MIB)
        val index = File(dir, "index.enc").readBytes()
        // One byte over: refused while streaming, its partial file removed, the index as it was.
        assertEquals(FileProblem.TOO_BIG, problem { s.add(Pattern(FileLimits.MAX_FILE + 1) {}, intake()) })
        assertArrayEquals(index, File(dir, "index.enc").readBytes())
        assertEquals(setOf("files.lock", "index.enc", "blobs/${e.id.hex}.rf"), files().toSet())
        assertTrue(File(dir, "incoming/voice").listFiles().orEmpty().isEmpty())
        // A sender that claims more than the cap is refused before a byte is read.
        val untouched = Pattern(10) {}
        assertEquals(FileProblem.TOO_BIG, problem { s.add(untouched, intake(), declared = FileLimits.MAX_FILE + 1) })
        assertEquals(0L, untouched.pos)
        s.delete(listOf(e.id))
    }

    /** A sender's name is a value in the index, cleaned; no path is ever made from it. */
    @Test fun pathsComeOnlyFromIds() {
        val s = store()
        val evil = "../../../etc/passwd\u0000\nreceipt‮fdp.exe  " + "x".repeat(300)
        val e = s.add(ByteArrayInputStream(data(10)), FileIntake(evil, FileSource.SHARE, app = "../not a package", type = "TEXT/../x"))
        assertTrue(files().all { it == "files.lock" || it == "index.enc" || it == "blobs/${e.id.hex}.rf" })
        assertEquals(FileIndexCodec.MAX_NAME, e.name.length)
        assertTrue(e.name.startsWith("../../../etc/passwd receipt fdp.exe x"))
        assertTrue(FileIndexCodec.plain(e.name))
        assertNull(e.from.app)
        assertNull(e.from.type)
        assertEquals("a b", FileIndexCodec.cleanName("  a\t‏b\uD800 "))
        // Every listing skips names that aren't ids: nothing is read, moved or deleted by a name it doesn't know.
        File(dir, "blobs/notes.txt").writeText("mine")
        File(dir, "blobs/${e.id.hex.uppercase()}.rf").writeText("x")
        assertEquals(0, s.recover().adopted)
        assertTrue(File(dir, "blobs/notes.txt").isFile && File(dir, "blobs/${e.id.hex.uppercase()}.rf").isFile)
        // An id is 32 lower-case hex digits and nothing else.
        for (bad in listOf(null, "", "../x", "0123456789ABCDEF0123456789abcdef", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdef0"))
            assertNull(bad, FileId.of(bad))
    }

    @Test fun nothingOfAFileIsOnDiskInTheClear() {
        val s = store()
        val secret = "zq9marker receipt: card 4111 1111 1111 1111".toByteArray()
        val e = s.add(ByteArrayInputStream(secret), FileIntake("zq9marker2 Costco.pdf", FileSource.SHARE))
        s.putThumb(e.id, "zq9marker3 thumbnail".toByteArray())
        s.update { i -> i.copy(entries = i.entries.map { it.copy(text = "zq9marker4 text", tags = listOf("zq9marker5")) }) }
        s.delete(listOf(s.addBytes("zq9marker6".toByteArray(), "zq9marker7").id))
        for (f in dir.walkTopDown().filter { it.isFile }) {
            val bytes = String(f.readBytes(), Charsets.ISO_8859_1)
            assertFalse(f.path, bytes.contains("zq9marker"))
            assertFalse(f.path, bytes.contains("4111 1111 1111 1111"))
        }
        assertTrue(e.toString() + loaded(s), !(e.toString() + loaded(s)).contains("zq9marker"))
    }

    // The index can't be read: never written over.

    @Test fun aLockedPhoneChangesNothing() {
        val s = store()
        val e = s.addBytes(data(100))
        val before = File(dir, "index.enc").readBytes()
        keys.refuse = FileKeyProblem.LOCKED
        s.drop()
        assertEquals(IndexLoad.Locked, s.load())
        assertEquals(FileProblem.LOCKED, problem { s.addBytes(data(5)) })
        assertEquals(FileProblem.LOCKED, problem { s.readAll(e.id, 1000) })
        assertEquals(IndexLoad.Locked, s.update { it.copy(entries = emptyList()) })
        assertNull(s.startOver())
        val r = s.recover()
        assertTrue(r.waiting)
        assertArrayEquals(before, File(dir, "index.enc").readBytes())
        assertTrue(blob(e.id).isFile)
        assertTrue(File(dir, "incoming/voice").listFiles().orEmpty().isEmpty())
        // A Keystore that throws something of its own: the same "not now".
        keys.refuse = null
        keys.boom = true
        s.drop()
        assertEquals(IndexLoad.Locked, s.load())
        keys.boom = false
        assertArrayEquals(data(100), s.readAll(e.id, 1000))
    }

    @Test fun aGoneKeyKeepsEveryFileUntilDeleteThemAll() {
        val s = store()
        val ids = (1..3).map { s.addBytes(data(1000, it)).id }
        s.putThumb(ids[0], data(50))
        keys.master = null
        s.drop()
        assertEquals(IndexLoad.KeyGone, s.load())
        assertEquals(FileProblem.KEY_GONE, problem { s.addBytes(data(5)) })
        assertEquals(FileProblem.KEY_GONE, problem { s.readAll(ids[0], 5000) })
        assertNull(s.startOver())
        s.recover()
        assertTrue(ids.all { blob(it).isFile })
        // The same with an invalidated key (KeyPermanentlyInvalidatedException).
        keys.master = ByteArray(32)
        keys.refuse = FileKeyProblem.GONE
        s.drop()
        assertEquals(IndexLoad.KeyGone, s.load())
        keys.refuse = null
        keys.master = null
        // Delete them all: every file, the index, and the key; the next file starts a new cabinet with a new key.
        s.deleteAll()
        assertEquals(listOf("files.lock"), files())
        assertEquals(IndexLoad.Empty, s.load())
        val e = s.addBytes(data(7))
        assertEquals(1, keys.created)
        assertArrayEquals(data(7), s.readAll(e.id, 100))
    }

    @Test fun aDamagedIndexIsNeverWrittenOverAndStartOverRebuildsIt() {
        val s = store()
        val d = (1..4).map { data(2 * c + it, it) }
        val es = d.map { s.addBytes(it, "file $it") }
        s.putThumb(es[0].id, data(10))
        // One blob is damaged too.
        blob(es[3].id).let { f -> f.writeBytes(f.readBytes().also { it[200] = (it[200].toInt() xor 1).toByte() }) }
        val index = File(dir, "index.enc")
        val damaged = index.readBytes().also { it[150] = (it[150].toInt() xor 1).toByte() }
        index.writeBytes(damaged)
        s.drop()
        assertEquals(IndexLoad.Unreadable, s.load())
        assertEquals(FileProblem.INDEX, problem { s.addBytes(data(5)) })
        assertEquals(IndexLoad.Unreadable, s.update { it })
        s.recover()
        assertArrayEquals(damaged, index.readBytes())
        // Start over: the damaged list kept aside as it was, a new one made from the sealed files (names lost).
        val r = s.startOver()!!
        assertArrayEquals(damaged, File(dir, "index.enc.bad").readBytes())
        assertEquals(4, r.adopted)
        val i = loaded(s)
        assertEquals(es.map { it.id }.toSet(), i.ids)
        for ((k, e) in es.withIndex()) {
            val x = i.entry(e.id)!!
            assertEquals("", x.name)
            assertEquals(FileSource.RECOVERED, x.from.source)
            assertEquals(FileState.INBOX, x.state)
            if (k < 3) {
                assertEquals(sha(d[k]), x.from.sha256)
                assertEquals(d[k].size.toLong(), x.from.size)
                assertFalse(x.damaged)
                assertArrayEquals(d[k], s.readAll(e.id, FileLimits.MAX_FILE))
            } else {
                // Kept, marked damaged ("This file is damaged", with Delete), never removed.
                assertTrue(x.damaged)
                assertNull(x.from.sha256)
                assertTrue(blob(e.id).isFile)
                assertEquals(FileProblem.DAMAGED, problem { s.readAll(e.id, FileLimits.MAX_FILE) })
            }
        }
        // The thumbnail of a file that is still there stays; Start over again only for an unreadable index.
        assertNotNull(s.thumb(es[0].id))
        assertNull(s.startOver())
    }

    @Test fun aNewerRobotsIndexIsNeverWrittenOver() {
        val s = store()
        s.addBytes(data(10))
        // A newer JSON version, sealed properly.
        sealIndex("{\"v\":2,\"entries\":[],\"new\":true}")
        s.drop()
        assertEquals(IndexLoad.TooNew(2), s.load())
        val before = File(dir, "index.enc").readBytes()
        assertEquals(FileProblem.INDEX, problem { s.addBytes(data(5)) })
        assertEquals(IndexLoad.TooNew(2), s.update { it })
        s.recover()
        assertArrayEquals(before, File(dir, "index.enc").readBytes())
        // A newer seal format.
        val newerSeal = before.copyOf().also { it[9] = 2 }
        File(dir, "index.enc").writeBytes(newerSeal)
        s.drop()
        assertEquals(IndexLoad.TooNew(2), s.load())
        assertArrayEquals(newerSeal, File(dir, "index.enc").readBytes())
        // Start over keeps it aside.
        assertEquals(1, s.startOver()!!.adopted)
        assertArrayEquals(newerSeal, File(dir, "index.enc.bad").readBytes())
    }

    /** An entry dropped as damaged (a bad value in an index this Robot wrote) loses only its name: its file comes back through Inbox. */
    @Test fun aDroppedEntrysFileComesBackThroughInbox() {
        val s = store()
        val a = s.addBytes(data(10, 1), "a")
        val b = s.addBytes(data(20, 2), "b")
        val good = FileIndexCodec.entry(loaded(s).entry(a.id)!!)
        val bad = FileIndexCodec.entry(loaded(s).entry(b.id)!!).replace("\"size\":20", "\"size\":\"twenty\"")
        sealIndex("{\"v\":1,\"entries\":[$good,$bad]}")
        s.drop()
        assertEquals(1, (s.load() as IndexLoad.Loaded).dropped)
        val r = s.recover()
        assertEquals(1, r.adopted)
        val x = loaded(s).entry(b.id)!!
        assertEquals(FileSource.RECOVERED, x.from.source)
        assertEquals(sha(data(20, 2)), x.from.sha256)
        assertEquals("a", loaded(s).entry(a.id)!!.name)
    }

    // An add cut short.

    private fun crashOn(s: FileStore, at: FileStep) {
        s.step = { if (it == at) throw Crash() }
    }

    /** A new process (the old one died): this process's adds under way are forgotten, the RAM index too. */
    private fun restart(process: String = "voice"): FileStore {
        FileStore.forgetInflight()
        return store(process)
    }

    @Test fun anAddCutWhileItsBytesWereWrittenIsRemoved() {
        val s = store()
        s.addBytes(data(5, 9))
        crashOn(s, FileStep.PART_WRITTEN)
        assertTrue(runCatching { s.addBytes(data(3 * c)) }.exceptionOrNull() is Crash)
        assertEquals(1, File(dir, "incoming/voice").listFiles()!!.count { it.name.endsWith(".rf.part") })
        // And one cut inside a chunk, made by hand (a crash between two writes).
        val cut = File(dir, "incoming/voice/${"ab".repeat(16)}.rf.part")
        File(dir, "incoming/voice").listFiles()!!.first().readBytes().copyOf(96 + 1000).let(cut::writeBytes)
        val r = restart().recover()
        assertEquals(2, r.partsRemoved)
        assertEquals(0, r.adopted)
        assertTrue(File(dir, "incoming/voice").listFiles()!!.isEmpty())
        assertEquals(1, loaded(store()).entries.size)
    }

    @Test fun anAddCutBeforeItsEntryLandsInInbox() {
        val s = store()
        s.addBytes(data(5, 9))
        val d = data(2 * c + 3)
        crashOn(s, FileStep.STAGED)
        assertTrue(runCatching { s.addBytes(d, "lost name.pdf") }.exceptionOrNull() is Crash)
        val r = restart().recover()
        assertEquals(1, r.adopted)
        val x = loaded(store()).entries.single { it.from.source == FileSource.RECOVERED }
        assertEquals(sha(d), x.from.sha256)
        assertEquals("", x.name)
        assertArrayEquals(d, store().readAll(x.id, FileLimits.MAX_FILE))
        assertTrue(blob(x.id).isFile && File(dir, "incoming/voice").listFiles()!!.isEmpty())
        // While the key is locked it waits, and is adopted at the next unlock.
        crashOn(s, FileStep.STAGED)
        assertTrue(runCatching { s.addBytes(data(77)) }.exceptionOrNull() is Crash)
        keys.refuse = FileKeyProblem.LOCKED
        val locked = restart().recover()
        assertTrue(locked.waiting)
        assertEquals(1, File(dir, "incoming/voice").listFiles()!!.size)
        keys.refuse = null
        assertEquals(1, restart().recover().adopted)
    }

    @Test fun anAddCutWhileItsEntryWasSealedLandsInInbox() {
        val s = store()
        s.addBytes(data(5, 9))
        val staged = s.stage(ByteArrayInputStream(data(1000)))
        crashOn(s, FileStep.INDEX_SEALED)
        assertTrue(runCatching { s.commit(staged, intake()) }.exceptionOrNull() is Crash)
        assertTrue(File(dir, ".index.enc.tmp").isFile)
        val r = restart().recover()
        assertFalse(File(dir, ".index.enc.tmp").exists())
        assertEquals(1, r.adopted)
        assertEquals(2, loaded(store()).entries.size)
    }

    @Test fun anAddCutAfterItsEntryKeepsItsNameAndMovesIn() {
        val s = store()
        val d = data(3000)
        val staged = s.stage(ByteArrayInputStream(d))
        crashOn(s, FileStep.INDEX_WRITTEN)
        assertTrue(runCatching { s.commit(staged, intake("kept.pdf")) }.exceptionOrNull() is Crash)
        val again = restart()
        // Readable at once from where it is, and moved in by the recovery.
        assertArrayEquals(d, again.readAll(staged.id, 10_000))
        assertEquals(1, again.recover().movedIn)
        assertTrue(blob(staged.id).isFile)
        val x = loaded(again).entry(staged.id)!!
        assertEquals("kept.pdf", x.name)
        assertEquals(FileSource.SHARE, x.from.source)
        // Another process finishes it too (its entry says it was committed).
        val other = store().stage(ByteArrayInputStream(data(10)))
        val s2 = store().also { it.step = { st -> if (st == FileStep.INDEX_WRITTEN) throw Crash() } }
        assertTrue(runCatching { s2.commit(other, intake()) }.exceptionOrNull() is Crash)
        FileStore.forgetInflight()
        assertEquals(1, store("main").recover().movedIn)
        assertTrue(blob(other.id).isFile)
    }

    @Test fun eachProcessFinishesOnlyItsOwnAdds() {
        val voice = store("voice")
        voice.addBytes(data(1))
        val main = store("main")
        val staged = main.stage(ByteArrayInputStream(data(500)))
        // :voice starts while the main app waits on "You already have this file. Add it anyway?": it leaves main's add alone.
        assertEquals(0, store("voice").recover().adopted)
        assertTrue(staged.file.isFile)
        // So does the main app's own recovery while the add is under way in it.
        assertEquals(0, store("main").recover().adopted)
        val e = main.commit(staged, intake("from main"))
        assertEquals("from main", loaded(voice).entry(e.id)!!.name)
        // Main dies with one staged: only main's next start adopts it.
        val lost = main.stage(ByteArrayInputStream(data(600)))
        FileStore.forgetInflight()
        assertEquals(0, store("voice").recover().adopted)
        assertEquals(1, store("main").recover().adopted)
        assertTrue(blob(lost.id).isFile)
        // A whole file in an incoming folder that was cut (made by hand): adopted, marked damaged, never removed.
        val id = FileId.of("cd".repeat(16))!!
        File(dir, "incoming/main/${id.hex}.rf").writeBytes(blob(lost.id).readBytes().copyOf(96 + 50))
        assertEquals(1, store("main").recover().adopted)
        assertTrue(loaded(main).entry(id)!!.damaged)
        assertTrue(blob(id).isFile)
    }

    @Test fun aDiscardedAddLeavesNothing() {
        val s = store()
        val first = s.addBytes(data(100))
        val staged = s.stage(ByteArrayInputStream(data(100)))
        assertEquals(first.id, s.duplicateOf(staged.sha256)!!.id)
        s.discard(staged)
        assertFalse(staged.file.exists())
        assertEquals(1, loaded(s).entries.size)
    }

    // A delete cut short.

    @Test fun aDeleteCutAtEveryStepIsFinishedAndNeverComesBack() {
        for (at in listOf(FileStep.JOURNALED, FileStep.KEYS_DESTROYED, FileStep.UNLINKED, FileStep.ENTRIES_REMOVED)) {
            val s = store()
            val keep = s.addBytes(data(10, 1), "keep")
            val gone = s.addBytes(data(3 * c, 2), "gone")
            s.putThumb(gone.id, data(30))
            crashOn(s, at)
            assertTrue("$at", runCatching { s.delete(listOf(gone.id)) }.exceptionOrNull() is Crash)
            val again = restart()
            // Before the recovery: hidden, and it can't be read.
            assertNull("$at", loaded(again).entry(gone.id))
            assertEquals("$at", FileProblem.MISSING, problem { again.open(gone.id) })
            assertNull(again.thumb(gone.id))
            if (at == FileStep.KEYS_DESTROYED) {
                // Its key went first: what is still on disk can't be opened.
                val left = blob(gone.id).readBytes()
                assertArrayEquals(ByteArray(62), left.copyOfRange(28, 90))
                assertEquals(FileProblem.DAMAGED, problem { FileSeal.readAll(ByteArrayInputStream(left), SealPurpose.FILE, gone.id, keys) })
            }
            val r = again.recover()
            assertEquals("$at", 0, r.adopted)
            assertFalse("$at", blob(gone.id).exists() || File(dir, "thumbs/${gone.id.hex}.rf").exists())
            assertEquals("$at", listOf(keep.id), loaded(again).entries.map { it.id })
            assertFalse("$at", File(dir, "journal.json").exists())
            assertArrayEquals(data(10, 1), again.readAll(keep.id, 100))
            again.deleteAll()
        }
    }

    @Test fun aDeleteWhileLockedDestroysTheBytesAndTheEntryGoesAtUnlock() {
        val s = store()
        val a = s.addBytes(data(100, 1))
        val b = s.addBytes(data(100, 2))
        keys.refuse = FileKeyProblem.LOCKED
        s.drop()
        val d = s.delete(listOf(a.id))
        assertEquals(Deleted(1, waiting = true), d)
        assertFalse(blob(a.id).exists())
        assertTrue(File(dir, "journal.json").readText().contains(a.id.hex))
        // The journal holds ids and nothing else.
        assertEquals("{\"v\":1,\"deleting\":[\"${a.id.hex}\"]}", File(dir, "journal.json").readText())
        keys.refuse = null
        s.drop()
        assertEquals(listOf(b.id), loaded(s).entries.map { it.id })
        val r = s.recover()
        assertEquals(1, r.deletesFinished)
        assertFalse(File(dir, "journal.json").exists())
        assertEquals(listOf(b.id), loaded(s).entries.map { it.id })
        // An id with nothing behind it deletes nothing.
        assertEquals(Deleted(0, false), s.delete(listOf(FileId.of("ee".repeat(16))!!)))
    }

    /**
     * What deleting can and can't do: a copy of the sealed bytes taken before the delete (a raw image of the flash, say)
     * still opens while robot_files_v1 lives on the phone; once the key is gone (Delete them all, a reset) nothing does.
     */
    @Test fun aCopyTakenBeforeTheDeleteNeedsTheKey() {
        val s = store()
        val e = s.addBytes(data(1000))
        val copy = blob(e.id).readBytes()
        s.delete(listOf(e.id))
        assertArrayEquals(data(1000), FileSeal.readAll(ByteArrayInputStream(copy), SealPurpose.FILE, e.id, keys))
        s.deleteAll()
        assertEquals(FileProblem.KEY_GONE, problem { FileSeal.readAll(ByteArrayInputStream(copy), SealPurpose.FILE, e.id, keys) })
    }

    // Thumbnails.

    @Test fun thumbnails() {
        val s = store()
        val e = s.addBytes(data(100))
        assertNull(s.thumb(e.id))
        s.putThumb(e.id, data(FileLimits.MAX_THUMB.toInt(), 1))
        assertArrayEquals(data(FileLimits.MAX_THUMB.toInt(), 1), s.thumb(e.id))
        assertEquals(FileProblem.TOO_BIG, problem { s.putThumb(e.id, ByteArray(FileLimits.MAX_THUMB.toInt() + 1)) })
        assertEquals(FileProblem.MISSING, problem { s.putThumb(FileId.of("12".repeat(16))!!, data(5)) })
        // Its own key and purpose: a thumbnail moved over the file doesn't open as the file.
        File(dir, "thumbs/${e.id.hex}.rf").copyTo(blob(e.id), overwrite = true)
        assertEquals(FileProblem.DAMAGED, problem { s.readAll(e.id, 100_000) })
        // A replacement cut before its rename leaves the old one; the next start removes the leftover.
        val f = s.addBytes(data(100, 5))
        s.putThumb(f.id, data(20, 1))
        crashOn(s, FileStep.THUMB_SEALED)
        assertTrue(runCatching { s.putThumb(f.id, data(20, 2)) }.exceptionOrNull() is Crash)
        assertEquals(1, File(dir, "thumbs").listFiles()!!.count { it.name.startsWith(".") })
        assertEquals(0, restart().recover().thumbsRemoved)
        assertEquals(0, File(dir, "thumbs").listFiles()!!.count { it.name.startsWith(".") })
        assertArrayEquals(data(20, 1), store().thumb(f.id))
        // A thumbnail of nothing goes.
        File(dir, "thumbs/${f.id.hex}.rf").copyTo(File(dir, "thumbs/${"34".repeat(16)}.rf"))
        assertEquals(1, store().recover().thumbsRemoved)
    }

    // Keys.

    @Test fun everySealedStreamGetsANewKey() {
        val keys = FakeFileKeys(present = false)
        val s = store(k = keys)
        val ids = (1..5).map { s.addBytes(data(100, it)).id }
        repeat(3) { n -> s.update { i -> i.copy(entries = i.entries.map { it.copy(text = "t$n") }) } }
        ids.take(2).forEach { s.putThumb(it, data(10)) }
        s.delete(listOf(ids[4]))
        // 1 empty index, 5 blobs and their 5 index writes, 3 updates, 2 thumbnails, 1 delete's index write.
        assertEquals(17, keys.keys.size)
        assertEquals(keys.keys.size, keys.keys.toSet().size)
        // A new cabinet makes robot_files_v1 once, under the lock, with its empty index.
        assertEquals(1, keys.created)
    }

    @Test fun theWrongKeyReadsNothing() {
        val s = store()
        val e = s.addBytes(data(100))
        val other = FileStore(dir, FakeFileKeys(seed = 5), "voice", space = { free })
        assertEquals(IndexLoad.Unreadable, other.load())
        assertEquals(FileProblem.DAMAGED, problem { other.readAll(e.id, 1000) })
        assertEquals(FileProblem.INDEX, problem { other.addBytes(data(1)) })
    }

    // Caps and space.

    @Test fun fiveThousandFilesIsFull() {
        val s = store()
        val e = s.addBytes(data(10))
        val one = FileIndexCodec.entry(loaded(s).entry(e.id)!!)
        fun index(n: Int) = sealIndex((1..n).joinToString(",", "{\"v\":1,\"entries\":[", "]}") { one.replace(e.id.hex, "%032x".format(it)) })
        index(FileLimits.MAX_FILES - 1)
        s.drop()
        val staged = s.stage(ByteArrayInputStream(data(5)))
        // Another process filled the last place meanwhile.
        index(FileLimits.MAX_FILES)
        s.drop()
        assertEquals(FileProblem.FULL, problem { s.commit(staged, intake()) })
        assertFalse(staged.file.exists())
        assertEquals(FileProblem.FULL, problem { s.addBytes(data(5)) })
        assertEquals(FileLimits.MAX_FILES, loaded(s).entries.size)
    }

    @Test fun lowSpaceWritesNothingAndDeletesNothing() {
        val s = store()
        val keep = s.addBytes(data(100))
        val index = File(dir, "index.enc").readBytes()
        // Twice the file plus 1 GiB, before anything is written.
        val size = 10L * FileLimits.MIB
        free = FileLimits.spaceToAdd(size) - 1
        val x = runCatching { s.add(Pattern(size) {}, intake(), declared = size) }.exceptionOrNull() as FileException
        assertEquals(FileProblem.LOW_SPACE, x.problem)
        assertEquals(1L, x.needed)
        free = FileLimits.GIB - 1
        assertEquals(FileProblem.LOW_SPACE, problem { s.addBytes(data(10)) })
        // The space running out while it is written (a sender that said nothing, or lied): stopped, its partial removed.
        free = 100L * FileLimits.GIB
        var reads = 0L
        val draining = Pattern(64 * FileLimits.MIB) { reads++; if (reads > 20) free = FileLimits.GIB - 1 }
        assertEquals(FileProblem.LOW_SPACE, problem { s.add(draining, intake()) })
        // A write that fails (ENOSPC shows as an IOException): low space when the space is low by then, IO otherwise.
        fun failing(lowAfter: Boolean) = object : InputStream() {
            var n = 0
            override fun read(): Int = throw IOException()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (n++ < 3) return len
                if (lowAfter) free = FileLimits.GIB / 2
                throw IOException("No space left on device")
            }
        }
        free = 100L * FileLimits.GIB
        assertEquals(FileProblem.IO, problem { s.add(failing(lowAfter = false), intake()) })
        assertEquals(FileProblem.LOW_SPACE, problem { s.add(failing(lowAfter = true), intake()) })
        assertTrue(File(dir, "incoming/voice").listFiles()!!.isEmpty())
        assertArrayEquals(index, File(dir, "index.enc").readBytes())
        assertArrayEquals(data(100), s.readAll(keep.id, 1000))
        // A thumbnail needs the margin too.
        free = FileLimits.GIB - 1
        assertEquals(FileProblem.LOW_SPACE, problem { s.putThumb(keep.id, data(5)) })
    }

    // The index in two processes.

    @Test fun twoProcessesSeeEachOthersChangesAndLoseNone() {
        val main = store("main")
        val voice = store("voice")
        val a = main.addBytes(data(10, 1), "a")
        assertEquals("a", loaded(voice).entry(a.id)!!.name)
        voice.update { i -> i.copy(entries = i.entries.map { it.copy(name = "renamed", named = true) }) }
        assertEquals("renamed", loaded(main).entry(a.id)!!.name)
        // Adds from both at once, on several threads: every one lands.
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (1..20).map { n -> pool.submit<FileEntry> { (if (n % 2 == 0) main else voice).addBytes(data(1000, n), "n$n") } }
        val added = jobs.map { it.get(60, TimeUnit.SECONDS) }
        pool.shutdown()
        assertEquals(21, loaded(main).entries.size)
        assertEquals(added.map { it.id }.toSet() + a.id, loaded(voice).ids)
        for ((n, e) in added.withIndex()) assertArrayEquals(data(1000, n + 1), voice.readAll(e.id, 2000))
    }

    @Test fun theIndexIsReadOnceAndDroppedOnRequest() {
        val s = store()
        s.addBytes(data(10))
        val before = keys.unwraps
        repeat(5) { s.load() }
        assertEquals(before, keys.unwraps)
        s.drop()
        s.load()
        assertEquals(before + 1, keys.unwraps)
    }

    @Test fun anUpdateMayChangeEntriesButNotWhichFilesThereAre() {
        val s = store()
        val e = s.addBytes(data(10))
        val index = File(dir, "index.enc").readBytes()
        assertEquals(s.load(), s.update { it })
        assertArrayEquals(index, File(dir, "index.enc").readBytes())
        assertTrue(runCatching { s.update { it.copy(entries = emptyList()) } }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { s.update { i -> i.copy(entries = i.entries.map { it.copy(from = it.from.copy(size = 11)) }) } }.exceptionOrNull() is IllegalArgumentException)
        val moved = s.update { i -> i.copy(entries = i.entries.map { it.copy(kind = FileKind.RECEIPT, state = FileState.FILED, drawer = "Receipts") }) }
        assertEquals(FileKind.RECEIPT, (moved as IndexLoad.Loaded).index.entry(e.id)!!.kind)
    }

    private fun sealIndex(json: String) {
        FileOutputStream(File(dir, "index.enc")).use { o ->
            FileSeal.writer(o, SealPurpose.INDEX, FileId.new(SecureRandom()), keys, false, SecureRandom()).use { it.write(json.toByteArray()) }
        }
    }

    /** [total] bytes of a fixed pattern, made as they are read (never held); [onRead] after each read. */
    private class Pattern(private val total: Long, private val onRead: () -> Unit) : InputStream() {
        var pos = 0L

        override fun read(): Int {
            if (pos >= total) return -1
            return byteAt(pos++)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= total) return -1
            val n = minOf(len.toLong(), total - pos, 1L shl 20).toInt()
            for (i in 0 until n) b[off + i] = byteAt(pos + i).toByte()
            pos += n
            onRead()
            return n
        }

        private fun byteAt(p: Long): Int = ((p * 2_654_435_761L) ushr 13).toInt() and 0xFF
    }
}
