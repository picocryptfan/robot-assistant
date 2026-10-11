package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BenchState
import app.heyrobot.core.EngineState
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.StopKey
import app.heyrobot.core.VoiceBenchLogic
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.InterruptPreference
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.Tab
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SliderPreference
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import java.util.Locale

// MAIN PROCESS. Settings › Voice (0.4.x redesign; before it, the Voice card on Settings' one long page): Robot's voice
// and its speed, when replies are spoken, live words, hearing you while Robot talks, and the troubleshooting tools.
// The wake word has its own page. Each single choice is a radio dialog instead of radio rows or segmented buttons.

/** "Speak replies" in the app's words (core's titles speak as you: "When I ask by voice"). */
internal fun speakLabel(v: SpeakReplies): String = when (v) {
    SpeakReplies.VOICE -> "Voice only"
    SpeakReplies.ALWAYS -> "Always"
    SpeakReplies.NEVER -> "Never"
}

private fun speakDetail(v: SpeakReplies): String = when (v) {
    SpeakReplies.VOICE -> "When you asked by voice or with a wake word"
    SpeakReplies.ALWAYS -> "Also when you typed"
    SpeakReplies.NEVER -> "Only the words on screen"
}

/** One line for the dialog; the whole of each is in the page's footer. */
private fun bargeDetail(v: BargeIn): String = when (v) {
    BargeIn.ECHO_CANCEL -> "The default. Call mode with echo cancellation while Robot talks"
    BargeIn.OFF -> "The microphone hears Robot over you"
    BargeIn.ALWAYS -> "Call mode the whole time the panel is open"
}

private fun stopKeyDetail(v: StopKey): String = when (v) {
    StopKey.VOLUME_DOWN -> "The default"
    StopKey.EITHER -> "Volume up or down"
    StopKey.OFF -> "The keys only change the volume"
}

@Composable
fun VoiceSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    PreferenceScreen("Voice", onBack, snackbar = snackbar) {
        if (!snap.voiceReady) {
            preferenceGroup(null, "download") {
                StatusPreference("Download voice models", "${VoiceModelLogic.size(VoiceBundle.STT.bytes + VoiceBundle.KOKORO.bytes)} · until then, typing and the phone's voice",
                    Mark.TODO, action = { FilledTonalButton(onClick = { a.openTab(Tab.MODEL) }) { Text("Open") } })
            }
        }
        preferenceGroup("Robot's voice", "voice") {
            fun installed(v: VoiceChoice) = v.bundle.let { b -> b == null || snap.voice(b)?.status == ModelStatus.VERIFIED }
            ListPreference("Voice", s.voiceChoice, VoiceChoice.entries, { it.title },
                { v -> if (installed(v)) v.detail else "${v.detail}. Not downloaded: the phone's voice speaks instead." }, RIcons.RecordVoiceOver,
                summary = s.voiceChoice.title + if (installed(s.voiceChoice)) "" else " · not downloaded, the phone's voice speaks") {
                update(s.copy(voice = it.id))
            }
            SliderPreference("Speed", s.speechRate, 0.5f..2.0f, 14, { String.format(Locale.US, "%.1f×", it) }) {
                update(s.copy(speechRate = RobotSettings.clampRate(it)))
            }
            PreferenceItem("Test the voice", "Robot says a sentence", onClick = a.speakTest)
        }
        preferenceGroup("Replies", "replies") {
            ListPreference("Speak replies", s.speakReplies, SpeakReplies.entries, ::speakLabel, ::speakDetail) { update(s.copy(speakReplies = it)) }
            SwitchPreference("Show words while you speak", "Uses more battery", s.livePartials) { update(s.copy(livePartials = it)) }
        }
        preferenceGroup("While Robot talks", "talks") {
            ListPreference("Hear you while Robot talks", s.bargeIn, BargeIn.entries, { it.title }, ::bargeDetail) { update(s.copy(bargeIn = it)) }
            InterruptPreference(s.interrupt) { if (it != s.interrupt) update(s.copy(interrupt = it)) }
            ListPreference("Voice plays as", s.playback, Playback.entries, { it.title },
                { (if (it == Playback.ASSISTANT) "The default. " else "") + it.detail }) { update(s.copy(playback = it)) }
            ListPreference("Volume key stops Robot", s.stopKey, StopKey.entries, { it.title }, ::stopKeyDetail) { update(s.copy(stopKey = it)) }
        }
        preferenceGroup("Troubleshooting", "trouble") {
            val e = snap.engines
            if (e != null) {
                for (b in listOf(VoiceBundle.STT, s.voiceChoice.bundle).filterNotNull().distinct()) {
                    val st = e.state(b)
                    StatusPreference(b.title, st.text(),
                        when (st) { EngineState.READY, EngineState.IDLE, EngineState.LOADING -> Mark.OK; EngineState.MISSING -> Mark.TODO; else -> Mark.WARN })
                }
                val stuck = listOf(VoiceBundle.STT, VoiceBundle.KOKORO, VoiceBundle.KITTEN).filter { e.state(it) == EngineState.BROKEN || e.state(it) == EngineState.FAILED }
                if (stuck.isNotEmpty()) PreferenceItem("Try the voice models again", "After a crash or a failed load", RIcons.Refresh, onClick = a.voiceReset)
            }
            val bench = snap.bench
            val running = bench?.state == BenchState.RUNNING && System.currentTimeMillis() - bench.started < 5 * 60_000L
            PreferenceItem("Test voice speed", if (running) "Testing…" else "Times the models on a built-in recording", RIcons.Timer,
                enabled = !running, onClick = a.voiceBench)
            if (bench != null) {
                PreferenceBlock {
                    if (running) {
                        Text(bench.step, style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (bench.state == BenchState.FAILED) Text(bench.step, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    for (line in VoiceBenchLogic.lines(bench)) Text(line, style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            PreferenceItem("Barge-in test", "Does Robot hear “stop” over its own voice?", RIcons.StopCircle,
                onClick = { a.openPage(Page.Settings(SettingsRoutes.BARGE_IN)) })
            PreferenceItem("Transcription check", "How well Robot writes down Québec French and English", RIcons.Search, onClick = a.openTranscriptionCheck)
            PreferenceItem("Voice log", "What the voice side did", RIcons.History, onClick = { a.openPage(Page.VoiceLog) })
        }
        preferenceFooter(VOICE_FOOTER)
    }
}

/** What the Voice page's rows mean, at length. */
internal val VOICE_FOOTER =
    "Speak replies: “Voice only” speaks when you asked by voice or with a wake word, not when you typed. Tap the conversation, " +
        "press volume down, or say “stop” (when the wake word is on), to stop Robot talking. Show words while you speak writes " +
        "down your question as you talk.\n\n" +
        "Hear you while Robot talks. Echo cancellation, the default: while Robot talks the microphone switches to the phone's call " +
        "mode with echo cancellation, so “stop” and your wake words get through, and back to the best mode for questions when Robot " +
        "is done. Off: the old way, the microphone hears Robot over you; tap the conversation or press the volume key to stop it. " +
        "Always echo cancellation: call mode with echo cancellation the whole time the panel is open, no switching, questions a " +
        "little less accurate.\n\n" +
        "Interrupt sensitivity: how readily Robot hears “stop” and your wake phrases while it talks; the wake phrases' own " +
        "sensitivity stays.\n\n" +
        "Volume key stops Robot: while Robot talks and the panel is on screen, the key stops Robot and doesn't change the volume. " +
        "With the screen off or the panel closed it works as always.\n\n" +
        "Test voice speed loads the downloaded models and times them on a built-in recording and two fixed sentences (nothing is " +
        "played or saved). The barge-in test checks what Robot hears while it talks. The Transcription check shows how well Robot " +
        "writes down Québec French and English: read sentences, or say anything and fix it; scored as conversations are decoded. " +
        "The voice log lists what the voice side did, for when Robot didn't respond."
