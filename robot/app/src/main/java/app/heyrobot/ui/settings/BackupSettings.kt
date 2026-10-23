// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.backup.BackupCategory
import app.heyrobot.core.backup.BackupState
import app.heyrobot.core.backup.BackupWords
import app.heyrobot.core.i18n.UiStrings
import app.heyrobot.sys.BackupFiles
import app.heyrobot.sys.BackupWindow
import app.heyrobot.sys.RestoreApply
import app.heyrobot.sys.RestoreFiles
import app.heyrobot.core.backup.CategoryOutcome
import app.heyrobot.core.backup.CategoryPlan
import app.heyrobot.core.backup.RestoreMode
import androidx.compose.ui.res.pluralStringResource
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.update.BackupReminders
import app.heyrobot.ui.pref.preferenceFooter
import app.heyrobot.ui.pref.preferenceGroup
import app.heyrobot.ui.theme.str

// MAIN PROCESS. Settings › Back up and restore (1.1, docs/plans/backup-1.1-design.md §9.1): Back up now opens the backup
// window (:voice, by class name: this process never reads a key, the voiceprint or the passphrase), and the last backup
// as backup_state.json says (when, what, how big; never where), and the monthly reminder (backup.json, job 4804).
// Restore from a backup opens the same window; after the restart, the last restore's result shows here until closed.

/** Settings' row: "Last backup: 12 Sep 2026" or "never". */
@Composable
fun backupSummaryText(ctx: Context): String {
    val at = remember { BackupFiles.state(ctx).lastAt }
    return if (at == null) stringResource(R.string.backup_row_never) else stringResource(R.string.backup_row_last, day(ctx, at))
}

private fun day(ctx: Context, at: Long): String = DateUtils.formatDateTime(ctx, at, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)

@Composable
fun BackupSettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val state = remember(tick) { BackupFiles.state(ctx) }
    var prefs by remember { mutableStateOf(BackupFiles.prefs(ctx)) }
    // The window reports nothing back but the file it writes; the page reads backup_state.json again on its return.
    val window = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val footer = stringResource(R.string.backup_footer)
    var result by remember { mutableStateOf(RestoreFiles.result(ctx)) }
    val resultTitle = result?.let { stringResource(R.string.backup_restore_result_title, day(ctx, it.at)) }
    PreferenceScreen(UiStrings.backup().str(), onBack) {
        preferenceGroup(null, "backup") {
            PreferenceItem(stringResource(R.string.backup_now), stringResource(R.string.backup_now_detail), onClick = { window.launch(BackupWindow.backup(ctx)) })
            // §7: in the backup window too (:voice); it ends with Robot's restart, after which this page shows the result.
            PreferenceItem(stringResource(R.string.backup_restore_row), stringResource(R.string.backup_restore_row_detail), onClick = { window.launch(BackupWindow.restore(ctx)) })
            PreferenceItem(stringResource(R.string.backup_last), lastBackup(ctx, state))
            // §9.5: off; a notice when the last backup is a month old.
            SwitchPreference(stringResource(R.string.backup_remind), stringResource(R.string.backup_remind_detail), prefs.remind) { on ->
                Thread({ BackupReminders.set(ctx.applicationContext, on) }, "robot-backup-remind").start()
                prefs = prefs.copy(remind = on)
            }
        }
        result?.let { r ->
            preferenceGroup(resultTitle, "restored") {
                for ((c, o) in r.outcomes) PreferenceItem(outcomeLine(c, o, r.madeBy))
                if (!r.mine && r.outcomes.isNotEmpty()) PreferenceItem(stringResource(R.string.backup_restore_result_others))
                if (!r.mine && r.keptSafer > 0) PreferenceItem(pluralStringResource(R.plurals.backup_restore_kept, r.keptSafer, r.keptSafer))
                PreferenceItem(stringResource(R.string.backup_restore_again))
                PreferenceItem(stringResource(R.string.backup_restore_result_close), onClick = { RestoreApply.dismiss(ctx); result = null })
            }
        }
        preferenceFooter(footer)
    }
}

/** One category of the last restore: restored, left out (why), or not restored (nothing of it changed). */
@Composable
private fun outcomeLine(c: BackupCategory, o: CategoryOutcome, madeBy: String): String {
    val what = when (o) {
        CategoryOutcome.Done -> stringResource(R.string.backup_restore_result_done)
        CategoryOutcome.Failed -> stringResource(R.string.backup_restore_result_failed)
        CategoryOutcome.Locked -> stringResource(R.string.backup_restore_result_locked)
        CategoryOutcome.AfterUnlock -> stringResource(R.string.backup_restore_result_unlock)
        CategoryOutcome.WaitsForRoom -> stringResource(R.string.backup_restore_result_room)
        CategoryOutcome.EarlierWaits -> stringResource(R.string.backup_restore_result_earlier)
        is CategoryOutcome.Skipped -> BackupWords.skip(CategoryPlan(c, RestoreMode.FRESH, o.why, 0), madeBy)?.str() ?: return BackupWords.category(c)().str()
    }
    return stringResource(R.string.backup_restore_result_line, BackupWords.category(c)().str(), what)
}

@Composable
private fun lastBackup(ctx: Context, s: BackupState): String {
    val at = s.lastAt ?: return stringResource(R.string.backup_last_none)
    val what = BackupCategory.entries.filter { it in s.lastCategories }.map { BackupWords.category(it)().str() }.joinToString(", ")
    return stringResource(R.string.backup_last_detail, day(ctx, at), what, Formatter.formatShortFileSize(ctx, s.lastBytes))
}
