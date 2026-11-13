// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.sys

import app.heyrobot.core.feedback.ReportFile
import app.heyrobot.core.feedback.ReportShot
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

// RP5: the sealed copy handed to another app goes after 24 hours (the alarm, or the next start), and only a screenshot
// whose sides the PC tool takes (1 to 4096, ReportShot.fits) goes into a report.
class ReportOutTest {
    @get:Rule val tmp = TemporaryFolder()
    private val ref = "RFB-ABCDEFGH"

    @Test fun aSentCopyGoesAfter24Hours() {
        val dir = tmp.newFolder("reports")
        val now = 1_800_000_000_000L
        fun f(name: String, age: Long) = File(dir, name).apply { writeBytes(byteArrayOf(1)); setLastModified(now - age) }
        val old = f("RFB-AAAAAAAA.robotreport", ReportOut.KEEP_MS + 1)
        val fresh = f("RFB-BBBBBBBB.robotreport", 1_000)
        val oldTmp = f("RFB-CCCCCCCC.tmp", 120_000)
        val newTmp = f("RFB-DDDDDDDD.tmp", 0)
        assertEquals(2, ReportOut.sweep(dir, now))
        assertFalse(old.exists()); assertTrue(fresh.exists()); assertFalse(oldTmp.exists()); assertTrue(newTmp.exists())
        assertEquals(0, ReportOut.sweep(File(dir, "none"), now))
    }

    @Test fun eachCopyHasItsOwnDeadlineAndTheAlarmIsForTheOldest() {
        val dir = tmp.newFolder("own")
        val t0 = 1_800_000_000_000L
        val h = 3_600_000L
        fun f(name: String, at: Long) = File(dir, name).apply { writeBytes(byteArrayOf(1)); setLastModified(at) }
        assertNull(ReportOut.next(dir))
        f("RFB-AAAAAAAA.robotreport", t0)
        f("RFB-BBBBBBBB.robotreport", t0 + 10 * h)
        // A later Send never pushes the first copy's alarm back, and Android's hour of slack still ends before 24 hours.
        val first = ReportOut.next(dir)!!
        assertEquals(t0 + ReportOut.KEEP_MS - ReportOut.EARLY_MS, first)
        assertTrue(first + h < t0 + ReportOut.KEEP_MS)
        assertEquals(0, ReportOut.sweep(dir, first - 1))
        assertEquals(1, ReportOut.sweep(dir, first))
        assertEquals(t0 + 10 * h + ReportOut.KEEP_MS - ReportOut.EARLY_MS, ReportOut.next(dir))
        f("RFB-CCCCCCCC.tmp", t0 + 11 * h)
        assertEquals(t0 + 11 * h + 60_000, ReportOut.next(dir))
    }

    @Test fun theSweepRunsAtAlarmBootAndEitherProcess() {
        val src = File("src/main/java/app/heyrobot/sys/ReportOut.kt").readText()
        val expiry = src.substringAfter("class ReportExpiry").substringBefore("class ReportPicked")
        assertTrue(expiry, expiry.contains("ReportOut.sweep(") && expiry.contains("ReportOut.schedule("))
        val start = src.substringAfter("fun start(").substringBefore("\n    }")
        assertTrue(start, start.contains(":voice") && start.contains("schedule("))
        assertTrue(src.contains("setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP"))
        val boot = File("src/main/java/app/heyrobot/boot/BootReceivers.kt").readText().substringAfter("class BootReceiver").substringBefore("\n}")
        assertTrue(boot, boot.contains("ReportOut.start("))
    }

    @Test fun aScreenshotGoesOnlyIfItsSidesFit() {
        val wide = ReportShot(ReportShot.MAX_SIDE, 2, IntArray(ReportShot.MAX_SIDE * 2)).png()
        val plain = ReportOut.plain(ref, "Robot feedback\n", wide)
        assertNotNull(plain)
        assertEquals(ReportFile.Read.Ok(ReportFile.Report(ref, "Robot feedback\n", wide)).report.ref, (ReportFile.read(plain!!) as ReportFile.Read.Ok).report.ref)
        assertNull(ReportOut.plain(ref, "x", byteArrayOf(1, 2, 3)))
        assertNull(ReportOut.plain("RFB-1", "x", null))
        assertNotNull(ReportOut.plain(ref, "x", null))
    }

    @Test fun theSubjectNamesTheReference() = assertEquals("Robot report RFB-ABCDEFGH", ReportOut.subject(ref))
}
