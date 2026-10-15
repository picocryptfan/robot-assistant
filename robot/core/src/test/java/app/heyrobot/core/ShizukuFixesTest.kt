// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxIdentity
import app.heyrobot.core.termux.TermuxSource
import app.heyrobot.core.termux.TermuxStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 1.0, the ADB fixes without a computer: each fix as rish runs it (the adb command without "adb shell", single-quoted),
 * only the fixed AdbFixes commands by id, and when "Run with Shizuku through Termux" may send one.
 */
class ShizukuFixesTest {
    private val ready = TermuxCheck(TermuxStatus.READY, TermuxSource.F_DROID, TermuxIdentity.F_DROID, "0.118.3", 1002, 1)

    @Test fun eachFixAsRishRunsIt() {
        assertEquals("rish -c 'settings put secure voice_recognition_service ai.robot_app/app.heyrobot.assistant.RobotRecognitionService'",
            ShizukuPath.command("recognizer"))
        assertEquals("rish -c 'pm grant ai.robot_app android.permission.WRITE_SECURE_SETTINGS'", ShizukuPath.command("repair"))
        for (fix in AdbFixes.ALL) {
            val lines = ShizukuPath.rishLines(fix)!!
            assertEquals(fix.commands.size, lines.size)
            for ((adb, rish) in fix.commands.zip(lines)) assertEquals("rish -c '" + adb.removePrefix("adb shell ") + "'", rish)
        }
        // The one recommended first is a fix, and it is the permission grant.
        assertEquals(AdbCheck.SECURE_SETTINGS_GRANTED, AdbFixes.ALL.single { it.id == ShizukuPath.FIRST }.check)
    }

    @Test fun quotingKeepsTheCommandOneWord() {
        assertEquals("'a b'", ShizukuPath.quote("a b"))
        assertEquals("'it'\\''s'", ShizukuPath.quote("it's"))
        assertEquals("'\$(reboot); `id` \"x\"'", ShizukuPath.quote("\$(reboot); `id` \"x\""))
        assertEquals("rish -c 'echo '\\''hi'\\'''", ShizukuPath.rish("adb shell echo 'hi'"))
        assertEquals("''", ShizukuPath.quote(""))
        // Only adb shell commands; never a line break or NUL.
        assertNull(ShizukuPath.rish("adb install x.apk"))
        assertNull(ShizukuPath.rish("settings put secure x y"))
        assertNull(ShizukuPath.rish("adb shell "))
        assertNull(ShizukuPath.rish("adb shell id\nreboot"))
        assertNull(ShizukuPath.rish("adb shell id\u0000"))
        // Termux's set-up line puts rish on the PATH for Termux, and the first run is by hand.
        assertTrue(ShizukuPath.TERMUX_LINE.contains("\"com.termux\"") && ShizukuPath.TERMUX_LINE.contains("chmod 400 \$PREFIX/bin/rish_shizuku.dex"))
        assertEquals("rish -c id", ShizukuPath.FIRST_RUN)
    }

    @Test fun onlyTheAdbFixesCommands() {
        assertEquals(AdbFixes.ALL.map { it.id }.toSet(), AdbFixes.ALL.map { it.id }.filter { ShizukuPath.command(it) != null }.toSet())
        for (bad in listOf(null, "", "gone", "recognizer; reboot", "rish -c 'reboot'", "settings put secure voice_recognition_service x", "REPAIR"))
            assertNull(bad, ShizukuPath.command(bad))
        assertEquals(1, ShizukuPath.number("recognizer"))
        assertEquals(2, ShizukuPath.number("repair"))
        assertEquals(0, ShizukuPath.number("gone"))
    }

    @Test fun sentOnlyToATrustedTermuxWhileUnlocked() {
        assertTrue(ShizukuRun.offered(ready, true))
        assertNull(ShizukuRun.refusal("repair", ready, ready = true, locked = false))
        assertEquals(ShizukuRefusal.NOT_A_FIX, ShizukuRun.refusal("reboot", ready, ready = true, locked = false))
        // The lock screen: never.
        assertEquals(ShizukuRefusal.LOCKED, ShizukuRun.refusal("repair", ready, ready = true, locked = true))
        // Not set up, the update lock, another signer, the test key (even with Run anyway).
        assertEquals(ShizukuRefusal.TERMUX_NOT_READY, ShizukuRun.refusal("repair", ready, ready = false, locked = false))
        assertEquals(ShizukuRefusal.TERMUX_CHANGED, ShizukuRun.refusal("repair", ready.copy(status = TermuxStatus.UPDATED), ready = true, locked = false))
        assertEquals(ShizukuRefusal.TERMUX_CHANGED, ShizukuRun.refusal("repair", ready.copy(status = TermuxStatus.CHANGED), ready = true, locked = false))
        val testKey = ready.copy(status = TermuxStatus.TEST_KEY, source = TermuxSource.GITHUB, testKeyRisk = true)
        assertTrue(testKey.usable)
        assertFalse(ShizukuRun.offered(testKey, true))
        assertEquals(ShizukuRefusal.TEST_KEY, ShizukuRun.refusal("repair", testKey, ready = true, locked = false))
    }

    @Test fun whatTheRunSays() {
        assertEquals(ShizukuOutcome.APPLIED, ShizukuRun.outcome(0, applied = true))
        assertEquals(ShizukuOutcome.NOT_APPLIED, ShizukuRun.outcome(0, applied = false))
        assertEquals(ShizukuOutcome.NO_RISH, ShizukuRun.outcome(127, applied = true))
        assertEquals(ShizukuOutcome.FAILED, ShizukuRun.outcome(1, applied = false))
        // Kept with the set-up's status, read back by Settings.
        val st = app.heyrobot.core.termux.TermuxSetupStatus(shizuku = ShizukuResult("repair", 5, ShizukuOutcome.REFUSED, refusal = ShizukuRefusal.LOCKED))
        assertEquals(st, app.heyrobot.core.termux.TermuxSetupStatus.parse(st.toJson().toString()))
        val ran = st.copy(shizuku = ShizukuResult("recognizer", 6, ShizukuOutcome.APPLIED, exit = 0))
        assertEquals(ran, app.heyrobot.core.termux.TermuxSetupStatus.parse(ran.toJson().toString()))
        // A result for something that isn't a fix is dropped as it is read.
        assertNull(app.heyrobot.core.termux.TermuxSetupStatus.parse("""{"shizuku":{"fix":"reboot","at":1,"outcome":"APPLIED"}}""").shizuku)
    }
}
