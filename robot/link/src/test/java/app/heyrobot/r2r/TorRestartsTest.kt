// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.r2r

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** review-1.2.4 findings 5 and 6: Tor woken at once, and Tor that keeps dying started again a few times an hour. */
class TorRestartsTest {
    private fun code(name: String) = File("src/main/java/app/heyrobot/r2r/$name").readText().replace(Regex("\\s+"), " ")

    @Test fun aFewRestartsAnHourThenItWaits() {
        val cap = RestartCap(max = 3, windowMs = 3_600_000)
        assertTrue(cap.allows(0))
        for (k in 1..3) { cap.died(k * 1000L); assertTrue(cap.allows(k * 1000L)) }
        cap.died(4000)
        assertFalse(cap.allows(5000))
        assertFalse(cap.allows(3_600_999))
        assertTrue(cap.allows(3_601_000))
    }

    @Test fun upChecksTheCapAfterADeath() {
        val up = code("TorRuntime.kt").substringAfter("override fun up(): Boolean").substringBefore("val p = plan()")
        assertTrue(up, up.indexOf("died() restarts.died(") in 0 until up.indexOf("if (!restarts.allows("))
    }

    @Test fun wakeIsDirectAndAQueuedSleepStandsDown() {
        val node = code("LinkNode.kt")
        val wake = node.substringAfter("fun wake(quiet: Boolean = false) = post {").substringBefore("quietWake = quiet")
        assertTrue(wake, wake.contains("awake = true") && wake.contains("tor?.wake()") && !wake.contains("torThread"))
        assertTrue(node.contains("torThread.execute { try { if (!awake) { t.sleep(); if (awake) t.wake() } }"))
        assertTrue(node.contains("@Volatile private var awake = false"))
    }
}
