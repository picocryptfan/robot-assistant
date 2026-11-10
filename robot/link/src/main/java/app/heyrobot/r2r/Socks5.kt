// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// :LINK PROCESS. 1.5, Robot to Robot (job L3): asking Tor for a stream, through its SOCKS socket (a unix: socket in :link's
// private storage, never a TCP port: TorConfig). RFC 1928's CONNECT to a host name (Tor resolves it, so nothing is looked
// up on the phone), with RFC 1929's user name and password only as Tor's stream isolation (IsolateSOCKSAuth, on by
// default): each pairing's streams get circuits of their own. Tor's extended errors (SocksPort's ExtendedErrors) say why
// an onion service couldn't be reached. Never throws anything but [SocksException], which carries a code only.

/** Why Tor didn't open the stream. Codes are stable (the link log). */
enum class SocksProblem(val code: Int) {
    /** The SOCKS socket answered something that isn't SOCKS 5, or closed. */
    PROTOCOL(1),
    /** Tor refused (a general failure, the network down, a host or service it couldn't reach). */
    REFUSED(2),
    /** The onion service's descriptor wasn't found (it isn't up now), or its introduction failed. */
    ONION_DOWN(3),
    /** The onion service asked for client authorization this Robot doesn't hold, or the one it holds was refused. */
    ONION_AUTH(4),
    /** The address isn't a valid onion address. */
    ONION_ADDRESS(5),
    /** No answer in time. */
    TIMEOUT(6),
}

class SocksException(val problem: SocksProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "SocksException(${problem.name})" // l10n: not text (toString for logs and tests)
}

object Socks5 {
    /**
     * review-link-net L4: [key] as a SOCKS isolation key, which is at most 255 bytes: a longer one (a feed's long host name)
     * becomes its prefix and its SHA-256, so it still keeps its own circuits and never fails unsaid.
     */
    fun isolationKey(key: String): String {
        if (key.toByteArray(Charsets.US_ASCII).size <= 255 && key.all { it.code in 0x21..0x7e }) return key
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } // l10n: not text (a SOCKS isolation key)
        return key.substringBefore(':', "").take(16) + ":" + hash // l10n: not text (a SOCKS isolation key)
    }

    /**
     * Asks for a stream to [host]:[port] on an open connection to Tor's SOCKS socket; [isolation], when given, keeps this
     * stream's circuits apart from other values' (a pairing's id). On return the stream is open and carries the bytes.
     */
    fun connect(input: InputStream, output: OutputStream, host: String, port: Int, isolation: String? = null) {
        val h = host.toByteArray(Charsets.US_ASCII)
        require(h.size in 1..255 && port in 1..65535 && host.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '.' || it == '-' })
        try {
            output.write(if (isolation == null) byteArrayOf(5, 1, 0) else byteArrayOf(5, 1, 2))
            output.flush()
            val choice = read(input, 2)
            if (choice[0].toInt() != 5) throw SocksException(SocksProblem.PROTOCOL)
            when (choice[1].toInt() and 0xFF) {
                0 -> if (isolation != null) throw SocksException(SocksProblem.PROTOCOL)
                2 -> {
                    val u = (isolation ?: throw SocksException(SocksProblem.PROTOCOL)).toByteArray(Charsets.US_ASCII)
                    require(u.size in 1..255)
                    output.write(byteArrayOf(1, u.size.toByte()) + u + byteArrayOf(1, 'x'.code.toByte()))
                    output.flush()
                    val ok = read(input, 2)
                    if (ok[0].toInt() != 1 || ok[1].toInt() != 0) throw SocksException(SocksProblem.REFUSED)
                }
                else -> throw SocksException(SocksProblem.PROTOCOL)
            }
            output.write(byteArrayOf(5, 1, 0, 3, h.size.toByte()) + h + byteArrayOf((port ushr 8).toByte(), port.toByte()))
            output.flush()
            val head = read(input, 4)
            if (head[0].toInt() != 5) throw SocksException(SocksProblem.PROTOCOL)
            val rep = head[1].toInt() and 0xFF
            // The bound address that follows, read and dropped whatever the answer, so the stream starts clean.
            when (head[3].toInt()) {
                1 -> read(input, 4 + 2)
                4 -> read(input, 16 + 2)
                3 -> read(input, (read(input, 1)[0].toInt() and 0xFF) + 2)
                else -> throw SocksException(SocksProblem.PROTOCOL)
            }
            if (rep != 0) throw SocksException(problemOf(rep))
        } catch (x: SocksException) {
            throw x
        } catch (_: java.net.SocketTimeoutException) {
            throw SocksException(SocksProblem.TIMEOUT)
        } catch (_: IOException) {
            throw SocksException(SocksProblem.PROTOCOL)
        }
    }

    /** RFC 1928's replies, and Tor's extended ones for onion services (tor's socks-extensions.txt, 0xF0 to 0xF7). */
    fun problemOf(rep: Int): SocksProblem = when (rep) {
        0xF0, 0xF2, 0xF3 -> SocksProblem.ONION_DOWN
        0xF4, 0xF5 -> SocksProblem.ONION_AUTH
        0xF1, 0xF6 -> SocksProblem.ONION_ADDRESS
        0xF7 -> SocksProblem.TIMEOUT
        6 -> SocksProblem.TIMEOUT
        else -> SocksProblem.REFUSED
    }

    private fun read(input: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var at = 0
        while (at < n) {
            val k = input.read(b, at, n - at)
            if (k < 0) throw EOFException()
            at += k
        }
        return b
    }
}
