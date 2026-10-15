// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant

import android.os.SystemClock
import app.heyrobot.core.BargeHit
import app.heyrobot.core.BargeHitKind
import app.heyrobot.core.BargeRun
import app.heyrobot.core.BargeTestLogic
import app.heyrobot.core.BargeTestResult
import app.heyrobot.core.BenchState
import app.heyrobot.core.RLog
import app.heyrobot.core.TtsStop
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLog
import app.heyrobot.core.writeAtomic
import app.heyrobot.sys.Paths
import app.heyrobot.voice.VoiceOutput

// VOICE PROCESS. Settings › Voice › "Barge-in test" (0.2.3): Robot reads a fixed paragraph of about 20 s with no word
// like "stop" and no wake phrase, and the user says "stop" or a wake phrase at any point; the result says whether
// she heard it and how soon she went quiet. The silent run plays the whole paragraph and counts any "stop" or wake
// word heard although nobody spoke. With the three "Hear me while Robot talks" modes (and the two playbacks), the
// user compares them on the phone. Keywords go here instead of to the panel while it runs. Main thread only.
//
// 0.3.1: every keyword heard is kept, with its phrase id (the first one used to end the test, so "stop, then hey
// robot" could only ever show the first), and the count run lets Robot read to the end while you say "stop" and the
// wake phrases as often as you like. The tricky-words run is a silent run of a paragraph full of words like "stop":
// whatever fires is her own voice, set aside by EchoGuard or not.

object BargeTest {
    @Volatile var active = false; private set
    private var r: BargeTestResult? = null
    private var speakStartE = 0L
    private var heardE = 0L
    private var lastMic = 0
    private var lastAec = 0

    private val timeout = Runnable { finish(timedOut = true) }

    fun start(run: BargeRun) {
        if (active) return
        val hub = VoiceHub
        val base = BargeTestResult(BenchState.RUNNING, run == BargeRun.SILENT || run == BargeRun.TRICKY, System.currentTimeMillis(), hub.settings.bargeIn,
            hub.settings.playback, count = run == BargeRun.COUNT, tricky = run == BargeRun.TRICKY, interrupt = hub.interruptInUse)
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
        r = base.copy(note = BargeTestLogic.note(run))
        write(r!!)
        hub.updateCapture()
        val text = if (run == BargeRun.TRICKY) BargeTestLogic.TRICKY_PARAGRAPH else BargeTestLogic.PARAGRAPH
        for (s in text.split(Regex("(?<=[.!?])\\s+"))) hub.tts.speak(s)
        hub.main.postDelayed(timeout, BargeTestLogic.TIMEOUT_MS)
        RLog.i("barge_test", "run" to run, "mode" to base.mode, "playback" to base.playback)
    }

    /** From VoiceHub: Robot's voice started or stopped. */
    fun onSpeaking(on: Boolean) {
        if (!active) return
        val now = SystemClock.elapsedRealtime()
        if (on) {
            if (speakStartE == 0L) speakStartE = now
            noteVoice()
            return
        }
        // Silent at last: the paragraph ended, or the voice stopped after a keyword.
        noteCapture()
        val cur = r ?: return
        r = cur.copy(spokeMs = if (speakStartE > 0) (if (heardE > 0) heardE else now) - speakStartE else 0,
            stopMs = if (heardE > 0) now - heardE else -1)
        finish(timedOut = false)
    }

    /**
     * From VoiceHub: "stop" ([phrase] 0) or a wake phrase was heard; [safe]: it can't be Robot's own voice (EchoGuard).
     * Every one is kept and logged; in the normal run the first one stops Robot.
     */
    fun onKeyword(phrase: Int, safe: Boolean) {
        if (!active) return
        // First, so what it notes (the phone's voice taking over) is in the copy made below.
        noteCapture()
        val cur = r ?: return
        val stop = phrase == 0
        val now = SystemClock.elapsedRealtime()
        val kind = when {
            speakStartE == 0L -> BargeHitKind.EARLY
            heardE > 0 -> BargeHitKind.LATE
            !safe -> BargeHitKind.SET_ASIDE
            cur.silent -> BargeHitKind.FALSE
            cur.count -> BargeHitKind.COUNTED
            else -> BargeHitKind.STOPPED_HER
        }
        val at = if (speakStartE == 0L) -1L else now - speakStartE
        VoiceLog.add(VoiceEvent.BARGE_HIT, phrase, at.toInt(), kind.id)
        val next = cur.plus(BargeHit(phrase, at, kind))
        r = when (kind) {
            BargeHitKind.EARLY, BargeHitKind.LATE -> next
            BargeHitKind.SET_ASIDE -> next.copy(suppressed = next.suppressed + 1)
            BargeHitKind.FALSE -> if (stop) next.copy(falseStops = next.falseStops + 1) else next.copy(falseWakes = next.falseWakes + 1)
            BargeHitKind.COUNTED -> next.copy(counted = next.counted + 1)
            BargeHitKind.STOPPED_HER -> {
                heardE = now
                next.copy(heard = if (stop) BargeTestResult.HEARD_STOP else BargeTestResult.HEARD_WAKE, atMs = at)
            }
        }
        if (kind == BargeHitKind.STOPPED_HER) VoiceHub.stopVoice(TtsStop.TEST)
    }

    /** The capture while Robot speaks (it changes back a moment after she stops). */
    private fun noteCapture() {
        noteVoice()
        val l = VoiceHub.listener ?: return
        lastMic = l.micKind.id
        lastAec = l.aec.id
    }

    /** Whether the phone's voice speaks (Kokoro may hand a run to it). */
    private fun noteVoice() {
        val cur = r ?: return
        if (!cur.phoneVoice && (VoiceHub.tts as? VoiceOutput)?.usesPhoneVoice == true) r = cur.copy(phoneVoice = true)
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
        val (run, detected) = when (res.run) {
            BargeRun.SILENT -> 1 to res.falseStops + res.falseWakes
            BargeRun.COUNT -> 2 to res.counted
            BargeRun.TRICKY -> 3 to res.falseStops + res.falseWakes + res.suppressed
            BargeRun.FIRST -> 0 to if (res.heard != BargeTestResult.HEARD_NOTHING) 1 else 0
        }
        VoiceLog.add(VoiceEvent.BARGE_TEST, run, detected, res.stopMs.toInt())
    }

    private fun write(res: BargeTestResult) {
        try { writeAtomic(Paths.bargeTest(VoiceHub.app), res.toJson().toString()) } catch (t: Throwable) { RLog.w("barge_test_save", t) }
    }
}
