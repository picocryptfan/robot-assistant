// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.parse

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SharedMemory
import app.heyrobot.core.parse.FileParseIpc
import app.heyrobot.core.parse.FileParseWire
import app.heyrobot.core.parse.ParseLimits
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

// :FILES PROCESS ONLY (android:process=":files", android:isolatedProcess="true", not exported, no permission, no intent
// filter). Robot Files' isolated reader (files-1.4-plan.md §4.3, D5, D19): the one place that decodes a file's bytes, or
// what was made from them. Android runs it under an isolated uid of its own: no permission, no network, none of Robot's
// files (the vault, its keys, the index, the settings) and no file it could open by name. A memory-safety bug in a
// decoder (libjpeg-turbo, libpng, libwebp, the HEIF decoder, pdfium, Skia), reached through a hostile file, runs code
// here, not as Robot.
//
// FileParseLink (main or :voice) binds a fresh instance of it (bindIsolatedService, a name of its own: a process and a uid
// of its own) for a batch or a screen, and lets it go after: unbinding or EXIT ends the process. Calls come one at a time.
// HELLO has this process prove its uid to the link (the kernel's, through the link's callback) before the link sends any
// file. PARSE: the request frame, the input region (read-only) and the output region; nothing is read from either before
// Process.isIsolated() says this process is isolated. Each request runs on a worker under a watchdog of
// ParseLimits.SERVICE_MS: past it the process ends itself, whatever the decoder is doing. Nothing here logs.

class FileParseService : Service() {
    private val lock = Any()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "robot-files-parse").apply { isDaemon = true } }  // l10n: not text (a thread's name)

    private val stub = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(FileParseIpc.DESCRIPTOR)
                return true
            }
            data.enforceInterface(FileParseIpc.DESCRIPTOR)
            when (code) {
                FileParseIpc.EXIT -> Process.killProcess(Process.myPid())
                FileParseIpc.HELLO -> synchronized(lock) { hello(data, reply ?: return false) }
                FileParseIpc.PARSE -> synchronized(lock) { parse(data, reply ?: return false) }
                else -> return super.onTransact(code, data, reply, flags)
            }
            return true
        }
    }

    override fun onBind(intent: Intent?): IBinder = stub

    /** The link let go (its batch or screen is over, or its process ended): this process has nothing left to do. */
    override fun onUnbind(intent: Intent?): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    private fun hello(data: Parcel, reply: Parcel) {
        val cb = data.readStrongBinder()
        val isolated = Process.isIsolated()
        val ok = isolated && cb != null && try {
            val d = Parcel.obtain()
            val r = Parcel.obtain()
            try {
                d.writeInterfaceToken(FileParseIpc.CALLBACK)
                d.writeInt(Process.myPid())
                cb.transact(FileParseIpc.CB_HELLO, d, r, 0) && r.readInt() == 1
            } finally {
                d.recycle()
                r.recycle()
            }
        } catch (_: Exception) { false }
        reply.writeNoException()
        reply.writeInt(if (ok) 1 else 0)
        reply.writeString(if (isolated) null else FileParseIpc.NOT_ISOLATED)
    }

    private fun parse(data: Parcel, reply: Parcel) {
        // First, before anything of a file is touched: never decode as Robot.
        if (!Process.isIsolated()) {
            reply.writeNoException()
            reply.writeInt(0)
            return
        }
        val frame = data.createByteArray()
        val input = if (data.readInt() != 0) SharedMemory.CREATOR.createFromParcel(data) else null
        val output = if (data.readInt() != 0) SharedMemory.CREATOR.createFromParcel(data) else null
        var inBuf: ByteBuffer? = null
        var outBuf: ByteBuffer? = null
        try {
            val req = if (frame == null || input == null || output == null || frame.size > FileParseIpc.MAX_FRAME) null
            else FileParseWire.decodeRequest(frame, input.size.toLong(), output.size)
            val answer = if (req == null) {
                null
            } else {
                inBuf = input!!.mapReadOnly()
                outBuf = output!!.mapReadWrite()
                val i = inBuf
                val o = outBuf
                val job = worker.submit<ByteArray> { FileParser(AndroidDecoders()).run(req, i, o) }
                try {
                    job.get(ParseLimits.SERVICE_MS, TimeUnit.MILLISECONDS)
                } catch (_: TimeoutException) {
                    // A decoder that doesn't finish: the process ends, and whatever it held with it.
                    Process.killProcess(Process.myPid())
                    null
                }
            }
            reply.writeNoException()
            reply.writeInt(1)
            reply.writeByteArray(answer)
        } finally {
            inBuf?.let(SharedMemory::unmap)
            outBuf?.let(SharedMemory::unmap)
            input?.close()
            output?.close()
        }
    }
}
