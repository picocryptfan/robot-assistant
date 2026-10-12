package app.heyrobot.ui.settings

import android.content.Intent
import android.net.Uri
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.core.feedback.FeedbackDiagnostics
import app.heyrobot.core.update.FoundRelease
import app.heyrobot.core.update.GitHubRepo
import app.heyrobot.core.update.GitHubToken
import app.heyrobot.core.update.UpdateDecision
import app.heyrobot.core.update.UpdateSettings
import app.heyrobot.core.update.UpdateText
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.Mark
import app.heyrobot.ui.Page
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.TextPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.RobotTokens
import app.heyrobot.update.UpdateInstaller
import app.heyrobot.update.UpdateStore
import app.heyrobot.update.UpdateToken
import app.heyrobot.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › About › Updates: this version, the last check and "Check now", the release on offer with its notes
// (plain text) and Download, Install and Skip, the switches, and in Advanced the repository and the access token for a
// private one. Built from the settings building blocks (ui/pref). [UpdatesScreen] only shows [UpdatesUi] and reports taps,
// so the UI tests drive it without a network; [UpdatesRoute] wires it to the updater.

/** Everything the Updates page shows. */
data class UpdatesUi(
    val installed: String,
    val settings: UpdateSettings,
    /** The repository asked, and Robot's own (Settings › About › Licences' source). */
    val repo: GitHubRepo?,
    val defaultRepo: GitHubRepo?,
    /** "Last checked today at 14:05. You're up to date." */
    val lastCheck: String,
    /** The release offered, if any (another repository's or one installed meanwhile never is). */
    val offer: FoundRelease? = null,
    /** It is downloaded and passed its checks: Install. */
    val ready: Boolean = false,
    /** Why its file was refused before, if it was. */
    val refused: String? = null,
    val work: Updater.Work? = null,
    /** What to tell you about what you last asked for. */
    val message: String? = null,
    /** Settings › Apps › Special app access › Install unknown apps › Robot is on. */
    val canInstall: Boolean = false,
    /** Another app store keeps Robot's updates (Android 14): Android asks every time. */
    val otherOwner: String? = null,
    /** The access token, masked ("github_pat_…AB12"), if one is saved. */
    val token: String? = null,
    val tokenUnreadable: Boolean = false,
    /** 0.7: the last check, a download's file or an install went wrong: "Report this" (Send feedback with the updater's codes). */
    val failed: Boolean = false,
)

/** What the page's taps do. */
class UpdatesActions(
    val check: () -> Unit = {},
    val download: () -> Unit = {},
    val stop: () -> Unit = {},
    val install: () -> Unit = {},
    val skip: () -> Unit = {},
    val unskip: () -> Unit = {},
    val openGitHub: () -> Unit = {},
    val allowInstalls: () -> Unit = {},
    val change: (UpdateSettings) -> Unit = {},
    val saveToken: (String) -> Unit = {},
    val removeToken: () -> Unit = {},
    val report: () -> Unit = {},
)

const val UPDATES_SENT = "To check, Robot asks GitHub (api.github.com) for the repository's newest releases, and it downloads an update " +
    "from GitHub's servers. GitHub sees your IP address and Robot's version in the request; nothing else is sent. An access token, " +
    "if you add one, goes to api.github.com only."

const val UPDATES_CHECKED = "Before installing, Robot checks the file's SHA-256 against the release's SHA256SUMS, then that it is Robot, " +
    "the release's version, newer than this one, and signed with Robot's key. Android's own window then asks you to confirm."

@Composable
fun UpdatesScreen(ui: UpdatesUi, a: UpdatesActions, onBack: () -> Unit) {
    var tokenOpen by rememberSaveable { mutableStateOf(false) }
    var removeOpen by rememberSaveable { mutableStateOf(false) }
    val s = ui.settings
    val idle = ui.work == null
    PreferenceScreen("Updates", onBack) {
        preferenceGroup(null, "version") {
            PreferenceItem("Robot ${ui.installed}", "The version on this phone", RIcons.Info)
            PreferenceItem(
                if (ui.work == Updater.Work.Checking) "Checking…" else "Check now",
                ui.lastCheck,
                RIcons.Refresh,
                enabled = idle && ui.repo != null,
                onClick = a.check,
            )
            ui.message?.let { StatusPreference("Update", it, Mark.WARN) }
            if (ui.failed || ui.message != null || ui.refused != null) {
                PreferenceItem("Report this", "Send the developer a bug report with the updater's codes: you choose the app it goes with", RIcons.Message,
                    onClick = a.report)
            }
        }
        ui.offer?.let { offer ->
            preferenceGroup(if (offer.testBuild) "Test build ${offer.version}" else "Robot ${offer.version}", "release") {
                ReleaseRow(ui, offer, a)
            }
            if (!ui.canInstall) {
                preferenceGroup(null, "allow") {
                    StatusPreference(
                        "Allow Robot to install updates",
                        "Android asks you to allow this once, in Settings › Apps › Special app access › Install unknown apps › Robot. " +
                            "Robot installs only its own updates.",
                        Mark.TODO,
                        action = { TextButton(onClick = a.allowInstalls, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open") } },
                    )
                }
            }
        }
        preferenceGroup("Automatic", "auto") {
            SwitchPreference("Check for updates automatically", if (s.autoCheck) "Once a day, in the background" else "Only when you tap Check now", s.autoCheck) {
                a.change(s.copy(autoCheck = it))
            }
            SwitchPreference("Download updates on Wi-Fi", "Downloads and checks an update on Wi-Fi, then tells you it's ready to install",
                s.wifiDownload, enabled = s.autoCheck) { a.change(s.copy(wifiDownload = it)) }
            SwitchPreference("Include test builds", "Pre-releases, to try new things early. They may have problems.", s.testBuilds) {
                a.change(s.copy(testBuilds = it))
            }
            SwitchPreference(
                "Install updates without asking",
                if (ui.otherOwner != null) "Android asks anyway: another app store keeps Robot's updates"
                else "Android installs Robot's own updates without its confirmation window. It may still ask, for example when another app store keeps Robot's updates.",
                s.installWithoutAsking && ui.otherOwner == null,
                enabled = ui.otherOwner == null,
            ) { a.change(s.copy(installWithoutAsking = it)) }
        }
        preferenceGroup("Advanced", "advanced") {
            val own = ui.repo != null && ui.repo == ui.defaultRepo
            TextPreference(
                "Repository",
                ui.repo?.slug.orEmpty(),
                emptySummary = "None",
                fieldLabel = "owner/name",
                supporting = "A fork's GitHub repository, as owner/name or its link. Leave it empty for Robot's own.",
                icon = RIcons.Folder,
                enabled = idle,
                validate = { t -> if (t.isBlank() || GitHubRepo.parse(t) != null) null else "Not a GitHub repository (owner/name)" },
            ) { t ->
                val r = GitHubRepo.parse(t)
                a.change(s.copy(repo = r?.takeIf { it != ui.defaultRepo }?.slug, skipped = null))
            }
            if (!own && ui.defaultRepo != null) {
                PreferenceItem("Use Robot's own repository", ui.defaultRepo.slug, RIcons.Refresh, enabled = idle,
                    onClick = { a.change(s.copy(repo = null, skipped = null)) })
            }
            PreferenceItem(
                "Access token",
                when {
                    ui.token != null -> ui.token
                    ui.tokenUnreadable -> "The saved token couldn't be read on this phone and was removed. Add it again."
                    else -> "None. Only needed while the repository is private."
                },
                RIcons.Lock,
                onClick = { tokenOpen = true },
            )
            if (ui.token != null) {
                PreferenceItem("Remove the access token", "Then only a public repository can be checked", RIcons.Delete, onClick = { removeOpen = true })
            }
        }
        preferenceFooter("What is sent: $UPDATES_SENT\n\nWhat is checked: $UPDATES_CHECKED")
    }
    if (tokenOpen) TokenDialog(onDismiss = { tokenOpen = false }, onSave = { tokenOpen = false; a.saveToken(it) })
    if (removeOpen) {
        ConfirmDialog("Remove the access token?", "Robot can then check only a public repository.", "Remove",
            onDismiss = { removeOpen = false }, onConfirm = { removeOpen = false; a.removeToken() })
    }
}

/** The release on offer: its date and size, notes, and what you can do now. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReleaseRow(ui: UpdatesUi, offer: FoundRelease, a: UpdatesActions) {
    val c = MaterialTheme.colorScheme
    val skipped = ui.settings.skipped?.let { app.heyrobot.core.update.RobotVersion.parse(it) } == offer.parsedVersion
    Column(
        Modifier.fillMaxWidth()
            .clip(if (RobotTokens.groupedSettings) RoundedCornerShape(RobotTokens.itemCorner) else RectangleShape)
            .background(if (RobotTokens.groupedSettings) c.surfaceBright else Color.Transparent)
            .padding(horizontal = RobotTokens.prefPaddingH, vertical = RobotTokens.prefPaddingV),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val facts = listOfNotNull(UpdateText.published(offer.publishedAt), UpdateText.size(offer.apk.size), if (offer.testBuild) "test build" else null)
        Text(facts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
        if (offer.notes.isNotEmpty()) Text(offer.notes, style = MaterialTheme.typography.bodyMedium)
        ui.refused?.let { Text("Robot refused this release's file before: $it", style = MaterialTheme.typography.bodyMedium, color = c.error) }
        when (val w = ui.work) {
            is Updater.Work.Downloading -> {
                val pct = if (w.total > 0) (w.done * 100 / w.total).toInt() else 0
                val label = if (w.total > 0) "Downloading… $pct % (${UpdateText.size(w.done)} of ${UpdateText.size(w.total)})" else "Downloading…"
                Text(label, style = MaterialTheme.typography.bodyMedium)
                if (w.total > 0) LinearProgressIndicator(progress = { w.done.toFloat() / w.total }, modifier = Modifier.fillMaxWidth().semantics { stateDescription = "$pct %" })
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                TextButton(onClick = a.stop, modifier = Modifier.heightIn(min = 48.dp)) { Text("Stop") }
            }
            Updater.Work.Verifying -> {
                Text("Checking the file…", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            Updater.Work.Installing -> Text("Installing… Android may ask you to confirm.", style = MaterialTheme.typography.bodyMedium)
            else -> {
                if (skipped) Text("You skipped this version: Robot won't tell you about it.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                if (ui.ready) Text("Downloaded and checked.", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val enabled = ui.work == null
                    if (ui.ready) Button(onClick = a.install, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Install") }
                    else Button(onClick = a.download, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download") }
                    if (skipped) OutlinedButton(onClick = a.unskip, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Don't skip") }
                    else OutlinedButton(onClick = a.skip, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) { Text("Skip this version") }
                }
            }
        }
        TextButton(onClick = a.openGitHub, modifier = Modifier.heightIn(min = 48.dp)) { Text("See it on GitHub") }
    }
}

/**
 * The access token's window: it can't be captured (screenshots, recordings, the recent-apps thumbnail), nothing in it is
 * offered to autofill, and the token is never kept in the page's saved state.
 */
@Composable
private fun TokenDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val ok = GitHubToken.plausible(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        title = { Text("Access token") },
        text = {
            val view = LocalView.current
            DisposableEffect(view) {
                view.rootView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                onDispose {}
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Only for a private repository: a fine-grained token that can read its contents, or a classic one with the repo scope. " +
                    "Robot encrypts it on this phone and sends it to api.github.com only.", style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.trim() },
                    singleLine = true,
                    label = { Text("Token") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    isError = text.isNotEmpty() && !ok,
                    supportingText = { if (text.isNotEmpty() && !ok) Text("That doesn't look like a GitHub token (github_pat_ or ghp_ followed by letters, digits and _).") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { val t = text; text = ""; onSave(t) }, enabled = ok) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The page, wired to the updater (SettingsNav's "updates" route). */
@Composable
internal fun UpdatesRoute(snackbar: SnackbarHostState, onBack: () -> Unit, onReport: () -> Unit = {}) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    UpdateStore.settings(app)
    val settings by UpdateStore.settings
    val state by UpdateStore.state
    val work by Updater.work
    val message by Updater.message
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lstate by lifecycle.currentStateFlow.collectAsState()
    var facts by remember { mutableStateOf(RouteFacts()) }
    // Read again when the page comes back (from Android's install access page) and after each change.
    LaunchedEffect(lstate.isAtLeast(Lifecycle.State.RESUMED), state, settings, work) {
        facts = withContext(Dispatchers.IO) {
            RouteFacts(
                repo = UpdateStore.repo(app), defaultRepo = UpdateStore.defaultRepo(app), ready = Updater.isReady(app),
                canInstall = UpdateInstaller.allowed(app), otherOwner = UpdateInstaller.otherUpdateOwner(app),
                token = UpdateToken.masked(app), tokenUnreadable = UpdateToken.unreadable(app), loaded = true,
            )
        }
    }
    val installed = Updater.installed()
    val offer = UpdateDecision.offered(state, facts.repo, installed).takeIf { facts.loaded }
    val ui = UpdatesUi(
        installed = installed.text,
        settings = settings,
        repo = facts.repo,
        defaultRepo = facts.defaultRepo,
        lastCheck = UpdateText.lastCheck(state, System.currentTimeMillis()),
        offer = offer,
        ready = facts.ready,
        refused = state.refusedWhy?.takeIf { offer != null && state.refusedApk == offer.apk.id }?.let { UpdateText.fail(it) },
        work = work,
        message = message,
        canInstall = facts.canInstall,
        otherOwner = facts.otherOwner,
        token = facts.token,
        tokenUnreadable = facts.tokenUnreadable,
        failed = FeedbackDiagnostics.updaterFailed(state),
    )
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }
    UpdatesScreen(ui, UpdatesActions(
        check = { Updater.checkNow(app) },
        download = { Updater.downloadNow(app) },
        stop = { Updater.cancel() },
        install = {
            if (!UpdateInstaller.allowed(app)) {
                try { ctx.startActivity(UpdateInstaller.allowIntent(app)) } catch (_: Exception) { say("Open Settings › Apps › Special app access › Install unknown apps › Robot.") }
            } else Updater.installNow(app)
        },
        skip = { offer?.let { Updater.skip(app, it.version) } },
        unskip = { Updater.unskip(app) },
        openGitHub = {
            val r = facts.repo
            if (offer != null && r != null) try {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(r.releasePage(offer.tag))).addCategory(Intent.CATEGORY_BROWSABLE))
            } catch (_: Exception) { say("No browser to open it.") }
        },
        allowInstalls = {
            try { ctx.startActivity(UpdateInstaller.allowIntent(app)) } catch (_: Exception) { say("Open Settings › Apps › Special app access › Install unknown apps › Robot.") }
        },
        change = { Updater.setSettings(app, it) },
        saveToken = { t ->
            scope.launch {
                val saved = withContext(Dispatchers.IO) { UpdateToken.save(app, t) }
                if (saved) UpdateToken.clearClipboard(ctx, t)
                facts = facts.copy(token = withContext(Dispatchers.IO) { UpdateToken.masked(app) }, tokenUnreadable = false)
                say(if (saved) "The token is saved." else "The token couldn't be saved on this phone.")
            }
        },
        removeToken = {
            scope.launch {
                withContext(Dispatchers.IO) { UpdateToken.delete(app) }
                facts = facts.copy(token = null, tokenUnreadable = false)
                say("The token was removed.")
            }
        },
        report = onReport,
    ), onBack)
}

/** What the page reads from files and Android, off the main thread. */
private data class RouteFacts(
    val repo: GitHubRepo? = null,
    val defaultRepo: GitHubRepo? = null,
    val ready: Boolean = false,
    val canInstall: Boolean = false,
    val otherOwner: String? = null,
    val token: String? = null,
    val tokenUnreadable: Boolean = false,
    val loaded: Boolean = false,
)

/** The Updates row's state in Settings › About: an update on offer, or when Robot last checked. */
fun updatesSummary(s: UpdateSettings, offer: FoundRelease?, lastCheck: Long, now: Long): String = when {
    offer != null && s.skipped != offer.version -> "Robot ${offer.version} is available"
    !s.autoCheck -> if (lastCheck > 0) "Checked " + UpdateText.whenText(lastCheck, now).replaceFirstChar { it.lowercase() } + " · automatic checks off" else "Automatic checks are off"
    lastCheck > 0 -> "Checked " + UpdateText.whenText(lastCheck, now).replaceFirstChar { it.lowercase() }
    else -> "Checks once a day"
}

/** The way into Updates from Settings › About: a row of its group, its state under the title. */
@Composable
internal fun UpdatesEntry(a: AppActions) {
    val app = LocalContext.current.applicationContext
    val state by UpdateStore.state
    var summary by remember { mutableStateOf("") }
    LaunchedEffect(state) {
        summary = withContext(Dispatchers.IO) {
            val s = UpdateStore.settings(app)
            val st = UpdateStore.state(app)
            updatesSummary(s, UpdateDecision.offered(st, UpdateStore.repo(app), Updater.installed()), st.lastCheck, System.currentTimeMillis())
        }
    }
    PreferenceItem("Updates", summary.ifEmpty { null }, onClick = { a.openPage(Page.Settings(SettingsRoutes.UPDATES)) })
}
