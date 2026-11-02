// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.BootstrapWatch
import app.heyrobot.core.link.OnionKey
import app.heyrobot.core.link.TorPlan
import app.heyrobot.core.link.TorRoute
import app.heyrobot.core.online.TorHours
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

// :LINK PROCESS. 1.5, Robot to Robot (robot-to-robot-plan.md §2.2, D5; R0 §4; job L3): Tor as :link's child process.
//
// Start-up follows Briar's onionwrapper 0.1.7 (AbstractTorWrapper.startTor, GPL-3.0, Copyright Briar Project; adapted
// here, as Robot is GPL-3.0 too): start the executable with "-f <torrc> __OwningControllerProcess <pid>" so Tor exits when
// :link does, HOME set to Tor's folder; read its output until "Opened Control listener" (then keep reading and dropping it:
// never logged, it can name addresses); wait for the cookie file; authenticate on the control socket and take ownership.
// What differs from onionwrapper is what R0 found it can't do: Robot's torrc (unix: sockets only, TorConfig), its own
// control commands (TorControl: client authorization, unix: targets) and its own listeners for the onion services' targets.
//
// The executable is libtor.so from the APK's native library folder (Android unpacks it there at install: the manifest's
// extractNativeLibs), pinned by SHA-256 at build time and again by verify_apk.sh. Tor's folder (files/link/tor) is made
// private (0700) first: Tor refuses unix sockets in a folder others can list.
//
// L-bridges (robot-to-robot-carriers-research.md): each start reads Settings › Robot to Robot › Tor bridges ([plan], core
// TorPlan) and writes that route's Bridge lines into the torrc; lyrebird ([lyrebird], liblyrebird.so from the same folder,
// pinned the same way) is started by Tor itself, only for a transport a line names. Off is plain Tor, exactly as before.
// Automatic tries plain Tor first and, when its bootstrap stalls (no progress for 30 s), starts Tor again over Snowflake,
// then the built-in obfs4 bridges, then meek, each given 60 s without progress; the one that worked is tried first next
// time (in memory only). A route chosen on its own is the only one tried: no plain Tor behind your back. A change in
// Settings applies at Tor's next start, at once while Tor is still connecting; a Tor that is up keeps its route, so the
// onion services it carries stay up.
//
// E4: What went online's row for Tor: [hours] (core TorHours) samples Tor's byte counters every few minutes through
// [tally], and once more before each stop; one row an hour comes of it, with no relay's address.

/** Filesystem unix sockets in :link's private storage (android.net.LocalSocket on the phone). */
interface UnixSockets {
    fun connect(path: File): Duplex
    /** Binds a listening socket at [path] (an older file there removed first). */
    fun listen(path: File): UnixServer
    /** Makes [dir] readable, writable and searchable by this app alone (0700). */
    fun makePrivate(dir: File)
}

interface UnixServer : Closeable {
    /** The next connection; throws once closed. */
    fun accept(): Duplex
}

/**
 * Tor on the phone: [exe] (libtor.so), its files in [paths], unix sockets through [sockets]. [pid]: :link's. L-bridges:
 * [lyrebird] (liblyrebird.so), and [plan], read at every start (Settings › Robot to Robot › Tor bridges); [onReady] hears
 * the route each time Tor finishes bootstrapping (the link log's TOR entry). The stall times and [pollMs] are the plan's
 * (core BootstrapWatch); tests shorten them.
 */
class TorRuntime(
    private val exe: File,
    private val paths: TorPaths,
    private val sockets: UnixSockets,
    private val pid: Int,
    private val bootstrapTimeoutMs: Long = 120_000,
    private val lyrebird: File? = null,
    private val plan: () -> TorPlan = { TorPlan.PLAIN },
    private val stallPlainMs: Long = BootstrapWatch.STALL_PLAIN_MS,
    private val stallBridgeMs: Long = BootstrapWatch.STALL_BRIDGE_MS,
    private val pollMs: Long = 500,
    private val onReady: (TorRoute) -> Unit = {},
    private val hours: TorHours? = null,
) : TorNet {
    private val lock = Any()
    private var process: Process? = null
    @Volatile private var control: TorControl? = null
    private var controlStream: Duplex? = null
    private val servers = HashMap<Int, Pair<UnixServer, PublishedOnion>>()
    @Volatile private var ready = false
    /** The route the running Tor was started with. */
    private var route: TorRoute? = null
    /** The route that last bootstrapped (Automatic tries it first), in memory only. */
    private var worked: TorRoute? = null

    override val running: Boolean get() = ready

    override fun up(): Boolean = synchronized(lock) {
        if (ready) return true
        try {
            val p = plan()
            // A route whose transport lyrebird carries is tried only when lyrebird is there.
            val usable = TorPlan(p.routes.filter { !p.needsLyrebird(it) || lyrebird?.isFile == true }, p.own)
            // Your own bridge with no line, or only routes without lyrebird: Tor doesn't start (never plain Tor instead).
            if (usable.routes.isEmpty()) { restart(); return false }
            // Still connecting over a route no longer chosen (Settings changed): start again with the new choice.
            if (control != null && route !in usable.routes) restart()
            val order = usable.order(route, worked)
            for ((n, r) in order.withIndex()) {
                val more = n < order.size - 1
                try {
                    if (control == null || route != r) { restart(); start(r, usable) }
                    val c = control ?: return false
                    c.networkOn(true)
                    if (bootstrapped(c, if (r.bridged) stallBridgeMs else stallPlainMs, more)) {
                        ready = true
                        worked = r
                        try { onReady(r) } catch (_: Exception) {}
                        return true
                    }
                } catch (x: TorException) {
                    if (!more) throw x
                }
            }
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: TorException) {
            false
        } catch (_: IOException) {
            false
        }
    }

    /** Waits for Tor's bootstrap: true at 100%; false once it stalls (when [fallback]: another route waits) or times out. */
    private fun bootstrapped(c: TorControl, stallMs: Long, fallback: Boolean): Boolean {
        val w = BootstrapWatch(nowMs(), stallMs, bootstrapTimeoutMs, fallback)
        while (true) {
            when (w.see(c.bootstrap(), nowMs())) {
                BootstrapWatch.Verdict.DONE -> return true
                BootstrapWatch.Verdict.STALLED, BootstrapWatch.Verdict.TIMED_OUT -> return false
                BootstrapWatch.Verdict.WAIT -> Thread.sleep(pollMs)
            }
        }
    }

    private fun nowMs(): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime())

    /** The running Tor ended (nothing is published while it isn't ready), so the next [start] begins clean. */
    private fun restart() {
        if (control == null && process == null) return
        sample()
        for (slot in servers.keys.toList()) unpublishLocked(slot)
        try { control?.shutdown() } catch (_: TorException) {}
        stopProcess()
    }

    /** Starts the process over [r] and the control connection (the network stays off until [up] turns it on). */
    private fun start(r: TorRoute, p: TorPlan) {
        hours?.started()
        paths.dir.mkdirs(); paths.data.mkdirs()
        sockets.makePrivate(paths.dir); sockets.makePrivate(paths.data)
        paths.torrc.writeText(TorConfig.torrc(paths, p.lines(r), lyrebird.takeIf { p.needsLyrebird(r) }))
        route = r
        paths.cookie.delete(); paths.control.delete(); paths.socks.delete()
        val pb = ProcessBuilder(exe.absolutePath, "-f", paths.torrc.absolutePath, "__OwningControllerProcess", pid.toString()) // l10n: not text (Tor's arguments)
        pb.environment()["HOME"] = paths.dir.absolutePath // l10n: not text (an environment variable)
        pb.directory(paths.dir)
        pb.redirectErrorStream(true)
        val p = try { pb.start() } catch (_: Exception) { throw TorException(TorProblem.START) }
        process = p
        try {
            if (!waitForControl(p)) throw TorException(TorProblem.START)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(COOKIE_SECONDS)
            while (paths.cookie.length() < 32) {
                if (System.nanoTime() > until || !p.isAlive) throw TorException(TorProblem.CONTROL)
                Thread.sleep(50)
            }
            val s = try { sockets.connect(paths.control) } catch (_: IOException) { throw TorException(TorProblem.CONTROL) }
            controlStream = s
            val c = TorControl(s.input, s.output)
            c.authenticate(paths.cookie.readBytes().copyOf(32))
            control = c
        } catch (x: Exception) {
            stopProcess()
            throw if (x is TorException) x else TorException(TorProblem.START)
        }
    }

    /** Reads Tor's output until its control socket is open; then a thread keeps reading it and drops every line. */
    private fun waitForControl(p: Process): Boolean {
        val opened = java.util.concurrent.CountDownLatch(1)
        val ok = AtomicBoolean(false)
        Thread({
            try {
                p.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    for (l in lines) if (!ok.get() && l.contains("Opened Control listener")) { ok.set(true); opened.countDown() } // l10n: not text (Tor's notice)
                }
            } catch (_: Exception) {}
            opened.countDown()
        }, "robot-link-tor-out").apply { isDaemon = true }.start() // l10n: not text (a thread's name)
        opened.await(START_SECONDS, TimeUnit.SECONDS)
        return ok.get()
    }

    override fun publish(slot: Int, keyBlob: String?, clients: List<ByteArray>, accept: (Duplex) -> Unit): PublishedOnion = synchronized(lock) {
        val c = control ?: throw TorException(TorProblem.NOT_READY)
        unpublishLocked(slot)
        val target = paths.service(slot)
        val server = sockets.listen(target)
        try {
            val onion = c.addOnion(keyBlob, target, clients)
            servers[slot] = server to onion
            Thread({
                while (true) {
                    val d = try { server.accept() } catch (_: Exception) { break }
                    try { accept(d) } catch (_: Exception) { d.close() }
                }
            }, "robot-link-onion").apply { isDaemon = true }.start() // l10n: not text (a thread's name)
            onion
        } catch (x: Exception) {
            try { server.close() } catch (_: IOException) {}
            target.delete()
            throw if (x is TorException) x else TorException(TorProblem.PROTOCOL)
        }
    }

    override fun unpublish(slot: Int) = synchronized(lock) { unpublishLocked(slot) }

    private fun unpublishLocked(slot: Int) {
        val (server, onion) = servers.remove(slot) ?: return
        try { control?.delOnion(onion.serviceId) } catch (_: TorException) {}
        try { server.close() } catch (_: IOException) {}
        paths.service(slot).delete()
    }

    override fun clientAuth(onion: OnionKey, privateKey: ByteArray) = synchronized(lock) {
        (control ?: throw TorException(TorProblem.NOT_READY)).addClientAuth(OnionAddress.of(onion), privateKey)
    }

    override fun dial(onion: OnionKey, isolation: String): Duplex = dialHost(OnionAddress.of(onion) + ".onion", TorConfig.VIRTUAL_PORT, isolation) // l10n: not text (a domain)

    override fun dialHost(host: String, port: Int, isolation: String): Duplex {
        if (!ready) throw TorException(TorProblem.NOT_READY)
        val d = sockets.connect(paths.socks)
        try {
            d.readTimeout(DIAL_MS)
            Socks5.connect(d.input, d.output, host, port, isolation)
            d.readTimeout(0)
            return d
        } catch (x: Exception) {
            d.close()
            throw x
        }
    }

    override fun tally() {
        if (hours?.due() == true) sample()
    }

    /** Takes no lock of this class's: [up] holds it for as long as Tor takes to connect. */
    private fun sample() {
        val h = hours ?: return
        try {
            val t = control?.traffic()
            if (t != null) h.sample(t.first, t.second) else h.flush()
        } catch (_: Exception) {
            try { h.flush() } catch (_: Exception) {}
        }
    }

    override fun down() = synchronized(lock) {
        sample()
        for (slot in servers.keys.toList()) unpublishLocked(slot)
        try { control?.shutdown() } catch (_: TorException) {}
        stopProcess()
    }

    private fun stopProcess() {
        ready = false
        route = null
        control = null
        controlStream?.close(); controlStream = null
        process?.let { p ->
            try { if (!p.waitFor(5, TimeUnit.SECONDS)) p.destroy() } catch (_: InterruptedException) { p.destroy() }
        }
        process = null
        paths.control.delete(); paths.socks.delete()
    }

    private companion object {
        const val START_SECONDS = 30L
        const val COOKIE_SECONDS = 10L
        const val DIAL_MS = 120_000
    }
}
