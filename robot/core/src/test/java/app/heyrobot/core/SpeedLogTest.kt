// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1.2 (design Step 5): the replies' numbers Robot keeps on the phone, and the medians Settings › Model shows. */
class SpeedLogTest {
    private val build = "google/frankel/frankel:16/BP4A.251005.004/14112847:user/release-keys"
    private val gpu = SpeedKey.of(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256, build)!!
    private val cpu = gpu.copy(backend = ModelBackend.CPU)

    private fun s(read: Double, write: Double, readTokens: Int = 1500, written: Int = 80, first: Long = 3000, rebuilt: Boolean = true) =
        SpeedSample(readTokens, read, written, write, first, rebuilt)

    private fun log(vararg e: Pair<SpeedKey, SpeedSample>): String = e.fold(null as String?) { t, (k, x) -> SpeedLog.add(t, k, x) }!!

    @Test fun theKeyIsTheModelBackendFileAndBuild() {
        assertEquals(BrainModel.GENERAL.sha256!!.take(12), gpu.file)
        assertEquals(build, gpu.build)
        // Your own file before its check has no hash yet; a phone that names no build has no key.
        assertNull(SpeedKey.of(BrainModel.OWN, ModelBackend.CPU, null, build))
        assertNull(SpeedKey.of(BrainModel.OWN, ModelBackend.CPU, "not a hash", build))
        assertNull(SpeedKey.of(BrainModel.GENERAL, ModelBackend.CPU, BrainModel.GENERAL.sha256, ""))
        assertNull(SpeedKey.of(BrainModel.GENERAL, ModelBackend.CPU, BrainModel.GENERAL.sha256, null))
        // An upper-case hash is the same file.
        assertEquals(gpu, SpeedKey.of(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256!!.uppercase(), build))
        // A build with spaces or control characters keeps one word.
        assertEquals("abc/def:16", SpeedKey.of(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256, " abc/def :16\n")!!.build)
        assertEquals(SpeedLog.MAX_BUILD, SpeedKey.of(BrainModel.GENERAL, ModelBackend.GPU, BrainModel.GENERAL.sha256, "x".repeat(500))!!.build.length)
    }

    @Test fun eachKeyIsMeasuredApart() {
        val t = log(gpu to s(400.0, 12.0), gpu to s(420.0, 13.0), gpu to s(410.0, 12.5), cpu to s(150.0, 15.0))
        assertEquals(MeasuredSpeed(410.0, 12.5, 3, 3, 3000, 3), SpeedLog.measured(t, gpu))
        // The CPU has one reply: not enough yet.
        assertNull(SpeedLog.measured(t, cpu))
        // Another Android build (an OS update) or another file starts again.
        assertNull(SpeedLog.measured(t, gpu.copy(build = "google/frankel/frankel:16/BP4A.251105.001/1:user/release-keys")))
        assertNull(SpeedLog.measured(t, gpu.copy(file = "0123456789ab")))
        assertNull(SpeedLog.measured(t, gpu.copy(model = BrainModel.SMARTER)))
        assertNull(SpeedLog.measured(t, null))
    }

    @Test fun mediansNotMeans() {
        // One slow reply (the screen went off) doesn't drag the figure down; an even count takes the middle two.
        val t = log(gpu to s(400.0, 12.0), gpu to s(40.0, 1.0), gpu to s(420.0, 13.0), gpu to s(430.0, 14.0))
        val m = SpeedLog.measured(t, gpu)!!
        assertEquals(410.0, m.readTps, 0.0)
        assertEquals(12.5, m.writeTps, 0.0)
        assertEquals(2.0, SpeedLog.median(listOf(3.0, 1.0, 2.0)), 0.0)
        assertEquals(2.5, SpeedLog.median(listOf(4.0, 1.0, 2.0, 3.0)), 0.0)
    }

    @Test fun shortReadsAndShortRepliesDontTimeAnything() {
        // A reused conversation reads only the new turn; an action's call writes a few tokens.
        val t = log(gpu to s(400.0, 12.0), gpu to s(410.0, 12.0), gpu to s(60.0, 2.0, readTokens = 40, written = 3, rebuilt = false),
            gpu to s(55.0, 13.0, readTokens = 30, rebuilt = false, first = 300))
        assertNull(SpeedLog.measured(t, gpu))
        val t2 = SpeedLog.add(t, gpu, s(420.0, 14.0))
        val m = SpeedLog.measured(t2, gpu)!!
        assertEquals(410.0, m.readTps, 0.0)
        assertEquals(3, m.readReplies)
        // Written: 12, 12, 13 and 14 (the 3-token call left out).
        assertEquals(12.5, m.writeTps, 0.0)
        assertEquals(4, m.writeReplies)
        // The first word's median is a new conversation's (the reused one's 300 ms is left out).
        assertEquals(3000L, m.firstWordMs)
        assertEquals(5, m.replies)
    }

    @Test fun eachKeyKeepsItsLastTwenty() {
        var t: String? = null
        for (i in 1..30) t = SpeedLog.add(t, gpu, s(100.0 + i, 10.0))
        val kept = SpeedLog.decode(t)
        assertEquals(SpeedLog.PER_KEY, kept.size)
        assertEquals(111.0, kept.first().second.readTps, 0.0)
        assertEquals(130.0, kept.last().second.readTps, 0.0)
    }

    @Test fun theOldestKeysGo() {
        var t: String? = null
        val keys = (1..10).map { gpu.copy(build = "build/$it") }
        for (k in keys) repeat(3) { t = SpeedLog.add(t, k, s(400.0, 12.0)) }
        val left = SpeedLog.decode(t).map { it.first }.distinct()
        assertEquals(keys.takeLast(SpeedLog.KEYS), left)
        // A key measured again is the newest: it stays when another comes.
        t = SpeedLog.add(t, keys[2], s(400.0, 12.0))
        t = SpeedLog.add(t, gpu.copy(build = "build/11"), s(400.0, 12.0))
        val now = SpeedLog.decode(t).map { it.first }.distinct()
        assertTrue(keys[2] in now)
        assertTrue(keys[3] !in now)
        assertEquals(SpeedLog.KEYS, now.size)
        // At its fullest the file stays far under the most Robot reads.
        var full: String? = null
        for (k in 1..SpeedLog.KEYS) for (i in 1..SpeedLog.PER_KEY) full = SpeedLog.add(full, gpu.copy(build = "x".repeat(SpeedLog.MAX_BUILD - 3) + k),
            s(99999.9, 99999.9, 999999, 999999, 599999))
        assertTrue(full!!.length < SpeedLog.MAX_CHARS / 2)
        assertEquals(SpeedLog.KEYS * SpeedLog.PER_KEY, SpeedLog.decode(full).size)
    }

    @Test fun aMissingOrDamagedFileIsEmpty() {
        assertEquals(emptyList<Any>(), SpeedLog.decode(null))
        assertEquals(emptyList<Any>(), SpeedLog.decode(""))
        assertEquals(emptyList<Any>(), SpeedLog.decode("something else\n"))
        assertNull(SpeedLog.measured(null, gpu))
        // A file far bigger than Robot ever writes isn't read.
        val good = log(gpu to s(400.0, 12.0), gpu to s(400.0, 12.0), gpu to s(400.0, 12.0))
        assertNotNull(SpeedLog.measured(good, gpu))
        assertEquals(emptyList<Any>(), SpeedLog.decode(good + "\n".repeat(SpeedLog.MAX_CHARS)))
        // A damaged line is skipped, the rest kept; adding to a damaged file keeps what it could read.
        val lines = good.lines()
        val bad = listOf(
            "e2b gpu ${gpu.file} $build 1 1500 nan 80 12.0 3000", "e2b gpu ${gpu.file} $build 1 1500 400.0 80 12.0", "x".repeat(300),
            "nope gpu ${gpu.file} $build 1 1500 400.0 80 12.0 3000", "e2b warp ${gpu.file} $build 1 1500 400.0 80 12.0 3000",
            "e2b gpu XYZ $build 1 1500 400.0 80 12.0 3000", "e2b gpu ${gpu.file} $build 2 1500 400.0 80 12.0 3000",
            "e2b gpu ${gpu.file} $build 1 -5 400.0 80 12.0 3000", "e2b gpu ${gpu.file} $build 1 1500 -400.0 80 12.0 3000",
            "e2b gpu ${gpu.file} $build 1 1500 400.0 80 12.0 9999999", "e2b gpu ${gpu.file} $build 1 1500 1e12 80 12.0 3000",
        )
        val damaged = (listOf(lines[0], lines[1]) + bad + lines.drop(2)).joinToString("\n")
        assertEquals(3, SpeedLog.decode(damaged).size)
        assertEquals(SpeedLog.decode(good), SpeedLog.decode(damaged))
        assertEquals(4, SpeedLog.decode(SpeedLog.add(damaged, gpu, s(400.0, 12.0))).size)
    }

    @Test fun numbersAreRoundedAndInTheirUnits() {
        val t = log(gpu to s(SpeedLog.round1(481.2549), SpeedLog.round1(17.36)))
        // Tokens a second to 0.1, written with a point whatever the phone's language; times in whole milliseconds.
        assertEquals("e2b gpu ${gpu.file} $build 1 1500 481.3 80 17.4 3000", t.lines()[1])
        val d = BrainMsg.Done(DoneReason.END, 1630, 3120, 17.36, true, prefillTokens = 1502, prefillTps = 481.2549, decodeTokens = 96)
        assertEquals(SpeedSample(1502, 481.3, 96, 17.4, 3120, true), SpeedLog.sample(d))
        val prev = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.CANADA_FRENCH)
            assertEquals("e2b gpu ${gpu.file} $build 1 1500 481.3 80 17.4 3000", log(gpu to SpeedLog.sample(d)!!.copy(readTokens = 1500, writtenTokens = 80,
                firstWordMs = 3000)).lines()[1])
        } finally {
            java.util.Locale.setDefault(prev)
        }
    }

    @Test fun onlyFinishedMeasuredRepliesCount() {
        val d = BrainMsg.Done(DoneReason.END, 1630, 3120, 17.4, true, prefillTokens = 1502, prefillTps = 481.3, decodeTokens = 96)
        for (r in listOf(DoneReason.END, DoneReason.TOOL_CALLS, DoneReason.LENGTH)) assertNotNull("$r", SpeedLog.sample(d.copy(reason = r)))
        for (r in listOf(DoneReason.CANCELLED, DoneReason.GARBAGE, DoneReason.CONTEXT_FULL)) assertNull("$r", SpeedLog.sample(d.copy(reason = r)))
        // An older :brain's Done (no reading speed), and anything it couldn't measure.
        assertNull(SpeedLog.sample(BrainMsg.Done(DoneReason.END, 1630, 3120, 17.4, true)))
        assertNull(SpeedLog.sample(d.copy(decodeTps = -1.0)))
        assertNull(SpeedLog.sample(d.copy(decodeTps = Double.NaN)))
        assertNull(SpeedLog.sample(d.copy(ttftMs = -1)))
        assertNull(SpeedLog.sample(d.copy(prefillTps = -1.0)))
        assertNull(SpeedLog.sample(d.copy(decodeTokens = -1)))
    }
}
