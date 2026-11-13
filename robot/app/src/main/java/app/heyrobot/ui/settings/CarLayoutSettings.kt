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
import app.heyrobot.sys.CarWidgets
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
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import app.heyrobot.R
import android.app.Activity
import android.content.ComponentName
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import app.heyrobot.core.car.CarWidget
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
    CarInfo.WIDGET -> R.string.car_panel_widget
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
    CarInfo.WIDGET -> RIcons.Apps
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
    InfoRow(layout, enabled, onChange)
    FourthRow(layout.fourth, enabled) { onChange(layout.copy(fourth = it)) }
    CarAppsRow(layout.apps, enabled) { onChange(layout.copy(apps = it)) }
}

/** A widget in the list: its app, by name, and the widget's own name. */
internal data class WidgetChoice(val pkg: String, val cls: String, val appLabel: String, val label: String)

/** A pick waiting on Android's question or the widget's setup screen, kept through a recreation: its widget and id. */
internal val PendingPick = listSaver<Pair<WidgetChoice, Int>?, Any>(
    save = { p -> p?.let { (c, id) -> listOf(c.pkg, c.cls, c.appLabel, c.label, id) } ?: emptyList() },
    restore = { l -> if (l.size != 5) null else WidgetChoice(l[0] as String, l[1] as String, l[2] as String, l[3] as String) to (l[4] as Int) },
)

/** The phone's widgets, by app and then by name. */
internal fun widgetChoices(ctx: Context): List<WidgetChoice> = CarWidgets.providers(ctx).map {
    WidgetChoice(it.provider.packageName, it.provider.className, MusicApps.label(ctx, it.provider.packageName), it.loadLabel(ctx.packageManager).orEmpty())
}.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))

/**
 * The information panel's row. "An app's widget" opens the phone's widgets by app: Android asks once whether Robot may add
 * widgets (its own question, with "Always allow"), and a widget with a setup screen shows it. The widget's app is pinned
 * with its signer; the id of a widget you no longer use is deleted.
 */
@Composable
private fun InfoRow(layout: CarLayout, enabled: Boolean, onChange: (CarLayout) -> Unit) {
    val ctx = LocalContext.current
    val app = ctx.applicationContext
    val scope = rememberCoroutineScope()
    var picking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<WidgetChoice>?>(null) }
    var pending by rememberSaveable(stateSaver = PendingPick) { mutableStateOf<Pair<WidgetChoice, Int>?>(null) }
    // Ids a pick cut short left behind: all but the saved widget's and the one still waiting on its answer.
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { CarWidgets.sweep(app, setOfNotNull(layout.widget?.id, pending?.second)) } }
    var note by remember { mutableStateOf<Int?>(null) }
    var gone by remember { mutableStateOf(false) }
    LaunchedEffect(layout.widget) {
        gone = layout.widget?.let { w -> withContext(Dispatchers.IO) { CarWidgets.bound(app, w) == null || MusicApps.check(app, w.app) != MusicApps.PinNow.OK } } ?: false
    }
    fun drop(id: Int, why: Int?) { pending = null; CarWidgets.delete(app, id); note = why }
    fun done(c: WidgetChoice, id: Int) {
        pending = null
        scope.launch {
            val sha = withContext(Dispatchers.IO) { MusicApps.signer(app, c.pkg) }
            val pin = sha?.let { MusicApp(c.pkg, it, c.appLabel) }
            if (pin == null || !CarWidget.valid(pin, c.cls, id)) return@launch drop(id, R.string.car_widget_failed)
            // Swept meanwhile (the car screen starting while this pick waited): saved, it would only show as gone.
            if (withContext(Dispatchers.IO) { CarWidgets.bound(app, CarWidget(pin, c.cls, id)) } == null) return@launch drop(id, R.string.car_widget_failed)
            layout.widget?.takeIf { it.id != id }?.let { CarWidgets.delete(app, it.id) }
            note = null
            onChange(layout.copy(info = CarInfo.WIDGET, widget = CarWidget(pin, c.cls, id)))
        }
    }
    val setup = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (c, id) = pending ?: return@rememberLauncherForActivityResult
        if (r.resultCode == Activity.RESULT_OK) done(c, id) else drop(id, null)
    }
    fun next(c: WidgetChoice, id: Int) {
        val i = CarWidgets.setup(app, id) ?: return done(c, id)
        try { setup.launch(i) } catch (_: Exception) { drop(id, R.string.car_widget_failed) }
    }
    val bind = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val (c, id) = pending ?: return@rememberLauncherForActivityResult
        if (r.resultCode == Activity.RESULT_OK) next(c, id) else drop(id, null)
    }
    val summary = layout.widget?.takeIf { layout.info == CarInfo.WIDGET }?.let { ctx.getString(R.string.car_widget_of, it.app.label) }
    ListPreference(stringResource(R.string.car_info_title), layout.info, CarInfo.entries, { ctx.getString(infoLabel(it)) }, {
        when (it) {
            CarInfo.DIRECTIONS -> ctx.getString(R.string.car_panel_directions_detail)
            CarInfo.LATEST_MESSAGE -> ctx.getString(R.string.car_panel_messages_detail)
            CarInfo.WIDGET -> ctx.getString(R.string.car_panel_widget_detail)
            CarInfo.MEDIA -> null
        }
    }, infoIcon(layout.info), enabled = enabled, summary = summary) {
        if (it == CarInfo.WIDGET) picking = true
        else {
            layout.widget?.let { w -> CarWidgets.delete(app, w.id) }
            onChange(layout.copy(info = it, widget = null))
        }
    }
    (note ?: R.string.car_widget_gone.takeIf { gone })?.let { Text(stringResource(it), Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
    if (!picking) return
    LaunchedEffect(Unit) { found = withContext(Dispatchers.IO) { widgetChoices(app) } }
    val list = found ?: return
    if (list.isEmpty()) { picking = false; found = null; note = R.string.car_widget_none; return }
    val now = layout.widget
    RadioDialog(
        title = stringResource(R.string.car_widget_pick),
        selected = list.firstOrNull { now != null && it.pkg == now.app.pkg && it.cls == now.provider } ?: list.first(),
        entries = list,
        label = { it.appLabel },
        detail = { it.label },
        onDismiss = { picking = false; found = null },
        onPick = { c ->
            picking = false
            found = null
            val id = CarWidgets.allocate(app)
            if (id == null) note = R.string.car_widget_failed
            else {
                pending = c to id
                val provider = ComponentName(c.pkg, c.cls)
                if (CarWidgets.bindNow(app, id, provider)) next(c, id)
                else try { bind.launch(CarWidgets.ask(id, provider)) } catch (_: Exception) { drop(id, R.string.car_widget_failed) }
            }
        },
    )
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
