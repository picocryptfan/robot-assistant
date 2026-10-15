// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Test translation speed (phase 5.0(b), docs/conversation-mode.md 9.1): the test set, the comparison and the gate. */
class MtSpeedTest {
    private val here = File(System.getProperty("user.dir") ?: ".")
    private val asset = File(here, "src/main/assets/" + MtTestSet.ASSET)
    private val set by lazy { MtTestSet.parse(asset.readText()) }

    @Test fun theAppsTestSetIsTheToolsOne() {
        val tools = File(here.absoluteFile.parentFile, "tools/lang/fr-CA/mt-test.tsv")
        assertArrayEquals("copy tools/lang/fr-CA/mt-test.tsv to core/src/main/assets/${MtTestSet.ASSET}", tools.readBytes(), asset.readBytes())
    }

    @Test fun theSetHasAReferenceForEveryLine() {
        assertEquals(23, set.size)
        assertEquals(List(15) { "fr-en" } + List(8) { "en-fr" }, set.map { it.direction })
        assertTrue(set.all { it.reference.isNotBlank() && it.bergamot.isNotBlank() })
        assertEquals("Je vais faire l'épicerie après la job, as-tu besoin de quelque chose?", set[0].source)
        assertEquals("I'm going to go grocery shopping after the job, do you need anything?", set[0].reference)
        assertEquals("I'm going to do the grocery store after the job, do you need anything?", set[0].bergamot)
        // The fast engine and the bergamot wheel agree on 13 of 23 lines on the build machine (docs/conversation-mode.md 9.6).
        assertEquals(13, set.count { it.reference == it.bergamot })
        // A line without the fast engine's column, a comment or another pair is left out.
        assertEquals(1, MtTestSet.parse("# c\nfr-en\ta\tb\tc\td\te\tf\tg\nfr-en\ta\tb\tc\nde-en\ta\tb\tc\td\te\tf\tg\n").size)
    }

    private fun report(outputs: List<String?> = set.map { it.reference }, ms: List<Long> = List(set.size) { 30L }, peakKb: Long = 300 * 1024L) =
        MtSpeedReport.of(set, outputs, ms, mapOf("fr-en" to 174L, "en-fr" to 205L), MtMemory(290 * 1024L, peakKb), 1_000)

    @Test fun theGateNeedsSpeedMemoryAndTheSameTranslations() {
        val ok = report()
        assertTrue(ok.passed)
        assertEquals(23, ok.same)
        assertEquals(13, ok.sameAsBergamot)
        assertEquals(listOf(
            "Speed: median 30 ms, slowest 30 ms a line (the mark is 60 ms or less): passes.",
            "Memory: 300 MB at most, 290 MB now with both directions loaded (the mark is 450 MB or less): passes.",
            "Translations: 23 of 23 the same as on the build machine (the mark is all of them): passes.",
            "Loaded in 174 ms (fr-en), 205 ms (en-fr).",
        ), ok.summary(set.size))
        // Each part on its own.
        val slow = report(ms = List(set.size) { if (it < 12) 61L else 20L })
        assertEquals(61L, slow.medianMs)
        assertFalse(slow.speedOk)
        assertFalse(slow.passed)
        assertFalse(report(peakKb = 451 * 1024L).memoryOk)
        assertTrue(report(peakKb = 450 * 1024L).memoryOk)
        assertFalse(report(peakKb = -1).memoryOk)
        val diff = report(outputs = set.map { it.reference }.toMutableList<String?>().also { it[3] = "Can you text me when you get there?" })
        assertEquals(22, diff.same)
        assertFalse(diff.outputsOk)
        assertTrue(diff.speedOk && diff.memoryOk)
        assertTrue(diff.summary(set.size)[2].startsWith("Translations: 22 of 23 the same"))
    }

    @Test fun aLineThatFailedCountsAsDifferentAndNotInTheTimes() {
        val r = report(outputs = set.map { it.reference }.toMutableList<String?>().also { it[0] = null }, ms = List(set.size) { (it + 1).toLong() })
        assertFalse(r.lines[0].same)
        assertEquals(22, r.times.size)
        assertEquals(12L, r.medianMs) // 2..23
        assertEquals(23L, r.maxMs)
        val text = r.plain(set, "Robot test")
        assertTrue(text.contains(" 1. fr-en    1 ms DIFFERENT\n    source:   Je vais faire"))
        assertTrue(text.contains("    got:      (no translation)\n"))
        assertTrue(text.contains(" 2. fr-en    2 ms same\n"))
        assertTrue(text.contains("Also the same as the bergamot 0.4.5 wheel's phase-4 outputs: 13 of 23"))
    }

    @Test fun medianOfOddAndEvenCounts() {
        assertEquals(0L, MtSpeedReport.median(emptyList()))
        assertEquals(5L, MtSpeedReport.median(listOf(9, 5, 1)))
        assertEquals(4L, MtSpeedReport.median(listOf(1, 3, 5, 9)))
    }

    @Test fun theLastResultIsKept() {
        val r = report(outputs = set.map { it.reference }.toMutableList<String?>().also { it[5] = null })
        assertEquals(r, MtSpeedReport.fromJson(r.toJson()))
        val failed = MtSpeedReport.failed(MtError.NOT_DOWNLOADED, 5)
        assertEquals(failed, MtSpeedReport.fromJson(failed.toJson()))
        assertEquals(listOf(MtError.NOT_DOWNLOADED.text()), failed.summary(set.size))
        assertFalse(failed.passed)
        assertNull(MtSpeedReport.fromJson("{"))
        assertNull(MtSpeedReport.fromJson(null))
    }
}
