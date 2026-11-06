// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, §3.7; R0 §5.5; job L3): posting one push message to
// the other Robot's push address, an UnifiedPush endpoint (RFC 8030's push resource). Always https: a push address is
// checked as https when it's read (core PushRoute), and here again. The body is RFC 8291's aes128gcm ciphertext.
//
// The TLS is the platform's (SSLEngine from a new SSLContext over the platform's trust managers: on the phone Conscrypt, the
// system's CAs only, as network_security_config.xml says). fix-push-tls: a new context for each post, so no TLS session is
// cached or resumed between posts; a resumed session would let the push server tie an Anonymous post to a direct one, or
// two pairings to each other. The cost is a full handshake each post (the trust managers are made once). over any byte stream: a TCP socket (Private pairings post directly) or a stream
// through Tor's SOCKS socket, which is a unix socket and not a java.net.Socket (Anonymous pairings post through Tor, so the
// push server never sees the phone's address). One path for both, so both are the same tested code. The host name goes in
// SNI, the certificate is checked for it by the trust manager (endpoint identification "HTTPS") and again by [hostnames]
// (the platform's verifier on the phone), and nothing but the status line is read back. No redirect, no cookie, no proxy,
// no retry here (the outbox retries), at most [PushHttp.DEADLINE_MS] for the whole answer, and no message of any exception leaves: every failure is a [PushProblem].

/** Why a push wasn't taken. Codes are stable (the link log). */
enum class PushProblem(val code: Int) {
    /** The address isn't an https push address Robot posts to. */
    BAD_ADDRESS(1),
    /** No connection (DNS, TCP, Tor), or it broke. */
    UNREACHABLE(2),
    /** TLS failed: the certificate wasn't trusted, or not for this host. */
    TLS(3),
    /** The push server answered 404 or 410: this push address is gone; the other Robot must give a new one. */
    GONE(4),
    /** 413: too large for this push server. */
    TOO_LARGE(5),
    /** 429: too many; try later. */
    BUSY(6),
    /** Any other answer, or none in time. */
    FAILED(7),
}

class PushException(val problem: PushProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "PushException(${problem.name})" // l10n: not text (toString for logs and tests)
}

/** Where a push address's host is: its name and port, and the path to post to. Never logged. */
class PushTarget private constructor(val host: String, val port: Int, val path: String) {
    override fun toString(): String = "PushTarget" // l10n: not text (toString for logs and tests)

    companion object {
        /** [endpoint] as Robot posts to it: https, a host name or address, no user part, no fragment. */
        fun of(endpoint: String): PushTarget? {
            if (!app.heyrobot.core.link.PushRoute.endpointOk(endpoint)) return null
            val u = try { URI(endpoint) } catch (_: Exception) { return null }
            if (u.scheme != "https" || u.rawUserInfo != null || u.rawFragment != null) return null // l10n: not text (a URL scheme)
            val host = u.host ?: return null
            if (host.isEmpty() || host.length > 253) return null
            val port = if (u.port == -1) 443 else u.port
            if (port !in 1..65535) return null
            val path = (u.rawPath?.takeIf { it.isNotEmpty() } ?: "/") + (u.rawQuery?.let { "?$it" } ?: "")
            if (path.any { it <= ' ' || it.code >= 0x7F }) return null
            return PushTarget(host.removePrefix("[").removeSuffix("]"), port, path)
        }
    }
}

/**
 * One POST to a push server over [stream], already connected to [target]'s host and port (directly or through Tor). [tls]:
 * [trust]: the platform's trust managers (each post gets its own SSLContext over them); [hostnames]: the platform's host name
 * check (the phone's default verifier). [deadlineMs]: the most a post may take once its stream is open.
 */
class PushHttp(private val trust: Array<out TrustManager>, private val deadlineMs: Long = DEADLINE_MS, private val hostnames: HostnameVerifier) {
    /** Posts [body] with RFC 8030's TTL (seconds) and urgency `normal` (R0 §9 item 16). Returns when the server took it. */
    fun post(stream: Duplex, target: PushTarget, body: ByteArray, ttlSeconds: Long) {
        require(body.size in 1..MAX_BODY && ttlSeconds in 0..MAX_TTL)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(deadlineMs)
        stream.readTimeout(TIMEOUT_MS)
        // fix-push-tls: never a shared context, so never a session from another post.
        val tls = try { SSLContext.getInstance("TLS").apply { init(null, trust, null) } } catch (_: Exception) { throw PushException(PushProblem.TLS) } // l10n: not text (JSSE's name)
        val engine = tls.createSSLEngine(target.host, target.port).apply {
            useClientMode = true
            sslParameters = sslParameters.apply {
                endpointIdentificationAlgorithm = "HTTPS" // l10n: not text (JSSE's name)
                if (!target.host.contains(':') && !target.host.all { it.isDigit() || it == '.' }) serverNames = listOf(SNIHostName(target.host))
            }
        }
        val io = TlsStream(engine, stream.input, stream.output, stream::readTimeout, deadline)
        try {
            io.handshake()
            if (!hostnames.verify(target.host, engine.session)) throw PushException(PushProblem.TLS)
            val head = buildString {
                append("POST ").append(target.path).append(" HTTP/1.1\r\n") // l10n: not text (HTTP)
                append("Host: ").append(if (target.host.contains(':')) "[${target.host}]" else target.host) // l10n: not text (HTTP)
                if (target.port != 443) append(':').append(target.port)
                append("\r\n")
                append("TTL: ").append(ttlSeconds).append("\r\n") // l10n: not text (RFC 8030's header)
                append("Urgency: normal\r\n") // l10n: not text (RFC 8030's header)
                append("Content-Encoding: aes128gcm\r\n") // l10n: not text (RFC 8188's coding)
                append("Content-Type: application/octet-stream\r\n") // l10n: not text (HTTP)
                append("Content-Length: ").append(body.size).append("\r\n") // l10n: not text (HTTP)
                append("Connection: close\r\n\r\n") // l10n: not text (HTTP)
            }
            io.write(head.toByteArray(Charsets.US_ASCII) + body)
            val status = io.statusLine()
            when (status) {
                in 200..299 -> {}
                404, 410 -> throw PushException(PushProblem.GONE)
                413 -> throw PushException(PushProblem.TOO_LARGE)
                429 -> throw PushException(PushProblem.BUSY)
                else -> throw PushException(PushProblem.FAILED)
            }
            io.close()
        } catch (x: PushException) {
            throw x
        } catch (_: SSLException) {
            throw PushException(PushProblem.TLS)
        } catch (_: java.net.SocketTimeoutException) {
            throw PushException(PushProblem.FAILED)
        } catch (_: IOException) {
            throw PushException(PushProblem.UNREACHABLE)
        } catch (_: RuntimeException) {
            throw PushException(PushProblem.FAILED)
        }
    }

    companion object {
        /** One push body: RFC 8291 over at most 3,993 bytes is 4,096 (UnifiedPush AND_3.1.0). */
        const val MAX_BODY = 4_096
        /** RFC 8030's TTL Robot asks for: at most a request's lifetime, 30 days. */
        const val MAX_TTL = 30L * 86_400
        const val TIMEOUT_MS = 30_000
        /** A post's whole answer, handshake included: a server that trickles a byte at a time can't hold it longer. */
        const val DEADLINE_MS = 60_000L

        /** The platform's trust managers (on the phone, network_security_config.xml's: the system's CAs). */
        fun platformTrust(): Array<TrustManager> =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as java.security.KeyStore?) }.trustManagers
        /** The status line at most; nothing more of the answer is read. */
        const val MAX_STATUS_LINE = 1_024
    }
}

/** TLS through [engine] over plain [input] and [output] streams: a handshake, writes, and the start of the answer. */
internal class TlsStream(
    private val engine: SSLEngine,
    private val input: InputStream,
    private val output: OutputStream,
    private val timeout: (Int) -> Unit = {},
    private val deadlineNs: Long = Long.MAX_VALUE,
) {
    private val session = engine.session
    private var netIn: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize).apply { flip() }
    private var appIn: ByteBuffer = ByteBuffer.allocate(session.applicationBufferSize).apply { flip() }
    private val netOut: ByteBuffer = ByteBuffer.allocate(session.packetBufferSize)
    private val readBuf = ByteArray(16 * 1024)

    fun handshake() {
        engine.beginHandshake()
        while (true) {
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(ByteBuffer.allocate(0))
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrapSome()
                SSLEngineResult.HandshakeStatus.NEED_TASK -> while (true) { (engine.delegatedTask ?: break).run() }
                SSLEngineResult.HandshakeStatus.FINISHED, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING -> return
                else -> throw SSLException("handshake") // l10n: not text (an exception's text, never shown or logged; DTLS's statuses aren't TLS's)
            }
        }
    }

    fun write(b: ByteArray) {
        val src = ByteBuffer.wrap(b)
        while (src.hasRemaining()) wrap(src)
    }

    /** The answer's HTTP status code, from its status line (at most [PushHttp.MAX_STATUS_LINE] bytes). */
    fun statusLine(): Int {
        val line = StringBuilder()
        while (true) {
            while (!appIn.hasRemaining()) unwrapSome()
            val c = appIn.get().toInt() and 0xFF
            if (c == '\n'.code) break
            if (c != '\r'.code) line.append(c.toChar())
            if (line.length > PushHttp.MAX_STATUS_LINE) throw IOException()
        }
        val m = STATUS.matchEntire(line) ?: throw IOException()
        return m.groupValues[1].toInt()
    }

    fun close() {
        try {
            engine.closeOutbound()
            wrap(ByteBuffer.allocate(0))
        } catch (_: Exception) {}
    }

    private fun wrap(src: ByteBuffer) {
        netOut.clear()
        val r = engine.wrap(src, netOut)
        if (r.status != SSLEngineResult.Status.OK && r.status != SSLEngineResult.Status.CLOSED) throw SSLException("wrap") // l10n: not text (never shown or logged)
        netOut.flip()
        if (netOut.hasRemaining()) {
            output.write(netOut.array(), netOut.position(), netOut.remaining())
            output.flush()
        }
        runTasks()
    }

    /** Unwraps until the engine produced something or needs no more input; reads from the stream when it does. */
    private fun unwrapSome() {
        while (true) {
            appIn.compact()
            val r = try { engine.unwrap(netIn, appIn) } finally { appIn.flip() }
            runTasks()
            when (r.status) {
                SSLEngineResult.Status.OK -> if (r.bytesProduced() > 0 || r.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_UNWRAP) return
                SSLEngineResult.Status.BUFFER_UNDERFLOW -> readMore()
                SSLEngineResult.Status.BUFFER_OVERFLOW -> appIn = grow(appIn, session.applicationBufferSize)
                SSLEngineResult.Status.CLOSED -> throw IOException()
                null -> throw IOException()
            }
        }
    }

    private fun readMore() {
        // Each read waits at most what's left of the post's deadline.
        val left = deadlineNs - System.nanoTime()
        if (left <= 0) throw java.net.SocketTimeoutException()
        if (deadlineNs != Long.MAX_VALUE) timeout(minOf(PushHttp.TIMEOUT_MS.toLong(), maxOf(1L, left / 1_000_000)).toInt())
        val n = input.read(readBuf)
        if (n < 0) throw IOException()
        netIn.compact()
        if (netIn.remaining() < n) {
            val bigger = ByteBuffer.allocate(netIn.position() + n + session.packetBufferSize)
            netIn.flip()
            bigger.put(netIn)
            netIn = bigger
        }
        netIn.put(readBuf, 0, n)
        netIn.flip()
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    private fun grow(b: ByteBuffer, more: Int): ByteBuffer = ByteBuffer.allocate(b.capacity() + more).apply { put(b); flip() }

    private companion object {
        val STATUS = Regex("HTTP/1\\.[01] ([1-5][0-9][0-9])( .*)?") // l10n: not text (HTTP's status line)
    }
}
