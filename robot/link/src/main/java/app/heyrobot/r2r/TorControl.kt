// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.BridgeLine
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, D5; R0 §4.2 and §9 items 9, 10; job L3): Robot's own
// small Tor controller. tor-android 0.4.9.14's executable runs as :link's child (TorProcess) with Robot's torrc
// ([TorConfig]): its control port and SOCKS port are unix: sockets in :link's private storage, never a TCP port on the
// phone, so no other app can drive it or borrow its client authorizations. Robot speaks the control protocol itself
// (control-spec.txt §2: one command, then its reply's lines until one with a space after the code): jtorctl 0.5, which R0
// fetched for this, can't build ADD_ONION's client authorization or ONION_CLIENT_AUTH_ADD, and keeps its reply lines in a
// package-private class, so it isn't used. No events are asked for, so every line Tor sends answers a command. Tor's
// replies never leave: a failure is a [TorException] with a code (a reply's text can name an onion address).
//
// What Robot asks Tor for:
//   - its onion service: ADD_ONION with its stored key, Flags=V3Auth and one ClientAuthV3 per paired Robot that may use it
//     (D5: only paired Robots get in); the virtual port forwarded to a unix: socket in :link's storage (TorRuntime's
//     listener). No proof-of-work: Briar's Tor has no PoW module, and client authorization keeps strangers from even
//     reading the descriptor (R0 item 10).
//   - a pairing code's one-time onion: the same, without client authorization (whoever holds the code may send its hello),
//     deleted when the code is used, expires or is cancelled.
//   - a paired Robot's client authorization: ONION_CLIENT_AUTH_ADD for its onion with the key it gave yours, kept in Tor's
//     memory only (never Permanent: Tor writes nothing of it to disk).

/** Why Tor didn't do what was asked. Codes are stable (the link log). */
enum class TorProblem(val code: Int) {
    /** libtor.so couldn't start, or exited. */
    START(1),
    /** The control socket or its cookie didn't come up in time, or authentication failed. */
    CONTROL(2),
    /** Tor refused a command (a 5xx reply). */
    REFUSED(3),
    /** A reply that isn't what the control protocol says. */
    PROTOCOL(4),
    /** The network is off (DisableNetwork), or Tor hasn't finished bootstrapping in time. */
    NOT_READY(5),
}

class TorException(val problem: TorProblem) : IOException() {
    override fun fillInStackTrace(): Throwable = this
    override fun toString(): String = "TorException(${problem.name})" // l10n: not text (toString for logs and tests)
}

/** Tor's files in [dir] (files/link/tor, made 0700: Tor refuses unix sockets in a directory others can list). */
class TorPaths(val dir: File) {
    val data = File(dir, "data") // l10n: not text (a folder name)
    val control = File(dir, "control.sock") // l10n: not text (a file name)
    val socks = File(dir, "socks.sock") // l10n: not text (a file name)
    val cookie = File(data, "control_auth_cookie") // l10n: not text (Tor's file name)
    val torrc = File(dir, "torrc") // l10n: not text (a file name)

    /** The unix: socket an onion service forwards to: [n] 0 is your own onion, 1 and up pairing codes' one-time ones. */
    fun service(n: Int): File {
        require(n in 0..MAX_SERVICES)
        return File(dir, "s$n.sock") // l10n: not text (a file name)
    }

    companion object {
        /** Your onion and at most this many open codes' one-time onions at once. */
        const val MAX_SERVICES = 16
    }
}

object TorConfig {
    /** The virtual port of every Robot onion service (Tor's port; nothing listens on the phone's network). */
    const val VIRTUAL_PORT = 7_710

    /**
     * Robot's torrc: both ports unix: sockets (no TCP port at all: Tor's default SocksPort 9050 is replaced, ControlPort
     * has none by default, and ORPort, DirPort, DNSPort, TransPort, HTTPTunnelPort and MetricsPort stay at 0), cookie
     * authentication, the network off until :link turns it on, host names only through SOCKS (SafeSocks: nothing is
     * resolved on the phone), Tor's extended errors and per-credential stream isolation, notices to stdout (read and
     * dropped by TorProcess, never logged), no GeoIP files (none ship).
     *
     * L-bridges: with [bridges] (Settings › Robot to Robot › Tor bridges, a route TorRuntime tries), UseBridges, one Bridge
     * line each (core BridgeLine: nothing else can ride in one), and, when a line names a transport, ClientTransportPlugin
     * for lyrebird ([lyrebird], liblyrebird.so in the APK's native library folder). Tor starts lyrebird itself, only for a
     * transport a Bridge line uses, and ends it when it ends; lyrebird listens on a loopback TCP port Tor picks (the
     * pluggable transport protocol has no unix: socket for a client; ProcessIsolationTest pins it as a deliberate change).
     * Without bridges the torrc is exactly as before: no bridge line, no lyrebird.
     */
    fun torrc(p: TorPaths, bridges: List<BridgeLine> = emptyList(), lyrebird: File? = null): String {
        for (f in listOf(p.dir, p.data, p.control, p.socks)) require(pathOk(f))
        val transports = bridges.mapNotNull { it.transport }.distinct()
        require(transports.isEmpty() || (lyrebird != null && exeOk(lyrebird)))
        return buildString {
            line("DataDirectory", p.data.path)
            line("SocksPort", "unix:${p.socks.path} ExtendedErrors IsolateSOCKSAuth") // l10n: not text (torrc)
            line("ControlPort", "unix:${p.control.path}") // l10n: not text (torrc)
            line("CookieAuthentication", "1")
            line("DisableNetwork", "1")
            line("SafeSocks", "1")
            line("AvoidDiskWrites", "1")
            line("RunAsDaemon", "0")
            line("Log", "notice stdout") // l10n: not text (torrc)
            line("GeoIPFile", "\"\"")
            line("GeoIPv6File", "\"\"")
            if (bridges.isNotEmpty()) {
                line("UseBridges", "1")
                if (transports.isNotEmpty()) line("ClientTransportPlugin", "${BridgeLine.TRANSPORTS.joinToString(",")} exec ${lyrebird!!.path}") // l10n: not text (torrc)
                for (b in bridges) line("Bridge", b.text) // l10n: not text (torrc)
            }
        }
    }

    /** An absolute path to an executable Tor reads as one word (the native library folder can be longer than a socket's path). */
    fun exeOk(f: File): Boolean = f.isAbsolute && f.path.length <= 255 && f.path.none { it <= ' ' || it == '"' || it == '\\' || it == '#' || it.code >= 0x7F }

    /** An absolute path Tor reads as one word: no white space, quote, backslash or control character, and short enough for a unix socket. */
    fun pathOk(f: File): Boolean = f.isAbsolute && f.path.length <= 100 && f.path.none { it <= ' ' || it == '"' || it == '\\' || it.code >= 0x7F }

    private fun StringBuilder.line(k: String, v: String) {
        append(k).append(' ').append(v).append('\n')
    }
}

/** An onion service Tor runs for Robot: its v3 address (56 characters) and its key as Tor writes it ("ED25519-V3:…"). */
class PublishedOnion(val serviceId: String, val keyBlob: String) {
    override fun toString(): String = "PublishedOnion" // l10n: not text (toString for logs and tests)
}

/** The control connection over [input] and [output] (Tor's control socket). Calls take turns. */
class TorControl(private val input: InputStream, private val output: OutputStream) {
    /** One reply line: its status code and its text after the separator ('-', '+' with its data, or ' ' for the last). */
    class Line(val status: Int, val text: String) {
        override fun toString(): String = "Line($status)" // l10n: not text (toString for logs and tests)
    }

    private val lock = Any()

    /** Sends [command] (one line, CRLF added) and reads its reply; a status other than 2xx is [TorProblem.REFUSED]. */
    fun send(command: String): List<Line> = synchronized(lock) {
        require(command.none { it == '\r' || it == '\n' } && command.all { it.code in 0x20..0x7E })
        guard {
            output.write((command + "\r\n").toByteArray(Charsets.US_ASCII))
            output.flush()
            val lines = ArrayList<Line>()
            var total = 0
            while (true) {
                val l = readLine()
                total += l.length
                if (l.length < 4 || total > MAX_REPLY) throw TorException(TorProblem.PROTOCOL)
                val status = l.substring(0, 3).toIntOrNull() ?: throw TorException(TorProblem.PROTOCOL)
                val sep = l[3]
                val text = l.substring(4)
                when (sep) {
                    '+' -> {
                        // A data block follows until a line with a single dot; its lines aren't needed, only skipped.
                        while (true) {
                            val d = readLine()
                            total += d.length
                            if (total > MAX_REPLY) throw TorException(TorProblem.PROTOCOL)
                            if (d == ".") break
                        }
                        lines += Line(status, text)
                    }
                    '-' -> lines += Line(status, text)
                    ' ' -> {
                        lines += Line(status, text)
                        break
                    }
                    else -> throw TorException(TorProblem.PROTOCOL)
                }
            }
            if (lines.any { it.status !in 200..299 }) throw TorException(TorProblem.REFUSED)
            lines
        }
    }

    /** AUTHENTICATE with the cookie file's 32 bytes, then TAKEOWNERSHIP: Tor exits when this connection closes. */
    fun authenticate(cookie: ByteArray) {
        require(cookie.size == 32)
        send("AUTHENTICATE " + cookie.joinToString("") { "%02X".format(it) }) // l10n: not text (control-spec)
        send("TAKEOWNERSHIP") // l10n: not text (control-spec)
        // TorProcess started Tor with __OwningControllerProcess (it exits with :link); this connection now owns it as well.
        send("RESETCONF $OWNER") // l10n: not text (control-spec)
    }

    fun networkOn(on: Boolean) {
        send("SETCONF DisableNetwork=" + if (on) "0" else "1") // l10n: not text (control-spec)
    }

    /** Tor's bootstrap progress, 0 to 100. */
    fun bootstrap(): Int {
        val l = send("GETINFO status/bootstrap-phase").firstOrNull { it.text.startsWith("status/bootstrap-phase=") } // l10n: not text (control-spec)
            ?: throw TorException(TorProblem.PROTOCOL)
        return PROGRESS.find(l.text)?.groupValues?.get(1)?.toInt()?.takeIf { it in 0..100 } ?: throw TorException(TorProblem.PROTOCOL)
    }

    /** Tor's own counters, bytes since it started: received, then sent. */
    fun traffic(): Pair<Long, Long> {
        val lines = send("GETINFO traffic/read traffic/written") // l10n: not text (control protocol)
        fun n(k: String) = lines.firstOrNull { it.text.startsWith(k) }?.text?.substring(k.length)?.toLongOrNull()?.takeIf { it >= 0 } ?: throw TorException(TorProblem.PROTOCOL)
        return n("traffic/read=") to n("traffic/written=") // l10n: not text (control protocol)
    }

    /**
     * Publishes an onion service forwarding [TorConfig.VIRTUAL_PORT] to the unix socket [target]: with [keyBlob] (as Tor
     * gave it before) or a new key, and, when [clients] isn't empty, only for clients holding one of those x25519 keys.
     */
    fun addOnion(keyBlob: String?, target: File, clients: List<ByteArray>): PublishedOnion {
        val lines = send(TorCommands.addOnion(keyBlob, target, clients).trimEnd())
        val id = lines.firstNotNullOfOrNull { it.text.removePrefix("ServiceID=").takeIf { _ -> it.text.startsWith("ServiceID=") } } // l10n: not text (control-spec)
            ?: throw TorException(TorProblem.PROTOCOL)
        if (OnionAddress.keyOf(id) == null) throw TorException(TorProblem.PROTOCOL)
        val key = keyBlob ?: lines.firstNotNullOfOrNull { it.text.removePrefix("PrivateKey=").takeIf { _ -> it.text.startsWith("PrivateKey=ED25519-V3:") } } // l10n: not text (control-spec)
            ?: throw TorException(TorProblem.PROTOCOL)
        if (!TorCommands.keyBlobOk(key)) throw TorException(TorProblem.PROTOCOL)
        return PublishedOnion(id, key)
    }

    fun delOnion(serviceId: String) {
        require(OnionAddress.keyOf(serviceId) != null)
        send("DEL_ONION $serviceId") // l10n: not text (control-spec)
    }

    /** Holds [privateKey] (x25519, 32 bytes) as this Robot's client authorization for [onion], in Tor's memory only. */
    fun addClientAuth(onion: String, privateKey: ByteArray) {
        send(TorCommands.clientAuthAdd(onion, privateKey).trimEnd())
    }

    fun removeClientAuth(onion: String) {
        require(OnionAddress.keyOf(onion) != null)
        send("ONION_CLIENT_AUTH_REMOVE $onion") // l10n: not text (control-spec)
    }

    fun shutdown() {
        send("SIGNAL SHUTDOWN") // l10n: not text (control-spec)
    }

    /** A line from Tor, up to LF (CR dropped), at most [MAX_LINE] bytes. */
    private fun readLine(): String {
        val b = ByteArrayOutputStream(128)
        while (true) {
            val c = input.read()
            if (c < 0) throw TorException(TorProblem.CONTROL)
            if (c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
            if (b.size() > MAX_LINE) throw TorException(TorProblem.PROTOCOL)
        }
        return String(b.toByteArray(), Charsets.US_ASCII)
    }

    private inline fun <T> guard(block: () -> T): T = try {
        block()
    } catch (x: TorException) {
        throw x
    } catch (_: IOException) {
        throw TorException(TorProblem.CONTROL)
    } catch (_: RuntimeException) {
        throw TorException(TorProblem.PROTOCOL)
    }

    private companion object {
        const val OWNER = "__OwningControllerProcess" // l10n: not text (torrc)
        val PROGRESS = Regex("PROGRESS=(\\d{1,3})") // l10n: not text (control-spec)
        const val MAX_LINE = 16 * 1024
        const val MAX_REPLY = 256 * 1024
    }
}

/** The control commands Robot builds itself, as Tor's control-spec reads them (TorControlTest pins them). */
object TorCommands {
    fun addOnion(keyBlob: String?, target: File, clients: List<ByteArray>): String {
        require(TorConfig.pathOk(target) && clients.size <= MAX_CLIENTS)
        require(keyBlob == null || KEY_BLOB.matches(keyBlob))
        val sb = StringBuilder("ADD_ONION ").append(keyBlob ?: "NEW:ED25519-V3") // l10n: not text (control-spec)
        if (clients.isNotEmpty()) sb.append(" Flags=V3Auth") // l10n: not text (control-spec)
        sb.append(" Port=").append(TorConfig.VIRTUAL_PORT).append(",unix:").append(target.path) // l10n: not text (control-spec)
        for (k in clients) sb.append(" ClientAuthV3=").append(OnionAddress.authorizedClient(k)) // l10n: not text (control-spec)
        return sb.append("\r\n").toString()
    }

    fun clientAuthAdd(onion: String, privateKey: ByteArray): String {
        require(OnionAddress.keyOf(onion) != null && privateKey.size == 32)
        return "ONION_CLIENT_AUTH_ADD $onion x25519:${Base64.getEncoder().encodeToString(privateKey)}\r\n" // l10n: not text (control-spec)
    }

    fun keyBlobOk(blob: String): Boolean = KEY_BLOB.matches(blob)

    /** At most this many paired Robots on one onion service (§2.3's 100 pairings). */
    const val MAX_CLIENTS = 100
    private val KEY_BLOB = Regex("ED25519-V3:[A-Za-z0-9+/]{86}==") // l10n: not text (Tor's key format: 64 bytes in base64)
}
