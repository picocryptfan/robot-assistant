// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.helpers.anthropic

import app.heyrobot.core.online.NetCause
import app.heyrobot.core.online.NetPurpose
import app.heyrobot.core.online.NetRecord
import app.heyrobot.core.online.NetResult
import app.heyrobot.helpers.http.NetCount
import com.anthropic.core.RequestOptions
import com.anthropic.core.http.Headers
import com.anthropic.core.http.HttpClient
import com.anthropic.core.http.HttpRequest
import com.anthropic.core.http.HttpResponse
import com.anthropic.core.http.Interceptor
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean

// VOICE PROCESS ONLY, :helpers module. What went online for the Claude API (docs/plans/everyday-set-plan.md §5.3, D10):
// the SDK builds its own OkHttp client, so Robot records each request through the SDK's own interceptor instead: the
// API's host, the request body's bytes (its JSON), the reply's bytes as the stream is read, and how it ended, written
// once when the reply is read to its end or closed. Never a path, a header, the key or a word of the request.

class AnthropicNetCount(private val purpose: NetPurpose = NetPurpose.HELPER, private val cause: NetCause = NetCause.ASKED) : Interceptor {
    override fun intercept(client: HttpClient): HttpClient = Counting(client)

    private inner class Counting(private val inner: HttpClient) : HttpClient {
        override fun execute(request: HttpRequest, requestOptions: RequestOptions): HttpResponse {
            val host = host(request)
            val out = sent(request)
            val res = try {
                inner.execute(request, requestOptions)
            } catch (t: Throwable) {
                failed(host, out, t)
                throw t
            }
            return CountedResponse(res) { n, r -> NetRecord.record(purpose, host, cause, out, n, r) }
        }

        override fun executeAsync(request: HttpRequest, requestOptions: RequestOptions): CompletableFuture<HttpResponse> {
            val host = host(request)
            val out = sent(request)
            return inner.executeAsync(request, requestOptions).handle { res, t ->
                if (t != null) {
                    failed(host, out, t)
                    throw (t as? RuntimeException ?: java.util.concurrent.CompletionException(t))
                }
                CountedResponse(res) { n, r -> NetRecord.record(purpose, host, cause, out, n, r) }
            }
        }

        override fun close() = inner.close()

        private fun failed(host: String?, out: Long, t: Throwable) {
            if (!NetCount.refused(t)) NetRecord.record(purpose, host, cause, out, 0, if (t is java.io.InterruptedIOException) NetResult.STOPPED else NetResult.FAILED)
        }
    }

    private fun host(r: HttpRequest): String? = try { r.baseUrl?.toHttpUrlOrNull()?.host } catch (_: Throwable) { null }

    private fun sent(r: HttpRequest): Long = try { r.body?.contentLength()?.takeIf { it >= 0 } ?: 0L } catch (_: Throwable) { 0 }

    /** The reply, its body counted as it is read; [done] once, at the body's end or when it is closed. */
    private class CountedResponse(private val inner: HttpResponse, private val done: (Long, NetResult) -> Unit) : HttpResponse {
        private val once = AtomicBoolean(false)
        @Volatile private var n = 0L
        @Volatile private var body: InputStream? = null

        private fun finish(r: NetResult) {
            if (once.compareAndSet(false, true)) done(n, r)
        }

        override fun statusCode(): Int = inner.statusCode()
        override fun headers(): Headers = inner.headers()

        @Synchronized override fun body(): InputStream = body ?: object : FilterInputStream(inner.body()) {
            override fun read(): Int = count { super.read().also { if (it >= 0) n++ else finish(NetResult.DONE) } }
            override fun read(b: ByteArray, off: Int, len: Int): Int = count { super.read(b, off, len).also { if (it >= 0) n += it else finish(NetResult.DONE) } }
            override fun close() {
                finish(NetResult.DONE)
                super.close()
            }

            private fun count(read: () -> Int): Int = try { read() } catch (e: IOException) {
                finish(if (e is java.io.InterruptedIOException) NetResult.STOPPED else NetResult.FAILED)
                throw e
            }
        }.also { body = it }

        override fun close() {
            finish(NetResult.DONE)
            inner.close()
        }
    }
}
