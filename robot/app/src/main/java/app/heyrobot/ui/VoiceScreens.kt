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

// MAIN PROCESS. Settings › Voice, 0.2.3: the wake phrases, "Hear me while Robot talks" with its barge-in test, the
// volume key, "Test wake words" and the voice log. Wake phrases are spelled here with core's pure Kotlin
// (KwsAssets): this process never loads sherpa-onnx. The tests run in the voice process (Cmd broadcasts) and report
// through files, as the voice speed test does.

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
    Column {
        Slider(value = level, onValueChange = { level = it }, enabled = enabled, valueRange = 0f..(InterruptSensitivity.entries.size - 1).toFloat(),
            steps = InterruptSensitivity.entries.size - 2,
            // Read now: level was just set by onValueChange, whether or not the screen has been drawn since.
            onValueChangeFinished = { onChange(InterruptSensitivity.at(level)) },
            modifier = Modifier.semantics { contentDescription = "Interrupt sensitivity"; stateDescription = shown.title })
        Row(Modifier.fillMaxWidth()) {
            for (v in InterruptSensitivity.entries) Text(v.title, style = MaterialTheme.typography.labelMedium,
                color = if (v == shown) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), textAlign = when (v.ordinal) { 0 -> TextAlign.Start; InterruptSensitivity.entries.size - 1 -> TextAlign.End; else -> TextAlign.Center })
        }
        Text("How readily Robot hears “stop” and your wake phrases while it talks. ${shown.detail}." +
            if (shown == InterruptSensitivity.MEDIUM) " (default)" else "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    fun problem(text: String): String? = WakePhrases.problem(text, s.wakePhrases) ?: speller?.spell(text)?.let { (it as? KwsSpeller.Result.Refused)?.why }
        ?: if (spellerMissing) "Robot can't check wake words: its keyword spelling file is missing from this build." else null
    val on = s.wakeWord
    // "robot" inside "hey robot": with "hey robot" off, saying it still fires "robot". A warning on that phrase only; a
    // plain note on the shorter one's.
    val inside = WakePhrases.inside(s.wakePhrases)
    PreferenceScreen("Wake word", onBack, snackbar = snackbar) {
        item(key = "main") { MainSwitchBar("Use wake words", on) { update(s.copy(wakeWord = it)) } }
        preferenceGroup(null, "when") {
            SwitchPreference("Only while charging", "Saves battery", s.wakeOnlyWhileCharging, enabled = on) { update(s.copy(wakeOnlyWhileCharging = it)) }
        }
        preferenceGroup("Wake phrases", "phrases") {
            if (s.wakePhrases.none { it.enabled }) {
                StatusPreference("No wake phrase is on", "Only the corner swipe opens Robot. “Stop” still works while Robot talks.", Mark.WARN)
            }
            for (p in s.wakePhrases) {
                val refused = (speller?.spell(p.text) as? KwsSpeller.Result.Refused) != null
                val note = WakePhrases.insideWarning(p, inside) != null || WakePhrases.insideNote(p, inside) != null || p.words == 1
                TwoTargetSwitchPreference(
                    "“${p.text}”",
                    when {
                        refused -> "Can't be used: tap for why"
                        note -> "Sensitivity ${p.sensitivity.title} · tap for a note"
                        else -> "Sensitivity ${p.sensitivity.title}"
                    },
                    p.enabled,
                    onClick = { opened = p.id },
                    onCheckedChange = { e -> update(s.copy(wakePhrases = s.wakePhrases.map { if (it.id == p.id) it.copy(enabled = e) else it })) },
                    switchLabel = "Listen for “${p.text}”",
                    enabled = on,
                )
            }
            PreferenceItem("Add a wake phrase", "1 to 4 words", RIcons.Add, enabled = on, onClick = { adding = true })
        }
        preferenceGroup(null, "test") {
            PreferenceItem("Test wake words", "See which phrase Robot hears, as you say it", RIcons.Search, onClick = { a.openPage(Page.WakeTest) })
        }
        preferenceFooter(wakeFooter())
    }
    s.wakePhrases.firstOrNull { it.id == opened }?.let { p ->
        PhraseDialog(p, speller, WakePhrases.insideWarning(p, inside), WakePhrases.insideNote(p, inside), onDismiss = { opened = null },
            onPick = { v -> opened = null; update(s.copy(wakePhrases = s.wakePhrases.map { if (it.id == p.id) it.copy(sensitivity = v) else it })) },
            onRemove = { opened = null; update(s.copy(wakePhrases = s.wakePhrases.filter { it.id != p.id })) })
    }
    if (adding) {
        EditDialog(
            "Add a wake phrase", "", "Phrase", "1 to 4 words: letters, spaces and apostrophes, up to 24 characters. “Stop” is reserved.",
            validate = { if (it.isBlank()) null else problem(it) },
            confirm = "Add",
            hint = { if (it.isNotBlank() && WakePhrases.singleWord(it)) WakePhrases.SHORT_WARNING + " It starts at Low." else null },
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
private fun wakeFooter(): String =
    "Say any of these to open Robot. Each runs on the phone: the microphone stays on while Robot listens, and nothing is " +
        "recorded or saved. A longer phrase wakes Robot by accident less often. Only while charging saves battery.\n\n" +
        WakeSensitivity.entries.joinToString("\n") { v ->
            val p = KwsThresholds.wake(v)
            "${v.title}: ${v.detail}. (Threshold ${p.threshold}, boost ${p.boost}.)"
        } +
        "\n\nTry a new phrase in Test wake words, from across the room too, before you rely on it."

/** One phrase: its sensitivity as radio rows (a pick saves and closes), what to know about it, and Remove. */
@Composable
private fun PhraseDialog(p: WakePhrase, speller: KwsSpeller?, insideWarning: String?, insideNote: String?, onDismiss: () -> Unit,
    onPick: (WakeSensitivity) -> Unit, onRemove: () -> Unit) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("“${p.text}”") },
        text = {
            Column(Modifier.selectableGroup().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Sensitivity", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
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
                            Text(v.title, style = MaterialTheme.typography.bodyLarge)
                            Text(v.detail, style = MaterialTheme.typography.bodyMedium, color = muted)
                        }
                    }
                }
                if (p.words == 1) Text(WakePhrases.SHORT_WARNING, style = MaterialTheme.typography.bodyMedium, color = muted)
                insideWarning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
                insideNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                (speller?.spell(p.text) as? KwsSpeller.Result.Refused)?.let {
                    Text("${it.why} Robot doesn't listen for this one.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Text("No. ${p.id} in the voice log", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        dismissButton = { TextButton(onClick = onRemove) { Text("Remove") } },
    )
}

/** Settings › Voice › "Interrupt sensitivity" as a settings row: its title over [InterruptSlider]. */
@Composable
fun InterruptPreference(value: InterruptSensitivity, enabled: Boolean = true, onChange: (InterruptSensitivity) -> Unit) {
    PreferenceBlock {
        Text("Interrupt sensitivity", style = prefTitleStyle(),
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
        VoiceBundle.KOKORO -> "Kokoro"
        VoiceBundle.KITTEN -> "Kitten"
        else -> null
    }
    val r = snap.bargeTest
    val running = r?.state == BenchState.RUNNING && System.currentTimeMillis() - r.started < BargeTestLogic.TIMEOUT_MS + 10_000
    val names = s.wakePhrases.associate { it.id to it.text }
    PreferenceScreen("Barge-in test", onBack, snackbar = snackbar) {
        item(key = "setup") {
            IntroPreference(BargeTestLogic.setup(s.bargeIn, s.playback, s.voiceChoice == VoiceChoice.SYSTEM || missing, if (missing) modelName else null, s.interrupt))
        }
        preferenceGroup("Run", "runs") {
            PreferenceItem("Run the test", if (running) "Testing…" else "Say “stop” or one wake phrase, once", enabled = !running,
                onClick = { a.bargeTest(BargeRun.FIRST) })
            PreferenceItem("Count run", "Say them as often as you like", enabled = !running, onClick = { a.bargeTest(BargeRun.COUNT) })
            PreferenceItem("Silent run", "Stay quiet", enabled = !running, onClick = { a.bargeTest(BargeRun.SILENT) })
            PreferenceItem("Tricky words", "Stay quiet while Robot reads words like “stop”", enabled = !running, onClick = { a.bargeTest(BargeRun.TRICKY) })
        }
        if (r != null) {
            preferenceGroup("Last result", "result") {
                PreferenceBlock(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
                    if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
                    for (line in BargeTestLogic.lines(r) { names[it] }) {
                        Text(line, style = MaterialTheme.typography.bodyMedium,
                            color = if (r.state == BenchState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        preferenceFooter(
            "Robot reads a paragraph of about 20 seconds. Run the test: say “stop” or one wake phrase, once; Robot stops when it hears " +
                "it, and the result says what it heard and how fast it went quiet. Count run: Robot reads to the end while you say " +
                "“stop” and your wake phrases as often as you like, a few seconds apart; the result lists each one it heard. Silent " +
                "run: stay quiet; it counts anything Robot hears anyway. Tricky words: stay quiet while Robot reads words like “stop” " +
                "(step, steep, stamp, bus stop); it shows how often its own words fire, and how many of those are set aside. Try " +
                "each mode and each interrupt sensitivity in Settings › Voice: the result says which it ran with.",
        )
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
    Scaffold(topBar = { BackBar("Test wake words", onBack) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val listening = snap.facts?.listening
            StatusRow(if (listening == true) Mark.OK else Mark.WARN, if (listening == true) "Listening" else "Not listening",
                if (listening == true) "Say a wake phrase. Robot won't open while this screen shows."
                else snap.facts?.listeningNote ?: "Robot must be the running assistant with the wake word on.")
            Text("On: " + s.wakePhrases.filter { it.enabled }.joinToString(" · ") { "“${it.text}” (${it.sensitivity.title})" }.ifEmpty { "none" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            if (hits.isEmpty()) Text("Nothing heard yet.", style = MaterialTheme.typography.bodyLarge)
            LazyColumn {
                items(hits) { e ->
                    ListItem(
                        headlineContent = { Text(names[e.a]?.let { "“$it”" } ?: "Phrase no. ${e.a} (removed)") },
                        trailingContent = { Text(VoiceLogText.ago(now - e.time), style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
    }
}

/** Settings › Voice › Voice log: newest first, with relative times, and Copy (plain text: codes and numbers in words). */
@Composable
fun VoiceLogScreen(s: RobotSettings, snackbar: SnackbarHostState, onBack: () -> Unit) {
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
    LaunchedEffect(copied) {
        if (copied) {
            snackbar.showSnackbar("Copied. Paste it in a message, or take a screenshot.")
            copied = false
        }
    }
    val names = s.wakePhrases.associate { it.id to it.text }
    Scaffold(
        topBar = {
            BackBar("Voice log", onBack) {
                IconButton(onClick = {
                    val text = VoiceLogText.plain(entries, System.currentTimeMillis(), "Robot ${BuildConfig.VERSION_NAME} voice log") { names[it] }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Robot voice log", text))
                    copied = true
                }, enabled = entries.isNotEmpty()) { Icon(RIcons.Copy, "Copy log") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("The last ${entries.size} things the voice side did, newest first: no words, no audio, only what happened and how long it took. " +
                    "When Robot didn't respond, take a screenshot or tap Copy log right away.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
            }
            if (entries.isEmpty()) item { Text("Nothing yet. Robot's voice side writes here once it runs as the assistant.") }
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
