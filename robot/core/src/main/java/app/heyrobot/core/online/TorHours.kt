// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.online

import org.json.JSONObject

// Pure. What went online's row for Tor (docs/plans/everyday-set-plan.md §5.2, job E4): one row per hour, "Tor network
// (Robot to Robot)", from Tor's own byte counters, never a relay's address. Counters go back to zero when Tor starts again,
// and :link can be stopped at any moment, so what an hour has so far is kept in a small file between samples: at most
// [SAMPLE_MS] of traffic is lost when :link is killed. An hour with no traffic has no row.

class TorHours(private val load: () -> String?, private val save: (String) -> Unit, private val now: () -> Long = System::currentTimeMillis) {
    private var loaded = false
    private var hour = NO_HOUR
    private var out = 0L
    private var inBytes = 0L
    private var lastRead = 0L
    private var lastWritten = 0L
    private var lastSample = NEVER

    /** Tor is starting: its counters begin at zero again. */
    @Synchronized fun started() {
        lastRead = 0
        lastWritten = 0
    }

    /** Whether it's time for another sample. */
    @Synchronized fun due(): Boolean {
        val t = now()
        return lastSample == NEVER || t < lastSample || t - lastSample >= SAMPLE_MS
    }

    /** Tor's counters as they are now ([read], [written]: bytes since it started). */
    @Synchronized fun sample(read: Long, written: Long) {
        ensure()
        val r = (if (read < lastRead) read else read - lastRead).coerceAtLeast(0)
        val w = (if (written < lastWritten) written else written - lastWritten).coerceAtLeast(0)
        lastRead = read
        lastWritten = written
        inBytes += r
        out += w
        settle(r > 0 || w > 0)
    }

    /** No counters to read now (Tor isn't running): an hour that has ended still gets its row. */
    @Synchronized fun flush() {
        ensure()
        settle(false)
    }

    private fun settle(changed: Boolean) {
        val t = now()
        lastSample = t
        val h = t / HOUR_MS
        var dirty = changed
        if (hour == NO_HOUR) hour = h
        if (h != hour) {
            if (out > 0 || inBytes > 0) {
                NetRecord.torHour(out, inBytes)
                dirty = true
            }
            out = 0
            inBytes = 0
            hour = h
        }
        if (dirty) try { save(encode()) } catch (_: Exception) {}
    }

    private fun encode(): String = JSONObject().put("h", hour).put("o", out).put("i", inBytes).toString()

    private fun ensure() {
        if (loaded) return
        loaded = true
        val o = try { JSONObject(load() ?: return) } catch (_: Exception) { return }
        fun n(k: String): Long? = (o.opt(k) as? Number)?.toLong()?.takeIf { it >= 0 }
        hour = n("h") ?: return
        out = n("o") ?: 0
        inBytes = n("i") ?: 0
    }

    companion object {
        const val HOUR_MS = 3_600_000L
        const val SAMPLE_MS = 300_000L
        private const val NO_HOUR = -1L
        private const val NEVER = -1L
    }
}
