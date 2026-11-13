// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.steps

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.assistant.car.CarModeController
import app.heyrobot.assistant.handsfree.HandsFreeHub
import app.heyrobot.assistant.routines.OwnTimerPanel
import app.heyrobot.core.RLog
import app.heyrobot.core.i18n.Arg
import app.heyrobot.sys.AppLanguage
import app.heyrobot.sys.RobotTimers
import app.heyrobot.sys.RoutineFacts
import app.heyrobot.core.i18n.HandsFreeStrings
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.ToolStrings
import app.heyrobot.core.read.ReadCommand
import app.heyrobot.core.read.StepRouter
import app.heyrobot.core.read.StepSay
import app.heyrobot.core.steps.StepAct
import app.heyrobot.core.steps.StepEnd
import app.heyrobot.core.steps.StepSession
import app.heyrobot.core.steps.StepSplit
import app.heyrobot.core.steps.StepWord
import app.heyrobot.core.steps.StepWords
import app.heyrobot.core.SpeechText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// VOICE PROCESS. Step by step (docs/plans/step-by-step-plan.md): runs core StepSession for a page Read it to me can read,
// shows the step in StepsActivity (large text, screen on), says it, and hears "next", "back", "again", "stop" (and the
// French) with no wake word through StepEar, only while the steps' window is on screen. Ends on "stop", on leaving the
// window, on a call or a silenced microphone (as conversation mode pauses), in Car mode, or after 30 minutes with no
// word. Starts, stops and the window's comings and goings all run on the main thread, each window tied to its own
// session's [serial], so a second "walk me through" can't be ended by the first one's window. Logs codes and counts,
// never words.
object StepVoice {
    /** What StepsActivity shows. [listening]: the ear has the microphone (the window shows its mark the whole time). */
    data class Ui(val on: Boolean = false, val index: Int = 0, val total: Int = 0, val text: String = "", val offerS: Long? = null,
        val listening: Boolean = false)

    private val _ui = MutableStateFlow(Ui())
    val ui: StateFlow<Ui> = _ui

    @Volatile private var session: StepSession? = null
    @Volatile private var page: String = ""
    private var ear: StepEar? = null
    private var gen = 0
    /** The session the window on screen belongs to (main thread). */
    private var serial = 0
    private val app: Context get() = VoiceHub.app
    private val ticker = object : Runnable {
        override fun run() {
            act(synchronized(this@StepVoice) { session?.tick(SystemClock.uptimeMillis()) } ?: return)
            if (session != null) VoiceHub.main.postDelayed(this, TICK_MS) // not after an idle end
        }
    }
    /** The timer offer closes after its 30 seconds (StepSession.OFFER_MS). */
    private val offerEnd = Runnable { act(synchronized(this@StepVoice) { session?.tick(SystemClock.uptimeMillis()) } ?: emptyList()) }
    /** While the steps are open (with or without the ear): a call, a silenced microphone or Car mode ends them; a lost
     *  shared listener is replaced. */
    private val watch = object : Runnable {
        override fun run() {
            if (session == null) return
            val e = ear
            val why = when {
                CarModeController.on -> StepEnd.CAR
                inCall() || e?.silencedNow(SystemClock.uptimeMillis()) == true -> StepEnd.CALL
                else -> null
            }
            if (why != null) { act(synchronized(this@StepVoice) { session?.cut(why) } ?: return); return }
            // The mark follows the ear each time: off when it lost the microphone, on again once it has it back.
            if (e != null) {
                val ok = e.keep() && e.hearing
                if (ok != _ui.value.listening) { RLog.i("steps_ear", "on" to ok); _ui.value = _ui.value.copy(listening = ok) }
            }
            VoiceHub.main.postDelayed(this, WATCH_MS)
        }
    }

    val on: Boolean get() = session?.ended == null && session != null

    /** Starts the steps of [text]: the line for the panel ("I can't find steps here", or how to use them). */
    fun start(text: String): Phrase {
        val steps = StepSplit.split(text)
        if (steps.isEmpty()) return HandsFreeStrings.stepsNone()
        // The window keeps the screen on and the ear listens: never while driving.
        if (CarModeController.on) return HandsFreeStrings.stepsCar()
        RLog.i("steps_start", "steps" to steps.size)
        VoiceHub.main.post { begin(steps, text) }
        return HandsFreeStrings.stepsStarting()
    }

    /** Main thread: ends what was open, then opens [steps] in a window tied to the new [serial]. */
    private fun begin(steps: List<String>, text: String) {
        stop()
        val now = SystemClock.uptimeMillis()
        val s = StepSession(steps, text, now)
        val id = ++serial
        synchronized(this) { session = s; page = text }
        act(s.start(now), speakNow = false)
        VoiceHub.main.postDelayed(ticker, TICK_MS)
        try {
            app.startActivity(Intent(app, StepsActivity::class.java).putExtra(StepsActivity.EXTRA_SERIAL, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { RLog.w("steps_window", t); act(s.left()) }
    }

    /** The panel heard something while the steps are open: a step word, or a question the page answers; else null. */
    fun route(q: String): ReadCommand? {
        val s = session?.takeIf { it.ended == null } ?: return null
        return StepRouter.during(q, page, s.steps.getOrNull(s.at))
    }

    /** fix-train-14: no router took [q] and it asks the page what the page doesn't say ([StepRouter.silent]): that line,
     *  never the model's guess. Null with something attached, or with no steps open. */
    fun silent(q: String, attached: Boolean): Phrase? =
        if (!attached && on && StepRouter.silent(q)) panel(StepSay(null, q)) else null

    /** The panel's line for [c]; the rest (the window, a timer, the end) is done here. */
    fun panel(c: StepSay): Phrase {
        val now = SystemClock.uptimeMillis()
        val a = synchronized(this) {
            val s = session ?: return HandsFreeStrings.readNothingOn()
            val w = c.word
            if (w != null) s.word(w, now) else s.ask(c.question.orEmpty(), now)
        }
        VoiceHub.main.post { act(a.filter { it !is StepAct.Say }) }
        return a.filterIsInstance<StepAct.Say>().map { it.line }.let { if (it.isEmpty()) Phrase.Plain("") else Phrase.Join(it) }
    }

    /** A button on the steps' window (Yes is the timer button). */
    fun tap(w: StepWord) = heardWord(w)

    /** The panel opened (true) or closed: the ear steps aside while you talk to Robot. */
    fun panelShown(shown: Boolean) { VoiceHub.main.post { panelOpen = shown; ear?.pause(shown) } }

    // Main thread. Still true when the ear starts from the panel's "walk me through": that ear starts paused.
    private var panelOpen = false

    /** The window of session [id] is on screen (true) or left (false: the steps end, if they are still its own). */
    fun shown(on: Boolean, id: Int) {
        VoiceHub.main.post {
            if (id != serial) return@post
            if (!on) { act(synchronized(this) { session?.left() } ?: return@post); return@post }
            if (ear != null || session == null) return@post
            // "Hey robot, next" goes to the panel, which steps once; the ear leaves it alone.
            val e = StepEar(app) { text -> if (!WAKE.containsMatchIn(text)) StepWords.of(text)?.let { w -> VoiceHub.main.post { heardWord(w) } } }
            e.pause(panelOpen)
            val ok = try { e.start() } catch (t: Throwable) { RLog.w("steps_ear", t); false }
            // No ear (taps only): the watch still ends the steps on a call or in Car mode.
            if (ok) ear = e else e.stop()
            VoiceHub.main.removeCallbacks(watch)
            VoiceHub.main.postDelayed(watch, WATCH_MS)
            RLog.i("steps_ear", "on" to ok)
            _ui.value = _ui.value.copy(listening = ok && e.hearing)
        }
    }

    private fun heardWord(w: StepWord) {
        RLog.i("steps_word", "w" to w.ordinal)
        act(synchronized(this) { session?.word(w, SystemClock.uptimeMillis()) } ?: return)
    }

    private fun act(acts: List<StepAct>, speakNow: Boolean = true) {
        val said = ArrayList<Phrase>()
        for (a in acts) when (a) {
            is StepAct.Show -> {
                _ui.value = _ui.value.copy(on = true, index = a.index, total = a.total, text = a.text, offerS = a.offerS)
                VoiceHub.main.removeCallbacks(offerEnd)
                if (a.offerS != null) VoiceHub.main.postDelayed(offerEnd, StepSession.OFFER_MS)
            }
            is StepAct.Say -> said += a.line
            is StepAct.SetTimer -> said += timer(a.seconds)
            is StepAct.End -> { RLog.i("steps_end", "why" to a.why.id); stop() }
        }
        // An end's own line ("Steps stopped") is said after stop() cleared the session.
        if (said.isNotEmpty()) speak(Phrase.Join(said), if (speakNow) 0 else 1, ending = acts.any { it is StepAct.End })
    }

    /** The timer tool's way: Robot's own timer when Routines keeps them, else the clock's (ToolExecutor.timer). */
    private fun timer(seconds: Long): Phrase {
        OwnTimerPanel.forTool(app, seconds)?.let { (_, said) -> return said }
        val i = Intent(android.provider.AlarmClock.ACTION_SET_TIMER).putExtra(android.provider.AlarmClock.EXTRA_LENGTH, seconds.toInt())
            .putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true).putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, "Robot") // l10n: not text (the timer's label, as the timer tool's)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val ok = try {
            app.startActivity(RoutineFacts.toChosenClock(app, i)); RobotTimers.set(app, seconds); true
        } catch (t: Throwable) { RLog.w("steps_timer", t); false }
        return if (ok) ToolStrings.timerSet("duration" to Arg.Seconds(seconds)) else ToolStrings.noClockTimers()
    }

    /** Says [p] once Robot's voice is quiet (the panel's own reply first, [tries] waits at least). */
    private fun speak(p: Phrase, tries: Int = 0, ending: Boolean = false) {
        val g = ++gen
        fun go(left: Int) {
            if (g != gen || (!ending && session == null)) return
            if (left > 0 || VoiceHub.tts.isSpeaking) { VoiceHub.main.postDelayed({ go(maxOf(0, left - 1)) }, 400); return }
            val t = SpeechText.clean(p.render(AppLanguage.langs(app)))
            if (t.isNotEmpty()) VoiceHub.tts.speak(t)
        }
        go(tries)
    }

    /** A phone call or another app's call: Robot's own headset session (SessionRoute.held) is not one. */
    private fun inCall(): Boolean = HandsFreeHub.callActive()

    private fun stop() {
        VoiceHub.main.removeCallbacks(ticker)
        VoiceHub.main.removeCallbacks(offerEnd)
        VoiceHub.main.removeCallbacks(watch)
        ear?.stop()
        ear = null
        synchronized(this) { session = null; page = "" }
        _ui.value = Ui()
    }

    private const val TICK_MS = 30_000L
    private const val WATCH_MS = 2_000L
    private val WAKE = Regex("""(?i)\brobot\b""") // l10n: not text (words Robot hears)
}
