// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.messages.MissedSettings
import app.heyrobot.sys.MissedStore
import app.heyrobot.sys.NotificationAccess
import app.heyrobot.ui.AppActions
import app.heyrobot.ui.common.AlertDialog
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.SwitchPreference

// 1.3, Settings › Messages › "What did I miss?" (docs/plans/what-did-i-miss-plan.md): off until you turn it on, and then
// only the apps you pick here, one by one, from those with a launcher icon (Robot itself is never offered). missed.json,
// not settings.json. A dialog in Settings, not a window of its own.

/**
 * The notification access row Settings › Messages shows (fix-what-did-i-miss, finding 2): What did I miss? needs access
 * too, so with Messages off it gets the row ("no access" answers point here), and "still allowed" never invites taking
 * back the access it uses. null: no row.
 */
internal enum class AccessRow { NEEDED, ALLOWED, STILL_ALLOWED, NEEDED_FOR_MISSED;
    companion object {
        fun of(messagesOn: Boolean, missedOn: Boolean, granted: Boolean): AccessRow? = when {
            messagesOn -> if (granted) ALLOWED else NEEDED
            missedOn -> if (granted) null else NEEDED_FOR_MISSED
            granted -> STILL_ALLOWED
            else -> null
        }
    }
}

/** [needsAccess]: show the access row here (Messages is off, so its own isn't shown); [onOn]: the switch as saved. */
@Composable
internal fun ColumnScope.MissedSection(a: AppActions, needsAccess: Boolean, onOn: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(MissedStore.current(ctx)) }
    var picking by rememberSaveable { mutableStateOf(false) }
    fun save(change: (MissedSettings) -> MissedSettings) {
        s = MissedStore.update(ctx, change)
        onOn(s.on)
        if (s.on && s.apps.isNotEmpty()) NotificationAccess.rebind(ctx)
    }
    SwitchPreference(stringResource(R.string.messages_missed_switch), stringResource(R.string.messages_missed_switch_detail), s.on) { on -> save { it.copy(on = on) } }
    if (s.on && needsAccess) app.heyrobot.ui.pref.StatusPreference(stringResource(R.string.messages_access), stringResource(R.string.messages_access_needed),
        app.heyrobot.ui.Mark.TODO, onClick = { a.openPage(app.heyrobot.ui.Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
    PreferenceItem(stringResource(R.string.messages_missed_apps),
        if (s.apps.isEmpty()) stringResource(R.string.messages_missed_apps_none) else stringResource(R.string.messages_missed_apps_some, s.apps.size),
        enabled = s.on, onClick = { picking = true })
    if (s.on) PreferenceItem(stringResource(R.string.messages_missed_types), stringResource(R.string.messages_missed_types_detail), onClick = {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationAccess.component(ctx).flattenToString())
        app.heyrobot.ui.openFirst(ctx, listOf(detail) + NotificationAccess.settings(ctx), a)
    })
    if (picking) MissedAppsDialog(s.apps, R.string.messages_missed_pick_title, R.string.messages_missed_pick_text, onDismiss = { picking = false }) { apps ->
        picking = false
        save { it.copy(apps = apps) }
    }
}

/** fix-what-did-i-miss, finding 8: Done keeps the ticked apps still on the phone; one uninstalled since can't be unticked, so it goes. */
internal object MissedPick {
    fun done(offered: List<String>, ticked: Set<String>): List<String> = offered.filter { it in ticked }
}

/** The apps with a launcher icon, by name, Robot left out; [onDone] with the packages ticked. */
@Composable
private fun MissedAppsDialog(picked: List<String>, title: Int, text: Int, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val all = remember {
        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != ctx.packageName }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
    }
    var apps by remember { mutableStateOf(picked.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                for ((pkg, name) in all) {
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(pkg in apps, role = Role.Checkbox) { on -> apps = if (on) apps + pkg else apps - pkg },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = pkg in apps, onCheckedChange = null)
                        Spacer(Modifier.width(12.dp))
                        Text(name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(MissedPick.done(all.map { it.first }, apps)) }) { Text(stringResource(R.string.messages_missed_pick_done)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.messages_setup_cancel)) } },
    )
}

/**
 * 1.3, Settings › Messages › "Hold notifications" (docs/plans/notify-hold-plan.md): off until you turn it on, and then only
 * the apps you pick here. hold.json, not settings.json. Robot changes no phone setting: the buzz row says how to make a
 * held app silent yourself.
 */
@Composable
internal fun ColumnScope.HoldSection(a: AppActions) {
    val ctx = LocalContext.current
    var s by remember { mutableStateOf(app.heyrobot.sys.HoldStore.current(ctx)) }
    var picking by rememberSaveable { mutableStateOf(false) }
    fun save(change: (app.heyrobot.core.messages.HoldSettings) -> app.heyrobot.core.messages.HoldSettings) {
        s = app.heyrobot.sys.HoldStore.update(ctx, change)
        if (s.on && s.apps.isNotEmpty()) NotificationAccess.rebind(ctx)
    }
    SwitchPreference(stringResource(R.string.messages_hold_switch), stringResource(R.string.messages_hold_switch_detail), s.on) { on -> save { it.copy(on = on) } }
    if (s.on && !NotificationAccess.granted(ctx)) app.heyrobot.ui.pref.StatusPreference(stringResource(R.string.messages_access), stringResource(R.string.messages_access_needed),
        app.heyrobot.ui.Mark.TODO, onClick = { a.openPage(app.heyrobot.ui.Page.Settings(SettingsRoutes.NOTIFICATION_ACCESS)) })
    PreferenceItem(stringResource(R.string.messages_hold_apps),
        if (s.apps.isEmpty()) stringResource(R.string.messages_missed_apps_none) else stringResource(R.string.messages_missed_apps_some, s.apps.size),
        enabled = s.on, onClick = { picking = true })
    if (s.on) PreferenceItem(stringResource(R.string.messages_hold_buzz), stringResource(R.string.messages_hold_buzz_detail), onClick = {
        app.heyrobot.ui.openFirst(ctx, listOf(Intent("android.settings.ALL_APPS_NOTIFICATION_SETTINGS"), Intent(Settings.ACTION_SETTINGS)), a) // l10n: not text (an action)
    })
    if (s.on) PreferenceItem(stringResource(R.string.messages_missed_types), stringResource(R.string.messages_missed_types_detail), onClick = {
        val detail = Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
            .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, NotificationAccess.component(ctx).flattenToString())
        app.heyrobot.ui.openFirst(ctx, listOf(detail) + NotificationAccess.settings(ctx), a)
    })
    if (picking) MissedAppsDialog(s.apps, R.string.messages_hold_apps, R.string.messages_hold_pick_text, onDismiss = { picking = false }) { apps ->
        picking = false
        save { it.copy(apps = apps) }
    }
}
