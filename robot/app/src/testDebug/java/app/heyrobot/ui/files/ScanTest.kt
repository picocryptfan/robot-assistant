// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.IntakeProblem
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseOutput
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.parse.ParsedPixels
import app.heyrobot.core.parse.ScanQuad
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.IntakeStep
import app.heyrobot.sys.KeystoreFileKeys
import app.heyrobot.sys.SoftFileKeystore
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.Collections

/**
 * files-1.4-plan.md §3.2, D4, D19 (F5): scanning. The camera's photo is read into RAM and its file deleted; its preview
 * comes from :files as pixels; the corners you drag must make a page before Use this page works; :files straightens each
 * page and makes one PDF of them, which lands in Inbox as a scan; at most 20 pages (or 64 MiB of them); a reader that
 * stopped, a photo it can't read and a closed gate are said; a duplicate is asked about, and closing the screen discards
 * it. The screen in English and Québec French, with goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class ScanTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    /** A page-like picture: a light sheet on a dark table, RGBA as :files sends it. */
    private fun pixels(w: Int = 300, h: Int = 400): ParsedPixels {
        val b = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val page = x in 30..270 && y in 40..360
            val v = if (page) 236 else 70
            val i = (y * w + x) * 4
            b[i] = v.toByte(); b[i + 1] = v.toByte(); b[i + 2] = (if (page) 228 else 64).toByte(); b[i + 3] = 0xFF.toByte()
        }
        return ParsedPixels(w, h, b)
    }

    private fun ok(px: ParsedPixels? = null, scan: ByteArray? = null, pdf: ByteArray? = null) =
        ParseResult.Ok(ParseOutput(FileType.JPEG, 1, 300, 400, null, null, 0, pixels = px, scan = scan, pdf = pdf))

    private inner class Reader : ScanReader {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        var down = false
        var pageSize = 1_000
        var lastQuad: ScanQuad? = null
        var pdfPages = 0
        var ended = 0
        override fun preview(photo: ByteArray): ParseResult { calls += "preview"; return if (down) ParseResult.Failed(ParseFailure.CRASHED) else ok(px = pixels()) }
        override fun straighten(photo: ByteArray, quad: ScanQuad): ParseResult {
            calls += "straighten"
            lastQuad = quad
            return ok(scan = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()) + ByteArray(pageSize))
        }
        override fun pdf(pages: List<ByteArray>): ParseResult { calls += "pdf"; pdfPages = pages.size; return ok(pdf = "%PDF-1.7 scan of ${pages.size}".toByteArray()) }
        override fun end() { ended++ }
    }

    private val reader = Reader()
    private var gate: Phrase? = null
    private var changed = 0
    private val adds = ArrayList<FileIntake>()
    private var next: ((ByteArray, FileIntake) -> IntakeStep)? = null
    private val dropped = ArrayList<IntakeStep.Duplicate>()

    private fun model(): ScanModel = ScanModel(reader, gate = { gate }, add = { bytes, intake ->
        val i = intake(FileType.PDF)
        adds += i
        next?.invoke(bytes, i) ?: IntakeStep.Failed(IntakeProblem.IO)
    }, anyway = { IntakeStep.Failed(IntakeProblem.IO) }, drop = { dropped += it }, changed = { changed++ }, langs = { Langs.EN }, now = { 1_792_000_000_000L })

    private fun waitUi(m: ScanModel, ok: (ScanUi) -> Boolean): ScanUi {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            if (ok(m.ui)) return m.ui
            Thread.sleep(10)
        }
        throw AssertionError("ui is ${m.ui}")
    }

    private fun corners(m: ScanModel) = waitUi(m) { it is ScanUi.Corners } as ScanUi.Corners
    private fun ready(m: ScanModel) = waitUi(m) { it is ScanUi.Ready } as ScanUi.Ready

    /** A real vault, for the scan's entry. */
    private val vault by lazy { FilesVault(File(tmp.root, "files"), KeystoreFileKeys(SoftFileKeystore()), "main", space = { 100L shl 30 }, locked = { false }) }

    @Test fun pagesInOneScanThatLandsInInbox() {
        val intake = FilesIntake(vault)
        next = { bytes, i -> intake.add(bytes) { i } }
        val m = model()
        assertEquals(0, (m.ui as ScanUi.Ready).pages)
        m.photo(byteArrayOf(1, 2, 3))
        val c = corners(m)
        assertEquals(ScanModel.START, c.corners)
        assertTrue(m.valid(c))
        // Corners that cross aren't a page: Use this page waits.
        m.move(0, 0.9f, 0.9f)
        m.move(2, 0.1f, 0.1f)
        assertFalse(m.valid(m.ui as ScanUi.Corners))
        m.use()
        assertEquals(FilesStrings.scanCorners(), m.note)
        assertEquals(listOf("preview"), reader.calls)
        m.move(0, 0.1f, 0.12f)
        m.move(2, 0.88f, 0.9f)
        assertTrue(m.valid(m.ui as ScanUi.Corners))
        m.use()
        assertEquals(1, ready(m).pages)
        assertEquals(listOf(0.1f, 0.12f, 0.94f, 0.06f, 0.88f, 0.9f, 0.06f, 0.94f), (0 until 8).map { i ->
            Math.round(reader.lastQuad!!.points[i] / 10_000.0).toFloat() / 100f })
        // A second page, then the scan.
        m.photo(byteArrayOf(4, 5))
        corners(m)
        m.use()
        assertEquals(2, ready(m).pages)
        m.finish()
        val done = waitUi(m) { it is ScanUi.Done } as ScanUi.Done
        assertEquals("Added to Robot Files. It's in your Inbox.", done.said.render(Langs.EN))
        assertEquals(2, reader.pdfPages)
        assertEquals(1, changed)
        val e = (vault.load() as IndexLoad.Loaded).index.entries.single()
        assertEquals(FileSource.SCAN, e.from.source)
        assertEquals("application/pdf", e.from.type)
        assertTrue(e.name, e.name.startsWith("Scan 2026-10-14 ") && e.name.endsWith(".pdf"))
        assertArrayEquals("%PDF-1.7 scan of 2".toByteArray(), vault.open(e.id).use { it.readAll(1_000) })
        m.close()
        Thread.sleep(100)
        assertEquals(1, reader.ended)
    }

    @Test fun retakeDropsThePhoto() {
        val m = model()
        m.photo(byteArrayOf(1))
        corners(m)
        m.retake()
        assertEquals(0, (m.ui as ScanUi.Ready).pages)
        assertEquals(listOf("preview"), reader.calls)
    }

    @Test fun aReaderThatStoppedAndAPhotoTooBigAreSaid() {
        reader.down = true
        val m = model()
        m.photo(byteArrayOf(1))
        waitUi(m) { it is ScanUi.Ready && m.note != null }
        assertEquals(FilesStrings.scanReaderDown(), m.note)
        reader.down = false
        val m2 = model()
        m2.photo(ByteArray(ParseLimits.MAX_INPUT + 1))
        assertEquals(FilesStrings.scanUnreadable(), m2.note)
        assertEquals(listOf("preview"), reader.calls)
    }

    @Test fun twentyPagesOrSixtyFourMegabytesAtMost() {
        val m = model()
        repeat(ParseLimits.MAX_SCAN_PAGES) {
            assertTrue(m.mayTake())
            m.photo(byteArrayOf(1))
            corners(m)
            m.use()
            ready(m)
        }
        assertTrue((m.ui as ScanUi.Ready).full)
        assertFalse(m.mayTake())
        // Large pages fill it sooner: their JPEGs together stay within what :files reads at once.
        reader.pageSize = ParseLimits.MAX_SCAN_JPEG - 3
        val big = model()
        var n = 0
        while (big.mayTake()) {
            big.photo(byteArrayOf(1))
            corners(big)
            big.use()
            ready(big)
            n++
        }
        assertEquals(8, n)
    }

    @Test fun aClosedGateIsSaidAtTheStartAndAtTheEnd() {
        gate = LockStrings.closedNow()
        assertEquals(LockStrings.closedNow(), (model().ui as ScanUi.Refused).why)
        gate = null
        val m = model()
        m.photo(byteArrayOf(1))
        corners(m)
        m.use()
        ready(m)
        gate = FilesStrings.locked()
        m.finish()
        assertEquals(FilesStrings.locked(), m.note)
        assertFalse(reader.calls.contains("pdf"))
    }

    @Test fun aDuplicateScanIsAskedAboutAndDiscardedWhenTheScreenGoes() {
        val intake = FilesIntake(vault)
        next = { bytes, i -> intake.add(bytes) { i } }
        repeat(2) { round ->
            val m = model()
            m.photo(byteArrayOf(1))
            corners(m)
            m.use()
            ready(m)
            m.finish()
            if (round == 0) {
                waitUi(m) { it is ScanUi.Done }
            } else {
                val a = waitUi(m) { it is ScanUi.Asking } as ScanUi.Asking
                assertTrue(a.where.render(Langs.EN), a.where.render(Langs.EN).startsWith("Inbox › Scan 2026-10-14 "))
                m.close()
                Thread.sleep(200)
                assertEquals(1, dropped.size)
            }
        }
        // The dropped one was discarded through the intake's drop when the test's drop is the real one: here it was counted.
        assertEquals(1, (vault.load() as IndexLoad.Loaded).index.entries.size)
    }

    @Test fun theCamerasFileIsReadThenDeleted() {
        val f = File(tmp.root, "page.jpg").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        assertArrayEquals(byteArrayOf(9, 8, 7), ScanModel.take(f))
        assertFalse(f.exists())
        assertNull(ScanModel.take(f))
    }

    // The screen.

    private fun screen(m: ScanModel, dark: Boolean = false) = rule.show(dark = dark) { ScanScreen(m, SnackbarHostState(), onBack = {}, takePhoto = {}) }

    @Test fun theScreen() {
        val m = model()
        screen(m)
        rule.onNodeWithText("Take a photo of the first page, flat and in good light. You set its corners next.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_scan")
        m.photo(byteArrayOf(1))
        rule.waitUntil(5_000) { m.ui is ScanUi.Corners }
        rule.onNodeWithText("Drag the four corners to the corners of the page.").assertExists()
        rule.shot("files_scan_corners")
        m.move(0, 0.9f, 0.9f)
        m.move(2, 0.1f, 0.1f)
        rule.onNodeWithText("Use this page").assertIsNotEnabled()
        m.move(0, 0.1f, 0.1f)
        m.move(2, 0.9f, 0.9f)
        rule.onNodeWithText("Use this page").performClick()
        rule.waitUntil(5_000) { m.ui is ScanUi.Ready }
        rule.onNodeWithText("1 page ready").assertExists()
        rule.onNodeWithText("Add a page").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_scan_pages")
    }

    @Test fun theScreenDark() {
        val m = model()
        m.photo(byteArrayOf(1))
        corners(m)
        screen(m, dark = true)
        rule.shot("files_scan_corners_dark")
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun theScreenInFrench() {
        val m = model()
        screen(m)
        rule.onNodeWithText("Numériser un document").assertExists()
        rule.onNodeWithText("Prends une photo de la première page, à plat et bien éclairée. Tu places ses coins ensuite.").assertExists()
        rule.shot("files_scan_fr")
        m.photo(byteArrayOf(1))
        rule.waitUntil(5_000) { m.ui is ScanUi.Corners }
        rule.onNodeWithText("Fais glisser les quatre coins aux coins de la page.").assertExists()
        rule.onNodeWithText("Garder cette page").performClick()
        rule.waitUntil(5_000) { m.ui is ScanUi.Ready }
        rule.onNodeWithText("1 page prête").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_scan_pages_fr")
    }
}
