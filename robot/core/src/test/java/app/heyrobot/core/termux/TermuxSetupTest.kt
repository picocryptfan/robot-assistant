package app.heyrobot.core.termux

import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Settings › Termux's checklist (B5.1), the status file the :voice process writes, the result's data URI, and the voice log's words. */
class TermuxSetupTest {
    private val fdroid = TermuxPackage(true, "0.118.3", listOf(TermuxIdentity.F_DROID), hasRunCommandService = true, permissionOwner = "com.termux")
    private val passed = TermuxSetupStatus(testAt = 1, testOk = true, testMs = 340, timeout = true, signer = TermuxIdentity.F_DROID)

    @Test fun nothingRunsUntilEveryLineIsGreen() {
        val pinned = TermuxIdentity.check(fdroid, TermuxIdentity.F_DROID)
        assertTrue(TermuxSetup.ready(pinned, true, passed, TermuxIdentity.F_DROID))
        assertFalse(TermuxSetup.ready(pinned, false, passed, TermuxIdentity.F_DROID))
        assertFalse(TermuxSetup.ready(TermuxIdentity.check(fdroid), true, passed, null))
        assertFalse(TermuxSetup.ready(pinned, true, passed.copy(testOk = false), TermuxIdentity.F_DROID))
        // No GNU timeout: Stop and the limit couldn't end a run's tree.
        assertFalse(TermuxSetup.ready(pinned, true, passed.copy(timeout = false), TermuxIdentity.F_DROID))
        // Reinstalled from GitHub over a pinned F-Droid Termux: refused until Trust again.
        val github = fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))
        assertFalse(TermuxSetup.ready(TermuxIdentity.check(github, TermuxIdentity.F_DROID), true, passed, TermuxIdentity.F_DROID))
        assertTrue(TermuxSetup.ready(TermuxIdentity.check(github, TermuxIdentity.GITHUB), true, passed, TermuxIdentity.GITHUB))
        // The Play build can't take commands from other apps.
        val play = fdroid.copy(signers = listOf(TermuxIdentity.GOOGLE_PLAY), hasRunCommandService = false)
        assertFalse(TermuxSetup.ready(TermuxIdentity.check(play, TermuxIdentity.GOOGLE_PLAY), true, passed, TermuxIdentity.GOOGLE_PLAY))
    }

    @Test fun theChecklistInWords() {
        val none = TermuxSetup.lines(TermuxIdentity.check(TermuxPackage(false)), false, TermuxSetupStatus(), null)
        assertEquals(SetupStep.entries, none.map { it.step })
        assertTrue(none.none { it.mark == SetupMark.OK })
        assertTrue(none[0].text.contains("F-Droid"))
        val ok = TermuxSetup.lines(TermuxIdentity.check(fdroid, TermuxIdentity.F_DROID), true, passed.copy(listAt = 2, scripts = listOf(TermuxScriptInfo("backup"))),
            TermuxIdentity.F_DROID)
        assertEquals(List(7) { SetupMark.OK }, ok.map { it.mark })
        assertEquals("Termux 0.118.3 from F-Droid", ok[0].text)
        assertEquals("Termux answered in 340 ms", ok[4].text)
        assertEquals("~/robot: 1 script", ok[5].text)
        assertEquals("Trusted · signer 228FB2CF…34D41C42", ok[6].text)
        // allow-external-apps off: the Test says so, and the line to paste.
        val off = TermuxSetup.lines(TermuxIdentity.check(fdroid, TermuxIdentity.F_DROID), true,
            TermuxSetupStatus(testAt = 1, testFailure = TermuxFailure.EXTERNAL_APPS_OFF), TermuxIdentity.F_DROID)
        assertEquals(SetupMark.TODO, off[3].mark)
        assertTrue(off[3].text.contains("allow-external-apps is off"))
        assertEquals(TermuxFailure.EXTERNAL_APPS_OFF.en, off[4].text)
        assertTrue(TermuxSetup.lines(TermuxIdentity.check(fdroid.copy(signers = listOf(TermuxIdentity.GITHUB))), true, TermuxSetupStatus(), null)[0].text
            .contains("public test key"))
        assertTrue(TermuxSetup.ALLOW_LINE.contains("allow-external-apps=true") && TermuxSetup.ALLOW_LINE.endsWith("termux-reload-settings"))
        assertTrue(TermuxSetup.FDROID_URL.startsWith("https://f-droid.org/"))
    }

    @Test fun theTestsOutputIsRead() {
        assertTrue(TermuxSetup.testOk("robot-ok\n/data/data/com.termux/files/usr/bin/timeout\n"))
        assertTrue(TermuxSetup.timeoutFound("robot-ok\n/data/data/com.termux/files/usr/bin/timeout\n"))
        assertFalse(TermuxSetup.timeoutFound("robot-ok\n"))
        assertFalse(TermuxSetup.testOk("robot-okay\n"))
        assertFalse(TermuxSetup.testOk(null))
    }

    @Test fun theStatusFileRoundTrips() {
        val s = passed.copy(listAt = 5, folderMissing = false, scripts = listOf(TermuxScriptInfo("backup", "Copies", listOf("sauvegarde"))),
            listFailure = TermuxFailure.NO_ANSWER, running = "list", runningAt = 9)
        assertEquals(s, TermuxSetupStatus.fromJson(s.toJson()))
        assertEquals(TermuxSetupStatus(), TermuxSetupStatus.parse("not json"))
        assertEquals(TermuxSetupStatus(), TermuxSetupStatus.parse(null))
        // A name a hand-edited file holds that isn't a safe file name is dropped.
        assertEquals(emptyList<TermuxScriptInfo>(), TermuxSetupStatus.parse("""{"scripts":[{"name":"../x"},{"name":"a b"}]}""").scripts)
    }

    @Test fun eachRunsResultFindsItsRun() {
        assertEquals("robot-termux://run/123", TermuxRun.resultUri(123))
        assertEquals(123, TermuxRun.execId("robot-termux://run/123"))
        for (bad in listOf(null, "", "robot-termux://run/", "robot-termux://run/-1", "robot-termux://run/0", "robot-termux://run/12a", "robot-termux://run/99999999999",
            "https://run/12", "robot-termux://run/12/x")) assertNull(bad, TermuxRun.execId(bad))
    }

    @Test fun theVoiceLogSaysCodesOnly() {
        assertEquals(listOf(170, 171, 172, 173), listOf(VoiceEvent.TERMUX_SENT, VoiceEvent.TERMUX_DONE, VoiceEvent.TERMUX_FAILED, VoiceEvent.TERMUX_LATE).map { it.id })
        assertEquals(TermuxLog.SENT, VoiceEvent.TERMUX_SENT.id)
        assertEquals(TermuxLog.LATE, VoiceEvent.TERMUX_LATE.id)
        assertTrue(VoiceEvent.entries.filter { it.id in TermuxLog.FIRST_ID..TermuxLog.LAST_ID }.all { it.name.startsWith("TERMUX_") })
        assertEquals("Termux run sent: command after its card · limit 60 s",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.TERMUX_SENT, TermuxRunKind.CARDED.id, 60, 0)))
        assertEquals("Termux run ended: exit 0 in 0.8 s · output up to 4 KB (cut)",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.TERMUX_DONE, TermuxExitBucket.OK.id, 800, TermuxLog.doneC(3000, true))))
        assertEquals("Termux run failed: allow-external-apps off", VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.TERMUX_FAILED, TermuxFailure.EXTERNAL_APPS_OFF.id)))
        assertEquals("Termux answered after Robot stopped waiting (exit 137/143 (stopped)): dropped",
            VoiceLogText.describe(VoiceLogEntry(0, VoiceEvent.TERMUX_LATE, TermuxExitBucket.STOPPED.id)))
        assertEquals(TermuxFailure.entries.size, TermuxFailure.entries.map { it.id }.toSet().size)
        assertEquals(TermuxRunKind.entries.size, TermuxRunKind.entries.map { it.id }.toSet().size)
    }
}
