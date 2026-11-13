// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.link

import androidx.compose.foundation.layout.fillMaxWidth
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.core.link.BridgeChoice
import app.heyrobot.core.link.BridgeLine
import androidx.compose.foundation.layout.Row
import app.heyrobot.core.link.BridgeRelayPlan
import app.heyrobot.core.link.LinkSettings
import app.heyrobot.core.link.TorBridges
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.SecureDialog
import app.heyrobot.ui.common.SecureWindow
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.RadioPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. 1.5, Settings › Robot to Robot › Tor bridges (robot-to-robot-carriers-research.md; job L-bridges): how :link's
// Tor reaches the Tor network when a network blocks it. Off by default; Automatic, Snowflake, the built-in obfs4 bridges, or
// your own bridge lines, each with one line on what its bridge sees. The choice goes to link_settings.json (core
// LinkSettings.bridges); :link's TorRuntime reads it each time it starts Tor. Your own lines pass core TorBridges.parseOwn
// before they are saved, so nothing but bridge lines reaches the torrc.

/** The choice's name, for the row on Settings › Robot to Robot. */
internal fun bridgesLabel(c: BridgeChoice): Int = when (c) {
    BridgeChoice.OFF -> R.string.link_bridges_off
    BridgeChoice.AUTO -> R.string.link_bridges_auto
    BridgeChoice.SNOWFLAKE -> R.string.link_bridges_snowflake
    BridgeChoice.OBFS4 -> R.string.link_bridges_obfs4
    BridgeChoice.OWN -> R.string.link_bridges_own
}

@Composable
fun BridgesScreen(port: LinkPort, a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    SecureWindow()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var settings by remember { mutableStateOf<LinkSettings?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var removing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(tick) { settings = withContext(Dispatchers.IO) { port.settings() } }

    fun save(done: String? = null, change: (LinkSettings) -> LinkSettings) {
        if (busy) return
        busy = true
        scope.launch {
            val ok = withContext(Dispatchers.IO) { port.saveSettings(change) }
            busy = false
            if (!ok) a.say(ctx.getString(R.string.link_not_saved)) else if (done != null) a.say(done)
            tick++
        }
    }

    val s = settings
    // Read here: the list's builder isn't composable.
    val footer = stringResource(R.string.link_bridges_footer)
    PreferenceScreen(stringResource(R.string.link_bridges), onBack, snackbar = snackbar) {
        item(key = "intro") { IntroPreference(stringResource(R.string.link_bridges_intro)) }
        if (s == null) return@PreferenceScreen
        preferenceGroup(null, "choice") {
            for ((c, detail) in listOf(
                BridgeChoice.OFF to R.string.link_bridges_off_detail,
                BridgeChoice.AUTO to R.string.link_bridges_auto_detail,
                BridgeChoice.SNOWFLAKE to R.string.link_bridges_snowflake_detail,
                BridgeChoice.OBFS4 to R.string.link_bridges_obfs4_detail,
            )) {
                RadioPreference(stringResource(bridgesLabel(c)), stringResource(detail), s.bridges == c) { if (s.bridges != c) save { it.copy(bridges = c) } }
            }
            val own = s.ownBridges
            // tor-bridge-relay-c L4: beside a home bridge your own Snowflake lines are skipped (lyrebird can't send them through the relay).
            val skips = remember(own) { BridgeRelayPlan.skipsSnowflake(own) }
            RadioPreference(stringResource(R.string.link_bridges_own),
                if (own.isEmpty()) stringResource(R.string.link_bridges_own_none) else pluralStringResource(R.plurals.link_bridges_own_count, own.size, own.size) +
                    (if (skips) "\n" + stringResource(R.string.link_bridges_own_snowflake_skipped) else ""), // l10n: not text (a line break)
                s.bridges == BridgeChoice.OWN,
                trailing = if (own.isEmpty()) null else { {
                    Row {
                        TextButton(onClick = { editing = true }, enabled = !busy) { Text(stringResource(R.string.link_bridges_edit)) }
                        TextButton(onClick = { removing = true }, enabled = !busy) { Text(stringResource(R.string.link_bridges_remove)) }
                    }
                } },
            ) {
                if (own.isEmpty()) editing = true else if (s.bridges != BridgeChoice.OWN) save { it.copy(bridges = BridgeChoice.OWN) }
            }
        }
        preferenceFooter(footer)
    }
    if (editing && s != null) {
        BridgeLinesDialog(s.ownBridges.joinToString("\n") { it.text }, onDismiss = { editing = false }) { lines ->
            editing = false
            save { it.copy(bridges = BridgeChoice.OWN, ownBridges = lines) }
        }
    }
    // fix-bridges-and-wording: your saved lines go without Start over. Chosen, the choice falls back to Off (never plain
    // Tor in their place unasked: Off is what Tor bridges start as) and the snackbar says so; :link's TorRuntime stops
    // using them, and any relay to a home bridge, within half a minute while Robot to Robot's screens are open (the pump,
    // even for a Tor still connecting), otherwise the next time Robot uses Tor.
    if (removing && s != null) {
        val wasOwn = s.bridges == BridgeChoice.OWN
        AlertDialog(
            onDismissRequest = { removing = false },
            properties = SecureDialog,
            title = { Text(stringResource(R.string.link_bridges_remove_title)) },
            text = { Text(stringResource(if (wasOwn) R.string.link_bridges_remove_text_own else R.string.link_bridges_remove_text)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = false
                    save(ctx.getString(if (wasOwn) R.string.link_bridges_removed_off else R.string.link_bridges_removed)) {
                        it.copy(ownBridges = emptyList(), bridges = if (it.bridges == BridgeChoice.OWN) BridgeChoice.OFF else it.bridges)
                    }
                }) { Text(stringResource(R.string.link_bridges_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = false }) { Text(UiStrings.cancel().str()) } },
        )
    }
}

/** Your own bridge lines: typed or pasted, one a line; saved only when every line is a bridge line Tor can use. */
@Composable
private fun BridgeLinesDialog(initial: String, onDismiss: () -> Unit, onSave: (List<BridgeLine>) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val lines = if (text.isBlank()) null else TorBridges.parseOwn(text)
    val bad = text.isNotBlank() && lines == null
    AlertDialog(
        onDismissRequest = onDismiss,
        // A bridge of your own is private: its address is known only to whoever gave it to you.
        properties = SecureDialog,
        title = { Text(stringResource(R.string.link_bridges_own)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(TorBridges.MAX_OWN_TEXT) },
                label = { Text(stringResource(R.string.link_bridges_own_field)) },
                supportingText = { Text(stringResource(if (bad) R.string.link_bridges_own_bad else R.string.link_bridges_own_help)) },
                isError = bad,
                singleLine = false,
                minLines = 3,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { lines?.let(onSave) }, enabled = lines != null) { Text(UiStrings.save().str()) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(UiStrings.cancel().str()) } },
    )
}
