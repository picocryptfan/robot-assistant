// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

import app.heyrobot.core.i18n.AndroidPath
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.ui.theme.LocalLangs
import app.heyrobot.ui.theme.str
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.R
import app.heyrobot.core.AdbCheck
import app.heyrobot.core.AdbFix
import app.heyrobot.core.AdbFixState
import app.heyrobot.core.AdbFixes
import app.heyrobot.core.RecognizerRepair
import app.heyrobot.core.RepairWhen
import app.heyrobot.core.ShizukuOutcome
import app.heyrobot.core.ShizukuPath
import app.heyrobot.core.ShizukuRefusal
import app.heyrobot.core.ShizukuResult
import app.heyrobot.core.ShizukuRun
import app.heyrobot.core.termux.TermuxSetup
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.Cmd
import app.heyrobot.sys.RecognizerKeeper
import app.heyrobot.sys.TermuxFacts
import app.heyrobot.sys.TermuxStore
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.settings.SettingsRoutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Advanced › ADB fixes (0.2.3): the platform workarounds that need a computer once, from the
// list in core AdbFixes.kt, each with its status (checked here every second while the screen shows); a fix's page says
// why in plain words and has its commands to copy (0.4.x redesign: a status row each, a page each). A new workaround is
// a new entry in that list.
// 1.0: each fix two ways, with a computer (adb) and without one (Shizuku's rish in Termux, core ShizukuPath), the list's
// Shizuku steps and its safety text; and, with Termux set up and trusted (not the GitHub build), "Run with Shizuku
// through Termux": exactly the fix's fixed command, sent by the :voice process (Cmd.TERMUX "shizuku") after a tap on its
// card here, never from the panel or the model, never on the lock screen; the page then shows how it ended.
// 1.2 (every-android-phone, Step 6): where another installed speech recognizer is set (a stock phone's own, core
// RecognizerRepair.kept), the recognizer fix shows as not needed, with why, and without its commands or its run: it would
// replace that recognizer for every app. :voice refuses its run then too (core ShizukuRun.refusal).

/** What the pages show the fixes by: which checks are done, and (1.2) what keeps Robot the assistant at the next boot. */
internal data class AdbNow(val applied: Map<AdbCheck, Boolean> = emptyMap(), val kept: RecognizerRepair.Kept = RecognizerRepair.Kept.NONE)

/** Read every second while a page shows; opening one repairs the recognizer setting if Robot may. */
@Composable
private fun rememberAdbNow(): AdbNow {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(AdbNow()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecognizerKeeper.check(ctx, RepairWhen.SETUP) }
        while (true) {
            now = withContext(Dispatchers.IO) {
                AdbNow(mapOf(AdbCheck.RECOGNIZER_IS_ROBOT to RecognizerKeeper.isRobot(ctx), AdbCheck.SECURE_SETTINGS_GRANTED to RecognizerKeeper.canWrite(ctx)),
                    RecognizerKeeper.kept(ctx))
            }
            delay(1_000)
        }
    }
    return now
}

private fun state(fix: AdbFix, now: AdbNow): AdbFixState = AdbFixes.state(fix, now.applied[fix.check] == true, BuildConfig.VERSION_CODE, now.kept)

/** 1.2: not needed reads as Assistant setup's row for another app's recognizer does: no warning. */
private fun mark(state: AdbFixState): Mark = when (state) {
    AdbFixState.APPLIED, AdbFixState.NOT_NEEDED -> Mark.OK
    AdbFixState.NOT_APPLIED -> Mark.WARN
    AdbFixState.NEEDS_NEWER -> Mark.TODO
}

/** Settings › Advanced › ADB fixes: one status row per fix, each opening its page. */
@Composable
fun AdbFixesScreen(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) = AdbFixesList(rememberAdbNow(), a, snackbar, onBack)

/** The list as [now] shows it (1.2: apart from the live reads, so its states can be shown and checked). */
@Composable
internal fun AdbFixesList(now: AdbNow, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val footer = AdbFixes.headerText.str()
    val system = LocalLangs.current.system
    val wireless = AndroidPath.WIRELESS_DEBUGGING.render(system)
    PreferenceScreen(UiStrings.adbFixes().str(), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.adb_intro)) }
        preferenceGroup(null, "fixes") {
            for (fix in AdbFixes.ALL) {
                val state = state(fix, now)
                StatusPreference(fix.titleText.str(), AdbFixes.labelText(fix, state).str(), mark(state),
                    onClick = { a.openPage(Page.Settings(SettingsRoutes.adbFix(fix.id))) })
            }
        }
        // 1.0: the same fixes without a computer.
        preferenceGroup(ctx.getString(R.string.shizuku_group), "shizuku") {
            PreferenceBlock { Text(stringResource(R.string.shizuku_intro, wireless), style = MaterialTheme.typography.bodyLarge) }
            Step(stringResource(R.string.shizuku_step1_title), stringResource(R.string.shizuku_step1))
            Step(stringResource(R.string.shizuku_step2_title, wireless), stringResource(R.string.shizuku_step2, AndroidPath.ABOUT_PHONE.render(system),
                AndroidPath.BUILD_NUMBER.render(system), AndroidPath.DEVELOPER_OPTIONS.render(system), wireless))
            PreferenceBlock {
                Text(stringResource(R.string.shizuku_step3_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.shizuku_step3), style = MaterialTheme.typography.bodyMedium)
                Mono(ShizukuPath.TERMUX_LINE)
                CopyButton(stringResource(R.string.shizuku_step3_label)) {
                    copy(ctx, "Termux", ShizukuPath.TERMUX_LINE)
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shizuku_copied_line)) }
                }
            }
            Step(stringResource(R.string.shizuku_step4_title), stringResource(R.string.shizuku_step4, ShizukuPath.FIRST_RUN))
            Step(stringResource(R.string.shizuku_step5_title), stringResource(R.string.shizuku_step5))
            PreferenceBlock { Text(stringResource(R.string.shizuku_safety, AndroidPath.WIRELESS_DEBUGGING.render(LocalLangs.current.system)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        preferenceFooter(footer)
    }
}

/** 1.0: one of the Shizuku steps: its title and what to do. */
@Composable
private fun Step(title: String, text: String) {
    PreferenceBlock {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/** 1.0: what "Run with Shizuku through Termux" may do now, and how the last run of this fix ended. */
data class ShizukuUi(
    /** Termux set up and trusted, not the GitHub build (core ShizukuRun.offered). */
    val offered: Boolean = false,
    val running: Boolean = false,
    val result: ShizukuResult? = null,
    /** Robot is the digital assistant (Termux answers the :voice process). */
    val assistant: Boolean = true,
)

/** 1.0: read every second while a fix's page shows (Settings › Termux and what the :voice process last wrote). */
@Composable
private fun rememberShizuku(fixId: String): ShizukuUi {
    val ctx = LocalContext.current
    var ui by remember { mutableStateOf(ShizukuUi()) }
    LaunchedEffect(fixId) {
        while (true) {
            ui = withContext(Dispatchers.IO) { shizukuNow(ctx, fixId) }
            delay(1_000)
        }
    }
    return ui
}

private fun shizukuNow(ctx: Context, fixId: String): ShizukuUi = try {
    val s = TermuxStore.load(ctx)
    val check = TermuxFacts.check(ctx, s)
    val st = TermuxStore.status(ctx)
    val ready = TermuxSetup.ready(check, TermuxFacts.granted(ctx), st, s.trustedSigner)
    val active = try { VoiceInteractionService.isActiveService(ctx, ComponentName(ctx.packageName, Assistant.VIS_CLASS)) } catch (_: Throwable) { true }
    ShizukuUi(ShizukuRun.offered(check, ready), st.running == ShizukuPath.RUN && System.currentTimeMillis() - st.runningAt < TermuxSetup.RUNNING_STALE_MS,
        st.shizuku?.takeIf { it.fixId == fixId }, active)
} catch (_: Exception) { ShizukuUi() }

/** One fix: whether it is done, why it is needed, and its commands, selectable and with Copy. */
@Composable
fun AdbFixScreen(id: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val fix = AdbFixes.ALL.firstOrNull { it.id == id }
    if (fix == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val now = rememberAdbNow()
    val shizuku = rememberShizuku(fix.id)
    AdbFixPage(fix, now.applied, shizuku, snackbar, onBack, now.kept) { fixId ->
        // The :voice process sends it (Termux answers there); it checks everything again before anything goes.
        Cmd.send(ctx.applicationContext, Cmd.TERMUX, Bundle().apply { putString(Cmd.EXTRA_RUN, ShizukuPath.RUN); putString(Cmd.EXTRA_ID, fixId) })
    }
}

/**
 * One fix's page: whether it is done, why, its commands with a computer (adb) and without one (rish, in Termux), each with
 * Copy, and (1.0) "Run with Shizuku through Termux" when [shizuku] offers it: its card shows the exact command, and only
 * its Run sends [run] the fix's id. 1.2: not needed ([kept]: another installed recognizer is set), it says why and offers
 * neither the commands nor the run.
 */
@Composable
internal fun AdbFixPage(fix: AdbFix, applied: Map<AdbCheck, Boolean>, shizuku: ShizukuUi, snackbar: SnackbarHostState, onBack: () -> Unit,
    kept: RecognizerRepair.Kept = RecognizerRepair.Kept.NONE, run: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = state(fix, AdbNow(applied, kept))
    val notNeeded = state == AdbFixState.NOT_NEEDED
    val rish = ShizukuPath.rishLines(fix)
    var card by rememberSaveable { mutableStateOf(false) }
    val title = fix.titleText.str()
    val why = stringResource(R.string.adb_why)
    val footer = AdbFixes.headerText.str()
    PreferenceScreen(title, onBack, snackbar = snackbar) {
        preferenceGroup(null, "state") {
            StatusPreference(stringResource(R.string.adb_status), AdbFixes.labelText(fix, state).str(), mark(state))
            if (notNeeded) PreferenceBlock { Text(stringResource(R.string.adb_recognizer_kept), style = MaterialTheme.typography.bodyLarge) }
        }
        preferenceGroup(why, "why") { PreferenceBlock { Text(fix.whyText.str(), style = MaterialTheme.typography.bodyLarge) } }
        // 1.2: nothing to copy or run where it would replace another installed recognizer.
        if (!notNeeded) preferenceGroup(ctx.getString(R.string.adb_with_computer), "commands") {
            PreferenceBlock {
                Mono(fix.commands.joinToString("\n"))
                CopyButton(stringResource(R.string.adb_copy_adb_label)) {
                    copy(ctx, title, fix.commands.joinToString("\n"))
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.adb_copied)) }
                }
            }
        }
        if (rish != null && !notNeeded) preferenceGroup(ctx.getString(R.string.adb_without_computer), "shizuku") {
            PreferenceBlock {
                Text(stringResource(R.string.shizuku_fix_intro), style = MaterialTheme.typography.bodyMedium)
                Mono(rish.joinToString("\n"))
                CopyButton(stringResource(R.string.adb_copy_rish_label)) {
                    copy(ctx, title, rish.joinToString("\n"))
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.adb_copied_rish)) }
                }
            }
            if (shizuku.offered) {
                val label = stringResource(R.string.shizuku_run_label)
                StatusPreference(stringResource(R.string.shizuku_run), stringResource(if (shizuku.running) R.string.shizuku_running else R.string.shizuku_run_detail),
                    Mark.TODO, action = if (shizuku.running) null else ({
                        FilledTonalButton(onClick = {
                            if (!shizuku.assistant) scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shizuku_need_assistant)) } else card = true
                        }, modifier = Modifier.semantics { contentDescription = label }) { Text(stringResource(R.string.shizuku_run_button)) }
                    }))
            } else PreferenceBlock { Text(stringResource(R.string.shizuku_run_unavailable), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            shizuku.result?.let { r -> StatusPreference(stringResource(R.string.shizuku_last), resultText(r), if (r.outcome == ShizukuOutcome.APPLIED) Mark.OK else Mark.WARN) }
            PreferenceBlock { Text(stringResource(R.string.shizuku_safety, AndroidPath.WIRELESS_DEBUGGING.render(LocalLangs.current.system)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        if (!notNeeded) preferenceFooter(footer)
    }
    // The tap-only card: the exact command Robot will send, Run or Cancel. Nothing is sent without its Run.
    val command = ShizukuPath.command(fix.id)
    if (card && command != null && !notNeeded) {
        ConfirmDialog(stringResource(R.string.shizuku_card_title), stringResource(R.string.shizuku_card_text, command), stringResource(R.string.shizuku_run_button),
            onDismiss = { card = false }, onConfirm = {
                card = false
                run(fix.id)
            })
    }
}

/** How the last run of this fix through Shizuku ended, in words. */
@Composable
private fun resultText(r: ShizukuResult): String = when (r.outcome) {
    ShizukuOutcome.APPLIED -> stringResource(R.string.shizuku_result_applied)
    ShizukuOutcome.NOT_APPLIED -> stringResource(R.string.shizuku_result_not_applied)
    ShizukuOutcome.NO_RISH -> stringResource(R.string.shizuku_result_no_rish)
    ShizukuOutcome.FAILED -> stringResource(R.string.shizuku_result_failed, r.exit ?: -1)
    ShizukuOutcome.TERMUX_FAILED -> stringResource(R.string.shizuku_result_termux)
    ShizukuOutcome.REFUSED -> stringResource(when (r.refusal) {
        ShizukuRefusal.LOCKED -> R.string.shizuku_result_locked
        ShizukuRefusal.TEST_KEY -> R.string.shizuku_result_test_key
        ShizukuRefusal.TERMUX_CHANGED -> R.string.shizuku_result_changed
        ShizukuRefusal.NOT_NEEDED -> R.string.shizuku_result_not_needed
        else -> R.string.shizuku_result_termux_not_ready
    })
}

private fun copy(ctx: Context, label: String, text: String) {
    try { ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text)) } catch (_: Exception) {}
}

/** Copy, with what it copies for TalkBack ([label]). */
@Composable
private fun CopyButton(label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        FilledTonalButton(onClick = onClick, modifier = Modifier.semantics { contentDescription = label }) {
            Icon(RIcons.Copy, null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.adb_copy))
        }
    }
}

/** Commands as typed: monospace, selectable. */
@Composable
private fun Mono(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        }
    }
}
