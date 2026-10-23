// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.files

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.heyrobot.core.files.FileEntry
import app.heyrobot.core.files.FileIntake
import app.heyrobot.core.files.FileSource
import app.heyrobot.core.files.IndexLoad
import app.heyrobot.core.files.IntakeCopy
import app.heyrobot.core.files.ShareRefusal
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.FilesStrings
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.LockStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.parse.FileType
import app.heyrobot.sys.FilesIntake
import app.heyrobot.sys.FilesVault
import app.heyrobot.sys.IntakeStep
import app.heyrobot.sys.KeystoreFileKeys
import app.heyrobot.sys.ResolverSource
import app.heyrobot.sys.SharedFile
import app.heyrobot.sys.SharedSource
import app.heyrobot.sys.SoftFileKeystore
import app.heyrobot.ui.testing.PHONE
import app.heyrobot.ui.testing.shot
import app.heyrobot.ui.testing.show
import app.heyrobot.ui.testing.touchTargetProblems
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch

/**
 * files-1.4-plan.md §3.1, D3 (F5): "Robot Files" in another app's Share, against what a sending app can do. A receipt lands
 * in Inbox with its provenance after your tap and never before; Robot's own storage, file: and another user's provider
 * are never opened; an endless stream stops at the cap; a provider that lies about its type and size is believed in
 * nothing; many files are taken twenty at a time; a sender that hangs is given up; a share while the phone is locked, during
 * a phone lock or at the tap is refused before anything is read; a duplicate is asked about and nothing staged is left
 * behind whatever the answer or when the window goes. The window in English and Québec French, with goldens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = PHONE)
class FilesShareTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder(File("build/tmp").apply { mkdirs() })

    private val keystore = SoftFileKeystore()
    @Volatile private var locked = false
    private val dir by lazy { File(tmp.root, "files") }
    private val vault by lazy { FilesVault(dir, KeystoreFileKeys(keystore), "main", space = { 100L shl 30 }, locked = { locked }) }
    private val source = FakeSource()
    private val times = IntakeCopy.Times(idleMs = 300, totalMs = 3_000, openMs = 300, stopMs = 500, tickMs = 20)
    private val intake by lazy { FilesIntake(vault, source, times) }

    private val pdf = "%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n%%EOF\n".toByteArray()
    private val zip = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + ByteArray(300_000) { (it % 7).toByte() }

    /** A sender's provider: what it claims, what it sends, and what Robot asked of it. */
    private class FakeSource : SharedSource {
        class F(val name: String?, val size: Long?, val type: String?, val hangClaims: Boolean = false, val hangOpen: Boolean = false, val bytes: () -> InputStream)
        val files = HashMap<Uri, F>()
        val asked: MutableList<Uri> = Collections.synchronizedList(ArrayList())
        val opened: MutableList<Uri> = Collections.synchronizedList(ArrayList())
        val hang = CountDownLatch(1)

        override fun claims(uri: Uri, signal: CancellationSignal): Pair<String?, Long?> {
            asked += uri
            val f = files[uri] ?: throw SecurityException("no grant")
            if (f.hangClaims) hang.await()
            return f.name to f.size
        }

        override fun type(uri: Uri): String? = files[uri]?.type

        override fun open(uri: Uri, signal: CancellationSignal): SharedSource.Opened {
            opened += uri
            val f = files[uri] ?: throw SecurityException("no grant")
            if (f.hangOpen) hang.await()
            return SharedSource.Opened(f.bytes(), -1)
        }
    }

    private fun file(uri: String, name: String?, bytes: ByteArray, size: Long? = bytes.size.toLong(), type: String? = "application/pdf",
        hangClaims: Boolean = false, hangOpen: Boolean = false): Uri = Uri.parse(uri).also {
        source.files[it] = FakeSource.F(name, size, type, hangClaims, hangOpen) { ByteArrayInputStream(bytes) }
    }

    private var gate: Phrase? = null
    private var changed = 0

    private inner class Port : ShareModel.Port {
        override fun gate(): Phrase? = this@FilesShareTest.gate
        override fun refusal(uri: Uri) = FilesIntake.refusal(uri) { it.startsWith("ai.robot_app.") }
        override fun describe(uri: Uri, refusal: ShareRefusal?) = intake.describe(uri, refusal)
        override fun add(f: SharedFile, intake: (FileType) -> FileIntake) = this@FilesShareTest.intake.add(f, intake)
        override fun add(bytes: ByteArray, intake: (FileType) -> FileIntake) = this@FilesShareTest.intake.add(bytes, intake)
        override fun anyway(d: IntakeStep.Duplicate) = intake.anyway(d)
        override fun drop(d: IntakeStep.Duplicate) = intake.drop(d)
        override fun changed() { changed++ }
        override fun langs(): Langs = Langs.EN
        override fun now(): Long = 1_792_000_000_000L
    }

    private fun model(uris: List<Uri>, text: String? = null, subject: String? = null, sender: Pair<String, String?>? = null) =
        ShareModel(Port(), ShareRequest(uris, text, subject, sender))

    private fun waitUi(m: ShareModel, ms: Long = 10_000, ok: (ShareUi) -> Boolean): ShareUi {
        val end = System.nanoTime() + ms * 1_000_000
        while (System.nanoTime() < end) {
            val u = m.ui
            if (ok(u)) return u
            Thread.sleep(10)
        }
        throw AssertionError("ui is ${m.ui}")
    }

    private fun review(m: ShareModel): ShareUi.Review {
        m.start()
        return waitUi(m) { it !is ShareUi.Reading } as ShareUi.Review
    }

    private fun done(m: ShareModel): ShareUi.Done = waitUi(m) { it is ShareUi.Done } as ShareUi.Done

    private fun lines(d: ShareUi.Done) = d.lines.map { it.render(Langs.EN) }
    private fun entries(): List<FileEntry> = (vault.load() as? IndexLoad.Loaded)?.index?.entries.orEmpty()
    private fun incoming(): List<String> = File(dir, "incoming").walk().filter { it.isFile }.map { it.name }.toList()

    @Test fun aReceiptFromAnotherAppLandsInInboxOnlyAfterTheTap() {
        val u = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        val m = model(listOf(u), sender = "org.example.mail" to "Example Mail")
        val r = review(m)
        assertEquals("Shared from Example Mail", r.sender.render(Langs.EN))
        assertEquals("receipt.pdf", r.items.single().file!!.name)
        assertEquals("application/pdf", r.items.single().file!!.type)
        // Before the tap: the sender was asked its claims, and nothing was read or written.
        assertEquals(emptyList<Uri>(), source.opened)
        assertEquals(emptyList<FileEntry>(), entries())
        m.add()
        assertEquals(listOf("Added to Robot Files: 1 file is in your Inbox."), lines(done(m)))
        val e = entries().single()
        assertEquals("receipt.pdf", e.name)
        assertEquals(FileSource.SHARE, e.from.source)
        assertEquals("org.example.mail", e.from.app)
        assertEquals("application/pdf", e.from.type)
        assertEquals(pdf.size.toLong(), e.from.size)
        assertArrayEquals(pdf, vault.open(e.id).use { it.readAll(10_000) })
        assertEquals(1, changed)
        assertEquals(emptyList<String>(), incoming())
    }

    @Test fun aSenderAndroidDidntConfirmIsAnotherApp() {
        val u = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        val m = model(listOf(u), sender = null)
        assertEquals("Shared from another app", review(m).sender.render(Langs.EN))
        m.add()
        done(m)
        assertNull(entries().single().from.app)
    }

    @Test fun robotsOwnStorageFileUrisAndAnotherUsersProviderAreNeverOpened() {
        val own = file("content://ai.robot_app.exports/exports/talk.md", "talk.md", pdf)
        val capture = file("content://ai.robot_app.capture/capture/photo.jpg", "photo.jpg", pdf)
        val path = Uri.parse("file:///data/user/0/ai.robot_app/files/files/index.enc")
        val other = file("content://10@media/external/images/1", "x.jpg", pdf)
        val good = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        val m = model(listOf(own, capture, path, other, good))
        val r = review(m)
        assertEquals(listOf(true, true, true, true, false), r.items.map { it.refused })
        assertEquals(listOf(ShareRefusal.OWN, ShareRefusal.OWN, ShareRefusal.NOT_CONTENT, ShareRefusal.OTHER_USER, null), r.items.map { it.file!!.refusal })
        m.add()
        done(m)
        // Only the other app's file was asked about and opened.
        assertEquals(listOf(good), source.asked.distinct())
        assertEquals(listOf(good), source.opened)
        assertEquals(1, entries().size)
        // A share of nothing but those: refused before anything.
        val m2 = model(listOf(own, path))
        m2.start()
        assertEquals(FilesStrings.notAllowed(), (waitUi(m2) { it is ShareUi.Refused } as ShareUi.Refused).why)
    }

    @Test fun anEndlessStreamStopsAtTheCap() {
        val u = Uri.parse("content://org.example.evil/endless")
        source.files[u] = FakeSource.F("receipt.pdf", 1_000, "application/pdf") {
            object : InputStream() {
                override fun read(): Int = 1
                override fun read(b: ByteArray, off: Int, len: Int): Int { b.fill(1, off, off + len); return len }
            }
        }
        // The copy's own deadlines as on the phone, far longer than the cap takes: the cap must be what ends it.
        val long = FilesIntake(vault, source, IntakeCopy.Times(idleMs = 20_000, totalMs = 120_000, openMs = 15_000, tickMs = 20))
        val m = ShareModel(object : ShareModel.Port by Port() {
            override fun add(f: SharedFile, intake: (FileType) -> FileIntake) = long.add(f, intake)
        }, ShareRequest(listOf(u), null, null, null))
        review(m)
        m.add()
        val d = waitUi(m, 120_000) { it is ShareUi.Done } as ShareUi.Done
        assertEquals(listOf("Nothing was added.", "receipt.pdf: This file is too big for Robot Files: 210 MB at most."), lines(d))
        assertEquals(emptyList<FileEntry>(), entries())
        assertEquals(emptyList<String>(), incoming())
    }

    @Test fun aProviderThatLiesAboutTypeAndSizeIsBelievedInNothing() {
        val u = file("content://org.example.evil/receipt", "receipt.pdf", zip, size = 10, type = "application/pdf")
        val m = model(listOf(u))
        val r = review(m)
        assertEquals(10L, r.items.single().file!!.size)
        m.add()
        assertEquals(listOf("Added to Robot Files: 1 file is in your Inbox.", "receipt.pdf: This could install or run something. Robot won't open it."),
            lines(done(m)))
        val e = entries().single()
        assertEquals("application/zip", e.from.type)
        assertEquals(zip.size.toLong(), e.from.size)
    }

    @Test fun aNameMadeToLookLikeSomethingElseIsCleaned() {
        val u = file("content://org.example.evil/x", "receipt‮fdp.exe\n", pdf, type = "application/pdf‮")
        val f = review(model(listOf(u))).items.single().file!!
        assertEquals("receipt fdp.exe", f.name)
        assertNull(f.type)
    }

    @Test fun manyFilesAreTakenTwentyAtATime() {
        val uris = (1..25).map { file("content://org.example.files/f/$it", "page$it.pdf", pdf + it.toString().toByteArray()) }
        val m = model(uris)
        val r = review(m)
        assertEquals(20, r.items.size)
        assertEquals(5, r.leftOut)
        m.add()
        assertEquals(listOf("Added to Robot Files: 20 files are in your Inbox.", "Robot takes 20 files at a time, so 5 more were left out."), lines(done(m)))
        assertEquals(20, entries().size)
        assertEquals(20, source.opened.size)
    }

    @Test fun aShareWhileLockedOrDuringAPhoneLockIsRefusedBeforeAnythingIsRead() {
        val u = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        gate = FilesStrings.locked()
        val m = model(listOf(u))
        m.start()
        assertEquals("Unlock your phone to use your files.", (m.ui as ShareUi.Refused).why.render(Langs.EN))
        gate = LockStrings.closedNow()
        val m2 = model(listOf(u))
        m2.start()
        assertEquals(LockStrings.closedNow(), (m2.ui as ShareUi.Refused).why)
        assertEquals(emptyList<Uri>(), source.asked)
        // Locked while the window showed: the tap is refused too, and nothing is read.
        gate = null
        val m3 = model(listOf(u))
        review(m3)
        gate = FilesStrings.locked()
        m3.add()
        assertTrue(m3.ui is ShareUi.Refused)
        assertEquals(emptyList<Uri>(), source.opened)
        // The vault's own gate, under the window's: a phone that locks between the check and the copy.
        gate = null
        val m4 = model(listOf(u))
        review(m4)
        locked = true
        m4.add()
        assertEquals(listOf("Nothing was added.", "receipt.pdf: Unlock your phone to use your files."), lines(done(m4)))
        locked = false
        assertEquals(emptyList<FileEntry>(), entries())
        assertEquals(emptyList<String>(), incoming())
    }

    @Test fun aSenderThatHangsIsGivenUp() {
        val claims = file("content://org.example.slow/claims", "a.pdf", pdf, hangClaims = true)
        val opening = file("content://org.example.slow/open", "b.pdf", pdf, hangOpen = true)
        val stalled = Uri.parse("content://org.example.slow/stalled")
        source.files[stalled] = FakeSource.F("c.pdf", null, null) {
            object : InputStream() {
                val closed = CountDownLatch(1)
                var sent = false
                override fun read(): Int = throw UnsupportedOperationException()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (!sent) { sent = true; System.arraycopy(pdf, 0, b, off, pdf.size); return pdf.size }
                    closed.await()
                    throw IOException("closed")
                }
                override fun close() = closed.countDown()
            }
        }
        val m = model(listOf(claims, opening, stalled))
        val r = review(m)
        // The provider that didn't answer about its file shows it without a name or size.
        assertNull(r.items[0].file!!.name)
        m.add()
        val d = done(m)
        source.hang.countDown()
        assertEquals("Added to Robot Files: 1 file is in your Inbox.", lines(d)[0])
        assertEquals(listOf("b.pdf: The app sharing this file stopped sending it, so Robot left it out.",
            "c.pdf: The app sharing this file stopped sending it, so Robot left it out."), lines(d).drop(1))
        assertEquals(1, entries().size)
        Thread.sleep(300)
        assertEquals(emptyList<String>(), incoming())
    }

    @Test fun aDuplicateIsAskedAboutAndNothingStagedIsLeftBehind() {
        val u = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        val first = model(listOf(u))
        review(first)
        first.add()
        done(first)
        // The same bytes again: "You already have this file: Inbox › receipt.pdf. Add it anyway?"
        val again = model(listOf(file("content://org.example.drive/2", "Costco.pdf", pdf)))
        review(again)
        again.add()
        val ask = waitUi(again) { it is ShareUi.Asking } as ShareUi.Asking
        assertEquals("You already have this file: Inbox › receipt.pdf. Add it anyway?", FilesStrings.duplicate("where" to Arg.Sub(ask.where)).render(Langs.EN))
        assertEquals(1, entries().size)
        again.answer(false)
        assertEquals(listOf("Nothing was added.", "Costco.pdf: Not added."), lines(done(again)))
        assertEquals(1, entries().size)
        assertEquals(emptyList<String>(), incoming())
        // Added anyway: two entries with the same hash.
        val third = model(listOf(file("content://org.example.drive/3", "Costco.pdf", pdf)))
        review(third)
        third.add()
        waitUi(third) { it is ShareUi.Asking }
        third.answer(true)
        assertEquals(listOf("Added to Robot Files: 1 file is in your Inbox."), lines(done(third)))
        assertEquals(2, entries().size)
        // Closing the window while a duplicate waits: it goes, and the next start adopts nothing.
        val fourth = model(listOf(file("content://org.example.drive/4", "x.pdf", pdf)))
        review(fourth)
        fourth.add()
        waitUi(fourth) { it is ShareUi.Asking }
        fourth.close()
        Thread.sleep(200)
        assertEquals(emptyList<String>(), incoming())
        assertEquals(0, vault.recover()!!.adopted)
        assertEquals(2, entries().size)
    }

    @Test fun sharedTextBecomesAFileAndNothingSharedIsSaidSo() {
        val m = model(emptyList(), text = "https://example.org/menu", subject = "Lunch‮")
        val r = review(m)
        assertEquals(null, r.items.single().file)
        m.add()
        done(m)
        val e = entries().single()
        assertEquals("Lunch.txt", e.name)
        assertEquals("text/plain", e.from.type)
        val untitled = model(emptyList(), text = "note to self")
        review(untitled)
        untitled.add()
        done(untitled)
        assertEquals("Shared text 2026-10-14 ${java.time.ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(1_792_000_000_000L), java.time.ZoneId.systemDefault())
            .let { "%02d.%02d".format(it.hour, it.minute) }}.txt", entries().last().name)
        val nothing = model(emptyList(), text = "  ")
        nothing.start()
        assertEquals(FilesStrings.nothingShared(), (waitUi(nothing) { it is ShareUi.Refused } as ShareUi.Refused).why)
    }

    @Test fun theIntentIsReadAsTheSenderWroteItAndBelievedInNothing() {
        val a = Uri.parse("content://org.example/1")
        val b = Uri.parse("content://org.example/2")
        val one = ShareRequest.of(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, a).apply { clipData = ClipData.newRawUri("", a) }, null)
        assertEquals(listOf(a), one.uris)
        val many = ShareRequest.of(Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(a, b)), null)
        assertEquals(listOf(a, b), many.uris)
        // A stream that isn't a Uri is nothing; the text and subject are read as they are.
        val bad = ShareRequest.of(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, "content://org.example/3")
            .putExtra(Intent.EXTRA_TEXT, "hello").putExtra(Intent.EXTRA_SUBJECT, "Hi"), null)
        assertEquals(emptyList<Uri>(), bad.uris)
        assertEquals("hello", bad.text.toString())
    }

    @Test fun theActivityTakesOnlySendAndShowsSecurely() {
        val view = Robolectric.buildActivity(FilesShareActivity::class.java, Intent(Intent.ACTION_VIEW, Uri.parse("content://org.example/1"))).create()
        assertTrue(view.get().isFinishing)
        val none = Robolectric.buildActivity(FilesShareActivity::class.java, Intent()).create()
        assertTrue(none.get().isFinishing)
        val send = Robolectric.buildActivity(FilesShareActivity::class.java, Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hi")).create()
        val a = send.get()
        assertTrue(!a.isFinishing)
        assertTrue(a.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        assertTrue(a.window.decorView.filterTouchesWhenObscured)
        send.destroy()
    }

    /** The phone's resolver path, against a provider Robolectric runs: its claims as a cursor gives them, its bytes once. */
    @Test fun theResolverReadsAProviderOnce() {
        val f = File(tmp.root, "shared.pdf").apply { writeBytes(pdf) }
        Provider.file = f
        Robolectric.setupContentProvider(Provider::class.java, "org.example.provider")
        val src = ResolverSource(RuntimeEnvironment.getApplication())
        val u = Uri.parse("content://org.example.provider/shared.pdf")
        assertEquals("shared.pdf" to 12L, src.claims(u, CancellationSignal()))
        assertEquals("application/pdf", src.type(u))
        val i = FilesIntake(vault, src, times)
        val shared = i.describe(u, null)
        assertEquals(12L, shared.size)
        val step = i.add(shared) { t -> FileIntake("shared.pdf", FileSource.SHARE, type = t.mime) } as IntakeStep.Added
        // The claimed 12 bytes are only a claim: what came is what is kept.
        assertEquals(pdf.size.toLong(), step.entry.from.size)
        assertEquals(FileType.PDF, step.type)
    }

    class Provider : ContentProvider() {
        companion object { var file: File? = null }
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)).apply { addRow(arrayOf<Any>("shared.pdf", 12L)) }
        override fun getType(uri: Uri) = "application/pdf"
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = ParcelFileDescriptor.open(file!!, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }

    // The window.

    private fun reviewUi() = ShareUi.Review(listOf(
        ShareItem(SharedFile(Uri.parse("content://a/1"), "2026-08-14 Costco receipt.pdf", 48_213, "application/pdf", null), null),
        ShareItem(SharedFile(Uri.parse("content://a/2"), "IMG_2041.jpg", 3_412_000, "image/jpeg", null), null),
        ShareItem(SharedFile(Uri.parse("file:///x"), null, null, null, ShareRefusal.NOT_CONTENT), null),
    ), FilesStrings.sharedFrom("sender" to Arg.Plain("Example Mail")), leftOut = 0)

    private fun doneUi() = ShareUi.Done(listOf(FilesStrings.added(2),
        FilesStrings.fileLine("name" to Arg.Plain("setup.apk"), "what" to Arg.Sub(FilesStrings.dangerous())),
        FilesStrings.fileLine("name" to Arg.Plain("scan.pdf"), "what" to Arg.Sub(FilesStrings.problem(app.heyrobot.core.files.IntakeProblem.TIMED_OUT)))))

    @Test fun theWindow() {
        rule.show { ShareWindow(reviewUi(), null) {} }
        rule.onNodeWithText("Add to Robot Files").assertExists()
        rule.onNodeWithText("Shared from Example Mail").assertExists()
        rule.onNodeWithText("48 KB · application/pdf").assertExists()
        rule.onNodeWithText("Robot can't take this file: the app shared it in a way Robot doesn't accept.").assertExists()
        rule.onNodeWithText("They wait in your Inbox until Robot files them.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_share")
    }

    @Test fun theWindowAsksAboutADuplicateThenSaysWhatHappened() {
        var ui: ShareUi by androidx.compose.runtime.mutableStateOf<ShareUi>(ShareUi.Asking("Costco.pdf", FilesStrings.where(null, "receipt.pdf")))
        rule.show { ShareWindow(ui, null) {} }
        rule.onNodeWithText("You already have this file: Inbox › receipt.pdf. Add it anyway?").assertExists()
        rule.onNodeWithText("Add it anyway").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_share_duplicate")
        ui = doneUi()
        rule.onNodeWithText("Added to Robot Files: 2 files are in your Inbox.").assertExists()
        rule.onNodeWithText("setup.apk: This could install or run something. Robot won't open it.").assertExists()
        rule.shot("files_share_done")
    }

    @Test fun theWindowDark() {
        rule.show(dark = true) { ShareWindow(reviewUi(), null) {} }
        rule.shot("files_share_dark")
    }

    @Test @Config(qualifiers = "fr-rCA-$PHONE")
    fun theWindowInFrench() {
        val nb = ' '
        val fine = ' '
        var ui: ShareUi by androidx.compose.runtime.mutableStateOf<ShareUi>(reviewUi())
        rule.show { ShareWindow(ui, null) {} }
        rule.onNodeWithText("Ajouter aux Fichiers de Robot").assertExists()
        rule.onNodeWithText("Partagé depuis Example Mail").assertExists()
        rule.onNodeWithText("48${nb}ko · application/pdf").assertExists()
        rule.onNodeWithText("Ils attendent dans ta boîte de réception jusqu’à ce que Robot les classe.").assertExists()
        assertEquals(emptyList<String>(), rule.touchTargetProblems())
        rule.shot("files_share_fr")
        ui = ShareUi.Asking("Costco.pdf", FilesStrings.where(null, "receipt.pdf"))
        rule.onNodeWithText("Tu as déjà ce fichier$nb: Boîte de réception › receipt.pdf. L’ajouter quand même$fine?").assertExists()
        rule.shot("files_share_duplicate_fr")
        ui = doneUi()
        rule.onNodeWithText("Ajoutés aux Fichiers de Robot$nb: 2 fichiers sont dans ta boîte de réception.").assertExists()
        rule.shot("files_share_done_fr")
    }

    @Test fun theButtonsDoWhatTheySay() {
        val u = file("content://org.example.mail.files/att/1", "receipt.pdf", pdf)
        val m = model(listOf(u))
        review(m)
        var closed = 0
        rule.show { ShareWindow(m.ui, m) { closed++ } }
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(10_000) { m.ui is ShareUi.Done }
        rule.onNodeWithText("Done").performClick()
        assertEquals(1, closed)
        assertEquals(1, entries().size)
    }
}
