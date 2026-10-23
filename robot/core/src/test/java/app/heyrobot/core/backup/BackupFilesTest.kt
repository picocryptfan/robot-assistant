// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.backup

import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileId
import app.heyrobot.core.files.FileIndex
import app.heyrobot.core.files.FileIndexCodec
import app.heyrobot.core.files.FileKind
import app.heyrobot.core.files.FileLevel
import app.heyrobot.core.files.FileLimits
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileState
import app.heyrobot.core.files.FilesSettingsCodec
import app.heyrobot.core.files.Provenance
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * files-1.4-plan.md §2.8 (F11): the FILES category's names and caps (appended last, format 1 unchanged), what verify
 * checks (every file the catalogue names has its bytes, of the size and SHA-256 the reader counts itself; bytes it doesn't
 * name skipped; a newer catalogue or settings left for a newer Robot; a damaged one refusing the category), and the
 * catalogue Back up now writes (only the files that went in whole).
 */
class BackupFilesTest {
    private val receipt = FileId.of(BackupKit.FILE_RECEIPT)!!
    private val photo = FileId.of(BackupKit.FILE_PHOTO)!!

    @Test fun theCategoryIsAppendedWithItsNamesAndCaps() {
        assertEquals(BackupCategory.entries.last(), BackupCategory.FILES)
        assertEquals("files", BackupCategory.FILES.id)
        assertTrue(BackupCategory.FILES.defaultOn)
        assertEquals(RestoreProcess.VOICE, BackupCategory.FILES.writer)
        assertEquals(5_002, BackupCategory.FILES.maxEntries)
        assertEquals(1, BackupFormat.FORMAT)
        // Each entry under what a 1.1 to 1.3 Robot reads through for a category it doesn't know.
        assertEquals(FileLimits.MAX_FILE, BackupCategory.FILES.maxBytes("blob/${receipt.hex}"))
        assertEquals(FileLimits.MAX_INDEX, BackupCategory.FILES.maxBytes("index.json"))
        assertEquals(BackupCaps.SMALL_FILE, BackupCategory.FILES.maxBytes("files_settings.json"))
        for (n in listOf("blob/${receipt.hex}", "index.json", "files_settings.json")) assertTrue(n, BackupCategory.FILES.maxBytes(n)!! < BackupCaps.UNKNOWN_ENTRY)
        assertEquals(FileIndexCodec.VERSION, BackupCategory.FILES.schema("index.json"))
        assertEquals(FilesSettingsCodec.VERSION, BackupCategory.FILES.schema("files_settings.json"))
        // Never a thumbnail, the sealed index, a file's own name, a path out, or an id that isn't one.
        for (n in listOf("thumbs/${receipt.hex}.rf", "blobs/${receipt.hex}.rf", "index.enc", "blob/${receipt.hex}.rf", "blob/${receipt.hex.uppercase()}",
                "blob/../index.json", "blob/", "receipt.pdf", "files_state.json", "files_settings.json/x", "/index.json")) {
            assertFalse(n, BackupCategory.FILES.validName(n))
        }
        // Settings stays at its 8 files: files_settings.json is Files', never a ninth.
        assertFalse(BackupCategory.SETTINGS.validName("files_settings.json"))
        assertEquals(8, BackupCategory.SETTINGS_FILES.size)
    }

    @Test fun aBackupWithFilesVerifiesAndHandsOutItsBytes() {
        val file = BackupKit.encrypt(BackupKit.contentRecords())
        val v = BackupKit.verify(file)
        assertTrue(v.has(BackupCategory.FILES))
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(CategoryCount(4, BackupKit.content().getValue(BackupCategory.FILES).values.sumOf { it.size.toLong() }), v.found[BackupCategory.FILES])
        assertEquals(BackupKit.filesIndex(), v.files)
        assertEquals(FileLevel.ASK, v.filesSettings!!.read.bills)
        val got = LinkedHashMap<String, ByteArray>()
        RestoreReader.read(BackupKit.opener(file), v, BackupKit.key(), setOf(BackupCategory.FILES)) { _, n, d -> got[n] = d.readBytes() }
        assertEquals(listOf("blob/${receipt.hex}", "blob/${photo.hex}", "index.json", "files_settings.json"), got.keys.toList())
        assertArrayEquals(BackupKit.receiptBytes(), got["blob/${receipt.hex}"])
        // Counts only in what the restore keeps around.
        assertFalse(v.toString().contains(BackupKit.MARK))
    }

    private fun verify(build: BackupContainerWriter.() -> Unit) =
        BackupKit.verify(BackupKit.encrypt(BackupKit.records(BackupKit.manifest(BackupCategory.FILES, BackupCategory.NOTES), build)))

    private fun BackupContainerWriter.note() = entry(BackupCategory.NOTES, "a.json", BackupKit.text("{\"v\":1,\"id\":\"a\",\"title\":\"\",\"text\":\"x\",\"created\":1,\"updated\":2}"))
    private fun BackupContainerWriter.blob(id: FileId, b: ByteArray) = entry(BackupCategory.FILES, FilesBackup.blobName(id), b)
    private fun BackupContainerWriter.index(i: FileIndex) = entry(BackupCategory.FILES, FilesBackup.INDEX, FileIndexCodec.encode(i))
    private fun refused(v: VerifiedBackup, why: SkipWhy = SkipWhy.REFUSED) {
        assertEquals(listOf(CategorySkip("files", BackupCategory.FILES, why)), v.skipped)
        assertFalse(v.has(BackupCategory.FILES))
        assertNull(v.files)
        // The rest is restored.
        assertTrue(v.has(BackupCategory.NOTES))
    }

    @Test fun theCatalogueAndTheBytesMustAgree() {
        val idx = BackupKit.filesIndex()
        val r = BackupKit.receiptBytes()
        val p = BackupKit.photoBytes()
        // Each named file's bytes: its size and its SHA-256, as the reader counts them, never as the catalogue says.
        refused(verify { note(); blob(receipt, r); blob(photo, p.copyOf(p.size - 1)); index(idx) })
        refused(verify { note(); blob(receipt, r); blob(photo, p.copyOf().also { it[10] = 9 }); index(idx) })
        refused(verify { note(); blob(receipt, r); index(idx) })
        // Bytes with no catalogue at all.
        refused(verify { note(); blob(receipt, r) })
        // A catalogue whose entry lies about its hash.
        val lying = idx.copy(entries = idx.entries.map { if (it.id == photo) it.copy(from = it.from.copy(sha256 = BackupKit.sha256(r))) else it })
        refused(verify { note(); blob(receipt, r); blob(photo, p); index(lying) })
        // Settings alone are fine.
        val settings = verify { note(); entry(BackupCategory.FILES, FilesBackup.SETTINGS, BackupKit.text("{\"v\":1,\"photos\":\"OFF\"}")) }
        assertTrue(settings.has(BackupCategory.FILES))
        assertNull(settings.files)
        assertEquals(FileLevel.OFF, settings.filesSettings!!.read.photos)
    }

    /** A file the old phone couldn't read whole went in as zeros and isn't named: skipped, the rest restored. */
    @Test fun bytesTheCatalogueDoesntNameAreSkipped() {
        val idx = BackupKit.filesIndex()
        val unnamed = FileId.of("f".repeat(32))!!
        val v = verify { note(); blob(receipt, BackupKit.receiptBytes()); blob(unnamed, ByteArray(100_000)); blob(photo, BackupKit.photoBytes()); index(idx) }
        assertEquals(emptyList<CategorySkip>(), v.skipped)
        assertEquals(listOf(receipt, photo), v.files!!.entries.map { it.id })
    }

    @Test fun newerOrDamagedCataloguesAndSettings() {
        val r = BackupKit.receiptBytes()
        val newer = String(FileIndexCodec.encode(BackupKit.filesIndex()), Charsets.UTF_8).replaceFirst("{\"v\":1,", "{\"v\":2,")
        refused(verify { note(); blob(receipt, r); entry(BackupCategory.FILES, FilesBackup.INDEX, BackupKit.text(newer), schema = null) }, SkipWhy.NEWER)
        refused(verify { note(); entry(BackupCategory.FILES, FilesBackup.INDEX, BackupKit.text("{\"v\":1,\"entries\":[")) })
        refused(verify { note(); entry(BackupCategory.FILES, FilesBackup.INDEX, BackupKit.text("[1,2]")) })
        // One entry it reads only in part (a broken id) refuses the whole catalogue.
        val broken = String(FileIndexCodec.encode(BackupKit.filesIndex()), Charsets.UTF_8).replaceFirst(BackupKit.FILE_PHOTO, "not-an-id")
        refused(verify { note(); blob(receipt, r); entry(BackupCategory.FILES, FilesBackup.INDEX, BackupKit.text(broken)) })
        refused(verify { note(); entry(BackupCategory.FILES, FilesBackup.SETTINGS, BackupKit.text("{\"v\":2}"), schema = null) }, SkipWhy.NEWER)
        refused(verify { note(); entry(BackupCategory.FILES, FilesBackup.SETTINGS, BackupKit.text("{\"v\":1,\"photos\":\"SOMETIMES\"}")) })
        refused(verify { note(); entry(BackupCategory.FILES, FilesBackup.SETTINGS, BackupKit.text("{\"v\":1,\"v\":1}")) })
    }

    @Test fun overFiveThousandFilesIsTooBig() {
        val e = BackupKit.filesIndex().entries.first()
        val many = FileIndex((0..FileLimits.MAX_FILES).map { e.copy(id = FileId.of("%032x".format(it))!!, text = "", fields = emptyMap(), name = "") })
        refused(verify { note(); index(many) }, SkipWhy.TOO_BIG)
    }

    /** What Back up now writes: the files that went in whole, each with what it streamed; damaged ones and (left out) videos never. */
    @Test fun theCatalogueNamesOnlyWhatWentInWhole() {
        val idx = BackupKit.filesIndex()
        val out = FilesBackup.exported(idx, mapOf(receipt to FilesBackup.Streamed(5, "a".repeat(64))))
        assertEquals(listOf(receipt), out.entries.map { it.id })
        assertEquals(5L, out.entries.single().from.size)
        assertEquals("a".repeat(64), out.entries.single().from.sha256)
        assertEquals(idx.extra, out.extra)
        val video = FileEntry(photo, "v", FileKind.OTHER, FileState.INBOX, Provenance(FileSource.SHARE, 1, 10, type = "video/mp4"))
        assertTrue(FilesBackup.video(video))
        assertTrue(FilesBackup.video(video.copy(kind = FileKind.VIDEO, from = video.from.copy(type = null))))
        assertFalse(FilesBackup.carried(video, videos = false))
        assertTrue(FilesBackup.carried(video, videos = true))
        assertFalse(FilesBackup.carried(idx.entries[0].copy(damaged = true), videos = true))
        assertEquals(receipt, FilesBackup.blobId("blob/${receipt.hex}"))
        assertNull(FilesBackup.blobId("index.json"))
    }
}
