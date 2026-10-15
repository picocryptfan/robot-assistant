// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui

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

/** Which checks are done now, read every second while a page shows; opening one repairs the recognizer setting if Robot may. */
@Composable
private fun rememberApplied(): Map<AdbCheck, Boolean> {
    val ctx = LocalContext.current
    var applied by remember { mutableStateOf<Map<AdbCheck, Boolean>>(emptyMap()) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { RecognizerKeeper.check(ctx, RepairWhen.SETUP) }
        while (true) {
            applied = withContext(Dispatchers.IO) {
                mapOf(AdbCheck.RECOGNIZER_IS_ROBOT to RecognizerKeeper.isRobot(ctx), AdbCheck.SECURE_SETTINGS_GRANTED to RecognizerKeeper.canWrite(ctx))
            }
            delay(1_000)
        }
    }
    return applied
}

private fun mark(state: AdbFixState): Mark = when (state) {
    AdbFixState.APPLIED -> Mark.OK
    AdbFixState.NOT_APPLIED -> Mark.WARN
    AdbFixState.NEEDS_NEWER -> Mark.TODO
}

/** Settings › Advanced › ADB fixes: one status row per fix, each opening its page. */
@Composable
fun AdbFixesScreen(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val applied = rememberApplied()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    PreferenceScreen("ADB fixes", onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference("Things only a computer can change for Robot, each done once with adb.") }
        preferenceGroup(null, "fixes") {
            for (fix in AdbFixes.ALL) {
                val state = AdbFixes.state(fix, applied[fix.check] == true, BuildConfig.VERSION_CODE)
                StatusPreference(fix.title, AdbFixes.label(fix, state), mark(state), onClick = { a.openPage(Page.Settings(SettingsRoutes.adbFix(fix.id))) })
            }
        }
        // 1.0: the same fixes without a computer.
        preferenceGroup(ctx.getString(R.string.shizuku_group), "shizuku") {
            PreferenceBlock { Text(stringResource(R.string.shizuku_intro), style = MaterialTheme.typography.bodyLarge) }
            Step(R.string.shizuku_step1_title, stringResource(R.string.shizuku_step1))
            Step(R.string.shizuku_step2_title, stringResource(R.string.shizuku_step2))
            PreferenceBlock {
                Text(stringResource(R.string.shizuku_step3_title), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.shizuku_step3), style = MaterialTheme.typography.bodyMedium)
                Mono(ShizukuPath.TERMUX_LINE)
                CopyButton(stringResource(R.string.shizuku_step3_label)) {
                    copy(ctx, "Termux", ShizukuPath.TERMUX_LINE)
                    scope.launch { snackbar.showSnackbar(ctx.getString(R.string.shizuku_copied_line)) }
                }
            }
            Step(R.string.shizuku_step4_title, stringResource(R.string.shizuku_step4, ShizukuPath.FIRST_RUN))
            Step(R.string.shizuku_step5_title, stringResource(R.string.shizuku_step5))
            PreferenceBlock { Text(stringResource(R.string.shizuku_safety), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        preferenceFooter(AdbFixes.HEADER)
    }
}

/** 1.0: one of the Shizuku steps: its title and what to do. */
@Composable
private fun Step(title: Int, text: String) {
    PreferenceBlock {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
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
    val applied = rememberApplied()
    val shizuku = rememberShizuku(fix.id)
    AdbFixPage(fix, applied, shizuku, snackbar, onBack) { fixId ->
        // The :voice process sends it (Termux answers there); it checks everything again before anything goes.
        Cmd.send(ctx.applicationContext, Cmd.TERMUX, Bundle().apply { putString(Cmd.EXTRA_RUN, ShizukuPath.RUN); putString(Cmd.EXTRA_ID, fixId) })
    }
}

/**
 * One fix's page: whether it is done, why, its commands with a computer (adb) and without one (rish, in Termux), each with
 * Copy, and (1.0) "Run with Shizuku through Termux" when [shizuku] offers it: its card shows the exact command, and only
 * its Run sends [run] the fix's id.
 */
@Composable
internal fun AdbFixPage(fix: AdbFix, applied: Map<AdbCheck, Boolean>, shizuku: ShizukuUi, snackbar: SnackbarHostState, onBack: () -> Unit,
    run: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state = AdbFixes.state(fix, applied[fix.check] == true, BuildConfig.VERSION_CODE)
    val rish = ShizukuPath.rishLines(fix)
    var card by rememberSaveable { mutableStateOf(false) }
    PreferenceScreen(fix.title, onBack, snackbar = snackbar) {
        preferenceGroup(null, "state") { StatusPreference("Status", AdbFixes.label(fix, state), mark(state)) }
        preferenceGroup("Why", "why") { PreferenceBlock { Text(fix.why, style = MaterialTheme.typography.bodyLarge) } }
        preferenceGroup(ctx.getString(R.string.adb_with_computer), "commands") {
            PreferenceBlock {
                Mono(fix.commands.joinToString("\n"))
                CopyButton(stringResource(R.string.adb_copy_adb_label)) {
                    copy(ctx, fix.title, fix.commands.joinToString("\n"))
                    scope.launch { snackbar.showSnackbar("Copied. Paste it on the computer.") }
                }
            }
        }
        if (rish != null) preferenceGroup(ctx.getString(R.string.adb_without_computer), "shizuku") {
            PreferenceBlock {
                Text(stringResource(R.string.shizuku_fix_intro), style = MaterialTheme.typography.bodyMedium)
                Mono(rish.joinToString("\n"))
                CopyButton(stringResource(R.string.adb_copy_rish_label)) {
                    copy(ctx, fix.title, rish.joinToString("\n"))
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
            PreferenceBlock { Text(stringResource(R.string.shizuku_safety), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
        }
        preferenceFooter(AdbFixes.HEADER)
    }
    // The tap-only card: the exact command Robot will send, Run or Cancel. Nothing is sent without its Run.
    val command = ShizukuPath.command(fix.id)
    if (card && command != null) {
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
