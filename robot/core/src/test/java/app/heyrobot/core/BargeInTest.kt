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
            mic = MicKind.COMMUNICATION.id, aec = AecState.ON.id, heard = BargeTestResult.HEARD_STOP, atMs = 6200, stopMs = 140, spokeMs = 6300,
            hits = listOf(BargeHit(0, 6200, BargeHitKind.STOPPED_HER)))
        assertEquals(r, BargeTestResult.fromJson(r.toJson().toString()))
        assertEquals(listOf("Heard your “stop” 6.2 s in; Robot was silent 140 ms later.",
            "Hear me while Robot talks: Echo cancellation · Robot's voice plays as Assistant audio · mic: call (echo cancellation) · echo cancellation on"),
            BargeTestLogic.lines(r))
        val silent = r.copy(silent = true, heard = 0, falseStops = 2, falseWakes = 1, suppressed = 1, spokeMs = 19_800, mode = BargeIn.OFF, mic = MicKind.RECOGNITION.id,
            aec = AecState.OFF.id, hits = listOf(BargeHit(0, 3000, BargeHitKind.FALSE), BargeHit(0, 9000, BargeHitKind.FALSE), BargeHit(1, 12_000, BargeHitKind.FALSE),
                BargeHit(0, 15_000, BargeHitKind.SET_ASIDE)))
        assertEquals("Silent run: 2 false “stop”, 1 false wake word in 19.8 s of Robot talking.", BargeTestLogic.lines(silent)[0])
        // Which keyword fired, and when: a wake phrase by name in the app, by number otherwise.
        assertEquals("Heard: 3.0 s “stop” · 9.0 s “stop” · 12.0 s “hey robot” · 15.0 s “stop” (set aside)", BargeTestLogic.lines(silent) { if (it == 1) "hey robot" else null }[1])
        assertEquals("Heard: 3.0 s “stop” · 9.0 s “stop” · 12.0 s wake phrase no. 1 · 15.0 s “stop” (set aside)", BargeTestLogic.lines(silent)[1])
        assertEquals("1 set aside as possibly Robot's own voice.", BargeTestLogic.lines(silent)[2])
        assertEquals("Silent run: no false “stop” or wake word in 19.8 s of Robot talking.",
            BargeTestLogic.lines(silent.copy(falseStops = 0, falseWakes = 0, hits = emptyList(), suppressed = 0))[0])
        assertEquals("Robot didn't hear you (she talked for 19.8 s).", BargeTestLogic.lines(r.copy(heard = 0, spokeMs = 19_800, hits = emptyList()))[0])
        val failed = BargeTestResult(BenchState.FAILED, false, 1, BargeIn.OFF, Playback.CALL, note = "Robot isn't listening.")
        assertEquals(listOf("Robot isn't listening."), BargeTestLogic.lines(failed))
        // A test that never reported back says so instead of "running" for ever.
        val stuck = failed.copy(state = BenchState.RUNNING, note = "Reading…", started = 0)
        assertEquals("The test stopped without a result. Try again.", BargeTestLogic.lines(stuck, now = BargeTestLogic.TIMEOUT_MS + 20_000)[0])
        assertEquals(null, BargeTestResult.fromJson("not json"))
    }

    /** 0.3.0 kept only the first keyword and didn't say which wake phrase it was. */
    @Test fun everyKeywordIsKeptWithItsPhrase() {
        val wake = BargeTestResult(BenchState.DONE, silent = false, started = 1000, mode = BargeIn.ALWAYS, playback = Playback.CALL,
            heard = BargeTestResult.HEARD_WAKE, atMs = 4100, stopMs = 300, spokeMs = 4100, phoneVoice = true,
            hits = listOf(BargeHit(0, 2000, BargeHitKind.SET_ASIDE), BargeHit(2, 4100, BargeHitKind.STOPPED_HER), BargeHit(0, 4300, BargeHitKind.LATE)), suppressed = 1)
        assertEquals(wake, BargeTestResult.fromJson(wake.toJson().toString()))
        assertEquals(listOf("Heard your wake phrase “robot” 4.1 s in; Robot was silent 300 ms later.",
            "Heard: 2.0 s “stop” (set aside) · 4.1 s “robot” · 4.3 s “stop” (after the first)",
            "1 set aside as possibly Robot's own voice.",
            "Hear me while Robot talks: Always echo cancellation · Robot's voice plays as Call audio · the phone's voice"),
            BargeTestLogic.lines(wake) { if (it == 2) "robot" else null })
        // Results from 0.3.0 (no hits) still read.
        val old = BargeTestResult.fromJson("""{"state":"DONE","silent":false,"started":1,"mode":"ECHO_CANCEL","playback":"ASSISTANT","heard":2,"atMs":3000,"stopMs":90}""")!!
        assertEquals(emptyList<BargeHit>(), old.hits)
        assertEquals("Heard your wake phrase 3.0 s in; Robot was silent 90 ms later.", BargeTestLogic.lines(old)[0])
        // At most MAX_HITS are kept, and a damaged hit is dropped.
        var many = wake.copy(hits = emptyList())
        repeat(BargeTestResult.MAX_HITS + 5) { many = many.plus(BargeHit(0, it * 100L, BargeHitKind.LATE)) }
        assertEquals(BargeTestResult.MAX_HITS, many.hits.size)
        // The list says when it was cut short.
        assertTrue(BargeTestLogic.lines(many)[1].endsWith(" · …"))
        assertFalse(BargeTestLogic.lines(wake)[1].endsWith("…"))
        val damaged = wake.toJson().put("hits", org.json.JSONArray().put(org.json.JSONArray().put(0).put(100).put(99)).put("x").put(org.json.JSONArray().put(1).put(200).put(2)))
        assertEquals(listOf(BargeHit(1, 200, BargeHitKind.COUNTED)), BargeTestResult.fromJson(damaged.toString())!!.hits)
    }

    /** 0.3.1: a silent run of words like "stop": everything that fires is Robot's own voice, set aside or not. */
    @Test fun theTrickyWordsRunSaysHowOftenHerOwnWordsFire() {
        val r = BargeTestResult(BenchState.DONE, silent = true, started = 1000, mode = BargeIn.ECHO_CANCEL, playback = Playback.ASSISTANT, tricky = true,
            falseStops = 1, suppressed = 2, spokeMs = 18_400,
            hits = listOf(BargeHit(0, 1200, BargeHitKind.SET_ASIDE), BargeHit(0, 9800, BargeHitKind.SET_ASIDE), BargeHit(0, 16_900, BargeHitKind.FALSE)))
        assertEquals(BargeRun.TRICKY, r.run)
        assertEquals(r, BargeTestResult.fromJson(r.toJson().toString()))
        val lines = BargeTestLogic.lines(r)
        assertEquals("Tricky words: Robot's own words fired 3 times in 18.4 s of talking: EchoGuard set aside 2, 1 got through " +
            "(in real use each would stop her or start a question).", lines[0])
        // Set aside or not, every one is listed.
        assertEquals("Heard: 1.2 s “stop” (set aside) · 9.8 s “stop” (set aside) · 16.9 s “stop”", lines[1])
        assertEquals("Tricky words: Robot's own words fired nothing in 18.4 s of talking.",
            BargeTestLogic.lines(r.copy(falseStops = 0, suppressed = 0, hits = emptyList()))[0])
        assertTrue(BargeTestLogic.note(BargeRun.TRICKY).contains("Stay quiet"))
        // The paragraph is about as long as the other one.
        assertTrue(BargeTestLogic.TRICKY_PARAGRAPH.split(Regex("\\s+")).size in 45..70)
    }

    @Test fun theSetupSaysWhenThePhonesVoiceStandsIn() {
        assertEquals("Hear me while Robot talks: Echo cancellation · Robot's voice plays as Assistant audio",
            BargeTestLogic.setup(BargeIn.ECHO_CANCEL, Playback.ASSISTANT, phoneVoice = false))
        assertEquals("Hear me while Robot talks: Off · Robot's voice plays as Call audio · the phone's voice (Kokoro isn't installed)",
            BargeTestLogic.setup(BargeIn.OFF, Playback.CALL, phoneVoice = true, missing = "Kokoro"))
    }

    @Test fun theCountRunListsWhatRobotHeard() {
        val r = BargeTestResult(BenchState.DONE, silent = false, started = 1000, mode = BargeIn.ECHO_CANCEL, playback = Playback.ASSISTANT, count = true,
            counted = 3, spokeMs = 19_600, hits = listOf(BargeHit(0, 2500, BargeHitKind.COUNTED), BargeHit(1, 6000, BargeHitKind.COUNTED), BargeHit(0, 9800, BargeHitKind.COUNTED)))
        assertEquals(BargeRun.COUNT, r.run)
        assertEquals(BargeRun.SILENT, r.copy(silent = true).run)
        assertEquals(BargeRun.FIRST, r.copy(count = false).run)
        assertEquals(r, BargeTestResult.fromJson(r.toJson().toString()))
        val lines = BargeTestLogic.lines(r) { if (it == 1) "hey robot" else null }
        assertEquals("Count run: Robot heard 3 in 19.6 s of talking. Compare with how many you said.", lines[0])
        assertEquals("Heard: 2.5 s “stop” · 6.0 s “hey robot” · 9.8 s “stop”", lines[1])
        assertEquals("Count run: Robot heard nothing in 19.6 s of talking.", BargeTestLogic.lines(r.copy(counted = 0, hits = emptyList()))[0])
        // What each run asks of you.
        assertTrue(BargeTestLogic.note(BargeRun.FIRST).contains("once"))
        assertTrue(BargeTestLogic.note(BargeRun.COUNT).contains("as often as you like"))
        assertTrue(BargeTestLogic.note(BargeRun.SILENT).contains("Stay quiet"))
    }

    @Test fun theParagraphIsAboutTwentySecondsAndSaysNothingRobotListensFor() {
        val words = BargeTestLogic.PARAGRAPH.split(Regex("\\s+")).size
        // Kokoro reads about 2.5 to 3 words a second at normal speed.
        assertTrue("$words words", words in 50..70)
        val lower = BargeTestLogic.PARAGRAPH.lowercase()
        for (w in listOf("stop", "step", "top", "shop", "drop", "robot", "hey", "okay")) assertFalse(w, Regex("\\b$w").containsMatchIn(lower))
    }
}
