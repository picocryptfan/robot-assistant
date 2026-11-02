// SPDX-License-Identifier: GPL-3.0-only
package app.heyrobot.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationManager
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import app.heyrobot.ui.common.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.heyrobot.core.car.CarNotice
import app.heyrobot.ui.theme.RobotTokens
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import app.heyrobot.core.car.CarAction
import app.heyrobot.core.car.CarFourth
import app.heyrobot.core.car.CarInfo
import app.heyrobot.core.car.CarLarge
import app.heyrobot.core.car.CarLayout
import app.heyrobot.core.everyday.MusicApp
import app.heyrobot.core.lock.LockLimits
import app.heyrobot.sys.MusicApps
import app.heyrobot.ui.common.RIcons
import app.heyrobot.ui.pref.ListPreference
import app.heyrobot.ui.pref.PreferenceItem
import app.heyrobot.ui.pref.RadioDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MAIN PROCESS. Settings › Car mode › Car screen (docs/plans/car-layout-design.md): the large panel, the information
// panel and the fourth button. Choices live here, not on the car screen. The apps listed are those with a launcher icon, seen
// through the manifest's MAIN/LAUNCHER <queries> entry, and the one you pick is pinned with its signer as your music app is.

/** An action or an app in the fourth button's list. */
internal sealed class FourthChoice {
    data class Action(val action: CarAction) : FourthChoice()
    data class App(val found: LauncherApp) : FourthChoice()
}

internal data class LauncherApp(val pkg: String, val label: String)

internal fun largeLabel(l: CarLarge): Int = when (l) {
    CarLarge.MAP -> R.string.car_panel_map
    CarLarge.MEDIA -> R.string.car_panel_media
    CarLarge.MESSAGES -> R.string.car_panel_messages
}

internal fun infoLabel(i: CarInfo): Int = when (i) {
    CarInfo.MEDIA -> R.string.car_panel_media_buttons
    CarInfo.DIRECTIONS -> R.string.car_panel_directions
    CarInfo.LATEST_MESSAGE -> R.string.car_panel_latest
}

internal fun actionLabel(a: CarAction): Int = when (a) {
    CarAction.MUSIC_APP -> R.string.car_fourth_music
    CarAction.MAPS -> R.string.car_maps
    CarAction.NAVIGATE_HOME -> R.string.car_navigate_home
}

/** A row's icon is the choice it holds, as the car screen shows it. */
private fun largeIcon(l: CarLarge): ImageVector = when (l) {
    CarLarge.MAP -> RIcons.Maps
    CarLarge.MEDIA -> RIcons.Play
    CarLarge.MESSAGES -> RIcons.Message
}

private fun infoIcon(i: CarInfo): ImageVector = when (i) {
    CarInfo.MEDIA -> RIcons.Play
    CarInfo.DIRECTIONS -> RIcons.Maps
    CarInfo.LATEST_MESSAGE -> RIcons.Message
}

private fun fourthIcon(f: CarFourth): ImageVector = when (f) {
    is CarFourth.App -> RIcons.Apps
    is CarFourth.Action -> when (f.action) {
        CarAction.MUSIC_APP -> RIcons.Play
        CarAction.MAPS -> RIcons.Maps
        CarAction.NAVIGATE_HOME -> RIcons.Navigate
    }
}

/** The apps with a launcher icon, by name; never Robot or Android's own resolver. */
internal fun launcherApps(ctx: Context): List<LauncherApp> = try {
    ctx.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }.distinct()
        .filter { it != ctx.packageName && it != "android" && LockLimits.validPackage(it) }
        .map { LauncherApp(it, MusicApps.label(ctx, it)) }
        .sortedBy { it.label.lowercase() }
} catch (_: Exception) { emptyList() }

@Composable
internal fun CarScreenRows(layout: CarLayout, enabled: Boolean, onChange: (CarLayout) -> Unit) {
    val ctx = LocalContext.current
    ListPreference(stringResource(R.string.car_large_title), layout.large, CarLarge.entries, { ctx.getString(largeLabel(it)) }, {
        when (it) {
            CarLarge.MAP -> ctx.getString(R.string.car_panel_map_detail)
            CarLarge.MESSAGES -> ctx.getString(R.string.car_panel_messages_detail)
            CarLarge.MEDIA -> null
        }
    }, largeIcon(layout.large), enabled = enabled) { onChange(layout.copy(large = it)) }
    ListPreference(stringResource(R.string.car_info_title), layout.info, CarInfo.entries, { ctx.getString(infoLabel(it)) }, {
        when (it) {
            CarInfo.DIRECTIONS -> ctx.getString(R.string.car_panel_directions_detail)
            CarInfo.LATEST_MESSAGE -> ctx.getString(R.string.car_panel_messages_detail)
            CarInfo.MEDIA -> null
        }
    }, infoIcon(layout.info), enabled = enabled) { onChange(layout.copy(info = it)) }
    FourthRow(layout.fourth, enabled) { onChange(layout.copy(fourth = it)) }
    CarAppsRow(layout.apps, enabled) { onChange(layout.copy(apps = it)) }
}

/** In :voice, named so this process doesn't load it. */
private const val SPEAKING_SERVICE = "app.heyrobot.assistant.routines.RoutineSpeakingService" // l10n: not text

/** Whether car mode is on now, in :voice: the notice it holds is all this process can see of it. */
internal fun carModeRunning(ctx: Context): Boolean = try {
    ctx.getSystemService(NotificationManager::class.java).activeNotifications.any { CarNotice.running(it.notification.channelId, it.isOngoing) }
} catch (_: Exception) { false }

/** The same way the notice's End went: :voice ends car mode, and the screen closes itself. */
internal fun endCarMode(ctx: Context) {
    try { ctx.startService(Intent().setClassName(ctx, SPEAKING_SERVICE).setAction(CarNotice.ACTION_END)) } catch (_: Exception) {}
}

/** End car mode, the first thing on the page while it's on: the car screen's Settings button lands here. Looked at again each time the page comes back. */
@Composable
internal fun EndCarModeRow(running: (Context) -> Boolean, end: (Context) -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var on by remember { mutableStateOf(running(ctx)) }
    DisposableEffect(owner) {
        val watch = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) on = running(ctx) }
        owner.lifecycle.addObserver(watch)
        onDispose { owner.lifecycle.removeObserver(watch) }
    }
    if (on) PreferenceItem(stringResource(R.string.car_end_mode), null, RIcons.Close, onClick = { end(ctx); on = false })
}

/**
 * The apps kept for the car, at most [CarLayout.MAX_APPS], any with a launcher icon, each pinned with its signer as the fourth
 * button's app is. The car screen's Apps button lists these and no others.
 */
@Composable
private fun CarAppsRow(apps: List<MusicApp>, enabled: Boolean, onChange: (List<MusicApp>) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<LauncherApp>?>(null) }
    val summary = if (apps.isEmpty()) stringResource(R.string.car_apps_summary_none, CarLayout.MAX_APPS) else apps.joinToString(", ") { it.label } // l10n: not text (a list's separator)
    PreferenceItem(stringResource(R.string.car_apps_title), summary, RIcons.Apps, enabled = enabled, onClick = { open = true })
    if (!open) return
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { launcherApps(app) } }
    val list = found ?: return
    val all = list + apps.filter { k -> list.none { it.pkg == k.pkg } }.map { LauncherApp(it.pkg, it.label) }
    AlertDialog(
        onDismissRequest = { open = false; found = null },
        title = { Text(stringResource(R.string.car_apps_title)) },
        confirmButton = { TextButton(onClick = { open = false; found = null }) { Text(stringResource(R.string.car_close)) } },
        text = {
            LazyColumn {
                items(all, key = { it.pkg }) { a ->
                    val kept = apps.any { it.pkg == a.pkg }
                    val can = kept || apps.size < CarLayout.MAX_APPS
                    Row(Modifier.fillMaxWidth().heightIn(min = RobotTokens.minTouch).toggleable(kept, enabled = can, role = Role.Checkbox) { on ->
                        if (!on) onChange(apps.filterNot { it.pkg == a.pkg })
                        else scope.launch {
                            val sha = withContext(Dispatchers.IO) { MusicApps.signer(app, a.pkg) } ?: return@launch
                            if (MusicApp.valid(a.pkg, sha, a.label)) onChange(apps + MusicApp(a.pkg, sha, a.label))
                        }
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(kept, null, enabled = can)
                        Text(a.label, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
    )
}

@Composable
private fun FourthRow(fourth: CarFourth, enabled: Boolean, onPick: (CarFourth) -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<LauncherApp>?>(null) }
    var replaced by remember { mutableStateOf(false) }
    LaunchedEffect(fourth) { replaced = (fourth as? CarFourth.App)?.let { withContext(Dispatchers.IO) { MusicApps.check(app, it.app) } } == MusicApps.PinNow.REPLACED }
    val summary = when (fourth) {
        is CarFourth.Action -> stringResource(actionLabel(fourth.action))
        is CarFourth.App -> if (replaced) stringResource(R.string.car_fourth_replaced, fourth.app.label) else fourth.app.label
    }
    PreferenceItem(stringResource(R.string.car_fourth_title), summary, fourthIcon(fourth), enabled = enabled, onClick = { open = true })
    if (!open) return
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { launcherApps(app) } }
    val list = found ?: return
    val pinned = (fourth as? CarFourth.App)?.app
    val apps = if (pinned != null && list.none { it.pkg == pinned.pkg }) list + LauncherApp(pinned.pkg, pinned.label) else list
    val choices: List<FourthChoice> = CarAction.entries.map { FourthChoice.Action(it) } + apps.map { FourthChoice.App(it) }
    RadioDialog(
        title = stringResource(R.string.car_fourth_title),
        selected = choices.first {
            when (it) {
                is FourthChoice.Action -> fourth == CarFourth.Action(it.action)
                is FourthChoice.App -> pinned?.pkg == it.found.pkg
            }
        },
        entries = choices,
        label = { c -> when (c) { is FourthChoice.Action -> app.getString(actionLabel(c.action)); is FourthChoice.App -> c.found.label } },
        onDismiss = { open = false; found = null },
        onPick = { c ->
            open = false
            found = null
            when (c) {
                is FourthChoice.Action -> onPick(CarFourth.Action(c.action))
                is FourthChoice.App -> scope.launch {
                    val sha = withContext(Dispatchers.IO) { MusicApps.signer(app, c.found.pkg) } ?: return@launch
                    if (MusicApp.valid(c.found.pkg, sha, c.found.label)) onPick(CarFourth.App(MusicApp(c.found.pkg, sha, c.found.label)))
                }
            }
        },
    )
}
