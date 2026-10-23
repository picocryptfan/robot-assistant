// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileOut
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.FileStore
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.OutgoingCopy
import app.heyrobot.core.parse.ParseOutput
import app.heyrobot.core.parse.ParseResult
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Share and Open with another app (files-1.4-plan.md §5.2, §6.4; F7): a copy written for one hand-off in cache/files_out/,
 * never the sealed file; the original as it streams from the vault, or :files' copy without metadata; nothing made while
 * locked (checked again after the copy is written) and nothing left behind when it fails; the chooser with a read grant
 * on that one URI only (never write, persistable or prefix), never Robot's own share target; taken back (the grant
 * revoked, the copy deleted) when you come back to Robot after it went to the background, for one file when it is
 * deleted, all at once at screen-off and at the next start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FilesOutTest {
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val keystore = SoftFileKeystore()
    private val dir by lazy { File(tmp.root, "files") }
    private var locked = false
    private val vault by lazy { FilesVault(dir, KeystoreFileKeys(keystore), "main", space = { 100L shl 30 }, locked = { locked }) }
    private val bytes = ByteArray(300_000) { (it * 31 + 7).toByte() }

    @Before fun clean() = FilesOut.clear(ctx)
    @After fun after() = FilesOut.clear(ctx)

    private fun add(type: String = "application/pdf") =
        FileStore(dir, KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 }).add(ByteArrayInputStream(bytes), FileIntake("receipt.pdf", FileSource.SHARE, type = type))

    /** [c] as a ready copy, or a failure that says why. */
    private fun ready(c: OutCopy): OutCopy.Ready = c as? OutCopy.Ready ?: throw AssertionError("not made: " + (c as OutCopy.Failed).why)

    private fun copies(): List<File> = FilesOut.dir(ctx).walkTopDown().filter { it.isFile }.toList()

    private val noStrip: (ByteArray) -> ParseResult = { throw AssertionError("not stripped") }

    /** FileProvider's URI for [f] as files_out_paths.xml maps it (Robolectric doesn't read a provider's paths). */
    private val uriOf: (File) -> android.net.Uri = { f ->
        android.net.Uri.parse("content://${FilesOut.AUTHORITY}/out/" + f.relativeTo(FilesOut.dir(ctx)).invariantSeparatorsPath.split('/').joinToString("/") { android.net.Uri.encode(it) })
    }

    @Test fun theOriginalStreamsFromTheVaultIntoItsOwnFolder() {
        val e = add()
        val c = ready(FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.ORIGINAL, "2026-10-18 Costco 84.12 CAD.pdf", "application/pdf", false, { locked }, noStrip, uriOf))
        assertEquals("ai.robot_app.files_out", c.uri.authority)
        assertTrue(c.uri.path!!.endsWith("/2026-10-18%20Costco%2084.12%20CAD.pdf") || c.uri.path!!.endsWith("/2026-10-18 Costco 84.12 CAD.pdf"))
        val f = copies().single()
        assertArrayEquals(bytes, f.readBytes())
        // Its folder is a random one under cache/files_out, never the sealed files' folder.
        assertEquals(FilesOut.dir(ctx), f.parentFile!!.parentFile)
        assertTrue(Regex("[0-9a-f]{32}").matches(f.parentFile!!.name))
        assertFalse(f.canonicalPath.startsWith(dir.canonicalPath))
        assertTrue(FilesOut.out)
    }

    @Test fun aPhotoGoesAsFilesCopyWithoutItsMetadata() {
        val e = add("image/jpeg")
        val stripped = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1, 2, 3)
        var sent: ByteArray? = null
        val c = ready(FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.STRIPPED, "IMG_1.jpg", "image/jpeg", false, { locked }, uriOf = uriOf, strip = { b ->
            sent = b.copyOf()
            ParseResult.Ok(ParseOutput(FileType.JPEG, 1, 10, 10, null, null, 0, copy = stripped))
        }))
        assertArrayEquals(bytes, sent)
        assertTrue(c.stripped)
        assertArrayEquals(stripped, copies().single().readBytes())
        // When :files can't make one, nothing goes and nothing is left.
        FilesOut.clear(ctx)
        val failed = FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.STRIPPED, "IMG_1.jpg", "image/jpeg", false, { locked }, uriOf = uriOf, strip = {
            ParseResult.Failed(app.heyrobot.core.parse.ParseFailure.CRASHED)
        })
        assertEquals(OutFailure.NO_COPY, (failed as OutCopy.Failed).why)
        assertTrue(copies().isEmpty())
    }

    @Test fun nothingWhileLockedNotEvenAfterTheCopy() {
        val e = add()
        locked = true
        assertEquals(OutFailure.LOCKED, (FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.ORIGINAL, "a.pdf", "application/pdf", false, { locked }, noStrip, uriOf) as OutCopy.Failed).why)
        // Locked while it was being written: deleted, nothing handed out.
        locked = false
        var asked = 0
        val late = FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.ORIGINAL, "a.pdf", "application/pdf", false, { asked++ > 0 }, noStrip, uriOf)
        assertEquals(OutFailure.LOCKED, (late as OutCopy.Failed).why)
        assertTrue(copies().isEmpty())
        assertFalse(FilesOut.out)
    }

    @Test fun theChooserGrantsReadOnThatOneUriOnly() {
        val e = add()
        val c = ready(FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.ORIGINAL, "a.pdf", "application/pdf", false, { locked }, noStrip, uriOf))
        for ((chooser, action) in listOf(FilesOut.shareIntent(ctx, c, "Share") to Intent.ACTION_SEND, FilesOut.openIntent(ctx, c, "Open with") to Intent.ACTION_VIEW)) {
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            @Suppress("DEPRECATION") val target = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(action, target.action)
            val bad = Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, target.flags and (bad or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            assertEquals(0, chooser.flags and bad)
            assertEquals(c.uri, target.clipData!!.getItemAt(0).uri)
            assertEquals(1, target.clipData!!.itemCount)
            if (action == Intent.ACTION_SEND) {
                @Suppress("DEPRECATION") assertEquals(c.uri, target.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM))
            } else assertEquals(c.uri, target.data)
            assertEquals("application/pdf", target.type)
            @Suppress("DEPRECATION") val excluded = chooser.getParcelableArrayExtra(Intent.EXTRA_EXCLUDE_COMPONENTS)!!.map { it as ComponentName }
            assertEquals(listOf(ComponentName(ctx.packageName, "app.heyrobot.ui.files.FilesShareActivity")), excluded)
        }
    }

    @Test fun takenBackWhenYouComeBack() {
        val e = add()
        ready(FilesOut.make(ctx, vault, e.id, e.from.size, OutgoingCopy.ORIGINAL, "a.pdf", "application/pdf", false, { locked }, noStrip, uriOf))
        // Resumed without having gone (the chooser cancelled over Robot): still out until the cap, a screen-off or leaving.
        FilesOut.resumed(ctx)
        assertTrue(FilesOut.out)
        assertEquals(1, copies().size)
        // The other app took over, then you came back: revoked and deleted.
        FilesOut.stopped()
        FilesOut.resumed(ctx)
        assertFalse(FilesOut.out)
        assertTrue(copies().isEmpty())
        assertTrue(FilesOut.dir(ctx).listFiles().orEmpty().isEmpty())
    }

    @Test fun oneFilesCopiesWhenItIsDeletedAllAtScreenOff() {
        val a = add()
        val b = FileStore(dir, KeystoreFileKeys(keystore), "voice", space = { 100L shl 30 }).add(ByteArrayInputStream(ByteArray(10) { 1 }), FileIntake("b.pdf", FileSource.SHARE, type = "application/pdf"))
        FilesOut.make(ctx, vault, a.id, a.from.size, OutgoingCopy.ORIGINAL, "a.pdf", "application/pdf", false, { locked }, noStrip, uriOf)
        FilesOut.make(ctx, vault, b.id, b.from.size, OutgoingCopy.ORIGINAL, "b.pdf", "application/pdf", false, { locked }, noStrip, uriOf)
        assertEquals(2, copies().size)
        FilesOut.revoke(ctx, a.id)
        assertEquals(listOf("b.pdf"), copies().map { it.name })
        FilesOut.revokeAll(ctx)
        assertTrue(copies().isEmpty())
        // At the next start, whatever a crash left goes too.
        File(FilesOut.dir(ctx), "0".repeat(32)).apply { mkdirs() }.resolve("left.pdf").writeBytes(byteArrayOf(1))
        FilesOut.clear(ctx)
        assertTrue(FilesOut.dir(ctx).listFiles().orEmpty().isEmpty())
    }

    @Test fun theProviderServesItsFolderAlone() {
        val info = ctx.packageManager.resolveContentProvider(FilesOut.AUTHORITY, 0)!!
        assertEquals("app.heyrobot.sys.FilesOutProvider", info.name)
        assertFalse(info.exported)
        assertTrue(info.grantUriPermissions)
        assertEquals(15 * 60_000L, FileOut.MAX_MS)
    }

    /**
     * FX2 (files-1.4-review.md K3): :voice deletes copies and scans' photos older than 15 minutes, left by a main app Android
     * ended; one still in its 15 minutes stays for the main app to take back.
     */
    @Test fun voiceClearsWhatAnEndedMainLeftAfterFifteenMinutes() {
        val now = 1_800_000_000_000L
        val root = FilesOut.dir(ctx).apply { mkdirs() }
        fun copy(name: String, age: Long) = File(root, name).apply { mkdirs() }.also { d ->
            File(d, "receipt.pdf").apply { writeText("plain"); setLastModified(now - age) }
            d.setLastModified(now - age)
        }
        val old = copy("a".repeat(32), FileOut.MAX_MS + 1_000)
        val young = copy("b".repeat(32), FileOut.MAX_MS - 60_000)
        assertEquals(1, FilesOut.clearStale(ctx, now))
        assertFalse(old.exists())
        assertTrue(young.exists())
        val photo = ScanFiles.photo(ctx).apply { writeText("jpeg"); setLastModified(now - FileOut.MAX_MS - 1) }
        assertEquals(1, ScanFiles.clearStale(ctx, now))
        assertFalse(photo.exists())
        val fresh = ScanFiles.photo(ctx).apply { writeText("jpeg"); setLastModified(now - 1_000) }
        assertEquals(0, ScanFiles.clearStale(ctx, now))
        assertTrue(fresh.exists())
        assertEquals(FileOut.MAX_MS, ScanFiles.STALE_MS)
        FilesOut.clear(ctx)
        ScanFiles.clear(ctx)
    }
}
