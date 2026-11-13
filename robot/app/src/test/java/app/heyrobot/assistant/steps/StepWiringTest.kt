// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Step by step's Android half (StepVoice, StepEar), which no unit test can run: the wiring stays (review-step-by-step-b). */
class StepWiringTest {
    private fun code(path: String) = File("src/main/java/app/heyrobot/$path").readText().replace(Regex("\\s+"), " ")
    private val voice = code("assistant/steps/StepVoice.kt")
    private val ear = code("assistant/steps/StepEar.kt")
    private val watch = voice.substringAfter("private val watch = object : Runnable {").substringBefore("val on: Boolean")
    private fun has(text: String, part: String) = assertTrue(part, text.contains(part))

    @Test fun thePanelsWordsGoThroughDuringWithThePageAndTheStepShown() {
        has(voice.substringAfter("fun route(q: String)").substringBefore("fun panel("), "StepRouter.during(q, page, s.steps.getOrNull(s.at))")
        has(code("assistant/read/PanelRead.kt"), "fun route(q: String): ReadCommand? = StepVoice.route(q) ?:")
    }

    @Test fun aCallCarModeOrASilencedMicEndTheStepsThroughCut() {
        has(watch, "CarModeController.on -> StepEnd.CAR")
        has(watch, "inCall() || e?.silencedNow(SystemClock.uptimeMillis()) == true -> StepEnd.CALL")
        has(watch, "act(synchronized(this@StepVoice) { session?.cut(why) } ?: return)")
    }

    @Test fun robotsOwnHeadsetSessionIsNotACall() {
        has(voice, "private fun inCall(): Boolean = HandsFreeHub.callActive()")
        assertFalse(voice.contains("MODE_IN_COMMUNICATION"))
    }

    @Test fun theMicMarkFollowsTheEarEachWatch() {
        has(watch, "val ok = e.keep()")
        has(watch, "_ui.value = _ui.value.copy(listening = ok)")
    }

    @Test fun aNewEarStartsPausedWhileThePanelIsOpen() {
        // "Walk me through" said in the panel: the panel is still open when the ear starts (review-step-by-step-c Low 1).
        has(voice, "fun panelShown(shown: Boolean) { VoiceHub.main.post { panelOpen = shown; ear?.pause(shown) } }")
        val shown = voice.substringAfter("fun shown(on: Boolean, id: Int)").substringBefore("private fun heardWord")
        assertTrue(shown.indexOf("e.pause(panelOpen)") in 0 until shown.indexOf("e.start()"))
        val start = ear.substringAfter("fun start(): Boolean {").substringBefore("private fun attach()")
        assertTrue(start.indexOf("if (paused) return true") in 0 until start.indexOf("attach()"))
    }

    @Test fun theMicMarkShowsOnlyWithACapture() {
        // keep() is true while the panel has the microphone; the mark needs a capture too (review-step-by-step-c Low 4).
        has(ear, "val hearing: Boolean get() { val s = source ?: return false; return s === VoiceHub.listener?.takeIf { it.running } || s.capturing }")
        has(watch, "val ok = e.keep() && e.hearing")
        has(voice.substringAfter("fun shown(on: Boolean, id: Int)").substringBefore("private fun heardWord"), "listening = ok && e.hearing")
    }

    @Test fun theWatchRunsWithNoEarAndTheEarWaitsOutThePanel() {
        // No microphone (taps only): a call or Car mode still ends the steps.
        val shown = voice.substringAfter("fun shown(on: Boolean, id: Int)").substringBefore("private fun heardWord")
        has(shown, "if (ok) ear = e else e.stop() VoiceHub.main.removeCallbacks(watch) VoiceHub.main.postDelayed(watch, WATCH_MS)")
        // The panel's turn has the microphone: the ear opens no capture of its own until it closes.
        val keep = ear.substringAfter("fun keep(): Boolean {").substringBefore("fun pause(")
        assertTrue(keep.indexOf("if (paused) return true") in 0 until keep.indexOf("attach()"))
        // Just after the panel closes, its capture may still read as silenced: not a call yet.
        has(ear, "fun silencedNow(now: Long): Boolean = !paused && now - resumedAt >= SETTLE_MS && silenced")
    }
}
