// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.news

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

// Today H7b: TLS over any byte stream (a stream through Tor isn't a java.net.Socket). The engine is the caller's, made for
// the feed's host with endpoint identification on, so the platform's trust manager checks the certificate is for it; the
// caller checks the name again with the platform's verifier after the handshake. As the link's push client does (PushHttp's
// TlsStream), with a reader for the answer's bytes: a peer's close (close_notify) or the stream's end reads as the end.
class FeedTls(private val engine: SSLEngine, private val input: InputStream, private val output: OutputStream) {
    private val session = engine.session
    private var netIn: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize).apply { flip() }
    private var appIn: ByteBuffer = ByteBuffer.allocate(session.applicationBufferSize).apply { flip() }
    private val netOut: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize)
    private val readBuf = ByteArray(16 * 1024)
    private var closed = false

    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> if (!unwrapSome()) throw SSLException("closed") // l10n: not text (never shown or logged)
                SSLEngineResult.HandshakeStatus.NEED_TASK -> runTasks()
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                else -> throw SSLException("handshake") // l10n: not text (never shown or logged)
            }
        }
    }

    fun write(b: ByteArray) {
        val src = ByteBuffer.wrap(b)
        // review-link-net L3: a wrap on a closed engine, or one that neither takes nor writes anything, fails the write
        // instead of looping. review-home-net-bind L2: one that writes a record and takes nothing (a post-handshake
        // message, say) goes on.
        while (src.hasRemaining()) {
            val r = wrap(src)
            if (r.status == SSLEngineResult.Status.CLOSED || (r.bytesConsumed() == 0 && r.bytesProduced() == 0)) throw SSLException("write") // l10n: not text (never shown or logged)
        }
    }

    /** The answer's plain bytes. */
    val reader: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (!appIn.hasRemaining()) if (closed || !unwrapSome()) return -1
            val n = minOf(len, appIn.remaining())
            appIn.get(b, off, n)
            return n
        }
    }

    fun close() {
        try {
            engine.closeOutbound()
            wrap(ByteBuffer.allocate(0))
        } catch (_: Exception) {}
    }

    private fun wrap(src: ByteBuffer): SSLEngineResult {
        netOut.clear()
        val r = engine.wrap(src, netOut)
        if (r.status != SSLEngineResult.Status.OK && r.status != SSLEngineResult.Status.CLOSED) throw SSLException("wrap") // l10n: not text (never shown or logged)
        netOut.flip()
        if (netOut.hasRemaining()) {
            output.write(netOut.array(), netOut.position(), netOut.remaining())
            output.flush()
        }
        runTasks()
        return r
    }

    /** Unwraps until the engine produced something or needs no more input; false at the peer's close or the stream's end. */
    private fun unwrapSome(): Boolean {
        while (true) {
            appIn.compact()
            val r = try { engine.unwrap(netIn, appIn) } finally { appIn.flip() }
            runTasks()
            when (r.status) {
                SSLEngineResult.Status.OK -> if (r.bytesProduced() > 0 || r.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) return true
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> if (!readMore()) { closed = true; return false }
                SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = grow(appIn, session.applicationBufferSize)
                SSLEngineResult.Status.CLOSED -> { closed = true; return false }
                null -> throw IOException()
            }
        }
    }

    private fun readMore(): Boolean {
        val n = input.read(readBuf)
        if (n < 0) return false
        netIn.compact()
        if (netIn.remaining() < n) {
            val bigger = ByteBuffer.allocate(netIn.position() + n + session.packetBufferSize)
            netIn.flip()
            bigger.put(netIn)
            netIn = bigger
        }
        netIn.put(readBuf, 0, n)
        netIn.flip()
        return true
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    private fun grow(b: ByteBuffer, more: Int): ByteBuffer = ByteBuffer.allocate(b.capacity() + more).apply { put(b); flip() }
}
