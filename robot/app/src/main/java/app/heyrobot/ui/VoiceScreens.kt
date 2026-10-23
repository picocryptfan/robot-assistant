// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import androidx.compose.ui.semantics.contentDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import app.heyrobot.core.InterruptSensitivity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material3.Slider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.R
import androidx.compose.ui.res.stringResource
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.str
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.RoutineStrings
import app.heyrobot.core.BargeIn
import app.heyrobot.core.BargeRun
import app.heyrobot.core.BargeTestLogic
import app.heyrobot.core.BargeTestResult
import app.heyrobot.core.BenchState
import app.heyrobot.core.KwsSpeller
import app.heyrobot.core.KwsThresholds
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.Playback
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StopKey
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceChoice
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.VoiceLogCodec
import app.heyrobot.core.VoiceLogEntry
import app.heyrobot.core.VoiceLogText
import app.heyrobot.core.WakeAction
import app.heyrobot.core.WakePhrase
import app.heyrobot.core.WakePhrases
import app.heyrobot.core.WakeSensitivity
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.KwsAssets
import app.heyrobot.sys.Paths
import app.heyrobot.ui.common.RIcons
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import app.heyrobot.ui.pref.EditDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TwoTargetSwitchPreference
import app.heyrobot.ui.pref.prefTitleStyle
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.RobotTokens
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Wake word and the voice's tools (0.2.3; 0.4.x redesign): the wake phrases, the barge-in test,
// "Interrupt sensitivity", "Test wake words" and the voice log. Wake phrases are spelled here with core's pure Kotlin
// (KwsAssets): this process never loads sherpa-onnx. The tests run in the voice process (Cmd broadcasts) and report
// through files, as the voice speed test does. The rest of Settings › Voice is ui/settings/VoiceSettings.kt.

/**
 * Settings › Voice › "Interrupt sensitivity": three steps, Low, Medium (as before 0.4.0) and High. Only while Robot talks;
 * the wake phrases' own sensitivity stays. [onChange] gets the level where the slider was let go.
 *
 * The level saved is read from the slider's position when the gesture ends ([InterruptSensitivity.at]), never from what
 * the screen last drew: a tap on the track (and TalkBack's adjust) moves the slider and ends the gesture in the same
 * moment, before the screen is drawn again, so until 0.4.6 a tap on Low or High showed it and saved the old level.
 */
@Composable
internal fun InterruptSlider(value: InterruptSensitivity, enabled: Boolean = true, onChange: (InterruptSensitivity) -> Unit) {
    var level by remember(value) { mutableFloatStateOf(value.ordinal.toFloat()) }
    val shown = InterruptSensitivity.at(level)
    val title = stringResource(R.string.voice_interrupt)
    val shownTitle = shown.titleText.str()
    Column {
        Slider(value = level, onValueChange = { level = it }, enabled = enabled, valueRange = 0f..(InterruptSensitivity.entries.size - 1).toFloat(),
            steps = InterruptSensitivity.entries.size - 2,
            // Read now: level was just set by onValueChange, whether or not the screen has been drawn since.
            onValueChangeFinished = { onChange(InterruptSensitivity.at(level)) },
            modifier = Modifier.semantics { contentDescription = title; stateDescription = shownTitle })
        Row(Modifier.fillMaxWidth()) {
            for (v in InterruptSensitivity.entries) Text(v.titleText.str(), style = MaterialTheme.typography.labelMedium,
                color = if (v == shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = when (v.ordinal) { 0 -> TextAlign.Start; InterruptSensitivity.entries.size - 1 -> TextAlign.End; else -> TextAlign.Center })
        }
        Text(stringResource(if (shown == InterruptSensitivity.MEDIUM) R.string.voice_interrupt_text_default else R.string.voice_interrupt_text, shown.detailText.str()),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * Settings › Wake word (0.4.x redesign, before it Settings › Voice › Wake words): the main switch, "Only while charging",
 * each phrase as a two-target row (the text opens its sensitivity and Remove, the switch turns it on and off), "Add a
 * wake phrase" in an edit dialog checked as you type, and "Test wake words". Turning the main switch off disables the rest.
 */
@Composable
fun WakePhrasesScreen(s: RobotSettings, update: (RobotSettings) -> Unit, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var speller by remember { mutableStateOf<KwsSpeller?>(null) }
    var spellerMissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        speller = withContext(Dispatchers.IO) { KwsAssets.speller(ctx) }
        spellerMissing = speller == null
    }
    var adding by rememberSaveable { mutableStateOf(false) }
    var opened by rememberSaveable { mutableStateOf<Int?>(null) }
    val kwsMissing = stringResource(R.string.voice_kws_missing)
    val langs = LocalLangs.current
    fun problem(text: String): String? = WakePhrases.problemText(text, s.wakePhrases)?.render(langs)
        ?: speller?.spell(text)?.let { (it as? KwsSpeller.Result.Refused)?.text?.render(langs) }
        ?: if (spellerMissing) kwsMissing else null
    val on = s.wakeWord
    // "robot" inside "hey robot": with "hey robot" off, saying it still fires "robot". A warning on that phrase only; a
    // plain note on the shorter one's.
    val inside = WakePhrases.inside(s.wakePhrases)
    val useWakeWords = stringResource(R.string.voice_use_wake_words)
    val groupPhrases = stringResource(R.string.voice_wake_phrases)
    val footer = wakeFooter()
    PreferenceScreen(UiStrings.wakeWord().str(), onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar(useWakeWords, on) { update(s.copy(wakeWord = it)) } }
        preferenceGroup(null, "when") {
            SwitchPreference(stringResource(R.string.voice_only_charging), stringResource(R.string.voice_saves_battery), s.wakeOnlyWhileCharging, enabled = on) {
                update(s.copy(wakeOnlyWhileCharging = it))
            }
        }
        preferenceGroup(groupPhrases, "phrases") {
            if (s.wakePhrases.none { it.enabled }) {
                StatusPreference(stringResource(R.string.voice_no_phrase_on), stringResource(R.string.voice_no_phrase_on_detail), Mark.WARN)
            }
            for (p in s.wakePhrases) {
                val refused = (speller?.spell(p.text) as? KwsSpeller.Result.Refused) != null
                val note = WakePhrases.insideWarning(p, inside) != null || WakePhrases.insideNote(p, inside) != null || p.words == 1
                TwoTargetSwitchPreference(
                    "“${p.text}”",
                    when {
                        refused -> stringResource(R.string.voice_phrase_refused)
                        note -> stringResource(R.string.voice_phrase_note, p.sensitivity.titleText.str())
                        else -> stringResource(R.string.voice_phrase_sensitivity, p.sensitivity.titleText.str())
                    },
                    p.enabled,
                    onClick = { opened = p.id },
                    onCheckedChange = { e -> update(s.copy(wakePhrases = s.wakePhrases.map { if (it.id == p.id) it.copy(enabled = e) else it })) },
                    switchLabel = stringResource(R.string.voice_listen_for, p.text),
                    enabled = on,
                )
            }
            PreferenceItem(stringResource(R.string.voice_add_phrase), stringResource(R.string.voice_add_phrase_summary), RIcons.Add, enabled = on,
                onClick = { adding = true })
        }
        preferenceGroup(null, "test") {
            PreferenceItem(stringResource(R.string.voice_test_wake), stringResource(R.string.voice_test_wake_summary), RIcons.Search,
                onClick = { a.openPage(Page.WakeTest) })
        }
        preferenceFooter(footer)
    }
    s.wakePhrases.firstOrNull { it.id == opened }?.let { p ->
        PhraseDialog(p, speller, WakePhrases.insideWarningText(p, inside), WakePhrases.insideNoteText(p, inside), onDismiss = { opened = null },
            onPick = { v -> opened = null; update(s.copy(wakePhrases = s.wakePhrases.map { if (it.id == p.id) it.copy(sensitivity = v) else it })) },
            onRemove = { opened = null; update(s.copy(wakePhrases = s.wakePhrases.filter { it.id != p.id })) })
    }
    if (adding) {
        val startsLow = stringResource(R.string.voice_starts_low, RoutineStrings.wakeShort().str())
        EditDialog(
            stringResource(R.string.voice_add_phrase), "", stringResource(R.string.voice_phrase), stringResource(R.string.voice_phrase_rules),
            validate = { if (it.isBlank()) null else problem(it) },
            confirm = stringResource(R.string.voice_add),
            hint = { if (it.isNotBlank() && WakePhrases.singleWord(it)) startsLow else null },
            canSave = { it.isNotBlank() && speller != null },
            maxLength = 40,
            onDismiss = { adding = false },
        ) { typed ->
            adding = false
            val t = WakePhrases.clean(typed)
            if (problem(t) == null && speller != null) {
                update(s.copy(wakePhrases = s.wakePhrases + WakePhrase(WakePhrases.nextId(s.wakePhrases), t, WakePhrases.defaultSensitivity(t))))
            }
        }
    }
}

/** The Wake word page's footer: what wake words do, and what each sensitivity means. */
@Composable
private fun wakeFooter(): String {
    val res = LocalContext.current.resources
    val langs = LocalLangs.current
    val levels = WakeSensitivity.entries.joinToString("\n") { v ->
        val p = KwsThresholds.wake(v)
        res.getString(R.string.voice_wake_footer_level, v.titleText.render(langs), v.detailText.render(langs), p.threshold.toString(), p.boost.toString())
    }
    return listOf(stringResource(R.string.voice_wake_footer), levels, stringResource(R.string.voice_wake_footer_try)).joinToString("\n\n")
}

/** One phrase: its sensitivity as radio rows (a pick saves and closes), what to know about it, and Remove. */
@Composable
private fun PhraseDialog(p: WakePhrase, speller: KwsSpeller?, insideWarning: Phrase?, insideNote: Phrase?, onDismiss: () -> Unit,
    onPick: (WakeSensitivity) -> Unit, onRemove: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("“${p.text}”") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.voice_sensitivity), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.semantics { heading() })
                for (v in WakeSensitivity.entries) {
                    val sel = v == p.sensitivity
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = RobotTokens.choiceRowHeight).clip(MaterialTheme.shapes.small)
                            .selectable(selected = sel, role = Role.RadioButton) { onPick(v) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = sel, onClick = null)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(v.titleText.str(), style = MaterialTheme.typography.bodyLarge)
                            Text(v.detailText.str(), style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    }
                }
                if (p.words == 1) Text(RoutineStrings.wakeShort().str(), style = MaterialTheme.typography.bodyMedium, color = muted)
                insideWarning?.let { Text(it.str(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                insideNote?.let { Text(it.str(), style = MaterialTheme.typography.bodyMedium, color = muted) }
                (speller?.spell(p.text) as? KwsSpeller.Result.Refused)?.let {
                    Text(stringResource(R.string.voice_not_listened, it.text.str()), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text(stringResource(R.string.voice_phrase_number, p.id), style = MaterialTheme.typography.bodySmall, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.voice_cancel)) } },
        dismissButton = { TextButton(onClick = onRemove) { Text(stringResource(R.string.voice_remove)) } },
    )
}

/** Settings › Voice › "Interrupt sensitivity" as a settings row: its title over [InterruptSlider]. */
@Composable
fun InterruptPreference(value: InterruptSensitivity, enabled: Boolean = true, onChange: (InterruptSensitivity) -> Unit) {
    PreferenceBlock {
        Text(stringResource(R.string.voice_interrupt), style = prefTitleStyle(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.38f), modifier = Modifier.clearAndSetSemantics {})
        InterruptSlider(value, enabled, onChange)
    }
}

/**
 * Settings › Voice › Barge-in test (0.4.x redesign: a page of its own): what the test runs with, its four runs as rows,
 * and the last result. The runs happen in the voice process (Cmd.BARGE_TEST), which writes the result read here.
 */
@Composable
fun BargeTestScreen(s: RobotSettings, snap: Snapshot, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    // What the test will run with (the phone's voice stands in while the chosen model isn't installed); the result says
    // what it ran with.
    val model = s.voiceChoice.bundle
    val missing = model != null && snap.voice.firstOrNull { it.bundle == model }?.let { it.status != ModelStatus.VERIFIED } == true
    val modelName = when (model) {
        VoiceBundle.KOKORO -> "Kokoro" // l10n: not text (a voice's name)
        VoiceBundle.KITTEN -> "Kitten" // l10n: not text (a voice's name)
        else -> null
    }
    val r = snap.bargeTest
    val langs = LocalLangs.current
    val running = r?.state == BenchState.RUNNING && System.currentTimeMillis() - r.started < BargeTestLogic.TIMEOUT_MS + 10_000
    val names = s.wakePhrases.associate { it.id to it.text }
    // The settings it runs with, as the Voice page says them (core BargeTestLogic.setup's, which speaks as you: "Hear me …").
    val phoneVoice = s.voiceChoice == VoiceChoice.SYSTEM || missing
    val setup = listOfNotNull(stringResource(R.string.voice_barge_setup, s.bargeIn.titleText.str(), s.interrupt.titleText.str(), s.playback.titleText.str()),
        if (!phoneVoice) null else if (missing && modelName != null) stringResource(R.string.voice_barge_phone_voice_missing, modelName)
        else stringResource(R.string.voice_barge_phone_voice)).joinToString(" · ")
    val groupRuns = stringResource(R.string.voice_barge_runs)
    val groupLast = stringResource(R.string.voice_barge_last)
    val footer = stringResource(R.string.voice_barge_footer)
    PreferenceScreen(stringResource(R.string.voice_barge_test), onBack, snackbar = snackbar) {
        item(key = "setup") { IntroPreference(setup) }
        preferenceGroup(groupRuns, "runs") {
            PreferenceItem(stringResource(R.string.voice_barge_run), stringResource(if (running) R.string.voice_testing else R.string.voice_barge_run_summary),
                enabled = !running, onClick = { a.bargeTest(BargeRun.FIRST) })
            PreferenceItem(stringResource(R.string.voice_barge_count), stringResource(R.string.voice_barge_count_summary), enabled = !running,
                onClick = { a.bargeTest(BargeRun.COUNT) })
            PreferenceItem(stringResource(R.string.voice_barge_silent), stringResource(R.string.voice_barge_silent_summary), enabled = !running,
                onClick = { a.bargeTest(BargeRun.SILENT) })
            PreferenceItem(stringResource(R.string.voice_barge_tricky), stringResource(R.string.voice_barge_tricky_summary), enabled = !running,
                onClick = { a.bargeTest(BargeRun.TRICKY) })
        }
        if (r != null) {
            preferenceGroup(groupLast, "result") {
                PreferenceBlock(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    // Its results are diagnostics (English, as the voice log); why it failed is in the menus' language (1.1).
                    val lines = BargeTestLogic.lines(r) { names[it] }.toMutableList()
                    if (r.state == BenchState.FAILED && lines.isNotEmpty()) r.noteText?.let { lines[0] = it.render(langs) }
                    for (line in lines) {
                        Text(line, style = MaterialTheme.typography.bodyMedium,
                            color = if (r.state == BenchState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        preferenceFooter(footer)
    }
}

/** Newest first: the voice process's log and the main app's few events (each process writes its own file). */
private fun readLog(ctx: Context): List<VoiceLogEntry> =
    (VoiceLogCodec.decode(readOrNull(Paths.voiceLog(ctx))) + VoiceLogCodec.decode(readOrNull(Paths.voiceLogMain(ctx))))
        .sortedByDescending { it.time }

/** Settings › Voice › Test wake words: while it shows, Robot lists the phrases it hears instead of opening. */
@Composable
fun WakeTestScreen(s: RobotSettings, snap: Snapshot, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val langs = LocalLangs.current
    val opened = remember { System.currentTimeMillis() }
    var hits by remember { mutableStateOf<List<VoiceLogEntry>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // Renewed every few seconds only while this screen is started (visible): with the screen off or the app in the
    // background the test ends, and wake words open Robot again. The voice process also ends it on its own.
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try {
                var tick = 0
                while (true) {
                    if (tick % 5 == 0) Cmd.send(ctx, Cmd.WAKE_TEST, android.os.Bundle().apply { putBoolean(Cmd.EXTRA_ON, true) })
                    hits = withContext(Dispatchers.IO) { readLog(ctx) }.filter { it.event == VoiceEvent.WAKE && it.c == WakeAction.TEST.id && it.time >= opened }
                    now = System.currentTimeMillis()
                    tick++
                    delay(1_000)
                }
            } finally {
                Cmd.send(ctx, Cmd.WAKE_TEST, android.os.Bundle().apply { putBoolean(Cmd.EXTRA_ON, false) })
            }
        }
    }
    DisposableEffect(Unit) { onDispose { Cmd.send(ctx, Cmd.WAKE_TEST, android.os.Bundle().apply { putBoolean(Cmd.EXTRA_ON, false) }) } }
    val names = s.wakePhrases.associate { it.id to it.text }
    Scaffold(topBar = { BackBar(stringResource(R.string.voice_test_wake), onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val listening = snap.facts?.listening
            StatusRow(if (listening == true) Mark.OK else Mark.WARN, stringResource(if (listening == true) R.string.voice_listening else R.string.voice_not_listening),
                if (listening == true) stringResource(R.string.voice_listening_detail)
                else snap.facts?.listeningNote ?: stringResource(R.string.voice_not_listening_detail))
            val on = s.wakePhrases.filter { it.enabled }.joinToString(" · ") { "“${it.text}” (${it.sensitivity.titleText.render(langs)})" }
            Text(stringResource(R.string.voice_wake_on, on.ifEmpty { stringResource(R.string.voice_wake_on_none) }),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (hits.isEmpty()) Text(stringResource(R.string.voice_nothing_heard), style = MaterialTheme.typography.bodyLarge)
            LazyColumn {
                items(hits) { e ->
                    ListItem(
                        headlineContent = { Text(names[e.a]?.let { "“$it”" } ?: stringResource(R.string.voice_phrase_removed, e.a)) },
                        trailingContent = { Text(VoiceLogText.ago(now - e.time), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

/**
 * Settings › Voice › Voice log: newest first, with relative times, and Copy (plain text: codes and numbers in words, wake
 * phrases by number). 0.7: "Send as bug report" opens Send feedback with the voice log ticked ([onReport]).
 */
@Composable
fun VoiceLogScreen(s: RobotSettings, snackbar: SnackbarHostState, onBack: () -> Unit, onReport: () -> Unit = {}) {
    val ctx = LocalContext.current
    var entries by remember { mutableStateOf<List<VoiceLogEntry>>(emptyList()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            entries = withContext(Dispatchers.IO) { readLog(ctx) }
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val copiedText = stringResource(R.string.voice_log_copied)
    LaunchedEffect(copied) {
        if (copied) {
            snackbar.showSnackbar(copiedText)
            copied = false
        }
    }
    val names = s.wakePhrases.associate { it.id to it.text }
    Scaffold(
        topBar = {
            BackBar(UiStrings.voiceLog().str(), onBack) {
                IconButton(onClick = {
                    // Wake phrases by number only: what leaves the screen never holds a phrase's words (0.7).
                    val text = VoiceLogText.plain(entries, System.currentTimeMillis(), "Robot ${BuildConfig.VERSION_NAME} voice log") // l10n: not text (the copied log, a diagnostic: English)
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Robot voice log", text)) // l10n: not text (a clip label)
                    copied = true
                }, enabled = entries.isNotEmpty()) { Icon(RIcons.Copy, stringResource(R.string.voice_log_copy)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text(stringResource(R.string.voice_log_intro, entries.size),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
                OutlinedButton(onClick = onReport, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.voice_log_send)) }
            }
            if (entries.isEmpty()) item { Text(stringResource(R.string.voice_log_empty)) }
            items(entries) { e ->
                Row {
                    Text(VoiceLogText.ago(now - e.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(min = 76.dp).padding(top = 2.dp))
                    Text(VoiceLogText.describe(e) { names[it] }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** For the snapshot loop: the last barge-in test. */
fun readBargeTest(ctx: Context): BargeTestResult? = BargeTestResult.fromJson(readOrNull(Paths.bargeTest(ctx)))
