// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.wiki

import app.heyrobot.core.ModelMove
import app.heyrobot.core.backup.BackupCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

class WikiDownloadTest {
    private val tmp: File = Files.createTempDirectory("wiki").toFile().apply { deleteOnExit() }
    private val files = File(tmp, "files")
    private val big = 1L shl 50

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A download the size of [s] (sparse), so moveIn's size check passes and only the SHA-256 decides. */
    private fun download(s: WikiSet): File = File(tmp, "out/${s.file}").apply {
        parentFile.mkdirs()
        java.io.RandomAccessFile(this, "rw").use { it.setLength(s.bytes) }
    }

    @Test fun wifiOnly() {
        for (s in WikiSet.entries) {
            val r = WikiDownload.request(s)
            assertFalse(s.id, r.overMetered)
            assertFalse(s.id, r.overRoaming)
            assertTrue(s.id, r.url.startsWith("https://download.kiwix.org/zim/wikipedia/"))
            assertEquals("${WikiDownload.STAGING}/${s.file}", r.path)
        }
    }

    @Test fun pinsAreWhole() {
        for (s in WikiSet.entries) {
            assertTrue(s.id, Regex("[0-9a-f]{64}").matches(s.sha256))
            assertTrue(s.id, s.file.endsWith(".zim") && s.file.contains("_${s.lang}_") && s.bytes > 100_000_000L)
        }
        assertEquals(WikiSet.entries.size, WikiSet.entries.map { it.sha256 }.toSet().size)
    }

    @Test fun tooLittleSpaceNeverStarts() {
        val s = WikiSet.FR_TOP
        val need = WikiDownload.spaceNeeded(s)
        assertEquals(2 * s.bytes + ModelMove.MARGIN, need)
        assertNull(WikiDownload.refuse(s, need, 0, WikiFiles(), files))
        assertEquals(WikiDownload.Refusal.NO_ROOM, WikiDownload.refuse(s, need - 1, 0, WikiFiles(), files))
        assertEquals(WikiDownload.Refusal.NO_ROOM, WikiDownload.refuse(s, need, 1, WikiFiles(), files))
        assertEquals(WikiDownload.Refusal.NO_ROOM, WikiDownload.refuse(s, -1, 0, WikiFiles(), files))
        assertEquals(WikiDownload.Refusal.ALREADY, WikiDownload.refuse(s, big, 0, WikiFiles(downloads = mapOf(s.id to 7L)), files))
    }

    @Test fun wrongSha256NeverMoved() {
        val s = WikiSet.FR_TOP
        val src = download(s)
        assertEquals(WikiDownload.Moved.WRONG_FILE, WikiDownload.moveIn(src, files, s, { big }))
        assertFalse(WikiDownload.target(files, s).exists())
        assertFalse(ModelMove.part(WikiDownload.target(files, s)).exists())
        assertFalse("the wrong download is deleted", src.exists())
        assertFalse(WikiDownload.installed(files, WikiDownload.withInstalled(WikiFiles(), s), s))
    }

    @Test fun wrongSizeNeverMoved() {
        val s = WikiSet.EN_TOP
        val src = File(tmp, "short.zim").apply { writeBytes(ByteArray(10)) }
        assertEquals(WikiDownload.Moved.WRONG_FILE, WikiDownload.moveIn(src, files, s, { big }))
        assertFalse(WikiDownload.target(files, s).exists())
        assertFalse(src.exists())
    }

    @Test fun noRoomToMoveKeepsTheDownload() {
        val s = WikiSet.FR_TOP
        val src = download(s)
        assertEquals(WikiDownload.Moved.NO_ROOM, WikiDownload.moveIn(src, files, s, { s.bytes }))
        assertTrue(src.exists())
        assertFalse(WikiDownload.target(files, s).exists())
        src.delete()
    }

    @Test fun rightFileMovesIn() {
        // ModelMove.copyIn is the one the models use; here its expected hash is the pinned one, so a file of the right
        // bytes moves. A real copy is 150 MB and more, so the check runs on ModelMove directly with a small file.
        val bytes = "zim".toByteArray()
        val src = File(tmp, "small.zim").apply { writeBytes(bytes) }
        val dst = File(files, "wiki/small.zim")
        assertTrue(ModelMove.copyIn(src, dst, sha(bytes), { big }) is ModelMove.Result.Moved)
        assertTrue(dst.isFile)
        assertTrue(ModelMove.copyIn(src, File(files, "wiki/other.zim"), sha("other".toByteArray()), { big }) is ModelMove.Result.Mismatch)
        assertFalse(File(files, "wiki/other.zim").exists())
    }

    @Test fun deleteRemovesTheFileAndItsEntry() {
        val s = WikiSet.FR_TOP
        val t = WikiDownload.target(files, s).apply { parentFile.mkdirs(); java.io.RandomAccessFile(this, "rw").use { it.setLength(s.bytes) } }
        ModelMove.part(t).writeBytes(ByteArray(3))
        val f = WikiDownload.withInstalled(WikiDownload.withInstalled(WikiFiles(downloads = mapOf(s.id to 4L)), s), WikiSet.EN_TOP)
        assertTrue(WikiDownload.installed(files, f, s))
        assertTrue("the move clears its download", s.id !in f.downloads)
        val after = WikiDownload.delete(files, f, s)
        assertFalse(t.exists())
        assertFalse(ModelMove.part(t).exists())
        assertFalse(s.id in after.sets)
        assertTrue(WikiSet.EN_TOP.id in after.sets)
        assertFalse(WikiDownload.installed(files, after, s))
        assertEquals(after, WikiFilesCodec.decode(WikiFilesCodec.encode(after)))
    }

    @Test fun codecRoundTripsAndForgivesDamage() {
        val f = WikiFiles(mapOf("fr_top" to WikiFiles.Installed(WikiSet.FR_TOP.file, WikiSet.FR_TOP.bytes, WikiSet.FR_TOP.sha256)), mapOf("en_top" to 12L))
        assertEquals(f, WikiFilesCodec.decode(WikiFilesCodec.encode(f)))
        assertEquals(WikiFiles(), WikiFilesCodec.decode(null))
        assertEquals(WikiFiles(), WikiFilesCodec.decode("{not json"))
    }

    @Test fun notInAnyBackup() {
        val names = listOf(WikiFilesCodec.FILE, "robot/${WikiFilesCodec.FILE}", "${WikiDownload.DIR}/${WikiSet.EN_TOP.file}", WikiSet.EN_TOP.file)
        // Notes', history's and transcripts' names are "<id>.json" read from their own folders, which wikipedia.json
        // (files/robot) and the copies (files/wiki) aren't in; every other category is checked by name.
        val ownFolders = setOf(BackupCategory.NOTES, BackupCategory.HISTORY, BackupCategory.TRANSCRIPTS)
        for (c in BackupCategory.entries - ownFolders) for (n in names) assertFalse("${c.name}/$n", c.validName(n))
        for (c in ownFolders) assertFalse(c.name, c.validName("${WikiDownload.DIR}/${WikiSet.EN_TOP.file}"))
    }

    @Test
    fun anOldCopyIsDeletedWhenItsPinChanges() {
        val dir = Files.createTempDirectory("wiki-tidy").toFile()
        try {
            val s = WikiSet.entries.first()
            val wiki = File(dir, WikiDownload.DIR).apply { mkdirs() }
            val old = File(wiki, "wikipedia_old_2025-01.zim").apply { writeText("old") }
            val current = WikiDownload.target(dir, s).apply { writeText("new") }
            val part = ModelMove.part(current).apply { writeText("n") }
            val f = WikiFiles(sets = mapOf(s.id to WikiFiles.Installed(old.name, 3, "00"), "gone_set" to WikiFiles.Installed("x.zim", 1, "00")))
            val tidied = WikiDownload.tidy(dir, f)
            assertFalse(old.exists())
            assertTrue(current.exists() && part.exists())
            assertTrue(tidied.sets.isEmpty())
            val kept = WikiFiles(sets = mapOf(s.id to WikiFiles.Installed(s.file, s.bytes, s.sha256)))
            assertEquals(kept, WikiDownload.tidy(dir, kept))
        } finally { dir.deleteRecursively() }
    }
}
