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
