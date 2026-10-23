// SPDX-License-Identifier: GPL-3.0-only
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.VoiceEvent
import app.heyrobot.core.i18n.Phrase
import app.heyrobot.core.i18n.TermuxStrings
import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.termux.ApiCommands
import app.heyrobot.core.termux.CommandLevel
import app.heyrobot.core.termux.ScriptLevel
import app.heyrobot.core.termux.ScriptRegistry
import app.heyrobot.core.termux.SetupMark
import app.heyrobot.core.termux.SetupStep
import app.heyrobot.core.termux.SpokenYes
import app.heyrobot.core.termux.TERMUX_LIMITS
import app.heyrobot.core.termux.TermuxCheck
import app.heyrobot.core.termux.TermuxLock
import app.heyrobot.core.termux.TermuxTrustOp
import app.heyrobot.core.termux.TermuxUpdateState
import app.heyrobot.core.termux.TermuxUpdateVerdict
import app.heyrobot.core.termux.TermuxUpdates
import app.heyrobot.core.termux.TermuxSettings
import app.heyrobot.core.termux.TermuxSetup
import app.heyrobot.core.termux.TermuxSetupStatus
import app.heyrobot.core.termux.TermuxSource
import app.heyrobot.core.termux.TermuxStatus
import app.heyrobot.core.termux.limitLabel
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.TermuxFacts
import app.heyrobot.sys.TermuxStore
import app.heyrobot.sys.TermuxUpdateStore
import app.heyrobot.sys.TermuxWords
import app.heyrobot.update.TermuxUpdateCheck
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
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Termux (0.9): the set-up checklist (Termux from
// F-Droid, opened once, Robot's permission asked from here only, allow-external-apps, the Test, your scripts folder,
// trusting this Termux's signer) and, once every line is green, the switch that lets Robot use it and its settings, kept
// in files/robot/termux.json. Off by default. The Test and the folder runs happen in the :voice process (Cmd.TERMUX);
// the page reads what they found from termux_status.json.
// 1.0: what changed about the installed Termux, above the checklist (the update lock's Trust this version, another
// signer's Set up again, the public test key's way to the F-Droid build and its "Run anyway"), and "Check for Termux
// updates" (TermuxUpdateCheck, once a week) with what Termux's developers and F-Droid offer. 1.1: the page's words are in
// strings_termux.xml, with their French; the checklist's lines, the choices' names and the row's summary are core's
// (TermuxStrings).

/** What the page shows (TermuxRoute reads it; a test passes its own). */
data class TermuxUi(
    val settings: TermuxSettings = TermuxSettings(),
    val check: TermuxCheck = TermuxCheck(TermuxStatus.NOT_INSTALLED, null, null, null),
    val granted: Boolean = false,
    val status: TermuxSetupStatus = TermuxSetupStatus(),
    /** Other apps Robot can see holding Termux's permission. */
    val others: List<String> = emptyList(),
    /** 1.0: what "Check for Termux updates" last found, and whether a check runs now. */
    val updates: TermuxUpdateState = TermuxUpdateState(),
    val checking: Boolean = false,
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
    /** 1.0: "Check now" (Termux's update check). */
    val checkUpdates: () -> Unit = {},
    /** 1.0: what you did about the trusted Termux, for the voice log (codes only). */
    val trustLog: (TermuxTrustOp) -> Unit = {},
)

/** 1.0: a date as Settings shows it ("23 May 2025"). */
internal fun termuxDate(t: Long): String = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(t))

private fun mark(m: SetupMark): Mark = when (m) {
    SetupMark.OK -> Mark.OK
    SetupMark.TODO -> Mark.TODO
    SetupMark.WARN -> Mark.WARN
}

/** "Off", "On · 2 scripts", "On · commands only" (Settings' row). */
internal fun termuxSummaryText(t: TermuxSettings, scripts: Int): Phrase = when {
    !t.enabled -> TermuxStrings.summaryOff()
    scripts > 0 -> TermuxStrings.summaryScripts(scripts)
    t.commands == CommandLevel.OFF -> TermuxStrings.summaryNoScripts()
    else -> TermuxStrings.summaryAskFirst()
}

@Composable
fun TermuxSettingsScreen(ui: TermuxUi, act: TermuxPageActions, onBack: () -> Unit, snackbar: SnackbarHostState? = null) {
    val s = ui.settings
    val st = ui.status
    val lines = TermuxSetup.lines(ui.check, ui.granted, st, s.trustedSigner)
    val installed = ui.check.status != TermuxStatus.NOT_INSTALLED
    // 1.0: nothing runs while the update lock, another signer or the test key holds it (the top of the page says why).
    val canRun = installed && ui.check.status != TermuxStatus.NOT_SUPPORTED && ui.granted && st.running == null && ui.check.lock == null
    // The commands each set-up run shows before its first run (B5.1: "a card the first time").
    var confirm by rememberSaveable { mutableStateOf<String?>(null) }
    var trust by rememberSaveable { mutableStateOf(false) }
    var trustVersion by rememberSaveable { mutableStateOf(false) }
    var setUpAgain by rememberSaveable { mutableStateOf(false) }
    var risk by rememberSaveable { mutableStateOf(false) }
    val ctx = LocalContext.current
    val setupTitle = stringResource(R.string.termux_setup_group)
    val scriptsTitle = stringResource(R.string.termux_scripts_group)
    val lockTitle = stringResource(R.string.termux_lock_group)
    val commandsTitle = stringResource(R.string.termux_commands_group)
    val runsTitle = stringResource(R.string.termux_runs_group)
    val othersTitle = stringResource(R.string.termux_others_group)
    val levelLabels = ScriptLevel.entries.associateWith { it.text.str() }
    val levelDetails = ScriptLevel.entries.associateWith { stringResource(scriptDetail(it)) }
    val commandLabels = CommandLevel.entries.associateWith { it.text.str() }
    val commandDetails = CommandLevel.entries.associateWith { stringResource(if (it == CommandLevel.ASK) R.string.termux_commands_ask else R.string.termux_commands_off) }
    val spokenLabels = SpokenYes.entries.associateWith { it.text.str() }
    val spokenDetails = SpokenYes.entries.associateWith {
        stringResource(if (it == SpokenYes.WITHOUT_WARNINGS) R.string.termux_spoken_without_warnings else R.string.termux_spoken_never)
    }
    val apiLabels = ApiCommands.entries.associateWith { it.text.str() }
    val apiDetails = ApiCommands.entries.associateWith { stringResource(if (it == ApiCommands.WITH_CARD) R.string.termux_api_with_card else R.string.termux_api_not_allowed) }
    val outputLabels = OUTPUT_LINES.associateWith { pluralStringResource(R.plurals.termux_lines, it, it) }
    val theDefault = stringResource(R.string.termux_default)
    val outputDefault = stringResource(R.string.termux_output_default)
    val footer = listOf(stringResource(R.string.termux_footer_cards), stringResource(R.string.termux_footer_runs, s.outputLines),
        stringResource(R.string.termux_footer_network)).joinToString("\n\n")
    PreferenceScreen(UiStrings.termux().str(), onBack, snackbar = snackbar) {
        item(key = "switch") {
            MainSwitchBar(stringResource(R.string.termux_use), s.enabled, enabled = s.enabled || ui.ready) { act.save(s.copy(enabled = it)) }
        }
        item(key = "intro") {
            IntroPreference(stringResource(if (ui.ready || s.enabled) R.string.termux_intro_ready else R.string.termux_intro_setup))
        }
        // 1.0: what changed, and what to do, before anything else.
        termuxLock(ui, ctx, onTrustVersion = { trustVersion = true }, onSetUpAgain = { setUpAgain = true }, onRisk = { on ->
            if (on) risk = true else { act.save(s.copy(testKeyRisk = false)); act.trustLog(TermuxTrustOp.RISK_OFF) }
        }, openFdroid = act.openFdroid)
        preferenceGroup(setupTitle, "setup") {
            for (l in lines) {
                val title = l.step.label().str()
                val text = l.phrase.str()
                when (l.step) {
                    SetupStep.INSTALLED -> StatusPreference(title, text, mark(l.mark),
                        action = if ((ui.check.usable && l.mark == SetupMark.OK) || ui.check.lock == TermuxLock.UPDATED) null else ({
                        val label = stringResource(R.string.termux_fdroid_label)
                        FilledTonalButton(onClick = act.openFdroid, modifier = Modifier.semantics { contentDescription = label }) {
                            Text(stringResource(R.string.termux_fdroid_button))
                        }
                    }))
                    SetupStep.PERMISSION -> {
                        StatusPreference(title, text, mark(l.mark), action = if (ui.granted || !installed) null else ({
                            val label = stringResource(R.string.termux_allow_label)
                            FilledTonalButton(onClick = act.askPermission, modifier = Modifier.semantics { contentDescription = label }) { Text(UiStrings.allow().str()) }
                        }))
                        if (!ui.granted && installed) PreferenceItem(stringResource(R.string.termux_android_doesnt_ask), AndroidPath.APP_ADDITIONAL_PERMISSIONS.render(LocalLangs.current.system),
                            RIcons.Settings, onClick = act.openAppInfo)
                    }
                    SetupStep.EXTERNAL_APPS -> {
                        StatusPreference(title, text, mark(l.mark), action = if (l.mark == SetupMark.OK) null else ({
                            val label = stringResource(R.string.termux_copy_label)
                            FilledTonalButton(onClick = act.copyLine, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.termux_copy)) }
                        }))
                        if (l.mark != SetupMark.OK) PreferenceBlock {
                            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
                                Text(TermuxSetup.ALLOW_LINE, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                            }
                            Text(TermuxSetup.allowWarning.str(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    SetupStep.TEST -> {
                        // 1.0: while a lock holds Termux, the Test says why it can't run, instead of a line about what it would run.
                        val lock = ui.check.lock
                        if (lock != null) StatusPreference(title, testBlocked(lock), Mark.TODO)
                        else StatusPreference(title, text, mark(l.mark), action = if (!canRun) null else ({
                            val label = stringResource(R.string.termux_test_label)
                            FilledTonalButton(onClick = { act.run("test") }, modifier = Modifier.semantics { contentDescription = label }) {
                                Text(stringResource(if (st.testAt > 0) R.string.termux_test_again else R.string.termux_test))
                            }
                        }))
                    }
                    SetupStep.FOLDER -> StatusPreference(title, text, mark(l.mark), action = if (!canRun || !st.testOk) null else ({
                        val make = st.folderMissing
                        val label = stringResource(if (make) R.string.termux_create_folder_label else R.string.termux_refresh_label)
                        FilledTonalButton(onClick = { confirm = if (make) "folder" else "list" }, modifier = Modifier.semantics { contentDescription = label }) {
                            Text(stringResource(if (make) R.string.termux_create_it else R.string.termux_refresh))
                        }
                    }))
                    SetupStep.TRUST -> StatusPreference(title, text, mark(l.mark), action = when {
                        ui.check.signer == null || l.mark == SetupMark.OK -> null
                        // 1.0: another signer is refused until set-up is redone; a new version is trusted as it is.
                        ui.check.lock == TermuxLock.SIGNER -> ({ SetUpAgainButton { setUpAgain = true } })
                        ui.check.lock == TermuxLock.UPDATED -> ({ TrustVersionButton { trustVersion = true } })
                        else -> ({
                            val label = stringResource(R.string.termux_trust_label)
                            FilledTonalButton(onClick = { trust = true }, modifier = Modifier.semantics { contentDescription = label }) {
                                Text(stringResource(if (s.trustedSigner != null) R.string.termux_trust_again else R.string.termux_trust_button))
                            }
                        })
                    })
                    else -> StatusPreference(title, text, mark(l.mark))
                }
            }
        }
        if (st.scripts.isNotEmpty()) preferenceGroup(scriptsTitle, "scripts") {
            for (sc in st.scripts) {
                val level = s.script(sc.name)
                ListPreference(sc.name, level, ScriptLevel.entries, { levelLabels.getValue(it) }, { levelDetails.getValue(it) }, RIcons.Terminal,
                    summary = levelLabels.getValue(level) + (sc.comment?.let { " · $it" } ?: "")) { lvl -> act.save(s.copy(scripts = s.scripts + (sc.name to lvl))) }
            }
        }
        if (st.scripts.isNotEmpty()) preferenceGroup(lockTitle, "locked") {
            for (sc in st.scripts.filter { s.script(it.name) != ScriptLevel.OFF }) {
                SwitchPreference(sc.name, stringResource(if (sc.name in s.lockScreenScripts) R.string.termux_lock_on else R.string.termux_lock_off),
                    sc.name in s.lockScreenScripts) { on ->
                    act.save(s.copy(lockScreenScripts = if (on) s.lockScreenScripts + sc.name else s.lockScreenScripts - sc.name))
                }
            }
            Text(stringResource(R.string.termux_lock_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        preferenceGroup(commandsTitle, "commands") {
            ListPreference(commandsTitle, s.commands, CommandLevel.entries, { commandLabels.getValue(it) }, { commandDetails.getValue(it) }, RIcons.Terminal) {
                act.save(s.copy(commands = it))
            }
            SwitchPreference(stringResource(R.string.termux_read_only), stringResource(R.string.termux_read_only_summary),
                s.readOnlyWithoutCard, enabled = s.commands != CommandLevel.OFF) { act.save(s.copy(readOnlyWithoutCard = it)) }
            ListPreference(stringResource(R.string.termux_spoken_yes), s.spokenYes, SpokenYes.entries, { spokenLabels.getValue(it) }, { spokenDetails.getValue(it) }) {
                act.save(s.copy(spokenYes = it))
            }
            ListPreference(stringResource(R.string.termux_api), s.api, ApiCommands.entries, { apiLabels.getValue(it) }, { apiDetails.getValue(it) }) {
                act.save(s.copy(api = it))
            }
        }
        preferenceGroup(runsTitle, "runs") {
            ListPreference(stringResource(R.string.termux_time_limit), s.limitSeconds, TERMUX_LIMITS, ::limitLabel, { if (it == TermuxSettings.DEFAULT_LIMIT_S) theDefault else null },
                RIcons.Timer) {
                act.save(s.copy(limitSeconds = it))
            }
            ListPreference(stringResource(R.string.termux_output), s.outputLines, OUTPUT_LINES, { outputLabels.getValue(it) },
                { if (it == TermuxSettings.DEFAULT_LINES) outputDefault else null }) {
                act.save(s.copy(outputLines = it))
            }
            SwitchPreference(stringResource(R.string.termux_hide_secrets), stringResource(R.string.termux_hide_secrets_summary), s.hideSecrets) {
                act.save(s.copy(hideSecrets = it))
            }
            SwitchPreference(stringResource(R.string.termux_profile), stringResource(if (s.loadProfile) R.string.termux_profile_on else R.string.termux_profile_off),
                s.loadProfile) { act.save(s.copy(loadProfile = it)) }
        }
        if (s.trustedSigner != null) termuxUpdates(ui, ctx, act)
        preferenceGroup(othersTitle, "others") {
            PreferenceItem(stringResource(R.string.termux_others), if (ui.others.isEmpty()) stringResource(R.string.termux_others_none)
                else ui.others.joinToString(", "), RIcons.Apps)
        }
        preferenceFooter(footer)
    }
    confirm?.let { what ->
        val commands = (if (what == "folder") ScriptRegistry.MAKE_FOLDER + "\n" else "") + ScriptRegistry.listShown.str()
        ConfirmDialog(
            stringResource(if (what == "folder") R.string.termux_create_title else R.string.termux_list_title),
            listOf(stringResource(R.string.termux_runs_this), commands).joinToString("\n\n"),
            UiStrings.run().str(), onDismiss = { confirm = null }, onConfirm = { confirm = null; act.run(what) },
        )
    }
    if (trust) {
        val src = ui.check.source
        val text = listOfNotNull(
            stringResource(R.string.termux_trust_text, ui.check.lineText().str(), ui.check.signer?.let(TermuxSetup::short) ?: stringResource(R.string.termux_signer_unknown)),
            when (src) {
                TermuxSource.GITHUB -> stringResource(R.string.termux_trust_github)
                TermuxSource.UNKNOWN -> stringResource(R.string.termux_trust_unknown)
                else -> null
            },
            if (s.updateCheck) stringResource(R.string.termux_trust_updates_note) else null,
        ).joinToString("\n\n")
        ConfirmDialog(stringResource(R.string.termux_trust_title), text,
            stringResource(R.string.termux_trust_button), onDismiss = { trust = false }, onConfirm = {
                trust = false
                // 1.0: the signer with the version, so a later update pauses Termux until you trust it.
                act.save(s.trust(ui.check))
                act.trustLog(TermuxTrustOp.TRUSTED)
            })
    }
    if (trustVersion) {
        val c = ui.check
        ConfirmDialog(stringResource(R.string.termux_trust_version_title, c.version ?: "?"),
            listOfNotNull(stringResource(R.string.termux_trust_version_text, TermuxWords.source(ctx, c.source), termuxDate(c.updatedAt)),
                if (c.source == TermuxSource.GITHUB) stringResource(R.string.termux_trust_version_test_key) else null).joinToString("\n\n"),
            stringResource(R.string.termux_trust_version), onDismiss = { trustVersion = false }, onConfirm = {
                trustVersion = false
                act.save(s.trust(c))
                act.trustLog(TermuxTrustOp.VERSION)
            })
    }
    if (setUpAgain) {
        ConfirmDialog(stringResource(R.string.termux_set_up_again_title), stringResource(R.string.termux_set_up_again_text), stringResource(R.string.termux_set_up_again),
            onDismiss = { setUpAgain = false }, onConfirm = {
                setUpAgain = false
                act.save(s.untrust())
                act.trustLog(TermuxTrustOp.SET_UP_AGAIN)
            })
    }
    if (risk) {
        ConfirmDialog(stringResource(R.string.termux_risk_dialog_title), stringResource(R.string.termux_risk_dialog_text), stringResource(R.string.termux_risk_confirm),
            onDismiss = { risk = false }, onConfirm = {
                risk = false
                act.save(s.copy(testKeyRisk = true))
                act.trustLog(TermuxTrustOp.RISK_ON)
            })
    }
}

/** "Output Robot reads": lines Gemma gets. */
private val OUTPUT_LINES = listOf(20, 40, 80)

/** 1.0: why the Test can't run while [lock] holds Termux, and what to do. */
@Composable
private fun testBlocked(lock: TermuxLock): String = stringResource(when (lock) {
    TermuxLock.TEST_KEY -> R.string.termux_test_blocked_test_key
    TermuxLock.UPDATED -> R.string.termux_test_blocked_updated
    TermuxLock.SIGNER -> R.string.termux_test_blocked_signer
})

@Composable
private fun TrustVersionButton(onClick: () -> Unit) {
    val label = stringResource(R.string.termux_trust_version_label)
    FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.termux_trust_version)) }
}

@Composable
private fun SetUpAgainButton(onClick: () -> Unit) {
    val label = stringResource(R.string.termux_set_up_again_label)
    FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.termux_set_up_again)) }
}

/**
 * 1.0, above the checklist: the update lock (Termux changed to …: Trust this version), another signer (Set up again), and
 * the GitHub build's public test key as soon as that build is installed (set up or not, whatever else holds it): how to
 * switch to the F-Droid build, and, for the build as trusted, "Run anyway, I understand the risk" (off; a tap and a
 * confirmation, for the trusted version only).
 */
private fun androidx.compose.foundation.lazy.LazyListScope.termuxLock(ui: TermuxUi, ctx: android.content.Context, onTrustVersion: () -> Unit,
    onSetUpAgain: () -> Unit, onRisk: (Boolean) -> Unit, openFdroid: () -> Unit) {
    val c = ui.check
    val s = ui.settings
    when (c.lock) {
        TermuxLock.UPDATED -> preferenceGroup(null, "lock") {
            StatusPreference(stringResource(R.string.termux_lock_title_updated), TermuxWords.lock(ctx, c, TermuxLock.UPDATED), Mark.WARN,
                action = { TrustVersionButton(onTrustVersion) })
        }
        TermuxLock.SIGNER -> preferenceGroup(null, "lock") {
            StatusPreference(stringResource(R.string.termux_lock_title_signer), TermuxWords.lock(ctx, c, TermuxLock.SIGNER), Mark.WARN,
                action = { SetUpAgainButton(onSetUpAgain) })
        }
        else -> {}
    }
    if (c.source != TermuxSource.GITHUB) return
    preferenceGroup(null, "lock-test-key") { // l10n: not text (a list key)
        StatusPreference(stringResource(R.string.termux_lock_title_test_key), TermuxWords.lock(ctx, c, TermuxLock.TEST_KEY), if (c.testKeyRisk) Mark.WARN else Mark.TODO)
        // Another signer or an update asks for Set up again or Trust this version first; then Run anyway is for this version.
        if (c.status == TermuxStatus.TEST_KEY) SwitchPreference(stringResource(R.string.termux_risk_title),
            when {
                s.trustedSigner == null -> stringResource(R.string.termux_risk_untrusted)
                c.testKeyRisk -> stringResource(R.string.termux_risk_on, c.version ?: "?")
                else -> stringResource(R.string.termux_risk_off)
            }, c.testKeyRisk, enabled = s.trustedSigner != null) { onRisk(it) }
    }
    preferenceGroup(ctx.getString(R.string.termux_switch_title), "switch-fdroid") { // l10n: not text (a list key)
        val label = stringResource(R.string.termux_fdroid_label)
        PreferenceBlock {
            Text(stringResource(R.string.termux_switch_steps), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = openFdroid, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.termux_fdroid_button)) }
        }
    }
}

/**
 * 1.0, "Termux updates": the switch (on), the installed Termux (its version, source and install date), what Termux's
 * developers released on GitHub and what F-Droid suggests, the verdict, and Check now. Robot never downloads or installs it.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.termuxUpdates(ui: TermuxUi, ctx: android.content.Context, act: TermuxPageActions) {
    val s = ui.settings
    val c = ui.check
    val u = ui.updates
    preferenceGroup(ctx.getString(R.string.termux_updates_group), "updates") {
        SwitchPreference(stringResource(R.string.termux_updates_switch),
            stringResource(if (s.updateCheck) R.string.termux_updates_switch_on else R.string.termux_updates_switch_off), s.updateCheck) {
            act.save(s.copy(updateCheck = it))
        }
        if (c.status != TermuxStatus.NOT_INSTALLED) PreferenceItem(stringResource(R.string.termux_updates_installed),
            stringResource(R.string.termux_updates_installed_line, c.version ?: "?", TermuxWords.source(ctx, c.source), if (c.updatedAt > 0) termuxDate(c.updatedAt) else "?"))
        if (!s.updateCheck) return@preferenceGroup
        val gh = u.github
        val ghVersion = gh?.version
        val ghTest = gh?.testVersion
        PreferenceItem(stringResource(R.string.termux_updates_developers), when {
            gh == null || ghVersion == null -> stringResource(R.string.termux_updates_unknown)
            ghTest != null -> stringResource(R.string.termux_updates_developers_test, ghVersion, termuxDate(gh.publishedAt), ghTest)
            else -> stringResource(R.string.termux_updates_developers_line, ghVersion, termuxDate(gh.publishedAt))
        })
        PreferenceItem(stringResource(R.string.termux_updates_fdroid),
            u.fdroid?.suggested?.let { stringResource(R.string.termux_updates_fdroid_line, it) } ?: stringResource(R.string.termux_updates_unknown))
        val pkg = app.heyrobot.core.termux.TermuxPackage(c.status != TermuxStatus.NOT_INSTALLED, c.version, versionCode = c.versionCode, lastUpdateTime = c.updatedAt)
        val news = TermuxUpdates.news(pkg, c.source, u)
        val (text, mark) = when (news.verdict) {
            TermuxUpdateVerdict.UP_TO_DATE -> stringResource(R.string.termux_updates_current) to Mark.OK
            TermuxUpdateVerdict.AVAILABLE, TermuxUpdateVerdict.FAR_BEHIND ->
                (if (c.source == TermuxSource.F_DROID) stringResource(R.string.termux_updates_available_fdroid)
                else stringResource(R.string.termux_updates_available_other, news.newest ?: "?")) +
                    (if (news.verdict == TermuxUpdateVerdict.FAR_BEHIND) " " + stringResource(R.string.termux_updates_far, news.newest ?: "?") else "") to Mark.WARN
            TermuxUpdateVerdict.NOT_ON_FDROID_YET -> stringResource(R.string.termux_updates_not_on_fdroid, news.newest ?: "?") to Mark.OK
            TermuxUpdateVerdict.FAILED -> stringResource(R.string.termux_updates_failed) to Mark.WARN
            TermuxUpdateVerdict.NOT_CHECKED, TermuxUpdateVerdict.UNKNOWN -> stringResource(R.string.termux_updates_never) to Mark.TODO
        }
        StatusPreference(stringResource(R.string.termux_updates_status), text, mark)
        val checkLabel = stringResource(R.string.termux_updates_check_now_label)
        StatusPreference(if (ui.checking) stringResource(R.string.termux_updates_checking)
            else if (u.checkedAt > 0) stringResource(R.string.termux_updates_checked, termuxDate(u.checkedAt)) else stringResource(R.string.termux_updates_never),
            null, if (u.githubFailure != null || u.fdroidFailure != null) Mark.WARN else if (u.checkedAt > 0) Mark.OK else Mark.TODO,
            action = if (ui.checking) null else ({
                FilledTonalButton(onClick = act.checkUpdates, modifier = Modifier.semantics { contentDescription = checkLabel }) {
                    Text(stringResource(R.string.termux_updates_check_now))
                }
            }))
        Text(stringResource(R.string.termux_updates_privacy, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

private fun scriptDetail(l: ScriptLevel): Int = when (l) {
    ScriptLevel.AUTO -> R.string.termux_script_auto
    ScriptLevel.ASK -> R.string.termux_script_ask
    ScriptLevel.OFF -> R.string.termux_script_off
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
        TermuxUi(s, TermuxFacts.check(app, s), TermuxFacts.granted(app), TermuxStore.status(app), TermuxFacts.otherHolders(app),
            TermuxUpdateStore.load(app), TermuxUpdateCheck.busy)
    }
    LaunchedEffect(lstate.isAtLeast(Lifecycle.State.RESUMED), tick) {
        ui = read()
        // A set-up run under way: read again until it ends (it writes its end itself; a stale one is over).
        while (true) {
            val now = ui ?: break
            val setup = now.status.running != null && System.currentTimeMillis() - now.status.runningAt < TermuxSetup.RUNNING_STALE_MS
            // 1.0: or Termux's update check, until it ends.
            if (!setup && !now.checking) break
            delay(500)
            ui = read()
        }
    }
    fun say(t: String) { scope.launch { snackbar.showSnackbar(t) } }
    val noBrowser = stringResource(R.string.termux_no_browser)
    val copied = stringResource(R.string.termux_copied)
    val copyFailed = stringResource(R.string.termux_copy_failed)
    val assistantFirst = stringResource(R.string.termux_assistant_first)
    val act = TermuxPageActions(
        save = { s ->
            val old = ui?.settings
            TermuxStore.save(app, s)
            ui = ui?.copy(settings = s)
            // 1.0: a trust or the update check's switch changed: the weekly check follows (and is read again).
            if (old?.checksUpdates != s.checksUpdates || old?.trustedSigner != s.trustedSigner || old?.trustedUpdatedAt != s.trustedUpdatedAt) scope.launch {
                withContext(Dispatchers.IO) { TermuxStore.saveNow(app, s); TermuxUpdateCheck.ensure(app) }
                tick++
            }
        },
        checkUpdates = {
            TermuxUpdateCheck.checkNow(app) { scope.launch { tick++ } }
            scope.launch {
                delay(200)
                tick++
            }
        },
        trustLog = { op ->
            val source = ui?.check?.source
            scope.launch(Dispatchers.IO) { TermuxUpdateCheck.log(app, VoiceEvent.TERMUX_TRUST, op.id, (source?.ordinal ?: -1) + 1) }
        },
        askPermission = a.askTermux,
        openFdroid = {
            try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TermuxSetup.FDROID_URL))) } catch (_: Exception) { say(noBrowser) }
        },
        openAppInfo = a.openAppSettings,
        copyLine = {
            try {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Termux", TermuxSetup.ALLOW_LINE))
                say(copied)
            } catch (_: Exception) { say(copyFailed) }
        },
        run = { what ->
            // The set-up runs happen in the assistant's process, where Termux's answer comes back.
            if (assistantActive != true) say(assistantFirst)
            else Cmd.send(app, Cmd.TERMUX, Bundle().apply { putString(Cmd.EXTRA_RUN, what) })
            scope.launch {
                delay(300)
                tick++
            }
        },
    )
    val now = ui
    val checking = stringResource(R.string.termux_checking)
    if (now == null) PreferenceScreen(UiStrings.termux().str(), onBack, snackbar = snackbar) { item(key = "checking") { IntroPreference(checking) } }
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
    StatusPreference(UiStrings.termux().str(),
        stringResource(when {
            on && ready -> R.string.termux_row_ready
            on -> R.string.termux_row_attention
            else -> R.string.termux_row_off
        }),
        when { on && ready -> Mark.OK; on -> Mark.WARN; else -> Mark.TODO },
        onClick = { a.openPage(Page.Settings(SettingsRoutes.TERMUX)) })
}
