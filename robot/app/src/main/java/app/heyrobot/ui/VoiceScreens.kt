package app.heyrobot.ui

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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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

/** The part of Settings › Voice about wake words, hearing you over Robot, and the log. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceListeningSection(s: RobotSettings, snap: Snapshot, update: (RobotSettings) -> Unit, a: AppActions) {
    Text("Wake words", style = MaterialTheme.typography.titleSmall)
    val on = s.wakePhrases.filter { it.enabled }
    NavRow(RIcons.Mic, "Wake words", if (on.isEmpty()) "None on: only the corner swipe opens Robot" else on.joinToString(" · ") { "“${it.text}”" }) {
        a.openPage(Page.WakePhrases)
    }
    NavRow(RIcons.Search, "Test wake words", "See which phrase Robot hears, as you say it") { a.openPage(Page.WakeTest) }

    Text("Hear me while Robot talks", style = MaterialTheme.typography.titleSmall)
    for (v in BargeIn.entries) {
        ChoiceRow(v.title + if (v == BargeIn.ECHO_CANCEL) " (default)" else "", v.detail, s.bargeIn == v) { update(s.copy(bargeIn = v)) }
    }
    Text("Robot's voice plays as", style = MaterialTheme.typography.titleSmall)
    for (p in Playback.entries) {
        ChoiceRow(p.title + if (p == Playback.ASSISTANT) " (default)" else "", p.detail, s.playback == p) { update(s.copy(playback = p)) }
    }
    Text("Volume key stops Robot", style = MaterialTheme.typography.titleSmall)
    SegmentedChoice(StopKey.entries, s.stopKey, { it.title }) { update(s.copy(stopKey = it)) }
    Text("While Robot talks and her panel is on screen, the key stops her and doesn't change the volume. With the screen off or the panel closed it works as always.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Text("Barge-in test", style = MaterialTheme.typography.titleSmall)
    Text("Robot reads a paragraph of about 20 seconds. Run the test: say “stop” or one wake phrase, once; she stops when she hears it, and the " +
        "result says what she heard and how fast she went quiet. Count run: she reads to the end while you say “stop” and your wake phrases as often " +
        "as you like, a few seconds apart; the result lists each one she heard. Silent run: stay quiet; it counts anything she hears anyway. " +
        "Tricky words: stay quiet while she reads words like “stop” (step, steep, stamp, bus stop); it shows how often her own words fire, and " +
        "how many of those are set aside. Try each mode above.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    // What the test will run with (the phone's voice stands in while the chosen model isn't installed); the result says
    // what it ran with.
    val model = s.voiceChoice.bundle
    val missing = model != null && snap.voice.firstOrNull { it.bundle == model }?.let { it.status != ModelStatus.VERIFIED } == true
    val modelName = when (model) {
        VoiceBundle.KOKORO -> "Kokoro"
        VoiceBundle.KITTEN -> "Kitten"
        else -> null
    }
    Text(BargeTestLogic.setup(s.bargeIn, s.playback, s.voiceChoice == VoiceChoice.SYSTEM || missing, if (missing) modelName else null),
        style = MaterialTheme.typography.bodyMedium)
    val r = snap.bargeTest
    val running = r?.state == BenchState.RUNNING && System.currentTimeMillis() - r.started < BargeTestLogic.TIMEOUT_MS + 10_000
    // Four buttons don't fit side by side on a phone: they wrap.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { a.bargeTest(BargeRun.FIRST) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (running) "Testing…" else "Run the test") }
        OutlinedButton(onClick = { a.bargeTest(BargeRun.COUNT) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("Count run") }
        OutlinedButton(onClick = { a.bargeTest(BargeRun.SILENT) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("Silent run") }
        OutlinedButton(onClick = { a.bargeTest(BargeRun.TRICKY) }, enabled = !running, modifier = Modifier.heightIn(min = 48.dp)) { Text("Tricky words") }
    }
    val names = s.wakePhrases.associate { it.id to it.text }
    if (r != null) for (line in BargeTestLogic.lines(r) { names[it] }) {
        Text(line, style = MaterialTheme.typography.bodyMedium, color = if (r.state == BenchState.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
    NavRow(RIcons.History, "Voice log", "What the voice side did, for when Robot didn't respond") { a.openPage(Page.VoiceLog) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SegmentedChoice(items: List<T>, selected: T, label: (T) -> String, onPick: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        items.forEachIndexed { i, v ->
            SegmentedButton(selected = selected == v, onClick = { onPick(v) }, shape = SegmentedButtonDefaults.itemShape(i, items.size)) { Text(label(v)) }
        }
    }
}

/** Settings › Voice › Wake words: add, remove, turn on and off, and each phrase's sensitivity. */
@Composable
fun WakePhrasesScreen(s: RobotSettings, update: (RobotSettings) -> Unit, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var speller by remember { mutableStateOf<KwsSpeller?>(null) }
    var spellerMissing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        speller = withContext(Dispatchers.IO) { KwsAssets.speller(ctx) }
        spellerMissing = speller == null
    }
    var input by remember { mutableStateOf("") }
    fun problem(text: String): String? = WakePhrases.problem(text, s.wakePhrases) ?: speller?.spell(text)?.let { (it as? KwsSpeller.Result.Refused)?.why }
        ?: if (spellerMissing) "Robot can't check wake words: its keyword spelling file is missing from this build." else null
    fun add() {
        val t = WakePhrases.clean(input)
        if (problem(t) != null || speller == null) return
        update(s.copy(wakePhrases = s.wakePhrases + WakePhrase(WakePhrases.nextId(s.wakePhrases), t, WakePhrases.defaultSensitivity(t))))
        input = ""
    }
    Scaffold(topBar = { BackBar("Wake words", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Say any of these to open Robot. Each runs on the phone; nothing is recorded. A longer phrase wakes Robot by accident less often.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!s.wakeWord) Text("The wake word is off (Settings › Voice).", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            if (s.wakePhrases.none { it.enabled }) {
                Text("No wake phrase is on: only the corner swipe opens Robot. “Stop” still works while Robot talks.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            // "robot" inside "hey robot": with "hey robot" off, saying it still fires "robot". A warning on its card in that
            // case only; a plain note on the shorter one's.
            val inside = WakePhrases.inside(s.wakePhrases)
            for (p in s.wakePhrases) PhraseCard(p, speller, WakePhrases.insideWarning(p, inside), WakePhrases.insideNote(p, inside), onChange = { np ->
                update(s.copy(wakePhrases = s.wakePhrases.map { if (it.id == p.id) np else it }))
            }, onRemove = { update(s.copy(wakePhrases = s.wakePhrases.filter { it.id != p.id })) })
            SectionCard("Add a wake phrase") {
                val err = if (input.isBlank()) null else problem(input)
                OutlinedTextField(
                    value = input, onValueChange = { input = it.take(40) }, label = { Text("Phrase") }, singleLine = true,
                    isError = err != null,
                    supportingText = {
                        Text(err ?: if (input.isNotBlank() && WakePhrases.singleWord(input)) WakePhrases.SHORT_WARNING + " It starts at Low."
                        else "1 to 4 words: letters, spaces and apostrophes, up to 24 characters. “Stop” is reserved.")
                    },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = ::add, enabled = input.isNotBlank() && err == null && speller != null, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add") }
            }
            SectionCard("Sensitivity") {
                for (v in WakeSensitivity.entries) {
                    val p = KwsThresholds.wake(v)
                    Text("${v.title}: ${v.detail}. (Threshold ${p.threshold}, boost ${p.boost}.)", style = MaterialTheme.typography.bodyMedium)
                }
                Text("Try a new phrase in Test wake words, from across the room too, before you rely on it.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun PhraseCard(p: WakePhrase, speller: KwsSpeller?, insideWarning: String?, insideNote: String?, onChange: (WakePhrase) -> Unit, onRemove: () -> Unit) {
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("“${p.text}”", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("No. ${p.id} in the voice log", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = p.enabled, onCheckedChange = { onChange(p.copy(enabled = it)) })
            IconButton(onClick = onRemove, modifier = Modifier.padding(start = 4.dp)) { Icon(RIcons.Delete, "Remove “${p.text}”") }
        }
        SegmentedChoice(WakeSensitivity.entries, p.sensitivity, { it.title }) { onChange(p.copy(sensitivity = it)) }
        if (p.words == 1) Text(WakePhrases.SHORT_WARNING, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        insideWarning?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        insideNote?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        (speller?.spell(p.text) as? KwsSpeller.Result.Refused)?.let {
            Text("${it.why} Robot doesn't listen for this one.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
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
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(hits) { e ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(VoiceLogText.ago(now - e.time), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.widthIn(min = 72.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(names[e.a]?.let { "“$it”" } ?: "Phrase no. ${e.a} (removed)", style = MaterialTheme.typography.titleMedium)
                    }
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
                TextButton(onClick = {
                    val text = VoiceLogText.plain(entries, System.currentTimeMillis(), "Robot ${BuildConfig.VERSION_NAME} voice log") { names[it] }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Robot voice log", text))
                    copied = true
                }, enabled = entries.isNotEmpty()) { Text("Copy") }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            item {
                Text("The last ${entries.size} things the voice side did, newest first: no words, no audio, only what happened and how long it took. " +
                    "When Robot didn't respond, take a screenshot or tap Copy right away.",
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
