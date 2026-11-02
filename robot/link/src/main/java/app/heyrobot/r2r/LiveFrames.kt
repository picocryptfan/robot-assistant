// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.EnvelopeKind
import app.heyrobot.core.link.HeaderRead
import app.heyrobot.core.link.LinkCarrier
import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkProblem
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §3.2, §3.7; job L3): envelopes on a live stream, the direct
// carrier's TCP connection or a Tor stream. Each is its 25-byte header, checked (core LinkEnvelope.header) before a byte of
// its body is read or a buffer made for it, then exactly the body its length names: at most 65,577 bytes in all. A stream
// that sends anything else is closed; nothing of what it sent is kept or logged.

/** A connected byte stream both ways: a socket (direct), or a stream through Tor's SOCKS socket. */
interface Duplex : Closeable {
    val input: InputStream
    val output: OutputStream
    /** How long a read may wait before it fails (ms; 0 never). */
    fun readTimeout(ms: Int)
}

/** What came off a stream: an envelope, a newer Robot's (answered `unsupported`), or nothing more (closed, or refused). */
sealed class Frame {
    class Got(val envelope: LinkEnvelope) : Frame() {
        override fun toString(): String = "Frame.Got($envelope)" // l10n: not text (toString for logs and tests)
    }
    data class Newer(val version: Int) : Frame()
    data class Bad(val problem: LinkProblem) : Frame()
    object Closed : Frame() {
        override fun toString(): String = "Frame.Closed" // l10n: not text (toString for logs and tests)
    }
}

object LiveFrames {
    /** The next envelope on [input]. Never throws: a stream that breaks is [Frame.Closed]. */
    fun read(input: InputStream): Frame = try {
        val head = ByteArray(LinkEnvelope.HEADER)
        if (!fill(input, head, allowEof = true)) {
            Frame.Closed
        } else when (val h = LinkEnvelope.header(head, LinkCarrier.LIVE)) {
            is HeaderRead.Bad -> Frame.Bad(h.problem)
            is HeaderRead.Newer -> Frame.Newer(h.version)
            is HeaderRead.Ok -> {
                val body = ByteArray(h.length)
                fill(input, body, allowEof = false)
                Frame.Got(LinkEnvelope(h.kind, h.route, body))
            }
        }
    } catch (_: IOException) {
        Frame.Closed
    } catch (_: RuntimeException) {
        Frame.Closed
    }

    /** Writes [e] (it must be one a live stream may carry) and flushes. */
    fun write(output: OutputStream, e: LinkEnvelope) {
        output.write(e.encode(LinkCarrier.LIVE))
        output.flush()
    }

    /** The connection hello (kind 5): [token], the per-pairing connection token, and no body. */
    fun connectHello(token: ByteArray): LinkEnvelope = LinkEnvelope(EnvelopeKind.CONNECT, token, ByteArray(0))

    /** Reads exactly [b.size] bytes. False only when [allowEof] and the stream ended before the first byte. */
    private fun fill(input: InputStream, b: ByteArray, allowEof: Boolean): Boolean {
        var at = 0
        while (at < b.size) {
            val n = input.read(b, at, b.size - at)
            if (n < 0) {
                if (at == 0 && allowEof) return false
                throw EOFException()
            }
            at += n
        }
        return true
    }
}
