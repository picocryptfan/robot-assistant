// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.core.TimeWindow
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.Langs
import app.heyrobot.core.i18n.Msg
import app.heyrobot.core.lock.ActiveLock
import app.heyrobot.core.lock.LockFile
import app.heyrobot.core.lock.LockRule
import app.heyrobot.core.lock.PhoneLock
import app.heyrobot.core.lock.RecordEvent
import app.heyrobot.core.lock.RecordLine
import app.heyrobot.ui.settings.lockRow
import app.heyrobot.ui.settings.newLockId
import app.heyrobot.ui.settings.recordText
import app.heyrobot.core.lock.LockLimits
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/** Settings › Phone lock's own lines: a lock's row and What happened, in both languages, with nothing left unfilled. */
class PhoneLockTextsTest {
    private val bedtime = PhoneLock("lk_bed0", "Bedtime", LockRule.Time(TimeWindow(LocalTime.of(22, 0), LocalTime.of(7, 0)), setOf(DayOfWeek.SUNDAY)))
    private val file = LockFile(locks = listOf(bedtime))
    private val en = Langs.of(Lang.EN)
    private val fr = Langs.of(Lang.FR)

    @Test fun aLocksRow() {
        // The days' words are routines' (RoutineCards); the hours are what this row adds.
        assertTrue(lockRow(bedtime).render(en).endsWith("22:00 to 7:00"))
        assertTrue(lockRow(bedtime).render(fr).contains("de 22 h à 7 h"))
    }

    @Test fun everyRecordLineRenders() {
        for (e in RecordEvent.entries) for (lock in listOf("lk_bed0", ActiveLock.VOICE, "lk_gone", null)) for (l in listOf(en, fr)) {
            val out = recordText(RecordLine(1_792_134_000_000, e, lock, 12), file).render(l)
            assertFalse("$e $lock: $out", Msg.PLACEHOLDER.containsMatchIn(out))
            assertTrue(out, out.isNotBlank())
        }
        assertTrue(recordText(RecordLine(1_792_134_000_000, RecordEvent.STARTED, "lk_bed0"), file).render(en).endsWith("Started: Bedtime"))
        assertTrue(recordText(RecordLine(1_792_134_000_000, RecordEvent.STARTED, "lk_gone"), file).render(en).endsWith("Started: A deleted lock"))
        // A lock by voice made longer shows its new end: the line's time plus the minutes it keeps.
        val end = java.time.Instant.ofEpochMilli(1_792_134_000_000).atZone(java.time.ZoneId.systemDefault()).plusMinutes(90).toLocalTime()
        assertTrue(recordText(RecordLine(1_792_134_000_000, RecordEvent.LONGER, ActiveLock.VOICE, 90), file).render(en)
            .endsWith("Lock by voice made longer, until %d:%02d".format(end.hour, end.minute)))
    }

    @Test fun newIdsAreValid() {
        repeat(50) { assertTrue(LockLimits.validId(newLockId())) }
    }
}
