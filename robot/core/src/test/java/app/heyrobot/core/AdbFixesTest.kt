// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.core

import app.heyrobot.core.RecognizerRepair.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staying the assistant across restarts: when Robot writes voice_recognition_service, and the ADB fixes list. */
class AdbFixesTest {
    private val robot = AdbFixes.RECOGNIZER
    private val other = "com.example.speech/.Recognizer"

    private fun d(role: Boolean, perm: Boolean, cur: String?, installed: Boolean = cur != null) =
        RecognizerRepair.decide(role, perm, cur, robot, installed)

    @Test fun writesOnlyAsTheAssistantWithTheGrantAndNoOtherRecognizer() {
        // Unset (or set to something no longer installed): written when Robot may.
        assertEquals(Decision.WRITE, d(role = true, perm = true, cur = null))
        assertEquals(Decision.WRITE, d(role = true, perm = true, cur = ""))
        assertEquals(Decision.WRITE, d(role = true, perm = true, cur = other, installed = false))
        // Without the adb grant: the ADB fixes screen says how.
        assertEquals(Decision.NO_PERMISSION, d(role = true, perm = false, cur = null))
        assertEquals(Decision.NO_PERMISSION, d(role = true, perm = false, cur = ""))
        // Not the assistant: not Robot's business, grant or not.
        for (perm in listOf(true, false)) for (cur in listOf<String?>(null, "", other)) {
            assertEquals(Decision.NOT_ASSISTANT, d(role = false, perm = perm, cur = cur))
        }
        // Another installed recognizer also keeps the assistant at boot: left alone.
        assertEquals(Decision.OTHER_RECOGNIZER, d(role = true, perm = true, cur = other))
        assertEquals(Decision.OTHER_RECOGNIZER, d(role = true, perm = false, cur = other))
    }

    /**
     * 1.2 (every-android-phone, Step 6): what keeps Robot the assistant at boot, as [RecognizerRepair.decide] reads it: a
     * stock phone's own installed recognizer ([RecognizerRepair.Kept.OTHER]) means the fix isn't asked for.
     */
    @Test fun keptReadsTheRecognizerAsTheRepairDoes() {
        val kept = { cur: String?, installed: Boolean -> RecognizerRepair.kept(cur, robot, installed) }
        assertEquals(RecognizerRepair.Kept.ROBOT, kept(robot, true))
        assertEquals(RecognizerRepair.Kept.ROBOT, kept("ai.robot_app/app.heyrobot.assistant.RobotRecognitionService", false))
        assertEquals(RecognizerRepair.Kept.OTHER, kept(other, true))
        for (cur in listOf<String?>(null, "", "  ")) assertEquals(RecognizerRepair.Kept.NONE, kept(cur, true))
        // Set to something no longer installed: Android clears it at boot, so the fix is still asked for.
        assertEquals(RecognizerRepair.Kept.NONE, kept(other, false))
        // The same split as the repair's: OTHER is exactly the case it leaves alone, NONE the one it writes.
        for (cur in listOf<String?>(null, "", other, robot)) for (installed in listOf(true, false)) {
            val d = d(role = true, perm = true, cur = cur, installed = installed)
            assertEquals("$cur $installed", when (d) {
                Decision.ALREADY_ROBOT -> RecognizerRepair.Kept.ROBOT
                Decision.OTHER_RECOGNIZER -> RecognizerRepair.Kept.OTHER
                else -> RecognizerRepair.Kept.NONE
            }, kept(cur, installed))
        }
    }

    @Test fun alreadyRobotsIsLeftAsItIs() {
        for (role in listOf(true, false)) for (perm in listOf(true, false)) {
            assertEquals(Decision.ALREADY_ROBOT, d(role, perm, robot))
            assertEquals(Decision.ALREADY_ROBOT, d(role, perm, "ai.robot_app/app.heyrobot.assistant.RobotRecognitionService"))
        }
    }

    @Test fun robotZeroNinesRecognizerIsAnotherApp() {
        // 1.0 is a new app (ai.robot_app): 0.9's recognizer, still installed beside it, is another app's and left alone;
        // once 0.9 is uninstalled it names nothing installed, and Robot writes its own when it may.
        val old = "app.heyrobot/.assistant.RobotRecognitionService"
        assertFalse(RecognizerRepair.same(old, robot))
        assertEquals(Decision.OTHER_RECOGNIZER, d(role = true, perm = true, cur = old))
        assertEquals(Decision.WRITE, d(role = true, perm = true, cur = old, installed = false))
    }

    @Test fun componentNamesCompareInEitherForm() {
        assertTrue(RecognizerRepair.same("com.example.speech/.Recognizer", "com.example.speech/com.example.speech.Recognizer"))
        assertTrue(RecognizerRepair.same(" ai.robot_app/app.heyrobot.assistant.RobotRecognitionService ", robot))
        // Robot's classes aren't in its package (1.0): the short form names ai.robot_app.assistant…, which doesn't exist.
        assertFalse(RecognizerRepair.same("ai.robot_app/.assistant.RobotRecognitionService", robot))
        assertFalse(RecognizerRepair.same(other, robot))
        assertFalse(RecognizerRepair.same(null, robot))
        assertFalse(RecognizerRepair.same("", robot))
        // The package and class the manifest declares (ProcessIsolationTest checks the recognizer component itself).
        assertEquals("ai.robot_app/app.heyrobot.assistant.RobotRecognitionService", robot)
        assertEquals("voice_recognition_service", RecognizerRepair.KEY)
    }

    @Test fun theFixesListSaysWhatToRunAndHowItIsChecked() {
        val ids = AdbFixes.ALL.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        val rec = AdbFixes.ALL.first { it.check == AdbCheck.RECOGNIZER_IS_ROBOT }
        assertEquals(listOf("adb shell settings put secure voice_recognition_service ai.robot_app/app.heyrobot.assistant.RobotRecognitionService"), rec.commands)
        val grant = AdbFixes.ALL.first { it.check == AdbCheck.SECURE_SETTINGS_GRANTED }
        assertEquals(listOf("adb shell pm grant ai.robot_app android.permission.WRITE_SECURE_SETTINGS"), grant.commands)
        // Robot never writes the assistant itself (RoleManager owns it): no command or key for it here.
        for (f in AdbFixes.ALL) for (c in f.commands) {
            assertFalse(c, c.contains("voice_interaction_service"))
            if (c.contains("settings put")) assertEquals(c, "voice_recognition_service", c.split(" ")[5])
        }
        for (f in AdbFixes.ALL) assertTrue(f.why.isNotBlank() && f.title.isNotBlank())
        assertTrue(AdbFixes.HEADER.contains("USB debugging") && AdbFixes.HEADER.contains("off again"))
    }

    @Test fun statusShowsAppliedNotAppliedOrNeedsNewer() {
        val grant = AdbFixes.ALL.first { it.check == AdbCheck.SECURE_SETTINGS_GRANTED }
        assertEquals(AdbFixState.APPLIED, AdbFixes.state(grant, applied = true, versionCode = 23))
        assertEquals(AdbFixState.NOT_APPLIED, AdbFixes.state(grant, applied = false, versionCode = 23))
        assertEquals(AdbFixState.NEEDS_NEWER, AdbFixes.state(grant, applied = true, versionCode = 22))
        assertEquals("Needs Robot 0.2.3+", AdbFixes.label(grant, AdbFixState.NEEDS_NEWER))
        assertEquals("Done", AdbFixes.label(grant, AdbFixState.APPLIED))
    }

    /**
     * 1.2 (every-android-phone, Step 6): another installed recognizer set (a stock phone's own) makes the recognizer fix "Not
     * needed", never "Not done"; nothing else changes, and unset, unreadable or uninstalled (NONE, as on GrapheneOS) reads as before.
     */
    @Test fun theRecognizerFixIsNotNeededOverAnotherRecognizer() {
        val rec = AdbFixes.ALL.single { it.check == AdbCheck.RECOGNIZER_IS_ROBOT }
        val grant = AdbFixes.ALL.single { it.check == AdbCheck.SECURE_SETTINGS_GRANTED }
        val other = RecognizerRepair.Kept.OTHER
        assertEquals(AdbFixState.NOT_NEEDED, AdbFixes.state(rec, applied = false, versionCode = 23, kept = other))
        assertEquals("Not needed", AdbFixes.label(rec, AdbFixState.NOT_NEEDED))
        assertTrue(AdbFixes.notNeeded(rec, other))
        // GrapheneOS (none set), a recognizer uninstalled or unreadable: as before.
        assertEquals(AdbFixState.NOT_APPLIED, AdbFixes.state(rec, applied = false, versionCode = 23, kept = RecognizerRepair.Kept.NONE))
        assertEquals(AdbFixState.NOT_APPLIED, AdbFixes.state(rec, applied = false, versionCode = 23))
        assertEquals(AdbFixState.APPLIED, AdbFixes.state(rec, applied = true, versionCode = 23, kept = RecognizerRepair.Kept.ROBOT))
        // The grant replaces nothing (Robot's repair leaves another recognizer alone): its state never changes with it.
        for (k in RecognizerRepair.Kept.entries) {
            assertFalse(AdbFixes.notNeeded(grant, k))
            assertEquals(AdbFixState.NOT_APPLIED, AdbFixes.state(grant, applied = false, versionCode = 23, kept = k))
            assertEquals(AdbFixState.NEEDS_NEWER, AdbFixes.state(grant, applied = false, versionCode = 22, kept = k))
        }
        // The page's rows read as Assistant setup's do: the check said, with the decision the repair makes.
        assertEquals(RecognizerRepair.Kept.OTHER, RecognizerRepair.kept("com.example.speech/.Recognizer", AdbFixes.RECOGNIZER, currentInstalled = true))
        assertEquals(RecognizerRepair.Decision.OTHER_RECOGNIZER,
            RecognizerRepair.decide(roleHeld = true, canWrite = true, current = "com.example.speech/.Recognizer", robot = AdbFixes.RECOGNIZER, currentInstalled = true))
    }
}
