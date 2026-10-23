// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.ConsentRecord
import app.heyrobot.core.ConversationSettings
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.MtBundle
import app.heyrobot.core.MtModelLogic
import app.heyrobot.core.MtSpeedReport
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.VoiceModelLogic
import app.heyrobot.core.i18n.Lang
import app.heyrobot.sys.MtLink
import app.heyrobot.sys.MtModelStore
import app.heyrobot.sys.MtSpeedRun
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.FooterPreference
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Conversation (0.5.0, docs/conversation-mode.md 9.3): the switches for transcribing (each with
// the default the plan gives), speaker labels (phase 5.3: the speaker model's download, "Tell speakers apart", the most
// speakers, and your optional voiceprint), translation and summaries (phases 5.4 and 5.6), the fast translation engine
// (its download and its speed test, phase 5.0(b), which talks to the :mt process through MtLink, by name: this process
// never loads the engine), the consent reminder, and the ways to the transcripts and the Transcription check. The
// session itself runs in the voice process, which re-reads the settings when they change; the speaker model and the
// fast engine's files are downloaded here, like the voice models (the voice process never goes online). Built like
// Android's Settings (0.4.x redesign): one line under each switch, the explanations in the footer. 1.1: its words are
// strings_conversation.xml's (and core's: the engine's and the pair's names, the speed test's result), in the menus'
// language; the copied speed report stays English (a diagnostic).

private fun mark(st: ModelStatus): Mark = when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }

/** "Your language", "English", "Français": the language of Robot's summaries (null: yours). */
private val SUMMARY_LANGS = listOf<String?>(null, "en", "fr")

@Composable
fun ConversationSettingsScreen(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmModel by rememberSaveable { mutableStateOf(false) }
    var confirmVoice by rememberSaveable { mutableStateOf(false) }
    var confirmMt by rememberSaveable { mutableStateOf(false) }
    val b = VoiceBundle.SPEAKERS
    val v = snap.voice(b)
    val ready = v?.status == ModelStatus.VERIFIED
    // The fast engine's speed test: its link to the :mt process is kept while this page shows (the engine stays loaded for
    // `adb shell dumpsys meminfo ai.robot_app:mt`), and let go when the page is left.
    val mtb = MtBundle.FR_EN
    val mt = snap.mt
    var report by remember { mutableStateOf<MtSpeedReport?>(null) }
    var step by remember { mutableStateOf<String?>(null) }
    var link by remember { mutableStateOf<MtLink?>(null) }
    val total = remember { MtSpeedRun.testSet(ctx).size }
    LaunchedEffect(Unit) { report = withContext(Dispatchers.IO) { MtSpeedRun.last(ctx) } }
    DisposableEffect(Unit) { onDispose { link?.close() } }
    // Every word of the page, read here (the rows' lambdas aren't composable).
    val speakerSize = MtModelLogic.sizeText(b.bytes).str()
    val pair = s.convPairChoice.titleText.str()
    val downloadSpeakers = stringResource(R.string.conv_speaker_model_download)
    val cancelSpeakers = stringResource(R.string.conv_speaker_model_cancel)
    val deleteSpeakers = stringResource(R.string.conv_speaker_model_delete)
    val downloadMt = stringResource(R.string.conv_mt_download_desc)
    val cancelMt = stringResource(R.string.conv_mt_cancel_desc)
    val deleteMt = stringResource(R.string.conv_mt_delete_desc)
    val download = stringResource(R.string.conv_download)
    val cancel = stringResource(R.string.conv_cancel)
    val delete = stringResource(R.string.conv_delete)
    val starting = stringResource(R.string.conv_test_starting)
    val copied = stringResource(R.string.conv_test_copied)
    val reminderShows = stringResource(R.string.conv_reminder_shows)
    val theDefault = stringResource(R.string.conv_the_default)
    val summaryLangs = mapOf(null to stringResource(R.string.conv_your_language), "en" to "English", "fr" to "Français") // l10n: not text (each language's own name)
    val footer = conversationFooter(s, total, mtb)
    val groups = listOf(R.string.conv_group_transcribing, R.string.conv_group_speakers, R.string.conv_group_translation, R.string.conv_group_fast,
        R.string.conv_group_telling, R.string.conv_transcripts).map { stringResource(it) }

    PreferenceScreen(stringResource(R.string.conv_settings_title), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.conv_settings_intro)) }
        preferenceGroup(groups[0], "transcribing") {
            SwitchPreference(stringResource(R.string.conv_live_words), stringResource(R.string.conv_live_words_summary), s.convPartials) { update(s.copy(convPartials = it)) }
            SwitchPreference(stringResource(R.string.conv_wake_during),
                stringResource(if (s.convWakeDuring) R.string.conv_wake_during_on else R.string.conv_wake_during_off),
                s.convWakeDuring) { update(s.copy(convWakeDuring = it)) }
            SwitchPreference(stringResource(R.string.conv_start_locked), stringResource(R.string.conv_start_locked_summary), s.convStartLocked) {
                update(s.copy(convStartLocked = it))
            }
        }
        preferenceGroup(groups[1], "speakers") {
            SwitchPreference(stringResource(R.string.conv_tell_apart), when {
                s.convSpeakers == false -> stringResource(R.string.conv_tell_apart_off)
                ready -> stringResource(R.string.conv_tell_apart_on)
                else -> stringResource(R.string.conv_tell_apart_needs, speakerSize)
            }, s.convSpeakers != false) { update(s.copy(convSpeakers = it)) }
            if (v != null) {
                StatusPreference(stringResource(R.string.conv_speaker_model, speakerSize), VoiceModelLogic.headlineText(v.status).str(), mark(v.status), action = {
                    when (v.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> FilledTonalButton(onClick = { a.voiceDownload(b) }, enabled = s.convSpeakers != false,
                            modifier = Modifier.semantics { contentDescription = downloadSpeakers }) { Text(download) }
                        ModelStatus.DOWNLOADING -> TextButton(onClick = { a.voiceCancel(b) },
                            modifier = Modifier.semantics { contentDescription = cancelSpeakers }) { Text(cancel) }
                        ModelStatus.VERIFIED -> TextButton(onClick = { confirmModel = true },
                            modifier = Modifier.semantics { contentDescription = deleteSpeakers }) { Text(delete) }
                        else -> {}
                    }
                })
                if (v.progress != null || v.failure != null) {
                    PreferenceBlock {
                        v.progress?.let { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                            if (v.percent >= 0) LinearProgressIndicator(progress = { v.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        v.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            ListPreference(stringResource(R.string.conv_most_speakers), s.convMaxSpeakers, ConversationSettings.SPEAKER_CHOICES.toList(), { "$it" },
                { if (it == ConversationSettings.SPEAKER_CHOICES.last) theDefault else null }) { update(s.copy(convMaxSpeakers = it)) }
            if (snap.voiceprint) {
                StatusPreference(stringResource(R.string.conv_voiceprint_saved), stringResource(R.string.conv_lines_say_you), Mark.OK)
                PreferenceItem(stringResource(R.string.conv_record_voice_again),
                    stringResource(if (ready) R.string.conv_three_sentences else R.string.conv_needs_speaker_model), RIcons.Mic,
                    enabled = ready, onClick = a.openVoiceprint)
                PreferenceItem(stringResource(R.string.conv_delete_voiceprint), stringResource(R.string.conv_stops_recognising), RIcons.Delete,
                    onClick = { confirmVoice = true })
            } else {
                PreferenceItem(stringResource(R.string.conv_set_up_voice),
                    stringResource(if (ready) R.string.conv_set_up_voice_summary else R.string.conv_needs_speaker_model), RIcons.Mic,
                    enabled = ready, onClick = a.openVoiceprint)
            }
        }
        // 0.5.0, phases 5.4 and 5.6.
        preferenceGroup(groups[2], "translation") {
            SwitchPreference(stringResource(R.string.conv_translate_transcripts), stringResource(R.string.conv_translate_transcripts_summary, pair), s.convTranslate) {
                update(s.copy(convTranslate = it))
            }
            ListPreference(stringResource(R.string.conv_summaries_in), s.convSummaryLang, SUMMARY_LANGS, { summaryLangs.getValue(it) }) {
                update(s.copy(convSummaryLang = it))
            }
            SwitchPreference(stringResource(R.string.conv_offer_stronger), stringResource(R.string.conv_offer_stronger_summary), s.convAutoOfferHelper) {
                update(s.copy(convAutoOfferHelper = it))
            }
        }
        preferenceGroup(groups[3], "fast") {
            if (mt != null) {
                StatusPreference(MtModelLogic.labelText(mtb).str(), VoiceModelLogic.headlineText(mt.status).str(), mark(mt.status), action = {
                    when (mt.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> FilledTonalButton(onClick = a.mtDownload,
                            modifier = Modifier.semantics { contentDescription = downloadMt }) { Text(download) }
                        ModelStatus.DOWNLOADING -> TextButton(onClick = a.mtCancel,
                            modifier = Modifier.semantics { contentDescription = cancelMt }) { Text(cancel) }
                        ModelStatus.VERIFIED -> TextButton(onClick = { confirmMt = true }, enabled = step == null,
                            modifier = Modifier.semantics { contentDescription = deleteMt }) { Text(delete) }
                        else -> {}
                    }
                })
                if (!mt.engine) {
                    StatusPreference(stringResource(R.string.conv_mt_not_in_build), stringResource(R.string.conv_mt_not_in_build_text, MtModelStore.LIBRARY), Mark.WARN)
                }
                if (mt.progress != null || mt.failure != null) {
                    PreferenceBlock {
                        mt.progress?.let { line ->
                            Text(line, style = MaterialTheme.typography.bodyMedium)
                            if (mt.percent >= 0) LinearProgressIndicator(progress = { mt.percent / 100f }, modifier = Modifier.fillMaxWidth())
                            else LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        mt.failure?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
                val canTest = mt.status == ModelStatus.VERIFIED && mt.engine
                PreferenceItem(stringResource(R.string.conv_test_speed), step ?: stringResource(R.string.conv_test_speed_summary, total), RIcons.Timer,
                    enabled = canTest && step == null, onClick = {
                        val l = link ?: MtLink(ctx).also { link = it }
                        step = starting
                        scope.launch {
                            try {
                                report = MtSpeedRun.run(ctx, l) { step = it }
                            } finally {
                                step = null
                            }
                        }
                    })
                if (step != null || report != null) {
                    PreferenceBlock {
                        if (step != null) LinearProgressIndicator(Modifier.fillMaxWidth())
                        report?.let { r ->
                            val ago = MtSpeedReport.agoText(System.currentTimeMillis() - r.at).str()
                            Text(stringResource(if (r.passed) R.string.conv_last_test_passed else R.string.conv_last_test, ago), style = MaterialTheme.typography.bodyMedium)
                            for (line in r.summaryLines(total)) {
                                Text(line.text.str(), style = MaterialTheme.typography.bodyMedium,
                                    color = if (line.missed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                            if (r.error == null) {
                                TextButton(onClick = {
                                    val text = r.plain(MtSpeedRun.testSet(ctx), "Robot ${BuildConfig.VERSION_NAME} translation speed test, ${VoiceLogText.clock(r.at)}") // l10n: not text (the copied report, a diagnostic: English)
                                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Robot translation speed test", text)) // l10n: not text (the copied report's label, a diagnostic)
                                    a.say(copied)
                                }) { Text(stringResource(R.string.conv_test_copy)) }
                            }
                        }
                    }
                }
            }
        }
        preferenceGroup(groups[4], "consent") {
            PreferenceItem(stringResource(R.string.conv_reminder_again), stringResource(R.string.conv_reminder_again_summary), RIcons.Refresh, onClick = {
                update(s.copy(convReminderAt = System.currentTimeMillis()))
                a.say(reminderShows)
            })
        }
        item(key = "consent-text") {
            FooterPreference(listOf(ConsentRecord.text.str(), stringResource(R.string.conv_consent_footer, ConsentRecord.line.str())).joinToString("\n\n"))
        }
        preferenceGroup(groups[5], "transcripts") {
            PreferenceItem(stringResource(R.string.conv_transcripts), stringResource(R.string.conv_transcripts_summary), RIcons.Message,
                onClick = { a.openPage(Page.Transcripts) })
            PreferenceItem(stringResource(R.string.conv_check_title), stringResource(R.string.conv_check_summary), RIcons.Search, onClick = a.openTranscriptionCheck)
        }
        preferenceFooter(footer)
    }
    if (confirmModel) {
        ConfirmDialog(stringResource(R.string.conv_delete_model_title), stringResource(R.string.conv_delete_model_text, speakerSize),
            delete, onDismiss = { confirmModel = false }) { confirmModel = false; a.voiceDelete(b) }
    }
    if (confirmVoice) {
        ConfirmDialog(stringResource(R.string.conv_vp_delete_title), stringResource(R.string.conv_delete_voiceprint_text), delete,
            onDismiss = { confirmVoice = false }) { confirmVoice = false; a.deleteVoiceprint() }
    }
    if (confirmMt) {
        ConfirmDialog(stringResource(R.string.conv_delete_mt_title),
            stringResource(R.string.conv_delete_mt_text, MtModelLogic.sizeText(mtb.downloadBytes).str()), delete,
            onDismiss = { confirmMt = false }) {
            confirmMt = false
            link?.close()
            link = null
            a.mtDelete()
        }
    }
}

/** What the Conversation page's rows mean, at length (before 0.4.x's redesign, each under its row). */
@Composable
private fun conversationFooter(s: RobotSettings, total: Int, mtb: MtBundle): String = listOf(
    stringResource(R.string.conv_footer_start),
    stringResource(R.string.conv_footer_live),
    stringResource(R.string.conv_footer_speakers, VoiceBundle.SPEAKERS.aboutText.str()),
    stringResource(R.string.conv_footer_translate, s.convPairChoice.titleText.str()),
    stringResource(R.string.conv_footer_fast, MtModelLogic.sizeText(mtb.downloadBytes).str(), MtModelLogic.sizeText(mtb.bytes).str()),
    stringResource(R.string.conv_footer_test, total, MtSpeedReport.MEDIAN_GATE_MS.toInt(), MtSpeedReport.RSS_GATE_MB.toInt(), BuildConfig.APPLICATION_ID),
).joinToString("\n\n")
