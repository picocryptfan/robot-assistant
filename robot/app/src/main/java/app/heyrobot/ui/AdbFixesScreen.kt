package app.heyrobot.ui

import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.heyrobot.BuildConfig
import app.heyrobot.core.AdbCheck
import app.heyrobot.core.AdbFix
import app.heyrobot.core.AdbFixState
import app.heyrobot.core.AdbFixes
import app.heyrobot.core.RepairWhen
import app.heyrobot.sys.RecognizerKeeper
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
    PreferenceScreen("ADB fixes", onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference("Things only a computer can change for Robot, each done once with adb.") }
        preferenceGroup(null, "fixes") {
            for (fix in AdbFixes.ALL) {
                val state = AdbFixes.state(fix, applied[fix.check] == true, BuildConfig.VERSION_CODE)
                StatusPreference(fix.title, AdbFixes.label(fix, state), mark(state), onClick = { a.openPage(Page.Settings(SettingsRoutes.adbFix(fix.id))) })
            }
        }
        preferenceFooter(AdbFixes.HEADER)
    }
}

/** One fix: whether it is done, why it is needed, and its commands, selectable and with Copy. */
@Composable
fun AdbFixScreen(id: String, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val fix = AdbFixes.ALL.firstOrNull { it.id == id }
    if (fix == null) {
        LaunchedEffect(id) { onBack() }
        return
    }
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val applied = rememberApplied()
    val state = AdbFixes.state(fix, applied[fix.check] == true, BuildConfig.VERSION_CODE)
    PreferenceScreen(fix.title, onBack, snackbar = snackbar) {
        preferenceGroup(null, "state") { StatusPreference("Status", AdbFixes.label(fix, state), mark(state)) }
        preferenceGroup("Why", "why") { PreferenceBlock { Text(fix.why, style = MaterialTheme.typography.bodyLarge) } }
        preferenceGroup("Commands", "commands") {
            PreferenceBlock {
                Commands(fix)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    FilledTonalButton(onClick = {
                        val cm = ctx.getSystemService(ClipboardManager::class.java)
                        cm.setPrimaryClip(ClipData.newPlainText(fix.title, fix.commands.joinToString("\n")))
                        scope.launch { snackbar.showSnackbar("Copied. Paste it on the computer.") }
                    }) {
                        Icon(RIcons.Copy, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Copy")
                    }
                }
            }
        }
        preferenceFooter(AdbFixes.HEADER)
    }
}

/** The commands as typed on the computer: monospace, selectable. */
@Composable
private fun Commands(fix: AdbFix) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = MaterialTheme.shapes.small, modifier = Modifier.fillMaxWidth()) {
        SelectionContainer {
            Text(fix.commands.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(12.dp))
        }
    }
}
