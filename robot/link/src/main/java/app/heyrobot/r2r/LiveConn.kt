// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.LinkEnvelope
import app.heyrobot.core.link.LinkWay

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §3.2, §3.7, §4.6; job L3): one live connection, direct or
// through Tor. It opens with a connection hello (kind 5: the per-pairing token its dialler was given) or a pairing hello
// (kind 3, sealed to the maker), within 30 seconds or it closes; from then on it carries one pairing's session messages
// both ways, each with the route it opened with (the token, or zeros after a pairing hello). At most 8 at once (LinkLimits).
// Its reader thread hands every frame to LinkNode's thread; writes take turns.
//
// 1.5 L5-fix: a connection proves itself. Bound by a connection hello (a token can be seen on the network, or kept from
// before) or by a pairing hello, dialled on a network anyone can answer, it is [proven] only once a session message on it
// decrypts for its pairing. Until then what LinkNode sends on it never counts as delivered (the envelope goes by the next
// carrier too), and a session message on it that doesn't decrypt closes it. A stream LinkNode dialled to a pairing's own
// onion is proven from the start: Tor checks the onion's key, which that Robot gave inside the session.

class LiveConn(private val duplex: Duplex, val way: LinkWay, val outbound: Boolean, val opened: Long) {
    /** The pairing this connection carries, once its hello was accepted (or, dialled, the one it was opened for). */
    @Volatile var pairing: String? = null
    /** The route its session messages carry: the token it opened with, or zeros after a pairing hello. */
    @Volatile var route: ByteArray = LinkEnvelope.NO_ROUTE
    /** Accepted on a listener that takes pairing hellos: the direct listener (an open in-person code) or a code's onion. */
    @Volatile var helloCode: String? = null
    @Volatile var helloAllowed: Boolean = false
    /** L5-fix: a session message on it decrypted for [pairing] (or LinkNode dialled it to that pairing's onion). */
    @Volatile var proven: Boolean = false
    /** L5-fix: bound by the newest token given: once proven, the next one is given (rotated each time it's used, §3.2). */
    @Volatile var newest: Boolean = false
    @Volatile var closed = false
        private set
    @Volatile var lastAt: Long = opened

    private val turns = java.util.concurrent.Semaphore(FRAMES_WAITING)

    /** F70: the reader's thread waits here while [FRAMES_WAITING] of this connection's frames wait for LinkNode's. False: closed. */
    fun takeTurn(): Boolean {
        while (!closed) if (turns.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) return true
        return false
    }

    fun turnDone() = turns.release()

    fun readTimeout(ms: Int) = try { duplex.readTimeout(ms) } catch (_: Exception) {}

    fun send(e: LinkEnvelope) = synchronized(this) {
        if (closed) throw java.io.IOException()
        LiveFrames.write(duplex.output, e)
    }

    /** L8: the transfer coming in on this connection, between its start frame and its last chunk (the reader's thread only). */
    var xfer: Any? = null

    /** L8: one transfer-chunk envelope, with this connection's route. */
    fun chunk(body: ByteArray) = send(LinkEnvelope(app.heyrobot.core.link.EnvelopeKind.CHUNK, route, body))

    /** Reads frames until the stream ends, handing each to [onFrame] (on this connection's own thread). */
    fun startReading(onFrame: (LiveConn, Frame) -> Unit) {
        Thread({
            while (!closed) {
                val f = LiveFrames.read(duplex.input)
                onFrame(this, f)
                if (f !is Frame.Got) break
            }
            close()
        }, "robot-link-conn").apply { isDaemon = true }.start() // l10n: not text (a thread's name)
    }

    fun close() {
        if (closed) return
        closed = true
        duplex.close()
    }

    override fun toString(): String = "LiveConn(way=$way, outbound=$outbound)" // l10n: not text (toString for logs and tests)

    companion object { const val FRAMES_WAITING = 16 }
}
