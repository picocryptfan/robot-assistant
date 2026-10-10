package app.heyrobot.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BargeInTest {
    private val down = VolumeKeyStop.KEY_VOLUME_DOWN
    private val up = VolumeKeyStop.KEY_VOLUME_UP

    @Test fun keyCodesAreAndroids() {
        // KeyEvent.KEYCODE_VOLUME_UP / KEYCODE_VOLUME_DOWN.
        assertEquals(24, up)
        assertEquals(25, down)
    }

    @Test fun volumeDownStopsRobotOnlyWhileSheSpeaksInTheShownPanel() {
        assertTrue(VolumeKeyStop.stops(down, StopKey.VOLUME_DOWN, speaking = true, panelShown = true, interactive = true))
        // Volume up still changes the volume with the default setting.
        assertFalse(VolumeKeyStop.stops(up, StopKey.VOLUME_DOWN, speaking = true, panelShown = true, interactive = true))
        // Not speaking: the volume changes as always.
        assertFalse(VolumeKeyStop.stops(down, StopKey.VOLUME_DOWN, speaking = false, panelShown = true, interactive = true))
        // The panel isn't on screen (closed, screen off, lock screen without the panel): the key is the system's.
        assertFalse(VolumeKeyStop.stops(down, StopKey.VOLUME_DOWN, speaking = true, panelShown = false, interactive = true))
        assertFalse(VolumeKeyStop.stops(down, StopKey.VOLUME_DOWN, speaking = true, panelShown = true, interactive = false))
    }

    @Test fun theSettingPicksTheKeys() {
        assertTrue(VolumeKeyStop.matches(down, StopKey.EITHER) && VolumeKeyStop.matches(up, StopKey.EITHER))
        assertTrue(VolumeKeyStop.matches(down, StopKey.VOLUME_DOWN) && !VolumeKeyStop.matches(up, StopKey.VOLUME_DOWN))
        assertFalse(VolumeKeyStop.matches(down, StopKey.OFF) || VolumeKeyStop.matches(up, StopKey.OFF))
        // Other keys never.
        for (k in listOf(0, 4, 26, 66, 164)) for (s in StopKey.entries) assertFalse(VolumeKeyStop.matches(k, s))
        assertFalse(VolumeKeyStop.stops(down, StopKey.OFF, speaking = true, panelShown = true, interactive = true))
    }

    @Test fun bargeTestResultRoundTripsAndReads() {
        val r = BargeTestResult(BenchState.DONE, silent = false, started = 1000, mode = BargeIn.ECHO_CANCEL, playback = Playback.ASSISTANT,
            mic = MicKind.COMMUNICATION.id, aec = AecState.ON.id, heard = BargeTestResult.HEARD_STOP, atMs = 6200, stopMs = 140, spokeMs = 6300)
        assertEquals(r, BargeTestResult.fromJson(r.toJson().toString()))
        assertEquals(listOf("Heard your “stop” 6.2 s in; Robot was silent 140 ms later.",
            "Mode: Echo cancellation · Assistant audio · mic: call (echo cancellation) · echo cancellation on"), BargeTestLogic.lines(r))
        val silent = r.copy(silent = true, heard = 0, falseStops = 2, falseWakes = 1, suppressed = 1, spokeMs = 19_800, mode = BargeIn.OFF, mic = MicKind.RECOGNITION.id, aec = AecState.OFF.id)
        assertEquals("Silent run: 2 false “stop”, 1 false wake word in 19.8 s of Robot talking.", BargeTestLogic.lines(silent)[0])
        assertEquals("1 “stop” set aside as possibly Robot's own voice.", BargeTestLogic.lines(silent)[1])
        assertEquals("Silent run: no false “stop” or wake word in 19.8 s of Robot talking.", BargeTestLogic.lines(silent.copy(falseStops = 0, falseWakes = 0))[0])
        assertEquals("Robot didn't hear you (she talked for 19.8 s).", BargeTestLogic.lines(r.copy(heard = 0, spokeMs = 19_800))[0])
        val failed = BargeTestResult(BenchState.FAILED, false, 1, BargeIn.OFF, Playback.CALL, note = "Robot isn't listening.")
        assertEquals(listOf("Robot isn't listening."), BargeTestLogic.lines(failed))
        // A test that never reported back says so instead of "running" for ever.
        val stuck = failed.copy(state = BenchState.RUNNING, note = "Reading…", started = 0)
        assertEquals("The test stopped without a result. Try again.", BargeTestLogic.lines(stuck, now = BargeTestLogic.TIMEOUT_MS + 20_000)[0])
        assertEquals(null, BargeTestResult.fromJson("not json"))
    }

    @Test fun theParagraphIsAboutTwentySecondsAndSaysNothingRobotListensFor() {
        val words = BargeTestLogic.PARAGRAPH.split(Regex("\\s+")).size
        // Kokoro reads about 2.5 to 3 words a second at normal speed.
        assertTrue("$words words", words in 50..70)
        val lower = BargeTestLogic.PARAGRAPH.lowercase()
        for (w in listOf("stop", "step", "top", "shop", "drop", "robot", "hey", "okay")) assertFalse(w, Regex("\\b$w").containsMatchIn(lower))
    }
}
