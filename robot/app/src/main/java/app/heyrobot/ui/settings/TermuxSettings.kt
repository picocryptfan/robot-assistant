package app.heyrobot.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.termux.ApiCommands
import app.heyrobot.core.termux.CommandLevel
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.SetupMark
import app.heyrobot.core.termux.SetupStep
import app.heyrobot.core.termux.SpokenYes
import app.heyrobot.core.termux.TERMUX_LIMITS
import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetup
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.core.termux.TermuxSource
import app.heyrobot.core.termux.TermuxStatus
import app.heyrobot.core.termux.limitLabel
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.TermuxFacts
import app.heyrobot.sys.TermuxStore
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Termux (0.9, docs/plans/websearch-termux-design.md B5.1, B6): the set-up checklist (Termux from
// F-Droid, opened once, Robot's permission asked from here only, allow-external-apps, the Test, your scripts folder,
// trusting this Termux's signer) and, once every line is green, the switch that lets Robot use it and its settings, kept
// in files/robot/termux.json. Off by default. The Test and the folder runs happen in the :voice process (Cmd.TERMUX);
// the page reads what they found from termux_status.json.

/** What the page shows (TermuxRoute reads it; a test passes its own). */
data class TermuxUi(
    val settings: TermuxSettings = TermuxSettings(),
    val check: TermuxCheck = TermuxCheck(TermuxStatus.NOT_INSTALLED, null, null, null),
    val granted: Boolean = false,
    val status: TermuxSetupStatus = TermuxSetupStatus(),
    /** Other apps Robot can see holding Termux's permission. */
    val others: List<String> = emptyList(),
) {
    val ready: Boolean get() = TermuxSetup.ready(check, granted, status, settings.trustedSigner)
}

/** What the page's buttons do. */
class TermuxPageActions(
    val save: (TermuxSettings) -> Unit = {},
    val askPermission: () -> Unit = {},
    val openFdroid: () -> Unit = {},
    val openAppInfo: () -> Unit = {},
    val copyLine: () -> Unit = {},
    /** TermuxTool's set-up runs in :voice: "test", "folder", "list". */
    val run: (String) -> Unit = {},
)

private fun mark(m: SetupMark): Mark = when (m) {
    SetupMark.OK -> Mark.OK
    SetupMark.TODO -> Mark.TODO
    SetupMark.WARN -> Mark.WARN
}

/** "Off", "On · 2 scripts", "On · commands only" (Settings' row). */
internal fun termuxSummary(t: TermuxSettings, scripts: Int): String = when {
    !t.enabled -> "Off"
    scripts > 0 -> "On · " + if (scripts == 1) "1 script" else "$scripts scripts"
    t.commands == CommandLevel.OFF -> "On · no scripts, commands off"
    else -> "On · commands ask first"
}

@Composable
fun TermuxSettingsScreen(ui: TermuxUi, act: TermuxPageActions, onBack: () -> Unit, snackbar: SnackbarHostState? = null) {
    val s = ui.settings
    val st = ui.status
    val lines = TermuxSetup.lines(ui.check, ui.granted, st, s.trustedSigner)
    val installed = ui.check.status != TermuxStatus.NOT_INSTALLED
    val canRun = installed && ui.check.status != TermuxStatus.NOT_SUPPORTED && ui.granted && st.running == null
    // The commands each set-up run shows before its first run (B5.1: "a card the first time").
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var trust by rememberSaveable { mutableStateOf(false) }
    PreferenceScreen("Termux", onBack, snackbar = snackbar) {
        item(key = "switch") {
            MainSwitchBar("Let Robot use Termux", s.enabled, enabled = s.enabled || ui.ready) { act.save(s.copy(enabled = it)) }
        }
        item(key = "intro") {
            IntroPreference(if (ui.ready || s.enabled) "Robot runs your own scripts in ~/robot and short commands in Termux. Each shows a card first unless you choose " +
                "otherwise below; a command with a warning always needs a tap."
            else "Set up Termux below first: nothing runs until every line is green. Termux runs on this phone; Robot sends it nothing else.")
        }
        preferenceGroup("Set-up", "setup") {
            for (l in lines) when (l.step) {
                SetupStep.INSTALLED -> StatusPreference(l.step.title, l.text, mark(l.mark), action = if (ui.check.usable && l.mark == SetupMark.OK) null else ({
                    FilledTonalButton(onClick = act.openFdroid, modifier = Modifier.semantics { contentDescription = "Get Termux on F-Droid" }) { Text("F-Droid") }
                }))
                SetupStep.PERMISSION -> {
                    StatusPreference(l.step.title, l.text, mark(l.mark), action = if (ui.granted || !installed) null else ({
                        FilledTonalButton(onClick = act.askPermission, modifier = Modifier.semantics { contentDescription = "Allow Robot to run commands in Termux" }) { Text("Allow") }
                    }))
                    if (!ui.granted && installed) PreferenceItem("Android doesn't ask?", "App info › Permissions › Additional permissions", RIcons.Settings, onClick = act.openAppInfo)
                }
                SetupStep.EXTERNAL_APPS -> {
                    StatusPreference(l.step.title, l.text, mark(l.mark), action = if (l.mark == SetupMark.OK) null else ({
                        FilledTonalButton(onClick = act.copyLine, modifier = Modifier.semantics { contentDescription = "Copy the line to paste in Termux" }) { Text("Copy") }
                    }))
                    if (l.mark != SetupMark.OK) PreferenceBlock {
                        Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                            Text(TermuxSetup.ALLOW_LINE, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                        }
                        Text(TermuxSetup.ALLOW_WARNING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SetupStep.TEST -> StatusPreference(l.step.title, l.text, mark(l.mark), action = if (!canRun) null else ({
                    FilledTonalButton(onClick = { act.run("test") }, modifier = Modifier.semantics { contentDescription = "Test Termux" }) { Text(if (st.testAt > 0) "Again" else "Test") }
                }))
                SetupStep.FOLDER -> StatusPreference(l.step.title, l.text, mark(l.mark), action = if (!canRun || !st.testOk) null else ({
                    val make = st.folderMissing
                    FilledTonalButton(onClick = { confirm = if (make) "folder" else "list" },
                        modifier = Modifier.semantics { contentDescription = if (make) "Create ~/robot" else "Refresh the list of your scripts" }) {
                        Text(if (make) "Create it" else "Refresh")
                    }
                }))
                SetupStep.TRUST -> StatusPreference(l.step.title, l.text, mark(l.mark), action = if (ui.check.signer == null || l.mark == SetupMark.OK) null else ({
                    FilledTonalButton(onClick = { trust = true }, modifier = Modifier.semantics { contentDescription = "Trust this Termux" }) {
                        Text(if (s.trustedSigner != null) "Trust again" else "Trust")
                    }
                }))
                else -> StatusPreference(l.step.title, l.text, mark(l.mark))
            }
        }
        if (st.scripts.isNotEmpty()) preferenceGroup("Your scripts", "scripts") {
            for (sc in st.scripts) {
                val level = s.script(sc.name)
                ListPreference(sc.name, level, ScriptLevel.entries, { it.en }, ::scriptDetail, RIcons.Terminal,
                    summary = level.en + (sc.comment?.let { " · $it" } ?: "")) { lvl -> act.save(s.copy(scripts = s.scripts + (sc.name to lvl))) }
            }
        }
        if (st.scripts.isNotEmpty()) preferenceGroup("Works on the lock screen", "locked") {
            for (sc in st.scripts.filter { s.script(it.name) != ScriptLevel.OFF }) {
                SwitchPreference(sc.name, if (sc.name in s.lockScreenScripts) "Runs while the phone is locked" else "Unlock first", sc.name in s.lockScreenScripts) { on ->
                    act.save(s.copy(lockScreenScripts = if (on) s.lockScreenScripts + sc.name else s.lockScreenScripts - sc.name))
                }
            }
            Text("Commands Robot writes never run on the lock screen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        preferenceGroup("Commands Robot writes", "commands") {
            ListPreference("Commands Robot writes", s.commands, CommandLevel.entries, { it.en },
                { if (it == CommandLevel.ASK) "Gemma may write a short command; you see its card and tap Run" else "Only your own scripts run" }, RIcons.Terminal) {
                act.save(s.copy(commands = it))
            }
            SwitchPreference("Read-only commands without a card",
                "Only commands on Robot's read-only list (ls, du, df, git status…), and never in a conversation with content Robot didn't write",
                s.readOnlyWithoutCard, enabled = s.commands != CommandLevel.OFF) { act.save(s.copy(readOnlyWithoutCard = it)) }
            ListPreference("A spoken yes runs a command", s.spokenYes, SpokenYes.entries, { it.en },
                { if (it == SpokenYes.WITHOUT_WARNINGS) "A command with a warning always needs a tap" else "Every command needs a tap on Run" }) {
                act.save(s.copy(spokenYes = it))
            }
            ListPreference("Termux:API commands", s.api, ApiCommands.entries, { it.en },
                { if (it == ApiCommands.WITH_CARD) "Sending a text or placing a call always needs a tap, with a red line" else "termux-sms-send, termux-location and the rest are refused" }) {
                act.save(s.copy(api = it))
            }
        }
        preferenceGroup("Each run", "runs") {
            ListPreference("Time limit", s.limitSeconds, TERMUX_LIMITS, ::limitLabel, { if (it == TermuxSettings.DEFAULT_LIMIT_S) "The default" else null }, RIcons.Timer) {
                act.save(s.copy(limitSeconds = it))
            }
            ListPreference("Output Robot reads", s.outputLines, listOf(20, 40, 80), { "$it lines" }, { if (it == TermuxSettings.DEFAULT_LINES) "The default (about 430 tokens)" else null }) {
                act.save(s.copy(outputLines = it))
            }
            SwitchPreference("Hide secrets in output", "Private keys, API keys, tokens and passwords become “[hidden: …]” before Gemma or the card sees them",
                s.hideSecrets) { act.save(s.copy(hideSecrets = it)) }
            SwitchPreference("Load my shell profile", if (s.loadProfile) "bash -l: your ~/.bash_profile runs first" else "Off: bash without your profile, predictable",
                s.loadProfile) { act.save(s.copy(loadProfile = it)) }
        }
        preferenceGroup("Other apps", "others") {
            PreferenceItem("Apps that can run commands in Termux", if (ui.others.isEmpty()) "None other than Robot that Robot can see"
                else ui.others.joinToString(", "), RIcons.Apps)
        }
        preferenceFooter(
            "Every command shows its card with Run, Edit and Cancel, except your own scripts set to Just do it and, if you turn it on, " +
                "commands on Robot's read-only list. Robot never has a setting that runs anything Gemma writes. In a conversation with screen " +
                "text, a photo, a web page or anything else Robot didn't write, every command needs a tap.\n\n" +
                "Each run has a time limit, and Stop ends it with everything it started. Robot reads at most ${s.outputLines} lines of the " +
                "output, with secrets hidden. History keeps “Ran a command in Termux (exit 0)”, never the command or its output.\n\n" +
                "Robot sends nothing anywhere: a command that goes online does so from Termux, under Termux's own Network permission. " +
                "Termux gets the label “Robot”, never the command, for its logs. Other apps Robot can't see may also hold Termux's " +
                "permission: Android doesn't show Robot every app.",
        )
    }
    confirm?.let { what ->
        ConfirmDialog(
            if (what == "folder") "Create ~/robot?" else "List your scripts?",
            "Robot runs this in Termux:\n\n" + (if (what == "folder") ScriptRegistry.MAKE_FOLDER + "\n" else "") + ScriptRegistry.LIST_SHOWN,
            "Run", onDismiss = { confirm = null }, onConfirm = { confirm = null; act.run(what) },
        )
    }
    if (trust) {
        val src = ui.check.source
        ConfirmDialog("Trust this Termux?",
            ui.check.line() + "\n\nSigner " + (ui.check.signer?.let(TermuxSetup::short) ?: "unknown") + ". Robot checks it before every run and stops if " +
                "Termux is replaced." + when (src) {
                TermuxSource.GITHUB -> "\n\nGitHub builds share a public test key: anyone can make an update that installs over it."
                TermuxSource.UNKNOWN -> "\n\nRobot doesn't know this key: trust it only if you built or chose this Termux yourself."
                else -> ""
            },
            "Trust", onDismiss = { trust = false }, onConfirm = { trust = false; act.save(s.copy(trustedSigner = ui.check.signer)) })
    }
}

private fun scriptDetail(l: ScriptLevel): String = when (l) {
    ScriptLevel.AUTO -> "Runs when you name it, without a card (not after content Robot didn't write)"
    ScriptLevel.ASK -> "Its card first: Run or Cancel"
    ScriptLevel.OFF -> "Robot never runs it"
}

/** The page with what Android, termux.json and termux_status.json say now; read again on resume and while a set-up run goes. */
@Composable
internal fun TermuxRoute(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit, assistantActive: Boolean?) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    var ui by remember { mutableStateOf<TermuxUi?>(null) }
    var tick by remember { mutableStateOf(0) }
    suspend fun read(): TermuxUi = withContext(Dispatchers.IO) {
        val s = TermuxStore.load(app)
        TermuxUi(s, TermuxFacts.check(app, s), TermuxFacts.granted(app), TermuxStore.status(app), TermuxFacts.otherHolders(app))
    }
    LaunchedEffect(lstate.isAtLeast(Lifecycle.State.RESUMED), tick) {
        ui = read()
        // A set-up run under way: read again until it ends (it writes its end itself; a stale one is over).
        while (ui?.status?.running != null && System.currentTimeMillis() - (ui?.status?.runningAt ?: 0) < TermuxSetup.RUNNING_STALE_MS) {
            delay(500)
            ui = read()
        }
    }
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }
    val act = TermuxPageActions(
        save = { s ->
            TermuxStore.save(app, s)
            ui = ui?.copy(settings = s)
        },
        askPermission = a.askTermux,
        openFdroid = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxSetup.FDROID_URL))) } catch (_: Exception) { say("No browser to open F-Droid's page.") }
        },
        openAppInfo = a.openAppSettings,
        copyLine = {
            try {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Termux", TermuxSetup.ALLOW_LINE))
                say("Copied. Paste it in Termux and press Enter.")
            } catch (_: Exception) { say("Couldn't copy.") }
        },
        run = { what ->
            // The set-up runs happen in the assistant's process, where Termux's answer comes back.
            if (assistantActive != true) say("Make Robot your digital assistant first: Termux answers the assistant.")
            else Cmd.send(app, Cmd.TERMUX, Bundle().apply { putString(Cmd.EXTRA_RUN, what) })
            scope.launch {
                delay(300)
                tick++
            }
        },
    )
    val now = ui
    if (now == null) PreferenceScreen("Termux", onBack, snackbar = snackbar) { item(key = "checking") { IntroPreference("Checking…") } }
    else TermuxSettingsScreen(now, act, onBack, snackbar)
}

/** Assistant setup › Termux: one row with the agent's state, opening Settings › Termux (read off the main thread). */
@Composable
internal fun TermuxSetupRow(a: AppActions) {
    val ctx = LocalContext.current.applicationContext
    var state by remember { mutableStateOf<Pair<Boolean, Boolean>?>(null) }
    LaunchedEffect(Unit) {
        state = withContext(Dispatchers.IO) { TermuxStore.load(ctx).let { s -> s.enabled to TermuxFacts.ready(ctx, s) } }
    }
    val (on, ready) = state ?: return
    StatusPreference("Termux",
        when {
            on && ready -> "On: your scripts and short commands in Termux, each with its card unless you chose otherwise"
            on -> "On, but its set-up needs attention"
            else -> "Optional and off: your own scripts and short commands in Termux"
        },
        when { on && ready -> Mark.OK; on -> Mark.WARN; else -> Mark.TODO },
        onClick = { a.openPage(Page.Settings(SettingsRoutes.TERMUX)) })
}
