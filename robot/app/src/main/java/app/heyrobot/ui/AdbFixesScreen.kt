package app.heyrobot.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
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
import androidx.compose.ui.Alignment
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Advanced › ADB fixes (0.2.3): the platform workarounds that need a computer once, from the
// list in core AdbFixes.kt, each with its status (checked here every second while the screen shows), why in plain
// words, and its commands to copy. A new workaround is a new entry in that list.

@Composable
fun AdbFixesScreen(snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var applied by remember { mutableStateOf<Map<AdbCheck, Boolean>>(emptyMap()) }
    LaunchedEffect(Unit) {
        // Opening this screen is also a moment to repair the recognizer setting, if Robot may.
        withContext(Dispatchers.IO) { RecognizerKeeper.check(ctx, RepairWhen.SETUP) }
        while (true) {
            applied = withContext(Dispatchers.IO) {
                mapOf(AdbCheck.RECOGNIZER_IS_ROBOT to RecognizerKeeper.isRobot(ctx), AdbCheck.SECURE_SETTINGS_GRANTED to RecognizerKeeper.canWrite(ctx))
            }
            delay(1_000)
        }
    }
    Scaffold(topBar = { BackBar("ADB fixes", onBack) }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(AdbFixes.HEADER, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (fix in AdbFixes.ALL) {
                val state = AdbFixes.state(fix, applied[fix.check] == true, BuildConfig.VERSION_CODE)
                AdbFixCard(fix, state) {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    cm.setPrimaryClip(ClipData.newPlainText(fix.title, fix.commands.joinToString("\n")))
                    scope.launch { snackbar.showSnackbar("Copied. Paste it on the computer.") }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun AdbFixCard(fix: AdbFix, state: AdbFixState, onCopy: () -> Unit) {
    SectionCard {
        StatusRow(
            when (state) { AdbFixState.APPLIED -> Mark.OK; AdbFixState.NOT_APPLIED -> Mark.WARN; AdbFixState.NEEDS_NEWER -> Mark.TODO },
            fix.title,
            AdbFixes.label(fix, state),
        )
        Text(fix.why, style = MaterialTheme.typography.bodyMedium)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            SelectionContainer {
                Text(fix.commands.joinToString("\n"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onCopy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Copy") }
        }
    }
}
