// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.SharedMemory
import app.heyrobot.core.parse.Box
import app.heyrobot.core.parse.Dim
import app.heyrobot.core.parse.FileParseIpc
import app.heyrobot.core.parse.FileParseWire
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseOp
import app.heyrobot.core.parse.ParseReply
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.parse.ParsedPixels
import app.heyrobot.core.parse.ParseBlob
import app.heyrobot.core.parse.BlobKind
import app.heyrobot.core.parse.ScanQuad
import app.heyrobot.parse.Decoders
import app.heyrobot.parse.FileParser
import app.heyrobot.parse.PdfDoc
import app.heyrobot.parse.PdfWriter
import app.heyrobot.parse.Picture
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * FileParseLink against an in-process stand-in for :files that speaks FileParseIpc through real Parcels and SharedMemory
 * regions, answering with FileParser (over faked platform decoders) or misbehaving: a process that crashes, hangs, answers
 * garbage, says it isn't isolated, or calls back from a uid that isn't an isolated one. Every request ends with one
 * answer; a misbehaving process is ended (EXIT) and the link is gone; nothing of a file is sent before the proof, or at all
 * when the link's own sniff refuses it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileParseLinkTest {
    private val ctx: Context = RuntimeEnvironment.getApplication()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var fake: FakeFiles
    private lateinit var link: FileParseLink

    private enum class Mode { OK, CRASH, HANG, GARBAGE, NOT_ISOLATED, WRONG_BYTES, WRONG_TYPE, EXCEPTION, HEADER, CUSTOM }

    /** 1.5 L4: the text of the QR code the decoded pictures show (none: a plain grey photo). */
    @Volatile private var qrText: String? = null

    // ------------------------------------------------------------------ the stand-in

    /** A JPEG header that says [d], [size] bytes long (SOI, SOF0, SOS, filler, EOI). */
    private fun jpeg(d: Dim, size: Int = 500): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(b(0xFF, 0xD8, 0xFF, 0xC0, 0, 11, 8, d.height shr 8, d.height and 0xFF, d.width shr 8, d.width and 0xFF, 1, 1, 0x11, 0))
        o.write(b(0xFF, 0xDA, 0, 8, 1, 1, 0, 0, 0x3F, 0))
        o.write(ByteArray(maxOf(0, size - o.size() - 2)) { 0x55 })
        o.write(b(0xFF, 0xD9))
        return o.toByteArray()
    }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private inner class Pic(override val width: Int, override val height: Int) : Picture {
        override fun scaled(box: Box): Picture = ParseLimits.fit(Dim(width, height), box).let { Pic(it.width, it.height) }
        override fun straightened(quad: ScanQuad, size: Dim): Picture = Pic(size.width, size.height)
        override fun jpeg(quality: Int, max: Int): ByteArray = jpeg(Dim(width, height))
        override fun png(max: Int): ByteArray? = null
        override fun rgba(out: ByteBuffer) {
            val q = qrText?.let { app.heyrobot.core.QrCode.encode(it) }
            if (q == null) { out.put(ByteArray(width * height * 4) { 3 }); return }
            // The code on white, centred, a 4-module margin: another phone's screen photographed square on.
            val m = minOf(width, height) / (q.size + 8)
            val x0 = (width - q.size * m) / 2
            val y0 = (height - q.size * m) / 2
            val px = ByteArray(width * height * 4)
            for (y in 0 until height) for (x in 0 until width) {
                val mx = if (x < x0) -1 else (x - x0) / m
                val my = if (y < y0) -1 else (y - y0) / m
                val v = (if (mx in 0 until q.size && my in 0 until q.size && q.dark(mx, my)) 20 else 235).toByte()
                val i = 4 * (y * width + x)
                px[i] = v; px[i + 1] = v; px[i + 2] = v; px[i + 3] = -1
            }
            out.put(px)
        }
        override fun close() {}
    }

    private inner class Fakes : Decoders {
        override fun size(input: ByteBuffer): Dim? = null
        override fun decode(input: ByteBuffer, box: Box): Picture = ParseLimits.fit(Dim(4032, 3024), box).let { Pic(it.width, it.height) }
        override fun decodePixels(input: ByteBuffer, maxPixels: Long): Picture = Pic(4032, 3024)
        override fun pdf(input: ByteBuffer): PdfDoc = throw IllegalStateException()
        override fun pdfWriter(): PdfWriter = throw IllegalStateException()
    }

    private inner class FakeFiles : Binder() {
        @Volatile var mode = Mode.OK
        @Volatile var dead = false
        /** The uid its callback call comes from: an isolated one by default. */
        @Volatile var callerUid = ISOLATED_UID
        @Volatile var problem: String? = null
        /** Its HELLO never answers (until it is told to end). */
        @Volatile var helloHangs = false
        /** Its HELLO answers with an exception in the reply's header. */
        @Volatile var helloThrows = false
        val hellos = AtomicInteger()
        val parses = AtomicInteger()
        val exits = AtomicInteger()
        @Volatile var lastInput: ByteArray? = null
        /** CUSTOM's answer frame. */
        @Volatile var custom: ByteArray? = null
        @Volatile var callback: IBinder? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (dead) throw DeadObjectException()
            data.enforceInterface(FileParseIpc.DESCRIPTOR)
            when (code) {
                FileParseIpc.EXIT -> {
                    exits.incrementAndGet()
                    dead = true
                }
                FileParseIpc.HELLO -> {
                    hellos.incrementAndGet()
                    if (helloHangs) {
                        while (!dead) Thread.sleep(5)
                        throw DeadObjectException()
                    }
                    val cb = data.readStrongBinder()
                    callback = cb
                    val ok = callBack(cb)
                    if (helloThrows) {
                        reply!!.writeException(SecurityException())
                        return true
                    }
                    reply!!.writeNoException()
                    reply.writeInt(if (ok) 1 else 0)
                    reply.writeString(problem)
                }
                FileParseIpc.PARSE -> {
                    parses.incrementAndGet()
                    parse(data, reply!!)
                }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }

        fun callBack(cb: IBinder): Boolean {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            return try {
                d.writeInterfaceToken(FileParseIpc.CALLBACK)
                d.writeInt(4242)
                ShadowBinder.setCallingUid(callerUid)
                cb.transact(FileParseIpc.CB_HELLO, d, r, 0) && r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        }

        private fun parse(data: Parcel, reply: Parcel) {
            when (mode) {
                Mode.CRASH -> {
                    dead = true
                    throw DeadObjectException()
                }
                Mode.HANG -> {
                    while (!dead) Thread.sleep(5)
                    throw DeadObjectException()
                }
                Mode.NOT_ISOLATED -> {
                    reply.writeNoException()
                    reply.writeInt(0)
                    return
                }
                // An exception of :files' choosing in the reply's header, or any other header: never unparcelled.
                Mode.EXCEPTION -> {
                    reply.writeException(IllegalStateException())
                    return
                }
                Mode.HEADER -> {
                    reply.writeInt(-128)
                    reply.writeInt(1)
                    return
                }
                else -> {}
            }
            val frame = data.createByteArray()!!
            val input = if (data.readInt() != 0) SharedMemory.CREATOR.createFromParcel(data) else null
            val output = if (data.readInt() != 0) SharedMemory.CREATOR.createFromParcel(data) else null
            // (Not closed here: in one process the stand-in's regions are the link's own descriptors.)
            val req = FileParseWire.decodeRequest(frame, input!!.size.toLong(), output!!.size)!!
            val inBuf = input.mapReadOnly()
            val outBuf = output.mapReadWrite()
            lastInput = ByteArray(inBuf.remaining()).also { inBuf.duplicate().get(it) }
            val answer = when (mode) {
                Mode.GARBAGE -> ByteArray(40) { 0x52 }
                Mode.WRONG_BYTES -> {
                    // A frame that says a 1,024 by 768 page JPEG, over bytes that aren't one.
                    outBuf.put(ByteArray(100) { 9 })
                    FileParseWire.encodeReply(ParseReply(ParseOp.READ, ParseStatus.OK, FileType.JPEG, 0, 1, 4032, 3024, 0, null,
                        listOf(ParseBlob(BlobKind.PAGE, 1024, 768, 0, 100))))
                }
                Mode.WRONG_TYPE -> FileParseWire.refusal(req.op, ParseStatus.NOT_READ, FileType.PNG)
                Mode.CUSTOM -> custom!!
                else -> FileParser(Fakes()).run(req, inBuf, outBuf)
            }
            SharedMemory.unmap(inBuf)
            SharedMemory.unmap(outBuf)
            reply.writeNoException()
            reply.writeInt(1)
            reply.writeByteArray(answer)
        }
    }

    @Before fun setUp() = fresh(HANG_MS)

    /** A fresh stand-in and link, waiting [requestMs] for each answer. */
    private fun fresh(requestMs: Long) {
        fake = FakeFiles()
        link = FileParseLink(ctx, requestMs = requestMs)
        put(link, "remote", fake)
        put(link, "bound", false)
    }

    @After fun tearDown() {
        fake.dead = true
        worker.shutdownNow()
        ShadowBinder.reset()
    }

    private fun put(o: Any, name: String, v: Any?) {
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, v)
    }

    /** HELLO, as start() does once connected (bindIsolatedService itself is the phone's). */
    private fun prove(): Boolean = off {
        FileParseLink::class.java.getDeclaredMethod("prove", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(link, 2_000L) as Boolean
    }

    /** Requests block, so they run off the main thread, as they must on the phone. */
    private fun <T> off(block: () -> T): T = worker.submit<T> { block() }.get(10, TimeUnit.SECONDS)

    private val photo by lazy { jpeg(Dim(4032, 3024), 3000) }
    private val readAll = ParseRequest.PAGES or ParseRequest.THUMB or ParseRequest.DATE

    // ------------------------------------------------------------------ the tests

    @Test fun anAnswerTravelsThroughParcelsAndRegionsAndIsChecked() {
        assertTrue(prove())
        val r = off { link.read(photo, readAll) }
        val out = (r as ParseResult.Ok).out
        assertEquals(FileType.JPEG, out.type)
        assertEquals(1, out.pageJpegs.size)
        assertNotNull(out.thumb)
        // What :files got is exactly the file's bytes, in a region of exactly its size.
        assertTrue(photo.contentEquals(fake.lastInput))
        // From a stream (Robot's own sealed reader), the same.
        val s = off { link.read(ByteArrayInputStream(photo), photo.size.toLong(), readAll) }
        assertTrue(s is ParseResult.Ok)
        // Pixels to show.
        val px = off { link.pixels(photo, thumb = true) }
        assertEquals(320 to 240, (px as ParseResult.Ok).out.pixels!!.let { it.width to it.height })
        assertTrue(link.isAlive)
        assertEquals(0, fake.exits.get())
    }

    /** The process crashes during a request: CRASHED, the link is gone, and no later request reaches anything. */
    /** F17: pages and thumbnails share a process; each copy to send gets its own, ended after, never the shared one. */
    @Test fun aCopyToSendHasAProcessOfItsOwn() {
        val made = ArrayList<FakeFiles>()
        val reader = app.heyrobot.ui.files.FilesReader(ctx) {
            val f = FakeFiles().also { made += it }
            FileParseLink(ctx, requestMs = 2_000).also { l ->
                put(l, "remote", f)
                put(l, "bound", false)
                FileParseLink::class.java.getDeclaredMethod("prove", Long::class.javaPrimitiveType).apply { isAccessible = true }.invoke(l, 2_000L)
            }
        }
        off {
            reader.pixels(photo, true, 0)
            reader.pixels(photo, true, 0)
        }
        assertEquals(1, made.size)
        assertEquals(2, made[0].parses.get())
        assertTrue(off { reader.strip(photo) } is ParseResult.Ok)
        assertTrue(off { reader.strip(photo) } is ParseResult.Ok)
        assertEquals(3, made.size)
        assertEquals(2, made[0].parses.get())
        for (f in made.drop(1)) {
            assertEquals(1, f.parses.get())
            assertEquals(1, f.exits.get())
        }
        assertEquals(0, made[0].exits.get())
        reader.end()
        assertEquals(1, made[0].exits.get())
    }

    /** F51: leaving Files shuts the link's thread under a request still being written: an answer, not a crash. */
    @Test fun leavingWhileARequestIsWrittenAnswersUnavailable() {
        assertTrue(prove())
        val big = jpeg(Dim(4032, 3024), 200_000)
        val leaves = object : ByteArrayInputStream(big) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= 100_000) link.end("files_screen")
                return super.read(b, off, len)
            }
        }
        val r = off { link.read(leaves, big.size.toLong(), readAll) }
        assertEquals(ParseFailure.UNAVAILABLE, (r as ParseResult.Failed).why)
        assertEquals(0, fake.parses.get())
    }

    @Test fun aCrashedProcessIsReportedAndTheLinkIsGone() {
        assertTrue(prove())
        fake.mode = Mode.CRASH
        assertEquals(ParseFailure.CRASHED, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertFalse(link.isAlive)
        fake.dead = false
        fake.mode = Mode.OK
        assertEquals(ParseFailure.UNAVAILABLE, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertEquals(1, fake.parses.get())
    }

    /** A decoder that never finishes: TIMED_OUT within the link's wait, and the process is told to end (EXIT). */
    @Test fun aHungProcessTimesOutAndIsEnded() {
        assertTrue(prove())
        fake.mode = Mode.HANG
        val t0 = System.nanoTime()
        val r = off { link.read(photo, readAll) }
        val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)
        assertEquals(ParseFailure.TIMED_OUT, (r as ParseResult.Failed).why)
        assertTrue("$ms ms", ms in 450..5_000)
        assertEquals(1, fake.exits.get())
        assertFalse(link.isAlive)
        assertEquals(ParseFailure.UNAVAILABLE, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
    }

    /** An answer that breaks the wire's rules, or whose bytes aren't what it says: nothing of it is used, and the process ends. */
    @Test fun aMalformedAnswerEndsTheProcess() {
        for (mode in listOf(Mode.GARBAGE, Mode.WRONG_BYTES, Mode.WRONG_TYPE, Mode.EXCEPTION, Mode.HEADER)) {
            setUp()
            assertTrue(prove())
            fake.mode = mode
            val r = off { link.read(photo, readAll) }
            assertEquals(mode.name, ParseFailure.MALFORMED, (r as ParseResult.Failed).why)
            assertEquals(mode.name, 1, fake.exits.get())
            assertFalse(link.isAlive)
        }
    }

    @Test fun aServiceThatSaysItIsntIsolatedIsEnded() {
        assertTrue(prove())
        fake.mode = Mode.NOT_ISOLATED
        assertEquals(ParseFailure.NOT_ISOLATED, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertEquals(1, fake.exits.get())
    }

    /** HELLO from a uid that isn't an isolated one (or a service that says it isn't isolated): nothing is ever sent. */
    @Test fun aHelloFromANonIsolatedUidIsRefusedAndNothingIsSent() {
        fake.callerUid = android.os.Process.myUid()
        assertFalse(prove())
        assertEquals(ParseFailure.NOT_ISOLATED, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertEquals(0, fake.parses.get())
        assertEquals(1, fake.exits.get())
        // An isolated uid, but the service reports it isn't isolated.
        setUp()
        fake.problem = FileParseIpc.NOT_ISOLATED
        assertFalse(prove())
        assertEquals(0, fake.parses.get())
        // A HELLO answered with an exception: no proof (the exception is never unparcelled).
        setUp()
        fake.helloThrows = true
        assertFalse(prove())
        assertEquals(0, fake.parses.get())
        // A second HELLO call-back (a replay) is never accepted.
        setUp()
        assertTrue(prove())
        assertFalse(off { fake.callBack(fake.callback!!) })
    }

    /** A HELLO that never answers: not proven, the process ended, and the link unavailable (not "not isolated"). */
    @Test fun aHelloWithNoAnswerEndsTheProcess() {
        fake.helloHangs = true
        assertFalse(prove())
        assertEquals(1, fake.exits.get())
        assertEquals(ParseFailure.UNAVAILABLE, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertEquals(0, fake.parses.get())
    }

    /** No request before the proof. */
    @Test fun nothingIsSentBeforeTheProof() {
        assertEquals(ParseFailure.UNAVAILABLE, (off { link.read(photo, readAll) } as ParseResult.Failed).why)
        assertEquals(0, fake.parses.get())
    }

    /** What the link's own sniff refuses never leaves the process: empty, too large, dangerous, kept-not-read. */
    @Test fun theLinksOwnSniffRefusesBeforeSending() {
        assertTrue(prove())
        assertEquals(ParseStatus.EMPTY, (off { link.read(ByteArray(0), readAll) } as ParseResult.Refused).status)
        val never = object : InputStream() { override fun read(): Int = throw AssertionError("read") }
        assertEquals(ParseStatus.TOO_LARGE, (off { link.read(never, ParseLimits.MAX_INPUT + 1L, readAll) } as ParseResult.Refused).status)
        assertEquals(ParseStatus.EMPTY, (off { link.read(never, 0, readAll) } as ParseResult.Refused).status)
        val zip = b(0x50, 0x4B, 0x03, 0x04) + "classes.dex".toByteArray()
        assertEquals(ParseStatus.DANGEROUS, (off { link.read(zip, readAll) } as ParseResult.Refused).status)
        assertEquals(ParseStatus.NOT_READ, (off { link.strip("%PDF-1.4".toByteArray()) } as ParseResult.Refused).status)
        assertEquals(ParseStatus.BAD_REQUEST, (off { link.scanPdf(emptyList()) } as ParseResult.Refused).status)
        assertEquals(ParseStatus.BAD_REQUEST, (off { link.scanPdf(listOf(photo, "%PDF-1.4".toByteArray())) } as ParseResult.Refused).status)
        assertEquals(0, fake.parses.get())
        assertTrue(link.isAlive)
    }

    @Test fun requestsNeverRunOnTheMainThread() {
        assertTrue(prove())
        assertTrue(runCatching { link.read(photo, readAll) }.exceptionOrNull() is IllegalStateException)
        assertEquals(0, fake.parses.get())
    }

    /** The one place pixels become a bitmap: within the box, exactly their size, or not at all. */
    @Test fun pixelsBecomeABitmapOnlyWithinTheirCaps() {
        val ok = FilePixels.bitmap(ParsedPixels(320, 240, ByteArray(320 * 240 * 4)), thumb = true)!!
        assertEquals(320 to 240, ok.width to ok.height)
        assertNull(FilePixels.bitmap(ParsedPixels(321, 240, ByteArray(321 * 240 * 4)), thumb = true))
        assertNull(FilePixels.bitmap(ParsedPixels(320, 240, ByteArray(320 * 240 * 4 - 1)), thumb = true))
        assertNotNull(FilePixels.bitmap(ParsedPixels(1600, 2400, ByteArray(1600 * 2400 * 4)), thumb = false))
        assertNull(FilePixels.bitmap(ParsedPixels(1601, 2400, ByteArray(1601 * 2400 * 4)), thumb = false))
    }

    /**
     * 1.5, Robot to Robot (job L4): a pairing code's QR read through real Parcels and regions, and the answer checked as any
     * other: no code is NO_CODE; an answer outside the QR's rules (text that isn't printable ASCII, past 1 KiB, cut, a blob)
     * is MALFORMED and ends the process; what isn't an image is never sent.
     */
    @Test fun aQrCodeIsReadAndItsAnswerChecked() {
        // A QR read decodes a 2,048 by 1,536 picture and scans every pixel of it: 470 to 590 ms in this JVM, the first read
        // the slowest, so the 500 ms the hang tests wait timed it out (L-bridges, 28 Oct 2026). It waits as the phone does.
        fresh(ParseLimits.LINK_MS)
        assertTrue(prove())
        val code = "ROBOT-PAIR-AQEFBbX3Vb6h2k_1Qp0yV0Q6s8xJ3p7nT1m2c3d4e5f6g7h8i9j0kLmNoPqRsTuVwXyZ"
        qrText = code
        assertEquals(code, (off { link.qr(photo) } as ParseResult.Ok).out.text)
        qrText = null
        assertEquals(ParseStatus.NO_CODE, (off { link.qr(photo) } as ParseResult.Refused).status)
        val sent = fake.parses.get()
        assertEquals(ParseStatus.NOT_READ, (off { link.qr("%PDF-1.4".toByteArray()) } as ParseResult.Refused).status)
        assertEquals(sent, fake.parses.get())
        assertTrue(link.isAlive)
        for (bad in listOf(
            ParseReply(ParseOp.QR, ParseStatus.OK, FileType.JPEG, ParseReply.HAS_TEXT, 1, 4032, 3024, 0, "Café"),
            ParseReply(ParseOp.QR, ParseStatus.OK, FileType.JPEG, ParseReply.HAS_TEXT or ParseReply.TEXT_CUT, 1, 4032, 3024, 0, code),
            ParseReply(ParseOp.QR, ParseStatus.OK, FileType.JPEG, ParseReply.HAS_TEXT, 1, 4032, 3024, 0, "x".repeat(1_025)),
            ParseReply(ParseOp.QR, ParseStatus.OK, FileType.JPEG, ParseReply.HAS_TEXT, 1, 4032, 3024, 0, code, listOf(ParseBlob(BlobKind.THUMB, 10, 10, 0, 100))),
        )) {
            fresh(ParseLimits.LINK_MS)
            assertTrue(prove())
            fake.mode = Mode.CUSTOM
            fake.custom = FileParseWire.encodeReply(bad)
            assertEquals(ParseFailure.MALFORMED, (off { link.qr(photo) } as ParseResult.Failed).why)
            assertEquals(1, fake.exits.get())
            assertFalse(link.isAlive)
        }
    }

    private companion object {
        /** An isolated service's uid for user 0 (99,000 to 99,999). */
        const val ISOLATED_UID = 99_123
        /** How long most tests let the link wait, so a hung process is found quickly (the phone waits ParseLimits.LINK_MS). */
        const val HANG_MS = 500L
    }
}
