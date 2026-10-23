// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** EndEarlyRules and LockCodeRules. */
class LockRulesTest {
    private val min = 60_000L
    private val time = ActiveLock("lk_aaaa", 0, 100 * min)
    private val voice = ActiveLock(ActiveLock.VOICE, 0, 100 * min, 100 * min, 1)

    @Test fun endEarlyOffWaitAndCode() {
        assertEquals(EarlyView.Off, EndEarlyRules.view(LockSettings(), listOf(time), 0))
        assertEquals(EarlyView.Code, EndEarlyRules.view(LockSettings(endEarly = EndEarly.CODE), listOf(time), 0))
        // Someone else: always the code, whatever End early says, a voice lock too.
        for (e in EndEarly.entries) assertEquals(EarlyView.Code, EndEarlyRules.view(LockSettings(forWho = LockFor.SOMEONE_ELSE, endEarly = e), listOf(time, voice), 0))
        assertEquals(EarlyView.Off, EndEarlyRules.view(LockSettings(endEarly = EndEarly.WAIT), emptyList(), 0))
    }

    @Test fun theWaitItsConfirmWindowAndTheReset() {
        val s = LockSettings(endEarly = EndEarly.WAIT, waitMin = 5)
        val st = LockState(active = listOf(time))
        assertEquals(EarlyView.CanStartWait, EndEarlyRules.view(s, st.active, 0))
        val waiting = EndEarlyRules.startWait(s, st, 10 * min)
        assertEquals(EarlyView.Waiting(15 * min), EndEarlyRules.view(s, waiting.active, 12 * min))
        assertFalse(EndEarlyRules.mayEndNow(s, waiting.active, 14 * min))
        assertEquals(EarlyView.Ready(20 * min), EndEarlyRules.view(s, waiting.active, 15 * min))
        assertTrue(EndEarlyRules.mayEndNow(s, waiting.active, 19 * min))
        // After the 5-minute window the wait starts over.
        assertEquals(EarlyView.CanStartWait, EndEarlyRules.view(s, waiting.active, 20 * min))
        assertEquals(EarlyView.Waiting(25 * min), EndEarlyRules.view(s, EndEarlyRules.startWait(s, waiting, 20 * min).active, 20 * min))
        // Starting it again while it runs changes nothing.
        assertEquals(waiting, EndEarlyRules.startWait(s, waiting, 12 * min))
        // The clock moved back before the wait's start: it starts over.
        assertEquals(EarlyView.CanStartWait, EndEarlyRules.view(s, waiting.active, 5 * min))
    }

    @Test fun aVoiceLockForYourselfAlwaysHasTheTenMinuteWait() {
        val off = LockSettings(endEarly = EndEarly.OFF, waitMin = 30)
        assertEquals(EarlyView.CanStartWait, EndEarlyRules.view(off, listOf(voice), 0))
        val w = EndEarlyRules.startWait(off, LockState(active = listOf(voice)), 0)
        assertEquals(EarlyView.Waiting(10 * min), EndEarlyRules.view(off, w.active, 1))
        assertEquals(EndEarly.WAIT, EndEarlyRules.rule(LockSettings(endEarly = EndEarly.CODE), voice))
    }

    @Test fun theStrictestRuleDecides() {
        assertEquals(EndEarly.OFF, EndEarlyRules.strictest(listOf(EndEarly.WAIT, EndEarly.OFF, EndEarly.CODE)))
        assertEquals(EndEarly.CODE, EndEarlyRules.strictest(listOf(EndEarly.WAIT, EndEarly.CODE)))
        assertEquals(EndEarly.WAIT, EndEarlyRules.strictest(listOf(EndEarly.WAIT)))
        assertEquals(EarlyView.Off, EndEarlyRules.view(LockSettings(), listOf(time, voice), 0))
        // Two locks under a wait: both waits must be over.
        val s = LockSettings(endEarly = EndEarly.WAIT, waitMin = 15)
        val both = EndEarlyRules.startWait(s, LockState(active = listOf(time, voice)), 0)
        assertEquals(EarlyView.Waiting(15 * min), EndEarlyRules.view(s, both.active, 11 * min))
    }

    @Test fun codeFormat() {
        for (ok in listOf("1234", "123456", "123456789012")) assertTrue(ok, LockCodeRules.valid(ok))
        for (bad in listOf("", "123", "1234567890123", "12a4", "12 34", "١٢٣٤")) assertFalse(bad, LockCodeRules.valid(bad))
    }

    @Test fun theWaitScheduleAfterWrongTries() {
        assertEquals(listOf(0L, 0, 0, 0, 0, 30_000, min, 2 * min, 4 * min, 8 * min, 16 * min, 32 * min, 60 * min, 60 * min, 60 * min),
            (0..14).map { LockCodeRules.waitAfter(it) })
        var t = CodeTries()
        for (i in 1..4) { t = LockCodeRules.wrong(t, 1_000); assertTrue(LockCodeRules.mayTry(t, 1_000)) }
        t = LockCodeRules.wrong(t, 1_000)
        assertEquals(CodeTries(5, 31_000), t)
        assertFalse(LockCodeRules.mayTry(t, 30_999))
        assertTrue(LockCodeRules.mayTry(t, 31_000))
        assertEquals(CodeTries(), LockCodeRules.RIGHT)
    }

    @Test fun aRestartKeepsTheWaitAndTheClockMovedBackDoesntStrandIt() {
        // nextTry is wall-clock time in state.json: a restart reads it back as it was.
        val t = CodeTries(12, 10 * min + 60 * min)
        assertEquals(70 * min, LockCodeRules.nextTry(t, 10 * min))
        // The clock moved back a day: the wait counts from now, never more than the longest one.
        val back = 10 * min - 24 * 60 * min
        assertEquals(back + 60 * min, LockCodeRules.nextTry(t, back))
        assertEquals(5_000, LockCodeRules.nextTry(CodeTries(5, 0), 5_000))
    }

    @Test fun theMacIsKeyedSaltedAndComparedWhole() {
        val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
        val mac = LockMac { Mac.getInstance("HmacSHA256").apply { init(key) }.doFinal(it) }
        val salt = ByteArray(16) { 7 }
        val f = LockCodeRules.make("246810", salt, mac)
        assertEquals(6, f.digits)
        assertEquals(32, f.mac.size)
        assertTrue(LockCodeRules.matches(f, "246810", mac))
        assertFalse(LockCodeRules.matches(f, "246811", mac))
        assertFalse(LockCodeRules.matches(f, "24681", mac))
        // Another salt, another MAC; another key, no match.
        assertFalse(LockCodeRules.make("246810", ByteArray(16) { 8 }, mac).mac.contentEquals(f.mac))
        val other = SecretKeySpec(ByteArray(32) { 1 }, "HmacSHA256")
        assertFalse(LockCodeRules.matches(f, "246810") { Mac.getInstance("HmacSHA256").apply { init(other) }.doFinal(it) })
        // The input is the label, the salt, the digits.
        assertTrue(LockCodeRules.input(salt, "1234").toString(Charsets.ISO_8859_1).startsWith("robot-lock-code-v1"))
        assertFalse(f.toString().contains("246810"))
    }
}
