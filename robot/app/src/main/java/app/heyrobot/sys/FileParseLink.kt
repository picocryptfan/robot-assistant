// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import android.os.RemoteException
import android.os.SharedMemory
import android.system.OsConstants
import app.heyrobot.core.IsolatedUid
import app.heyrobot.core.RLog
import app.heyrobot.core.parse.FileParseIpc
import app.heyrobot.core.parse.FileParseWire
import app.heyrobot.core.parse.FileSniff
import app.heyrobot.core.parse.FileType
import app.heyrobot.core.parse.ParseFailure
import app.heyrobot.core.parse.ParseLimits
import app.heyrobot.core.parse.ParseOp
import app.heyrobot.core.parse.ParseRequest
import app.heyrobot.core.parse.ParseResult
import app.heyrobot.core.parse.ParseResults
import app.heyrobot.core.parse.ParseStatus
import app.heyrobot.core.parse.ScanQuad
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger

// MAIN AND VOICE PROCESSES. One binding to Robot Files' isolated reader, :files (FileParseService, files-1.4-plan.md §4.3,
// D5, D19), for a batch of filing (:voice) or while a Files screen shows pages (main): [start], the requests, [end]. Each
// link binds a fresh instance of the service (bindIsolatedService under a name of its own), so it gets a process and an
// isolated uid of its own: main's and :voice's work never share one, and a batch never inherits another's state.
//
// The process proves an isolated uid before it is sent anything: HELLO has it call this link's callback, whose calling uid
// is the kernel's (core IsolatedUid), as :brain's LlamaLink does with :llama. A request's file goes in as a read-only
// SharedMemory this link fills; the outputs come back in a SharedMemory this link makes at the op's cap, described by one
// frame (core FileParseWire): :files never hands back a region or a descriptor of its own. Every number, range and byte
// is checked where it is read (FileParseWire.decodeReply, then ParseResults.from on the copies). A request that gets no
// answer in ParseLimits.LINK_MS, a process that dies, or an answer that breaks the rules ends the process; the link is
// then gone, and the caller makes a new one if it wants to go on.
//
// Requests block: call them from a worker thread, never the main one. Logs carry codes only, never a file's name, type,
// text or bytes.

class FileParseLink(private val ctx: Context, private val requestMs: Long = ParseLimits.LINK_MS) {
    private val connected = CountDownLatch(1)
    private val gone = CountDownLatch(1)
    @Volatile private var remote: IBinder? = null
    @Volatile private var bound = false
    /** The process proved an isolated uid (its HELLO): only then does it get a file. */
    @Volatile private var proven = false
    /** The isolated uid its HELLO came from; -1 before. */
    @Volatile private var uid = -1
    @Volatile private var ending = false
    /** Its HELLO was answered but didn't prove an isolated uid. */
    @Volatile private var refusedHello = false
    private val calls = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-files-link").apply { isDaemon = true } }  // l10n: not text (a thread's name)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            if (binder == null) return lost()
            try {
                binder.linkToDeath({ lost() }, 0)
            } catch (_: RemoteException) {
                return lost()
            }
            remote = binder
            connected.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) = lost()
        override fun onBindingDied(name: ComponentName?) = lost()
        override fun onNullBinding(name: ComponentName?) = lost()
    }

    /** The callback the process calls during HELLO, from its own uid: accepted once, and only from an isolated uid. */
    private val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(FileParseIpc.CALLBACK)
                return true
            }
            data.enforceInterface(FileParseIpc.CALLBACK)
            if (code != FileParseIpc.CB_HELLO) return super.onTransact(code, data, reply, flags)
            val from = Binder.getCallingUid()
            val ok = IsolatedUid.of(from) && uid == -1
            if (ok) uid = from
            reply?.writeInt(if (ok) 1 else 0)
            return true
        }
    }

    val isAlive: Boolean get() = gone.count > 0 && !ending

    /**
     * Starts a fresh isolated process and has it prove its uid; false when it didn't within [timeoutMs] (it is ended then).
     * Never on the main thread.
     */
    fun start(timeoutMs: Long = ParseLimits.BIND_MS): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper())
        bound = try {
            // No capability flags: :files gets the caller's priority and nothing it could use the microphone or location with.
            ctx.bindIsolatedService(Intent().setClassName(ctx.packageName, FileParseIpc.SERVICE_CLASS),
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT, "files_${Process.myPid()}_${SEQ.incrementAndGet()}", EVENTS, conn)  // l10n: not text (an instance name)
        } catch (t: Throwable) {
            RLog.w("files_parse_bind", t)
            false
        }
        if (!bound) {
            lost()
            return false
        }
        if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS) || remote == null) {
            end("bind")
            return false
        }
        return prove(timeoutMs)
    }

    /**
     * HELLO: the process answers, and calls back from an isolated uid. Anything else ends it, sent nothing: an answer that
     * doesn't prove it is NOT_ISOLATED, no answer in [timeoutMs] (or a dead process) UNAVAILABLE.
     */
    private fun prove(timeoutMs: Long): Boolean {
        val accepted = try {
            hello(timeoutMs)
        } catch (_: Exception) {
            null
        }
        proven = accepted == true && IsolatedUid.of(uid)
        if (!proven) {
            refusedHello = accepted != null
            end(if (accepted != null) "not_isolated" else "hello")
        }
        return proven
    }

    private fun hello(timeoutMs: Long): Boolean {
        val b = remote ?: return false
        val job = calls.submit<Boolean> {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(FileParseIpc.DESCRIPTOR)
                d.writeStrongBinder(callback)
                if (!b.transact(FileParseIpc.HELLO, d, r, 0)) return@submit false
                // The header :files writes is read as a plain number, never through readException (which would unparcel
                // whatever exception it named): anything but "no exception" is no proof.
                r.readInt() == 0 && r.readInt() == 1 && r.readString() == null
            } finally {
                d.recycle()
                r.recycle()
            }
        }
        return job.get(timeoutMs, TimeUnit.MILLISECONDS)
    }

    // The requests. [flags] are ParseRequest's READ flags; each caps and checks as FileParseWire says.

    /** For filing (§4.3): the type, pages, a PDF's text layer or a text file's text, page JPEGs, a thumbnail, the date taken. */
    fun read(bytes: ByteArray, flags: Int): ParseResult = run(ParseRequest(ParseOp.READ, flags, parts = listOf(bytes.size)), listOf(Part.of(bytes)))

    /** The same, from Robot's own sealed reader: exactly [size] bytes of [input]. */
    fun read(input: InputStream, size: Long, flags: Int): ParseResult =
        if (size !in 1..ParseLimits.MAX_INPUT) tooLargeOrEmpty(size)
        else run(ParseRequest(ParseOp.READ, flags, parts = listOf(size.toInt())), listOf(Part.of(input, size.toInt())))

    /** Raw pixels to show (D19): a thumbnail's (320 by 320) or a page's (1,600 by 2,400); [page] of a PDF. */
    fun pixels(bytes: ByteArray, thumb: Boolean, page: Int = 0): ParseResult =
        run(ParseRequest(ParseOp.PIXELS, if (thumb) ParseRequest.VIEW_THUMB else 0, page = page, parts = listOf(bytes.size)), listOf(Part.of(bytes)))

    /** A copy to send, with no metadata at all (Outgoing.copy says when). */
    fun strip(bytes: ByteArray): ParseResult = run(ParseRequest(ParseOp.STRIP, parts = listOf(bytes.size)), listOf(Part.of(bytes)))

    /** A scan's photo straightened from [quad] to a page JPEG. */
    fun straighten(bytes: ByteArray, quad: ScanQuad): ParseResult =
        run(ParseRequest(ParseOp.STRAIGHTEN, parts = listOf(bytes.size), quad = quad), listOf(Part.of(bytes)))

    /** Straightened pages into one PDF. */
    fun scanPdf(pages: List<ByteArray>): ParseResult =
        if (pages.size !in 1..ParseLimits.MAX_SCAN_PAGES) ParseResult.Refused(ParseStatus.BAD_REQUEST, FileType.EMPTY)
        else run(ParseRequest(ParseOp.SCAN_PDF, parts = pages.map { it.size }), pages.map(Part::of))

    /** Ends the process, whatever it is doing: EXIT (one way), then unbinding. */
    fun end(why: String) {
        if (ending) return
        ending = true
        RLog.i("files_parse_end", "why" to RLog.Code(why))
        val connectedOnce = remote != null
        remote?.let { b ->
            val d = Parcel.obtain()
            try {
                d.writeInterfaceToken(FileParseIpc.DESCRIPTOR)
                b.transact(FileParseIpc.EXIT, d, null, IBinder.FLAG_ONEWAY)
            } catch (_: Exception) {
            } finally {
                d.recycle()
            }
        }
        unbind()
        calls.shutdown()
        if (!connectedOnce) lost()
    }

    /** One part of a request's input: [size] bytes, written into the input region by [fill]. */
    private class Part(val size: Int, val prefix: ByteArray, val fill: (ByteBuffer) -> Unit) {
        companion object {
            fun of(b: ByteArray) = Part(b.size, b.copyOf(minOf(b.size, FileSniff.PREFIX))) { it.put(b) }

            /** From a stream: its first bytes are read for the sniff, then the rest; exactly [size] bytes or an IOException. */
            fun of(input: InputStream, size: Int): Part {
                val head = ByteArray(minOf(size, FileSniff.PREFIX))
                readFully(input, head, head.size)
                return Part(size, head) { out ->
                    out.put(head)
                    val chunk = ByteArray(64 * 1024)
                    var left = size - head.size
                    while (left > 0) {
                        val n = input.read(chunk, 0, minOf(chunk.size, left))
                        if (n < 0) throw IOException()
                        out.put(chunk, 0, n)
                        left -= n
                    }
                    if (input.read() != -1) throw IOException()
                }
            }

            private fun readFully(input: InputStream, b: ByteArray, n: Int) {
                var off = 0
                while (off < n) {
                    val r = input.read(b, off, n - off)
                    if (r < 0) throw IOException()
                    off += r
                }
            }
        }
    }

    private fun tooLargeOrEmpty(size: Long): ParseResult =
        if (size <= 0) ParseResult.Refused(ParseStatus.EMPTY, FileType.EMPTY) else ParseResult.Refused(ParseStatus.TOO_LARGE, FileType.UNKNOWN)

    /**
     * One request: refused here before anything is sent (ParseResults.before), else the parts written into a fresh
     * read-only region and sent with a fresh output region, the answer checked whole. A stream that doesn't give exactly
     * its size throws IOException.
     */
    @Synchronized private fun run(req: ParseRequest, parts: List<Part>): ParseResult {
        check(Looper.myLooper() != Looper.getMainLooper())
        val types = parts.map { FileSniff.sniff(it.prefix, it.size.toLong()) }
        ParseResults.before(req, types)?.let { return it }
        // Nothing of a file goes to a process that hasn't proven an isolated uid.
        if (!proven) return ParseResult.Failed(if (refusedHello) ParseFailure.NOT_ISOLATED else ParseFailure.UNAVAILABLE)
        if (!isAlive) return ParseResult.Failed(ParseFailure.UNAVAILABLE)
        val b = remote ?: return ParseResult.Failed(ParseFailure.UNAVAILABLE)
        val regions = try {
            SharedMemory.create("robot-files-in", req.inputSize.toInt()) to SharedMemory.create("robot-files-out", req.outSize)  // l10n: not text (regions' names)
        } catch (e: Exception) {
            RLog.w("files_parse_region", e)
            return ParseResult.Failed(ParseFailure.UNAVAILABLE)
        }
        val (input, output) = regions
        try {
            val w = input.mapReadWrite()
            try {
                for (p in parts) {
                    val before = w.position()
                    p.fill(w)
                    if (w.position() - before != p.size) throw IOException()
                }
            } finally {
                SharedMemory.unmap(w)
            }
            input.setProtect(OsConstants.PROT_READ)
            val job: Future<Pair<Int, ByteArray?>> = calls.submit<Pair<Int, ByteArray?>> { transact(b, req, input, output) }
            val (marker, frame) = try {
                job.get(requestMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                end("timeout")
                return ParseResult.Failed(ParseFailure.TIMED_OUT)
            } catch (_: ExecutionException) {
                end("crash")
                return ParseResult.Failed(ParseFailure.CRASHED)
            }
            if (marker == BAD_HEADER) {
                end("malformed")
                return ParseResult.Failed(ParseFailure.MALFORMED)
            }
            if (marker != 1) {
                end("not_isolated")
                return ParseResult.Failed(ParseFailure.NOT_ISOLATED)
            }
            // :files refused the request itself (it couldn't read it): nothing of the file was answered.
            frame ?: return ParseResult.Refused(ParseStatus.BAD_REQUEST, types[0])
            val reply = FileParseWire.decodeReply(frame, req)
            if (reply == null) {
                end("malformed")
                return ParseResult.Failed(ParseFailure.MALFORMED)
            }
            // Copied first, then checked: :files may still hold the region mapped. A region it made unreadable (its
            // protection changed) fails closed like any other bad answer.
            val result = try {
                val r = output.mapReadOnly()
                try {
                    ParseResults.from(req, reply, types[0]) { blob ->
                        val at = r.duplicate()
                        at.position(blob.offset)
                        ByteArray(blob.length).also { at.get(it) }
                    }
                } finally {
                    SharedMemory.unmap(r)
                }
            } catch (e: Exception) {
                RLog.w("files_parse_region", e)
                ParseResult.Failed(ParseFailure.MALFORMED)
            }
            if (result is ParseResult.Failed) end("malformed")
            return result
        } finally {
            // Closing never decides the answer (the regions go with their last mapping and descriptor anyway).
            try { input.close() } catch (_: Exception) {}
            try { output.close() } catch (_: Exception) {}
        }
    }

    /**
     * PARSE on the link's own thread: the marker (1, or 0 from a process that isn't isolated, or [BAD_HEADER]) and the
     * reply frame.
     */
    private fun transact(b: IBinder, req: ParseRequest, input: SharedMemory, output: SharedMemory): Pair<Int, ByteArray?> {
        val d = Parcel.obtain()
        val r = Parcel.obtain()
        try {
            d.writeInterfaceToken(FileParseIpc.DESCRIPTOR)
            d.writeByteArray(FileParseWire.encodeRequest(req))
            d.writeInt(1)
            input.writeToParcel(d, 0)
            d.writeInt(1)
            output.writeToParcel(d, 0)
            if (!b.transact(FileParseIpc.PARSE, d, r, 0)) throw RemoteException()
            // As HELLO's: the header is a plain number, 0 ("no exception") or the answer is refused unread.
            if (r.readInt() != 0) return BAD_HEADER to null
            val marker = r.readInt()
            if (marker != 1) return marker to null
            // At most what the reply holds (Binder's buffer, about 1 MB); decodeReply refuses anything past MAX_FRAME.
            return 1 to r.createByteArray()
        } finally {
            d.recycle()
            r.recycle()
        }
    }

    private fun unbind() {
        if (!bound) return
        bound = false
        try { ctx.unbindService(conn) } catch (_: Exception) {}
    }

    private fun lost() {
        if (gone.count == 0L) return
        gone.countDown()
        connected.countDown()
        remote = null
        unbind()
    }

    private companion object {
        /** Where every link's connection callbacks run (never a thread that may be waiting for them). */
        val EVENTS = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-files-events").apply { isDaemon = true } }  // l10n: not text (a thread's name)
        /** Each link's own service instance: this process's pid and a count, unique across Robot's processes. */
        val SEQ = AtomicInteger()
        /** A reply whose header isn't "no exception". */
        const val BAD_HEADER = -1
    }
}
