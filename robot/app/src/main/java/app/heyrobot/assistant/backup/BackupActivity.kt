// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.assistant.backup

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.text.format.Formatter
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalAutofillManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import app.heyrobot.R
import app.heyrobot.core.backup.BackupCaps
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupCode
import app.heyrobot.core.backup.BackupWords
import app.heyrobot.core.backup.PassphraseCheck
import app.heyrobot.core.backup.PassphraseLevel
import app.heyrobot.core.backup.PassphraseMaker
import app.heyrobot.core.i18n.Arg
import app.heyrobot.core.i18n.BackupStrings
import app.heyrobot.sys.Assistant
import app.heyrobot.sys.BackupFiles
import app.heyrobot.sys.BackupWindow
import app.heyrobot.sys.SettingsStore
import app.heyrobot.ui.common.NoImeLearning
import app.heyrobot.ui.common.SecureDialog
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.RobotTheme
import app.heyrobot.ui.theme.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

// :VOICE PROCESS (docs/plans/backup-1.1-design.md §6.1, §8, §9.2). Back up now's window: what goes in, the passphrase,
// where to save it (Android's file picker), the progress and the result. Opened by class name from Settings › Back up and
// restore (sys/BackupWindow), not exported, never over the lock screen, FLAG_SECURE for its whole life, its dialogs
// secure too. The passphrase lives only in this window's memory: `remember`, never `rememberSaveable`, never in an
// intent, a file or a log. Its fields keep the keyboard from learning (NoImeLearning) and, by the user's decision of
// 15 Oct, let a password manager fill and save the passphrase (autofill's Password and NewPassword
// content types) instead of keeping it out of autofill as the design first said. The screen going off ends the window.

class BackupActivity : ComponentActivity() {
    private var job: BackupJob? = null
    /** Restore from a backup's session (RestoreFlow): closed with the window unless a restore is being written. */
    internal var restore: RestoreSession? = null
    @Volatile internal var restoring = false
    private val main = Handler(Looper.getMainLooper())

    /** The phone locking (the screen going off) ends the window and any backup in progress. */
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // A restore being written carries on to its end and the restart (each category whole or not at all).
            if (restoring) return
            job?.cancelled = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
        val words = try { assets.open(WORDS).bufferedReader().use { PassphraseMaker.parse(it.readText()) } } catch (_: Exception) { null }
        val restoreMode = intent.getStringExtra(BackupWindow.EXTRA_MODE) == BackupWindow.MODE_RESTORE
        setContent { RobotTheme { if (restoreMode) RestoreFlow(this) else Flow(words) } }
    }

    override fun onDestroy() {
        job?.cancelled = true
        if (!restoring) restore?.close()
        try { unregisterReceiver(screenOff) } catch (_: Exception) {}
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private enum class Step { CHOOSE, PASSPHRASE, WORKING, DONE, FAILED }

    @Composable
    private fun Flow(words: List<String>?) {
        val ctx = LocalContext.current
        val start = remember { BackupFiles.state(ctx) }
        var step by remember { mutableStateOf(if (Assistant.locked(ctx)) Step.FAILED else Step.CHOOSE) }
        var failure by remember { mutableStateOf<BackupCode?>(if (Assistant.locked(ctx)) BackupCode.LOCKED else null) }
        var survey by remember { mutableStateOf<Map<BackupCategory, BackupSources.Summary>?>(null) }
        // Keys and the voiceprint start off every time: each needs its warning answered again.
        var chosen by remember { mutableStateOf(start.chosen - BackupCategory.SENSITIVE) }
        var recordings by remember { mutableStateOf(start.recordings) }
        var pictures by remember { mutableStateOf(start.pictures) }
        var videos by remember { mutableStateOf(start.videos) }
        var passphrase by remember { mutableStateOf("") }
        var progress by remember { mutableStateOf<BackupJob.Progress?>(null) }
        var done by remember { mutableStateOf<BackupJob.Outcome.Done?>(null) }
        var tooBig by remember { mutableStateOf(0L) }
        val scope = lifecycleScope
        LaunchedEffect(Unit) { survey = withContext(Dispatchers.IO) { BackupSources.survey(ctx, SettingsStore.load(ctx)) } }
        DisposableEffect(step) {
            if (step == Step.WORKING) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(MIME)) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (Assistant.locked(ctx)) {
                failure = BackupCode.LOCKED; step = Step.FAILED; return@rememberLauncherForActivityResult
            }
            // What the list shows on goes in; one it can't open now is shown off and left out, your tick kept for next time.
            val j = BackupJob(applicationContext, BackupSources.going(chosen, survey), recordings, pictures, videos, remember = chosen)
            job = j
            step = Step.WORKING
            val pass = passphrase
            scope.launch {
                val outcome = withContext(Dispatchers.IO) { j.run(uri, pass) { p -> main.post { progress = p } } }
                clearClipboard(pass)
                job = null
                when (outcome) {
                    is BackupJob.Outcome.Done -> { done = outcome; passphrase = ""; step = Step.DONE }
                    is BackupJob.Outcome.Failed -> { failure = outcome.code; tooBig = j.tooBig; step = Step.FAILED }
                    BackupJob.Outcome.Cancelled -> { failure = BackupCode.CANCELLED; step = Step.FAILED }
                }
            }
        }
        when (step) {
            Step.CHOOSE -> Choose(survey, chosen, recordings, pictures, videos, onChosen = { chosen = it }, onRecordings = { recordings = it },
                onPictures = { pictures = it }, onVideos = { videos = it }, onNext = { step = Step.PASSPHRASE })
            Step.PASSPHRASE -> Passphrase(words, sensitive = chosen.any { it in BackupCategory.SENSITIVE }, onBack = { step = Step.CHOOSE }) { p ->
                passphrase = p
                picker.launch(getString(R.string.backup_file_name, LocalDate.now().toString()))
            }
            Step.WORKING -> Working(progress) { job?.cancelled = true }
            Step.DONE -> Done(done)
            Step.FAILED -> Failed(failure, tooBig, onAgain = { failure = null; step = Step.CHOOSE })
        }
    }

    @Composable
    private fun failureText(code: BackupCode?, tooBig: Long): String = when (code) {
        BackupCode.LOCKED -> BackupStrings.locked().str()
        BackupCode.MEMORY_UNREADABLE -> BackupStrings.memoryUnreadable().str()
        BackupCode.FILES_UNREADABLE -> BackupStrings.filesUnreadable().str()
        BackupCode.TOO_BIG -> BackupWords.tooBigToSave(tooBig).str()
        BackupCode.WRITE_FAILED -> BackupStrings.cantWrite().str()
        BackupCode.CHECK_FAILED -> BackupStrings.readBackFailed().str()
        BackupCode.CANCELLED -> stringResource(R.string.backup_cancelled)
        null, BackupCode.DONE_CHECKED, BackupCode.FAILED -> stringResource(R.string.backup_failed)
    }

    // --------------------------------------------------------------------------------------------------- what goes in

    @Composable
    private fun Choose(survey: Map<BackupCategory, BackupSources.Summary>?, chosen: Set<BackupCategory>, recordings: Boolean, pictures: Boolean,
                       videos: Boolean, onChosen: (Set<BackupCategory>) -> Unit, onRecordings: (Boolean) -> Unit, onPictures: (Boolean) -> Unit,
                       onVideos: (Boolean) -> Unit, onNext: () -> Unit) {
        val ctx = LocalContext.current
        var ask by remember { mutableStateOf<BackupCategory?>(null) }
        val never = stringResource(R.string.backup_never)
        val whatTitle = stringResource(R.string.backup_choose_title)
        val included = chosen.filter { survey?.get(it)?.let { s -> !s.empty && s.count >= 0 } == true }
        val total = included.sumOf { survey?.get(it)?.bytes ?: 0 } +
            (if (recordings && BackupCategory.TRANSCRIPTS in included) survey?.get(BackupCategory.TRANSCRIPTS)?.extraBytes ?: 0 else 0) +
            (if (videos && BackupCategory.FILES in included) survey?.get(BackupCategory.FILES)?.extraBytes ?: 0 else 0)
        val totalLine = stringResource(R.string.backup_total, size(ctx, total))
        // 1.4: over the 32 GiB a backup holds, refused here, with its size, before anything is written.
        val over = total > BackupCaps.MAX_TOTAL
        val overLine = BackupWords.tooBigToSave(total).str()
        PreferenceScreen(stringResource(R.string.backup_title), onBack = { finish() }, bottomBar = {
            Buttons(stringResource(R.string.backup_next), enabled = survey != null && included.isNotEmpty() && !over, onCancel = { finish() }, onGo = onNext)
        }) {
            preferenceGroup(whatTitle, "what") {
                for (c in BackupCategory.entries) {
                    val sum = survey?.get(c)
                    val unreadable = sum != null && sum.count < 0
                    val empty = sum == null || sum.empty
                    val on = c in chosen && !empty && !unreadable
                    SwitchPreference(BackupWords.category(c)().str(), summary(ctx, c, sum), on, enabled = !empty && !unreadable) { now ->
                        if (now && c in BackupCategory.SENSITIVE) ask = c else onChosen(if (now) chosen + c else chosen - c)
                    }
                    if (c == BackupCategory.HISTORY && on) SwitchPreference(stringResource(R.string.backup_pictures), null, pictures, onCheckedChange = onPictures)
                    if (c == BackupCategory.TRANSCRIPTS && on && (sum?.extra ?: 0) > 0) {
                        val rec = pluralStringResource(R.plurals.backup_n_recordings, sum!!.extra, sum.extra)
                        SwitchPreference(stringResource(R.string.backup_recordings), stringResource(R.string.backup_line, rec, size(ctx, sum.extraBytes)),
                            recordings, onCheckedChange = onRecordings)
                    }
                    if (c == BackupCategory.VOICEPRINT && on) PreferenceItem(stringResource(R.string.backup_vp_warning))
                    if (c == BackupCategory.FILES && on && (sum?.extra ?: 0) > 0) {
                        val n = pluralStringResource(R.plurals.backup_n_videos, sum!!.extra, sum.extra)
                        SwitchPreference(stringResource(R.string.backup_videos), stringResource(R.string.backup_line, n, size(ctx, sum.extraBytes)),
                            videos, onCheckedChange = onVideos)
                    }
                }
            }
            preferenceFooter(if (over) overLine else totalLine, "total")
            preferenceFooter(never, "never")
        }
        ask?.let { c ->
            val keys = c == BackupCategory.KEYS
            AlertDialog(
                onDismissRequest = { ask = null },
                properties = SecureDialog,
                title = { Text(stringResource(if (keys) R.string.backup_keys_title else R.string.backup_vp_title)) },
                text = { Text(stringResource(if (keys) R.string.backup_keys_text else R.string.backup_vp_text)) },
                confirmButton = { TextButton(onClick = { onChosen(chosen + c); ask = null }) { Text(stringResource(if (keys) R.string.backup_keys_yes else R.string.backup_vp_yes)) } },
                dismissButton = { TextButton(onClick = { ask = null }) { Text(stringResource(if (keys) R.string.backup_keys_no else R.string.backup_vp_no)) } },
            )
        }
    }

    /** A category's line on the list: what it holds now and about how big. */
    @Composable
    private fun summary(ctx: Context, c: BackupCategory, s: BackupSources.Summary?): String? {
        if (s == null) return null
        if (s.count < 0) return stringResource(when (c) {
            BackupCategory.FILES -> R.string.backup_files_cant
            // FX3: travel mode keeps History out while it holds Robot Files' answers (BackupSources.historyHidden).
            BackupCategory.HISTORY -> R.string.backup_history_travel
            else -> R.string.backup_memory_cant
        })
        if (s.empty) return stringResource(R.string.backup_empty)
        val what = when (c) {
            BackupCategory.SETTINGS -> stringResource(R.string.backup_sum_settings)
            BackupCategory.PEOPLE -> pluralStringResource(R.plurals.backup_n_people, s.count, s.count)
            BackupCategory.MEMORY -> pluralStringResource(R.plurals.backup_n_items, s.count, s.count).let { items ->
                if (s.extra > 0) stringResource(R.string.backup_two, items, pluralStringResource(R.plurals.backup_n_private, s.extra, s.extra)) else items
            }
            BackupCategory.HANDSFREE -> pluralStringResource(R.plurals.backup_n_devices, s.extra, s.extra)
            BackupCategory.ROUTINES -> pluralStringResource(R.plurals.backup_n_routines, s.extra, s.extra)
            BackupCategory.SKILLS -> pluralStringResource(R.plurals.backup_n_skills, s.extra, s.extra)
            BackupCategory.PHONE_LOCK -> pluralStringResource(R.plurals.backup_n_locks, s.count, s.count)
            BackupCategory.NOTES -> pluralStringResource(R.plurals.backup_n_notes, s.count, s.count)
            BackupCategory.HISTORY -> pluralStringResource(R.plurals.backup_n_conversations, s.count, s.count)
            BackupCategory.TRANSCRIPTS -> pluralStringResource(R.plurals.backup_n_transcripts, s.count, s.count)
            BackupCategory.KEYS -> {
                val names = s.names.joinToString(", ")
                val codes = s.extra
                when {
                    codes > 0 && names.isNotEmpty() -> stringResource(R.string.backup_two, names, pluralStringResource(R.plurals.backup_n_codes, codes, codes))
                    codes > 0 -> pluralStringResource(R.plurals.backup_n_codes, codes, codes)
                    else -> names
                }
            }
            BackupCategory.VOICEPRINT -> stringResource(R.string.backup_sum_voiceprint)
            BackupCategory.FILES -> pluralStringResource(R.plurals.backup_n_files, s.count, s.count)
        }
        return if (s.bytes > 0 && c != BackupCategory.KEYS && c != BackupCategory.VOICEPRINT) stringResource(R.string.backup_line, what, size(ctx, s.bytes)) else what
    }

    private fun size(ctx: Context, bytes: Long): String = Formatter.formatShortFileSize(ctx, bytes)

    // ------------------------------------------------------------------------------------------------------ passphrase

    @Composable
    private fun Passphrase(words: List<String>?, sensitive: Boolean, onBack: () -> Unit, onGo: (String) -> Unit) {
        val ctx = LocalContext.current
        val maker = remember(words) { words?.let { PassphraseMaker(it) } }
        val wordSet = remember(words) { words?.toSet().orEmpty() }
        var own by remember { mutableStateOf(maker == null) }
        var made by remember { mutableStateOf(maker?.make().orEmpty()) }
        var saved by remember { mutableStateOf(false) }
        var again by remember { mutableStateOf("") }
        var mine by remember { mutableStateOf("") }
        var mineAgain by remember { mutableStateOf("") }
        var copied by remember { mutableStateOf(false) }
        val rating = remember(mine) { PassphraseCheck.rate(mine, wordSet) }
        val ownOk = own && PassphraseCheck.allowed(rating, keys = sensitive) && mine == mineAgain
        val madeOk = !own && made.isNotEmpty() && saved && again == made
        val autofill = LocalAutofillManager.current
        PreferenceScreen(stringResource(R.string.backup_pass_title), onBack = onBack, bottomBar = {
            Buttons(stringResource(R.string.backup_choose_place), enabled = ownOk || madeOk, onCancel = onBack) {
                // A password manager offers to save what the fields hold (the 15 Oct decision): the fields are done.
                autofill?.commit()
                onGo(if (own) mine else made)
            }
        }) {
            item(key = "pass") {
                NoImeLearning {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (!own) {
                            Text(stringResource(R.string.backup_pass_generated), style = MaterialTheme.typography.bodyMedium)
                            Text(made.split('-').let { w -> w.take(3).joinToString("-") + "-\n" + w.drop(3).joinToString("-") }, // l10n: not text (the passphrase's own words)
                                style = MaterialTheme.typography.headlineSmall, fontFamily = FontFamily.Monospace)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { made = maker?.make().orEmpty(); again = ""; saved = false; copied = false }) { Text(stringResource(R.string.backup_pass_another)) }
                                OutlinedButton(onClick = { copy(made); copied = true }) { Text(stringResource(R.string.backup_pass_copy)) }
                            }
                            if (copied) Text(stringResource(R.string.backup_pass_copied), style = MaterialTheme.typography.bodySmall)
                            SwitchPreference(stringResource(R.string.backup_pass_saved), null, saved) { saved = it }
                            PassField(again, stringResource(R.string.backup_pass_again), ContentType.NewPassword) { again = it }
                            if (again.isNotEmpty() && again != made) Text(stringResource(R.string.backup_pass_mismatch), color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { own = true }) { Text(stringResource(R.string.backup_pass_own)) }
                        } else {
                            PassField(mine, stringResource(R.string.backup_pass_field), ContentType.NewPassword) { mine = it }
                            if (mine.isNotEmpty()) Text(ratingText(rating.level, sensitive), color = if (PassphraseCheck.allowed(rating, sensitive))
                                MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
                            if (rating.edgeSpace) Text(stringResource(R.string.backup_pass_edge), style = MaterialTheme.typography.bodySmall)
                            PassField(mineAgain, stringResource(R.string.backup_pass_again), ContentType.NewPassword) { mineAgain = it }
                            if (mineAgain.isNotEmpty() && mineAgain != mine) Text(stringResource(R.string.backup_pass_mismatch), color = MaterialTheme.colorScheme.error)
                            Text(stringResource(R.string.backup_pass_safe), style = MaterialTheme.typography.bodySmall)
                            if (maker != null) TextButton(onClick = { own = false }) { Text(stringResource(R.string.backup_pass_robots)) }
                        }
                        Text(stringResource(R.string.backup_pass_lost), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    @Composable
    private fun ratingText(level: PassphraseLevel, sensitive: Boolean): String = stringResource(when (level) {
        PassphraseLevel.TOO_SHORT -> R.string.backup_pass_short
        PassphraseLevel.TOO_EASY -> R.string.backup_pass_easy
        PassphraseLevel.FAIR -> if (sensitive) R.string.backup_pass_fair_keys else R.string.backup_pass_fair
        PassphraseLevel.STRONG -> R.string.backup_pass_strong
    })

    /** A passphrase field: hidden as typed, no suggestions, and offered to a password manager as [type] (the 15 Oct decision). */
    @Composable
    private fun PassField(value: String, label: String, type: ContentType, onChange: (String) -> Unit) {
        OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().semantics { contentType = type })
    }

    /** Copy, marked sensitive; cleared after a minute if the clipboard still holds it (KeystoreSecret.clearClipboard's way). */
    private fun copy(p: String) {
        val cm = getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(getString(R.string.backup_pass_title), p)
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        cm.setPrimaryClip(clip)
        val app = applicationContext
        main.postDelayed({ clearClipboard(app, p) }, CLIP_MS)
    }

    private fun clearClipboard(p: String) = clearClipboard(applicationContext, p)

    // ------------------------------------------------------------------------------------------------- working, done

    @Composable
    private fun Working(p: BackupJob.Progress?, onCancel: () -> Unit) {
        val line = when {
            p == null || p.category == null && !p.checking -> stringResource(R.string.backup_starting)
            p.checking -> stringResource(R.string.backup_checking)
            else -> stringResource(R.string.backup_working, BackupWords.category(p.category!!)().str(), p.done + 1, p.entries)
        }
        PreferenceScreen(stringResource(R.string.backup_title), onBack = null, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = onCancel, enabled = p?.checking != true) { Text(stringResource(R.string.backup_cancel)) }
            }
        }) {
            item(key = "work") {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(line, style = MaterialTheme.typography.bodyLarge)
                    if (p != null && p.entries > 0 && !p.checking) LinearProgressIndicator(progress = { p.done.toFloat() / p.entries }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Composable
    private fun Done(d: BackupJob.Outcome.Done?) {
        val ctx = LocalContext.current
        PreferenceScreen(stringResource(R.string.backup_title), onBack = { finish() }, bottomBar = {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.End) {
                Button(onClick = { finish() }) { Text(stringResource(R.string.backup_close)) }
            }
        }) {
            item(key = "done") {
                Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.backup_done, size(ctx, d?.bytes ?: 0)), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.backup_done_keep), style = MaterialTheme.typography.bodyMedium)
                    for (name in d?.unreadableKeys.orEmpty()) Text(BackupStrings.keyUnreadable("helper" to Arg.Plain(name)).str(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    @Composable
    private fun Failed(code: BackupCode?, tooBig: Long, onAgain: () -> Unit) {
        val locked = Assistant.locked(LocalContext.current)
        PreferenceScreen(stringResource(R.string.backup_title), onBack = { finish() }, bottomBar = {
            Buttons(stringResource(R.string.backup_try_again), enabled = !locked, onCancel = { finish() }, onGo = onAgain)
        }) {
            item(key = "failed") {
                Text(failureText(code, tooBig), Modifier.fillMaxWidth().padding(24.dp), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }

    @Composable
    private fun Buttons(go: String, enabled: Boolean, onCancel: () -> Unit, onGo: () -> Unit) {
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End)) {
            OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.backup_cancel)) }
            Button(onClick = onGo, enabled = enabled) { Text(go) }
        }
    }

    companion object {
        /** The EFF long word list (THIRD_PARTY.md), an app asset. */
        const val WORDS = "eff_large_wordlist.txt"
        const val MIME = "application/octet-stream"
        const val CLIP_MS = 60_000L

        /** Clears the clipboard if it still holds exactly [p]. */
        fun clearClipboard(ctx: Context, p: String) {
            if (p.isEmpty()) return
            try {
                val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return
                val clip = cm.primaryClip ?: return
                if (clip.itemCount > 0 && clip.getItemAt(0).coerceToText(ctx)?.toString() == p) cm.clearPrimaryClip()
            } catch (_: Throwable) {}
        }
    }
}
