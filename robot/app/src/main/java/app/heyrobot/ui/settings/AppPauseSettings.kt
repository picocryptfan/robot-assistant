// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.telecom.TelecomManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import app.heyrobot.R
import app.heyrobot.core.lock.LockLimits
import app.heyrobot.core.pause.NeverPick
import app.heyrobot.core.pause.PauseSettings
import app.heyrobot.lock.AppPauseWatch
import app.heyrobot.lock.LockAccess
import app.heyrobot.ui.Mark
import app.heyrobot.ui.pref.PreferenceBlock
import app.heyrobot.ui.pref.PreferenceScreen
import app.heyrobot.ui.pref.StatusPreference
import app.heyrobot.ui.pref.SwitchPreference
import app.heyrobot.ui.pref.preferenceGroup

// Settings › App pause (docs/plans/app-pause-plan.md): on or off (off by default), the apps to pause, the wait. Its own
// file (lock/AppPauseWatch, files/robot/app_pause.json), not settings.json. The picker lists the launcher's apps (the
// existing <queries>) without the ones that can never be picked (core NeverPick), and saving drops any of those again.

/** Settings' row: off, or on for how many apps. */
@Composable
fun appPauseSummary(ctx: Context): String {
    val s = remember { AppPauseWatch.settings(ctx) }
    return if (!s.on || s.apps.isEmpty()) stringResource(R.string.lock_pause_off) else pluralStringResource(R.plurals.lock_pause_on_apps, s.apps.size, s.apps.size)
}

@Composable
fun AppPauseScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val s = remember(tick) { AppPauseWatch.settings(ctx) }
    val access = remember(tick) { LockAccess.enabled(ctx) }
    val usage = remember(tick) { app.heyrobot.sys.PhoneInfo.usageAccess(ctx) }
    val never = remember { neverPick(ctx) }
    val apps = remember { pickable(ctx, never) }
    fun save(n: PauseSettings) { AppPauseWatch.save(ctx, n.copy(apps = n.apps.filterNot(never::blocks).toSet())); tick++ }
    fun start(i: Intent) = try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
    val waitTitle = stringResource(R.string.lock_pause_wait)
    val appsTitle = stringResource(R.string.lock_pause_apps)
    PreferenceScreen(stringResource(R.string.lock_pause_settings), onBack) {
        preferenceGroup(null, "on") {
            PreferenceBlock { Text(stringResource(R.string.lock_pause_intro), style = MaterialTheme.typography.bodyMedium) }
            SwitchPreference(stringResource(R.string.lock_pause_settings), null, s.on) { save(s.copy(on = it)) }
            if (s.on && !access) StatusPreference(stringResource(R.string.lock_access_a11y), stringResource(R.string.lock_pause_needs_access), Mark.WARN,
                onClick = { start(LockAccess.settings()) })
            if (s.on && !usage) StatusPreference(stringResource(R.string.lock_pause_settings), stringResource(R.string.lock_pause_needs_usage), Mark.TODO,
                onClick = { start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        }
        preferenceGroup(waitTitle, "wait") {
            PreferenceBlock {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (w in PauseSettings.WAITS) FilterChip(selected = s.waitSeconds == w, onClick = { save(s.copy(waitSeconds = w)) },
                        label = { Text(stringResource(R.string.lock_pause_seconds, w)) })
                }
            }
        }
        preferenceGroup(appsTitle, "apps") {
            PreferenceBlock {
                if (apps.isEmpty()) Text(stringResource(R.string.lock_pause_none), style = MaterialTheme.typography.bodyMedium)
                for ((pkg, label) in apps) {
                    val on = pkg in s.apps
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Checkbox, onValueChange = {
                        if (!it) save(s.without(pkg)) else if (s.apps.size < PauseSettings.MAX_APPS) save(s.copy(apps = s.apps + pkg))
                    }).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = on, onCheckedChange = null)
                        Spacer(Modifier.width(16.dp))
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

/** Robot, the Home apps, the default dialer and the fixed list (core NeverPick). */
private fun neverPick(ctx: Context): NeverPick = NeverPick(ctx.packageName, AppPauseWatch.homes(ctx),
    dialer = try { ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage } catch (_: Throwable) { null },
    keyboard = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/') } catch (_: Throwable) { null })

private fun pickable(ctx: Context, never: NeverPick): List<Pair<String, String>> = try {
    val pm = ctx.packageManager
    pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }.distinct()
        .filter { !never.blocks(it) && LockLimits.validPackage(it) }
        .map { it to (try { pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString() } catch (_: Throwable) { it }) }
        .sortedBy { it.second.lowercase() }
} catch (_: Throwable) {
    emptyList()
}
