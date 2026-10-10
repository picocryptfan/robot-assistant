package app.heyrobot.ui

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.VoiceBench
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceEngineStatus
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.Paths
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN (UI) PROCESS. Must never load sherpa-onnx or LiteRT: no reference to the assistant package, the
// voice or brain engine modules (ProcessIsolationTest). The assistant itself lives in the :voice process;
// this app opens it with Activity.showAssist and talks to it through files and Cmd broadcasts.

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { RobotTheme { RobotAppUi(this) } }
    }

    /** Opens the assistant panel (Robot must be the digital assistant). [conversation] continues a saved one. */
    fun talk(conversation: String? = null): Boolean = showAssist(Bundle().apply {
        putString("robot_source", "app")
        conversation?.let { putString("robot_conversation", it) }
    })
}

enum class Tab(val title: String) { HOME("Home"), HISTORY("History"), MODEL("Model"), SETTINGS("Settings") }

sealed class Page {
    data object Setup : Page()
    data class Conversation(val id: String) : Page()
}

/** Live facts the screens show, refreshed every second while the app is in front. */
data class Snapshot(
    val facts: AssistantFacts? = null,
    val model: ModelStatus = ModelStatus.MISSING,
    val progress: String? = null,
    val percent: Int = -1,
    val failure: String? = null,
    val freeBytes: Long = -1,
    /** The voice models (speech recognition, voice, fast voice), in VoiceBundle order. */
    val voice: List<VoiceModelStore.Snap> = emptyList(),
    /** What the voice process last reported about its speech engines, and the last voice speed test. */
    val engines: VoiceEngineStatus? = null,
    val bench: VoiceBench? = null,
) {
    fun voice(b: VoiceBundle): VoiceModelStore.Snap? = voice.firstOrNull { it.bundle == b }

    /** Speech recognition and the default voice are installed. */
    val voiceReady: Boolean get() = voice(VoiceBundle.STT)?.status == ModelStatus.VERIFIED && voice(VoiceBundle.KOKORO)?.status == ModelStatus.VERIFIED
}

@Composable
fun RobotAppUi(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var page by remember { mutableStateOf<Page?>(null) }
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var snap by remember { mutableStateOf(Snapshot()) }
    var history by remember { mutableStateOf<List<StoredConversation>>(emptyList()) }
    var historyTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var voiceInstalling by remember { mutableStateOf<VoiceBundle?>(null) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(resumed) {
        while (resumed) {
            snap = withContext(Dispatchers.IO) {
                val dl = ModelStore.downloadProgress(ctx)
                Snapshot(
                    facts = Assistant.facts(ctx),
                    model = ModelStore.status(ctx),
                    progress = ModelStore.importText?.let { "Copying: $it" } ?: ModelStore.verifyPercent?.let { "Checking the file: $it%" } ?: dl?.first,
                    percent = ModelStore.verifyPercent ?: dl?.second ?: -1,
                    failure = ModelStore.lastFailure(ctx),
                    freeBytes = ModelStore.freeBytes(ctx),
                    voice = VoiceBundle.entries.map { VoiceModelStore.snapshot(ctx, it) },
                    engines = VoiceEngineStatus.fromJson(readOrNull(Paths.voiceEngines(ctx))),
                    bench = VoiceBench.fromJson(readOrNull(Paths.voiceBench(ctx))),
                )
            }
            // A finished voice download is checked and installed right away, one bundle at a time.
            snap.voice.firstOrNull { it.status == ModelStatus.UNVERIFIED }?.let { v ->
                if (voiceInstalling == null) {
                    voiceInstalling = v.bundle
                    val app = ctx.applicationContext
                    Thread({
                        val ok = VoiceModelStore.install(app, v.bundle)
                        scope.launch {
                            voiceInstalling = null
                            say(if (ok) "${v.bundle.title} is ready." else "${v.bundle.title} couldn't be installed. See Model › Voice models.")
                        }
                    }, "robot-voice-install").start()
                }
            }
            // A finished download (or import) is checked right away: the model is never loaded unchecked.
            if (snap.model == ModelStatus.UNVERIFIED && busy == null) {
                busy = "verify"
                scope.launch(Dispatchers.IO) {
                    val ok = ModelStore.verify(ctx)
                    withContext(Dispatchers.Main) {
                        busy = null
                        say(if (ok) "Gemma is ready." else "The file is damaged. Delete it and download again.")
                    }
                }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(resumed, historyTick) {
        if (resumed) history = withContext(Dispatchers.IO) { ConversationStore.list(ctx) }
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The voice process starts listening once it may.
        Cmd.send(ctx, Cmd.SETTINGS)
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val why = ModelStore.refuseImport(ctx, uri)
        if (why != null) return@rememberLauncherForActivityResult say(why)
        busy = "import"
        // Not tied to this screen: the copy (minutes) goes on if it is left.
        Thread({
            val err = ModelStore.import(ctx.applicationContext, uri)
            val ok = err == null && ModelStore.verify(ctx.applicationContext)
            scope.launch {
                busy = null
                say(err ?: if (ok) "Gemma is ready." else "The copied file is damaged. Pick the right file or download it.")
            }
        }, "robot-import").start()
    }

    fun update(s: RobotSettings) {
        settings = s
        scope.launch(Dispatchers.IO) { SettingsStore.save(ctx, s) }
    }

    val actions = AppActions(
        talk = { id ->
            val f = snap.facts
            if (f?.active != true) { page = Page.Setup; say("Make Robot your digital assistant first.") }
            else if (!activity.talk(id)) say("Couldn't open the assistant.")
        },
        openSetup = { page = Page.Setup },
        openTab = { tab = it; page = null },
        openConversation = { page = Page.Conversation(it) },
        askPermissions = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
        openAssistantSettings = { if (!Assistant.openSettings(ctx)) say("Open Settings > Apps > Default apps > Digital assistant app.") },
        download = { ModelStore.startDownload(ctx)?.let(::say) },
        cancelDownload = { ModelStore.cancelDownload(ctx) },
        import = { importer.launch(arrayOf("*/*")) },
        verify = {
            if (busy == null) {
                busy = "verify"
                scope.launch(Dispatchers.IO) {
                    val ok = ModelStore.verify(ctx)
                    withContext(Dispatchers.Main) { busy = null; say(if (ok) "The file checks out." else "The file is damaged. Delete it and download again.") }
                }
            }
        },
        deleteModel = { ModelStore.delete(ctx); say("The model was deleted.") },
        deleteConversation = { id -> ConversationStore.delete(ctx, id); historyTick++; page = null },
        deleteAllConversations = { ConversationStore.deleteAll(ctx); historyTick++; say("History deleted.") },
        speakTest = { Cmd.send(ctx, Cmd.SPEAK_TEST) },
        say = ::say,
        voiceDownload = { b -> VoiceModelStore.startDownload(ctx, b)?.let(::say) },
        voiceCancel = { b -> VoiceModelStore.cancelDownload(ctx, b) },
        voiceDelete = { b -> VoiceModelStore.delete(ctx, b); say("${b.title} was deleted.") },
        voiceBench = {
            if (snap.facts?.active != true) say("Make Robot your digital assistant first: the test runs in the assistant.")
            else Cmd.send(ctx, Cmd.VOICE_BENCH)
        },
        voiceReset = { Cmd.send(ctx, Cmd.VOICE_RESET); say("Robot will try the voice models again.") },
    )

    BackHandler(enabled = page != null || tab != Tab.HOME) {
        if (page != null) page = null else tab = Tab.HOME
    }

    when (val p = page) {
        is Page.Setup -> SetupScreen(snap.facts, actions, snackbar, onBack = { page = null })
        is Page.Conversation -> ConversationScreen(history.firstOrNull { it.id == p.id }, actions, snackbar, onBack = { page = null })
        null -> Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t },
                            icon = { Icon(when (t) { Tab.HOME -> RIcons.Home; Tab.HISTORY -> RIcons.History; Tab.MODEL -> RIcons.Chip; Tab.SETTINGS -> RIcons.Settings }, null) },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            val m = Modifier.padding(pad)
            when (tab) {
                Tab.HOME -> HomeScreen(snap, history.take(3), actions, m)
                Tab.HISTORY -> HistoryScreen(history, actions, m)
                Tab.MODEL -> ModelScreen(snap, busy, actions, m)
                Tab.SETTINGS -> SettingsScreen(settings, snap, ::update, actions, m)
            }
        }
    }
}

/** What the screens can ask for. */
class AppActions(
    val talk: (String?) -> Unit,
    val openSetup: () -> Unit,
    val openTab: (Tab) -> Unit,
    val openConversation: (String) -> Unit,
    val askPermissions: () -> Unit,
    val openAssistantSettings: () -> Unit,
    val download: () -> Unit,
    val cancelDownload: () -> Unit,
    val import: () -> Unit,
    val verify: () -> Unit,
    val deleteModel: () -> Unit,
    val deleteConversation: (String) -> Unit,
    val deleteAllConversations: () -> Unit,
    val speakTest: () -> Unit,
    val say: (String) -> Unit,
    val voiceDownload: (VoiceBundle) -> Unit,
    val voiceCancel: (VoiceBundle) -> Unit,
    val voiceDelete: (VoiceBundle) -> Unit,
    val voiceBench: () -> Unit,
    val voiceReset: () -> Unit,
)
