package app.heyrobot.assistant

import android.os.SystemClock
import app.heyrobot.core.BargeTestLogic
import app.heyrobot.core.BargeTestResult
import app.heyrobot.core.BenchState
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.Paths

// VOICE PROCESS. Settings › Voice › "Barge-in test" (0.2.3): Robot reads a fixed paragraph of about 20 s with no word
// like "stop" and no wake phrase, and the user says "stop" or a wake phrase at any point; the result says whether
// she heard it and how soon she went quiet. The silent run plays the whole paragraph and counts any "stop" or wake
// word heard although nobody spoke. With the three "Hear me while Robot talks" modes (and the two playbacks), the
// user compares them on the phone. Keywords go here instead of to the panel while it runs. Main thread only.

object BargeTest {
    @Volatile var active = false; private set
    private var r: BargeTestResult? = null
    private var speakStartE = 0L
    private var heardE = 0L
    private var lastMic = 0
    private var lastAec = 0

    private val timeout = Runnable { finish(timedOut = true) }

    fun start(silent: Boolean) {
        if (active) return
        val hub = VoiceHub
        val base = BargeTestResult(BenchState.RUNNING, silent, System.currentTimeMillis(), hub.settings.bargeIn, hub.settings.playback)
        val l = hub.listener
        if (l == null || !l.running || !l.wakeWordLoaded) {
            write(base.copy(state = BenchState.FAILED, note = "Robot isn't listening for wake words right now. Turn the wake word on (Settings › Voice) " +
                "and check Assistant setup, then try again."))
            return
        }
        if (PanelController.shown) {
            write(base.copy(state = BenchState.FAILED, note = "Close Robot's panel first, then start the test from here."))
            return
        }
        hub.stopVoice(TtsStop.TEST)
        active = true
        speakStartE = 0
        heardE = 0
        r = base.copy(note = if (silent) "Robot is reading the test paragraph. Stay quiet until she's done."
            else "Robot is reading the test paragraph. Say “stop” or your wake word at any point.")
        write(r!!)
        hub.updateCapture()
        for (s in BargeTestLogic.PARAGRAPH.split(Regex("(?<=[.!?])\\s+"))) hub.tts.speak(s)
        hub.main.postDelayed(timeout, BargeTestLogic.TIMEOUT_MS)
        RLog.i("barge_test", "silent" to silent, "mode" to base.mode, "playback" to base.playback)
    }

    /** From VoiceHub: Robot's voice started or stopped. */
    fun onSpeaking(on: Boolean) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (on) {
            if (speakStartE == 0L) speakStartE = now
            return
        }
        // Silent at last: the paragraph ended, or the voice stopped after a keyword.
        noteCapture()
        val cur = r ?: return
        r = cur.copy(spokeMs = if (speakStartE > 0) (if (heardE > 0) heardE else now) - speakStartE else 0,
            stopMs = if (heardE > 0) now - heardE else -1)
        finish(timedOut = false)
    }

    /** From VoiceHub: "stop" ([stop]) or a wake phrase was heard; [safe]: it can't be Robot's own voice (EchoGuard). */
    fun onKeyword(stop: Boolean, safe: Boolean) {
        val cur = r ?: return
        if (!active || speakStartE == 0L || heardE > 0) return
        noteCapture()
        if (stop && !safe) {
            r = cur.copy(suppressed = cur.suppressed + 1)
            return
        }
        if (cur.silent) {
            r = if (stop) cur.copy(falseStops = cur.falseStops + 1) else cur.copy(falseWakes = cur.falseWakes + 1)
            return
        }
        val now = SystemClock.elapsedRealtime()
        heardE = now
        r = cur.copy(heard = if (stop) BargeTestResult.HEARD_STOP else BargeTestResult.HEARD_WAKE, atMs = now - speakStartE)
        VoiceHub.stopVoice(TtsStop.TEST)
    }

    /** The capture while Robot speaks (it changes back a moment after she stops). */
    private fun noteCapture() {
        val l = VoiceHub.listener ?: return
        lastMic = l.micKind.id
        lastAec = l.aec.id
    }

    private fun finish(timedOut: Boolean) {
        if (!active) return
        active = false
        VoiceHub.main.removeCallbacks(timeout)
        var res = (r ?: return).copy(mic = lastMic, aec = lastAec)
        res = when {
            timedOut && speakStartE == 0L -> res.copy(state = BenchState.FAILED, note = "Robot's voice didn't start. Check Settings › Voice › Test the voice.")
            timedOut -> res.copy(state = BenchState.DONE, note = "", spokeMs = SystemClock.elapsedRealtime() - speakStartE)
            else -> res.copy(state = BenchState.DONE, note = "")
        }
        if (timedOut) VoiceHub.stopVoice(TtsStop.TEST)
        r = null
        write(res)
        VoiceHub.updateCapture()
        val detected = if (res.silent) res.falseStops + res.falseWakes else if (res.heard != BargeTestResult.HEARD_NOTHING) 1 else 0
        VoiceLog.add(VoiceEvent.BARGE_TEST, if (res.silent) 1 else 0, detected, res.stopMs.toInt())
    }

    private fun write(res: BargeTestResult) {
        try { writeAtomic(Paths.bargeTest(VoiceHub.app), res.toJson().toString()) } catch (t: Throwable) { RLog.w("barge_test_save", t) }
    }
}
