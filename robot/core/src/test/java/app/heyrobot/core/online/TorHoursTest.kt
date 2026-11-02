// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** E4: Tor's hourly row from its counters: one row an hour, kept across a restart of Tor and of :link. */
class TorHoursTest {
    private val rows = ArrayList<NetEntry>()
    private var now = 100 * TorHours.HOUR_MS
    private var file: String? = null
    private fun hours() = TorHours({ file }, { file = it }, { now })

    @Before fun capture() {
        NetRecord.sink = NetSink { rows += it }
    }

    @After fun reset() {
        NetRecord.sink = null
    }

    private fun bytes() = rows.map { it.out to it.inBytes }

    @Test fun oneRowAnHourWithTheBytesOfThatHour() {
        val h = hours()
        h.sample(1_000, 100)
        now += TorHours.HOUR_MS / 2
        h.sample(4_000, 300)
        assertEquals(0, rows.size)
        now += TorHours.HOUR_MS / 2
        h.sample(5_000, 400)
        assertEquals(listOf(400L to 5_000L), bytes())
        assertEquals(NetPurpose.LINK_TOR, rows[0].purpose)
        assertEquals(NetHost.TOR_NETWORK, rows[0].host)
        // The next hour holds only what came after.
        now += TorHours.HOUR_MS
        h.sample(5_500, 450)
        assertEquals(listOf(400L to 5_000L, 50L to 500L), bytes())
    }

    @Test fun anHourWithNoTrafficHasNoRow() {
        val h = hours()
        h.sample(1_000, 100)
        now += TorHours.HOUR_MS
        h.sample(1_000, 100)
        assertEquals(1, rows.size)
        now += TorHours.HOUR_MS
        h.sample(1_000, 100)
        now += TorHours.HOUR_MS
        h.flush()
        assertEquals(1, rows.size)
    }

    @Test fun torStartingAgainCountsFromZero() {
        val h = hours()
        h.sample(1_000, 100)
        h.started()
        h.sample(300, 30)
        // Without the word it started: counters that went back are a new count too.
        h.sample(100, 10)
        now += TorHours.HOUR_MS
        h.flush()
        assertEquals(listOf(140L to 1_400L), bytes())
    }

    @Test fun aKilledLinkKeepsItsHourAndTheRowComesOnceTorIsNotEvenRunning() {
        hours().sample(1_000, 100)
        now += TorHours.HOUR_MS
        // A new :link, a new Tor not started yet.
        val again = hours()
        again.flush()
        assertEquals(listOf(100L to 1_000L), bytes())
        // And it isn't written twice by the one after.
        hours().flush()
        assertEquals(1, rows.size)
    }

    @Test fun aDamagedFileIsNoHour() {
        file = "{"
        val h = hours()
        h.sample(10, 1)
        now += TorHours.HOUR_MS
        h.flush()
        assertEquals(listOf(1L to 10L), bytes())
    }

    @Test fun samplesAreEveryFewMinutes() {
        val h = hours()
        assertTrue(h.due())
        h.flush()
        assertFalse(h.due())
        now += TorHours.SAMPLE_MS
        assertTrue(h.due())
    }
}
