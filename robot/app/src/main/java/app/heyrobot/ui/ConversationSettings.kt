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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.heyrobot.BuildConfig
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
// Android's Settings (0.4.x redesign): one line under each switch, the explanations in the footer.

private fun mark(st: ModelStatus): Mark = when (st) { ModelStatus.VERIFIED -> Mark.OK; ModelStatus.BAD -> Mark.WARN; else -> Mark.TODO }

/** "Your language", "English", "Français": the language of Robot's summaries (null: yours). */
private val SUMMARY_LANGS = listOf<String?>(null, "en", "fr")

private fun summaryLang(l: String?): String = when (l) { "en" -> "English"; "fr" -> "Français"; else -> "Your language" }

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

    PreferenceScreen("Conversation", onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference("Robot writes down a conversation in French and English, on this phone, and saves each line as it goes.") }
        preferenceGroup("Transcribing", "transcribing") {
            SwitchPreference("Live words", "The line being spoken, in grey · uses more battery", s.convPartials) { update(s.copy(convPartials = it)) }
            SwitchPreference("“Hey robot” works during a conversation",
                if (s.convWakeDuring) "Pauses the transcript and opens Robot" else "The wake word sleeps while Robot transcribes",
                s.convWakeDuring) { update(s.copy(convWakeDuring = it)) }
            SwitchPreference("Start from the lock screen", "The tile and “hey robot” can start one while locked", s.convStartLocked) {
                update(s.copy(convStartLocked = it))
            }
        }
        preferenceGroup("Speakers", "speakers") {
            SwitchPreference("Tell speakers apart", when {
                s.convSpeakers == false -> "Off: every line is “Speaker”"
                ready -> "On: each line says who spoke"
                else -> "Needs the speaker model (${VoiceModelLogic.size(b.bytes)})"
            }, s.convSpeakers != false) { update(s.copy(convSpeakers = it)) }
            if (v != null) {
                StatusPreference("Speaker model · ${VoiceModelLogic.size(b.bytes)}", VoiceModelLogic.headline(v.status), mark(v.status), action = {
                    when (v.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> FilledTonalButton(onClick = { a.voiceDownload(b) }, enabled = s.convSpeakers != false,
                            modifier = Modifier.semantics { contentDescription = "Download the speaker model" }) { Text("Download") }
                        ModelStatus.DOWNLOADING -> TextButton(onClick = { a.voiceCancel(b) },
                            modifier = Modifier.semantics { contentDescription = "Cancel the speaker model's download" }) { Text("Cancel") }
                        ModelStatus.VERIFIED -> TextButton(onClick = { confirmModel = true },
                            modifier = Modifier.semantics { contentDescription = "Delete the speaker model" }) { Text("Delete") }
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
            ListPreference("Most speakers in a transcript", s.convMaxSpeakers, ConversationSettings.SPEAKER_CHOICES.toList(), { "$it" },
                { if (it == ConversationSettings.SPEAKER_CHOICES.last) "The default" else null }) { update(s.copy(convMaxSpeakers = it)) }
            if (snap.voiceprint) {
                StatusPreference("Your voiceprint is saved", "Your lines say “You”", Mark.OK)
                PreferenceItem("Record your voice again", if (ready) "Three short sentences" else "Needs the speaker model first", RIcons.Mic,
                    enabled = ready, onClick = a.openVoiceprint)
                PreferenceItem("Delete your voiceprint", "Robot stops recognising your voice", RIcons.Delete, onClick = { confirmVoice = true })
            } else {
                PreferenceItem("Set up your voice", if (ready) "Optional: your lines say “You”" else "Needs the speaker model first", RIcons.Mic,
                    enabled = ready, onClick = a.openVoiceprint)
            }
        }
        // 0.5.0, phases 5.4 and 5.6.
        preferenceGroup("Translation and summaries", "translation") {
            SwitchPreference("Translate transcripts", "Each line in the other language (${s.convPairChoice.title})", s.convTranslate) {
                update(s.copy(convTranslate = it))
            }
            ListPreference("Summaries in", s.convSummaryLang, SUMMARY_LANGS, ::summaryLang) { update(s.copy(convSummaryLang = it)) }
            SwitchPreference("Offer a stronger AI first", "For a long or important conversation", s.convAutoOfferHelper) {
                update(s.copy(convAutoOfferHelper = it))
            }
        }
        preferenceGroup("Fast translation", "fast") {
            if (mt != null) {
                StatusPreference(MtModelLogic.label(mtb), VoiceModelLogic.headline(mt.status), mark(mt.status), action = {
                    when (mt.status) {
                        ModelStatus.MISSING, ModelStatus.BAD -> FilledTonalButton(onClick = a.mtDownload,
                            modifier = Modifier.semantics { contentDescription = "Download the fast translation engine" }) { Text("Download") }
                        ModelStatus.DOWNLOADING -> TextButton(onClick = a.mtCancel,
                            modifier = Modifier.semantics { contentDescription = "Cancel the fast translation engine's download" }) { Text("Cancel") }
                        ModelStatus.VERIFIED -> TextButton(onClick = { confirmMt = true }, enabled = step == null,
                            modifier = Modifier.semantics { contentDescription = "Delete the fast translation engine's files" }) { Text("Delete") }
                        else -> {}
                    }
                })
                if (!mt.engine) {
                    StatusPreference("Not in this build", "This build of Robot doesn't include the fast engine (${MtModelStore.LIBRARY}), so it can't " +
                        "translate with it even once the files are downloaded.", Mark.WARN)
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
                PreferenceItem("Test translation speed", step ?: "Robot's $total test lines, Québec French and English", RIcons.Timer,
                    enabled = canTest && step == null, onClick = {
                        val l = link ?: MtLink(ctx).also { link = it }
                        step = "Starting…"
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
                            Text("Last test: ${VoiceLogText.ago(System.currentTimeMillis() - r.at)}${if (r.passed) ", passed" else ""}.",
                                style = MaterialTheme.typography.bodyMedium)
                            for (line in r.summary(total)) {
                                Text(line, style = MaterialTheme.typography.bodyMedium,
                                    color = if (line.endsWith("misses.") || r.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                            if (r.error == null) {
                                TextButton(onClick = {
                                    val text = r.plain(MtSpeedRun.testSet(ctx), "Robot ${BuildConfig.VERSION_NAME} translation speed test, ${VoiceLogText.clock(r.at)}")
                                    ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Robot translation speed test", text))
                                    a.say("Copied: the result and every line that wasn't the same.")
                                }) { Text("Copy the result") }
                            }
                        }
                    }
                }
            }
        }
        preferenceGroup("Telling people", "consent") {
            PreferenceItem("Show the reminder again", "Before the next transcript", RIcons.Refresh, onClick = {
                update(s.copy(convReminderAt = System.currentTimeMillis()))
                a.say("The reminder shows before the next transcript.")
            })
        }
        item(key = "consent-text") {
            FooterPreference(ConsentRecord.TEXT + "\n\nRobot shows this before your first transcript, and the notification says “${ConsentRecord.LINE}” the whole time.")
        }
        preferenceGroup("Transcripts", "transcripts") {
            PreferenceItem("Transcripts", "Read, search, rename, export, share and delete", RIcons.Message, onClick = { a.openPage(Page.Transcripts) })
            PreferenceItem("Transcription check", "How well Robot writes down Québec French and English", RIcons.Search, onClick = a.openTranscriptionCheck)
        }
        preferenceFooter(conversationFooter(s, total, mtb))
    }
    if (confirmModel) {
        ConfirmDialog("Delete the speaker model?", "Transcripts stop telling speakers apart (every line is “Speaker”) until you download it again " +
            "(${VoiceModelLogic.size(b.bytes)}). Names you gave in transcripts stay. Your voiceprint, if you made one, stays until you delete it below.",
            "Delete", onDismiss = { confirmModel = false }) { confirmModel = false; a.voiceDelete(b) }
    }
    if (confirmVoice) {
        ConfirmDialog("Delete your voiceprint?", "Robot stops recognising your voice at once, also in a transcript being recorded; lines already " +
            "labelled “You” stay as they are.", "Delete", onDismiss = { confirmVoice = false }) { confirmVoice = false; a.deleteVoiceprint() }
    }
    if (confirmMt) {
        ConfirmDialog("Delete the fast translation engine's files?",
            "Robot can't translate with the fast engine until you download them again (${VoiceModelLogic.size(mtb.downloadBytes)}).", "Delete",
            onDismiss = { confirmMt = false }) {
            confirmMt = false
            link?.close()
            link = null
            a.mtDelete()
        }
    }
}

/** What the Conversation page's rows mean, at length (before 0.4.x's redesign, each under its row). */
private fun conversationFooter(s: RobotSettings, total: Int, mtb: MtBundle): String =
    "Start a transcript from Home, the Quick Settings tile “Transcribe”, a long press on Robot's icon, or say “hey robot, transcribe " +
        "this conversation”. Transcripts are kept in Robot's private storage on this phone and never backed up. The audio is never kept.\n\n" +
        "Live words: the words of the line being spoken, in grey, while the transcript is on screen (uses more battery; off when the " +
        "phone is warm). “Hey robot” during a conversation: on, it pauses the transcript and opens Robot, and what you say to Robot " +
        "isn't transcribed; off, the wake word sleeps while Robot transcribes, so nobody's “hey robot” opens it, “stop” still works " +
        "while Robot talks, and the wake word is back when you stop. Start from the lock screen: the tile and “hey robot” can start a " +
        "transcript while the phone is locked; that screen shows only what it hears from then on, and reading, exporting and deleting " +
        "transcripts need the phone unlocked.\n\n" +
        "Speakers: Robot can label who says what in a transcript, by their voices: S1, S2… or the names you give (tap a line's " +
        "speaker). It all happens on this phone; the voices are forgotten when the transcript stops. The speaker model is a pinned " +
        "file, checked (SHA-256) before Robot uses it. ${VoiceBundle.SPEAKERS.about} Past the most speakers, a new voice goes to the " +
        "nearest speaker; the interpreter always tells two people apart. Your voice, optional: read three short sentences so your " +
        "lines say “You” instead of S1 or S2. Robot keeps a voiceprint (numbers made from your voice, not the recording), encrypted on " +
        "this phone, used only for this and never sent anywhere. A voiceprint is biometric data; delete it here any time.\n\n" +
        "Translate transcripts: each line in the other language (${s.convPairChoice.title}), under it, on this phone: at once by the " +
        "fast engine when it is downloaded, then better by Robot's model. The transcript screen's Translate turns it on or off for " +
        "one transcript. The interpreter always translates. Offer a stronger AI first: for a long conversation (about half an hour or " +
        "more), one marked important, or a summary whose check found something missing, Robot offers a stronger AI first. Nothing is sent " +
        "before you tap Send on its card.\n\n" +
        "Fast translation: Robot's fast translation engine translates French ↔ English on this phone in a fraction of a second, in a " +
        "process of its own: Mozilla's Bergamot models, run by slimt. Once it is downloaded, each transcript line gets its translation " +
        "at once (marked “fast”) and Robot's model then replaces it with a better one; in the interpreter it stands in when Robot's " +
        "model is slow, and leads when the model runs on the CPU. Without it, Robot's model translates alone, more slowly. From " +
        "Mozilla's servers, the files each direction needs: ${VoiceModelLogic.size(mtb.downloadBytes)} to download, " +
        "${VoiceModelLogic.size(mtb.bytes)} in Robot's private storage. Each is checked (SHA-256) before Robot uses it. Wi-Fi recommended.\n\n" +
        "Test translation speed translates Robot's $total test lines (Québec French and English) and compares each with what the same " +
        "engine gave on the build machine. The mark: a median of ${MtSpeedReport.MEDIAN_GATE_MS} ms a line or less, " +
        "${MtSpeedReport.RSS_GATE_MB} MB or less with both directions loaded, and every line the same. Only the test's own sentences " +
        "are translated. While this page shows, the engine stays loaded: `adb shell dumpsys meminfo ${BuildConfig.APPLICATION_ID}:mt` shows its memory."
