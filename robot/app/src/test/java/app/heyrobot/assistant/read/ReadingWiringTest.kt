// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.read

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** fix-stage-reading: a reading's wiring in :voice and :lock, read from the source as ProcessIsolationTest does (spaces and comments ignored). */
class ReadingWiringTest {
    private val SEND = "app.heyrobot.sys.Cmd.send(ctx, app.heyrobot.sys.Cmd.PHONE_LOCK)"

    private fun src(path: String): String {
        val f = listOf(File("src/main/java/app/heyrobot/$path"), File("app/src/main/java/app/heyrobot/$path")).first { it.exists() }
        return f.readLines().joinToString("") { it.substringBefore("// ") }.replace(Regex("\\s+"), "")
    }
    private fun flat(s: String) = s.replace(Regex("\\s+"), "")
    private fun has(path: String, code: String) = assertTrue("$path: $code", src(path).contains(flat(code)))
    private fun before(path: String, a: String, b: String) {
        val s = src(path)
        val i = s.indexOf(flat(a))
        val j = s.indexOf(flat(b))
        assertTrue("$path: $a before $b ($i, $j)", i >= 0 && j > i)
    }

    /** H1: every Cmd.PHONE_LOCK reaches the reading first, which ends it when the lock is for someone else. */
    @Test fun phoneLockReachesTheReading() {
        has("assistant/VoiceHub.kt", "Cmd.PHONE_LOCK -> { ReadingVoice.lockStarted(); PanelController.lockStarted() }")
        has("assistant/read/ReadingVoice.kt", "if (!other) return")
        has("assistant/read/ReadingVoice.kt", "run(s.lockForSomeoneElse())")
    }

    /** H1, by voice: Robot's lock phrase starts it through ACTION_START_VOICE, and Cmd.PHONE_LOCK goes out. */
    @Test fun aLockByVoiceSendsPhoneLock() {
        has("lock/LockAlarmReceiver.kt", "LockSignals.ACTION_START_VOICE -> LockEngine.refresh(app, voiceEnd =")
        has("lock/LockEngine.kt", "if (before == null) started0 = started0 + a")
        has("lock/LockEngine.kt", SEND)
    }

    /** H1, by tap or on a schedule: locks.json's lock starting now (LockSchedule.refresh), and Cmd.PHONE_LOCK goes out. */
    @Test fun aLockByTapOrScheduleSendsPhoneLock() {
        has("lock/LockEngine.kt", "started0 = r.started")
        has("lock/LockEngine.kt", SEND)
    }

    /** H1, at an alarm's end (LockClock.step starts it itself): startedNow sends Cmd.PHONE_LOCK too. */
    @Test fun aLockAtAnAlarmsEndSendsPhoneLock() {
        has("lock/LockClock.kt", "LockEngine.refresh(ctx, startedNow = started)")
        has("lock/LockEngine.kt", "if (started0.isNotEmpty() || startedNow) $SEND")
    }

    /** M1: Robot's voice closing ends a reading, so its notice, headset button and text don't stay 30 minutes. */
    @Test fun shutdownEndsTheReading() = has("assistant/VoiceHub.kt", "ReadingVoice.stopNow() EarbudSession.ended(EarbudButtons.End.KILLED)")

    /** M2: the earbuds' session never takes the headset's buttons from a reading's. */
    @Test fun earbudsStepAsideDuringAReading() =
        has("assistant/VoiceHub.kt", "if (ReadingVoice.state == ReadState.NONE) EarbudSession.speaking(on) else EarbudSession.ended(EarbudButtons.End.STOPPED)")

    /** M3: point and read's check comes after Messages, Conversation mode and hands-free's waiting answer. */
    @Test fun readThisComesAfterAWaitingAnswer() {
        before("assistant/PanelController.kt", "val hfWaits =", "ReadAloud.panelAsk(")
        has("assistant/PanelController.kt", "taken = hfWaits || msgCmd != null || convCmd != null, car = CarModeController.on")
    }

    /** L1: nothing is posted during a lock for someone else (review-lock 10). */
    @Test fun noNoticeUpdateDuringALockForSomeoneElse() = has("assistant/read/ReadingService.kt", "if (foreground && !someoneElse())")

    /** L2: a camera photo a process death left behind goes when :voice starts. */
    @Test fun aLeftPhotoGoesWhenVoiceStarts() {
        has("assistant/VoiceHub.kt", "worker.post { CaptureActivity.dropLeftover(app) }")
        has("assistant/Capture.kt", "File(File(ctx.cacheDir, \"capture\"), \"photo.jpg\").delete()")
    }
}
