// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import app.heyrobot.core.link.BridgeChoice
import app.heyrobot.core.link.BridgeLine
import app.heyrobot.core.link.TorBridges
import app.heyrobot.core.link.TorPlan
import app.heyrobot.core.link.TorRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.Collections

/**
 * L-bridges: TorRuntime's routes through a real child process. A stand-in for libtor.so (a shell script: it says its
 * control socket opened, writes the cookie, and runs until its control connection closes) and a stand-in for Tor's control
 * socket that reads which route each start's torrc is for and answers its bootstrap as the test says. Off is plain Tor
 * alone; Automatic walks plain Tor, Snowflake, the built-in obfs4 bridges, then meek, and starts next time from the one
 * that worked; a route you chose is the only one tried; a route that needs lyrebird isn't tried without it; a change in
 * Settings while Tor is still connecting starts it again with the new choice.
 */
class TorRuntimeBridgesTest {
    private val root = File(System.getProperty("user.dir"), "build/tbt").also { it.deleteRecursively(); it.mkdirs() }
    private val paths = TorPaths(File(root, "tor"))
    private val lyrebird = File(root, "liblyrebird.so").also { it.writeText("#!/bin/sh\nexit 0\n"); it.setExecutable(true) } // l10n: not text (a test script)
    private val exe = File(root, "tor.sh").also {
        it.writeText("#!/bin/sh\ntouch alive\nhead -c 32 /dev/zero > data/control_auth_cookie\necho 'Opened Control listener'\nwhile [ -e alive ]; do sleep 0.02; done\n") // l10n: not text (a test script)
        it.setExecutable(true)
    }
    /** Each start's route, read from the torrc it was given. */
    private val starts: MutableList<String> = Collections.synchronizedList(ArrayList())
    private val torrcs: MutableList<String> = Collections.synchronizedList(ArrayList())
    /** The routes whose bootstrap reaches 100%; every other one stays at 5% (a network that blocks it). */
    @Volatile private var works: Set<String> = emptySet()
    private val runtimes = ArrayList<TorRuntime>()
    /** E4: Tor's own counters, as the control socket gives them. */
    @Volatile private var read = 0L
    @Volatile private var written = 0L

    private fun routeOf(torrc: String): String = when {
        torrc.contains("\nBridge snowflake ") -> "snowflake"
        torrc.contains("\nBridge obfs4 ") -> "obfs4"
        torrc.contains("\nBridge meek_lite ") -> "meek"
        torrc.contains("\nBridge ") -> "own"
        else -> "plain"
    }

    private inner class FakeSockets : UnixSockets {
        override fun connect(path: File): Duplex {
            if (path != paths.control) throw IOException()
            val torrc = paths.torrc.readText()
            val route = routeOf(torrc)
            starts += route
            torrcs += torrc
            val (mine, theirs) = PipeDuplex.pair()
            Thread({ serve(theirs, route) }, "fake-tor-control").apply { isDaemon = true }.start()
            return object : Duplex by mine {
                override fun close() { mine.close(); File(paths.dir, "alive").delete() }
            }
        }
        override fun listen(path: File): UnixServer = throw IOException()
        override fun makePrivate(dir: File) {}
    }

    private fun serve(d: Duplex, route: String) {
        try {
            val r = d.input.bufferedReader(Charsets.US_ASCII)
            while (true) {
                val cmd = r.readLine() ?: break
                val reply = if (cmd.startsWith("GETINFO traffic/read")) "250-traffic/read=$read\r\n250-traffic/written=$written\r\n250 OK\r\n"
                else if (cmd.startsWith("GETINFO status/bootstrap-phase"))
                    "250-status/bootstrap-phase=NOTICE BOOTSTRAP PROGRESS=${if (route in works) 100 else 5} TAG=x SUMMARY=\"x\"\r\n250 OK\r\n"
                else "250 OK\r\n"
                d.output.write(reply.toByteArray(Charsets.US_ASCII))
                d.output.flush()
            }
        } catch (_: IOException) {}
    }

    private fun tor(plan: () -> TorPlan, withLyrebird: Boolean = true) = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 3_000,
        lyrebird = if (withLyrebird) lyrebird else File(root, "missing.so"), plan = plan, stallPlainMs = 300, stallBridgeMs = 300, pollMs = 20).also { runtimes += it }

    private fun plan(c: BridgeChoice, own: List<BridgeLine> = emptyList()) = TorPlan(TorBridges.routes(c, own), own)

    @After fun tearDown() {
        for (t in runtimes) t.down()
        root.deleteRecursively()
    }

    /** E4: Tor's counters, read through its control socket, become What went online's row; a stop reads them first. */
    @Test fun torsCountersBecomeItsHourlyRow() {
        val rows = Collections.synchronizedList(ArrayList<app.heyrobot.core.online.NetEntry>())
        app.heyrobot.core.online.NetRecord.sink = app.heyrobot.core.online.NetSink { rows += it }
        try {
            val hour = app.heyrobot.core.online.TorHours.HOUR_MS
            var now = 100 * hour
            val hours = app.heyrobot.core.online.TorHours({ null }, {}, { now })
            works = setOf("plain")
            val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 3_000, lyrebird = lyrebird, plan = { plan(BridgeChoice.OFF) }, stallPlainMs = 300, stallBridgeMs = 300, pollMs = 20, hours = hours)
            runtimes += t
            assertTrue(t.up())
            read = 1_000; written = 100
            t.tally()
            assertEquals(0, rows.size)
            // Not due yet: the counters aren't asked for.
            read = 2_000
            t.tally()
            now += hour
            t.tally()
            assertEquals(listOf(100L to 2_000L), rows.map { it.out to it.inBytes })
            assertEquals(app.heyrobot.core.online.NetHost.TOR_NETWORK, rows[0].host)
            read = 2_500
            t.down()
            now += hour
            t.tally()
            assertEquals(listOf(100L to 2_000L, 0L to 500L), rows.map { it.out to it.inBytes })
        } finally {
            app.heyrobot.core.online.NetRecord.sink = null
        }
    }

    /** fix-push-tls: Tor ending on its own is seen (no longer running), and the next up() starts it again, a new start. */
    @Test fun torEndingOnItsOwnIsNoticedAndStartedAgain() {
        works = setOf("plain")
        val t = tor({ plan(BridgeChoice.OFF) })
        assertTrue(t.up())
        assertEquals(1, t.starts)
        File(paths.dir, "alive").delete()
        val until = System.nanoTime() + 5_000_000_000L
        while (t.running && System.nanoTime() < until) Thread.sleep(20)
        assertFalse(t.running)
        assertTrue(t.exited)
        assertTrue(t.up())
        assertEquals(listOf("plain", "plain"), starts)
        assertEquals(2, t.starts)
        assertFalse(t.exited)
    }

    /** fix-push-tls: after sleep(), nothing starts Tor again (a send already under way) until wake(). */
    @Test fun noStartBetweenSleepAndWake() {
        works = setOf("plain")
        val t = tor({ plan(BridgeChoice.OFF) })
        assertTrue(t.up())
        t.sleep()
        assertFalse(t.running)
        assertFalse(t.up())
        assertEquals(listOf("plain"), starts)
        t.wake()
        assertTrue(t.up())
        assertEquals(listOf("plain", "plain"), starts)
    }

    /**
     * tor-bridge-relay: a bridge you saved at a home address sends Tor and lyrebird through Robot's relay, on a loopback
     * port with a password made at this start, stopped with Tor; a bridge elsewhere leaves the torrc as it was.
     */
    @Test fun aBridgeAtAHomeAddressGoesThroughTheRelayStoppedWithTor() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = setOf("obfs4", "own")
        val t = tor({ plan(BridgeChoice.OWN, listOf(BridgeLine.parse("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0")!!)) })
        assertTrue(t.up())
        val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val m = Regex("""\nSocks5Proxy ${Regex.escape(lo.hostAddress!!)}:(\d+)\nSocks5ProxyUsername ([0-9a-f]{32})\nSocks5ProxyPassword ([0-9a-f]{32})\nUseBridges 1\nClientTransportPlugin obfs4 exec """)
            .find(torrcs.single())
        assertTrue(torrcs.single(), m != null)
        val port = m!!.groupValues[1].toInt()
        Socket(lo, port).close()
        t.down()
        assertTrue(portShut(port))
        // Started again: a new password.
        assertTrue(t.up())
        assertFalse(torrcs.last().contains(m.groupValues[3]))
        t.down()
        // A bridge elsewhere: no relay, no proxy.
        val far = tor({ plan(BridgeChoice.OWN, listOf(BridgeLine.parse("obfs4 192.0.2.5:443 $fp cert=x iat-mode=0")!!)) })
        assertTrue(far.up())
        assertFalse(torrcs.last().contains("Socks5"))
    }

    /**
     * fix-bridges-and-wording: your own lines removed while Tor runs over them through the relay: the next use starts Tor
     * again without them and the relay stops; lines unchanged, Tor keeps running.
     */
    @Test fun removingYourOwnLinesStopsTheRelayOnTheNextUse() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        // The fake names a start by the transport in its torrc: your own obfs4 line reads as "obfs4".
        works = setOf("obfs4", "plain")
        val home = listOf(BridgeLine.parse("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0")!!)
        var p = plan(BridgeChoice.OWN, home)
        val t = tor({ p })
        assertTrue(t.up())
        val n = torrcs.size
        assertTrue(t.up())
        assertEquals("lines unchanged: Tor not started again", n, torrcs.size)
        val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        val port = Regex("""\nSocks5Proxy [^\n]*:(\d+)\n""").find(torrcs.last())!!.groupValues[1].toInt()
        Socket(lo, port).close()
        // Your own bridge still chosen, no line left: Tor stops (never plain Tor in its place) and so does the relay.
        p = plan(BridgeChoice.OWN)
        assertFalse(t.up())
        assertTrue("relay still open", runCatching { Socket(lo, port).close() }.isFailure)
        // Fallen back to Off: plain Tor, no proxy.
        p = plan(BridgeChoice.OFF)
        assertTrue(t.up())
        assertFalse(torrcs.last().contains("Socks5"))
    }

    /**
     * fix-bridges-and-wording-b L1, L3: each of ownLinesChanged's checks on its own: your own lines edited (still chosen), and
     * another choice with the lines still saved. [TorRuntime.stale] says so before any start, and up() starts Tor again.
     */
    @Test fun editingYourOwnLinesOrChoosingAnotherStartsTorAgain() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = setOf("obfs4", "plain")
        val saved = listOf(BridgeLine.parse("obfs4 192.0.2.5:443 $fp cert=x iat-mode=0")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.6:443 $fp cert=x iat-mode=0")!!)
        var p = plan(BridgeChoice.OWN, saved)
        val t = tor({ p })
        assertTrue(t.up())
        assertFalse("lines unchanged", t.stale)
        // Edited, your own bridge still chosen: the lines check alone.
        p = plan(BridgeChoice.OWN, edited)
        assertTrue("edited", t.stale)
        val n = torrcs.size
        assertTrue(t.up())
        assertEquals(n + 1, torrcs.size)
        assertTrue(torrcs.last(), torrcs.last().contains("192.0.2.6:443") && !torrcs.last().contains("192.0.2.5:443"))
        assertFalse(t.stale)
        // Another choice, the edited lines still saved: the route check alone.
        p = plan(BridgeChoice.OFF, edited)
        assertTrue("switched away", t.stale)
        assertTrue(t.up())
        assertFalse(torrcs.last(), torrcs.last().contains("\nBridge "))
        assertFalse("plain Tor: none of your lines in use", t.stale)
    }

    /** fix-bridges-and-wording-b L2: your own lines edited while a start was still connecting: Tor never comes up over the old ones. */
    @Test fun linesEditedWhileTorConnectsAreNeverUsed() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = setOf("obfs4")
        val saved = listOf(BridgeLine.parse("obfs4 192.0.2.5:443 $fp cert=x iat-mode=0")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.6:443 $fp cert=x iat-mode=0")!!)
        var reads = 0
        val t = tor({ if (reads++ == 0) plan(BridgeChoice.OWN, saved) else plan(BridgeChoice.OWN, edited) })
        assertTrue(t.up())
        assertEquals(2, torrcs.size)
        assertTrue(torrcs.first(), torrcs.first().contains("192.0.2.5:443"))
        assertTrue(torrcs.last(), torrcs.last().contains("192.0.2.6:443") && !torrcs.last().contains("192.0.2.5:443"))
        assertFalse(t.stale)
    }

    /** tor-bridge-relay-c H1: a relay that can't start (any exception) is Tor not starting, with its reason, never a crash. */
    @Test fun aRelayThatCannotStartIsTorNotStartingWithItsReason() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = setOf("obfs4", "own")
        val why = ArrayList<TorProblem>()
        for (x in listOf(IllegalArgumentException(), java.io.IOException())) {
            why.clear()
            val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 3_000, lyrebird = lyrebird, stallPlainMs = 300, stallBridgeMs = 300, pollMs = 20,
                plan = { plan(BridgeChoice.OWN, listOf(BridgeLine.parse("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0")!!)) }, onFailed = { why += it }, relays = { throw x })
            runtimes += t
            assertFalse(t.up())
            assertEquals(listOf(TorProblem.RELAY), why)
        }
        assertTrue(torrcs.isEmpty())
    }

    /** review-tor-bridge-relay-b L4: Tor that can't start (here, no executable) leaves no relay open. */
    @Test fun torThatCannotStartLeavesNoRelay() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = setOf("obfs4", "own")
        val made = Collections.synchronizedList(ArrayList<BridgeRelay>())
        val why = ArrayList<TorProblem>()
        val t = TorRuntime(File(root, "missing.sh"), paths, FakeSockets(), 1, bootstrapTimeoutMs = 3_000, lyrebird = lyrebird, stallPlainMs = 300, stallBridgeMs = 300, pollMs = 20,
            plan = { plan(BridgeChoice.OWN, listOf(BridgeLine.parse("obfs4 10.0.0.5:443 $fp cert=x iat-mode=0")!!)) }, onFailed = { why += it },
            relays = { BridgeRelay.start(it).also(made::add) })
        runtimes += t
        assertFalse(t.up())
        assertTrue(why.toString(), TorProblem.START in why)
        assertTrue(made.isNotEmpty())
        val lo = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
        for (r in made) assertTrue("relay still open", portShut(r.auth.port))
    }

    @Test fun offIsPlainTorAlone() {
        val t = tor({ plan(BridgeChoice.OFF) })
        assertFalse(t.up())
        // Only plain Tor, waited for until its timeout (no stall: there is nothing else to try), and no bridge in its torrc.
        assertEquals(listOf("plain"), starts)
        assertFalse(torrcs.single().contains("Bridge") || torrcs.single().contains("ClientTransportPlugin"))
        works = setOf("plain")
        assertTrue(t.up())
        assertEquals(listOf("plain"), starts)
    }

    @Test fun automaticWalksTheRoutesInOrderAndRemembersTheOneThatWorked() {
        works = setOf("obfs4")
        val reported = ArrayList<TorRoute>()
        val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 3_000, lyrebird = lyrebird, plan = { plan(BridgeChoice.AUTO) },
            stallPlainMs = 300, stallBridgeMs = 300, pollMs = 20, onReady = { reported += it }).also { runtimes += it }
        assertTrue(t.up())
        assertEquals(listOf("plain", "snowflake", "obfs4"), starts)
        assertEquals(listOf(TorRoute.OBFS4), reported)
        // Snowflake's torrc: lyrebird for its transports, the built-in Snowflake lines, nothing of plain Tor's ports changed.
        val snow = torrcs[1]
        assertTrue(snow.contains("UseBridges 1\nClientTransportPlugin meek_lite,obfs4,snowflake,webtunnel exec ${lyrebird.path}\n"))
        assertEquals(2, snow.lines().count { it.startsWith("Bridge snowflake ") })
        // Next start: the route that worked first.
        t.down()
        assertTrue(t.up())
        assertEquals(listOf("plain", "snowflake", "obfs4", "obfs4"), starts)
        // Nothing works: each route once, then up() says no, with meek the last tried.
        t.down()
        works = emptySet()
        starts.clear()
        assertFalse(t.up())
        assertEquals(listOf("obfs4", "meek", "plain", "snowflake"), starts)
    }

    @Test fun aRouteYouChoseIsTheOnlyOne() {
        val t = tor({ plan(BridgeChoice.SNOWFLAKE) })
        assertFalse(t.up())
        assertEquals(listOf("snowflake"), starts)
    }

    @Test fun noLyrebirdNoBridgeThatNeedsIt() {
        // Snowflake without lyrebird: Tor doesn't start at all, never plain Tor instead.
        tor({ plan(BridgeChoice.SNOWFLAKE) }, withLyrebird = false).let { assertFalse(it.up()); it.down() }
        assertEquals(emptyList<String>(), starts)
        // Automatic without lyrebird: plain Tor only.
        tor({ plan(BridgeChoice.AUTO) }, withLyrebird = false).let { assertFalse(it.up()); it.down() }
        assertEquals(listOf("plain"), starts)
        // A plain bridge of your own needs no lyrebird, and its torrc names none.
        starts.clear(); torrcs.clear()
        works = setOf("own")
        val own = listOf(BridgeLine.parse("192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567")!!)
        tor({ plan(BridgeChoice.OWN, own) }, withLyrebird = false).let { assertTrue(it.up()); it.down() }
        assertEquals(listOf("own"), starts)
        assertTrue(torrcs.single().endsWith("UseBridges 1\nBridge 192.0.2.9:443 0123456789ABCDEF0123456789ABCDEF01234567\n"))
        // Your own bridge with no line: no route, so no Tor.
        assertFalse(tor({ TorPlan(TorBridges.routes(BridgeChoice.OWN, emptyList())) }).up())
        assertEquals(listOf("own"), starts)
    }

    /**
     * review-fix-bridges-and-wording-b M1: your own bridge doesn't connect, so you edit it: the Tor still connecting over the
     * old line reads as [TorRuntime.stale] and the next up() starts it over the new one; removed (back to Off), the same.
     */
    @Test fun aTorStillConnectingOverYourOwnLinesLetsGoOfThem() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = emptySet()
        val first = listOf(BridgeLine.parse("192.0.2.7:443 $fp")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.8:443 $fp cert=x iat-mode=0")!!)
        var p = plan(BridgeChoice.OWN, first)
        val t = tor({ p })
        assertFalse(t.up())
        assertEquals(listOf("own"), starts)
        assertFalse("lines unchanged", t.stale)
        p = plan(BridgeChoice.OWN, edited)
        assertTrue("still connecting over the old line", t.stale)
        works = setOf("obfs4")
        assertTrue(t.up())
        assertEquals(listOf("own", "obfs4"), starts)
        assertTrue(torrcs.last(), torrcs.last().contains("192.0.2.8:443") && !torrcs.last().contains("192.0.2.7:443"))
        assertFalse(t.stale)
        // Removed while a start over them still can't connect: stale too, so the pump lets it go.
        t.down()
        works = emptySet()
        assertFalse(t.up())
        p = plan(BridgeChoice.OFF)
        assertTrue("removed while connecting", t.stale)
        t.down()
        assertFalse(t.stale)
    }

    /**
     * review-fix-bridges-and-wording-c M1: you edit your own line while a start over the old one is still connecting (your
     * own route has no stall cut): that start lets go of it at once and connects over the new one, well before its timeout.
     */
    @Test fun aStartStillConnectingOverYourOldLinesMovesToTheNewOnes() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = emptySet()
        val first = listOf(BridgeLine.parse("192.0.2.7:443 $fp")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.8:443 $fp cert=x iat-mode=0")!!)
        val p = java.util.concurrent.atomic.AtomicReference(plan(BridgeChoice.OWN, first))
        val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 20_000, lyrebird = lyrebird, plan = { p.get() },
            stallPlainMs = 20_000, stallBridgeMs = 20_000, pollMs = 20).also { runtimes += it }
        val result = java.util.concurrent.CompletableFuture.supplyAsync { t.up() }
        val until = System.nanoTime() + 5_000_000_000L
        while (starts.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(listOf("own"), starts)
        works = setOf("obfs4")
        p.set(plan(BridgeChoice.OWN, edited))
        assertTrue("up over the new line well before the old start's 20 s", result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("own", "obfs4"), starts)
        assertTrue(torrcs.last(), torrcs.last().contains("192.0.2.8:443") && !torrcs.last().contains("192.0.2.7:443"))
        assertFalse(t.stale)
    }

    /**
     * review-fix-bridges-and-wording-d L4: your own line edited during every start: [TorRuntime.up] gives up after a few
     * starts, and says why (the link log's TOR row 5), as every other start that fails does.
     */
    @Test fun linesEditedThroughEveryStartGiveUpWithTheirReason() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = emptySet()
        val reads = java.util.concurrent.atomic.AtomicInteger()
        val why = Collections.synchronizedList(ArrayList<TorProblem>())
        val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 20_000, lyrebird = lyrebird,
            plan = { plan(BridgeChoice.OWN, listOf(BridgeLine.parse("192.0.2.${reads.incrementAndGet() % 200 + 1}:443 $fp")!!)) },
            stallPlainMs = 20_000, stallBridgeMs = 20_000, pollMs = 20, onFailed = { why += it }).also { runtimes += it }
        assertFalse(t.up())
        assertEquals(listOf("own", "own", "own"), starts)
        assertEquals(listOf(TorProblem.START), why)
    }

    /**
     * review-fix-bridges-lows L1: you edit your own line and turn off Robot's network access while a start connects over
     * the old one: that start lets go of it and starts no new Tor over the new one.
     */
    @Test fun aStartWithNetworkOffNeverMovesToYourNewLines() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = emptySet()
        val first = listOf(BridgeLine.parse("192.0.2.7:443 $fp")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.8:443 $fp cert=x iat-mode=0")!!)
        val p = java.util.concurrent.atomic.AtomicReference(plan(BridgeChoice.OWN, first))
        val net = java.util.concurrent.atomic.AtomicBoolean(true)
        val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 20_000, lyrebird = lyrebird, plan = { p.get() },
            stallPlainMs = 20_000, stallBridgeMs = 20_000, pollMs = 20).also { runtimes += it }
        t.mayStart = { net.get() }
        val result = java.util.concurrent.CompletableFuture.supplyAsync { t.up() }
        val until = System.nanoTime() + 5_000_000_000L
        while (starts.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
        assertEquals(listOf("own"), starts)
        works = setOf("obfs4")
        net.set(false)
        p.set(plan(BridgeChoice.OWN, edited))
        assertFalse("no start over the new line", result.get(5, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("own"), starts)
        assertFalse(t.running)
        assertFalse(t.stale)
    }

    /**
     * review-fix-bridges-lows L2: the pump's stop ([TorRuntime.letGo], from another thread) while a start moves to your new
     * lines waits on Tor's lock and checks again under it: the Tor that came up over the new lines keeps running.
     */
    @Test fun letGoDuringAStartThatMovesToYourNewLinesKeepsTheNewTor() {
        val fp = "0123456789ABCDEF0123456789ABCDEF01234567"
        works = emptySet()
        val first = listOf(BridgeLine.parse("192.0.2.7:443 $fp")!!)
        val edited = listOf(BridgeLine.parse("obfs4 192.0.2.8:443 $fp cert=x iat-mode=0")!!)
        val p = java.util.concurrent.atomic.AtomicReference(plan(BridgeChoice.OWN, first))
        val t = TorRuntime(exe, paths, FakeSockets(), 1, bootstrapTimeoutMs = 20_000, lyrebird = lyrebird, plan = { p.get() },
            stallPlainMs = 20_000, stallBridgeMs = 20_000, pollMs = 1_000).also { runtimes += it }
        val result = java.util.concurrent.CompletableFuture.supplyAsync { t.up() }
        val until = System.nanoTime() + 5_000_000_000L
        while (starts.isEmpty() && System.nanoTime() < until) Thread.sleep(20)
        Thread.sleep(200) // the start is in its 1 s wait between bootstrap reads
        assertEquals(listOf("own"), starts)
        works = setOf("obfs4")
        p.set(plan(BridgeChoice.OWN, edited))
        val stop = Thread { t.letGo() }.apply { isDaemon = true; start() }
        assertTrue("up over the new line", result.get(10, java.util.concurrent.TimeUnit.SECONDS))
        stop.join(10_000)
        assertFalse("letGo returned", stop.isAlive)
        assertEquals(listOf("own", "obfs4"), starts)
        assertTrue("the new Tor still running", t.running)
    }

    @Test fun aChangeWhileTorIsStillConnectingStartsItAgain() {
        var choice = BridgeChoice.OFF
        val t = tor({ plan(choice) })
        assertFalse(t.up())
        assertEquals(listOf("plain"), starts)
        // You choose the built-in obfs4 bridges while plain Tor still can't connect: Tor starts again over them.
        choice = BridgeChoice.OBFS4
        works = setOf("obfs4")
        assertTrue(t.up())
        assertEquals(listOf("plain", "obfs4"), starts)
        // Once up, it keeps its route (and the onions it carries) until its next start.
        choice = BridgeChoice.SNOWFLAKE
        assertTrue(t.up())
        assertEquals(listOf("plain", "obfs4"), starts)
        t.down()
        works = setOf("snowflake")
        assertTrue(t.up())
        assertEquals(listOf("plain", "obfs4", "snowflake"), starts)
    }
}
