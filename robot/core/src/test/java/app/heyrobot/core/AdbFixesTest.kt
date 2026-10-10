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

    @Test fun alreadyRobotsIsLeftAsItIs() {
        for (role in listOf(true, false)) for (perm in listOf(true, false)) {
            assertEquals(Decision.ALREADY_ROBOT, d(role, perm, robot))
            assertEquals(Decision.ALREADY_ROBOT, d(role, perm, "app.heyrobot/app.heyrobot.assistant.RobotRecognitionService"))
        }
    }

    @Test fun componentNamesCompareInEitherForm() {
        assertTrue(RecognizerRepair.same("app.heyrobot/.assistant.RobotRecognitionService", "app.heyrobot/app.heyrobot.assistant.RobotRecognitionService"))
        assertTrue(RecognizerRepair.same(" app.heyrobot/.assistant.RobotRecognitionService ", robot))
        assertFalse(RecognizerRepair.same(other, robot))
        assertFalse(RecognizerRepair.same(null, robot))
        assertFalse(RecognizerRepair.same("", robot))
        // The class the manifest declares (ProcessIsolationTest checks the recognizer component itself).
        assertEquals("app.heyrobot/.assistant.RobotRecognitionService", robot)
        assertEquals("voice_recognition_service", RecognizerRepair.KEY)
    }

    @Test fun theFixesListSaysWhatToRunAndHowItIsChecked() {
        val ids = AdbFixes.ALL.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        val rec = AdbFixes.ALL.first { it.check == AdbCheck.RECOGNIZER_IS_ROBOT }
        assertEquals(listOf("adb shell settings put secure voice_recognition_service app.heyrobot/.assistant.RobotRecognitionService"), rec.commands)
        val grant = AdbFixes.ALL.first { it.check == AdbCheck.SECURE_SETTINGS_GRANTED }
        assertEquals(listOf("adb shell pm grant app.heyrobot android.permission.WRITE_SECURE_SETTINGS"), grant.commands)
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
}
