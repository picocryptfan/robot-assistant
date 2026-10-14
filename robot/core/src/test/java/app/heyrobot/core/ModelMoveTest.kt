package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import kotlin.random.Random

/**
 * 1.0.1: a model file comes into Robot's internal storage hashed as it is written; the outside copy is never touched by
 * the copy (the caller deletes it only after the inside one is complete and checked); a tampered file, a lack of room
 * and a copy that stops half-way keep nothing; one that stopped with the app is cleaned up by the next.
 */
class ModelMoveTest {
    @get:Rule val tmp = TemporaryFolder()

    private val bytes = Random(7).nextBytes(20 shl 20)
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun outside() = File(tmp.newFolder("external"), "gemma.litertlm").apply { writeBytes(bytes) }
    private fun inside() = File(tmp.root, "files/models/gemma.litertlm")
    private val plenty = { 100L shl 30 }

    @Test fun theMoveHashesWhatItWrites() {
        val src = outside()
        val seen = mutableListOf<Long>()
        val r = ModelMove.copyIn(src, inside(), sha, plenty) { done, total -> seen += done; assertEquals(bytes.size.toLong(), total) }
        assertEquals(ModelMove.Result.Moved(inside(), sha), r)
        assertArrayEquals(bytes, inside().readBytes())
        assertFalse(ModelMove.part(inside()).exists())
        assertEquals(bytes.size.toLong(), seen.last())
        // The outside copy is left for the caller, who deletes it once the inside one's mark is written.
        assertArrayEquals(bytes, src.readBytes())
        // Your own file has no expected hash before its check: the copy's own is returned.
        val own = File(tmp.root, "files/models/own_model.gguf")
        assertEquals(ModelMove.Result.Moved(own, sha), ModelMove.copyIn(src, own, null, plenty))
    }

    @Test fun aTamperedFileKeepsNothing() {
        val src = outside()
        // Same size, same modification time, one byte changed: what 1.0.0's size-and-time check couldn't see.
        val time = src.lastModified()
        RandomAccessFile(src, "rw").use { it.seek(12_345); it.write(bytes[12_345].toInt() xor 1) }
        src.setLastModified(time)
        val r = ModelMove.copyIn(src, inside(), sha, plenty)
        assertTrue(r is ModelMove.Result.Mismatch)
        assertFalse(inside().exists())
        assertFalse(ModelMove.part(inside()).exists())
        assertTrue(src.isFile)
    }

    @Test fun notEnoughRoomKeepsNothing() {
        val src = outside()
        val size = bytes.size.toLong()
        // To start: the file and the margin, after what downloads under way will still write; unknown free space is no room.
        assertTrue(ModelMove.room(size, size + ModelMove.MARGIN))
        assertFalse(ModelMove.room(size, size + ModelMove.MARGIN - 1))
        assertFalse(ModelMove.room(size, size + ModelMove.MARGIN, pending = 1))
        assertFalse(ModelMove.room(size, -1))
        assertEquals(ModelMove.Result.NoRoom, ModelMove.copyIn(src, inside(), sha, { size + ModelMove.MARGIN - 1 }))
        assertFalse(inside().exists())
        // Running out on the way: stopped, the part deleted.
        var reads = 0
        val shrinking = { if (reads++ == 0) 100L shl 30 else ModelMove.FLOOR - 1 }
        assertEquals(ModelMove.Result.NoRoom, ModelMove.copyIn(src, inside(), sha, shrinking, checkEvery = 1L shl 20))
        assertFalse(inside().exists())
        assertFalse(ModelMove.part(inside()).exists())
        assertArrayEquals(bytes, src.readBytes())
    }

    @Test fun aCopyThatStopsHalfWayKeepsNothingAndTheNextOneFinishes() {
        val src = outside()
        // Stopped after the first block (a delete, a download or an import of that model wins over the move).
        var blocks = 0
        assertTrue(ModelMove.copyIn(src, inside(), sha, plenty, go = { blocks++ < 1 }) is ModelMove.Result.Failed)
        assertFalse(inside().exists())
        assertFalse(ModelMove.part(inside()).exists())
        // An error half-way (the storage failing): the same.
        val failed = ModelMove.copyIn(src, inside(), sha, plenty) { done, _ -> if (done > 0) throw java.io.IOException("I/O error") }
        assertTrue(failed is ModelMove.Result.Failed)
        assertFalse(ModelMove.part(inside()).exists())
        // The app killed half-way leaves a .part behind and no file: the next copy starts over and finishes.
        inside().parentFile!!.mkdirs()
        ModelMove.part(inside()).writeBytes(bytes.copyOf(5 shl 20))
        assertEquals(ModelMove.Result.Moved(inside(), sha), ModelMove.copyIn(src, inside(), sha, plenty))
        assertArrayEquals(bytes, inside().readBytes())
        assertFalse(ModelMove.part(inside()).exists())
        assertArrayEquals(bytes, src.readBytes())
    }

    @Test fun onlyAFileInsideIsTrustedWithoutItsHash() {
        val files = tmp.newFolder("files")
        assertFalse(ModelMove.hashBeforeLoad(File(files, "models/gemma.litertlm"), files))
        assertTrue(ModelMove.hashBeforeLoad(File(tmp.root, "external/gemma.litertlm"), files))
        assertTrue(ModelMove.hashBeforeLoad(File(files, "models/../../external/gemma.litertlm"), files))
        assertTrue(ModelMove.hashBeforeLoad(File(tmp.root, "files-other/gemma.litertlm"), files))
        // The full hash, from a stream or by position from an open file (the :llama process's descriptor).
        val src = outside()
        assertEquals(sha, src.inputStream().use { ModelMove.sha256(it) })
        assertEquals(sha, RandomAccessFile(src, "r").use { ModelMove.sha256(it.channel) })
    }
}
