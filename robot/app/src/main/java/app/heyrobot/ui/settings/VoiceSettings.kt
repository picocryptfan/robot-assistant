// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BenchState
import app.heyrobot.core.EngineState
import app.heyrobot.core.ListenPause
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.SpeakReplies
import app.heyrobot.core.StopKey
import app.heyrobot.core.VoiceBenchLogic
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.i18n.Lang
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.InterruptPreference
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.Snapshot
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SliderPreference
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import java.util.Locale

// MAIN PROCESS. Settings › Voice (0.4.x redesign; before it, the Voice card on Settings' one long page): Robot's voice
// and its speed, when replies are spoken, live words, hearing you while Robot talks, and the troubleshooting tools.
// The wake word has its own page. Each single choice is a radio dialog instead of radio rows or segmented buttons. 1.1: the
// page's words are in strings_voice.xml with their French; the values other areas name (a voice, how it plays) are theirs.

/** "Speak replies" in the app's words (core's titles speak as you: "When I ask by voice"). */
internal fun speakLabel(v: SpeakReplies): Int = when (v) {
    SpeakReplies.VOICE -> R.string.voice_speak_voice
    SpeakReplies.ALWAYS -> R.string.voice_speak_always
    SpeakReplies.NEVER -> R.string.voice_speak_never
}

private fun speakDetail(v: SpeakReplies): Int = when (v) {
    SpeakReplies.VOICE -> R.string.voice_speak_voice_detail
    SpeakReplies.ALWAYS -> R.string.voice_speak_always_detail
    SpeakReplies.NEVER -> R.string.voice_speak_never_detail
}

/** One line for the dialog; the whole of each is in the page's footer. */
private fun bargeDetail(v: BargeIn): Int = when (v) {
    BargeIn.ECHO_CANCEL -> R.string.voice_barge_echo_detail
    BargeIn.OFF -> R.string.voice_barge_off_detail
    BargeIn.ALWAYS -> R.string.voice_barge_always_detail
}

private fun waitLabel(v: ListenPause): Int = when (v) {
    ListenPause.SHORT -> R.string.voice_wait_short
    ListenPause.NORMAL -> R.string.voice_wait_normal
    ListenPause.LONG -> R.string.voice_wait_long
    ListenPause.VERY_LONG -> R.string.voice_wait_very_long
}

private fun waitDetail(v: ListenPause): Int = when (v) {
    ListenPause.SHORT -> R.string.voice_wait_short_detail
    ListenPause.NORMAL -> R.string.voice_wait_normal_detail
    ListenPause.LONG -> R.string.voice_wait_long_detail
    ListenPause.VERY_LONG -> R.string.voice_wait_very_long_detail
}

private fun stopKeyDetail(v: StopKey): Int = when (v) {
    StopKey.VOLUME_DOWN -> R.string.voice_stop_down_detail
    StopKey.EITHER -> R.string.voice_stop_either_detail
    StopKey.OFF -> R.string.voice_stop_off_detail
}

@Composable
fun VoiceSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val res = LocalContext.current.resources
    val langs = LocalLangs.current
    val groupVoice = stringResource(R.string.voice_group_voice)
    val groupReplies = stringResource(R.string.voice_group_replies)
    val groupTalks = stringResource(R.string.voice_group_talks)
    val groupTrouble = stringResource(R.string.voice_group_trouble)
    val footer = stringResource(R.string.voice_footer)
    val languagesNote = stringResource(R.string.voice_languages_note)
    PreferenceScreen(UiStrings.voice().str(), onBack, snackbar = snackbar) {
        item(key = "languages") { IntroPreference(languagesNote) }
        if (!snap.voiceReady) {
            preferenceGroup(null, "download") {
                StatusPreference(stringResource(R.string.voice_download_models),
                    stringResource(R.string.voice_download_models_summary, VoiceModelLogic.sizeText(VoiceBundle.STT.bytes + VoiceBundle.KOKORO.bytes).str()),
                    Mark.TODO, action = { FilledTonalButton(onClick = { a.openPage(Page.Settings(app.heyrobot.ui.settings.SettingsRoutes.MODEL)) }) { Text(stringResource(R.string.voice_open)) } })
            }
        }
        preferenceGroup(groupVoice, "voice") {
            fun installed(v: VoiceChoice) = v.bundle.let { b -> b == null || snap.voice(b)?.status == ModelStatus.VERIFIED }
            ListPreference(stringResource(R.string.voice_voice), s.voiceChoice, VoiceChoice.entries, { it.titleText.render(langs) },
                { v -> if (installed(v)) v.detailText(snap.systemVoice).render(langs) else res.getString(R.string.voice_not_downloaded_detail, v.detailText.render(langs)) }, RIcons.RecordVoiceOver,
                summary = if (installed(s.voiceChoice)) s.voiceChoice.titleText.render(langs) else stringResource(R.string.voice_not_downloaded_summary, s.voiceChoice.titleText.render(langs))) {
                update(s.copy(voice = it.id))
            }
            val decimals = if (langs.ui == Lang.FR) Locale.CANADA_FRENCH else Locale.US
            SliderPreference(stringResource(R.string.voice_speed), s.speechRate, 0.5f..2.0f, 14, { String.format(decimals, "%.1f×", it) }) {
                update(s.copy(speechRate = RobotSettings.clampRate(it)))
            }
            PreferenceItem(UiStrings.testTheVoice().str(), stringResource(R.string.voice_test_summary), onClick = a.speakTest)
        }
        preferenceGroup(groupReplies, "replies") {
            ListPreference(stringResource(R.string.voice_speak_replies), s.speakReplies, SpeakReplies.entries, { res.getString(speakLabel(it)) },
                { res.getString(speakDetail(it)) }) { update(s.copy(speakReplies = it)) }
            SwitchPreference(stringResource(R.string.voice_show_words), stringResource(R.string.voice_more_battery), s.livePartials) { update(s.copy(livePartials = it)) }
            ListPreference(stringResource(R.string.voice_wait), s.listenPause, ListenPause.entries, { res.getString(waitLabel(it)) }, { res.getString(waitDetail(it)) },
                summary = stringResource(R.string.voice_wait_summary, res.getString(waitLabel(s.listenPause)))) { update(s.copy(listenPause = it)) }
        }
        preferenceGroup(groupTalks, "talks") {
            ListPreference(stringResource(R.string.voice_hear_you), s.bargeIn, BargeIn.entries, { it.titleText.render(langs) }, { res.getString(bargeDetail(it)) }) {
                update(s.copy(bargeIn = it))
            }
            InterruptPreference(s.interrupt) { if (it != s.interrupt) update(s.copy(interrupt = it)) }
            ListPreference(stringResource(R.string.voice_plays_as), s.playback, Playback.entries, { it.titleText.render(langs) },
                { if (it == Playback.ASSISTANT) res.getString(R.string.voice_default_detail, it.detailText.render(langs)) else it.detailText.render(langs) }) { update(s.copy(playback = it)) }
            ListPreference(stringResource(R.string.voice_stop_key), s.stopKey, StopKey.entries, { it.titleText.render(langs) }, { res.getString(stopKeyDetail(it)) }) {
                update(s.copy(stopKey = it))
            }
        }
        preferenceGroup(groupTrouble, "trouble") {
            val e = snap.engines
            if (e != null) {
                for (b in listOf(VoiceBundle.STT, s.voiceChoice.bundle).filterNotNull().distinct()) {
                    val st = e.state(b)
                    StatusPreference(b.titleText.str(), st.textPhrase().str(),
                        when (st) { EngineState.READY, EngineState.IDLE, EngineState.LOADING -> Mark.OK; EngineState.MISSING -> Mark.TODO; else -> Mark.WARN })
                }
                val stuck = listOf(VoiceBundle.STT, VoiceBundle.KOKORO, VoiceBundle.KITTEN).filter { e.state(it) == EngineState.BROKEN || e.state(it) == EngineState.FAILED }
                if (stuck.isNotEmpty()) PreferenceItem(stringResource(R.string.voice_try_again), stringResource(R.string.voice_try_again_summary), RIcons.Refresh,
                    onClick = a.voiceReset)
            }
            val bench = snap.bench
            val running = bench?.state == BenchState.RUNNING && System.currentTimeMillis() - bench.started < 5 * 60_000L
            PreferenceItem(stringResource(R.string.voice_test_speed), stringResource(if (running) R.string.voice_testing else R.string.voice_test_speed_summary),
                RIcons.Timer, enabled = !running, onClick = a.voiceBench)
            if (bench != null) {
                PreferenceBlock {
                    if (running) {
                        Text(bench.stepText.str(), style = MaterialTheme.typography.bodyMedium)
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (bench.state == BenchState.FAILED) Text(bench.stepText.str(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    for (line in VoiceBenchLogic.linesText(bench)) Text(line.str(), style = MaterialTheme.typography.bodyMedium, color = muted)
                }
            }
            PreferenceItem(stringResource(R.string.voice_barge_test), stringResource(R.string.voice_barge_test_summary), RIcons.StopCircle,
                onClick = { a.openPage(Page.Settings(SettingsRoutes.BARGE_IN)) })
            PreferenceItem(UiStrings.transcriptionCheck().str(), stringResource(R.string.voice_transcription_check_summary), RIcons.Search,
                onClick = a.openTranscriptionCheck)
            PreferenceItem(UiStrings.voiceLog().str(), stringResource(R.string.voice_log_summary), RIcons.History, onClick = { a.openPage(Page.VoiceLog) })
        }
        preferenceFooter(footer)
    }
}

