// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.heyrobot.R
import app.heyrobot.core.logbook.LogEntry
import app.heyrobot.core.logbook.Logbook
import app.heyrobot.core.logbook.LogbookSettings
import app.heyrobot.sys.LogbookFiles
import app.heyrobot.sys.LogbookRead
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.pref.ConfirmDialog
import app.heyrobot.ui.pref.IntroPreference
import app.heyrobot.ui.pref.MainSwitchBar
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Settings › Logbook (docs/plans/spoken-log-plan.md §5, §6, §10): the switch, adding while locked, your entries newest first
 * with Delete and Undo, Export as a table (CSV, through Android's file picker) and Delete all. Inside MainActivity, so
 * covered during a phone lock (LockGate.ROBOT_COVERED). Every read and write off the main thread; entries only unlocked.
 */
@Composable
fun LogbookSettingsScreen(a: AppActions, snackbar: SnackbarHostState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var s by remember { mutableStateOf<LogbookSettings?>(null) }
    var read by remember { mutableStateOf<LogbookRead?>(null) }
    var confirmAll by rememberSaveable { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        var first = true
        owner.lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) { if (!first) tick++; first = false }
    }
    LaunchedEffect(tick) {
        s = withContext(Dispatchers.IO) { LogbookFiles.settings(ctx) }
        read = withContext(Dispatchers.IO) { LogbookFiles.store(ctx).read() }
    }
    val notSaved = stringResource(R.string.logbook_not_saved)
    fun save(change: (LogbookSettings) -> LogbookSettings) {
        val now = s ?: return
        s = change(now)
        scope.launch { if (!withContext(Dispatchers.IO) { LogbookFiles.saveSettings(ctx, change(now)) }) a.say(notSaved); tick++ }
    }
    val exported = stringResource(R.string.logbook_exported)
    val exportFailed = stringResource(R.string.logbook_export_failed)
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch { a.say(if (withContext(Dispatchers.IO) { exportTo(ctx, uri) }) exported else exportFailed) }
    }
    val deletedMsg = stringResource(R.string.logbook_deleted)
    val undo = stringResource(R.string.logbook_undo)
    val zone = ZoneId.systemDefault()

    PreferenceScreen(stringResource(R.string.logbook_title), onBack, snackbar = snackbar) {
        val now = s
        if (now != null) {
            item(key = "main") { MainSwitchBar(stringResource(R.string.logbook_use), now.on) { v -> save { it.copy(on = v) } } }
            item(key = "intro") { IntroPreference(stringResource(R.string.logbook_intro)) }
            item(key = "locked") {
                SwitchPreference(stringResource(R.string.logbook_add_locked), stringResource(R.string.logbook_add_locked_summary), now.addLocked) { v -> save { it.copy(addLocked = v) } }
            }
        }
        when (val r = read) {
            null -> Unit
            is LogbookRead.Ok -> {
                item(key = "export") {
                    PreferenceItem(stringResource(R.string.logbook_export), stringResource(R.string.logbook_export_summary), enabled = r.entries.isNotEmpty(),
                        onClick = { export.launch(EXPORT_NAME) })
                }
                if (r.entries.isEmpty()) item(key = "none") { IntroPreference(stringResource(R.string.logbook_none)) }
                val today = ZonedDateTime.now(zone)
                for (e in r.entries.sortedByDescending { it.at }) item(key = "e-" + e.id) {
                    PreferenceItem(e.what, Logbook.whenOf(e.at, e.added, today).str(), trailing = {
                        TextButton(onClick = {
                            scope.launch {
                                val at = System.currentTimeMillis()
                                val gone = withContext(Dispatchers.IO) { LogbookFiles.store(ctx).delete(setOf(e.id)) }
                                tick++
                                if (gone != null && snackbar.showSnackbar(deletedMsg, actionLabel = undo, withDismissAction = true) == SnackbarResult.ActionPerformed) {
                                    withContext(Dispatchers.IO) { LogbookFiles.store(ctx).restore(gone, at) }
                                    tick++
                                }
                            }
                        }) { Text(stringResource(R.string.logbook_delete)) }
                    })
                }
                if (r.entries.isNotEmpty()) item(key = "all") { PreferenceItem(stringResource(R.string.logbook_delete_all), onClick = { confirmAll = true }) }
            }
            else -> item(key = "unlock") { IntroPreference(stringResource(R.string.logbook_unlock)) }
        }
    }
    if (confirmAll) {
        ConfirmDialog(stringResource(R.string.logbook_delete_all), stringResource(R.string.logbook_delete_all_text), stringResource(R.string.logbook_delete_all),
            onDismiss = { confirmAll = false }) {
            confirmAll = false
            scope.launch { if (!withContext(Dispatchers.IO) { LogbookFiles.store(ctx).deleteAll() }) a.say(notSaved); tick++ }
        }
    }
}

private const val EXPORT_NAME = "logbook.csv" // l10n: not text (a file name)

/** The log as a plain table (when, what, number, unit), written to [uri]; false when it couldn't be read or written. */
private fun exportTo(ctx: Context, uri: Uri): Boolean = try {
    val r = LogbookFiles.store(ctx).read() as? LogbookRead.Ok
    if (r == null) false else {
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(LogbookCsv.of(r.entries, ZoneId.systemDefault()).toByteArray(Charsets.UTF_8)) } != null
    }
} catch (_: Exception) {
    false
}

/** The export's table: a header row, then one row per entry, oldest first; quotes doubled, every field quoted. */
object LogbookCsv {
    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun of(entries: List<LogEntry>, zone: ZoneId): String = buildString {
        append("when,what,number,unit\r\n") // l10n: not text (the table's column names, read by spreadsheets)
        for (e in entries.sortedBy { it.at }) {
            append(listOf(TIME.format(Instant.ofEpochMilli(e.at).atZone(zone)), plain(e.what), e.number?.toPlainString().orEmpty(), plain(e.unit.orEmpty()))
                .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" })
            append("\r\n")
        }
    }

    /** spoken-log-4: words a spreadsheet would run as a formula (= + - @, tab, return first) start with ' instead. */
    private fun plain(s: String): String = if (s.isNotEmpty() && s[0] in "=+-@\t\r") "'" + s else s
}
