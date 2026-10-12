package app.heyrobot.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.NearestAnswer
import app.heyrobot.core.Approval
import app.heyrobot.core.BargeRun
import app.heyrobot.core.BargeTestResult
import app.heyrobot.core.BrainModel
import app.heyrobot.core.CalendarInfo
import app.heyrobot.core.ModelFormat
import app.heyrobot.core.ModelStatus
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.Note
import app.heyrobot.core.OwnModel
import app.heyrobot.core.RobotSettings
import app.heyrobot.core.ModelTerms
import app.heyrobot.core.SettingsPage
import app.heyrobot.core.TextApp
import app.heyrobot.core.ToolGroup
import app.heyrobot.core.ToolName
import app.heyrobot.core.StoredConversation
import app.heyrobot.core.VoiceBench
import app.heyrobot.core.VoiceBundle
import app.heyrobot.core.VoiceEngineStatus
import app.heyrobot.core.readOrNull
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.AssistantFacts
import app.heyrobot.sys.Calendars
import app.heyrobot.sys.HelperKeyFiles
import app.heyrobot.sys.HelperWindow
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.NearestAnswers
import app.heyrobot.sys.ConversationStore
import app.heyrobot.sys.ModelStore
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.sys.RoutineIntents
import app.heyrobot.sys.NotesFiles
import app.heyrobot.sys.Paths
import app.heyrobot.sys.SettingsIntents
import app.heyrobot.sys.SettingsStore
import app.heyrobot.sys.Transcripts
import app.heyrobot.sys.VoiceprintFiles
import app.heyrobot.core.ConvEntry
import app.heyrobot.sys.VoiceModelStore
import app.heyrobot.sys.MtModelStore
import app.heyrobot.core.MtBundle
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.model.ModelScreen
import app.heyrobot.ui.settings.AppRoutes
import app.heyrobot.ui.settings.SettingsNav
import app.heyrobot.ui.settings.SettingsRoot
import app.heyrobot.ui.settings.SetupScreen
import app.heyrobot.ui.settings.rememberPageStack
import app.heyrobot.ui.theme.RobotTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN (UI) PROCESS. Must never load sherpa-onnx or LiteRT: no reference to the assistant package, the
// voice or brain engine modules (ProcessIsolationTest). The assistant itself lives in the :voice process;
// this app opens it with Activity.showAssist and talks to it through files and Cmd broadcasts.

class MainActivity : ComponentActivity() {
    /**
     * Where the last intent asked the app to open: Robot Notes (the "Robot Notes" launcher entry, an activity-alias of
     * this activity) or the permission screen (a card's "Allow" in the panel). Read once by the UI, then cleared.
     */
    val route = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) route.value = routeOf(intent)
        setContent { RobotTheme { RobotAppUi(this) } }
        // The speech recognizer setting that keeps Robot the assistant at the next restart (only with the adb grant).
        val app = applicationContext
        Thread({
            try {
                RecognizerKeeper.flushPending(app)
                RecognizerKeeper.check(app, RepairWhen.APP)
            } catch (_: Throwable) {}
        }, "robot-recognizer").start()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        route.value = routeOf(intent)
    }

    /** The pages other screens and apps may open (ui/settings AppRoutes). */
    private fun routeOf(i: Intent?): String? = AppRoutes.of(i)

    /** 0.8: opens the panel to run a routine or show a Ready card ([key]: sys RoutineIntents' show argument). */
    fun routine(key: String, value: String): Boolean = showAssist(Bundle().apply {
        putString("robot_source", "app")
        putString(key, value)
    })

    /** Opens the assistant panel (Robot must be the digital assistant). [conversation] continues a saved one. */
    fun talk(conversation: String? = null): Boolean = showAssist(Bundle().apply {
        putString("robot_source", "app")
        conversation?.let { putString("robot_conversation", it) }
    })
}

enum class Tab(val title: String) { HOME("Home"), NOTES("Notes"), HISTORY("History"), MODEL("Model"), SETTINGS("Settings") }

sealed class Page {
    /** [people]: opened for a permission ("People, notes and calendar" first). */
    data class Setup(val people: Boolean = false) : Page()
    data class Conversation(val id: String) : Page()
    /** [id] null: a new note (until its first save gives it one). */
    data class NoteEdit(val id: String?) : Page()
    /** Settings › Voice (0.2.3). */
    data object WakePhrases : Page()
    data object WakeTest : Page()
    data object VoiceLog : Page()
    /** Settings › Advanced › ADB fixes. */
    data object AdbFixes : Page()
    /** Settings › People (0.4.0), and one person ([id] null: a new one). */
    data object People : Page()
    data class PersonEdit(val id: String?) : Page()
    /** Settings › Safety & privacy (0.4.0). */
    data object Safety : Page()
    /** Settings › About › Licences (0.4.5), and one part's licence ([file]: one of its notices instead). */
    data object Licences : Page()
    data class Licence(val id: String, val file: String? = null) : Page()
    /** A Settings page by route (ui/settings/SettingsNav.kt; 0.4.x: "display"). */
    data class Settings(val route: String) : Page()
    /** 0.5.0: the Transcripts library, one transcript (and the line a search found), Settings › Conversation. */
    data object Transcripts : Page()
    data class Transcript(val id: String, val line: Int? = null) : Page()
    data object ConversationSettings : Page()
    /** Settings › Helpers (0.6.0), one helper's page, and "Add a helper". */
    data object Helpers : Page()
    data class Helper(val id: String) : Page()
    data object AddHelper : Page()

    companion object {
        /** The open page survives a recreation (rotation, dark mode, the process killed): "note:<id>", "setup:1"... */
        val Saver: Saver<Page?, String> = Saver(
            save = { p ->
                when (p) {
                    null -> ""
                    is Setup -> if (p.people) "setup:1" else "setup:0"
                    is Conversation -> "conv:${p.id}"
                    is NoteEdit -> "note:${p.id.orEmpty()}"
                    WakePhrases -> "wake"
                    WakeTest -> "waketest"
                    VoiceLog -> "voicelog"
                    AdbFixes -> "adbfixes"
                    People -> "people"
                    is PersonEdit -> "person:${p.id.orEmpty()}"
                    Safety -> "safety"
                    Licences -> "licences"
                    is Licence -> "licence:${p.id}|${p.file.orEmpty()}"
                    is Settings -> "settings:${p.route}"
                    Transcripts -> "transcripts"
                    is Transcript -> "transcript:${p.id}:${p.line ?: -1}"
                    ConversationSettings -> "convsettings"
                    Helpers -> "helpers"
                    is Helper -> "helper:${p.id}"
                    AddHelper -> "addhelper"
                }
            },
            restore = { v ->
                when {
                    v.startsWith("setup:") -> Setup(v == "setup:1")
                    v.startsWith("conv:") -> Conversation(v.removePrefix("conv:"))
                    v.startsWith("note:") -> NoteEdit(v.removePrefix("note:").ifEmpty { null })
                    v == "wake" -> WakePhrases
                    v == "waketest" -> WakeTest
                    v == "voicelog" -> VoiceLog
                    v == "adbfixes" -> AdbFixes
                    v == "people" -> People
                    v.startsWith("person:") -> PersonEdit(v.removePrefix("person:").ifEmpty { null })
                    v == "safety" -> Safety
                    v == "licences" -> Licences
                    v.startsWith("licence:") -> v.removePrefix("licence:").split('|', limit = 2).let { Licence(it[0], it.getOrNull(1)?.ifEmpty { null }) }
                    v.startsWith("settings:") -> Settings(v.removePrefix("settings:"))
                    v == "transcripts" -> Transcripts
                    v.startsWith("transcript:") -> v.split(':').let { Transcript(it[1], it.getOrNull(2)?.toIntOrNull()?.takeIf { n -> n >= 0 }) }
                    v == "convsettings" -> ConversationSettings
                    v == "helpers" -> Helpers
                    v.startsWith("helper:") -> Helper(v.removePrefix("helper:"))
                    v == "addhelper" -> AddHelper
                    else -> null
                }
            },
        )
    }
}

/** Live facts the screens show, refreshed every second while the app is in front. */
data class Snapshot(
    val facts: AssistantFacts? = null,
    /** The model Robot answers with: the one you chose when it is ready, else Fast (Home's "Get started"). */
    val model: ModelStatus = ModelStatus.MISSING,
    val progress: String? = null,
    val percent: Int = -1,
    val failure: String? = null,
    val freeBytes: Long = -1,
    /** Settings › Model (0.4.1): every model's card, your own file's record, and the model :brain holds now. */
    val models: List<ModelStore.Snap> = emptyList(),
    val own: OwnModel? = null,
    val loaded: BrainModel? = null,
    /** 0.4.5: this build has its llama.cpp library (tools/build_llama.sh), so your own GGUF file can run. */
    val ggufEngine: Boolean = false,
    /** The voice models (speech recognition, voice, fast voice), in VoiceBundle order. */
    val voice: List<VoiceModelStore.Snap> = emptyList(),
    /** What the voice process last reported about its speech engines, and the last voice speed test. */
    val engines: VoiceEngineStatus? = null,
    val bench: VoiceBench? = null,
    /** The last barge-in test (Settings › Voice). */
    val bargeTest: BargeTestResult? = null,
    /** The panel's answer to the nearest place's first-use card (Settings › Actions shows it when newer than its own). */
    val nearestAnswer: NearestAnswer? = null,
    /** 0.5.0: the fast translation engine's files (Settings › Conversation). */
    val mt: MtModelStore.Snap? = null,
    /** 0.5.0: a "me" voiceprint is saved (only that: it is encrypted, and read by the voice process only). */
    val voiceprint: Boolean = false,
) {
    fun voice(b: VoiceBundle): VoiceModelStore.Snap? = voice.firstOrNull { it.bundle == b }

    fun model(m: BrainModel): ModelStore.Snap? = models.firstOrNull { it.model == m }

    /** [m] can be chosen: checked, and (your own file) runnable here, the TPU allowed if it needs it, llama.cpp in this build for a GGUF file. */
    fun ready(m: BrainModel): Boolean = model(m)?.status == ModelStatus.VERIFIED &&
        (m != BrainModel.OWN || own?.backend != null && (own.format != ModelFormat.GGUF || ggufEngine))

    /** Speech recognition and the default voice are installed. */
    val voiceReady: Boolean get() = voice(VoiceBundle.STT)?.status == ModelStatus.VERIFIED && voice(VoiceBundle.KOKORO)?.status == ModelStatus.VERIFIED
}

@Composable
fun RobotAppUi(activity: MainActivity) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    // The open pages, the last one shown: Back goes up one page (ui/settings PageStack).
    val nav = rememberPageStack()
    var page by nav
    /** Opened from the "Robot Notes" icon and still on Notes: Back leaves the app, as from any app's first screen. */
    var fromNotesIcon by rememberSaveable { mutableStateOf(false) }
    var settings by remember { mutableStateOf(SettingsStore.load(ctx)) }
    var snap by remember { mutableStateOf(Snapshot()) }
    var history by remember { mutableStateOf<List<StoredConversation>>(emptyList()) }
    var historyTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf<String?>(null) }
    var voiceInstalling by remember { mutableStateOf<VoiceBundle?>(null) }
    var mtInstalling by remember { mutableStateOf(false) }
    /** The model the file picker is choosing a file for. */
    var importTarget by rememberSaveable { mutableStateOf(BrainModel.FAST.id) }
    var notes by remember { mutableStateOf<List<Note>>(emptyList()) }
    var notesTick by remember { mutableIntStateOf(0) }
    var calendars by remember { mutableStateOf<List<CalendarInfo>>(emptyList()) }
    var permissionTick by remember { mutableIntStateOf(0) }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    val resumed = lstate.isAtLeast(Lifecycle.State.RESUMED)

    fun say(msg: String) { scope.launch { snackbar.showSnackbar(msg) } }

    LaunchedEffect(resumed) {
        while (resumed) {
            snap = withContext(Dispatchers.IO) {
                val models = BrainModel.entries.map { ModelStore.snapshot(ctx, it) }
                val chosen = models.first { it.model == settings.brainModelChoice }
                val use = chosen.takeIf { it.status == ModelStatus.VERIFIED } ?: models.first { it.model == BrainModel.FAST }
                Snapshot(
                    facts = Assistant.facts(ctx),
                    model = use.status,
                    progress = use.progress,
                    percent = use.percent,
                    failure = use.failure,
                    freeBytes = ModelStore.freeBytes(ctx),
                    models = models,
                    own = ModelStore.own(ctx),
                    loaded = ModelStore.loadedNow(ctx),
                    ggufEngine = ModelStore.ggufEngine(ctx),
                    voice = VoiceBundle.entries.map { VoiceModelStore.snapshot(ctx, it) },
                    engines = VoiceEngineStatus.fromJson(readOrNull(Paths.voiceEngines(ctx))),
                    bench = VoiceBench.fromJson(readOrNull(Paths.voiceBench(ctx))),
                    bargeTest = readBargeTest(ctx),
                    nearestAnswer = NearestAnswers.load(ctx),
                    mt = MtModelStore.snapshot(ctx, MtBundle.FR_EN),
                    voiceprint = VoiceprintFiles.saved(ctx),
                )
            }
            // The meVoiceprint setting is only a flag, kept in step with the file (made or deleted in the voice process).
            if (snap.voiceprint != settings.meVoiceprint) {
                settings = settings.copy(meVoiceprint = snap.voiceprint)
                SettingsStore.save(ctx, settings)
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
            // The fast translation engine's files too, the same way.
            if (snap.mt?.status == ModelStatus.UNVERIFIED && !mtInstalling) {
                mtInstalling = true
                val app = ctx.applicationContext
                Thread({
                    val ok = MtModelStore.install(app, MtBundle.FR_EN)
                    scope.launch {
                        mtInstalling = false
                        say(if (ok) "The fast translation engine is ready." else "The fast translation engine couldn't be installed. See Settings › Conversation.")
                    }
                }, "robot-mt-install").start()
            }
            // A finished download (or import) is checked right away: a model is never loaded unchecked.
            snap.models.firstOrNull { it.status == ModelStatus.UNVERIFIED }?.let { u ->
                if (busy == null) {
                    busy = "verify"
                    scope.launch(Dispatchers.IO) {
                        val ok = ModelStore.verify(ctx, u.model)
                        withContext(Dispatchers.Main) {
                            busy = null
                            say(checkedText(u.model, ok))
                        }
                    }
                }
            }
            delay(1_000)
        }
    }
    LaunchedEffect(resumed, historyTick) {
        if (resumed) history = withContext(Dispatchers.IO) { ConversationStore.list(ctx) }
    }
    // The assistant adds (and undoes) notes from the panel, which opens over this window without pausing it: read the
    // folder again when the app comes back and whenever the window gets the focus back from the panel.
    LaunchedEffect(resumed, notesTick) {
        if (resumed) notes = withContext(Dispatchers.IO) { NotesFiles.store(ctx).list() }
    }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
        snapshotFlow { windowInfo.isWindowFocused }.collect { focused -> if (focused) notesTick++ }
    }
    LaunchedEffect(resumed, permissionTick) {
        if (resumed) calendars = withContext(Dispatchers.IO) { Calendars.writable(ctx) }
    }
    val route by activity.route
    // An open note editor saves when it closes (its onDispose), so switching pages here never loses text.
    LaunchedEffect(route) {
        route?.let(AppRoutes::target)?.let { t ->
            if (t.tab != null) { tab = t.tab; page = null }
            if (t.page != null) page = t.page
            if (t.notesIcon) fromNotesIcon = true
        }
        activity.route.value = null
    }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // The voice process starts listening once it may.
        Cmd.send(ctx, Cmd.SETTINGS)
    }
    // Contacts and calendar: asked for here only, when you tap Allow or turn a feature on (never at first launch).
    val peoplePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionTick++
        if (result.values.any { !it }) say("Not allowed. If Android doesn't ask again: Settings › Apps › Robot › Permissions.")
    }
    // Photos and videos (0.4.0): "Select photos" grants only READ_MEDIA_VISUAL_USER_SELECTED, which is allowed too.
    val mediaPermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        permissionTick++
        when {
            result[Manifest.permission.READ_MEDIA_IMAGES] == true || result[Manifest.permission.READ_MEDIA_VIDEO] == true -> {}
            result[Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED] == true -> say("Robot can see the photos and videos you picked.")
            else -> say("Not allowed. If Android doesn't ask again: Settings › Apps › Robot › Permissions.")
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val target = BrainModel.of(importTarget)
        val why = ModelStore.refuseImport(ctx, target, uri)
        if (why != null) return@rememberLauncherForActivityResult say(why)
        busy = "import"
        // Not tied to this screen: the copy (minutes) and the check go on if it is left.
        Thread({
            val err = ModelStore.import(ctx.applicationContext, target, uri)
            val ok = err == null && ModelStore.verify(ctx.applicationContext, target)
            scope.launch {
                busy = null
                say(err ?: if (target == BrainModel.OWN) checkedText(target, ok)
                    else if (ok) "“${target.title}” is ready." else "The copied file is damaged. Pick the right file or download it.")
            }
        }, "robot-import").start()
    }

    fun update(s: RobotSettings) {
        settings = s
        // Off the main thread, one save at a time in order (SettingsStore).
        SettingsStore.save(ctx, s)
    }

    /** A helper's connection window, once the settings that added or changed the helper are on disk (it reads them in the assistant's process). */
    fun openHelperWindow(id: String, mode: String) {
        SettingsStore.afterSaves {
            scope.launch(Dispatchers.Main) {
                // By name: the main process never refers to the assistant's classes.
                try {
                    ctx.startActivity(Intent().setClassName(ctx.packageName, HelperWindow.CLASS).putExtra(HelperWindow.EXTRA_HELPER, id)
                        .putExtra(HelperWindow.EXTRA_MODE, mode))
                } catch (_: Exception) { say("Couldn't open the connection window.") }
            }
        }
    }

    val actions = AppActions(
        talk = { id ->
            val f = snap.facts
            if (f?.active != true) { page = Page.Setup(); say("Make Robot your digital assistant first.") }
            else if (!activity.talk(id)) say("Couldn't open the assistant.")
        },
        openSetup = { page = Page.Setup() },
        openTab = { tab = it; page = null },
        openConversation = { page = Page.Conversation(it) },
        askPermissions = { permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) },
        openAssistantSettings = { if (!Assistant.openSettings(ctx)) say("Open Settings > Apps > Default apps > Digital assistant app.") },
        // A model whose publisher asks for conditions (core ModelTerms) downloads or imports only once you ticked them.
        download = { m -> if (ModelTerms.missing(settings, m) != null) say(ModelTerms.REFUSED) else ModelStore.startDownload(ctx, m)?.let(::say) },
        cancelDownload = { m -> ModelStore.cancelDownload(ctx, m) },
        // Storage Access Framework: no storage permission; a .litertlm or .gguf has no type of its own, so any file.
        import = { m -> if (ModelTerms.missing(settings, m) != null) say(ModelTerms.REFUSED) else { importTarget = m.id; importer.launch(arrayOf("*/*")) } },
        verify = { m ->
            if (busy == null) {
                busy = "verify"
                scope.launch(Dispatchers.IO) {
                    val ok = ModelStore.verify(ctx, m)
                    withContext(Dispatchers.Main) {
                        busy = null
                        say(if (m == BrainModel.OWN) checkedText(m, ok) else if (ok) "The file checks out." else "The file is damaged. Delete it and download again.")
                    }
                }
            }
        },
        deleteModel = { m ->
            scope.launch {
                val why = withContext(Dispatchers.IO) { ModelStore.delete(ctx, m) }
                if (why != null) return@launch say(why)
                // The choice goes back to Fast with its model.
                val chosen = settings.brainModelChoice == m && m != BrainModel.FAST
                if (chosen) update(settings.copy(brainModel = BrainModel.FAST.id))
                say("“${m.title}” was deleted." + if (chosen) " Robot uses Fast again." else "")
            }
        },
        allowOwnTpu = { allow, sha256 ->
            scope.launch {
                val ok = withContext(Dispatchers.IO) { ModelStore.allowOwnTpu(ctx, allow, sha256) }
                say(if (!ok && allow) "Robot couldn't allow that: the file changed since its check. Check it again first."
                    else if (!ok) "Robot couldn't change that." else if (allow) "Your model file may run on the TPU now." else "Your model file won't run on the TPU.")
            }
        },
        // The panel's live conversation too, or its next save would write a deleted one back.
        deleteConversation = { id ->
            ConversationStore.delete(ctx, id); Cmd.send(ctx, Cmd.CONVERSATIONS_DELETED, Bundle().apply { putString(Cmd.EXTRA_ID, id) })
            historyTick++; page = null
        },
        deleteAllConversations = {
            ConversationStore.deleteAll(ctx); Cmd.send(ctx, Cmd.CONVERSATIONS_DELETED)
            historyTick++; say("History deleted.")
        },
        removeSavedMedia = {
            scope.launch {
                val n = withContext(Dispatchers.IO) { ConversationStore.removeMedia(ctx) }
                // The panel's live conversation too, or its next save would write them back.
                Cmd.send(ctx, Cmd.MEDIA_REMOVED)
                historyTick++
                say(if (n == 0) "No saved screenshots or photos." else "Removed from $n conversation${if (n == 1) "" else "s"}.")
            }
        },
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
        askContacts = { peoplePermissions.launch(arrayOf(Manifest.permission.READ_CONTACTS)) },
        askCalendar = { peoplePermissions.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) },
        openAppSettings = {
            try {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)))
            } catch (_: Exception) { say("Open Settings › Apps › Robot › Permissions.") }
        },
        openNote = { id -> page = Page.NoteEdit(id) },
        helperWindow = ::openHelperWindow,
        helperMoved = { id, dropKey ->
            // The address now names another host: the key saved for the old one goes first (files and the Keystore; the
            // assistant drops its client), so Test connection, which opens after, can't send it to the new address.
            scope.launch(Dispatchers.IO) {
                if (dropKey) {
                    HelperKeyFiles.delete(ctx, id)
                    Cmd.send(ctx, Cmd.HELPER_KEY, Bundle().apply { putString(Cmd.EXTRA_ID, id) })
                }
                withContext(Dispatchers.Main) { openHelperWindow(id, HelperWindow.MODE_TEST) }
            }
        },
        deleteHelperKey = { id ->
            val name = settings.helper(id)?.name ?: "helper's"
            // Off the main thread (files and the Keystore); then the assistant drops its client at once.
            scope.launch(Dispatchers.IO) {
                HelperKeyFiles.delete(ctx, id)
                Cmd.send(ctx, Cmd.HELPER_KEY, Bundle().apply { putString(Cmd.EXTRA_ID, id) })
                withContext(Dispatchers.Main) { say("The $name key was deleted.") }
            }
        },
        removeHelper = { id ->
            settings.helper(id)?.let { h ->
                update(settings.copy(helpers = settings.helpers.filter { it.id != id }, helpDefault = settings.helpDefault.takeIf { it != id }))
                page = Page.Helpers
                // Its key (crypto-shredded), what Test connection found and its folder; the assistant ends its conversation.
                scope.launch(Dispatchers.IO) {
                    HelperKeyFiles.removeAll(ctx, id)
                    Cmd.send(ctx, Cmd.HELPER_KEY, Bundle().apply { putString(Cmd.EXTRA_ID, id) })
                    withContext(Dispatchers.Main) { say("${h.name} was removed.") }
                }
            }
        },
        openPage = { page = it },
        bargeTest = { run ->
            if (snap.facts?.active != true) say("Make Robot your digital assistant first: the test runs in the assistant.")
            else Cmd.send(ctx, Cmd.BARGE_TEST, Bundle().apply { putString(Cmd.EXTRA_RUN, run.name) })
        },
        askLocation = { peoplePermissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION)) },
        askMedia = {
            mediaPermissions.launch(arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))
        },
        transcribe = {
            // By name: the screen runs in the voice process, which starts the session.
            try { ctx.startActivity(Transcripts.startIntent(ctx, ConvEntry.APP.name)) } catch (_: Exception) { say("Couldn't open the transcript screen.") }
        },
        interpret = {
            try { ctx.startActivity(Transcripts.startIntent(ctx, ConvEntry.APP.name, app.heyrobot.core.ConvMode.INTERPRET.name)) } catch (_: Exception) {
                say("Couldn't open the interpreter.")
            }
        },
        openTranscriptionCheck = {
            try { ctx.startActivity(Transcripts.checkIntent(ctx)) } catch (_: Exception) { say("Couldn't open the Transcription check.") }
        },
        mtDownload = { MtModelStore.startDownload(ctx, MtBundle.FR_EN)?.let(::say) },
        mtCancel = { MtModelStore.cancelDownload(ctx, MtBundle.FR_EN) },
        mtDelete = { MtModelStore.delete(ctx, MtBundle.FR_EN); say("The fast translation engine's files were deleted.") },
        openVoiceprint = {
            // By name: the screen runs in the voice process, which alone holds the speaker model and the voiceprint's key.
            try {
                ctx.startActivity(Intent().setClassName(ctx.packageName, "app.heyrobot.assistant.conversation.VoiceprintActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) { say("Couldn't open Your voice.") }
        },
        deleteVoiceprint = {
            // Off the main thread (the file and the Keystore); a running session stops comparing with it at once.
            scope.launch(Dispatchers.IO) {
                VoiceprintFiles.delete(ctx)
                Cmd.send(ctx, Cmd.VOICEPRINT_DELETED)
                withContext(Dispatchers.Main) { update(settings.copy(meVoiceprint = false)); say("Your voiceprint was deleted.") }
            }
        },
        openUsageAccess = {
            val opened = SettingsIntents.of(ctx, SettingsPage.USAGE_ACCESS).any { i -> try { ctx.startActivity(i); true } catch (_: Exception) { false } }
            if (!opened) say("Open Settings › Apps › Special app access › Usage access, then Robot.")
        },
        runRoutine = { id ->
            if (snap.facts?.active != true) { page = Page.Setup(); say("Make Robot your digital assistant first: routines run in the assistant.") }
            else if (!activity.routine(RoutineIntents.ARG_ROUTINE, id)) say("Couldn't open the assistant.")
        },
        openReady = { key ->
            if (snap.facts?.active != true) { page = Page.Setup(); say("Make Robot your digital assistant first.") }
            else if (!activity.routine(RoutineIntents.ARG_READY, key)) say("Couldn't open the assistant.")
        },
    )

    /** Turning a people or calendar feature on (from Off) asks for what it needs, if it isn't allowed yet. */
    fun updateSettings(s: RobotSettings) {
        val f = snap.facts
        val turnedOn = ToolName.entries.filter { settings.approval(it) == Approval.OFF && s.approval(it) != Approval.OFF }
        // Texting turned back on (from Off) needs the contacts as much as the send_text level does.
        val textingOn = settings.textApp == TextApp.OFF && s.textApp != TextApp.OFF
        update(s)
        if (f != null && turnedOn.any { it.group == ToolGroup.CALENDAR } && !f.calendarGranted) actions.askCalendar()
        else if (f != null && (textingOn || turnedOn.any { it.group == ToolGroup.PEOPLE }) && !f.contactsGranted) actions.askContacts()
    }

    BackHandler(enabled = page != null || (tab != Tab.HOME && !(tab == Tab.NOTES && fromNotesIcon))) {
        if (page != null) nav.back() else tab = Tab.HOME
    }

    when (val p = page) {
        // Back goes up one page: to the one under it, or its parent in Settings (ui/settings PageStack).
        is Page.Setup -> SetupScreen(snap.facts, actions, snackbar, onBack = nav::back, peopleFirst = p.people, helpers = settings.helpers)
        is Page.Conversation -> ConversationScreen(history.firstOrNull { it.id == p.id }, actions, snackbar, settings.helperShowCost, onBack = nav::back)
        // A new note keeps the id its first save gave it; a save as the editor closes (Back, a route) never reopens it.
        is Page.NoteEdit -> NoteScreen(p.id, notesTick, actions, snackbar,
            onCreated = { id -> if (page == Page.NoteEdit(null)) nav.replace(Page.NoteEdit(id)) }, onBack = { nav.back(); notesTick++ })
        Page.WakePhrases -> WakePhrasesScreen(settings, ::updateSettings, actions, snackbar, onBack = nav::back)
        Page.WakeTest -> WakeTestScreen(settings, snap, onBack = nav::back)
        Page.VoiceLog -> VoiceLogScreen(settings, snackbar, onBack = nav::back)
        Page.AdbFixes -> AdbFixesScreen(actions, snackbar, onBack = nav::back)
        Page.People -> PeopleScreen(settings, ::updateSettings, snap.facts, actions, snackbar, onOpen = { page = Page.PersonEdit(it) }, onBack = nav::back)
        is Page.PersonEdit -> PersonScreen(p.id, actions, snackbar, onBack = nav::back)
        Page.Safety -> SafetyScreen(settings, ::updateSettings, actions, snackbar, onBack = nav::back)
        Page.Licences -> LicencesScreen(onOpen = { page = it }, onBack = nav::back)
        // Back from a notice goes to its part's licence, from a licence to the list.
        is Page.Licence -> LicenceScreen(p.id, p.file, onOpen = { page = it }, onBack = nav::back)
        is Page.Settings -> SettingsNav(p.route, settings, snap, calendars, ::updateSettings, actions, snackbar, onBack = nav::back, busy = busy)
        Page.Transcripts -> TranscriptsScreen(snackbar, onOpen = { id, line -> page = Page.Transcript(id, line) }, onBack = nav::back)
        is Page.Transcript -> TranscriptScreen(p.id, p.line, snackbar, onBack = nav::back)
        Page.ConversationSettings -> ConversationSettingsScreen(settings, snap, ::updateSettings, actions, snackbar, onBack = nav::back)
        Page.Helpers -> HelpersScreen(settings, snap, ::updateSettings, actions, snackbar, onBack = nav::back)
        is Page.Helper -> HelperScreen(p.id, settings, snap, ::updateSettings, actions, snackbar, onBack = nav::back)
        Page.AddHelper -> AddHelperScreen(settings, ::updateSettings, actions, snackbar, onAdded = { nav.replace(Page.Helper(it)) }, onBack = nav::back)
        null -> Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    for (t in Tab.entries) {
                        NavigationBarItem(
                            selected = tab == t, onClick = { tab = t; fromNotesIcon = false },
                            icon = { Icon(when (t) { Tab.HOME -> RIcons.Home; Tab.NOTES -> RIcons.Note; Tab.HISTORY -> RIcons.History; Tab.MODEL -> RIcons.Chip; Tab.SETTINGS -> RIcons.Settings }, null) },
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            Column(Modifier.padding(pad).consumeWindowInsets(pad)) {
                // Once, after updating to 0.4.0: what changed about cards, and where to choose (core SafetyNotice).
                if (settings.safetyNotice && (tab == Tab.HOME || tab == Tab.SETTINGS)) {
                    SafetyNoticeLine(
                        onOpen = { updateSettings(settings.copy(safetyNotice = false)); page = Page.Safety },
                        onDismiss = { updateSettings(settings.copy(safetyNotice = false)) },
                    )
                }
                val m = Modifier.weight(1f)
                when (tab) {
                    Tab.HOME -> HomeScreen(snap, history.take(3), actions, m)
                    Tab.NOTES -> NotesScreen(notes, actions, m)
                    Tab.HISTORY -> HistoryScreen(history, actions, m)
                    Tab.MODEL -> ModelScreen(settings, ::updateSettings, snap, busy, actions, m)
                    Tab.SETTINGS -> SettingsRoot(settings, snap, actions, m)
                }
            }
        }
    }
}

/** What a finished check of [m] means, in a line. */
private fun checkedText(m: BrainModel, ok: Boolean): String = when {
    m != BrainModel.OWN -> if (ok) "“${m.title}” is ready." else "The file is damaged. Delete it and download again."
    ok -> "Your model file is checked. See what Robot found in Model."
    else -> "Robot can't use that file. Model says why."
}

/** What the screens can ask for. */
class AppActions(
    val talk: (String?) -> Unit,
    val openSetup: () -> Unit,
    val openTab: (Tab) -> Unit,
    val openConversation: (String) -> Unit,
    val askPermissions: () -> Unit,
    val openAssistantSettings: () -> Unit,
    /** Settings › Model (0.4.1): each model's download, import, check and delete. */
    val download: (BrainModel) -> Unit,
    val cancelDownload: (BrainModel) -> Unit,
    val import: (BrainModel) -> Unit,
    val verify: (BrainModel) -> Unit,
    val deleteModel: (BrainModel) -> Unit,
    /** Your own file is a Tensor G5 build: run it on the TPU (after its warning card), or stop. */
    val allowOwnTpu: (allow: Boolean, sha256: String?) -> Unit = { _, _ -> },
    val deleteConversation: (String) -> Unit,
    val deleteAllConversations: () -> Unit,
    /** Settings › Safety & privacy: every kept thumbnail and screen text out of the saved conversations. */
    val removeSavedMedia: () -> Unit = {},
    val speakTest: () -> Unit,
    val say: (String) -> Unit,
    val voiceDownload: (VoiceBundle) -> Unit,
    val voiceCancel: (VoiceBundle) -> Unit,
    val voiceDelete: (VoiceBundle) -> Unit,
    val voiceBench: () -> Unit,
    val voiceReset: () -> Unit,
    val askContacts: () -> Unit,
    val askCalendar: () -> Unit,
    val openAppSettings: () -> Unit,
    val openNote: (String?) -> Unit,
    /**
     * Helpers (0.6.0): a helper's connection window, in the assistant's process (its key is pasted and Test connection
     * runs there, never through this one; [HelperWindow] modes); its key deleted here; the helper removed with its files.
     */
    val helperWindow: (id: String, mode: String) -> Unit = { _, _ -> },
    /** A helper's address changed (already saved): its key goes first when [dropKey] (another host), then Test connection opens. */
    val helperMoved: (id: String, dropKey: Boolean) -> Unit = { _, _ -> },
    val deleteHelperKey: (String) -> Unit = {},
    val removeHelper: (String) -> Unit = {},
    val openPage: (Page) -> Unit,
    val bargeTest: (BargeRun) -> Unit,
    /** 0.4.0: approximate location for the weather (asked here only), and Android's Usage access page for Robot. */
    val askLocation: () -> Unit = {},
    val openUsageAccess: () -> Unit = {},
    /** 0.4.0: photos and videos (Android offers all of them or the ones you pick). */
    val askMedia: () -> Unit = {},
    /** 0.5.0: the transcript screen (voice process), to start transcribing; Settings › Voice › Transcription check. */
    val transcribe: () -> Unit = {},
    /** 0.5.0: the interpreter (face to face, spoken translations). */
    val interpret: () -> Unit = {},
    val openTranscriptionCheck: () -> Unit = {},
    /** 0.5.0: the fast translation engine's files (Settings › Conversation › Fast translation). */
    val mtDownload: () -> Unit = {},
    val mtCancel: () -> Unit = {},
    val mtDelete: () -> Unit = {},
    /** 0.5.0, phase 5.3: "Your voice" (voice process), and deleting the voiceprint (file and key). */
    val openVoiceprint: () -> Unit = {},
    val deleteVoiceprint: () -> Unit = {},
    /** 0.8: the panel opens to run a routine (Run now), or with a Ready card (its key). */
    val runRoutine: (String) -> Unit = {},
    val openReady: (String) -> Unit = {},
)
