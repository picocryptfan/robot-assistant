// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.listen

import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.assistant.VoiceHub
import app.heyrobot.core.everyday.ListenForApps
import app.heyrobot.core.everyday.ListenSheetCode
import app.heyrobot.core.everyday.SheetFacts
import app.heyrobot.core.everyday.SheetMic
import app.heyrobot.core.everyday.SheetRefusal
import app.heyrobot.lock.PhoneLockNow
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.EverydaySettingsStore
import app.heyrobot.ui.common.filterObscuredTouches
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.voice.AppListen

// VOICE PROCESS. The listening sheet (docs/plans/everyday-set-plan.md §6.2, D15, E5): many apps don't use the recognizer
// service but start RECOGNIZE_SPEECH for a result, which nothing answers on a phone without Google's apps. Robot answers it
// with this sheet: "Listening…", the level and the words so far, Stop and Cancel; the app gets back one transcript
// (RecognizerIntent.EXTRA_RESULTS) and nothing else. Robot records with its own permission here, only while its own sheet is
// on screen (leaving it ends the listening), as this intent has always worked; never over the lock screen (no showWhenLocked,
// and refused while it's up), never for an app a phone lock for someone else doesn't leave open, never for nobody (an app
// that didn't start it for a result can't get the words). The caller's prompt shows as plain text, at most 80 characters.
// The switch in Settings › Voice turns it off too: then it says so, and the component stays as it is. A results
// PendingIntent the caller passes is never sent: the words go back only through the result. Nothing is kept.
//
// Only while you can see it (E10; core SheetMic says when). The microphone opens once the sheet is resumed and has the
// window's focus, and it ends at the first of onPause, the end of top-resumed, the focus going, or a touch Android marks as
// obscured. onStop alone isn't enough: the theme of an activity started over this one decides whether Android stops this one,
// and a translucent theme with opaque content leaves it paused and unseen. A window drawn over the sheet that takes neither
// focus nor a touch tells nobody, so other apps' overlays are hidden while the sheet is up (1.1.2, F14: HIDE_OVERLAY_WINDOWS).

class ListenSheetActivity : ComponentActivity() {
    private enum class Phase { LISTENING, WRITING, PROBLEM }

    private val listening: AppListening get() = AppListening.live
    private var phase by mutableStateOf(Phase.LISTENING)
    /** 0 to 1, for the level bar. */
    private var level by mutableFloatStateOf(0f)
    private var heard by mutableStateOf("")
    private var problem by mutableStateOf("")
    /** What the app gets when the problem shown is closed (RecognizerIntent's result codes). */
    private var problemResult = RESULT_CANCELED
    private var request: AppListening.Request? = null
    private val mic = SheetMic()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recreated (the window changed): the listening ended with the old one, and isn't started again behind your back.
        if (savedInstanceState != null) return close(RESULT_CANCELED)
        window.filterObscuredTouches()
        window.setHideOverlayWindows(true)
        VoiceHub.init(this)
        val prompt = try { ListenForApps.prompt(intent.getCharSequenceExtra(RecognizerIntent.EXTRA_PROMPT)) } catch (_: Exception) { null }
        setContent { RobotTheme { Sheet(prompt) } }
        refusal()?.let(::refuse)
    }

    /** What stops the sheet listening, now: asked when it opens, to say so at once, and again as the microphone would open. */
    private fun refusal(): SheetRefusal? {
        val caller = callingPackage
        return ListenForApps.decide(SheetFacts(
            caller = caller != null,
            on = EverydaySettingsStore.current(this).listen,
            keyguard = Assistant.keyguard(this),
            lockRefuses = PhoneLockNow.someoneElse(this) && caller !in PhoneLockNow.apps(this),
            language = asked().first,
            modelReady = { VoiceHub.stt.available },
            busy = { listening.busy() },
        ))
    }

    /** The sheet is on top with the focus: only from here does the microphone open. */
    private fun listen() {
        refusal()?.let { return refuse(it) }
        val s = VoiceHub.settings
        val pause = asked().second
        // The words so far show on the sheet when "Show words as you speak" is on; they never go to the app.
        request = listening.open(AppListening.From.SHEET, AppListen(ListenForApps.pauseMs(pause, s.listenPause.ms), partials = s.livePartials), sink)
        if (request == null) refuse(SheetRefusal.BUSY)
    }

    /** The language and the pause the app asked for; none when its extras can't be read. */
    @Suppress("DEPRECATION")
    private fun asked(): Pair<String?, Long?> = try {
        intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE) to (intent.extras?.get(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS) as? Number)?.toLong()
    } catch (_: Exception) {
        null to null
    }

    override fun onResume() {
        super.onResume()
        act(mic.resumed())
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        act(mic.focus(hasFocus))
    }

    override fun onTopResumedActivityChanged(isTopResumedActivity: Boolean) {
        super.onTopResumedActivityChanged(isTopResumedActivity)
        act(mic.top(isTopResumedActivity))
    }

    /** Anything over it, even a translucent activity that leaves it "visible": the listening ends, and the app gets nothing. */
    override fun onPause() {
        act(mic.paused())
        super.onPause()
    }

    /** Off screen (Home, another app, the screen going off): the listening ends, and the app gets nothing. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) cancel()
    }

    /**
     * A touch Android marks as obscured: a window from another app is over the sheet. The listening ends and the sheet says
     * why; the next touch closes it, since with such a window over it its buttons don't get taps.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!SheetMic.covered(ev.flags)) return super.dispatchTouchEvent(ev)
        if (mic.obscured() == SheetMic.Step.END) {
            request?.let(listening::cancel)
            request = null
            showProblem(getString(R.string.everyday_listen_sheet_covered), RESULT_CANCELED)
        } else if (ev.actionMasked == MotionEvent.ACTION_DOWN) close(problemResult)
        return true
    }

    private fun act(step: SheetMic.Step) {
        when (step) {
            SheetMic.Step.NONE -> {}
            SheetMic.Step.LISTEN -> listen()
            SheetMic.Step.END -> if (isChangingConfigurations) {
                // Its replacement closes the sheet (onCreate): the microphone just doesn't stay open until then.
                request?.let(listening::cancel)
                request = null
            } else cancel()
        }
    }

    override fun onDestroy() {
        request?.let(listening::cancel)
        super.onDestroy()
    }

    private fun refuse(why: SheetRefusal) {
        listening.refused(AppListening.From.SHEET, ListenSheetCode.of(why))
        when (why) {
            // Nobody to answer, or the lock screen is up: nothing to show.
            SheetRefusal.NO_CALLER, SheetRefusal.LOCKED -> close(RESULT_CANCELED)
            SheetRefusal.OFF -> showProblem(getString(R.string.everyday_listen_sheet_off), RESULT_CANCELED)
            SheetRefusal.LOCK_APP -> showProblem(getString(R.string.everyday_listen_sheet_lock), RESULT_CANCELED)
            SheetRefusal.LANGUAGE -> showProblem(getString(R.string.everyday_listen_sheet_language), RecognizerIntent.RESULT_CLIENT_ERROR)
            SheetRefusal.NO_MODEL -> showProblem(getString(R.string.everyday_listen_sheet_model), RecognizerIntent.RESULT_SERVER_ERROR)
            SheetRefusal.BUSY -> showProblem(getString(R.string.everyday_listen_sheet_busy), RecognizerIntent.RESULT_AUDIO_ERROR)
        }
    }

    private val sink = object : AppListening.Sink {
        override fun level(dbfs: Float) {
            level = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)
        }

        override fun partial(text: String) {
            heard = text
        }

        override fun endOfSpeech() {
            phase = Phase.WRITING
        }

        override fun words(text: String) {
            request = null
            close(RESULT_OK, Intent().putStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS, arrayListOf(text)))
        }

        override fun ended(end: AppListening.End, message: String?) {
            request = null
            when (end) {
                // Robot's own listening took over (its panel opened): the sheet goes.
                AppListening.End.TAKEN -> close(RESULT_CANCELED)
                AppListening.End.DONE, AppListening.End.NO_SPEECH, AppListening.End.NO_MATCH ->
                    showProblem(getString(R.string.everyday_listen_sheet_nothing), RecognizerIntent.RESULT_NO_MATCH)
                AppListening.End.BUSY -> showProblem(getString(R.string.everyday_listen_sheet_busy), RecognizerIntent.RESULT_AUDIO_ERROR)
                // The sheet gives no check on its caller, so never this; if it came, the app gets nothing.
                AppListening.End.CALLER -> close(RESULT_CANCELED)
                AppListening.End.MIC -> showProblem(message ?: getString(R.string.everyday_listen_sheet_mic), RecognizerIntent.RESULT_AUDIO_ERROR)
                AppListening.End.NO_MODEL -> showProblem(message ?: getString(R.string.everyday_listen_sheet_model), RecognizerIntent.RESULT_SERVER_ERROR)
                AppListening.End.ERROR -> showProblem(message ?: getString(R.string.everyday_listen_sheet_failed), RecognizerIntent.RESULT_SERVER_ERROR)
            }
        }
    }

    private fun showProblem(text: String, result: Int) {
        mic.over()
        problem = text
        problemResult = result
        phase = Phase.PROBLEM
    }

    /** Cancel, a tap outside the sheet, leaving it: nothing goes back. */
    private fun cancel() {
        request?.let(listening::cancel)
        request = null
        close(RESULT_CANCELED)
    }

    private fun close(result: Int, data: Intent? = null) {
        mic.over()
        setResult(result, data)
        finish()
    }

    @Composable
    private fun Sheet(prompt: String?) {
        val muted = MaterialTheme.colorScheme.onSurfaceVariant
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.36f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { cancel() }, contentAlignment = Alignment.BottomCenter) {
            Surface(shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // A tap on the sheet itself isn't a tap outside it.
                modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}) {
                Column(Modifier.navigationBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (prompt != null) Text(prompt, style = MaterialTheme.typography.bodyMedium, color = muted)
                    val title = when (phase) {
                        Phase.LISTENING -> stringResource(R.string.everyday_listen_sheet_listening)
                        Phase.WRITING -> stringResource(R.string.everyday_listen_sheet_writing)
                        Phase.PROBLEM -> problem
                    }
                    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (phase != Phase.PROBLEM) LinearProgressIndicator(progress = { level }, modifier = Modifier.fillMaxWidth().clearAndSetSemantics {})
                    if (heard.isNotEmpty() && phase != Phase.PROBLEM) Text(heard, style = MaterialTheme.typography.bodyLarge)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                        when (phase) {
                            Phase.LISTENING -> {
                                TextButton(onClick = { cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.everyday_listen_sheet_cancel)) }
                                Button(onClick = { request?.let(listening::stop) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                    Text(stringResource(R.string.everyday_listen_sheet_stop))
                                }
                            }
                            Phase.WRITING -> TextButton(onClick = { cancel() }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.everyday_listen_sheet_cancel)) }
                            Phase.PROBLEM -> Button(onClick = { close(problemResult) }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.everyday_listen_sheet_close))
                            }
                        }
                    }
                }
            }
        }
    }
}
