// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.voice

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import app.heyrobot.core.Playback
import app.heyrobot.core.RLog
import app.heyrobot.core.output.VolumePolicy

// VOICE PROCESS. 1.1: other apps' music and video pause while you ask Robot something, and start again after the
// answer. The one place Robot asks Android for audio focus (ProcessIsolationTest), so every request has the same
// requester and nests: a question takes AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE (Android's guidance for speech
// recognition: players pause on the transient loss and resume when the focus comes back) from the moment it starts
// listening (SherpaSpeechToText.start; never while the wake word only listens for its word) until the panel says the turn
// is over (PanelMediaPause), and Robot's voice speaking inside it changes nothing. Outside a question Robot's voice asks
// as before 1.1 (may duck), or for a transient gain when OutputGuard decided a long answer pauses other sound.
//
// One requester matters: a request without a listener is filed under the AudioManager alone, so the voice's own request
// would replace the question's, and letting go of it would hand the focus back to the music mid-answer.
//
// During a call (another app's, or the phone's), nothing changes: the question takes no focus and the voice asks no more
// than it did before (FocusPlan.mayTake: the normal mode, or the communication mode Robot holds itself for a headset).

/** Pure. The focus Robot asks for (an AudioManager constant, [NONE] for none). */
internal object FocusPlan {
    const val NONE = 0
    const val MAY_DUCK = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
    const val TRANSIENT = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
    const val EXCLUSIVE = AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE

    /**
     * [turn]: a question is under way (from its first listen to the panel's end of the turn). [speaking]: Robot's voice
     * plays. [pause]: OutputGuard asked this speaking run to pause other sound (VolumePolicy.PAUSE_OTHERS). [mayTake]: the
     * phone is in no call ([mayTake]).
     */
    fun gain(turn: Boolean, speaking: Boolean, pause: Boolean, mayTake: Boolean): Int = when {
        turn && mayTake -> EXCLUSIVE
        speaking -> if (pause && mayTake) TRANSIENT else MAY_DUCK
        else -> NONE
    }

    /**
     * May a question take the focus, or the voice ask for more than ducking? Only in the phone's normal mode, or in the
     * communication mode when Robot holds it itself ([robotHolds]: a headset session's route, its own call audio). Never
     * in a call, a ringing or call screening, even while Robot holds a route (the rule HandsFreeHub.callActive uses).
     */
    fun mayTake(mode: Int, robotHolds: Boolean): Boolean =
        mode == AudioManager.MODE_NORMAL || (mode == AudioManager.MODE_IN_COMMUNICATION && robotHolds)

    /** OutputGuard's volume as a speaking run's ask: only PAUSE_OTHERS pauses; DUCK_OTHERS, NORMAL and CALL may duck. */
    fun pauses(v: VolumePolicy): Boolean = v == VolumePolicy.PAUSE_OTHERS
}

/**
 * The turn's focus and the voice's, as one requester ([Port]). A question holds the focus from its first listen ([listen])
 * until the panel ends the turn ([end]: after [GRACE_MS], once Robot's voice is quiet; at once when the panel closes) or
 * [SAFETY_MS] pass with nothing heard, spoken or done in the panel. Thread-safe; [Port.later] runs on one thread.
 */
internal class FocusKeeper(private val port: Port) {
    interface Port {
        /** The phone is in its normal mode, or its communication mode is Robot's own (FocusPlan.mayTake). */
        fun mayTake(): Boolean
        /** Asks for [gain] with [p]'s attributes, replacing what this requester held. */
        fun request(gain: Int, p: Playback)
        /** Lets go of what this requester held: the music comes back. */
        fun abandon()
        /** Runs [r] after [ms] (a pending run of the same [r] is replaced). */
        fun later(r: Runnable, ms: Long)
        fun cancel(r: Runnable)
        fun log(event: String, gain: Int) {}
    }

    private data class Want(val gain: Int, val playback: Playback)

    private var turn = false
    /** The panel said the turn is over: it ends once the voice is quiet. */
    private var ending = false
    /** Who speaks now (SherpaTextToSpeech, AndroidTextToSpeech) and with which playback. */
    private val speakers = LinkedHashMap<Any, Playback>()
    private var pause = false
    /** What was last asked for (null: nothing, or let go). */
    private var held: Want? = null
    /** Another app took the focus for good: nothing is asked again until the turn and the voice are over. */
    private var lost = false

    private val release = Runnable { synchronized(this) { if (turn && ending && speakers.isEmpty()) finish("focus_ended") } }
    private val safety = Runnable {
        synchronized(this) {
            if (!turn) return@synchronized
            // Never while the voice speaks: an answer read for longer than the limit keeps the music paused.
            if (speakers.isNotEmpty()) touch() else finish("focus_safety")
        }
    }

    /** True while a question holds the focus. */
    val holding: Boolean @Synchronized get() = turn

    /** The gain asked for now, [FocusPlan.NONE] for none. */
    val gain: Int @Synchronized get() = held?.gain ?: FocusPlan.NONE

    /** A question starts listening. False during a call: the turn takes no focus. */
    @Synchronized
    fun listen(): Boolean {
        if (!port.mayTake()) return false
        if (!turn) lost = false
        turn = true
        ending = false
        port.cancel(release)
        touch()
        apply()
        return true
    }

    /** The panel is still busy with the turn (a phase changed): it isn't over, and the safety limit starts again. */
    @Synchronized
    fun keep() {
        if (!turn) return
        ending = false
        port.cancel(release)
        touch()
    }

    /** The panel says the turn is over ([now]: it closed). The music comes back once the voice is quiet. */
    @Synchronized
    fun end(now: Boolean) {
        if (!turn) return
        ending = true
        if (speakers.isNotEmpty()) return
        if (now) finish("focus_closed") else port.later(release, GRACE_MS)
    }

    /** [owner]'s speaking run started or ended, with [p] the playback it runs with. */
    @Synchronized
    fun speaking(owner: Any, p: Playback, on: Boolean) {
        if (on) speakers[owner] = p else if (speakers.remove(owner) == null) return
        if (speakers.isEmpty()) pause = false
        if (turn) {
            touch()
            if (ending && speakers.isEmpty()) port.later(release, GRACE_MS)
        }
        apply()
    }

    /** OutputGuard's volume for the sentence about to be queued: a speaking run only ever goes from ducking to pausing. */
    @Synchronized
    fun volume(v: VolumePolicy) {
        val p = FocusPlan.pauses(v)
        pause = if (speakers.isEmpty()) p else pause || p
        apply()
    }

    /**
     * Android: another app took the focus for good (you asked for music, or started a video). Nothing is asked again until
     * the turn and the voice are over, so a follow-up listen in the same turn doesn't pause what you just started.
     */
    @Synchronized
    fun lostForGood() {
        // A late callback for a request already let go changes nothing.
        if (held == null) return
        if (!lost) port.log("focus_lost", held?.gain ?: FocusPlan.NONE)
        lost = true
    }

    /** [event]: the log line's name (why the turn ended). */
    private fun finish(event: String) {
        port.log(event, held?.gain ?: FocusPlan.NONE)
        turn = false
        ending = false
        port.cancel(release)
        port.cancel(safety)
        apply()
    }

    private fun touch() {
        port.later(safety, SAFETY_MS)
    }

    private fun apply() {
        val may = port.mayTake()
        val g = FocusPlan.gain(turn, speakers.isNotEmpty(), pause, may)
        val want = if (g == FocusPlan.NONE) null else Want(g, if (turn && may) Playback.ASSISTANT else speakers.values.first())
        if (want == held) return
        if (want == null) {
            held = null
            lost = false
            port.abandon()
            return
        }
        // Lost for good: the other app keeps it until this is over.
        if (lost) return
        held = want
        port.request(want.gain, want.playback)
    }

    companion object {
        /** The panel's end of a turn waits this long, so a listen that follows at once (350 ms) keeps the music paused. */
        const val GRACE_MS = 500L
        /** A turn with nothing heard, spoken or done in the panel for this long lets the music come back (a turn that hangs). */
        const val SAFETY_MS = 60_000L
    }
}

/** The :voice process's [FocusKeeper] on Android's AudioManager, with one listener so every request is the same requester. */
object TurnFocus {
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var audio: AudioManager? = null
    private var request: AudioFocusRequest? = null

    private val changes: AudioManager.OnAudioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A transient loss (a call ringing, another assistant) gives the focus back by itself; only a lasting loss counts.
        if (change == AudioManager.AUDIOFOCUS_LOSS) keeper.lostForGood()
    }

    private val keeper: FocusKeeper = FocusKeeper(object : FocusKeeper.Port {
        override fun mayTake(): Boolean {
            val am = audio ?: return false
            return FocusPlan.mayTake(try { am.mode } catch (_: Throwable) { -1 }, CallAudio.held)
        }

        override fun request(gain: Int, p: Playback) {
            val am = audio ?: return
            val r = AudioFocusRequest.Builder(gain).setAudioAttributes(CallAudio.attributes(p)).setOnAudioFocusChangeListener(changes, main).build()
            request = r
            val res = try { am.requestAudioFocus(r) } catch (t: Throwable) { RLog.w("focus", t); AudioManager.AUDIOFOCUS_REQUEST_FAILED }
            log("focus_take", gain, res)
        }

        override fun abandon() {
            val am = audio ?: return
            val r = request ?: return
            request = null
            try { am.abandonAudioFocusRequest(r) } catch (t: Throwable) { RLog.w("focus", t) }
            log("focus_give", FocusPlan.NONE)
        }

        override fun later(r: Runnable, ms: Long) {
            main.removeCallbacks(r)
            main.postDelayed(r, ms)
        }

        override fun cancel(r: Runnable) = main.removeCallbacks(r)

        override fun log(event: String, gain: Int) = log(event, gain, -1)

        private fun log(event: String, gain: Int, result: Int) = RLog.i(event, "gain" to gain, "result" to result)
    })

    private fun init(ctx: Context) {
        if (audio == null) audio = ctx.applicationContext.getSystemService(AudioManager::class.java)
    }

    /** SherpaSpeechToText: a question starts listening (before its first frame is read). */
    fun listen(ctx: Context) {
        init(ctx)
        keeper.listen()
    }

    /** The panel is still in its turn. */
    fun keep() = keeper.keep()

    /** The panel's turn is over ([now]: the panel closed). */
    fun end(now: Boolean = false) = keeper.end(now)

    /** Robot's voice: a speaking run started or ended ([owner]: the engine), with the playback it runs with. */
    fun speaking(ctx: Context, owner: Any, p: Playback, on: Boolean) {
        init(ctx)
        keeper.speaking(owner, p, on)
    }

    /** OutputGuard's volume for the sentence about to be queued (DUCK_OTHERS ducks, PAUSE_OTHERS pauses other sound). */
    fun volume(v: VolumePolicy) = keeper.volume(v)
}
