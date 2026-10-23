// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.RoutineCodec
import app.heyrobot.core.RoutineFile
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupWords
import app.heyrobot.core.backup.CategoryChoice
import app.heyrobot.core.backup.FilesHere
import app.heyrobot.core.backup.OpenProblem
import app.heyrobot.core.backup.PhoneHas
import app.heyrobot.core.backup.PlanSkip
import app.heyrobot.core.backup.RestoreMode
import app.heyrobot.core.backup.RestorePlanner
import app.heyrobot.core.backup.VerifiedBackup
import app.heyrobot.core.i18n.BackupStrings
import app.heyrobot.lock.LockSignals
import app.heyrobot.sys.AppPages
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.RestoreFiles
import app.heyrobot.ui.common.NoImeLearning
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

// :VOICE PROCESS (docs/plans/backup-1.1-design.md §7.1, §9.3, §9.4). Restore from a backup, in the backup window: the
// file (Android's picker), its passphrase (a password manager may fill it, the 15 Oct decision), "Opening…" and
// "Checking the backup…", the preview (counts, never content; Add or Replace per category; "This backup is my own" off;
// Keys and the voiceprint unticked; Phone lock's row asks for this phone's lock code, checked by :lock's code pad only),
// then the restore, a tap only, and Robot's restart. The result shows on Settings › Back up and restore afterwards.

private enum class RStep { PICK, COPY, PASS, OPENING, VERIFY, PREVIEW, WORKING, FAILED }

/** Why a restore stopped, in words on the screen. */
private sealed class Why {
    class Problem(val p: OpenProblem) : Why()
    class NoSpace(val bytes: Long) : Why()
    data object Recording : Why()
    data object Locked : Why()
}

@Composable
internal fun RestoreFlow(host: BackupActivity) {
    val ctx = LocalContext.current
    val session = remember { RestoreSession(ctx.applicationContext).also { host.restore = it } }
    var step by remember { mutableStateOf(RStep.PICK) }
    var why by remember { mutableStateOf<Why?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var pass by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var choices by remember { mutableStateOf<Map<BackupCategory, CategoryChoice>>(emptyMap()) }
    var mine by remember { mutableStateOf(false) }
    var phone by remember { mutableStateOf<PhoneHas?>(null) }
    var kept by remember { mutableIntStateOf(0) }
    var current by remember { mutableStateOf<BackupCategory?>(null) }
    val scope = rememberCoroutineScope()
    fun fail(w: Why) { why = w; step = RStep.FAILED }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { host.finish(); return@rememberLauncherForActivityResult }
        step = RStep.COPY
        scope.launch {
            when (val r = withContext(Dispatchers.IO) { session.copyIn(uri) { p -> host.runOnUiThread { progress = p } } }) {
                null -> step = RStep.PASS
                is RestoreSession.Refused.File -> fail(Why.Problem(r.problem))
                is RestoreSession.Refused.NoSpace -> fail(Why.NoSpace(r.bytes))
                RestoreSession.Refused.Recording -> fail(Why.Recording)
                RestoreSession.Refused.Locked -> fail(Why.Locked)
            }
        }
    }
    // Phone lock's row: :lock's code pad checks this phone's code; only its answer comes back.
    val code = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) choices = choices + (BackupCategory.PHONE_LOCK to (choices[BackupCategory.PHONE_LOCK] ?: CategoryChoice()).copy(codeOk = true))
    }
    // 1.4: a restore of many files outlasts the screen's timeout; the phone locking would stop Robot Files' key.
    DisposableEffect(step) {
        if (step == RStep.WORKING) host.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { host.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(Unit) {
        when {
            Assistant.locked(ctx) -> fail(Why.Locked)
            session.recording() -> fail(Why.Recording)
            else -> picker.launch(arrayOf("*/*"))
        }
    }
    fun open() {
        step = RStep.OPENING
        wrong = false
        val p = pass
        scope.launch {
            if (Assistant.locked(ctx)) return@launch fail(Why.Locked)
            val problem = withContext(Dispatchers.IO) { session.unlock(p) }
            if (problem == OpenProblem.WRONG_PASSPHRASE) { wrong = true; step = RStep.PASS; return@launch }
            if (problem != null) return@launch fail(Why.Problem(problem))
            step = RStep.VERIFY
            progress = 0f
            val total = RestoreFiles.incoming(ctx).length()
            val q = withContext(Dispatchers.IO) { session.verify { b -> if (total > 0) host.runOnUiThread { progress = (b.toFloat() / total).coerceAtMost(1f) } } }
            if (q != null) return@launch fail(Why.Problem(q))
            BackupActivity.clearClipboard(ctx, p)
            pass = ""
            phone = withContext(Dispatchers.IO) { session.phone() }
            kept = withContext(Dispatchers.IO) { session.keptSafer(false) }
            choices = RestorePlanner.defaults(session.verified!!, phone)
            step = RStep.PREVIEW
        }
    }
    when (step) {
        RStep.PICK -> Busy(stringResource(R.string.backup_restore_choosing), null)
        RStep.COPY -> Busy(stringResource(R.string.backup_restore_copying), progress)
        RStep.PASS -> Passphrase(pass, wrong, onChange = { pass = it }, onCancel = { host.finish() }, onOpen = ::open)
        RStep.OPENING -> Busy(stringResource(R.string.backup_restore_opening), null)
        RStep.VERIFY -> Busy(stringResource(R.string.backup_restore_checking), progress)
        RStep.PREVIEW -> Preview(session.verified!!, phone!!, choices, mine, kept,
            onChoice = { c, ch -> choices = choices + (c to ch) },
            onMine = { m -> mine = m; scope.launch { kept = withContext(Dispatchers.IO) { session.keptSafer(m) } } },
            onCode = { code.launch(LockSignals.code(ctx, LockSignals.PURPOSE_RESTORE)) },
            onCancel = { host.finish() },
            onRestore = {
                if (Assistant.locked(ctx)) return@Preview fail(Why.Locked)
                step = RStep.WORKING
                val plan = RestorePlanner.plan(session.verified!!, phone!!, choices, mine)
                // Its own thread, so the screen going off can't stop it half way: it always ends with the restart.
                host.restoring = true
                Thread({
                    try {
                        session.apply(plan) { c -> host.runOnUiThread { current = c } }
                    } finally {
                        session.close()
                        restartRobot(host)
                    }
                }, "robot-restore").start()
            })
        RStep.WORKING -> Busy(current?.let { stringResource(R.string.backup_restore_working, BackupWords.category(it)().str()) } ?: stringResource(R.string.backup_restore_finishing), null)
        RStep.FAILED -> Failed(why, onClose = { host.finish() })
    }
}

/**
 * The restart after a restore: the main process ends (its files are
 * read again at its start, where it writes the settings :voice staged), the app opens again on Settings › Back up and
 * restore, and this process ends, which Android binds again as the assistant. :lock keeps running.
 */
private fun restartRobot(a: Activity) {
    val ctx = a.applicationContext
    try {
        ctx.getSystemService(ActivityManager::class.java)?.runningAppProcesses
            ?.filter { it.processName == ctx.packageName }?.forEach { android.os.Process.killProcess(it.pid) }
    } catch (_: Throwable) {}
    try {
        a.startActivity(Intent().setClassName(ctx.packageName, MAIN).putExtra(AppPages.EXTRA, AppPages.BACKUP)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    } catch (_: Throwable) {}
    android.os.Process.killProcess(android.os.Process.myPid())
}

private const val MAIN = "app.heyrobot.ui.MainActivity"

@Composable
private fun Busy(line: String, progress: Float?) {
    PreferenceScreen(stringResource(R.string.backup_restore_title), onBack = null) {
        item(key = "busy") {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(line, style = MaterialTheme.typography.bodyLarge)
                if (progress != null && progress > 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun Passphrase(value: String, wrong: Boolean, onChange: (String) -> Unit, onCancel: () -> Unit, onOpen: () -> Unit) {
    PreferenceScreen(stringResource(R.string.backup_restore_title), onBack = onCancel, bottomBar = {
        Actions(stringResource(R.string.backup_restore_open), enabled = value.isNotEmpty(), onCancel = onCancel, onGo = onOpen)
    }) {
        item(key = "pass") {
            NoImeLearning {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_restore_pass_intro), style = MaterialTheme.typography.bodyMedium)
                    // A password manager may fill it.
                    OutlinedTextField(value, onChange, label = { Text(stringResource(R.string.backup_pass_field)) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                        isError = wrong,
                        modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Password })
                    if (wrong) Text(BackupStrings.wrongPassphrase().str(), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

/** How many of a category's things the file holds, for the preview's row (counts only, never content). */
private fun count(v: VerifiedBackup, c: BackupCategory): Int? = when (c) {
    BackupCategory.PEOPLE -> v.people?.people?.size
    BackupCategory.MEMORY -> v.memory?.items?.size
    BackupCategory.NOTES -> v.notes.size
    BackupCategory.HISTORY -> v.history.size
    BackupCategory.TRANSCRIPTS -> v.transcripts.size
    BackupCategory.ROUTINES -> (RoutineCodec.decode(v.routines["routines.json"]) as? RoutineFile.Ok)?.routines?.size // l10n: not text (a file name)
    BackupCategory.PHONE_LOCK -> v.phoneLock?.locks?.size
    BackupCategory.SKILLS -> v.skills?.size
    BackupCategory.KEYS -> v.keys.size + (v.memoryCodes?.items?.size ?: 0)
    BackupCategory.FILES -> v.files?.entries?.size
    else -> null
}

@Composable
private fun countText(c: BackupCategory, n: Int): String? = when (c) {
    BackupCategory.PEOPLE -> pluralStringResource(R.plurals.backup_n_people, n, n)
    BackupCategory.MEMORY -> pluralStringResource(R.plurals.backup_n_items, n, n)
    BackupCategory.NOTES -> pluralStringResource(R.plurals.backup_n_notes, n, n)
    BackupCategory.HISTORY -> pluralStringResource(R.plurals.backup_n_conversations, n, n)
    BackupCategory.TRANSCRIPTS -> pluralStringResource(R.plurals.backup_n_transcripts, n, n)
    BackupCategory.ROUTINES -> pluralStringResource(R.plurals.backup_n_routines, n, n)
    BackupCategory.SKILLS -> pluralStringResource(R.plurals.backup_n_skills, n, n)
    BackupCategory.PHONE_LOCK -> pluralStringResource(R.plurals.backup_n_locks, n, n)
    BackupCategory.FILES -> pluralStringResource(R.plurals.backup_n_files, n, n)
    else -> null
}

@Composable
private fun Preview(v: VerifiedBackup, phone: PhoneHas, choices: Map<BackupCategory, CategoryChoice>, mine: Boolean, kept: Int,
                    onChoice: (BackupCategory, CategoryChoice) -> Unit, onMine: (Boolean) -> Unit, onCode: () -> Unit,
                    onCancel: () -> Unit, onRestore: () -> Unit) {
    val plan = RestorePlanner.plan(v, phone, choices, mine)
    val madeBy = v.manifest.robot
    val head = stringResource(R.string.backup_restore_header, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(v.manifest.made)), madeBy)
    val again = stringResource(R.string.backup_restore_again)
    val note = stringResource(R.string.backup_restore_restart_note)
    val keptLine = if (!mine && kept > 0) pluralStringResource(R.plurals.backup_restore_kept, kept, kept) else null
    val byName = v.skipped.filter { it.category == null }.distinctBy { it.name }.map { BackupWords.skipped(it, madeBy).str() }
    val mineTitle = stringResource(R.string.backup_restore_mine)
    val mineDetail = stringResource(R.string.backup_restore_mine_detail)
    val codeTitle = stringResource(R.string.backup_restore_code)
    val codeDetail = stringResource(R.string.backup_restore_code_detail)
    val replaceTitle = stringResource(R.string.backup_restore_replace)
    val rows = BackupCategory.entries.filter { v.has(it) || v.skipped.any { s -> s.category == it } }.map { c ->
        val line = plan.of(c)
        val choice = choices[c] ?: CategoryChoice(restore = false)
        val n = count(v, c)
        val what = n?.let { countText(c, it) }
        val here = phone.counts[c] ?: 0
        val summary = BackupWords.skip(line, madeBy)?.str() ?: when {
            // 1.4: how many of this phone's files go, before your tick (its key gone: they can't be read anyway; off until ticked).
            c == BackupCategory.FILES && phone.files == FilesHere.KEY_GONE && here > 0 -> BackupStrings.filesKeyGone(here.toLong()).str()
            !line.restores -> what
            c == BackupCategory.FILES && line.mode == RestoreMode.REPLACE -> BackupStrings.filesDeleted(here.toLong()).str()
            line.mode == RestoreMode.REPLACE -> stringResource(R.string.backup_restore_mode_replace)
            line.mode == RestoreMode.ADD -> stringResource(R.string.backup_restore_mode_add)
            else -> what
        }
        Row5(c, BackupWords.category(c)().str(), summary, choice, line.restores || line.skip == PlanSkip.NEEDS_CODE,
            canReplace = choice.restore && !phone.empty(c) && c !in setOf(BackupCategory.SETTINGS, BackupCategory.KEYS, BackupCategory.VOICEPRINT) &&
                !(c == BackupCategory.FILES && phone.files != FilesHere.OK),
            needsCode = c == BackupCategory.PHONE_LOCK && phone.lockCode && choice.restore && !choice.codeOk,
            selectable = v.has(c))
    }
    PreferenceScreen(stringResource(R.string.backup_restore_title), onBack = onCancel, bottomBar = {
        Actions(stringResource(R.string.backup_restore_go), enabled = plan.restoring.isNotEmpty(), onCancel = onCancel, onGo = onRestore)
    }) {
        preferenceFooter(head, "head")
        preferenceGroup(null, "what") {
            for (r in rows) {
                SwitchPreference(r.title, r.summary, r.choice.restore, enabled = r.selectable) { on -> onChoice(r.c, r.choice.copy(restore = on)) }
                if (r.canReplace) SwitchPreference(replaceTitle, null, r.choice.replace) { on -> onChoice(r.c, r.choice.copy(replace = on)) }
                if (r.needsCode) PreferenceItem(codeTitle, codeDetail, onClick = onCode)
            }
        }
        preferenceGroup(null, "mine") {
            SwitchPreference(mineTitle, mineDetail, mine, onCheckedChange = onMine)
        }
        keptLine?.let { preferenceFooter(it, "kept") }
        byName.forEachIndexed { i, s -> preferenceFooter(s, "newer$i") } // l10n: not text (a list key)
        preferenceFooter(again, "again")
        preferenceFooter(note, "note")
    }
}

private class Row5(val c: BackupCategory, val title: String, val summary: String?, val choice: CategoryChoice, val on: Boolean,
                   val canReplace: Boolean, val needsCode: Boolean, val selectable: Boolean)

@Composable
private fun Failed(why: Why?, onClose: () -> Unit) {
    val text = when (why) {
        is Why.Problem -> BackupWords.problem(why.p).str()
        is Why.NoSpace -> BackupWords.noSpace(why.bytes).str()
        Why.Recording -> BackupStrings.recording().str()
        Why.Locked, null -> BackupStrings.locked().str()
    }
    PreferenceScreen(stringResource(R.string.backup_restore_title), onBack = onClose, bottomBar = {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
            Button(onClick = onClose) { Text(stringResource(R.string.backup_close)) }
        }
    }) {
        item(key = "failed") { Text(text, Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge) }
    }
}

@Composable
private fun Actions(go: String, enabled: Boolean, onCancel: () -> Unit, onGo: () -> Unit) {
    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
        OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) }
        Button(onClick = onGo, enabled = enabled) { Text(go) }
    }
}
