// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.http

import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

// VOICE PROCESS ONLY, :helpers module. What went online (docs/plans/everyday-set-plan.md §5.3, D10): every OkHttp client
// Robot builds (HelperHttp's, WebTransport's three, PageFetcher's) records each request through this one interceptor, as
// an application interceptor so a request that never connects (no internet, a refused address) is recorded too. What
// goes in the record: the host (core NetHost keeps a name, or "your network" for an address on it), what for, what
// allowed it, the request body's bytes, the reply body's bytes as they are read, and how it ended, written when the body
// is read to its end or closed (a helper's stream stays open for minutes). Never a path, a query, a header or a body.
// ProcessIsolationTest.everyNetworkPathIsRecorded checks each builder adds it.

/** What a request is for, when its client serves more than one purpose ([purpose] decides otherwise). */
class NetTag(val purpose: NetPurpose, val cause: NetCause)

class NetCount(private val cause: NetCause = NetCause.ASKED, private val purpose: (Request) -> NetPurpose) : Interceptor {
    constructor(purpose: NetPurpose, cause: NetCause = NetCause.ASKED) : this(cause, { purpose })

    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val tag = req.tag(NetTag::class.java)
        val p = tag?.purpose ?: purpose(req)
        val c = tag?.cause ?: cause
        val host = req.url.host
        val out = sent(req)
        val res = try {
            chain.proceed(req)
        } catch (e: IOException) {
            if (!refused(e)) NetRecord.record(p, host, c, out, 0, if (chain.call().isCanceled()) NetResult.STOPPED else NetResult.FAILED)
            throw e
        } catch (e: SecurityException) {
            // GrapheneOS's Network switch off: nothing went online.
            throw e
        }
        val body = res.body ?: run {
            NetRecord.record(p, host, c, out, 0, NetResult.DONE)
            return res
        }
        return res.newBuilder().body(Counted(body, { chain.call().isCanceled() }) { n, r -> NetRecord.record(p, host, c, out, n, r) }).build()
    }

    companion object {
        /** The request body's bytes (0 for none, or one that can be written only once). */
        fun sent(req: Request): Long {
            val b = req.body ?: return 0
            val n = try { b.contentLength() } catch (_: IOException) { -1 }
            if (n >= 0) return n
            if (b.isOneShot()) return 0
            return try { Buffer().also { b.writeTo(it) }.size } catch (_: IOException) { 0 }
        }

        /** The Network switch off (the socket refused with EACCES): nothing went online, so no row. */
        fun refused(e: Throwable): Boolean {
            var t: Throwable? = e
            while (t != null) {
                if (t is SecurityException) return true
                val m = t.message ?: ""
                if (m.contains("EACCES") || m.contains("Permission denied")) return true // l10n: not text (the system's error message, matched)
                t = t.cause
            }
            return false
        }
    }

    /** The reply's body, counted as it is read; [done] once, at its end or when it is closed. */
    private class Counted(private val body: ResponseBody, private val canceled: () -> Boolean, private val done: (Long, NetResult) -> Unit) : ResponseBody() {
        private val once = AtomicBoolean(false)
        @Volatile private var n = 0L

        private fun finish(r: NetResult) {
            if (once.compareAndSet(false, true)) done(n, r)
        }

        private val counted: BufferedSource by lazy {
            object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val got = try {
                        super.read(sink, byteCount)
                    } catch (e: IOException) {
                        finish(if (canceled()) NetResult.STOPPED else NetResult.FAILED)
                        throw e
                    }
                    if (got < 0) finish(NetResult.DONE) else n += got
                    return got
                }

                override fun close() {
                    finish(if (canceled()) NetResult.STOPPED else NetResult.DONE)
                    super.close()
                }
            }.buffer()
        }

        override fun contentType(): MediaType? = body.contentType()
        override fun contentLength(): Long = body.contentLength()
        override fun source(): BufferedSource = counted

        override fun close() {
            // Closed without being read: what arrived is what counts.
            finish(if (canceled()) NetResult.STOPPED else NetResult.DONE)
            try { counted.close() } catch (_: Throwable) { body.close() }
        }
    }
}
